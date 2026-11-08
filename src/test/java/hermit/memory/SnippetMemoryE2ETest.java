package hermit.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.memory.SnippetMemory.Recall;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * END-TO-END proof of the semantic memory: an approved program is ingested (recipe +
 * extracted snippets), then later user/plan queries recall the right recipe and the
 * right snippets to compose from - while unrelated queries recall nothing. All offline
 * via the deterministic hashing embedder + in-memory SQLite.
 */
class SnippetMemoryE2ETest {

    private static final String BTC_PROGRAM = String.join("\n",
            "import requests",
            "",
            "def fetch_btc_price():",
            "    \"\"\"Fetch the current bitcoin price in usd from the coingecko api.\"\"\"",
            "    url = 'https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd'",
            "    return requests.get(url).json()['bitcoin']['usd']",
            "",
            "def format_usd(amount):",
            "    \"\"\"Format a number as a usd currency string with a dollar sign.\"\"\"",
            "    return f'${amount:,.2f}'",
            "",
            "print(format_usd(fetch_btc_price()))");

    private static final String QUICKSORT_PROGRAM = String.join("\n",
            "def quicksort(items):",
            "    \"\"\"Sort a list of integers in ascending order using the quicksort algorithm.\"\"\"",
            "    if len(items) <= 1:",
            "        return items",
            "    pivot = items[0]",
            "    return quicksort([x for x in items[1:] if x < pivot]) + [pivot]");

    private SnippetStore store;
    private SnippetMemory memory;

    @BeforeEach
    void setUp() {
        store = SnippetStore.inMemory();
        memory = new SnippetMemory(store, new HashingEmbedder());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void ingestStoresRecipeAndSnippets() {
        int stored = memory.ingestProgram(
                "write a python program that fetches the current bitcoin price in usd and prints it",
                "python", BTC_PROGRAM);
        assertEquals(3, stored, "1 recipe + 2 snippets");
        assertEquals(3, memory.size());
    }

    @Test
    void recallReturnsTheRecipeForA_SimilarTask() {
        memory.ingestProgram(
                "write a python program that fetches the current bitcoin price in usd and prints it",
                "python", BTC_PROGRAM);
        // A later, differently-worded but semantically similar request.
        List<Recall> hits = memory.recall("get the current bitcoin price in usd with python", "python", 5, 0.3);
        assertFalse(hits.isEmpty(), "should recall the stored solution");
        assertEquals(Snippet.RECIPE, hits.get(0).kind(), "recipe tier should win for a whole-task match");
        assertTrue(hits.get(0).code().contains("coingecko"), "recalled the real program");
    }

    @Test
    void snippetsAreRetrievableAndComposableBySubIntent() {
        memory.ingestProgram(
                "write a python program that fetches the current bitcoin price in usd and prints it",
                "python", BTC_PROGRAM);

        // plan-step intent 1: fetching the price -> the fetch snippet
        List<Recall> fetch = memory.search("fetch the current bitcoin price in usd", Snippet.SNIPPET, "python", 5, 0.3);
        assertFalse(fetch.isEmpty());
        assertTrue(fetch.get(0).code().contains("coingecko"),
                "top snippet should be the fetch fn, got: " + fetch.get(0).description());

        // plan-step intent 2: formatting -> the format snippet (composition)
        List<Recall> fmt = memory.search("format a number as usd currency string", Snippet.SNIPPET, "python", 5, 0.3);
        assertFalse(fmt.isEmpty());
        assertTrue(fmt.get(0).code().contains("amount:,.2f"),
                "top snippet should be the formatter, got: " + fmt.get(0).description());
    }

    @Test
    void unrelatedProgramsDoNotContaminateRecall() {
        memory.ingestProgram(
                "write a python program that fetches the current bitcoin price in usd and prints it",
                "python", BTC_PROGRAM);
        memory.ingestProgram("sort a list of integers in python using quicksort", "python", QUICKSORT_PROGRAM);

        // bitcoin query must still surface the bitcoin fetch, never quicksort
        List<Recall> hits = memory.search("current bitcoin price in usd", Snippet.SNIPPET, "python", 3, 0.3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).code().contains("coingecko"));
        assertTrue(hits.stream().noneMatch(r -> r.code().contains("quicksort")),
                "quicksort must not rank for a bitcoin query");
    }

    @Test
    void unrelatedQueryRecallsNothing() {
        memory.ingestProgram(
                "write a python program that fetches the current bitcoin price in usd and prints it",
                "python", BTC_PROGRAM);
        List<Recall> hits = memory.recall("explain photosynthesis chlorophyll in plant biology", "python", 5, 0.3);
        assertTrue(hits.isEmpty(), "an unrelated query must recall nothing above the floor: " + hits);
    }

    @Test
    void reingestingSameProgramDoesNotDuplicate() {
        memory.ingestProgram("write a python program that fetches the current bitcoin price in usd", "python", BTC_PROGRAM);
        long after1 = memory.size();
        memory.ingestProgram("fetch bitcoin price in usd with python again", "python", BTC_PROGRAM);
        assertEquals(after1, memory.size(), "identical code must dedup by content hash");
    }
}
