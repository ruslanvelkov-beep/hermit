package hermit.tool.consult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.model.HostedPlatforms;
import hermit.model.HostedPlatforms.CloudModel;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pure, no-network unit tests for ConsultTool's routing/context/answer helpers.
 * These never touch an API key or the wire, so they run everywhere.
 */
class ConsultToolTest {

    @Test
    void trimContextKeepsShortContextUntouched() {
        String ctx = "short context";
        assertEquals(ctx, ConsultTool.trimContext(ctx, 1000));
    }

    @Test
    void trimContextKeepsHeadAndTailWithinBudget() {
        String ctx = "H".repeat(5000) + "T".repeat(5000);
        String trimmed = ConsultTool.trimContext(ctx, 2000);
        assertTrue(trimmed.length() < ctx.length(), "should shrink");
        assertTrue(trimmed.startsWith("H"), "keeps head");
        assertTrue(trimmed.endsWith("T"), "keeps tail");
        assertTrue(trimmed.contains("trimmed to save tokens"), "marks the cut");
    }

    @Test
    void trimContextHandlesNull() {
        assertEquals("", ConsultTool.trimContext(null, 100));
    }

    @Test
    void stripThinkRemovesClosedThinkBlock() {
        String s = "<think>lots of reasoning</think>The answer is 42.";
        assertEquals("The answer is 42.", ConsultTool.stripThink(s));
    }

    @Test
    void stripThinkKeepsReasoningIfThatIsAllThereIs() {
        // Truncated / unclosed think with no final answer: don't return empty.
        String s = "<think>reasoning that never closed";
        assertFalse(ConsultTool.stripThink(s).isEmpty());
    }

    @Test
    void stripThinkPassesPlainAnswerThrough() {
        assertEquals("plain answer", ConsultTool.stripThink("  plain answer  "));
    }

    @Test
    void buildPromptSelectsModeTemplate() {
        assertTrue(ConsultTool.buildPrompt("refinePlan", "do X", null).contains("planning reviewer"));
        assertTrue(ConsultTool.buildPrompt("critique", "do X", null).contains("sceptical"));
        assertTrue(ConsultTool.buildPrompt("answer", "do X", null).contains("senior engineer"));
        assertTrue(ConsultTool.buildPrompt(null, "do X", null).contains("senior engineer"));
    }

    @AfterEach
    void clearProperties() {
        for (String p : List.of("hermit.cloud.models", "hermit.cloud.providers", "hermit.deepseek.apiKey", "hermit.openai.apiKey",
                "hermit.openai.baseUrl", "hermit.huggingface.apiKey", "hermit.huggingface.baseUrl", "hermit.bigbrother.model", "hermit.bigbrother.direct", "hermit.deepseek.baseUrl")) {
            System.clearProperty(p);
        }
    }

    @Test
    void aModelLeftOutOfTheAllowListIsNeitherListedNorHonouredWhenNamed() {
        System.setProperty("hermit.cloud.providers", "deepseek");
        System.setProperty("hermit.deepseek.apiKey", "test-key");
        String unset = new ConsultTool().getDocumentation();
        assertTrue(unset.contains("deepseek/deepseek-flash"), unset);
        assertFalse(unset.contains("deepseek-v4-pro"), "unset: of DeepSeek's models only flash is offered");

        System.setProperty("hermit.cloud.models", "deepseek-flash");
        assertEquals(List.of("deepseek-flash"), HostedPlatforms.models(null).stream().map(CloudModel::id).toList());
        assertNull(HostedPlatforms.modelById("deepseek-v4-pro"), "a named model outside the list is not honoured");
        assertEquals("deepseek-flash", HostedPlatforms.modelById("deepseek-flash").id());
        String doc = new ConsultTool().getDocumentation();
        assertTrue(doc.contains("deepseek/deepseek-flash"), doc);
        assertFalse(doc.contains("deepseek-v4-pro"), "the planner is never offered it");
    }

