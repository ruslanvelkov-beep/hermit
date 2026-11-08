package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import hermit.checkpoint.TurnSnapshot;
import hermit.review.ReviewPanel;
import hermit.runtime.LoopSettings;
import hermit.runtime.RepairCoordinator;
import hermit.runtime.ResultSummaries;
import hermit.runtime.ToolExecutor;
import hermit.text.CodeFence;
import hermit.tool.analysis.TreeEditTool;
import hermit.tool.api.ToolRegistry;
import hermit.tool.process.BashTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** A plan that re-runs this turn's calls verbatim is rejected (successes → conclude; failures → change); and the other plan checks. */
class RepeatGuardTest {

    private final Action catches = new Action("CodeGraph", Map.of("action", "catches", "symbol", "A#m"));
    private final Action locate = new Action("CodeGraph", Map.of("action", "locate", "symbol", "A#m"));
    private final Action badEdit = new Action("TreeEdit", Map.of("file", "src/main/java/com/example/X.java", "op", "replaceStatement"));

    @Test
    void repeatingOnlySuccessesAsksToConclude() {
        Map<String, String> executed = new LinkedHashMap<>();
        ResultSummaries.recordSignatures(List.of(catches, locate), new LinkedHashMap<>(Map.of(
                "CodeGraph", Map.of("status", "Success"), "CodeGraph#2", Map.of("status", "Success"))), executed);
        String issue = RepeatGuard.repeatIssue(List.of(catches, locate), executed);
        assertNotNull(issue);
        assertTrue(issue.contains("SUCCEEDED"), issue);
        // a plan with ONE new action is not a repeat
        assertNull(RepeatGuard.repeatIssue(List.of(catches, new Action("Bash", Map.of("command", "ls"))), executed));
    }

    @Test
    void repeatingAFailureVerbatimAsksToChangeSomething() {
        Map<String, String> executed = new LinkedHashMap<>();
        ResultSummaries.recordSignatures(List.of(badEdit), new LinkedHashMap<>(Map.of(
                "TreeEdit", Map.of("status", "Error", "message", "file not found"))), executed);
        String issue = RepeatGuard.repeatIssue(List.of(badEdit), executed);
        assertNotNull(issue);
        assertTrue(issue.contains("FAILED"), issue);
        // changing a parameter makes it a new attempt
        Action changed = new Action("TreeEdit", Map.of("file", "app/src/main/java/X.java", "op", "replaceStatement"));
        assertNull(RepeatGuard.repeatIssue(List.of(changed), executed));
    }

    @Test
    void signatureIsOrderInsensitive() {
        Action a = new Action("T", new LinkedHashMap<>(Map.of("x", "1", "y", "2")));
        Action b = new Action("T", new LinkedHashMap<>(Map.of("y", "2", "x", "1")));
        assertTrue(RepeatGuard.signature(a).equals(RepeatGuard.signature(b)));
    }

    @Test
    void aReQualifiedSymbolIsTheSameCall() {
        // the plan re-ran catches/tests/callers with SIMPLE names after running them qualified
        Map<String, String> executed = new HashMap<>();
        ResultSummaries.recordSignatures(
                List.of(new Action("CodeGraph", Map.of(
                        "action", "catches", "symbol", "com.example.library.loans.LoanDesk#run"))),
                new LinkedHashMap<>(Map.of("CodeGraph", Map.of("status", "Success"))),
                executed);
        String issue = RepeatGuard.repeatIssue(
                List.of(new Action("CodeGraph", Map.of("action", "catches", "symbol", "LoanDesk#run"))),
                executed);
        assertNotNull(issue, "the simple-name form is the same call: " + executed);
        // Type:method is the same too, and a genuinely different method is not
        assertNotNull(RepeatGuard.repeatIssue(
                List.of(new Action("CodeGraph", Map.of("action", "catches", "symbol", "LoanDesk:run"))),
                executed));
        assertNull(RepeatGuard.repeatIssue(
                List.of(new Action("CodeGraph", Map.of("action", "catches", "symbol", "LoanDesk#other"))),
                executed));
        // callers{target=X} and callers{symbol=X} are the same call (the tool folds the alias)
        Map<String, String> done = new HashMap<>();
        ResultSummaries.recordSignatures(
                List.of(new Action("CodeGraph", Map.of("action", "callers", "target", "A#m"))),
                new LinkedHashMap<>(Map.of("CodeGraph", Map.of("status", "Success"))), done);
        assertNotNull(RepeatGuard.repeatIssue(
                List.of(new Action("CodeGraph", Map.of("action", "callers", "symbol", "A#m"))), done));
        // reach keeps its two roles apart
        assertEquals("target", RepeatGuard.canonicalKey("target", Map.of("action", "reach", "target", "A#m")));
        assertEquals("symbol", RepeatGuard.canonicalKey("target", Map.of("action", "callers", "target", "A#m")));
        assertEquals("LoanDesk#run", RepeatGuard.canonicalValue("  com.x.y.LoanDesk#run "));
        assertEquals("a test that stubs the iterator", RepeatGuard.canonicalValue("a test that stubs the iterator"));
    }

    @Test
    void aFailedFirstActionRepeatedVerbatimIsRefusedEvenWithNewFollowUps() {
        // the same failed edit led three plans in a row; new gradle/commit steps after it let it through
        Action edit = new Action("TreeEdit", Map.of("op", "replaceStatement", "method", "W#m", "anchor", "x(1);", "code", "x(2);"));
        Action fresh = new Action("Bash", Map.of("command", "./gradlew :app:test --tests W"));
        Map<String, String> executed = new HashMap<>();
        executed.put(RepeatGuard.signature(edit), "Error");
        String issue = RepeatGuard.repeatIssue(List.of(edit, fresh), executed);
        assertNotNull(issue);
        assertTrue(issue.contains("FIRST action"), issue);
        // a SUCCEEDED first action followed by something new is still fine
        executed.put(RepeatGuard.signature(edit), "Success");
        assertNull(RepeatGuard.repeatIssue(List.of(edit, fresh), executed));
    }

    @Test
    void aPushOrPostStepIsRefusedAtThePlanGateInReadOnlyMode() {
        // `git push` entered two plans running despite the request's hard rule
        String issue = PlanGate.forbiddenStepIssue(List.of(
                new Action("TreeCodegen", Map.of("task", "x")),
                new Action("Bash", Map.of("command", "git add a.java && git commit -s -m 'test: x'")),
                new Action("Bash", Map.of("command", "git push origin feature-branch"))));
        assertNotNull(issue);
        assertTrue(issue.contains("git push"), issue);
        assertTrue(issue.contains("Re-plan WITHOUT that step"), issue);
        // local add/commit, analysis and edits are not forbidden
        assertNull(PlanGate.forbiddenStepIssue(List.of(
                new Action("Bash", Map.of("command", "git add a.java && git commit -s -m 'fix: y'")),
                new Action("Bash", Map.of("command", "./gradlew :app:test --tests X")),
                new Action("TreeEdit", Map.of("op", "replaceStatement")))));
        // posting to a ticket or PR is outward-facing too
        assertNotNull(PlanGate.forbiddenStepIssue(List.of(
                new Action("Bash", Map.of("command", "gh pr comment 12 --body 'done'")))));
        assertNull(PlanGate.forbiddenStepIssue(null));
    }

    @Test
    void aPredictionIsNotAnObservedTestOutcome() {
        // emulatorPrediction counted as "test observed", so a predicted RED silenced the honesty note
        boolean[] committed = {false};
        boolean[] observed = {false};
        RepairCoordinator.observeGroundTruth(Map.of("TreeCodegen", Map.of("status", "Success", "emulatorPrediction", "PREDICTED RED: x")),
                committed, observed);
        assertFalse(observed[0], "a prediction is not a run");
        RepairCoordinator.observeGroundTruth(Map.of("CodeGraph", Map.of("status", "Success", "verdict", "PREDICTED GREEN")), committed, observed);
        assertFalse(observed[0]);
        RepairCoordinator.observeGroundTruth(Map.of("TreeCodegen", Map.of("status", "Success", "testOutcome", "red")), committed, observed);
        assertTrue(observed[0]);
        boolean[] observed2 = {false};
        RepairCoordinator.observeGroundTruth(Map.of("Bash", Map.of("status", "NonZeroExit", "exitCode", 1,
                "output", "> Task :inventory:test FAILED")), committed, observed2);
        assertTrue(observed2[0], "a gradle test task in the output is a run");
        assertFalse(committed[0]);
    }

    @Test
    void theAgentCommitsAGeneratedTestItselfWhenThePlannerWillNot() throws IOException {
        // told twice to commit, the planner answered with a fabricated hash instead
        Path repo = Files.createTempDirectory("repo");
        Files.createDirectories(repo.resolve(".git"));
        Path file = repo.resolve("mod/src/test/java/ShelfReportTest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class ShelfReportTest {}");
        assertEquals(repo, LoopSettings.repoRootOf(file));
        String cmd = RepairCoordinator.autoCommitCommand(file.toString(),
                "ShelfReportTest#testNoRecountForUnchangedShelf", repo);
        assertEquals("git -C '" + repo + "' add '" + file + "' && git -C '" + repo
                + "' commit -s -m 'test: add failing test testNoRecountForUnchangedShelf'", cmd);
        assertNull(BashTool.destructiveOp(cmd), "a local signed commit is not destructive");
        assertTrue(RepairCoordinator.autoCommitCommand("/x/T.java", null, null).endsWith("commit -s -m 'test: add failing test the generated test'"));
        // a relative path (the planner's) uses plain git in the shell tool's project directory - no -C prefix
        assertEquals("git add 'inventory/mod/src/test/java/T.java' && git commit -s -m 'test: add failing test t'",
                RepairCoordinator.autoCommitCommand("inventory/mod/src/test/java/T.java", "T#t",
                        Path.of("inventory/mod/src/test/java")));
    }

    @Test
    void theProgramAWriteProgramWillRunIsKnownAtTheSeam() {
        // the seam showed a WriteProgram whose program was the fenced block after the JSON
        String raw = "{\"branches\": []}\n\n```bash\necho contract\n```\n";
        CodeFence.Extracted fenced = CodeFence.first(raw);
        List<Action> actions = List.of(new Action("WriteProgram", Map.of("language", "bash", "run", true)),
                new Action("Bash", Map.of("command", "git status")));
        assertEquals("echo contract", RepairCoordinator.programAtSeam(actions, fenced).strip());
        assertNull(RepairCoordinator.programAtSeam(List.of(new Action("Bash", Map.of("command", "ls"))), fenced));
        assertNull(RepairCoordinator.programAtSeam(actions, null));
    }

    @Test
    void consecutiveMemberInsertsOfOneTypeAreOneEdit() {
        // members that call each other were refused one by one
        Action a = new Action("TreeEdit", Map.of("op", "insertMember", "file", "S.java", "type", "S", "code", "void a() { b(); }"));
        Action b = new Action("TreeEdit", Map.of("op", "insertMember", "file", "S.java", "type", "S", "code", "void b() { a(); }"));
        Action other = new Action("TreeEdit", Map.of("op", "insertMember", "file", "T.java", "type", "T", "code", "void c() { }"));
        Action body = new Action("TreeEdit", Map.of("op", "replaceBody", "file", "S.java", "method", "S#a()", "code", "b();"));
        List<Action> merged = ToolExecutor.mergedMemberInserts(List.of(a, b, other, body));
        assertEquals(3, merged.size(), merged.toString());
        assertEquals("void a() { b(); }\n\nvoid b() { a(); }", merged.get(0).parameters().get("code"));
        assertEquals(other, merged.get(1));
        assertEquals(body, merged.get(2));
    }

