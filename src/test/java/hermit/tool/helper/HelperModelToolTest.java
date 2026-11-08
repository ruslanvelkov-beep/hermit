package hermit.tool.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import hermit.model.HelperEndpoint;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/** Pure, network-free tests for HelperModelTool: prompt shaping per mode, the
 * auto-detect (unreachable endpoint => unavailable), and the clean decline when
 * the remote box is offline. No dependency on the helper's machine being up. */
class HelperModelToolTest {

    /** A config pointed at a closed local port - the probe must fail fast. */
    private HelperModelTool offlineTool() {
        return new HelperModelTool(new HelperEndpoint(
                "unreachable", "http://127.0.0.1:1/v1", "some-model", null));
    }

    @Test
    void unreachableEndpointIsDetectedUnavailable() {
        assertFalse(offlineTool().isAvailable());
    }

    @Test
    void executeDeclinesCleanlyWhenUnavailable() {
        Map<String, Object> r = offlineTool().execute(Map.of("task", "hello"));
        assertEquals("Error", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).contains("not reachable"), r.toString());
    }

    @Test
    void missingTaskIsAnError() {
        Map<String, Object> r = offlineTool().execute(Map.of());
        assertEquals("Error", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).contains("task is required"), r.toString());
    }

    @Test
    void buildPromptShapesEachMode() {
        String answer = HelperModelTool.buildPrompt("answer", "2+2?", null);
        assertEquals("2+2?", answer);

        String refine = HelperModelTool.buildPrompt("refinePlan", "clone and build repo X", "step1; step2");
        assertTrue(refine.toLowerCase().contains("planning reviewer"), refine);
        assertTrue(refine.contains("clone and build repo X"), refine);
        assertTrue(refine.contains("step1; step2"), refine);

        String critique = HelperModelTool.buildPrompt("critique", "this URL is invalid", "we got a 404");
        assertTrue(critique.toLowerCase().contains("sceptical critic"), critique);
        assertTrue(critique.contains("this URL is invalid"), critique);
    }

    @Test
    void endpointLabelShowsConfig() {
        HelperModelTool t = new HelperModelTool(new HelperEndpoint(
                "helper (qwen)", "http://10.0.0.5:1234/v1", "qwen2.5-coder-14b-instruct-mlx", null));
        assertTrue(t.endpointLabel().contains("helper (qwen)"));
        assertTrue(t.endpointLabel().contains("10.0.0.5"));
    }

    @Test
    void defaultConfigTargetsTheLocalQwen14b() {
        HelperEndpoint c = HelperEndpoint.defaultConfig();
        assertTrue(c.baseUrl().contains("127.0.0.1:1234"), c.baseUrl());
        assertEquals("qwen2.5-coder-14b-instruct-mlx", c.model());
    }

    @Test
    void aRateLimitedReplyIsRetriedOnceAfterRetryAfter() throws Exception {
        // Groq round prep: a 429 with Retry-After must not lose the round - one paced retry
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/v1/chat/completions", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body;
            if (hits.incrementAndGet() == 1) {
                ex.getResponseHeaders().add("Retry-After", "1");
                body = "{\"error\":\"rate limited\"}".getBytes();
                ex.sendResponseHeaders(429, body.length);
            } else {
                body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes();
                ex.sendResponseHeaders(200, body.length);
            }
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            HelperModelTool t = new HelperModelTool(new HelperEndpoint("cloud", base, "big-model", "k-123"));
            long t0 = System.currentTimeMillis();
            assertEquals("ok", t.complete("hi", 10));
            assertEquals(2, hits.get());
            assertTrue(System.currentTimeMillis() - t0 >= 900, "waited for Retry-After");
            assertEquals("Bearer k-123", auth.get());
        } finally {
            server.stop(0);
        }
        assertEquals(15_000L, HelperModelTool.retryAfterMs(new FakeResponse("")));
        assertEquals(60_000L, HelperModelTool.retryAfterMs(new FakeResponse("600")));
        assertEquals(2_500L, HelperModelTool.retryAfterMs(new FakeResponse("2.5")));
    }

    /** Only the headers matter to retryAfterMs. */
    private record FakeResponse(String retryAfter) implements HttpResponse<String> {
        public int statusCode() { return 429; }
        public HttpRequest request() { return null; }
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() {
            return HttpHeaders.of(retryAfter.isEmpty() ? Map.of() : Map.of("Retry-After", List.of(retryAfter)),
                    (a, b) -> true);
        }
        public String body() { return ""; }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return null; }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    @Test
    void pacingKeepsRequestsApart() {
        System.setProperty("hermit.remote.minIntervalMs", "300");
        try {
            HelperModelTool.pace(true);
            long t0 = System.currentTimeMillis();
            HelperModelTool.pace(true);
            assertTrue(System.currentTimeMillis() - t0 >= 250, "second cloud call waited");
            long t1 = System.currentTimeMillis();
            HelperModelTool.pace(false);
            assertTrue(System.currentTimeMillis() - t1 < 200, "a LAN endpoint is not paced");
            assertTrue(HelperModelTool.isCloud("https://api.groq.com/openai/v1"));
            assertFalse(HelperModelTool.isCloud("http://10.0.0.5:1234/v1"));
        } finally {
            System.clearProperty("hermit.remote.minIntervalMs");
        }
    }

    @Test
    void aCloudBaseUrlTakesItsKeyFromTheCloudKeyStore() {
        System.setProperty("hermit.form.baseUrl", "https://api.groq.com/openai/v1");
        System.setProperty("hermit.form.model", "openai/gpt-oss-120b");
        System.setProperty("hermit.groq.apiKey", "test-groq-key");
        try {
            HelperEndpoint c = HelperEndpoint.named("form", null, null, null);
            assertEquals("test-groq-key", c.apiKey());
            assertEquals("openai/gpt-oss-120b", c.model());
            assertTrue(c.label().contains("api.groq.com"), c.label());
        } finally {
            System.clearProperty("hermit.form.baseUrl");
            System.clearProperty("hermit.form.model");
            System.clearProperty("hermit.groq.apiKey");
        }
        // no endpoint configured under the prefix → no config
        assertEquals(null, HelperEndpoint.named("nothing-here", null, null, null));
    }
}
