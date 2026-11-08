package hermit.text.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.Tag;
import hermit.text.model.Token.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClosedClassTest {

    private static final ClosedClass CLOSED = ClosedClass.standard();

    /** The word's candidate tags, in order. */
    private static List<Tag> tags(String word) {
        return CLOSED.candidates(word).stream().map(Candidate::tag).toList();
    }

    @Test
    void closedClassTagsStable() {
        assertEquals(List.of(Tag.DET), tags("the"));
        assertEquals(List.of(Tag.PRON), tags("they"));
        assertEquals(List.of(Tag.ADP), tags("of"));
        assertEquals(List.of(Tag.CCONJ), tags("and"));
        assertEquals(List.of(Tag.SCONJ), tags("because"));
        assertEquals(List.of(Tag.AUX), tags("is"));
        assertEquals(List.of(Tag.AUX), tags("should"));
        assertEquals(List.of(Tag.PART), tags("not"));
        assertEquals(List.of(Tag.ADV), tags("never"));
        assertEquals(List.of(Tag.NUM), tags("twelve"));
        assertEquals(List.of(Tag.INTJ), tags("please"));
    }

    @Test
    void aWordInOneListIsFinal() {
        for (String word : List.of("an", "we", "with", "nor", "unless", "were", "cannot", "probably", "hundred")) {
            assertEquals(1, CLOSED.candidates(word).size(), word);
        }
    }

    @Test
    void anAmbiguousWordKeepsEveryCandidateInTheListsOrder() {
        assertEquals(List.of(Tag.DET, Tag.PRON, Tag.SCONJ), tags("that"));
        assertEquals(List.of(Tag.ADP, Tag.SCONJ, Tag.ADV), tags("as"));
        assertEquals(List.of(Tag.ADP, Tag.SCONJ), tags("since"));
        assertEquals(List.of(Tag.ADP, Tag.SCONJ, Tag.ADV), tags("before"));
        assertEquals(List.of(Tag.ADP, Tag.VERB), tags("like"));
        assertEquals(List.of(Tag.ADP, Tag.PART), tags("to"));
    }

    @Test
    void subordinatorsAreKeptApartFromCoordinators() {
        for (String word : List.of("and", "or", "but", "nor")) {
            assertEquals(List.of(Tag.CCONJ), tags(word), word);
        }
        for (String word : List.of("because", "although", "unless", "whether", "if")) {
            assertEquals(List.of(Tag.SCONJ), tags(word), word);
        }
        assertEquals(List.of(Tag.CCONJ, Tag.SCONJ, Tag.ADV), tags("so"));
    }

    @Test
    void auxiliariesModalsAndTheirContractionsAreAux() {
        for (String word : List.of("be", "been", "can't", "won't", "don't", "isn't", "might", "ought")) {
            assertEquals(List.of(Tag.AUX), tags(word), word);
        }
        assertEquals(List.of(Tag.AUX, Tag.VERB), tags("has"), "have and do are main verbs too");
        assertEquals(List.of(Tag.AUX, Tag.VERB), tags("did"));
    }

    @Test
    void wordsAreLookedUpInLowerCaseWithTheTypographicApostrophe() {
        assertEquals(List.of(Tag.DET), tags("The"));
        assertEquals(List.of(Tag.PRON), tags("I"));
        assertEquals(List.of(Tag.AUX), tags("Don’t"));
        assertEquals(List.of(Tag.AUX, Tag.PART), tags("’s"));
    }

    @Test
    void abbreviationsKeepTheirPeriods() {
        assertEquals(List.of(Tag.ADP), tags("vs."));
        assertEquals(List.of(Tag.ADV), tags("e.g."));
        assertTrue(CLOSED.candidates("vs").isEmpty());
    }

    @Test
    void anUnknownWordHasNoCandidates() {
        assertTrue(CLOSED.candidates("parser").isEmpty());
        assertTrue(CLOSED.candidates("").isEmpty());
    }

    @Test
    void everyCandidateNamesTheClosedClassRule() {
        for (String word : List.of("that", "as", "like", "has", "never")) {
            for (Candidate c : CLOSED.candidates(word)) {
                assertEquals("closed-class", c.rule(), word);
            }
        }
    }

    @Test
    void theListsHoldFourHundredNineteenWords() {
        assertEquals(419, CLOSED.size(), "the contractions that start with a pronoun are two tokens now");
    }

    @Test
    void theLogicalMarkersAreClosed() {
        for (String word : List.of("always", "sometimes", "therefore", "hence", "perhaps", "probably")) {
            assertEquals(List.of(Tag.ADV), tags(word), word);
        }
        assertEquals(List.of(Tag.ADP), tags("without"));
        assertEquals(List.of(Tag.DET, Tag.INTJ), tags("no"));
        assertEquals(List.of(Tag.ADV, Tag.ADJ), tags("likely"));
    }

    @Test
    void whoseIsAPronounOnly() {
        assertEquals(List.of(Tag.PRON), tags("whose"));
        assertEquals(List.of(Tag.DET, Tag.PRON), tags("which"));
    }
}