    @Test
    void anEditPlannedInTheSameStepAsItsReadsIsCut() {
        // placeholder bodies and fragments written before the reads came back
        Action read = new Action("Bash", Map.of("command", "sed -n '358,434p' Ref.java"));
        Action edit = new Action("TreeEdit", Map.of("op", "replaceBody", "method", "S#m()", "code", "return false;"));
        Action gen = new Action("TreeCodegen", Map.of("file", "S.java"));
        assertEquals(1, RepeatGuard.writeAfterRead(List.of(read, edit, gen)));
        assertEquals(0, RepeatGuard.writeAfterRead(List.of(edit, gen)), "no read: nothing to cut");
        assertEquals(0, RepeatGuard.writeAfterRead(List.of(gen, read)), "a read after the edit is not cut");
    }

    @Test
    void aPlanThatEditsWithoutTheReadsTheRequestNamesIsRefusedBeforeAnythingRan() {
        // the plan wrote code against an invented API instead of reading first
        String request = "Rewrite task 034b: a first resolver.\n\nRead first (each under 4,000 characters): the stub; "
                + "ParseTree's public members.\n\nMake the test pass.";
        List<Action> edit = List.of(new Action("TreeCodegen", Map.of("file", "S.java")));
        String issue = RepeatGuard.readFirstIssue(request, edit, false);
        assertNotNull(issue);
        assertTrue(issue.contains("Read first (each under 4,000 characters): the stub; ParseTree's public members."), issue);
        assertNull(RepeatGuard.readFirstIssue(request, edit, true), "something already ran in the turn");
        assertNull(RepeatGuard.readFirstIssue(request, List.of(new Action("Bash", Map.of("command", "cat S.java")),
                new Action("TreeCodegen", Map.of("file", "S.java"))), false), "the plan reads first");
        assertNull(RepeatGuard.readFirstIssue("Fix it.", edit, false), "no read-first instruction");
        assertEquals(2, PlanShaper.occurrences("a [x] b [x]", "[x]"));
    }

    @Test
    void onlyAGeneratedTestFileOutsideStepModeIsCommittedByTheAgent() {
        // generated parser code was committed as "test: add failing test parseSelectors",
        // past the step-mode seam
        assertTrue(RepairCoordinator.autoCommitAllowed("/r/mod/src/test/java/ShelfReportTest.java", false));
        assertFalse(RepairCoordinator.autoCommitAllowed("/r/src/main/java/hermit/analysis/tree/JavaParser.java", false));
        assertFalse(RepairCoordinator.autoCommitAllowed("/r/mod/src/test/java/ShelfReportTest.java", true));
        assertFalse(RepairCoordinator.autoCommitAllowed(null, false));
    }

    @Test
    void aToolNamedWithAResultSuffixResolvesToTheTool() {
        // a plan step named "TreeEdit#2" died with "Tool not found"
        ToolExecutor executor = new ToolExecutor(new ToolRegistry());
        Map<String, Object> results = executor.executeActions(List.of(
                new Action("Bash#2", Map.of("command", "echo suffix-ok")),
                new Action("Bash", Map.of("command", "echo plain-ok"))));
        assertEquals(List.of("Bash", "Bash#2"), new ArrayList<>(results.keySet()));
        assertTrue(String.valueOf(results.get("Bash")).contains("suffix-ok"), results.toString());
        assertTrue(String.valueOf(results.get("Bash#2")).contains("plain-ok"), results.toString());
    }

    @Test
    void declarationEditsRunBeforeTheStatementsThatUseThem() {
        // the plan declared the new field in its THIRD edit
        Action use1 = new Action("TreeEdit", Map.of("op", "insertBefore", "code",
                "if (widths.containsKey(column)) { }"));
        Action use2 = new Action("TreeEdit", Map.of("op", "insertAfter", "code", "widths.put(column, width);"));
        Action decl = new Action("TreeEdit", Map.of("op", "insertMember", "code",
                "private Map<String, Integer> widths = new HashMap<>();"));
        Action imp = new Action("TreeEdit", Map.of("op", "addImport", "code", "import java.util.HashMap;"));
        Action record = new Action("HindsightDebugger", Map.of("testClass", "T"));
        Action run = new Action("Bash", Map.of("command", "./gradlew :m:test"));
        List<Action> ordered = ToolExecutor.declarationsFirst(List.of(use1, use2, decl, imp, record, run));
        assertEquals(List.of(imp, decl, use1, use2, record, run), ordered);
        // a nested type declaration lands before the field typed with it
        Action field = new Action("TreeEdit", Map.of("op", "insertMember", "code",
                "private final Map<String, Column> columns = new HashMap<>();"));
        Action nested = new Action("TreeEdit", Map.of("op", "insertMember", "code",
                "private static final class Column { final int width; Column(int w) { width = w; } }"));
        assertEquals(List.of(nested, field), ToolExecutor.declarationsFirst(List.of(field, nested)));
        // nothing to reorder → the same list
        List<Action> plain = List.of(use1, run);
        assertEquals(plain, ToolExecutor.declarationsFirst(plain));
        // a Bash step before the first edit keeps its place
        List<Action> withBashFirst = ToolExecutor.declarationsFirst(List.of(run, use1, decl));
        assertEquals(List.of(run, decl, use1), withBashFirst);
        // a Bash step creates the files, TreeEdit adds imports and a class, a Bash
        // step checks - with no statement edit, the plan keeps its order (the declarations used to jump to the front)
        Action create = new Action("Bash", Map.of("command", "printf 'package p;\\n' > P.java"));
        Action type = new Action("TreeEdit", Map.of("op", "addType", "code", "public final class P { }"));
        Action check = new Action("Bash", Map.of("command", "./gradlew classes"));
        assertEquals(List.of(create, imp, type, check), ToolExecutor.declarationsFirst(List.of(create, imp, type, check)));
        // the class, its members and then its imports, between two Bash steps; the members
        // were checked before the imports existed ("unknown type 'List'"). Within such a run the imports go first.
        Action member = new Action("TreeEdit", Map.of("op", "insertMember", "code", "private List<String> notes;"));
        assertEquals(List.of(create, imp, type, member, check),
                ToolExecutor.declarationsFirst(List.of(create, type, member, imp, check)));
    }

    @Test
    void theEscalatedPlanAuthorGetsTheFullContractOfTheToolsNamed() {
        // Groq designed the right fix and invented `edits`/`position` from 60-char glosses
        ToolRegistry registry = new ToolRegistry();
        String docs = registry.documentationFor("the plan used TreeEdit and Bash; run HindsightDebugger", 2500);
        assertTrue(docs.contains("--- TreeEdit (full contract) ---"), docs);
        assertTrue(docs.contains("--- Bash (full contract) ---"), docs);
        assertTrue(docs.contains("insertMember"), "the TreeEdit contract names its ops");
        assertFalse(docs.contains("--- TreeCodegen (full contract) ---"), "tools not named are not included");
        assertEquals("", registry.documentationFor("nothing relevant here", 2500));
        // "the spec composer" names SpecTestComposer by its first and last camel words
        assertTrue(registry.documentationFor("Using the spec composer, compose a validated @SpecTest", 2500)
                .contains("--- SpecTestComposer (full contract) ---"));
    }

    @Test
    void theEscalatedPlanAuthorIsShownTheProductionMethodsNamed() {
        // the authored body was a sketch with '...' - the author had never seen the method
        List<String> specs = RepeatGuard.specsMentioned("the defect is in ReadingCache#latestFor; the test is "
                + "ReadingCacheTest#testNoReload; the path ReadingCache#lookup -> ReadingCache#latestFor");
        assertEquals(List.of("ReadingCache#latestFor", "ReadingCache#lookup"), specs);
        assertTrue(RepeatGuard.specsMentioned(null).isEmpty());
    }

    @Test
    void anEditWhoseBuildFailsInItsFileIsReverted() throws IOException {
        // a validated edit left the module uncompilable and every later plan worked on a broken file
        Path dir = Files.createTempDirectory("revert");
        Path f = dir.resolve("Handler.java");
        String original = "package t;\n\nclass Handler {\n    Object m() { return null; }\n}\n";
        Files.writeString(f, original);
        TreeEditTool tool = new TreeEditTool();
        Map<String, Object> params = new HashMap<>();
        params.put("file", f.toString());
        params.put("op", "insertMember");
        params.put("code", "int extra() { return 1; }");
        params.put("projectDir", dir.toString());
        Map<String, Object> edit = tool.execute(params);
        assertEquals("Success", edit.get("status"), edit.toString());
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("TreeEdit", edit);
        results.put("Bash", Map.of("status", "NonZeroExit", "exitCode", 1,
                "compileErrors", "=== compile errors === Handler.java:4: cannot find symbol <- int extra() { return 1; }"));
        String note = RepairCoordinator.revertEditsThatBrokeTheBuild(List.of(new Action("TreeEdit", params), new Action("Bash", Map.of("command", "./gradlew :m:test"))), results);
        assertNotNull(note);
        assertTrue(note.contains("Handler.java") && note.contains("REVERTED"), note);
        assertEquals(original, Files.readString(f));
        // a compile error in ANOTHER file reverts nothing
        Files.writeString(f, original);
        Map<String, Object> edit2 = tool.execute(params);
        Map<String, Object> results2 = new LinkedHashMap<>();
        results2.put("TreeEdit", edit2);
        results2.put("Bash", Map.of("status", "NonZeroExit", "compileErrors", "=== compile errors === Other.java:9: cannot find symbol <- x"));
        assertNull(RepairCoordinator.revertEditsThatBrokeTheBuild(List.of(new Action("TreeEdit", params)), results2));
        assertTrue(Files.readString(f).contains("extra()"));
    }

    @Test
    void aCommitIsRefusedWhileTheModuleDoesNotCompile() {
        // `git commit -a` ran right after the plan's own build had failed; a broken handler landed
        boolean[] broken = {false};
        RepairCoordinator.observeBuildState(Map.of("Bash", Map.of("status", "NonZeroExit", "compileErrors", "=== compile errors === H.java:3: x")), broken);
        assertTrue(broken[0]);
        List<Action> plan = List.of(new Action("TreeEdit", Map.of("op", "insertMember", "code", "int x;")),
                new Action("Bash", Map.of("command", "git add H.java && git commit -s -m 'fix: y'")));
        String issue = RepairCoordinator.commitWhileBrokenIssue(plan, broken[0]);
        assertNotNull(issue);
        assertTrue(issue.contains("does NOT compile"), issue);
        assertNull(RepairCoordinator.commitWhileBrokenIssue(List.of(new Action("Bash", Map.of("command", "./gradlew :m:test"))), broken[0]));
        // a green build clears the state
        RepairCoordinator.observeBuildState(Map.of("Bash", Map.of("status", "Success", "exitCode", 0, "output", "> Task :m:test BUILD SUCCESSFUL")), broken);
        assertFalse(broken[0]);
        assertNull(RepairCoordinator.commitWhileBrokenIssue(plan, broken[0]));
    }

