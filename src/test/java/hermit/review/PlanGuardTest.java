package hermit.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The plan-review guard skips the panel only for a plan the reviewer is confident is clean, and never blocks one. */
class PlanGuardTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("hermit.inference.localOnly");
    }

    /** A reviewer that answers the guard's two questions as given. */
    private static StructuredReviewer answering(int score, double confidence, double wrong) {
        return (state, questions) -> new StructuredReviewer.Result(Map.of(
                "reviewNeed", new StructuredReviewer.Answer("score", null, null, score, Map.of(), confidence),
                "obviouslyWrong", new StructuredReviewer.Answer("yes", wrong, null, null, Map.of(), null)), null);
    }

    @Test
    void aConfidentCleanVerdictSkipsTheReviewPanel() {
        assertTrue(PlanGuard.canSkipReview(answering(0, 0.95, 0.02), "context", "{\"branches\":[]}", "do X"));
    }

    @Test
    void aLowConfidenceCleanVerdictDoesNotSkip() {
        assertFalse(PlanGuard.canSkipReview(answering(0, 0.5, 0.02), "context", "{\"branches\":[]}", "do X"));
    }

    @Test
    void aNonZeroReviewNeedScoreDoesNotSkip() {
        assertFalse(PlanGuard.canSkipReview(answering(2, 0.95, 0.02), "context", "{\"branches\":[]}", "do X"));
    }

    @Test
    void anObviouslyWrongSignalDoesNotSkipEvenWithACleanScore() {
        assertFalse(PlanGuard.canSkipReview(answering(0, 0.95, 0.4), "context", "{\"branches\":[]}", "do X"));
    }

    @Test
    void aFailedCallNeverSkips() {
        StructuredReviewer failing = (state, questions) -> new StructuredReviewer.Result(Map.of(), "the call failed");
        assertFalse(PlanGuard.canSkipReview(failing, "context", "{\"branches\":[]}", "do X"));
    }

    @Test
    void aLocalOnlyRunDoesNotAskUnlessToldTo() {
        System.setProperty("hermit.inference.localOnly", "true");
        assertFalse(PlanGuard.canSkipReview(answering(0, 0.95, 0.02), "context", "{\"branches\":[]}", "do X"));
    }

    @Test
    void noReviewerNeverSkips() {
        assertFalse(PlanGuard.canSkipReview(null, "context", "{\"branches\":[]}", "do X"));
    }
}
