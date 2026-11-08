package hermit.tool.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The planner-facing CodeGraph tool: symbol-shape leniency and the analysis actions. */
class CodeGraphToolTest {

    @TempDir
    Path dir;

    private final CodeGraphTool tool = new CodeGraphTool();

    @BeforeEach
    void fixture() throws IOException {
        Files.writeString(dir.resolve("Svc.java"), """
                class Svc {
                    void work() {
                        try {
                            risky();
                        } catch (IllegalStateException e) {
                            recover();
                        }
                    }

                    void risky() {
                        throw new IllegalStateException("boom");
                    }

                    void recover() { }
                }
                """);
    }

    private Map<String, Object> call(Map<String, Object> extra) {
        Map<String, Object> p = new HashMap<>(extra);
        p.put("projectDir", dir.toString());
        return tool.execute(p);
    }

    @Test
    void normalizeSymbolRepairsCommonShapes() {
        assertEquals("A#m", CodeGraphTool.normalizeSymbol("A#m"));
        assertEquals("A#m", CodeGraphTool.normalizeSymbol("A.m"));          // dot → hash
        assertEquals("m", CodeGraphTool.normalizeSymbol("receiver.m"));      // drop lowercase receiver
        assertEquals("Foo.java:42", CodeGraphTool.normalizeSymbol("Foo.java:42"));  // file:line untouched
        // the server/CLI spec shape Type:method (and its FQN form) is a '#', not a file:line address
        assertEquals("Thermostat#adjust",
                CodeGraphTool.normalizeSymbol("Thermostat:adjust"));
        assertEquals("Thermostat#adjust",
                CodeGraphTool.normalizeSymbol(
                        "com.example.home.climate.control.Thermostat:adjust"));
        assertEquals("plain", CodeGraphTool.normalizeSymbol("plain"));
        // An FQN-qualified spec reduces the type to its simple name (how the graph is keyed)
        assertEquals("CatalogSearch#byAuthor",
                CodeGraphTool.normalizeSymbol(
                        "com.example.library.catalog.search.CatalogSearch#byAuthor"));
        assertEquals("Inner#m", CodeGraphTool.normalizeSymbol("a.b.Outer.Inner#m"));
    }

    @Test
    void catchesAcceptsAMethodSpecAndNeedsNoLineNumber() {
        Map<String, Object> r = call(Map.of("action", "catches", "symbol", "Svc#work"));
        assertEquals("Success", r.get("status"), r.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("catches");
        assertEquals(1, rows.size(), rows.toString());
        assertEquals(List.of("IllegalStateException"), rows.get(0).get("declared"));
        assertTrue(rows.get(0).get("receives").toString().contains("IllegalStateException"),
                rows.get(0).toString());
        assertTrue(String.valueOf(rows.get(0).get("at")).startsWith("Svc.java:"), rows.get(0).toString());
    }

    @Test
    void impactAcceptsAMethodSpec() {
        Map<String, Object> r = call(Map.of("action", "impact", "symbol", "Svc#risky"));
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(r.get("impact") instanceof List, r.toString());
    }

    @Test
    void escapesAndReachWithARepairedTarget() {
        Map<String, Object> esc = call(Map.of("action", "escapes", "symbol", "Svc#risky"));
        assertEquals("Success", esc.get("status"), esc.toString());
        assertTrue(esc.get("escapes").toString().contains("IllegalStateException"), esc.toString());

        // a receiver-qualified target is repaired to a simple name and still resolves
        Map<String, Object> reach = call(Map.of("action", "reach",
                "symbol", "Svc#work", "target", "this.recover"));
        assertEquals("Success", reach.get("status"), reach.toString());
        assertEquals(true, reach.get("reachable"), reach.toString());
    }

    @Test
    void reachWithATypeTargetIsCoachedToTheExceptionActions() {
        // reach(symbol=Svc#work, target=Exception) - call-graph reach cannot answer an exception question
        Map<String, Object> r = call(Map.of("action", "reach", "symbol", "Svc#work", "target", "Exception"));
        assertEquals("Error", r.get("status"), r.toString());
        String msg = String.valueOf(r.get("message"));
        assertTrue(msg.contains("action=escapes") && msg.contains("action=catches"), msg);
    }

    @Test
    void reachWithAnUnresolvedStartIsAnErrorNotANotReachable() {
        Map<String, Object> r = call(Map.of("action", "reach", "symbol", "<fully.qualified.SvcTest>", "target", "Svc#work"));
        assertEquals("Error", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("message")).contains("start not found"), r.toString());
    }

