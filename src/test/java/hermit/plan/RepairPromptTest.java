package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Deterministic: the repair prompt states the goal (valid JSON), the code-out-of-
 * band remedy (WriteProgram + fenced block), and includes the broken text. */
class RepairPromptTest {

    @Test
    void repairPromptAsksForValidJsonAndCarriesTheBrokenText() {
        String broken = "{\"branches\": [BROKEN \"unescaped\" quote]}";
        String p = new PlanPromptBuilder().repairJsonPrompt(broken);
        assertTrue(p.contains("VALID"), p);
        assertTrue(p.contains("WriteProgram"), p);
        assertTrue(p.contains("fenced"), p);
        assertTrue(p.contains(broken), "the malformed text must be included for repair");
    }
}
