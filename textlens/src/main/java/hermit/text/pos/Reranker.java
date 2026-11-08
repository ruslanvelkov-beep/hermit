package hermit.text.pos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import hermit.text.model.Token.Candidate;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * The statistical stage of the tagger (option C of the annotation plan, section 13): an averaged perceptron that
 * chooses among the candidates the context rules leave a WORD token. Its weights are trained outside this code, on
 * UD English EWT run through the rule tagger and on the gold set's dev units; they are a derived work under
 * CC BY-SA 4.0, kept as a separate file and loaded only when one is named ({@link #WEIGHTS_PROPERTY}).
 *
 * <p>A token is reranked when it is a WORD with two or more candidates whose first one does not name a preference of
 * the context rules ({@link ContextRules#PREFERENCES}, which order a token's readings on purpose; 2026-11-05).
 * Its features ({@link #features}) are those the
 * weights were trained with, and its choice ({@link #choose}) is the candidate with the highest score, the earlier one
 * in the rules' order on a tie. When the choice is not the first candidate, it moves to the front and its rule gets
 * the prefix {@link #RULE_PREFIX}; the other candidates keep their order, and the token stays guessed.
 *
 * <p>The weights file is UTF-8 text, gzip-compressed when its name ends in {@code .gz}, one JSON object a line: a line
 * with a {@code "feature"} gives that feature's weights by tag ({@code {"feature": "w=run", "weights": {"VERB": 1.25,
 * "NOUN": -0.5}}}); any other line (the header, with the source and the licence) is skipped, and so is a weight for a
 * tag that {@link Tag} does not name.
 */
public final class Reranker {

    /** The system property that names the weights file. */
    public static final String WEIGHTS_PROPERTY = "hermit.pos.weights";
    /** The prefix of the rule of a candidate the reranker moved to the front. */
    public static final String RULE_PREFIX = "rerank:";

    private final Map<String, Map<Tag, Double>> weights;
    private static final Map<String, Optional<Reranker>> LOADED = new HashMap<>();

    /** A reranker with these weights: feature, then tag, then weight. */
    public Reranker(Map<String, Map<Tag, Double>> weights) {
        Map<String, Map<Tag, Double>> copy = new HashMap<>();
        weights.forEach((feature, byTag) -> copy.put(feature, Map.copyOf(byTag)));
        this.weights = Map.copyOf(copy);
    }

    /** The reranker of the weights file at {@code path}, read as the class javadoc says; empty when there is none. */
    public static Optional<Reranker> load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Map<String, Map<Tag, Double>> weights = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream file = Files.newInputStream(path);
                InputStream input = path.getFileName().toString().endsWith(".gz")
                        ? new GZIPInputStream(file) : file;
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode object = mapper.readTree(line);
                JsonNode featureNode = object.get("feature");
                JsonNode weightNode = object.get("weights");
                if (featureNode == null || weightNode == null || !weightNode.isObject()) {
                    continue;
                }
                Map<Tag, Double> byTag = new HashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = weightNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    try {
                        byTag.put(Tag.valueOf(field.getKey()), field.getValue().doubleValue());
                    } catch (IllegalArgumentException ignored) {
                        // A weight for a tag outside this tag set is ignored.
                    }
                }
                weights.put(featureNode.textValue(), byTag);
            }
        }
        return Optional.of(new Reranker(weights));
    }

    /**
     * The reranker of the file that {@link #WEIGHTS_PROPERTY} names, loaded once for each file and kept; empty when the
     * property is not set, the file does not exist or it cannot be read.
     */
    public static Optional<Reranker> fromSystemProperties() {
        String named = System.getProperty(WEIGHTS_PROPERTY);
        if (named == null) {
            return Optional.empty();
        }
        synchronized (LOADED) {
            if (LOADED.containsKey(named)) {
                return LOADED.get(named);
            }
            Optional<Reranker> reranker;
            try {
                reranker = load(Path.of(named));
            } catch (IOException | RuntimeException ignored) {
                reranker = Optional.empty();
            }
            LOADED.put(named, reranker);
            return reranker;
        }
    }

    /**
     * The features of token {@code index} of {@code tokens}, the tagged tokens of {@code text}, in this order, as the
     * weights were trained. With {@code w} the token's text, {@code lw} that in lower case, {@code prev}, {@code next}
     * and {@code next2} the lower-cased texts of the token before, after and two after ({@code <s>} before the first
     * token, {@code </s>} after the last), {@code t1} and {@code t2} the tags of the token before and two before
     * ({@code <s>} and {@code <s2>} before the first token), {@code r1} the token's own tag, and {@code last(x, n)} the
     * last {@code n} characters of {@code x} (all of it when shorter): {@code b}, {@code w=lw}, {@code s1=last(lw, 1)},
     * {@code s2=last(lw, 2)}, {@code s3=last(lw, 3)}, {@code p1=} the first character of {@code lw}, {@code t1=t1},
     * {@code t2=t1|t2}, {@code t1w=t1|lw}, {@code pw=prev}, {@code nw=next}, {@code nw2=next2},
     * {@code ns3=last(next, 3)}, {@code ps3=last(prev, 3)}, {@code pw_w=prev|lw}, {@code w_nw=lw|next},
     * {@code first=True} for the first token and {@code first=False} for any other; then {@code sh=cap} when {@code w}
     * starts with an upper-case letter, {@code sh=allcap} when it is longer than one character and has an upper-case
     * letter and no lower-case one, {@code sh=digit} when it has a digit, {@code sh=hyph} when it has a hyphen,
     * {@code sh=code} when it has a period after its first and before its last character, a slash or an underscore;
     * then {@code r1=r1}, {@code rc=} the candidates' tags joined by {@code |}, {@code r1w=r1|lw}, {@code g=True} or
     * {@code g=False} as the token is guessed, and {@code r1t1=r1|t1}. A tag is a token's first candidate's
     * ({@link Token#tag}), and lower case is taken with {@link Locale#ROOT}.
     */
    public static List<String> features(String text, List<Token> tokens, int index) {
        Token token = tokens.get(index);
        String word = text.substring(token.range().start(), token.range().end());
        String lower = word.toLowerCase(Locale.ROOT);
        String previous = index == 0 ? "<s>" : tokenText(text, tokens.get(index - 1));
        String next = index + 1 >= tokens.size() ? "</s>" : tokenText(text, tokens.get(index + 1));
        String next2 = index + 2 >= tokens.size() ? "</s>" : tokenText(text, tokens.get(index + 2));
        String tag1 = index == 0 ? "<s>" : tokens.get(index - 1).tag().name();
        String tag2 = index == 0 ? "<s2>" : index == 1 ? "<s>" : tokens.get(index - 2).tag().name();
        String ruleTag = token.tag().name();
        String candidateTags = token.candidates().stream()
                .map(candidate -> candidate.tag().name())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");

        List<String> result = new ArrayList<>();
        result.add("b");
        result.add("w=" + lower);
        result.add("s1=" + last(lower, 1));
        result.add("s2=" + last(lower, 2));
        result.add("s3=" + last(lower, 3));
        result.add("p1=" + lower.substring(0, 1));
        result.add("t1=" + tag1);
        result.add("t2=" + tag1 + "|" + tag2);
        result.add("t1w=" + tag1 + "|" + lower);
        result.add("pw=" + previous);
        result.add("nw=" + next);
        result.add("nw2=" + next2);
        result.add("ns3=" + last(next, 3));
        result.add("ps3=" + last(previous, 3));
        result.add("pw_w=" + previous + "|" + lower);
        result.add("w_nw=" + lower + "|" + next);
        result.add("first=" + (index == 0 ? "True" : "False"));
        if (!word.isEmpty() && Character.isUpperCase(word.charAt(0))) {
            result.add("sh=cap");
        }
        boolean hasUpper = word.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = word.chars().anyMatch(Character::isLowerCase);
        if (word.length() > 1 && hasUpper && !hasLower) {
            result.add("sh=allcap");
        }
        if (word.chars().anyMatch(Character::isDigit)) {
            result.add("sh=digit");
        }
        if (word.indexOf('-') >= 0) {
            result.add("sh=hyph");
        }
        int period = word.indexOf('.');
        if ((period > 0 && period < word.length() - 1) || word.indexOf('/') >= 0 || word.indexOf('_') >= 0) {
            result.add("sh=code");
        }
        result.add("r1=" + ruleTag);
        result.add("rc=" + candidateTags);
        result.add("r1w=" + ruleTag + "|" + lower);
        result.add("g=" + (token.guessed() ? "True" : "False"));
        result.add("r1t1=" + ruleTag + "|" + tag1);
        return List.copyOf(result);
    }

    /** The sum of the weights of {@code features} for {@code tag}; a feature or a tag without a weight adds nothing. */
    public double score(List<String> features, Tag tag) {
        double total = 0.0;
        for (String feature : features) {
            total += weights.getOrDefault(feature, Map.of()).getOrDefault(tag, 0.0);
        }
        return total;
    }

    /** The candidate with the highest score for {@code features}, the earlier one on a tie; there is at least one. */
    public Candidate choose(List<String> features, List<Candidate> candidates) {
        Candidate best = candidates.get(0);
        double bestScore = score(features, best.tag());
        for (int index = 1; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            double candidateScore = score(features, candidate.tag());
            if (candidateScore > bestScore) {
                best = candidate;
                bestScore = candidateScore;
            }
        }
        return best;
    }

    /**
     * The tokens of {@code text}, tagged by the rules, with each WORD token of two or more candidates reranked as the
     * class javadoc describes; the features of every token come from the rules' tags, so one choice does not change the
     * next. The other tokens are the given ones.
     */
    public List<Token> rerank(String text, List<Token> tokens) {
        List<Token> result = new ArrayList<>(tokens.size());
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.shape() != Token.Shape.WORD || token.candidates().size() < 2
                    || ContextRules.PREFERENCES.contains(token.candidates().get(0).rule())) {
                result.add(token);
                continue;
            }
            Candidate choice = choose(features(text, tokens, index), token.candidates());
            if (choice == token.candidates().get(0)) {
                result.add(token);
                continue;
            }
            List<Candidate> candidates = new ArrayList<>(token.candidates().size());
            candidates.add(new Candidate(choice.tag(), RULE_PREFIX + choice.rule()));
            for (Candidate candidate : token.candidates()) {
                if (candidate != choice) {
                    candidates.add(candidate);
                }
            }
            result.add(new Token(token.id(), token.range(), token.shape(), candidates, token.guessed(),
                    token.provenance()));
        }
        return List.copyOf(result);
    }

    private static String tokenText(String text, Token token) {
        return text.substring(token.range().start(), token.range().end()).toLowerCase(Locale.ROOT);
    }

    private static String last(String value, int length) {
        return value.substring(Math.max(0, value.length() - length));
    }
}
