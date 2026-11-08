package hermit.api;

import hermit.plan.Branch;
import java.util.List;
import java.util.Map;

/**
 * Structured observation points for a single agent run. The agent emits these
 * instead of printing directly, so different front-ends (console, TUI) can
 * render the same steps. Every method has a default no-op so a listener need
 * only override what it cares about.
 */
public interface AgentListener {

    /** Agent constructed and ready; tools are "Name - summary" lines. */
    default void onInit(List<String> tools) {}

    /** A new planning iteration begins; maxIterations is negative when stepping mode has no cap. */
    default void onIteration(int iteration, int maxIterations) {}

    /** The brain's planning response could not be parsed (will retry). */
    default void onPlanUnparsed(int iteration, String rawResponse) {}

    /** The brain proposed these alternative branches this iteration (before one is chosen). */
    default void onPlanProposed(List<Branch> branches) {}

    /** The remote refiner rewrote the local brain's plan into a (hopefully) better one. */
    default void onPlanRefined(boolean changed, String note) {}

    /** The plan-review panel's verdict (approved, or the issues it raised). */
    default void onPlanReview(boolean approved, List<String> issues) {}

    /** The final-answer execution gate's verdict: rejected when a terminal answer asks for /
     * describes something a successful tool result already provides (forces re-answer from results). */
    default void onAnswerGate(boolean approved, List<String> issues) {}

    /** A branch was chosen; its actions name the tools/commands about to run. */
    default void onBranchSelected(String name, double confidence, int outOf, List<Action> actions) {}

    /** Results of the selected branch's tool executions. */
    default void onToolResults(Map<String, Object> results) {}

    /** The brain returned a final answer; the run ends. */
    default void onFinalAnswer(String answer, int executedSteps) {}

    /** The brain asked the user a clarifying question; the run ends awaiting a reply. */
    default void onClarify(String question) {}

    /** The run hit the iteration cap without a final answer. */
    default void onHalted(String contextLog) {}

    /** An optional tool's auto-detected availability at startup. When not available,
     * front-ends surface a yellow notice that the tool is disabled (endpoint unreachable). */
    default void onOptionalTool(String name, boolean available, String detail) {}
}
