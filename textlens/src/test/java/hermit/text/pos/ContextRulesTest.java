package hermit.text.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.lex.Tokenizer;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import hermit.text.model.Token.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ContextRulesTest {

    /** Each token of the text as {@code surface/TAG}: with the standard tagger, or with only these context rules. */
    private static String tags(String text, String... rules) {
        PosTagger tagger = rules.length == 0 ? PosTagger.standard()
                : new PosTagger(ClosedClass.standard(), DomainLexicon.standard(), ContextRules.only(List.of(rules)));
        return tagger.tagText(text).stream()
                .map(t -> text.substring(t.range().start(), t.range().end()) + "/" + t.tag())
                .collect(Collectors.joining(" "));
    }

    /** The tagged token whose text is {@code surface}. */
    private static Token token(String text, String surface) {
        return PosTagger.standard().tagText(text).stream()
                .filter(t -> text.substring(t.range().start(), t.range().end()).equals(surface)).findFirst()
                .orElseThrow();
    }

    @Test
    void theRulesAreTriedInTheListsOrder() {
        assertEquals(ContextRules.RULES.stream().map(ContextRules.Rule::name).toList(),
                ContextRules.standard().names());
        assertEquals(List.of("to-then-verb", "number-then-noun"),
                ContextRules.only(List.of("number-then-noun", "to-then-verb")).names());
    }

    @Test
    void anUnknownRuleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ContextRules.only(List.of("no-such-rule")));
    }

    @Test
    void toBeforeVerbIsVerb() {
        assertEquals("We/PRON want/VERB to/PART build/VERB the/DET parser/NOUN ./PUNCT",
                tags("We want to build the parser."));
    }

    @Test
    void toBeforeANounPhraseIsAdp() {
        assertEquals("It/PRON goes/VERB to/ADP the/DET server/NOUN ./PUNCT", tags("It goes to the server."));
    }

    @Test
    void aDeterminerOrAnAdjectiveThenNoun() {
        assertEquals("The/DET build/NOUN failed/VERB ./PUNCT", tags("The build failed."));
        assertEquals("It/PRON is/AUX a/DET readable/ADJ build/NOUN ./PUNCT", tags("It is a readable build."));
    }

    @Test
    void aPossessiveThenNoun() {
        assertEquals("We/PRON pushed/VERB our/PRON commit/NOUN ./PUNCT", tags("We pushed our commit."));
    }

    @Test
    void numberBeforeNounMakesNoun() {
        assertEquals("It/PRON took/VERB three/NUM runs/NOUN and/CCONJ 2/NUM builds/NOUN ./PUNCT",
                tags("It took three runs and 2 builds."));
    }

    @Test
    void anAuxiliaryThenVerbSkippingNotAndAdverbs() {
        assertEquals("The/DET suite/NOUN does/AUX not/PART build/VERB ./PUNCT", tags("The suite does not build."));
        assertEquals("It/PRON will/AUX probably/ADV run/VERB ./PUNCT", tags("It will probably run."));
    }

    @Test
    void aSubjectPronounThenVerb() {
        assertEquals("They/PRON commit/VERB often/ADV ./PUNCT", tags("They commit often."));
    }

    @Test
    void thatAfterAVerbOfSayingIsASubordinator() {
        assertEquals("The/DET log/NOUN shows/VERB that/SCONJ the/DET build/NOUN passed/VERB ./PUNCT",
                tags("The log shows that the build passed."));
    }

    @Test
    void thatBeforeANounIsADeterminer() {
        assertEquals("That/DET migration/NOUN failed/VERB ./PUNCT", tags("That migration failed."));
    }

    @Test
    void thatAfterANounIsAPronoun() {
        assertEquals("Fix/VERB the/DET test/NOUN that/PRON fails/VERB ./PUNCT", tags("Fix the test that fails."));
    }

    @Test
    void beforeASubjectPronounIsASubordinator() {
        assertEquals("It/PRON failed/VERB since/SCONJ we/PRON merged/VERB it/PRON ./PUNCT",
                tags("It failed since we merged it."));
    }

    @Test
    void beforeANounPhraseIsAnAdposition() {
        assertEquals("It/PRON failed/VERB since/ADP the/DET merge/NOUN ./PUNCT", tags("It failed since the merge."));
    }

    @Test
    void haveOrDoBeforeNotIsAnAuxiliary() {
        assertEquals("We/PRON have/AUX not/PART merged/VERB it/PRON ./PUNCT", tags("We have not merged it."));
    }

    @Test
    void haveBeforeAParticipleIsAnAuxiliary() {
        assertEquals("The/DET build/NOUN has/AUX passed/VERB ./PUNCT", tags("The build has passed."));
        assertEquals("It/PRON has/AUX been/AUX merged/VERB ./PUNCT", tags("It has been merged."));
    }

    @Test
    void haveOrDoBeforeAnObjectIsAVerb() {
        assertEquals("The/DET class/NOUN has/VERB a/DET field/NOUN ./PUNCT", tags("The class has a field."));
        assertEquals("We/PRON did/VERB it/PRON ./PUNCT", tags("We did it."));
    }

    @Test
    void aVerbBeforeADeterminer() {
        assertEquals("Hermit/PROPN runs/VERB the/DET suite/NOUN ./PUNCT", tags("Hermit runs the suite."));
        assertEquals("Commit/VERB the/DET change/NOUN ./PUNCT", tags("Commit the change."));
    }

    @Test
    void aVersionAfterANameIsANumber() {
        String text = "We use Gradle 8.10 and 9.1.2 here.";
        assertEquals(List.of(new Candidate(Tag.NUM, "version-after-a-name-is-num")), token(text, "8.10").candidates());
        assertFalse(token(text, "8.10").guessed());
        assertEquals(List.of(Tag.NUM, Tag.PROPN),
                token(text, "9.1.2").candidates().stream().map(Candidate::tag).toList());
        assertTrue(token(text, "9.1.2").guessed());
    }

    @Test
    void aRuleNeedsItsWinnerSoNeverRemovesTheLastCandidate() {
        assertEquals("The/DET deploys/VERB ./PUNCT", tags("The deploys."));
    }

    @Test
    void theWinnerNamesItsRuleAndTheOtherCandidatesStay() {
        Token running = token("Stop the running.", "running");
        assertEquals(List.of(new Candidate(Tag.ADJ, "suffix:ing"), new Candidate(Tag.NOUN, "determiner-then-noun")),
                running.candidates());
        assertTrue(running.guessed());
    }

    @Test
    void theRulesWorkLeftToRightOnTheNarrowedTokensBeforeThem() {
        assertEquals("We/PRON plan/NOUN to/ADP build/NOUN it/PRON ./PUNCT",
                tags("We plan to build it.", "to-then-verb"));
        assertEquals("We/PRON plan/NOUN to/PART build/VERB it/PRON ./PUNCT",
                tags("We plan to build it.", "to-before-verb-is-part", "to-then-verb"));
    }

    @Test
    void applyNarrowsOnlyTheCandidatesOfCopies() {
        String text = "to build";
        List<Token> given = new ArrayList<>();
        for (Token t : Tokenizer.standard().tokenize(text)) {
            given.add(new Token(t.id(), t.range(), t.shape(), List.of(new Candidate(Tag.ADP, "a"),
                    new Candidate(Tag.PART, "b"), new Candidate(Tag.VERB, "c")), true, t.provenance()));
        }
        List<Token> out = ContextRules.only(List.of("to-before-verb-is-part")).apply(text, given);
        assertEquals(List.of(new Candidate(Tag.PART, "to-before-verb-is-part"), new Candidate(Tag.VERB, "c")),
                out.get(0).candidates());
        assertEquals(given.get(1), out.get(1));
        assertEquals(given.get(0).id(), out.get(0).id());
        assertEquals(given.get(0).range(), out.get(0).range());
        assertTrue(out.get(0).guessed());
    }

    @Test
    void anAdjectiveEndingNotBeforeANounIsANoun() {
        String rule = "adjective-suffix-not-before-a-noun-is-noun";
        assertEquals("Read/VERB the/DET editorial/NOUN ./PUNCT", tags("Read the editorial.", rule));
        assertEquals("Read/VERB the/DET editorial/ADJ board/NOUN ./PUNCT", tags("Read the editorial board.", rule));
        assertEquals("It/PRON is/AUX editorial/ADJ ./PUNCT", tags("It is editorial.", rule), "after AUX it stays");
    }

    @Test
    void aDeterminerPronounBeforeAVerbIsAPronoun() {
        String rule = "determiner-pronoun-before-a-verb-is-pron";
        assertEquals("Both/PRON remain/VERB empty/ADJ ./PUNCT", tags("Both remain empty.", rule));
        assertEquals("This/PRON is/AUX fine/ADJ ./PUNCT", tags("This is fine.", rule));
        assertEquals("Both/DET tests/NOUN remain/VERB ./PUNCT", tags("Both tests remain.", rule));
    }

    @Test
    void aDeterminerPronounBeforePunctuationIsAPronoun() {
        String rule = "determiner-pronoun-before-punctuation-is-pron";
        assertEquals("We/PRON fixed/VERB that/PRON ./PUNCT", tags("We fixed that.", rule));
        assertEquals("Keep/VERB this/PRON ,/PUNCT then/ADV stop/NOUN ./PUNCT", tags("Keep this, then stop.", rule));
    }

    @Test
    void aVerbFollowsARelativePronoun() {
        assertEquals("Jobs/NOUN that/PRON stall/VERB are/AUX retried/VERB ./PUNCT",
                tags("Jobs that stall are retried.", "that-after-noun-is-pron", "relative-pronoun-then-verb"));
        assertEquals("Jobs/NOUN that/PRON stall/NOUN are/AUX retried/VERB ./PUNCT",
                tags("Jobs that stall are retried.", "that-after-noun-is-pron"));
    }

    @Test
    void asBeforeAParticipleIsASubordinator() {
        String rule = "as-before-a-participle-is-sconj";
        assertEquals("Keep/VERB it/PRON as/SCONJ written/VERB ./PUNCT", tags("Keep it as written.", rule));
        assertEquals("Keep/VERB it/PRON as/SCONJ planned/VERB ./PUNCT", tags("Keep it as planned.", rule));
        assertEquals("Keep/VERB it/PRON as/ADP a/DET key/NOUN ./PUNCT", tags("Keep it as a key.", rule));
    }

    @Test
    void aParticipleBeforeANounIsAnAdjective() {
        String rule = "participle-before-a-noun-is-adj";
        assertEquals("Remove/VERB the/DET broken/ADJ test/NOUN ./PUNCT", tags("Remove the broken test.", rule));
        assertEquals("Remove/VERB the/DET failing/ADJ test/NOUN ./PUNCT", tags("Remove the failing test.", rule));
        assertEquals("The/DET parser/NOUN merged/VERB lists/NOUN ./PUNCT", tags("The parser merged lists.", rule),
                "after a subject it stays a verb");
    }

    @Test
    void aKnownVerbStartsASentence() {
        String rule = "known-verb-starts-a-sentence";
        assertEquals("Returns/VERB the/DET value/NOUN ./PUNCT", tags("Returns the value.", rule));
        assertEquals("Widgets/NOUN the/DET value/NOUN ./PUNCT", tags("Widgets the value.", rule), "no known verb");
        assertEquals("known-verb-starts-a-sentence", token("Returns the value.", "Returns").candidates().get(0).rule(),
                "the rules added after G1 are tried first");
    }

    @Test
    void aKnownSVerbFollowsANounSubject() {
        String rule = "noun-then-known-s-verb";
        assertEquals("The/DET suite/NOUN passes/VERB in/ADP CI/PROPN ./PUNCT", tags("The suite passes in CI.", rule));
        assertEquals("The/DET suite/NOUN gizmos/NOUN in/ADP CI/PROPN ./PUNCT", tags("The suite gizmos in CI.", rule));
    }

    @Test
    void aVerbFollowsAPluralNoun() {
        String rule = "plural-noun-then-verb";
        assertEquals("Builds/NOUN stall/VERB on/ADP Mondays/PROPN ./PUNCT", tags("Builds stall on Mondays.", rule));
        assertEquals("Builds/NOUN stall/NOUN ./PUNCT", tags("Builds stall.", rule));
    }

    @Test
    void aVerbFollowsAndAfterAVerb() {
        String rule = "and-then-verb";
        assertEquals("The/DET parser/NOUN reads/VERB and/CCONJ caches/VERB the/DET file/NOUN ./PUNCT",
                tags("The parser reads and caches the file.", rule));
        assertEquals("The/DET parser/NOUN and/CCONJ caches/NOUN of/ADP the/DET file/NOUN ./PUNCT",
                tags("The parser and caches of the file.", rule), "no verb before the and");
    }

    @Test
    void aVerbBeforeAnArticleOnly() {
        String rule = "verb-before-determiner";
        assertEquals("Logs/VERB the/DET error/NOUN ./PUNCT", tags("Logs the error.", rule));
        assertEquals("Logs/NOUN that/DET fail/VERB ./PUNCT", tags("Logs that fail.", rule), "that is no article");
    }

    @Test
    void aVerbBeforeAnObjectPronoun() {
        String rule = "verb-before-an-object-pronoun";
        assertEquals("Then/ADV sort/VERB them/PRON by/ADP size/NOUN ./PUNCT", tags("Then sort them by size.", rule),
                "the owner, 2026-11-06: sort before them is no noun");
        assertEquals("Just/ADV call/VERB me/PRON ./PUNCT", tags("Just call me.", rule));
        assertEquals("Sort/NOUN it/PRON by/ADP size/NOUN ./PUNCT", tags("Sort it by size.", rule),
                "it may be a subject");
    }

    @Test
    void anImperativeVerbAfterLeadingAdverbsOrPlease() {
        String rule = "known-verb-starts-a-sentence";
        assertEquals("Just/ADV call/VERB Julie/PROPN ./PUNCT", tags("Just call Julie.", rule),
                "the owner, 2026-11-06: an imperative after an adverb");
        assertEquals("Please/INTJ call/VERB Julie/PROPN tomorrow/NOUN ./PUNCT",
                tags("Please call Julie tomorrow.", rule));
        assertEquals("Just/ADV sort/VERB files/NOUN by/ADP size/NOUN ./PUNCT", tags("Just sort files by size.", rule),
                "a marker, then a verb's base form, before any token");
        assertEquals("Call/NOUN logs/NOUN are/AUX kept/VERB ./PUNCT", tags("Call logs are kept.", rule),
                "no marker, and a noun after it");
    }
}
