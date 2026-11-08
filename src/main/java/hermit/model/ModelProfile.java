package hermit.model;

import hermit.model.escalation.EscalationLadder;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * The run's model settings - sampling, the thinking switch, the reasoning fallback, the request timeout - in one
 * place, so a client pointed at the local server (-Dhermit.remote.useProfile=true) asks it the same way the
 * driver does. Without it the client sent no sampling and no thinking switch: a thinking model reasoned until the
 * client's 1500-token cap and that cut reasoning came back as the client's answer.
 */
public final class ModelProfile {

    private ModelProfile() {}

    public static void applyOptional(Map<String, Object> body) {
        putIfSet(body, "top_p", "hermit.topP", Double::parseDouble);
        putIfSet(body, "top_k", "hermit.topK", Integer::parseInt);
        putIfSet(body, "min_p", "hermit.minP", Double::parseDouble);
        putIfSet(body, "presence_penalty", "hermit.presencePenalty", Double::parseDouble);
        String thinking = System.getProperty("hermit.enableThinking");
        if (thinking != null && !thinking.isBlank()) {
            body.put("enable_thinking", Boolean.parseBoolean(thinking.trim()));
        }
    }

    public static boolean remoteUsesProfile() {
        return Boolean.getBoolean("hermit.remote.useProfile");
    }

    public static void applyToRemote(Map<String, Object> body) {
        if (!remoteUsesProfile()) {
            return;
        }
        putIfSet(body, "temperature", "hermit.temperature", Double::parseDouble);
        applyOptional(body);
    }

    public static boolean reasoningFallbackAllowed() {
        return Boolean.parseBoolean(System.getProperty("hermit.reasoningFallback", "true"));
    }

    public static Duration remoteTimeout(Duration fallback) {
        long seconds = Long.getLong("hermit.requestTimeoutSec", 0L);
        return remoteUsesProfile() && seconds > 0 ? Duration.ofSeconds(seconds) : fallback;
    }

    /** The helper's timeout for a call to {@code url}: {@link #remoteTimeout(Duration)}, within the hosted ceiling. */
    public static Duration remoteTimeout(Duration fallback, String url) {
        return forEndpoint(url, remoteTimeout(fallback));
    }

    /**
     * A call's timeout at {@code url}: a hosted platform's call never waits longer than the user's 4-minute ceiling
     * ({@link EscalationLadder#TIMEOUT_SECONDS}), whatever -Dhermit.requestTimeoutSec says, so a run can give its local
     * model the minutes a long prompt takes while every hosted call keeps the ceiling. A local server's timeout is
     * returned as given, null (no timeout) included. A call through the Hugging Face router takes its timeout from the
     * chosen provider's speed instead, up to 15 minutes (the owner, 2026-10-26; {@link ProviderRoute#timeoutFor}).
     */
    public static Duration forEndpoint(String url, Duration timeout) {
        if (HostedPlatforms.platformOf(url) == null) {
            return timeout;
        }
        Duration ceiling = Duration.ofSeconds(EscalationLadder.TIMEOUT_SECONDS);
        return timeout == null || timeout.compareTo(ceiling) > 0 ? ceiling : timeout;
    }

    private static void putIfSet(Map<String, Object> body, String field, String property, Function<String, Object> parse) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            body.put(field, parse.apply(value.trim()));
        }
    }
}
