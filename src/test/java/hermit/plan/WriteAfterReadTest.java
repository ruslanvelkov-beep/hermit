package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import hermit.review.ReviewPanel;
import hermit.runtime.ResultSummaries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A plan that reads files and then writes one in the same plan is cut after its reads. */
class WriteAfterReadTest {

    static Action bash(String command) {
        return new Action("Bash", Map.of("command", command));
    }

    @Test
    void aWriteAfterAReadInTheSamePlanIsFound() {
        assertEquals(1, RepeatGuard.writeAfterRead(List.of(
                bash("sed -n '715,777p' /work/plan.md"),
                bash("cat > docs/GUIDE.md <<'EOF'\n# Guide\nEOF"))));
        assertEquals(2, RepeatGuard.writeAfterRead(List.of(
                bash("cd /work && grep -n '^# ' plan.md"),
                bash("head -40 notes.md"),
                bash("cat <<'EOF' > docs/A.md\ntext\nEOF"))));
        assertEquals(1, RepeatGuard.writeAfterRead(List.of(
                bash("cat /work/a.txt"),
                bash("printf 'x' | tee -a log.txt"))));
    }

    @Test
    void aReadOnlyPlanIsNotFaultedForNotWritingYet() {
        String reads = "{\"branches\": [{\"actions\": [{\"tool\": \"Bash\", \"parameters\": {\"command\": \"sed -n '700,760p' /work/plan.md\"}}]}]}";
        String writes = "{\"branches\": [{\"actions\": [{\"tool\": \"Bash\", \"parameters\": {\"command\": \"cat > docs/A.md <<'EOF'\\nx\\nEOF\"}}]}]}";
        String commits = "{\"branches\": [{\"actions\": [{\"tool\": \"Bash\", \"parameters\": {\"command\": \"git add docs/A.md && git commit -m 'Add A'\"}}]}]}";
        assertTrue(PlanGate.readsOnly(reads));
        assertFalse(PlanGate.readsOnly(writes));
        assertFalse(PlanGate.readsOnly(commits));
        assertFalse(PlanGate.readsOnly("{\"branches\": [{\"actions\": [{\"tool\": \"TreeEdit\", \"parameters\": {}}]}]}"));
        assertTrue(ReviewPanel.demandsTheWriteStep("[completeness] The plan only includes read actions (sed) for the master plan "
                + "ranges but fails to include the action to write the docs/TDD-WORKFLOW.md file using Bash heredocs"));
        assertTrue(ReviewPanel.demandsTheWriteStep("[completeness] The plan is incomplete for the current task, which requires both "
                + "reading the source material and producing the output file in one go"));
        assertFalse(ReviewPanel.demandsTheWriteStep("[grounding] The sed range 700,760 does not exist in the file."));
    }

    @Test
    void writesWithoutAReadBeforeThemAndReadsAfterThemAreLeftAlone() {
        assertEquals(0, RepeatGuard.writeAfterRead(List.of(
                bash("mkdir -p docs && cat > docs/A.md <<'EOF'\npart one\nEOF"),
                bash("cat >> docs/A.md <<'EOF'\npart two\nEOF"),
                bash("test -s docs/A.md && git add docs/A.md && git commit -m 'Add A'"))));
        assertEquals(0, RepeatGuard.writeAfterRead(List.of(
                bash("cat > docs/A.md <<'EOF'\ntext\nEOF"),
                bash("sed -n '1,20p' docs/A.md"))));
        assertEquals(0, RepeatGuard.writeAfterRead(List.of(
                new Action("LocateFile", Map.of("name", "A.java")),
                bash("cat > docs/A.md <<'EOF'\ntext\nEOF"))), "only shell reads count");
    }

    @Test
    void aShellStepBashCannotParseIsRefusedBeforeItRuns() {
        String broken = "cd /work && \\\ncat > a.txt <<'EOF'\nx\nEOF\n&& \\\ncat > b.txt <<'EOF'\ny\nEOF";
        String issue = RepeatGuard.shellSyntaxIssue(List.of(bash("echo ok"), bash(broken)));
        assertTrue(issue != null && issue.startsWith("Step 2's shell command does not parse"), String.valueOf(issue));
        assertEquals(null, RepeatGuard.shellSyntaxIssue(List.of(
                bash("cd /work && mkdir -p d\ncat > a.txt <<'EOF'\nx && y\nEOF\ncat > b.txt <<'EOF'\ny\nEOF\ngit add a.txt b.txt"))));
    }

