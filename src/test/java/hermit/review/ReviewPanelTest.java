package hermit.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure tests for the review panel's issue dedup - when several lenses and the
 * remote 14B critic raise the SAME point, the planner should see it once. */
class ReviewPanelTest {

    @Test
    void collapsesRestatementsAcrossLensesAndRemoteCritic() {
        List<String> raw = List.of(
                "[completeness] The plan does not fetch the title of the pull request.",
                "[necessity] the plan does not fetch the title of the PULL REQUEST",
                "[local-14b] The plan does not fetch the title of the pull request",
                "[reference] The URL is already provided in the context.");
        List<String> deduped = ReviewPanel.dedupeIssues(raw);
        // The three "does not fetch the title" restatements collapse to one; the
        // distinct URL point remains -> 2 issues total.
        assertEquals(2, deduped.size(), deduped.toString());
        assertTrue(deduped.stream().anyMatch(s -> s.contains("fetch the title")), deduped.toString());
        assertTrue(deduped.stream().anyMatch(s -> s.contains("already provided")), deduped.toString());
    }

    @Test
    void issuesDemandingUndeclaredParametersAreInventions() {
        // "missing the required parameter 'exceptionType'" - TreeCodegen declares no such parameter
        Set<String> vocab = new HashSet<>();
        ReviewPanel.addWords(vocab, "TreeCodegen — params: task, file, method, templateMethod, expectTestOutcome, "
                + "targetReach, module, testFilter, items");
        vocab.add("expecttestoutcome");
        assertTrue(ReviewPanel.fabricatedParameterIssue(
                "[completeness] The TreeCodegen action is missing the required parameter 'exceptionType' which specifies "
                        + "the type of exception to be thrown during testing.", vocab));
        assertTrue(ReviewPanel.fabricatedParameterIssue(
                "[grounding] The plan should include a `mockBehaviour` parameter for TreeCodegen.", vocab));
        // a real parameter being demanded is a legitimate complaint
        assertFalse(ReviewPanel.fabricatedParameterIssue(
                "[completeness] TreeCodegen lacks the 'expectTestOutcome' parameter (the test must be RED).", vocab));
        // an issue that names no parameter is untouched
        assertFalse(ReviewPanel.fabricatedParameterIssue(
                "[completeness] The plan omits the reach step.", vocab));
        // an empty vocabulary (unparseable plan) never drops anything
        assertFalse(ReviewPanel.fabricatedParameterIssue(
                "missing the required parameter 'exceptionType'", Set.of()));
    }

    @Test
    void demandsForTheGeneratedCodeInThePlanAreMisreadings() {
        assertTrue(ReviewPanel.demandsGeneratedCode(
                "[completeness] The plan does not specify the exact content of the new failing unit test in "
                        + "PrintSpoolerTest.java."));
        assertTrue(ReviewPanel.demandsGeneratedCode(
                "[completeness] The plan should include the specific assertions the test will make."));
        // real gaps stay
        assertFalse(ReviewPanel.demandsGeneratedCode("[completeness] The plan omits the reach step."));
        assertFalse(ReviewPanel.demandsGeneratedCode(
                "[completeness] TreeCodegen is missing the required parameter 'expectTestOutcome'."));
        assertFalse(ReviewPanel.demandsGeneratedCode(
                "[grounding] The templateMethod 'fooBar' does not exist in the test class."));
        // the phrasings that slipped past the sequence pattern
        assertTrue(ReviewPanel.demandsGeneratedCode("[completeness] The plan omits the step to actually write the "
                + "failing unit test in PrintSpoolerTest.java. The TreeCodegen tool is used to generate the test, but "
                + "the specific code that should be written is not included in the plan."));
        assertTrue(ReviewPanel.demandsGeneratedCode("[necessity] The plan does not explicitly state how the failing "
                + "unit test will be written."));
        // real gaps that mention code-ish words are kept
        assertFalse(ReviewPanel.demandsGeneratedCode(
                "[grounding] The plan writes the test into src/main/java, which contradicts the test source root."));
        assertFalse(ReviewPanel.demandsGeneratedCode("[completeness] The plan is missing the module parameter."));
        assertTrue(ReviewPanel.demandsGeneratedCode("[completeness] The plan omits the step to actually write the "
                + "failing unit test in PrintSpoolerTest.java."));
        assertFalse(ReviewPanel.demandsGeneratedCode("[completeness] The plan omits the reach step."));
    }

