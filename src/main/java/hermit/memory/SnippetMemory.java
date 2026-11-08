package hermit.memory;

import hermit.memory.EmbeddingProvider.Mode;
import hermit.memory.SnippetStore.Hit;
import hermit.memory.StructuralSplitter.Block;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level semantic memory of verified code (SNIPPETS.md). On approval, a program is
 * ingested as a whole RECIPE plus its extracted SNIPPETS (functions/blocks), each
 * embedded by its description. On a later query, {@link #recall} finds the closest
 * recipe first (exact-task reuse), then closest snippets (composition) - the caller
 * injects them as reference implementations to adapt and re-verify.
 */
public final class SnippetMemory {

    /** A recalled reference: what to reuse, its code, similarity, and whether it's a recipe. */
    public record Recall(String kind, String description, String code, double score) {}

    private final SnippetStore store;
    private final EmbeddingProvider embedder;
    private final int minCodeChars;

    public SnippetMemory(SnippetStore store, EmbeddingProvider embedder) {
        this(store, embedder, 20);
    }

    public SnippetMemory(SnippetStore store, EmbeddingProvider embedder, int minCodeChars) {
        this.store = store;
        this.embedder = embedder;
        this.minCodeChars = minCodeChars;
    }

    /** Store a whole approved solution as a recipe. Returns its row id. */
    public long ingestRecipe(String query, String lang, String program) {
        return store.upsert(Snippet.of(Snippet.RECIPE, lang, query, program.strip(),
                embedder.id(), embedder.embed(query, Mode.DOCUMENT), query));
    }

    /**
     * Ingest an approved program: store the recipe AND split it into snippets, embedding
     * each by its description. Skips trivially short snippets (specificity filter).
     * Returns the number of units stored (recipe + snippets).
     */
    public int ingestProgram(String query, String lang, String program) {
        int stored = 0;
        ingestRecipe(query, lang, program);
        stored++;
        for (Block b : StructuralSplitter.split(lang, program)) {
            if (b.code().length() < minCodeChars || b.description().isBlank()) {
                continue;   // too generic/short to be worth memorizing
            }
            store.upsert(Snippet.of(Snippet.SNIPPET, lang, b.description(), b.code(),
                    embedder.id(), embedder.embed(b.description(), Mode.DOCUMENT), query));
            stored++;
        }
        return stored;
    }

    /** Search a tier (recipe|snippet) for a query. */
    public List<Recall> search(String query, String kind, String lang, int topK, double floor) {
        float[] q = embedder.embed(query, Mode.QUERY);
        List<Recall> out = new ArrayList<>();
        for (Hit h : store.search(q, kind, lang, topK, floor)) {
            out.add(new Recall(h.snippet().kind(), h.snippet().description(),
                    h.snippet().code(), h.score()));
        }
        return out;
    }

    /**
     * Two-tier recall for a user/plan query: a close RECIPE first (fast exact reuse),
     * else the closest SNIPPETS to compose from. Empty if nothing clears the floor.
     */
    public List<Recall> recall(String query, String lang, int topK, double floor) {
        List<Recall> recipe = search(query, Snippet.RECIPE, lang, 1, floor);
        if (!recipe.isEmpty()) {
            return recipe;
        }
        return search(query, Snippet.SNIPPET, lang, topK, floor);
    }

    public long size() {
        return store.count();
    }
}
