package hermit.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The agent-facing service: ingest-then-inject a reusable reference for a related query. */
class MemoryServiceTest {

    private static final String BTC = String.join("\n",
            "def fetch_btc_price():",
            "    \"\"\"Fetch the current bitcoin price in usd from the coingecko api.\"\"\"",
            "    return http_get('https://api.coingecko.com/...')['bitcoin']['usd']");

    private SnippetStore store;
    private MemoryService svc;

    @BeforeEach
    void setUp() {
        store = SnippetStore.inMemory();
        // deterministic embedder + a floor that suits it, so the test is offline & stable
        svc = new MemoryService(new SnippetMemory(store, new HashingEmbedder()), 3, 0.3);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void remembersThenInjectsAReferenceForARelatedQuery() {
        svc.remember("write a python program that fetches the current bitcoin price in usd", "python", BTC);
        assertTrue(svc.size() >= 1);

        String block = svc.referenceBlock("get the current bitcoin price in usd", "python");
        assertFalse(block.isBlank(), "a related query should surface a reference");
        assertTrue(block.contains("REUSABLE REFERENCES"), block);
        assertTrue(block.contains("coingecko"), "reference should carry the real code");
        assertTrue(block.toLowerCase().contains("adapt"), "must frame as adapt-and-verify");
    }

    @Test
    void snippetTierReferenceForAStepIntent() {
        String prog = String.join("\n",
                "def fetch_btc_price():",
                "    \"\"\"fetch the current bitcoin price in usd from coingecko\"\"\"",
                "    return http_get(URL)['bitcoin']['usd']",
                "def format_usd(x):",
                "    \"\"\"format a number as a usd currency string\"\"\"",
                "    return f'${x:.2f}'");
        svc.remember("bitcoin price program in python", "python", prog);
        // a per-step intent should surface the matching SNIPPET (not the whole recipe)
        String block = svc.snippetReferenceBlock("format a number as usd currency string", "python");
        assertFalse(block.isBlank());
        assertTrue(block.contains("[snippet]"), block);
        assertTrue(block.contains("x:.2f"), "should surface the formatter snippet: " + block);
    }

    @Test
    void noReferenceForAnUnrelatedQuery() {
        svc.remember("write a python program that fetches the current bitcoin price in usd", "python", BTC);
        String block = svc.referenceBlock("explain photosynthesis chlorophyll in plants", "python");
        assertTrue(block.isBlank(), "unrelated query must inject nothing: " + block);
    }

    @Test
    void disabledByDefault() {
        // Master switch is off unless -Dhermit.snippets.enabled=true.
        assertFalse(MemoryService.enabled());
        assertNull(MemoryService.defaultServiceOrNull());
    }
}

