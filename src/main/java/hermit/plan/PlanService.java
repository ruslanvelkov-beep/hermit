package hermit.plan;

import hermit.model.ChatClient;
import hermit.model.ModelEndpoint;

public class PlanService {

    private final ChatClient chatClient;

    public PlanService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Calls the chat client to get a real response.
     */
    public String getRealLLMResponse(String prompt) {
        return getRealLLMResponse(prompt, ModelEndpoint.model());
    }

    /** As {@link #getRealLLMResponse(String)}, asking the given model at the same endpoint. */
    public String getRealLLMResponse(String prompt, String model) {
        try {
            return chatClient.getResponse(prompt, model);
        } catch (Exception e) {
            System.err.println("ERROR during LLM API call: " + e);
            lastLlmError = e.getMessage() == null ? e.toString() : e.getMessage();
            return ChatClient.LLM_CALL_FAILED;
        }
    }

    /** What the last failed model call said - a context-length 400 is not "the model is unloaded". */
    public static volatile String lastLlmError;
}
