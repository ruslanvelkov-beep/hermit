package hermit.context;

import hermit.api.Action;
import hermit.model.escalation.EscalationLadder;
import hermit.runtime.ToolExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One shell read printed 31,572 characters and the context keeps a few thousand of one tool output, so most of it would
 * have been cut. Splitting the read is the default; when an output that already ran does not fit, it is handed to the
 * hosted ladder for an extract instead of being cut: the hosted model gets the whole output and the request, and only
 * its compact extract (declarations and asked-for lines quoted, the rest summarised) enters the local context, marked
 * as an extract.
 *
 * <p>Off unless {@code -Dhermit.handoff.enabled=true}, and only for outputs whose every path lies under a root named in
 * {@code -Dhermit.handoff.roots} (comma-separated): material outside those roots is never sent.
 */
public final class ReadHandoff {

    /** One hand-off: the result key, the model that extracted, the output's size and the extract's size. */
    public record Note(String key, String model, int outputChars, int extractChars, Path fullOutput) {}

    private ReadHandoff() {}

    public static boolean enabled() {
        return Boolean.getBoolean("hermit.handoff.enabled") && !roots().isEmpty();
    }

    /** The roots under which outputs may be handed off (normalized, absolute); empty means never. */
    public static List<Path> roots() {
        List<Path> out = new ArrayList<>();
        for (String r : System.getProperty("hermit.handoff.roots", "").split(",")) {
            if (!r.isBlank()) {
                out.add(Path.of(r.strip()).toAbsolutePath().normalize());
            }
        }
        return out;
    }

    /** The default asker: the cheapest rung of the escalation ladder for a BigBrother job. */
    public static Function<String, EscalationLadder.Reply> ladderAsker(int maxTokens) {
        return prompt -> {
            EscalationLadder ladder = EscalationLadder.shared();
            return ladder.enabled() ? ladder.ask(EscalationLadder.Job.BIGBROTHER, 0, 0, prompt, maxTokens) : null;
        };
    }

    /**
     * The results with every eligible oversized Bash output replaced by an extract. Results that are not eligible,
     * or whose hand-off fails, stay as they are (the context cuts them as before). {@code notes} receives one entry per
     * hand-off; {@code saveDir}, when not null, keeps each full output in a file.
     */
    public static Map<String, Object> apply(List<Action> actions, Map<String, Object> results, String request, int cap,
            List<Path> roots, Path defaultDir, Function<String, EscalationLadder.Reply> ask, Path saveDir,
            List<Note> notes) {
        if (actions == null || results == null || roots.isEmpty()) {
            return results;
        }
        Map<String, Object> out = new LinkedHashMap<>(results);
        Map<String, Integer> seen = new HashMap<>();
        for (Action a : actions) {
            String tool = ToolExecutor.baseToolName(a.toolName());
            int n = seen.merge(tool, 1, Integer::sum);
            String key = n == 1 ? tool : tool + "#" + n;
            if (!"Bash".equals(tool) || !(out.get(key) instanceof Map<?, ?> r)) {
                continue;
            }
            Object output = r.get("output");
            Object command = a.parameters() == null ? null : a.parameters().get("command");
            if (output == null || command == null || String.valueOf(output).length() <= cap) {
                continue;
            }
            Object wd = a.parameters().get("workingDir");
            Path cwd = wd != null && !String.valueOf(wd).isBlank() ? Path.of(String.valueOf(wd)) : defaultDir;
            if (!pathsUnder(String.valueOf(command), cwd, roots)) {
                continue;
            }
            String full = String.valueOf(output);
            EscalationLadder.Reply reply;
            try {
                reply = ask.apply(prompt(request, String.valueOf(command), full, cap));
            } catch (RuntimeException e) {
                continue;   // no extract: the output is cut as before
            }
            if (reply == null || reply.text() == null || reply.text().isBlank()) {
                continue;
            }
            String extract = reply.text().strip();
            String header = "[EXTRACT by " + reply.model() + " of a " + full.length() + "-character output that does not "
                    + "fit in this context: quoted lines are verbatim, the rest is summarised; read a narrower range for "
                    + "anything it leaves out]\n";
            int room = Math.max(500, cap - header.length());
            if (extract.length() > room) {
                extract = extract.substring(0, room);
            }
            Path saved = null;
            if (saveDir != null) {
                try {
                    Files.createDirectories(saveDir);
                    saved = Files.createTempFile(saveDir, "handoff-" + key.replace('#', '-') + "-", ".txt");
                    Files.writeString(saved, "COMMAND:\n" + command + "\n\nOUTPUT:\n" + full + "\n\nEXTRACT ("
                            + reply.model() + "):\n" + extract + "\n");
                } catch (Exception e) {
                    saved = null;   // the extract still stands
                }
            }
            Map<String, Object> replaced = new LinkedHashMap<>();
            r.forEach((k, v) -> replaced.put(String.valueOf(k), v));
            replaced.put("output", header + extract);
            out.put(key, replaced);
            notes.add(new Note(key, reply.model(), full.length(), extract.length(), saved));
        }
        return out;
    }

