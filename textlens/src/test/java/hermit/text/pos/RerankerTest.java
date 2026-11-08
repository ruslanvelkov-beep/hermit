package hermit.text.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.Provenance;
import hermit.text.model.Tag;
import hermit.text.model.TextRange;
import hermit.text.model.Token;
import hermit.text.model.Token.Candidate;
import hermit.text.model.Token.Shape;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RerankerTest {

    private static final String TEXT = "We run tests.";
    private static final String WEIGHTS = String.join("\n",
            "{\"model\": \"reranker\", \"license\": \"CC BY-SA 4.0\"}",
            "{\"feature\": \"b\", \"weights\": {\"NOUN\": 0.5, \"VERB\": -0.25}}",
            "",
            "{\"feature\": \"w=run\", \"weights\": {\"VERB\": 2.0, \"FOO\": 9.0}}") + "\n";

    /** A token of {@code shape} over {@code start} to {@code end}, its candidates proposed by the rule {@code test}. */
    private static Token token(String id, int start, int end, Shape shape, boolean guessed, Tag... tags) {
        List<Candidate> candidates = Arrays.stream(tags).map(tag -> new Candidate(tag, "test")).toList();
        return new Token(id, new TextRange(start, end), shape, candidates, guessed, Provenance.rule("tokenizer"));
    }

    /** The rule-tagged tokens of {@link #TEXT}: We/PRON run/VERB,NOUN tests/NOUN,VERB ./PUNCT. */
    private static List<Token> tokens() {
        return List.of(token("t1", 0, 2, Shape.WORD, false, Tag.PRON),
                token("t2", 3, 6, Shape.WORD, true, Tag.VERB, Tag.NOUN),
                token("t3", 7, 12, Shape.WORD, true, Tag.NOUN, Tag.VERB),
                token("t4", 12, 13, Shape.PUNCT, false, Tag.PUNCT));
    }

    @Test
    void featuresAsTrained() {
        assertEquals(List.of("b", "w=run", "s1=n", "s2=un", "s3=run", "p1=r", "t1=PRON", "t2=PRON|<s>",
                "t1w=PRON|run", "pw=we", "nw=tests", "nw2=.", "ns3=sts", "ps3=we", "pw_w=we|run", "w_nw=run|tests",
                "first=False", "r1=VERB", "rc=VERB|NOUN", "r1w=VERB|run", "g=True", "r1t1=VERB|PRON"),
                Reranker.features(TEXT, tokens(), 1));
    }

    @Test
    void theFirstTokenAndTheShapeFeatures() {
        String text = "A.B_c-1";
        List<Token> one = List.of(token("t1", 0, 7, Shape.WORD, true, Tag.NOUN, Tag.PROPN));
        assertEquals(List.of("b", "w=a.b_c-1", "s1=1", "s2=-1", "s3=c-1", "p1=a", "t1=<s>", "t2=<s>|<s2>",
                "t1w=<s>|a.b_c-1", "pw=<s>", "nw=</s>", "nw2=</s>", "ns3=/s>", "ps3=<s>", "pw_w=<s>|a.b_c-1",
                "w_nw=a.b_c-1|</s>", "first=True", "sh=cap", "sh=digit", "sh=hyph", "sh=code", "r1=NOUN",
                "rc=NOUN|PROPN", "r1w=NOUN|a.b_c-1", "g=True", "r1t1=NOUN|<s>"), Reranker.features(text, one, 0));
        List<String> gpu = Reranker.features("GPU", List.of(token("t1", 0, 3, Shape.WORD, true, Tag.PROPN, Tag.NOUN)),
                0);
        assertTrue(gpu.contains("sh=cap") && gpu.contains("sh=allcap"));
        assertFalse(gpu.contains("sh=code"));
    }

    @Test
    void scoreSumsTheWeights() {
        Reranker reranker = new Reranker(Map.of("b", Map.of(Tag.NOUN, 0.5, Tag.VERB, -0.25),
                "w=run", Map.of(Tag.VERB, 2.0)));
        List<String> features = List.of("b", "w=run", "w=unknown");
        assertEquals(1.75, reranker.score(features, Tag.VERB), 1e-9);
        assertEquals(0.5, reranker.score(features, Tag.NOUN), 1e-9);
        assertEquals(0.0, reranker.score(features, Tag.ADJ), 1e-9);
    }

    @Test
    void theHighestScoreWinsAndATieKeepsTheRulesOrder() {
        Candidate noun = new Candidate(Tag.NOUN, "test");
        Candidate verb = new Candidate(Tag.VERB, "test");
        Reranker verbs = new Reranker(Map.of("w=run", Map.of(Tag.VERB, 1.0)));
        assertSame(verb, verbs.choose(List.of("w=run"), List.of(noun, verb)));
        Reranker even = new Reranker(Map.of("w=run", Map.of(Tag.VERB, 1.0, Tag.NOUN, 1.0)));
        assertSame(noun, even.choose(List.of("w=run"), List.of(noun, verb)));
        assertSame(verb, even.choose(List.of("w=run"), List.of(verb, noun)));
        assertSame(noun, even.choose(List.of("w=other"), List.of(noun, verb)));
    }

    @Test
    void theChoiceMovesToTheFrontWithItsRuleMarked() {
        List<Token> tokens = tokens();
        List<Token> out = new Reranker(Map.of("w=run", Map.of(Tag.NOUN, 1.0))).rerank(TEXT, tokens);
        assertEquals(List.of(new Candidate(Tag.NOUN, "rerank:test"), new Candidate(Tag.VERB, "test")),
                out.get(1).candidates());
        assertTrue(out.get(1).guessed());
        assertEquals(tokens.get(1).id(), out.get(1).id());
        assertEquals(tokens.get(1).range(), out.get(1).range());
        assertSame(tokens.get(0), out.get(0));
        assertSame(tokens.get(2), out.get(2), "a choice that is already first leaves the token as it is");
        assertSame(tokens.get(3), out.get(3));
    }

    @Test
    void onlyAWordWithTwoOrMoreCandidatesIsReranked() {
        String text = "Use RepeatGuard now";
        List<Token> tokens = List.of(token("t1", 0, 3, Shape.WORD, false, Tag.VERB),
                token("t2", 4, 15, Shape.IDENT, true, Tag.PROPN, Tag.NOUN),
                token("t3", 16, 19, Shape.WORD, true, Tag.ADV, Tag.NOUN));
        Reranker nouns = new Reranker(Map.of("b", Map.of(Tag.NOUN, 1.0)));
        List<Token> out = nouns.rerank(text, tokens);
        assertSame(tokens.get(0), out.get(0));
        assertSame(tokens.get(1), out.get(1));
        assertEquals(Tag.NOUN, out.get(2).tag());
    }

    @Test
    void aTokenThatAPreferenceOrderedIsNotReranked() {
        String text = "the agent 's log";
        Token s = token("t3", 10, 12, Shape.WORD, true, Tag.PART, Tag.AUX);
        Token preferred = new Token(s.id(), s.range(), s.shape(), List.of(new Candidate(Tag.PART,
                "clitic-s-prefers-possessive"), new Candidate(Tag.AUX, "closed-class")), true, s.provenance());
        List<Token> tokens = List.of(token("t1", 0, 3, Shape.WORD, false, Tag.DET),
                token("t2", 4, 9, Shape.WORD, false, Tag.NOUN), preferred, token("t4", 13, 16, Shape.WORD, false,
                Tag.NOUN));
        List<Token> out = new Reranker(Map.of("b", Map.of(Tag.AUX, 1.0))).rerank(text, tokens);
        assertSame(preferred, out.get(2));
    }

    @Test
    void theFeaturesUseTheRulesTagsNotEarlierChoices() {
        Reranker reranker = new Reranker(Map.of("w=run", Map.of(Tag.NOUN, 1.0), "t1=NOUN", Map.of(Tag.VERB, 5.0)));
        List<Token> out = reranker.rerank(TEXT, tokens());
        assertEquals(Tag.NOUN, out.get(1).tag());
        assertEquals(Tag.NOUN, out.get(2).tag(), "tests still follows a VERB, as the rules tagged run");
    }

    @Test
    void loadReadsPlainAndGzippedWeights(@TempDir Path dir) throws IOException {
        Path plain = dir.resolve("weights.jsonl");
        Files.writeString(plain, WEIGHTS);
        Path zipped = dir.resolve("weights.jsonl.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(zipped))) {
            out.write(WEIGHTS.getBytes(StandardCharsets.UTF_8));
        }
        for (Path path : List.of(plain, zipped)) {
            Reranker reranker = Reranker.load(path).orElseThrow();
            assertEquals(1.75, reranker.score(List.of("b", "w=run"), Tag.VERB), 1e-9);
            assertEquals(0.5, reranker.score(List.of("b", "w=run"), Tag.NOUN), 1e-9);
        }
        assertEquals(Optional.empty(), Reranker.load(dir.resolve("missing.jsonl")));
    }

    @Test
    void theNamedFileIsLoadedOnceAndOnlyWhenPresent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("weights.jsonl");
        Files.writeString(file, WEIGHTS);
        String before = System.getProperty(Reranker.WEIGHTS_PROPERTY);
        try {
            System.clearProperty(Reranker.WEIGHTS_PROPERTY);
            assertEquals(Optional.empty(), Reranker.fromSystemProperties());
            System.setProperty(Reranker.WEIGHTS_PROPERTY, dir.resolve("missing.jsonl").toString());
            assertEquals(Optional.empty(), Reranker.fromSystemProperties());
            System.setProperty(Reranker.WEIGHTS_PROPERTY, file.toString());
            Reranker first = Reranker.fromSystemProperties().orElseThrow();
            assertSame(first, Reranker.fromSystemProperties().orElseThrow());
            assertEquals(1.75, first.score(List.of("b", "w=run"), Tag.VERB), 1e-9);
        } finally {
            if (before == null) {
                System.clearProperty(Reranker.WEIGHTS_PROPERTY);
            } else {
                System.setProperty(Reranker.WEIGHTS_PROPERTY, before);
            }
        }
    }
}
