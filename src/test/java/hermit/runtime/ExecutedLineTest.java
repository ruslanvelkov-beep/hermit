package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import hermit.plan.Branch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A branch named "Implement parser changes and commit" held only a read; the next prompt said "No steps executed yet"
 * and listed that name as tried, and the planner went looking for the commit's hash.
 */
class ExecutedLineTest {

    @Test
    void theIterationLineSaysWhatRanBesideTheBranchName() {
        Branch b = new Branch("Implement parser changes and commit", "", 0.9,
                List.of(new Action("Bash", Map.of("command", "sed -n '1,9p' A.java"))));
        String line = ResultSummaries.executedBranchLine(1, b);
        assertTrue(line.startsWith("\n[Iteration 1] Executed branch 'Implement parser changes and commit'"), line);
        assertTrue(line.contains("what ran: Bash (1 action;"), line);
    }

    @Test
    void theNothingRanHeaderIsReplacedOnceAStepRan() {
        StringBuilder log = new StringBuilder("Current request: do it\nNo steps executed yet for the current request.\nmore");
        ResultSummaries.markStepsExecuted(log, "do it");
        assertFalse(log.toString().contains("No steps executed yet"));
        assertEquals("Current request: do it\nSteps executed for the current request follow.\nmore", log.toString());
        ResultSummaries.markStepsExecuted(log, "do it");
        assertEquals("Current request: do it\nSteps executed for the current request follow.\nmore", log.toString());
    }

    // the request quoted the header (a test fixture it ported), and the first occurrence, inside
    // the request, was replaced: the planner read a request the supervisor never wrote, under a header saying nothing
    // ran
    @Test
    void aRequestThatQuotesTheHeaderKeepsItsText() {
        String request = "Port it: \"...\\nNo steps executed yet for the current request.\"\n"
                + "No steps executed yet for the current request.";
        StringBuilder log = new StringBuilder("Current request: " + request
                + "\nNo steps executed yet for the current request.\nmore");
        ResultSummaries.markStepsExecuted(log, request);
        assertEquals("Current request: " + request + "\nSteps executed for the current request follow.\nmore",
                log.toString());
        ResultSummaries.markStepsExecuted(log, request);
        assertEquals("Current request: " + request + "\nSteps executed for the current request follow.\nmore",
                log.toString());
    }
}
