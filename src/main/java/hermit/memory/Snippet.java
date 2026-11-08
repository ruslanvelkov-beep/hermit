package hermit.memory;

/**
 * One reusable unit in the snippet memory. {@code kind} is either {@code "recipe"}
 * (a whole approved solution) or {@code "snippet"} (an extracted function/block).
 * {@code embedding} is the L2-normalized vector of {@code description}. {@code id} is
 * -1 until the store assigns one; {@code contentHash} may be null (the store fills it).
 */
public record Snippet(
        long id,
        String kind,
        String lang,
        String description,
        String code,
        String contentHash,
        String embedModel,
        float[] embedding,
        String sourceQuery,
        int useCount,
        boolean active) {

    public static final String RECIPE = "recipe";
    public static final String SNIPPET = "snippet";

    /** Build a fresh (unstored) unit; the store assigns id/hash/useCount/active. */
    public static Snippet of(String kind, String lang, String description, String code,
                             String embedModel, float[] embedding, String sourceQuery) {
        return new Snippet(-1, kind, lang, description, code, null, embedModel,
                embedding, sourceQuery, 0, true);
    }
}
