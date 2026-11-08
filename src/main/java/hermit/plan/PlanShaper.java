package hermit.plan;

import hermit.api.Action;
import hermit.runtime.ToolExecutor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rewrites a plan gets before it runs: a TreeCodegen call given the request's task sentence, the request's own
 * commands put back verbatim, repeats within the plan and repeats of calls that already succeeded dropped, the read-only
 * steps of sibling branches merged in, a placeholder resolved to the one source file the turn found, and the step that
 * writes hosted edit items recognised.
 */
public final class PlanShaper {

    private PlanShaper() {
    }

    public static int countOccurrences(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    /** The plan's actions with later verbatim duplicates (same signature) removed; the same instance when there are none. */
    public static List<Action> withoutIntraPlanDuplicates(List<Action> actions) {
        if (actions == null || actions.size() < 2) {
            return actions;
        }
        Set<String> seen = new HashSet<>();
        List<Action> out = new ArrayList<>();
        for (Action a : actions) {
            if (seen.add(RepeatGuard.signature(a))) {
                out.add(a);
            }
        }
        return out.size() == actions.size() ? actions : out;
    }

    /** The actions that are not verbatim repeats of a call that already SUCCEEDED this turn (same instance when nothing is dropped). */
    public static List<Action> withoutSucceededRepeats(List<Action> actions, Map<String, String> executed) {
        if (actions == null || executed == null || executed.isEmpty()) {
            return actions;
        }
        List<Action> out = new ArrayList<>();
        for (Action a : actions) {
            if (!"Success".equals(executed.get(RepeatGuard.signature(a)))) {
                out.add(a);
            }
        }
        return out.size() == actions.size() ? actions : out;
    }

    /**
     * The plan's TreeCodegen call named its file, method and items but no task sentence, and the tool failed ("task,
     * file and projectDir are required") after a 400-second plan. A TreeCodegen call without a task gets the request's
     * first line, which names the task; the list is returned unchanged when none lacks it.
     */
    public static List<Action> withDefaultTask(List<Action> actions, String request) {
        if (actions == null || request == null) {
            return actions;
        }
        String firstLine = request.strip().lines().findFirst().orElse("").strip();
        if (firstLine.isEmpty()) {
            return actions;
        }
        List<Action> out = new ArrayList<>();
        boolean changed = false;
        for (Action a : actions) {
            Object task = a.parameters() == null ? null : a.parameters().get("task");
            if ("TreeCodegen".equals(ToolExecutor.baseToolName(a.toolName())) && (task == null || String.valueOf(task).isBlank())) {
                Map<String, Object> p = new LinkedHashMap<>(a.parameters() == null ? Map.of() : a.parameters());
                p.put("task", firstLine);
                out.add(new Action(a.toolName(), p));
                changed = true;
            } else {
                out.add(a);
            }
        }
        return changed ? out : actions;
    }

    /**
     * Plans copied the one Bash command the request gives with one change (the checker's path replaced by a search for
     * it; the closing '|' of a ledger pattern dropped, which would have left '||' in the row). A Bash command that
     * differs from a command the request gives after "ONE Bash command" by a few characters (nine in ten the same) is
     * that command, verbatim; a command of the plan's own is left alone.
     */
    public static List<Action> withRequestCommandsVerbatim(List<Action> actions, String request) {
        if (actions == null || request == null) {
            return actions;
        }
        List<String> given = new ArrayList<>();
        Matcher m = Pattern.compile("ONE Bash command[^\\n]*:\\s*\\n\\s*(\\S[^\\n]*)").matcher(request);
        while (m.find()) {
            given.add(m.group(1).strip());
        }
        if (given.isEmpty()) {
            return actions;
        }
        List<Action> out = null;
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            Object c = a.parameters() == null ? null : a.parameters().get("command");
            if (!"Bash".equals(ToolExecutor.baseToolName(a.toolName())) || c == null) {
                continue;
            }
            String command = String.valueOf(c).strip();
            for (String g : given) {
                if (!g.equals(command) && similarity(g, command) >= 0.9) {
                    Map<String, Object> p = new LinkedHashMap<>(a.parameters());
                    p.put("command", g);
                    if (out == null) {
                        out = new ArrayList<>(actions);
                    }
                    out.set(i, new Action(a.toolName(), p));
                    break;
                }
            }
        }
        return out == null ? actions : out;
    }

    /** One minus the edit distance over the longer length: 1 for equal texts. */
    static double similarity(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (Math.max(n, m) == 0) {
            return 1;
        }
        if (Math.abs(n - m) > Math.max(n, m) / 10 + 1) {
            return 0;   // more than a tenth apart in length: not a near copy
        }
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[m] / Math.max(n, m);
    }