    @Test
    void absoluteAndRelativeFormsOfOnePathAreNotAContradiction() {
        assertTrue(ReviewPanel.falsePathContradiction("[completeness] The TreeCodegen action's 'file' parameter uses the "
                + "path '/work/x/projects/print-service/spool/spool-core/src/test/java/com/example/print/"
                + "spool/PrintSpoolerTest.java', which contradicts the provided context that states the tests "
                + "live under 'spool/spool-core/src/test/java'."));
        // a genuinely different path is a real contradiction
        assertFalse(ReviewPanel.falsePathContradiction("[grounding] The plan writes to spool/spool-core/src/main/java/"
                + "PrintSpoolerTest.java, which contradicts the context's spool/spool-core/src/test/java."));
        // no contradiction wording → untouched
        assertFalse(ReviewPanel.falsePathContradiction("[completeness] The plan omits the reach step."));
    }

    @Test
    void disputesOfDocumentedActionsAreInventions() {
        // "action=tests is incorrect … use reach or callees" - tests IS a documented CodeGraph action
        Set<String> vocab = new HashSet<>();
        ReviewPanel.addWords(vocab, "CodeGraph: actions locate|callers|callees|reach|escapes|catches|impact|emulate|flowreach|tests");
        assertTrue(ReviewPanel.disputesDocumentedValue("[completeness] The CodeGraph action with 'action=tests' is incorrect "
                + "for the task of identifying existing test methods. It should use the 'reach' or 'callees' actions instead.", vocab));
        assertTrue(ReviewPanel.disputesDocumentedValue("[grounding] The action `flowreach` is not a valid CodeGraph action.", vocab));
        // the phrasings a critic actually used against a documented action
        assertTrue(ReviewPanel.disputesDocumentedValue("[grounding] The plan uses CodeGraph with the 'tests' action, "
                + "which is not documented for the CodeGraph tool in the TOOL CONTRACTS.", vocab));
        assertTrue(ReviewPanel.disputesDocumentedValue("[necessity] The 'tests' action is undocumented.", vocab));
        assertTrue(ReviewPanel.disputesDocumentedValue("[grounding] 'flowreach' is missing from the contracts.", vocab));
        // the shape that survived an earlier fix - the negation sits after a "but according to" clause
        assertTrue(ReviewPanel.disputesDocumentedValue("[grounding] The plan assumes that the CodeGraph tool has an "
                + "action called 'tests', but according to the TOOL CONTRACTS, the valid actions for CodeGraph are "
                + "locate, callers, callees and reach.", vocab));
        // a completeness complaint that a DOCUMENTED parameter is absent must SURVIVE (it asks to add, not to deny)
        assertFalse(ReviewPanel.disputesDocumentedValue("[completeness] TreeCodegen lacks the 'expectTestOutcome' "
                + "parameter (the test must be RED).", vocab));
        assertFalse(ReviewPanel.disputesDocumentedValue("[completeness] The plan omits the 'templateMethod' parameter.", vocab));
        // an undocumented value really is a defect
        assertFalse(ReviewPanel.disputesDocumentedValue("[grounding] The action 'testsForMethod' does not exist.", vocab));
        assertFalse(ReviewPanel.disputesDocumentedValue("[completeness] The plan omits the reach step.", vocab));
    }

