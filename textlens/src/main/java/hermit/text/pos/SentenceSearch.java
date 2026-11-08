package hermit.text.pos;

import hermit.text.model.Sentence;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import hermit.text.model.Token.Candidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sentence search (the owner, 2026-11-06): the tags of a sentence's tokens chosen together, by a second-order
 * Viterbi search over each token's complete candidates with the weights of an averaged structured perceptron
 * ({@link SearchModel}), as they were trained.
 * <ol>
 *   <li>A token's complete candidates are those the tagger's stages gave it, and, for a WORD token, the model's
 *       {@link SearchModel#extras} after them; a word starts its sentence when it is the first WORD token of its
 *       sentence.</li>
 *   <li>The rules' tag of a token is its first candidate after the context rules ({@link ContextRules#apply}) narrow
 *       the complete candidates of all the tokens.</li>
 *   <li>A sentence's tokens are those from its first token to its last; its tags are those of the path that
 *       {@link #decode} finds over their texts, complete candidates and rules' tags.</li>
 *   <li>A token's tag reaches the tagged token as {@link #search} says; a token outside every sentence keeps what the
 *       context rules gave it.</li>
 * </ol>
 * The words are the tokens' texts; a word's lower form is its {@link SearchModel#normalize}d text; a suffix or a prefix
 * of {@code n} characters counts code points, and is the whole text when it is shorter.
 */
public final class SentenceSearch {

    /** The prefix of the rule of a candidate the search moved or added to the front. */
    public static final String RULE_PREFIX = "search:";
    /** The tag before a sentence's first token, as the transition features name it. */
    public static final String START = "<s>";
    /** The tag two before a sentence's first token, as the transition features name it. */
    public static final String START2 = "<s2>";
    /** The word before a sentence's first token and after its last, as the context features name them. */
    public static final String BEFORE = "<s>";
    /** The word after a sentence's last token, as the context features name it. */
    public static final String AFTER = "</s>";

    private final SearchModel model;
    private final ContextRules rules;

    /** A search with this model, whose rules' tags come from these context rules. */
    public SentenceSearch(SearchModel model, ContextRules rules) {
        this.model = model;
        this.rules = rules;
    }

    /** A search state: the tag of the token and that of the token before it. */
    private record State(String tag, String previous) {}

    /**
     * The features that token {@code index} of a sentence shares among its candidates, in this order, as the weights
     * were trained. With {@code w} the token's word, {@code lw} its lower form, {@code prev}, {@code next} and
     * {@code next2} the lower forms of the words before, after and two after ({@link #BEFORE} before the first,
     * {@link #AFTER} after the last), {@code last(x, n)} and {@code first(x, n)} the suffix and the prefix of {@code n}
     * characters, and a token's signature the names of its candidates' tags in alphabetical order joined by
     * {@code |}: {@code b}; {@code w=lw}; {@code s1=}, {@code s2=}, {@code s3=} the suffixes of 1 to 3 of {@code lw};
     * {@code p1=first(lw, 1)}; {@code pw=prev}; {@code nw=next}; {@code nw2=next2}; {@code ns3=last(next, 3)};
     * {@code ps3=last(prev, 3)}; {@code pw_w=prev|lw}; {@code w_nw=lw|next}; {@code first=True} for the first token
     * and {@code first=False} for any other; {@code cs=} the token's signature, {@code pcs=} that of the token before
     * ({@link #BEFORE} for the first) and {@code ncs=} that of the token after ({@link #AFTER} for the last); then the
     * shape features: {@code sh=cap} when the first character of {@code w} is upper case, {@code sh=allcap} when
     * {@code w} has two or more characters, one of them upper case and none lower or title case, {@code sh=digit} when
     * it holds a digit, {@code sh=hyph} when it holds {@code -}, and {@code sh=code} when a {@code .} is neither its
     * first character nor its last, or it holds {@code /} or {@code _}.
     */
    public static List<String> sharedFeatures(List<String> words, List<List<Candidate>> candidates, int index) {
        String word = words.get(index);
        String lower = SearchModel.normalize(word);
        String previous = index == 0 ? BEFORE : SearchModel.normalize(words.get(index - 1));
        String next = index + 1 == words.size() ? AFTER : SearchModel.normalize(words.get(index + 1));
        String next2 = index + 2 >= words.size() ? AFTER : SearchModel.normalize(words.get(index + 2));
        List<String> signatures = new ArrayList<>();
        for (List<Candidate> tokenCandidates : candidates) {
            signatures.add(tokenCandidates.stream().map(candidate -> candidate.tag().name()).distinct().sorted()
                    .collect(java.util.stream.Collectors.joining("|")));
        }
        java.util.function.BiFunction<String, Integer, String> first = (value, count) -> {
            int points = value.codePointCount(0, value.length());
            return value.substring(0, value.offsetByCodePoints(0, Math.min(count, points)));
        };
        java.util.function.BiFunction<String, Integer, String> last = (value, count) -> {
            int points = value.codePointCount(0, value.length());
            return value.substring(value.offsetByCodePoints(0, Math.max(0, points - count)));
        };
        List<String> result = new ArrayList<>();
        result.add("b");
        result.add("w=" + lower);
        result.add("s1=" + last.apply(lower, 1));
        result.add("s2=" + last.apply(lower, 2));
        result.add("s3=" + last.apply(lower, 3));
        result.add("p1=" + first.apply(lower, 1));
        result.add("pw=" + previous);
        result.add("nw=" + next);
        result.add("nw2=" + next2);
        result.add("ns3=" + last.apply(next, 3));
        result.add("ps3=" + last.apply(previous, 3));
        result.add("pw_w=" + previous + "|" + lower);
        result.add("w_nw=" + lower + "|" + next);
        result.add("first=" + (index == 0 ? "True" : "False"));
        result.add("cs=" + signatures.get(index));
        result.add("pcs=" + (index == 0 ? BEFORE : signatures.get(index - 1)));
        result.add("ncs=" + (index + 1 == words.size() ? AFTER : signatures.get(index + 1)));
        int[] points = word.codePoints().toArray();
        if (points.length > 0 && Character.isUpperCase(points[0])) {
            result.add("sh=cap");
        }
        boolean upper = false;
        boolean lowerOrTitle = false;
        for (int point : points) {
            upper |= Character.isUpperCase(point);
            lowerOrTitle |= Character.isLowerCase(point) || Character.isTitleCase(point);
        }
        if (points.length >= 2 && upper && !lowerOrTitle) {
            result.add("sh=allcap");
        }
        if (word.codePoints().anyMatch(Character::isDigit)) {
            result.add("sh=digit");
        }
        if (word.indexOf('-') >= 0) {
            result.add("sh=hyph");
        }
        int period = word.indexOf('.');
        if ((period > 0 && period < word.length() - 1) || word.indexOf('/') >= 0 || word.indexOf('_') >= 0) {
            result.add("sh=code");
        }
        return List.copyOf(result);
    }

    /**
     * The features of each of the {@code candidates} of a token, {@code word}, whose rules' tag is {@code ruleTag},
     * by tag, in the candidates' order; a tag held twice has the features of its later candidate. With a candidate's
     * family the part of its rule before the first {@code :} (the whole rule when it has none or starts with
     * {@code x:}), and the word's shares those of {@link SearchModel#shares}: {@code src=} the family; {@code rule=}
     * the rule; {@code rank=} the candidate's position from 0, at most 3; {@code d=} the bucket of the tag's share
     * ({@link #bucket}, 0 when the word has none); {@code dtop} when the tag has the largest share, the earliest in the
     * dictionary's order on a tie; {@code wn} when {@link SearchModel#wordNetTags} holds the tag; {@code r1} when the
     * tag is the rules' tag.
     */
    public Map<Tag, List<String>> candidateFeatures(String word, List<Candidate> candidates, Tag ruleTag) {
        Map<Tag, Double> shares = model.shares(word);
        Tag top = null;
        double largest = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Tag, Double> entry : shares.entrySet()) {
            if (entry.getValue() > largest) {
                largest = entry.getValue();
                top = entry.getKey();
            }
        }
        Set<Tag> wordNet = model.wordNetTags(word);
        Map<Tag, List<String>> result = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            String rule = candidate.rule();
            int colon = rule.indexOf(':');
            String family = colon > 0 && !rule.startsWith("x:") ? rule.substring(0, colon) : rule;
            List<String> features = new ArrayList<>();
            features.add("src=" + family);
            features.add("rule=" + rule);
            features.add("rank=" + Math.min(index, 3));
            features.add("d=" + bucket(shares.getOrDefault(candidate.tag(), 0.0)));
            if (candidate.tag() == top) {
                features.add("dtop");
            }
            if (wordNet.contains(candidate.tag())) {
                features.add("wn");
            }
            if (candidate.tag() == ruleTag) {
                features.add("r1");
            }
            result.put(candidate.tag(), List.copyOf(features));
        }
        return result;
    }

    /** The bucket of a share: {@code 0} for none, then {@code 1} below 0.1, {@code 2} below 0.3, {@code 3} below 0.6,
     * {@code 4} below 0.9 and {@code 5} from 0.9. */
    public static String bucket(double share) {
        if (share == 0.0) {
            return "0";
        }
        if (share < 0.1) {
            return "1";
        }
        if (share < 0.3) {
            return "2";
        }
        if (share < 0.6) {
            return "3";
        }
        if (share < 0.9) {
            return "4";
        }
        return "5";
    }

    /**
     * The transition features of a token whose lower form is {@code lower}, after the tags {@code tag1} (the token
     * before) and {@code tag2} (two before): {@code t1=tag1}, {@code t2=tag1|tag2} and {@code t1w=tag1|lower}.
     */
    public static List<String> transitionFeatures(String tag1, String tag2, String lower) {
        return List.of("t1=" + tag1, "t2=" + tag1 + "|" + tag2, "t1w=" + tag1 + "|" + lower);
    }

    /**
     * The tags of a sentence's tokens, of texts {@code words}, complete candidates {@code candidates} and rules' tags
     * {@code ruleTags}: the path of the highest score, one candidate's tag for each token, by a search over states of
     * a token's tag and the tag before it ({@link #START} before the first token, {@link #START2} before that). A
     * token's score for a tag after a state is the sum of the weights of its {@link #sharedFeatures}, then of its
     * {@link #candidateFeatures} for the tag, then of each of its {@link #transitionFeatures} after the state, each sum
     * from 0 and each weight added in order. A token with one candidate takes its tag and adds nothing. The states
     * after a token keep the order in which the search first reaches them, the states before it in their order and
     * then its candidates in order; a state keeps the first of the highest scores that reach it, and the path ends in
     * the first state of the highest score.
     */
    public List<Tag> decode(List<String> words, List<List<Candidate>> candidates, List<Tag> ruleTags) {
        Map<State, Double> scores = new LinkedHashMap<>();
        Map<State, List<Tag>> paths = new LinkedHashMap<>();
        State initial = new State(START, START2);
        scores.put(initial, 0.0);
        paths.put(initial, List.of());
        for (int index = 0; index < words.size(); index++) {
            List<String> shared = sharedFeatures(words, candidates, index);
            Map<Tag, List<String>> byTag = candidateFeatures(words.get(index), candidates.get(index),
                    ruleTags.get(index));
            Map<State, Double> nextScores = new LinkedHashMap<>();
            Map<State, List<Tag>> nextPaths = new LinkedHashMap<>();
            for (Map.Entry<State, Double> reached : scores.entrySet()) {
                State before = reached.getKey();
                for (Candidate candidate : candidates.get(index)) {
                    Tag tag = candidate.tag();
                    double score = reached.getValue();
                    if (candidates.get(index).size() > 1) {
                        for (String feature : shared) {
                            score += model.weight(feature, tag);
                        }
                        for (String feature : byTag.get(tag)) {
                            score += model.weight(feature, tag);
                        }
                        for (String feature : transitionFeatures(before.tag(), before.previous(),
                                SearchModel.normalize(words.get(index)))) {
                            score += model.weight(feature, tag);
                        }
                    }
                    State after = new State(tag.name(), before.tag());
                    if (!nextScores.containsKey(after) || score > nextScores.get(after)) {
                        List<Tag> path = new ArrayList<>(paths.get(before));
                        path.add(tag);
                        nextScores.put(after, score);
                        nextPaths.put(after, List.copyOf(path));
                    }
                }
            }
            scores = nextScores;
            paths = nextPaths;
        }
        State best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<State, Double> reached : scores.entrySet()) {
            if (best == null || reached.getValue() > bestScore) {
                best = reached.getKey();
                bestScore = reached.getValue();
            }
        }
        return best == null ? List.of() : paths.get(best);
    }

    /**
     * The tokens of {@code text} with the search's tags: {@code staged} are the tokens as the tagger's stages left
     * them, before the context rules, {@code ruled} the same tokens after the context rules, and {@code sentences} the
     * sentences that split them. Each token of a sentence is its ruled token with the search's tag first: unchanged
     * when the tag is its first candidate; else, when it holds the tag, with that candidate moved to the front; else
     * with a candidate of the tag added at the front, naming the rule of the tag's first complete candidate; a moved or
     * added candidate's rule gets the prefix {@link #RULE_PREFIX}, the other candidates keep their order, and the
     * token is guessed. The other tokens are the ruled ones.
     */
    public List<Token> search(String text, List<Token> staged, List<Token> ruled, List<Sentence> sentences) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < staged.size(); index++) {
            indexes.put(staged.get(index).id(), index);
        }
        List<Token> complete = new ArrayList<>(staged);
        for (Sentence sentence : sentences) {
            boolean firstWord = true;
            for (String id : sentence.tokenIds()) {
                int index = indexes.get(id);
                Token token = staged.get(index);
                if (token.shape() == Token.Shape.WORD) {
                    List<Candidate> candidates = new ArrayList<>(token.candidates());
                    candidates.addAll(model.extras(text.substring(token.range().start(), token.range().end()),
                            candidates, firstWord));
                    complete.set(index, new Token(token.id(), token.range(), token.shape(), List.copyOf(candidates),
                            token.guessed(), token.provenance()));
                    firstWord = false;
                }
            }
        }
        List<Token> completeRuled = rules.apply(text, complete);
        List<Token> result = new ArrayList<>(ruled);
        for (Sentence sentence : sentences) {
            List<String> words = new ArrayList<>();
            List<List<Candidate>> candidates = new ArrayList<>();
            List<Tag> ruleTags = new ArrayList<>();
            List<Integer> sentenceIndexes = new ArrayList<>();
            for (String id : sentence.tokenIds()) {
                int index = indexes.get(id);
                Token token = complete.get(index);
                words.add(text.substring(token.range().start(), token.range().end()));
                candidates.add(token.candidates());
                ruleTags.add(completeRuled.get(index).tag());
                sentenceIndexes.add(index);
            }
            List<Tag> tags = decode(words, candidates, ruleTags);
            for (int offset = 0; offset < tags.size(); offset++) {
                int index = sentenceIndexes.get(offset);
                Token token = ruled.get(index);
                Tag tag = tags.get(offset);
                if (token.tag() == tag) {
                    continue;
                }
                Candidate chosen = null;
                for (Candidate candidate : token.candidates()) {
                    if (candidate.tag() == tag) {
                        chosen = candidate;
                        break;
                    }
                }
                if (chosen == null) {
                    for (Candidate candidate : candidates.get(offset)) {
                        if (candidate.tag() == tag) {
                            chosen = candidate;
                            break;
                        }
                    }
                }
                List<Candidate> reordered = new ArrayList<>();
                reordered.add(new Candidate(tag, RULE_PREFIX + chosen.rule()));
                for (Candidate candidate : token.candidates()) {
                    if (candidate.tag() != tag) {
                        reordered.add(candidate);
                    }
                }
                result.set(index, new Token(token.id(), token.range(), token.shape(), List.copyOf(reordered), true,
                        token.provenance()));
            }
        }
        return List.copyOf(result);
    }
}
