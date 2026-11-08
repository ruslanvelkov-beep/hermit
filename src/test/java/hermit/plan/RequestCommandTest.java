package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import hermit.api.Action;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A hosted edit plan that leaves out the request's command after TreeCodegen gets it back, verbatim. */
class RequestCommandTest {

    static final String REQUEST = "Task 9: tidy A.\n\nRepository (change files only inside it): /work/repo\n"
            + "The change, as exactly ONE TreeCodegen action.\n\nWhen TreeCodegen succeeds, run ONE Bash command:\n"
            + "  ./gradlew test --offline -q && git add A.java && git commit -m \"Tidy A\"\n\nAnswer with the commit hash.";

    static Action treeCodegen() {
        return new Action("TreeCodegen", Map.of("file", "A.java", "projectDir", "/work/repo"));
    }

    @Test
    void aPlanWithoutTheCommandAfterTreeCodegenGetsIt() {
        // the hosted plan held the call and not the command, twice
        List<Action> out = PlanShaper.withRequestCommand(List.of(treeCodegen()), REQUEST);
        assertEquals(2, out.size());
        assertEquals("Bash", out.get(1).toolName());
        assertEquals("./gradlew test --offline -q && git add A.java && git commit -m \"Tidy A\"",
                out.get(1).parameters().get("command"));
        assertEquals("/work/repo", out.get(1).parameters().get("workingDir"));
    }

    @Test
    void aPlanWithACommandAfterTheCallOrWithoutTheCallOrARequestWithoutTheStepIsLeftAlone() {
        List<Action> withCommand = List.of(treeCodegen(), new Action("Bash", Map.of("command", "git commit -m x")));
        assertSame(withCommand, PlanShaper.withRequestCommand(withCommand, REQUEST), "the seam's review compares it");
        List<Action> reads = List.of(new Action("Bash", Map.of("command", "sed -n '1,9p' A.java")));
        assertSame(reads, PlanShaper.withRequestCommand(reads, REQUEST));
        List<Action> call = List.of(treeCodegen());
        assertSame(call, PlanShaper.withRequestCommand(call, "Task 9: tidy A with one TreeCodegen action."));
    }

    // plans copied the request's command with one change (the checker's path replaced by
    // a search for it; the closing '|' of a ledger pattern dropped, which would have left '||' in the row)
    @Test
    void aNearCopyOfACommandTheRequestGivesIsThatCommand() {
        String command = "./gradlew test -q && perl -pi -e 's/\\Q| A | P3 | red: not implemented |\\E/| A | P3 | green |/' L.md "
                + "&& /opt/rw/neutral-check src && git commit -m \"Port A\"";
        String request = "Task 9.\n\nRepository (change files only inside it): /work/repo\n\n2. Then ONE Bash command:\n   " + command
                + "\n\nAnswer with the commit hash that git printed.";
        Action near = new Action("Bash", Map.of("command", command.replace("not implemented |\\E", "not implemented \\E"),
                "workingDir", "/work/repo"));
        List<Action> out = PlanShaper.withRequestCommandsVerbatim(List.of(treeCodegen(), near), request);
        assertEquals(command, out.get(1).parameters().get("command"));
        assertEquals("/work/repo", out.get(1).parameters().get("workingDir"), "its other parameters stay");
        List<Action> exact = List.of(new Action("Bash", Map.of("command", command)));
        assertSame(exact, PlanShaper.withRequestCommandsVerbatim(exact, request));
        List<Action> other = List.of(new Action("Bash", Map.of("command", "sed -n '1,40p' A.java")));
        assertSame(other, PlanShaper.withRequestCommandsVerbatim(other, request), "a command of the plan's own");
    }
}
