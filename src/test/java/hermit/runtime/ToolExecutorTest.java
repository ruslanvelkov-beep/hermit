package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import hermit.tool.api.ToolRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ToolExecutor.resolvePlaceholders is the single choke-point that rewrites the placeholder args a
 * small model copies verbatim from the tool docs ("<project dir>", "/path/to/...") into the real
 * configured values, so tools stop failing on unresolved args.
 */
class ToolExecutorTest {

    @AfterEach
    void clear() {
        System.clearProperty("hermit.projectDir");
    }

    @Test
    void rewritesPlaceholderProjectPaths() {
        System.setProperty("hermit.projectDir", "/real/project");

        Map<String, Object> in = new HashMap<>();
        in.put("projectDir", "<project dir>");
        in.put("sourceCodePath", "/path/to/your/project");
        in.put("workingDir", "");
        in.put("symbol", "doExport");        // real value - must be untouched
        in.put("maxResults", 50);            // non-string - must be untouched

        Map<String, Object> out = ToolExecutor.resolvePlaceholders(in);

        assertEquals("/real/project", out.get("projectDir"));
        assertEquals("/real/project", out.get("sourceCodePath"));
        assertEquals("/real/project", out.get("workingDir"));
        assertEquals("doExport", out.get("symbol"));
        assertEquals(50, out.get("maxResults"));
    }

    @Test
    void leavesRealValuesAndUnsetDefaultsAlone() {
        // no properties set -> nothing to substitute, placeholders pass through unchanged
        Map<String, Object> in = new HashMap<>();
        in.put("projectDir", "<project dir>");
        in.put("workingDir", "/actual/dir");
        Map<String, Object> out = ToolExecutor.resolvePlaceholders(in);
        assertEquals("<project dir>", out.get("projectDir"));
        assertEquals("/actual/dir", out.get("workingDir"));

        // a real value is never overwritten even when a default exists
        System.setProperty("hermit.projectDir", "/real/project");
        Map<String, Object> in2 = new HashMap<>();
        in2.put("projectDir", "/some/explicit/project");
        assertEquals("/some/explicit/project", ToolExecutor.resolvePlaceholders(in2).get("projectDir"));
    }