    @Test
    void colonSpecsDoNotCrashTheLineAddressedActions() {
        // catches("Svc:work") used to throw NumberFormatException("For input string: work")
        Map<String, Object> r = call(Map.of("action", "catches", "symbol", "Svc:work"));
        assertEquals("Success", r.get("status"), r.toString());
        Map<String, Object> bad = call(Map.of("action", "catches", "symbol", "Svc.java:notaline"));
        assertEquals("Error", bad.get("status"), bad.toString());
        assertTrue(String.valueOf(bad.get("message")).contains("not a line number"), bad.toString());
    }

    @Test
    void impactAcceptsAStatementAnchorInsteadOfALineNumber() {
        // impact(symbol=Type#method, anchor=<statement text>) sliced from the FIRST statement
        Map<String, Object> r = call(Map.of("action", "impact", "symbol", "Svc#work", "anchor", "recover();"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("Svc.java:6", r.get("anchoredAt"), r.toString());
        Map<String, Object> bad = call(Map.of("action", "impact", "symbol", "Svc#work", "anchor", "nothingLikeThis()"));
        assertEquals("Error", bad.get("status"), bad.toString());
    }

    @Test
    void targetOrMethodStandInForSymbolOnSingleSymbolActions() {
        // catches{target=Svc#work} - the planner's parameter name slipped
        Map<String, Object> r = call(Map.of("action", "catches", "target", "Svc#work"));
        assertEquals("Success", r.get("status"), r.toString());
        Map<String, Object> r2 = call(Map.of("action", "callees", "method", "Svc#work"));
        assertEquals("Success", r2.get("status"), r2.toString());
    }

    @Test
    void reachWithoutAStartAndWildcardsAreCoachedTowardTestsOrCallers() {
        // reach{target=X} with no symbol → "action and symbol are required" was re-planned identically
        Map<String, Object> r = call(Map.of("action", "reach", "target", "Svc#risky"));
        assertEquals("Error", r.get("status"), r.toString());
        String msg = String.valueOf(r.get("message"));
        assertTrue(msg.contains("START") && msg.contains("action=tests") && msg.contains("action=callers"), msg);
        // a start without a target gets the same role coaching
        Map<String, Object> t = call(Map.of("action", "reach", "symbol", "Svc#work"));
        assertEquals("Error", t.get("status"), t.toString());
        assertTrue(String.valueOf(t.get("message")).contains("action=callees")
                && String.valueOf(t.get("message")).contains("action=tests"), t.toString());
        // reach{target=TestClass#*} means "which tests reach it"
        Map<String, Object> w = call(Map.of("action", "reach", "symbol", "Svc#work", "target", "SvcTest#*"));
        assertEquals("Error", w.get("status"), w.toString());
        assertTrue(String.valueOf(w.get("message")).contains("action=tests"), w.toString());
    }

    @Test
    void testsActionListsTheExercisingTests() throws IOException {
        Files.createDirectories(dir.resolve("src/test/java"));
        Files.writeString(dir.resolve("src/test/java/SvcTest.java"), """
                class SvcTest {
                    @Test
                    void recovers() { new Svc().work(); }
                }
                """);
        Map<String, Object> r = call(Map.of("action", "tests", "symbol", "Svc#recover"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(true, r.get("exercised"), r.toString());
        assertTrue(String.valueOf(r.get("tests")).contains("SvcTest#recovers"), r.toString());
        assertTrue(String.valueOf(r.get("hint")).contains("templateMethod"), r.toString());
        // the role repair applies: target= instead of symbol= still works for this single-symbol action
        Map<String, Object> r2 = call(Map.of("action", "tests", "target", "Svc#recover"));
        assertEquals("Success", r2.get("status"), r2.toString());
    }

    @Test
    void reverseWalkActionsRefuseATypeAndNameTheTestClassMistake() {
        // tests(symbol=SvcTest) answered exercised=false with an empty list
        Map<String, Object> r = call(Map.of("action", "tests", "symbol", "com.example.SvcTest"));
        assertEquals("Error", r.get("status"), r.toString());
        String msg = String.valueOf(r.get("message"));
        assertTrue(msg.contains("PRODUCTION") && msg.contains("TEST class") && msg.contains("action=methods"), msg);
        // a production type is coached to add the method
        Map<String, Object> t = call(Map.of("action", "tests", "symbol", "Svc"));
        assertEquals("Error", t.get("status"), t.toString());
        assertTrue(String.valueOf(t.get("message")).contains("Add #<method>"), t.toString());
        // a real method still works
        assertEquals("Success", call(Map.of("action", "tests", "symbol", "Svc#recover")).get("status"));
        // an unresolvable target is an Error, not a Success with an empty list
        Map<String, Object> typo = call(Map.of("action", "tests", "symbol", "Svc#recoverr"));
        assertEquals("Error", typo.get("status"), typo.toString());
        assertTrue(String.valueOf(typo.get("message")).contains("target not found")
                && String.valueOf(typo.get("message")).contains("action=locate"), typo.toString());
        assertTrue(CodeGraphTool.looksLikeTypeName("com.example.SvcTest"));
        assertFalse(CodeGraphTool.looksLikeTypeName("Svc#recover"));
        assertFalse(CodeGraphTool.looksLikeTypeName("recover"));
        assertFalse(CodeGraphTool.looksLikeTypeName("Svc.java:3"));
    }

    @Test
    void reachRefusesATypeTargetInAnyCase() {
        // target=com.x.SvcTest (lower-case first letter) slipped past the guard and came back as
        // Success reachable=false
        Map<String, Object> r = call(Map.of("action", "reach", "symbol", "Svc#work", "target", "com.example.SvcTest"));
        assertEquals("Error", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("message")).contains("START method")
                && String.valueOf(r.get("message")).contains("TestClass#testMethod"), r.toString());
        // a real method target still works
        Map<String, Object> ok = call(Map.of("action", "reach", "symbol", "Svc#work", "target", "Svc#risky"));
        assertEquals("Success", ok.get("status"), ok.toString());
        assertEquals(true, ok.get("reachable"), ok.toString());
    }

    @Test
    void methodActionsRefuseALineAddress() {
        // escapes(symbol=Svc.java:3) answered escapes=[] instead of coaching
        Map<String, Object> r = call(Map.of("action", "escapes", "symbol", "Svc.java:3"));
        assertEquals("Error", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("message")).contains("Type#method"), r.toString());
        // line-addressed actions still accept it
        assertEquals("Success", call(Map.of("action", "catches", "symbol", "Svc.java:3")).get("status"));
    }

    @Test
    void methodsActionListsATypesMethodsAndRefusesAnUnknownType() {
        Map<String, Object> listed = call(Map.of("action", "methods", "symbol", "Svc"));
        String text = String.valueOf(listed.get("methods"));
        assertTrue(text.contains("work") && text.contains("risky") && text.contains("recover"), listed.toString());
        assertEquals("Error", call(Map.of("action", "methods", "symbol", "Missing")).get("status"));
    }
}
