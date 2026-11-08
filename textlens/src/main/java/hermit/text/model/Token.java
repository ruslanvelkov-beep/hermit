package hermit.text.model;

import java.util.List;

/**
 * One token: its range, its shape and its tag candidates in order, each with the rule that proposed it. A consumer
 * that needs one tag takes the first; {@code guessed} says that no rule narrowed the candidates to it.
 */
public record Token(String id, TextRange range, Shape shape, List<Candidate> candidates, boolean guessed,
        Provenance provenance) {

    /** A token's shape, decided before any tagging: code-shaped text first, ordinary words last. */
    public enum Shape {
        URL, PATH, MEMBER_REF, FILE_LINE, FLAG, VERSION, HASH, NUMBER, IDENT, CODE_SPAN, WORD, PUNCT, SYMBOL
    }

    /** One tag that a rule proposed for the token. */
    public record Candidate(Tag tag, String rule) {}

    /** The first candidate's tag, or X when there is no candidate. */
    public Tag tag() {
        return candidates.isEmpty() ? Tag.X : candidates.get(0).tag();
    }
}
