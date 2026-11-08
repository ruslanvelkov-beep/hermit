package hermit.text.lex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Known abbreviations, each written with its final period ({@code e.g.}, {@code Fig.}). The tokenizer keeps such a word
 * and its periods in one token, so a sentence never ends inside it. Entries are matched case-sensitively: {@code No.}
 * is an abbreviation, {@code no.} at the end of a sentence is not.
 */
public final class Abbreviations {

    /** The plan's list: Latin and reference abbreviations, and the abbreviated months (May is never abbreviated). */
    public static final List<String> STANDARD = List.of("e.g.", "E.g.", "i.e.", "I.e.", "etc.", "vs.", "cf.", "Cf.",
            "Fig.", "No.", "approx.", "Jan.", "Feb.", "Mar.", "Apr.", "Jun.", "Jul.", "Aug.", "Sep.", "Sept.", "Oct.",
            "Nov.", "Dec.");

    private final List<String> entries;

    private Abbreviations(List<String> entries) {
        this.entries = entries;
    }

    /** The standard list. */
    public static Abbreviations standard() {
        return new Abbreviations(STANDARD);
    }

    /**
     * These entries, refused with an IllegalArgumentException when one is blank, holds whitespace or does not end with
     * a period.
     */
    public static Abbreviations of(Collection<String> entries) {
        List<String> copied = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank() || entry.chars().anyMatch(Character::isWhitespace)
                    || !entry.endsWith(".")) {
                throw new IllegalArgumentException("bad entry: " + entry);
            }
            copied.add(entry);
        }
        return new Abbreviations(List.copyOf(copied));
    }

    /** Whether {@code word} is one of the entries, exactly. */
    public boolean contains(String word) {
        return entries.contains(word);
    }

    /**
     * The length of the longest entry that {@code text} holds at {@code at}, or 0. An entry counts only at the start of
     * a word (the character before it is not a letter or digit) and when no letter or digit follows its final period.
     */
    public int matchAt(String text, int at) {
        if (at > 0 && Character.isLetterOrDigit(text.charAt(at - 1))) {
            return 0;
        }
        int best = 0;
        for (String entry : entries) {
            if (text.regionMatches(at, entry, 0, entry.length())) {
                int end = at + entry.length();
                if (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
                    continue;
                }
                if (entry.length() > best) {
                    best = entry.length();
                }
            }
        }
        return best;
    }
}