    @Test
    void unfilledSlotsAreRefusedBeforeTheToolRuns() {
        // a guidance slot like "<an EXISTING method - …>" reached the tool as a parameter value
        ToolExecutor ex = new ToolExecutor(new ToolRegistry());
        Map<String, Object> out = ex.executeActions(List.of(
                new Action("Bash", Map.of("command", "<the shell command to run here>")),
                new Action("Bash", Map.of("command", "echo filled"))));
        Map<?, ?> refused = (Map<?, ?>) out.get("Bash");
        assertEquals("Error", refused.get("status"), refused.toString());
        assertTrue(String.valueOf(refused.get("message")).contains("not a real value")
                && String.valueOf(refused.get("message")).contains("'command'"), refused.toString());
        assertTrue(String.valueOf(((Map<?, ?>) out.get("Bash#2")).get("output")).contains("filled"));
        // generics and short tokens are not slots
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("code", "List<String> xs = new ArrayList<>();")));
        // a bracketed lower-case token is a slot however short
        assertEquals("op", ToolExecutor.unfilledSlot(Map.of("op", "<x>")));
        // a document written through a here-document may contain angle-bracket placeholders of its own
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("command",
                "cat > docs/FLOW.md <<'EOF'\nWrite docs/contracts/<area>.md, "
                        + "then run --tests <class>.\nEOF\ntest -s docs/FLOW.md")));
        assertEquals("command", ToolExecutor.unfilledSlot(Map.of("command",
                "cat > docs/FLOW.md <<'EOF'\ntext\nEOF\nsed -n '1,20p' <file>")),
                "a slot outside the document still counts");
        assertEquals("testMethod", ToolExecutor.unfilledSlot(Map.of("testMethod", "<testMethodName>")));
    }

    @Test
    void deBracketedPlaceholdersAndProseInIdentifierParamsAreRefused() {
        // After angle brackets were forbidden, the planner emitted the SLOT NAME itself -
        // testMethod=existingTestMethodName - and later a whole prose phrase; both are unfilled values
        assertEquals("testMethod", ToolExecutor.unfilledSlot(Map.of("testMethod", "existingTestMethodName")));
        assertEquals("testMethod", ToolExecutor.unfilledSlot(Map.of("testMethod", "testMethodName")));
        assertEquals("method", ToolExecutor.unfilledSlot(Map.of("method", "yourTestMethodHere")));
        assertEquals("symbol", ToolExecutor.unfilledSlot(Map.of("symbol", "theExactMethodName")));
        // prose can never be an identifier-shaped parameter
        assertEquals("testMethod", ToolExecutor.unfilledSlot(
                Map.of("testMethod", "the existing @Test method's simple name")));
        assertEquals("templateMethod", ToolExecutor.unfilledSlot(
                Map.of("templateMethod", "ThermostatTest#some existing test")));
        // real values pass, including specs, filters and prose in prose-carrying params
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("testMethod", "nextReadingUsesSampleIntervalMillis")));
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("method", "ThermostatTest#setPointThrows")));
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("symbol", "Thermostat.java:86")));
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("task", "a JUnit test that stubs the sensor reader")));
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("command", "./gradlew :app:test --tests X.y")));
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("testName", "resetClearsDisplay")));
        // several slots in one value
        assertEquals("symbol", ToolExecutor.unfilledSlot(Map.of("symbol", "<File.java>:<line>")));
        assertEquals("method", ToolExecutor.unfilledSlot(Map.of("method", "<Type>#<method>")));
        assertEquals("include", ToolExecutor.unfilledSlot(Map.of("include", "com.<module>.service.")));
        // Java generics are not slots
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("code", "Map<String, Integer> m = new HashMap<>();")));
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("code", "List<Instant> times = List.of();")));
        // a spec with stray spaces is repairable, not prose
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("symbol", "Thermostat # setPoint")));
        assertTrue(ToolExecutor.isProse("the existing @Test method's simple name"));
        assertTrue(ToolExecutor.isProse("some test"));
        assertFalse(ToolExecutor.isProse("ThermostatTest#setPointThrows"));
        assertFalse(ToolExecutor.isProse("JavaParser#parseTypeDeclarationRest(Cursor, Node, Node)"),
                "a spec with a parameter list is a name, not a phrase");
        assertFalse(ToolExecutor.isProse("Parser#parse(java.util.List<Token> tokens, int from)"));
        assertTrue(ToolExecutor.isProse("the method that parses the rest"));
    }

    @Test
    void repeatedToolsInOnePlanKeepEveryResultInOrder() {
        // gradle test → git commit → git rev-parse are three Bash actions; keyed by tool name they
        // collapsed into the last one and the planner never saw the first two.
        ToolExecutor ex = new ToolExecutor(new ToolRegistry());
        Map<String, Object> out = ex.executeActions(List.of(
                new Action("Bash", Map.of("command", "echo one")),
                new Action("Bash", Map.of("command", "echo two")),
                new Action("Bash", Map.of("command", "echo three"))));
        assertEquals(List.of("Bash", "Bash#2", "Bash#3"), new ArrayList<>(out.keySet()));
        assertTrue(String.valueOf(((Map<?, ?>) out.get("Bash")).get("output")).contains("one"));
        assertTrue(String.valueOf(((Map<?, ?>) out.get("Bash#3")).get("output")).contains("three"));
        assertEquals("Bash", ToolExecutor.baseToolName("Bash#2"));
        assertEquals("TreeCodegen", ToolExecutor.baseToolName("TreeCodegen"));
    }

    @Test
    void aJavadocWithHtmlInCodeIsNotAnUnfilledSlot() {
        // a class comment's <ul> and <li> read as placeholder slots, so the addType item that
        // carried the class was refused as "a DESCRIPTION of the value"
        String classWithJavadoc = "/**\n * A graph:\n * <ul>\n *   <li>declarations;</li>\n * </ul>\n * <p>More.\n */\n"
                + "public final class G {\n}";
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("code", classWithJavadoc)));
        assertEquals("code", ToolExecutor.unfilledSlot(Map.of("code", "<the method body here>")),
                "a slot in code still is one");
        // a usage text block's <srcDir> and <command> are the text of a string, not slots
        String usage = "static final String USAGE = \"\"\"\n        usage: G --root <srcDir> <command>\n"
                + "        \"\"\";\n"
                + "static final String HELP = \"see <more>\";";
        assertEquals(null, ToolExecutor.unfilledSlot(Map.of("code", usage)));
    }

    @Test
    void aCommitAfterAFailedEditInTheSamePlanIsNotRun() {
        // the plan's first edit was refused, and the plan went on to run its commit command,
        // which committed a file holding only its imports
        ToolExecutor ex = new ToolExecutor(new ToolRegistry());
        Map<String, Object> out = ex.executeActions(List.of(
                new Action("TreeEdit",
                        Map.of("file", "/nonexistent/dir/A.java", "op", "addImport", "code", "java.util.List")),
                new Action("Bash", Map.of("command", "echo before && git commit --dry-run -m probe"))));
        assertFalse("Success".equals(((Map<?, ?>) out.get("TreeEdit")).get("status")), out.toString());
        Map<?, ?> commit = (Map<?, ?>) out.get("Bash");
        assertEquals("Error", commit.get("status"), commit.toString());
        assertTrue(String.valueOf(commit.get("message")).contains("not run"), commit.toString());
        // an edit refused before it runs (an unfilled slot) holds the commit back too
        Map<String, Object> refused = ex.executeActions(List.of(
                new Action("TreeEdit",
                        Map.of("file", "/nonexistent/dir/A.java", "op", "insertMember", "code", "<the member here>")),
                new Action("Bash", Map.of("command", "echo before && git commit --dry-run -m probe"))));
        assertEquals("Error", ((Map<?, ?>) refused.get("Bash")).get("status"), refused.toString());
        Map<String, Object> fine = ex.executeActions(List.of(new Action("Bash", Map.of("command", "echo after"))));
        assertTrue(String.valueOf(((Map<?, ?>) fine.get("Bash")).get("output")).contains("after"),
                "a new plan starts clean");
    }
}

