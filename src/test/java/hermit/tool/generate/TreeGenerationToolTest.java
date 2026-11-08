package hermit.tool.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.sym.ClasspathIndex;
import hermit.tool.analysis.TreeEditTool;
import hermit.tool.helper.HelperModelTool;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The incremental-codegen loop, driven by a SCRIPTED author/critic instead of the live model. */
class TreeGenerationToolTest {

    private static final String APPROVE = "{\"approved\": true, \"issues\": []}";

    @TempDir
    Path dir;

    private Path aFile;

    @BeforeEach
    void setUp() throws IOException {
        // A NAMED package unknown to the classpath index: sibling-file types (Target from A.java) are
        // honestly unprovable per-file, so cross-file references pass validation and javac remains the
        // arbiter - exactly the multi-file posture of a real project without compiled classes.
        aFile = dir.resolve("A.java");
        Files.writeString(aFile, """
                package t;

                class A {
                    void run() {
                        int base = 1;
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Files.writeString(dir.resolve("Target.java"), """
                package t;

                class Target {
                    static void target() { }
                }
                """);
    }

    @AfterEach
    void clearProps() {
        System.clearProperty("hermit.treegen.processRetries");
        System.clearProperty("hermit.treegen.criticRepairs");
        System.clearProperty("hermit.treegen.emulate");
    }

    /** A scripted LLM: pops canned responses in order; fails the test when over-consumed. */
    private static Function<String, String> scripted(Deque<String> responses) {
        return prompt -> {
            if (responses.isEmpty()) {
                throw new AssertionError("scripted LLM exhausted; prompt was:\n" + prompt);
            }
            return responses.poll();
        };
    }

    private Map<String, Object> baseParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("projectDir", dir.toString());
        p.put("file", aFile.toString());
        p.put("method", "A#run");
        p.put("task", "make run() reach Target.target() through a helper");
        return p;
    }

    @Test
    void presetItemsCommitCompileAndReachVerify() throws IOException {
        Deque<String> llm = new ArrayDeque<>(List.of(APPROVE, APPROVE, APPROVE));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(
                Map.of("op", "insertMember", "type", "A",
                        "code", "void helper() { Target.target(); }"),
                Map.of("op", "insertAtEnd", "method", "A#run", "code", "helper();")));
        p.put("targetReach", "Target#target");
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(1, r.get("rounds"));
        assertEquals(List.of("A#run", "A#helper", "Target#target"), r.get("reachPath"));
        assertTrue(String.valueOf(r.get("critics")).startsWith("3/3"));
        assertTrue(Files.readString(aFile).contains("helper();"));
        assertTrue(llm.isEmpty(), "all critic responses consumed");
    }

    @Test
    void rejectedItemsShowTheirCodeInTheTranscript() throws IOException {
        // F121 + F123 (drive 2K): a comment-only item is named a COMMENT (not "INCOMPLETE") and its code head is logged
        Deque<String> llm = new ArrayDeque<>(List.of(
                "{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"int twice = base * 2;\"}",
                APPROVE, APPROVE, APPROVE));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(
                Map.of("op", "insertAtEnd", "method", "A#run", "code", "// stub the helper to double base")));
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        String all = String.valueOf(r);
        assertTrue(all.contains("code=«// stub the helper"), all);
        assertTrue(all.contains("is a COMMENT") && !all.contains("INCOMPLETE"), all);
    }

    @Test
    void rejectedItemIsReauthoredFromTheProblems() throws IOException {
        Deque<String> llm = new ArrayDeque<>(List.of(
                // the fix for the rejected item (references a real variable this time)
                "{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"int twice = base * 2;\"}",
                APPROVE, APPROVE, APPROVE));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(
                Map.of("op", "insertAtEnd", "method", "A#run", "code", "int twice = missingVar * 2;")));
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        String out = Files.readString(aFile);
        assertTrue(out.contains("int twice = base * 2;"), out);
        assertTrue(!out.contains("missingVar"));
        assertTrue(llm.isEmpty());
    }

    @Test
    void compileFailureTriggersSurgicalRepair() throws IOException {
        // the item commits (validation has no return-path analysis) but javac rejects the missing
        // return - the repair author gets the javac errors and fixes the method IN PLACE, same round.
        Deque<String> llm = new ArrayDeque<>(List.of(
                "[{\"op\": \"insertAtEnd\", \"method\": \"A#broken\", \"code\": \"return 1;\"}]",
                APPROVE, APPROVE, APPROVE));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(
                Map.of("op", "insertMember", "type", "A", "code", "int broken() { }")));
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(1, r.get("rounds"), "no process round was burned on a one-line javac fix");
        String out = Files.readString(aFile);
        assertTrue(out.contains("return 1;"), out);
        @SuppressWarnings("unchecked")
        List<String> transcript = (List<String>) r.get("transcript");
        assertTrue(transcript.stream().anyMatch(t -> t.contains("compile failed — surgical repair 1")),
                transcript.toString());
        assertTrue(llm.isEmpty());
    }

    @Test
    void compileRepairPromptQuotesTheOffendingLineAndTheRepairRules() throws IOException {
        // the repair author must see the EXACT broken statement (surgical anchor) and the
        // never-re-add rules - otherwise a 3-bit appends near-duplicates until the panel rejects.
        List<String> repairPrompts = new ArrayList<>();
        Deque<String> canned = new ArrayDeque<>(List.of(
                "[{\"op\": \"insertAtEnd\", \"method\": \"A#broken\", \"code\": \"return 1;\"}]",
                APPROVE, APPROVE, APPROVE));
        Function<String, String> llm = prompt -> {
            if (prompt.contains("REPAIR it IN PLACE")) {
                repairPrompts.add(prompt);
            }
            return canned.poll();
        };
        TreeGenerationTool tool = new TreeGenerationTool(llm);
        Map<String, Object> p = baseParams();
        p.put("items", List.of(
                Map.of("op", "insertMember", "type", "A", "code", "int broken() { }")));
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(1, repairPrompts.size());
        String rp = repairPrompts.get(0);
        assertTrue(rp.contains("does NOT COMPILE"), rp);
        assertTrue(rp.contains("line ") && rp.contains("is: `"), "offending line quoted:\n" + rp);
        assertTrue(rp.contains("never RE-ADD a statement"), rp);
    }

    @Test
    void exhaustedRepairBudgetBurnsTheRoundWithTheJavacError() throws IOException {
        // the repair author keeps proposing items that still don't fix the compile error
        System.setProperty("hermit.treegen.processRetries", "1");
        System.setProperty("hermit.treegen.criticRepairs", "1");
        try {
            Deque<String> llm = new ArrayDeque<>(List.of(
                    "[{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"int unrelatedFix = 1;\"}]"));
            TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
            Map<String, Object> p = baseParams();
            p.put("items", List.of(
                    Map.of("op", "insertMember", "type", "A", "code", "int broken() { }")));
            Map<String, Object> r = tool.execute(p);
            assertEquals("Error", r.get("status"));
            assertTrue(String.valueOf(r.get("message")).contains("compilation failed"), r.toString());
            String out = Files.readString(aFile);
            assertEquals(false, out.contains("broken"), "the file was restored: " + out);
        } finally {
            System.clearProperty("hermit.treegen.criticRepairs");
        }
    }

    @Test
    void unparseableAuthorRepliesAreRetried() throws IOException {
        // the author's first two replies have no item array; the third is valid.
        Deque<String> llm = new ArrayDeque<>(List.of(
                "I think we should add a statement.",
                "{\"op\": \"not-an-array\"}",
                "[{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"int twice = base * 2;\"}]",
                APPROVE, APPROVE, APPROVE));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(Files.readString(aFile).contains("int twice = base * 2;"));
        assertTrue(llm.isEmpty());
    }

    @Test
    void criticsAreToldWhenARedTestOutcomeIsThePoint() {
        // with expectTestOutcome=red the critics must judge the DESIRED behavior, not reject the
        // test for contradicting today's implementation.
        List<String> criticPrompts = new ArrayList<>();
        Function<String, String> llm = prompt -> {
            if (prompt.contains("STRICT Java reviewer")) {
                criticPrompts.add(prompt);
                return APPROVE;
            }
            throw new AssertionError("unexpected non-critic prompt:\n" + prompt);
        };
        TreeGenerationTool tool = new TreeGenerationTool(llm);
        Map<String, Object> p = baseParams();
        p.put("items", List.of(
                Map.of("op", "insertAtEnd", "method", "A#run", "code", "int twice = base * 2;")));
        System.setProperty("hermit.treegen.emulate", "false");   // this test exercises the critics only
        p.put("expectTestOutcome", "red");   // no testFilter: the framing must not depend on the run
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(3, criticPrompts.size());
        assertTrue(criticPrompts.stream().allMatch(x -> x.contains("MUST fail (red)")
                        && x.contains("Do NOT reject it")),
                "every critic sees the TDD framing:\n" + criticPrompts.get(0));
    }

    @Test
    void criticDissentTriggersSurgicalRepairInsteadOfARoundRestart() throws IOException {
        // the committed, compiling method is REPAIRED in place from the critic issues - the
        // process round (and the method) survives.
        String reject = "{\"approved\": false, \"issues\": [\"run() must also derive the fixed value\"]}";
        Deque<String> llm = new ArrayDeque<>(List.of(
                APPROVE, reject, reject,
                "[{\"op\": \"insertAfter\", \"method\": \"A#run\", \"anchor\": \"int twice = base * 2;\","
                        + " \"code\": \"int fixed = twice + 1;\"}]",
                APPROVE, APPROVE, APPROVE));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(
                Map.of("op", "insertAtEnd", "method", "A#run", "code", "int twice = base * 2;")));
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(1, r.get("rounds"), "no process round was burned");
        assertTrue(String.valueOf(r.get("critics")).contains("after 1 repair(s)"), r.toString());
        String out = Files.readString(aFile);
        assertTrue(out.contains("int twice = base * 2;\n        int fixed = twice + 1;"), out);
        @SuppressWarnings("unchecked")
        List<String> transcript = (List<String>) r.get("transcript");
        assertTrue(transcript.stream().anyMatch(t -> t.contains("surgical repair 1")), transcript.toString());
        assertTrue(llm.isEmpty());
    }

    @Test
    void emulatorGateRepairsAPredictionContradiction() throws IOException {
        // expecting RED but the committed assertion would hold → the emulator predicts GREEN and the
        // repair author fixes the assertion IN PLACE, before any real test run
        String repair = "[{\"op\": \"replaceStatement\", \"method\": \"A#checkIt\","
                + " \"anchor\": \"assertEquals(2, x);\", \"code\": \"assertEquals(3, x);\"}]";
        Deque<String> llm = new ArrayDeque<>(List.of(
                APPROVE, APPROVE, APPROVE, repair, APPROVE, APPROVE, APPROVE));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("method", "A#checkIt");
        p.put("items", List.of(
                Map.of("op", "insertMember", "type", "A", "code", "void checkIt() {\n    }"),
                Map.of("op", "insertAtEnd", "method", "A#checkIt",
                        "code", "int x = 1 + 1;\nassertEquals(2, x);")));
        p.put("expectTestOutcome", "red");
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("emulatorPrediction")).startsWith("PREDICTED RED"), r.toString());
        assertTrue(Files.readString(aFile).contains("assertEquals(3, x);"));
        @SuppressWarnings("unchecked")
        List<String> transcript = (List<String>) r.get("transcript");
        assertTrue(transcript.stream().anyMatch(t -> t.contains("emulator contradicts expected red")),
                transcript.toString());
        assertTrue(llm.isEmpty());
    }

    @Test
    void unreachableTargetFailsTheRound() {
        System.setProperty("hermit.treegen.processRetries", "1");
        Deque<String> llm = new ArrayDeque<>();   // never consulted: preset items + failure before critics
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(
                Map.of("op", "insertAtEnd", "method", "A#run", "code", "int unrelated = base + 1;")));
        p.put("targetReach", "Target#target");
        Map<String, Object> r = tool.execute(p);
        assertEquals("Error", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).contains("does not transitively reach"), r.toString());
    }

    @Test
    void bareMethodNamesAreQualifiedFromTheFile() {
        // drive 2B: method=testRefillWhenEmptyThrows (no Type#) made the emulator gate skip
        assertEquals("StockLedgerTest#testFoo", TreeGenerationTool.qualifySpec("testFoo",
                "warehouse/stock-app/src/test/java/com/acme/stock/ledger/StockLedgerTest.java"));
        assertEquals("StockLedgerTest#testFoo", TreeGenerationTool.qualifySpec("StockLedgerTest.testFoo", "X.java"));
        // drive 2C: fully-qualified specs must reduce to the simple type the file-level lookups use
        assertEquals("StockLedgerTest#testFoo",
                TreeGenerationTool.qualifySpec("com.acme.stock.ledger.StockLedgerTest#testFoo", "X.java"));
        // a fully-qualified DOTTED spec starts lowercase - it is a.b.Type.method, not a bare name
        assertEquals("StockLedgerTest#keepsEntriesWithoutASupplier", TreeGenerationTool.qualifySpec(
                "com.acme.stock.ledger.StockLedgerTest.keepsEntriesWithoutASupplier", "StockLedgerTest.java"));
        assertEquals("A#m", TreeGenerationTool.qualifySpec("A#m", "X.java"));
        assertEquals("StockLedgerTest#testFoo",
                TreeGenerationTool.qualifySpec("StockLedgerTest:testFoo", "X.java"));   // F97
        assertEquals("StockLedger#restockAll",
                TreeGenerationTool.qualifySpec("com.acme.stock.ledger.StockLedger:restockAll", "X.java"));
        assertEquals(null, TreeGenerationTool.qualifySpec(null, "X.java"));
    }

    @Test
    void aMethodCannotBeModelledOnItself() throws IOException {
        // the plan set method == templateMethod and the run inserted an EMPTY DUPLICATE of the
        // existing test into the file
        TreeGenerationTool tool = new TreeGenerationTool(prompt -> APPROVE);
        Map<String, Object> p = baseParams();
        p.put("method", "A#run");
        p.put("templateMethod", "A#run");
        Map<String, Object> r = tool.execute(p);
        assertEquals("Error", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("message")).contains("SAME method")
                && String.valueOf(r.get("message")).contains("NEW test"), r.toString());
        assertTrue(Files.readString(aFile).contains("void run()"), "the file is untouched");
    }

    @Test
    void javaStyleConcatenatedItemsAreParsed() throws IOException {
        // the author wrote `"code": "a\n" + "b\n"` - invalid JSON - and four correct replies in a
        // row were discarded as "no parseable item array", which cost the campaign its first landed test
        Deque<String> llm = new ArrayDeque<>(List.of(
                "```json\n[\n  {\n    \"op\": \"insertAtEnd\",\n    \"method\": \"A#run\",\n"
                        + "    \"code\": \"int twice = base * 2;\\n\" +\n           \"Target.target();\"\n  }\n]\n```",
                APPROVE, APPROVE, APPROVE));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> r = tool.execute(baseParams());
        assertEquals("Success", r.get("status"), r.toString());
        String out = Files.readString(aFile);
        assertTrue(out.contains("int twice = base * 2;") && out.contains("Target.target();"), out);
    }

    @Test
    void aSingleItemObjectIsAcceptedToo() throws IOException {
        // the author sometimes replies with ONE item object instead of an array
        Deque<String> llm = new ArrayDeque<>(List.of(
                "{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"Target.target();\"}",
                APPROVE, APPROVE, APPROVE));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> r = tool.execute(baseParams());
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(Files.readString(aFile).contains("Target.target();"));
    }

    @Test
    void aTruncatedTypeInAnItemIsRepairedToTheTarget() {
        // the plan said InvoiceTest#testTotalThrows and the author wrote its
        // items against Test#testTotalThrows - both rounds died on "type not found: Test"
        assertEquals("InvoiceTest#testX",
                TreeGenerationTool.repairTruncatedType("Test#testX", "InvoiceTest#testX"));
        assertEquals("InvoiceTest",
                TreeGenerationTool.repairTruncatedType("Test", "InvoiceTest#testX"));
        assertEquals("InvoiceTest#testX",
                TreeGenerationTool.repairTruncatedType("com.x.InvoiceTest#testX", "InvoiceTest#testX"));
        // an unrelated type is left alone (the author may legitimately edit another class)
        assertEquals("Frobnicator#run",
                TreeGenerationTool.repairTruncatedType("Frobnicator#run", "InvoiceTest#testX"));
        // and a correct type is untouched
        assertEquals("InvoiceTest#other",
                TreeGenerationTool.repairTruncatedType("InvoiceTest#other", "InvoiceTest#testX"));
    }

    @Test
    void theRepairPromptQuotesTheTemplatesCorrectIdiom() throws IOException {
        // javac rejected willThrow(...).given(x).next(); the template stubs the same symbols the
        // right way round, and quoting it is more useful than the error alone
        Files.writeString(aFile, """
                package t;

                class A {
                    void templateTest() {
                        given(readings.next()).willReturn(one);
                        verify(heater).setLevel(LOW, zone);
                    }

                    void broken() {
                        int base = 1;
                    }
                }
                """);
        String javac = "A.java:9: error: cannot find symbol\n  symbol: method willThrow(RuntimeException)\n"
                + "A.java:10: error: method setLevel in class Heater cannot be applied to given types;";
        String idiom = TreeGenerationTool.idiomFromTemplate(aFile, "A#templateTest", javac);
        assertTrue(idiom.contains("uses those same symbols CORRECTLY"), idiom);
        assertTrue(idiom.contains("setLevel(LOW, zone)"), idiom);
        // no template, no symbols, or no matching line → nothing appended
        assertEquals("", TreeGenerationTool.idiomFromTemplate(aFile, null, javac));
        assertEquals("", TreeGenerationTool.idiomFromTemplate(aFile, "A#templateTest", "A.java:3: error: ';' expected"));
    }

    @Test
    void aMethodSpecWithAParameterListIsNormalized() {
        // "InvoiceTest.testX()" and "Type#m(int, String)" address the method m
        assertEquals("InvoiceTest#testX",
                TreeGenerationTool.qualifySpec("InvoiceTest.testX()", "/x/InvoiceTest.java"));
        assertEquals("InvoiceTest#testX",
                TreeGenerationTool.qualifySpec("InvoiceTest#testX(int, String)", "/x/InvoiceTest.java"));
        assertEquals("InvoiceTest#testX",
                TreeGenerationTool.qualifySpec("com.a.b.InvoiceTest#testX()", "/x/InvoiceTest.java"));
        // a plain spec is untouched
        assertEquals("InvoiceTest#testX",
                TreeGenerationTool.qualifySpec("InvoiceTest#testX", "/x/InvoiceTest.java"));
    }

    @Test
    void theProductionTwinMethodIsShownAsTheCodeUnderTest() throws IOException {
        // the author never saw the production method it was testing and kept a stub
        // that made the defect invisible; the twin of the test class supplies it, ranked by task-word overlap
        Files.writeString(dir.resolve("Thermostat.java"), """
                package t;

                class Thermostat {
                    long lastSetting;

                    void adjust(long requestedLevel) {
                        long safeLevel = lastSetting == 0 ? requestedLevel : lastSetting;
                        lastSetting = requestedLevel;
                    }

                    void unrelatedCleanup() { }
                }
                """);
        Files.writeString(dir.resolve("ThermostatTest.java"), """
                package t;

                class ThermostatTest {
                    void existing() { }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir),
                ClasspathIndex.system());
        List<String> transcript = new ArrayList<>();
        String section = TreeGenerationTool.codeUnderTest(g, "ThermostatTest#newTest",
                "when the sensor read throws, adjust must fall back only to safeLevel", null, transcript);
        assertTrue(section.contains("THE CODE UNDER TEST") && section.contains("void adjust"),
                section);
        assertFalse(section.contains("unrelatedCleanup"), section);
        assertTrue(transcript.get(0).startsWith("code under test: Thermostat#adjust"), transcript.toString());
        // an explicit target wins, and no twin means no section
        assertTrue(TreeGenerationTool.codeUnderTest(g, "ThermostatTest#x", "anything", "Thermostat#unrelatedCleanup",
                new ArrayList<>()).contains("unrelatedCleanup"));
        assertEquals("", TreeGenerationTool.codeUnderTest(g, "Nothing#x", "anything", null, new ArrayList<>()));
    }

