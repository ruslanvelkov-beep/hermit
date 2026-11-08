package hermit.text.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import hermit.text.TextLens;
import hermit.text.model.AnnotationDocument;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TextLens's resident server: the annotation and the findings over HTTP, its refusals of bad requests, its end. */
class TextLensServerTest {

    static final Instant CLOCK = Instant.parse("2026-10-28T10:00:00Z");
    static final ZoneId UTC = ZoneId.of("UTC");
    static final String RELEASE = "{\"text\": \"The release was on Mon Oct 20, 2026.\", \"clock\": "
            + "\"2026-10-28T10:00:00Z\", \"zone\": \"UTC\"}";

    static HttpResponse<String> post(int port, String path, String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    static TextLensServer.Request request(String body) throws Exception {
        return TextLensServer.request(TextLensServer.JSON.readTree(body));
    }

    @Test
    void theServerAnnotatesAndChecksOverHttp() throws Exception {
        TextLensServer server = new TextLensServer(TextLens.standard(), 0, 0);
        try {
            AnnotationDocument document = TextLens.standard().annotate("The release was on Mon Oct 20, 2026.",
                    List.of(), CLOCK, UTC);
            HttpResponse<String> annotated = post(server.port(), "/annotate", RELEASE);
            assertEquals(200, annotated.statusCode());
            assertEquals(document, AnnotationDocument.fromJson(annotated.body()));
            HttpResponse<String> checked = post(server.port(), "/check", RELEASE);
            assertEquals(200, checked.statusCode());
            JsonNode findings = TextLensServer.JSON.readTree(checked.body()).path("findings");
            assertEquals(1, findings.size());
            assertEquals("date", findings.get(0).path("checker").asText());
            assertEquals("October 20, 2026 was a Tuesday, not a Monday.", findings.get(0).path("sentence").asText());
            List<String> targets = new ArrayList<>();
            findings.get(0).path("targets").forEach(target -> targets.add(target.asText()));
            assertEquals(document.checks().get(0).targets(), targets);
            assertEquals("{\"requests\": 2}", get(server.port(), "/stats").body());
        } finally {
            server.stop();
        }
    }

    @Test
    void aBadRequestIsRefused() throws Exception {
        TextLensServer server = new TextLensServer(TextLens.standard(), 0, 0);
        try {
            assertEquals(400, post(server.port(), "/annotate", "not json").statusCode());
            assertEquals(400, post(server.port(), "/check", "{\"sources\": []}").statusCode());
            HttpResponse<String> clock = post(server.port(), "/check", "{\"text\": \"x\", \"clock\": \"yesterday\"}");
            assertEquals(400, clock.statusCode());
            assertTrue(clock.body().contains("clock"), clock.body());
            assertEquals(405, get(server.port(), "/annotate").statusCode());
            assertEquals(404, get(server.port(), "/nothing").statusCode());
            assertEquals("{\"requests\": 0}", get(server.port(), "/stats").body());
        } finally {
            server.stop();
        }
    }

    @Test
    void aRequestTakesItsDefaults() throws Exception {
        TextLensServer.Request plain = request("{\"text\": \"x\"}");
        assertEquals(List.of("x", List.of(), ZoneId.systemDefault()), List.of(plain.text(), plain.sources(),
                plain.zone()));
        assertTrue(Duration.between(plain.clock(), Instant.now()).abs().toMinutes() < 1, "the clock is now");
        assertEquals(new TextLensServer.Request("x", List.of("a", "b"), CLOCK, UTC), request("{\"text\": \"x\", "
                + "\"sources\": [\"a\", \"b\"], \"clock\": \"2026-10-28T10:00:00Z\", \"zone\": \"UTC\"}"));
        assertEquals("text: a string is required",
                assertThrows(IllegalArgumentException.class, () -> request("{\"text\": 3}")).getMessage());
        assertEquals("sources: an array of strings is required",
                assertThrows(IllegalArgumentException.class, () -> request("{\"text\": \"x\", \"sources\": [1]}"))
                        .getMessage());
        assertEquals("zone: a zone id is required",
                assertThrows(IllegalArgumentException.class, () -> request("{\"text\": \"x\", \"zone\": \"Mars\"}"))
                        .getMessage());
    }

    @Test
    void shutdownStopsTheServer() throws Exception {
        TextLensServer server = new TextLensServer(TextLens.standard(), 0, 0);
        assertEquals(200, get(server.port(), "/shutdown").statusCode());
        assertTimeoutPreemptively(Duration.ofSeconds(10), server::awaitShutdown);
    }

    @Test
    void theCommandAsksARunningServer() throws Exception {
        TextLensServer server = new TextLensServer(TextLens.standard(), 0, 0);
        try {
            String port = String.valueOf(server.port());
            TextLensCommandTest.Run check = TextLensCommandTest.run(TextLensCommandTest.args("check", "--server", port),
                    "The release was on Mon Oct 20, 2026.");
            assertEquals(1, check.code());
            assertEquals("date: October 20, 2026 was a Tuesday, not a Monday.\n", check.out());
            TextLensCommandTest.Run tag = TextLensCommandTest.run(TextLensCommandTest.args("tag", "--server", port),
                    "Just sort files by size.");
            assertEquals(TextLensCommandTest.run(TextLensCommandTest.args("tag"), "Just sort files by size."), tag);
            assertEquals("{\"requests\": 2}", get(server.port(), "/stats").body());
        } finally {
            server.stop();
        }
    }
}
