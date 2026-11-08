package hermit.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.memory.EmbeddingProvider.Mode;
import hermit.memory.SnippetStore.Hit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SnippetStoreTest {

    private final HashingEmbedder emb = new HashingEmbedder();
    private SnippetStore store;

    @BeforeEach
    void open() {
        store = SnippetStore.inMemory();
    }

    @AfterEach
    void close() {
        store.close();
    }

    private void put(String kind, String lang, String desc, String code) {
        store.upsert(Snippet.of(kind, lang, desc, code, emb.id(), emb.embed(desc, Mode.DOCUMENT), "q"));
    }

    @Test
    void storesAndRetrievesBySemanticSimilarity() {
        put(Snippet.SNIPPET, "python", "fetch the current bitcoin price in usd from an api", "def btc(): ...");
        put(Snippet.SNIPPET, "python", "sort a list of integers using quicksort", "def qs(): ...");
        put(Snippet.SNIPPET, "python", "read a text file line by line", "def rd(): ...");
        assertEquals(3, store.count());

        float[] q = emb.embed("get the current bitcoin price in usd", Mode.QUERY);
        List<Hit> hits = store.search(q, Snippet.SNIPPET, null, 5, 0.0);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).snippet().description().contains("bitcoin"),
                "top hit should be the bitcoin snippet, got: " + hits.get(0).snippet().description());
        assertEquals("def btc(): ...", hits.get(0).snippet().code());
    }

    @Test
    void floorExcludesUnrelatedQueries() {
        put(Snippet.SNIPPET, "python", "fetch the current bitcoin price in usd", "def btc(): ...");
        float[] q = emb.embed("explain photosynthesis in plants biology chlorophyll", Mode.QUERY);
        List<Hit> hits = store.search(q, Snippet.SNIPPET, null, 5, 0.3);
        assertTrue(hits.isEmpty(), "unrelated query must return nothing above the floor, got: " + hits);
    }

    @Test
    void kindAndLangFilterApply() {
        put(Snippet.RECIPE, "python", "end to end fetch bitcoin price program", "print(btc)");
        put(Snippet.SNIPPET, "python", "fetch bitcoin price helper", "def h(): ...");
        put(Snippet.SNIPPET, "java", "fetch bitcoin price in java", "String btc(){}");
        float[] q = emb.embed("fetch bitcoin price", Mode.QUERY);
        assertEquals(1, store.search(q, Snippet.RECIPE, null, 5, 0.0).size());
        assertEquals(1, store.search(q, Snippet.SNIPPET, "java", 5, 0.0).size());
        assertEquals(2, store.search(q, Snippet.SNIPPET, null, 5, 0.0).size());
    }

    @Test
    void dedupByContentHashBumpsUseCount() {
        String code = "def btc():\n    return requests.get(URL).json()['usd']";
        store.upsert(Snippet.of(Snippet.SNIPPET, "python", "fetch btc price", code, emb.id(),
                emb.embed("fetch btc price", Mode.DOCUMENT), "q"));
        store.upsert(Snippet.of(Snippet.SNIPPET, "python", "fetch btc price again", code, emb.id(),
                emb.embed("fetch btc price again", Mode.DOCUMENT), "q"));
        assertEquals(1, store.count(), "identical code must not duplicate");
    }

    @Test
    void nearDuplicateDoesNotAddARow() {
        // Same purpose with different code has a different hash but a similar description embedding.
        store.upsert(Snippet.of(Snippet.SNIPPET, "python", "fetch the current bitcoin price in usd",
                "def a():\n    return get(URL_V1)", emb.id(),
                emb.embed("fetch the current bitcoin price in usd", Mode.DOCUMENT), "q"));
        store.upsert(Snippet.of(Snippet.SNIPPET, "python", "fetch the current bitcoin price in usd",
                "def a():\n    return get(URL_V2)", emb.id(),
                emb.embed("fetch the current bitcoin price in usd", Mode.DOCUMENT), "q"));
        assertEquals(1, store.count(), "near-duplicate should not add a second row");
    }

    @Test
    void distinctSnippetsStillInsertAndDedupCanBeDisabled() {
        store.upsert(Snippet.of(Snippet.SNIPPET, "python", "fetch the current bitcoin price in usd",
                "def a(): ...", emb.id(),
                emb.embed("fetch the current bitcoin price in usd", Mode.DOCUMENT), "q"));
        // A clearly different purpose inserts a new row.
        store.upsert(Snippet.of(Snippet.SNIPPET, "python", "sort a list of integers with quicksort",
                "def b(): ...", emb.id(),
                emb.embed("sort a list of integers with quicksort", Mode.DOCUMENT), "q"));
        assertEquals(2, store.count());
        store.setNearDupThreshold(1.0);
        store.upsert(Snippet.of(Snippet.SNIPPET, "python", "fetch the current bitcoin price in usd",
                "def a(): return 2", emb.id(),
                emb.embed("fetch the current bitcoin price in usd", Mode.DOCUMENT), "q"));
        assertEquals(3, store.count());
    }

    @Test
    void embeddingBlobRoundTrips() {
        float[] v = emb.embed("round trip vector", Mode.DOCUMENT);
        assertArrayEquals(v, SnippetStore.fromBytes(SnippetStore.toBytes(v)), 0f);
    }
}
