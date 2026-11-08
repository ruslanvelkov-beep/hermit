package hermit.model;

public class ModelResponse {
    private String choices; // This will hold the full response string (if available)
    private String reasoningContent; // The detailed thought process (if content is empty)

    public ModelResponse(String choices, String reasoningContent) {
        this.choices = choices;
        this.reasoningContent = reasoningContent;
    }

    public String getChoices() {
        return choices;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }
}
