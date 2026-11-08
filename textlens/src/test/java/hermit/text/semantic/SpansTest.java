package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.text.model.Provenance;
import hermit.text.model.Span;
import hermit.text.model.TextRange;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpansTest {

    private static final ConceptRecognizer.Input INPUT = ConceptRecognizers.input("Wait 40 minutes, then Retry.", null);

    @Test
    void overCoversTheTokensWithTheRulesProvenance() {
        List<JsonNode> alternatives = new ArrayList<>();
        alternatives.add(Spans.alternative(Span.Concept.NUMBER, Spans.JSON.textNode("40")));
        Span s = Spans.over(INPUT, 1, 2, "x1", Span.Concept.DURATION, Spans.JSON.textNode("PT40M"), alternatives,
                "duration");
        alternatives.clear();
        assertEquals(new TextRange(5, 15), s.range());
        assertEquals("x1", s.id());
        assertEquals(Provenance.rule("duration"), s.provenance());
        assertEquals(1, s.alternatives().size(), "a copy of the alternatives");
        assertEquals(new ObjectMapper().createObjectNode().put("concept", "NUMBER").put("value", "40"),
                s.alternatives().get(0));
    }

    @Test
    void surfaceAndLowerReadTheText() {
        assertEquals("40 minutes", Spans.surface(INPUT, 1, 2));
        assertEquals("retry", Spans.lower(INPUT, 5));
        assertNull(Spans.lower(INPUT, 99));
        assertNull(Spans.lower(INPUT, -1));
    }

    @Test
    void touchesMeansNoWhitespaceBetween() {
        assertTrue(Spans.touches(INPUT, 2), "minutes and the comma");
        assertFalse(Spans.touches(INPUT, 1), "40 and minutes");
        assertFalse(Spans.touches(INPUT, INPUT.tokens().size() - 1), "the last token");
    }

    @Test
    void sentenceTokensListTheSentenceOfAToken() {
        ConceptRecognizer.Input input = ConceptRecognizers.input("Shelves fill up. Then the clerk sorts them.", null);
        assertEquals(List.of(0, 1, 2, 3), Spans.sentenceTokens(input, 1));
        assertEquals(List.of(4, 5, 6, 7, 8, 9), Spans.sentenceTokens(input, 9));
        assertEquals(List.of(), Spans.sentenceTokens(input, 10));
        assertEquals(List.of(), Spans.sentenceTokens(input, -1));
    }
}
