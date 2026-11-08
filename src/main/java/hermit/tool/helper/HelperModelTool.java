package hermit.tool.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.model.EndpointPolicy;
import hermit.model.HelperEndpoint;
import hermit.model.InferenceTrace;
import hermit.model.ModelEndpoint;
import hermit.model.ModelProfile;
import hermit.model.ModelSwapper;
import hermit.model.ProviderRoute;
import hermit.tool.api.OptionalTool;
import hermit.tool.api.Tool;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consults ANOTHER agent - a larger model on a different box - over an
 * OpenAI-compatible endpoint. Configured by default for the Qwen-14B on the local server
 * on a machine on the local network (see {@link HelperEndpoint}); use it to
 * refine a plan or to run a sceptical critique with a stronger brain than the
 * small local one.
 *
 * OPTIONAL + AUTO-DETECTING: it is on by default, but the helper's machine is not always on the
 * network. {@link #isAvailable()} probes the endpoint; when the box is off or not
 * serving, the agent shows a yellow notice and the tool declines cleanly rather
 * than hanging or erroring obscurely.
 */
public class HelperModelTool implements Tool, OptionalTool {

    private final HelperEndpoint config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public HelperModelTool() {
        this(HelperEndpoint.defaultConfig());
    }

    public HelperModelTool(HelperEndpoint config) {
        this.config = config;
    }

    @Override
    public String endpointLabel() {
        return config.label() + " @ " + config.baseUrl();
    }

    /** Quick probe of the endpoint's /models - reachable and serving => available. */
    @Override
    public boolean isAvailable() {
        if (!EndpointPolicy.allowed(config.baseUrl())) return false;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/models"))
                    .timeout(Duration.ofSeconds(4))
                    .GET();
            // Auth-gated endpoints (e.g. a chat UI behind a tunnel) 401 the probe
            // without the key, which would wrongly read as "unavailable".
            if (config.apiKey() != null && !config.apiKey().isBlank()) {
                req.header("Authorization", "Bearer " + config.apiKey());
            }
            HttpResponse<String> resp = send(req.build());
            return resp.statusCode() == 200;
        } catch (Exception unreachable) {
            return false;
        }
    }

    @Override
    public String getDocumentation() {
        return "RemoteAgent: consult a STRONGER model on another machine (default: Qwen-14B on the local server "
                + "on a machine on the local network at " + config.baseUrl()
                + ") over an OpenAI-compatible API. Use it to refine a "
                + "plan or run a sceptical critique when the local brain is too small. OPTIONAL — only "
                + "works when that box is on the network and serving. Parameters: {\"task\": \"<question or "
                + "instruction>\", \"mode\": \"answer\"|\"refinePlan\"|\"critique\" (optional, default answer), "
                + "\"context\": \"<plan/claim/background to refine or critique>\" (optional)}.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String task = str(parameters.get("task"));
        if (task == null || task.isBlank()) {
            task = str(parameters.get("question"));
        }
        if (task == null || task.isBlank()) {
            return err("task is required.");
        }
        if (!isAvailable()) {
            return err("RemoteAgent endpoint is not reachable (" + endpointLabel()
                    + "). The remote box is off the network or the local server is not serving.");
        }
        String mode = str(parameters.getOrDefault("mode", "answer"));
        String context = str(parameters.get("context"));
        String prompt = buildPrompt(mode, task, context);

        // On a 16 GB machine (swap mode) load the refiner 14B before calling (unloads the 7B).
        ModelSwapper.ensureRefiner();

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            body.put("max_tokens", 1500);
            if (servesTheRunModel()) {
                ModelProfile.applyToRemote(body);   // the run's profile is for the run's model
            }
            // the owner, 2026-10-26: on the router the call goes provider after provider (ProviderRoute); elsewhere once
            HttpResponse<String> resp = ProviderRoute.send(config.baseUrl(), config.model(), model -> {
                body.put("model", model);
                HttpRequest.Builder req = HttpRequest.newBuilder()
                        .uri(URI.create(config.baseUrl() + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .timeout(ProviderRoute.timeoutFor(config.baseUrl(), model, 1500,
                                ModelProfile.remoteTimeout(Duration.ofMinutes(3), config.baseUrl())))
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
                if (config.apiKey() != null && !config.apiKey().isBlank()) {
                    req.header("Authorization", "Bearer " + config.apiKey());
                }
                return send(req.build(), model);
            });
            if (resp.statusCode() != 200) {
                return err("remote endpoint " + resp.statusCode() + ": " + tail(resp.body()));
            }
            JsonNode msg = mapper.readTree(resp.body()).path("choices").path(0).path("message");
            String answer = msg.path("content").asText("");
            if (answer.isBlank() && ModelProfile.reasoningFallbackAllowed()) {
                answer = msg.path("reasoning_content").asText("");
            }
            if (answer.isBlank()) {
                return err("remote model returned no answer text (reasoning only or empty).");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "Success");
            result.put("mode", mode);
            result.put("model", config.model());
            result.put("answer", answer.strip());
            return result;
        } catch (Exception e) {
            return err("RemoteAgent call failed: " + e.getMessage());
        }
    }

    /**
     * a RAW completion of {@code prompt} on the remote model (no role wrapping) - the codegen critic panel and
     * other callers that build their own prompts. Returns null when the endpoint fails, so callers can fall back.
     * {@code maxTokens} is generous because reasoning models spend tokens before the visible answer.
     */
    /** Minimum spacing between requests to this process's remote endpoints (-Dhermit.remote.minIntervalMs). */
    private static final Object PACE = new Object();
    private static long lastRequestAt = 0L;

    /** True for a rate-limited cloud endpoint (Groq, Cerebras) - the only ones paced unless -Dhermit.remote.paceAll. */
    static boolean isCloud(String baseUrl) {
        return baseUrl != null && (baseUrl.contains("api.groq.com") || baseUrl.contains("api.cerebras.ai"));
    }

    /**
     * Waits so that consecutive requests to cloud endpoints are at least -Dhermit.remote.minIntervalMs apart
     * (3 s when unset); a LAN box is never paced unless -Dhermit.remote.paceAll=true.
     */
    static void pace(boolean cloud) {
        if (!cloud && !Boolean.getBoolean("hermit.remote.paceAll")) {
            return;
        }
        long min = Long.getLong("hermit.remote.minIntervalMs", 3_000L);
        if (min <= 0) {
            return;
        }
        synchronized (PACE) {
            long wait = lastRequestAt + min - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    /**
     * Sends with pacing and ONE retry on 429 (rate limited), honouring {@code Retry-After} up to a minute
     * (15 s when absent): a cloud endpoint such as Groq throttles bursts, and one paced retry is cheaper than a lost round.
     */
    HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return send(request, config.model());
    }

    /** As {@link #send(HttpRequest)}, recorded under {@code model} (on the router, the model named with its provider). */
    HttpResponse<String> send(HttpRequest request, String model) throws IOException, InterruptedException {
        EndpointPolicy.requireAllowed(request.uri().toString());
        boolean cloud = isCloud(config.baseUrl());
        pace(cloud);
        long requestStarted = System.nanoTime();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        InferenceTrace.record(config.baseUrl(),model,requestStarted,resp);
        if (resp.statusCode() == 429) {
            long waitMs = retryAfterMs(resp);
            Thread.sleep(waitMs);
            pace(cloud);
            requestStarted = System.nanoTime();
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            InferenceTrace.record(config.baseUrl(),model,requestStarted,resp);
        }
        return resp;
    }

    static long retryAfterMs(HttpResponse<?> resp) {
        String header = resp.headers().firstValue("retry-after").orElse("");
        try {
            double seconds = Double.parseDouble(header.trim());
            return Math.max(1_000L, Math.min(60_000L, Math.round(seconds * 1000)));
        } catch (NumberFormatException e) {
            return 15_000L;
        }
    }

    /**
     * A hosted reasoning model (deepseek-v4-pro) spends its reasoning out of max_tokens:
     * a 1500-token review budget can end before the answer starts.
     * Hosted calls get at least {@code -Dhermit.remote.hostedMinTokens} (default 0: unchanged).
     */
    public static int hostedMinTokens() {
        return Integer.getInteger("hermit.remote.hostedMinTokens", 0);
    }

    /**
     * The run's sampling profile (temperature, top_k, thinking switch …) belongs to the run's model,
     * not to a hosted one.
     */
    boolean servesTheRunModel() {
        return config.model() != null && config.model().equals(ModelEndpoint.model());
    }

    public String complete(String prompt, int maxTokens) {
        return complete(prompt, maxTokens, Map.of());
    }

    private volatile String lastFailure;

    /** As {@link #complete(String, int)}, with more fields for the request body (the instruction follower's sampling). */
    public String complete(String prompt, int maxTokens, Map<String, Object> extra) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            body.put("max_tokens", servesTheRunModel() ? maxTokens : Math.max(maxTokens, hostedMinTokens()));
            if (servesTheRunModel()) {
                ModelProfile.applyToRemote(body);   // the run's profile is for the run's model
            }
            body.putAll(extra);
            // the owner, 2026-10-26: on the router the call goes provider after provider (ProviderRoute); elsewhere once
            HttpResponse<String> resp = ProviderRoute.send(config.baseUrl(), config.model(), model -> {
                body.put("model", model);
                HttpRequest.Builder req = HttpRequest.newBuilder()
                        .uri(URI.create(config.baseUrl() + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .timeout(ProviderRoute.timeoutFor(config.baseUrl(), model, (int) body.get("max_tokens"),
                                ModelProfile.remoteTimeout(Duration.ofMinutes(4), config.baseUrl())))
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
                if (config.apiKey() != null && !config.apiKey().isBlank()) {
                    req.header("Authorization", "Bearer " + config.apiKey());
                }
                return send(req.build(), model);
            });
            if (resp.statusCode() != 200) {
                // two syntheses came back null from the escalation model and the log said
                // only "returned no answer text: null" - a 429, a 5xx and a timeout all looked the same.
                // The failure is kept and printed, so the log says what the endpoint said.
                lastFailure = "HTTP " + resp.statusCode() + " " + tail(resp.body());
                System.err.println("[RemoteAgent] " + endpointLabel() + " completion failed: " + lastFailure);
                return null;
            }
            lastFailure = null;
            String answer = completionContent(resp.body());
            return answer == null || answer.isBlank() ? null : answer;
        } catch (Exception e) {
            lastFailure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            System.err.println("[RemoteAgent] " + endpointLabel() + " completion failed: " + lastFailure);
            return null;
        }
    }

    /**
     * why the last {@link #complete} returned null ("HTTP 429 …", "HttpTimeoutException: …"),
     * or null after a success.
     */
    public String lastFailure() {
        return lastFailure;
    }

    /** The visible answer of an OpenAI-style completion; a reasoning model's `reasoning_content` is the fallback. */
    public static String completionContent(String responseJson) {
        try {
            JsonNode msg = new ObjectMapper().readTree(responseJson).path("choices").path(0).path("message");
            String answer = msg.path("content").asText("");
            if (answer.isBlank() && !ModelProfile.reasoningFallbackAllowed()) {
                return "";   // reasoning is not the answer
            }
            if (answer.isBlank()) {
                answer = msg.path("reasoning_content").asText("");
            }
            if (answer.isBlank()) {
                answer = msg.path("reasoning").asText("");
            }
            return answer.strip();
        } catch (Exception e) {
            return null;
        }
    }

    /** Wrap the task per mode so the remote model plays the right role. */
    static String buildPrompt(String mode, String task, String context) {
        String ctx = (context == null || context.isBlank()) ? "" : "\n\nCONTEXT:\n" + context;
        return switch (mode == null ? "answer" : mode) {
            case "refinePlan" -> "You are an expert planning reviewer. Improve the plan for the task below: "
                    + "fix gaps, remove unnecessary steps, and make each step concrete and executable. "
                    + "Return the improved plan only.\n\nTASK: " + task + ctx;
            case "critique" -> "You are a rigorous sceptical critic. Try to REFUTE or find concrete flaws in "
                    + "the claim/plan below. List each concrete problem; if you find none, say APPROVED.\n\n"
                    + "TARGET: " + task + ctx;
            default -> task + ctx;
        };
    }

    private Map<String, Object> err(String message) {
        return Map.of("status", "Error", "message", message);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String tail(String s) {
        return s == null ? "" : (s.length() > 400 ? s.substring(s.length() - 400) : s);
    }
}
