package hermit.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.plan.PlanPromptBuilder;
import hermit.review.ReviewPanel;
import hermit.runtime.ResultSummaries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Pure tests for the deterministic pre-check of the final-answer execution gate. */
class AnswerGateTest {

    @Test
    void noGateWhenNoSuccessfulResultYet() {
        // No tool has succeeded - a clarification or any answer is allowed through.
        assertNull(AnswerGate.deterministicAnswerGateIssue("Please provide the URL.", true, false));
        assertNull(AnswerGate.deterministicAnswerGateIssue("Please provide the URL.", false, false));
    }

    @Test
    void rejectsClarifyAfterASuccessfulResult() {
        String issue = AnswerGate.deterministicAnswerGateIssue(
                "Could you provide the exact URL?", true, true);
        assertNotNull(issue);
        assertTrue(issue.toLowerCase().contains("answer from that result"), issue);
    }

    @Test
    void rejectsFinalAnswerThatAsksForDataAlreadyFetched() {
        // The exact failure we saw: it had the PR diff but still asked for the URL.
        assertNotNull(AnswerGate.deterministicAnswerGateIssue(
                "I'm sorry, but I need you to provide the URL of the PR you want me to examine.", false, true));
        assertNotNull(AnswerGate.deterministicAnswerGateIssue(
                "Please provide the link to the pull request.", false, true));
    }

    @Test
    void fabricationGuardRejectsUnbackedCommitHashesAndTestVerdicts() {
        // the confabulated report - fake hashes + "test is green" with NO real commit/test.
        String confab = "Failing Test Commit: 8a2b3c4d5e6f7g8h. Fix Commit: mno1pqr2stu3vwx4.\n"
                + "Real Gradle Results: Before Fix the test is red, After Fix the test is green.";
        assertNotNull(AnswerGate.fabricationIssue(confab, false, false));

        // a hash claim alone, with no commit observed → rejected; mentions the missing commit
        String hashIssue = AnswerGate.fabricationIssue("Committed as a1b2c3d4.", false, false);
        assertNotNull(hashIssue);
        assertTrue(hashIssue.toLowerCase().contains("commit"), hashIssue);

        // FABRICATED non-hex placeholder hashes (g/h/i/j) must still be caught via the claim phrasing
        assertNotNull(AnswerGate.fabricationIssue("Commit hash: 1a2b3c4d5e6f7g8h9i0j", false, false));
        assertNotNull(AnswerGate.fabricationIssue(
                "Failing Test Commit: 8a2b3c4d5e6f7g8h. Fix Commit: mno1pqr2stu3vwx4.", false, false));

        // a verdict claim with no test observed → rejected
        assertNotNull(AnswerGate.fabricationIssue("The test now passes (green).", false, false));

        // once the ground truth is real, the SAME claims are allowed through
        assertNull(AnswerGate.fabricationIssue(confab, true, true));
        assertNull(AnswerGate.fabricationIssue("Committed as a1b2c3d4.", true, false));
        assertNull(AnswerGate.fabricationIssue("The test now passes (green).", false, true));

        // an honest incompleteness report with no hashes/verdicts is always fine
        assertNull(AnswerGate.fabricationIssue(
                "The task is INCOMPLETE: TreeCodegen could not import ReportArchiveService. "
                        + "No commit was made.", false, false));
    }

    @Test
    void rejectsFinalAnswerThatIsJustACommandTemplateWithPlaceholder() {
        assertNotNull(AnswerGate.deterministicAnswerGateIssue(
                "Run `gh pr view <exact-url> --json title,body,files` to see the PR.", false, true));
    }

    @Test
    void noToolGuard_flagsRequestsThatPlainlyNeedATool() {
        // The exact request that hallucinated "Update UserService..." with no tool run.
        assertNotNull(AnswerGate.noToolAnswerIssue(
                "Take a look at PR https://github.com/example/feed-reader/pull/500 and tell me its title."));
        assertNotNull(AnswerGate.noToolAnswerIssue("Does the file build.gradle.kts exist?"));
        assertNotNull(AnswerGate.noToolAnswerIssue("What does this PR change?"));
        assertNotNull(AnswerGate.noToolAnswerIssue("Read Foo.java and summarise it."));
        // External/current info → must go to the web, not memory.
        assertNotNull(AnswerGate.noToolAnswerIssue("What is the latest version of Selenium?"));
        assertNotNull(AnswerGate.noToolAnswerIssue("Search the web for the JUnit 5 user guide."));
        assertNotNull(AnswerGate.noToolAnswerIssue("Show me the release notes for Gradle 9."));
        // Real-time / live data must be fetched, not answered from memory.
        assertNotNull(AnswerGate.noToolAnswerIssue("What is the current BTC price?"));
        assertNotNull(AnswerGate.noToolAnswerIssue("Give me the live USD to EUR exchange rate."));
        assertNotNull(AnswerGate.noToolAnswerIssue("What's today's weather in Paris?"));
    }

    @Test
    void noToolGuard_exemptsConclusionsFromAlreadyGatheredEvidence() {
        // A verdict/conclusion drawn from evidence already in context is NOT a from-memory hallucination -
        // the guard must stay quiet (earlier bug: "do not run more tools" tripped the bare word "run").
        assertNull(AnswerGate.noToolAnswerIssue(
                "Give your final verdict on the report and do not run more tools."));
        assertNull(AnswerGate.noToolAnswerIssue(
                "You now have all the evidence. State the verdict from the context so far."));
        assertNull(AnswerGate.noToolAnswerIssue(
                "Based on the analysis you gathered, conclude whether it is a invalid report."));
        // But a genuine fetch/inspect request is still flagged (no false exemption):
        assertNotNull(AnswerGate.noToolAnswerIssue("Run the tests in :app and report the result."));
        assertNotNull(AnswerGate.noToolAnswerIssue("Inspect QueueProcessor.java and summarise it."));
    }

    @Test
    void webReadGuard_demandsAReadForCurrentFactAfterSearchOnly() {
        // Version question, search ran, no read → must force a read.
        assertNotNull(AnswerGate.webReadRequiredIssue(
                "what's the latest version of Selenium?", true, false));
        assertNotNull(AnswerGate.webReadRequiredIssue(
                "What is the current stable release of Gradle?", true, false));
    }

    @Test
    void webReadGuard_staysQuietWhenSatisfiedOrIrrelevant() {
        // A read already happened → fine.
        assertNull(AnswerGate.webReadRequiredIssue(
                "what's the latest version of Selenium?", true, true));
        // No web search ran at all → not this guard's concern.
        assertNull(AnswerGate.webReadRequiredIssue(
                "what's the latest version of Selenium?", false, false));
        // Not a current-fact question.
        assertNull(AnswerGate.webReadRequiredIssue(
                "Explain how a hash map works.", true, false));
    }

    @Test
    void noToolGuard_allowsGenuinelyDirectQuestions() {
        assertNull(AnswerGate.noToolAnswerIssue("Who are you and what are your capabilities?"));
        assertNull(AnswerGate.noToolAnswerIssue("What is 2 + 2?"));
        assertNull(AnswerGate.noToolAnswerIssue("Explain what a hash map is."));
    }

    @Test
    void allowsFinalAnswerThatReportsTheResult() {
        // A real answer that conveys the fetched substance is not gated by the
        // deterministic pre-check (it defers to the LLM gate, which returns null here).
        String realAnswer = "PR #500 'fix: make the date parser thread-safe' is OPEN by "
                + "example-dev; it changes 5 files (+174/-16) to drop a shared mutable formatter field.";
        assertNull(AnswerGate.deterministicAnswerGateIssue(realAnswer, false, true));
    }

    @Test
    void clarifyForADiscoverableFilePathIsRejected() {
        // the driver asked "Please provide the exact file path for FeedImporterTest.java." with
        // LocateFile/CodeGraph available - the stage produced nothing. Such asks are never necessary.
        String issue = AnswerGate.discoverableClarifyIssue(
                "Please provide the exact file path for FeedImporterTest.java.");
        assertNotNull(issue);
        assertTrue(issue.contains("LocateFile"), issue);
        assertNotNull(AnswerGate.discoverableClarifyIssue("Where is the test class located?"));
        assertNotNull(AnswerGate.discoverableClarifyIssue("Which module contains the handler?"));
        // genuinely undiscoverable asks pass through
        assertNull(AnswerGate.discoverableClarifyIssue("Which of the two fix designs do you prefer: cache the hash or store it at write time?"));
        assertNull(AnswerGate.discoverableClarifyIssue(null));
    }

    @Test
    void unverifiedCommitClaimsLeaveWithAnHonestyNote() {
        // after the bounded fabrication gate gives up, the claim must not leave unmarked
        String out = AnswerGate.withHonestyNote(
                "Committed the failing test as 922d74ae83a2017f167069b917ca0170c909005d.", false, false);
        assertTrue(out.contains("[Hermit honesty note"), out);
        assertTrue(out.contains("NOT verified"), out);
        String clean = AnswerGate.withHonestyNote("The analysis found the catch at line 212.", false, false);
        assertTrue(!clean.contains("honesty note"), clean);
        String real = AnswerGate.withHonestyNote("Committed as 922d74ae83.", true, false);
        assertTrue(!real.contains("honesty note"), "a real commit this session needs no note: " + real);
    }

    @Test
    void planShapedEndingsAreRejectedWhenResultsExist() {
        // "we need to identify… once this information is gathered, a unit test will be
        // chosen…" - a plan posing as the answer, with line 718 / the test path / NO HANDLER all in hand
        String plan = "To address this, we need to identify which exceptions can reach the catch block and "
                + "determine which test methods cover the path. Once this information is gathered, a unit test "
                + "will be chosen and a fix will be proposed.";
        assertNotNull(AnswerGate.planShapedAnswerIssue(plan));
        assertNotNull(AnswerGate.deterministicAnswerGateIssue(plan, false, true));
        assertNull(AnswerGate.deterministicAnswerGateIssue(plan, false, false), "no results yet → no gate");
        String real = "The catch is in FeedImporter.java:212; CodeGraph reach reports NO HANDLER on any "
                + "caller chain, so a unit test is the vehicle. Next we should check the template candidates.";
        assertNull(AnswerGate.planShapedAnswerIssue(real), "concrete facts + one forward-looking sentence is fine");
    }

