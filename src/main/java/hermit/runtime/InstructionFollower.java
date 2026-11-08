package hermit.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.api.Action;
import hermit.model.HelperEndpoint;
import hermit.tool.api.ToolRegistry;
import hermit.tool.consult.ConsultTool;
import hermit.tool.helper.HelperModelTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The instruction follower: a local model, GPT-OSS-20B on a local server ({@code -Dhermit.follower.model},
 * served at {@code -Dhermit.follower.baseUrl}, LM Studio by default), carries out the plans the hosted planners write, one
 * instruction (a plan action) at a time. It takes no part in planning or in the critic panels, and it never argues: an
 * instruction runs exactly as written unless it holds more than one step, and then the follower splits it into a micro
 * plan, with its reason, whose steps run in order and stop at the first that fails.
 *
 * <ul>
 *   <li>Only a Bash command that changes something and a TreeCodegen task are put to the model. Everything else runs as
 *       given: reads, and TreeEdit, whose items already apply all or none and name the item that failed.</li>
 *   <li>A Bash command that changes the shell's state (cd, export, source), holds a here-document, or chains with
 *       anything but {@code &&} is never split: each step runs in a new shell, and {@code ||} and {@code ;} do not
 *       stop at a failure.</li>
 *   <li>The micro plan must keep the instruction. A Bash split holds every {@code &&}-joined command of the instruction,
 *       character for character and in order, and adds read-only checks at most (the file exists before text is
 *       appended to it); its steps keep the instruction's other parameters. A TreeCodegen split keeps every parameter
 *       but the task and the method, and only its last step runs the instruction's test. A plan that breaks this, an
 *       unreadable reply or no reply runs the instruction as given.</li>
 *   <li>A split instruction's result is its last step's, with the instruction's status and a message that names the
 *       micro plan, its reason and the step that failed, so the planners can replace a wrong instruction or correct the
 *       plan from what the attempt did. An instruction run as given returns the tool's own result.</li>
 * </ul>
 * Every instruction is appended to {@code -Dhermit.follower.trace} (JSONL) with what the follower did.
 *
 * <p>The helper: when an instruction fails, DeepSeek Flash writes the corrective plan in half the
 * cases ({@code -Dhermit.follower.helpChance}), the step planner in the others; the helper plans nothing else and sits on
 * no panel (see the agent loop). A helper that is out of funds is set aside at once and stays aside, in later
 * runs too, until the marker file {@code -Dhermit.follower.helperOff} is removed; a helper that times out or fails
 * otherwise leaves that one plan to the step planner and is asked again next time.
 */
public final class InstructionFollower {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<String, String> model;   // the prompt to the model's reply; null when it did not answer
    private final Function<String, String> docs;    // a tool's documentation by its name
    private final Path trace;

    public InstructionFollower(Function<String, String> model, Function<String, String> docs, Path trace) {
        this.model = model;
        this.docs = docs;
        this.trace = trace;
    }

    /** The run's follower from {@code -Dhermit.follower.*}; null when no model is named. */
    public static InstructionFollower fromSystemProperties(ToolRegistry tools) {
        HelperEndpoint config = HelperEndpoint.named("follower", "the instruction follower",
                "http://127.0.0.1:1234/v1", null);
        if (config == null || config.model() == null || config.model().isBlank()) {
            return null;
        }
        HelperModelTool remote = new HelperModelTool(config);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("temperature", Double.parseDouble(System.getProperty("hermit.follower.temperature", "0.2")));
        extra.put("reasoning_effort", System.getProperty("hermit.follower.reasoningEffort", "low"));
        String tracePath = System.getProperty("hermit.follower.trace");
        return new InstructionFollower(prompt -> remote.complete(prompt, Integer.getInteger("hermit.follower.maxTokens", 4000), extra),
                name -> tools.getTool(name) == null ? "" : tools.getTool(name).getDocumentation(),
                tracePath == null || tracePath.isBlank() ? null : Path.of(tracePath));
    }

    private static volatile String helperOff;   // why the helper was set aside this run

