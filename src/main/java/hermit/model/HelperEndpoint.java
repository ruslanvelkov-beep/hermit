package hermit.model;

/**
 * Configuration for a remote agent/model reached over an OpenAI-compatible API -
 * by default the Qwen-14B running in the local server on a machine on the local network.
 * Values can be overridden at launch with -Dhermit.remote.baseUrl / .model /
 * .apiKey / .label, so the same tool can point at a different box without a rebuild.
 *
 * The endpoint is DISCOVERED, not assumed available: the tool probes it and stays
 * dark (with a yellow notice) when the box is off the network or not serving.
 */
public final class HelperEndpoint {

    // A helper on the local network: qwen2.5-coder-14b-instruct-mlx on the local server.
    public static final String DEFAULT_LABEL = "helper (qwen2.5-coder-14b)";
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:1234/v1";
    public static final String DEFAULT_MODEL = "qwen2.5-coder-14b-instruct-mlx";

    private final String label;
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public HelperEndpoint(String label, String baseUrl, String model, String apiKey) {
        this.label = label;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }

    /** The default helper config, with optional -Dhermit.remote.* overrides. */
    public static HelperEndpoint defaultConfig() {
        return named("remote", DEFAULT_LABEL, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    /**
     * A config read from {@code -Dhermit.<prefix>.label/baseUrl/model/apiKey}; a cloud endpoint (Groq, Cerebras)
     * takes its key from {@link HostedPlatforms} when none is given, so no key ever appears on a command line.
     * Null when the prefix names no endpoint and there is no default.
     */
    public static HelperEndpoint named(String prefix, String defaultLabel,
                                       String defaultBaseUrl, String defaultModel) {
        String baseUrl = System.getProperty("hermit." + prefix + ".baseUrl", defaultBaseUrl);
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String apiKey = System.getProperty("hermit." + prefix + ".apiKey", null);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = cloudKeyFor(baseUrl);
        }
        return new HelperEndpoint(
                System.getProperty("hermit." + prefix + ".label",
                        defaultLabel == null ? prefix + " @ " + baseUrl : defaultLabel),
                baseUrl,
                System.getProperty("hermit." + prefix + ".model", defaultModel),
                apiKey);
    }

    /**
     * The configured cloud key for a hosted platform's base URL
     * (Groq, Cerebras, DeepSeek, Hugging Face, OpenAI), else null.
     */
    static String cloudKeyFor(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        if (baseUrl.contains("api.groq.com")) {
            return HostedPlatforms.apiKey(HostedPlatforms.GROQ);
        }
        if (baseUrl.contains("api.cerebras.ai")) {
            return HostedPlatforms.apiKey(HostedPlatforms.CEREBRAS);
        }
        if (baseUrl.contains("api.deepseek.com")) {
            return HostedPlatforms.apiKey(HostedPlatforms.DEEPSEEK);
        }
        String platform = HostedPlatforms.platformOf(baseUrl);   // the Hugging Face router, OpenAI
        return platform == null ? null : HostedPlatforms.apiKey(platform);
    }

    public String label() {
        return label;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public String apiKey() {
        return apiKey;
    }
}
