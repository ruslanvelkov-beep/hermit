package hermit.text.pos;

import hermit.text.model.Tag;
import hermit.text.model.Token;
import hermit.text.model.Token.Candidate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The context stage (milestone A2 of the annotation plan, section 5, stage 5): an ordered list of rules that narrow a
 * token's candidates by its neighbours. The tokens are visited left to right; at a token that holds two or more
 * candidates the rules are tried in order, each one that applies narrowing what the next one sees, until one candidate
 * is left. A token with one candidate is never changed.
 *
 * <p>A rule chooses a winner over losers ({@link Rule#description} ends with them: "NOUN over VERB"). It applies when
 * its condition holds and the token holds the winner and at least one loser; then it removes the losers, keeps every
 * other candidate in its order, and the winner's candidate names the rule ({@code new Candidate(winner, name)}). So a
 * rule never removes the last candidate. A preference ({@link #PREFERENCES}: "AUX before PART") removes nothing: it
 * moves the winner to the front, naming the rule, and no later rule is tried for the token, so the token keeps both
 * readings for the reranker and the later stages (the owner, 2026-11-05: {@code 's} is "is" in "the build's failing"
 * and a possessive in "the agent's log"). The conditions use these terms:
 * <ul>
 *   <li>a token's word: its text, in lower case, with a typographic apostrophe (U+2019) read as {@code '};</li>
 *   <li>"after": the token right before it, as the rules left it, and its tag, its first candidate; the first token
 *       has none;</li>
 *   <li>"before" and "the next token": the token right after it, as the earlier stages left it (not yet narrowed), and
 *       its tag, its first candidate; the last token has none;</li>
 *   <li>a token holds a tag when the tag is one of its candidates;</li>
 *   <li>"skipping": over the tokens right before it that the description names, to the first one it does not;</li>
 *   <li>punctuation: a PUNCT or SYMBOL token; "after punctuation" also holds for the first token, and "before
 *       punctuation" for the last one;</li>
 *   <li>a known verb: a token that holds VERB with a candidate naming the rule {@code word-forms} or {@code domain}
 *       ({@link WordForms}, {@link DomainLexicon}), not a guess of the suffix rules;</li>
 *   <li>a participle: a token that holds VERB and ADJ, whose word ends in {@code ed} or {@code ing} or that does not
 *       hold NOUN (an irregular participle of {@link WordForms}).</li>
 * </ul>
 *
 * <p>The rules from {@code adjective-suffix-not-before-a-noun-is-noun} to {@code and-then-verb} were added after the
 * gold set G1 was measured (plan section 13), and are tried first.
 */
public final class ContextRules {

    /** One rule: its name, which the winner's candidate names, and what it does. */
    public record Rule(String name, String description) {}

    /** The subject pronouns, as words. */
    public static final List<String> SUBJECT_PRONOUNS = List.of("i", "we", "you", "they", "he", "she", "it");

    /** The possessives, as words. */
    public static final List<String> POSSESSIVES = List.of("my", "your", "his", "her", "its", "our", "their");

    /** The verbs of saying or showing, as words. */
    public static final List<String> SAYING = List.of("say", "says", "said", "show", "shows", "showed", "shown", "note",
            "notes", "noted", "report", "reports", "reported", "mean", "means", "meant", "confirm", "confirms",
            "confirmed", "prove", "proves", "proved", "proven", "claim", "claims", "claimed", "state", "states",
            "stated", "find", "finds", "found", "assume", "assumes", "assumed", "think", "thinks", "thought", "know",
            "knows", "knew", "ensure", "ensures", "ensured", "check", "checks", "checked", "verify", "verifies",
            "verified", "expect", "expects", "expected", "explain", "explains", "explained");

    /** Words that are a determiner before a noun and a pronoun on their own, as words. */
    public static final List<String> DETERMINER_PRONOUNS = List.of("that", "which", "what", "whatever", "whichever",
            "this", "these", "those", "both", "each", "either", "neither", "all", "some", "any", "whose");

    /** The words that mark an imperative at a sentence's start, before its verb ({@code Just call Julie}). */
    public static final List<String> IMPERATIVE_MARKERS = List.of("please", "just", "simply", "then", "now", "also",
            "always", "never", "kindly");

    /** The pronouns that are only objects, as words ({@code her}, {@code it} and {@code you} are not). */
    public static final List<String> OBJECT_PRONOUNS = List.of("me", "him", "us", "them");

    /** The relative and interrogative pronouns that a verb follows, as words. */
    public static final List<String> RELATIVE_PRONOUNS = List.of("that", "which", "who", "what");

    /** The articles and possessives that start a noun phrase, as words. */
    public static final List<String> ARTICLES = List.of("a", "an", "the", "every", "no", "another", "its", "their",
            "our", "your", "my", "his", "her");

    /** The suffix rules' adjective endings ({@link SuffixRules#SUFFIXES}), whose candidates name {@code suffix:} it. */
    public static final List<String> ADJECTIVE_ENDINGS = List.of("al", "ic", "ive", "ant", "ent", "ary", "ory", "ar");

    /** The rules in the order they are tried. */
    public static final List<Rule> RULES = List.of(
            new Rule("adjective-suffix-not-before-a-noun-is-noun",
                    "a token whose ADJ candidate names the rule suffix: and one of the adjective endings, when the "
                            + "next token does not hold NOUN or there is none, and the token before it is not tagged "
                            + "AUX: NOUN over ADJ"),
            new Rule("determiner-pronoun-before-a-verb-is-pron",
                    "one of the determiner pronouns, when the next token is tagged AUX, or is tagged VERB, does not "
                            + "hold ADJ and holds at most two candidates: PRON over DET"),
            new Rule("determiner-pronoun-before-punctuation-is-pron",
                    "one of the determiner pronouns, before punctuation: PRON over DET"),
            new Rule("relative-pronoun-then-verb",
                    "after one of the relative pronouns tagged PRON: VERB over NOUN and ADJ"),
            new Rule("as-before-a-participle-is-sconj",
                    "the word as, when the next token holds VERB and either its word ends in ed or en, or it holds ADJ "
                            + "and not NOUN: SCONJ over ADP and ADV"),
            new Rule("participle-before-a-noun-is-adj",
                    "a participle, before a token tagged NOUN or PROPN, when the token before it is not tagged NOUN, "
                            + "PROPN, PRON or AUX and is not the word to (or there is none): ADJ over VERB and NOUN"),
            new Rule("known-verb-starts-a-sentence",
                    "a known verb after punctuation, skipping tokens tagged ADV or INTJ (an imperative: Just call "
                            + "Julie), before a token tagged DET, PRON, PROPN, NUM, ADJ, ADV or PART, or before any "
                            + "token when one of the skipped words is an imperative marker and the verb's word does "
                            + "not end in s, ed or ing (Just sort files by size): VERB over NOUN"),
            new Rule("noun-then-known-s-verb",
                    "a known verb whose word ends in s, after a token tagged NOUN or PROPN, before a token tagged ADP, "
                            + "DET, PRON, ADV, SCONJ, NUM or PART: VERB over NOUN"),
            new Rule("plural-noun-then-verb",
                    "a token whose word does not end in s, after a token tagged NOUN whose word ends in s, before a "
                            + "token tagged ADP, DET, PRON or ADV: VERB over NOUN"),
            new Rule("and-then-verb",
                    "after the word and or or, before a token tagged ADP, DET, PRON, ADV or SCONJ, when one of the "
                            + "seven tokens before that word is tagged VERB: VERB over NOUN"),
            new Rule("to-before-verb-is-part", "the word to, when the next token holds VERB: PART over ADP"),
            new Rule("to-before-a-noun-phrase-is-adp",
                    "the word to, before a token tagged DET, NUM, PRON or PROPN: ADP over PART"),
            new Rule("to-then-verb", "after the word to tagged PART: VERB over NOUN and ADJ"),
            new Rule("determiner-then-noun", "after a token tagged DET or ADJ: NOUN over VERB"),
            new Rule("possessive-then-noun", "after one of the possessives: NOUN over VERB"),
            new Rule("number-then-noun", "after a token tagged NUM: NOUN over VERB"),
            new Rule("auxiliary-then-verb",
                    "after a token tagged AUX, skipping the words not and n't and tokens tagged ADV: VERB over NOUN"),
            new Rule("subject-pronoun-then-verb", "after one of the subject pronouns: VERB over NOUN"),
            new Rule("that-after-saying-is-sconj",
                    "the word that, after one of the verbs of saying or showing: SCONJ over DET and PRON"),
            new Rule("that-before-noun-is-det",
                    "the word that, when the next token holds NOUN and not VERB: DET over PRON and SCONJ"),
            new Rule("that-after-noun-is-pron",
                    "the word that, after a token tagged NOUN or PROPN: PRON over DET and SCONJ"),
            new Rule("before-a-subject-is-sconj", "before one of the subject pronouns: SCONJ over ADP and ADV"),
            new Rule("before-a-noun-phrase-is-adp", "before a token tagged DET, NUM or PROPN: ADP over SCONJ and ADV"),
            new Rule("have-do-before-not-is-aux", "before the word not or n't: AUX over VERB"),
            new Rule("have-before-participle-is-aux",
                    "before a word that ends in ed or en, or a token tagged AUX or VERB: AUX over VERB"),
            new Rule("have-do-before-object-is-verb", "before a token tagged DET, NUM, PRON or NOUN: VERB over AUX"),
            new Rule("verb-before-determiner", "before one of the articles tagged DET: VERB over NOUN"),
            new Rule("verb-before-an-object-pronoun", "before one of the object pronouns: VERB over NOUN and ADJ"),
            new Rule("version-after-a-name-is-num",
                    "a VERSION token, after a token tagged PROPN or NOUN: NUM over PROPN"),
            new Rule("clitic-s-after-a-pronoun-is-aux", "the word 's, after a token tagged PRON: AUX over PART"),
            new Rule("clitic-s-before-not-been-being-or-going-is-aux",
                    "the word 's, before the word not, been, being or going: AUX over PART"),
            new Rule("clitic-s-before-a-verb-form-prefers-aux",
                    "the word 's, before a token that holds VERB and whose word ends in ing, ed or en, when the "
                            + "token after that one is not tagged NOUN, PROPN or ADJ: AUX before PART"),
            new Rule("clitic-s-prefers-possessive", "the word 's: PART before AUX"));

    /** The rules that only prefer their winner and keep every candidate. */
    public static final Set<String> PREFERENCES = Set.of("clitic-s-before-a-verb-form-prefers-aux",
            "clitic-s-prefers-possessive");

    /** The rules added after the gold set G1 was measured (plan section 13), by name. */
    private static final Set<String> ADDED_RULES = Set.of("adjective-suffix-not-before-a-noun-is-noun",
            "determiner-pronoun-before-a-verb-is-pron", "determiner-pronoun-before-punctuation-is-pron",
            "relative-pronoun-then-verb", "as-before-a-participle-is-sconj", "participle-before-a-noun-is-adj",
            "known-verb-starts-a-sentence", "noun-then-known-s-verb", "plural-noun-then-verb", "and-then-verb");

    private final List<Rule> rules;

    ContextRules(List<Rule> rules) {
        this.rules = rules;
    }

    /** All of {@link #RULES}, in their order. */
    public static ContextRules standard() {
        return new ContextRules(RULES);
    }

    /**
     * Only the named rules of {@link #RULES}, in that list's order, refused with an IllegalArgumentException when a
     * name is not one of its rules.
     */
    public static ContextRules only(Collection<String> names) {
        Set<String> requested = Set.copyOf(names);
        Set<String> known = Set.copyOf(RULES.stream().map(Rule::name).toList());
        for (String name : requested) {
            if (!known.contains(name)) {
                throw new IllegalArgumentException("Unknown context rule: " + name);
            }
        }
        return new ContextRules(RULES.stream().filter(rule -> requested.contains(rule.name())).toList());
    }

    /** The names of the rules, in the order they are tried. */
    public List<String> names() {
        return rules.stream().map(Rule::name).toList();
    }

    /**
     * The tokens with their candidates narrowed: copies of the given ones, in the same order, with everything but the
     * candidates unchanged. {@code text} is the text that the tokens' ranges index.
     */
    public List<Token> apply(String text, List<Token> tokens) {
        List<Token> result = new ArrayList<>(tokens);
        for (int index = 0; index < result.size(); index++) {
            Token token = result.get(index);
            if (token.candidates().size() < 2) {
                continue;
            }
            List<Candidate> candidates = token.candidates();
            for (Rule rule : rules) {
                if (candidates.size() < 2) {
                    break;
                }
                List<Candidate> narrowed = applyRule(text, result, index, rule);
                if (!narrowed.equals(candidates)) {
                    candidates = narrowed;
                    token = new Token(token.id(), token.range(), token.shape(), candidates, candidates.size() > 1,
                            token.provenance());
                    result.set(index, token);
                    if (PREFERENCES.contains(rule.name())) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static String word(String text, Token token) {
        return text.substring(token.range().start(), token.range().end()).toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'');
    }

    private static boolean holds(Token token, Tag tag) {
        return token != null && token.candidates().stream().anyMatch(candidate -> candidate.tag() == tag);
    }

    private static boolean tagged(Token token, Tag tag) {
        return token != null && !token.candidates().isEmpty() && token.candidates().get(0).tag() == tag;
    }

    private static boolean taggedAny(Token token, Tag... tags) {
        for (Tag tag : tags) {
            if (tagged(token, tag)) {
                return true;
            }
        }
        return false;
    }

    private static List<Candidate> narrow(List<Candidate> candidates, Rule rule, Tag winner, Tag... losers) {
        if (candidates.stream().noneMatch(candidate -> candidate.tag() == winner)) {
            return candidates;
        }
        Set<Tag> loserTags = Set.of(losers);
        if (candidates.stream().noneMatch(candidate -> loserTags.contains(candidate.tag()))) {
            return candidates;
        }
        List<Candidate> narrowed = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.tag() == winner) {
                narrowed.add(new Candidate(winner, rule.name()));
            } else if (!loserTags.contains(candidate.tag())) {
                narrowed.add(candidate);
            }
        }
        return narrowed.isEmpty() ? candidates : List.copyOf(narrowed);
    }

    private static List<Candidate> applyRule(String text, List<Token> tokens, int index, Rule rule) {
        if (ADDED_RULES.contains(rule.name())) {
            return applyAddedRule(text, tokens, index, rule);
        }
        Token token = tokens.get(index);
        Token next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
        Token previous = index > 0 ? tokens.get(index - 1) : null;
        String current = word(text, token);
        boolean applies = false;
        Tag winner = null;
        Tag[] losers = new Tag[0];
        switch (rule.name()) {
            case "to-before-verb-is-part" -> {
                applies = current.equals("to") && holds(next, Tag.VERB);
                winner = Tag.PART;
                losers = new Tag[] {Tag.ADP};
            }
            case "to-before-a-noun-phrase-is-adp" -> {
                applies = current.equals("to") && taggedAny(next, Tag.DET, Tag.NUM, Tag.PRON, Tag.PROPN);
                winner = Tag.ADP;
                losers = new Tag[] {Tag.PART};
            }
            case "to-then-verb" -> {
                applies = previous != null && word(text, previous).equals("to") && tagged(previous, Tag.PART);
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN, Tag.ADJ};
            }
            case "determiner-then-noun" -> {
                applies = taggedAny(previous, Tag.DET, Tag.ADJ);
                winner = Tag.NOUN;
                losers = new Tag[] {Tag.VERB};
            }
            case "possessive-then-noun" -> {
                applies = previous != null && POSSESSIVES.contains(word(text, previous));
                winner = Tag.NOUN;
                losers = new Tag[] {Tag.VERB};
            }
            case "number-then-noun" -> {
                applies = tagged(previous, Tag.NUM);
                winner = Tag.NOUN;
                losers = new Tag[] {Tag.VERB};
            }
            case "auxiliary-then-verb" -> {
                int cursor = index - 1;
                while (cursor >= 0) {
                    Token skipped = tokens.get(cursor);
                    String skippedWord = word(text, skipped);
                    if (!skippedWord.equals("not") && !skippedWord.equals("n't") && !tagged(skipped, Tag.ADV)) {
                        break;
                    }
                    cursor--;
                }
                applies = cursor >= 0 && tagged(tokens.get(cursor), Tag.AUX);
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN};
            }
            case "subject-pronoun-then-verb" -> {
                applies = previous != null && SUBJECT_PRONOUNS.contains(word(text, previous));
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN};
            }
            case "that-after-saying-is-sconj" -> {
                applies = current.equals("that") && previous != null && SAYING.contains(word(text, previous));
                winner = Tag.SCONJ;
                losers = new Tag[] {Tag.DET, Tag.PRON};
            }
            case "that-before-noun-is-det" -> {
                applies = current.equals("that") && holds(next, Tag.NOUN) && !holds(next, Tag.VERB);
                winner = Tag.DET;
                losers = new Tag[] {Tag.PRON, Tag.SCONJ};
            }
            case "that-after-noun-is-pron" -> {
                applies = current.equals("that") && taggedAny(previous, Tag.NOUN, Tag.PROPN);
                winner = Tag.PRON;
                losers = new Tag[] {Tag.DET, Tag.SCONJ};
            }
            case "before-a-subject-is-sconj" -> {
                applies = next != null && SUBJECT_PRONOUNS.contains(word(text, next));
                winner = Tag.SCONJ;
                losers = new Tag[] {Tag.ADP, Tag.ADV};
            }
            case "before-a-noun-phrase-is-adp" -> {
                applies = taggedAny(next, Tag.DET, Tag.NUM, Tag.PROPN);
                winner = Tag.ADP;
                losers = new Tag[] {Tag.SCONJ, Tag.ADV};
            }
            case "have-do-before-not-is-aux" -> {
                applies = next != null && (word(text, next).equals("not") || word(text, next).equals("n't"));
                winner = Tag.AUX;
                losers = new Tag[] {Tag.VERB};
            }
            case "have-before-participle-is-aux" -> {
                String nextWord = next == null ? "" : word(text, next);
                applies = next != null && (nextWord.endsWith("ed") || nextWord.endsWith("en")
                        || taggedAny(next, Tag.AUX, Tag.VERB));
                winner = Tag.AUX;
                losers = new Tag[] {Tag.VERB};
            }
            case "have-do-before-object-is-verb" -> {
                applies = taggedAny(next, Tag.DET, Tag.NUM, Tag.PRON, Tag.NOUN);
                winner = Tag.VERB;
                losers = new Tag[] {Tag.AUX};
            }
            case "verb-before-determiner" -> {
                applies = tagged(next, Tag.DET) && ARTICLES.contains(word(text, next));
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN};
            }
            case "verb-before-an-object-pronoun" -> {
                applies = next != null && OBJECT_PRONOUNS.contains(word(text, next));
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN, Tag.ADJ};
            }
            case "version-after-a-name-is-num" -> {
                applies = token.shape() == Token.Shape.VERSION && taggedAny(previous, Tag.PROPN, Tag.NOUN);
                winner = Tag.NUM;
                losers = new Tag[] {Tag.PROPN};
            }
            case "clitic-s-after-a-pronoun-is-aux" -> {
                applies = current.equals("'s") && tagged(previous, Tag.PRON);
                winner = Tag.AUX;
                losers = new Tag[] {Tag.PART};
            }
            case "clitic-s-before-not-been-being-or-going-is-aux" -> {
                String nextWord = next == null ? "" : word(text, next);
                applies = current.equals("'s")
                        && Set.of("not", "been", "being", "going").contains(nextWord);
                winner = Tag.AUX;
                losers = new Tag[] {Tag.PART};
            }
            case "clitic-s-before-a-verb-form-prefers-aux" -> {
                String nextWord = next == null ? "" : word(text, next);
                Token afterNext = index + 2 < tokens.size() ? tokens.get(index + 2) : null;
                boolean verbForm = holds(next, Tag.VERB)
                        && (nextWord.endsWith("ing") || nextWord.endsWith("ed") || nextWord.endsWith("en"));
                applies = current.equals("'s") && verbForm
                        && !taggedAny(afterNext, Tag.NOUN, Tag.PROPN, Tag.ADJ);
                winner = Tag.AUX;
                losers = new Tag[] {Tag.PART};
            }
            case "clitic-s-prefers-possessive" -> {
                applies = current.equals("'s");
                winner = Tag.PART;
                losers = new Tag[] {Tag.AUX};
            }
            default -> throw new IllegalArgumentException("Unknown context rule: " + rule.name());
        }
        if (!applies || !PREFERENCES.contains(rule.name())) {
            return applies ? narrow(token.candidates(), rule, winner, losers) : token.candidates();
        }
        Tag preferredWinner = winner;
        Set<Tag> loserTags = Set.of(losers);
        boolean holdsWinner = token.candidates().stream()
                .anyMatch(candidate -> candidate.tag() == preferredWinner);
        boolean holdsLoser = token.candidates().stream()
                .anyMatch(candidate -> loserTags.contains(candidate.tag()));
        if (!holdsWinner || !holdsLoser) {
            return token.candidates();
        }
        List<Candidate> preferred = new ArrayList<>(token.candidates().size());
        for (Candidate candidate : token.candidates()) {
            if (candidate.tag() == preferredWinner) {
                preferred.add(new Candidate(preferredWinner, rule.name()));
                break;
            }
        }
        for (Candidate candidate : token.candidates()) {
            if (candidate.tag() != preferredWinner) {
                preferred.add(candidate);
            }
        }
        return List.copyOf(preferred);
    }

    /**
     * The candidates of the token at {@code index} after the rule {@code rule}, one of {@link #ADDED_RULES}, as its
     * description says.
     */
    private static List<Candidate> applyAddedRule(String text, List<Token> tokens, int index, Rule rule) {
        Token token = tokens.get(index);
        Token previous = index > 0 ? tokens.get(index - 1) : null;
        Token next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
        String current = word(text, token);
        String previousWord = previous == null ? "" : word(text, previous);
        String nextWord = next == null ? "" : word(text, next);
        boolean punctuationBefore = previous == null || taggedAny(previous, Tag.PUNCT, Tag.SYM);
        boolean punctuationAfter = next == null || taggedAny(next, Tag.PUNCT, Tag.SYM);
        boolean knownVerb = token.candidates().stream().anyMatch(candidate -> candidate.tag() == Tag.VERB
                && (candidate.rule().equals(WordForms.RULE) || candidate.rule().equals("domain")));
        boolean participle = holds(token, Tag.VERB) && holds(token, Tag.ADJ)
                && (current.endsWith("ed") || current.endsWith("ing") || !holds(token, Tag.NOUN));
        boolean applies = false;
        Tag winner = null;
        Tag[] losers = new Tag[0];

        switch (rule.name()) {
            case "adjective-suffix-not-before-a-noun-is-noun" -> {
                boolean adjectiveSuffix = token.candidates().stream().anyMatch(candidate ->
                        candidate.tag() == Tag.ADJ && ADJECTIVE_ENDINGS.stream()
                                .anyMatch(ending -> candidate.rule().equals("suffix:" + ending)));
                applies = adjectiveSuffix && !holds(next, Tag.NOUN) && !tagged(previous, Tag.AUX);
                winner = Tag.NOUN;
                losers = new Tag[] {Tag.ADJ};
            }
            case "determiner-pronoun-before-a-verb-is-pron" -> {
                boolean suitableVerb = tagged(next, Tag.AUX) || tagged(next, Tag.VERB)
                        && !holds(next, Tag.ADJ) && next.candidates().size() <= 2;
                applies = DETERMINER_PRONOUNS.contains(current) && suitableVerb;
                winner = Tag.PRON;
                losers = new Tag[] {Tag.DET};
            }
            case "determiner-pronoun-before-punctuation-is-pron" -> {
                applies = DETERMINER_PRONOUNS.contains(current) && punctuationAfter;
                winner = Tag.PRON;
                losers = new Tag[] {Tag.DET};
            }
            case "relative-pronoun-then-verb" -> {
                applies = previous != null && tagged(previous, Tag.PRON)
                        && RELATIVE_PRONOUNS.contains(previousWord);
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN, Tag.ADJ};
            }
            case "as-before-a-participle-is-sconj" -> {
                boolean nextParticiple = holds(next, Tag.VERB)
                        && (nextWord.endsWith("ed") || nextWord.endsWith("en")
                                || holds(next, Tag.ADJ) && !holds(next, Tag.NOUN));
                applies = current.equals("as") && nextParticiple;
                winner = Tag.SCONJ;
                losers = new Tag[] {Tag.ADP, Tag.ADV};
            }
            case "participle-before-a-noun-is-adj" -> {
                boolean subjectBefore = taggedAny(previous, Tag.NOUN, Tag.PROPN, Tag.PRON, Tag.AUX)
                        || previousWord.equals("to");
                applies = participle && taggedAny(next, Tag.NOUN, Tag.PROPN) && !subjectBefore;
                winner = Tag.ADJ;
                losers = new Tag[] {Tag.VERB, Tag.NOUN};
            }
            case "known-verb-starts-a-sentence" -> {
                int start = index - 1;
                boolean marked = false;
                while (start >= 0 && taggedAny(tokens.get(start), Tag.ADV, Tag.INTJ)) {
                    marked |= IMPERATIVE_MARKERS.contains(word(text, tokens.get(start)));
                    start--;
                }
                boolean base = !current.endsWith("s") && !current.endsWith("ed") && !current.endsWith("ing");
                applies = knownVerb && (start < 0 || taggedAny(tokens.get(start), Tag.PUNCT, Tag.SYM))
                        && (taggedAny(next, Tag.DET, Tag.PRON, Tag.PROPN, Tag.NUM, Tag.ADJ, Tag.ADV, Tag.PART)
                                || marked && base);
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN};
            }
            case "noun-then-known-s-verb" -> {
                applies = knownVerb && current.endsWith("s") && taggedAny(previous, Tag.NOUN, Tag.PROPN)
                        && taggedAny(next, Tag.ADP, Tag.DET, Tag.PRON, Tag.ADV, Tag.SCONJ, Tag.NUM, Tag.PART);
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN};
            }
            case "plural-noun-then-verb" -> {
                applies = !current.endsWith("s") && tagged(previous, Tag.NOUN) && previousWord.endsWith("s")
                        && taggedAny(next, Tag.ADP, Tag.DET, Tag.PRON, Tag.ADV);
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN};
            }
            case "and-then-verb" -> {
                boolean conjunction = previousWord.equals("and") || previousWord.equals("or");
                boolean verbBefore = false;
                for (int cursor = index - 2; cursor >= 0 && cursor >= index - 8; cursor--) {
                    if (tagged(tokens.get(cursor), Tag.VERB)) {
                        verbBefore = true;
                        break;
                    }
                }
                applies = conjunction && verbBefore
                        && taggedAny(next, Tag.ADP, Tag.DET, Tag.PRON, Tag.ADV, Tag.SCONJ);
                winner = Tag.VERB;
                losers = new Tag[] {Tag.NOUN};
            }
            default -> throw new IllegalArgumentException("Unknown added context rule: " + rule.name());
        }
        return applies ? narrow(token.candidates(), rule, winner, losers) : token.candidates();
    }
}
