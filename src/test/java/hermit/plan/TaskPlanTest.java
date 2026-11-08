package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import hermit.checkpoint.TurnSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The task plan (user, 2026-10-21): numbered steps written first, worked one at a time, revised when they bring nothing. */
class TaskPlanTest {

    @Test
    void aReplyGivesItsStepsWithOrWithoutProseAround() {
        List<TurnSnapshot.TaskStep> steps = TaskPlan.parseSteps("Here is the plan:\n```json\n{\"steps\": [{\"goal\": \"Read A\"}, "
                + "{\"goal\": \"Edit A and run its test\"}]}\n```");
        assertEquals(2, steps.size());
        assertEquals("Edit A and run its test", steps.get(1).goal);
        assertEquals(List.of("a", "b"), TaskPlan.parseSteps("{\"steps\": [\"a\", \"b\"]}").stream().map(s -> s.goal).toList());
        assertNull(TaskPlan.parseSteps("{\"steps\": []}"), "no step is no plan");
        assertNull(TaskPlan.parseSteps("I would read the file first."));
    }

    @Test
    void thePlannerSeesEachStepTheDoneOnesWithTheirOutcomeAndTheCurrentOneMarked() {
        TurnSnapshot s = new TurnSnapshot();
        s.taskPlan = TaskPlan.parseSteps("{\"steps\": [\"Read A\", \"Edit A\", \"Commit\"]}");
        assertTrue(TaskPlan.active(s));
        assertFalse(TaskPlan.finished(s));
        String first = TaskPlan.block(s);
        assertTrue(first.contains("1. [CURRENT] Read A") && first.contains("2. Edit A") && first.contains("3. Commit"), first);
        TaskPlan.markCurrentDone(s, "A has 40 lines");
        String second = TaskPlan.block(s);
        assertTrue(second.contains("1. [done] Read A: A has 40 lines") && second.contains("2. [CURRENT] Edit A"), second);
        TaskPlan.replaceFromCurrent(s, TaskPlan.parseSteps("{\"steps\": [\"Edit A with TreeEdit\", \"Run the test\", \"Commit\"]}"));
        assertEquals(List.of("Read A", "Edit A with TreeEdit", "Run the test", "Commit"), s.taskPlan.stream().map(t -> t.goal).toList(),
                "a revision rewrites the current step and the ones after it; the done ones stay");
        TaskPlan.markCurrentDone(s, "edited");
        TaskPlan.markCurrentDone(s, "green");
        TaskPlan.markCurrentDone(s, "abc1234");
        assertTrue(TaskPlan.finished(s));
        assertTrue(TaskPlan.block(s).contains("Every step of the task plan is done"), TaskPlan.block(s));
        assertEquals("", TaskPlan.block(new TurnSnapshot()), "no plan, no block");
    }

    @Test
    void theJudgesVerdictIsReadLeniently() {
        assertTrue(TaskPlan.parseVerdict("{\"done\": true, \"why\": \"the commit ran\"}").done());
        assertEquals("the commit ran", TaskPlan.parseVerdict("Verdict: {\"done\": true, \"why\": \"the commit ran\"}").why());
        assertFalse(TaskPlan.parseVerdict("{\"done\": false, \"why\": \"no test ran\"}").done());
        assertNull(TaskPlan.parseVerdict("probably done"));
    }

    @Test
    void theTaskPlanTravelsWithTheState() {
        TurnSnapshot s = new TurnSnapshot();
        s.taskPlan = TaskPlan.parseSteps("{\"steps\": [\"Read A\", \"Edit A\"]}");
        TaskPlan.markCurrentDone(s, "read");
        TurnSnapshot back = TurnSnapshot.fromJson(s.toJson());
        assertEquals(1, back.currentStep);
        assertTrue(back.taskPlan.get(0).done);
        assertEquals("Edit A", back.taskPlan.get(1).goal);
    }

    // a look-ahead that cuts the steps ahead to under a third of their text is taken for a misreading and not applied
    @Test
    void aLookAheadThatLosesTheWorkAheadIsNotTaken() {
        List<TurnSnapshot.TaskStep> before = TaskPlan.parseSteps("{\"steps\": [\"Port moveMember, spaceBefore and replaceTypeComment "
                + "with their helpers in one TreeCodegen call, then run the suite and commit\", \"Answer with the two hashes\"]}");
        assertFalse(TaskPlan.keepsTheWork(before, TaskPlan.parseSteps("{\"steps\": [\"Step 1 hash\", \"Step 2 hash\"]}")));
        assertTrue(TaskPlan.keepsTheWork(before, TaskPlan.parseSteps("{\"steps\": [\"Port the three edits and their helpers "
                + "with TreeCodegen, then run the suite and commit\", \"Answer with both hashes\"]}")));
    }