    @Test
    void aWholeBodyRewriteThatDropsTheSetupIsRefused() {
        // a repair rewrote the body down to 8 of ~20 statements, losing the fixture setup
        String source = """
                class T {
                    void big() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                        int f = 6;
                        int g = 7;
                        int h = 8;
                        int i = 9;
                        int j = 10;
                    }
                }
                """;
        String problem = TreeGenerationTool.bodyShrinkProblem(source, "T#big", "replaceBody", "int a = 1;\nint z = 0;", null);
        assertTrue(problem != null && problem.contains("2 statements") && problem.contains("int b = 2;"), problem);
        // keeping most of it is fine, other ops are never judged, small bodies are never judged
        assertEquals(null, TreeGenerationTool.bodyShrinkProblem(source, "T#big", "replaceBody",
                "int a = 1; int b = 2; int c = 3; int d = 4; int e = 5; int f = 6; int g = 7; int z = 0;", null));
        assertEquals(null, TreeGenerationTool.bodyShrinkProblem(source, "T#big", "insertAtEnd", "int z = 0;", null));
        assertEquals(null, TreeGenerationTool.bodyShrinkProblem("class S { void s() { int a = 1; int b = 2; } }", "S#s",
                "replaceBody", "int z = 0;", null));
    }

    @Test
    void afterAFailedRoundTheWholeBodyItemIsRequired() {
        // statement-level items after a failed round produced use-before-declaration and
        // stray calls; the second round must be one replaceBody item
        assertEquals("", TreeGenerationTool.wholeBodyRequirement(false, "A#t"));
        String req = TreeGenerationTool.wholeBodyRequirement(true, "A#t");
        assertTrue(req.contains("REQUIRED THIS ROUND") && req.contains("\"replaceBody\"") && req.contains("A#t"), req);
        assertTrue(TreeGenerationTool.isSingleWholeBodyItem(List.of(Map.of("op", "replaceBody", "method", "A#t", "code", "x();"))));
        assertTrue(TreeGenerationTool.isSingleWholeBodyItem(List.of(Map.of("op", "rewriteBody", "method", "A#t", "code", "x();"))));
        assertFalse(TreeGenerationTool.isSingleWholeBodyItem(List.of(Map.of("op", "insertAtEnd", "method", "A#t", "code", "x();"))));
        assertFalse(TreeGenerationTool.isSingleWholeBodyItem(List.of(
                Map.of("op", "replaceBody", "method", "A#t", "code", "x();"),
                Map.of("op", "insertAtEnd", "method", "A#t", "code", "y();"))));
    }

    @Test
    void theDerivedValueOverridesAConflictingAuthorStub() throws Exception {
        // the author stubbed the derivation call to the template's UNSET; the declared expectation
        // must be what the code under test derives
        Files.writeString(aFile, """
                package t;

                class ThermostatTest {
                    void cloned() {
                        given(dial.lastSetting()).willReturn(UNSET);
                        subject.run(zone);
                    }
                }
                """);
        // the derivation text stands in for the code under test
        String code = "var safeLevel = dial.lastSetting(); "
                + "if (safeLevel.equals(UNSET)) { safeLevel = requestedLevel; }";
        Field f = TreeGenerationTool.class.getDeclaredField("codeUnderTestForDerivation");
        f.setAccessible(true);
        f.set(null, code);
        try {
            List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm(
                    "{\"declare\": [{\"name\": \"safeLevel\", \"expr\": \"COMFORT_LEVEL - 2\"}], "
                    + "\"stubs\": [{\"call\": \"dial.lastSetting()\", \"returns\": \"UNSET\"}]}",
                    aFile, "ThermostatTest#cloned", List.of(), "fall back only to safeLevel, never to requestedLevel");
            List<String> codes = items.stream().map(i -> i.get("code")).toList();
            assertTrue(codes.contains("given(dial.lastSetting()).willReturn(safeLevel);"), codes.toString());
            assertFalse(codes.contains("given(dial.lastSetting()).willReturn(UNSET);"), codes.toString());
            assertTrue(TreeGenerationTool.lastFormNote != null && TreeGenerationTool.lastFormNote.contains("replaced"));
            // an author stub that already returns the declared name yields ONE stub, not two on the same anchor
            List<Map<String, String>> same = TreeGenerationTool.itemsFromScenarioForm(
                    "{\"declare\": [{\"name\": \"safeLevel\", \"expr\": \"COMFORT_LEVEL - 2\"}], "
                    + "\"stubs\": [{\"call\": \"dial.lastSetting()\", \"returns\": \"safeLevel\"}]}",
                    aFile, "ThermostatTest#cloned", List.of(), "fall back only to safeLevel, never to requestedLevel");
            assertEquals(1, same.stream().filter(i -> "replaceStatement".equals(i.get("op"))).count(), same.toString());
        } finally {
            f.set(null, null);
        }
    }

    @Test
    void aMethodGeneratedThisSessionIsNotGeneratedAgain() throws IOException {
        // a second generation of a landed red test can only degrade it
        TreeGenerationTool tool = new TreeGenerationTool(prompt -> APPROVE);
        Map<String, Object> p = baseParams();
        p.put("method", "A#run");
        TreeGenerationTool.GENERATED_THIS_SESSION.add(aFile.toString() + "::A#run");
        try {
            Map<String, Object> r = tool.execute(p);
            assertEquals("Success", r.get("status"), r.toString());
            assertEquals(true, r.get("alreadyGenerated"));
            assertTrue(String.valueOf(r.get("message")).contains("COMMIT"), r.toString());
        } finally {
            TreeGenerationTool.GENERATED_THIS_SESSION.remove(aFile.toString() + "::A#run");
        }
    }

