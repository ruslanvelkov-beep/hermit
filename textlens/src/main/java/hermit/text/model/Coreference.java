package hermit.text.model;

import java.util.List;

/** An anaphor (plan section 6.3): its tokens, and its antecedent, or null when it stays unresolved. */
public record Coreference(List<String> tokenIds, Antecedent antecedent) {

    /** The antecedent's tokens, and the code-entity span that covers them, or null when no span does. */
    public record Antecedent(List<String> tokenIds, String spanId) {}
}