    /** The chance that DeepSeek Flash, not the step planner, corrects a plan whose instruction failed. */
    public static double helpChance() {
        return Double.parseDouble(System.getProperty("hermit.follower.helpChance", "0.5"));
    }

    /** Why the helper is set aside (this run, or by the marker file of an earlier one); null when it may be asked. */
    public static String helperOff() {
        if (helperOff != null) {
            return helperOff;
        }
        String marker = System.getProperty("hermit.follower.helperOff");
        if (marker != null && !marker.isBlank() && Files.isRegularFile(Path.of(marker))) {
            try {
                return "set aside by " + marker + ": " + Files.readString(Path.of(marker)).strip();
            } catch (IOException e) {
                return "set aside by " + marker;
            }
        }
        return null;
    }

    /** Sets the helper aside for this run and, through the marker file, for later runs until the user removes it. */
    public static void setHelperOff(String why) {
        helperOff = why;
        String marker = System.getProperty("hermit.follower.helperOff");
        if (marker != null && !marker.isBlank()) {
            try {
                Files.writeString(Path.of(marker), Instant.now() + " " + why + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[FOLLOWER] marker not written: " + e.getMessage());
            }
        }
    }

    /** Whether a helper's failure ("model: HTTP 402: …") is a refusal for want of funds or credits. */
    public static boolean outOfFunds(String failure) {
        return failure != null && (failure.contains("HTTP 402") || ConsultTool.OUT_OF_FUNDS.matcher(failure).matches());
    }

    /** Tests: a new run. */
    static void resetHelper() {
        helperOff = null;
    }

    /**
     * The first instruction of an executed plan that failed, as "key: message"; null when none did. A test that ran and
     * failed is an outcome for the planners to judge, not a failure to carry out the instruction.
     */
    public static String failure(Map<String, Object> results) {
        for (Map.Entry<String, Object> e : results.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> r && !"Success".equals(String.valueOf(r.get("status")))
                    && r.get("failedTests") == null && r.get("testOutcome") == null) {
                Object detail = r.get("message") != null ? r.get("message") : r.get("output");
                return e.getKey() + ": " + excerpt(String.valueOf(detail), 600);
            }
        }
        return null;
    }

    /** One step of a micro plan: the action it runs and what it does. */
    record Step(Action action, String why) {}

    /**
     * Carries out one instruction. {@code run} runs an action as the executor runs any: the instruction itself when it
     * runs as given, else each step of the micro plan.
     */
    public Map<String, Object> follow(Action instruction, Function<Action, Map<String, Object>> run) {
        String tool = instruction.toolName();
        String unasked = unasked(instruction);
        if (unasked != null) {
            trace(instruction, "as given: " + unasked, null, null, null, 0);
            return run.apply(instruction);
        }
        long started = System.nanoTime();
        String reply = model.apply(prompt(instruction));
        double seconds = Math.round((System.nanoTime() - started) / 1e8) / 10.0;
        String why = null;
        List<Step> steps;
        String refusal;
        try {
            JsonNode root = reply == null ? null : MAPPER.readTree(jsonObject(reply));
            if (root == null || !root.isObject()) {
                throw new IOException("no JSON object");
            }
            why = root.path("why").asText("");
            steps = root.path("split").asBoolean(false) ? steps(instruction, root.path("steps")) : List.of();
            refusal = steps.isEmpty() ? null : "Bash".equals(tool) ? keepsBash(instruction, steps) : keepsTreeCodegen(steps);
        } catch (IOException | RuntimeException e) {
            trace(instruction, "as given: no readable reply (" + (reply == null ? "no answer" : excerpt(reply, 120)) + ")",
                    null, null, null, seconds);
            return run.apply(instruction);
        }
        if (steps.size() < 2 || refusal != null) {
            trace(instruction, refusal != null ? "as given: the plan " + refusal
                    : steps.isEmpty() ? "as given: the model kept it whole" : "as given: one step", why, steps, null, seconds);
            return run.apply(instruction);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Step step : steps) {
            Map<String, Object> result = run.apply(step.action());
            results.add(result);
            if (!"Success".equals(String.valueOf(result.get("status")))) {
                break;
            }
        }
        trace(instruction, "split", why, steps, results, seconds);
        return combined(instruction, why, steps, results);
    }

