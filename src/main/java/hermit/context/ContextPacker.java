package hermit.context;

import hermit.memory.EmbeddingProvider;
import hermit.memory.EmbeddingProvider.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Query-focused context compression for OUTBOUND calls to
 * a small local model with an 8k window, or a hosted model.
 * "Light mode": embedding-RAG only - no extra LLM
 * calls. Given the outbound query, a set of candidate chunks (conversation turns, file
 * sections, tool outputs), and a char budget, it:
 * <ol>
 *   <li>always keeps <b>anchors</b> (the task, the latest turn, explicitly-referenced
 *       resources) verbatim - protects recall;</li>
 *   <li>ranks the rest by cosine to the query, drops anything below a similarity floor,
 *       de-duplicates near-identical chunks, and greedily packs the highest-scoring
 *       until the budget is spent;</li>
 *   <li>emits each kept chunk labeled with its source.</li>
 * </ol>
 * Deterministic and fast (one local embed per chunk); reuses {@link EmbeddingProvider}.
 * An agentic rerank/extract stage ("tight mode") can layer on top later.
 */
public final class ContextPacker {

    /** A candidate piece of context. {@code anchor} = always include (never dropped). */
    public record Chunk(String source, String text, boolean anchor) {
        public static Chunk anchor(String source, String text) {
            return new Chunk(source, text, true);
        }
        public static Chunk of(String source, String text) {
            return new Chunk(source, text, false);
        }
    }

    private static final double DEFAULT_FLOOR = 0.25;   // suits the hashing embedder; tune per model
    private static final double NEAR_DUP = 0.97;

    private final EmbeddingProvider embedder;

    public ContextPacker(EmbeddingProvider embedder) {
        this.embedder = embedder;
    }

    /** Approx token→char budget (≈4 chars/token) for callers that think in tokens. */
    public String packToTokenBudget(String query, List<Chunk> chunks, int tokenBudget, double floor) {
        return pack(query, chunks, Math.max(0, tokenBudget) * 4, floor);
    }

    public String pack(String query, List<Chunk> chunks, int budgetChars) {
        return pack(query, chunks, budgetChars, DEFAULT_FLOOR);
    }

    /**
     * Pack {@code chunks} for {@code query} into ~{@code budgetChars}. Anchors are always
     * included (even if they alone exceed the budget); ranked candidates fill the rest.
     */
    public String pack(String query, List<Chunk> chunks, int budgetChars, double floor) {
        List<Chunk> anchors = new ArrayList<>();
        List<Chunk> candidates = new ArrayList<>();
        for (Chunk c : chunks) {
            if (c == null || c.text() == null || c.text().isBlank()) {
                continue;
            }
            (c.anchor() ? anchors : candidates).add(c);
        }
        List<Chunk> selected = new ArrayList<>(anchors);
        int used = 0;
        for (Chunk a : anchors) {
            used += rendered(a).length();
        }
        // Rank candidates by cosine to the query; drop below floor; dedup; greedy-pack.
        float[] q = embedder.embed(query, Mode.QUERY);
        List<Scored> scored = new ArrayList<>();
        for (Chunk c : candidates) {
            double s = EmbeddingProvider.cosine(q, embedder.embed(c.text(), Mode.DOCUMENT));
            if (s >= floor) {
                scored.add(new Scored(c, s, embedder.embed(c.text(), Mode.DOCUMENT)));
            }
        }
        scored.sort((x, y) -> Double.compare(y.score, x.score));
        List<float[]> keptVecs = new ArrayList<>();
        for (Scored sc : scored) {
            if (isNearDup(sc.vec, keptVecs) || alreadyHave(selected, sc.chunk)) {
                continue;
            }
            int cost = rendered(sc.chunk).length();
            if (used + cost > budgetChars) {
                continue;   // skip this one; a smaller lower-ranked chunk may still fit
            }
            selected.add(sc.chunk);
            keptVecs.add(sc.vec);
            used += cost;
        }
        // Emit: anchors first (in given order), then candidates by descending score
        // (selected already has anchors first; the candidate part is in add order = score order).
        StringBuilder sb = new StringBuilder();
        for (Chunk c : selected) {
            sb.append(rendered(c));
        }
        return sb.toString().strip();
    }

    private static String rendered(Chunk c) {
        String src = c.source() == null || c.source().isBlank() ? "context" : c.source();
        return "[" + src + "]\n" + c.text().strip() + "\n\n";
    }

    private static boolean isNearDup(float[] v, List<float[]> kept) {
        for (float[] k : kept) {
            if (EmbeddingProvider.cosine(v, k) >= NEAR_DUP) {
                return true;
            }
        }
        return false;
    }

    private static boolean alreadyHave(List<Chunk> selected, Chunk c) {
        String norm = c.text().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        for (Chunk s : selected) {
            if (s.text().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).equals(norm)) {
                return true;
            }
        }
        return false;
    }

    private record Scored(Chunk chunk, double score, float[] vec) {}

    // ---- convenience chunkers ----

    /** Split a blob into paragraph chunks (blank-line separated), each labeled with source. */
    public static List<Chunk> paragraphs(String source, String text) {
        List<Chunk> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String p : text.split("\\n\\s*\\n")) {
            if (!p.isBlank()) {
                out.add(Chunk.of(source, p.strip()));
            }
        }
        return out;
    }
}