    @Test
    void inventedFindingsAreRejected() {
        // "(c) existing tests: testImportAllFeeds, testImportAllParseErrorHandling
        // (d) spec verbs reach parseEntries" - nothing returned those; no reach analysis ran
        String evidence = "CodeGraph => locate FeedImporter#importAll line=198\n"
                + "CodeGraph#2 => catches at FeedImporter.java:212 receives IllegalStateException ...";
        String answer = "(c) Existing test methods: testImportAllFeeds, testImportAllParseErrorHandling. "
                + "(d) Spec verbs reach FeedImporter#parseEntries end-to-end.";
        String issue = AnswerGate.fabricatedIdentifiersIssue(answer, evidence, "characterize the catch");
        assertNotNull(issue);
        assertTrue(issue.contains("testImportAllParseErrorHandling"), issue);
        assertTrue(issue.contains("no spec reach analysis"), issue);
        // grounded: the names came from a tool, the reach claim from the composer
        String grounded = AnswerGate.fabricatedIdentifiersIssue(
                "Template: FeedImporterTest#importSkipsEmptyFeeds. SpecTestComposer reach: NO HANDLER.",
                evidence + "\nSpecTestComposer => specReachable=false NO HANDLER\nBash => importSkipsEmptyFeeds",
                "characterize");
        assertNull(grounded, grounded);
        // a name the USER gave is not a fabrication
        assertNull(AnswerGate.fabricatedIdentifiersIssue("Model on FeedImporterTest#importSkipsEmptyFeeds",
                evidence, "use FeedImporterTest#importSkipsEmptyFeeds as the template"));
    }

    @Test
    void clarifyForTheStatedBehaviourIsRejected() {
        // "Please provide the specific behavior you want to test" - the request said it
        String request = "Write ONE failing unit test that pins the correct behavior: when parsing a feed throws, "
                + "the feed must stay UNREAD, never be marked as read.";
        String issue = AnswerGate.discoverableClarifyIssue(
                "Please provide the specific behavior you want to test and "
                        + "implement in the FeedImporterTest class.", request);
        assertNotNull(issue);
        assertTrue(issue.contains("STATES it"), issue);
        assertNull(AnswerGate.discoverableClarifyIssue("Which of the two fix designs do you prefer?", request));
    }

    @Test
    void unsupportedReachClaimsLeaveWithAnHonestyNoteToo() {
        // the gate rejected the spec-reach claim twice; the third ending must not leave unmarked
        String out = AnswerGate.withHonestyNote("Spec verbs reach FeedImporter#parseEntries end-to-end.",
                false, false, "CodeGraph => catches ...", "characterize");
        assertTrue(out.contains("honesty note") && out.contains("no spec reach analysis"), out);
    }

    @Test
    void negatedOrUndeterminedReachStatementsAreNotClaims() {
        // "No spec verb has been determined to reach ..." is the honest answer, not a claim
        String evidence = "CodeGraph => callees parseEntries at FeedImporter.java:211 ...";
        assertNull(AnswerGate.fabricatedIdentifiersIssue(
                "No spec verb has been determined to reach FeedImporter#parseEntries end-to-end.", evidence, "x"));
        assertNull(AnswerGate.fabricatedIdentifiersIssue("Whether a spec verb can reach it is unknown.", evidence, "x"));
        assertNotNull(AnswerGate.fabricatedIdentifiersIssue("Spec verbs reach FeedImporter#parseEntries.", evidence,
                "x"));
    }

    @Test
    void gateIssuesThatDisputeATermTheResultsContainAreOverruled() {
        // the gate "corrected" the defect's method to one the results never place a catch in
        String evidence = "CodeGraph: {status=Success, action=catches, symbol=FeedImporter#importAll, "
                + "catches=[{at=FeedImporter.java:212, declared=[Exception]}]} CodeGraph#2: {action=escapes, "
                + "symbol=FeedImporter#parseEntries, escapes=[...]}";
        assertTrue(ReviewPanel.gateOverrulesEvidence("The final answer does not convey that the exact defect is in the "
                + "method 'parseEntries'. Instead, it incorrectly states that the defect is in a different method "
                + "'importAll'.", evidence));
        // a genuine omission complaint names nothing the results dispute
        assertFalse(ReviewPanel.gateOverrulesEvidence("The answer does not report the escaping exceptions.", evidence));
        // an unsupported term the answer invented is a real complaint
        assertFalse(ReviewPanel.gateOverrulesEvidence("The answer incorrectly states the defect is in 'FrobnicatorImpl'.",
                evidence));
    }

    @Test
    void theNoToolGuardStandsDownWhenTheContextAlreadyCarriesFindings() {
        // a "report the defect and the fix" request with all the analysis in context was
        // answered, rejected by this guard for "no tool ran", and re-planned into a redundant CodeGraph call
        String request = "Report the exact defect (file, method, lines), which exceptions reach the catch, and the fix "
                + "— stated as the concrete code change. Analysis so far: FeedImporter.java:198 …";
        assertNotNull(AnswerGate.noToolAnswerIssue(request), "with no evidence the guard must still fire");
        String evidenced = "CodeGraph: {status=Success, action=catches, catches=[{at=FeedImporter.java:212}]} "
                + "CodeGraph#2: NO HANDLER on any caller chain";
        assertNull(AnswerGate.noToolAnswerIssue(request, evidenced), "findings in context → answerable");
        assertTrue(AnswerGate.hasGatheredEvidence("… reachable=false, routes=[] …"));
        assertTrue(AnswerGate.hasGatheredEvidence("FeedImporterTest#x score=9.1 overlap=4"));
        assertFalse(AnswerGate.hasGatheredEvidence("Please look at the workflow and tell me what you think."));
        // a genuinely fresh request with only prose context still needs a tool
        assertNotNull(AnswerGate.noToolAnswerIssue("take a look at Gateway.java and tell me what onRequest does",
                "Conversation so far: the user asked about the workflow."));
    }

    @Test
    void aGuessedFileLineAddressIsRefusedUntilAToolReportsIt() {
        // the FIRST plan hard-coded FeedImporter.java:212 before any tool ran
        String params = "{action=impact, symbol=FeedImporter.java:212}";
        String bare = "Report: a fail-open catch in FeedImporter.importAll swallows parse errors.";
        String issue = AnswerGate.inventedLineAddressIssue(params, bare);
        assertNotNull(issue, "no tool has reported line 212 yet");
        assertTrue(issue.contains("action=catches") && issue.contains("GUESS"), issue);
        // grounded by a locate/catches result in either shape
        assertNull(AnswerGate.inventedLineAddressIssue(params,
                "CodeGraph: {action=catches, catches=[{at=FeedImporter.java:212}]}"));
        assertNull(AnswerGate.inventedLineAddressIssue(params,
                "CodeGraph: {declarations=[{file=/x/FeedImporter.java, line=212}]}"));
        // no address in the plan at all
        assertNull(AnswerGate.inventedLineAddressIssue("{action=catches, symbol=FeedImporter#run}", bare));
    }

    @Test
    void theSynthesizedLoopGuardAnswerGoesThroughTheSameGates() {
        // a synthesized answer made of reviewer complaints ended stage 3 with no fix applied
        String request = "FIRST commit it locally … THEN: stage 3 of 4: FIX to GREEN. Make the minimal production fix …";
        String evidence = "Bash: {status=Success, output=[fix-feed-import 2dc712dfae] test(FeedImporter): "
                + "initial failing test}";
        String junk = "1. The initial commit message is incorrect. 2. The commit message does not follow the conventional format.";
        assertNotNull(AnswerGate.deterministicAnswerIssue(request, evidence, junk));
        assertNull(AnswerGate.deterministicAnswerIssue(request,
                evidence + " TreeEdit: {status=Success, op=replaceStatement}", "The fix is applied and the test is green."));
    }

    @Test
    void aFixRequestAnsweredWithADescriptionAndNoEditIsIncomplete() {
        // "To fix this, replace X with Y … the run should now pass" after one failed edit
        String request = "stage 3 of 4: FIX to GREEN. Make the minimal production fix in FeedImporter.importAll …";
        String failedOnly = "TreeEdit: {status=Error, message=edit rejected} HindsightDebugger: {status=Success}";
        String answer = "The correct anchor is 'x(a, b);'. To fix this, replace it with 'x(c, b);'. The real gradle run should now pass.";
        assertNotNull(AnswerGate.describedNotDoneIssue(request, failedOnly, answer));
        assertNull(AnswerGate.describedNotDoneIssue(request, failedOnly + " TreeEdit: {status=Success, op=replaceStatement}", answer));
        assertNull(AnswerGate.describedNotDoneIssue("Characterize the defect; do not touch code", failedOnly, answer));
    }

    @Test
    void aPlanThatRegeneratesAnAlreadyGeneratedTestIsRejected() {
        // the generator refused the repeat and the planner planned it a third time
        String context = "TreeCodegen: {status=Success, alreadyGenerated=true, "
                + "message=FeedImporterTest#testX was ALREADY generated by this tool earlier}";
        String plan = "{\"actions\": [{\"tool\": \"TreeCodegen\", \"parameters\": "
                + "{\"method\": \"FeedImporterTest#testX\", \"task\": \"t\"}}]}";
        String issue = AnswerGate.regenerationIssue(plan, context);
        assertNotNull(issue);
        // the rendered parameter-map form the plan gate sees
        assertNotNull(AnswerGate.regenerationIssue("TreeCodegen {task=t, method=FeedImporterTest#testX, file=F.java}",
                context));
        assertTrue(issue.contains("COMMIT"), issue);
        assertNull(AnswerGate.regenerationIssue(plan, "CodeGraph: {action=catches}"), "nothing generated yet");
        String other = "{\"actions\": [{\"tool\": \"TreeCodegen\", "
                + "\"parameters\": {\"method\": \"FeedImporterTest#testOther\"}}]}";
        assertNull(AnswerGate.regenerationIssue(other, context), "a different method is fine");
        assertNull(AnswerGate.regenerationIssue("{\"actions\": [{\"tool\": \"Bash\", \"parameters\": {\"command\": \"git commit -s -m x\"}}]}", context));
    }

