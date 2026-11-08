package hermit.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.model.UsageLedger.CallUsage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI-compatible client for a cloud platform (Cerebras or Groq). Sends a single
 * chat completion, extracts the answer, and parses token usage from both the response
 * body ({@code usage.*_tokens}) and the provider's rate-limit headers so
 * {@link UsageLedger} always has fresh remaining-token figures.
 */
public final class HostedChatClient {

    /** Outcome of one cloud call. On error, {@code answer} is null and {@code error} is set. */
    public record Result(String answer, CallUsage usage, int statusCode, String error) {
        public boolean ok() {
            return error == null && answer != null;
        }
    }

    private final String platform;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public HostedChatClient(String platform) {
        this.platform = platform;
    }

    static long retryAfterMillis(String header, long fallbackMs) {
        try {
            return Math.max(1_000L, (long) (Double.parseDouble(header.replaceAll("[^0-9.]", "")) * 1000));
        } catch (Exception e) {
            return fallbackMs;
        }
    }

    public Result chat(String model, String prompt, int maxTokens) {
        String key = HostedPlatforms.apiKey(platform);
        if (key == null) {
            return new Result(null, null, 0, "No API key configured for " + platform);
        }
        if (!EndpointPolicy.allowed(HostedPlatforms.baseUrl(platform))) {   // defence in depth: never past the boundary
            return new Result(null, null, 0, "Hosted inference to " + platform + " is outside this run's boundary");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.3);

            // the owner, 2026-10-26: on the router the call goes provider after provider (ProviderRoute); elsewhere once
            HttpResponse<String> resp = ProviderRoute.send(HostedPlatforms.baseUrl(platform), model, m -> {
                body.put("model", m);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(HostedPlatforms.baseUrl(platform) + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .timeout(ProviderRoute.timeoutFor(HostedPlatforms.baseUrl(platform), m, maxTokens, Duration.ofSeconds(120)))
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                long requestStarted = System.nanoTime();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                InferenceTrace.record(HostedPlatforms.baseUrl(platform), m, requestStarted, r);
                return r;
            });
            if (resp.statusCode() == 429) {
                CallUsage limited = usageFromHeaders(resp, model, 0, 0, 0, 0);
                if (limited != null) {
                    UsageLedger.record(limited);
                }
                String retry = header(resp, "retry-after").orElse("?");
                // a per-DAY limit ("tokens per day", "TPD") shuts the platform for an hour (the daily window is
                // rolling and refills a few thousand tokens at a time); a per-minute one for the retry-after seconds
                boolean daily = resp.body() != null
                        && resp.body().matches("(?is).*(per day|TPD|tokens_per_day|daily).*");
                long waitMs = daily ? 60 * 60 * 1000L : retryAfterMillis(retry, 60_000L);
                UsageLedger.markExhausted(platform, System.currentTimeMillis() + waitMs);
                return new Result(null, limited, 429,
                        "rate-limited (429) on " + platform + "; retry after " + retry + "s. "
                                + (limited != null ? UsageLedger.formatCall(limited) : ""));
            }
            if (resp.statusCode() != 200) {
                return new Result(null, null, resp.statusCode(),
                        platform + " HTTP " + resp.statusCode() + ": " + tail(resp.body()));
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode msg = root.path("choices").path(0).path("message");
            String answer = msg.path("content").asText("");
            if (answer.isBlank()) {
                // Reasoning models keep the answer in content; if it's empty (e.g. the
                // token budget was spent thinking), surface the reasoning so we're not
                // silently blank. Cerebras uses "reasoning"; some others "reasoning_content".
                answer = firstNonBlank(msg.path("reasoning_content").asText(""),
                        msg.path("reasoning").asText(""));
            }
            String finish = root.path("choices").path(0).path("finish_reason").asText("");
            if (answer.isBlank() && "length".equals(finish)) {
                answer = "[no answer: the model spent the whole max_tokens budget on reasoning "
                        + "before emitting content — raise max_tokens for this reasoning model.]";
            }
            JsonNode usage = root.path("usage");
            int in = usage.path("prompt_tokens").asInt(0);
            int out = usage.path("completion_tokens").asInt(0);
            int total = usage.path("total_tokens").asInt(in + out);
            int reasoning = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);

            CallUsage cu = usageFromHeaders(resp, model, in, out, total, reasoning);
            UsageLedger.record(cu);
            return new Result(answer.strip(), cu, 200, null);
        } catch (Exception e) {
            return new Result(null, null, 0, platform + " call failed: " + e.getMessage());
        }
    }

    /** Build a CallUsage, reading remaining/limit-token from the platform's headers. */
    private CallUsage usageFromHeaders(HttpResponse<String> resp, String model,
                                       int in, int out, int total, int reasoning) {
        Long remaining;
        Long limit;
        String window;
        String reset = null;
        if (HostedPlatforms.GROQ.equals(platform)) {
            remaining = longHeader(resp, "x-ratelimit-remaining-tokens");
            limit = longHeader(resp, "x-ratelimit-limit-tokens");
            window = "minute";
            reset = header(resp, "x-ratelimit-reset-tokens").orElse(null);
        } else {
            // Cerebras exposes minute/hour/day; the day figure is the meaningful budget.
            remaining = longHeader(resp, "x-ratelimit-remaining-tokens-day");
            limit = longHeader(resp, "x-ratelimit-limit-tokens-day");
            window = "day";
        }
        return new CallUsage(platform, model, in, out, total, reasoning, remaining, limit, window, reset);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b == null ? "" : b);
    }

    private static Optional<String> header(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name);
    }

    private static Long longHeader(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException nfe) {
                return null;
            }
        }).orElse(null);
    }

    private static String tail(String s) {
        return s == null ? "" : (s.length() > 400 ? s.substring(s.length() - 400) : s);
    }
}
