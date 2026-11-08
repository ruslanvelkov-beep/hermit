package hermit.text.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import hermit.text.model.Provenance;
import hermit.text.model.Span;
import hermit.text.model.TextRange;
import java.util.List;
import java.util.Locale;

/** What the recognizers share: spans over token runs, alternatives, and the text of tokens. */
public final class Spans {

    /** The factory every recognizer makes its JSON values with. */
    public static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private Spans() {}

    /**
     * A span from the start of token {@code from} to the end of token {@code to} (indices into the input's tokens,
     * both included), with a copy of {@code alternatives} and the provenance of the rule {@code rule}.
     */
    public static Span over(ConceptRecognizer.Input input, int from, int to, String id, Span.Concept concept,
            JsonNode value, List<JsonNode> alternatives, String rule) {
        int start = input.tokens().get(from).range().start();
        int end = input.tokens().get(to).range().end();
        return new Span(id, new TextRange(start, end), concept, value, List.copyOf(alternatives),
                Provenance.rule(rule));
    }

    /** An alternative reading: {@code {"concept": "<concept>", "value": <value>}}. */
    public static JsonNode alternative(Span.Concept concept, JsonNode value) {
        return JSON.objectNode().put("concept", concept.name()).set("value", value);
    }

    /** The text from the start of token {@code from} to the end of token {@code to}, both included. */
    public static String surface(ConceptRecognizer.Input input, int from, int to) {
        int start = input.tokens().get(from).range().start();
        int end = input.tokens().get(to).range().end();
        return input.text().substring(start, end);
    }

    /** Token {@code i}'s text, lower-cased with the root locale; null when there is no token {@code i}. */
    public static String lower(ConceptRecognizer.Input input, int i) {
        if (i < 0 || i >= input.tokens().size()) {
            return null;
        }
        return Spans.surface(input, i, i).toLowerCase(Locale.ROOT);
    }

    /**
     * The indices of the tokens of the sentence that holds token {@code i}, in text order; empty when there is no token
     * {@code i} or no sentence of the input holds it.
     */
    public static List<Integer> sentenceTokens(ConceptRecognizer.Input input, int i) {
        if (i < 0 || i >= input.tokens().size()) {
            return List.of();
        }
        String tokenId = input.tokens().get(i).id();
        for (var sentence : input.sentences()) {
            if (!sentence.tokenIds().contains(tokenId)) {
                continue;
            }
            List<Integer> indices = new java.util.ArrayList<>();
            for (String sentenceTokenId : sentence.tokenIds()) {
                for (int j = 0; j < input.tokens().size(); j++) {
                    if (input.tokens().get(j).id().equals(sentenceTokenId)) {
                        indices.add(j);
                        break;
                    }
                }
            }
            return List.copyOf(indices);
        }
        return List.of();
    }

    /** Whether token {@code i + 1} exists and starts where token {@code i} ends, with no whitespace between them. */
    public static boolean touches(ConceptRecognizer.Input input, int i) {
        return i >= 0 && i + 1 < input.tokens().size()
                && input.tokens().get(i).range().end() == input.tokens().get(i + 1).range().start();
    }
}
