package hermit.tool.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LocateFile must find a file by name deep in a nested (multi-module-like) tree, return its
 * relative + absolute paths, and NOT return copies living under pruned build/.git dirs.
 */
class LocateFileToolTest {

    @Test
    @SuppressWarnings("unchecked")
    void findsNestedFileAndPrunesBuildDirs(@TempDir Path root) throws Exception {
        // realistic nested location (like inventory/inventory-app/src/main/java/.../steps/)
        Path deep = root.resolve("inventory/inventory-app/src/main/java/com/x/steps");
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("StockLevels.java"), "class X {}");
        // a stale generated copy under build/ that must be pruned
        Path build = root.resolve("inventory/inventory-app/build/classes");
        Files.createDirectories(build);
        Files.writeString(build.resolve("StockLevels.java"), "// generated");

        Map<String, Object> out = new LocateFileTool().execute(
                Map.of("name", "StockLevels.java", "root", root.toString()));

        assertEquals("Success", out.get("status"));
        assertEquals(1, out.get("count"), "must find the source copy only, not the build/ copy");
        List<Map<String, String>> matches = (List<Map<String, String>>) out.get("matches");
        String rel = matches.get(0).get("relative");
        assertTrue(rel.contains("src/main/java"), "should be the real source path, was: " + rel);
        assertFalse(rel.contains("build/"), "must not return the build/ copy");
    }

    @Test
    @SuppressWarnings("unchecked")
    void supportsGlobAndReportsMisses(@TempDir Path root) throws Exception {
        Files.writeString(Files.createDirectories(root.resolve("a")).resolve("Foo.java"), "x");
        Files.writeString(root.resolve("a").resolve("Bar.java"), "x");

        Map<String, Object> glob = new LocateFileTool().execute(
                Map.of("name", "*.java", "root", root.toString()));
        assertEquals(2, glob.get("count"));

        Map<String, Object> miss = new LocateFileTool().execute(
                Map.of("name", "Nope.java", "root", root.toString()));
        assertEquals(0, miss.get("count"));
        assertTrue(String.valueOf(miss.get("note")).contains("no file named"));
    }

    @Test
    void requiresName() {
        Map<String, Object> out = new LocateFileTool().execute(Map.of("root", "/tmp"));
        assertEquals("Error", out.get("status"));
    }
}
