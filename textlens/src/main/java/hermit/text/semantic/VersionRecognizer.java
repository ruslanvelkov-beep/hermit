package hermit.text.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import hermit.text.model.Span;
import hermit.text.model.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Versions (milestone A3 of the annotation plan, section 6.1): a VERSION token that is a version on its own
 * ({@code 2.21.1}, {@code v3}, {@code v1.2}, and {@code 8.10} after a version cue), valued with the token as written
 * (rule {@code version}). One of two dotted numbers keeps the alternative NUMBER with its decimal value
 * ({@code 1.10} gives {@code "1.1"}). A VERSION token with a letter in it other than a leading {@code v}
 * ({@code Qwen3.8-27B}) is a product's name and version, which {@link ProductRecognizer} reports, not this recognizer.
 * A CODE_SPAN token whose inside text (between the backticks) is a {@link #CODE_SPAN_VERSION} ({@code `2.21.1`},
 * {@code `v3`}, {@code `1.10`}) is a version too, valued with the inside text; its span covers the backticks, and a
 * two-part decimal keeps the alternative NUMBER in the same way. Spans come in text order, ids {@code v1},
 * {@code v2}, ...
 */
public final class VersionRecognizer implements ConceptRecognizer {

    /** A version between backticks: {@code v} and digits with optional dotted parts, or two or more dotted numbers. */
    public static final Pattern CODE_SPAN_VERSION = Pattern.compile("v[0-9]+(?:\\.[0-9]+)*|[0-9]+(?:\\.[0-9]+)+");

    /** The spans of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Span> recognize(Input input) {
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < input.tokens().size(); i++) {
            Token token = input.tokens().get(i);
            String surface = Spans.surface(input, i, i);
            String text;
            if (token.shape() == Token.Shape.CODE_SPAN) {
                text = surface.substring(1, surface.length() - 1);
                if (!CODE_SPAN_VERSION.matcher(text).matches()) {
                    continue;
                }
            } else if (token.shape() == Token.Shape.VERSION) {
                text = surface;
                String withoutLeadingV = text.startsWith("v") ? text.substring(1) : text;
                if (LETTER_PATTERN.matcher(withoutLeadingV).matches()) {
                    continue;
                }
            } else {
                continue;
            }
            List<JsonNode> alternatives = new ArrayList<>();
            if (TWO_PART_DECIMAL.matcher(text).matches()) {
                String decimal = NumberRecognizer.decimalOf(text);
                if (decimal != null) {
                    alternatives.add(Spans.alternative(Span.Concept.NUMBER, Spans.JSON.textNode(decimal)));
                }
            }
            spans.add(Spans.over(input, i, i, "v" + (spans.size() + 1), Span.Concept.VERSION,
                    Spans.JSON.textNode(text), alternatives, "version"));
        }
        return List.copyOf(spans);
    }

    /** A letter anywhere, after the leading {@code v} is cut off. */
    private static final Pattern LETTER_PATTERN = Pattern.compile(".*[A-Za-z].*");
    /** Two dotted numbers, the shape of a decimal version. */
    private static final Pattern TWO_PART_DECIMAL = Pattern.compile("[0-9]+\\.[0-9]+");
}