    @Test
    void aRequestThatNamesItsOwnCommitCommandGetsThatCommandBackToRunNext() {
        // told to commit "the test file" and then to answer, the planner
        // answered twice with an invented hash and invented test results
        String command = "./gradlew test --tests 'p.ParserTest' --offline > build/run.txt 2>&1; python3 -c 'x' && "
                + "git add src/main/java/p/Parser.java && git commit -m \"Parse prefix operators\"";
        String request = "Rewrite task: prefix operators.\n\nWhen TreeCodegen succeeds, run ONE Bash command:\n  " + command
                + "\n\nAnswer with the commit hash that git printed.";
        String issue = AnswerGate.uncommittedGenerationIssue(request, "TreeCodegen: {status=Success, compiled=true}");
        assertNotNull(issue);
        assertTrue(issue.contains("your next plan is ONE Bash action") && issue.endsWith(command), issue);
        assertFalse(issue.contains("the test file"), issue);
        assertEquals(null, AnswerGate.requestCommitCommand("Commit the test locally (signed-off with -s)"));
    }

    @Test
    void aGeneratedTestThatWasNeverCommittedIsAnIncompleteAnswer() {
        // the red test was generated, run and recorded; the planner then re-selected generation
        // instead of committing
        String request = "Write ONE failing unit test ... run it ... record it ... Commit the test locally (signed-off with -s)";
        String evidence = "TreeCodegen: {status=Success, compiled=true, critics=3/3 approved} Bash: {status=NonZeroExit}";
        assertNotNull(AnswerGate.uncommittedGenerationIssue(request, evidence));
        // key order varies in a rendered result (one run put emulatorPrediction first)
        assertNotNull(AnswerGate.uncommittedGenerationIssue(request,
                "TreeCodegen: {emulatorPrediction=UNDECIDED, critics=3/3 approved, status=Success, focus=...}"));
        assertNotNull(AnswerGate.uncommittedGenerationIssue(request,
                "TreeCodegen: {status=Success, alreadyGenerated=true, message=X#y was ALREADY generated by this tool}"));
        assertNull(AnswerGate.uncommittedGenerationIssue(request,
                evidence + " Bash: {command: git add x && git commit -s -m 'y', status=Success, output=[hermit2 abc1234] y}"));
        assertNull(AnswerGate.uncommittedGenerationIssue("Characterize the defect; do not touch code", evidence));
        assertNull(AnswerGate.uncommittedGenerationIssue(request, "CodeGraph: {action=catches}"));
    }

    @Test
    void anAnswerThatIgnoresARuntimeRecordingIsIncomplete() {
        // two recordings succeeded, one proving the fail-open catch swallowed an NPE, and the answer
        // mentioned neither
        String evidence = "HindsightDebugger: {recordingDir=/tmp/hindsight-rec-81998, stats=records: 695, "
                + "throwAndReturnEvents=[[seq 659] throw NullPointerException [FeedImporter.parseEntries], "
                + "[seq 667] return false [FeedImporter.importAll]]}";
        String staticOnly = "The method catches any exception and marks the feed as read. "
                + "The CodeGraph results show both branch writes.";
        assertNotNull(AnswerGate.ignoredRecordingIssue(staticOnly, evidence));
        assertTrue(AnswerGate.ignoredRecordingIssue(staticOnly, evidence).contains("propagated or was swallowed"));
        // an answer that reports the trace passes
        assertNull(AnswerGate.ignoredRecordingIssue("The recording shows a NullPointerException thrown in "
                + "parseEntries and swallowed by importAll (695 records).", evidence));
        // no recording in the evidence → nothing to demand
        assertNull(AnswerGate.ignoredRecordingIssue(staticOnly, "CodeGraph: {status=Success, catches=[…]}"));
    }

    @Test
    void aProposedNewTestNameIsNotAFabrication() {
        // "A unit test … can be created using FeedImporterTest as the template class and a new
        // method like 'testParseFailureLeavesTheFeedUnread'" was flagged as an invented finding
        String evidence = "CodeGraph: {action=tests, tests=[{test=FeedImporterTest#importSkipsEmptyFeeds}]}";
        String proposal = "The fix is to leave the feed unread when parsing fails. A unit test for this "
                + "scenario can be created using FeedImporterTest as the template class and a new method like "
                + "'testParseFailureLeavesTheFeedUnread'.";
        assertNull(AnswerGate.fabricatedIdentifiersIssue(proposal, evidence, ""), "a proposal is not a finding");
        // presenting an unseen name as an existing finding is still caught
        String claim = "The existing test FeedImporterTest#alreadyCoversTheFailOpenCatch exercises the path.";
        assertNotNull(AnswerGate.fabricatedIdentifiersIssue(claim, evidence, ""));
        assertTrue(AnswerGate.introducedAsAProposal("… a new method like 'testFoo'", 22));
        assertFalse(AnswerGate.introducedAsAProposal("The existing test testFoo covers it", 18));
    }

    @Test
    void reviewerRestatementsAreNotComplaints() {
        // the LLM gate returned approved=false with the CORRECT characterization as its "issue"
        assertFalse(ReviewPanel.looksLikeAComplaint("The exact defect is in FeedImporter.java, method "
                + "importAll. The fail-open catch block is located at line 212, which catches any "
                + "Exception and logs it before marking the feed as read."));
        assertTrue(ReviewPanel.looksLikeAComplaint("The answer does not report the successful results."));
        assertTrue(ReviewPanel.looksLikeAComplaint("Missing the commit hash."));
        assertTrue(ReviewPanel.looksLikeAComplaint("Your answer claims the callers reach the code, but no analysis ran."));
    }

    @Test
    void gatesReadTheAgentsOwnRenderingOfResults() {
        // the agent renders a result as Tool={…} (summarizeResults) while the listener log renders
        // Tool: {…}; the gates knew only the log form, so a turn that edited, ran green and committed was flagged
        // "no edit succeeded" and the driver re-planned an edit that was already in place
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("status", "Success");
        edit.put("op", "replaceStatement");
        edit.put("file", "/w/FeedImporterTest.java");
        edit.put("focus", "/** {@code a} */ lenient().when(feedIter.hasNext()).thenReturn(true);");
        String evidence = "TreeEdit => " + ResultSummaries.summarizeResults(Map.of("TreeEdit", edit)) + "\n"
                + "Bash => {Bash={status=Success, exitCode=0, output=[feature-branch 9c53e5c03e] fix: lenient}; }\n";
        String request = "make the minimal production fix … confirmed by the real gradle run, then commit";
        assertNull(AnswerGate.describedNotDoneIssue(request, evidence, "The stub is lenient now; the run passed."));
        // the F219b commit gate must fire on the same rendering (it never fired in production before F236)
        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("status", "Success");
        gen.put("focus", "@Test void testParseThrows() { subject.importAll(); }");
        String generatedOnly = "TreeCodegen => " + ResultSummaries.summarizeResults(Map.of("TreeCodegen", gen)) + "\n";
        assertNotNull(AnswerGate.uncommittedGenerationIssue("write one failing test and commit it", generatedOnly));
        // and the F221 regeneration plan gate must recognise the generated method from the context rendering
        String context = "[Iteration 2] Executed 'Generate'. Tool results: "
                + ResultSummaries.summarizeResults(Map.of("TreeCodegen", gen));
        assertNotNull(AnswerGate.regenerationIssue(
                "{\"tool\":\"TreeCodegen\",\"method\":\"FeedImporterTest#testParseThrows\"}", context));
        // the log rendering keeps working
        assertNull(AnswerGate.describedNotDoneIssue(request, "TreeEdit: {status=Success, op=replaceStatement}", "Done."));
    }

    @Test
    void aBraceInsideAValueDoesNotHideTheStatus() {
        // a result whose first value carries braces (code, javadoc) before status=Success is still ONE block
        String evidence = "TreeCodegen={emulatorPrediction=GREEN, focus=void t() { if (a) { b(); } }, status=Success}; "
                + "HindsightDebugger={status=Success, throwAndReturnEvents=[…]}";
        assertNotNull(AnswerGate.successfulResultBlock(evidence, Set.of("TreeCodegen")));
        // a FAILED edit followed by the recording's Success is not an edit (an earlier lesson, kept)
        String failedEdit = "TreeEdit={status=Error, message=edit rejected}; HindsightDebugger={status=Success, note=x}";
        assertNull(AnswerGate.successfulResultBlock(failedEdit, AnswerGate.EDIT_TOOLS));
        assertNotNull(AnswerGate.describedNotDoneIssue("make the minimal fix", failedEdit, "Replace X with Y."));
    }

    @Test
    void theToolsThatSucceededThisTurnAreARenderingIndependentSignal() {
        String request = "apply the fix and run the module's test";
        assertNull(AnswerGate.describedNotDoneIssue(request, "(evidence trimmed)", "Done.",
                Set.of("TreeEdit", "Bash")));
        assertNotNull(AnswerGate.describedNotDoneIssue(request, "(evidence trimmed)", "Done.", Set.of("Bash")));
        assertNotNull(AnswerGate.uncommittedGenerationIssue("write the test and commit it", "(trimmed)",
                Set.of("TreeCodegen")));
        assertNull(AnswerGate.uncommittedGenerationIssue("write the test and commit it",
                "Bash={status=Success, output=[b 0123abc] test: x}; the plan ran git commit -s", Set.of("TreeCodegen")));
    }

    @Test
    void aRequestForTheRealGradleOutcomeNeedsARealRun() {
        // RED was about to be reported on the emulator's prediction alone; the test was green
        String request = "write ONE failing unit test … then run the real gradle test to confirm RED, then commit";
        String predictionOnly = "TreeCodegen => {TreeCodegen={status=Success, emulatorPrediction=PREDICTED RED: assertEquals …, critics=2/3}; }\n"
                + "HindsightDebugger => {HindsightDebugger={status=Success, recordingDir=/tmp/x}; }\n";
        String issue = AnswerGate.unconfirmedOutcomeIssue(request, predictionOnly);
        assertNotNull(issue);
        assertTrue(issue.contains("no gradle test run happened"), issue);
        String withRun = predictionOnly + "Bash => {Bash={status=NonZeroExit, exitCode=1, "
                + "output=> Task :library-lookup:test FAILED … BUILD FAILED}; }\n";
        assertNull(AnswerGate.unconfirmedOutcomeIssue(request, withRun));
        assertNull(AnswerGate.unconfirmedOutcomeIssue(request, "TreeCodegen={status=Success, testOutcome=red}"));
        // a request that does not ask for the real run is not gated
        assertNull(AnswerGate.unconfirmedOutcomeIssue("characterize the defect with the analysis tools", predictionOnly));
        // and the chain reaches it
        assertNotNull(AnswerGate.deterministicAnswerIssue(request, predictionOnly, "The test is RED as predicted.", Set.of("TreeCodegen", "HindsightDebugger", "Bash")));
    }