    @Test
    void withoutTheLadderBigBrotherNeverListsOrPicksAnOpenAiModel() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        System.setProperty("hermit.cloud.providers", "openai");
        System.setProperty("hermit.openai.apiKey", "test-key");
        System.setProperty("hermit.openai.baseUrl", "http://127.0.0.1:" + closed + "/v1");   // the live-model probe fails fast
        assertFalse(new ConsultTool().getDocumentation().contains("gpt-6"), "OpenAI is reached only through the ladder");
        assertNull(ConsultTool.selectModel("openai", "gpt-6-astra"), "neither by name nor by platform");
    }

    @Test
    void huggingFaceIsAPlatformAndItsGptOssModelIsTheDefault() throws Exception {
        assertEquals("https://router.huggingface.co/v1", HostedPlatforms.baseUrl(HostedPlatforms.HUGGINGFACE));
        assertEquals("openai/gpt-oss-120b:preferred", ConsultTool.DEFAULT_MODEL);
        CloudModel gptOss = HostedPlatforms.modelById(ConsultTool.DEFAULT_MODEL);
        assertEquals(HostedPlatforms.HUGGINGFACE, gptOss.platform(), "not Groq's model of the same served name");
        assertEquals("openai/gpt-oss-120b", HostedPlatforms.servedId(HostedPlatforms.HUGGINGFACE, gptOss.id()));
        assertEquals(HostedPlatforms.HUGGINGFACE, HostedPlatforms.modelById("Qwen/Qwen3.8-27B:preferred").platform(),
                "the Qwen model is still offered");
        // the router lists models without the provider policy after ':'
        assertEquals("Qwen/Qwen3.8-27B", HostedPlatforms.servedId(HostedPlatforms.HUGGINGFACE, "Qwen/Qwen3.8-27B:preferred"));
        assertEquals("qwen/qwen3.6-27b", HostedPlatforms.servedId(HostedPlatforms.GROQ, "qwen/qwen3.6-27b"));

        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        System.setProperty("hermit.cloud.providers", "huggingface");
        System.setProperty("hermit.huggingface.apiKey", "test-key");
        System.setProperty("hermit.huggingface.baseUrl", "http://127.0.0.1:" + closed + "/v1");   // the live probe fails fast
        assertEquals(ConsultTool.DEFAULT_MODEL, ConsultTool.requestedModel(null, "auto"), "auto: the default model");
        assertEquals(ConsultTool.DEFAULT_MODEL, ConsultTool.selectModel("auto", ConsultTool.requestedModel(null, "auto")).id());
        assertNull(ConsultTool.requestedModel(null, "groq"), "a named platform is not overridden by the default model");
        assertEquals("deepseek-flash", ConsultTool.requestedModel("deepseek-flash", "auto"), "a named model wins");
        System.setProperty("hermit.bigbrother.model", "deepseek-flash");
        assertEquals("deepseek-flash", ConsultTool.requestedModel(null, "auto"), "and so does the configured one");
        assertTrue(new ConsultTool().getDocumentation().contains("huggingface/openai/gpt-oss-120b:preferred"));
        assertTrue(new ConsultTool().getDocumentation().contains("huggingface/Qwen/Qwen3.8-27B:preferred"));
        assertTrue(new ConsultTool().endpointLabel().contains("HuggingFace"), new ConsultTool().endpointLabel());
    }

    @Test
    void aDirectBigBrotherCallsItsConfiguredModelEvenWithTheLadderOn() {
        // the rewrite runs keep the escalation ladder for plan authoring, but BigBrother asks one configured model
        assertTrue(ConsultTool.viaLadder(true), "with the ladder on, BigBrother asks the ladder");
        assertFalse(ConsultTool.viaLadder(false));
        System.setProperty("hermit.bigbrother.direct", "true");
        assertTrue(ConsultTool.viaLadder(true), "direct needs a configured model");
        System.setProperty("hermit.bigbrother.model", ConsultTool.DEFAULT_MODEL);
        assertFalse(ConsultTool.viaLadder(true), "direct: the configured model, not the ladder");
    }

    @Test
    void ofDeepSeeksModelsOnlyFlashIsChosenUnlessTheAllowListNamesAnother() throws Exception {
        // a test query ran without the allow-list and auto-routing took deepseek-v4-pro, against the rule to use flash
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        System.setProperty("hermit.cloud.providers", "deepseek");
        System.setProperty("hermit.deepseek.apiKey", "test-key");
        System.setProperty("hermit.deepseek.baseUrl", "http://127.0.0.1:" + closed + "/v1");   // the live probe fails fast
        assertEquals("deepseek-flash", ConsultTool.selectModel("deepseek", null).id());
        assertEquals("deepseek-flash", ConsultTool.selectModel("auto", "deepseek-v4-pro").id(), "not even when named");
        System.setProperty("hermit.cloud.models", "deepseek-flash,deepseek-v4-pro");
        assertEquals("deepseek-v4-pro", ConsultTool.selectModel("auto", "deepseek-v4-pro").id(),
                "an allow-list that names it lets it through");
    }

    @Test
    void buildPromptEmbedsContextWhenPresent() {
        String p = ConsultTool.buildPrompt("answer", "task", "some code");
        assertTrue(p.contains("RELEVANT CONTEXT"));
        assertTrue(p.contains("some code"));
        assertFalse(ConsultTool.buildPrompt("answer", "task", "  ").contains("RELEVANT CONTEXT"));
    }
}
