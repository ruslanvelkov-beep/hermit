package hermit.tool.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.tool.api.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TextLens as an agent tool: the findings, tags and spans of a text or a file, the problems, the opt-in. */
class TextAnalysisToolTest {

    static final TextAnalysisTool TOOL = new TextAnalysisTool(Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"),
            ZoneId.of("UTC")));

    @Test
    void checkListsTheFindings() {
        assertEquals(Map.of("status", "Success", "findings", List.of("October 20, 2026 was a Tuesday, not a Monday."),
                "message", "1 finding"), TOOL.execute(Map.of("action", "check",
                "text", "The release was on Mon Oct 20, 2026.")));
        assertEquals(Map.of("status", "Success", "findings", List.of(), "message", "no findings"),
                TOOL.execute(Map.of("action", "check", "text", "We met on Tue Oct 20.")));
    }

    @Test
    void tagAndSpansListTheWords(@TempDir Path dir) throws IOException {
        assertEquals(Map.of("status", "Success", "tags", "Just/ADV sort/VERB files/NOUN by/ADP size/NOUN ./PUNCT"),
                TOOL.execute(Map.of("action", "tag", "text", "Just sort files by size.")));
        Path note = dir.resolve("note.md");
        Files.writeString(note, "Version 1.9 shipped on Oct 20, 2026.");
        assertEquals(Map.of("status", "Success", "spans", List.of("VERSION: 1.9", "DATE: Oct 20, 2026")),
                TOOL.execute(Map.of("action", "spans", "path", note.toString())));
    }

    @Test
    void aLongListIsCut() {
        Map<String, Object> result = TOOL.execute(Map.of("action", "tag", "text", "word ".repeat(250)));
        String[] tags = result.get("tags").toString().split(" ");
        assertEquals("word/NOUN", tags[0]);
        assertTrue(result.get("tags").toString().endsWith("word/NOUN ... and 50 more"), result.toString());
    }

    @Test
    void aProblemIsAnError(@TempDir Path dir) {
        assertEquals(Map.of("status", "Error", "message", "action must be check, tag or spans."),
                TOOL.execute(Map.of("action", "fix", "text", "x")));
        assertEquals(Map.of("status", "Error", "message", "give either text or path, not both and not neither."),
                TOOL.execute(Map.of("action", "check")));
        assertEquals("Error", TOOL.execute(Map.of("action", "check", "text", "x", "path", "y")).get("status"));
        Map<String, Object> missing = TOOL.execute(Map.of("action", "check",
                "path", dir.resolve("none.md").toString()));
        assertEquals("Error", missing.get("status"));
        assertTrue(missing.get("message").toString().startsWith("cannot read "), missing.toString());
    }

    @Test
    void theRegistryOffersItOnlyWhenAsked() {
        String saved = System.getProperty("hermit.textlens.tool");
        try {
            System.clearProperty("hermit.textlens.tool");
            assertNull(new ToolRegistry().getTool("TextAnalysis"));
            System.setProperty("hermit.textlens.tool", "true");
            assertNotNull(new ToolRegistry().getTool("TextAnalysis"));
        } finally {
            if (saved == null) {
                System.clearProperty("hermit.textlens.tool");
            } else {
                System.setProperty("hermit.textlens.tool", saved);
            }
        }
    }
}
