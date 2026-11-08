package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StuckIssueBudgetTest {

    @Test
    void issueWordingIsNormalizedForCounting() {
        // the same complaint arrives under different lens tags and punctuation
        assertEquals(PlanGate.normalizeIssue("[completeness] The plan's 'branches' field should be 'steps'."),
                PlanGate.normalizeIssue("[review] The plan's \"branches\" field should be \"steps\""));
        assertEquals("the plan omits the graph reach step",
                PlanGate.normalizeIssue("[necessity]  The plan omits the GRAPH reach step. "));
        assertEquals("", PlanGate.normalizeIssue(null));
    }
}
