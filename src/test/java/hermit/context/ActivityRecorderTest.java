package hermit.context;

import static org.junit.jupiter.api.Assertions.*;

import hermit.api.Action;
import hermit.evidence.ActivityLedger;
import hermit.memory.HashingEmbedder;
import hermit.plan.RepeatGuard;
import hermit.review.StructuredReviewer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityRecorderTest {
    @TempDir Path root;
    @AfterEach void clearLocalOnly() {
        System.clearProperty("hermit.inference.localOnly");
    }
    /** A reviewer that answers every yes/no question with {@code yes}. */
    private static StructuredReviewer answering(double yes) {
        return (state, questions) -> new StructuredReviewer.Result(Map.of(
                "q", new StructuredReviewer.Answer("yes", yes, null, null, Map.of(), null)), null);
    }
    @Test void criticMustCiteAnExistingHistoricalEvent() {
        String evidence="[activity #12 SUCCEEDED state=a] located X";
        assertTrue(ActivityRecorder.hasCitedEvidence("Repeats activity #12",evidence));
        assertFalse(ActivityRecorder.hasCitedEvidence("Repeats activity #99",evidence));
        assertFalse(ActivityRecorder.hasCitedEvidence("This is a repeat",evidence));
    }
    @Test void fingerprintChangesForTrackedAndUntrackedEdits() throws Exception {
        git("init","-q");Files.writeString(root.resolve("a.txt"),"one");git("add",".");git("-c","user.name=test","-c","user.email=test@localhost","commit","-qm","initial");
        String first=ActivityRecorder.fingerprint(root);assertFalse(first.isBlank());
        Files.writeString(root.resolve("a.txt"),"two");assertNotEquals(first,ActivityRecorder.fingerprint(root));
        Files.writeString(root.resolve("a.txt"),"one");assertEquals(first,ActivityRecorder.fingerprint(root));
        Files.writeString(root.resolve("new.txt"),"new");assertNotEquals(first,ActivityRecorder.fingerprint(root));
    }
    @Test void anExactRepeatOfARejectedProposalIsVetoedEvenForABashAction() throws Exception {
        git("init", "-q");
        Files.writeString(root.resolve("a.txt"), "one");
        git("add", ".");
        git("-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-qm", "initial");
        var memory = new ActivityLedger(root.resolve("m.db"), "t", "p", new HashingEmbedder());
        var action = new Action("Bash", Map.of("command", "grep -n parseRow X.java"));
        var different = new Action("Bash", Map.of("command", "sed -n '1,60p' X.java"));
        try (var activity = new ActivityRecorder(memory, root)) {
            activity.iteration(null);
            assertNull(activity.rejectedRepeatIssue(List.of(action)), "never proposed yet: nothing to veto");
            memory.append("REJECTED", "grep it", RepeatGuard.signature(action), ActivityRecorder.fingerprint(root), "does not read the method body; insufficient evidence gathering", 0, "");
            String issue = activity.rejectedRepeatIssue(List.of(action));
            assertNotNull(issue, "the exact same rejected action is proposed again");
            assertTrue(issue.contains("does not read the method body"));
            assertNull(activity.rejectedRepeatIssue(List.of(different)), "a genuinely different action is not vetoed");
        }
    }
    @Test void anUnknownRepositoryStateCannotVetoARejectedRepeatEither() {
        var memory = new ActivityLedger(root.resolve("m2.db"), "t", "p", new HashingEmbedder());
        var action = new Action("Bash", Map.of("command", "grep -n parseRow X.java"));
        memory.append("REJECTED", "grep it", RepeatGuard.signature(action), "", "does not read the method body", 0, "");
        try (var activity = new ActivityRecorder(memory, root)) {
            activity.iteration(null);   // root is not a git repo here: fingerprint() is blank, same as unknownStateCannotVetoCompletedLookup
            assertNull(activity.rejectedRepeatIssue(List.of(action)));
        }
    }
    @Test void aRewordedRepeatThatEvadesExactMatchIsCaughtByTheReviewerWhenConfigured() {
        var memory = new ActivityLedger(root.resolve("m3.db"), "t", "p", new HashingEmbedder());
        var priorAction = new Action("Bash", Map.of("command", "grep -n parseRow CsvReader.java"));
        memory.append("REJECTED", "grep -n parseRow CsvReader.java",
                RepeatGuard.signature(priorAction), "", "does not read the method body; insufficient evidence gathering", 0, "");
        var rewordedAction = new Action("CodeGraph", Map.of("action", "locate", "symbol", "parseRow CsvReader.java"));
        try (var activity = new ActivityRecorder(memory, root)) {
            activity.iteration(null);
            assertNull(activity.rejectedRepeatIssue(List.of(rewordedAction)), "different signature: exact match misses it");
            String issue = activity.semanticRepeatIssue(List.of(rewordedAction), answering(0.9));
            assertNotNull(issue, "the reviewer recognizes the reworded action as the same rejected idea");
            assertTrue(issue.contains("does not read the method body"), issue);
        }
    }
    @Test void semanticRepeatCheckIsANoopWithoutAConfiguredReviewer() {
        var memory = new ActivityLedger(root.resolve("m4.db"), "t", "p", new HashingEmbedder());
        var priorAction = new Action("Bash", Map.of("command", "grep -n parseRow CsvReader.java"));
        memory.append("REJECTED", "grep -n parseRow CsvReader.java",
                RepeatGuard.signature(priorAction), "", "does not read the method body", 0, "");
        try (var activity = new ActivityRecorder(memory, root)) {
            activity.iteration(null);
            assertNull(activity.semanticRepeatIssue(List.of(priorAction), null),
                    "no reviewer: must be a silent no-op, never a crash or a false veto");
            System.setProperty("hermit.inference.localOnly", "true");   // local-only inference switches the reviewer off
            assertNull(activity.semanticRepeatIssue(List.of(priorAction), answering(0.9)));
        }
    }
    @Test void unknownStateCannotVetoCompletedLookup() {
        var memory=new ActivityLedger(root.resolve("m.db"),"t","p",new HashingEmbedder());
        var action=new Action("CodeGraph",Map.of("action","locate","symbol","X"));
        memory.append("SUCCEEDED","locate X",RepeatGuard.signature(action),"","X.java",0,"");
        try(var activity=new ActivityRecorder(memory,root)) {activity.iteration(null);assertNull(activity.repeatIssue(List.of(action)));}
    }
    void git(String... args)throws Exception {var cmd=new ArrayList<>(List.of("git","-C",root.toString()));cmd.addAll(List.of(args));assertEquals(0,new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());}
}