    @Test
    void aTestThatAssertsOnAnUnrelatedCallIsRefusedForAContrastTask() {
        // approved 3/3 and compiling, yet RED for the wrong reason - the only verify was the
        // template's display call, which fails before and after the fix
        String task = "when the sensor read throws, the heater must fall back only to safeLevel, "
                + "never to requestedLevel";
        List<String> writes = List.of("heater.setLevel(requestedLevel, zone)");
        String wrong = "given(x.y()).willReturn(1); subject.adjust(zone, reading -> {});\n"
                + "verify(display).showLevel(eq(safeLevel));";
        String issue = TreeGenerationTool.contrastedVerifyIssue(wrong, writes, task);
        assertTrue(issue != null && issue.contains("heater.setLevel"), issue);
        String right = wrong + "\nverify(heater).setLevel(eq(safeLevel), any());";
        assertEquals(null, TreeGenerationTool.contrastedVerifyIssue(right, writes, task));
        // eq(requestedLevel) on the right write is NOT the contrasted expectation
        String wrongValue = wrong + "\nverify(heater).setLevel(eq(requestedLevel), any());";
        assertTrue(TreeGenerationTool.contrastedVerifyIssue(wrongValue, writes, task) != null);
        // no contrast in the task, or no known writes → no gate
        assertEquals(null, TreeGenerationTool.contrastedVerifyIssue(wrong, writes, "the sum must equal the total"));
        assertEquals(null, TreeGenerationTool.contrastedVerifyIssue(wrong, List.of(), task));
    }

    @Test
    void aReasoningModelsCompletionIsReadFromContentThenReasoning() {
        // the local helper (gpt-oss-20b) puts its answer in `content` and its thinking in `reasoning`; older bridges
        // used `reasoning_content` - the visible answer wins, the reasoning is the fallback
        String content = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"approved\\\": true}\",\"reasoning\":\"thinking...\"}}]}";
        assertEquals("{\"approved\": true}", HelperModelTool.completionContent(content));
        String onlyReasoning = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"fallback\"}}]}";
        assertEquals("fallback", HelperModelTool.completionContent(onlyReasoning));
        assertEquals(null, HelperModelTool.completionContent("not json"));
    }

    @Test
    void theEmulatorBreaksASplitPanelOnlyWithAPositivePrediction() {
        // a split panel is settled by the emulator, and only by a positive prediction
        assertTrue(TreeGenerationTool.emulatorConfirms("PREDICTED RED: assertEquals at T.java:9 fails", "red"));
        assertTrue(TreeGenerationTool.emulatorConfirms("PREDICTED GREEN: all 3 evaluated assertion(s) hold", "green"));
        assertFalse(TreeGenerationTool.emulatorConfirms("UNDECIDED: 1 assertion(s) undecidable", "red"));
        assertFalse(TreeGenerationTool.emulatorConfirms("PREDICTED GREEN: all hold", "red"));
        assertFalse(TreeGenerationTool.emulatorConfirms("PREDICTED THROW: NullPointerException escapes", "red"));
        assertFalse(TreeGenerationTool.emulatorConfirms("PREDICTED RED: x", null));
    }

    @Test
    void derivationStubsRankingAndDuplicateDeclaresAreHandled() throws Exception {
        // the declared expectation must be what the code under test derives; a re-declared local
        // collided; the wrong dial was chosen from an unordered list
        String code = "var safeLevel = mode == MANUAL ? dial.lastSetting()"
                + " : panel.lastSetting(); if (safeLevel.equals(UNSET)) { safeLevel = requestedLevel; }";
        assertEquals(List.of("dial.lastSetting()", "panel.lastSetting()"),
                TreeGenerationTool.derivationCalls(code, "safeLevel"));
        // with several derivation calls only one branch runs - the stubs must be lenient or strict stubs fail
        Field f = TreeGenerationTool.class.getDeclaredField("codeUnderTestForDerivation");
        f.setAccessible(true);
        f.set(null, code);
        try {
            Files.writeString(aFile, """
                    package t;

                    class ThermostatTest {
                        void cloned() {
                            given(dial.lastSetting()).willReturn(UNSET);
                            subject.run(zone);
                        }
                    }
                    """);
            List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm(
                    "{\"declare\": [{\"name\": \"safeLevel\", \"expr\": \"COMFORT_LEVEL - 2\"}]}",
                    aFile, "ThermostatTest#cloned", List.of(), "fall back only to safeLevel, never to requestedLevel");
            List<String> codes = items.stream().map(i -> i.get("code")).toList();
            assertTrue(codes.contains("lenient().when(dial.lastSetting()).thenReturn(safeLevel);"), codes.toString());
            assertTrue(codes.contains("lenient().when(panel.lastSetting()).thenReturn(safeLevel);"), codes.toString());
        } finally {
            f.set(null, null);
        }
        assertTrue(TreeGenerationTool.derivationCalls(code, "nothing").isEmpty());
        List<String> ranked = TreeGenerationTool.rankByTestBody(
                List.of("panel.setLevel(requestedLevel)",
                        "dial.setLevel(requestedLevel, zone)"),
                "given(dial.lastSetting()).willReturn(UNSET); subject.run();");
        assertEquals("dial.setLevel(requestedLevel, zone)", ranked.get(0));
    }

    @Test
    void loggingCallsAndUnheldCollaboratorsAreNotVerifyCandidates() {
        // logger.error(..., safeLevel, requestedLevel, e) was offered as a write and chosen
        String task = "fall back only to safeLevel, never to requestedLevel";
        String code = "logger.error(\"{} between [{}, {}]\", ALERT, safeLevel, requestedLevel, e);"
                + " panel.setLevel(requestedLevel);"
                + " heater.setLevel(requestedLevel, zone);";
        List<String> all = TreeGenerationTool.contrastedWriteCalls(code, task);
        assertEquals(2, all.size(), all.toString());
        assertFalse(all.toString().contains("logger"), all.toString());
        // a test that holds only the heater gets only that candidate
        List<String> held = TreeGenerationTool.contrastedWriteCalls(code, task,
                "class T { @Mock HeaterImpl heater; void t() { } }");
        assertEquals(List.of("heater.setLevel(requestedLevel, zone)"), held);
    }

    @Test
    void aNumberedVerifyChoiceBecomesTheVerifyStatement() throws IOException {
        // a numbered choice replaces free-text verify; the contrasted argument becomes eq(X)
        String task = "when the sensor read throws, the heater must fall back only to safeLevel, "
                + "never to requestedLevel";
        String code = "applyLevel(zone, safeLevel, requestedLevel, info); "
                + "heater.setLevel(requestedLevel, zone);";
        List<String> calls = TreeGenerationTool.contrastedWriteCalls(code, task);
        // the unqualified helper call is the method's own, not a write on a collaborator - one candidate
        assertEquals(List.of("heater.setLevel(requestedLevel, zone)"), calls);
        assertEquals("verify(heater).setLevel(eq(safeLevel), any())",
                TreeGenerationTool.verifyFromChoice(calls, 1, task));
        assertEquals(null, TreeGenerationTool.verifyFromChoice(calls, 7, task));
        Files.writeString(aFile, """
                package t;

                class ThermostatTest {
                    void cloned() {
                        given(dial.lastSetting()).willReturn(UNSET);
                        subject.run(zone);
                        verify(display).showLevel(eq(COMFORT_LEVEL));
                    }
                }
                """);
        List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm(
                "{\"declare\": [{\"name\": \"safeLevel\", \"expr\": \"COMFORT_LEVEL - 2\"}], "
                + "\"stubs\": [{\"call\": \"dial.lastSetting()\", \"returns\": \"safeLevel\"}], \"verifyChoice\": 1}",
                aFile, "ThermostatTest#cloned", calls, task);
        assertEquals("verify(heater).setLevel(eq(safeLevel), any());",
                items.get(items.size() - 1).get("code"), items.toString());
        assertEquals("subject.run(zone);", items.get(items.size() - 1).get("anchor"));
        assertEquals("deleteStatement", TreeEditTool.canonicalOp("removeStatement", null));
    }

    @Test
    void theWritesOfTheContrastedValuesAreListedForTheVerify() {
        // the form verified the template's call instead of the write in the catch block
        String code = "try { return applyLevel(zone, safeLevel, requestedLevel, info); }"
                + " catch (Exception e) { log.error(\"x {}\", requestedLevel); "
                + "heater.setLevel(requestedLevel, zone); }";
        String hint = TreeGenerationTool.contrastedWrites(code,
                "when the sensor read throws, the heater must fall back only to safeLevel, never to requestedLevel");
        assertTrue(hint.contains("heater.setLevel(requestedLevel, zone)"), hint);
        assertTrue(hint.contains("eq(safeLevel)"), hint);
        assertFalse(hint.contains("log.error"), hint);
        assertEquals("", TreeGenerationTool.contrastedWrites(code, "the total must equal the sum"));
        // an invalid expression in the form is named rather than turned into a broken item
        assertTrue(TreeGenerationTool.badExpression("COMFORT_LEVEL.plus(2").contains("not a valid Java expression"));
        assertEquals(null, TreeGenerationTool.badExpression("COMFORT_LEVEL.plus(2)"));
    }

    @Test
    void stubsOnTheObjectProducedByTheThrowingCallBecomeLenient() throws IOException {
        // throwAt=sensor.readings() left the clone's iterator stubs unreachable - strict stubs failed
        Files.writeString(aFile, """
                package t;

                class ThermostatTest {
                    void cloned() {
                        final var iter = mock(Iter.class);
                        given(iter.hasNext()).willReturn(true);
                        given(sensor.readings()).willReturn(iter);
                        subject.run(zone);
                    }
                }
                """);
        List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm(
                "{\"throwAt\": \"sensor.readings()\", \"throwType\": \"RuntimeException\"}", aFile,
                "ThermostatTest#cloned");
        List<String> codes = items.stream().map(i -> i.get("code")).toList();
        assertTrue(codes.contains("lenient().when(iter.hasNext()).thenReturn(true);"), codes.toString());
        assertTrue(codes.stream().anyMatch(c -> c.startsWith("given(sensor.readings()).willThrow(")), codes.toString());
    }

    @Test
    void aScenarioFormBecomesDeterministicEditsOfTheClonedTest() throws IOException {
        // the author decides the scenario; the tool writes the Java
        Files.writeString(aFile, """
                package t;

                class ThermostatTest {
                    void cloned() {
                        given(dial.lastSetting()).willReturn(UNSET);
                        final var iter = mock(Iter.class);
                        given(iter.hasNext()).willReturn(true);
                        given(sensor.readings()).willReturn(iter);
                        subject.run(zone);
                        verify(display).showLevel(eq(COMFORT_LEVEL));
                    }
                }
                """);
        String form = """
                ```json
                {"declare": [{"name": "fallback", "expr": "COMFORT_LEVEL - 2"}],
                 "stubs": [{"call": "dial.lastSetting()", "returns": "fallback"}],
                 "throwAt": "iter.next()", "throwType": "RuntimeException",
                 "verify": ["dial.setLevel(eq(fallback), any())"],
                 "dropVerify": ["verify(display).showLevel"]}
                ```
                """;
        List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm(form, aFile,
                "ThermostatTest#cloned");
        List<String> ops = items.stream().map(i -> i.get("op")).toList();
        assertEquals(List.of("insertAtStart", "replaceStatement", "insertAfter", "deleteStatement", "insertAfter"), ops, items.toString());
        // a declare whose name the body already has is skipped
        List<Map<String, String>> dup = TreeGenerationTool.itemsFromScenarioForm(
                "{\"declare\": [{\"name\": \"iter\", \"expr\": \"mock(Iter.class)\"}], "
                + "\"verify\": [\"dial.setLevel(eq(1L), any())\"]}",
                aFile, "ThermostatTest#cloned");
        assertEquals(List.of("insertAfter"), dup.stream().map(i -> i.get("op")).toList(), dup.toString());
        assertEquals("final var fallback = COMFORT_LEVEL - 2;", items.get(0).get("code"));
        assertEquals("given(dial.lastSetting()).willReturn(UNSET);", items.get(1).get("anchor"));
        assertEquals("given(dial.lastSetting()).willReturn(fallback);", items.get(1).get("code"));
        assertEquals("final var iter = mock(Iter.class);", items.get(2).get("anchor"), "the throw stub follows the mock's declaration");
        assertEquals("given(iter.next()).willThrow(new RuntimeException(\"boom\"));", items.get(2).get("code"));
        assertEquals("verify(display).showLevel(eq(COMFORT_LEVEL));", items.get(3).get("anchor"));
        assertEquals("subject.run(zone);", items.get(4).get("anchor"), "verifies follow the exercised call");
        assertEquals("verify(dial).setLevel(eq(fallback), any());", items.get(4).get("code"));
        // garbage in → nothing out, so the caller falls back to the whole-body author
        assertTrue(TreeGenerationTool.itemsFromScenarioForm("not json at all", aFile, "ThermostatTest#cloned")
                .isEmpty());
    }

