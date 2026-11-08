package hermit.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Structural split of a Python program into functions with docstring descriptions. */
class StructuralSplitterTest {

    private static final String PROG = String.join("\n",
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

    @Test
    void splitsTopLevelFunctionsWithDocstrings() {
        List<StructuralSplitter.Block> blocks = StructuralSplitter.split("python", PROG);
        assertEquals(2, blocks.size(), "two top-level defs -> two blocks");
        assertEquals("fetch_btc_price", blocks.get(0).name());
        assertTrue(blocks.get(0).description().contains("bitcoin price"), blocks.get(0).description());
        assertTrue(blocks.get(0).code().contains("coingecko"));
        assertEquals("format_usd", blocks.get(1).name());
        assertTrue(blocks.get(1).description().contains("usd currency"), blocks.get(1).description());
    }

    @Test
    void nonPythonFallsBackToWholeProgram() {
        List<StructuralSplitter.Block> blocks = StructuralSplitter.split("java", "// compute sum\nint x = 1;");
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).description().contains("compute sum"));
    }
}
