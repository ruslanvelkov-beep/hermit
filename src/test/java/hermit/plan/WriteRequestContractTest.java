package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WriteRequestContractTest {

    @Test
    void theContractAlsoForbidsGenerationInACharacterizeStep() {
        // in the characterize stage the plan called the generator on the PRODUCTION file, which the
        // misplaced-test guard refused
        String prompt = new PlanPromptBuilder().generatePrompt(new PlanNode("n", "goal", Map.of()), "ctx");
        assertTrue(prompt.contains("CHARACTERIZE / ANALYSE / REPORT"), prompt);
        assertTrue(prompt.contains("NO code-authoring action"), prompt);
    }

    @Test
    void theContractTellsThePlannerThatAWriteRequestNeedsACodeAuthoringAction() {
        // after two critic rejections the TDD plan retreated to locate/escapes/tests - analysis only
        String prompt = new PlanPromptBuilder().generatePrompt(new PlanNode("n", "goal", Map.of()), "ctx");
        assertTrue(prompt.contains("WHEN THE REQUEST SAYS *WRITE*"), prompt);
        assertTrue(prompt.contains("code-authoring action — TreeCodegen (unit tests, edits) or SpecTestComposer (spec tests)"), prompt);
        assertTrue(prompt.contains("do NOT satisfy a write request") || prompt.contains("DO NOT satisfy a write request"),
                prompt);
    }
}
