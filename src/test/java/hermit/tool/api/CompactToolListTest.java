package hermit.tool.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The compact tool list must stay small enough to fit a remote refiner's tiny
 * context window while still naming every tool. */
class CompactToolListTest {

    @Test
    void compactListNamesToolsAndStaysShort() {
        String list = new ToolRegistry().compactToolList();
        assertTrue(list.contains("LocateFile:"), list);
        assertTrue(list.contains("RemoteAgent:"), list);
        assertTrue(list.contains("Bash:"), list);
        // Every line is a short "name: gloss" (no full-paragraph docs leaking in).
        for (String line : list.split("\n")) {
            assertTrue(line.length() <= 80, "line too long for compact list: " + line);
        }
    }
}
