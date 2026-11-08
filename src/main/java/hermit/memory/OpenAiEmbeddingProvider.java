package hermit.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Real local embeddings via an embedding server's OpenAI-compatible {@code /v1/embeddings}
 * (default model {@code text-embedding-nomic-embed-text-v1.5}). The default model wants task
 * prefixes - {@code search_document:} for stored snippets, {@code search_query:} for
 * queries - which this applies from the {@link Mode}. Vectors are L2-normalized on
 * return. Configure with {@code -Dhermit.embed.baseUrl} / {@code -Dhermit.embed.model}.
 */
public final class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private final String baseUrl;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile int dim = -1;

    public OpenAiEmbeddingProvider() {
        this(System.getProperty("hermit.embed.baseUrl", "http://localhost:1234/v1"),
                System.getProperty("hermit.embed.model", "text-embedding-nomic-embed-text-v1.5"));
    }

    public OpenAiEmbeddingProvider(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /** Reachable and serving an embeddings endpoint? Used to pick real vs fallback embedder. */
    public boolean isAvailable() {
        try {
            float[] v = embed("ping", Mode.QUERY);
            return v.length > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public float[] embed(String text, Mode mode) {
        String prefix = mode == Mode.QUERY ? "search_query: " : "search_document: ";
        try {
            Map<String, Object> body = Map.of("model", model, "input", prefix + (text == null ? "" : text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("embeddings HTTP " + resp.statusCode());
            }
            JsonNode arr = mapper.readTree(resp.body()).path("data").path(0).path("embedding");
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = (float) arr.get(i).asDouble();
            }
            normalize(v);
            dim = v.length;
            return v;
        } catch (Exception e) {
            throw new RuntimeException("embedding server embedding failed: " + e.getMessage(), e);
        }
    }

    private static void normalize(float[] v) {
        double n = 0.0;
        for (float x : v) {
            n += (double) x * x;
        }
        n = Math.sqrt(n);
        if (n > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / n);
            }
        }
    }

    @Override
    public int dimension() {
        return dim > 0 ? dim : 768;   // default dimension; learned exactly on first embed
    }

    @Override
    public String id() {
        return "openai-compatible:" + model;
    }

    /** Picks the real embedding server provider if reachable, else the deterministic fallback. */
    public static EmbeddingProvider available() {
        OpenAiEmbeddingProvider real = new OpenAiEmbeddingProvider();
        return real.isAvailable() ? real : new HashingEmbedder();
    }
}
