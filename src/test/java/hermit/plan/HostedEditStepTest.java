package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** A hosted model writes the plan that carries the edit items. */
class HostedEditStepTest {

    private static final String REQUEST = "Port part 3. Read first: sed -n '1,9p' A.java\n"
            + "The change, as exactly ONE TreeCodegen action. The items:\n  1. ...";

    @Test
    void theStepAfterTheReadsAndBeforeTheEditIsTheHostedOne() {
        String afterReads = "Current request: ...\n[Iteration 1] Executed branch 'read' — what ran: Bash (1 action; ...).";
        assertTrue(PlanShaper.hostedEditStep(true, REQUEST, afterReads, Map.of("Bash|{\"command\":\"sed\"}", "Success")));
    }

    @Test
    void theReadStepTheCommitStepAndOtherRequestsStayLocal() {
        String nothingRan = "Current request: ...\nNo steps executed yet for the current request.";
        assertFalse(PlanShaper.hostedEditStep(true, REQUEST, nothingRan, Map.of()), "the reads come first");
        String afterReads = "Current request: ...\n[Iteration 1] Executed branch 'read' — what ran: Bash (1 action; ...).";
        assertFalse(PlanShaper.hostedEditStep(true, REQUEST, afterReads,
                Map.of("TreeCodegen|{\"file\":\"A.java\"}", "Success")), "after the edit succeeded, the commit is local");
        assertFalse(PlanShaper.hostedEditStep(true, "Run the tests and report.", afterReads, Map.of()));
        assertFalse(PlanShaper.hostedEditStep(false, REQUEST, afterReads, Map.of()), "off unless switched on");
    }
}