    /** Why an instruction runs as given without the model, or null when the model decides. */
    static String unasked(Action instruction) {
        Map<String, Object> p = instruction.parameters() == null ? Map.of() : instruction.parameters();
        switch (String.valueOf(instruction.toolName())) {
            case "Bash" -> {
                String command = String.valueOf(p.get("command") == null ? "" : p.get("command"));
                Chain chain = Chain.of(command);
                if (chain.blocker() != null) {
                    return chain.blocker();
                }
                if (chain.commands().stream().allMatch(InstructionFollower::readOnly)) {
                    return "it only reads";
                }
                return chain.commands().stream().anyMatch(InstructionFollower::changesTheShell)
                        ? "it changes the shell's state, which a step in a new shell would lose" : null;
            }
            case "TreeCodegen" -> {
                return p.get("items") instanceof List<?> items && !items.isEmpty() ? "its edits are planned already" : null;
            }
            default -> {
                return instruction.toolName() + " runs as one step";
            }
        }
    }

    private String prompt(Action instruction) {
        String json;
        try {
            json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("tool", instruction.toolName(), "parameters", instruction.parameters()));
        } catch (IOException e) {
            json = instruction.toString();
        }
        boolean bash = "Bash".equals(instruction.toolName());
        return "You are the instruction follower. Hosted planners wrote a plan; you carry out ONE instruction of it. You do "
                + "not plan the task, judge the plan or argue: the instruction is correct as given, and you do exactly what it "
                + "says, nothing more and nothing less.\n\n"
                + "Split the instruction only when it holds more than one step, so that each step is one command or one "
                + "generation task whose result can be seen on its own. The steps run in order and stop at the first that fails.\n"
                + (bash
                ? "Rules for this Bash instruction: each step is one of the instruction's &&-joined commands, or several of them "
                + "still joined by &&, copied character for character and in the same order. Do not change, drop or add a "
                + "command, except read-only checks the instruction implies (for example `test -f <file>` before appending "
                + "to the file, or `test -d <dir>` before writing into it). Every step runs in a new shell in the "
                + "instruction's working directory. A step's \"parameters\" hold only \"command\".\n"
                : "Rules for this TreeCodegen instruction: each step is one part of the task, in the task's own words, with "
                + "the method it generates; the parts together are the whole task, in order. A step's \"parameters\" hold "
                + "only \"task\" and \"method\"; the instruction's test runs after the last step.\n")
                + "Answer with one JSON object and nothing else, either\n{\"split\": false}\nor\n"
                + "{\"split\": true, \"why\": \"<why the instruction is several steps>\", \"steps\": [{\"tool\": \""
                + instruction.toolName() + "\", \"parameters\": {...}, \"why\": \"<what this step does>\"}]}\n\n"
                + "TOOL DOCUMENTATION:\n" + docs.apply(instruction.toolName()) + "\n\nINSTRUCTION:\n" + json + "\n";
    }

    /** The micro plan's steps: each on the instruction's own tool and parameters, with the step's own few in place. */
    private static List<Step> steps(Action instruction, JsonNode stepsNode) {
        List<Step> steps = new ArrayList<>();
        boolean bash = "Bash".equals(instruction.toolName());
        for (int i = 0; i < stepsNode.size(); i++) {
            JsonNode s = stepsNode.get(i);
            JsonNode p = s.path("parameters");
            String toolName = s.path("tool").asText(instruction.toolName());
            Map<String, Object> params = new LinkedHashMap<>(instruction.parameters());
            if (bash) {
                params.put("command", p.path("command").asText(""));
            } else {
                params.put("task", p.path("task").asText(""));
                if (p.hasNonNull("method")) {
                    params.put("method", p.path("method").asText());
                }
                if (i < stepsNode.size() - 1) {
                    for (String test : List.of("testFilter", "expectTestOutcome", "emulateSpec", "targetReach")) {
                        params.remove(test);
                    }
                }
            }
            steps.add(new Step(new Action(toolName, params), s.path("why").asText("")));
        }
        return steps;
    }

    /**
     * Null when the steps keep the Bash instruction: its {@code &&}-joined commands in order, with read-only checks
     * between them at most; else what they changed. A step is matched to the instruction's commands across the escapes
     * and spacing a model's JSON may change (a printf's {@code \n} written as a line break), and then carries the
     * instruction's own text, so what runs is what was approved.
     */
    static String keepsBash(Action instruction, List<Step> steps) {
        List<String> given = Chain.of(String.valueOf(instruction.parameters().get("command"))).commands();
        int next = 0;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (!"Bash".equals(step.action().toolName())) {
                return "turns the Bash instruction into " + step.action().toolName() + " (step " + (i + 1) + ")";
            }
            String command = String.valueOf(step.action().parameters().get("command"));
            Chain chain = Chain.of(command);
            List<String> commands = chain.commands();
            if (chain.blocker() == null && next + commands.size() <= given.size()
                    && given.subList(next, next + commands.size()).stream().map(InstructionFollower::loose).toList()
                    .equals(commands.stream().map(InstructionFollower::loose).toList())) {
                Map<String, Object> params = new LinkedHashMap<>(step.action().parameters());
                params.put("command", String.join(" && ", given.subList(next, next + commands.size())));
                steps.set(i, new Step(new Action("Bash", params), step.why()));
                next += commands.size();
            } else if (chain.blocker() != null || !commands.stream().allMatch(InstructionFollower::readOnly)) {
                return "does not keep the instruction (step " + (i + 1) + " `" + excerpt(command, 80)
                        + "` is not the instruction's next command or a read-only check)";
            }
        }
        return next == given.size() ? null : "leaves out `" + excerpt(given.get(next), 80) + "`";
    }

    /** A command as compared with a step: line breaks and tabs as their escapes, runs of spaces as one. */
    static String loose(String command) {
        return command.replace("\r\n", "\\n").replace("\n", "\\n").replace("\t", "\\t").replaceAll(" +", " ").strip();
    }

    /** Null when the steps are TreeCodegen parts with a task each; else what is wrong with them. */
    static String keepsTreeCodegen(List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) {
            Action a = steps.get(i).action();
            if (!"TreeCodegen".equals(a.toolName()) || String.valueOf(a.parameters().get("task")).isBlank()) {
                return "has a step that is no TreeCodegen part with a task (step " + (i + 1) + ")";
            }
        }
        return null;
    }

    /** The instruction's result after its micro plan ran: the last step's result, with the instruction's own status. */
    private static Map<String, Object> combined(Action instruction, String why, List<Step> steps,
                                                List<Map<String, Object>> results) {
        Map<String, Object> last = results.get(results.size() - 1);
        Map<String, Object> out = new LinkedHashMap<>(last);
        boolean ok = "Success".equals(String.valueOf(last.get("status"))) && results.size() == steps.size();
        int n = steps.size();
        String plan = "The instruction follower split this instruction into " + n + " steps (" + why + ")";
        if (ok) {
            out.put("message", "The instruction follower ran this instruction as " + n + " steps (" + why + "); all succeeded.");
        } else {
            int failed = results.size();
            Object detail = last.get("message") != null ? last.get("message") : last.get("output");
            out.put("message", plan + "; step " + failed + " of " + n + " failed: `" + summary(steps.get(failed - 1).action())
                    + "`: " + excerpt(String.valueOf(detail), 300) + (failed < n ? ". The steps after it did not run." : ".")
                    + " If the instruction itself is wrong, replace it; if it is right, correct the plan from this result.");
        }
        if ("Bash".equals(instruction.toolName())) {
            String command = String.valueOf(instruction.parameters().get("command"));
            out.put("command", command.length() > 200 ? command.substring(0, 200) + "…" : command);
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                output.append("$ ").append(summary(steps.get(i).action())).append('\n')
                        .append(String.valueOf(results.get(i).getOrDefault("output", "")).strip()).append('\n');
            }
            out.put("output", output.toString().strip());
        }
        List<String> followed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            followed.add((i + 1) + ". `" + excerpt(summary(steps.get(i).action()), 100) + "` (" + steps.get(i).why() + "): "
                    + (i < results.size() ? results.get(i).get("status") : "not run"));
        }
        out.put("followed", String.join("; ", followed));
        return out;
    }

    private static String summary(Action a) {
        Object text = "Bash".equals(a.toolName()) ? a.parameters().get("command")
                : "TreeCodegen".equals(a.toolName()) ? a.parameters().get("task") : a.parameters();
        return String.valueOf(text);
    }

    private void trace(Action instruction, String decision, String why, List<Step> steps, List<Map<String, Object>> results,
                       double seconds) {
        System.out.println("[FOLLOWER] " + instruction.toolName() + ": " + decision);
        if (trace == null) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", Instant.now().toString());
        row.put("tool", instruction.toolName());
        row.put("instruction", excerpt(summary(instruction), 300));
        row.put("decision", decision);
        if (why != null && !why.isBlank()) {
            row.put("why", why);
        }
        if (steps != null && !steps.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("step", excerpt(summary(steps.get(i).action()), 300));
                s.put("why", steps.get(i).why());
                if (results != null) {
                    s.put("status", i < results.size() ? results.get(i).get("status") : "not run");
                }
                rows.add(s);
            }
            row.put("steps", rows);
        }
        row.put("modelSeconds", seconds);
        try {
            Files.writeString(trace, MAPPER.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[FOLLOWER] trace not written: " + e.getMessage());
        }
    }

    /** The JSON object in a reply that may wrap it in prose or a code fence. */
    private static String jsonObject(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        return start >= 0 && end > start ? reply.substring(start, end + 1) : reply;
    }

    private static String excerpt(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** Programs that only read; git, sed and find only in their reading forms (see {@link #readOnly}). */
    private static final Set<String> READERS = Set.of("test", "[", "[[", "ls", "stat", "cat", "head", "tail", "wc", "grep",
            "egrep", "fgrep", "rg", "file", "readlink", "realpath", "pwd", "du", "df", "which", "basename", "dirname", "diff",
            "cmp", "uniq", "cut", "tr", "nl", "jq", "sed", "find", "git", "sort");
    private static final Set<String> GIT_READS = Set.of("status", "diff", "log", "show", "rev-parse", "ls-files", "cat-file",
            "blame", "grep");
    private static final Set<String> SHELL_STATE = Set.of("cd", "pushd", "popd", "export", "source", ".", "set", "unset",
            "alias", "unalias", "umask", "shopt", "declare", "typeset", "local", "readonly", "exec", "trap", "eval");
    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=\\S*");
    private static final Pattern HARMLESS_REDIRECT = Pattern.compile("\\d?>&\\d|&?\\d?>\\s*/dev/null");

    /** One command (no {@code &&}) that only reads: every stage of its pipe a reader, nothing redirected into a file. */
    static boolean readOnly(String command) {
        String c = command.strip();
        if (c.startsWith("! ")) {
            c = c.substring(2).strip();
        }
        if (c.contains("$(") || c.contains("`") || HARMLESS_REDIRECT.matcher(Chain.unquoted(c)).replaceAll("").contains(">")) {
            return false;
        }
        for (String stage : Chain.split(c, '|')) {
            List<String> words = List.of(stage.strip().split("\\s+"));
            String program = words.get(0);
            if (!READERS.contains(program)
                    || "git".equals(program) && (words.size() < 2 || !GIT_READS.contains(words.get(1))
                    && !List.of("branch", "--show-current").equals(words.subList(1, words.size())))
                    || "sed".equals(program) && (!words.contains("-n") || words.stream().anyMatch(w -> w.startsWith("-i")))
                    || "find".equals(program) && words.stream().anyMatch(w -> w.matches("-(delete|exec|execdir|ok|okdir|fprint.*|fls)"))
                    || "sort".equals(program) && words.stream().anyMatch(w -> w.startsWith("-o"))) {
                return false;
            }
        }
        return true;
    }

    /** A command whose effect is on the shell itself (cd, export, an assignment alone), lost in a step's new shell. */
    static boolean changesTheShell(String command) {
        String c = command.strip();
        String first = c.split("\\s+")[0];
        return SHELL_STATE.contains(first) || ASSIGNMENT.matcher(c).matches();
    }

    /**
     * A Bash command cut at its top-level {@code &&} (outside quotes, parentheses, braces and back-quotes), or the
     * reason it is not a plain {@code &&} chain: a here-document, {@code ||}, {@code ;}, a line break or a background {@code &}.
     */
    record Chain(List<String> commands, String blocker) {

        static Chain of(String command) {
            if (unquoted(command).contains("<<")) {
                return new Chain(List.of(command.strip()), "it holds a here-document");
            }
            List<String> commands = new ArrayList<>();
            String blocker = null;
            StringBuilder current = new StringBuilder();
            int depth = 0;
            char quote = 0;
            for (int i = 0; i < command.length(); i++) {
                char ch = command.charAt(i);
                char nextCh = i + 1 < command.length() ? command.charAt(i + 1) : 0;
                if (quote != 0) {
                    current.append(ch);
                    if (ch == '\\' && quote == '"' && nextCh != 0) {
                        current.append(nextCh);
                        i++;
                    } else if (ch == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (ch == '\\' && nextCh != 0) {
                    current.append(ch).append(nextCh);
                    i++;
                    continue;
                }
                if (ch == '\'' || ch == '"' || ch == '`') {
                    quote = ch;
                } else if (ch == '(' || ch == '{') {
                    depth++;
                } else if ((ch == ')' || ch == '}') && depth > 0) {
                    depth--;
                } else if (depth == 0) {
                    if (ch == '&' && nextCh == '&') {
                        commands.add(current.toString().strip());
                        current.setLength(0);
                        i++;
                        continue;
                    }
                    if (ch == '|' && nextCh == '|') {
                        blocker = blocker == null ? "it chains with ||, which runs on a failure" : blocker;
                    } else if (ch == ';') {
                        blocker = blocker == null ? "it chains with ;, which goes on after a failure" : blocker;
                    } else if (ch == '\n' && !current.toString().isBlank()) {
                        blocker = blocker == null ? "it runs several lines" : blocker;
                    } else if (ch == '&' && i > 0 && command.charAt(i - 1) != '>' && nextCh != '>') {
                        blocker = blocker == null ? "it runs a command in the background" : blocker;
                    }
                }
                current.append(ch);
            }
            commands.add(current.toString().strip());
            return new Chain(List.copyOf(commands), blocker);
        }

        /** The command with its quoted text taken out, for looking at what the shell itself reads. */
        static String unquoted(String command) {
            StringBuilder out = new StringBuilder();
            char quote = 0;
            for (int i = 0; i < command.length(); i++) {
                char ch = command.charAt(i);
                if (quote != 0) {
                    if (ch == '\\' && quote == '"') {
                        i++;
                    } else if (ch == quote) {
                        quote = 0;
                    }
                } else if (ch == '\\') {
                    i++;
                } else if (ch == '\'' || ch == '"') {
                    quote = ch;
                } else {
                    out.append(ch);
                }
            }
            return out.toString();
        }

        /** A command cut at a top-level separator character outside quotes ({@code |} but not {@code ||}). */
        static List<String> split(String command, char separator) {
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            char quote = 0;
            for (int i = 0; i < command.length(); i++) {
                char ch = command.charAt(i);
                if (quote != 0) {
                    current.append(ch);
                    if (ch == '\\' && quote == '"' && i + 1 < command.length()) {
                        current.append(command.charAt(++i));
                    } else if (ch == quote) {
                        quote = 0;
                    }
                } else if (ch == '\'' || ch == '"') {
                    quote = ch;
                    current.append(ch);
                } else if (ch == separator && (i + 1 >= command.length() || command.charAt(i + 1) != separator)
                        && (i == 0 || command.charAt(i - 1) != separator)) {
                    parts.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
            parts.add(current.toString());
            return parts;
        }
    }
}
