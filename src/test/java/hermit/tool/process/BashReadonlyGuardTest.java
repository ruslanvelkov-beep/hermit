package hermit.tool.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Locks the BashTool READ-ONLY / dry-run guard ({@code -Dhermit.readonly=true}): destructive or
 * outward-facing git/GitHub operations must be refused, while read-only inspection and local
 * build/test/edit commands must pass through untouched. Pure string classification - no process
 * is ever launched (destructiveOp is tested directly), plus one end-to-end refusal via execute().
 */
class BashReadonlyGuardTest {

    @AfterEach
    void clearProp() {
        System.clearProperty("hermit.readonly");
    }

    // ---- OUTWARD-FACING (publishes/notifies): must be caught ----

    @Test
    void blocksGitPushOnly() {
        assertNotNull(BashTool.destructiveOp("git push origin HEAD"));
        assertNotNull(BashTool.destructiveOp("git push --force"));
        // buried in a chain after a cd / && (commit is fine; the push is what's blocked)
        assertNotNull(BashTool.destructiveOp("cd /repo && git add -A && git commit -m x && git push"));
        assertNotNull(BashTool.destructiveOp("git -C /some/repo push"));
    }

    @Test
    void allowsAllLOCALGitHistoryOps() {
        // Policy for this experiment: local git is allowed (commit & stash & merge locally, NEVER push).
        assertNull(BashTool.destructiveOp("git commit -m \"fix\""));
        assertNull(BashTool.destructiveOp("git commit -s -m x"));
        assertNull(BashTool.destructiveOp("git -C /some/repo commit -m x"));
        assertNull(BashTool.destructiveOp("git stash"));
        assertNull(BashTool.destructiveOp("git merge upstream/main"));
        assertNull(BashTool.destructiveOp("git rebase -i HEAD~3"));
        assertNull(BashTool.destructiveOp("git reset --hard HEAD~1"));
        assertNull(BashTool.destructiveOp("git checkout -b fix/rounding-error"));
        assertNull(BashTool.destructiveOp("git fetch upstream"));
    }

    @Test
    void blocksGhPrAndIssueWrites() {
        assertNotNull(BashTool.destructiveOp("gh pr create --title x --body y"));
        assertNotNull(BashTool.destructiveOp("gh pr comment 123 --body hi"));
        assertNotNull(BashTool.destructiveOp("gh pr merge 123 --squash"));
        assertNotNull(BashTool.destructiveOp("gh pr review 123 --approve"));
        assertNotNull(BashTool.destructiveOp("gh issue comment 359 --body \"analysis\""));
        assertNotNull(BashTool.destructiveOp("gh issue close 359"));
        assertNotNull(BashTool.destructiveOp("gh issue edit 359 --add-label reviewed"));
    }

    @Test
    void blocksWritingGhApiAndOtherNouns() {
        assertNotNull(BashTool.destructiveOp("gh api repos/o/r/issues/1/comments -f body=hi"));
        assertNotNull(BashTool.destructiveOp("gh api -X POST repos/o/r/issues"));
        assertNotNull(BashTool.destructiveOp("gh api --method DELETE repos/o/r/git/refs/heads/x"));
        assertNotNull(BashTool.destructiveOp("gh release create v1.0"));
        assertNotNull(BashTool.destructiveOp("gh workflow run ci.yml"));
    }

    // ---- read-only / benign: must pass ----

    @Test
    void allowsReadOnlyGitAndGh() {
        assertNull(BashTool.destructiveOp("git status"));
        assertNull(BashTool.destructiveOp("git log --oneline -20"));
        assertNull(BashTool.destructiveOp("git diff HEAD~1 -- some/File.java"));
        assertNull(BashTool.destructiveOp("git show 6cef8b65ba --stat"));
        assertNull(BashTool.destructiveOp("git add -A")); // staging alone is harmless (commit is blocked)
        assertNull(BashTool.destructiveOp("gh issue view 359 --repo o/r --comments"));
        assertNull(BashTool.destructiveOp("gh pr diff 26901"));
        assertNull(BashTool.destructiveOp("gh pr list --state open"));
        assertNull(BashTool.destructiveOp("gh api repos/o/r/pulls/1")); // a plain GET
    }

    @Test
    void allowsBuildTestAndEditCommands() {
        assertNull(BashTool.destructiveOp("./gradlew :inventory-app:test --tests StockLevelsTest"));
        assertNull(BashTool.destructiveOp("grep -rn doExport inventory/"));
        assertNull(BashTool.destructiveOp("cat > /tmp/Patch.java <<'EOF'\npublic class X{}\nEOF"));
        assertNull(BashTool.destructiveOp("sed -i '' 's/foo/bar/' File.java"));
    }

    // ---- end-to-end through execute(): the policy gate ----

    @Test
    void executeRefusesDestructiveWhenReadonlyEnabled() {
        BashTool bash = new BashTool();
        Map<String, Object> params = Map.of("command", "git push origin HEAD");

        System.setProperty("hermit.readonly", "true");
        Map<String, Object> refused = bash.execute(params);
        assertEquals("Error", refused.get("status"));
        assertTrue(String.valueOf(refused.get("message")).toLowerCase().contains("push")
                        || String.valueOf(refused.get("message")).contains("dry-run"),
                "refusal should explain the no-publish policy");
    }

    @Test
    void readOnlyPolicyIsOffByDefault() {
        // Default (property unset) leaves normal autonomous behavior unchanged: the gate is not active.
        assertNull(System.getProperty("hermit.readonly"));
        assertTrue(!BashTool.readOnly(), "read-only must default to OFF");
    }

    // ---- build/test timeout flooring ----

    @Test
    void floorsTimeoutForSlowBuildCommands() {
        assertTrue(BashTool.looksLongRunning("./gradlew :inventory-app:test --tests '*StockLevelsTest'"));
        assertTrue(BashTool.looksLongRunning("cd /repo && ./gradlew :app:compileTestJava"));
        assertTrue(BashTool.looksLongRunning("mvn -q verify"));
        // a too-small (or default) timeout is floored to the build-sized default
        assertEquals(1200, BashTool.effectiveTimeout("./gradlew :app:test", 120));
        assertEquals(1200, BashTool.effectiveTimeout("./gradlew :app:test", 30));
    }

    @Test
    void respectsExplicitLargerTimeoutAndLeavesQuickCommandsAlone() {
        // an explicitly larger request is kept
        assertEquals(1800, BashTool.effectiveTimeout("./gradlew :app:test", 1800));
        // non-build commands are untouched
        assertFalse(BashTool.looksLongRunning("grep -rn doExport ."));
        assertEquals(120, BashTool.effectiveTimeout("git status", 120));
        assertEquals(5, BashTool.effectiveTimeout("echo hi", 5));
    }
}
