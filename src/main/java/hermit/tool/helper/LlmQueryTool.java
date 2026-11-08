package hermit.tool.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.model.EndpointPolicy;
import hermit.model.ModelEndpoint;
import hermit.tool.api.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connects to another LLM (or agent) over an OpenAI-compatible chat endpoint
 * and returns its answer - a way for the local brain to consult a larger or
 * differently-capable model for facts it cannot know (URLs, world facts) or to
 * refine its planning. Defaults to a local model server (works offline);
 * point it at a remote supplier or another agent with baseUrl + apiKey.
 *
 * Note: this speaks the OpenAI /chat/completions shape. Providers with that
 * shape (many, incl. local servers and gateways) work directly; a native
 * Anthropic endpoint would need a small adapter.
 */
public class LlmQueryTool implements Tool {

    private static final String DEFAULT_BASE = "http://localhost:1234/v1";
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getDocumentation() {
        return "LlmQuery: ask another LLM (or agent) over an OpenAI-compatible endpoint - use it to "
                + "obtain facts you cannot know (a real API URL, world facts) or to refine a plan. "
                + "Parameters: {\"question\": \"<what to ask>\", \"model\": \"<model id>\" (optional), "
                + "\"baseUrl\": \"<...>/v1\" (optional, default a local model server), \"apiKey\": "
                + "\"<bearer, optional>\"}. Returns the other model's answer.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String question = (String) parameters.get("question");
        if (question == null || question.isBlank()) {
            return Map.of("status", "Error", "message", "question is required.");
        }
        String baseUrl = (String) parameters.getOrDefault("baseUrl", DEFAULT_BASE);
        String model = (String) parameters.getOrDefault("model", ModelEndpoint.model());
        String apiKey = (String) parameters.get("apiKey");

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", question)));
            body.put("max_tokens", 1024);
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(3))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            EndpointPolicy.requireAllowed(req.build().uri().toString());
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Map.of("status", "Error",
                        "message", "LLM endpoint " + resp.statusCode() + ": " + tail(resp.body()));
            }
            JsonNode msg = mapper.readTree(resp.body()).path("choices").path(0).path("message");
            String answer = msg.path("content").asText("");
            if (answer.isBlank()) {
                answer = msg.path("reasoning_content").asText(""); // local-model quirk
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "Success");
            result.put("model", model);
            result.put("answer", answer);
            return result;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "LlmQuery failed: " + e.getMessage());
        }
    }

    private String tail(String s) {
        return s.length() > 500 ? s.substring(s.length() - 500) : s;
    }
}
