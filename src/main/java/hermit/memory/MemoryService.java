package hermit.memory;

import hermit.memory.SnippetMemory.Recall;
import java.io.File;
import java.util.List;

/**
 * The agent-facing entry point to the snippet memory (SNIPPETS.md). OFF unless
 * {@code -Dhermit.snippets.enabled=true}. Wraps {@link SnippetMemory} with a default
 * on-disk store ({@code ~/.hermit/snippets.db}) + the best available embedder, formats
 * recalled units into a reference block for the planning prompt, and ingests approved
 * programs on feedback.
 *
 * <p>Tunables: {@code -Dhermit.snippets.db}, {@code .topK} (default 3),
 * {@code .floor} (default 0.5 - cosine cut for a match).
 */
public final class MemoryService {

    private final SnippetMemory memory;
    private final int topK;
    private final double floor;

    public MemoryService(SnippetMemory memory, int topK, double floor) {
        this.memory = memory;
        this.topK = topK;
        this.floor = floor;
    }

    public static boolean enabled() {
        return Boolean.getBoolean("hermit.snippets.enabled");
    }

    /** Default service (on-disk store + real-or-fallback embedder), or null if disabled. */
    public static MemoryService defaultServiceOrNull() {
        if (!enabled()) {
            return null;
        }
        String db = System.getProperty("hermit.snippets.db",
                new File(System.getProperty("user.home"), ".hermit/snippets.db").getAbsolutePath());
        new File(db).getParentFile().mkdirs();
        int k = Integer.getInteger("hermit.snippets.topK", 3);
        double fl = Double.parseDouble(System.getProperty("hermit.snippets.floor", "0.5"));
        return new MemoryService(
                new SnippetMemory(SnippetStore.atFile(db), OpenAiEmbeddingProvider.available()), k, fl);
    }

    /**
     * A reference block to inject into the planning context for a query - the closest
     * recipe or snippets from past verified solutions. Empty string if nothing clears
     * the floor. Framed as adapt-and-verify, never copy-blindly.
     */
    public String referenceBlock(String query, String lang) {
        return format(memory.recall(query, lang, topK, floor));
    }

    /** Snippet-tier only (for per-plan-step composition). */
    public String snippetReferenceBlock(String query, String lang) {
        return format(memory.search(query, Snippet.SNIPPET, lang, topK, floor));
    }

    private String format(List<Recall> hits) {
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "REUSABLE REFERENCES from past VERIFIED solutions (ADAPT — rename entities, fix the "
                + "specifics — then VERIFY by running; do NOT copy blindly, and prefer these over guessing):\n");
        int i = 1;
        for (Recall r : hits) {
            sb.append(i++).append(". [").append(r.kind()).append("] ").append(r.description())
                    .append(" (similarity ").append(String.format("%.2f", r.score())).append(")\n```\n")
                    .append(cap(r.code(), 1200)).append("\n```\n");
        }
        return sb.toString();
    }

    /** Store an approved program (recipe + extracted snippets). No-op-safe. */
    public void remember(String query, String lang, String program) {
        if (program != null && !program.isBlank()) {
            memory.ingestProgram(query, lang == null ? "python" : lang, program);
        }
    }

    public long size() {
        return memory.size();
    }

    private static String cap(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) + "\n… (truncated)" : s);
    }
}

