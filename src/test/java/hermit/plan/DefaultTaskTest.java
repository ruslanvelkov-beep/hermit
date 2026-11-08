package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import hermit.api.Action;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A TreeCodegen call without its task sentence failed ("task, file and projectDir are required"). */
class DefaultTaskTest {

    @Test
    void aTreeCodegenCallWithoutATaskGetsTheRequestsFirstLine() {
        List<Action> plan = List.of(new Action("TreeCodegen", Map.of("file", "src/A.java", "items", List.of())),
                new Action("Bash", Map.of("command", "ls")));
        List<Action> out = PlanShaper.withDefaultTask(plan, "\nRewrite task 040a: port part 1 of A.\n\nRead first: ...");
        assertEquals("Rewrite task 040a: port part 1 of A.", out.get(0).parameters().get("task"));
        assertEquals("src/A.java", out.get(0).parameters().get("file"));
        assertSame(plan.get(1), out.get(1));
    }

    @Test
    void aCallThatHasItsTaskIsLeftAsItIs() {
        List<Action> plan = List.of(new Action("TreeCodegen", Map.of("task", "port it", "file", "src/A.java")));
        assertSame(plan, PlanShaper.withDefaultTask(plan, "Rewrite task: something else."));
    }
}
