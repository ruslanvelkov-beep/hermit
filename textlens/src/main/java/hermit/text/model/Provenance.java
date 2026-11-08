package hermit.text.model;

/**
 * Where an annotation came from: the rules or a model, how certain its recognition is (not whether the text is true),
 * and the rule or the model that made it.
 */
public record Provenance(Source source, double confidence, String rule, String model) {

    /** The two origins; a model's proposal keeps its origin after it is validated locally. */
    public enum Source { RULES, MODEL }

    /** A rule's annotation, certain. */
    public static Provenance rule(String rule) {
        return new Provenance(Source.RULES, 1.0, rule, null);
    }
}
