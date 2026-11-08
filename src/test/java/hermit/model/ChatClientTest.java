package hermit.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression for the request-body serializer. A tool result like `gh issue view` is tab-aligned,
 * and the old hand-rolled escaper only handled \\ " \n \r - a literal TAB (or any other control
 * char) produced an invalid JSON body and the LLM API rejected it with 400 "failed to parse JSON
 * value", stalling the whole run. buildJsonRequest must now escape EVERY character so the body is
 * always valid JSON and the content round-trips byte-for-byte.
 */
class ChatClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildsValidJsonForTabbedControlCharAndEmojiPrompts() throws Exception {
        // exactly the shape that broke it: TABs (the `gh` alignment), newlines, CR, quotes,
        // backslashes and a multi-byte emoji - all in one string.
        String nasty = "author:\tsample-author\nassociation:\tmember\r\n"
                + "# Release notes ⚠️ \"unguarded\" export \\ path candidate\0roster.json \1 end";

        String body = assertDoesNotThrow(() -> new ChatClient().buildJsonRequest(nasty));

        // The body must be valid JSON...
        JsonNode root = mapper.readTree(body);
        // ...and the content must survive unchanged (Jackson escaped, the local server can re-parse).
        String content = root.path("messages").get(0).path("content").asText();
        assertEquals(nasty, content, "prompt content must round-trip byte-for-byte");
        assertEquals("user", root.path("messages").get(0).path("role").asText());
    }

    // the planner on a hosted router: the base URL's platform supplies the key, and a call may name a second model
    @Test
    void aHostedPlannerEndpointGetsItsPlatformsKeyAndTheModelChosenForTheCall() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> auth = new ArrayList<>();
        List<String> models = new ArrayList<>();
        server.createContext("/v1/chat/completions", ex -> {
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            models.add(mapper.readTree(ex.getRequestBody()).path("model").asText());
            byte[] out = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        String liveUrl = ModelEndpoint.baseUrl();
        String liveModel = ModelEndpoint.model();
        try {
            System.setProperty("hermit.huggingface.baseUrl", url);
            System.setProperty("hermit.huggingface.apiKey", "test-key");
            ModelEndpoint.setBaseUrl(url);
            ModelEndpoint.setModel("Qwen/Qwen3.8-27B:preferred");
            ChatClient client = new ChatClient();
            assertEquals("ok", client.getResponse("plan"));
            assertEquals("ok", client.getResponse("plan", "openai/gpt-oss-120b:preferred"));
            assertEquals(List.of("Bearer test-key", "Bearer test-key"), auth);
            assertEquals(List.of("Qwen/Qwen3.8-27B:preferred", "openai/gpt-oss-120b:preferred"), models);
            System.clearProperty("hermit.huggingface.baseUrl");   // now no platform's endpoint: no key is sent
            new ChatClient().getResponse("plan");
            assertEquals("null", auth.get(2));
        } finally {
            System.clearProperty("hermit.huggingface.baseUrl");
            System.clearProperty("hermit.huggingface.apiKey");
            ModelEndpoint.setBaseUrl(liveUrl);
            ModelEndpoint.setModel(liveModel);
            server.stop(0);
        }
    }

    // GPT-OSS on the Hugging Face router at times answers with a tool call, which the
    // provider refuses with a 400 ("Tool choice is none, but model called a tool"); asked again, it answers
    @Test
    void aReplyRefusedForAToolCallIsAskedForOnceMore() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Integer> statuses = new ArrayList<>();
        server.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            // the first call, and every one after the second
            boolean refuse = statuses.size() == 0 || statuses.size() >= 2;
            String body = refuse ? "{\"error\":{\"message\":\"Tool choice is none, but model called a tool\","
                    + "\"type\":\"invalid_request_error\",\"code\":\"tool_use_failed\"}}"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"plan\"},"
                    + "\"finish_reason\":\"stop\"}]}";
            statuses.add(refuse ? 400 : 200);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(refuse ? 400 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String liveUrl = ModelEndpoint.baseUrl();
        try {
            ModelEndpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            assertEquals("plan", new ChatClient().getResponse("plan"));
            assertEquals(List.of(400, 200), statuses, "asked once more after the refused tool call");
            RuntimeException twice = assertThrows(RuntimeException.class,
                    () -> new ChatClient().getResponse("plan"));
            assertTrue(twice.getMessage().contains("tool_use_failed"), twice.getMessage());
            assertEquals(List.of(400, 200, 400, 400), statuses, "one retry only");
        } finally {
            ModelEndpoint.setBaseUrl(liveUrl);
            server.stop(0);
        }
    }

    // the run's sampling profile is its own model's (Qwen's thinking switch, top-k, min-p); a second model at the same
    // endpoint gets the output cap and the temperature only - GPT-OSS's provider refuses enable_thinking
    @Test
    void aSecondModelGetsNoneOfTheRunModelsSamplingProfile() throws Exception {
        System.setProperty("hermit.enableThinking", "false");
        System.setProperty("hermit.topK", "20");
        System.setProperty("hermit.maxTokens", "8192");
        String liveModel = ModelEndpoint.model();
        try {
            ModelEndpoint.setModel("Qwen/Qwen3.8-27B:preferred");
            JsonNode own = mapper.readTree(new ChatClient().buildJsonRequest("p"));
            assertTrue(own.has("enable_thinking") && own.has("top_k"), own.toString());
            JsonNode other = mapper.readTree(new ChatClient().buildJsonRequest("p", "openai/gpt-oss-120b:preferred"));
            assertFalse(other.has("enable_thinking") || other.has("top_k"), other.toString());
            assertEquals(8192, other.path("max_tokens").asInt());
            assertTrue(other.has("temperature"));
        } finally {
            ModelEndpoint.setModel(liveModel);
        }
    }

    @Test
    void plainPromptStillSerializes() throws Exception {
        String body = new ChatClient().buildJsonRequest("hello world");
        JsonNode root = mapper.readTree(body);
        assertEquals("hello world", root.path("messages").get(0).path("content").asText());
    }

    // run profiles (the local cluster/Qwen) set the output cap, sampling and thinking per run.
    private static final String[] PROFILE_PROPERTIES = {"hermit.maxTokens", "hermit.topP", "hermit.topK", "hermit.minP",
        "hermit.presencePenalty", "hermit.enableThinking", "hermit.reasoningFallback", "hermit.rejectTruncated",
        "hermit.contextTokens"};

    @AfterEach
    void clearProfileProperties() {
        for (String property : PROFILE_PROPERTIES) {
            System.clearProperty(property);
        }
    }

    @Test
    void defaultBodyCarriesOnlyTheOldFields() throws Exception {
        JsonNode root = mapper.readTree(new ChatClient().buildJsonRequest("x"));
        assertEquals(4096, root.path("max_tokens").asInt());
        assertFalse(root.has("top_p") || root.has("top_k") || root.has("min_p")
                || root.has("presence_penalty") || root.has("enable_thinking"), root.toString());
    }

    @Test
    void configuredProfileIsSent() throws Exception {
        System.setProperty("hermit.maxTokens", "8192");
        System.setProperty("hermit.topP", "0.8");
        System.setProperty("hermit.topK", "20");
        System.setProperty("hermit.minP", "0");
        System.setProperty("hermit.presencePenalty", "1.5");
        System.setProperty("hermit.enableThinking", "false");
        JsonNode root = mapper.readTree(new ChatClient().buildJsonRequest("x"));
        assertEquals(8192, root.path("max_tokens").asInt());
        assertEquals(0.8, root.path("top_p").asDouble());
        assertEquals(20, root.path("top_k").asInt());
        assertEquals(0.0, root.path("min_p").asDouble());
        assertEquals(1.5, root.path("presence_penalty").asDouble());
        assertTrue(root.path("enable_thinking").isBoolean() && !root.path("enable_thinking").asBoolean());
    }

    private JsonNode response(String content, String reasoning, String finishReason) throws Exception {
        var message = mapper.createObjectNode().put("content", content).put("reasoning_content", reasoning);
        var choice = mapper.createObjectNode().put("finish_reason", finishReason).set("message", message);
        return mapper.createObjectNode().set("choices", mapper.createArrayNode().add(choice));
    }

    @Test
    void reasoningOnlyResponseIsAFailureWhenTheFallbackIsOff() throws Exception {
        assertEquals("thought", new ChatClient().extractAssistantText(response("", "thought", "stop")),
                "default keeps the old fallback");
        System.setProperty("hermit.reasoningFallback", "false");
        assertTrue(new ChatClient().extractAssistantText(response("", "thought", "stop"))
                .startsWith("Extraction Failed"));
        assertEquals("answer", new ChatClient().extractAssistantText(response("answer", "thought", "stop")));
    }

    @Test
    void truncatedResponseIsAFailureWhenRejectionIsOn() throws Exception {
        assertEquals("half", new ChatClient().extractAssistantText(response("half", "", "length")));
        System.setProperty("hermit.rejectTruncated", "true");
        assertTrue(new ChatClient().extractAssistantText(response("half", "", "length"))
                .startsWith("Extraction Failed"));
        assertEquals("whole", new ChatClient().extractAssistantText(response("whole", "", "stop")));
    }

    @Test
    void configuredContextWindowSkipsTheProbe() {
        System.setProperty("hermit.contextTokens", "16384");
        assertEquals(16384, ContextWindow.tokens());
    }

    // the step planner local and its second model hosted (GPT-OSS on the router): each call goes to its own model's
    // endpoint, the hosted one with its platform's key
    @Test
    void aHostedSecondPlannerIsAskedAtItsPlatformWhileTheRunsModelStaysLocal() throws Exception {
        List<String> local = new ArrayList<>();
        List<String> hosted = new ArrayList<>();
        HttpServer localServer = answering(local);
        HttpServer router = answering(hosted);
        String localUrl = "http://127.0.0.1:" + localServer.getAddress().getPort() + "/v1";
        String routerUrl = "http://127.0.0.1:" + router.getAddress().getPort() + "/v1";
        String liveUrl = ModelEndpoint.baseUrl();
        String liveModel = ModelEndpoint.model();
        try {
            System.setProperty("hermit.huggingface.baseUrl", routerUrl);
            System.setProperty("hermit.huggingface.apiKey", "test-key");
            System.setProperty("hermit.cloud.models", "openai/gpt-oss-120b:preferred");
            ModelEndpoint.setBaseUrl(localUrl);
            ModelEndpoint.setModel("qwen3.8-27b");
            ChatClient client = new ChatClient();
            assertEquals("ok", client.getResponse("plan"));
            assertEquals("ok", client.getResponse("plan", "openai/gpt-oss-120b:preferred"));
            assertEquals(List.of("qwen3.8-27b null"), local);
            assertEquals(List.of("openai/gpt-oss-120b:preferred Bearer test-key"), hosted);

            System.setProperty("hermit.cloud.models", "deepseek-flash");   // a hosted model the run does not permit
            assertEquals(localUrl,
                    ChatClient.endpointFor(localUrl, ModelEndpoint.model(), "openai/gpt-oss-120b:preferred"));
            assertEquals(localUrl,
                    ChatClient.endpointFor(localUrl, ModelEndpoint.model(), "qwen2.5-coder-7b-instruct"));
            System.setProperty("hermit.cloud.models", "openai/gpt-oss-120b:preferred");
            assertEquals(localUrl, ChatClient.endpointFor(localUrl, "openai/gpt-oss-120b:preferred",
                    "openai/gpt-oss-120b:preferred"), "the run's own model is asked where the run points");
        } finally {
            System.clearProperty("hermit.huggingface.baseUrl");
            System.clearProperty("hermit.huggingface.apiKey");
            System.clearProperty("hermit.cloud.models");
            ModelEndpoint.setBaseUrl(liveUrl);
            ModelEndpoint.setModel(liveModel);
            localServer.stop(0);
            router.stop(0);
        }
    }

    /** A chat endpoint that answers "ok" and records each call's model and Authorization header. */
    private HttpServer answering(List<String> calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            calls.add(mapper.readTree(ex.getRequestBody()).path("model").asText() + " "
                    + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return server;
    }
}
