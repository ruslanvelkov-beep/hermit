package hermit.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Queries the current model's context window (in tokens) from the local server's
 * native REST API (/api/v0/models exposes loaded/max context length), so the
 * agent knows the real limit instead of assuming. Falls back to 128k if the
 * endpoint or field is unavailable.
 */
public final class ContextWindow {

    private static final int FALLBACK_TOKENS = 131_072;

    private ContextWindow() {}

    /** Context length in tokens for the currently-selected model, or a 128k fallback. */
    public static int tokens() {
        // An OpenAI-style server without the native /api/v0/models would always get the 128k
        // fallback; the run's real budget is configured instead.
        Integer configured = Integer.getInteger("hermit.contextTokens");
        if (configured != null && configured > 0) {
            return configured;
        }
        try {
            // Native endpoint sits at the host root, not under /v1.
            String base = ModelEndpoint.baseUrl().replaceAll("/v1/?$", "");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v0/models"))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode data = new ObjectMapper().readTree(resp.body()).path("data");
                for (JsonNode m : data) {
                    if (ModelEndpoint.model().equals(m.path("id").asText(""))) {
                        int loaded = m.path("loaded_context_length").asInt(0);
                        int max = m.path("max_context_length").asInt(0);
                        int ctx = loaded > 0 ? loaded : max;
                        if (ctx > 0) {
                            return ctx;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to the default
        }
        return FALLBACK_TOKENS;
    }
}