    // the critic denied an unquoted op that the tool's own description documents
    @Test
    void anUnquotedDocumentedOpCalledUnsupportedIsAnInvention() {
        Set<String> vocab = new HashSet<>();
        ReviewPanel.addWords(vocab, "TreeEdit: insertMember adds a member; moveMember moves the member named by method "
                + "before the member named by anchor");
        assertTrue(ReviewPanel.disputesDocumentedValue("[grounding] TreeEdit does not support the moveMember operation; "
                + "its documented operations exclude moveMember, so this action cannot execute as planned.", vocab));
        assertFalse(ReviewPanel.disputesDocumentedValue("[grounding] TreeEdit does not support the swapMembers operation.",
                vocab), "an op the tool does not document");
        assertFalse(ReviewPanel.disputesDocumentedValue("[completeness] The plan omits the moveMember of fixture.", vocab),
                "asking for a documented op is not a denial");
    }

    // reviewers denied a documented op in words the dispute phrases did not hold ("does not list ... as a supported
    // operation", "only supports ...")
    @Test
    void aDocumentedOpLeftOffAReviewersShortListIsAnInvention() {
        Set<String> vocab = new HashSet<>();
        ReviewPanel.addWords(vocab, "TreeEdit: insertMember adds a member; moveMember moves the member named by method "
                + "before the member named by anchor");
        assertTrue(ReviewPanel.disputesDocumentedValue("[helper] The plan uses the operation \"moveMember\" in the "
                + "TreeEdit actions, but the TreeEdit tool contract does not list \"moveMember\" as a supported "
                + "operation. Only insertAtEnd, insertMember and addType are allowed.", vocab));
        assertTrue(ReviewPanel.disputesDocumentedValue("[hosted critic] The TreeEdit action includes two moveMember "
                + "items, but TreeEdit only supports insertAtEnd, insertMember and addType. This action will be "
                + "rejected.", vocab));
        assertFalse(ReviewPanel.disputesDocumentedValue("[completeness] The plan only supports one moveMember of the "
                + "two the step needs; add the second.", vocab), "asking for more of a documented op is not a denial");
    }

    @Test
    void laterStepDemandsAndGeneratorDoubtsAreNotPlanGaps() {
        assertTrue(ReviewPanel.demandsLaterSteps("[completeness] The PLAN omits the emulator verdict, the real gradle "
                + "outcome, and the Hindsight evidence as required by the current request."));
        assertTrue(ReviewPanel.demandsLaterSteps("[completeness] The plan does not include committing the test."));
        assertFalse(ReviewPanel.demandsLaterSteps("[completeness] The plan omits the reach step."));
        assertFalse(ReviewPanel.demandsLaterSteps("[completeness] TreeCodegen is missing the parameter 'expectTestOutcome'."));
        // a demand about THIS step's parameters is kept even when it mentions the emulator gate
        assertFalse(ReviewPanel.demandsLaterSteps("[completeness] The plan omits expectTestOutcome=red, so the emulator gate cannot run."));
        assertTrue(ReviewPanel.demandsGeneratedCode("[grounding] The plan assumes that the TreeCodegen tool can generate a "
                + "unit test that specifically tests for the behavior where the interval process time advances."));
        assertFalse(ReviewPanel.demandsGeneratedCode("[grounding] The plan assumes the file lives under src/main/java."));
    }

    @Test
    void valuePrecomputationDemandsAgainstComposedTestsAreMisreadings() {
        // the composed test computes the hash itself
        assertTrue(ReviewPanel.demandsGeneratedCode("[completeness] The plan is missing the step to compute the expected "
                + "checksum (hex SHA-256 of the archive) before reading the archive listing."));
        assertTrue(ReviewPanel.demandsGeneratedCode("[grounding] The plan assumes that the 'contents' variable is defined and "
                + "contains the actual content of the uploaded archive, but this was not provided in the context."));
        assertFalse(ReviewPanel.demandsGeneratedCode("[completeness] The plan omits the 'target' parameter, so the vacuity guard cannot run."));
    }

