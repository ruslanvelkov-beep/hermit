package hermit.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.checkpoint.TurnSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The owner, 2026-10-26: a stack of frames (the request, its steps, each step's plans) so the history the planner sees
 * is served from a deterministic structure. Measured on 257 recorded planning steps, 71% of the earlier results a plan
 * needed came from earlier sibling steps: a step that ends returns what it touched to its parent, and the planner sees
 * the current step and the previous one whole, the other steps' return values, and the rest in tiers.
 */
class FrameStackTest {

    private static String iteration(int n, String file) {
        return "[Iteration " + n + "] Executed branch 'b" + n + "' Tool results: {TreeEdit={status=Success, file=" + file
                + ", method=" + file.replace(".java", "") + "#run}, Bash={status=Success, output=" + "x".repeat(4000) + "}}\n";
    }

    /** Three steps of three iterations each; the turn on its third step. */
    private static TurnSnapshot turn(StringBuilder log) {
        TurnSnapshot live = new TurnSnapshot();
        live.taskPlan = List.of(new TurnSnapshot.TaskStep("port A"), new TurnSnapshot.TaskStep("port B"),
                new TurnSnapshot.TaskStep("port C"));
        log.append("Current request: port three classes\nSteps executed for the current request follow.\n");
        int n = 0;
        for (int step = 0; step < 3; step++) {
            live.currentStep = step;
            for (int k = 0; k < 3; k++) {
                n++;
                int start = log.length();
                log.append(iteration(n, "Step" + step + "File" + k + ".java"));
                FrameStack.record(live, "port three classes", n, "b" + n, log.toString(), log.substring(start));
            }
        }
        return live;
    }

    @Test
    void framesFollowTheTaskSteps() {
        TurnSnapshot live = turn(new StringBuilder());
        List<TurnSnapshot.Frame> steps = live.frames.stream().filter(f -> f.kind.equals("step")).toList();
        assertEquals("request", live.frames.get(0).kind);
        assertEquals(3, steps.size());
        assertFalse(steps.get(0).open, "a step the turn moved on from is closed");
        assertTrue(steps.get(2).open, "the current step is open");
        assertTrue(steps.get(0).returned.contains("Step0File0.java") && steps.get(0).returned.contains("Step0File2#run"),
                steps.get(0).returned);
        assertTrue(steps.get(0).returned.contains("6 succeeded, 0 failed"), steps.get(0).returned);   // two calls an iteration
        long plans = live.frames.stream().filter(f -> f.kind.equals("plan")).count();
        assertEquals(9, plans, "one plan frame an iteration");
        assertEquals(live.frames.indexOf(steps.get(1)),
                live.frames.stream().filter(f -> f.kind.equals("plan") && f.firstIteration == 5).findFirst().orElseThrow().parent);
    }

    @Test
    void theCurrentAndThePreviousStepStayWholeAndEarlierStepsReturnWhatTheyTouched() {
        StringBuilder log = new StringBuilder();
        TurnSnapshot live = turn(log);
        String fitted = new TieredContext(chunk -> new String[] {"MEDIUM", "SHORT"}).fit(log.toString(), 30_000, live);
        assertTrue(fitted.length() <= 30_000, "within the budget: " + fitted.length());
        for (int n = 4; n <= 9; n++) {
            assertTrue(fitted.contains("[Iteration " + n + "]"), "steps 2 and 3 whole: iteration " + n);
        }
        assertTrue(fitted.contains("[step 1 returned] files: Step0File0.java, Step0File1.java, Step0File2.java"), fitted);
        assertTrue(fitted.indexOf("[step 1 returned]") < fitted.indexOf("[Iteration 4]"), "the return values come before the history");
    }

    @Test
    void withoutATaskPlanItIsTheTieredHistory() {
        StringBuilder log = new StringBuilder("Current request: x\n");
        TurnSnapshot live = new TurnSnapshot();
        for (int n = 1; n <= 9; n++) {
            int start = log.length();
            log.append(iteration(n, "F" + n + ".java"));
            FrameStack.record(live, "x", n, "b" + n, log.toString(), log.substring(start));
        }
        TieredContext tiered = new TieredContext(chunk -> new String[] {"MEDIUM", "SHORT"});
        assertEquals(tiered.fit(log.toString(), 20_000), tiered.fit(log.toString(), 20_000, live));
    }

    @Test
    void theStackIsKeptWithTheTurnsCheckpoint(@TempDir Path dir) throws Exception {
        TurnSnapshot live = turn(new StringBuilder());
        Path state = dir.resolve("state.json");
        live.write(state);   // what a seam writes; a resume reads it back
        TurnSnapshot back = TurnSnapshot.read(state);
        assertEquals(live.frames.size(), back.frames.size());
        assertEquals(live.frames.get(1).returned, back.frames.get(1).returned);
        assertTrue(Files.exists(state));
    }
}
