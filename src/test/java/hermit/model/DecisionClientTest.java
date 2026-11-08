package hermit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Network-free tests for DecisionClient against a scripted mock server. */
class DecisionClientTest {
    static HttpServer server;
    static volatile String scriptedBody = "";
    static volatile int scriptedStatus = 200;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", ex -> {
            byte[] out = scriptedBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(scriptedStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @AfterEach
    void clearKey() {
        System.clearProperty("hermit.jev.apiKey");
        System.clearProperty("hermit.inference.localOnly");
    }

    private DecisionClient client() {
        System.setProperty("hermit.jev.apiKey", "test-key");
        return new DecisionClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone");
    }

    @Test
    void parsesANoulAnswer() {
        scriptedStatus = 200;
        scriptedBody = "{\"model\":\"jev-1.13.0\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.92}}}";
        Double p = client().noul("some state", "is this true");
        assertEquals(0.92, p, 0.0001);
    }

    @Test
    void parsesAChoiceAnswerWithProbabilitiesAndConfidence() {
        scriptedStatus = 200;
        scriptedBody = "{\"model\":\"jev-1.13.0\",\"answers\":{\"verdict\":{\"type\":\"choice\","
                + "\"choice\":\"reject\",\"confidence\":1.0,"
                + "\"probabilities\":{\"allow\":0.0,\"reject\":1.0,\"escalate\":0.0}}}}";
        DecisionClient.Result r = client().decide("state", Map.of("verdict",
                new DecisionClient.Question.Choice("what next", Map.of("allow", "a", "reject", "r", "escalate", "e"))));
        assertTrue(r.ok(), r.error());
        DecisionClient.Answer a = r.answers().get("verdict");
        assertEquals("reject", a.choice());
        assertEquals(1.0, a.confidence(), 0.0001);
        assertEquals(1.0, a.probabilities().get("reject"), 0.0001);
    }

    @Test
    void aNonTwoHundredStatusIsSurfacedAsAnError() {
        scriptedStatus = 401;
        scriptedBody = "{\"error\":\"invalid api key\"}";
        DecisionClient.Result r = client().decide("state", Map.of("q", new DecisionClient.Question.Noul("x")));
        assertFalse(r.ok());
        assertTrue(r.error().contains("401"), r.error());
        assertTrue(r.error().contains("invalid api key"), r.error());
    }

    @Test
    void noKeyConfiguredIsANoopNotACrash() {
        // Forces DecisionConfig.apiKey() to short-circuit to null even though this machine has a real
        // jev.api.key file at the repo root (the gradle test working directory) that the plain
        // file-fallback lookup would otherwise pick up.
        System.setProperty("hermit.inference.localOnly", "true");
        DecisionClient.Result r = new DecisionClient("http://127.0.0.1:1/nope")
                .decide("state", Map.of("q", new DecisionClient.Question.Noul("x")));
        assertFalse(r.ok());
        assertTrue(r.error().contains("No Jev API key"), r.error());
    }

    @Test
    void isYesRespectsTheThreshold() {
        var answer = new DecisionClient.Answer("noul", 0.65, null, null, Map.of(), null);
        assertTrue(answer.isYes(0.6));
        assertFalse(answer.isYes(0.7));
    }

    @Test
    void aScoreQuestionSendsItsOrderedCriteriaList() {
        scriptedStatus = 200;
        scriptedBody = "{\"model\":\"jev-1.13.0\",\"answers\":{\"frustration\":{\"type\":\"score\","
                + "\"score\":1,\"confidence\":1.0,\"legend\":{\"0\":\"calm\",\"1\":\"upset\"}}}}";
        DecisionClient.Result r = client().decide("state", Map.of("frustration",
                new DecisionClient.Question.Score("how upset", List.of("calm", "upset"))));
        assertTrue(r.ok(), r.error());
        assertEquals(1, r.answers().get("frustration").score());
    }
}