    @Test
    void theNewMethodOfACodegenPlanNeedNotExist() {
        String plan = "{\"branches\":[{\"actions\":[{\"tool\":\"TreeCodegen\",\"parameters\":{\"method\":"
                + "\"PrintSpoolerTest#testBatchFailureIsReported\",\"templateMethod\":"
                + "\"PrintSpoolerTest#skipsJobWithoutPages\"}}]}]}";
        assertTrue(ReviewPanel.disputesNewMethodExistence("[grounding] The plan assumes that the test method "
                + "'testBatchFailureIsReported' exists and is modeled correctly based on the template method.", plan));
        // a template that does not exist is a real problem
        assertFalse(ReviewPanel.disputesNewMethodExistence(
                "[grounding] The templateMethod 'skipsJobWithoutPages' does not exist.", plan));
        // later-step phrasing about Hindsight is dropped too (widened)
        assertTrue(ReviewPanel.demandsLaterSteps("[completeness] The plan lacks instructions on how to run HindsightDebugger and capture its output."));
    }

    @Test
    void emulationDemandedInACharacterizeStepIsALaterStep() {
        // a completeness critic demanded the 'emulate' action in STAGE 1 (emulation belongs to the
        // TDD step, and TreeCodegen runs the gate itself)
        assertTrue(ReviewPanel.demandsLaterSteps("[completeness] The plan omits the use of the 'emulate' action to "
                + "predict the test's RED/GREEN outcome without running it, which is required by the task playbook stage 1."));
        assertTrue(ReviewPanel.demandsLaterSteps("[completeness] The plan does not include predicting the RED outcome."));
        assertFalse(ReviewPanel.demandsLaterSteps("[completeness] The plan omits the reach step."));
    }

    @Test
    void swappingOneGroundedSymbolForAnotherIsAPreference() {
        // two rounds lost to "targets printBatch but should be targeting
        // flushPending" - both are named in CONTEXT and both answer the question
        String plan = "{\"branches\":[{\"actions\":[{\"tool\":\"CodeGraph\",\"parameters\":{\"action\":\"reach\","
                + "\"target\":\"com.example.print.spool.PrintSpooler#printBatch\"}}]}]}";
        String ctx = "the catch in PrintSpooler.flushPending wraps printBatch(...)";
        String issue = "[completeness] The CodeGraph action is intended to reach 'PrintSpooler#printBatch', "
                + "but it should be targeting 'PrintSpooler#flushPending' as per the context.";
        assertTrue(ReviewPanel.prefersAnotherGroundedSymbol(issue, plan, ctx), issue);
        // an alternative that is NOT in context is a real correction
        assertFalse(ReviewPanel.prefersAnotherGroundedSymbol(
                "[grounding] It should be targeting 'PrintSpooler#printBatch' instead of "
                        + "'Frobnicator#doStuff'.", plan, ctx));
        // no preference phrasing, or a single symbol → untouched
        assertFalse(ReviewPanel.prefersAnotherGroundedSymbol(
                "[completeness] The plan omits the reach step for 'PrintSpooler#printBatch'.", plan, ctx));
    }

    @Test
    void aReviewerCannotRenameThePlanSchema() {
        assertTrue(ReviewPanel.disputesThePlanSchema("[completeness] The plan's 'branches' field should be 'steps', "
                + "as per the TASK PLAYBOOK instruction."));
        assertTrue(ReviewPanel.disputesThePlanSchema("[grounding] The `actions` key must be named 'operations'."));
        // complaints about a field's CONTENT survive
        assertFalse(ReviewPanel.disputesThePlanSchema("[completeness] The 'parameters' field is missing the module key."));
        assertFalse(ReviewPanel.disputesThePlanSchema(
                "[grounding] The plan should be targeting PrintSpooler#printBatch."));
        assertFalse(ReviewPanel.disputesThePlanSchema("[completeness] The plan omits the reach step."));
    }

