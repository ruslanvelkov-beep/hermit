package hermit.tool.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.graph.SpecReachTest;
import hermit.tool.graph.CodeGraphTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The planner-facing composer: reach / verbs / compose over the cached graph, writing only when valid. */
class SpecTestComposerToolTest {

    @TempDir
    Path dir;

    private final SpecTestComposerTool tool = new SpecTestComposerTool();

    @BeforeEach
    void fixture() throws IOException {
        SpecReachTest shape = new SpecReachTest();
        shape.dir = dir;
        shape.fixture();
        Files.writeString(dir.resolve("SpecTest.java"), "package example.spec.junit;\npublic @interface SpecTest {}\n");
        Files.writeString(dir.resolve("DynamicTest.java"), "package org.junit.jupiter.api;\npublic class DynamicTest {}\n");
        Files.writeString(dir.resolve("SpecRunner.java"), "package example.spec;\n"
                + "import java.util.stream.Stream;\nimport org.junit.jupiter.api.DynamicTest;\n"
                + "public class SpecRunner {\n    public static Stream<DynamicTest> specTest(Object... ops) { return null; }\n}\n");
        Files.writeString(dir.resolve("LoanSuite.java"),
                "package example.spec.suites.loans;\n\nclass LoanSuite {\n}\n");
    }

    private Map<String, Object> call(Map<String, Object> extra) {
        Map<String, Object> p = new HashMap<>(extra);
        p.put("projectDir", dir.toString());
        return tool.execute(p);
    }

