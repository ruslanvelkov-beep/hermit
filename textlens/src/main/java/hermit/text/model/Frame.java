package hermit.text.model;

import java.util.List;

/**
 * The subject, verb and object of a clause (plan section 6.3): each a phrase, or null when the clause has none; the
 * clause that is the object ({@code objectClause}, null when none); the word the frame assumes ({@code implicit}:
 * "is" for a verbless "X: Y", "you" for an imperative, else null); and its polarity.
 */
public record Frame(String clause, Phrase subject, Phrase verb, Phrase object, String objectClause, String implicit,
        Polarity polarity) {

    /** A phrase: its head token and all its tokens, in text order. */
    public record Phrase(String head, List<String> tokenIds) {}

    /** NEGATIVE when a NEGATION marker scopes over the frame's clause, else POSITIVE. */
    public enum Polarity { POSITIVE, NEGATIVE }
}
