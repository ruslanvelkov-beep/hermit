package hermit.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// the turn state is plain data - it round-trips through JSON without loss
class TurnSnapshotTest {

    static TurnSnapshot sample() {
        TurnSnapshot s = new TurnSnapshot();
        s.request = "Compose a validated test for item 1.2.3";
        s.turn = 2;
        s.iteration = 7;
        s.seam = "plan";
        s.contextLog = "Current request: …\n[Iteration 6] Executed branch 'x'. Tool results: TreeCodegen={status=Error}";
        s.history = List.of("User: a", "Assistant: b");
        s.successfulResults = "CodeGraph => CodeGraph={status=Success, action=reach}\n";
        s.succeededTools = List.of("CodeGraph", "Bash");
        s.lastSuccessTool = "Bash";
        s.lastSuccessResult = new LinkedHashMap<>(Map.of("status", "Success", "output", "ok", "exit", 0));
        s.lastRefusalHint = "closest known verbs: [shelfMove]";
        s.nodeName = "compose";
        s.nodeContext = new LinkedHashMap<>(Map.of("TreeCodegen", Map.of("status", "Error")));
        s.committedThisSession = true;
        s.testOutcomeObserved = true;
        s.buildBroken = false;
        s.haveSuccess = true;
        s.generatedFile = "/tmp/x/FooTest.java";
        s.generatedMethod = "fooFails";
        s.autoCommitted = true;
        s.pendingExpertProgram = "print(1)";
        s.lastMemoryProgram = "print(2)";
        s.planReviews = 2;
        s.jsonRepairs = 1;
        s.answerGates = 2;
        s.deterministicGates = 3;
        s.uncommittedRefusals = 1;
        s.regenerationRefusals = 1;
        s.clarifyGates = 1;
        s.forbiddenGates = 1;
        s.planShapedGates = 1;
        s.repeatGates = 2;
        s.noToolGates = 1;
        s.webReadGates = 1;
        s.authoredPlans = 3;
        s.programConsults = 1;
        s.fabricationGates = 2;
        s.surrenderGates = 1;
        s.repeatCount = 1;
        s.lastActionSig = "bash(command=ls;) ";
        s.webSearchRan = true;
        s.webReadRan = false;
        s.planEscalationProbed = true;
        s.executedSignatures = new LinkedHashMap<>(Map.of("Bash|ls", "Success"));
        s.triedBranches = List.of("a", "b");
        s.issueRounds = new LinkedHashMap<>(Map.of("issue x", 2));
        s.editOriginals = new LinkedHashMap<>(Map.of("/tmp/x/Foo.java", "class Foo {}"));
        s.workbenchDir = "/tmp/wb";
        s.workbenchTree = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
        return s;
    }

    @Test
    void roundTripsThroughJsonWithoutLoss() {
        TurnSnapshot a = sample();
        TurnSnapshot b = TurnSnapshot.fromJson(a.toJson());
        assertEquals(a.toJson(), b.toJson());
        assertEquals(7, b.iteration);
        assertEquals(2, b.turn);
        assertEquals("compose", b.nodeName);
        assertEquals("Error", ((Map<?, ?>) b.nodeContext.get("TreeCodegen")).get("status"));
        assertEquals(List.of("CodeGraph", "Bash"), b.succeededTools);
        assertEquals(0, b.lastSuccessResult.get("exit"));
        assertEquals(3, b.authoredPlans);
        assertEquals("Success", b.executedSignatures.get("Bash|ls"));
        assertEquals(2, b.issueRounds.get("issue x"));
        assertTrue(b.committedThisSession && b.testOutcomeObserved && b.haveSuccess && b.autoCommitted);
        // copy() is a detached deep copy
        TurnSnapshot c = a.copy();
        c.executedSignatures.put("other", "Error");
        c.iteration = 99;
        assertEquals(7, a.iteration);
        assertNull(a.executedSignatures.get("other"));
    }

    @Test
    void writesAndReadsAFile() throws Exception {
        Path dir = Files.createTempDirectory("turnsnapshot");
        Path f = dir.resolve("nested/state.json");
        sample().write(f);
        TurnSnapshot back = TurnSnapshot.read(f);
        assertEquals(sample().toJson(), back.toJson());
        // unknown fields from a newer hermit are ignored, missing ones take defaults
        TurnSnapshot partial = TurnSnapshot.fromJson("{\"request\":\"r\",\"iteration\":3,\"futureField\":true}");
        assertEquals(3, partial.iteration);
        assertEquals("", partial.lastActionSig);
        assertTrue(partial.triedBranches.isEmpty());
    }

    @Test
    void toolResultsBecomeJsonShaped() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "Success");
        result.put("file", Path.of("/tmp/a/B.java"));
        result.put("edits", List.of(Map.of("line", 3, "path", Path.of("x"))));
        result.put("arr", new String[] {"p", "q"});
        result.put("n", 4L);
        result.put("none", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> safe = (Map<String, Object>) TurnSnapshot.jsonSafe(result);
        assertEquals("/tmp/a/B.java", safe.get("file"));          // a Path is a string, not a list of segments
        assertEquals(List.of("p", "q"), safe.get("arr"));
        assertEquals(4L, safe.get("n"));
        assertNull(safe.get("none"));
        @SuppressWarnings("unchecked")
        Map<String, Object> edit = (Map<String, Object>) ((List<?>) safe.get("edits")).get(0);
        assertEquals("x", edit.get("path"));
        // and it serializes
        TurnSnapshot s = new TurnSnapshot();
        s.lastSuccessResult = safe;
        assertEquals("/tmp/a/B.java", TurnSnapshot.fromJson(s.toJson()).lastSuccessResult.get("file"));
    }
}
