package hermit.tool.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WriteProgramToolTest {

    private final WriteProgramTool tool = new WriteProgramTool();

    @Test
    void deflattenFixesLiteralNewlineEscapesOnlyWhenFlattened() {
        // flattened (no real newlines, literal \n) -> repaired to real lines
        String flat = "import requests\\ndef f():\\n    return 1";
        assertEquals("import requests\ndef f():\n    return 1", WriteProgramTool.deflatten(flat));
        // genuine multi-line code is left ALONE, even if a string contains \n
        String real = "print('a\\nb')\nx = 1";
        assertEquals(real, WriteProgramTool.deflatten(real));
        // nothing to fix
        assertEquals("x = 1", WriteProgramTool.deflatten("x = 1"));
    }

    private static boolean has(String... cmd) {
        try {
            return new ProcessBuilder(cmd).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void writesChecksAndRunsPython() {
        assumeTrue(has("python3", "--version"), "python3 not available — skipping");
        Map<String, Object> r = tool.execute(Map.of("language", "python", "run", "true",
                "code", "print(6 * 7)"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(Boolean.TRUE, r.get("syntaxOk"));
        assertEquals(Boolean.TRUE, r.get("ran"));
        assertTrue(String.valueOf(r.get("stdout")).contains("42"), r.toString());
    }

    @Test
    void reportsSyntaxErrorAndDoesNotRun() {
        assumeTrue(has("python3", "--version"), "python3 not available — skipping");
        Map<String, Object> r = tool.execute(Map.of("language", "python", "run", "true",
                "code", "print(6 *"));            // syntax error
        assertEquals("Success", r.get("status"));   // tool ran fine; the program didn't compile
        assertEquals(Boolean.FALSE, r.get("syntaxOk"));
        assertEquals(Boolean.FALSE, r.get("ran"));
    }

    @Test
    void missingCodeIsAnError() {
        Map<String, Object> r = tool.execute(Map.of("language", "python"));
        assertEquals("Error", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).contains("fenced"), r.toString());
    }
}