    @Test
    void aTaskContrastingTwoValuesDemandsThatTheStubsDistinguishThem() {
        // stubs made safeLevel == requestedLevel, so no assertion could go red
        String rule = TreeGenerationTool.contrastRule(
                "when the sensor read throws, the heater must fall back only to safeLevel, never to requestedLevel");
        assertTrue(rule.contains("safeLevel vs requestedLevel") && rule.contains("safeLevel != requestedLevel"), rule);
        assertTrue(TreeGenerationTool.contrastRule("the result must be UPDATED instead of STALE").contains("UPDATED vs STALE"));
        assertEquals("", TreeGenerationTool.contrastRule("the total must equal the sum of the parts"));
    }

    @Test
    void aTaskAboutAThrowDemandsAnExplicitThrowStub() {
        // a compiling test was rejected 0/3 because its throw was implicit (a null that
        // trips an NPE deep inside) - the author must be told to make the throw explicit
        String rule = TreeGenerationTool.explicitThrowRule(
                "when the sensor read throws, the heater must fall back only to safeLevel");
        assertTrue(rule.contains("willThrow") && rule.contains("EXPLICITLY"), rule);
        assertEquals("", TreeGenerationTool.explicitThrowRule("the total must equal the sum of the parts"));
    }

    @Test
    void wellKnownTestStaticsAreImportedWithoutLoadingTheirClasses() throws IOException {
        // hermit's JVM has no Mockito/JUnit/AssertJ, so the reflective lookup could never
        // import doThrow/when/assertThat for the target project. Their owners are known by name.
        Files.writeString(aFile, """
                package t;

                import static org.mockito.BDDMockito.given;

                class A {
                    void run() {
                        int base = 1;
                    }
                }
                """);
        List<String> added = TreeGenerationTool.addMissingStaticImports(aFile,
                "A.java:7: error: cannot find symbol\n  symbol: method doThrow(RuntimeException)\n"
                + "A.java:8: error: cannot find symbol\n  symbol: method any()\n"
                + "A.java:9: error: cannot find symbol\n  symbol: method assertThatThrownBy(ThrowingCallable)\n"
                + "A.java:9: error: cannot find symbol\n  symbol: method frobnicate(int)\n");
        assertEquals(List.of("org.mockito.Mockito.doThrow", "org.mockito.ArgumentMatchers.any",
                "org.assertj.core.api.Assertions.assertThatThrownBy"), added);
        String out = Files.readString(aFile);
        assertTrue(out.contains("import static org.mockito.Mockito.doThrow;"), out);
        // the repair author is offered the whole-body op for the committed method too
        assertTrue(TreeGenerationTool.REPAIR_RULES.contains("\"replaceBody\""));
    }

    @Test
    void anInheritedStaticMethodIsImportedFromItsDeclaringClass() throws IOException {
        // `when`, `doThrow` and `doAnswer` were never imported because BDDMockito
        // inherits them from Mockito - two owners, so the import step gave up. The DECLARING class decides.
        Files.writeString(aFile, """
                package t;

                import static org.mockito.BDDMockito.given;

                class A {
                    void run() {
                        int base = 1;
                    }
                }
                """);
        // Mockito is not on this test's classpath, so the same shape is modelled with two nested classes: Derived
        // inherits Base's static methods exactly as BDDMockito inherits Mockito's
        List<String> added = TreeGenerationTool.addMissingStaticImports(aFile,
                "A.java:7: error: cannot find symbol\n  symbol: method frobWhen(Object)\n"
                + "A.java:8: error: cannot find symbol\n  symbol: method frobThrow(Throwable)\n",
                List.of(Derived.class.getName(), Base.class.getName()));
        String base = Base.class.getCanonicalName();
        assertEquals(List.of(base + ".frobWhen", base + ".frobThrow"), added);
        assertTrue(Files.readString(aFile).contains("import static " + base + ".frobWhen;"));
    }

    /** Stand-ins for Mockito / BDDMockito: the subclass inherits the statics, the base class declares them
     *  (names deliberately absent from the well-known table so the declaring-class rule is what is tested). */
    public static class Base {
        public static Object frobWhen(Object o) { return o; }
        public static Object frobThrow(Throwable t) { return t; }
    }

    public static class Derived extends Base { }

