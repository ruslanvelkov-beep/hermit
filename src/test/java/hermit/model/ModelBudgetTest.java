package hermit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelBudgetTest {

    @AfterEach
    void clear() {
        System.clearProperty("hermit.model.inputTokens");
    }

    // a model is sent no more than its configured input budget (GPT-OSS: 32k tokens to start with); a model without
    // one keeps the budget its caller sized the prompt for
    @Test
    void aModelsInputBudgetCutsOnlyItsOwnPrompts() {
        System.setProperty("hermit.model.inputTokens", "openai/gpt-oss-120b:preferred=1000, other-model=32000");
        assertEquals(1000, ModelBudget.inputTokens("openai/gpt-oss-120b:preferred"));
        assertEquals(32000, ModelBudget.inputTokens("other-model"));
        assertNull(ModelBudget.inputTokens("gpt-6-luna"));
        assertEquals(3200, ModelBudget.chars("openai/gpt-oss-120b:preferred", 5));
        assertEquals(16, ModelBudget.chars("gpt-6-luna", 5), "without a budget of its own, the caller's");

        String prompt = "HEAD " + "x".repeat(10_000) + " TAIL";
        String fitted = ModelBudget.fit("openai/gpt-oss-120b:preferred", prompt);
        assertTrue(fitted.length() <= 3200, "cut to the budget: " + fitted.length());
        assertTrue(fitted.startsWith("HEAD ") && fitted.endsWith(" TAIL"), "the opening and the end are kept");
        assertTrue(fitted.contains("cut to fit"), "the cut is marked");
        assertSame(prompt, ModelBudget.fit("gpt-6-luna", prompt), "no budget: the prompt goes whole");
        assertSame(prompt, ModelBudget.fit("other-model", prompt), "inside the budget: the prompt goes whole");
    }
}
