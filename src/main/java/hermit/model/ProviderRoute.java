package hermit.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Which provider of the Hugging Face router serves a call, and which one takes over when it fails. The owner, 2026-10-26:
 * the providers of GPT-OSS-120B failed one after another in an afternoon (Groq refused, DeepInfra and nscale were paused,
 * Novita was overloaded) while Hermit asked the router for one provider only. A call now tries the model's live providers
 * in order and moves to the next one on a provider failure (403, 404, 408, 425, 429, 5xx, a timeout or a broken
 * connection, or an answer the caller finds unusable, such as an empty reply); a request error (400, 401, 402) is the same
 * on every provider and ends the call.
 *
 * <p>The order, {@code -Dhermit.hf.route}: {@code cheapest} (the default: the lowest price of a call that sends
 * {@code -Dhermit.hf.inputPerOutput} input tokens per output token, 10 by default), {@code fastest} (the highest listed
 * throughput) or {@code preferred} (the router's own choice first, then the cheapest). A model named with a provider
 * ({@code openai/gpt-oss-20b:deepinfra}) tries that provider first. A provider that failed waits at the back of the order
 * for {@code -Dhermit.hf.cooldownSec} (300). The router's catalogue of providers, prices and throughput is read once and
 * kept for half an hour; when it cannot be read, the model is sent as given.
 */
public final class ProviderRoute {

    /** One attempt of a call with a model id that names its provider. */
    public interface Attempt {
        HttpResponse<String> send(String model) throws IOException, InterruptedException;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROUTER = "router.huggingface.co";
    private static final long CATALOGUE_TTL_MS = 30 * 60 * 1000L;
    private static final Map<String, Long> FAILED_UNTIL = new ConcurrentHashMap<>();   // model:provider -> until
    private static volatile Supplier<String> source = ProviderRoute::fetchCatalogue;
    private static volatile JsonNode catalogue;
    private static volatile long catalogueAt;

    private ProviderRoute() {
    }

    /** What makes an answer that came back unusable all the same (an empty reply); null when it is usable. */
    public interface Check {
        String problem(HttpResponse<String> resp) throws IOException;
    }

    /** Sends through {@code attempt}, provider after provider, when {@code baseUrl} is the router; once otherwise. */
    public static HttpResponse<String> send(String baseUrl, String model, Attempt attempt)
            throws IOException, InterruptedException {
        return send(baseUrl, model, attempt, resp -> null);
    }

    /**
     * As {@link #send(String, String, Attempt)}, and an answer (HTTP 200) in which {@code check} finds a problem is that
     * provider's failure too: the call moves to the next provider, and the last provider's answer is returned as it is.
     * The owner, 2026-10-27: GPT-OSS-120B answered empty on 17% of Novita's calls and 30% of nscale's, against 1.5% of
     * DeepInfra's.
     */
    public static HttpResponse<String> send(String baseUrl, String model, Attempt attempt, Check check)
            throws IOException, InterruptedException {
        List<String> candidates = baseUrl != null && baseUrl.contains(ROUTER) ? candidates(model) : List.of(model);
        for (int i = 0; i < candidates.size(); i++) {
            String m = candidates.get(i);
            boolean last = i == candidates.size() - 1;
            try {
                HttpResponse<String> resp = attempt.send(m);
                String problem = providerFailure(resp.statusCode()) ? "HTTP " + resp.statusCode()
                        : resp.statusCode() == 200 && !last ? check.problem(resp) : null;
                if (last || problem == null) {
                    return resp;
                }
                failed(m, problem, candidates.get(i + 1));
            } catch (IOException e) {
                if (last) {
                    throw e;
                }
                failed(m, e.getClass().getSimpleName(), candidates.get(i + 1));
            }
        }
        throw new IllegalStateException("no provider to try for " + model);
    }

    /** The model ids to try, each naming its provider, in the order of the route; the model as given when unknown. */
    static List<String> candidates(String model) {
        String base = model;
        String suffix = "";
        int colon = model.lastIndexOf(':');
        if (colon > 0) {
            base = model.substring(0, colon);
            suffix = model.substring(colon + 1);
        }
        JsonNode providers = providersOf(base);
        if (providers == null) {
            return List.of(model);
        }
        String route = System.getProperty("hermit.hf.route", "cheapest");
        if (suffix.equals("cheapest") || suffix.equals("fastest")) {
            route = suffix;
        }
        double inputPerOutput = Double.parseDouble(System.getProperty("hermit.hf.inputPerOutput", "10"));
        Comparator<JsonNode> order = route.equals("fastest")
                ? Comparator.comparingDouble(p -> -p.path("throughput").asDouble(-1))
                : Comparator.comparingDouble(p -> price(p, inputPerOutput));
        List<JsonNode> live = new ArrayList<>();
        providers.forEach(p -> {
            if ("live".equals(p.path("status").asText())) {
                live.add(p);
            }
        });
        live.sort(order);
        List<String> ids = new ArrayList<>();
        String named = suffix.isEmpty() || suffix.equals("cheapest") || suffix.equals("fastest") ? null : suffix;
        if (named != null && (!named.equals("preferred") || route.equals("preferred"))) {
            ids.add(base + ":" + named);
        }
        for (JsonNode p : live) {
            String id = base + ":" + p.path("provider").asText();
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        // a provider that failed a moment ago waits at the back, in its own order
        long now = System.currentTimeMillis();
        List<String> resting = ids.stream().filter(id -> FAILED_UNTIL.getOrDefault(id, 0L) > now).toList();
        List<String> out = new ArrayList<>(ids.stream().filter(id -> !resting.contains(id)).toList());
        out.addAll(resting);
        return out;
    }

    /**
     * A call's timeout (the owner, 2026-10-26: cheap providers write 30-40 tokens a second, as slow as a local model). On
     * the router: twice the time the chosen provider takes to write {@code maxTokens} at its listed throughput, plus two
     * minutes, between {@code -Dhermit.hostedTimeoutMinSec} (240) and {@code -Dhermit.hostedTimeoutMaxSec} (900); the
     * ceiling when the provider lists no speed. Elsewhere {@code fallback}.
     */
    public static Duration timeoutFor(String baseUrl, String model, int maxTokens, Duration fallback) {
        if (baseUrl == null || !baseUrl.contains(ROUTER)) {
            return fallback;
        }
        long min = Long.getLong("hermit.hostedTimeoutMinSec", 240L);
        long max = Long.getLong("hermit.hostedTimeoutMaxSec", 900L);
        double tokensPerSecond = throughputOf(model);
        if (tokensPerSecond <= 0) {
            return Duration.ofSeconds(max);
        }
        long seconds = (long) (2 * maxTokens / tokensPerSecond) + 120;
        return Duration.ofSeconds(Math.max(min, Math.min(max, seconds)));
    }

    private static double throughputOf(String model) {
        int colon = model == null ? -1 : model.lastIndexOf(':');
        JsonNode providers = colon > 0 ? providersOf(model.substring(0, colon)) : null;
        if (providers != null) {
            for (JsonNode p : providers) {
                if (model.substring(colon + 1).equals(p.path("provider").asText())) {
                    return p.path("throughput").asDouble(-1);
                }
            }
        }
        return -1;
    }

    /** Replaces the catalogue's source (tests); {@code null} restores the router's. A new source starts with no failures. */
    static void useCatalogue(Supplier<String> s) {
        source = s == null ? ProviderRoute::fetchCatalogue : s;
        catalogue = null;
        catalogueAt = 0;
        FAILED_UNTIL.clear();
    }

    private static boolean providerFailure(int status) {
        return status == 403 || status == 404 || status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static void failed(String model, String why, String next) {
        FAILED_UNTIL.put(model, System.currentTimeMillis() + Long.getLong("hermit.hf.cooldownSec", 300L) * 1000);
        System.out.println("--- " + model + " failed (" + why + "); trying " + next + " ---");
    }

    private static double price(JsonNode provider, double inputPerOutput) {
        JsonNode pricing = provider.path("pricing");
        if (!pricing.has("input") || !pricing.has("output")) {
            return Double.MAX_VALUE;
        }
        return inputPerOutput * pricing.path("input").asDouble() + pricing.path("output").asDouble();
    }

    private static JsonNode providersOf(String base) {
        long now = System.currentTimeMillis();
        if (catalogue == null || now - catalogueAt > CATALOGUE_TTL_MS) {
            try {
                catalogue = MAPPER.readTree(source.get());
            } catch (IOException | RuntimeException e) {
                return null;
            }
            catalogueAt = now;
        }
        for (JsonNode m : catalogue.path("data")) {
            if (base.equals(m.path("id").asText())) {
                return m.path("providers");
            }
        }
        return null;
    }

    private static String fetchCatalogue() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://" + ROUTER + "/v1/models"))
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("the router's catalogue answered HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (IOException e) {
            throw new IllegalStateException("the router's catalogue could not be read: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading the router's catalogue", e);
        }
    }
}
