package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.TextLens;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What would refuse a text (plan section 7): the findings, their sentences, and the quoted text that is none. */
class FindingsTest {

    static final Instant CLOCK = Instant.parse("2026-10-28T10:00:00Z");
    static final ZoneId UTC = ZoneId.of("UTC");

    static AnnotationDocument annotate(String text, Instant clock) {
        return TextLens.standard().annotate(text, List.of(), clock, UTC);
    }

    static AnnotationDocument annotate(String text) {
        return annotate(text, CLOCK);
    }

    static List<String> sentences(AnnotationDocument document) {
        return Findings.of(document).stream().map(Findings.Finding::sentence).toList();
    }

    static List<Check.Status> statuses(AnnotationDocument document) {
        return document.checks().stream().map(Check::status).toList();
    }

    @Test
    void aFindingStatesTheReadingsTheTenseKeeps() {
        AnnotationDocument document = annotate("Wed Dec 29 was the release.", Instant.parse("2027-01-02T10:00:00Z"));
        assertEquals(List.of(Check.Status.INCONSISTENT), statuses(document));
        assertEquals(List.of("December 29, 2026 was a Tuesday, not a Wednesday."), sentences(document),
                "29 December 2027 is a Wednesday, but the release lies in the past");
    }

    @Test
    void aDayThatDoesNotExistIsAFinding() {
        assertEquals(List.of("There is no February 30."), sentences(annotate("We met on Feb 30.")));
        assertEquals(List.of("February 29, 2001 does not exist."), sentences(annotate("In 2001 we met on Feb 29.")));
    }

    @Test
    void anUnverifiableCheckIsNoFinding() {
        // true of versions, false of decimals: the statement is read both ways, so it is unverifiable
        AnnotationDocument document = annotate("Release 1.10 exceeds 1.9 by far.");
        assertEquals(List.of(Check.Status.UNVERIFIABLE), statuses(document));
        assertEquals(List.of(), sentences(document));
    }

    @Test
    void eachRefusingCheckerStatesItsOwnSentence() {
        assertEquals(List.of("2 + 2 = 5 does not hold: 4 = 5 is false."),
                sentences(annotate("We know that 2 + 2 = 5 here.")));
        assertEquals(List.of("1.9 is not newer than 1.10."), sentences(annotate("Version 1.9 is newer than 1.10.")));
    }

    @Test
    void aCountMismatchStaysInTheShadow() {
        AnnotationDocument document = annotate("The run made 3 fixes:\n- the lexer\n- the parser\n");
        assertEquals(List.of(Check.Status.INCONSISTENT), statuses(document));
        assertEquals(List.of(), sentences(document));
        assertEquals("The text announces 3 items, but the list that follows has 2.",
                Findings.sentence(document, document.checks().get(0)));
    }

    @Test
    void aQuotedCheckIsNoFinding() {
        AnnotationDocument quoted = annotate(
                "The example sentence was \"On Mon Oct 20, 2026 the runs took 2+2=5 minutes.\"");
        assertEquals(List.of(Check.Status.INCONSISTENT, Check.Status.INCONSISTENT), statuses(quoted));
        assertEquals(List.of(), sentences(quoted));
        AnnotationDocument asserted = annotate("We know that 2 + 2 = 5 here, and the old note said \"3 + 3 = 7\".");
        assertEquals(List.of("2 + 2 = 5 does not hold: 4 = 5 is false."), sentences(asserted));
    }

    @Test
    void anExampleInBackquotesIsNoFinding() {
        AnnotationDocument example = annotate(
                "It throws when a text ends in a number, as in `2 + 2 = 5` with no full stop.");
        assertEquals(List.of(Check.Status.INCONSISTENT), statuses(example));
        assertEquals(List.of(), sentences(example));
        assertTrue(Findings.example(example, example.checks().get(0)));
        AnnotationDocument listed = annotate(
                "Sums such as `1 + 1 = 2`, `2 + 2 = 5` or `3 + 3 = 7` are parsed; e.g., `4 + 4 = 9` too.");
        assertEquals(List.of(), sentences(listed));
        AnnotationDocument asserted = annotate("The total is `2 + 2 = 5`, i.e. `3 + 3 = 7`.");
        assertEquals(List.of("`2 + 2 = 5` does not hold: 4 = 5 is false.",
                "`3 + 3 = 7` does not hold: 6 = 7 is false."), sentences(asserted));
        assertFalse(Findings.example(asserted, asserted.checks().get(0)));
    }
}
