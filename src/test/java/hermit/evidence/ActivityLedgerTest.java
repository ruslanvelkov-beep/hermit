package hermit.evidence;

import static org.junit.jupiter.api.Assertions.*;

import hermit.memory.EmbeddingProvider;
import hermit.memory.EmbeddingProvider.Mode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityLedgerTest {
    @TempDir Path dir;
    final EmbeddingProvider embeddings = new EmbeddingProvider() {
        public float[] embed(String text, Mode mode) { return text.contains("unrelated") ? new float[]{0,1} : new float[]{1,0}; }
        public int dimension() { return 2; }
        public String id() { return "test-semantic-v1"; }
    };
    ActivityLedger open(String task) { return new ActivityLedger(dir.resolve("activity.db"), task, "project", embeddings); }
    @Test void currentProposalIsExcludedFromItsOwnReviewEvidence() {
        try (var m = open("a")) {
            m.append("FAILED", "previous approach", "x", "r", "not found", 0, "old");
            long current=m.append("PROPOSED", "brand new proposal", "y", "r", "", 0, "current");
            assertFalse(m.context("brand new proposal", 2000, current).contains("brand new proposal"));
            assertTrue(m.context("brand new proposal", 2000, current).contains("not found"));
        }
    }
    @Test void exactEvidenceCanBeReadOnlyWithinItsTask() {
        long id;
        try(var m=open("a")){id=m.append("SUCCEEDED","lookup","x","r","full result",0,"source");assertEquals("full result",m.read(id).outcome());}
        try(var m=open("b")){assertNull(m.read(id));}
    }
    @Test void survivesRestartAndKeepsExactSourceText() {
        long proposal;
        try (var m = open("a")) {
            proposal = m.append("PROPOSED", "Find the declaration", "CodeGraph|locate X", "rev1", "", 0, "steps/1/plan.json");
            m.append("SUCCEEDED", "Find the declaration", "CodeGraph|locate X", "rev1", "X.java:12\nexact result", proposal, "steps/2/results.json");
        }
        try (var m = open("a")) {
            var hit = m.recall("Locate the implementation", 4).stream().filter(e -> e.kind().equals("SUCCEEDED")).findFirst().orElseThrow();
            assertEquals(proposal, hit.parent());
            assertEquals("X.java:12\nexact result", hit.outcome());
            assertEquals("steps/2/results.json", hit.source());
            assertNotNull(m.completed("CodeGraph|locate X", "rev1"));
        }
    }
    @Test void proposalsFailuresAndOtherTasksAreNotCompletedWork() {
        try (var m = open("a")) {
            m.append("PROPOSED", "Find X", "x", "r", "", 0, "");
            m.append("FAILED", "Find Y", "y", "r", "network timeout", 0, "");
            assertNull(m.completed("x", "r"));
            assertNull(m.completed("y", "r"));
        }
        try (var m = open("b")) { assertTrue(m.recall("Find X", 5).isEmpty()); }
    }
    @Test void changedStatePermitsRetryAndSimilarityAloneDoesNotBlock() {
        try (var m = open("a")) {
            m.append("SUCCEEDED", "Inspect X", "x", "r1", "done", 0, "");
            assertNull(m.completed("x", "r2"));
            assertNull(m.completed("y", "r1"));
            assertFalse(m.recall("Investigate Y", 2).isEmpty());
        }
    }
    @Test void modelChangeNeverComparesIncompatibleVectors() {
        try (var m = open("a")) { m.append("SUCCEEDED", "Inspect X", "x", "r1", "done", 0, ""); }
        var different = new EmbeddingProvider() {
            public float[] embed(String t, Mode mode) { return new float[]{1,0}; }
            public int dimension() { return 2; }
            public String id() { return "different-model"; }
        };
        try (var m = new ActivityLedger(dir.resolve("activity.db"), "a", "project", different)) {
            assertTrue(m.recall("paraphrase", 3).isEmpty());
            assertNotNull(m.completed("x", "r1"));
        }
    }
    @Test void lastRejectedFindsTheMostRecentRejectionOfThatExactActionInThatState() {
        try (var m = open("a")) {
            m.append("REJECTED", "grep -n hashOf X.java", "Bash|grep X", "r1", "does not read the method body", 0, "");
            m.append("REJECTED", "grep -n hashOf X.java again", "Bash|grep X", "r1", "still does not read the method body", 0, "");
            var latest = m.lastRejected("Bash|grep X", "r1");
            assertNotNull(latest);
            assertEquals("still does not read the method body", latest.outcome());
            assertNull(m.lastRejected("Bash|grep X", "r2"), "a changed repository state is not the same rejection");
            assertNull(m.lastRejected("Bash|other", "r1"), "a different action is not the same rejection");
        }
    }
    @Test void embeddingOutagePreservesEventsAndRecentFailures() {
        var down = new EmbeddingProvider() {
            public float[] embed(String t, Mode mode) { throw new IllegalStateException("offline"); }
            public int dimension() { return 2; }
            public String id() { return "down"; }
        };
        try (var m = new ActivityLedger(dir.resolve("activity.db"), "a", "project", down)) {
            m.append("REJECTED", "Inspect missing type", "x", "r", "type does not exist", 0, "");
            assertTrue(m.context("anything", 2000).contains("type does not exist"));
        }
    }
    @Test void recentFailuresRemainAfterSuccessAndContextIsBounded() {
        try (var m = open("a")) {
            m.append("REJECTED", "wrong approach", "x", "r", "unsupported assumption", 0, "");
            m.append("SUCCEEDED", "different work", "y", "r", "z".repeat(20000), 0, "");
            String context = m.context("anything", 1800);
            assertTrue(context.contains("unsupported assumption"));
            assertTrue(context.length() <= 1800);
        }
    }
}
