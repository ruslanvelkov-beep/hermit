package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.text.model.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpressionRecognizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Span> spans(String text) {
        return new ExpressionRecognizer().recognize(ConceptRecognizers.input(text, null));
    }

    private static List<String> found(String text) {
        return spans(text).stream()
                .map(s -> s.concept() + " " + text.substring(s.range().start(), s.range().end()) + " = " + s.value())
                .toList();
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void arithmeticAsWritten() {
        assertEquals(List.of("EXPRESSION 2 + 3 * 4 = \"2 + 3 * 4\"",
                "EXPRESSION (7 \u2212 2) \u00d7 3 = \"(7 \u2212 2) \u00d7 3\""),
                found("It is 2 + 3 * 4, or (7 \u2212 2) \u00d7 3 in the notes."));
        assertEquals("expression", spans("So 2 + 2 here.").get(0).provenance().rule());
        assertEquals("e1", spans("So 2 + 2 here.").get(0).id());
    }

    @Test
    void anEquationIsOneExpression() {
        assertEquals(List.of("EXPRESSION 2 + 2 = 5 = \"2 + 2 = 5\""), found("The answer says 2 + 2 = 5 today."));
    }

    @Test
    void rangesAndDatesAreNoArithmetic() {
        assertEquals(List.of(), found("Rounds 10-27 ran on 03/04/2026 and 2026-10-28."));
    }

    @Test
    void anOperatorBeforeANumberIsAComparison() {
        List<Span> spans = spans("Keep it \u2265 0.95 and < 18k.");
        assertEquals(List.of(json("{\"op\": \">=\", \"value\": \"0.95\"}"),
                json("{\"op\": \"<\", \"value\": \"18000\"}")), spans.stream().map(Span::value).toList());
        assertEquals("comparison", spans.get(0).provenance().rule());
    }

    @Test
    void comparisonPhrases() {
        String text = "Write at most 1500 characters, stop over 50 steps. No more than 3 retries.";
        List<Span> spans = spans(text);
        assertEquals(List.of("at most 1500", "over 50", "No more than 3"),
                spans.stream().map(s -> text.substring(s.range().start(), s.range().end())).toList());
        assertEquals(List.of(json("{\"op\": \"<=\", \"value\": \"1500\"}"), json("{\"op\": \">\", \"value\": \"50\"}"),
                json("{\"op\": \"<=\", \"value\": \"3\"}")), spans.stream().map(Span::value).toList());
    }

    @Test
    void wordsAroundAreNotPartOfIt() {
        assertEquals(List.of(), found("It is over the limit and under review."));
    }

    @Test
    void holdsComparisonNeedsWhitespaceAroundTheOperator() {
        assertTrue(ExpressionRecognizer.holdsComparison("duplicate != null"));
        assertTrue(ExpressionRecognizer.holdsComparison("size >= 3"));
        assertFalse(ExpressionRecognizer.holdsComparison("Map<K, V>"));
        assertFalse(ExpressionRecognizer.holdsComparison("x = 1"));
        assertFalse(ExpressionRecognizer.holdsComparison("a!=b"));
    }

    @Test
    void aComparisonInACodeSpanIsAnExpression() {
        assertEquals(List.of("EXPRESSION `duplicate != null` = \"duplicate != null\""),
                found("It checks `duplicate != null` first, then `x = 1`."));
    }

    @Test
    void aComparisonWithANumberWord() {
        assertEquals(List.of("COMPARISON more than one = {\"op\":\">\",\"value\":\"1\"}",
                "COMPARISON at least Three = {\"op\":\">=\",\"value\":\"3\"}"),
                found("It held more than one plan, at least Three times."));
    }

    @Test
    void aFractionIsAnOperandAndAPairIsNeverCut() {
        assertEquals(List.of("EXPRESSION 3/4 = 0.75 = \"3/4 = 0.75\""), found("Note that 3/4 = 0.75 here."));
        assertEquals(List.of("EXPRESSION 3/4=0.75 = \"3/4=0.75\""), found("Note that 3/4=0.75 here."));
        assertEquals(List.of("EXPRESSION 1 - 3/4 = \"1 - 3/4\""), found("Is 1 - 3/4 right?"));
        assertEquals(List.of("EXPRESSION 3/4 - 1 = \"3/4 - 1\""), found("Is 3/4 - 1 right?"));
        assertEquals(List.of(), found("It was 3/4."));
        assertEquals(List.of(), found("Done - 3/4 at the latest."));
        assertEquals(List.of(), found("The deadline = 3/4 for everyone."));
        assertEquals(List.of(), found("It is 1 - 10-25 or 10-25 - 1."));
        assertEquals(List.of(), found("Due 03/04/2026 = soon."));
    }

    @Test
    void aParenthesisLetsATouchingOperatorCount() {
        assertEquals(List.of("EXPRESSION (3/4)/2 = \"(3/4)/2\""), found("The result (3/4)/2 is small."));
        assertEquals(List.of("EXPRESSION (3/4)+(1/2) = \"(3/4)+(1/2)\""), found("Add (3/4)+(1/2) here."));
        assertEquals(List.of("EXPRESSION (3/4) + (1/2) = \"(3/4) + (1/2)\""), found("Add (3/4) + (1/2) here."));
    }

    @Test
    void arithmeticInACodeSpanIsAnExpression() {
        assertEquals(List.of("EXPRESSION `1 + 2` = \"1 + 2\"", "EXPRESSION `1+2 = 3` = \"1+2 = 3\"",
                "EXPRESSION `(3/4)/2` = \"(3/4)/2\""), found("Check `1 + 2`, `1+2 = 3` and `(3/4)/2` here."));
        assertTrue(ExpressionRecognizer.arithmetic(" 3/4 = 0.75 "));
        assertFalse(ExpressionRecognizer.arithmetic("3/4"), "a pair alone");
        assertFalse(ExpressionRecognizer.arithmetic("x = 1"));
        assertFalse(ExpressionRecognizer.arithmetic("1.2.3"));
        assertFalse(ExpressionRecognizer.arithmetic("-1"), "one number");
        assertFalse(ExpressionRecognizer.arithmetic("03/04/2026"), "a date");
        assertFalse(ExpressionRecognizer.arithmetic("--10-25"), "a month-day");
    }
}
