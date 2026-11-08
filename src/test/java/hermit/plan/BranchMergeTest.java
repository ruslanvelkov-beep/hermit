package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Read-only analysis actions of sibling branches are folded into the selected branch. */
class BranchMergeTest {

    private final Action catches = new Action("CodeGraph", Map.of("action", "catches", "symbol", "A#m"));
    private final Action reach = new Action("CodeGraph", Map.of("action", "reach", "target", "A#m"));
    private final Action grep = new Action("Bash", Map.of("command", "grep -rn 'drainPending' src/test"));
    private final Action edit = new Action("TreeCodegen", Map.of("task", "write the fix", "file", "A.java"));
    private final Action rm = new Action("Bash", Map.of("command", "rm -rf build"));

    @Test
    void readOnlyClassification() {
        assertTrue(RepeatGuard.isReadOnly(catches));
        assertTrue(RepeatGuard.isReadOnly(reach));
        assertTrue(RepeatGuard.isReadOnly(grep));
        assertFalse(RepeatGuard.isReadOnly(edit));
        assertFalse(RepeatGuard.isReadOnly(rm));
        assertFalse(RepeatGuard.isReadOnly(new Action("SpecTestComposer", Map.of("action", "compose"))));
        assertFalse(RepeatGuard.isReadOnly(new Action("Bash", Map.of("command", "grep x > out.txt"))));
    }

    @Test
    void siblingsAnalysisIsFoldedIntoTheSelectedBranch() {
        Branch a = new Branch("(a) exceptions", "d", 0.9, List.of(catches));
        Branch b = new Branch("(c) tests", "d", 0.8, List.of(grep));
        Branch c = new Branch("(d) reach", "d", 0.7, List.of(reach));
        Branch d = new Branch("fix now", "d", 0.6, List.of(edit));
        Branch merged = PlanShaper.mergeReadOnlySiblings(List.of(a, b, c, d), a);
        assertEquals(3, merged.actions().size(), merged.actions().toString());
        assertTrue(merged.actions().contains(grep) && merged.actions().contains(reach));
        assertFalse(merged.actions().contains(edit), "side-effecting siblings are never merged");
        assertTrue(merged.name().contains("read-only siblings"));
        // nothing to merge → the very same branch
        assertSame(d, PlanShaper.mergeReadOnlySiblings(List.of(d, new Branch("x", "d", 0.5, List.of(edit))), d));
    }
}
