package hermit.text.model;

import java.util.List;

/**
 * A logical marker (plan section 6.2): the words that negate, qualify, count, place in time, give a cause or a
 * condition, report someone's claim, hedge or quote, with their class, the kind from the class's list (null when the
 * class has none) and what they scope over. Two readings of the same words are two markers ({@code never} is a
 * NEGATION and a FREQUENCY).
 */
public record Marker(String id, TextRange range, List<String> tokenIds, Class markerClass, String kind, Scope scope,
        Provenance provenance) {

    /** The ten marker classes. */
    public enum Class {
        NEGATION, MODALITY, FREQUENCY, QUANTIFIER, TEMPORAL, CAUSAL, CONDITIONAL, ATTRIBUTION, HEDGE, QUOTATION
    }

    /**
     * What a marker scopes over, in one of three forms: a clause ({@code clause}); a token list ({@code tokenIds}: the
     * noun phrase a quantifier or a determiner negation heads, the verb a modal governs, or the words a quotation
     * quotes); or a conditional's clause with its {@code antecedent} and {@code consequent} clauses. An ATTRIBUTION or
     * a QUOTATION also names the clauses it makes not asserted ({@code notAsserted}). A part a form does not use is
     * null.
     */
    public record Scope(String clause, List<String> tokenIds, String antecedent, String consequent,
            List<String> notAsserted) {}
}
