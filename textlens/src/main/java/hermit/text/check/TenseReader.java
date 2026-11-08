package hermit.text.check;

import hermit.text.model.Clause;
import hermit.text.model.DateResolution;
import hermit.text.model.DateResolution.Tense;
import hermit.text.model.DateResolution.Time;
import hermit.text.model.Span;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import hermit.text.pos.WordForms;
import hermit.text.semantic.ClauseSegmenter;
import hermit.text.semantic.ConceptRecognizer;
import hermit.text.semantic.MarkerRecognizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tense that a date's clause gives the date (the owner, 2026-11-05): whether the date lies in the past, today or
 * later as the text speaks of it. Words are compared in lower case, with a typographic apostrophe (U+2019) read as
 * {@code '}. In the verb group a negated form reads as its verb ({@link #NEGATED}: {@code won't} as {@code will},
 * {@code didn't} as {@code did}), and the clitic {@code 'd} as {@code had} when the head is a past form or a participle
 * ({@code she'd shipped}) and as {@code would} otherwise ({@code she'd ship}); the cue keeps the words as written.
 * <ol>
 *   <li><b>The clause.</b> The date's clause is the smallest clause ({@link ClauseSegmenter}) whose range holds the
 *       date span. There is no tense when there is none, or when it has no head.</li>
 *   <li><b>How the date is placed.</b> The word before the date is the token right before the span, or the one before
 *       that when it is {@code the} ({@code on the 20th of October}).
 *     <ul>
 *       <li>After {@code since}, the tense is PAST, with the cue {@code since}, whatever the verb.</li>
 *       <li>The date is the clause's subject when it ends before the head and every token of the clause before it is
 *           a PUNCT or SYM token. The verb's tense applies, except when the clause states the date's weekday: the
 *           head is one of {@link #BE} and a WEEKDAY span starts right after it, or after {@code a} or {@code an}
 *           ({@code Oct 20 is a Monday}), which gives no tense; a weekday elsewhere does not count
 *           ({@code January 15th -- we were signed up for Saturday's class}).</li>
 *       <li>After one of {@link #TIME_WORDS} ({@code on Oct 20}: the date is the time of the event), the verb's tense
 *           applies.</li>
 *       <li>After one of {@link #TARGET_WORDS} ({@code moved to Oct 20}: the date can be the event's target), the
 *           verb's tense applies unless it is PAST, which gives no tense.</li>
 *       <li>Any other date gets no tense.</li>
 *     </ul>
 *   </li>
 *   <li><b>The verb's tense.</b> The verb group is the clause's AUX tokens before its head, then the head; its first
 *       word is the finite verb. The cue is the group's words joined by a space. The first rule that applies:
 *     <ol>
 *       <li>a word of the group is {@code will}, {@code shall} or {@code 'll}: FUTURE;</li>
 *       <li>a word is {@code would} or {@code could}: no tense;</li>
 *       <li>a word is {@code can}, {@code may}, {@code might}, {@code must} or {@code should}: PAST when
 *           {@code have} follows it in the group ({@code should have shipped}), else FUTURE;</li>
 *       <li>the head is {@code going} and {@code to} comes right after it: FUTURE after {@code am}, {@code is},
 *           {@code are}, {@code 'm}, {@code 're} or {@code 's}, else no tense ({@code were going to});</li>
 *       <li>the finite verb is {@code was}, {@code were}, {@code had} or {@code did}, or it is the head and a past
 *           form: a VERB ending in {@code ed}, or the past form of one of {@link WordForms#IRREGULAR} that is not
 *           also its base ({@code met}, not {@code cut}): PAST;</li>
 *       <li>the finite verb is {@code has}, {@code have} or {@code 've} and is not the head ({@code have shipped}):
 *           PAST;</li>
 *       <li>the finite verb is {@code 's}, it is not the head, and the head does not end in {@code ing}: PAST when the
 *           group's next word is {@code been} ({@code it's been shipped}), else no tense ({@code it's shipped}: is or
 *           has);</li>
 *       <li>the finite verb is {@code am}, {@code is}, {@code are}, {@code 'm}, {@code 're} or {@code 's}, and the
 *           head is another word ending in {@code ing} ({@code are releasing}): PRESENT when the clause holds
 *           {@code now}, {@code currently}, {@code right now}, {@code at the moment} or {@code at present} (the cue
 *           then adds that word or those words after a space), else FUTURE;</li>
 *       <li>the finite verb is {@code am}, {@code is}, {@code are}, {@code 'm}, {@code 're}, {@code 's},
 *           {@code do}, {@code does}, {@code has} or {@code have}, or it is the head and neither a past form nor
 *           ending in {@code ing} ({@code ships}, {@code is}): FUTURE, the present speaking of today or later;</li>
 *       <li>else no tense.</li>
 *     </ol>
 *   </li>
 * </ol>
 * The tense is a {@link DateResolution.Tense}: PAST allows a date before the clock's day or on it, PRESENT only the
 * clock's day, FUTURE the clock's day or a later one.
 */
public final class TenseReader {

    /** The negated forms that stay one token, each with the verb it reads as (the owner, 2026-11-05). */
    public static final Map<String, String> NEGATED = Map.ofEntries(Map.entry("won't", "will"),
            Map.entry("can't", "can"), Map.entry("cannot", "can"), Map.entry("don't", "do"),
            Map.entry("doesn't", "does"), Map.entry("didn't", "did"), Map.entry("isn't", "is"),
            Map.entry("aren't", "are"), Map.entry("wasn't", "was"), Map.entry("weren't", "were"),
            Map.entry("hasn't", "has"), Map.entry("haven't", "have"), Map.entry("hadn't", "had"),
            Map.entry("couldn't", "could"), Map.entry("wouldn't", "would"), Map.entry("shouldn't", "should"),
            Map.entry("mustn't", "must"), Map.entry("mightn't", "might"), Map.entry("shan't", "shall"),
            Map.entry("needn't", "need"), Map.entry("oughtn't", "ought"), Map.entry("ain't", "is"));

    /** The forms of be that can state a date's weekday. */
    public static final List<String> BE = List.of("is", "was", "are", "were", "'s", "be", "been");

    /** Words before a date that make it the time of the event. */
    public static final List<String> TIME_WORDS = List.of("on", "at", "in", "by", "before", "after", "from", "during");

    /** Words before a date that can make it the event's target. */
    public static final List<String> TARGET_WORDS = List.of("to", "until", "till", "for", "through");

    private TenseReader() {}

    /** The clauses of {@code input}, from its markers, as the date checker reads them. */
    public static List<Clause> clauses(Checker.Input input) {
        ConceptRecognizer.Input words = new ConceptRecognizer.Input(input.text(), input.tokens(), input.sentences(),
                input.context());
        return new ClauseSegmenter().segment(words, new MarkerRecognizer().recognize(words, input.spans()));
    }

    /** The tense that {@code clauses} of {@code input} give {@code date}, as the class javadoc says, or null. */
    public static Tense read(Checker.Input input, List<Clause> clauses, Span date) {
        int first = Checks.firstToken(input, date);
        int last = Checks.lastToken(input, date);
        Clause clause = null;
        for (Clause candidate : clauses) {
            String firstId = input.tokens().get(first).id();
            String lastId = input.tokens().get(last).id();
            if (candidate.tokenIds().contains(firstId) && candidate.tokenIds().contains(lastId)
                    && (clause == null || candidate.tokenIds().size() < clause.tokenIds().size())) {
                clause = candidate;
            }
        }
        if (clause == null || clause.head() == null) {
            return null;
        }
        int head = -1;
        for (int i = 0; i < input.tokens().size(); i++) {
            if (input.tokens().get(i).id().equals(clause.head())) {
                head = i;
                break;
            }
        }
        if (head < 0) {
            return null;
        }
        int before = first - 1;
        if ("the".equals(safeWord(input, before))) {
            before--;
        }
        String preceding = safeWord(input, before);
        if ("since".equals(preceding)) {
            return new Tense(Time.PAST, "since");
        }
        Tense tense = verbTense(input, clause);
        boolean subject = last < head;
        if (subject) {
            for (int i = 0; i < first; i++) {
                Token token = input.tokens().get(i);
                if (clause.tokenIds().contains(token.id()) && token.tag() != Tag.PUNCT && token.tag() != Tag.SYM) {
                    subject = false;
                    break;
                }
            }
        }
        if (subject) {
            String headWord = safeWord(input, head);
            if (BE.contains(headWord)) {
                for (Span span : input.spans()) {
                    if (span.concept() != Span.Concept.WEEKDAY) {
                        continue;
                    }
                    boolean insideDate = span.range().start() >= date.range().start()
                            && span.range().end() <= date.range().end();
                    if (insideDate) {
                        continue;
                    }
                    int weekdayFirst = Checks.firstToken(input, span);
                    boolean directlyAfter = weekdayFirst == head + 1;
                    boolean afterArticle = weekdayFirst == head + 2
                            && Set.of("a", "an").contains(safeWord(input, head + 1));
                    if (directlyAfter || afterArticle) {
                        return null;
                    }
                }
            }
            return tense;
        }
        if (preceding != null && TIME_WORDS.contains(preceding)) {
            return tense;
        }
        if (preceding != null && TARGET_WORDS.contains(preceding)) {
            return tense != null && tense.time() != Time.PAST ? tense : null;
        }
        return null;
    }

    private static boolean pastForm(Token token, String word) {
        if (token.tag() != Tag.VERB) {
            return false;
        }
        if (word.endsWith("ed")) {
            return true;
        }
        return WordForms.IRREGULAR.stream()
                .anyMatch(verb -> verb.past().equals(word) && !verb.base().equals(word));
    }

    private static String nowCue(Checker.Input input, Clause clause) {
        List<Integer> indexes = new ArrayList<>();
        for (String tokenId : clause.tokenIds()) {
            for (int i = 0; i < input.tokens().size(); i++) {
                if (input.tokens().get(i).id().equals(tokenId)) {
                    indexes.add(i);
                    break;
                }
            }
        }
        for (int i = 0; i < indexes.size(); i++) {
            String word = Checks.word(input, indexes.get(i));
            if ("now".equals(word) || "currently".equals(word)) {
                return word;
            }
            if ("right".equals(word) && i + 1 < indexes.size()
                    && "now".equals(Checks.word(input, indexes.get(i + 1)))) {
                return "right now";
            }
            if ("at".equals(word) && i + 2 < indexes.size()
                    && "the".equals(Checks.word(input, indexes.get(i + 1)))
                    && "moment".equals(Checks.word(input, indexes.get(i + 2)))) {
                return "at the moment";
            }
            if ("at".equals(word) && i + 1 < indexes.size()
                    && "present".equals(Checks.word(input, indexes.get(i + 1)))) {
                return "at present";
            }
        }
        return null;
    }

    private static Tense verbTense(Checker.Input input, Clause clause) {
        int head = -1;
        for (int i = 0; i < input.tokens().size(); i++) {
            if (input.tokens().get(i).id().equals(clause.head())) {
                head = i;
                break;
            }
        }
        if (head < 0) {
            return null;
        }
        List<Integer> group = new ArrayList<>();
        for (String tokenId : clause.tokenIds()) {
            for (int i = 0; i < head; i++) {
                Token token = input.tokens().get(i);
                if (token.id().equals(tokenId) && token.tag() == Tag.AUX) {
                    group.add(i);
                    break;
                }
            }
        }
        group.add(head);
        List<String> written = group.stream().map(tokenId -> Checks.word(input, tokenId)).toList();
        String cue = String.join(" ", written);
        String headWord = Checks.word(input, head);
        boolean participle = pastForm(input.tokens().get(head), headWord)
                || input.tokens().get(head).tag() == Tag.VERB
                        && (headWord.endsWith("ing") || headWord.endsWith("ed") || headWord.endsWith("en"));
        List<String> words = new ArrayList<>(written.size());
        for (String word : written) {
            String normalized = NEGATED.getOrDefault(word, word);
            if (normalized.equals("'d")) {
                normalized = participle ? "had" : "would";
            }
            words.add(normalized);
        }
        if (words.stream().anyMatch(word -> Set.of("will", "shall", "'ll").contains(word))) {
            return new Tense(Time.FUTURE, cue);
        }
        if (words.stream().anyMatch(word -> "would".equals(word) || "could".equals(word))) {
            return null;
        }
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (Set.of("can", "may", "might", "must", "should").contains(word)) {
                boolean perfect = words.subList(i + 1, words.size()).contains("have");
                return new Tense(perfect ? Time.PAST : Time.FUTURE, cue);
            }
        }
        Set<String> presentBe = Set.of("am", "is", "are", "'m", "'re", "'s");
        if ("going".equals(headWord) && "to".equals(Checks.word(input, head + 1))) {
            return presentBe.contains(words.get(0)) ? new Tense(Time.FUTURE, cue) : null;
        }
        String finite = words.get(0);
        if (Set.of("was", "were", "had", "did").contains(finite)
                || group.size() == 1 && pastForm(input.tokens().get(head), headWord)) {
            return new Tense(Time.PAST, cue);
        }
        if (Set.of("has", "have", "'ve").contains(finite) && group.size() > 1) {
            return new Tense(Time.PAST, cue);
        }
        if ("'s".equals(finite) && group.size() > 1 && !headWord.endsWith("ing")) {
            return words.size() > 1 && "been".equals(words.get(1)) ? new Tense(Time.PAST, cue) : null;
        }
        if (presentBe.contains(finite) && group.size() > 1 && headWord.endsWith("ing")) {
            String now = nowCue(input, clause);
            return now == null ? new Tense(Time.FUTURE, cue) : new Tense(Time.PRESENT, cue + " " + now);
        }
        if (presentBe.contains(finite) || Set.of("do", "does", "has", "have").contains(finite)
                || group.size() == 1 && !pastForm(input.tokens().get(head), headWord) && !headWord.endsWith("ing")) {
            return new Tense(Time.FUTURE, cue);
        }
        return null;
    }

    private static String safeWord(Checker.Input input, int tokenIndex) {
        if (tokenIndex < 0 || tokenIndex >= input.tokens().size()) {
            return null;
        }
        return Checks.word(input, tokenIndex);
    }
}
