package hermit.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for the decision service, Jev - a fast (70-500ms) "structured decision" model
 * that returns a typed judgment (yes/no probability, a category choice with a
 * confidence distribution, or a rubric score) instead of generated text. Used for
 * cheap, low-latency gate decisions Hermit currently makes with brittle string/regex
 * heuristics or an expensive full LLM call.
 *
 * <p>The API key is read (in order) from: the {@code -Dhermit.jev.apiKey} system
 * property, the {@code JEV_API_KEY} env var, or the gitignored {@code jev.api.key}
 * file at the repo root (same convention as {@code deepseek.api.key}). Never logged.
 */
public final class DecisionConfig {
    public static final String BASE_URL = "https://api.typesafe.ai/v1/systemone";
    public static final String DEFAULT_MODEL = "jev-latest";

    /** Where Jev is reached: {@code -Dhermit.jev.baseUrl} (a test server), else the hosted API. */
    public static String baseUrl() {
        String url = System.getProperty("hermit.jev.baseUrl");
        return url == null || url.isBlank() ? BASE_URL : url.strip();
    }

    private DecisionConfig() {}

    /**
     * Jev's plan-review guardrail and repeat detector send the request, the context and the plans. In a local-only run
     * (where Jev may be allow-listed for the model router alone, which sends only counts) they run only when asked for:
     * {@code -Dhermit.jev.guardrails=true}. Elsewhere they follow the key, as before.
     */
    public static boolean guardrailsEnabled() {
        return hasKey() && (!EndpointPolicy.localOnly() || Boolean.getBoolean("hermit.jev.guardrails"));
    }

    public static boolean hasKey() {
        return apiKey() != null;
    }

    /** API key, or null if none configured. Never logged. */
    public static String apiKey() {
        if (EndpointPolicy.localOnly() && !EndpointPolicy.allowed(BASE_URL)) return null;   // only when allow-listed
        String prop = System.getProperty("hermit.jev.apiKey");
        if (notBlank(prop)) {
            return prop.trim();
        }
        String env = System.getenv("JEV_API_KEY");
        if (notBlank(env)) {
            return env.trim();
        }
        for (Path candidate : List.of(
                Path.of("jev.api.key"),
                Path.of(System.getProperty("user.home"), ".hermit", "jev.api.key"))) {
            if (Files.isReadable(candidate)) {
                try (InputStream in = Files.newInputStream(candidate)) {
                    String key = new String(in.readAllBytes()).trim();
                    if (notBlank(key)) {
                        return key;
                    }
                } catch (IOException ignored) {
                    // fall through to next candidate
                }
            }
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
