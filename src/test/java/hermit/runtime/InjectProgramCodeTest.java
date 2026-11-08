package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import hermit.api.Action;
import hermit.text.CodeFence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The out-of-band program is injected into the code-bearing action; other actions
 * (and non-empty code params) are left untouched. */
class InjectProgramCodeTest {

    private final CodeFence.Extracted prog = new CodeFence.Extracted("python", "print(6*7)", "");

    @Test
    void injectsCodeIntoWriteProgram() {
        List<Action> out = RepairCoordinator.injectProgramCode(
                List.of(new Action("WriteProgram", Map.of("language", "python", "run", "true"))), prog);
        assertEquals("print(6*7)", out.get(0).parameters().get("code"));
        assertEquals("python", out.get(0).parameters().get("language"));
    }

    @Test
    void injectsIntoSyntaxCheckWhenCodeMissing() {
        List<Action> out = RepairCoordinator.injectProgramCode(
                List.of(new Action("SyntaxCheck", Map.of("language", "python"))), prog);
        assertEquals("print(6*7)", out.get(0).parameters().get("code"));
    }

    @Test
    void nonCodeToolUntouchedAndFencePreferredOverInline() {
        List<Action> out = RepairCoordinator.injectProgramCode(List.of(
                new Action("Bash", Map.of("command", "ls")),
                new Action("WriteProgram", Map.of("language", "python", "code", "print('already here')"))), prog);
        assertFalse(out.get(0).parameters().containsKey("code"));            // Bash untouched
        // The fenced block WINS over inline JSON code (inline is often flattened by the 7B).
        assertEquals("print(6*7)", out.get(1).parameters().get("code"));
    }

    @Test
    void noBlockLeavesActionsUnchanged() {
        List<Action> in = List.of(new Action("WriteProgram", Map.of("language", "python")));
        assertEquals(in, RepairCoordinator.injectProgramCode(in, new CodeFence.Extracted(null, null, "x")));
    }
}
