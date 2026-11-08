package hermit.review;

import java.util.List;
import java.util.Map;

/**
 * Optional (needs a configured {@link StructuredReviewer} - see {@link ReviewConfig}): a single cheap call that runs
 * before the agent loop's expensive multi-lens LLM panel (currently 5 lenses plus an optional
 * remote critic - up to 6 full LLM calls per review round). It can only SKIP that panel for a plan the reviewer
 * is confident needs no review; it never blocks a plan by itself, so it introduces no new veto path -
 * the panel stays the sole source of rejections. A no-op (never skips) without a configured reviewer.
 */
public final class PlanGuard {

    private static final int STATE_CONTEXT_CAP = 2000;

    private PlanGuard() {}

    public static boolean canSkipReview(StructuredReviewer reviewer, String contextSoFar, String planJson,
            String currentRequest) {
        if (!ReviewConfig.guardrailsEnabled(reviewer)) return false;
        String state = "Current request: " + currentRequest
                + "\nRecent context (evidence gathered so far):\n" + capTail(contextSoFar, STATE_CONTEXT_CAP)
                + "\nProposed plan:\n" + planJson;
        StructuredReviewer.Result r = reviewer.decide(state, Map.of(
                "reviewNeed", new StructuredReviewer.Question.Score(
                        "How much review does this proposed plan need before it is safe to execute, given the "
                                + "request and the evidence already gathered? It needs no review when every value it "
                                + "uses is grounded in the request or the shown evidence, it only takes the immediate "
                                + "next step (not a whole multi-step method), and it does not repeat work the evidence "
                                + "shows is already done or was already rejected.",
                        List.of("no issues likely", "minor issues possible", "likely has real issues")),
                "obviouslyWrong", new StructuredReviewer.Question.YesNo(
                        "The proposed plan uses a value, tool, or parameter that is not grounded anywhere in the "
                                + "request or the shown evidence, or repeats an action the evidence shows already ran "
                                + "or was already rejected.")));
        if (!r.ok()) return false;
        StructuredReviewer.Answer reviewNeed = r.answers().get("reviewNeed");
        StructuredReviewer.Answer obviouslyWrong = r.answers().get("obviouslyWrong");
        if (reviewNeed == null || obviouslyWrong == null) return false;
        return reviewNeed.score() != null && reviewNeed.score() == 0
                && reviewNeed.confidence() != null && reviewNeed.confidence() >= 0.85
                && obviouslyWrong.yes() != null && obviouslyWrong.yes() < 0.15;
    }

    private static String capTail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