    @Test
    void aCitedCommitHashMustBeAGitHashAndMatchTheCommitLine() {
        // "REAL commit hash: 01a2b3c4d5e6f7g8h9i0j1k2l3m4n5o6p7q8r9s0t1u2v3w4x5y6z" passed both patterns
        String fake = "Real gradle outcome: the test failed. REAL commit hash: 01a2b3c4d5e6f7g8h9i0j1k2l3m4n5o6p7q8r9s0t1u2v3w4x5y6z";
        String issue = AnswerGate.hashClaimIssue(fake, "");
        assertNotNull(issue);
        assertTrue(issue.contains("is not a git hash"), issue);
        // the old fabrication gate now sees the claim too
        assertNotNull(AnswerGate.fabricationIssue(fake, false, true));
        String evidence = "Bash => {Bash={status=Success, exitCode=0, "
                + "output=[fix-lookup17 3f2a1b9c] test: add failing test x}; }";
        assertNotNull(AnswerGate.hashClaimIssue("committed as deadbeef1", evidence));
        // a word after "commit" is not a cited hash
        assertNull(AnswerGate.hashClaimIssue("710601a. It details the approval and commit protocol for every task.", evidence));
        assertNotNull(AnswerGate.hashClaimIssue("The commit hash is 01a2b3c4d5e6f7g8h9", ""), "digits and letters beyond f still count");
        assertTrue(AnswerGate.hashClaimIssue("committed as deadbeef1", evidence).contains("3f2a1b9c"));
        assertNull(AnswerGate.hashClaimIssue("The commit hash is 3f2a1b9c.", evidence));
        assertNull(AnswerGate.hashClaimIssue("commit: 3f2a1b9c0d", evidence), "a longer prefix-compatible form is the same commit");
        assertNull(AnswerGate.hashClaimIssue("No commit was made; the task is incomplete.", evidence));
        // the deterministic chain reaches it
        assertNotNull(AnswerGate.deterministicAnswerIssue("write the test, run gradle, commit it", evidence
                + "TreeCodegen => {TreeCodegen={status=Success, testOutcome=red}; }", "Done. commit hash: 0000000zz", Set.of("Bash", "TreeCodegen")));
    }

    @Test
    void aRefusedRegenerationAfterTheCommitPointsAtTheAnswer() {
        // the commit had happened and the gate still said "the step still missing is the COMMIT"
        String context = "TreeCodegen={status=Success, focus=@Test void testCachedListing() throws IOException { … }}; "
                + "Bash#3={status=Success, exitCode=0, output=[fix-lookup17 bc21b9f4f5] Add test for cached listing "
                + "1 file changed}";
        String issue = AnswerGate.regenerationIssue(
                "{\"tool\":\"TreeCodegen\",\"method\":\"ShelfLookupTest#testCachedListing\"}", context);
        assertNotNull(issue);
        assertTrue(issue.contains("COMMITTED (bc21b9f4f5)"), issue);
        assertTrue(issue.contains("answer now"), issue);
        assertFalse(issue.contains("still missing"), issue);
    }

    @Test
    void aCommitLineInAToolOutputCountsAsTheCommit() {
        // the gate looked for the text "git commit", which a result never carries
        String evidence = "TreeCodegen => {TreeCodegen={status=Success, focus=@Test void t() { … }}; }\n"
                + "Bash#3 => {Bash#3={status=Success, exitCode=0, output=[fix-lookup17 bc21b9f4f5] Add test for "
                + "cached listing 1 file changed, 29 insertions(+)}; }\n";
        assertNull(AnswerGate.uncommittedGenerationIssue("write one failing test … Commit the test locally", evidence));
        assertNotNull(AnswerGate.uncommittedGenerationIssue("write one failing test … Commit the test locally",
                "TreeCodegen => {TreeCodegen={status=Success, focus=@Test void t() { … }}; }\n"));
    }

    @Test
    void aBuildSummaryOrTheCommandProvesTheRunWhenTheOutputIsCapped() {
        String request = "implement the minimal fix … confirmed by the real gradle run";
        String capped = "Bash#build => {Bash#build={status=Success, exitCode=0, command=./gradlew :library-lookup:test "
                + "--tests ShelfLookupTest.t, buildSummary=:library-lookup:test → exit 0, BUILD SUCCESSFUL, "
                + "output=Calculating task graph … > Task :api:generateSources UP-TO-DATE …}; }";
        assertNull(AnswerGate.unconfirmedOutcomeIssue(request, capped));
        assertNotNull(AnswerGate.unconfirmedOutcomeIssue(request, "Bash => {Bash={status=Success, "
                + "exitCode=0, output=Calculating task graph … > Task :api:generateSources UP-TO-DATE}; }"));
    }

    @Test
    void aConfirmationRequestDoesNotDemandAnEdit() {
        // "CONFIRM the fix to GREEN. The fix is committed (3415d4947c) … Do NOT edit" matched "fix to green"
        String request = "stage 3 of 4: CONFIRM the fix to GREEN. The fix is committed (3415d4947c). Stage 3's FIX is ALSO already "
                + "implemented and committed. Do NOT edit ShelfLookupHandler "
                + "again. Run the real gradle test and record it.";
        assertNull(AnswerGate.describedNotDoneIssue(request, "Bash={status=Success, buildSummary=:m:test → exit 0, BUILD SUCCESSFUL}", "GREEN confirmed."));
        // a genuine write request without an edit is still refused
        assertNotNull(AnswerGate.describedNotDoneIssue("make the minimal production fix and run the test", "Bash={status=Success}", "Replace X with Y."));
    }

    @Test
    void aHashTheRequestOrAToolOutputHoldsIsNotAFabrication() {
        // the confirmation request named the commit made in an earlier session
        String request = "CONFIRM the fix. The fix is committed: [fix-lookup17 3415d4947c]. "
                + "Report the REAL commit hash 3415d4947c.";
        assertNull(AnswerGate.fabricationIssue("GREEN confirmed; the fix is commit 3415d4947c.", false, true, request));
        assertNull(AnswerGate.fabricationIssue("commit hash: 3415d4947cfd239fc4fd551717a067befbe021d9", false, true,
                "Bash#2 => {Bash#2={status=Success, output=3415d4947cfd239fc4fd551717a067befbe021d9}; }"));
        // a hash nothing holds is still a fabrication
        assertNotNull(AnswerGate.fabricationIssue("commit hash: deadbeef1", false, true, request));
    }

    @Test
    void aRequestThatForbidsRunningIsNotARequestForARun() {
        // the preface recalled an earlier "real gradle run"; the stage said "Do not run the spec yourself"
        String request = "Stage 3 is COMPLETE: the real gradle run of the test already exited 0. Stage 4: compose a spec test … "
                + "Do not run the spec yourself unless asked; report the exact gradle command the generator returned.";
        assertNull(AnswerGate.unconfirmedOutcomeIssue(request, "TreeCodegen => {TreeCodegen={status=Success, action=generate}; }"));
    }

    // the coaching of a failed tool result is the next step
    @Test
    void refusalHintIsTheCoachingOfAFailedResult() {
        String failed = "{status=Error, action=compose, problems=[unknown verb 'updateShelf' in op "
                + "«updateShelf(12, x)» — closest known verbs: [shelfUpdate, renameShelf, resizeShelf] (use one of "
                + "the catalogue's names exactly)]}";
        String hint = AnswerGate.refusalHint(failed);
        assertNotNull(hint);
        assertTrue(hint.contains("closest known verbs: [shelfUpdate, renameShelf, resizeShelf]"), hint);
        assertTrue(AnswerGate.refusalHint("{status=Error, problems=[[16:57] unknown variable 'expectedLabel'], "
                + "DECLARE the values the ops use as locals with the `declare` field: \"declare\": "
                + "[{\"name\": \"expectedLabel\"}]}").contains("DECLARE the values"));
        assertTrue(AnswerGate.refusalHint("[17:17] no overload of 'shelfCreate' takes 2 arguments — shelfCreate's "
                + "overloads: shelfCreate(final String shelfName); e.g. shelfCreate(\"shelf-12\") (ids are Strings)")
                .contains("shelfCreate's overloads: shelfCreate(final String shelfName)"));
        assertNull(AnswerGate.refusalHint("{status=Error, error=Connection refused}"));
        assertNull(AnswerGate.refusalHint(null));
    }

    @Test
    void aFinalAnswerThatGivesUpWithTurnsLeftAndANamedNextStepIsRejected() {
        String hint = "closest known verbs: [shelfUpdate, renameShelf, resizeShelf]";
        String surrender = "The task of composing a spec-level regression test has not been fully achieved. The composition was "
                + "rejected due to unknown verbs. To complete this task, the following steps should be taken: 1. Verify that the "
                + "updateShelf verb is recognized. The task remains INCOMPLETE and requires further action.";
        String issue = AnswerGate.surrenderIssue(surrender, hint, 10);
        assertNotNull(issue);
        assertTrue(issue.contains("10 iteration(s) remain"), issue);
        assertTrue(issue.contains("shelfUpdate"), issue);
        // no turns left, no hint, or an answer that does not give up → no issue
        assertNull(AnswerGate.surrenderIssue(surrender, hint, 0));
        assertNull(AnswerGate.surrenderIssue(surrender, null, 10));
        assertNull(AnswerGate.surrenderIssue("Composed renamedShelfKeepsItsBooks into ShelfRenameSpec (reaches "
                + "ShelfLookupHandler#booksOn via getShelfInfo); committed as 9ab12cd.", hint, 10));
    }