    @Test
    void aCriticsShellSyntaxClaimIsRecognised() {
        assertTrue(ReviewPanel.claimsShellSyntaxError("[review] Action 1's heredoc for `build.gradle.kts` ends with `EOF` followed by "
                + "a newline and then `cat > .gitignore <<'EOF'`. there is no command separator (`;` or `&&`) between them"));
        assertFalse(ReviewPanel.claimsShellSyntaxError("[grounding] The plan reads a file that does not exist."));
    }

    @Test
    void aReadThatPrintsMoreThanTheContextKeepsIsRefusedWithARangeThatFits() throws Exception {
        Path dir = Files.createTempDirectory("hermit-reads");
        Path big = dir.resolve("big.md");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("line ").append(i).append(" ").append("x".repeat(50)).append('\n');
        }
        Files.writeString(big, text);
        String issue = RepeatGuard.oversizedReadIssue(List.of(bash("cat big.md")), dir);
        assertTrue(issue != null && issue.contains("sed -n '1,"), String.valueOf(issue));
        assertEquals(null, RepeatGuard.oversizedReadIssue(List.of(bash("sed -n '1,40p' big.md")), dir));
        assertTrue(RepeatGuard.oversizedReadIssue(List.of(bash("sed -n '1,40p' big.md && sed -n '41,100p' big.md")), dir) != null,
                "two reads in one step share one result");
        assertEquals(null, RepeatGuard.oversizedReadIssue(List.of(bash("cat big.md | head -20")), dir), "a pipe filters the output");
        assertEquals(null, RepeatGuard.oversizedReadIssue(List.of(bash("cat missing.md")), dir));
        assertTrue(RepeatGuard.oversizedReadIssue(List.of(bash("sed -n '81,$p' big.md")), dir) != null, "a range to the end is measured");
        Files.writeString(dir.resolve("A.java"), "class A {\n    void a() {\n        x();\n    }\n" + text + "}\n");
        assertTrue(RepeatGuard.oversizedReadIssue(List.of(bash("sed -n '/void a/,/^ }/p' A.java")), dir) != null,
                "a pattern range whose end never matches runs to the end of the file, and is measured");
        assertEquals(null, RepeatGuard.oversizedReadIssue(List.of(bash("sed -n '/void a/,/^    }/p' A.java")), dir),
                "a pattern range that ends at the method's closing brace is small");
        assertTrue(ReviewPanel.demandsTheWriteStep("[grounding] The plan fails to follow the instruction 'Write after the reads "
                + "are in front of you', stopping at the read phase."));
        assertTrue(ReviewPanel.demandsTheWriteStep("[local-14b] The plan lacks the execution step to run the tests and verify the 'red' state"));
    }

    @Test
    void aCommandThatReadsAndThenWritesIsRefusedAndTheCriticWordingIsRecognised() {
        String issue = RepeatGuard.readThenWriteInOneCommandIssue(List.of(bash(
                "cat src/A.java && cat > src/A.java <<'EOF'\nclass A {}\nEOF")));
        assertTrue(issue != null && issue.startsWith("Step 1 reads files and then writes one"), String.valueOf(issue));
        assertEquals(null, RepeatGuard.readThenWriteInOneCommandIssue(List.of(bash(
                "mkdir -p src && cat > src/A.java <<'EOF'\nclass A {}\nEOF\ncat src/A.java"))), "a read after the write checks it");
        assertTrue(ReviewPanel.demandsTheWriteStep("[completeness] The plan only includes reading the source files but omits the "
                + "actual implementation of BranchSelector.java, the renaming of the test field in BranchSelectorTest.java"));
        // the necessity critic rejected the read-first plan, and the re-plan's edit was cut
        assertTrue(ReviewPanel.demandsTheWriteStep("[necessity] The plan only includes reading steps (grep/sed) but completely "
                + "omits the core task: invoking TreeCodegen with the six specified items to apply the changes."));
        assertFalse(ReviewPanel.demandsTheWriteStep("[necessity] The sed range omits the closing brace of parseCatch."));
        // task 037b: the demand named first, "missing" after it
        assertTrue(ReviewPanel.demandsTheWriteStep("[grounding] The request explicitly requires a final Bash command to run "
                + "specific Gradle tests, a neutral-check script, and git commit commands, which is missing from the plan."));
        assertFalse(ReviewPanel.demandsTheWriteStep("[grounding] The sed command is missing its file argument."));
    }

    @Test
    void anInPlaceEditOfAJavaFileIsSaidBeforeApproval() {
        String issue = RepeatGuard.inPlaceJavaEditIssue(List.of(bash(
                "cat > src/A.java <<'EOF'\nclass A {}\nEOF\nsed -i '' 's/a/b/g' src/ATest.java")));
        assertTrue(issue != null && issue.startsWith("Step 1 edits a .java file in place"), String.valueOf(issue));
        assertEquals(null, RepeatGuard.inPlaceJavaEditIssue(List.of(bash("sed -i '' 's/red/green/' docs/LEDGER.md"))));
        assertEquals(null, RepeatGuard.inPlaceJavaEditIssue(List.of(bash(
                "cat > docs/HOW.md <<'EOF'\nNever run sed -i on a .java file.\nEOF"))), "a document may mention it");
    }

    @Test
    void aRepairedPlanThatRunsNoProgramIsRecognised() {
        ParsedPlan empty = new ParsedPlan(null, null, List.of(new Branch("Run WriteProgram", "", 1.0,
                List.of(new Action("WriteProgram", Map.of("language", "python", "run", true))))));
        assertTrue(PlanGate.programlessRun(empty, null));
        assertFalse(PlanGate.programlessRun(empty, "print('x')"), "a fenced program out of band is a program");
        ParsedPlan withCode = new ParsedPlan(null, null, List.of(new Branch("run", "", 1.0,
                List.of(new Action("WriteProgram", Map.of("language", "bash", "program", "echo hi"))))));
        assertFalse(PlanGate.programlessRun(withCode, null));
        ParsedPlan shell = new ParsedPlan(null, null, List.of(new Branch("b", "", 1.0, List.of(bash("echo hi")))));
        assertFalse(PlanGate.programlessRun(shell, null));
    }

    @Test
    void theOutputCapIsAProperty() {
        String big = "x".repeat(9000);
        String defaultView = ResultSummaries.summarizeResults(Map.of("Bash", Map.of("output", big)));
        assertTrue(defaultView.contains("(4500 more chars NOT in your context)"), defaultView.substring(defaultView.length() - 80));
        System.setProperty("hermit.context.outputCap", "8000");
        try {
            String wider = ResultSummaries.summarizeResults(Map.of("Bash", Map.of("output", big)));
            assertTrue(wider.contains("(1000 more chars NOT in your context)"), wider.substring(wider.length() - 80));
            assertEquals(160, RepeatGuard.readLinesThatArrive());
        } finally {
            System.clearProperty("hermit.context.outputCap");
        }
        assertEquals(90, RepeatGuard.readLinesThatArrive());
    }

    // A consultant's 15,866-character plan reached the planner as its first 600 characters: only a read's `output` had
    // the output cap, and the answer, a program's stdout and the like were cut like any short entry.
    @Test
    void aConsultantsAnswerArrivesAsWholeAsAReadsOutput() {
        String plan = "p".repeat(15000);
        System.setProperty("hermit.context.outputCap", "20000");
        try {
            String view = ResultSummaries.summarizeResults(Map.of("BigBrother", Map.of("status", "Success", "answer", plan)));
            assertTrue(view.contains(plan) && !view.contains("NOT in your context"), view.substring(view.length() - 80));
            String run = ResultSummaries.summarizeResults(Map.of("RunProgram", Map.of("stdout", plan, "stderr", "e".repeat(900))));
            assertTrue(run.contains(plan) && run.contains("e".repeat(900)), run.substring(run.length() - 80));
            // a graph query's callers list and an edit's problems are what those calls were for, too
            String callers = "{caller=A#b, line=1}, ".repeat(150);
            assertTrue(ResultSummaries.summarizeResults(Map.of("CodeGraph", Map.of("callers", callers))).contains(callers));
            String problems = "[1:2] cannot resolve x; ".repeat(60);
            assertTrue(ResultSummaries.summarizeResults(Map.of("TreeEdit", Map.of("problems", problems))).contains(problems));
            String note = ResultSummaries.summarizeResults(Map.of("BigBrother", Map.of("note", "n".repeat(900))));
            assertTrue(note.contains("(300 more chars NOT in your context)"), note);
        } finally {
            System.clearProperty("hermit.context.outputCap");
        }
    }
}
