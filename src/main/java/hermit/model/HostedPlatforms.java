package hermit.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration for the cloud LLM "big brains" - Cerebras and Groq - that Hermit's
 * small local Qwen/Gemma brain can consult for complex programming, debugging, and
 * planning. Both expose OpenAI-compatible {@code /chat/completions} endpoints.
 *
 * <p>API keys are read (in order) from: the {@code -Dhermit.cerebras.apiKey} /
 * {@code -Dhermit.groq.apiKey} system properties, the {@code CEREBRAS_API_KEY} /
 * {@code GROQ_API_KEY} env vars, or the gitignored {@code .secrets/cloud-keys.properties}
 * file. Keys are NEVER logged.
 *
 * <p>The model catalogue is a curated set (id + routing metadata like a {@code codingScore})
 * but is FILTERED at selection time against each platform's live {@code GET /v1/models}
 * list (cached, see {@link #availableModels}), so a model the provider archives (e.g.
 * Cerebras retired {@code zai-glm-4.7}) is dropped automatically instead of 404-ing.
 * Cerebras and Groq are tracked independently, including their separate remaining-token
 * counters in {@link UsageLedger}.
 */
public final class HostedPlatforms {

    public static final String CEREBRAS = "cerebras";
    public static final String GROQ = "groq";
    public static final String DEEPSEEK = "deepseek";   // paid, no daily token cap, 128k context - the fallback
    public static final String OPENAI = "openai";       // paid; reached only through the escalation ladder
    public static final String HUGGINGFACE = "huggingface";   // the Hugging Face router; paid beyond the free credits

    public static final String CEREBRAS_BASE_URL = "https://api.cerebras.ai/v1";
    public static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";
    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";
    public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String HUGGINGFACE_BASE_URL = "https://router.huggingface.co/v1";

    /** Platforms that bill per token - chosen only as a fallback, or on every tenth automatic call. */
    public static boolean isPaid(String platform) {
        return DEEPSEEK.equals(platform) || OPENAI.equals(platform) || HUGGINGFACE.equals(platform);
    }

    /** A cloud chat model available on a platform, with routing metadata. */
    public record CloudModel(
            String platform, String id, String label,
            int codingScore, int contextTokens, String note) {}

    // Curated metadata; the LIVE /v1/models list (fetched + cached) decides what is
    // actually offered right now. codingScore: higher = better for programming/debugging.
    // Coding-relevant chat models only (no audio/guard/moderation). Verified 2026-08-18.
    private static final List<CloudModel> CATALOG = List.of(
            // ---- Cerebras (free tier: 30K tok/min, 1M/hour, 1M/day) ----
            new CloudModel(CEREBRAS, "gpt-oss-120b", "GPT-OSS 120B",
                    8, 131072, "Large, strong general + coding; robust default on Cerebras."),
            new CloudModel(CEREBRAS, "gemma-4-31b", "Gemma-4 31B",
                    6, 131072, "Mid-size general model; cheaper per call."),
            // ---- Groq (free tier: per-model TPM/TPD; see rate-limit headers) ----
            new CloudModel(GROQ, "qwen/qwen3.6-27b", "Qwen3.6 27B",
                    8, 131072, "Qwen, strong coder; good default on Groq."),
            new CloudModel(GROQ, "openai/gpt-oss-120b", "GPT-OSS 120B",
                    8, 131072, "Large, strong coding; built-in code execution."),
            new CloudModel(GROQ, "groq/compound", "Groq Compound",
                    7, 131072, "Agentic system w/ built-in web search + code exec."),
            new CloudModel(GROQ, "openai/gpt-oss-20b", "GPT-OSS 20B",
                    6, 131072, "Fast, capable; good for lighter coding help."),
            new CloudModel(GROQ, "groq/compound-mini", "Groq Compound Mini",
                    5, 131072, "Lighter agentic system; fast, cheaper."),
            // ---- DeepSeek (paid; off-peak hours are half price; no daily cap; 128k context) ----
            new CloudModel(DEEPSEEK, "deepseek-chat", "DeepSeek V3 (chat)",
                    8, 131072, "Strong coder, long context; PAID — the fallback when the free tiers are exhausted."),
            new CloudModel(DEEPSEEK, "deepseek-reasoner", "DeepSeek R1 (reasoner)",
                    8, 131072, "Reasoning model; PAID and slower — for a design, not a lookup."),
            // the ids the API serves since October 2026 (deepseek-chat is answered by deepseek-flash)
            new CloudModel(DEEPSEEK, "deepseek-v4-pro", "DeepSeek V4 Pro",
                    9, 131072, "DeepSeek's strongest served model; PAID — the BigBrother for hard reasoning."),
            new CloudModel(DEEPSEEK, "deepseek-flash", "DeepSeek Flash",
                    7, 131072, "Fast DeepSeek model; PAID, cheaper."),
            // ---- OpenAI (paid; only the escalation ladder calls these - BigBrother never picks them by itself) ----
            // ---- Hugging Face router (OpenAI-compatible; ":preferred" lets the router pick the provider in the
            // account's preference order; paid beyond the free credits) ----
            new CloudModel(HUGGINGFACE, "Qwen/Qwen3.8-27B:preferred", "Qwen3.8 27B (Hugging Face)",
                    8, 131072, "Qwen through the Hugging Face router; PAID beyond the free credits."),
            // the policy suffix also keeps the id apart from Groq's openai/gpt-oss-120b
            new CloudModel(HUGGINGFACE, "openai/gpt-oss-120b:preferred",
                    "GPT-OSS 120B (Hugging Face)",
                    8, 131072, "GPT-OSS through the Hugging Face router; BigBrother's default model; "
                    + "PAID beyond the free credits."),
            new CloudModel(OPENAI, "gpt-6-luna", "GPT-6 Luna",
                    6, 1050000, "OpenAI's most efficient GPT-6 model, for focused high-volume tasks; PAID."),
            new CloudModel(OPENAI, "gpt-6.1-sol", "GPT-6.1 Sol",
                    9, 1050000, "Near-Astra performance for complex work at a lower cost; PAID."),
            new CloudModel(OPENAI, "gpt-6-astra", "GPT-6 Astra",
                    10, 1050000,
                    "OpenAI's most capable model, for the most demanding work; PAID, the most expensive."));

    private HostedPlatforms() {}

    public static String baseUrl(String platform) {
        String override = System.getProperty("hermit." + platform + ".baseUrl");   // a test server, or a proxy
        if (override != null && !override.isBlank()) {
            return override.strip();
        }
        if (GROQ.equals(platform)) {
            return GROQ_BASE_URL;
        }
        if (DEEPSEEK.equals(platform)) {
            return DEEPSEEK_BASE_URL;
        }
        if (OPENAI.equals(platform)) {
            return OPENAI_BASE_URL;
        }
        if (HUGGINGFACE.equals(platform)) {
            return HUGGINGFACE_BASE_URL;
        }
        return CEREBRAS_BASE_URL;
    }

    /** All coding-relevant models, optionally filtered to one platform. */
    public static List<CloudModel> models(String platformOrNull) {
        List<CloudModel> out = new ArrayList<>();
        for (CloudModel m : CATALOG) {
            if ((platformOrNull == null || m.platform().equals(platformOrNull)) && permitted(m.id())) {
                out.add(m);
            }
        }
        return out;
    }

    public static CloudModel modelById(String id) {
        for (CloudModel m : CATALOG) {
            if (m.id().equals(id) && permitted(m.id())) {
                return m;
            }
        }
        return null;
    }

    /**
     * {@code -Dhermit.cloud.models=<id,…>} narrows the catalogue to those ids: a model left out is never listed, never
     * chosen and never honoured when the planner names it (a drive on the cheaper deepseek-flash only). Unset: all.
     */
    static boolean permitted(String id) {
        String allowed = System.getProperty("hermit.cloud.models");
        return allowed == null || allowed.isBlank()
                || Arrays.stream(allowed.split(",")).map(String::trim).anyMatch(id::equals);
    }

    // ---- Live availability: catalog ∩ what the platform actually serves now ----

    private record LiveModels(Set<String> ids, long fetchedAtMs) {}

    private static final ConcurrentHashMap<String, LiveModels> LIVE = new ConcurrentHashMap<>();
    private static final long LIVE_TTL_MS = 10 * 60 * 1000;   // re-probe at most every 10 min
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The set of model ids the platform currently serves (from {@code GET /v1/models}),
     * cached for {@link #LIVE_TTL_MS}. Returns null if there's no key or the probe fails
     * - callers treat null as "unknown", falling back to the curated catalog rather than
     * hiding everything on a transient network error.
     */
    public static Set<String> liveModelIds(String platform) {
        String key = apiKey(platform);
        if (key == null) {
            return null;
        }
        LiveModels cached = LIVE.get(platform);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs() < LIVE_TTL_MS) {
            return cached.ids();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl(platform) + "/models"))
                    .header("Authorization", "Bearer " + key)
                    .timeout(Duration.ofSeconds(6))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return cached != null ? cached.ids() : null;   // keep a stale-but-usable list
            }
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode m : MAPPER.readTree(resp.body()).path("data")) {
                String id = m.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            LIVE.put(platform, new LiveModels(ids, System.currentTimeMillis()));
            return ids;
        } catch (Exception probeFailed) {
            return cached != null ? cached.ids() : null;
        }
    }

    /**
     * Catalog models that are ALSO live on the platform right now. If the live list is
     * unknown (no key / probe failed), returns the catalog unfiltered (best effort).
     * Used by the routing/selection path so an archived model is never chosen.
     */
    public static List<CloudModel> availableModels(String platformOrNull) {
        List<CloudModel> out = new ArrayList<>();
        for (CloudModel m : models(platformOrNull)) {
            Set<String> live = liveModelIds(m.platform());
            if (live == null || live.contains(servedId(m.platform(), m.id()))) {
                out.add(m);
            }
        }
        return out;
    }

    /** True if the platform currently serves this exact model id (null live => assume yes). */
    public static boolean isModelLive(String platform, String id) {
        Set<String> live = liveModelIds(platform);
        return live == null || live.contains(servedId(platform, id));
    }

    /**
     * The id a platform lists in {@code GET /v1/models} for a model we call by {@code id}:
     * the Hugging Face router lists {@code Qwen/Qwen3.8-27B} and takes
     * {@code Qwen/Qwen3.8-27B:preferred}, the provider policy after the colon.
     */
    public static String servedId(String platform, String id) {
        int colon = id.lastIndexOf(':');
        return HUGGINGFACE.equals(platform) && colon > 0 ? id.substring(0, colon) : id;
    }

    /** The platform a catalogue model is served on, whether or not -Dhermit.cloud.models lets this run choose it. */
    public static String catalogPlatform(String id) {
        for (CloudModel m : CATALOG) {
            if (m.id().equals(id)) {
                return m.platform();
            }
        }
        return null;
    }

    /**
     * The platform whose base URL this is (a hosted endpoint the planner is pointed at),
     * or null for any other.
     */
    public static String platformOf(String url) {
        String wanted = url == null ? "" : url.strip().replaceAll("/+$", "");
        for (String platform : List.of(CEREBRAS, GROQ, DEEPSEEK, OPENAI, HUGGINGFACE)) {
            if (baseUrl(platform).replaceAll("/+$", "").equals(wanted)) {
                return platform;
            }
        }
        return null;
    }

    /** API key for a platform, or null if none configured. Never logged. */
    public static String apiKey(String platform) {
        if (EndpointPolicy.localOnly() && !EndpointPolicy.allowed(baseUrl(platform))) {
            return null;   // only an allow-listed host
        }
        String allowed = System.getProperty("hermit.cloud.providers");
        if (allowed != null && !Arrays.asList(allowed.split(",")).contains(platform)) {
            return null;
        }
        String prop = System.getProperty("hermit." + platform + ".apiKey");
        if (notBlank(prop)) {
            return prop.trim();
        }
        String env = System.getenv(platform.toUpperCase(Locale.ROOT) + "_API_KEY");
        if (notBlank(env)) {
            return env.trim();
        }
        String fromFile = SECRETS.getProperty(platform + ".apiKey");
        return notBlank(fromFile) ? fromFile.trim() : null;
    }

    public static boolean hasKey(String platform) {
        return apiKey(platform) != null;
    }

    private static final Properties SECRETS = loadSecrets();

    private static Properties loadSecrets() {
        Properties p = new Properties();
        // .secrets/cloud-keys.properties in the working directory, else ~/.hermit/cloud-keys.properties
        for (Path candidate : List.of(
                Path.of(".secrets", "cloud-keys.properties"),
                Path.of(System.getProperty("user.home"), ".hermit", "cloud-keys.properties"))) {
            if (Files.isReadable(candidate)) {
                try (InputStream in = Files.newInputStream(candidate)) {
                    p.load(in);
                    return p;
                } catch (IOException ignored) {
                    // fall through to next candidate
                }
            }
        }
        return p;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