    // the composer's reach SUCCEEDED, the compose FAILED, and the answer claimed the test was added
    @Test
    void aReadOnlyActionOfAnEditToolIsNotAnEditSuccess() {
        // the agent's rendering: `Tool={…}`; the listener's is `Tool: {…}`
        String evidence = "SpecTestComposer => SpecTestComposer={status=Success, action=reach, "
                + "target=ShelfLookupHandler#booksOn, specReachable=true}\n"
                + "SpecTestComposer#2 => SpecTestComposer#2={status=Error, "
                + "action=compose, problems=[no overload of 'shelfCreate' "
                + "takes 2 arguments]}\n";
        assertNull(AnswerGate.successfulResultBlock(evidence, AnswerGate.EDIT_TOOLS));
        assertNotNull(AnswerGate.successfulResultBlock(
                "SpecTestComposer: {status=Success, action=compose, suiteFile=/x/Y.java, written=true}\n", AnswerGate.EDIT_TOOLS));
        assertFalse(AnswerGate.isEditSuccess("SpecTestComposer", Map.of("status", "Success", "action", "reach")));
        assertTrue(AnswerGate.isEditSuccess("SpecTestComposer", Map.of("status", "Success", "action", "compose")));
        assertTrue(AnswerGate.isEditSuccess("TreeEdit", Map.of("status", "Success")));
        assertFalse(AnswerGate.isEditSuccess("CodeGraph", Map.of("status", "Success")));
        // the compose request is a write request: with only the reach success the described-not-done gate fires
        String request = "Compose a validated @SpecTest that renames shelf 12 and then queries its info.";
        String answer = "The spec-level regression test was successfully composed using the SpecTestComposer tool. "
                + "No further action is needed.";
        assertNotNull(AnswerGate.describedNotDoneIssue(request, evidence, answer, Set.of("CodeGraph")));
        assertNull(AnswerGate.describedNotDoneIssue(request,
                "SpecTestComposer={status=Success, action=compose, suiteFile=/x/Y.java}\n", answer, Set.of("SpecTestComposer")));
    }

    @Test
    void aClaimedFileThatDoesNotExistIsAFabrication() throws Exception {
        String missing = "The test method `renamedShelfKeepsItsBooks` was added to the suite file "
                + "`/nonexistent/definitely/missing/ShelfRenameSpec.java`. No further action is needed.";
        String issue = AnswerGate.claimedArtifactMissingIssue(missing);
        assertNotNull(issue);
        assertTrue(issue.contains("/nonexistent/definitely/missing/ShelfRenameSpec.java"), issue);
        Path real = Files.createTempFile("claimed", ".java");
        try {
            assertNull(AnswerGate.claimedArtifactMissingIssue("The test was written to " + real + " and compiles."));
            assertNull(AnswerGate.claimedArtifactMissingIssue(real + " was updated with the new method."));
        } finally {
            Files.deleteIfExists(real);
        }
        // a path merely mentioned (not claimed written) is not checked
        assertNull(AnswerGate.claimedArtifactMissingIssue(
                "The handler lives in /nonexistent/missing/ShelfLookupHandler.java."));
        assertNull(AnswerGate.claimedArtifactMissingIssue(null));
        // the tail of a relative path is not a path at the file system's root
        assertNull(AnswerGate.claimedArtifactMissingIssue("docs/NEUTRAL-LANGUAGE.md was created and committed."));
        assertNull(AnswerGate.claimedArtifactMissingIssue("The guide was written to docs/TDD-WORKFLOW.md."));
        assertNotNull(AnswerGate.claimedArtifactMissingIssue("The guide was written to /nonexistent/docs/TDD-WORKFLOW.md."));
    }

    // "the fix is committed (3415d4947c) … compose a validated @SpecTest" - the already-done exemption
    // is about the fix; the test the request asks to compose still has to be composed
    @Test
    void theAlreadyCommittedFixDoesNotExemptTheTestTheRequestAsksToCompose() {
        String request = "The fix is committed (3415d4947c) on the local branch. Compose a validated @SpecTest "
                + "that renames shelf 12 and then queries its info, asserting the label is the new name.";
        String evidence = "SpecTestComposer => SpecTestComposer={status=Success, "
                + "action=reach, target=ShelfLookupHandler#booksOn}\n"
                + "SpecTestComposer#2 => SpecTestComposer#2={status=Error, action=compose, problems=[op uses single quotes]}\n";
        String answer = "SpecTestComposer was used to confirm that the spec verbs reach ShelfLookupHandler#booksOn. "
                + "The composed @SpecTest method is as follows: ...";
        assertNotNull(AnswerGate.describedNotDoneIssue(request, evidence, answer, Set.of("CodeGraph")));
        // a pure confirmation request keeps the exemption
        assertNull(AnswerGate.describedNotDoneIssue("The fix is committed (3415d4947c). Confirm the fix is GREEN with a "
                + "real gradle run and a runtime recording; do NOT edit code.", evidence, answer, Set.of("Bash")));
    }

    // imports and a gradle command attributed to the composer that it never returned
    @Test
    void factsAttributedToTheComposerMustBeTheComposersFacts() {
        String evidence = "SpecTestComposer => SpecTestComposer={status=Success, action=compose, "
                + "suiteFile=/w/ShelfRenameSpec.java, testName=renamedShelfKeepsItsBooks, "
                + "importsAdded=[static example.spec.commands.CommandVerbs.shelfUpdate, "
                + "static example.spec.lookups.LookupVerbs.getShelfInfo, static example.spec.util.Hex.hex, "
                + "static example.spec.util.Digests.sha256Of], written=true, next=run it for real: ./gradlew specTest "
                + "--tests \"example.library.spec.ShelfRenameSpec.renamedShelfKeepsItsBooks\"}\n";
        String invented = "Imports added by the composer:\nimport static example.spec.support.FixtureUtils.*;\n"
                + "import static example.spec.setup.SetupOps.*;\nGradle command returned by the composer:\n"
                + "./gradlew :library:spec-tests:test\nLocal commit: d1cde5aa62";
        String issue = AnswerGate.misattributedToolFactsIssue(invented, evidence);
        assertNotNull(issue);
        assertTrue(issue.contains("FixtureUtils"), issue);
        assertTrue(issue.contains("LookupVerbs.getShelfInfo"), issue);
        String honest = "Imports added by the composer:\n"
                + "import static example.spec.commands.CommandVerbs.shelfUpdate;\n"
                + "import static example.spec.lookups.LookupVerbs.getShelfInfo;\n"
                + "import static example.spec.util.Hex.hex;\n"
                + "Gradle command returned by the composer: ./gradlew specTest "
                + "--tests \"example.library.spec.ShelfRenameSpec.renamedShelfKeepsItsBooks\"";
        assertNull(AnswerGate.misattributedToolFactsIssue(honest, evidence));
        // the wrong command alone is caught too
        String badCmd = "Gradle command returned by the composer: ./gradlew :library:spec-tests:test";
        assertTrue(AnswerGate.misattributedToolFactsIssue(badCmd, evidence).contains("specTest --tests"));
        // imports not attributed to a tool are the answer's own business
        assertNull(AnswerGate.misattributedToolFactsIssue("You may want import static a.b.FixtureUtils.*; as well.",
                evidence));
        assertNull(AnswerGate.misattributedToolFactsIssue(invented, "Bash => Bash={status=Success, output=ok}"));
    }

    // a long tool result is capped per entry, so its short trailing facts survive the fold
    @Test
    void aLongResultMapKeepsItsShortTrailingFacts() {
        Map<String, Object> compose = new LinkedHashMap<>();
        compose.put("status", "Success");
        compose.put("action", "compose");
        compose.put("methodText", "x".repeat(3000));
        compose.put("reaching", List.of("y".repeat(2000)));
        compose.put("importsAdded",
                List.of("static a.b.CommandVerbs.shelfUpdate", "static a.b.LookupVerbs.getShelfInfo"));
        compose.put("written", true);
        compose.put("next", "run it for real: ./gradlew specTest --tests \"a.b.Spec.m\"");
        String summary = ResultSummaries.summarizeResults(Map.of("SpecTestComposer", compose));
        assertTrue(summary.contains(
                "importsAdded=[static a.b.CommandVerbs.shelfUpdate, static a.b.LookupVerbs.getShelfInfo]"), summary);
        assertTrue(summary.contains("written=true"), summary);
        assertTrue(summary.contains("next=run it for real: ./gradlew specTest"), summary);
        assertTrue(summary.contains("more chars NOT in your context)"), "the long values are still capped: " + summary.length());
        // the attribution gate then sees the facts even when the answer uses JSON keys instead of an attribution phrase
        String evidence = "SpecTestComposer => SpecTestComposer=" + summary;
        String jsonAnswer = "{\"composed_test\": \"...\", \"imports_added\": \"import static a.b.FixtureUtils.*;\", "
                + "\"gradle_command\": \"./gradlew :x:test\"}";
        String issue = AnswerGate.misattributedToolFactsIssue(jsonAnswer, evidence);
        assertNotNull(issue);
        assertTrue(issue.contains("FixtureUtils"), issue);
    }

    // a fenced "quotation" of render() that no tool ever returned
    @Test
    void codeQuotedAsTheRepositorysMustHaveBeenRead() {
        String evidence = "Bash => Bash={status=Success, output=    private boolean sameEntry(final Page cached, "
                + "final Page requested) {\n"
                + "        return cached.version() == requested.version()\n"
                + "            && Objects.equals(cached.path(), requested.path())\n"
                + "            && Objects.equals(cached.locale(), requested.locale());\n    }}\n";
        String invented = "In render (lines 96-109):\n```java\nfinal var cached = cache.lookup(requested);\n"
                + "if (cached != null) {\n"
                + "    throw new CacheException(CacheErrorEntryAlreadyPresent, cached.pageId());\n}\n"
                + "```\nVerdict: TRUE POSITIVE";
        String issue = AnswerGate.quotedCodeNotInEvidenceIssue(invented, evidence);
        assertNotNull(issue);
        assertTrue(issue.contains("no tool result contains"), issue);
        String read = "The check is:\n```java\nprivate boolean sameEntry(final Page cached, final Page requested) {\n"
                + "    return cached.version() == requested.version()\n"
                + "        && Objects.equals(cached.path(), requested.path())\n"
                + "        && Objects.equals(cached.locale(), requested.locale());\n}\n```";
        assertNull(AnswerGate.quotedCodeNotInEvidenceIssue(read, evidence));
        // a one-line fence (a command, a path) is not a quotation claim
        assertNull(AnswerGate.quotedCodeNotInEvidenceIssue("Run:\n```\n./gradlew :x:test --tests Foo\n```", evidence));
        assertNull(AnswerGate.quotedCodeNotInEvidenceIssue("no fences here", evidence));
    }

