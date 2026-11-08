package hermit.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import hermit.checkpoint.Checkpoint;
import hermit.model.DecisionClient;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Network-free tests for the optional reviewer against a scripted local decision server. */
class OptionalReviewerTest {

    static HttpServer server;
    static volatile String lastBody = "";
    static volatile String lastAuthorization;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", ex -> {
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuthorization = ex.getRequestHeaders().getFirst("Authorization");
            byte[] out = ("{\"model\":\"openthai-systemone-mlx-8bit\",\"answers\":{\"grounding\":{\"type\":\"score\",\"score\":2.0,"
                    + "\"confidence\":0.3,\"probabilities\":{\"0\":0.1,\"1\":0.2,\"2\":0.3,\"3\":0.4}}},\"usage\":{\"input_tokens\":42}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
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
    void clearProperties() {
        for (String p : List.of("hermit.shadow.openthai", "hermit.shadow.jev", "hermit.jev.apiKey", "hermit.inference.localOnly",
                "hermit.inference.allowHosted")) {
            System.clearProperty(p);
        }
    }

    static String localUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone";
    }

    @Test
    void theStateIsTheMeasuredOneWithTheEvidenceTailAndTheLedgerOnlyWhenThereIsOne() {
        String evidence = "OLD-START" + "x".repeat(OptionalReviewer.EVIDENCE_CAP) + "RECENT-END";
        String state = OptionalReviewer.state("  the request \n", evidence, "C1 [SUPPORTED] (request) a claim", " the answer ");
        assertTrue(state.startsWith("REQUEST:\nthe request\n\nEVIDENCE GATHERED (most recent part):\n"), state.substring(0, 80));
        assertFalse(state.contains("OLD-START"), "only the evidence's last characters are shown");
        assertTrue(state.contains("RECENT-END\n\nCLAIMS LEDGER:\nC1 [SUPPORTED] (request) a claim\n\nPROPOSED FINAL ANSWER:\nthe answer"));
        assertFalse(OptionalReviewer.state("r", "e", "  ", "a").contains("CLAIMS LEDGER"), "no ledger section without a ledger");
    }

    @Test
    void itIsOffUnlessASourceIsConfiguredAndThenRecordsNothing() throws Exception {
        OptionalReviewer critic = OptionalReviewer.fromSystemProperties();
        assertFalse(critic.enabled());
        assertNull(critic.start("state"));
        Path step = Files.createTempDirectory("hermit-shadow-off");
        critic.record(step, null, new Checkpoint.Decision(Checkpoint.Control.CONTINUE, null));
        assertFalse(Files.exists(step.resolve("shadow.json")));
    }

    @Test
    void theLocalSourceTakesOnlyALoopbackUrl() {
        assertTrue(DecisionClient.isLoopback("http://127.0.0.1:53690/v1/systemone"));
        assertTrue(DecisionClient.isLoopback("http://localhost:53690/v1/systemone"));
        assertTrue(DecisionClient.isLoopback("http://[::1]:53690/v1/systemone"));
        assertFalse(DecisionClient.isLoopback("http://192.0.2.10:53690/v1/systemone"), "another machine is not this one");
        assertFalse(DecisionClient.isLoopback("https://api.typesafe.ai/v1/systemone"));
        assertFalse(DecisionClient.isLoopback("not a url"));
        assertThrows(IllegalArgumentException.class, () -> DecisionClient.local("https://api.typesafe.ai/v1/systemone"));
        System.setProperty("hermit.shadow.openthai", "http://192.0.2.10:53690/v1/systemone");
        assertFalse(OptionalReviewer.fromSystemProperties().enabled(), "a non-loopback URL is ignored");
    }

    @Test
    void eachSourceHasItsOwnSwitch() {
        System.setProperty("hermit.jev.apiKey", "test-key");
        assertFalse(OptionalReviewer.fromSystemProperties().enabled(), "a Jev key alone does not send answers to Jev");
        System.setProperty("hermit.shadow.jev", "true");
        System.setProperty("hermit.shadow.openthai", localUrl());
        assertEquals(List.of("openthai", "jev"), OptionalReviewer.fromSystemProperties().sourceNames());
    }

    @Test
    void theScoreAndTheDecisionAreRecordedAfterTheDecisionAndTheLocalCallCarriesNoKey() throws Exception {
        System.setProperty("hermit.jev.apiKey", "test-key");
        System.setProperty("hermit.shadow.openthai", localUrl());
        OptionalReviewer critic = OptionalReviewer.fromSystemProperties();
        Path step = Files.createTempDirectory("hermit-shadow-step");
        OptionalReviewer.Pending pending = critic.start(OptionalReviewer.state("the request", "the evidence", "", "the answer"));
        assertFalse(Files.exists(step.resolve("shadow.json")), "nothing is written before the decision");
        critic.record(step, pending, new Checkpoint.Decision(Checkpoint.Control.REJECT, "point 2 has no line"));

        JsonNode doc = new ObjectMapper().readTree(step.resolve("shadow.json").toFile());
        assertEquals("grounding", doc.path("question").asText());
        assertEquals("reject", doc.path("decision").asText());
        assertEquals("point 2 has no line", doc.path("note").asText());
        assertEquals(2.0, doc.path("openthai").path("score").asDouble(), 1e-9, doc.toString());
        assertEquals(0.4, doc.path("openthai").path("probabilities").path("3").asDouble(), 1e-9);
        assertNull(lastAuthorization, "the local server is sent no key");
        JsonNode request = new ObjectMapper().readTree(lastBody);
        assertEquals("score", request.path("questions").path("grounding").path("type").asText());
        assertEquals(4, request.path("questions").path("grounding").path("criteria").size());
        assertTrue(request.path("state").asText().contains("PROPOSED FINAL ANSWER:\nthe answer"));
    }

    @Test
    void aSourceThatCannotAnswerRecordsWhyAndJevSendsNothingWhenItsHostIsNotAllowed() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        System.setProperty("hermit.shadow.openthai", "http://127.0.0.1:" + closed + "/v1/systemone");
        System.setProperty("hermit.shadow.jev", "true");
        System.setProperty("hermit.jev.apiKey", "test-key");
        System.setProperty("hermit.inference.localOnly", "true");   // api.typesafe.ai is not allow-listed
        OptionalReviewer critic = OptionalReviewer.fromSystemProperties();
        Path step = Files.createTempDirectory("hermit-shadow-fail");
        critic.record(step, critic.start("state"), new Checkpoint.Decision(Checkpoint.Control.CONTINUE, null));

        JsonNode doc = new ObjectMapper().readTree(step.resolve("shadow.json").toFile());
        assertEquals("continue", doc.path("decision").asText());
        assertTrue(doc.path("openthai").path("error").asText().startsWith("Jev call failed"), doc.toString());
        assertEquals("No Jev API key configured", doc.path("jev").path("error").asText(), "no key, so no call");
    }

    @Test
    void theScoreIsTheExpectedLevel() {
        assertEquals(2.0, OptionalReviewer.expectedLevel(Map.of("0", 0.1, "1", 0.2, "2", 0.3, "3", 0.4)), 1e-9);
        assertEquals(0.12, OptionalReviewer.expectedLevel(Map.of("0", 0.88, "1", 0.12, "2", 0.0, "3", 0.0)), 1e-9);
    }
}
