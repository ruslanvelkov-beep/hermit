package hermit.text.pos;

import hermit.text.lex.LayoutAnalyzer;
import hermit.text.lex.SentenceSplitter;
import hermit.text.lex.Tokenizer;
import hermit.text.model.Layout;
import hermit.text.model.Sentence;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import hermit.text.model.Token.Candidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The part-of-speech tagger (milestone A2 of the annotation plan, section 5): each token gets its tag candidates in
 * order, each with the rule that proposed it, by stages that only narrow or add candidates and never leave a token
 * without one.
 * <ol>
 *   <li>The shape ({@link #shapeCandidates}): a token of any shape but WORD gets its shape's candidates, and no other
 *       stage adds to them.</li>
 *   <li>A WORD that is one of the closed class's words gets its candidates;</li>
 *   <li>else, when the domain lexicon knows the word, the lexicon's;</li>
 *   <li>else, when the word forms ({@link WordForms}) know the word, theirs;</li>
 *   <li>else, when the word is a hyphenated compound, the compound stage's ({@link #compoundCandidates});</li>
 *   <li>else the suffix rules' ({@link SuffixRules}); a word starts its sentence when it is the first WORD token of
 *       its sentence (for the word forms too).</li>
 *   <li>The context rules narrow the tokens that hold two or more candidates.</li>
 *   <li>The reranker ({@link Reranker}), when the tagger has one, chooses among the candidates the context rules
 *       leave a WORD token.</li>
 *   <li>The sentence search ({@link SentenceSearch}), when the tagger has one, chooses the tags of each sentence's
 *       tokens together, in place of the reranker.</li>
 *   <li>A token left with two or more candidates reports its first and is guessed; one with one candidate is not.</li>
 * </ol>
 * The tagged tokens are copies of the given ones, in the same order, with the same ids, ranges, shapes and provenance.
 */
public final class PosTagger {

    /**
     * Hyphenated compounds that dictionaries list as adjectives, in lower case: the compound stage gives them ADJ
     * whatever their last part is.
     */
    public static final List<String> ADJECTIVE_COMPOUNDS = List.of("top-level", "low-level", "high-level",
            "read-only", "free-form", "built-in", "open-source", "out-of-range", "out-of-date", "up-to-date",
            "end-to-end", "state-of-the-art", "well-known", "long-running", "short-lived", "long-lived", "real-time",
            "full-text", "first-class", "second-class", "one-to-one", "one-to-many", "many-to-one", "many-to-many",
            "peer-to-peer", "point-to-point");

    /** First parts that make a hyphenated compound an adjective ({@code non-build}, {@code per-T}), in lower case. */
    public static final List<String> ADJECTIVE_PREFIXES = List.of("non", "per");

    /** Last parts that make a hyphenated compound an adjective ({@code local-only}, {@code thread-safe}). */
    public static final List<String> ADJECTIVE_LAST_PARTS = List.of("only", "safe", "free", "aware", "like", "wide",
            "specific", "based", "compatible", "th");

    /** Java modifiers: a code span that starts with one is a declaration, a name, not a command. */
    public static final List<String> MODIFIERS = List.of("public", "protected", "private", "static", "final",
            "abstract", "default", "synchronized", "native");

    private final ClosedClass closedClass;
    private final DomainLexicon domain;
    private final ContextRules rules;
    private final Reranker reranker;
    private final SentenceSearch search;

    /** A tagger with these word lists and context rules, and no reranker. */
    public PosTagger(ClosedClass closedClass, DomainLexicon domain, ContextRules rules) {
        this(closedClass, domain, rules, null);
    }

    /** A tagger with these word lists, context rules and reranker ({@code null} for none), and no sentence search. */
    public PosTagger(ClosedClass closedClass, DomainLexicon domain, ContextRules rules, Reranker reranker) {
        this(closedClass, domain, rules, reranker, null);
    }

    /**
     * A tagger with these word lists, context rules, reranker and sentence search ({@code null} for none); with a
     * search, the reranker is not used.
     */
    public PosTagger(ClosedClass closedClass, DomainLexicon domain, ContextRules rules, Reranker reranker,
            SentenceSearch search) {
        this.closedClass = closedClass;
        this.domain = domain;
        this.rules = rules;
        this.reranker = reranker;
        this.search = search;
    }

    /**
     * The standard closed class, domain lexicon and context rules, and the sentence search with the standard context
     * rules and the model file that {@link SearchModel#MODEL_PROPERTY} names
     * ({@link SearchModel#fromSystemProperties}), when there is one; else the reranker of the weights file that
     * {@link Reranker#WEIGHTS_PROPERTY} names ({@link Reranker#fromSystemProperties}), when there is one.
     */
    public static PosTagger standard() {
        SentenceSearch search = SearchModel.fromSystemProperties()
                .map(model -> new SentenceSearch(model, ContextRules.standard()))
                .orElse(null);
        Reranker reranker = search == null ? Reranker.fromSystemProperties().orElse(null) : null;
        return new PosTagger(ClosedClass.standard(), DomainLexicon.standard(), ContextRules.standard(), reranker,
                search);
    }

    /**
     * The tagged tokens of {@code text}, from the standard tokenizer and sentence splitter over the flow text of its
     * layout ({@link LayoutAnalyzer}, {@link Layout#flow}), as the concept stage reads a text.
     */
    public List<Token> tagText(String text) {
        Layout layout = LayoutAnalyzer.standard().analyze(text);
        String flow = layout.flow(text);
        List<Token> tokens = Tokenizer.standard().tokenize(flow);
        List<Sentence> sentences = SentenceSplitter.standard().split(flow, tokens, layout);
        return tag(flow, tokens, sentences);
    }

    /** The tagged {@code tokens} of {@code text}, which {@code sentences} split into sentences. */
    public List<Token> tag(String text, List<Token> tokens, List<Sentence> sentences) {
        Map<String, Token> byId = new HashMap<>();
        for (Token token : tokens) {
            byId.put(token.id(), token);
        }
        Set<String> sentenceStarts = new HashSet<>();
        for (Sentence sentence : sentences) {
            for (String tokenId : sentence.tokenIds()) {
                Token token = byId.get(tokenId);
                if (token != null && token.shape() == Token.Shape.WORD) {
                    sentenceStarts.add(token.id());
                    break;
                }
            }
        }

        List<Token> tagged = new ArrayList<>();
        for (Token token : tokens) {
            List<Candidate> candidates = shapeCandidates(text, token);
            if (token.shape() == Token.Shape.WORD) {
                String word = text.substring(token.range().start(), token.range().end());
                candidates = closedClass.candidates(word);
                if (candidates.isEmpty()) {
                    candidates = domain.candidates(word);
                }
                if (candidates.isEmpty()) {
                    candidates = WordForms.candidates(word, sentenceStarts.contains(token.id()));
                }
                if (candidates.isEmpty()) {
                    candidates = compoundCandidates(word);
                }
                if (candidates.isEmpty()) {
                    candidates = SuffixRules.candidates(word, sentenceStarts.contains(token.id()));
                }
            }
            tagged.add(new Token(token.id(), token.range(), token.shape(), candidates, candidates.size() > 1,
                    token.provenance()));
        }
        List<Token> ruled = rules.apply(text, tagged);
        if (search != null) {
            return search.search(text, tagged, ruled, sentences);
        }
        return reranker == null ? ruled : reranker.rerank(text, ruled);
    }

    /**
     * The shape stage's candidates for a token, each naming the rule {@code shape:} and the shape ({@code shape:PATH}):
     * a URL, PATH, MEMBER_REF, FILE_LINE, HASH or IDENT is PROPN (code names are names); a NUMBER is NUM; a VERSION
     * with a letter in it is PROPN ({@code v3}, {@code Qwen3.8-27B}), one of digits and periods NUM, then PROPN; a FLAG
     * is PROPN (a flag names an option, as a path names a file); a CODE_SPAN is NOUN when it reads as a command and
     * PROPN otherwise (a name, signature, expression, value or notation); a PUNCT is PUNCT and a SYMBOL SYM. A WORD has
     * none: an empty list. A code span reads as a command when what lies between its backticks starts with {@code $(}
     * (a command substitution, with or without whitespace), or when it holds whitespace and no arrow (U+2192 or
     * {@code ->}), and its first word, up to the first whitespace, either starts with a shell operator ({@code ||},
     * {@code &&}, {@code |}, {@code ;} or {@code $}), or is made only of lower-case ASCII letters, digits, periods,
     * slashes, underscores and hyphens, is not one of {@link #MODIFIERS} and is not followed by {@code =} or
     * {@code :}. So {@code ./gradlew test}, {@code bash -n}, {@code || true} and {@code $(list_files)} are commands,
     * and {@code a.graph -> a.sym}, {@code static Foo bar} and {@code x = 1} are not.
     */
    public static List<Candidate> shapeCandidates(String text, Token token) {
        String rule = "shape:" + token.shape();
        return switch (token.shape()) {
            case WORD -> List.of();
            case URL, PATH, MEMBER_REF, FILE_LINE, HASH, IDENT ->
                    List.of(new Candidate(Tag.PROPN, rule));
            case NUMBER -> List.of(new Candidate(Tag.NUM, rule));
            case VERSION -> {
                String surface = text.substring(token.range().start(), token.range().end());
                boolean hasLetter = surface.chars().anyMatch(Character::isLetter);
                if (hasLetter) {
                    yield List.of(new Candidate(Tag.PROPN, rule));
                }
                yield List.of(new Candidate(Tag.NUM, rule), new Candidate(Tag.PROPN, rule));
            }
            case FLAG -> List.of(new Candidate(Tag.PROPN, rule));
            case CODE_SPAN -> {
                String surface = text.substring(token.range().start(), token.range().end());
                String inner = surface.length() >= 2 ? surface.substring(1, surface.length() - 1) : surface;
                int firstWhitespace = 0;
                while (firstWhitespace < inner.length()
                        && !Character.isWhitespace(inner.charAt(firstWhitespace))) {
                    firstWhitespace++;
                }
                boolean command = inner.startsWith("$(");
                if (!command && firstWhitespace < inner.length()
                        && !inner.contains("\u2192") && !inner.contains("->")) {
                    String first = inner.substring(0, firstWhitespace);
                    int next = firstWhitespace;
                    while (next < inner.length() && Character.isWhitespace(inner.charAt(next))) {
                        next++;
                    }
                    boolean operator = first.startsWith("||") || first.startsWith("&&")
                            || first.startsWith("|") || first.startsWith(";") || first.startsWith("$");
                    boolean commandWord = !first.isEmpty() && first.chars().allMatch(c ->
                            (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                                    || c == '.' || c == '/' || c == '_' || c == '-');
                    boolean assignment = next < inner.length()
                            && (inner.charAt(next) == '=' || inner.charAt(next) == ':');
                    command = operator || (commandWord && !MODIFIERS.contains(first) && !assignment);
                }
                yield List.of(new Candidate(command ? Tag.NOUN : Tag.PROPN, rule));
            }
            case PUNCT -> List.of(new Candidate(Tag.PUNCT, rule));
            case SYMBOL -> List.of(new Candidate(Tag.SYM, rule));
        };
    }

    /**
     * The compound stage's candidates for a WORD with a hyphen inside it (not as its first or last character); an
     * empty list for any other word. The word is read in lower case, with a typographic apostrophe (U+2019) read as
     * {@code '}; its first part is the text before its first hyphen, its last part the text after its last one:
     * <ul>
     *   <li>one of {@link #ADJECTIVE_COMPOUNDS}, a first part in {@link #ADJECTIVE_PREFIXES} or a last part in
     *       {@link #ADJECTIVE_LAST_PARTS}: ADJ, naming the rule {@code compound:adjective};</li>
     *   <li>else a word of three or more parts, a phrase or a coined label used as one word ({@code end-of-line},
     *       {@code adapt-and-verify}): NOUN, naming the rule {@code compound:phrase};</li>
     *   <li>else the last part's candidates as the closed class, the domain lexicon, the word forms and then the suffix
     *       rules give them (as for a word that does not start its sentence), in their order and with the rules they
     *       name, keeping the first NOUN, VERB and ADJ candidate, but not an ADJ that names the rule
     *       {@code open-class} (a guess); a last part left with none, a particle such as {@code off} or {@code up},
     *       gives NOUN, naming the rule
     *       {@code compound:particle}; one left with NOUN and without VERB gets VERB after them, naming the rule
     *       {@code compound:open} (a compound may be a verb, as {@code re-type} is).</li>
     * </ul>
     * So {@code single-project} and {@code switch-off} take NOUN, {@code fully-qualified} the candidates of a
     * participle, {@code re-type} those of {@code type}, and {@code top-level}, {@code non-build} and
     * {@code local-only} ADJ. A capital does not make a compound PROPN.
     */
    public List<Candidate> compoundCandidates(String word) {
        String normalized = word.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        int firstHyphen = normalized.indexOf('-');
        int lastHyphen = normalized.lastIndexOf('-');
        if (firstHyphen <= 0 || lastHyphen == normalized.length() - 1) {
            return List.of();
        }
        String firstPart = normalized.substring(0, firstHyphen);
        String lastPart = normalized.substring(lastHyphen + 1);
        if (ADJECTIVE_COMPOUNDS.contains(normalized) || ADJECTIVE_PREFIXES.contains(firstPart)
                || ADJECTIVE_LAST_PARTS.contains(lastPart)) {
            return List.of(new Candidate(Tag.ADJ, "compound:adjective"));
        }
        if (firstHyphen != lastHyphen) {
            return List.of(new Candidate(Tag.NOUN, "compound:phrase"));
        }

        List<List<Candidate>> sources = List.of(
                closedClass.candidates(lastPart),
                domain.candidates(lastPart),
                WordForms.candidates(lastPart, false),
                SuffixRules.candidates(lastPart, false));
        List<Candidate> proposed = List.of();
        for (List<Candidate> source : sources) {
            if (!source.isEmpty()) {
                proposed = source;
                break;
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        boolean noun = false;
        boolean verb = false;
        boolean adjective = false;
        for (Candidate candidate : proposed) {
            if (candidate.tag() == Tag.NOUN && !noun) {
                candidates.add(candidate);
                noun = true;
            } else if (candidate.tag() == Tag.VERB && !verb) {
                candidates.add(candidate);
                verb = true;
            } else if (candidate.tag() == Tag.ADJ && !adjective && !"open-class".equals(candidate.rule())) {
                candidates.add(candidate);
                adjective = true;
            }
        }
        if (candidates.isEmpty()) {
            return List.of(new Candidate(Tag.NOUN, "compound:particle"));
        }
        if (noun && !verb) {
            candidates.add(new Candidate(Tag.VERB, "compound:open"));
        }
        return List.copyOf(candidates);
    }
}