    // a verdict is read by a second, different model against the evidence
    @Test
    void aSecondReviewersUnsupportedClaimsRefuseTheVerdict() {
        assertTrue(ReviewPanel.isVerdictAnswer("**Verdict: TRUE POSITIVE**\n\nReasoning: ..."));
        assertTrue(ReviewPanel.isVerdictAnswer("Verdict: false-positive, the check is sound."));
        assertFalse(ReviewPanel.isVerdictAnswer("The test was composed and committed as abc1234."));
        String reply = "Here is my review.\n{\"unsupported_claims\": [\"a French reader gets the English page — "
                + "sameEntry (41-46) compares the path, so two locales share one entry\", \"stale pages survive a "
                + "deploy — the version is compared equal\"], \"verdict_follows\": false, \"note\": \"Without those "
                + "two claims nothing the reader sees remains.\"}";
        String issue = AnswerGate.verdictCriticIssue(reply, "local (deepseek)");
        assertNotNull(issue);
        assertTrue(issue.contains("second reviewer (local (deepseek))"), issue);
        assertTrue(issue.contains("English page") && issue.contains("survive a deploy"), issue);
        assertTrue(issue.contains("does not follow") && issue.contains("nothing the reader sees"), issue);
        // supported claims, verdict follows → no issue; the verdict not following on its own is an issue
        assertNull(AnswerGate.verdictCriticIssue("{\"unsupported_claims\": [], \"verdict_follows\": true, \"note\": \"ok\"}", "local"));
        assertNotNull(AnswerGate.verdictCriticIssue("{\"unsupported_claims\": [], \"verdict_follows\": false}", "local"));
        // prose, garbage or nothing from the reviewer never refuses
        assertNull(AnswerGate.verdictCriticIssue("I think it is fine.", "local"));
        assertNull(AnswerGate.verdictCriticIssue("{not json", "local"));
        assertNull(AnswerGate.verdictCriticIssue(null, "local"));
    }

    // a cut-off rewrite is not an answer; a rewrite that rules the opposite is a disagreement
    @Test
    void aCutOffRewriteAndAFlippedRulingAreRecognised() {
        assertTrue(AnswerGate.endsMidSentence("Because these fields must match exactly, a reader never gets a "
                + "foreign page (the `locale` is part of the key"));
        assertTrue(AnswerGate.endsMidSentence("The check is:\n```java\nreturn a == b"));
        assertTrue(AnswerGate.endsMidSentence("the cached page cannot leak because"));
        assertTrue(AnswerGate.endsMidSentence("   "));
        assertFalse(AnswerGate.endsMidSentence("FALSE POSITIVE. The cache keeps one page per locale (lines 96-109)."));
        assertFalse(AnswerGate.endsMidSentence("**Verdict: FALSE POSITIVE**"));
        assertFalse(AnswerGate.endsMidSentence("Run:\n```\n./gradlew test\n```"));
        assertFalse(AnswerGate.endsMidSentence("- the path is compared (line 44)\n- the locale is compared (line 45)"));
        assertEquals("TRUE POSITIVE", AnswerGate.verdictOf("**Verdict: TRUE POSITIVE**\n\nReasoning ..."));
        assertEquals("FALSE POSITIVE", AnswerGate.verdictOf("false-positive: the comparison includes the locale"));
        assertNull(AnswerGate.verdictOf("The test was composed."));
        assertNull(AnswerGate.verdictOf(null));
    }

    // what counts as a synthesized ANSWER - prose, not a tool call, a plan, a failure text or a cut-off
    @Test
    void aSynthesizedAnswerIsProseNotAToolCall() {
        assertTrue(AnswerGate.isAnAnswerText("FALSE POSITIVE. sameEntry (41-46) compares the locale, so each "
                + "reader gets their own page."));
        assertFalse(AnswerGate.isAnAnswerText("<|channel|>commentary to=repo_browser.bash code<|message|>sed -n '300,380p' /r/A.java"));
        assertFalse(AnswerGate.isAnAnswerText("{\"tool\": \"Bash\", \"parameters\": {\"command\": \"ls\"}}"));
        assertFalse(AnswerGate.isAnAnswerText("{\"branches\": []}"));
        assertFalse(AnswerGate.isAnAnswerText("Extraction Failed: 'message' object structure was unexpected."));
        assertFalse(AnswerGate.isAnAnswerText("the cached page cannot leak because"));
        assertFalse(AnswerGate.isAnAnswerText("  "));
        assertFalse(AnswerGate.isAnAnswerText(null));
    }

    // a critic demanding the method's later steps in the current plan is dropped
    @Test
    void aCriticDemandingLaterStepsIsNotADefect() {
        assertTrue(ReviewPanel.demandsLaterSteps("[review] Plan only performs step 1 (locate and read the cited code) but omits the required steps to trace callers, gather runtime evidence, compare findings with code behavior, and produce the verdict."));
        assertTrue(ReviewPanel.demandsLaterSteps("[completeness] The plan only locates the file and does not read or analyze the hash computation, nor examine surrounding logic, to determine whether the finding is real."));
        assertFalse(ReviewPanel.demandsLaterSteps("the plan re-runs the reach lookup instead of composing with the verb it named"));
        assertFalse(ReviewPanel.demandsLaterSteps("[grounding] The directory path supplied does not match the repository location provided by the user."));
        assertFalse(ReviewPanel.demandsLaterSteps(null));
    }

    // a quoted block mixing printed lines with invented ones is a paraphrase shown as a quotation
    @Test
    void aQuotedCodeBlockThatMixesPrintedAndInventedLinesIsRefused() {
        String evidence = "Bash => {status=Success, output="
                + "   194\t        final var reader = Files.newBufferedReader(source);\n"
                + "   195\t        final var header = reader.readLine();\n"
                + "   196\t        final var columns = header == null ? List.<String>of() : splitHeader(header);\n"
                + "   197\t        final var missing = requiredColumns(columns, expected);\n"
                + "   198\t        if (!missing.isEmpty()) {\n"
                + "   205\t            throw new ImportException(MISSING_COLUMNS);\n}";
        String paraphrase = "**FALSE POSITIVE**\n\nWhen a CSV file is imported, the loader does:\n\n```java\n"
                + "final var header = reader.readLine();\n"
                + "if (header == null || header.isBlank()) {\n"
                + "    throw new ImportException(MISSING_COLUMNS);\n}\n```\n\n(lines 191-206 of CsvLoader.java)";
        String issue = AnswerGate.paraphrasedQuoteIssue(paraphrase, evidence);
        assertNotNull(issue);
        assertTrue(issue.contains("if (header == null || header.isBlank()) {")
                && issue.contains("appears in no tool output"), issue);
        String verbatim = paraphrase.replace("if (header == null || header.isBlank()) {\n",
                "final var missing = requiredColumns(columns, expected);\nif (!missing.isEmpty()) {\n");
        assertNull(AnswerGate.paraphrasedQuoteIssue(verbatim, evidence), "every substantial line is in the evidence (numbers and indentation aside)");
        String proposal = "**TRUE POSITIVE**\n\nA fix would skip a leading byte mark:\n\n```java\n"
                + "header = stripLeadingMark(header);\nif (header.isBlank()) { return List.of(); }\n```";
        assertNull(AnswerGate.paraphrasedQuoteIssue(proposal, evidence), "a proposed fix is not a quotation");
        String illustration = "**FALSE POSITIVE**\n\n```java\nsomething entirely unrelated to any read line here();\nanother line that no read printed at all();\n```";
        assertNull(AnswerGate.paraphrasedQuoteIssue(illustration, evidence), "no line of the block is in the evidence: not a mixed quotation (an illustration, or a cut read)");
        assertNull(AnswerGate.paraphrasedQuoteIssue("no code block here", evidence));
        assertNull(AnswerGate.paraphrasedQuoteIssue(null, evidence));
    }

    // a critic's repeat claim is recognised (its truth is checked against the signatures)
    @Test
    void aCriticsRepeatClaimIsRecognised() {
        assertTrue(ReviewPanel.claimsARepeat("[local-14b] The plan repeats a call that already succeeded this turn (Run CodeGraph). The agent should respond with Shape B or propose a new action."));
        assertTrue(ReviewPanel.claimsARepeat("[necessity] This step re-runs the same lookup as iteration 2."));
        assertTrue(ReviewPanel.claimsARepeat("[grounding] The action duplicates a call already made."));
        assertFalse(ReviewPanel.claimsARepeat("[grounding] The plan reads lines 30-80 but the method starts at 36."));
        assertFalse(ReviewPanel.claimsARepeat("[review] The test class already exists in the module, so a new one is not needed."), "\"already exists\" is not a repeat claim");
        assertFalse(ReviewPanel.claimsARepeat(null));
    }

    // a claim a reviewer disputed is checked again when no reviewer is left
    @Test
    void aClaimAReviewerDisputedIsCheckedAgainWhenNoReviewerIsLeft() {
        String issue = "A second reviewer (DeepSeek-plan) read your verdict against the evidence and finds these claims have NO line behind them or a line CONTRADICTS them: "
                + "The size limit is applied to every upload type and is computed from the total byte size of "
                + "the entire archive, so the nested file's size counts toward it — no quoted line states this"
                + " | The uploader exceeds the storage quota — no line shows how the quota is counted. Drop each "
                + "such claim or cite the line that shows it, then re-decide the verdict from what remains.";
        List<String> claims = ReviewPanel.disputedClaimsOf(issue);
        assertEquals(2, claims.size(), claims.toString());
        assertTrue(claims.get(0).startsWith("The size limit is applied to every upload type"), claims.get(0));
        String sameClaim = "**Verdict: FALSE POSITIVE**\n\nThe upload settings define a size limit. This limit is "
                + "applied to every upload type and computed from the total byte size of the entire archive, so "
                + "the nested file counts toward it.";
        String still = AnswerGate.stillDisputedIssue(sameClaim, claims);
        assertNotNull(still);
        assertTrue(still.contains("disputed this claim earlier this turn"), still);
        String supported = "**Verdict: FALSE POSITIVE**\n\nUploadLimits.java:97 resolves MAX_BYTES to `bytes`, "
                + "the archive size (line 91), so the nested file is measured by the limit.";
        assertNull(AnswerGate.stillDisputedIssue(supported, claims), "a differently grounded statement is not the disputed claim");
        assertNull(AnswerGate.stillDisputedIssue(sameClaim, List.of()));
        assertTrue(ReviewPanel.disputedClaimsOf("no marker here").isEmpty());
        assertTrue(ReviewPanel.disputedClaimsOf(null).isEmpty());
    }

