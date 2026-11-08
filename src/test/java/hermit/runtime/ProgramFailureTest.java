package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Detection of a failing generated program (the trigger for the outer-LLM consult). */
class ProgramFailureTest {

    @Test
    void detectsSyntaxError() {
        String f = RepairCoordinator.programFailure(Map.of(
                "WriteProgram", Map.of("status", "Success", "syntaxOk", false,
                        "syntaxError", "line 1: invalid syntax", "code", "// bad")));
        assertNotNull(f);
        assertTrue(f.contains("WriteProgram") && f.contains("invalid syntax"), f);
    }

    @Test
    void detectsNonZeroExit() {
        String f = RepairCoordinator.programFailure(Map.of(
                "RunProgram", Map.of("status", "NonZeroExit", "exitCode", 127,
                        "stderr", "bash: python: command not found")));
        assertNotNull(f);
        assertTrue(f.contains("command not found"), f);
    }

    @Test
    void ignoresSuccessfulRun() {
        assertNull(RepairCoordinator.programFailure(Map.of(
                "RunProgram", Map.of("status", "Success", "stdout", "BTC 62000", "exitCode", 0))));
    }

    @Test
    void ignoresNonProgramTools() {
        assertNull(RepairCoordinator.programFailure(Map.of(
                "WebSearch", Map.of("status", "Success", "count", 3))));
    }
}
