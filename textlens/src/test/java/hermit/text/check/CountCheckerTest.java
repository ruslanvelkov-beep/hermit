package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CountCheckerTest {

    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("UTC"), "en");

    private static List<Check> checks(String text) {
        return new CountChecker().check(Checkers.input(text, CONTEXT));
    }

    private static Check only(String text) {
        List<Check> checks = checks(text);
        assertEquals(1, checks.size(), text);
        return checks.get(0);
    }

    @Test
    void listOfFourIsNotThree() {
        Check check = only("The following three fixes:\n- fix the parser\n- fix the cache\n- add a test\n"
                + "- update the docs");
        assertEquals("count", check.checker());
        assertEquals(Check.Status.INCONSISTENT, check.status());
        assertEquals(List.of("count-mismatch"), check.reasons());
        assertEquals(3, check.evidence().path("stated").asInt());
        assertEquals(4, check.evidence().path("listed").asInt());
        assertEquals(1, check.targets().size(), "the number");
    }

    @Test
    void aCountThatMatchesIsConsistent() {
        Check check = only("These 3 steps:\n1. build it\n2. test it\n3. ship it");
        assertEquals(Check.Status.CONSISTENT, check.status());
        assertEquals(List.of("count-matches"), check.reasons());
    }

    @Test
    void nestedItemsAreNotCounted() {
        Check check = only("The following three fixes:\n- fix the parser\n  - with a nested note\n- fix the cache\n"
                + "- add a test");
        assertEquals(Check.Status.CONSISTENT, check.status());
        assertEquals(3, check.evidence().path("listed").asInt());
    }

    @Test
    void aHedgedCountIsUnverifiable() {
        Check check = only("About five changes:\n- a change\n- another change");
        assertEquals(Check.Status.UNVERIFIABLE, check.status());
        assertEquals(List.of("hedged"), check.reasons());
    }

    @Test
    void anOpenListIsUnverifiable() {
        Check check = only("Four notes:\n- the first note\n- the second note\n- more notes etc.");
        assertEquals(Check.Status.UNVERIFIABLE, check.status());
        assertEquals(List.of("open-list"), check.reasons());
    }

    @Test
    void theListEndsAtTheFirstSentenceThatIsNoItem() {
        Check check = only("We have these 2 options:\n* use the cache\n* skip the cache\n\nThen a paragraph follows.");
        assertEquals(Check.Status.CONSISTENT, check.status());
        assertEquals(2, check.evidence().path("listed").asInt());
    }

    @Test
    void aCountWithoutAListIsNotChecked() {
        assertEquals(List.of(), checks("We made 4 fixes:"), "no item after the colon");
        assertEquals(List.of(), checks("We made 4 fixes.\n- one fix here"), "no colon");
        assertEquals(List.of(), checks("The notes:\n- one note\n- another note"), "no count");
    }

    @Test
    void aCountBeforeAnAdjectiveAndANounIsChecked() {
        Check check = only("The following three new fixes:\n- fix the parser\n- fix the cache");
        assertEquals(Check.Status.INCONSISTENT, check.status());
        assertEquals(3, check.evidence().path("stated").asInt());
        assertEquals(2, check.evidence().path("listed").asInt());
    }

    @Test
    void aMatchingCountBeforeAnAdjectiveIsConsistent() {
        Check check = only("These two small changes:\n- rename the field\n- drop the option");
        assertEquals(Check.Status.CONSISTENT, check.status());
        assertEquals(List.of("count-matches"), check.reasons());
    }

    @Test
    void aCountBeforeAHyphenatedModifierIsChecked() {
        Check check = only("Four read-only views:\n- the list view\n- the card view\n- the map view");
        assertEquals(Check.Status.INCONSISTENT, check.status());
        assertEquals(List.of("count-mismatch"), check.reasons());
    }
}