    /** The extraction prompt: the agent's task, the command and the whole output. */
    static String prompt(String request, String command, String output, int cap) {
        String task = request == null ? "" : request.length() > 4000 ? request.substring(0, 4000) + " …" : request;
        String body = output.length() > 200_000 ? output.substring(0, 200_000) + "\n…(cut at 200,000 characters)" : output;
        return "A coding agent ran a read whose output is too long for its context. Write the part of it the agent needs, "
                + "in at most " + (cap - 600) + " characters.\n"
                + "Rules: quote declarations, signatures and any lines the task names VERBATIM, each with its file and "
                + "line number when the output shows them; summarise everything else in one short line per part; never "
                + "add a name, a signature or a line that the output does not contain; end with one line naming what "
                + "you left out.\n\nTHE AGENT'S TASK:\n" + task + "\n\nTHE COMMAND:\n" + command
                + "\n\nTHE OUTPUT (" + output.length() + " characters):\n" + body;
    }

    private static final Pattern ASSIGNMENT = Pattern.compile("(?:^|[;&|\\s])([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|'[^']*'|[^\\s;&|]+)");
    private static final Pattern LOOP_VARIABLE = Pattern.compile("\\bfor\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+in\\b");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)\\}?");
    private static final Pattern WORD = Pattern.compile("\"[^\"]*\"|'[^']*'|[^\\s;&|()<>]+");

    /**
     * True when every path-like word of the command (after its own simple variable assignments are substituted) and
     * the working directory lie under one of the roots. A variable that is not assigned in the command, a {@code cd}
     * outside the roots, or {@code ~} makes it false: when in doubt, nothing is sent.
     */
    static boolean pathsUnder(String command, Path cwd, List<Path> roots) {
        if (cwd == null || !under(cwd, roots)) {
            return false;
        }
        Map<String, String> vars = new HashMap<>();
        Matcher assign = ASSIGNMENT.matcher(command);
        while (assign.find()) {
            vars.put(assign.group(1), unquote(assign.group(2)));
        }
        Matcher loop = LOOP_VARIABLE.matcher(command);
        while (loop.find()) {
            vars.putIfAbsent(loop.group(1), "_");   // its values are words of the command, checked as paths themselves
        }
        String expanded = command;
        for (int pass = 0; pass < 3; pass++) {
            Matcher v = VARIABLE.matcher(expanded);
            StringBuilder sb = new StringBuilder();
            while (v.find()) {
                String value = vars.get(v.group(1));
                v.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : "\u0000UNSET\u0000"));
            }
            v.appendTail(sb);
            expanded = sb.toString();
        }
        Path dir = cwd;
        Matcher w = WORD.matcher(expanded);
        String previous = "";
        while (w.find()) {
            String word = unquote(w.group());
            if (word.contains("\u0000UNSET\u0000") && (word.contains("/") || previous.equals("cd"))) {
                return false;
            }
            if (word.startsWith("~")) {
                return false;
            }
            boolean sedExpression = word.startsWith("s/") || word.contains(",/") || word.matches("(?s)^/.*/p$")
                    || word.matches("^\\d+,\\d+p$");
            boolean pathLike = word.contains("/") && !word.startsWith("-") && !sedExpression;
            if (previous.equals("cd")) {
                dir = dir.resolve(word).normalize();
                if (!under(dir, roots)) {
                    return false;
                }
            } else if (pathLike) {
                String clean = word.replaceAll("^[A-Za-z_][A-Za-z0-9_]*=", "");
                Path p = (clean.startsWith("/") ? Path.of(clean) : dir.resolve(clean)).normalize();
                if (!under(p, roots)) {
                    return false;
                }
            }
            previous = word;
        }
        return true;
    }

    private static boolean under(Path p, List<Path> roots) {
        Path abs = p.toAbsolutePath().normalize();
        for (Path r : roots) {
            if (abs.startsWith(r)) {
                return true;
            }
        }
        return false;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