    @Test
    void reachListsVerbsForATarget() {
        Map<String, Object> r = call(Map.of("action", "reach", "target", "LoanLedger#stampDueDate"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(true, r.get("specReachable"));
        assertTrue(((List<?>) r.get("verbs")).contains("checkoutBook()"), r.toString());
    }

    @Test
    void verbsCatalogueFilters() {
        Map<String, Object> r = call(Map.of("action", "verbs", "query", "ShelfLookup"));
        assertEquals("Success", r.get("status"));
        assertEquals(1, ((List<?>) r.get("verbs")).size(), r.toString());
    }

    @Test
    void missingActionIsInferredFromTheParameters() throws IOException {
        // every compose parameter present, no action
        Path suite = dir.resolve("LoanSuite.java");
        Map<String, Object> ok = call(Map.of("suiteFile", suite.toString(), "testName", "checkoutStampsDueDateInferred",
                "ops", List.of("checkoutBook()"), "target", "LoanLedger#stampDueDate"));
        assertEquals("Success", ok.get("status"), ok.toString());
        assertEquals("compose", ok.get("action"), ok.toString());
        assertTrue(Files.readString(suite).contains("checkoutStampsDueDateInferred()"));
        // a target alone is a reach question
        Map<String, Object> reach = call(Map.of("target", "LoanLedger#stampDueDate"));
        assertEquals("Success", reach.get("status"), reach.toString());
        assertEquals("reach", reach.get("action"), reach.toString());
        // nothing to infer from → still coached
        assertEquals("Error", call(Map.of()).get("status"));
    }

    @Test
    void composeWritesTheSuiteOnlyWhenValid() throws IOException {
        Path suite = dir.resolve("LoanSuite.java");
        Map<String, Object> bad = call(Map.of("action", "compose", "suiteFile", suite.toString(),
                "testName", "vacuous", "ops", List.of("getShelf(\"42\")"), "target", "LoanLedger#stampDueDate"));
        assertEquals("Error", bad.get("status"), bad.toString());
        assertTrue(String.valueOf(bad.get("problems")).contains("VACUOUS"), bad.toString());
        assertEquals("package example.spec.suites.loans;\n\nclass LoanSuite {\n}\n", Files.readString(suite));

        Map<String, Object> ok = call(Map.of("action", "compose", "suiteFile", suite.toString(),
                "testName", "checkoutStampsDueDate", "ops", List.of("checkoutBook()"),
                "target", "LoanLedger#stampDueDate"));
        assertEquals("Success", ok.get("status"), ok.toString());
        assertEquals(true, ok.get("written"));
        String written = Files.readString(suite);
        assertTrue(written.contains("checkoutStampsDueDate()"), written);
        assertTrue(written.contains("import static spec.CommandVerbs.checkoutBook;"), written);
        assertTrue(String.valueOf(ok.get("next")).contains("example.spec.suites.loans.LoanSuite.checkoutStampsDueDate"),
                ok.toString());
    }

    @Test
    void composeIntoANewSuiteCreatesTheFile() {
        Map<String, Object> ok = call(Map.of("action", "compose",
                "newSuite", Map.of("dir", dir.toString(), "package", "example.spec.suites.misc", "className", "Fresh"),
                "testName", "t1", "ops", List.of("checkoutCopy()")));
        assertEquals("Success", ok.get("status"), ok.toString());
        assertEquals(true, ok.get("newFile"));
        assertTrue(Files.exists(dir.resolve("example/spec/suites/misc/Fresh.java")), ok.toString());
    }

    @Test
    void actionAliasesFromTheOtherSpecSurfaceAreAccepted() {
        // the planner mixes CodeGraph's specreach and the composer's reach/verbs
        Map<String, Object> r = call(Map.of("action", "specreach", "target", "LoanLedger#stampDueDate"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(true, r.get("specReachable"));
        Map<String, Object> cg = new CodeGraphTool().execute(Map.of("action", "verbs", "symbol", "ShelfLookup",
                "projectDir", dir.toString()));
        assertEquals("Success", cg.get("status"), cg.toString());
        assertEquals(1, ((List<?>) cg.get("verbs")).size(), cg.toString());
        assertTrue(String.valueOf(cg.get("hint")).contains("action=specreach"), cg.toString());
    }

    @Test
    void quotedOpsAreUnquotedAndANewSuitePathYieldsItsPackage() {
        assertEquals("shelfGet(42).hasShelf(x)", SpecTestComposerTool.unquoted("\"shelfGet(42).hasShelf(x)\""));
        assertEquals("shelfMove(42).toShelf(c)", SpecTestComposerTool.unquoted("'shelfMove(42).toShelf(c)',"));
        assertEquals("getShelf(\"42\")", SpecTestComposerTool.unquoted("getShelf(\"42\")"));
        // JSON-escaped quotes inside an op are quotes
        assertEquals("bookCreate(42, \"0xdeadbeef\", Payload.of(\"test\"))",
                SpecTestComposerTool.unquoted("\"bookCreate(42, \\\"0xdeadbeef\\\", Payload.of(\\\"test\\\"))\""));
        assertEquals("example.spec.suites", SpecTestComposerTool.packageFromPath(
                Path.of("/w/library-app/spec-tests/src/main/java/example/spec/suites/OverdueNoticeTest.java")));
        assertEquals(null, SpecTestComposerTool.packageFromPath(Path.of("/w/somewhere/else/X.java")));
    }

    @Test
    void unknownVariablesPointAtTheDeclareField() {
        // only title/expectedShelf stood between the composition and the target
        List<String> problems = SpecTestComposerTool.withDeclareHint(
                List.of("[16:42] unknown variable 'title'", "[17:48] unknown variable 'expectedShelf'"), List.of());
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(2).contains("\"declare\": [{\"name\": \"title\""), problems.get(2));
        assertTrue(problems.get(2).contains("{\"name\": \"expectedShelf\""), problems.get(2));
        assertEquals(List.of("VACUOUS"), SpecTestComposerTool.withDeclareHint(List.of("VACUOUS"), List.of()));
    }

    // a suite whose source root exists nowhere was "validated" against nothing and written
    @Test
    void aSuiteWhoseSourceRootDoesNotExistIsRefused() throws Exception {
        Path project = Files.createTempDirectory("proj");
        Path srcRoot = project.resolve("library-app/spec-tests/src/main/java");
        Files.createDirectories(srcRoot);
        assertNull(SpecTestComposerTool.outsideSourceTree(srcRoot, project));
        String doubled = SpecTestComposerTool.outsideSourceTree(
                project.resolve("library-app/library-app/spec-tests/src/main/java"), project);
        assertNotNull(doubled);
        assertTrue(doubled.contains("not an existing directory under the project"), doubled);
        assertNotNull(SpecTestComposerTool.outsideSourceTree(Path.of("/"), project), "outside the project");
    }

    // single-quoted ids were refused three plans in a row; a compose that would be refused
    // is now told so at plan time
    @Test
    void singleQuotedLiteralsAreRewrittenAndAComposeIsPreflighted() {
        assertEquals("getShelf(\"42\").hasShelf(s)", SpecTestComposerTool.doubleQuoted("getShelf('42').hasShelf(s)"));
        assertEquals("shelfMove(\"42\").byMember(DEFAULT_USER)",
                SpecTestComposerTool.doubleQuoted("shelfMove('42').byMember(DEFAULT_USER)"));
        // an op that already uses double quotes is left alone (a single quote inside may be a char literal)
        assertEquals("x(\"a'b\")", SpecTestComposerTool.doubleQuoted("x(\"a'b\")"));
        assertNull(SpecTestComposerTool.doubleQuoted(null));

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("action", "compose");
        bad.put("testName", "t");
        bad.put("ops", List.of("bookCreate(42, hex(\"deadbeef\"))", "shelfGet(42)",
                "getShelf('42').hasShelf(expectedShelf)"));
        bad.put("declare", List.of(Map.of("name", "expectedShelf", "expr", "hex(\"2a9f...<full digest hex>...\")")));
        String pre = SpecTestComposerTool.preflight(bad);
        assertNotNull(pre);
        assertTrue(pre.contains("needs suiteFile or newSuite"), pre);
        assertTrue(pre.contains("passes the number 42"), pre);
        assertTrue(pre.contains("holds a placeholder"), pre);
        // an undeclared local is named
        Map<String, Object> undeclared = new LinkedHashMap<>(bad);
        undeclared.put("newSuite", Map.of("dir", "x", "package", "p", "className", "C"));
        undeclared.put("ops", List.of("shelfMove(\"42\").toShelf(destination)",
                "getShelf(\"42\").hasShelf(expectedShelf)"));
        undeclared.put("declare", List.of(Map.of("name", "expectedShelf", "expr", "hex(sha256Of(destination))")));
        String pre2 = SpecTestComposerTool.preflight(undeclared);
        assertNotNull(pre2);
        assertTrue(pre2.contains("'destination' which no declare defines"), pre2);
        assertFalse(pre2.contains("expectedShelf' which"), pre2);
        // a sound compose passes; helpers and constants need no declare; single quotes are normalized first
        Map<String, Object> good = new LinkedHashMap<>(undeclared);
        good.put("ops", List.of("shelfMove('42').byMember(DEFAULT_USER).toShelf(destination)",
                "getShelf('42').hasShelf(expectedShelf)"));
        good.put("declare", List.of(Map.of("name", "destination", "expr", "\"B-12\""),
                Map.of("name", "expectedShelf", "expr", "hex(sha256Of(destination))")));
        assertNull(SpecTestComposerTool.preflight(good));
        // reach/verbs actions are not preflighted
        assertNull(SpecTestComposerTool.preflight(Map.of("action", "reach", "target", "X#y")));
    }
}
