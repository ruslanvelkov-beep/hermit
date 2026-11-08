package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** a plan that only reads, when the request names reads to make first, skips the critic panel. */
class NamedReadsReviewTest {

    private static final String REQUEST = "Rewrite task: port part 3.\nRead first, as two Bash commands: sed -n '1,9p' A.java\n";

    private static ParsedPlan plan(Action... actions) {
        return new ParsedPlan(null, null, List.of(new Branch("read", "", 0.9, List.of(actions))));
    }

    private static Action bash(String command) {
        return new Action("Bash", Map.of("command", command));
    }

    @Test
    void aPlanOfReadsAfterARequestThatNamesReadsSkipsTheReview() {
        assertTrue(RepeatGuard.onlyNamedReads(plan(bash("sed -n '165,275p' ref/A.java"),
                bash("grep -n 'private' src/A.java")), REQUEST));
        assertTrue(RepeatGuard.onlyNamedReads(plan(bash("sed -n '109,164p;285,313p' ref/R.java && echo '---CURRENT---' && "
                + "grep -n 'private\\|public\\|case ' src/R.java 2>&1")), REQUEST), "the task 037b read");
        assertFalse(RepeatGuard.onlyNamedReads(plan(bash("sed -i '' 's/a/b/' src/A.java")), REQUEST));
    }

    @Test
    void readsFollowedByAnEditTheCutWillDropCountAsReads() {
        // task 037d: read + TreeCodegen was reviewed and rejected, though the cut after a read leaves only the read to run
        assertTrue(RepeatGuard.onlyNamedReads(plan(bash("sed -n '1,9p' A.java"),
                new Action("TreeCodegen", Map.of("task", "port"))), REQUEST));
    }

    @Test
    void anEditAWriteAnAnswerOrARequestWithoutReadsIsReviewed() {
        assertFalse(RepeatGuard.onlyNamedReads(plan(new Action("TreeCodegen", Map.of("task", "port")),
                bash("sed -n '1,9p' A.java")), REQUEST));
        assertFalse(RepeatGuard.onlyNamedReads(plan(bash("cat A.java > B.java")), REQUEST));
        assertFalse(RepeatGuard.onlyNamedReads(plan(bash("git commit -m x")), REQUEST));
        assertFalse(RepeatGuard.onlyNamedReads(new ParsedPlan("done", null, null), REQUEST));
        assertFalse(RepeatGuard.onlyNamedReads(plan(bash("sed -n '1,9p' A.java")), "Port part 3 of A."));
    }
}
