package hermit.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import hermit.model.DecisionClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The adapter carries each question to the decision service's client and each answer back, against a local server. */
class DecisionReviewerTest {

    @Test
    void questionsAndAnswersAreCarriedAcrossOneToOne() throws Exception {
        String[] body = new String[1];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", ex -> {
            body[0] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = ("{\"answers\":{\"same\":{\"type\":\"noul\",\"noul\":0.8},\"kind\":{\"type\":\"choice\","
                    + "\"choice\":\"read\",\"confidence\":0.6,\"probabilities\":{\"read\":0.6,\"write\":0.4}}}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            StructuredReviewer reviewer = new DecisionReviewer(DecisionClient.local(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone"));
            Map<String, StructuredReviewer.Question> questions = new LinkedHashMap<>();
            questions.put("same", new StructuredReviewer.Question.YesNo("Is it the same action?"));
            questions.put("kind", new StructuredReviewer.Question.Choice("Which kind?", Map.of("read", "a read", "write", "a write")));
            StructuredReviewer.Result r = reviewer.decide("the state", questions);

            assertTrue(r.ok(), r.error());
            assertEquals(0.8, r.answers().get("same").yes(), 1e-9);
            assertEquals("read", r.answers().get("kind").choice());
            assertEquals(0.4, r.answers().get("kind").probabilities().get("write"), 1e-9);
            JsonNode sent = new ObjectMapper().readTree(body[0]);
            assertEquals("noul", sent.path("questions").path("same").path("type").asText());
            assertEquals("choice", sent.path("questions").path("kind").path("type").asText());
            JsonNode criteria = sent.path("questions").path("kind").path("criteria");
            assertTrue(criteria.has("read") && criteria.has("write"), criteria.toString());
        } finally {
            server.stop(0);
        }
    }
}
