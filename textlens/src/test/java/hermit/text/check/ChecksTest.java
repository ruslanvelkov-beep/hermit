package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import hermit.text.model.Provenance;
import hermit.text.model.Span;
import hermit.text.model.TextRange;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChecksTest {

    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("UTC"), "en");

    private static Span span(String id, int start, int end) {
        return new Span(id, new TextRange(start, end), Span.Concept.NUMBER, Checks.JSON.textNode("1"), List.of(),
                Provenance.rule("number"));
    }

    @Test
    void theStatusFollowsTheReadings() {
        assertEquals(Check.Status.CONSISTENT, Checks.status(List.of(true, true)));
        assertEquals(Check.Status.INCONSISTENT, Checks.status(List.of(false, false)));
        assertEquals(Check.Status.UNVERIFIABLE, Checks.status(List.of(true, false)), "one reading holds");
        assertEquals(Check.Status.UNVERIFIABLE, Checks.status(List.of()), "no reading");
    }

    @Test
    void aCheckTargetsItsSpansInOrder() {
        List<Span> spans = new ArrayList<>(List.of(span("b", 4, 5), span("a", 0, 1)));
        List<String> reasons = new ArrayList<>(List.of("holds"));
        Check check = Checks.check("arithmetic", Check.Status.CONSISTENT, spans, Checks.JSON.objectNode(), List.of(),
                reasons);
        spans.clear();
        reasons.clear();
        assertEquals(List.of("b", "a"), check.targets());
        assertEquals("arithmetic", check.checker());
        assertEquals(List.of("holds"), check.reasons(), "the lists are copies");
    }

    @Test
    void theTokensAroundASpan() {
        Checker.Input input = Checkers.input("Today is Thursday.", CONTEXT);
        Span today = input.spans().get(0);
        assertEquals(0, Checks.firstToken(input, today));
        assertEquals(0, Checks.lastToken(input, today));
        assertEquals("is", Checks.word(input, 1));
        assertNull(Checks.word(input, 9));
        assertEquals("THURSDAY", Checks.spanAt(input, 2, Set.of(Span.Concept.WEEKDAY)).value().asText());
        assertNull(Checks.spanAt(input, 1, Set.of(Span.Concept.WEEKDAY)));
        assertNull(Checks.spanAt(input, 2, Set.of(Span.Concept.DATE)), "a span of another concept");
    }

    @Test
    void theSentenceOfASpan() {
        Checker.Input input = Checkers.input("We shipped it. Today is Thursday.", CONTEXT);
        Span thursday = input.spans().stream().filter(s -> s.concept() == Span.Concept.WEEKDAY).findFirst()
                .orElseThrow();
        assertEquals(15, Checks.sentenceOf(input, thursday).range().start());
        assertNull(Checks.sentenceOf(input, span("x", 90, 91)));
    }
}
