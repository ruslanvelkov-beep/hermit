package hermit.text.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.lex.SentenceSplitter;
import hermit.text.lex.Tokenizer;
import hermit.text.model.Sentence;
import hermit.text.model.Sentence.Boundary;
import hermit.text.model.Sentence.Kind;
import hermit.text.model.Tag;
import hermit.text.model.TextRange;
import hermit.text.model.Token;
import hermit.text.model.Token.Candidate;
import hermit.text.model.Token.Shape;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PosTaggerTest {

    private static final PosTagger TAGGER = PosTagger.standard();

    /** The tagged token whose text is {@code surface}. */
    private static Token token(String text, String surface) {
        return TAGGER.tagText(text).stream()
                .filter(t -> text.substring(t.range().start(), t.range().end()).equals(surface)).findFirst()
                .orElseThrow();
    }

    /** Each token of the text as {@code surface/TAG}. */
    private static String tags(String text) {
        return TAGGER.tagText(text).stream()
                .map(t -> text.substring(t.range().start(), t.range().end()) + "/" + t.tag())
                .collect(Collectors.joining(" "));
    }

    @Test
    void codeNamesArePropn() {
        String text = "Open src/main/X.java; call RepeatGuard#signature at AgentLoop.java:1281 after d4016e8 in "
                + "processRequest.";
        Set<Shape> seen = EnumSet.noneOf(Shape.class);
        for (Token t : TAGGER.tagText(text)) {
            if (t.shape() != Shape.WORD && t.shape() != Shape.PUNCT) {
                assertEquals(List.of(new Candidate(Tag.PROPN, "shape:" + t.shape())), t.candidates(), t.toString());
                seen.add(t.shape());
            }
        }
        assertEquals(EnumSet.of(Shape.PATH, Shape.MEMBER_REF, Shape.FILE_LINE, Shape.HASH, Shape.IDENT), seen);
    }

    @Test
    void numbersAreNumAndFlagsArePropn() {
        String text = "Run ./gradlew test --offline -q on 3,500 cases.";
        assertEquals(List.of(new Candidate(Tag.NUM, "shape:NUMBER")), token(text, "3,500").candidates());
        assertEquals(List.of(new Candidate(Tag.PROPN, "shape:FLAG")), token(text, "--offline").candidates());
        assertEquals(Tag.PROPN, token(text, "-q").tag());
        assertFalse(token(text, "-q").guessed());
    }

    @Test
    void aVersionWithALetterIsPropnAndOneOfDigitsNumThenPropn() {
        String text = "Qwen3.8-27B, v3 and 2.21.1";
        assertEquals(List.of(new Candidate(Tag.PROPN, "shape:VERSION")), token(text, "Qwen3.8-27B").candidates());
        assertEquals(Tag.PROPN, token(text, "v3").tag());
        assertEquals(List.of(new Candidate(Tag.NUM, "shape:VERSION"), new Candidate(Tag.PROPN, "shape:VERSION")),
                token(text, "2.21.1").candidates());
        assertTrue(token(text, "2.21.1").guessed());
    }

    @Test
    void aCodeSpanNameIsPropnAndAStatementIsNoun() {
        String text = "Call `parse` or run `git log -1` now.";
        assertEquals(List.of(new Candidate(Tag.PROPN, "shape:CODE_SPAN")), token(text, "`parse`").candidates());
        assertEquals(List.of(new Candidate(Tag.NOUN, "shape:CODE_SPAN")), token(text, "`git log -1`").candidates());
    }

    @Test
    void punctuationIsPunctAndSymbolsAreSym() {
        String text = "a -> b != c.";
        assertEquals(Tag.SYM, token(text, "->").tag());
        assertEquals(Tag.SYM, token(text, "!=").tag());
        assertEquals(List.of(new Candidate(Tag.PUNCT, "shape:PUNCT")), token(text, ".").candidates());
    }

    @Test
    void theLexiconsComeBeforeTheSuffixes() {
        String text = "Nothing changed during the run, only enable and implement the comment.";
        assertEquals(List.of(new Candidate(Tag.PRON, "closed-class")), token(text, "Nothing").candidates());
        assertEquals(List.of(new Candidate(Tag.ADP, "closed-class")), token(text, "during").candidates());
        assertEquals(List.of(new Candidate(Tag.ADV, "closed-class")), token(text, "only").candidates());
        assertEquals(List.of(new Candidate(Tag.VERB, "domain")), token(text, "enable").candidates());
        assertEquals(List.of(new Candidate(Tag.VERB, "domain")), token(text, "implement").candidates());
        assertEquals(List.of(new Candidate(Tag.NOUN, "determiner-then-noun")), token(text, "comment").candidates());
    }

    @Test
    void finiteVerbsAndSubjectsInAReport() {
        assertEquals("The/DET parser/NOUN returns/VERB a/DET list/NOUN ./PUNCT It/PRON fails/VERB when/SCONJ we/PRON "
                + "merge/VERB it/PRON ./PUNCT", tags("The parser returns a list. It fails when we merge it."));
    }

    @Test
    void aTokenIsGuessedOnlyWhenCandidatesRemain() {
        for (Token t : TAGGER.tagText("The parser returns a list.")) {
            assertFalse(t.guessed(), t.toString());
        }
        Token merged = token("It merged 3 runs.", "merged");
        assertEquals(List.of(Tag.VERB, Tag.ADJ), merged.candidates().stream().map(Candidate::tag).toList());
        assertTrue(merged.guessed());
        assertFalse(token("It took 3 runs.", "took").guessed(), "a past form of the word forms is a verb only");
    }

    @Test
    void everyTokenKeepsACandidate() {
        String text = "See https://example.org/x, `a b` and -Dk=v: 1e-9 > 0, isn't it? — yes.";
        for (Token t : TAGGER.tagText(text)) {
            assertFalse(t.candidates().isEmpty(), t.toString());
        }
    }

    @Test
    void theTaggedTokensKeepTheirIdsRangesShapesAndProvenance() {
        String text = "Edit TreeEdit.java, then run it.";
        List<Token> tokens = Tokenizer.standard().tokenize(text);
        List<Token> tagged = TAGGER.tag(text, tokens, SentenceSplitter.standard().split(text, tokens));
        assertEquals(tokens.size(), tagged.size());
        for (int i = 0; i < tokens.size(); i++) {
            assertEquals(tokens.get(i).id(), tagged.get(i).id());
            assertEquals(tokens.get(i).range(), tagged.get(i).range());
            assertEquals(tokens.get(i).shape(), tagged.get(i).shape());
            assertEquals(tokens.get(i).provenance(), tagged.get(i).provenance());
        }
    }

    @Test
    void aCapitalisedWordMidSentenceIsPropnFirst() {
        assertEquals("We/PRON asked/VERB Zephyr/PROPN twice/ADV ./PUNCT", tags("We asked Zephyr twice."));
        Token first = token("Zephyr left.", "Zephyr");
        assertEquals(List.of(Tag.NOUN, Tag.VERB, Tag.ADJ), first.candidates().stream().map(Candidate::tag).toList());
        assertTrue(first.guessed());
    }

    @Test
    void theSentenceStartComesFromTheSentences() {
        String text = "Done. Zephyr left.";
        List<Token> tokens = Tokenizer.standard().tokenize(text);
        Sentence one = new Sentence("s1", new TextRange(0, text.length()), Kind.PROSE, Boundary.CERTAIN,
                tokens.stream().map(Token::id).toList());
        assertEquals(Tag.PROPN, TAGGER.tag(text, tokens, List.of(one)).get(2).tag());
        assertEquals(Tag.NOUN, TAGGER.tag(text, tokens, SentenceSplitter.standard().split(text, tokens)).get(2).tag());
    }

    @Test
    void aCodeSpanIsANounOnlyWhenItReadsAsACommand() {
        String text = "Run `./gradlew test`, `bash -n` or `|| true`; see `a.graph -> a.sym`, `static Foo bar`, "
                + "`x = 1`, `GET /v1/models` and `Type#parse(cursor, node)`.";
        for (String command : List.of("`./gradlew test`", "`bash -n`", "`|| true`")) {
            assertEquals(List.of(new Candidate(Tag.NOUN, "shape:CODE_SPAN")), token(text, command).candidates(),
                    command);
        }
        for (String name : List.of("`a.graph -> a.sym`", "`static Foo bar`", "`x = 1`", "`GET /v1/models`",
                "`Type#parse(cursor, node)`")) {
            assertEquals(List.of(new Candidate(Tag.PROPN, "shape:CODE_SPAN")), token(text, name).candidates(), name);
        }
    }

    @Test
    void theWordFormsComeAfterTheLexiconsAndBeforeTheSuffixes() {
        assertEquals(List.of(new Candidate(Tag.VERB, "word-forms")),
                token("The suite came back.", "came").candidates());
        assertEquals("word-forms", token("Ask for approval first.", "approval").candidates().get(0).rule());
        assertEquals(List.of(new Candidate(Tag.VERB, "domain")), token("It may contain bugs.", "contain").candidates(),
                "the domain lexicon first");
        assertEquals("suffix:al", token("It is logical.", "logical").candidates().get(0).rule());
    }

    @Test
    void aRerankerChoosesAmongTheCandidatesTheRulesLeave() {
        Reranker verbs = new Reranker(Map.of("w=pass", Map.of(Tag.VERB, 1.0)));
        PosTagger tagger =
                new PosTagger(ClosedClass.standard(), DomainLexicon.standard(), ContextRules.standard(), verbs);
        String text = "The core tests pass.";
        Token pass = tagger.tagText(text).get(3);
        assertEquals(List.of(new Candidate(Tag.VERB, "rerank:domain"), new Candidate(Tag.NOUN, "domain")),
                pass.candidates());
        assertEquals(Tag.NOUN, token(text, "pass").tag(), "the standard tagger has no reranker without the property");
    }

    @Test
    void theStandardTaggerLoadsTheNamedWeights(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("weights.jsonl");
        Files.writeString(file, "{\"feature\": \"w=pass\", \"weights\": {\"VERB\": 1.0}}\n");
        String before = System.getProperty(Reranker.WEIGHTS_PROPERTY);
        try {
            System.setProperty(Reranker.WEIGHTS_PROPERTY, file.toString());
            assertEquals(Tag.VERB, PosTagger.standard().tagText("The core tests pass.").get(3).tag());
        } finally {
            if (before == null) {
                System.clearProperty(Reranker.WEIGHTS_PROPERTY);
            } else {
                System.setProperty(Reranker.WEIGHTS_PROPERTY, before);
            }
        }
    }

    @Test
    void aSentenceSearchChoosesTheTagsInPlaceOfTheReranker() {
        Reranker verbs = new Reranker(Map.of("w=pass", Map.of(Tag.VERB, 1.0)));
        SentenceSearch nouns = new SentenceSearch(new SearchModel(Map.of("w=pass", Map.of(Tag.NOUN, 10.0)), Map.of(),
                Map.of(), Map.of()), ContextRules.standard());
        PosTagger tagger = new PosTagger(ClosedClass.standard(), DomainLexicon.standard(), ContextRules.standard(),
                verbs, nouns);
        assertEquals(List.of(new Candidate(Tag.NOUN, "domain"), new Candidate(Tag.VERB, "domain")),
                tagger.tagText("The core tests pass.").get(3).candidates());
        SentenceSearch searchVerbs = new SentenceSearch(new SearchModel(Map.of("w=pass", Map.of(Tag.VERB, 10.0)),
                Map.of(), Map.of(), Map.of()), ContextRules.standard());
        PosTagger searching = new PosTagger(ClosedClass.standard(), DomainLexicon.standard(), ContextRules.standard(),
                null, searchVerbs);
        assertEquals(List.of(new Candidate(Tag.VERB, "search:domain"), new Candidate(Tag.NOUN, "domain")),
                searching.tagText("The core tests pass.").get(3).candidates());
    }

    @Test
    void theStandardTaggerLoadsTheNamedSearchModel(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("search.jsonl");
        Files.writeString(file, "{\"kind\": \"weight\", \"feature\": \"w=pass\", \"weights\": {\"VERB\": 10.0}}\n");
        String before = System.getProperty(SearchModel.MODEL_PROPERTY);
        try {
            System.setProperty(SearchModel.MODEL_PROPERTY, file.toString());
            assertEquals(Tag.VERB, PosTagger.standard().tagText("The core tests pass.").get(3).tag());
        } finally {
            if (before == null) {
                System.clearProperty(SearchModel.MODEL_PROPERTY);
            } else {
                System.setProperty(SearchModel.MODEL_PROPERTY, before);
            }
        }
        assertEquals(Tag.NOUN, PosTagger.standard().tagText("The core tests pass.").get(3).tag(),
                "no search without the property");
    }

    @Test
    void aCommandSubstitutionIsACommandWithoutWhitespace() {
        String text = "Pass `$(list_drafts)` to the loop.";
        assertEquals(List.of(new Candidate(Tag.NOUN, "shape:CODE_SPAN")), token(text, "`$(list_drafts)`").candidates());
    }

    @Test
    void aCompoundWithANounLastPartIsANounWithoutAnAdjectiveReading() {
        Token compound = token("We sorted the single-shelf archive.", "single-shelf");
        assertEquals(Tag.NOUN, compound.tag());
        assertTrue(compound.candidates().stream().noneMatch(c -> c.tag() == Tag.ADJ), compound.toString());
    }

    @Test
    void aCompoundWithAParticipleLastPartIsAnAdjectiveBeforeANoun() {
        assertEquals(List.of(new Candidate(Tag.VERB, "word-forms"), new Candidate(Tag.ADJ, "word-forms")),
                TAGGER.compoundCandidates("hand-written"));
        assertEquals(Tag.ADJ, token("Keep the hand-written notes.", "hand-written").tag());
    }

    @Test
    void aListedCompoundIsAnAdjective() {
        assertEquals(List.of(new Candidate(Tag.ADJ, "compound:adjective")), TAGGER.compoundCandidates("Low-Level"));
        assertEquals(Tag.ADJ, token("Use the low-level reader.", "low-level").tag());
    }

    @Test
    void aPrefixCompoundIsAnAdjective() {
        assertEquals(List.of(new Candidate(Tag.ADJ, "compound:adjective")), TAGGER.compoundCandidates("per-user"));
        assertEquals(Tag.ADJ, token("The non-empty queue waits.", "non-empty").tag());
    }

    @Test
    void anAdjectiveLastPartMakesACompoundAnAdjectiveBeforeTheSuffixRules() {
        assertEquals(List.of(new Candidate(Tag.ADJ, "compound:adjective")),
                token("Keep a local-only copy.", "local-only").candidates());
    }

    @Test
    void aCompoundEndingInAParticleIsANoun() {
        assertEquals(List.of(new Candidate(Tag.NOUN, "compound:particle")), TAGGER.compoundCandidates("follow-up"));
        assertEquals(Tag.NOUN, token("Book the follow-up visit.", "follow-up").tag());
    }

    @Test
    void aCompoundOfThreeOrMorePartsIsANounPhrase() {
        assertEquals(List.of(new Candidate(Tag.NOUN, "compound:phrase")), TAGGER.compoundCandidates("ask-then-wait"));
    }

    @Test
    void aCompoundTakesItsLastPartsCandidatesAndANounOnlyPartGainsAVerb() {
        assertEquals(List.of(new Candidate(Tag.VERB, "word-forms"), new Candidate(Tag.NOUN, "word-forms")),
                TAGGER.compoundCandidates("re-send"));
        assertEquals(List.of(new Candidate(Tag.NOUN, "domain"), new Candidate(Tag.VERB, "compound:open")),
                TAGGER.compoundCandidates("sub-repository"));
    }

    @Test
    void aCapitalDoesNotMakeACompoundPropn() {
        Token compound = token("Read the Retry-Budget notes.", "Retry-Budget");
        assertTrue(compound.candidates().stream().noneMatch(c -> c.tag() == Tag.PROPN), compound.toString());
    }

    @Test
    void aWordWithoutAnInnerHyphenIsNoCompound() {
        assertEquals(List.of(), TAGGER.compoundCandidates("plain"));
        assertEquals(List.of(), TAGGER.compoundCandidates("-flag"));
        assertEquals(List.of(), TAGGER.compoundCandidates("trailing-"));
    }

    /** The tags of the token whose text is {@code surface}, in order. */
    private static List<Tag> candidates(String text, String surface) {
        return token(text, surface).candidates().stream().map(Candidate::tag).toList();
    }

    @Test
    void theCliticSKeepsBothReadingsUnlessItsContextSettlesIt() {
        assertEquals(List.of(Tag.AUX), candidates("It's done.", "'s"), "after a pronoun");
        assertEquals(List.of(Tag.AUX), candidates("The build's been fixed.", "'s"), "before been");
        assertEquals(List.of(Tag.AUX, Tag.PART), candidates("The build's failing again.", "'s"), "before a verb form");
        assertEquals(List.of(Tag.PART, Tag.AUX), candidates("The agent's log is empty.", "'s"), "a possessive first");
        assertEquals(List.of(Tag.AUX), candidates("We'll ship it.", "'ll"));
    }
}