    /**
     * A hosted edit plan held the request's TreeCodegen call and left out the one Bash command the request gives for
     * after it, twice, and each time a rejection and a re-plan put it back. A plan that calls TreeCodegen with no Bash
     * action after the call gets that command, verbatim, right after the last TreeCodegen call, run in the request's
     * repository.
     */
    public static List<Action> withRequestCommand(List<Action> actions, String request) {
        if (actions == null || request == null) {
            return actions;
        }
        Matcher m = Pattern
                .compile("When TreeCodegen succeeds, run ONE Bash command:\\s*\\n\\s*(\\S[^\\n]*)").matcher(request);
        if (!m.find()) {
            return actions;
        }
        int last = -1;
        for (int i = 0; i < actions.size(); i++) {
            String tool = ToolExecutor.baseToolName(actions.get(i).toolName());
            if ("TreeCodegen".equals(tool)) {
                last = i;
            } else if ("Bash".equals(tool) && last >= 0) {
                return actions;   // a command follows the call: the seam's review compares it with the request's
            }
        }
        if (last < 0) {
            return actions;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("command", m.group(1).strip());
        Matcher repo = Pattern
                .compile("Repository \\(change files only inside it\\):\\s*(\\S+)").matcher(request);
        Object projectDir = actions.get(last).parameters() == null ? null : actions.get(last).parameters().get("projectDir");
        if (repo.find()) {
            p.put("workingDir", repo.group(1));
        } else if (projectDir != null) {
            p.put("workingDir", projectDir);
        }
        List<Action> out = new ArrayList<>(actions);
        out.add(last + 1, new Action("Bash", p));
        return out;
    }

    /**
     * True for the plan step that writes the edit items: the switch is on, the request calls for a TreeCodegen
     * action, something has run in this turn (the reads), and no TreeCodegen call has succeeded yet.
     */
    public static boolean hostedEditStep(boolean enabled, String request, String contextLog, Map<String, String> executed) {
        if (!enabled || request == null || !request.contains("TreeCodegen") || contextLog == null
                || !contextLog.contains("[Iteration ")) {
            return false;
        }
        for (Map.Entry<String, String> e : executed.entrySet()) {
            if (e.getKey().startsWith("TreeCodegen") && "Success".equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static int occurrences(String text, String mark) {
        int n = 0;
        for (int at = text.indexOf(mark); at >= 0; at = text.indexOf(mark, at + mark.length())) {
            n++;
        }
        return n;
    }

    public static List<Action> cleanPrefix(List<Action> actions, Function<List<Action>, String> gate) {
        for (int n = actions.size() - 1; n >= 1; n--) {
            List<Action> prefix = List.copyOf(actions.subList(0, n));
            if (gate.apply(prefix) == null) {
                return prefix;
            }
        }
        return List.of();
    }

    private static final int MAX_MERGED_ACTIONS = 8;

    /** The selected branch plus the read-only actions of its sibling branches (deduplicated, bounded). */
    public static Branch mergeReadOnlySiblings(List<Branch> all, Branch selected) {
        if (all == null || all.size() < 2 || selected == null || selected.actions() == null) {
            return selected;
        }
        List<Action> merged = new ArrayList<>(selected.actions());
        Set<String> seen = new HashSet<>();
        for (Action a : merged) {
            seen.add(RepeatGuard.signature(a));
        }
        boolean changed = false;
        for (Branch b : all) {
            if (b == selected || b.actions() == null) {
                continue;
            }
            for (Action a : b.actions()) {
                if (merged.size() >= MAX_MERGED_ACTIONS) {
                    break;
                }
                if (RepeatGuard.isReadOnly(a) && seen.add(RepeatGuard.signature(a))) {
                    merged.add(a);
                    changed = true;
                }
            }
        }
        return changed ? new Branch(selected.name() + " (+read-only siblings)", selected.description(),
                selected.confidence(), merged) : selected;
    }

    /** Strip a single leading/trailing ``` fence so the code can be re-fenced cleanly. */
    public static String stripFences(String s) {
        String t = s.strip();
        t = t.replaceFirst("^```[a-zA-Z0-9]*\\n", "").replaceFirst("\\n```\\s*$", "");
        return t.strip();
    }

    /** The placeholder replaced by the one source file this turn located or read - else the actions unchanged. */
    public static List<Action> resolvePlaceholders(List<Action> actions, Set<String> executedSignatures) {
        if (actions == null || executedSignatures == null) {
            return actions;
        }
        Set<String> known = new LinkedHashSet<>();
        for (String sig : executedSignatures) {
            Matcher p = Pattern.compile("(/[^\\s,}=\"']+\\.java)").matcher(sig);
            while (p.find()) {
                known.add(p.group(1));
            }
        }
        if (known.size() != 1) {
            return actions;
        }
        String path = known.iterator().next();
        List<Action> out = new ArrayList<>();
        boolean changed = false;
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null || a.parameters().get("command") == null) {
                out.add(a);
                continue;
            }
            String command = String.valueOf(a.parameters().get("command"));
            Matcher m = RepeatGuard.PLACEHOLDER_SUBSTITUTION.matcher(command);
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                if (!m.group().matches("(?i).*(locat|file|path|output|source).*")) {
                    continue;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(path));
                any = true;
            }
            if (!any) {
                out.add(a);
                continue;
            }
            m.appendTail(sb);
            Map<String, Object> params = new LinkedHashMap<>(a.parameters());
            params.put("command", sb.toString().replace("\"" + path + "\"", path).replace("'" + path + "'", path));
            out.add(new Action(a.toolName(), params));
            changed = true;
        }
        return changed ? out : actions;
    }
}