    // a critic saying the plan's own action "was not performed" refuses the plan for being a plan
    @Test
    void aCriticSayingThePlansOwnActionWasNotPerformedIsDropped() {
        String plan = "{\"branches\":[{\"name\":\"Run CodeGraph locate X\",\"actions\":[{\"tool\":\"CodeGraph\","
                + "\"parameters\":{\"action\":\"locate\",\"symbol\":\"ShippingQuote#addHandlingCharge\"}}]}]}";
        assertTrue(ReviewPanel.complainsThePlanIsNotYetDone("[local-14b] The agent did not perform the first step of locating the cited code (CodeGraph action=locate on the type and the method) as instructed.", plan));
        assertTrue(ReviewPanel.complainsThePlanIsNotYetDone("[grounding] CodeGraph locate has not been run yet, so the location is unknown.", plan));
        assertFalse(ReviewPanel.complainsThePlanIsNotYetDone("[grounding] The plan did not read the method body after locating it.", plan), "a different action than the plan's is a real demand (dropped elsewhere if it is a later step)");
        assertFalse(ReviewPanel.complainsThePlanIsNotYetDone("[necessity] The symbol is spelled with a dot; CodeGraph takes Type#method.", plan), "no not-done complaint");
        assertFalse(ReviewPanel.complainsThePlanIsNotYetDone("[local-14b] The agent did not perform the locate.", "{\"branches\":[{\"actions\":[{\"tool\":\"Bash\",\"parameters\":{\"command\":\"ls\"}}]}]}"), "the plan's tool is not the one named");
        assertFalse(ReviewPanel.complainsThePlanIsNotYetDone(null, plan));
    }

    // the reviewer sees the results the verdict cites before the tail
    @Test
    void theReviewerSeesTheResultsTheVerdictCitesFirst() {
        StringBuilder results = new StringBuilder();
        results.append("Bash => {status=Success, output=    15\tpublic class ShippingQuote implements QuoteStep {\n"
                + "    23\t        quote.setHandlingCents(rates.handlingCents());\n}\n");
        for (int i = 0; i < 40; i++) {
            results.append("CodeGraph => {status=Success, action=callers, symbol=Other#thing" + i + ", callers=[" + "x".repeat(400) + "]}\n");
        }
        String answer = "**FALSE POSITIVE** — ShippingQuote#addHandlingCharge sets only rates.handlingCents() "
                + "(line 23 of ShippingQuote.java).";
        String evidence = AnswerGate.evidenceFor(results.toString(), answer, 6000);
        assertTrue(evidence.length() <= 6000 + 200, "" + evidence.length());
        assertTrue(evidence.startsWith("--- results the verdict cites ---"), evidence.substring(0, 80));
        assertTrue(evidence.contains("setHandlingCents(rates.handlingCents())"),
                "the cited read is in front of the reviewer");
        assertTrue(evidence.contains("--- the latest results ---") && evidence.contains("Other#thing39"), "the tail follows");
        String small = "Bash => {status=Success, output=ok}\n";
        assertEquals(small, AnswerGate.evidenceFor(small, answer, 6000), "under the cap: the results as they are");
    }

    // a critic calling a documented action, or a registered tool, an invalid tool is dropped
    @Test
    void aCriticCallingAValidActionOrToolInvalidIsDropped() {
        Set<String> tools = Set.of("CodeGraph", "LocateFile", "Bash");
        Set<String> actions = Set.of("locate", "callers", "tests", "reach");
        String plan = "{\"branches\":[{\"actions\":[{\"tool\":\"CodeGraph\",\"parameters\":{\"action\":\"tests\",\"symbol\":\"X#y\"}}]}]}";
        assertTrue(ReviewPanel.callsAValidToolOrActionInvalid("[reference] Invalid tool 'tests' used in plan", plan, tools, actions));
        assertTrue(ReviewPanel.callsAValidToolOrActionInvalid("[local-14b] The plan uses the 'tests' tool, which is invalid according to the current context.", plan, tools, actions));
        assertTrue(ReviewPanel.callsAValidToolOrActionInvalid("[review] Unknown tool 'LocateFile'.", plan, tools, actions));
        assertFalse(ReviewPanel.callsAValidToolOrActionInvalid("[reference] Invalid tool 'container.exec' used in plan", plan, tools, actions), "a made-up tool IS invalid");
        assertFalse(ReviewPanel.callsAValidToolOrActionInvalid("[reference] Invalid tool 'tests' used in plan", "{\"branches\":[{\"actions\":[{\"tool\":\"Bash\",\"parameters\":{\"command\":\"ls\"}}]}]}", tools, actions), "an action name without its tool in the plan");
        assertFalse(ReviewPanel.callsAValidToolOrActionInvalid("[grounding] The symbol is unknown to the graph.", plan, tools, actions));
        assertFalse(ReviewPanel.callsAValidToolOrActionInvalid(null, plan, tools, actions));
    }

    // a type name no result contains is an invented identifier
    @Test
    void aTypeNameNoResultContainsIsInvented() {
        String evidence = "Bash => {output=   90\t final var zones = zonesOrThrow(plan, context::activeZones);}\n"
                + "CodeGraph => {status=Success, action=locate, symbol=ZonePlanBuilder, "
                + "declarations=[{file=/w/x/ZonePlanBuilder.java, line=52}]}\n";
        String request = "Review the finding about HeatingCurveCalculator#targetFor.";
        String answer = "FALSE POSITIVE. The zones are checked when the plan runs (handled by ZonePlanExecutor and its "
                + "timer); ZonePlanBuilder only collects them.";
        String issue = AnswerGate.fabricatedIdentifiersIssue(answer, evidence, request);
        assertNotNull(issue);
        assertTrue(issue.contains("ZonePlanExecutor") && !issue.contains("ZonePlanBuilder"), issue);
        assertNull(AnswerGate.fabricatedIdentifiersIssue("FALSE POSITIVE. ZonePlanBuilder line 90 gathers the zones; "
                + "HeatingCurveCalculator computes only the target.", evidence, request),
                "names the results and the request hold");
        assertNull(AnswerGate.fabricatedIdentifiersIssue("A fix could add a new class such as ZoneLimitValidator.",
                evidence, request), "a proposal is not a finding");
    }

    // a critic doubting that a registered tool is installed is dropped
    @Test
    void aCriticDoubtingARegisteredToolExistsIsDropped() {
        Set<String> tools = Set.of("CodeGraph", "LocateFile", "Bash", "HindsightDebugger");
        assertTrue(ReviewPanel.doubtsARegisteredTool("[local-14b] The plan's chosen tool is not capable of the task as described. CodeGraph locate / callers / reach are not standard CLI tools and cannot be assumed to be installed unless explicitly stated in CONTEXT.", tools));
        assertTrue(ReviewPanel.doubtsARegisteredTool("[grounding] HindsightDebugger is not available in this environment.", tools));
        assertTrue(ReviewPanel.doubtsARegisteredTool("[review] Unknown tool CodeGraph — use grep instead.", tools));
        assertFalse(ReviewPanel.doubtsARegisteredTool("[review] LocateFile cannot find a method symbol; CodeGraph locate takes the symbol.", tools), "a capability remark that names no existence doubt stays");
        assertFalse(ReviewPanel.doubtsARegisteredTool("[grounding] The file /w/X.java does not exist in the repository.", tools), "a file that does not exist is a real defect");
        assertFalse(ReviewPanel.doubtsARegisteredTool("[grounding] codeql is not installed on this machine.", tools), "codeql is not a registered tool name");
        assertFalse(ReviewPanel.doubtsARegisteredTool(null, tools));
    }

    // a citation into a read file must fall inside a range the turn read
    @Test
    void aCitationOutsideEveryReadRangeOfTheFileIsRefused() {
        Set<String> executed = new LinkedHashSet<>(List.of(
                "CodeGraph|{action=locate, symbol=InvoiceTotals#roundedSum}",
                "Bash|{command=sed -n '150,250p' /w/billing/x/print/InvoicePrinter.java}",
                "Bash|{command=sed -n '30,80p' billing/x/InvoiceTotals.java}"));
        String answer = "**FALSE POSITIVE**\n\n1. `InvoiceTotals.roundedSum` (lines 36\u201160) rounds the sum once.\n"
                + "2. In `InvoicePrinter.print` (lines 75\u201184) the code calls roundedSum and prints TOTAL_LINE.";
        String issue = AnswerGate.uncoveredCitationIssue(answer, executed);
        assertNotNull(issue);
        assertTrue(issue.contains("InvoicePrinter lines 75-84") && issue.contains("read only lines 150-250"), issue);
        String fixed = answer.replace("lines 75\u201184", "lines 195\u2011205");
        assertNull(AnswerGate.uncoveredCitationIssue(fixed, executed), "195-205 lies inside 150-250; 36-60 overlaps 30-80");
        assertNull(AnswerGate.uncoveredCitationIssue("InvoiceArchive.java:75 calls the sum", executed),
                "a file never read is not judged here");
        assertNull(AnswerGate.uncoveredCitationIssue("InvoicePrinter.java:158 declares print", executed.stream()
                .filter(sig -> sig.startsWith("CodeGraph")).collect(Collectors.toSet())), "no reads at all: no opinion");
        assertNull(AnswerGate.uncoveredCitationIssue(null, executed));
    }

    // the report's consequences, quoted by a FALSE POSITIVE verdict to reject them, are not its claims
    @Test
    void aReviewerDisputingTheReportsOwnClaimsDoesNotDisputeADenial() {
        String request = "Report: the cache key omits the locale, so two readers can share a single cached page "
                + "and have one reader's saved settings shown to another reader.";
        String answer = "**FALSE POSITIVE**\n\nThe cache stores one entry per locale (PageCache.java:205). "
                + "The claim of \u201csharing a single cached page\u201d is wrong; no entry is shared.";
        String reply = "{\"unsupported_claims\": [\"Two readers share a single cached page — the cache keeps one "
                + "entry per locale\", \"One reader's saved settings are shown to another reader — no code copies "
                + "settings\"], \"verdict_follows\": true, \"note\": \"ok\"}";
        assertNull(AnswerGate.verdictCriticIssue(reply, "Groq", answer, request), "both disputed claims are the report's, which the verdict denies");
        String own = "{\"unsupported_claims\": [\"The page is rendered before any lookup is counted — no quoted "
                + "line shows the lookup order\"], \"verdict_follows\": true}";
        assertNotNull(AnswerGate.verdictCriticIssue(own, "Groq", answer, request), "a claim in the verdict's own words stays disputed");
        String truePositive = answer.replace("FALSE POSITIVE", "TRUE POSITIVE");
        assertNotNull(AnswerGate.verdictCriticIssue(reply, "Groq", truePositive, request), "a TRUE POSITIVE asserts the report's claims — they stay disputed");
        assertNotNull(AnswerGate.verdictCriticIssue(reply, "Groq"), "without the answer and request nothing is dropped");
    }

