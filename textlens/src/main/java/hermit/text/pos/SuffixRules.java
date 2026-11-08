package hermit.text.pos;

import hermit.text.model.Tag;
import hermit.text.model.Token.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The morphology stage (milestone A2 of the annotation plan, section 5, stage 4), for a WORD that neither lexicon
 * nor the word forms ({@link WordForms}) know. The word is read in lower case, with a typographic apostrophe (U+2019)
 * read as {@code '}. The first of {@link #SUFFIXES} that it ends with, with at least {@link #MIN_STEM} characters
 * before the suffix, gives the candidates, each naming the rule {@code suffix:} and the suffix ({@code suffix:ing}); a
 * word with no such suffix gets {@link #OPEN_CLASS}, each naming the rule {@code open-class}. A word whose first
 * character is upper case and that does not start its sentence also gets PROPN, first, naming the rule
 * {@code capital}.
 */
public final class SuffixRules {

    /** One suffix and the candidates it gives, in order. */
    public record Suffix(String suffix, List<Tag> tags) {}

    /** The fewest characters a word keeps before its suffix ({@code red} and {@code thing} have no suffix). */
    public static final int MIN_STEM = 3;

    /**
     * The suffixes in the order they are tried; -ed and -ing stay ambiguous on purpose. The adjective endings come last
     * (section 13): ADJ, then NOUN, for the context rules to settle ({@code logical}, {@code holistic}).
     */
    public static final List<Suffix> SUFFIXES = List.of(new Suffix("'s", List.of(Tag.NOUN)),
            new Suffix("ly", List.of(Tag.ADV)), new Suffix("tion", List.of(Tag.NOUN)),
            new Suffix("ment", List.of(Tag.NOUN)), new Suffix("ness", List.of(Tag.NOUN)),
            new Suffix("ity", List.of(Tag.NOUN)), new Suffix("ed", List.of(Tag.VERB, Tag.ADJ, Tag.NOUN)),
            new Suffix("ing", List.of(Tag.VERB, Tag.ADJ, Tag.NOUN)), new Suffix("able", List.of(Tag.ADJ)),
            new Suffix("ible", List.of(Tag.ADJ)), new Suffix("ful", List.of(Tag.ADJ)),
            new Suffix("less", List.of(Tag.ADJ)), new Suffix("ous", List.of(Tag.ADJ)),
            new Suffix("al", List.of(Tag.ADJ, Tag.NOUN)), new Suffix("ic", List.of(Tag.ADJ, Tag.NOUN)),
            new Suffix("ive", List.of(Tag.ADJ, Tag.NOUN)), new Suffix("ant", List.of(Tag.ADJ, Tag.NOUN)),
            new Suffix("ent", List.of(Tag.ADJ, Tag.NOUN)), new Suffix("ary", List.of(Tag.ADJ, Tag.NOUN)),
            new Suffix("ory", List.of(Tag.ADJ, Tag.NOUN)), new Suffix("ar", List.of(Tag.ADJ, Tag.NOUN)));

    /** The candidates of a word with no suffix, in order. */
    public static final List<Tag> OPEN_CLASS = List.of(Tag.NOUN, Tag.VERB, Tag.ADJ);

    private SuffixRules() {
    }

    /** The candidates of {@code word}; {@code sentenceStart} says whether it is the first word of its sentence. */
    public static List<Candidate> candidates(String word, boolean sentenceStart) {
        String normalized = word.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        List<Candidate> candidates = new ArrayList<>();
        if (!sentenceStart && !word.isEmpty() && Character.isUpperCase(word.charAt(0))) {
            candidates.add(new Candidate(Tag.PROPN, "capital"));
        }

        for (Suffix suffix : SUFFIXES) {
            if (normalized.endsWith(suffix.suffix())
                    && normalized.length() - suffix.suffix().length() >= MIN_STEM) {
                for (Tag tag : suffix.tags()) {
                    candidates.add(new Candidate(tag, "suffix:" + suffix.suffix()));
                }
                return List.copyOf(candidates);
            }
        }

        for (Tag tag : OPEN_CLASS) {
            candidates.add(new Candidate(tag, "open-class"));
        }
        return List.copyOf(candidates);
    }
}
