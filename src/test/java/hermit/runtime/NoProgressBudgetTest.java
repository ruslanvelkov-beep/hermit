package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hermit.plan.PlanGate;
import org.junit.jupiter.api.Test;

class NoProgressBudgetTest {

    @Test
    void issueWordingIsNormalizedForCounting() {
        // the same complaint arrives under different lens tags and punctuation
        assertEquals(PlanGate.normalizeIssue("[completeness] The plan's 'branches' field should be 'steps'."),
                PlanGate.normalizeIssue("[review] The plan's \"branches\" field should be \"steps\""));
        assertEquals("the plan omits the reach step",
                PlanGate.normalizeIssue("[necessity]  The plan omits the reach step. "));
        assertEquals("", PlanGate.normalizeIssue(null));
    }

    @Test
    void theBudgetIsSmallEnoughToUnblockADriveAndLargeEnoughToBeHeard() {
        // two rounds of feedback, then the issue stops vetoing
        assertEquals(2, AgentLoop.STUCK_ISSUE_LIMIT);
    }
}
