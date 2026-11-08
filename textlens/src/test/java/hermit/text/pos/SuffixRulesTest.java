package hermit.text.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SuffixRulesTest {

    /** The candidates as {@code TAG@rule}, separated by spaces. */
    private static String candidates(String word, boolean sentenceStart) {
        return SuffixRules.candidates(word, sentenceStart).stream().map(c -> c.tag() + "@" + c.rule())
                .collect(Collectors.joining(" "));
    }

    @Test
    void suffixAmbiguityRetained() {
        assertEquals("VERB@suffix:ed ADJ@suffix:ed NOUN@suffix:ed", candidates("merged", false));
        assertEquals("VERB@suffix:ing ADJ@suffix:ing NOUN@suffix:ing", candidates("parsing", false));
    }

    @Test
    void eachSuffixGivesItsClass() {
        assertEquals("ADV@suffix:ly", candidates("quickly", false));
        assertEquals("NOUN@suffix:tion", candidates("migration", false));
        assertEquals("NOUN@suffix:ment", candidates("deployment", false));
        assertEquals("NOUN@suffix:ness", candidates("readiness", false));
        assertEquals("NOUN@suffix:ity", candidates("stability", false));
        assertEquals("ADJ@suffix:able", candidates("readable", false));
        assertEquals("ADJ@suffix:ible", candidates("visible", false));
        assertEquals("ADJ@suffix:ful", candidates("useful", false));
        assertEquals("ADJ@suffix:less", candidates("stateless", false));
        assertEquals("ADJ@suffix:ous", candidates("various", false));
    }

    @Test
    void aWordWithNoSuffixGetsTheOpenClass() {
        assertEquals("NOUN@open-class VERB@open-class ADJ@open-class", candidates("widget", false));
    }

    @Test
    void aShortStemTakesNoSuffix() {
        for (String word : List.of("red", "thing", "fly", "only")) {
            assertEquals("NOUN@open-class VERB@open-class ADJ@open-class", candidates(word, false), word);
        }
    }

    @Test
    void theFirstSuffixInTheListWins() {
        assertEquals("NOUN@suffix:'s", candidates("building's", false));
    }

    @Test
    void aPossessiveIsANounAndATypographicApostropheCounts() {
        assertEquals("NOUN@suffix:'s", candidates("parser’s", false));
    }

    @Test
    void aCapitalMidSentenceAddsPropnFirst() {
        assertEquals("PROPN@capital NOUN@open-class VERB@open-class ADJ@open-class", candidates("Mira", false));
        assertEquals("PROPN@capital VERB@suffix:ing ADJ@suffix:ing NOUN@suffix:ing", candidates("Parsing", false));
    }

    @Test
    void aCapitalAtTheSentenceStartAddsNothing() {
        assertEquals("NOUN@open-class VERB@open-class ADJ@open-class", candidates("Mira", true));
        assertEquals("NOUN@suffix:'s", candidates("Mira's", true));
    }

    @Test
    void theAdjectiveEndingsComeLastAndStayAmbiguous() {
        assertEquals("ADJ@suffix:al NOUN@suffix:al", candidates("logical", false));
        assertEquals("ADJ@suffix:ic NOUN@suffix:ic", candidates("holistic", false));
        assertEquals("ADJ@suffix:ory NOUN@suffix:ory", candidates("advisory", false));
        assertEquals("ADJ@suffix:ant NOUN@suffix:ant", candidates("significant", false));
        assertEquals("ADJ@suffix:ar NOUN@suffix:ar", candidates("similar", false));
        assertEquals("NOUN@suffix:ment", candidates("argument", false), "-ment comes before -ent");
    }
}
