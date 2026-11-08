package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hermit.model.HostedBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The owner, 2026-10-26: the planner's prompt fills its window less the room for the reply (32k tokens for the local
 * model, hosted Qwen and GPT-OSS-20B); a 128k-character cap was about 40k tokens, more than such a window before any reply.
 */
class LoopSettingsTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("hermit.prompt.windowTokens");
        System.clearProperty("hermit.maxTokens");
        System.clearProperty("hermit.prompt.maxChars");
    }

    @Test
    void thePromptFillsTheWindowLessTheReply() {
        assertEquals((int) ((32_768 - 8_192) * HostedBudget.CHARS_PER_TOKEN), LoopSettings.promptChars());
        System.setProperty("hermit.prompt.windowTokens", "131072");
        System.setProperty("hermit.maxTokens", "16384");
        assertEquals((int) ((131_072 - 16_384) * HostedBudget.CHARS_PER_TOKEN), LoopSettings.promptChars(), "a larger window");
        System.setProperty("hermit.prompt.maxChars", "50000");
        assertEquals(50_000, LoopSettings.promptChars(), "a fixed cap still overrides");
    }

    @Test
    void theHistoryGetsWhatTheRestOfThePromptLeaves() {
        assertEquals(38_000, LoopSettings.historyChars(78_000, 40_000));
        assertEquals(8_000, LoopSettings.historyChars(30_000, 40_000), "never less than a floor");
    }
}