    // the later-steps complaint in its "plan incomplete: missing steps …" shape
    @Test
    void aCriticCallingAOneStepPlanIncompleteIsNotADefect() {
        assertTrue(ReviewPanel.demandsLaterSteps("[grounding] Plan incomplete: missing steps to trace the method call chain, gather runtime evidence, compare findings with code behavior, and produce a verdict."));
        assertTrue(ReviewPanel.demandsLaterSteps("[completeness] Plan missing required steps: trace callers, gather runtime evidence, compare findings with code behavior, and produce a verdict."));
        assertTrue(ReviewPanel.demandsLaterSteps("[review] Plan does not include all required steps; only locates the method."));
        assertTrue(ReviewPanel.demandsLaterSteps("[necessity] The plan lacks the steps that trace callers and compare the findings with the runtime recording."));
        assertTrue(ReviewPanel.demandsLaterSteps("[local-14b] The plan does not describe how to run runtime evidence using HindsightDebugger."), "a later-step demand");
        assertTrue(ReviewPanel.demandsLaterSteps("[grounding] No recording of a test that reaches the method is planned."), "a later-step demand");
        assertFalse(ReviewPanel.demandsLaterSteps("[grounding] The HindsightDebugger step is missing the testClass parameter."), "a parameter is a real defect");
        assertFalse(ReviewPanel.demandsLaterSteps("[grounding] Plan is missing the projectDir parameter that CodeGraph needs for the callers step."));
        assertFalse(ReviewPanel.demandsLaterSteps("[grounding] The plan is missing a step to verify the file exists before reading it."));
        assertFalse(ReviewPanel.demandsLaterSteps("[reference] The plan reads lines 30-80 but the method starts at line 36 and ends at 110."));
        assertFalse(ReviewPanel.demandsLaterSteps("[necessity] The plan is incomplete: the sed command has no file argument."));
    }

    // a context too long for the served model is trimmed from its oldest iterations
    @Test
    void anOversizedContextLosesItsOldestIterationsFirst() {
        StringBuilder ctx = new StringBuilder("Current request: rule on the finding.\n");
        for (int i = 1; i <= 6; i++) {
            ctx.append("[Iteration ").append(i).append("] Executed branch 'read").append(i).append("'. Tool results: {Bash={output=").append("x".repeat(1000)).append("}}\n");
        }
        String fitted = PlanPromptBuilder.fitContext(ctx.toString(), 3500);
        assertTrue(fitted.length() <= 3500 + 400, "" + fitted.length());
        assertTrue(fitted.startsWith("Current request: rule on the finding."), fitted.substring(0, 60));
        assertTrue(fitted.contains("earlier iteration(s) trimmed to fit the model's context"), fitted.substring(0, 200));
        assertTrue(fitted.contains("[Iteration 6]") && fitted.contains("[Iteration 5]") && !fitted.contains("[Iteration 1]"), "the newest stay, the oldest go");
        String whole = ctx.toString();
        assertSame(whole, PlanPromptBuilder.fitContext(whole, 100_000), "a fitting context passes through");
    }

    // final answers that were the model's working notes, approved by the same-model reviewer
    @Test
    void workingNotesAreNotAFinalAnswer() {
        String reEvaluating = "**Verdict: FALSE POSITIVE**\n\n*Correction/Refinement based on Iteration 13 output:* "
                + "The `keyOf` method shown in Iteration 13 explicitly includes `locale`.\n\n*Re-evaluating the "
                + "Index:* If the key does not include the reader...";
        String issue = AnswerGate.workingNotesIssue(reEvaluating);
        assertNotNull(issue);
        assertTrue(issue.contains("Correction/Refinement") && issue.contains("Re-evaluat"), issue);
        assertNotNull(AnswerGate.workingNotesIssue("The reader's page will be cached as a new entry (because the key "
                + "lookup does NOT include the version? Wait.)\n\nThe verdict is FALSE POSITIVE."));
        assertNotNull(AnswerGate.workingNotesIssue(
                "**Wait, let's re-evaluate based on the key.** If `keyOf` does not include it..."));
        assertNotNull(AnswerGate.workingNotesIssue("**Verdict Re-evaluation:**\nThe finding describes a True Positive for the mechanism."));
        assertNotNull(AnswerGate.workingNotesIssue("I will stick with FALSE POSITIVE because the impact is incorrect."));
        assertNotNull(AnswerGate.workingNotesIssue("Verdict: FALSE POSITIVE. Let's check the locale aspect. The "
                + "locale defaults to English. Let's look at the eviction. Eviction needs the version."),
                "two think-aloud steps");
    }

    @Test
    void aCleanReportIsNotWorkingNotes() {
        String clean = "**Verdict: FALSE POSITIVE**\n\n1. `sameZone` (ZoneTable.java:335-343) compares "
                + "`waitForWarmup`, name and floor but not the sensor.\n2. A merged zone needs the same floor; "
                + "heating is gated by it (HeaterSwitch.java:75-76).\n3. The reporter is right about the mechanism "
                + "and wrong about the impact. Let's be clear: no room overheats.\n```java\n// wait, this is code: "
                + "wait. hmm\n```";
        assertNull(AnswerGate.workingNotesIssue(clean),
                "one think-aloud phrase, waitForWarmup, and code are not working notes");
        assertNull(AnswerGate.workingNotesIssue("The heater may wait. Then it starts when the last sensor reports."));
        assertNull(AnswerGate.workingNotesIssue(null));
    }

    // "pass" in prose is not a test verdict
    @Test
    void passInProseIsNotATestVerdict() {
        for (String prose : new String[] {
                "`lookup(draftPage)` passes the `draftPage` object to the cache.",
                "two different readers can produce `Page` objects that pass `sameEntry`.",
                "The finding assumes the caller blindly passes this ID to other readers who then open it.",
                "The locale is shared (required for `sameEntry` to pass), so both see one page.",
                "The reader's request fails with ENTRY_ALREADY_CACHED (line 205)."}) {
            assertNull(AnswerGate.fabricationIssue(prose, false, false), prose);
        }
        for (String verdict : new String[] {"The test now passes (green).", "All tests pass after the fix.",
                "The spec fails before the change.", "BUILD SUCCESSFUL in 41s", "The suite still fails.", "It now passes."}) {
            assertNotNull(AnswerGate.fabricationIssue(verdict, false, false), verdict);
        }
    }

    // the helper critic refused the request's own command because the checker's absolute path "is
    // unlikely to exist in the execution environment", and the next plan searched the home directory for it instead
    @Test
    void doubtingThatAPathTheRequestNamesExistsIsNoDefect() {
        String request = "Then ONE Bash command: ./gradlew test && /opt/rw/neutral-check src/main && git commit -m x";
        assertTrue(ReviewPanel.doubtsAPathTheRequestNames("[local-14b] The Bash command uses an absolute path to the neutral-check "
                + "script (/opt/rw/neutral-check) which is unlikely to exist in the execution environment.", request));
        assertFalse(ReviewPanel.doubtsAPathTheRequestNames("[local-14b] The path /tmp/other/notes.txt does not exist.", request),
                "a path the request does not name");
        assertFalse(ReviewPanel.doubtsAPathTheRequestNames("[completeness] The plan leaves out /opt/rw/neutral-check.", request),
                "no doubt about the path");
    }

    @Test
    void anAnswerThatReportsTestsPassingNeedsATestRunThisTurn() {
        // an answer said "All 28 tests in the repository pass" though no test ran
        // that turn; the outcome check only looked at whether the request asked for a run
        String edited = "TreeEdit={status=Success, file=/w/A.java, items=1, }";
        String claim = "The class is in place and compiles. All 28 tests in the repository pass.";
        String issue = AnswerGate.claimedTestPassIssue(claim, edited);
        assertNotNull(issue);
        assertTrue(issue.contains("All 28 tests in the repository pass") && issue.contains("no test ran"), issue);
        assertNotNull(AnswerGate.deterministicAnswerIssue("port the class", edited, claim), "the deterministic chain runs it");
        assertNull(AnswerGate.claimedTestPassIssue(claim, "Bash={status=Success, output=$ ./gradlew test --offline -q\n"
                + "BUILD SUCCESSFUL, exitCode=0}"), "a test run this turn supports it");
        assertNull(AnswerGate.claimedTestPassIssue("The tests should pass once part 3 has run.", edited), "a prospect");
        assertNull(AnswerGate.claimedTestPassIssue("Two tests failed: see the output.", edited), "a failure is not checked");
        assertNotNull(AnswerGate.claimedTestPassIssue("The suite is green.", edited));
    }

    // a request said "Answer with 'done' once the last round ran"; the model gate refused "done" for reporting no
    // substance, and the answers written in its place invented a passing suite and a stricter regex
    @Test
    void theAnswerTheRequestDictatesIsTheAnswer() {
        String request = "Rounds: 0. ONE TreeEdit action ... Answer with 'done' once the last round ran (nothing is committed in this part).";
        assertEquals("done", AnswerGate.dictatedAnswer(request));
        assertTrue(AnswerGate.isTheDictatedAnswer(request, "done"));
        assertTrue(AnswerGate.isTheDictatedAnswer(request, " Done. "));
        assertFalse(AnswerGate.isTheDictatedAnswer(request, "done - and the suite passes"), "anything more goes to the gate");
        assertNull(AnswerGate.dictatedAnswer("Answer with the commit hash git printed."), "no quoted words: nothing dictated");
        assertEquals("FIXED", AnswerGate.dictatedAnswer("When the test is green, reply exactly \"FIXED\"."));
        assertFalse(AnswerGate.isTheDictatedAnswer(null, "done"));
    }
}
