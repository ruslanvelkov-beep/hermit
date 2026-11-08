package hermit.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.context.ContextPacker.Chunk;
import hermit.memory.HashingEmbedder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Light-mode embedding-RAG packing: anchors kept, relevance-ranked, floor, dedup, budget. */
class ContextPackerTest {

    private final ContextPacker packer = new ContextPacker(new HashingEmbedder());

    private static final String QUERY = "fetch the current bitcoin price in usd via an api";

    @Test
    void keepsRelevantDropsIrrelevantByFloor() {
        String out = packer.pack(QUERY, List.of(
                Chunk.of("doc1", "get the current bitcoin price in usd from the coingecko api"),
                Chunk.of("doc2", "sort a list of integers using the quicksort algorithm")),
                10_000, 0.25);
        assertTrue(out.contains("coingecko"), out);
        assertFalse(out.contains("quicksort"), out);
    }

    @Test
    void anchorsAlwaysKeptEvenUnderTinyBudget() {
        String out = packer.pack(QUERY, List.of(
                Chunk.anchor("task", "TASK: report the live BTC price"),
                Chunk.of("doc", "get the current bitcoin price in usd from the coingecko api")),
                5, 0.0);   // budget too small for any candidate
        assertTrue(out.contains("report the live BTC price"), "anchor must survive: " + out);
        assertFalse(out.contains("coingecko"), "candidate must be dropped under tiny budget: " + out);
    }

    @Test
    void budgetLimitsHowManyCandidatesFit() {
        Chunk anchor = Chunk.anchor("task", "TASK: BTC price");
        Chunk a = Chunk.of("a", "fetch the current bitcoin price in usd from the coingecko api");
        Chunk b = Chunk.of("b", "current bitcoin price api endpoint spec");
        List<Chunk> all = List.of(anchor, a, b);

        // Generous budget: both candidates fit.
        String big = packer.pack(QUERY, all, 100_000, 0.0);
        assertTrue(big.contains("coingecko") && big.contains("endpoint"), "both should fit: " + big);

        // Budget that only fits the anchor: NO candidate fits (anchor still kept).
        int anchorOnly = packer.pack(QUERY, List.of(anchor), 100_000, 0.0).length();
        String tight = packer.pack(QUERY, all, anchorOnly, 0.0);
        assertTrue(tight.contains("BTC price"), "anchor kept: " + tight);
        assertFalse(tight.contains("coingecko") || tight.contains("endpoint"),
                "no candidate should fit an anchor-only budget: " + tight);
    }

    @Test
    void deduplicatesIdenticalChunks() {
        String text = "get the current bitcoin price in usd from the coingecko api";
        String out = packer.pack(QUERY, List.of(
                Chunk.of("a", text), Chunk.of("b", text)), 10_000, 0.0);
        int occurrences = out.split("coingecko", -1).length - 1;
        assertEquals(1, occurrences, "identical chunks should appear once: " + out);
    }

    @Test
    void paragraphChunkerSplitsOnBlankLines() {
        List<Chunk> chunks = ContextPacker.paragraphs("notes", "first para\n\nsecond para\n\n\nthird");
        assertEquals(3, chunks.size());
        assertEquals("notes", chunks.get(0).source());
        assertFalse(chunks.get(0).anchor());
    }
}
