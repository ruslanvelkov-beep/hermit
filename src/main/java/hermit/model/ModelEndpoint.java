package hermit.model;

/**
 * Shared, runtime-mutable configuration for the local LLM the agent's brain
 * uses - the model id and base URL. Held in one place so the /model command
 * can switch models and every chat client / tool sees it immediately.
 */
public final class ModelEndpoint {

    // Launch overrides: -Dhermit.model / -Dhermit.baseUrl (default to the local gemma
    // brain). On a 16 GB machine in swap mode set -Dhermit.model to the 7B driver so the request
    // body's model id matches whatever ModelSwapper has loaded.
    private static volatile String model =
            System.getProperty("hermit.model", "google/gemma-4-e2b");
    private static volatile String baseUrl =
            System.getProperty("hermit.baseUrl", "http://localhost:1234/v1");

    private ModelEndpoint() {}

    public static String model() {
        return model;
    }

    public static void setModel(String m) {
        if (m != null && !m.isBlank()) {
            model = m.trim();
        }
    }

    public static String baseUrl() {
        return baseUrl;
    }

    /** Tests / stepping: point the driver at another OpenAI-style endpoint at runtime. */
    public static void setBaseUrl(String url) {
        if (url != null && !url.isBlank()) {
            baseUrl = url.trim();
        }
    }
}
