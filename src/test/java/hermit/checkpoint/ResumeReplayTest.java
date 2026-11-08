package hermit.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hermit.plan.ParsedPlan;
import hermit.plan.PlanParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A resume from a "selected" step puts that step's plan up again instead of planning anew. */
class ResumeReplayTest {

    @TempDir
    Path dir;

    @Test
    void theSelectedPlanOfASelectedStepIsReplayed() throws IOException {
        Files.writeString(dir.resolve("selected.json"), """
                {"actions": [{"parameters": {"command": "echo replay-42"}, "tool": "Bash"}],
                 "confidence": 0.96, "name": "apply-corrected-shapes"}
                """);
        String plan = TurnReplay.replayedPlan(dir, "selected");
        ParsedPlan parsed = new PlanParser().parse(plan);
        assertEquals(1, parsed.branches().size());
        assertEquals("apply-corrected-shapes", parsed.branches().get(0).name());
        assertEquals("echo replay-42", parsed.branches().get(0).actions().get(0).parameters().get("command"));
    }

    @Test
    void otherSeamsAndAMissingPlanPlanAnew() throws IOException {
        assertNull(TurnReplay.replayedPlan(dir, "selected"), "no selected.json");
        Files.writeString(dir.resolve("selected.json"), "{\"actions\": [], \"name\": \"x\"}");
        assertNull(TurnReplay.replayedPlan(dir, "plan"), "a plan step without its reply");
        assertNull(TurnReplay.replayedPlan(dir, "executed"));
    }

    @Test
    void theReplyOfAPlanStepIsReadAgain() throws IOException {
        // task 040a: the reply did not parse; after the parser fix the same reply is replayed
        Files.writeString(dir.resolve("llm-raw.txt"), "prose { code } then {\"final_answer\": \"done\"}");
        assertEquals("prose { code } then {\"final_answer\": \"done\"}", TurnReplay.replayedPlan(dir, "plan"));
    }
}