    @Test
    void successfulEditsWithoutABuildStepRunTheRequestsTest() {
        // the escalated plan applied the fix and stopped; nobody built
        System.setProperty("hermit.treegen.module", ":thermostat");
        try {
            String request = "stage 3: implement the minimal fix … the failing unit test "
                    + "ReadingCacheTest#testNoReloadForUnchangedSensor is RED";
            List<Action> plan = List.of(new Action("TreeEdit", Map.of("op", "addImport", "code", "java.util.Map")),
                    new Action("TreeEdit", Map.of("op", "replaceBody", "method", "H#m", "code", "return 1;")));
            Map<String, Object> results = new LinkedHashMap<>();
            results.put("TreeEdit", Map.of("status", "Success", "op", "addImport"));
            results.put("TreeEdit#2", Map.of("status", "Success", "op", "replaceBody"));
            assertEquals("./gradlew :thermostat:test --tests ReadingCacheTest.testNoReloadForUnchangedSensor",
                    RepairCoordinator.buildAfterEditsCommand(plan, results, request));
            // a plan that already builds, or whose edits failed, runs nothing extra
            List<Action> withBuild = new ArrayList<>(plan);
            withBuild.add(new Action("Bash", Map.of("command", "./gradlew :thermostat:test")));
            assertNull(RepairCoordinator.buildAfterEditsCommand(withBuild, results, request));
            assertNull(RepairCoordinator.buildAfterEditsCommand(plan, Map.of("TreeEdit", Map.of("status", "Error")), request));
            assertNull(RepairCoordinator.buildAfterEditsCommand(plan, results, "no test named here"));
        } finally {
            System.clearProperty("hermit.treegen.module");
        }
    }

    @Test
    void aProjectDirThatDoesNotExistResolvesToTheConfiguredProject() throws IOException {
        // projectDir=./service - "projectDir not found" from the composer
        Path project = Files.createTempDirectory("project");
        Files.createDirectories(project.resolve("service"));
        System.setProperty("hermit.projectDir", project.toString());
        try {
            assertEquals(project.toString(), ToolExecutor.normalizeProjectDir(Map.of("projectDir", "/no/such/dir")).get("projectDir"));
            assertEquals(project.resolve("service").toString(),
                    ToolExecutor.normalizeProjectDir(Map.of("projectDir", "./service")).get("projectDir"));
            assertEquals(project.toString(), ToolExecutor.normalizeProjectDir(Map.of("projectDir", project.toString())).get("projectDir"));
            assertEquals(Map.of("file", "x"), ToolExecutor.normalizeProjectDir(Map.of("file", "x")));
        } finally {
            System.clearProperty("hermit.projectDir");
        }
    }

    // after the composer wrote the suite, three plans re-located it instead of committing
    @Test
    void aPlanThatOnlyLocatesTheWrittenFileIsARepeat() {
        String written = "/w/service/spec-tests/src/main/java/com/example/scenarios/LabelPrintSpec.java";
        assertTrue(RepeatGuard.locatesOnly(List.of(new Action("LocateFile", new HashMap<>(Map.of("name",
                "LabelPrintSpec.java", "root", "/w")))), written));
        assertTrue(RepeatGuard.locatesOnly(List.of(new Action("LocateFile", new HashMap<>(Map.of("name",
                "*PrintSpec.java")))), written));
        assertFalse(RepeatGuard.locatesOnly(List.of(new Action("LocateFile", new HashMap<>(Map.of("name", "Other.java")))), written));
        assertFalse(RepeatGuard.locatesOnly(List.of(new Action("Bash", new HashMap<>(Map.of("command", "git add x")))), written));
        assertFalse(RepeatGuard.locatesOnly(List.of(), written));
    }

    // ReadFile and exec were planned, failed with "Tool not found" and were planned again
    @Test
    void anUnregisteredToolIsRefusedBeforeItRunsWithTheRealNames() {
        Set<String> tools = Set.of("Bash", "LocateFile", "CodeGraph");
        String issue = PlanGate.unknownToolIssue(List.of(new Action("ReadFile", new HashMap<>(Map.of("path", "x")))), tools);
        assertNotNull(issue);
        assertTrue(issue.contains("'ReadFile' is not a tool here"), issue);
        assertTrue(issue.contains("Bash tool"), issue);
        String exec = PlanGate.unknownToolIssue(List.of(new Action("exec", new HashMap<>(Map.of("command", "sed -n 1p f")))), tools);
        assertTrue(exec.contains("run through the Bash tool"), exec);
        assertNull(PlanGate.unknownToolIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command", "ls")))), tools));
        assertNull(PlanGate.unknownToolIssue(List.of(new Action("Bash#2", new HashMap<>(Map.of("command", "ls")))), tools));
    }

    // rewrite drive t061: a read refused as "already in CONTEXT SO FAR" after the earlier output had left the context
    // (cut to the output cap, or summarized away), and the model wrote a test class from memory. A covered read is
    // refused only while the lines it would show are still in the context.
    @Test
    void aCoveredReadWhoseLinesLeftTheContextIsAllowed() throws IOException {
        Path file = Files.createTempFile("covered", ".java");
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            source.append("    assertEquals(\"value number ").append(i).append("\", compute(").append(i).append("));\n");
        }
        Files.writeString(file, source);
        Set<String> done = Set.of("Bash|{command=sed -n '1,60p' " + file + ", timeoutSec=120}");
        List<Action> again = List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '10,40p' " + file))));
        assertNotNull(RepeatGuard.coveredReadIssue(again, done, false, source.toString()), "the lines are in the context");
        assertNull(RepeatGuard.coveredReadIssue(again, done, false, "[Earlier conversation summary] read the test"),
                "the lines left the context: reading them again is not a repeat");
        // rewrite drive t080b: the supervisor asked for the range again (the plan had written the method from memory),
        // and the gate refused the read four times; the planner then wrote it from memory again
        String asked = source + "\n[Supervisor refused the last plan; this still applies to the next plans] reject THIS plan: "
                + "only the read sed -n '10,40p' of the reference, then copy the method from it";
        assertNull(RepeatGuard.coveredReadIssue(again, done, false, asked), "the supervisor asked for exactly this read");
        assertNotNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '12,38p' " + file)))), done, false, asked), "another range is still a covered read");
    }

    // a sed read inside a range already read from the same file is a repeat
    @Test
    void aReadInsideAnAlreadyReadRangeIsARepeat() {
        Set<String> done = Set.of(
                "Bash|{command=sed -n '150,250p' /w/app/x/LibraryCatalog.java, timeoutSec=120}");
        String issue = RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '170,240p' /w/app/x/LibraryCatalog.java")))), done);
        assertNotNull(issue);
        assertTrue(issue.contains("inside the 150-250"), issue);
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '300,372p' /w/app/x/LibraryCatalog.java")))), done), "a new range is fine");
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '170,240p' /w/Other.java")))), done), "another file is fine");
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command", "git status")))), done));
        // a `$(git rev-parse --show-toplevel)/…` path names the same file
        Set<String> shellPath = Set.of(
                "Bash|{command=sed -n '300,380p' $(git rev-parse --show-toplevel)/app/x/LibraryCatalog.java, "
                        + "timeoutSec=120}");
        assertNotNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '300,360p' /w/app/x/LibraryCatalog.java")))), shellPath));
    }