    // after round 3 was done, a look-ahead gave back the steps for rounds 5 and 6 and the answer; round 4 was gone, and
    // the step planner sent round 5 twice while round 4 never ran
    @Test
    void aLookAheadThatDropsARoundTheStepsNamedIsNotTaken() {
        List<TurnSnapshot.TaskStep> before = TaskPlan.parseSteps("{\"steps\": [\"Round 4: apply one TreeEdit action to A.java with "
                + "the round-4 items\", \"Round 5: apply one TreeEdit action to A.java with the round-5 items\", \"Round 6: apply one "
                + "TreeEdit action to A.java with the round-6 items\", \"After all seven rounds, reply done\"]}");
        List<TurnSnapshot.TaskStep> dropped = TaskPlan.parseSteps("{\"steps\": [\"Apply exactly one TreeEdit action to A.java using "
                + "the round\u20115 items (insert the fabricated-identifier check and the hash checks)\", \"Apply exactly one TreeEdit "
                + "action to A.java using the round\u20116 items (insert the result-block helpers)\", \"Reply done\"]}");
        assertFalse(TaskPlan.keepsTheRounds(before, dropped));
        assertFalse(TaskPlan.keepsTheWork(before, dropped), "a round the steps ahead named is gone");
        List<TurnSnapshot.TaskStep> reworded = TaskPlan.parseSteps("{\"steps\": [\"Apply the round 4 items to A.java\", \"Apply "
                + "the round-5 items to A.java\", \"Apply the Round 6 items to A.java\", \"Reply done\"]}");
        assertTrue(TaskPlan.keepsTheRounds(before, reworded));
        assertTrue(TaskPlan.keepsTheRounds(TaskPlan.parseSteps("{\"steps\": [\"Port the class\", \"Commit\"]}"),
                TaskPlan.parseSteps("{\"steps\": [\"Commit\"]}")), "steps that name no round are judged by their text alone");
    }

    static final String ROUNDS = "Rounds (each TreeEdit action has an items list):\n"
            + "0. ONE TreeEdit action on src/A.java with the items\n[\n {\n  \"op\": \"insertMember\",\n  \"type\": \"A\",\n"
            + "  \"code\": \"int a() {\\n    return 1;\\n}\"\n }\n]\n"
            + "1. ONE Bash command: ./gradlew test\n"
            + "2. ONE TreeEdit action on src/A.java with the items\n[{\"op\": \"insertMember\", \"type\": \"A\", \"code\": \"int b() {\\n    return 2;\\n}\"}]\n"
            + "3. ONE TreeEdit action on src/A.java with the items\n[{\"op\": \"moveMember\", \"method\": \"A#b\", \"anchor\": \"A#a\"}]\n"
            + "Answer with 'done' once the last round ran.";

    static Action treeEdit(Map<String, Object> item) {
        return new Action("TreeEdit", Map.of("file", "src/A.java", "items", List.of(item)));
    }