    @Test
    void theRepairPromptQuotesARealCallSiteWhenTheArgumentsAreWrong() throws IOException {
        // javac said `setLevel … required: long,String
        // found: long` four rounds running; the template never calls the setter, but PRODUCTION does - one real
        // call site shows the author the arity and what to pass
        Files.writeString(dir.resolve("Heater.java"), """
                package t;

                class Heater {
                    void setLevel(long level, String zone) { }
                }
                """);
        Files.writeString(dir.resolve("Thermostat.java"), """
                package t;

                class Thermostat {
                    Heater heater = new Heater();

                    void run(String zone) {
                        heater.setLevel(42L, zone);
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir),
                ClasspathIndex.system());
        String javac = "A.java:9: error: method setLevel in class Heater cannot be applied to given types;\n"
                + "        verify(heater).setLevel(eq(7L));\n  required: long,String\n  found: long\n";
        String hint = TreeGenerationTool.callSiteExamples(g, javac);
        assertTrue(hint.contains("heater.setLevel(42L, zone);"), hint);
        assertTrue(hint.contains("required: long,String"), hint);
        assertEquals("", TreeGenerationTool.callSiteExamples(g, "A.java:3: error: ';' expected"));
    }

    @Test
    void aMissingStaticImportIsAddedFromTheJavacError() throws IOException {
        // four process rounds were spent on "symbol: method doThrow" - a missing static import that
        // the author cannot see in the file
        Files.writeString(aFile, """
                package t;

                import java.util.List;

                class A {
                    void run() {
                        int base = 1;
                    }
                }
                """);
        List<String> added = TreeGenerationTool.addMissingStaticImports(aFile,
                "A.java:7: error: cannot find symbol\n  symbol: method requireNonNull(Object)\n");
        assertEquals(List.of("java.util.Objects.requireNonNull"), added);
        assertTrue(Files.readString(aFile).contains("import static java.util.Objects.requireNonNull;"),
                Files.readString(aFile));
        // an unknown name adds nothing, so the caller falls through to a normal repair
        assertTrue(TreeGenerationTool.addMissingStaticImports(aFile,
                "A.java:7: error: cannot find symbol\n  symbol: method frobnicate(int)\n").isEmpty());
        // and an error with no method symbol is ignored
        assertTrue(TreeGenerationTool.addMissingStaticImports(aFile, "A.java:3: error: ';' expected").isEmpty());
    }

    @Test
    void aCommentOnlyItemGetsTheTwoLineShapeInItsRetryPrompt() throws IOException {
        // the author kept re-sending the same note; the retry now names the shape that works
        List<String> prompts = new ArrayList<>();
        TreeGenerationTool tool = new TreeGenerationTool(prompt -> {
            prompts.add(prompt);
            return prompt.contains("YOUR CODE WAS ONLY A NOTE")
                    ? "{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"// note\\nTarget.target();\"}"
                    : APPROVE;
        });
        Map<String, Object> p = baseParams();
        p.put("items", List.of(Map.of("op", "insertAtEnd", "method", "A#run", "code", "// just a note")));
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(prompts.stream().anyMatch(x -> x.contains("YOUR CODE WAS ONLY A NOTE")
                && x.contains("then the Java STATEMENT it describes")), "the retry names the two-line shape");
        assertTrue(Files.readString(aFile).contains("Target.target();"));
    }

    @Test
    void afterCloningTheAuthorMayRewriteTheWholeBody() throws IOException {
        // after the template-first clone the author is offered ONE replaceBody item for the new test -
        // the shape a small model handles - instead of anchored statement surgery it kept failing at
        Files.writeString(aFile, """
                package t;

                class A {
                    void existingTest() {
                        int base = 1;
                        assertEquals(1, base);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        List<String> prompts = new ArrayList<>();
        TreeGenerationTool tool = new TreeGenerationTool(prompt -> {
            prompts.add(prompt);
            if (prompt.contains("ALREADY EXISTS as an exact clone")) {
                return "[{\"op\": \"replaceBody\", \"method\": \"A#newTest\", "
                        + "\"code\": \"int base = 2;\\nTarget.target();\\nassertEquals(2, base);\"}]";
            }
            return APPROVE;
        });
        Map<String, Object> p = baseParams();
        p.put("task", "the base must be two and the target must be reached");
        p.put("method", "A#newTest");
        p.put("templateMethod", "A#existingTest");
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        String out = Files.readString(aFile);
        assertTrue(out.contains("void newTest()") && out.contains("int base = 2;") && out.contains("Target.target();"),
                out);
        assertTrue(out.contains("void existingTest()") && out.contains("int base = 1;"), "the template is untouched");
        assertTrue(prompts.stream().anyMatch(x -> x.contains("replaceBody")), "the author was offered replaceBody");
    }

    @Test
    void placeholderTasksAreRefused() {
        // the coaching example came back verbatim as the task
        assertTrue(TreeGenerationTool.looksLikePlaceholderTask("a JUnit test that stubs X to throw and asserts Y"));
        assertTrue(TreeGenerationTool.looksLikePlaceholderTask("stubs <collaborator> to throw and asserts <observable>"));
        assertFalse(TreeGenerationTool.looksLikePlaceholderTask(
                "a JUnit test that stubs Thermostat.readSensor to throw and asserts "
                        + "the heater level falls back only to safeLevel"));
        assertFalse(TreeGenerationTool.looksLikePlaceholderTask(
                "in the catch block replace requestedLevel with safeLevel"));
        assertFalse(TreeGenerationTool.looksLikePlaceholderTask("assert A.run() returns 1"));   // one letter is a real name
    }

    @Test
    void docEchoTasksAreRefused() {
        assertTrue(TreeGenerationTool.looksLikeDocEcho("incremental codegen with an emulator gate"));
        assertTrue(TreeGenerationTool.looksLikeDocEcho("<what to generate>"));
        assertTrue(TreeGenerationTool.looksLikeDocEcho("fix"));
        assertTrue(TreeGenerationTool.looksLikeDocEcho("write unit test"));   // drive 2B, turn 3
        assertTrue(TreeGenerationTool.looksLikeDocEcho("write a failing unit test"));   // drives 2B-2G: no behaviour
        assertTrue(TreeGenerationTool.looksLikeDocEcho("generate a failing test"));
        assertFalse(TreeGenerationTool.looksLikeDocEcho(
                "a JUnit test that stubs sensor.readings to throw and asserts the fallback level"));
        assertFalse(TreeGenerationTool.looksLikeDocEcho(
                "in the catch block replace heater.setLevel(requestedLevel) with safeLevel"));
    }

    @Test
    void templateCloneRenamesOnlyTheDeclaration() throws IOException {
        // template-first codegen - the clone is the template's exact text under the new name
        Path f = Files.createTempFile("T", ".java");
        Files.writeString(f, """
                class ThermostatTest {
                    @Test
                    void heaterUsesTheComfortLevel() {
                        given(x.heaterUsesTheComfortLevel()).willReturn(1);
                        assertEquals(1, subject.run());
                    }
                }
                """);
        Map<String, String> item = TreeGenerationTool.cloneTemplateItem(f,
                "ThermostatTest#heaterUsesTheComfortLevel",
                "ThermostatTest#failureFallsBackToTheSafeLevel");
        assertEquals("insertMember", item.get("op"));
        assertEquals("ThermostatTest", item.get("type"));
        String code = item.get("code");
        assertTrue(code.startsWith("@Test"), code);
        assertTrue(code.contains("void failureFallsBackToTheSafeLevel()"), code);
        // a same-named CALL inside the body is not the declaration - it stays
        assertTrue(code.contains("x.heaterUsesTheComfortLevel()"), code);
        assertTrue(TreeGenerationTool.methodExists(f, "ThermostatTest#heaterUsesTheComfortLevel"));
        assertFalse(TreeGenerationTool.methodExists(f, "ThermostatTest#failureFallsBackToTheSafeLevel"));
    }

    @Test
    void autoTemplatePicksTheClosestExistingTest() throws IOException {
        // F87 fallback: no templateMethod given - the @Test whose name/body overlaps the task words wins
        Path f = Files.createTempFile("T", ".java");
        Files.writeString(f, """
                class ThermostatTest {
                    @Test void displayShowsUnitsWithoutAZone() { header(); }
                    @Test void heaterUsesTheComfortLevel() {
                        given(sensorService.latestReadings(any(), any(), any())).willReturn(iterator);
                        subject.adjust(zone, mode, callback);
                    }
                    void helper() { }
                }
                """);
        String pick = TreeGenerationTool.autoTemplate(f, "ThermostatTest#failureFallsBackToTheSafeLevel",
                "a failing test: when the sensor read throws, the heater level falls back only to safeLevel");
        assertEquals("ThermostatTest#heaterUsesTheComfortLevel", pick);
        assertEquals(null, TreeGenerationTool.autoTemplate(f, "ThermostatTest#x", "something entirely unrelated zzz"));
        assertTrue(TreeGenerationTool.looksLikeTestTask("write unit test", Path.of("Foo.java")));
        assertTrue(TreeGenerationTool.looksLikeTestTask("anything", Path.of("/p/src/test/java/Foo.java")));
    }

    @Test
    void specJobsAreRedirectedToTheComposer() {
        // the planner sent "compose a @SpecTest" to TreeCodegen
        assertTrue(TreeGenerationTool.looksLikeSpecTestTask("compose a @SpecTest that renames a shelf", "X.java"));
        assertTrue(TreeGenerationTool.looksLikeSpecTestTask("anything",
                "library/spec-tests/src/main/java/com/acme/library/suites/ShelfLabelSpec.java"));
        assertFalse(TreeGenerationTool.looksLikeSpecTestTask(
                "a JUnit test that stubs the catalog and asserts the title",
                "library/library-catalog-impl/src/test/java/x/CatalogSearchTest.java"));
    }

    @Test
    void camelWordsFeedTheAutoTemplateWhenTheTaskIsTerse() throws IOException {
        // F94 companion: "write a failing unit test" has no content words; the reach target does
        assertEquals("apply Level", TreeGenerationTool.camelWords("Thermostat#applyLevel"));
        assertEquals("ThermostatTest", TreeGenerationTool.fileParamType(Path.of("/x/ThermostatTest.java")));
        Path f = Files.createTempFile("T", ".java");
        Files.writeString(f, """
                class ThermostatTest {
                    @Test void displayShowsUnitsWithoutAZone() { header(); }
                    @Test void heaterLevelFollowsTheSensor() {
                        given(sensorService.latestReadings(any(), any(), any())).willReturn(iterator);
                        subject.adjust(zone, mode, callback);
                    }
                }
                """);
        String pick = TreeGenerationTool.autoTemplate(f, "ThermostatTest#testAdjustHeaterLevel",
                "write a failing unit test " + TreeGenerationTool.camelWords("ThermostatTest#testAdjustHeaterLevel")
                        + " " + TreeGenerationTool.camelWords("Thermostat#applyLevel"));
        assertEquals("ThermostatTest#heaterLevelFollowsTheSensor", pick);
        // an unrelated test with a long body mentioning "adjust"/"zone" must not outscore
        // a test whose NAME says "heater" - name hits weigh 3, body hits 1
        Files.writeString(f, """
                class ThermostatTest {
                    @Test void displayShowsUnitsWithoutAZone() {
                        subject.adjust(zone, mode, callback); adjustZone(); zoneAdjusting(); errorHandler();
                        subject.adjust(zone, mode, callback); adjustZone(); zoneAdjusting(); errorHandler();
                    }
                    @Test void heaterLevelFollowsTheSensor() {
                        given(sensorService.latestReadings(any(), any(), any())).willReturn(iterator);
                        subject.adjust(zone, mode, callback);
                    }
                }
                """);
        String pick2 = TreeGenerationTool.autoTemplate(f, "ThermostatTest#testAdjustZoneHeaterErrorHandling",
                "Write a failing unit test "
                        + TreeGenerationTool.camelWords("ThermostatTest#testAdjustZoneHeaterErrorHandling"));
        assertEquals("ThermostatTest#heaterLevelFollowsTheSensor", pick2);
    }

    @Test
    void testJobsAimedAtProductionFilesAreRefused() {
        // file=Thermostat.java + "write a failing unit test" + expectTestOutcome=red
        String issue = TreeGenerationTool.misplacedTestIssue("write a failing unit test",
                "home/climate-app/src/main/java/com/acme/climate/Thermostat.java",
                "com.acme.climate.ThermostatTest#testX", "red");
        assertTrue(issue != null && issue.contains("PRODUCTION source"), issue);
        assertTrue(issue.contains("ThermostatTest"), issue);
        // a production FIX in a production file is fine
        assertEquals(null, TreeGenerationTool.misplacedTestIssue(
                "in the catch block replace requestedLevel with safeLevel",
                "home/climate-app/src/main/java/com/acme/climate/Thermostat.java", null, "green"));
        // a test in a test file is fine
        assertEquals(null, TreeGenerationTool.misplacedTestIssue("write a failing unit test",
                "home/climate-app/src/test/java/com/acme/climate/ThermostatTest.java", null, "red"));
    }

    @Test
    void aWhollyBracketedSpecOrPlaceholderTypeResolvesToTheTarget() {
        // "<Type#testTotalThrows>" wrapped the WHOLE spec, and the coaching echoed
        // "type=<Type" back at the author
        assertEquals("InvoiceTest#testX",
                TreeGenerationTool.resolveItemSpec("<Type#testX>", "InvoiceTest#testX"));
        assertEquals("InvoiceTest#testX",
                TreeGenerationTool.resolveItemSpec("TestClass#testX", "InvoiceTest#testX"));
        assertEquals("InvoiceTest", TreeGenerationTool.resolveItemType("<Type", "InvoiceTest#testX"));
        assertEquals("InvoiceTest", TreeGenerationTool.resolveItemType("TheClass", "InvoiceTest#testX"));
        // a real type is untouched
        assertEquals("OtherTest#testX",
                TreeGenerationTool.resolveItemSpec("OtherTest#testX", "InvoiceTest#testX"));
        assertEquals("OtherTest", TreeGenerationTool.resolveItemType("OtherTest", "InvoiceTest#testX"));
    }

    @Test
    void placeholderTypesInItemsResolveToTheTargetType() {
        // the repair author copied "<ClassName>#testX" / type=<the class> from the coaching
        assertEquals("InvoiceTest#testX", TreeGenerationTool.resolveItemSpec("<ClassName>#testX", "InvoiceTest#testX"));
        assertEquals("InvoiceTest#other", TreeGenerationTool.resolveItemSpec("other", "InvoiceTest#testX"));
        assertEquals("A#m", TreeGenerationTool.resolveItemSpec("A#m", "InvoiceTest#testX"));
        assertEquals("InvoiceTest", TreeGenerationTool.resolveItemType("<the class>", "InvoiceTest#testX"));
        assertEquals("Other", TreeGenerationTool.resolveItemType("Other", "InvoiceTest#testX"));
    }

    @Test
    void anUnrelatedNamedTemplateYieldsToTheBestMatch() throws IOException {
        // templateMethod=displayShowsUnitsWithoutAZone (real, unrelated) for a sensor-failure test
        Path f = Files.createTempFile("T", ".java");
        Files.writeString(f, """
                class ThermostatTest {
                    @Test void displayShowsUnitsWithoutAZone() { subject.adjust(zone, mode, callback); }
                    @Test void heaterUsesTheComfortLevel() {
                        given(sensorService.latestReadings(any(), any(), any())).willReturn(iterator);
                        subject.adjust(zone, mode, callback);
                    }
                }
                """);
        String task = "a JUnit test that stubs readSensor to throw and asserts the heater level falls back only to "
                + "safeLevel";
        assertEquals(0, TreeGenerationTool.templateScore(f, "ThermostatTest#displayShowsUnitsWithoutAZone", task));
        assertTrue(TreeGenerationTool.templateScore(f, "ThermostatTest#heaterUsesTheComfortLevel", task) >= 3);
    }

    @Test
    void cutOffSnippetsAreMechanicallyCompleted() {
        // item 3 arrived cut off at the same column four times
        assertEquals("given(sensor.readings(any(), any(), any())).willThrow(new RuntimeException(\"boom\"));",
                TreeGenerationTool.autoCompleteSnippet(
                        "given(sensor.readings(any(), any(), any())).willThrow(new RuntimeException(\"boom"));
        assertEquals("verify(heater).setLevel(captor.capture(), any());",
                TreeGenerationTool.autoCompleteSnippet("verify(heater).setLevel(captor.capture(), any("));
        assertEquals(null, TreeGenerationTool.autoCompleteSnippet("int x = 1;"), "complete code is left alone");
    }

    @Test
    void qualifySpecAcceptsDollarSeparatedMethods() {
        // `com.x.ReportService$summaryFor` became `ReportTest#com.x…$summaryFor`
        assertEquals("ReportService#summaryFor", TreeGenerationTool.qualifySpec(
                "com.acme.reports.service.ReportService$summaryFor", "/w/ReportTest.java"));
        assertEquals("ReportService#summaryFor", TreeGenerationTool.qualifySpec("ReportService$summaryFor", null));
        // a nested type keeps its `$`
        assertEquals("Outer$Inner#run", TreeGenerationTool.qualifySpec("Outer$Inner#run", null));
    }

    private void writeSpyFixture() throws IOException {
        Files.writeString(dir.resolve("Base.java"), """
                package t;

                class Base {
                    @Mock(strictness = LENIENT)
                    protected Session session;
                    @Mock
                    protected Shelves shelves;
                    protected CatalogImpl catalog;

                    void refresh() { catalog = new CatalogImpl(shelves); }
                }
                """);
        Files.writeString(aFile, """
                package t;

                class SearchTest extends Base {
                    @Mock
                    private Clock clock;
                    private Search subject;

                    void setUp() { subject = new Search(clock); }

                    void cloned() {
                        refresh();
                        given(session.open(Catalog.class)).willReturn(catalog);
                        final var result = subject.find(session);
                        assertEquals(FOUND, result.status());
                    }
                }
                """);
    }

    @Test
    void theFixtureListsMocksAndRealObjectsAcrossTheSuperclassChain() throws IOException {
        // the author stubbed a REAL catalog built with new in the fixture's base class
        writeSpyFixture();
        TreeGenerationTool.Fixture f = TreeGenerationTool.fixtureOf(aFile);
        assertEquals(Set.of("clock", "session", "shelves"), f.mocks());
        assertEquals("CatalogImpl", f.reals().get("catalog"));
        assertEquals("Search", f.reals().get("subject"));
    }

    @Test
    void aRealObjectCanBeSpiedByTheFormAndStubsOnItUseDoReturn() throws IOException {
        writeSpyFixture();
        List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm(
                "{\"spy\": [\"catalog\"], \"stubs\": [{\"call\": \"catalog.loadAll(any())\", \"returns\": \"books\"}], "
                + "\"verify\": [\"catalog.loadAll(any())\"], \"repeatTrigger\": 2}",
                aFile, "SearchTest#cloned", List.of(), "repeated searches must not re-load the catalog");
        List<String> codes = items.stream().map(i -> i.get("code")).toList();
        // the spy is declared under the SAME name right before the statement that hands the catalog out
        int spyAt = codes.indexOf("final var catalog = spy(this.catalog);");
        assertTrue(spyAt >= 0, codes.toString());
        assertEquals("given(session.open(Catalog.class)).willReturn(catalog);", items.get(spyAt).get("anchor"));
        assertEquals("insertBefore", items.get(spyAt).get("op"));
        // a stub on a spy never calls the real method while being stubbed
        int stubAt = codes.indexOf("doReturn(books).when(catalog).loadAll(any());");
        assertTrue(stubAt > spyAt, codes.toString());
        // the bare exercised call is repeated once, right before the asserted call and after the stubs
        int repeatAt = codes.indexOf("subject.find(session);");
        assertTrue(repeatAt > stubAt, codes.toString());
        assertEquals("final var result = subject.find(session);", items.get(repeatAt).get("anchor"));
        assertEquals("insertBefore", items.get(repeatAt).get("op"));
        // the verify lands after the asserted call
        int verifyAt = codes.indexOf("verify(catalog).loadAll(any());");
        assertTrue(verifyAt > repeatAt, codes.toString());
        assertEquals("insertAfter", items.get(verifyAt).get("op"));
    }

    @Test
    void aStubOnARealObjectSpiesItAutomaticallyAndKeepsTheScenario() throws IOException {
        // F241 refused a stub on a REAL object naming the mocks; F250 (drive 2C/317): told so, the author's second
        // reply kept only the spy and dropped every stub and verify - the tool now spies the object itself
        writeSpyFixture();
        List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm(
                "{\"stubs\": [{\"call\": \"catalog.loadAll(any())\", \"returns\": \"books\"}], "
                + "\"verify\": [\"catalog.loadAll(any())\"]}",
                aFile, "SearchTest#cloned", List.of(), "repeated searches must not re-load the catalog");
        List<String> codes = items.stream().map(i -> i.get("code")).toList();
        int spyAt = codes.indexOf("final var catalog = spy(this.catalog);");
        assertTrue(spyAt >= 0, codes.toString());
        assertEquals("given(session.open(Catalog.class)).willReturn(catalog);", items.get(spyAt).get("anchor"));
        assertTrue(spyAt < codes.indexOf("doReturn(books).when(catalog).loadAll(any());"), codes.toString());
        assertTrue(codes.contains("verify(catalog).loadAll(any());"), codes.toString());
        assertEquals(1, codes.stream().filter(c -> c.contains("spy(this.catalog)")).count(), "spied once");
        assertNotNull(TreeGenerationTool.lastFormNote);
        assertTrue(TreeGenerationTool.lastFormNote.contains("catalog is a REAL CatalogImpl"),
                TreeGenerationTool.lastFormNote);
        assertTrue(TreeGenerationTool.lastFormNote.contains("spied automatically"), TreeGenerationTool.lastFormNote);
        // a throw on a real object is spied too and rendered as doThrow(...).when(spy)
        List<Map<String, String>> thrown = TreeGenerationTool.itemsFromScenarioForm(
                "{\"throwAt\": \"catalog.loadAll(any())\", \"throwType\": \"IllegalStateException\"}",
                aFile, "SearchTest#cloned", List.of(), "t");
        List<String> thrownCodes = thrown.stream().map(i -> i.get("code")).toList();
        assertTrue(thrownCodes.contains("final var catalog = spy(this.catalog);"), thrownCodes.toString());
        assertTrue(thrownCodes.contains("doThrow(new IllegalStateException(\"boom\")).when(catalog).loadAll(any());"),
                thrownCodes.toString());
        // a mock is left alone
        List<Map<String, String>> mockOnly = TreeGenerationTool.itemsFromScenarioForm(
                "{\"verify\": [\"session.open(any())\"]}", aFile, "SearchTest#cloned", List.of(), "t");
        assertEquals(List.of("verify(session).open(any());"), mockOnly.stream().map(i -> i.get("code")).toList());
    }

    @Test
    void repeatTriggerRepeatsTheExercisedCallBeforeTheAssertedOne() throws IOException {
        writeSpyFixture();
        List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm("{\"repeatTrigger\": 3}",
                aFile, "SearchTest#cloned", List.of(), "t");
        assertEquals(1, items.size(), items.toString());
        assertEquals("subject.find(session);\nsubject.find(session);", items.get(0).get("code"));
        assertEquals("subject.find(session);",
                TreeGenerationTool.bareCall("final var result = subject.find(session);"));
        assertEquals("subject.run();", TreeGenerationTool.bareCall("subject.run()"));
        assertEquals("subject.run();", TreeGenerationTool.bareCall("Response r = subject.run();"));
    }

    @Test
    void rejectionsNameTheMethodsTheTypeHas() throws IOException {
        // five invented method names in two rounds - the rejection never said what existed
        Files.writeString(dir.resolve("CatalogImpl.java"), """
                package t;

                class CatalogImpl {
                    byte[] loadAll(long id) { return null; }
                    Object lookup(long id) { return null; }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir),
                ClasspathIndex.system());
        List<String> coached = TreeGenerationTool.coachTypeProblems(
                List.of("[261:46] CatalogImpl has no method 'getBookById'", "[3:1] something else"), g);
        assertTrue(coached.get(0).contains("loadAll(1)"), coached.get(0));
        assertTrue(coached.get(0).contains("lookup(1)"), coached.get(0));
        assertFalse(coached.get(0).contains("hashCode"), coached.get(0));
        assertEquals("[3:1] something else", coached.get(1));
    }

    @Test
    void addMissingThrowsDeclaresTheReportedException() throws IOException {
        // verify(catalog).loadAll(any()) - "unreported exception IOException" - the repair author
        // deleted the verify; the fix is a throws clause on the generated method
        Files.writeString(aFile, """
                package t;

                class SearchTest {
                    @Test
                    void repeated() {
                        verify(catalog).loadAll(any());
                    }

                    void other() throws IOException {
                        verify(catalog).loadAll(any());
                    }
                }
                """);
        String tail = aFile + ":6: error: unreported exception java.io.IOException; must be caught or declared to be thrown\n"
                + "        verify(catalog).loadAll(any());\n";
        assertEquals(List.of("IOException"), TreeGenerationTool.addMissingThrows(aFile, "SearchTest#repeated", tail));
        String after = Files.readString(aFile);
        assertTrue(after.contains("void repeated() throws IOException {"), after);
        assertTrue(after.contains("void other() throws IOException {"), "the other method is untouched");
        // an existing clause is extended, a declared exception is not repeated
        String tail2 = aFile + ":6: error: unreported exception TimeoutException; must be caught or declared to be thrown\n";
        assertEquals(List.of("TimeoutException"),
                TreeGenerationTool.addMissingThrows(aFile, "SearchTest#repeated", tail2));
        assertTrue(Files.readString(aFile).contains("void repeated() throws IOException, TimeoutException {"), Files.readString(aFile));
        assertTrue(TreeGenerationTool.addMissingThrows(aFile, "SearchTest#repeated", tail).isEmpty(),
                "already declared");
        assertTrue(TreeGenerationTool.addMissingThrows(aFile, "SearchTest#repeated", "error: cannot find symbol")
                .isEmpty());
    }

    @Test
    void collaboratorCallsAreNumberedAndMappedToTheTestsObjects() {
        // the author verified lookup - nothing listed the calls the code under test makes
        String cut = """
                private Optional<Summary> summaryFor(@NonNull final ReportId reportId, @NonNull final IndexReader index,
                        @NonNull final PageArchive pageArchive) throws IOException {
                    final var first = pageArchive.lookup(reportId);
                    final var pages = pageArchive.loadAll(reportId).toByteArray();
                    final var checksum = hex(digests.sha256(pages));
                    final var meta = index.metadataOf(reportId);
                    final var summary = Summary.newBuilder();
                    summary.title(meta.title());
                    return Optional.of(summary.build());
                }
                """;
        TreeGenerationTool.Fixture fixture = new TreeGenerationTool.Fixture(Set.of("session"),
                Map.of("archive", "PageArchiveImpl", "subject", "ReportService"),
                Map.of("session", "Session"));
        List<String> stmts = List.of("when(session.open(PageArchive.class)).thenReturn(archive);",
                "final var summary = subject.respond(session, header);");
        List<TreeGenerationTool.CallChoice> calls = TreeGenerationTool.collaboratorCalls(cut, fixture, stmts);
        List<String> texts = calls.stream().map(TreeGenerationTool.CallChoice::call).toList();
        assertEquals(List.of("pageArchive.lookup(reportId)", "pageArchive.loadAll(reportId)",
                "digests.sha256(pages)", "index.metadataOf(reportId)"), texts);
        assertEquals("archive", calls.get(1).testObject());
        assertTrue(calls.get(1).real());
        assertEquals(null, calls.get(2).testObject(), "digests is not held by the test");
        assertEquals("verify(archive, times(1)).loadAll(any())", TreeGenerationTool.verifyFromCallChoice(calls.get(1)));
        assertTrue(calls.get(1).display().contains("REAL"), calls.get(1).display());
        assertTrue(calls.get(2).display().contains("not held"), calls.get(2).display());
    }

    @Test
    void verifyCallChoiceSpiesTheRealObjectAndPinsTheCount() throws Exception {
        writeSpyFixture();
        Field f = TreeGenerationTool.class.getDeclaredField("codeUnderTestForDerivation");
        f.setAccessible(true);
        f.set(null, "Object find(final Catalog catalog, final Session ctx) { final var books = catalog.loadAll(ctx); "
                + "return books; }");
        try {
            List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm(
                    "{\"verifyCallChoice\": 1, \"repeatTrigger\": 2}",
                    aFile, "SearchTest#cloned", List.of(), "repeated searches must not re-load the catalog");
            List<String> codes = items.stream().map(i -> i.get("code")).toList();
            int spyAt = codes.indexOf("final var catalog = spy(this.catalog);");
            assertTrue(spyAt >= 0, codes.toString());
            assertEquals("given(session.open(Catalog.class)).willReturn(catalog);", items.get(spyAt).get("anchor"));
            int repeatAt = codes.indexOf("subject.find(session);");
            assertTrue(repeatAt >= 0, codes.toString());
            int verifyAt = codes.indexOf("verify(catalog, times(1)).loadAll(any());");
            assertTrue(verifyAt > repeatAt, codes.toString());
            assertEquals("insertAfter", items.get(verifyAt).get("op"));
        } finally {
            f.set(null, null);
        }
    }

    @Test
    void placeholderParametersAreIgnoredNotHunted() {
        // `targetReach: "<Type#method>"` copied from the tool description, two plans running
        assertTrue(TreeGenerationTool.isPlaceholderValue("<Type#method>"));
        assertTrue(TreeGenerationTool.isPlaceholderValue(" <TestClass#test> "));
        assertFalse(TreeGenerationTool.isPlaceholderValue("ReportService#summaryFor"));
        assertFalse(TreeGenerationTool.isPlaceholderValue("Map<String, Object> x"));
        List<String> notes = new ArrayList<>();
        assertEquals(null, TreeGenerationTool.dropPlaceholder("<Type#method>", "targetReach", notes));
        assertEquals("ReportService#summaryFor",
                TreeGenerationTool.dropPlaceholder("ReportService#summaryFor", "targetReach", notes));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("targetReach '<Type#method>' is a placeholder"), notes.get(0));
    }

    @Test
    void aPinnedCallCountImpliesARepeatedTriggerWhenTheTaskSpeaksOfRepetition() throws Exception {
        // verifyCallChoice without repeatTrigger cannot express "not again"
        writeSpyFixture();
        Field f = TreeGenerationTool.class.getDeclaredField("codeUnderTestForDerivation");
        f.setAccessible(true);
        f.set(null, "Object find(final Catalog catalog, final Session ctx) { final var books = catalog.loadAll(ctx); "
                + "return books; }");
        try {
            List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm("{\"verifyCallChoice\": 1}",
                    aFile, "SearchTest#cloned", List.of(), "repeated searches must not re-load the catalog every time");
            List<String> codes = items.stream().map(i -> i.get("code")).toList();
            assertTrue(codes.contains("subject.find(session);"), codes.toString());
            assertTrue(codes.contains("verify(catalog, times(1)).loadAll(any());"), codes.toString());
            assertTrue(TreeGenerationTool.lastFormNote.contains("repeatTrigger defaulted to 2"), TreeGenerationTool.lastFormNote);
            // a task that is not about repetition keeps a single trigger
            List<Map<String, String>> single = TreeGenerationTool.itemsFromScenarioForm("{\"verifyCallChoice\": 1}",
                    aFile, "SearchTest#cloned", List.of(), "the catalog must be consulted for the title");
            assertFalse(single.stream().map(i -> i.get("code")).toList().contains("subject.find(session);"),
                    single.toString());
        } finally {
            f.set(null, null);
        }
    }

    @Test
    void criticDissentAfterAFormAuthoredRoundGoesBackToTheForm() {
        // the local repair author "fixed" scenario complaints at code level through six repairs
        assertTrue(TreeGenerationTool.criticDissentGoesToTheForm(true));
        assertFalse(TreeGenerationTool.criticDissentGoesToTheForm(false));
        System.setProperty("hermit.treegen.formFeedback", "false");
        try {
            assertFalse(TreeGenerationTool.criticDissentGoesToTheForm(true));
        } finally {
            System.clearProperty("hermit.treegen.formFeedback");
        }
    }

    @Test
    void theTranscriptSummarisesTheFormFieldsAReplyUsed() {
        // a lost scenario must be visible without the reply itself
        String summary = TreeGenerationTool.formFieldSummary("{\"declare\": [{\"name\": \"a\", \"expr\": \"1\"}], "
                + "\"spy\": [\"catalog\"], \"verifyCallChoice\": 2, \"repeatTrigger\": 3, \"stubs\": []}");
        assertEquals("declare=1, spy=1, verifyCallChoice=2, repeatTrigger=3", summary);
        assertEquals("(none)", TreeGenerationTool.formFieldSummary("{}"));
        assertEquals("(unparsable)", TreeGenerationTool.formFieldSummary("not json"));
    }

    @Test
    void theExercisedCallIsTheSubjectCallEvenInsideADeclarationWithStatementsAfterIt() throws IOException {
        // with `final var titles = result.titlesOrThrow();` after the subject
        // call, the fallback picked that LAST statement and the repeat duplicated it - times(1) then passed
        Files.writeString(dir.resolve("Base.java"), """
                package t;

                class Base {
                    @Mock
                    protected Session session;
                    protected CatalogImpl catalog;
                    void refresh() { catalog = new CatalogImpl(); }
                }
                """);
        Files.writeString(aFile, """
                package t;

                class SearchTest extends Base {
                    private Search subject;

                    void cloned() {
                        refresh();
                        given(session.open(Catalog.class)).willReturn(catalog);
                        final var result = subject.find(session);
                        result.titlesOrThrow();
                        final var titles = result.titlesOrThrow();
                        assertEquals(FOUND, titles.status());
                    }
                }
                """);
        List<Map<String, String>> items = TreeGenerationTool.itemsFromScenarioForm("{\"repeatTrigger\": 2}",
                aFile, "SearchTest#cloned", List.of(), "t");
        assertEquals(1, items.size(), items.toString());
        assertEquals("subject.find(session);", items.get(0).get("code"));
        assertEquals("final var result = subject.find(session);", items.get(0).get("anchor"));
    }

    @Test
    void theProductionTwinOfATestClassMayCarryASuffix() throws IOException {
        // ReportTest → ReportService; the bare stem `Report` names no type
        Files.writeString(dir.resolve("ReportService.java"), """
                package t;

                class ReportService {
                    Object respond(Object request) { return summaryFor(request); }
                    Object summaryFor(Object request) {
                        byte[] pages = archive.loadAll(request).toByteArray();
                        return checksum(pages);
                    }
                }
                """);
        Files.writeString(dir.resolve("ReportServiceTest.java"), """
                package t;

                class ReportServiceTest {
                    void unrelated() { }
                }
                """);
        // a generated value type with a SHORTER name starting with the stem must not win over the service
        Files.writeString(dir.resolve("ReportQuery.java"), """
                package t;

                class ReportQuery {
                    Object header() { return null; }
                    Object reportId() { return null; }
                }
                """);
        Files.writeString(aFile, """
                package t;

                class ReportTest {
                    void cloned() { new ReportService().respond(request); }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir),
                ClasspathIndex.system());
        List<String> transcript = new ArrayList<>();
        String section = TreeGenerationTool.codeUnderTest(g, "ReportTest#repeated",
                "repeated requests must not re-load all the pages (summaryFor) and checksum them", null, transcript);
        assertTrue(section.contains("ReportService#summaryFor"), section);
        assertTrue(transcript.stream().anyMatch(l -> l.contains("code under test twin: ReportTest → ReportService")),
                transcript.toString());
        // no twin at all: the transcript says so
        List<String> none = new ArrayList<>();
        assertEquals("", TreeGenerationTool.codeUnderTest(g, "NothingLikeItTest#x", "t", null, none));
        assertTrue(none.stream().anyMatch(l -> l.startsWith("code under test: NONE")), none.toString());
    }

    @Test
    void aNegativeRequirementImpliesRedAndCriticsMayNotObjectThatTheTestFailsToday() {
        // no expectTestOutcome → no TDD context → "the service does not cache yet, loadAll will be
        // called twice" rejected the right test
        List<String> notes = new ArrayList<>();
        assertEquals("red", TreeGenerationTool.inferredExpectation(
                "Repeated report requests for the SAME unchanged archive must not re-load all the pages", null, notes));
        assertTrue(notes.get(0).contains("inferred RED"), notes.get(0));
        assertEquals(null,
                TreeGenerationTool.inferredExpectation("the service returns the title of the report", null, notes));
        assertEquals("green", TreeGenerationTool.inferredExpectation("x must not y", "green", notes));
        List<String> issues = List.of(
                "[correctness of the inserted logic] [\"The test expects that all pages are loaded and checksummed "
                + "only once across multiple identical requests. However, `subject.respond` creates a new archive "
                + "reader on each call and does not cache the pages or their checksum. Consequently, "
                + "`archive.loadAll` will be called twice and the verification will fail.\"]",
                "[meaningful assertions] [\"The verify targets lookup, not the load — the test never verifies the load "
                + "count.\"]");
        List<String> kept = TreeGenerationTool.dropTddContradictions(issues, "red", new ArrayList<>());
        assertEquals(1, kept.size(), kept.toString());
        assertTrue(kept.get(0).contains("targets lookup"), kept.toString());
        // with GREEN expected nothing is dropped
        assertEquals(2, TreeGenerationTool.dropTddContradictions(issues, "green", null).size());
    }

    @Test
    void anUnresolvableStubOnASpyIsDroppedNotRepaired() throws IOException {
        // doReturn(Books.from("content")).when(spy).loadAll(...) - an invented static on a stub
        // the scenario never needed - cost the round to a repair that imported a non-existent class
        Files.writeString(aFile, """
                package t;

                class SearchTest {
                    void cloned() {
                        final var catalog = spy(this.catalog);
                        doReturn(Books.from("content")).when(catalog).loadAll(any());
                        subject.find(session);
                        verify(catalog, times(1)).loadAll(any());
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir),
                ClasspathIndex.system());
        String tail = aFile + ":6: error: cannot find symbol\n"
                + "        doReturn(Books.from(\"content\")).when(catalog).loadAll(any());\n"
                + "                         ^\n  symbol:   method from(String)\n";
        String dropped = TreeGenerationTool.dropUnresolvableSpyStub(aFile, "SearchTest#cloned", tail,
                ClasspathIndex.system(), g.sources());
        assertEquals("doReturn(Books.from(\"content\")).when(catalog).loadAll(any());", dropped);
        String after = Files.readString(aFile);
        assertFalse(after.contains("doReturn("), after);
        assertTrue(after.contains("verify(catalog, times(1)).loadAll(any());"), after);
        // a stub on a MOCK (not a spy) or a non-stub line is left to the repair author
        assertEquals(null, TreeGenerationTool.dropUnresolvableSpyStub(aFile, "SearchTest#cloned",
                aFile + ":7: error: cannot find symbol\n        subject.find(session);\n", ClasspathIndex.system(),
                g.sources()));
    }

    @Test
    void aStubCallWrappedInGivenByTheAuthorIsUnwrapped() {
        // given(given(catalog.lookup(id))).willReturn(x) did not compile
        assertEquals("catalog.lookup(id)", TreeGenerationTool.unwrapStubCall("given(catalog.lookup(id))"));
        assertEquals("catalog.lookup(id)", TreeGenerationTool.unwrapStubCall("when(catalog.lookup(id))"));
        assertEquals("catalog.lookup(id)", TreeGenerationTool.unwrapStubCall("lenient().when(catalog.lookup(id))"));
        assertEquals("catalog.loadAll(any(), eq(1))",
                TreeGenerationTool.unwrapStubCall("catalog.loadAll(any(), eq(1))"));
        assertEquals("", TreeGenerationTool.unwrapStubCall(null));
    }

    /** A root single-project build whose fake wrapper answers every task with the given exit code. */
    private void fakeGradle(int testExit) throws IOException {
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }\n");
        Path wrapper = dir.resolve("gradlew");
        Files.writeString(wrapper, "#!/bin/sh\ncase \"$*\" in *test*) exit " + testExit + ";; *) exit 0;; esac\n");
        wrapper.toFile().setExecutable(true);
    }

    @Test
    void anExpectedGreenTestDecidesAndTheCriticsBecomeAdvisory() throws IOException {
        fakeGradle(0);
        String dissent = "{\"approved\": false, \"issues\": [\"asks for work outside the task\"]}";
        Deque<String> llm = new ArrayDeque<>(List.of(dissent, dissent, dissent));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(Map.of("op", "insertAtEnd", "method", "A#run", "code", "base = base + 1;")));
        p.put("testFilter", "t.ATest");
        p.put("expectTestOutcome", "green");
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("green", r.get("testOutcome"));
        assertEquals(true, r.get("testExpectationMet"));
        assertTrue(String.valueOf(r.get("reviewNotes")).contains("outside the task"), r.toString());
        assertTrue(String.valueOf(r.get("reviewNotes")).startsWith("ADVISORY ONLY: the expected test passed, so this step is "
                + "done; do the request's next step"), r.toString());
        assertTrue(Files.readString(aFile).contains("base = base + 1;"), "the change is kept");
    }

    @Test
    void aRedTestThatWasExpectedGreenFailsTheRoundWhenNoRepairIsLeft() throws IOException {
        fakeGradle(1);
        Deque<String> llm = new ArrayDeque<>();
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(Map.of("op", "insertAtEnd", "method", "A#run", "code", "base = base + 1;")));
        p.put("testFilter", "t.ATest");
        p.put("expectTestOutcome", "green");
        System.setProperty("hermit.treegen.processRetries", "1");
        System.setProperty("hermit.treegen.criticRepairs", "0");
        Map<String, Object> r = tool.execute(p);
        assertTrue(String.valueOf(r.get("transcript")).contains("test outcome: red (run before the critics)"), r.toString());
        // a test that is still red when green was expected fails the round, and the file is restored (before, the
        // round returned Success with the change kept)
        assertEquals("Error", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("message")).contains("but green was expected"), r.toString());
        assertTrue(!Files.readString(aFile).contains("base = base + 1;"), "the file is restored");
    }

    @Test
    void anExpectedRedTestThatFailsAtAnAssertionDecidesAndTheCriticsBecomeAdvisory() throws IOException {
        // the caller's reviewed items reached the expected red, and the critic rounds then
        // rewrote the test into one that could never pass
        fakeGradleRuns("red", "red");
        String dissent = "{\"approved\": false, \"issues\": [\"the position asserts are fragile\"]}";
        Deque<String> llm = new ArrayDeque<>(List.of(dissent, dissent, dissent));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(Map.of("op", "insertAtEnd", "method", "A#run", "code", "base = base + 1;")));
        p.put("testFilter", "t.ATest.works");
        p.put("expectTestOutcome", "red");
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("red", r.get("testOutcome"));
        assertEquals(true, r.get("testExpectationMet"));
        assertTrue(String.valueOf(r.get("transcript")).contains("test outcome: red at an assertion (run before the critics)"),
                r.toString());
        assertTrue(String.valueOf(r.get("reviewNotes")).startsWith("ADVISORY ONLY: the expected red test fails at its "
                + "assertion, so this step is done"), r.toString());
        assertTrue(String.valueOf(r.get("reviewNotes")).contains("fragile"), r.toString());
        assertTrue(Files.readString(aFile).contains("base = base + 1;"), "the change is kept as the caller wrote it");
    }

    @Test
    void onlyAnAssertionFailureOfTheNamedMethodIsAnAssertionRed() throws IOException {
        Path results = dir.resolve("build/test-results/test");
        Files.createDirectories(results);
        assertFalse(TreeGenerationTool.redAtAnAssertion(dir, "t.ATest.works"), "no report");
        Path report = results.resolve("TEST-t.ATest.xml");
        Files.writeString(report, "<testsuite><testcase name=\"works()\" classname=\"t.ATest\">"
                + "<failure message=\"expected: &lt;4&gt; but was: &lt;0&gt;\" type=\"org.opentest4j.AssertionFailedError\">"
                + "trace</failure></testcase><testcase name=\"other()\" classname=\"t.ATest\"/></testsuite>");
        assertTrue(TreeGenerationTool.redAtAnAssertion(dir, "t.ATest.works"));
        assertFalse(TreeGenerationTool.redAtAnAssertion(dir, "t.ATest.other"), "a passing method");
        assertFalse(TreeGenerationTool.redAtAnAssertion(dir, "t.ATest.missing"), "a method the report does not hold");
        assertFalse(TreeGenerationTool.redAtAnAssertion(dir, "t.ATest"), "a class filter names no one method");
        Files.writeString(report, "<testsuite><testcase name=\"works()\" classname=\"t.ATest\">"
                + "<failure message=\"boom\" type=\"java.lang.NullPointerException\">trace</failure></testcase></testsuite>");
        assertFalse(TreeGenerationTool.redAtAnAssertion(dir, "t.ATest.works"), "an exception from the fixture");
    }

    /**
     * A root single-project build whose fake wrapper compiles everything and answers the n-th test run with the n-th
     * outcome, writing a test report: a red run fails in A.match called from A.run.
     */
    private void fakeGradleRuns(String... outcomes) throws IOException {
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }\n");
        Path wrapper = dir.resolve("gradlew");
        String red = "<testsuite><testcase name=\"works()\" classname=\"t.ATest\">"
                + "<failure message=\"expected 2 but was 1\" type=\"java.lang.AssertionError\">"
                + "java.lang.AssertionError: expected 2 but was 1\n\tat t.A.match(A.java:7)\n\tat t.A.run(A.java:12)\n"
                + "\tat t.ATest.works(ATest.java:9)\n</failure></testcase></testsuite>";
        String green = "<testsuite><testcase name=\"works()\" classname=\"t.ATest\"/></testsuite>";
        StringBuilder sh = new StringBuilder("#!/bin/sh\ncd \"$(dirname \"$0\")\"\n"
                + "case \"$*\" in *test*) ;; *) exit 0;; esac\n"
                + "n=$(cat runs 2>/dev/null || echo 0); n=$((n+1)); echo $n > runs\n"
                + "mkdir -p build/test-results/test\n");
        for (int i = 0; i < outcomes.length; i++) {
            boolean ok = "green".equals(outcomes[i]);
            sh.append("if [ $n -eq ").append(i + 1).append(" ]; then cat > build/test-results/test/TEST-t.ATest.xml <<'XML'\n")
                    .append(ok ? green : red).append("\nXML\nexit ").append(ok ? 0 : 1).append("; fi\n");
        }
        sh.append("exit 1\n");
        Files.writeString(wrapper, sh.toString());
        wrapper.toFile().setExecutable(true);
        Files.writeString(aFile, """
                package t;

                class A {
                    int pos;

                    boolean match(int kind) {
                        return kind == pos;
                    }

                    void run() {
                        int base = 1;
                    }
                }
                """);
    }

    private Map<String, Object> testDrivenParams() {
        Map<String, Object> p = baseParams();
        p.put("items", List.of(
                Map.of("op", "insertAtEnd", "method", "A#run", "code", "match(base);"),
                Map.of("op", "insertMember", "type", "A", "code", "void twice() { run(); run(); }")));
        p.put("testFilter", "t.ATest");
        p.put("expectTestOutcome", "green");
        return p;
    }

    @Test
    void aRedTestThatWasExpectedGreenDrivesTheRepairWithItsFailurePathAndTheCalledMembers() throws IOException {
        // eight committed items left the expected-green test red, and the critics'
        // complaints (about the last item alone) went to the repair author instead of the failure
        fakeGradleRuns("red", "green");
        List<String> prompts = new ArrayList<>();
        Deque<String> llm = new ArrayDeque<>(List.of(
                "[{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"pos = pos + 1;\"}]",
                APPROVE, APPROVE, APPROVE));
        Function<String, String> author = scripted(llm);
        TreeGenerationTool tool = new TreeGenerationTool(prompt -> {
            prompts.add(prompt);
            return author.apply(prompt);
        });
        Map<String, Object> r = tool.execute(testDrivenParams());
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(true, r.get("testExpectationMet"), r.toString());
        assertTrue(Files.readString(aFile).contains("pos = pos + 1;"), "the repair is kept");
        String repair = prompts.get(0);
        assertTrue(repair.contains("its named test FAILS") && repair.contains("WHAT FAILED:"), repair);
        assertTrue(repair.contains("expected 2 but was 1"), repair);
        assertTrue(repair.contains("match, line 7: return kind == pos;"), repair);
        assertTrue(repair.contains("THE EXISTING MEMBERS YOUR CODE CALLS") && repair.contains("boolean match(int kind) {"),
                repair);
        assertFalse(repair.contains("REVIEW ISSUES"), repair);
        assertTrue(String.valueOf(r.get("transcript")).contains("repair 1 from the test failure"), r.toString());
    }

    @Test
    void theNextRoundStartsFromTheCallersItemsAsCommittedAndTheCriticsSeeEveryItem() throws IOException {
        fakeGradleRuns("red", "green");
        System.setProperty("hermit.treegen.processRetries", "2");
        System.setProperty("hermit.treegen.criticRepairs", "0");
        List<String> prompts = new ArrayList<>();
        Deque<String> llm = new ArrayDeque<>(List.of(
                // round 1: the first item is re-authored once from its problem
                "{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"match(base);\"}",
                APPROVE, APPROVE, APPROVE));
        Function<String, String> author = scripted(llm);
        TreeGenerationTool tool = new TreeGenerationTool(prompt -> {
            prompts.add(prompt);
            return author.apply(prompt);
        });
        Map<String, Object> p = testDrivenParams();
        p.put("items", List.of(
                Map.of("op", "insertAtEnd", "method", "A#run", "code", "match(missingVar);"),
                Map.of("op", "insertMember", "type", "A", "code", "void twice() { run(); run(); }")));
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(2, r.get("rounds"), r.toString());
        String transcript = String.valueOf(r.get("transcript"));
        assertTrue(transcript.contains("the next round starts from the 2 committed item(s)"), transcript);
        assertEquals(4, prompts.size(), "round 2 re-applies the committed items without re-authoring: " + prompts);
        String critic = prompts.get(1);
        assertTrue(critic.contains("match(base);") && critic.contains("void twice()"), critic);
    }

    @Test
    void anItemRejectedThreeTimesWithTheSameProblemsStalls() throws IOException {
        System.setProperty("hermit.treegen.processRetries", "1");
        String same = "{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"int twice = missingVar * 2;\"}";
        Deque<String> llm = new ArrayDeque<>(List.of(same, same));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(Map.of("op", "insertAtEnd", "method", "A#run", "code", "int twice = missingVar * 2;")));
        Map<String, Object> r = tool.execute(p);
        assertEquals("Error", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("message")).contains("stalled: the same problems came back 3 times"), r.toString());
        assertTrue(llm.isEmpty(), "two re-authorings, then the item stops");
    }

    @Test
    void anItemWhoseProblemsCycleStallsToo() throws IOException {
        // two invented type names alternated for 25 attempts
        System.setProperty("hermit.treegen.processRetries", "1");
        String a = "{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"int twice = missingB * 2;\"}";
        String b = "{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"int twice = missingA * 2;\"}";
        Deque<String> llm = new ArrayDeque<>(List.of(a, b, a, b));
        TreeGenerationTool tool = new TreeGenerationTool(scripted(llm));
        Map<String, Object> p = baseParams();
        p.put("items", List.of(Map.of("op", "insertAtEnd", "method", "A#run", "code", "int twice = missingA * 2;")));
        Map<String, Object> r = tool.execute(p);
        assertEquals("Error", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("message")).contains("stalled: the same problems came back 3 times"), r.toString());
        assertEquals(0, llm.size(), "A, B, A, B, then the third A stops the item");
    }

    @Test
    void aCommandThatOutlivesItsTimeoutIsStopped() throws Exception {
        long start = System.nanoTime();
        TreeGenerationTool.CliResult r = TreeGenerationTool.run(dir, 1, "sh", "-c", "echo started; sleep 30");
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        assertEquals(-1, r.exitCode());
        assertTrue(r.output().contains("TIMEOUT") && r.output().contains("started"), r.output());
        assertTrue(seconds < 10, "stopped after " + seconds + " s");
    }

    /** A fake wrapper whose n-th test run writes the n-th report (testcase name to failure message, "" = passes). */
    @SafeVarargs
    private void fakeGradleReports(Map<String, String>... runs) throws IOException {
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }\n");
        Path wrapper = dir.resolve("gradlew");
        StringBuilder sh = new StringBuilder("#!/bin/sh\ncd \"$(dirname \"$0\")\"\n"
                + "case \"$*\" in *test*) ;; *) exit 0;; esac\n"
                + "n=$(cat runs 2>/dev/null || echo 0); n=$((n+1)); echo $n > runs\n"
                + "mkdir -p build/test-results/test\n");
        for (int i = 0; i < runs.length; i++) {
            StringBuilder xml = new StringBuilder("<testsuite>");
            boolean failed = false;
            for (Map.Entry<String, String> c : runs[i].entrySet()) {
                xml.append("<testcase name=\"").append(c.getKey()).append("()\" classname=\"t.ATest\"");
                if (c.getValue().isEmpty()) {
                    xml.append("/>");
                } else {
                    failed = true;
                    xml.append("><failure message=\"").append(c.getValue()).append("\" type=\"java.lang.AssertionError\">")
                            .append("java.lang.AssertionError: ").append(c.getValue()).append("\n\tat t.A.run(A.java:12)\n")
                            .append("</failure></testcase>");
                }
            }
            xml.append("</testsuite>");
            sh.append("if [ $n -eq ").append(i + 1).append(" ]; then cat > build/test-results/test/TEST-t.ATest.xml <<'XML'\n")
                    .append(xml).append("\nXML\nexit ").append(failed ? 1 : 0).append("; fi\n");
        }
        sh.append("exit 1\n");
        Files.writeString(wrapper, sh.toString());
        wrapper.toFile().setExecutable(true);
    }

    @Test
    void aChangeThatBreaksAnotherTestOfTheClassIsRepairedFromThatTest() throws IOException {
        // two items made the named test pass and broke two round-trip tests of the
        // same class; TreeCodegen would have returned Success
        fakeGradleReports(
                new LinkedHashMap<>(Map.of("works", "", "roundTrips", "")),          // baseline
                Map.of("works", ""),                                                          // the named test
                new LinkedHashMap<>(Map.of("works", "", "roundTrips", "lost the name")), // the class
                Map.of("works", ""),                                                          // after the repair
                new LinkedHashMap<>(Map.of("works", "", "roundTrips", "")));
        List<String> prompts = new ArrayList<>();
        Deque<String> llm = new ArrayDeque<>(List.of(
                "[{\"op\": \"insertAtEnd\", \"method\": \"A#run\", \"code\": \"base = base + 2;\"}]",
                APPROVE, APPROVE, APPROVE));
        Function<String, String> author = scripted(llm);
        TreeGenerationTool tool = new TreeGenerationTool(prompt -> {
            prompts.add(prompt);
            return author.apply(prompt);
        });
        Map<String, Object> p = baseParams();
        p.put("items", List.of(Map.of("op", "insertAtEnd", "method", "A#run", "code", "base = base + 1;")));
        p.put("testFilter", "t.ATest.works");
        p.put("expectTestOutcome", "green");
        Map<String, Object> r = tool.execute(p);
        assertEquals("Success", r.get("status"), r.toString());
        String transcript = String.valueOf(r.get("transcript"));
        assertTrue(transcript.contains("regression baseline: 2 test(s) of t.ATest pass before the change"), transcript);
        assertTrue(transcript.contains("regression: 1 test(s) of t.ATest that passed before now fail: roundTrips"), transcript);
        String repair = prompts.get(0);
        assertTrue(repair.contains("keep every test that passed before it passing") && repair.contains("roundTrips")
                && repair.contains("lost the name"), repair);
    }

    @Test
    void aSingleMethodFilterNamesItsClass() {
        assertEquals("pkg.ClassTest", TreeGenerationTool.testClassOf("pkg.ClassTest.method"));
        assertEquals(null, TreeGenerationTool.testClassOf("pkg.ClassTest"));
        assertEquals(null, TreeGenerationTool.testClassOf("pkg.ClassTest.method*"));
        assertEquals(null, TreeGenerationTool.testClassOf(null));
    }

    @Test
    void declarationItemsAreAppliedBeforeTheStatementItemsThatUseThem() {
        // item 1 called isKeywordOf, which item 2 inserts; item 1 was validated first and refused
        Map<String, String> statement = Map.of("op", "replaceStatement", "anchor", "if (a)", "code", "if (isKeywordOf(a)) { }");
        Map<String, String> member = Map.of("op", "insertMember", "code", "static boolean isKeywordOf(int a) { return a > 0; }");
        Map<String, String> body = Map.of("op", "replaceBody", "method", "A#b", "code", "{ }");
        Map<String, String> imp = Map.of("op", "addImport", "code", "java.util.List");
        List<String> transcript = new ArrayList<>();
        assertEquals(List.of(imp, member, statement, body),
                TreeGenerationTool.declarationsFirst(List.of(statement, member, body, imp), transcript));
        assertTrue(transcript.get(0).contains("planned items 4, 2 first"), transcript.toString());
        List<Map<String, String>> inOrder = List.of(member, statement);
        assertEquals(inOrder, TreeGenerationTool.declarationsFirst(inOrder, transcript));
        assertEquals(1, transcript.size(), "an order that needs no change is not noted");
    }

    @Test
    void consecutiveMemberInsertsIntoOneTypeAreValidatedTogether() {
        // build (item 6) called processImports, which item 7 inserts; both are member inserts
        Map<String, String> build = Map.of("op", "insertMember", "type", "R", "code", "private void build() { load(); }");
        Map<String, String> load = Map.of("op", "insertMember", "type", "R", "code", "private void load() { }");
        Map<String, String> other = Map.of("op", "insertMember", "type", "Q", "code", "int q;");
        Map<String, String> body = Map.of("op", "replaceBody", "method", "R#run", "code", "{ build(); }");
        List<String> transcript = new ArrayList<>();
        List<Map<String, String>> merged = TreeGenerationTool.mergedMemberInserts(List.of(build, load, other, body), transcript);
        assertEquals(3, merged.size(), merged.toString());
        assertEquals("private void build() { load(); }\n\nprivate void load() { }", merged.get(0).get("code"));
        assertEquals("R", merged.get(0).get("type"));
        assertEquals(List.of(other, body), merged.subList(1, 3));
        assertTrue(transcript.get(0).contains("2 member inserts into R are validated together"), transcript.toString());
    }

    @Test
    void aReplaceMemberItemWithoutItsOwnTargetReplacesTheMemberItsCodeDeclares() {
        // a re-plan dropped each replaceMember item's own "method"; every item fell back to
        // the call's method, so the code of isDirect and namedAfter replaced annotationNames in turn
        Map<String, String> item = Map.of("op", "replaceMember",
                "code", "/** Doc. */\nstatic boolean isDirect(List<String> path, List<CodeGraph.MethodRef> targets) {\n    return true;\n}");
        assertEquals("TestReach#isDirect(List, List)", TreeGenerationTool.itemTarget(item, "TestReach#annotationNames"));
        assertEquals("TestReach#TestReach(CodeGraph)", TreeGenerationTool.itemTarget(Map.of("op", "replaceMember",
                "code", "private TestReach(CodeGraph graph) {\n    this.graph = graph;\n}"), "TestReach#of"));
        assertEquals("A#x", TreeGenerationTool.itemTarget(Map.of("op", "replaceMember", "method", "A#x",
                "code", "void y() {\n}"), "A#z"), "an item's own target stands");
        assertEquals("A#z", TreeGenerationTool.itemTarget(Map.of("op", "replaceBody", "code", "return;"), "A#z"),
                "a body names no member: the call's method");
    }
}
