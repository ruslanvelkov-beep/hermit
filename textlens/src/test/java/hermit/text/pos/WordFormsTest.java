package hermit.text.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WordFormsTest {

    /** The candidates as {@code TAG@rule}, separated by spaces. */
    private static String candidates(String word, boolean sentenceStart) {
        return WordForms.candidates(word, sentenceStart).stream().map(c -> c.tag() + "@" + c.rule())
                .collect(Collectors.joining(" "));
    }

    @Test
    void aPastFormThatIsNoParticipleIsAVerb() {
        assertEquals("VERB@word-forms", candidates("came", false));
        assertEquals("VERB@word-forms", candidates("took", false));
        assertEquals("VERB@word-forms", candidates("went", false));
    }

    @Test
    void aParticipleIsAVerbThenAnAdjective() {
        assertEquals("VERB@word-forms ADJ@word-forms", candidates("taken", false));
        assertEquals("VERB@word-forms ADJ@word-forms", candidates("written", false));
        assertEquals("VERB@word-forms ADJ@word-forms", candidates("found", false), "a past equal to its participle");
    }

    @Test
    void aBaseOrItsThirdPersonIsAVerbThenANoun() {
        assertEquals("VERB@word-forms NOUN@word-forms", candidates("remain", false));
        assertEquals("VERB@word-forms NOUN@word-forms", candidates("remains", false));
        assertEquals("VERB@word-forms NOUN@word-forms", candidates("goes", false));
        assertEquals("VERB@word-forms NOUN@word-forms", candidates("run", false), "a base that is also a participle");
        assertEquals("VERB@word-forms NOUN@word-forms", candidates("set", false));
    }

    @Test
    void theThirdPersonSpelling() {
        assertEquals("fixes", WordForms.thirdPerson("fix"));
        assertEquals("goes", WordForms.thirdPerson("go"));
        assertEquals("teaches", WordForms.thirdPerson("teach"));
        assertEquals("applies", WordForms.thirdPerson("apply"));
        assertEquals("stays", WordForms.thirdPerson("stay"));
        assertEquals("says", WordForms.thirdPerson("say"));
    }

    @Test
    void aNounThatEndsLikeAnAdjectiveIsANounFirst() {
        assertEquals("NOUN@word-forms ADJ@word-forms", candidates("approval", false));
        assertEquals("NOUN@word-forms ADJ@word-forms", candidates("refusal", false));
        assertEquals("NOUN@word-forms ADJ@word-forms", candidates("agent", false));
    }

    @Test
    void anAdjectiveIsAnAdjectiveThenANoun() {
        assertEquals("ADJ@word-forms NOUN@word-forms", candidates("exact", false));
        assertEquals("ADJ@word-forms NOUN@word-forms", candidates("empty", false));
    }

    @Test
    void anyOtherWordHasNone() {
        assertEquals("", candidates("widget", false));
        assertEquals("", candidates("neutral", false), "the suffix rules give it");
        assertEquals("", candidates("merged", false), "a regular past form: the suffix rules give it");
    }

    @Test
    void aCapitalMidSentenceAddsPropnFirstAndTheCaseIsIgnored() {
        assertEquals("PROPN@capital VERB@word-forms", candidates("Came", false));
        assertEquals("VERB@word-forms", candidates("Came", true));
        assertEquals("VERB@word-forms NOUN@word-forms", candidates("REMAINS", true));
        assertEquals("", candidates("Widget", false));
    }

    @Test
    void theListsDoNotOverlap() {
        Set<String> bases = new HashSet<>();
        for (WordForms.Verb verb : WordForms.IRREGULAR) {
            assertTrue(bases.add(verb.base()), verb.base());
        }
        assertEquals(149, WordForms.IRREGULAR.size());
        for (String word : WordForms.VERBS) {
            assertTrue(!WordForms.ADJECTIVES.contains(word) && !WordForms.NOUN_LOOKALIKES.contains(word), word);
        }
        for (String word : WordForms.NOUN_LOOKALIKES) {
            assertTrue(!WordForms.ADJECTIVES.contains(word), word);
        }
        assertEquals(List.of(171, 169, 125), List.of(WordForms.VERBS.size(), WordForms.ADJECTIVES.size(),
                WordForms.NOUN_LOOKALIKES.size()));
    }
}
