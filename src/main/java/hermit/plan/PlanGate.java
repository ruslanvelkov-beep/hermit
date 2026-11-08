package hermit.plan;

import hermit.answer.AnswerGate;
import hermit.api.Action;
import hermit.runtime.RepairCoordinator;
import hermit.runtime.ToolExecutor;
import hermit.tool.process.BashTool;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plan gate's checks on a plan before it runs: a destructive or outward-facing git step, a tool that is not
 * registered, a refiner's rewrite that is no refinement, a program run with no program, and the plan's shape (complex
 * enough for the hosted critic, editing or only reading).
 */
public final class PlanGate {

    private PlanGate() {
    }

    /** The wording of an issue, ignoring its [lens] tag, case, punctuation and whitespace. */
    public static String normalizeIssue(String issue) {
        return issue == null ? "" : issue.replaceAll("^\\s*\\[[^]]*]\\s*", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static final Pattern PLAN_TOOL = Pattern.compile("\"tool\"\\s*:\\s*\"(\\w+)\"");

    /**
     * Whether a plan is complex enough for the hosted critic: at least {@code -Dhermit.review.hostedCriticActions}
     * actions (3 by default), or an action that writes code.
     */
    public static boolean planIsComplex(String planJson) {
        Matcher m = PLAN_TOOL.matcher(planJson);
        int actions = 0;
        while (m.find()) {
            actions++;
            if (List.of("TreeEdit", "TreeCodegen", "WriteProgram").contains(m.group(1))) {
                return true;
            }
        }
        return actions >= Integer.getInteger("hermit.review.hostedCriticActions", 3);
    }

    private static final Pattern EDITING_TOOL = Pattern.compile(
            "\"tool\"\\s*:\\s*\"(?:TreeEdit|TreeCodegen|GenerateCode|GenerateTest|WriteProgram|TddCycle|SpecTestComposer)\"");

    public static boolean readsOnly(String planJson) {
        return planJson != null && !RepeatGuard.SHELL_FILE_WRITE.matcher(planJson).find()
                && !EDITING_TOOL.matcher(planJson).find()
                && !planJson.matches("(?s).*\\bgit\\s+(?:-C\\s+\\S+\\s+)?(?:add|commit)\\b.*");
    }

    /** A plan whose only steps run a program (WriteProgram, RunProgram) with no program text, in the step or out of band. */
    public static boolean programlessRun(ParsedPlan plan, String outOfBandProgram) {
        if (plan == null || plan.branches() == null || plan.branches().isEmpty()) {
            return false;
        }
        for (Branch b : plan.branches()) {
            for (Action a : b.actions()) {
                String tool = a.toolName();
                if (!"WriteProgram".equals(tool) && !"RunProgram".equals(tool)) {
                    return false;
                }
                Object code = a.parameters() == null ? null
                        : RepairCoordinator.firstNonNull(a.parameters().get("program"), a.parameters().get("code"),
                                a.parameters().get("command"));
                if ((code != null && !String.valueOf(code).isBlank()) || (outOfBandProgram != null && !outOfBandProgram.isBlank())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Two discarded rewrites in a turn and the refiner no longer rewrites this turn. */
    public static boolean refinerExhausted(int discardedRewrites) {
        return discardedRewrites >= 2;
    }

    /** Why a refiner's rewrite is not a refinement - it repeats calls this turn already made, or shotguns actions. */
    public static String rewriteNotARefinementIssue(ParsedPlan rewrite, Map<String, String> executed) {
        if (rewrite == null || rewrite.branches() == null) {
            return null;
        }
        int actions = 0;
        for (Branch b : rewrite.branches()) {
            if (b.actions() == null) {
                continue;
            }
            actions += b.actions().size();
            for (Action a : b.actions()) {
                String status = executed == null ? null : executed.get(RepeatGuard.signature(a));
                if (status != null) {
                    return "re-runs a call this turn already made (" + a.toolName() + ", " + status + ")";
                }
            }
        }
        return actions > MAX_REWRITE_ACTIONS ? "shotguns " + actions + " actions (more than " + MAX_REWRITE_ACTIONS + ")" : null;
    }

    static final int MAX_REWRITE_ACTIONS = 6;

    /** The refined plan carries an editing tool the original plan did not. */
    public static boolean introducesEdits(ParsedPlan original, ParsedPlan refined) {
        return !usesEditTool(original) && usesEditTool(refined);
    }

    private static boolean usesEditTool(ParsedPlan plan) {
        if (plan == null || plan.branches() == null) {
            return false;
        }
        for (Branch b : plan.branches()) {
            if (b.actions() == null) {
                continue;
            }
            for (Action a : b.actions()) {
                if (AnswerGate.EDIT_TOOLS.contains(a.toolName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first Bash step of {@code actions} that is a destructive or outward-facing git/gh operation, as a
     *  plan-gate issue naming it; null when the plan has none. */
    public static String forbiddenStepIssue(List<Action> actions) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null) {
                continue;
            }
            Object cmd = a.parameters().get("command");
            String op = BashTool.destructiveOp(cmd == null ? null : String.valueOf(cmd));
            if (op != null) {
                return "This plan runs `" + op + "` — FORBIDDEN by the hard rule (read-only mode: never push, never post "
                        + "to a ticket or PR; only LOCAL `git add` and `git commit -s` are allowed). Re-plan WITHOUT that "
                        + "step and keep the others.";
            }
        }
        return null;
    }

    // A refiner reply that is really a "give me your question" non-answer (common from
    // chat models fed a thin prompt) - must NEVER overwrite a real answer.
    private static final Pattern REFINER_FILLER = Pattern.compile(
            "(?i)(you haven'?t provided|please (provide|supply|share)|i'?m ready|provide the (exact|specific)"
            + "|no (question|task|problem) (was )?(provided|given|specified)|what (is your|do you) (question|need))");

    /** True if text is a generic "give me your question" non-answer, not a real answer. */
    public static boolean isRefinerFiller(String s) {
        return s != null && REFINER_FILLER.matcher(s).find();
    }

    /** The first action whose tool is not registered, named with the real tools (shell commands are Bash). */
    public static String unknownToolIssue(List<Action> actions, Set<String> registered) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            String name = a.toolName();
            if (name == null || registered.contains(name) || registered.contains(ToolExecutor.baseToolName(name))) {
                continue;
            }
            boolean shellLike = name.matches("(?i)exec|shell|sh|run|terminal|command|cat|sed|read_?file|readfile|ls|grep");
            return "'" + name + "' is not a tool here" + (shellLike ? " — shell commands and file reads run through the Bash tool "
                    + "(e.g. {\"tool\": \"Bash\", \"parameters\": {\"command\": \"sed -n '1,200p' <file>\"}})" : "")
                    + "; the tools are " + registered + ".";
        }
        return null;
    }
}
