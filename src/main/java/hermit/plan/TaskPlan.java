package hermit.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import hermit.api.Action;
import hermit.checkpoint.TurnSnapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The task plan (user, 2026-10-21): the strongest model writes the task's steps first, numbered; the step planner plans
 * the commands of the current step only; a judge decides when the current step is done; the steps ahead are checked
 * again when one is done, and the current and later steps are rewritten when they bring nothing. The plan lives in the
 * turn's state ({@link TurnSnapshot#taskPlan}), so a resumed turn keeps it.
 */
public final class TaskPlan {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    private TaskPlan() {
    }

    /** The judge's answer: whether the current step is done, and why. */
    public record Verdict(boolean done, String why) {}

    /** The steps of a {@code {"steps": [...]}} reply, each a {@code {"goal": ...}} or a string; null when it holds none. */
    public static List<TurnSnapshot.TaskStep> parseSteps(String reply) {
        JsonNode root = object(reply);
        JsonNode steps = root == null ? null : root.path("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            return null;
        }
        List<TurnSnapshot.TaskStep> out = new ArrayList<>();
        for (JsonNode s : steps) {
            String goal = s.isTextual() ? s.asText() : s.path("goal").asText("");
            if (!goal.isBlank()) {
                out.add(new TurnSnapshot.TaskStep(goal.strip()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** The judge's {@code {"done": ..., "why": ...}}; null when the reply holds none. */
    public static Verdict parseVerdict(String reply) {
        JsonNode root = object(reply);
        if (root == null || !root.has("done")) {
            return null;
        }
        JsonNode d = root.get("done");
        boolean done = d.isBoolean() ? d.asBoolean() : List.of("true", "yes").contains(d.asText("").strip().toLowerCase());
        return new Verdict(done, root.path("why").asText("").strip());
    }

    public static boolean active(TurnSnapshot s) {
        return s.taskPlan != null && !s.taskPlan.isEmpty();
    }

    public static boolean finished(TurnSnapshot s) {
        return active(s) && s.currentStep >= s.taskPlan.size();
    }

    public static TurnSnapshot.TaskStep current(TurnSnapshot s) {
        return active(s) && !finished(s) ? s.taskPlan.get(s.currentStep) : null;
    }

    /** The current step is done with {@code outcome}; the next one becomes current. */
    public static void markCurrentDone(TurnSnapshot s, String outcome) {
        TurnSnapshot.TaskStep step = current(s);
        if (step != null) {
            step.done = true;
            step.outcome = outcome;
            s.currentStep++;
            s.stepIterations = 0;
        }
    }

    /** The current step and the ones after it are replaced by {@code steps}; the done ones stay. */
    public static void replaceFromCurrent(TurnSnapshot s, List<TurnSnapshot.TaskStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        List<TurnSnapshot.TaskStep> kept = new ArrayList<>(s.taskPlan.subList(0, Math.min(s.currentStep, s.taskPlan.size())));
        kept.addAll(steps);
        s.taskPlan = kept;
        s.stepIterations = 0;
    }

    /**
     * A look-ahead that gives back steps whose text is under a third of the old ones' is taken for a misreading of the
     * request and is not applied; so are new steps that drop a round the old ones named ({@link #keepsTheRounds}).
     */
    public static boolean keepsTheWork(List<TurnSnapshot.TaskStep> before, List<TurnSnapshot.TaskStep> after) {
        int was = before.stream().mapToInt(t -> t.goal.length()).sum();
        int is = after == null ? 0 : after.stream().mapToInt(t -> t.goal.length()).sum();
        return is * 3 >= was && keepsTheRounds(before, after);
    }

    /** A request's round named in a step: "Round 4", "the round-4 items" (any dash). */
    private static final Pattern ROUND = Pattern.compile("(?i)\\bround[\\s\\-\\u2010-\\u2015]*(\\d+)\\b");

    /**
     * After round 3 was done, a look-ahead gave back the steps for rounds 5 and 6 and the answer; round 4 was gone, and
     * the step planner sent round 5 twice while round 4 never ran. New steps keep every round the old ones named; steps
     * that name no round are judged by their text alone.
     */
    public static boolean keepsTheRounds(List<TurnSnapshot.TaskStep> before, List<TurnSnapshot.TaskStep> after) {
        return after != null && rounds(after).containsAll(rounds(before));
    }

    /** A request's round that gives exact TreeEdit items: "2. ONE TreeEdit action on <file> with the items" and a JSON list. */
    private static final Pattern EXACT_ROUND = Pattern.compile("(?m)^(\\d+)\\. ONE TreeEdit action on \\S+ with the items\\s*(?=\\[)");
    private static final List<String> ITEM_FIELDS = List.of("op", "type", "code", "method", "anchor");

    /** The request's rounds that give exact TreeEdit items: the round's number and its items, in order. */
    static Map<Integer, List<Map<String, Object>>> exactRounds(String request) {
        Map<Integer, List<Map<String, Object>>> out = new TreeMap<>();
        Matcher m = EXACT_ROUND.matcher(request == null ? "" : request);
        while (m.find()) {
            try (JsonParser p = MAPPER.createParser(request.substring(m.end()))) {
                out.put(Integer.parseInt(m.group(1)), MAPPER.readValue(p, new TypeReference<List<Map<String, Object>>>() {}));
            } catch (IOException | RuntimeException e) {
                // not a JSON list of items: not an exact round
            }
        }
        return out;
    }

    /**
     * Round 2 never ran, round 3's items ran in its place, and the step judge, which read only "TreeEdit succeeded",
     * marked round 2 done; the answer "done" followed with round 2 missing. A round that gives exact items has run
     * when an action sent exactly those items and its edit succeeded; this records every such round of the iteration
     * (the executor keys the results TreeEdit, TreeEdit#2, … in the order of the actions).
     */
    public static void recordExactRounds(String request, List<Action> actions, Map<String, Object> results, Set<Integer> run) {
        Map<Integer, List<Map<String, Object>>> rounds = exactRounds(request);
        if (rounds.isEmpty() || actions == null || results == null) {
            return;
        }
        int n = 0;
        for (Action a : actions) {
            if (!"TreeEdit".equals(a.toolName())) {
                continue;
            }
            n++;
            Object r = results.get(n == 1 ? "TreeEdit" : "TreeEdit#" + n);
            if (!(r instanceof Map<?, ?> res) || !"Success".equals(String.valueOf(res.get("status")))) {
                continue;
            }
            List<?> sent = itemsOf(a);
            for (Map.Entry<Integer, List<Map<String, Object>>> e : rounds.entrySet()) {
                if (sameItems(e.getValue(), sent)) {
                    run.add(e.getKey());
                }
            }
        }
    }

    /** The items an action sent: its items list, or the one item given at the action's level. */
    private static List<?> itemsOf(Action a) {
        Map<String, Object> p = a.parameters();
        if (p == null) {
            return List.of();
        }
        if (p.get("items") instanceof List<?> l && !l.isEmpty()) {
            return l;
        }
        return p.get("op") != null ? List.of(p) : List.of();
    }

    private static boolean sameItems(List<Map<String, Object>> expected, List<?> sent) {
        if (expected.size() != sent.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!(sent.get(i) instanceof Map<?, ?> got)) {
                return false;
            }
            for (String f : ITEM_FIELDS) {
                if (!Objects.equals(expected.get(i).get(f), got.get(f))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The verdict on a step that names exactly one round giving exact items: done when its items ran; null otherwise. */
    public static Verdict exactRoundVerdict(String request, String goal, Set<Integer> run) {
        Set<String> named = rounds(List.of(new TurnSnapshot.TaskStep(goal == null ? "" : goal)));
        if (named.size() != 1) {
            return null;
        }
        int k = Integer.parseInt(named.iterator().next());
        if (!exactRounds(request).containsKey(k)) {
            return null;
        }
        return run.contains(k) ? new Verdict(true, "round " + k + "'s items ran and the edit succeeded")
                : new Verdict(false, "round " + k + "'s items have not run" + (run.isEmpty() ? "" : " (rounds whose items ran: "
                + run + ")") + "; send round " + k + "'s items exactly as the request gives them");
    }

    /** A request's round that is one exact shell command: "4. ONE Bash command: <the command>". */
    private static final Pattern BASH_ROUND = Pattern.compile("(?m)^(\\d+)\\. ONE Bash command: (.+)$");

    /**
     * The planner sent round 5's items, or the final commit command, while round 4's items had never run, though the
     * task plan named round 4 as the current step. A plan that runs a later round of the request (its exact items, or
     * its exact shell command) while a round of exact items before it has not run is refused; rounds sent in order in
     * one plan count as run for the ones after them.
     */
    public static String laterRoundFirstIssue(String request, List<Action> actions, Set<Integer> run) {
        Map<Integer, List<Map<String, Object>>> rounds = exactRounds(request);
        if (rounds.isEmpty() || actions == null) {
            return null;
        }
        Map<Integer, String> commands = new TreeMap<>();
        Matcher m = BASH_ROUND.matcher(request);
        while (m.find()) {
            commands.put(Integer.parseInt(m.group(1)), m.group(2).strip());
        }
        Set<Integer> sent = new HashSet<>(run);
        for (Action a : actions) {
            int round = -1;
            if ("TreeEdit".equals(a.toolName())) {
                List<?> items = itemsOf(a);
                for (Map.Entry<Integer, List<Map<String, Object>>> e : rounds.entrySet()) {
                    if (sameItems(e.getValue(), items)) {
                        round = e.getKey();
                        break;
                    }
                }
            } else if ("Bash".equals(a.toolName()) && a.parameters() != null) {
                String cmd = String.valueOf(a.parameters().get("command")).strip();
                for (Map.Entry<Integer, String> e : commands.entrySet()) {
                    if (e.getValue().equals(cmd)) {
                        round = e.getKey();
                        break;
                    }
                }
            }
            if (round < 0) {
                continue;
            }
            for (int k : rounds.keySet()) {
                if (k < round && !sent.contains(k)) {
                    return "This plan runs round " + round + " of the request, but round " + k + " has not run: its items "
                            + "were never sent and applied. Rounds run in order: send round " + k + "'s items exactly as the "
                            + "request gives them first.";
                }
            }
            sent.add(round);
        }
        return null;
    }

    /** The rounds giving exact items whose items have not run, in order. */
    public static List<Integer> exactRoundsNotRun(String request, Set<Integer> run) {
        List<Integer> out = new ArrayList<>();
        for (int k : exactRounds(request).keySet()) {
            if (!run.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static Set<String> rounds(List<TurnSnapshot.TaskStep> steps) {
        Set<String> out = new HashSet<>();
        for (TurnSnapshot.TaskStep t : steps) {
            Matcher m = ROUND.matcher(t.goal == null ? "" : t.goal);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** The goals of the steps, one per line, numbered from {@code first}. */
    public static String goals(List<TurnSnapshot.TaskStep> steps, int first) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            sb.append(first + i).append(". ").append(steps.get(i).goal).append('\n');
        }
        return sb.toString().strip();
    }

    /** The planner's view of the plan: each step numbered, the done ones with their outcome, the current one marked. */
    public static String block(TurnSnapshot s) {
        if (!active(s)) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nTASK PLAN (the task's steps; plan the commands of the CURRENT step only):\n");
        for (int i = 0; i < s.taskPlan.size(); i++) {
            TurnSnapshot.TaskStep step = s.taskPlan.get(i);
            sb.append("  ").append(i + 1).append(". ");
            if (step.done) {
                sb.append("[done] ").append(step.goal);
                if (step.outcome != null && !step.outcome.isBlank()) {
                    sb.append(": ").append(step.outcome);
                }
            } else if (i == s.currentStep) {
                sb.append("[CURRENT] ").append(step.goal);
            } else {
                sb.append(step.goal);
            }
            sb.append('\n');
        }
        if (finished(s)) {
            sb.append("Every step of the task plan is done: answer the request now (Shape B).\n");
        }
        return sb.toString();
    }

    /** The plan as a reviewer reads it at the "composed" seam. */
    public static String markdown(TurnSnapshot s) {
        StringBuilder sb = new StringBuilder("# Task plan\n\n");
        for (int i = 0; i < s.taskPlan.size(); i++) {
            sb.append(i + 1).append(". ").append(s.taskPlan.get(i).goal).append('\n');
        }
        return sb.toString();
    }

    private static JsonNode object(String reply) {
        if (reply == null) {
            return null;
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(reply.substring(start, end + 1));
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }
}
