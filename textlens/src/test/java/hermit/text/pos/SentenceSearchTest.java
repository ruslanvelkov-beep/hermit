package hermit.text.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.Provenance;
import hermit.text.model.Sentence;
import hermit.text.model.Sentence.Boundary;
import hermit.text.model.Sentence.Kind;
import hermit.text.model.Tag;
import hermit.text.model.TextRange;
import hermit.text.model.Token;
import hermit.text.model.Token.Candidate;
import hermit.text.model.Token.Shape;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SentenceSearchTest {

    /** Candidates of {@code tags}, each proposed by the rule {@code test}. */
    private static List<Candidate> candidates(Tag... tags) {
        return Arrays.stream(tags).map(tag -> new Candidate(tag, "test")).toList();
    }

    /** A search with these weights and no shares, lemmas or exceptions, and no context rules. */
    private static SentenceSearch search(Map<String, Map<Tag, Double>> weights) {
        return new SentenceSearch(new SearchModel(weights, Map.of(), Map.of(), Map.of()), ContextRules.only(List.of()));
    }

    @Test
    void theSharedFeaturesAsTrained() {
        List<String> words = List.of("We", "run", "tests", ".");
        List<List<Candidate>> candidates = List.of(candidates(Tag.PRON), candidates(Tag.VERB, Tag.NOUN),
                candidates(Tag.NOUN, Tag.VERB), candidates(Tag.PUNCT));
        assertEquals(List.of("b", "w=run", "s1=n", "s2=un", "s3=run", "p1=r", "pw=we", "nw=tests", "nw2=.",
                        "ns3=sts", "ps3=we", "pw_w=we|run", "w_nw=run|tests", "first=False", "cs=NOUN|VERB",
                        "pcs=PRON", "ncs=NOUN|VERB"),
                SentenceSearch.sharedFeatures(words, candidates, 1));
        assertEquals(List.of("b", "w=we", "s1=e", "s2=we", "s3=we", "p1=w", "pw=<s>", "nw=run", "nw2=tests",
                        "ns3=run", "ps3=<s>", "pw_w=<s>|we", "w_nw=we|run", "first=True", "cs=PRON", "pcs=<s>",
                        "ncs=NOUN|VERB", "sh=cap"),
                SentenceSearch.sharedFeatures(words, candidates, 0));
        assertEquals(List.of("b", "w=tests", "s1=s", "s2=ts", "s3=sts", "p1=t", "pw=run", "nw=.", "nw2=</s>",
                        "ns3=.", "ps3=run", "pw_w=run|tests", "w_nw=tests|.", "first=False", "cs=NOUN|VERB",
                        "pcs=NOUN|VERB", "ncs=PUNCT"),
                SentenceSearch.sharedFeatures(words, candidates, 2));
    }

    @Test
    void theShapeFeaturesAndSuffixesOfCodePoints() {
        List<List<Candidate>> one = List.of(candidates(Tag.NOUN, Tag.PROPN));
        List<String> code = SentenceSearch.sharedFeatures(List.of("A.B_c-1"), one, 0);
        assertEquals(List.of("sh=cap", "sh=digit", "sh=hyph", "sh=code"), code.subList(code.indexOf("sh=cap"),
                code.size()));
        List<String> capitals = SentenceSearch.sharedFeatures(List.of("NASA"), one, 0);
        assertTrue(capitals.containsAll(List.of("sh=cap", "sh=allcap")), capitals.toString());
        List<String> period = SentenceSearch.sharedFeatures(List.of("etc."), one, 0);
        assertTrue(period.stream().noneMatch(feature -> feature.startsWith("sh=")), "a last period is no code");
        List<String> emoji = SentenceSearch.sharedFeatures(List.of("go😀"), one, 0);
        assertEquals(List.of("s1=😀", "s2=o😀", "s3=go😀", "p1=g"),
                emoji.subList(2, 6));
    }

    @Test
    void theCandidateFeaturesReadTheDictionaryWordNetAndTheRules(@TempDir Path dir) throws IOException {
        SentenceSearch search = new SentenceSearch(SearchModelTest.model(dir), ContextRules.only(List.of()));
        Map<Tag, List<String>> features = search.candidateFeatures("Run", List.of(new Candidate(Tag.VERB,
                "word-forms"), new Candidate(Tag.NOUN, "suffix:un"), new Candidate(Tag.ADJ, "x:dict"),
                new Candidate(Tag.PROPN, "x:cap"), new Candidate(Tag.X, "shape:WORD")), Tag.NOUN);
        assertEquals(List.of(Tag.VERB, Tag.NOUN, Tag.ADJ, Tag.PROPN, Tag.X), List.copyOf(features.keySet()));
        assertEquals(List.of("src=word-forms", "rule=word-forms", "rank=0", "d=4", "dtop", "wn"),
                features.get(Tag.VERB));
        assertEquals(List.of("src=suffix", "rule=suffix:un", "rank=1", "d=3", "wn", "r1"), features.get(Tag.NOUN));
        assertEquals(List.of("src=x:dict", "rule=x:dict", "rank=2", "d=1"), features.get(Tag.ADJ));
        assertEquals(List.of("src=x:cap", "rule=x:cap", "rank=3", "d=0"), features.get(Tag.PROPN));
        assertEquals(List.of("src=shape", "rule=shape:WORD", "rank=3", "d=0"), features.get(Tag.X));
        Map<Tag, List<String>> twice = search.candidateFeatures("tie", List.of(new Candidate(Tag.NOUN, "first"),
                new Candidate(Tag.VERB, "test"), new Candidate(Tag.NOUN, "second")), Tag.VERB);
        assertEquals(List.of("src=second", "rule=second", "rank=2", "d=3"), twice.get(Tag.NOUN),
                "a tag held twice has its later candidate's features");
        assertEquals(List.of("src=test", "rule=test", "rank=1", "d=3", "dtop", "r1"), twice.get(Tag.VERB),
                "the dictionary's first tag on a tie of shares");
    }

    @Test
    void theBucketsOfShares() {
        assertEquals(List.of("0", "1", "2", "2", "3", "4", "4", "5", "5"),
                List.of(0.0, 0.05, 0.1, 0.29, 0.3, 0.6, 0.89, 0.9, 1.0).stream().map(SentenceSearch::bucket)
                        .toList());
    }

    @Test
    void theSearchFindsTheBestPathOverTwoTagsOfContext() {
        List<String> words = List.of("the", "run", "ends");
        List<List<Candidate>> candidates = List.of(candidates(Tag.DET), candidates(Tag.VERB, Tag.NOUN),
                candidates(Tag.NOUN, Tag.VERB));
        List<Tag> rules = List.of(Tag.DET, Tag.VERB, Tag.NOUN);
        SentenceSearch joint = search(Map.of("w=run", Map.of(Tag.VERB, 1.0), "t1=NOUN", Map.of(Tag.VERB, 3.0),
                "w=ends", Map.of(Tag.NOUN, 1.0)));
        assertEquals(List.of(Tag.DET, Tag.NOUN, Tag.VERB), joint.decode(words, candidates, rules),
                "the path, not each token alone");
        List<String> three = List.of("a", "b", "c");
        List<List<Candidate>> pairs = List.of(candidates(Tag.NOUN, Tag.VERB), candidates(Tag.ADJ),
                candidates(Tag.NOUN, Tag.VERB));
        SentenceSearch second = search(Map.of("t2=ADJ|VERB", Map.of(Tag.NOUN, 3.0), "w=a", Map.of(Tag.NOUN, 1.0),
                "w=c", Map.of(Tag.VERB, 1.5)));
        assertEquals(List.of(Tag.VERB, Tag.ADJ, Tag.NOUN), second.decode(three, pairs, List.of(Tag.NOUN, Tag.ADJ,
                Tag.NOUN)), "the tag two before");
        SentenceSearch start = search(Map.of("t2=<s>|<s2>", Map.of(Tag.VERB, 1.0)));
        assertEquals(List.of(Tag.VERB), start.decode(List.of("go"), List.of(candidates(Tag.NOUN, Tag.VERB)),
                List.of(Tag.NOUN)), "the tags before the first token");
        SentenceSearch none = search(Map.of());
        assertEquals(List.of(Tag.NOUN, Tag.ADJ, Tag.NOUN), none.decode(three, pairs, List.of(Tag.VERB, Tag.ADJ,
                Tag.VERB)), "the earlier candidate on a tie");
        assertEquals(List.of(), none.decode(List.of(), List.of(), List.of()));
    }

    @Test
    void theSearchMovesOrAddsItsTagAndLeavesTokensOutsideSentences() {
        String text = "We run tests. ok";
        Provenance provenance = Provenance.rule("tokenizer");
        List<Token> staged = List.of(
                new Token("t1", new TextRange(0, 2), Shape.WORD, candidates(Tag.PRON), false, provenance),
                new Token("t2", new TextRange(3, 6), Shape.WORD, candidates(Tag.VERB, Tag.NOUN), true, provenance),
                new Token("t3", new TextRange(7, 12), Shape.WORD,
                        List.of(new Candidate(Tag.NOUN, "test"), new Candidate(Tag.VERB, "test"),
                                new Candidate(Tag.ADJ, "suffix:s")), true, provenance),
                new Token("t4", new TextRange(12, 13), Shape.PUNCT, candidates(Tag.PUNCT), false, provenance),
                new Token("t5", new TextRange(14, 16), Shape.WORD, candidates(Tag.INTJ, Tag.ADJ), true, provenance));
        List<Token> ruled = List.of(staged.get(0), staged.get(1),
                new Token("t3", new TextRange(7, 12), Shape.WORD, candidates(Tag.NOUN, Tag.VERB), true, provenance),
                staged.get(3), staged.get(4));
        Sentence sentence = new Sentence("s1", new TextRange(0, 13), Kind.PROSE, Boundary.CERTAIN,
                List.of("t1", "t2", "t3", "t4"));
        SentenceSearch search = search(Map.of("w=run", Map.of(Tag.NOUN, 2.0), "w=tests", Map.of(Tag.ADJ, 2.0),
                "w=ok", Map.of(Tag.ADJ, 2.0)));
        List<Token> result = search.search(text, staged, ruled, List.of(sentence));
        assertSame(ruled.get(0), result.get(0), "one candidate");
        assertEquals(List.of(new Candidate(Tag.NOUN, "search:test"), new Candidate(Tag.VERB, "test")),
                result.get(1).candidates(), "moved to the front");
        assertEquals(List.of(new Candidate(Tag.ADJ, "search:suffix:s"), new Candidate(Tag.NOUN, "test"),
                new Candidate(Tag.VERB, "test")), result.get(2).candidates(), "added from the complete candidates");
        assertTrue(result.get(2).guessed());
        assertSame(ruled.get(3), result.get(3));
        assertSame(ruled.get(4), result.get(4), "outside every sentence");
    }
}