    // the planner located LibraryCatalog#addCopy, then LibraryCatalog, then
    // LibraryCatalog.addCopy - each a re-locate of what it had located (qualified) and read at the start of the turn
    @Test
    void aLocateOfAnAlreadyLocatedTypeIsARepeat() {
        Map<String, String> done = new LinkedHashMap<>();
        done.put("CodeGraph|{action=locate, symbol=com.example.library.catalog.LibraryCatalog}", "Success");
        done.put("Bash|{command=sed -n '150,230p' $(git rev-parse --show-toplevel)/app/x/LibraryCatalog.java, "
                + "workingDir=/w}", "Success");
        String issue = RepeatGuard.coveredLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "LibraryCatalog"))), done);
        assertNotNull(issue);
        assertTrue(issue.contains("already located this turn"), issue);
        assertNotNull(RepeatGuard.coveredLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "LibraryCatalog#addCopy"))), done),
                "the type was located and its file read");
        assertNotNull(RepeatGuard.coveredLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "LibraryCatalog.addCopy"))), done), "dot form");
        assertNotNull(RepeatGuard.coveredLocateIssue(List.of(new Action("LocateFile", Map.of("name",
                "LibraryCatalog.java"))), done));
        assertNull(RepeatGuard.coveredLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol",
                "ShelfIndex#lookup"))), done),
                "a new symbol is fine");
        assertNull(RepeatGuard.coveredLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "callers", "symbol",
                "LibraryCatalog#addCopy"))), done),
                "another action on it is fine");
        assertNull(RepeatGuard.coveredLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol",
                "LibraryCatalog")),
                new Action("Bash", Map.of("command", "sed -n '400,450p' /w/LibraryCatalog.java"))), done),
                "a plan that also does something new may run");
        // a member locate after only the file was found, before any read of it, is new information; the type itself is not
        Map<String, String> onlyFile = new LinkedHashMap<>();
        onlyFile.put("LocateFile|{maxResults=5, name=LibraryCatalog.java}", "Success");
        assertNull(RepeatGuard.coveredLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol",
                "LibraryCatalog#addCopy"))), onlyFile));
        assertNotNull(RepeatGuard.coveredLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "LibraryCatalog"))), onlyFile));
        // a failed locate covers nothing; a glob covers nothing
        Map<String, String> failed = new LinkedHashMap<>();
        failed.put("CodeGraph|{action=locate, symbol=LibraryCatalog}", "Error");
        failed.put("LocateFile|{name=*Catalog.java}", "Success");
        assertNull(RepeatGuard.coveredLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol",
                "LibraryCatalog"))), failed));
    }

    // the capped view of a result says the rest is NOT in the context
    @Test
    void aCappedResultValueSaysTheRestIsNotInTheContext() {
        String big = "x".repeat(6000);
        String view = ResultSummaries.summarizeResults(Map.of("Bash", Map.of("status", "Success", "output", big)));
        assertTrue(view.contains("1500 more chars NOT in your context"), view);
        assertTrue(ResultSummaries.summarizeResults(Map.of("Bash", "x".repeat(2000))).contains("800 more chars NOT in your context"));
        // a 60-line read (under 4500 chars) arrives WHOLE; any other long entry is still cut at 600
        String read = "line\n".repeat(60) + "private boolean sameEdition(a, b) { return a.isbn().equals(b.isbn()); }";
        assertTrue(ResultSummaries.summarizeResults(Map.of("Bash", Map.of("status", "Success", "output",
                read))).contains("sameEdition(a, b)"));
        assertTrue(ResultSummaries.summarizeResults(Map.of("T", Map.of("methodText", "y".repeat(2000)))).contains("1400 more chars NOT in your context"));
    }

    // a read that is two thirds already read is a covered read naming the new lines
    @Test
    void aMostlyCoveredReadNamesTheLinesThatWouldBeNew() {
        Set<String> done = Set.of(
                "Bash|{command=sed -n '310,360p' /w/app/x/LibraryCatalog.java, timeoutSec=120}",
                "Bash|{command=sed -n '330,350p' $(git rev-parse --show-toplevel)/app/x/LibraryCatalog.java}");
        String creep = RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '320,380p' /w/app/x/LibraryCatalog.java")))), done);
        assertNotNull(creep);
        assertTrue(creep.contains("41 of 61") && creep.contains("only lines 361-380 would be new"), creep);
        String before = RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '300,360p' /w/app/x/LibraryCatalog.java")))), done);
        assertNotNull(before);
        assertTrue(before.contains("only lines 300-309 would be new"), before);
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '361,420p' /w/app/x/LibraryCatalog.java")))), done), "nothing of it was read");
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '200,400p' /w/app/x/LibraryCatalog.java")))), done), "a quarter read is a new read");
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '320,380p' /w/app/x/Other.java")))), done), "another file");
    }

    // `$(locate_file_output_path)` is a placeholder the shell cannot run
    @Test
    void aPlaceholderInAShellCommandIsRefusedWithTheRealPath() {
        Set<String> done = Set.of(
                "LocateFile|{name=LibraryCatalog.java, root=/w}",
                "Bash|{command=sed -n '190,220p' /w/app/x/LibraryCatalog.java, timeoutSec=120}");
        String issue = RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '300,440p' $(locate_file_output_path)"))), done);
        assertNotNull(issue);
        assertTrue(issue.contains("$(locate_file_output_path)") && issue.contains("/w/app/x/LibraryCatalog.java"),
                issue);
        assertNotNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "cat <path to file>"))), done));
        assertNotNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "sed -n '1,5p' $FILE_PATH"))), done));
        // real substitutions and real paths pass
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '300,440p' $(git rev-parse --show-toplevel)/app/x/LibraryCatalog.java"))), done));
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "cd $(pwd) && ls"))), done));
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "grep -n 'a<b>' X.java"))), done), "a generic type is not a placeholder");
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol", "<T>"))), done));
    }

    // a File.java:LINE inside a range this turn read is grounded, though sed prints no numbers
    @Test
    void aLineInsideAReadRangeIsGrounded() {
        Set<String> done = Set.of(
                "Bash|{command=sed -n '310,360p' /w/app/x/LibraryCatalog.java, timeoutSec=120}");
        assertTrue(RepeatGuard.groundedByARead("{file=LibraryCatalog.java:335, op=x}", done));
        assertTrue(RepeatGuard.groundedByARead("LibraryCatalog.java:310 and LibraryCatalog.java:360", done));
        assertFalse(RepeatGuard.groundedByARead("{file=LibraryCatalog.java:400}", done), "outside every read");
        assertFalse(RepeatGuard.groundedByARead("{file=Other.java:335}", done), "another file");
        assertFalse(RepeatGuard.groundedByARead("{file=LibraryCatalog.java:335, then=Other.java:5}", done),
                "one of two is not");
        assertFalse(RepeatGuard.groundedByARead("no address here", done));
        assertFalse(RepeatGuard.groundedByARead("{file=LibraryCatalog.java:335}", Set.of()));
    }

    // a file placeholder resolves to the ONE source file the turn has located
    @Test
    void aFilePlaceholderResolvesToTheOneKnownFile() {
        Set<String> one = Set.of(
                "LocateFile|{name=LibraryCatalog.java, root=/w}",
                "Bash|{command=sed -n '320,360p' /w/app/x/LibraryCatalog.java, timeoutSec=120}");
        List<Action> in = List.of(new Action("Bash", Map.of("command", "sed -n '300,420p' \"$(locate_file_output)\"")),
                new Action("CodeGraph", Map.of("action", "locate", "symbol", "A#b")));
        List<Action> out = PlanShaper.resolvePlaceholders(in, one);
        assertEquals("sed -n '300,420p' /w/app/x/LibraryCatalog.java", out.get(0).parameters().get("command"));
        assertEquals("A#b", out.get(1).parameters().get("symbol"));
        assertNull(RepeatGuard.placeholderCommandIssue(out, one), "nothing left to refuse");
        // two known files: ambiguous, unchanged and refused; a non-file placeholder: unchanged
        Set<String> two = Set.of(
                "Bash|{command=sed -n '1,5p' /w/x/A.java}", "Bash|{command=sed -n '1,5p' /w/x/B.java}");
        assertTrue(PlanShaper.resolvePlaceholders(in, two) == in);
        assertNotNull(RepeatGuard.placeholderCommandIssue(in, two));
        List<Action> other = List.of(new Action("Bash", Map.of("command", "echo $(some_value_here)")));
        assertTrue(PlanShaper.resolvePlaceholders(other, one) == other);
        assertNotNull(RepeatGuard.placeholderCommandIssue(other, one));
    }

    // the file named only inside `$(git ls-files | grep 'X.java')` is still that file
    @Test
    void aSedReadThroughAGitSubstitutionNamesItsFile() {
        Set<String> done = Set.of(
                "Bash|{command=sed -n '300,380p' /w/app/x/LibraryCatalog.java, timeoutSec=120}");
        assertNotNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '300,380p' $(git ls-files | grep 'LibraryCatalog.java')")))), done));
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '150,250p' $(git ls-files | grep 'LibraryCatalog.java')")))), done), "mostly new");
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '300,380p' $(git ls-files | grep 'Other.java')")))), done), "another file");
        // the same form as the EARLIER read grounds a later line address and covers a later read
        Set<String> viaGit = Set.of(
                "Bash|{command=sed -n '300,380p' $(git ls-files | grep 'LibraryCatalog.java'), timeoutSec=120}");
        assertTrue(RepeatGuard.groundedByARead("{file=LibraryCatalog.java:347}", viaGit));
        assertNotNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '320,360p' /w/app/x/LibraryCatalog.java")))), viaGit));
        assertEquals("LibraryCatalog.java", RepeatGuard.sedFileName("$(git ls-files | grep 'LibraryCatalog.java')"));
        assertEquals("A.java", RepeatGuard.sedFileName("/w/x/A.java"));
        assertNull(RepeatGuard.sedFileName(""));
    }

    // after a covered-read refusal, a widened overlapping read of the same file is covered
    @Test
    void aWidenedReadAfterACoveredReadRefusalIsStillCovered() {
        Set<String> done = Set.of(
                "Bash|{command=sed -n '190,220p' /w/app/x/LibraryCatalog.java, timeoutSec=120}");
        List<Action> widened = List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '150,260p' /w/app/x/LibraryCatalog.java"))));
        assertNull(RepeatGuard.coveredReadIssue(widened, done, false), "a quarter read passes on its own");
        String issue = RepeatGuard.coveredReadIssue(widened, done, true);
        assertNotNull(issue);
        assertTrue(issue.startsWith("Your last plan was refused as a covered read") && issue.contains("only lines 150-189, 221-260 would be new"), issue);
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '300,380p' /w/app/x/LibraryCatalog.java")))), done, true), "no overlap: a new read");
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "sed -n '150,260p' /w/app/x/Other.java")))), done, true), "another file");
    }

    // a sed read whose path is a shell variable names its file in the assignment before it
    @Test
    void aSedReadThroughAShellVariableNamesItsFile() {
        Set<String> done = Set.of(
                "Bash|{command=sed -n '150,230p' /w/app/x/LibraryCatalog.java, timeoutSec=120}",
                "Bash|{command=sed -n '300,380p' /w/app/x/LibraryCatalog.java, timeoutSec=120}");
        String cmd = "file=$(find . -type f -path \"*/catalog/LibraryCatalog.java\" | head -n 1); "
                + "echo '--- addCopy ---'; sed -n '196,209p' \"$file\"; "
                + "echo '--- sameEdition ---'; sed -n '335,357p' \"$file\"";
        String issue = RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command", cmd)))), done);
        assertNotNull(issue);
        assertTrue(issue.contains("196-209") && issue.contains("inside the 150-230"), issue);
        assertNull(RepeatGuard.coveredReadIssue(List.of(new Action("Bash", new HashMap<>(Map.of("command",
                "file=$(find . -name Other.java | head -n 1); sed -n '196,209p' \"$file\"")))), done), "another file");
        assertEquals("LibraryCatalog.java", RepeatGuard.sedFileName("\"$file\"", cmd));
        assertEquals("A.java", RepeatGuard.sedFileName("/w/A.java", "sed -n '1,2p' /w/A.java && cat B.java"), "the path itself wins");
    }

    // a refiner's rewrite that adds an editing step to an analysis-only task is not a refinement
    @Test
    void aRewriteThatAddsAnEditToAnAnalysisOnlyTaskIsRecognised() {
        ParsedPlan locate = new ParsedPlan(null, null, List.of(new Branch("look", "", 0.9,
                List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol", "PriceTagPrinter"))))));
        ParsedPlan edited = new ParsedPlan(null, null, List.of(new Branch("fix", "", 0.9, List.of(
                new Action("CodeGraph", Map.of("action", "locate", "symbol", "PriceTagPrinter")),
                new Action("TreeEdit", Map.of("file", "PriceTagPrinter.java", "op", "replaceStatement"))))));
        assertTrue(PlanGate.introducesEdits(locate, edited));
        assertFalse(PlanGate.introducesEdits(edited, edited), "the original already edited");
        assertFalse(PlanGate.introducesEdits(locate, locate));
        assertTrue(RepeatGuard.analysisOnly("Hard rules: NEVER git push; do NOT edit any source file; this is an analysis-only task."));
        assertTrue(RepeatGuard.analysisOnly("Read-only task: explain the flow."));
        assertFalse(RepeatGuard.analysisOnly("Write a failing test for the handler and commit it."));
        assertFalse(RepeatGuard.analysisOnly(null));
    }

    // tool names with empty parameters are not a plan
    @Test
    void anActionWithoutParametersIsRefused() {
        List<Action> empty = List.of(new Action("CodeQL", Map.of()), new Action("TreeEdit", Map.of()));
        String issue = RepeatGuard.parameterlessActionIssue(empty);
        assertNotNull(issue);
        assertTrue(issue.startsWith("'CodeQL' is planned with NO parameters"), issue);
        assertNotNull(RepeatGuard.parameterlessActionIssue(List.of(new Action("Bash", null))));
        assertNull(RepeatGuard.parameterlessActionIssue(List.of(new Action("Bash", Map.of("command", "ls")))));
        ParsedPlan plan = new ParsedPlan(null, null, List.of(new Branch("x", "", 0.9, empty)));
        assertNotNull(RepeatGuard.parameterlessActionIssue(plan));
        assertNull(RepeatGuard.parameterlessActionIssue((ParsedPlan) null));
    }

    // the strongest configured model authors corrective plans; the helper chain only when there is none
    @Test
    void theEscalationModelAuthorsWhenConfigured() {
        assertFalse(LoopSettings.authorViaHelpersFirst(true));
        assertTrue(LoopSettings.authorViaHelpersFirst(false));
    }

    // a refiner's rewrite that re-runs executed calls or shotguns lookups is no refinement
    @Test
    void aRewriteThatRepeatsOrShotgunsIsNotARefinement() {
        Map<String, String> done = new LinkedHashMap<>();
        Action loc = new Action("CodeGraph", Map.of("action", "locate", "symbol", "PlaylistShuffler#nextTrack"));
        Action missing = new Action("CodeGraph", Map.of("action", "locate", "symbol", "PlaylistShuffler#skipRepeat"));
        done.put(RepeatGuard.signature(loc), "Success");
        done.put(RepeatGuard.signature(missing), "Error");
        Action read = new Action("Bash", Map.of("command", "sed -n '30,60p' /w/PlaylistShuffler.java"));
        assertNotNull(PlanGate.rewriteNotARefinementIssue(plan(loc, read), done));
        assertTrue(PlanGate.rewriteNotARefinementIssue(plan(read, missing), done).contains("Error"));
        assertNull(PlanGate.rewriteNotARefinementIssue(plan(read), done), "a new read is a refinement");
        List<Action> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            many.add(new Action("CodeGraph", Map.of("action", "callers", "symbol", "T#m" + i)));
        }
        String shotgun = PlanGate.rewriteNotARefinementIssue(new ParsedPlan(null, null, List.of(new Branch("x", "", 0.9, many))), done);
        assertNotNull(shotgun);
        assertTrue(shotgun.contains("shotguns 7 actions"), shotgun);
        assertNull(PlanGate.rewriteNotARefinementIssue(null, done));
    }

    private static ParsedPlan plan(Action... actions) {
        return new ParsedPlan(null, null, List.of(new Branch("x", "", 0.9, List.of(actions))));
    }

    // a refiner whose rewrites keep being discarded stops rewriting for the turn
    @Test
    void aRefinerIsSwitchedOffAfterTwoDiscardedRewrites() {
        assertFalse(PlanGate.refinerExhausted(0));
        assertFalse(PlanGate.refinerExhausted(1));
        assertTrue(PlanGate.refinerExhausted(2));
        assertTrue(PlanGate.refinerExhausted(5));
    }

    // only a program this turn wrote is a "failing program" worth an expert consult
    @Test
    void anArbitraryCommandsFailureIsNotAProgramFailure() {
        Map<String, Object> bashFail = new LinkedHashMap<>(Map.of("status", "NonZeroExit", "exitCode", 2, "output", "grep: no match"));
        assertNull(RepairCoordinator.programFailure(Map.of("Bash", bashFail)), "a shell command's exit code is not a program to repair");
        Map<String, Object> gradleFail = new LinkedHashMap<>(Map.of("status", "NonZeroExit", "exitCode", 1, "buildSummary", "BUILD FAILED"));
        assertNull(RepairCoordinator.programFailure(Map.of("Bash#build", gradleFail)));
        assertTrue(RepeatGuard.analysisOnly("Hard rules: do NOT edit any source file; this is an analysis-only task."), "the call site skips the consult for these");
        Map<String, Object> written = new LinkedHashMap<>();
        written.put("status", "Success");
        written.put("file", "/tmp/hermit-prog-1/program.py");
        written.put("syntaxOk", false);
        written.put("syntaxError", "line 1: invalid syntax");
        assertNotNull(RepairCoordinator.programFailure(Map.of("WriteProgram", written)), "a program this turn wrote");
        Map<String, Object> ranWritten = new LinkedHashMap<>(Map.of("status", "NonZeroExit", "code", "print(1/0)", "stderr", "ZeroDivisionError"));
        assertNotNull(RepairCoordinator.programFailure(Map.of("RunProgram", ranWritten)));
    }

    // a recursive search rooted outside the repository, or at its whole root, is refused
    @Test
    void aRecursiveSearchOutsideTheModuleIsRefused() {
        String out = RepeatGuard.unscopedSearchIssue(List.of(new Action("Bash", Map.of("command",
                "grep -R \"roomIdByName\" -n .. | head"))));
        assertNotNull(out);
        assertTrue(out.contains("OUTSIDE the repository"), out);
        assertNotNull(RepeatGuard.unscopedSearchIssue(List.of(new Action("Bash", Map.of("command",
                "grep -rn getGuestName ~/work/hotel-service | head")))));
        assertNotNull(RepeatGuard.unscopedSearchIssue(List.of(new Action("Bash", Map.of("command",
                "find / -name BookingDesk.java")))));
        assertNotNull(RepeatGuard.unscopedSearchIssue(List.of(new Action("Bash", Map.of("command",
                "grep -R sameStay -n .")))), "the whole repository root");
        assertNull(RepeatGuard.unscopedSearchIssue(List.of(new Action("Bash", Map.of("command",
                "grep -rn 'sameStay' hotel/bookings/src/main/java")))), "a module is fine");
        assertNull(RepeatGuard.unscopedSearchIssue(List.of(new Action("Bash", Map.of("command",
                "grep -n sameStay /w/x/BookingDesk.java")))), "not recursive");
        assertNull(RepeatGuard.unscopedSearchIssue(List.of(new Action("Bash", Map.of("command",
                "find hotel/bookings -name '*.java'")))));
        assertNull(RepeatGuard.unscopedSearchIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol", "A")))));
    }

    // program tools are refused in an analysis-only request
    @Test
    void aProgramToolIsRefusedInAnAnalysisOnlyTask() {
        String analysis = "Hard rules: NEVER git push; do NOT edit any source file; this is an analysis-only task. Decide TRUE or FALSE POSITIVE.";
        String issue = RepeatGuard.programToolInAnalysisIssue(List.of(new Action("WriteProgram", Map.of("language", "python", "run", true))), analysis);
        assertNotNull(issue);
        assertTrue(issue.startsWith("'WriteProgram' writes or runs a program"), issue);
        assertNotNull(RepeatGuard.programToolInAnalysisIssue(List.of(new Action("RunProgram", Map.of("command", "java -cp x Y"))), analysis));
        assertNull(RepeatGuard.programToolInAnalysisIssue(List.of(new Action("Bash", Map.of("command", "sed -n '1,5p' A.java"))), analysis));
        assertNull(RepeatGuard.programToolInAnalysisIssue(List.of(new Action("WriteProgram", Map.of("language", "python"))), "Write a script that prints the fee table and run it."), "a programming task may write programs");
    }

    // a ruling written in prose is a final answer; F374: the ledger names the refused plan next to the reason
    @Test
    void aProseRulingIsAFinalAnswerAndTheLedgerNamesRefusedPlans() {
        String prose = "Verdict: FALSE POSITIVE. The cache key covers the user and the whole query, so two users "
                + "never share a page; the omitted fields decide only the page size.";
        assertTrue(ReviewPanel.isProseRuling(prose));
        assertFalse(ReviewPanel.isProseRuling("{\"final_answer\": \"FALSE POSITIVE\"}"), "JSON is parsed, not ruled");
        assertFalse(ReviewPanel.isProseRuling("FALSE POSITIVE"), "too short to be a ruling");
        assertFalse(ReviewPanel.isProseRuling("<|channel|>commentary to=Bash code<|message|>sed -n '1,5p' A.java"));
        assertFalse(ReviewPanel.isProseRuling("I will now read the file and then decide whether it is a true positive or a false positive later on."), "mentioned in passing, not stated");
        assertTrue(ReviewPanel.isProseRuling("**FALSE POSITIVE**\n\nThe purported collision does not lead to the "
                + "described mix-up of owners because the store rejects any colliding upload."));
        assertTrue(ReviewPanel.isProseRuling("(1) **TRUE POSITIVE**\n\n(2) **Reasoning:** the cache key omits the "
                + "user, so two users share one entry and the second sees the first's page."));
        Branch b = new Branch("look", "", 1.0, List.of(
                new Action("CodeGraph", Map.of("action", "locate", "symbol", "UploadStore#save")),
                new Action("Bash", Map.of("command", "sed -n '150,230p' /w/x/UploadStore.java", "timeoutSec", 120))));
        String s = ResultSummaries.summarizeBranch(b);
        assertTrue(s.startsWith("CodeGraph{action=locate, symbol=UploadStore#save}; Bash{command=sed -n"), s);
        assertEquals("final answer: \"Verdict: TRUE POSITIVE. x\"", ResultSummaries.summarizePlan(new ParsedPlan("Verdict: TRUE POSITIVE. x", null, null)));
        assertEquals("", PlanPromptBuilder.failureLedgerBlock(List.of()));
        assertEquals("", PlanPromptBuilder.failureLedgerBlock(null));
        String block = PlanPromptBuilder.failureLedgerBlock(List.of("CodeGraph{action=locate, symbol=A} — already located", "Bash{command=sed -n '1,5p' A.java} — covered read"));
        assertTrue(block.startsWith("\nFAILED SINCE YOUR LAST SUCCESS"), block);
        assertTrue(block.contains("1. CodeGraph{action=locate, symbol=A} — already located\n2. Bash{command=sed -n '1,5p' A.java} — covered read\n"), block);
        assertTrue(block.contains("NOT on this list"), block);
    }

    // a locate of a symbol a callers result already placed is refused with that place
    @Test
    void aLocateOfASymbolACallersResultAlreadyPlacedIsRefused() {
        String ctx = "[Iteration 2] Executed branch 'callers'. Tool results: {CodeGraph={status=Success, action=callers, "
                + "symbol=TemperatureLog#slotOf, callers=[{line=75, file=/w/x/HourlyReport.java, "
                + "caller=HourlyReport#average, receiverType=TemperatureLog}, {line=92, file=/w/x/AlarmPanel.java, "
                + "caller=AlarmPanel#check, receiverType=TemperatureLog}]}}";
        String issue = RepeatGuard.locatedByResultIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "HourlyReport#average"))), ctx);
        assertNotNull(issue);
        assertTrue(issue.contains("/w/x/HourlyReport.java, line 75")
                && issue.contains("sed -n '60,120p' /w/x/HourlyReport.java"), issue);
        assertNotNull(RepeatGuard.locatedByResultIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "com.example.thermostat.AlarmPanel.check"))), ctx), "qualified, dot form: the same symbol");
        assertNull(RepeatGuard.locatedByResultIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "Thermostat#setTarget"))), ctx), "a symbol no result placed");
        assertNull(RepeatGuard.locatedByResultIssue(List.of(new Action("CodeGraph", Map.of("action", "callers",
                "symbol", "HourlyReport#average"))), ctx), "only locates are covered by a placement");
        assertNull(RepeatGuard.locatedByResultIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "HourlyReport#average"))), ""), "no results yet");
        assertNull(RepeatGuard.locatedByResultIssue(null, ctx));
    }

    // a test grounded only by a callers entry with an unresolved receiver is refused
    @Test
    void aRecordingOfATestGroundedOnlyByAnUnresolvedReceiverEntryIsRefused() {
        String ctx = "[Iteration 2] Tool results: {CodeGraph={status=Success, action=callers, "
                + "symbol=InvoiceTotals#addLine, "
                + "callers=[{line=117, file=/w/billing/src/test/java/x/ReceiptPrinterTest.java, "
                + "caller=ReceiptPrinterTest#printsOneLinePerItem, receiverType=?}, "
                + "{line=41, file=/w/billing/src/main/java/x/InvoiceBuilder.java, caller=InvoiceBuilder#build, "
                + "receiverType=InvoiceTotals}]}}";
        Action record = new Action("HindsightDebugger", new LinkedHashMap<>(Map.of("testClass", "x.ReceiptPrinterTest",
                "testMethod", "printsOneLinePerItem", "trigger", "InvoiceTotals#addLine")));
        String issue = RepeatGuard.inventedTestMethodIssue(List.of(record), ctx);
        assertNotNull(issue);
        assertTrue(issue.contains("receiverType=?")
                && issue.contains("CodeGraph action=tests on 'InvoiceTotals#addLine'"), issue);
        String grounded = ctx
                + "\n[Iteration 3] Tool results: {Bash={status=Success, output=   83\t    "
                + "void printsOneLinePerItem() {\n class ReceiptPrinterTest}}";
        assertNull(RepeatGuard.inventedTestMethodIssue(List.of(record), grounded), "once a read shows the class, the recording is grounded (right or wrong, the tool will say)");
        assertTrue(RepeatGuard.groundedOnlyByUnresolvedReceivers(ctx, "ReceiptPrinterTest"));
        assertFalse(RepeatGuard.groundedOnlyByUnresolvedReceivers(ctx, "InvoiceBuilder"));
        assertFalse(RepeatGuard.groundedOnlyByUnresolvedReceivers(ctx, "NotThere"));
    }

    // a Hermit tool invoked as a shell command inside a substitution is refused
    @Test
    void aToolNameUsedAsAShellCommandIsRefused() {
        Set<String> tools = Set.of("LocateFile", "CodeGraph", "HindsightDebugger", "Bash");
        String issue = RepeatGuard.toolInShellIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '1,120p' $(LocateFile name=InvoiceTotals.java root=/w maxResults=1 "
                        + "| jq -r '.[0].absolute')"))), tools);
        assertNotNull(issue);
        assertTrue(issue.contains("runs the Hermit tool LocateFile as a shell command") && issue.contains("run LocateFile first"), issue);
        assertNotNull(RepeatGuard.toolInShellIssue(List.of(new Action("Bash", Map.of("command", "CodeGraph locate X#y"))), tools), "a tool as the command itself");
        assertNotNull(RepeatGuard.toolInShellIssue(List.of(new Action("Bash", Map.of("command", "cd /w && CodeGraph callers X#y"))), tools));
        assertNull(RepeatGuard.toolInShellIssue(List.of(new Action("Bash", Map.of("command", "sed -n '1,120p' /w/LocateFile.java"))), tools), "a file named like a tool is not the tool");
        assertNull(RepeatGuard.toolInShellIssue(List.of(new Action("Bash", Map.of("command", "grep -rn 'CodeGraph' /w/src | head"))), tools), "the word inside a quoted pattern is not a command");
        assertNull(RepeatGuard.toolInShellIssue(List.of(new Action("Bash", Map.of("command", "sed -n '30,80p' $(git rev-parse --show-toplevel)/x/A.java"))), tools));
        assertNull(RepeatGuard.toolInShellIssue(List.of(new Action("LocateFile", Map.of("name", "A.java"))), tools));
        assertNull(RepeatGuard.toolInShellIssue(null, tools));
        // a markdown document written through a quoted heredoc names `CodeGraph` in a code
        // span; the shell substitutes nothing inside a quoted heredoc, so its text runs no command
        assertNull(RepeatGuard.toolInShellIssue(List.of(new Action("Bash", Map.of("command",
                "cat > docs/g.md << 'EOF'\n# Graph\n\n`CodeGraph` indexes calls.\nCodeGraph builds the index.\nEOF\ngit add docs/g.md"))),
                tools), "a quoted heredoc's text is not run");
        assertNotNull(RepeatGuard.toolInShellIssue(List.of(new Action("Bash", Map.of("command",
                "cat > a.txt << 'EOF'\nx\nEOF\nCodeGraph locate X#y"))), tools), "a command after the heredoc still counts");
    }

    // a plan wrote Java source with a Python script instead of the tree editor
    @Test
    void aScriptThatWritesJavaSourceIsRefused() {
        Function<String, String> bash = c -> RepeatGuard.scriptWritesJavaIssue(
                List.of(new Action("Bash", Map.of("command", c))));
        assertNotNull(bash.apply("python3 - <<'PY'\nfrom pathlib import Path\nPath('src/A.java').write_text('class A {}')\nPY"));
        assertNotNull(bash.apply("cat > src/A.java << 'EOF'\nclass A {}\nEOF"));
        assertNotNull(bash.apply("printf 'class A {}\\n' > src/A.java"));
        assertNotNull(bash.apply("echo 'class A {}' >> src/A.java"));
        assertNotNull(RepeatGuard.scriptWritesJavaIssue(List.of(new Action("WriteProgram", Map.of("language", "bash",
                "code", "cat > src/A.java <<EOF\nclass A {}\nEOF")))));
        assertNull(bash.apply("printf 'package p.q;\\n' > src/A.java"), "the package line of a new file");
        assertNull(bash.apply("sed -e 's/x/y/' ref/A.java > src/A.java"), "a copy of an existing file");
        assertNull(bash.apply("cp ref/A.java src/A.java && git add src/A.java"));
        assertNull(bash.apply("python3 -c 'import sys; print(1)' && ./gradlew test"));
        assertNull(bash.apply("grep -n 'write' src/A.java"), "reading a Java file writes nothing");
    }

    // the script wrote the ledger; the .java files were named by the commands after it
    @Test
    void aScriptCountsAsWritingJavaOnlyWhenItsOwnCommandNamesTheFile() {
        Function<String, String> bash = c -> RepeatGuard.scriptWritesJavaIssue(
                List.of(new Action("Bash", Map.of("command", c))));
        assertNull(bash.apply("python3 -c \"import pathlib;p=pathlib.Path('docs/LEDGER.md');p.write_text(p.read_text()+'x')\""
                + " && ./gradlew test --offline -q && ./check src/A.java docs/LEDGER.md && git add src/A.java docs/LEDGER.md"
                + " && git commit -m \"Add A\""), "a script writing the ledger, the .java files named by later commands");
        assertNotNull(bash.apply("git status; python3 -c \"open('src/A.java','w').write('class A {}')\""),
                "the script's own command names the .java file");
        assertNotNull(bash.apply("python3 - <<'PY' && git add src/A.java\nfrom pathlib import Path\n"
                + "Path('src/A.java').write_text('class A {}')\nPY\ngit status"), "a here-document's body belongs to its script");
        assertEquals(List.of("python3 -c \"a; b && c\"", "git add x", "cat <<'EOF' \nb\na\nEOF", "sort", "echo done"),
                RepeatGuard.shellCommands("python3 -c \"a; b && c\" && git add x\ncat <<'EOF' | sort\nb\na\nEOF\necho done"));
        assertEquals(List.of("make 2>&1", "tail -1"), RepeatGuard.shellCommands("make 2>&1 | tail -1"));
    }

    // a test named only by the planner's own refused plan is not grounded
    @Test
    void groundingCountsToolResultsNotPlansOrRefusals() {
        String ctx = "[Iteration 1] Executed branch 'locate'. Tool results: {CodeGraph={status=Success, "
                + "action=locate, symbol=LoanFines#overdueCharge, "
                + "declarations=[{line=27, file=/w/x/fines/LoanFines.java}]}}\n"
                + "[Plan review REJECTED your last plan] Fix ALL of these: steps 4-6 assume LoanFinesTest exists "
                + "though no tool result has shown it.\n"
                + "FAILED SINCE YOUR LAST SUCCESS: 1. HindsightDebugger{testClass=x.LoanFinesTest} — guessed\n";
        String results = ResultSummaries.toolResultsOnly(ctx);
        assertTrue(results.contains("LoanFines.java") && !results.contains("LoanFinesTest"), results);
        Action record = new Action("HindsightDebugger", new LinkedHashMap<>(Map.of("testClass", "x.LoanFinesTest",
                "trigger", "LoanFines#overdueCharge")));
        assertNotNull(RepeatGuard.inventedTestMethodIssue(List.of(record), ResultSummaries.toolResultsOnly(ctx)), "named only by a plan and its refusal: a guess");
        String withResult = ctx
                + "[Iteration 3] Executed branch 'tests'. Tool results: {CodeGraph={status=Success, action=tests, "
                + "tests=[{class=LoanFinesTest, method=chargesPerDay}]}}\n";
        assertNull(RepeatGuard.inventedTestMethodIssue(List.of(record), ResultSummaries.toolResultsOnly(withResult)), "a result naming it grounds it");
        assertEquals("", ResultSummaries.toolResultsOnly(""));
        assertEquals("", ResultSummaries.toolResultsOnly(null));
    }

    // a success closes the ledger and restores the synthesis budget
    @Test
    void aSuccessClosesTheLedgerAndRestoresTheSynthesisBudget() {
        TurnSnapshot live = new TurnSnapshot();
        live.failedSteps.add("CodeGraph{action=locate, symbol=A} — already located");
        live.synthesizedAnswers = 2;
        live.repeatGates = 5;
        live.disputedClaims.add("the late charge is the daily rate only");
        ResultSummaries.succeeded(live);
        assertTrue(live.failedSteps.isEmpty());
        assertTrue(live.disputedClaims.isEmpty(), "F418: new evidence, the disputed claims are reviewed afresh");
        assertEquals(0, live.synthesizedAnswers, "an answer synthesized from more evidence is a different answer");
        assertEquals(5, live.repeatGates, "the repeat count is the turn's, not the evidence's");
    }

    // a camelCase, indexed, dotted result "expression" in $( ) is a placeholder
    @Test
    void aCamelCaseResultExpressionInASubstitutionIsAPlaceholder() {
        Set<String> none = Set.of();
        assertNotNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "sed -n '1,200p' $(locateFileResult[0].absolutePath)"))), none));
        assertNotNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "cat $(codeGraph.declarations[0].file)"))), none));
        assertNotNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "sed -n '30,80p' $(result.path)"))), none));
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "sed -n '30,80p' $(git rev-parse --show-toplevel)/x/A.java"))), none), "a real command with spaces is not an expression");
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "echo $(date +%s)"))), none));
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "sed -n '30,80p' /w/x/A.java"))), none));
    }

    // a locate of an ALL_CAPS constant is refused with the grep
    @Test
    void aLocateOfAConstantIsRefusedWithTheGrep() {
        String issue = RepeatGuard.constantLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "MAX_RETRIES"))));
        assertNotNull(issue);
        assertTrue(issue.contains("is a constant") && issue.contains("grep -rn \"MAX_RETRIES\""), issue);
        assertNotNull(RepeatGuard.constantLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "RetryPolicy.MAX_RETRIES")))), "a qualified constant");
        assertNotNull(RepeatGuard.constantLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol", "Extra#KEYS")))), "a short all-caps member");
        assertNull(RepeatGuard.constantLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol",
                "RetryPolicy#nextDelay")))));
        assertNull(RepeatGuard.constantLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol",
                "RetryPolicy")))));
        assertNull(RepeatGuard.constantLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate", "symbol",
                "com.x.RetryPolicy.Backoff")))), "a nested type");
        assertNull(RepeatGuard.constantLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "callers",
                "symbol", "MAX_RETRIES")))), "only locates");
        assertNull(RepeatGuard.constantLocateIssue(null));
    }

    // a read of an absolute path that does not exist is refused before it runs
    @Test
    void aReadOfAPathThatDoesNotExistIsRefused() throws Exception {
        Path root = Files.createTempDirectory("hermit-missing");
        Path dir = Files.createDirectories(root.resolve("billing/src/test/java/y"));
        Path real = dir.resolve("InvoiceCalculatorsTest.java");
        Files.writeString(real, "class InvoiceCalculatorsTest {}\n");
        String issue = RepeatGuard.missingFileReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '1,200p' " + dir.resolve("InvoiceTotalsTest.java")))), root);
        assertNotNull(issue);
        assertTrue(issue.contains("InvoiceTotalsTest.java, but no such file exists") && issue.contains(dir.toString()),
                issue);
        assertNull(RepeatGuard.missingFileReadIssue(List.of(new Action("Bash", Map.of("command", "sed -n '1,20p' " + real))), root), "the real file");
        assertNull(RepeatGuard.missingFileReadIssue(List.of(new Action("Bash", Map.of("command", "cat /etc/hosts"))), root), "outside the workbench: not judged");
        assertNull(RepeatGuard.missingFileReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '1,20p' billing/Missing.java"))), root), "a relative path is not judged here");
        assertNull(RepeatGuard.missingFileReadIssue(List.of(new Action("Bash", Map.of("command",
                "grep -rn LATE_CHARGE " + root))), root), "not a plain read");
        assertNull(RepeatGuard.missingFileReadIssue(null, root));
        assertNull(RepeatGuard.missingFileReadIssue(List.of(new Action("Bash", Map.of("command", "cat " + root + "/none.java"))), null));
    }

    // a locate of a made-up type is refused with the grep that would find the real one
    @Test
    void aLocateOfATypeNoResultNamedAndNoFileHoldsIsRefused() throws Exception {
        Path root = Files.createTempDirectory("hermit-types");
        Path dir = Files.createDirectories(root.resolve("shop/app/src/main/java/x/shipping"));
        Files.writeString(dir.resolve("RateTable.java"), "class RateTable {}\n");
        String results = "Tool results: {Bash={status=Success, output=x/shipping/ParcelPricer.java:41: "
                + "void addSurcharge(}}";
        String issue = RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "ShippingQuote#regionalRate"))), results, root);
        assertNotNull(issue);
        assertTrue(issue.contains("no source file ShippingQuote.java exists")
                && issue.contains("grep -rn \"regionalRate\""), issue);
        assertNull(RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "RateTable"))), results, root), "no result named it, but the file exists");
        Path deep = Files.createDirectories(root.resolve("shop/shipping-impl/src/main/java/"
                + "com/example/shop/shipping/zones/rates/tables/internal"));
        Files.writeString(deep.resolve("ZoneSurchargeTable.java"), "class ZoneSurchargeTable {}\n");
        assertNull(RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "com.example.shop.shipping.zones.rates.tables.internal.ZoneSurchargeTable#addSurcharge"))),
                results, root),
                "a class fourteen directories deep exists");
        Files.writeString(dir.resolve("RateTable.java"), "class RateTable { long express() { return 0; } }\n");
        String member = RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "RateTable#regionalRate"))), results, root);
        assertNotNull(member, "a made-up member on a real type");
        assertTrue(member.contains("declares no 'regionalRate'") && member.contains("grep -n \"regionalRate\""),
                member);
        assertNull(RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "RateTable#express"))), results, root), "a member the file declares");
        assertNull(RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "ParcelPricer#addSurcharge"))), results, root), "a result named it");
        String nameOnly = results
                + "\nTool results: {CodeGraph={status=Success, action=callers, callers=[{line=117, "
                + "file=/w/x/LabelPrinterTest.java, caller=LabelPrinterTest#printsTheZoneOnEveryLabel, "
                + "receiverType=?}]}}";
        String trail = RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "LabelPrinterTest"))), nameOnly, root);
        assertNotNull(trail, "named only by an unresolved-receiver entry");
        assertTrue(trail.contains("receiverType=?") && trail.contains("Do not follow that trail"), trail);
        String resolved = nameOnly
                + "\nTool results: {Bash={status=Success, output=/w/x/LabelPrinterTest.java:83: "
                + "void printsTheZoneOnEveryLabel()}}";
        assertNull(RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "LabelPrinterTest"))), resolved, root), "a grep hit names it too: grounded");
        assertNull(RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "locate",
                "symbol", "MAX_RETRIES"))), results, root), "a constant is another check's");
        assertNull(RepeatGuard.unknownTypeLocateIssue(List.of(new Action("CodeGraph", Map.of("action", "callers", "symbol", "Nowhere#x"))), results, root), "only locates");
        assertNull(RepeatGuard.unknownTypeLocateIssue(null, results, root));
    }

    // a read longer than the view refused with the range around the grep hits
    @Test
    void anOversizedReadIsRefusedWithTheRangeAroundTheGrepHits() {
        String results = "Tool results: {Bash={status=Success, output=/w/shipping/RateTable.java:122:"
                + "        final int zones = route.zoneCount();\n"
                + "/w/shipping/RateTable.java:133:            addZoneSurcharge(quote, zones, weight);}}";
        String issue = RepeatGuard.oversizedReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '1,200p' /w/shipping/RateTable.java"))), results);
        assertNotNull(issue);
        assertTrue(issue.contains("asks for 200 lines")
                && issue.contains("sed -n '112,163p' /w/shipping/RateTable.java"), issue);
        String noHits = RepeatGuard.oversizedReadIssue(List.of(new Action("Bash", Map.of("command", "sed -n '1,200p' /w/x/Other.java"))), results);
        assertNotNull(noHits);
        assertTrue(noHits.contains("at most 60 lines"), noHits);
        assertNull(RepeatGuard.oversizedReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '116,160p' /w/shipping/RateTable.java"))), results), "45 lines: fine");
        assertNull(RepeatGuard.oversizedReadIssue(List.of(new Action("Bash", Map.of("command", "sed -n '1,100p' /w/x/A.java"))), results), "a hundred lines still arrive");
        assertNull(RepeatGuard.oversizedReadIssue(List.of(new Action("Bash", Map.of("command", "grep -n foo /w/x/A.java"))), results));
        assertNull(RepeatGuard.oversizedReadIssue(null, results));
    }

    // an action the code graph does not have is refused with the list and the right tool
    @Test
    void anUnknownCodeGraphActionIsRefusedBeforeItRuns() {
        String issue = RepeatGuard.unknownActionIssue(List.of(new Action("CodeGraph", new LinkedHashMap<>(Map.of(
                "action", "read", "file", "/w/x/RecipeScaler.java", "lineStart", 30, "lineEnd", 70)))));
        assertNotNull(issue);
        assertTrue(issue.contains("no action 'read'") && issue.contains("sed -n 'A,Bp' /w/x/RecipeScaler.java"), issue);
        assertNotNull(RepeatGuard.unknownActionIssue(List.of(new Action("CodeGraph", Map.of("action", "grep", "symbol", "X")))));
        String search = RepeatGuard.unknownActionIssue(List.of(new Action("CodeGraph", Map.of("action", "search",
                "symbol", "RECIPE_SERVINGS_DEFAULT"))));
        assertNotNull(search);
        assertTrue(search.contains("grep -rn \"RECIPE_SERVINGS_DEFAULT\"") && search.contains("--include=*.json"),
                "the search refusal carries the grep: " + search);
        for (String ok : List.of("locate", "callers", "callees", "reach", "tests", "impls", "escapes", "catches", "impact", "emulate",
                "whichtests", "specreach")) {
            assertNull(RepeatGuard.unknownActionIssue(List.of(new Action("CodeGraph", Map.of("action", ok, "symbol", "X#y")))), ok);
        }
        assertNull(RepeatGuard.unknownActionIssue(List.of(new Action("CodeGraph", Map.of("symbol", "X#y")))), "no action given: the tool's own default applies");
        assertNull(RepeatGuard.unknownActionIssue(List.of(new Action("Bash", Map.of("command", "sed -n '30,70p' /w/x/A.java")))));
        assertNull(RepeatGuard.unknownActionIssue(null));
    }

    // a read whose file operand comes out of a shell lookup is refused before it runs
    @Test
    void aReadWhoseFileComesFromAShellLookupIsRefused() {
        String issue = RepeatGuard.derivedPathReadIssue(List.of(
                new Action("Bash", Map.of("command", "sed -n '30,80p' $(ls -1 **/RecipeScaler.java | head -n1)"))));
        assertNotNull(issue);
        assertTrue(issue.contains("$(ls …)") && issue.contains("LITERAL absolute path"), issue);
        assertNotNull(RepeatGuard.derivedPathReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '1,120p' $(ls -1 /w/repo/**/RecipeScaler.java | head -n1)")))));
        assertNotNull(RepeatGuard.derivedPathReadIssue(List.of(new Action("Bash", Map.of("command",
                "cat \"$(find /w/repo -name MealPlanner.java | head -1)\"")))));
        assertNotNull(RepeatGuard.derivedPathReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '300,380p' $(git ls-files | grep 'MealPlanner.java')")))));
        assertNotNull(RepeatGuard.derivedPathReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '30,80p' \"$(cat /tmp/locate_output_path | head -n1)\"")))), "a path read out of a file nothing wrote");
        assertNull(RepeatGuard.derivedPathReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '150,250p' $(git rev-parse --show-toplevel)/kitchen/x/MealPlanner.java")))),
                "the repository root is not a lookup");
        assertNull(RepeatGuard.derivedPathReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '30,80p' /w/repo/kitchen/x/RecipeScaler.java")))), "a literal path");
        assertNull(RepeatGuard.derivedPathReadIssue(List.of(new Action("Bash", Map.of("command",
                "ls -1 /w/repo/kitchen")))), "a lookup on its own is fine");
        assertNull(RepeatGuard.derivedPathReadIssue(List.of(new Action("LocateFile", Map.of("name", "X.java")))));
        assertNull(RepeatGuard.derivedPathReadIssue(null));
    }

    // the same call listed twice in one plan runs once
    @Test
    void aCallListedTwiceInOnePlanRunsOnce() {
        Action callers = new Action("CodeGraph", new LinkedHashMap<>(Map.of("action", "callers", "symbol",
                "PhotoAlbum#resizeThumbnails")));
        Action callersAgain = new Action("CodeGraph", new LinkedHashMap<>(Map.of("symbol",
                "PhotoAlbum#resizeThumbnails", "action", "callers")));
        Action grep = new Action("Bash", new LinkedHashMap<>(Map.of("command",
                "grep -rn resizeThumbnails /w/gallery --include=*.java | head -40")));
        assertEquals(List.of(callers, grep), PlanShaper.withoutIntraPlanDuplicates(List.of(callers, callersAgain, grep)), "the second copy goes, order kept");
        List<Action> distinct = List.of(callers, grep);
        assertSame(distinct, PlanShaper.withoutIntraPlanDuplicates(distinct), "nothing to drop: the same instance");
        assertSame(null, PlanShaper.withoutIntraPlanDuplicates(null));
    }

    // succeeded repeats are dropped from a plan that also holds new steps
    @Test
    void succeededRepeatsAreDroppedFromAPlanThatAlsoHoldsNewSteps() {
        Action locateFile = new Action("LocateFile", new LinkedHashMap<>(Map.of("name", "WeatherStation.java", "root",
                "/w")));
        Action locate = new Action("CodeGraph", new LinkedHashMap<>(Map.of("action", "locate", "symbol",
                "WeatherStation#dailyHigh")));
        Action read = new Action("Bash", new LinkedHashMap<>(Map.of("command",
                "sed -n '30,80p' /w/WeatherStation.java")));
        Action failedRead = new Action("Bash", new LinkedHashMap<>(Map.of("command", "sed -n '1,5p' /w/Missing.java")));
        Map<String, String> executed = new LinkedHashMap<>();
        executed.put(RepeatGuard.signature(locateFile), "Success");
        executed.put(RepeatGuard.signature(locate), "Success");
        executed.put(RepeatGuard.signature(failedRead), "Error");
        assertEquals(List.of(read), PlanShaper.withoutSucceededRepeats(List.of(locateFile, locate, read), executed), "the two succeeded lookups go, the read stays");
        List<Action> allRepeats = List.of(locateFile, locate);
        assertEquals(List.of(), PlanShaper.withoutSucceededRepeats(allRepeats, executed), "nothing but repeats: empty, and the caller refuses the plan");
        List<Action> freshOnly = List.of(read, failedRead);
        assertSame(freshOnly, PlanShaper.withoutSucceededRepeats(freshOnly, executed), "a failed call may be retried; nothing dropped, same instance");
        assertSame(freshOnly, PlanShaper.withoutSucceededRepeats(freshOnly, Map.of()));
    }

    // the calls already made this turn are listed at the prompt's end with their outcome
    @Test
    void theCallsAlreadyExecutedThisTurnAreListedWithTheirOutcome() {
        assertEquals("", PlanPromptBuilder.executedBlock(Map.of()));
        assertEquals("", PlanPromptBuilder.executedBlock(null));
        Map<String, String> executed = new LinkedHashMap<>();
        executed.put("CodeGraph|{action=locate, symbol=WeatherStation#dailyHigh}", "Success");
        executed.put("CodeGraph|{action=callers, symbol=WeatherStation#dailyHigh}", "Success");
        executed.put("Bash|{command=sed -n '1,5p' /w/Missing.java}", "Error");
        String block = PlanPromptBuilder.executedBlock(executed);
        assertTrue(block.startsWith("\nALREADY EXECUTED THIS TURN"), block);
        assertTrue(block.contains("1. CodeGraph {action=locate, symbol=WeatherStation#dailyHigh} → Success\n"
                + "2. CodeGraph {action=callers, symbol=WeatherStation#dailyHigh} → Success\n"
                + "3. Bash {command=sed -n '1,5p' /w/Missing.java} → Error\n"), block);
        assertTrue(block.contains("native tool call"), block);
        // only the last twenty are listed
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 1; i <= 25; i++) {
            many.put("Bash|{command=echo " + i + "}", "Success");
        }
        String tail = PlanPromptBuilder.executedBlock(many);
        assertFalse(tail.contains("echo 5}"), tail);
        assertTrue(tail.contains("1. Bash {command=echo 6} → Success") && tail.contains("20. Bash {command=echo 25} → Success"), tail);
    }

    // a `$(git rev-parse --show-toplevel)` root is one operand, and the whole repository
    @Test
    void aSubstitutedRepositoryRootIsOneOperandAndTheWholeRepository() {
        String[] w = RepeatGuard.shellWords("grep -R --line-number \"WindSpeed\" $(git rev-parse --show-toplevel)");
        assertEquals(5, w.length, String.join("|", w));
        assertEquals("$(git rev-parse --show-toplevel)", w[4]);
        assertEquals("\"Weekly Report\"", RepeatGuard.shellWords("grep -rn \"Weekly Report\" src")[2]);
        String issue = RepeatGuard.unscopedSearchIssue(List.of(new Action("Bash", Map.of("command",
                "grep -R --line-number \"WindSpeed\" $(git rev-parse --show-toplevel) | head -n 5"))));
        assertNotNull(issue);
        assertTrue(issue.contains("WHOLE repository"), issue);
        assertNull(RepeatGuard.unscopedSearchIssue(List.of(new Action("Bash", Map.of("command",
                "grep -rn WindSpeed $(git rev-parse --show-toplevel)/weather/station-impl/src/main/java")))),
                "a module below the root is fine");
    }

    // a recording of a guessed test method is refused with the way to find a real one
    @Test
    void aRecordingOfAGuessedTestMethodIsRefused() {
        String ctx = "[Iteration 1] LocateFile: "
                + "matches=[{absolute=/w/library/x/src/test/java/com/example/library/loans/LoanServiceTest.java}]\n"
                + "[Iteration 2] Bash: output=42:    @Test\n43:    void lendsACopyAndReturnsTheDueDate() {";
        Map<String, Object> guessed = Map.of("testClass", "com.example.library.loans.LoanServiceTest", "testMethod",
                "lendCopy", "action", "recordExecution");
        String issue = RepeatGuard.inventedTestMethodIssue(List.of(new Action("HindsightDebugger", guessed)), ctx);
        assertNotNull(issue);
        assertTrue(issue.contains("testMethod 'lendCopy'") && issue.contains("@Test"), issue);
        Map<String, Object> shown = Map.of("testClass", "com.example.library.loans.LoanServiceTest", "testMethod",
                "lendsACopyAndReturnsTheDueDate", "action", "recordExecution");
        assertNull(RepeatGuard.inventedTestMethodIssue(List.of(new Action("HindsightDebugger", shown)), ctx), "the grep showed it");
        Map<String, Object> otherClass = Map.of("testClass", "com.example.library.OtherTest", "testMethod",
                "lendsACopyAndReturnsTheDueDate");
        assertTrue(RepeatGuard.inventedTestMethodIssue(List.of(new Action("HindsightDebugger", otherClass)), ctx).contains("testClass"));
        assertNull(RepeatGuard.inventedTestMethodIssue(List.of(new Action("Bash", Map.of("command", "ls"))), ctx));
    }

    // an incidental parameter (projectDir, maxDepth) does not make a new call
    @Test
    void anIncidentalParameterDoesNotMakeANewCall() {
        Action first = new Action("CodeGraph", Map.of("action", "callers", "symbol", "Gradebook#averageFor",
                "projectDir", "/w"));
        Action again = new Action("CodeGraph", Map.of("action", "callers", "symbol", "Gradebook#averageFor"));
        assertEquals(RepeatGuard.signature(first), RepeatGuard.signature(again));
        Map<String, String> done = new LinkedHashMap<>();
        done.put(RepeatGuard.signature(first), "Success");
        assertNotNull(RepeatGuard.repeatIssue(List.of(again), done));
        Action other = new Action("CodeGraph", Map.of("action", "callees", "symbol", "Gradebook#averageFor"));
        assertNull(RepeatGuard.repeatIssue(List.of(other), done), "a different action is a new call");
    }

    // a read of a bare file name the repository root does not hold is refused with the real path
    @Test
    void aReadOfABareFileNameIsRefusedWithTheKnownPath() throws IOException {
        Path root = Files.createTempDirectory("hermit-root");
        Files.writeString(root.resolve("README.md"), "# here");
        Set<String> done = Set.of("CodeGraph|{action=locate, symbol=Gradebook}, file=/w/x/impl/Gradebook.java");
        String issue = RepeatGuard.bareFileNameReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '50,110p' Gradebook.java"))), done, root);
        assertNotNull(issue);
        assertTrue(issue.contains("/w/x/impl/Gradebook.java"), issue);
        String unknown = RepeatGuard.bareFileNameReadIssue(List.of(new Action("Bash", Map.of("command", "cat Other.java"))), done, root);
        assertTrue(unknown != null && unknown.contains("Locate it first"), unknown);
        assertNull(RepeatGuard.bareFileNameReadIssue(List.of(new Action("Bash", Map.of("command", "sed -n '1,5p' README.md"))), done, root), "a file the root holds");
        assertNull(RepeatGuard.bareFileNameReadIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '1,5p' /w/x/impl/Gradebook.java"))), done, root), "a path");
        assertNull(RepeatGuard.bareFileNameReadIssue(List.of(new Action("Bash", Map.of("command", "ls -la"))), done, root));
    }

    // a variable holding "another action's result" exists in no shell
    @Test
    void anImaginaryResultVariableIsAPlaceholder() {
        Set<String> done = Set.of("CodeGraph|{action=locate, symbol=X}, file=/w/x/Gradebook.java");
        String issue = RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '30,80p' \"$(jq -r '.file' <<< \"${CODEGRAPH_RESULT}\")\""))), done);
        assertNotNull(issue);
        assertTrue(issue.contains("CODEGRAPH_RESULT"), issue);
        assertNotNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "cat $LOCATE_OUTPUT"))), done));
        assertNotNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "sed -n '1,5p' ${TARGET_FILE}"))), done));
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "ls $HOME/projects"))), done), "a real variable");
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command", "echo $JAVA_HOME && ls $PWD"))), done));
        assertNull(RepeatGuard.placeholderCommandIssue(List.of(new Action("Bash", Map.of("command",
                "sed -n '1,5p' /w/x/Gradebook.java"))), done));
    }

    // an authored eight-step plan was refused whole because its LAST step named an
    // unseen test class; the plan is cut to the clean steps before the first refused one
    @Test
    void aMultiStepPlanIsCutToTheStepsBeforeTheFirstRefusedOne() {
        String root = "/w/garden";
        String ctx = "[Iteration 1] CodeGraph: {status=Success, declarations=[{file=" + root
                + "/garden-app/src/main/java/com/example/garden/WateringPlan.java}]}";
        List<Action> plan = List.of(
                new Action("LocateFile", new HashMap<>(Map.of("name", "WateringPlan.java", "root", root))),
                new Action("CodeGraph", new HashMap<>(Map.of("action", "callers", "symbol", "WateringPlan#litresFor"))),
                new Action("CodeGraph", new HashMap<>(Map.of("action", "reach", "symbol", "WateringPlan#litresFor"))),
                new Action("HindsightDebugger", new HashMap<>(Map.of("testClass", "com.example.garden.SprinklerTest",
                        "trigger", "WateringPlan#litresFor"))),
                new Action("Bash", new HashMap<>(Map.of("command", "grep -R -n litresFor " + root
                        + "/garden-app | head -n 20"))));
        Function<List<Action>, String> gate = acts -> RepeatGuard.inventedTestMethodIssue(acts, ctx);
        assertNotNull(gate.apply(plan), "the whole plan is refused for its fourth step");
        List<Action> prefix = PlanShaper.cleanPrefix(plan, gate);
        assertEquals(3, prefix.size(), "the three steps before the refused one run");
        assertEquals(plan.subList(0, 3), prefix);
        // the refused step first: nothing to run
        assertEquals(List.of(), PlanShaper.cleanPrefix(List.of(plan.get(3), plan.get(0)), gate));
        // one refused step alone: nothing to run, and never the whole plan back
        assertEquals(List.of(), PlanShaper.cleanPrefix(List.of(plan.get(3)), gate));
    }
}
