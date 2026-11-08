package hermit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.tool.helper.HelperModelToolTestAccess;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A client pointed at the run's own model asks it with the run's settings, and reasoning is not its answer.
 */
class ModelProfileTest {

    @AfterEach
    void clear() {
        for (String p : new String[] {"hermit.remote.useProfile", "hermit.temperature", "hermit.topP", "hermit.topK",
                "hermit.minP", "hermit.presencePenalty", "hermit.enableThinking", "hermit.reasoningFallback",
                "hermit.requestTimeoutSec"}) {
            System.clearProperty(p);
        }
    }

    private static void profileB() {
        System.setProperty("hermit.temperature", "0.7");
        System.setProperty("hermit.topP", "0.8");
        System.setProperty("hermit.topK", "20");
        System.setProperty("hermit.enableThinking", "false");
        System.setProperty("hermit.requestTimeoutSec", "900");
    }

    @Test
    void remoteBodyIsUntouchedWithoutTheOptIn() {
        profileB();
        Map<String, Object> body = new LinkedHashMap<>();
        ModelProfile.applyToRemote(body);
        assertTrue(body.isEmpty(), body.toString());
        assertEquals(Duration.ofMinutes(3), ModelProfile.remoteTimeout(Duration.ofMinutes(3)));
    }

    @Test
    void remoteBodyCarriesTheRunProfileWhenOptedIn() {
        profileB();
        System.setProperty("hermit.remote.useProfile", "true");
        Map<String, Object> body = new LinkedHashMap<>();
        ModelProfile.applyToRemote(body);
        assertEquals(0.7, body.get("temperature"));
        assertEquals(0.8, body.get("top_p"));
        assertEquals(20, body.get("top_k"));
        assertEquals(false, body.get("enable_thinking"));
        assertFalse(body.containsKey("min_p"));
        assertEquals(Duration.ofSeconds(900), ModelProfile.remoteTimeout(Duration.ofMinutes(3)));
    }

    @Test
    void reasoningOnlyCompletionIsNoAnswerWhenTheFallbackIsOff() {
        String json = "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"thinking\"}}]}";
        assertEquals("thinking", HelperModelToolTestAccess.completionContent(json), "default keeps the old fallback");
        System.setProperty("hermit.reasoningFallback", "false");
        assertEquals("", HelperModelToolTestAccess.completionContent(json));
    }

    @Test
    void aHostedCallKeepsTheFourMinuteCeilingWhileALocalCallTakesTheRunsTimeout() {
        profileB();
        System.setProperty("hermit.remote.useProfile", "true");
        String hosted = HostedPlatforms.baseUrl(HostedPlatforms.HUGGINGFACE);
        String local = "http://127.0.0.1:8080/v1";
        assertEquals(Duration.ofSeconds(900), ModelProfile.forEndpoint(local, Duration.ofSeconds(900)));
        assertEquals(Duration.ofSeconds(240), ModelProfile.forEndpoint(hosted, Duration.ofSeconds(900)));
        assertEquals(Duration.ofSeconds(240), ModelProfile.forEndpoint(hosted + "/", Duration.ofSeconds(900)));
        assertEquals(Duration.ofSeconds(60), ModelProfile.forEndpoint(hosted, Duration.ofSeconds(60)));
        assertEquals(Duration.ofSeconds(240), ModelProfile.forEndpoint(hosted, null), "no timeout set is no exemption");
        assertEquals(null, ModelProfile.forEndpoint(local, null), "a local server without a timeout keeps none");
        assertEquals(Duration.ofSeconds(240), ModelProfile.remoteTimeout(Duration.ofMinutes(3), hosted));
        assertEquals(Duration.ofSeconds(900), ModelProfile.remoteTimeout(Duration.ofMinutes(3), local));
    }
}