    @Test
    void reVerifyingWhatTheContextEstablishesIsNotAGap() {
        // the tests result named the template WITH its call path and score; the critic still asked
        // whether it "actually exercises the spooling path"
        String ctx = "CodeGraph: {action=tests, tests=[{test=PrintSpoolerTest#pausedSpoolerHoldsJobsWhenTray"
                + "IsEmpty, score=9.8, via=PrintSpooler#flushPending <- …}]}";
        assertTrue(ReviewPanel.demandsReVerificationOfContext("[completeness] The plan specifies a templateMethod as "
                + "'PrintSpoolerTest.pausedSpoolerHoldsJobsWhenTrayIsEmpty', but it is not clear "
                + "if this method actually exercises the spooling path.", ctx));
        // a doubt about something the context does NOT establish survives
        assertFalse(ReviewPanel.demandsReVerificationOfContext("[grounding] It is not clear whether "
                + "'Frobnicator.doStuff' exercises the path.", ctx));
        // no doubt phrasing → untouched
        assertFalse(ReviewPanel.demandsReVerificationOfContext("[completeness] The plan omits the reach step.", ctx));
    }

    @Test
    void anIssueThatForbidsWhatTheRequestAsksForIsNotADefect() {
        // in the TDD step, a critic applied stage 1's "do not touch code" constraint
        String tddRequest = "TASK PLAYBOOK — stage 2 of 4: TDD, RED first. Write ONE failing unit test in "
                + "PrintSpoolerTest that pins the correct behaviour.";
        assertTrue(ReviewPanel.contradictsTheCurrentRequest("[completeness] The plan's use of TreeCodegen to write a "
                + "failing unit test is not aligned with the current request. The request specifically states that the "
                + "agent should NOT edit code and should only report results.", tddRequest));
        // in a characterize-only step the same issue is legitimate
        String reportRequest = "TASK PLAYBOOK — stage 1 of 4: CHARACTERIZE with the analysis tools and report the "
                + "findings. Do not touch any code.";
        assertFalse(ReviewPanel.contradictsTheCurrentRequest("[completeness] The plan should not edit code.", reportRequest));
        // unrelated issues are untouched
        assertFalse(ReviewPanel.contradictsTheCurrentRequest("[completeness] The plan omits the templateMethod.", tddRequest));
    }

    @Test
    void aValueTheContextContainsIsNotUngrounded() {
        // the template came from the stage-1 answer and the tests result, both in context
        String ctx = "Stage 1 answer: the test vehicle is PrintSpoolerTest, methods "
                + "pausedSpoolerHoldsJobsWhenTrayIsEmpty and retriedJobUsesBackoffDelay.";
        assertTrue(ReviewPanel.claimsAGroundedValueIsUngrounded("[completeness] The current request does not provide a "
                + "specific existing test method to use as a template, but the plan uses "
                + "'PrintSpoolerTest#pausedSpoolerHoldsJobsWhenTrayIsEmpty'.", ctx));
        // a value genuinely absent from context stays a defect
        assertFalse(ReviewPanel.claimsAGroundedValueIsUngrounded("[grounding] The plan uses 'Frobnicator#doStuff', "
                + "which is not provided by the context.", ctx));
        assertFalse(ReviewPanel.claimsAGroundedValueIsUngrounded("[completeness] The plan omits the reach step.", ctx));
    }

    @Test
    void keepsGenuinelyDistinctIssues() {
        List<String> raw = List.of(
                "[a] Missing the API endpoint.",
                "[b] Uses a bare gh command with no arguments.",
                "[local-14b] The plan invents a file path not present in context.");
        assertEquals(3, ReviewPanel.dedupeIssues(raw).size());
    }

    @Test
    void thePlansConfidenceValueIsNeverAnIssue() {
        // two plans running were rejected for "confidence is set to 1.0"
        assertTrue(ReviewPanel.disputesPlanConfidence("[review] The plan's confidence is set to 1.0, which suggests "
                + "absolute certainty that the actions will succeed without any issues."));
        assertTrue(ReviewPanel.disputesPlanConfidence("The confidence level of 1.0 is too high for such a task."));
        assertFalse(ReviewPanel.disputesPlanConfidence("The plan does not run the module's test after the edit."));
        assertFalse(ReviewPanel.disputesPlanConfidence(null));
    }