    // round 2 never ran, round 3's items ran in its place, and the step judge, which read only "TreeEdit succeeded",
    // marked round 2 done; the answer "done" followed with round 2 missing
    @Test
    void anExactRoundIsDoneOnlyWhenItsOwnItemsRan() {
        Map<Integer, List<Map<String, Object>>> rounds = TaskPlan.exactRounds(ROUNDS);
        assertEquals(Set.of(0, 2, 3), rounds.keySet(), "the rounds that give TreeEdit items");
        Set<Integer> run = new TreeSet<>();
        Action roundThree = treeEdit(Map.of("op", "moveMember", "method", "A#b", "anchor", "A#a"));
        TaskPlan.recordExactRounds(ROUNDS, List.of(roundThree), Map.of("TreeEdit", Map.of("status", "Success")), run);
        assertEquals(Set.of(3), run);
        TaskPlan.Verdict two = TaskPlan.exactRoundVerdict(ROUNDS, "Round 2: apply one TreeEdit action with round 2's items", run);
        assertNotNull(two);
        assertFalse(two.done(), two.why());
        assertTrue(two.why().contains("round 2's items have not run"), two.why());
        // a failed edit records nothing; the round's own items, flattened into the action, count
        TaskPlan.recordExactRounds(ROUNDS, List.of(new Action("TreeEdit", Map.of("op", "insertMember", "type", "A",
                "code", "int b() {\n    return 2;\n}"))), Map.of("TreeEdit", Map.of("status", "Error")), run);
        assertEquals(Set.of(3), run);
        TaskPlan.recordExactRounds(ROUNDS, List.of(new Action("TreeEdit", Map.of("file", "src/A.java", "op", "insertMember",
                "type", "A", "code", "int b() {\n    return 2;\n}"))), Map.of("TreeEdit", Map.of("status", "Success")), run);
        assertTrue(TaskPlan.exactRoundVerdict(ROUNDS, "Round 2: apply round 2's items", run).done());
        // a step that names no exact round, or a Bash round, is left to the judge
        assertNull(TaskPlan.exactRoundVerdict(ROUNDS, "Round 1: run the supplied Bash command", run));
        assertNull(TaskPlan.exactRoundVerdict(ROUNDS, "Answer with done", run));
        assertNull(TaskPlan.exactRoundVerdict("Port the class.", "Round 2: anything", run));
        // an item that differs by a character is not the round's
        Set<Integer> other = new TreeSet<>();
        TaskPlan.recordExactRounds(ROUNDS, List.of(treeEdit(Map.of("op", "insertMember", "type", "A", "code", "int a() {\n  return 1;\n}"))),
                Map.of("TreeEdit", Map.of("status", "Success")), other);
        assertTrue(other.isEmpty());
        assertEquals(List.of(0), TaskPlan.exactRoundsNotRun(ROUNDS, run));
    }

    @Test
    void twoRoundsInOnePlanAreBothRecorded() {
        Set<Integer> run = new TreeSet<>();
        Action two = treeEdit(Map.of("op", "insertMember", "type", "A", "code", "int b() {\n    return 2;\n}"));
        Action three = treeEdit(Map.of("op", "moveMember", "method", "A#b", "anchor", "A#a"));
        TaskPlan.recordExactRounds(ROUNDS, List.of(two, three),
                Map.of("TreeEdit", Map.of("status", "Success"), "TreeEdit#2", Map.of("status", "Success")), run);
        assertEquals(Set.of(2, 3), run);
        Set<Integer> half = new TreeSet<>();
        TaskPlan.recordExactRounds(ROUNDS, List.of(two, three),
                Map.of("TreeEdit", Map.of("status", "Success"), "TreeEdit#2", Map.of("status", "Error")), half);
        assertEquals(Set.of(2), half, "the second edit failed");
    }

    // the planner sent round 5's items, or the final commit command, while round 4's items had never run; the task plan
    // named round 4 as the current step every time
    @Test
    void aPlanThatRunsALaterRoundWhileAnEarlierExactRoundIsPendingIsRefused() {
        Set<Integer> run = new TreeSet<>(Set.of(0));
        Action roundThree = treeEdit(Map.of("op", "moveMember", "method", "A#b", "anchor", "A#a"));
        String issue = TaskPlan.laterRoundFirstIssue(ROUNDS, List.of(roundThree), run);
        assertNotNull(issue);
        assertTrue(issue.contains("round 2") && issue.contains("round 3"), issue);
        Action bashRound = new Action("Bash", Map.of("command", "./gradlew test"));
        assertNull(TaskPlan.laterRoundFirstIssue(ROUNDS, List.of(bashRound), run), "round 1 is the next round: no exact round before it is pending");
        Action roundTwo = treeEdit(Map.of("op", "insertMember", "type", "A", "code", "int b() {\n    return 2;\n}"));
        assertNull(TaskPlan.laterRoundFirstIssue(ROUNDS, List.of(roundTwo, roundThree), run), "rounds 2 and 3 in order, in one plan");
        assertNull(TaskPlan.laterRoundFirstIssue(ROUNDS, List.of(roundTwo), run));
        assertNull(TaskPlan.laterRoundFirstIssue(ROUNDS, List.of(new Action("Bash", Map.of("command", "sed -n '1,9p' A.java"))), run),
                "an action that is no round is not judged here");
        String commit = "1. ONE TreeEdit action on src/A.java with the items\n[{\"op\": \"insertMember\", \"type\": \"A\", "
                + "\"code\": \"int a() {\\n    return 1;\\n}\"}]\n2. ONE Bash command: ./gradlew test && git commit -m x\nAnswer with the hash.";
        String early = TaskPlan.laterRoundFirstIssue(commit, List.of(new Action("Bash", Map.of("command", "./gradlew test && git commit -m x"))),
                new TreeSet<>());
        assertNotNull(early, "the last round's command while round 1's items have not run");
        assertTrue(early.contains("round 1"), early);
    }
}
