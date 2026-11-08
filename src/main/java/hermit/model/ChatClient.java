package hermit.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatClient {

    /** the sentinel a failed model call returns (the loop halts after two in a row). */
    public static final String LLM_CALL_FAILED = "LLM_CALL_FAILED";

    // Base URL comes from the shared config so /model changes take effect live.
    private final String apiUrl = ModelEndpoint.baseUrl();
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ChatClient() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper(); // Initialize the JSON mapper
    }

    /**
     * Sends a prompt to the local LLM API and retrieves the response.
     * @param prompt The structured prompt (including CoT context) to send to the LLM.
     * @return The extracted, clean text response from the LLM.
     */
    public String getResponse(String prompt) throws Exception {
        return getResponse(prompt, ModelEndpoint.model());
    }

    /**
     * As {@link #getResponse(String)}, asking the given model: a second model served at the same endpoint, or a hosted
     * one the run permits at its own platform ({@link #endpointFor}).
     */
    public String getResponse(String prompt, String model) throws Exception {
        String url = endpointFor(apiUrl, ModelEndpoint.model(), model);
        // On a machine on the local network (swap mode) the driver 7B must be the loaded model before we call.
        EndpointPolicy.requireAllowed(url);
        ModelSwapper.ensureDriver();
        System.out.println("--- Connecting to LLM API at: " + url + " ---");

        // a hosted endpoint (the Hugging Face router) takes its platform's key; a local server takes none
        String platform = HostedPlatforms.platformOf(url);
        String key = platform == null ? null : HostedPlatforms.apiKey(platform);
        // a local model server can take minutes for a long prompt; without a request timeout a stuck call
        // blocks the drive forever. A hosted endpoint keeps the 4-minute ceiling however long the run lets its local
        // model take.
        long timeoutSec = Long.getLong("hermit.requestTimeoutSec", 0L);
        Duration timeout = ModelProfile.forEndpoint(url, timeoutSec > 0 ? Duration.ofSeconds(timeoutSec) : null);
        // the owner, 2026-10-26: on the router the call goes provider after provider (ProviderRoute); elsewhere once
        HttpResponse<String> response = ProviderRoute.send(url, model, m -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildJsonRequest(prompt, m)));
            if (key != null) {
                builder.header("Authorization", "Bearer " + key);
            }
            Duration callTimeout = ProviderRoute.timeoutFor(url, m, Integer.getInteger("hermit.maxTokens", 8192), timeout);
            if (callTimeout != null) {
                builder.timeout(callTimeout);
            }
            HttpRequest request = builder.build();
            long requestStarted = System.nanoTime();
            HttpResponse<String> r = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            InferenceTrace.record(url, m, requestStarted, r);
            if (refusedToolCall(r)) {
                // GPT-OSS at times answers with a tool call, which the provider refuses
                // ("Tool choice is none, but model called a tool"); the same request asked once more answers
                System.out.println("--- the provider refused a tool call in the reply; asking once more ---");
                requestStarted = System.nanoTime();
                r = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                InferenceTrace.record(url, m, requestStarted, r);
            }
            return r;
        });

        if (response.statusCode() == 200) {
            // 2. Use Jackson to parse the response body into our POJO
            JsonNode rootNode = objectMapper.readTree(response.body());

            // 3. Extract content based on the new logic
            String extractedContent = extractAssistantText(rootNode);

            if (extractedContent != null) {
                return extractedContent;
            } else {
                 // Fallback if extraction fails unexpectedly
                 return "LLM_RESPONSE_PARSING_FAILED";
            }

        } else {
            throw new RuntimeException("LLM API Error: Status Code " + response.statusCode()
                    + ", Body: " + response.body());
        }
    }

    /**
     * Where {@code model} is asked when the run points at {@code runUrl} with {@code runModel}: a catalogued hosted
     * model the run permits (-Dhermit.cloud.models) at its platform, when the run's endpoint is not that platform, so
     * the step planner can be local while its second model stays hosted (GPT-OSS on the router). The run's own model
     * and any other model are asked at the run's endpoint.
     */
    static String endpointFor(String runUrl, String runModel, String model) {
        HostedPlatforms.CloudModel hosted = model.equals(runModel) ? null : HostedPlatforms.modelById(model);
        return hosted == null || hosted.platform().equals(HostedPlatforms.platformOf(runUrl))
                ? runUrl : HostedPlatforms.baseUrl(hosted.platform());
    }

    /** A 400 the provider gives when the model answered with a tool call the request did not offer. */
    public static boolean refusedToolCall(HttpResponse<String> response) {
        return response.statusCode() == 400 && response.body() != null
                && response.body().contains("tool_use_failed");
    }

    /**
     * Build the chat-completions request body with Jackson so EVERY character in the prompt is
     * escaped correctly. This used to be hand-assembled with a StringBuilder + a partial escaper
     * that only handled \\ " \n \r - a literal TAB (or any other control char) from a tool result
     * (e.g. the tab-aligned output of `gh issue view`) produced an invalid JSON body and the API
     * rejected it with 400 "failed to parse JSON value", stalling the whole run.
     */
    String buildJsonRequest(String prompt) {
        return buildJsonRequest(prompt, ModelEndpoint.model());
    }

    String buildJsonRequest(String prompt, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        // the output cap, the sampling set and the thinking switch are per-run settings (the local cluster/Qwen
        // profiles need 8-16k output, top-p/top-k/min-p, a presence penalty and an explicit thinking on/off).
        body.put("max_tokens", Integer.getInteger("hermit.maxTokens", 4096));
        // no sampling parameter was ever sent, so the served model's default
        // (0.8 or higher) applied - the same intent came back in three spellings (`handle`, `Type#handle`,
        // `Type.handle`), each defeating a guard that compared calls verbatim. Planning is not creative writing.
        body.put("temperature", Double.parseDouble(System.getProperty("hermit.temperature", "0.2")));
        // the profile is the run model's; a second model gets cap and temperature
        if (model.equals(ModelEndpoint.model())) {
            ModelProfile.applyOptional(body);
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize LLM request body", e);
        }
    }

    /**
     * Helper method to extract the final answer text from the API response structure,
     * handling the correct array format.
     */
    String extractAssistantText(JsonNode rootNode) {
        JsonNode choicesArray = rootNode.path("choices");

        if (choicesArray == null || !choicesArray.isArray() || choicesArray.size() == 0) {
            System.err.println("Extraction Error: 'choices' array is null, empty, or not an array.");
            return null;
        }

        try {
            // 1. Get the first element of the array (index 0)
            JsonNode firstChoice = choicesArray.get(0);

            // 2. Check for the nested 'message' object
            JsonNode messageNode = firstChoice.path("message");

            if (messageNode != null && messageNode.isObject()) {
                // an answer cut at max_tokens is not an answer (a half plan, a verdict without its end).
                if ("length".equals(firstChoice.path("finish_reason").asText(""))) {
                    System.out.println("Warning: the response stopped at max_tokens (finish_reason=length).");
                    if (Boolean.getBoolean("hermit.rejectTruncated")) {
                        return "Extraction Failed: the response was cut at max_tokens.";
                    }
                }
                // 3. Extract the final text content, checking for fallback
                String content = messageNode.path("content").asText();

                if (content != null && !content.trim().isEmpty()) {
                    return content; // Success: Return the extracted text
                } else {
                    // Fallback logic if 'content' is empty or null, check for 'reasoning_content'
                    String fallbackContent = messageNode.path("reasoning_content").asText();
                    // with a thinking model the reasoning is not the answer; return a failure instead.
                    if (!fallbackContent.isEmpty() && !ModelProfile.reasoningFallbackAllowed()) {
                        return "Extraction Failed: the response held reasoning but no final answer.";
                    }
                    if (!fallbackContent.isEmpty()) {
                        System.out.println("Warning: 'content' was empty. Using 'reasoning_content' as fallback.");
                        return fallbackContent; // Return the reasoning as the content
                    }
                }
            }
            return "Extraction Failed: 'message' object structure was unexpected.";

        } catch (Exception e) {
            System.err.println("General Error during extraction: " + e.getMessage());
            return "Extraction Failed due to general exception.";
        }
    }
}