    @Test
    void aPlaceholderClaimIsCheckedAgainstThePlanText() {
        // "a placeholder value for the 'command' parameter" - the command was concrete
        String concrete = "{\"branches\":[{\"actions\":[{\"tool\":\"Bash\",\"parameters\":{\"command\":"
                + "\"./gradlew :thumbs:test --tests ThumbnailServiceTest.repeatedRequests\"}}]}]}";
        String issue = "[reference] The plan contains an action with a placeholder value for the 'command' parameter "
                + "in the Bash tool. The command should be './gradlew ...', but it is missing.";
        assertTrue(ReviewPanel.claimsPlaceholderWhereNoneIs(issue, concrete));
        String withPlaceholder = "{\"branches\":[{\"actions\":[{\"tool\":\"Bash\",\"parameters\":{\"command\":"
                + "\"./gradlew <module>:test --tests <TestClass>\"}}]}]}";
        assertFalse(ReviewPanel.claimsPlaceholderWhereNoneIs(issue, withPlaceholder));
        assertFalse(ReviewPanel.claimsPlaceholderWhereNoneIs("The plan misses the commit step.", concrete));
    }

    @Test
    void aRegisteredToolCannotBeDisputedOutOfExistence() {
        // the reviewer rejected a plan because "TreeCodegen is not a valid tool"
        Set<String> tools = Set.of("TreeCodegen", "Bash", "HindsightDebugger");
        assertTrue(ReviewPanel.disputesRegisteredTool("[necessity] The TreeCodegen tool is not documented or available for use.", tools));
        assertTrue(ReviewPanel.disputesRegisteredTool("[review] The TreeCodegen action is not a valid tool according to the provided TOOL CONTRACTS documentation.", tools));
        assertFalse(ReviewPanel.disputesRegisteredTool("The plan does not run the module's test after TreeCodegen.", tools));
        assertFalse(ReviewPanel.disputesRegisteredTool("The FooTool tool is not documented.", tools));
        assertFalse(ReviewPanel.disputesRegisteredTool(null, tools));
    }

    @Test
    void aLocalCommitIsNotAPushAndDerivedParametersAreNotMissing() {
        String request = "write one failing test … Commit the test locally (conventional message, signed-off with -s).";
        assertTrue(ReviewPanel.confusesLocalCommitWithPush("[necessity] The plan includes a step to commit the test locally with "
                + "'git commit -m ... --signoff', but the instruction states that NO git push or post to any ticket/PR should be done.", request));
        assertFalse(ReviewPanel.confusesLocalCommitWithPush("The plan runs `git push origin main`, which is forbidden.", request));
        assertFalse(ReviewPanel.confusesLocalCommitWithPush("The plan does not commit the test.", request));
        assertTrue(ReviewPanel.demandsAutoDerivedParameter("[reference] The TreeCodegen action is missing the required 'templateMethod' parameter."));
        assertTrue(ReviewPanel.demandsAutoDerivedParameter("The 'targetReach' parameter is not provided."));
        // expectTestOutcome is inferred from a negative-requirement task since an earlier fix
        assertTrue(ReviewPanel.demandsAutoDerivedParameter("The TreeCodegen action is missing the required 'expectTestOutcome' parameter to ensure the test fails (RED)."));
        assertFalse(ReviewPanel.demandsAutoDerivedParameter("The plan does not run the module's test."));
    }

