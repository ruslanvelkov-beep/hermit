package hermit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.runtime.LoopSettings;
import hermit.tool.consult.ConsultTool;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Groq first, DeepSeek the paid fallback and every tenth automatic call; a daily-quota 429 rests a platform. */
class HostedRoutingTest {

    @Test
    void groqFirstDeepSeekFallbackAndEveryTenthCall() {
        assertEquals(HostedPlatforms.GROQ, ConsultTool.platformForAutoCall(1, true, true, true));
        assertEquals(HostedPlatforms.GROQ, ConsultTool.platformForAutoCall(9, true, false, true));
        assertEquals(HostedPlatforms.DEEPSEEK, ConsultTool.platformForAutoCall(10, true, true, true), "every tenth call");
        assertEquals(HostedPlatforms.DEEPSEEK, ConsultTool.platformForAutoCall(20, true, true, true));
        assertEquals(HostedPlatforms.GROQ, ConsultTool.platformForAutoCall(10, true, true, false), "no DeepSeek key: the tenth call stays free");
        assertEquals(HostedPlatforms.CEREBRAS, ConsultTool.platformForAutoCall(3, false, true, true), "Groq exhausted: the other free tier before the paid one");
        assertEquals(HostedPlatforms.DEEPSEEK, ConsultTool.platformForAutoCall(3, false, false, true), "every free tier exhausted: the fallback");
        assertNull(ConsultTool.platformForAutoCall(3, false, false, false));
    }

    @Test
    void aDailyQuotaRestsThePlatformForAnHourAPerMinuteOneForTheRetryAfter() {
        assertEquals(60 * 60 * 1000L, LoopSettings.exhaustionMillisFor("HTTP 429 {\"error\":{\"message\":\"Rate limit reached … on tokens per day (TPD): Limit 200000\"}}"));
        assertEquals(2 * 60 * 1000L, LoopSettings.exhaustionMillisFor("HTTP 429 {\"error\":{\"message\":\"Rate limit reached … on tokens per minute (TPM)\"}}"));
        assertEquals(0, LoopSettings.exhaustionMillisFor("HTTP 500 upstream error"));
        assertEquals(0, LoopSettings.exhaustionMillisFor(null));
        assertEquals(30_000L, HostedChatClient.retryAfterMillis("30", 60_000L));
        assertEquals(60_000L, HostedChatClient.retryAfterMillis("?", 60_000L));
        UsageLedger.markExhausted("unit-test-platform", System.currentTimeMillis() + 5_000);
        assertTrue(UsageLedger.isExhausted("unit-test-platform"));
        UsageLedger.markExhausted("unit-test-platform", System.currentTimeMillis() - 1);
        assertFalse(UsageLedger.isExhausted("unit-test-platform"));
        assertFalse(UsageLedger.isExhausted("never-seen"));
    }

    @Test
    void deepSeekIsAKnownPaidPlatform() {
        assertEquals("https://api.deepseek.com/v1", HostedPlatforms.baseUrl(HostedPlatforms.DEEPSEEK));
        assertEquals("https://api.groq.com/openai/v1", HostedPlatforms.baseUrl(HostedPlatforms.GROQ));
        assertTrue(HostedPlatforms.isPaid(HostedPlatforms.DEEPSEEK));
        assertFalse(HostedPlatforms.isPaid(HostedPlatforms.GROQ));
        assertEquals(4, HostedPlatforms.models(HostedPlatforms.DEEPSEEK).size(), "chat, reasoner, and the served v4-pro and flash");
        assertEquals(HostedPlatforms.hasKey(HostedPlatforms.DEEPSEEK), HelperEndpoint.cloudKeyFor("https://api.deepseek.com/v1") != null);
    }

    // a helper on the Hugging Face router or on OpenAI takes that platform's key, as Groq's, Cerebras's and DeepSeek's do
    @Test
    void aHelperOnTheHuggingFaceRouterTakesItsKey() {
        try {
            System.setProperty("hermit.huggingface.apiKey", "hf-test-key");
            System.setProperty("hermit.openai.apiKey", "openai-test-key");
            assertEquals("hf-test-key", HelperEndpoint.cloudKeyFor("https://router.huggingface.co/v1"));
            assertEquals("openai-test-key", HelperEndpoint.cloudKeyFor("https://api.openai.com/v1"));
            assertNull(HelperEndpoint.cloudKeyFor("http://127.0.0.1:52415/v1"));
            System.setProperty("hermit.chain.lan.baseUrl", "https://router.huggingface.co/v1");
            assertEquals("hf-test-key", HelperChain.lanConfig().apiKey(), "the chain's first helper too");
        } finally {
            System.clearProperty("hermit.huggingface.apiKey");
            System.clearProperty("hermit.openai.apiKey");
            System.clearProperty("hermit.chain.lan.baseUrl");
        }
    }

    // the helper on the Hugging Face router (GPT-OSS) reviewed plans from the last 3,500 characters of
    // the context and repaired them from their last 2,500, windows sized for a 14B model on a 16 GB machine; the request's
    // opening had already fallen off. A hosted helper reads its model's input budget, two thirds of it context.
    @Test
    void aHelperOnAHostedRouterReadsItsModelsInputBudget() {
        try {
            System.setProperty("hermit.chain.lan.baseUrl", "http://127.0.0.1:1234/v1");
            assertEquals(3500, LoopSettings.helperContextChars(3500), "a helper on the local network keeps its small window");
            assertEquals(6000, HelperChain.lanPromptChars());
            System.setProperty("hermit.chain.lan.baseUrl", "https://router.huggingface.co/v1");
            System.setProperty("hermit.chain.lan.model", "openai/gpt-oss-120b:preferred");
            assertEquals(HostedBudget.charsFor(""), HelperChain.lanPromptChars(), "no budget of its own: the hosted one");
            System.setProperty("hermit.model.inputTokens", "openai/gpt-oss-120b:preferred=32000");
            assertEquals(102_400, HelperChain.lanPromptChars(), "GPT-OSS: 32k tokens");
            assertEquals(68_266, LoopSettings.helperContextChars(2500), "two thirds for the context, the rest for the plan and the instructions");
            System.setProperty("hermit.chain.lan.maxPromptChars", "30000");
            assertEquals(30_000, HelperChain.lanPromptChars(), "a limit set for the helper still wins");
            assertEquals(20_000, LoopSettings.helperContextChars(2500));
        } finally {
            for (String p : List.of("hermit.chain.lan.baseUrl", "hermit.chain.lan.model", "hermit.model.inputTokens",
                    "hermit.chain.lan.maxPromptChars")) {
                System.clearProperty(p);
            }
        }
    }
}
