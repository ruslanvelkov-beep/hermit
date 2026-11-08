package hermit.runtime;

import hermit.api.Action;
import hermit.checkpoint.TurnSnapshot;
import hermit.plan.Branch;
import hermit.plan.ParsedPlan;
import hermit.plan.RepeatGuard;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a turn keeps of its runs: the results, plans and branches summarised for the context, the steps marked executed,
 * the signatures of the calls made, and whether a step succeeded, was refused, or only repeated calls that had already
 * succeeded.
 */
public final class ResultSummaries {

    private ResultSummaries() {
    }

    /**
     * A stable signature of a branch's actions (tool + its salient argument) so the
     * loop guard can tell when the brain is re-proposing the SAME action. Deterministic
     * and independent of parameter map ordering.
     */
    static String actionSignature(Branch b) {
        if (b == null || b.actions() == null || b.actions().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Action a : b.actions()) {
            sb.append(a.toolName()).append('(');
            Map<String, Object> p = a.parameters() == null ? Map.of() : a.parameters();
            int salient = 0;
            for (String k : new String[] {"query", "url", "command", "task", "behavior",
                    "action", "symbol", "filename"}) {
                Object v = p.get(k);
                if (v != null && !String.valueOf(v).isBlank()) {
                    sb.append(k).append('=').append(String.valueOf(v).strip()).append(';');
                    salient++;
                }
            }
            if (salient == 0) {
                // an edit has none of those keys, and every TreeEdit signed alike; an action
                // without them is signed by all its parameters
                sb.append(RepeatGuard.signature(a));
            }
            sb.append(") ");
        }
        return sb.toString().toLowerCase();
    }

    /**
     * Whether the context's last refusal of a plan or an ending is newer than its last executed branch. One refused plan
     * once handed every later step of the turn to the stronger planner; the user wants the lighter
     * one to do most of the work, with the stronger one helping after a refusal.
     */
    static boolean refusedSinceLastRun(String context) {
        int ran = context.lastIndexOf("] Executed branch '");
        int refused = Math.max(context.lastIndexOf("[Plan REJECTED"), Math.max(context.lastIndexOf("REJECTED your last plan"),
                context.lastIndexOf("[Answer gate REJECTED")));
        return refused > ran;
    }

    /** Every action of the plan is a verbatim repeat of a call that SUCCEEDED this turn. */
    static boolean allSucceededRepeats(List<Action> actions, Map<String, String> executed) {
        if (actions == null || actions.isEmpty() || executed == null) {
            return false;
        }
        for (Action a : actions) {
            if (!"Success".equals(executed.get(RepeatGuard.signature(a)))) {
                return false;
            }
        }
        return true;
    }

    /** The first step a refusal names ("step 2 …", "steps 4-6 …") when it is a later step of a plan with that many steps, else -1. */
    static int refusedStepIndex(String note, int steps) {
        if (note == null || steps < 2) {
            return -1;
        }
        Matcher m = Pattern.compile("(?i)\\bsteps?\\s+#?(\\d{1,2})\\b").matcher(note);
        if (!m.find()) {
            return -1;
        }
        int n = Integer.parseInt(m.group(1));
        return n >= 2 && n <= steps ? n : -1;
    }

    /**
     * Both syntheses of the turn were spent on answers written BEFORE the calculator had been read (each refused for
     * quoting code no result contained); when the read finally ran there was no synthesis left and the turn halted with
     * the evidence in hand. A success - new evidence - closes the ledger and restores the synthesis budget: an answer
     * synthesized from more evidence is a different answer.
     */
    public static void succeeded(TurnSnapshot live) {
        live.failedSteps.clear();
        live.synthesizedAnswers = 0;
        live.disputedClaims.clear();   // new evidence - a disputed claim may now have its line
    }

    static final String NO_STEPS_YET = "No steps executed yet for the current request.";

    /**
     * Once a step ran, the header no longer says none has. The header replaced is the line right after the request: a
     * request that quoted the header once had that quote replaced instead.
     */
    static void markStepsExecuted(StringBuilder log, String request) {
        String head = "Current request: " + request + "\n";
        int at = log.indexOf(head + NO_STEPS_YET);
        if (at >= 0) {
            at += head.length();
            log.replace(at, at + NO_STEPS_YET.length(), "Steps executed for the current request follow.");
        }
    }

    /**
     * A branch's name says what it planned, which may be more than ran (a cut, or a plan of reads named for the whole
     * task); the line names the tools that ran.
     */
    static String executedBranchLine(int iteration, Branch selected) {
        List<Action> actions = selected.actions() == null ? List.of() : selected.actions();
        String ran = actions.stream().map(a -> ToolExecutor.baseToolName(a.toolName())).distinct()
                .collect(Collectors.joining(", "));
        return "\n[Iteration " + iteration + "] Executed branch '" + selected.name() + "' — what ran: " + ran + " ("
                + actions.size() + (actions.size() == 1 ? " action" : " actions") + "; a branch's name is what it planned, "
                + "only these ran).";
    }