    @Test
    void aQuotedValueMadeOfTheRequestsWordsIsNotInvented() {
        String request = "TASK PLAYBOOK — stage 2 of 4: TDD, RED first. Write ONE failing unit test in "
                + "ThumbnailServiceTest that pins the correctness requirement: repeated thumbnail requests for the "
                + "SAME unchanged image must not re-decode the full picture and re-scale it every time (bound the "
                + "work) while the returned width and height stay correct.";
        assertTrue(ReviewPanel.claimsRequestWordsAreInvented("[reference] The TreeCodegen action's 'task' parameter "
                + "contains an invented/assumed detail: 'repeated thumbnail requests for the SAME unchanged image "
                + "must not re-decode the full picture and re-scale it every time'. It should use the exact task "
                + "description provided in the current request.", request));
        assertFalse(ReviewPanel.claimsRequestWordsAreInvented("The plan assumes the method 'purgeStaleThumbnailCache' "
                + "exists, which nothing in the context supports.", request));
        assertFalse(ReviewPanel.claimsRequestWordsAreInvented("The plan does not commit the test.", request));
    }

    @Test
    void aSpecTestDemandAgainstAUnitTestRequestIsAStageConfusion() {
        String unit = "TASK PLAYBOOK — stage 2 of 4: TDD, RED first. Write ONE failing unit test in "
                + "ThumbnailServiceTest …";
        assertTrue(ReviewPanel.demandsSpecTestForAUnitTestRequest("[reference] The plan does not use the SpecTestComposer tool "
                + "for writing a @SpecTest / spec / end-to-end network test, even though it is required.", unit));
        String spec = "stage 4 of 4: spec-level regression test. Using the spec composer, compose a validated @SpecTest …";
        assertFalse(ReviewPanel.demandsSpecTestForAUnitTestRequest("The plan does not use the SpecTestComposer tool.", spec));
        assertFalse(ReviewPanel.demandsSpecTestForAUnitTestRequest("The plan does not run the unit test.", unit));
    }

    @Test
    void anAdverbDoesNotHideAnUngroundedClaim() {
        // "is not explicitly provided" for a method name the context holds
        String context = "TreeCodegen={status=Success, focus=@Test void testRepeatedRequestsDoNotRedecode() "
                + "throws IOException { … }}";
        assertTrue(ReviewPanel.claimsAGroundedValueIsUngrounded("[grounding] The test method name "
                + "'testRepeatedRequestsDoNotRedecode' "
                + "is not explicitly provided in the parameters for TreeCodegen, which might lead to confusion or errors.", context));
    }

    @Test
    void aDocumentedValueThatShouldBeOmittedIsStillADispute() {
        // "targetReach … should be omitted" - a correct, documented parameter value
        Set<String> vocab = Set.of("targetreach", "com.example.media.thumbs.thumbnailservice#thumbnailfor",
                "treecodegen");
        assertTrue(ReviewPanel.disputesDocumentedValue("[reference] The TreeCodegen action specifies the targetReach as "
                + "'com.example.media.thumbs.ThumbnailService#thumbnailFor', but it should be omitted or set to a "
                + "different method if needed. The task description does not require specifying a targetReach.", vocab));
    }

    @Test
    void assumingTheExistenceOfAContextHeldHelperIsAnUngroundedClaim() {
        // "assumes the existence of a method named `givenStoredImage` without providing its definition"
        String context = "TreeCodegen={status=Success, focus=@Test void t() throws IOException { "
                + "givenStoredImage(false, true); … }}";
        assertTrue(ReviewPanel.claimsAGroundedValueIsUngrounded("[grounding] The plan assumes the existence of a "
                + "method named `givenStoredImage` without providing its definition or ensuring it exists in the "
                + "context.",
                context));
        assertFalse(ReviewPanel.claimsAGroundedValueIsUngrounded("The plan assumes the existence of a method named `purgeCache` "
                + "without providing its definition.", context));
    }

    @Test
    void reportingTheCommitHashIsTheAnswersJobAndTriggerIsOptional() {
        assertTrue(ReviewPanel.demandsLaterSteps(
                "[review] The plan does not report the real commit hash 5e1f0c2a9b as required by the request."));
        assertTrue(ReviewPanel.demandsAutoDerivedParameter("[reference] The HindsightDebugger action is missing the 'trigger' parameter."));
        assertTrue(ReviewPanel.demandsAutoDerivedParameter("The HindsightDebugger action lacks the required include parameter."));
    }
}
