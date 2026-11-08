package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Coverage;
import hermit.text.model.Span;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConceptRecognizersTest {

    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T20:00:00Z"), ZoneId.of("UTC"), "en");

    /** Each merged span as {@code id CONCEPT surface}. */
    private static List<String> found(String text) {
        return ConceptRecognizers.spansOf(text, CONTEXT).stream()
                .map(s -> s.id() + " " + s.concept() + " " + text.substring(s.range().start(), s.range().end()))
                .toList();
    }

    @Test
    void inputHoldsTaggedTokensAndSentences() {
        ConceptRecognizer.Input input = ConceptRecognizers.input("Run three tests. Then stop.", CONTEXT);
        assertEquals(7, input.tokens().size());
        assertEquals(2, input.sentences().size());
        assertEquals(Tag.NUM, input.tokens().get(1).tag());
        assertSame(CONTEXT, input.context());
    }

    @Test
    void theNumbersOfADateOrTimeAreNotReportedAgain() {
        assertEquals(List.of("c1 DATE 2026-10-28", "c2 TIME 22:12", "c3 NUMBER 5"),
                found("On 2026-10-28 at 22:12 we ran 5 tasks."));
    }

    @Test
    void spansAreOrderedLongerFirstWithIdsInOrder() {
        assertEquals(List.of("c1 DATE Thu 29 Oct", "c2 WEEKDAY Thu", "c3 DURATION 40 minutes", "c4 NUMBER 40"),
                found("Thu 29 Oct took 40 minutes."));
    }

    @Test
    void aProductOrAFlagIsNotACodeRefToo() {
        assertEquals(List.of("c1 PRODUCT DeepSeek", "c2 FLAG --offline", "c3 CODE_REF RepeatGuard"),
                found("DeepSeek ran --offline on RepeatGuard."));
        assertEquals(List.of("c1 COMMAND `git log -1`"), found("Check `git log -1` first."));
        assertEquals(List.of("c1 DURATION 5-hour"), found("A 5-hour window."));
    }

    @Test
    void decimalVsVersionAcrossRecognizers() {
        List<Span> spans = ConceptRecognizers.spansOf("Gradle 8.10 scored 0.95 there.", CONTEXT);
        assertEquals(List.of(Span.Concept.PRODUCT, Span.Concept.VERSION, Span.Concept.NUMBER),
                spans.stream().map(Span::concept).toList());
        assertEquals(1, spans.get(1).alternatives().size());
        assertEquals(1, spans.get(2).alternatives().size());
    }

    @Test
    void theSpansMakeAValidDocument() {
        String text = "Merged 3,500 lines on Oct 28 in RepeatGuard.java, see d4016e8.";
        ConceptRecognizer.Input input = ConceptRecognizers.input(text, CONTEXT);
        List<Span> spans = ConceptRecognizers.standard().recognize(input);
        List<Coverage> coverage = new ArrayList<>();
        for (Token t : input.tokens()) {
            coverage.add(new Coverage(t.id(), spans.stream().filter(s -> s.range().contains(t.range()))
                    .map(Span::id).toList()));
        }
        AnnotationDocument doc = new AnnotationDocument(AnnotationDocument.SCHEMA_VERSION, "a3", text, CONTEXT, null,
                input.sentences(), input.tokens(), spans, List.of(), List.of(), coverage);
        assertSame(doc, doc.validated());
        assertEquals(4, spans.size());
    }

    @Test
    void aCodeSpanTakesTheConceptOfWhatItHolds() {
        assertEquals(List.of("c1 EXPRESSION `duplicate != null`", "c2 VERSION `2.21.1`", "c3 PRODUCT `gpt-6-astra`"),
                found("Check `duplicate != null` on `2.21.1` with `gpt-6-astra`."));
    }

    @Test
    void aPairOrArithmeticInBackquotesIsNoCodeRef() {
        assertEquals(List.of("c1 EXPRESSION `3/4`", "c2 EXPRESSION `1 + 2`"), found("Run `3/4` and `1 + 2` now."));
    }

    @Test
    void aCommitHashOfDigitsIsACodeRefOnly() {
        assertEquals(List.of("c1 CODE_REF t119", "c2 CODE_REF 7375134"), found("Committed: t119 (7375134)."));
    }

    @Test
    void aNumberNestsInADurationAndAComparison() {
        assertEquals(List.of("c1 DURATION 15 minutes", "c2 NUMBER 15", "c3 COMPARISON at most 3", "c4 NUMBER 3"),
                found("It took 15 minutes for at most 3 crates."));
    }
}