    /**
     * The author planned a recording of `InvoiceTotalsTest` - a class that does not exist (the module's test is
     * `InvoiceCalculatorsTest`) - and the invented-test guard let it through because the name was in the context: in
     * the author's own refused plan and in the refusal that quoted it. Grounding is what TOOLS returned: the "Tool
     * results:" segments of the context and nothing else - not plans, not refusals, not the ledger.
     */
    public static String toolResultsOnly(String contextSoFar) {
        if (contextSoFar == null || contextSoFar.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int at = contextSoFar.indexOf("Tool results: ", from);
            if (at < 0) {
                break;
            }
            int end = contextSoFar.indexOf("\n[", at);
            if (end < 0) {
                end = contextSoFar.length();
            }
            out.append(contextSoFar, at, end).append('\n');
            from = end;
        }
        return out.toString();
    }

    /** One line naming a plan - its first branch's actions, or the start of its final answer. */
    public static String summarizePlan(ParsedPlan plan) {
        if (plan == null) {
            return "an unparseable plan";
        }
        if (plan.isFinal()) {
            return "final answer: \"" + RepeatGuard.excerpt(plan.finalAnswer(), 100) + "\"";
        }
        if (plan.isClarify()) {
            return "clarifying question: \"" + RepeatGuard.excerpt(plan.clarification(), 100) + "\"";
        }
        return plan.branches() == null || plan.branches().isEmpty() ? "an empty plan" : summarizeBranch(plan.branches().get(0));
    }

    public static String summarizeBranch(Branch b) {
        if (b == null || b.actions() == null || b.actions().isEmpty()) {
            return "an empty plan";
        }
        StringBuilder sb = new StringBuilder();
        for (Action a : b.actions()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(a.toolName());
            Map<String, Object> p = a.parameters() == null ? Map.of() : a.parameters();
            List<String> shown = new ArrayList<>();
            for (String k : new String[] {"action", "command", "symbol", "name", "file", "method", "query", "op"}) {
                Object v = p.get(k);
                if (v != null && !String.valueOf(v).isBlank()) {
                    shown.add(k + "=" + RepeatGuard.excerpt(String.valueOf(v), 70));
                }
                if (shown.size() == 2) {
                    break;
                }
            }
            if (!shown.isEmpty()) {
                sb.append('{').append(String.join(", ", shown)).append('}');
            }
        }
        return sb.length() > 220 ? sb.substring(0, 220) + "…" : sb.toString();
    }

    public static void recordSignatures(List<Action> actions, Map<String, Object> results, Map<String, String> store) {
        Iterator<Object> values = results.values().iterator();
        for (Action a : actions) {
            Object v = values.hasNext() ? values.next() : null;
            store.put(RepeatGuard.signature(a), isSuccess(v) ? "Success" : "Error");
        }
    }

    /** True if a tool result reports success (has information the answer should report). */
    static boolean isSuccess(Object value) {
        return value instanceof Map<?, ?> m && "Success".equalsIgnoreCase(String.valueOf(m.get("status")));
    }

    /**
     * The entries of a tool result that hold what the call was for, each shown up to
     * {@link RepeatGuard#readOutputCap()} like a read's output: a consultant's answer, a program's stdout and stderr,
     * assembled content, a page's text, a file search's matches, a graph query's lists and an edit's problems. A
     * consultant's 15,866-character plan once reached the planner as its first 600 characters, and a graph query's
     * callers list as its first three callers.
     */
    static final Set<String> PAYLOAD_KEYS = Set.of("output", "answer", "stdout", "stderr", "content", "text", "matches",
            "callers", "callees", "declarations", "candidates", "methods", "tests", "routes", "reachPath", "escapes",
            "catches", "impact", "entryPoints", "testEntryPoints", "problems");

    public static String summarizeResults(Map<String, Object> results) {
        final int perValueCap = 1200;
        StringBuilder sb = new StringBuilder("{");
        results.forEach((key, value) -> {
            String text;
            if (value instanceof Map<?, ?> m) {
                // the composer's result was one 1200-char value, and the cap cut off its
                // LAST keys - importsAdded, written, next - so the model never saw the imports it then invented and
                // the gates had nothing to check them against. A map is capped per ENTRY, so short facts survive
                // however long methodText or the reach paths are.
                StringBuilder inner = new StringBuilder("{");
                m.forEach((k, v) -> {
                    String t = String.valueOf(v);
                    int cap = PAYLOAD_KEYS.contains(String.valueOf(k)) ? RepeatGuard.readOutputCap() : perValueCap / 2;
                    if (t.length() > cap) {
                        t = t.substring(0, cap) + "...(" + (t.length() - cap) + " more chars NOT in your context)";
                    }
                    inner.append(k).append("=").append(t).append(", ");
                });
                text = inner.append("}").toString();
            } else {
                text = String.valueOf(value);
                if (text.length() > perValueCap) {
                    text = text.substring(0, perValueCap) + "...(" + (text.length() - perValueCap) + " more chars NOT in your context)";
                }
            }
            sb.append(key).append("=").append(text).append("; ");
        });
        return sb.append("}").toString();
    }
}
