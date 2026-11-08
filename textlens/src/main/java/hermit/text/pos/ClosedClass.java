package hermit.text.pos;

import hermit.text.model.Tag;
import hermit.text.model.Token.Candidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The closed classes (milestone A2 of the annotation plan, section 5, stage 2): hand-written lists of the words whose
 * class is fixed. A word is looked up in lower case, with a typographic apostrophe (U+2019) read as {@code '}. Its
 * candidates are the tags of the lists that hold it, in the order the lists are declared below, then the tags
 * {@link #ALSO} gives it, each tag once; every candidate names the rule {@link #RULE}. A word in one list only is
 * final; one in several, such as {@code that}, {@code as}, {@code since}, {@code before} and {@code like}, keeps every
 * candidate for the context rules.
 */
public final class ClosedClass implements Lexicon {

    /** The rule every closed-class candidate names. */
    public static final String RULE = "closed-class";

    /** DET. */
    public static final List<String> DETERMINERS = List.of("a", "an", "the", "this", "that", "these", "those", "every",
            "each", "either", "neither", "another", "some", "any", "no", "all", "both", "what", "which", "whatever",
            "whichever", "several", "many", "much", "few", "enough", "such", "half");

    /** PRON (a contraction that starts with a pronoun is two tokens, the pronoun and its clitic). */
    public static final List<String> PRONOUNS = List.of("i", "me", "my", "mine", "myself", "we", "us", "our", "ours",
            "ourselves", "you", "your", "yours", "yourself", "yourselves", "he", "him", "his", "himself", "she", "her",
            "hers", "herself", "it", "its", "itself", "they", "them", "their", "theirs", "themselves", "oneself",
            "this", "that", "these", "those", "who", "whom", "whose", "what", "which", "whoever", "whomever",
            "whatever", "whichever", "someone", "somebody", "something", "anyone", "anybody", "anything", "everyone",
            "everybody", "everything", "nobody", "nothing", "none", "each", "either", "neither", "all", "both", "some",
            "any", "many", "much", "few", "several", "others", "another", "there");

    /** ADP. */
    public static final List<String> PREPOSITIONS = List.of("about", "above", "across", "after", "against", "along",
            "alongside", "amid", "among", "amongst", "around", "as", "at", "atop", "before", "behind", "below",
            "beneath", "beside", "besides", "between", "beyond", "by", "despite", "down", "during", "except", "for",
            "from", "in", "inside", "into", "like", "near", "of", "off", "on", "onto", "out", "outside", "over", "past",
            "per", "since", "than", "through", "throughout", "till", "to", "toward", "towards", "under", "underneath",
            "unlike", "until", "up", "upon", "via", "with", "within", "without", "versus", "vs.", "regarding",
            "concerning", "including", "excluding", "plus", "minus", "worth", "aboard", "circa", "notwithstanding",
            "pending");

    /** CCONJ. */
    public static final List<String> COORDINATORS = List.of("and", "or", "but", "nor", "yet", "so");

    /** SCONJ, kept apart from the coordinators. */
    public static final List<String> SUBORDINATORS = List.of("after", "although", "as", "because", "before", "if",
            "lest", "once", "since", "so", "than", "that", "though", "till", "unless", "until", "when", "whenever",
            "where", "whereas", "wherever", "whether", "while", "whilst", "albeit", "whereupon");

    /** AUX: the forms of be, have and do, with their negated contractions, and the clitics 'm, 're, 've and 's. */
    public static final List<String> AUXILIARIES = List.of("be", "am", "is", "are", "was", "were", "been", "being",
            "have", "has", "had", "having", "do", "does", "did", "isn't", "aren't", "wasn't", "weren't", "hasn't",
            "haven't", "hadn't", "don't", "doesn't", "didn't", "ain't", "'m", "'re", "'ve", "'s");

    /** AUX: the modals, with their negated contractions, and the clitics 'll and 'd. */
    public static final List<String> MODALS = List.of("can", "could", "may", "might", "must", "shall", "should", "will",
            "would", "ought", "cannot", "can't", "couldn't", "mustn't", "shan't", "shouldn't", "won't", "wouldn't",
            "mightn't", "needn't", "oughtn't", "'ll", "'d");

    /** PART: the negation and the infinitive marker. */
    public static final List<String> PARTICLES = List.of("not", "n't", "to", "'s");

    /** ADV: closed adverbs, among them the logical markers of frequency, hedging and cause. */
    public static final List<String> ADVERBS = List.of("never", "always", "sometimes", "usually", "often", "rarely",
            "seldom", "ever", "once", "twice", "again", "already", "also", "too", "very", "quite", "rather", "just",
            "only", "even", "still", "yet", "soon", "now", "then", "here", "there", "ago", "almost", "nearly", "maybe",
            "perhaps", "probably", "possibly", "certainly", "definitely", "apparently", "seemingly", "presumably",
            "therefore", "hence", "thus", "however", "moreover", "furthermore", "otherwise", "instead", "meanwhile",
            "nevertheless", "nonetheless", "accordingly", "consequently", "indeed", "so", "how", "why", "when", "where",
            "else", "anyway", "anywhere", "everywhere", "somewhere", "nowhere", "anymore", "together", "apart", "away",
            "back", "forward", "ahead", "aside", "later", "finally", "eventually", "immediately", "recently",
            "currently", "previously", "initially", "originally", "respectively", "especially", "particularly",
            "mostly", "mainly", "merely", "exactly", "precisely", "roughly", "approximately", "fully", "partly",
            "entirely", "completely", "hardly", "barely", "scarcely", "more", "most", "less", "least", "e.g.", "i.e.",
            "as", "well", "forever", "frequently", "occasionally", "generally", "typically", "normally", "likely",
            "unfortunately", "clearly", "obviously", "actually", "basically", "essentially", "simply", "directly",
            "automatically", "manually", "explicitly", "implicitly", "correctly", "incorrectly", "successfully",
            "silently", "separately", "alone", "afterwards", "beforehand", "thereafter", "thereby", "whereby",
            "somehow", "somewhat", "further", "far", "fairly");

    /** NUM: the number words. */
    public static final List<String> NUMERALS = List.of("zero", "one", "two", "three", "four", "five", "six", "seven",
            "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
            "hundred", "thousand", "million", "billion", "trillion");

    /** INTJ. */
    public static final List<String> INTERJECTIONS = List.of("yes", "no", "ok", "okay", "oh", "ah", "hi", "hello",
            "hey", "please", "thanks", "wow", "oops", "hmm", "yeah", "yep", "nope", "alas", "uh", "um", "bye",
            "goodbye");

    /** Readings of closed-class words outside the closed classes, added after the lists' tags. */
    public static final Map<String, List<Tag>> ALSO = Map.ofEntries(Map.entry("like", List.of(Tag.VERB)),
            Map.entry("have", List.of(Tag.VERB)), Map.entry("has", List.of(Tag.VERB)),
            Map.entry("had", List.of(Tag.VERB)), Map.entry("having", List.of(Tag.VERB)),
            Map.entry("do", List.of(Tag.VERB)), Map.entry("does", List.of(Tag.VERB)),
            Map.entry("did", List.of(Tag.VERB)), Map.entry("before", List.of(Tag.ADV)),
            Map.entry("after", List.of(Tag.ADV)), Map.entry("up", List.of(Tag.ADV)),
            Map.entry("down", List.of(Tag.ADV)), Map.entry("out", List.of(Tag.ADV)), Map.entry("off", List.of(Tag.ADV)),
            Map.entry("over", List.of(Tag.ADV)), Map.entry("around", List.of(Tag.ADV)),
            Map.entry("likely", List.of(Tag.ADJ)), Map.entry("well", List.of(Tag.INTJ)));

    private final Map<String, List<Candidate>> entries;

    ClosedClass(Map<String, List<Candidate>> entries) {
        this.entries = entries;
    }

    /** The lists above, each word with its candidates. */
    public static ClosedClass standard() {
        Map<String, List<Candidate>> entries = new LinkedHashMap<>();
        add(entries, DETERMINERS, Tag.DET);
        add(entries, PRONOUNS, Tag.PRON);
        add(entries, PREPOSITIONS, Tag.ADP);
        add(entries, COORDINATORS, Tag.CCONJ);
        add(entries, SUBORDINATORS, Tag.SCONJ);
        add(entries, AUXILIARIES, Tag.AUX);
        add(entries, MODALS, Tag.AUX);
        add(entries, PARTICLES, Tag.PART);
        add(entries, ADVERBS, Tag.ADV);
        add(entries, NUMERALS, Tag.NUM);
        add(entries, INTERJECTIONS, Tag.INTJ);
        for (Map.Entry<String, List<Tag>> entry : ALSO.entrySet()) {
            for (Tag tag : entry.getValue()) {
                add(entries, List.of(entry.getKey()), tag);
            }
        }
        entries.replaceAll((word, candidates) -> List.copyOf(candidates));
        return new ClosedClass(Map.copyOf(entries));
    }

    @Override
    public List<Candidate> candidates(String word) {
        String normalized = word.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        return entries.getOrDefault(normalized, List.of());
    }

    /** The number of distinct words in the lists. */
    public int size() {
        return entries.size();
    }

    private static void add(Map<String, List<Candidate>> entries, List<String> words, Tag tag) {
        for (String word : words) {
            List<Candidate> candidates = entries.computeIfAbsent(word, ignored -> new ArrayList<>());
            if (candidates.stream().noneMatch(candidate -> candidate.tag() == tag)) {
                candidates.add(new Candidate(tag, RULE));
            }
        }
    }
}
