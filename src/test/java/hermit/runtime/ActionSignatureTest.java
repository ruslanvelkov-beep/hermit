package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import hermit.api.Action;
import hermit.plan.Branch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The loop guard's signature: same action → same sig (so repeats are detected). */
class ActionSignatureTest {

    private static Branch search(String q) {
        return new Branch("b", "d", 0.9,
                List.of(new Action("WebSearch", Map.of("action", "search", "query", q))));
    }

    @Test
    void identicalActionsProduceEqualSignatures() {
        assertEquals(actionSig(search("current price of BTC")),
                actionSig(search("current price of BTC")));
    }

    @Test
    void differentQueryProducesDifferentSignature() {
        assertNotEquals(actionSig(search("current price of BTC")),
                actionSig(search("BTC price on coinbase")));
    }

    @Test
    void signatureIndependentOfParamMapOrdering() {
        Branch a = new Branch("b", "d", 0.9, List.of(new Action("WebSearch",
                new LinkedHashMap<>(Map.of("query", "x", "action", "search")))));
        Branch b = new Branch("b", "d", 0.9, List.of(new Action("WebSearch",
                new TreeMap<>(Map.of("action", "search", "query", "x")))));
        assertEquals(actionSig(a), actionSig(b));
    }

    // every TreeEdit signed as "treeedit()" (it has none of the salient keys), so the plan that
    // replaced three more members after one that replaced five was refused as a repeat of it
    @Test
    void editsWithNoneOfTheSalientKeysAreSignedByWhatTheyChange() {
        Branch five = new Branch("b", "d", 0.9, List.of(new Action("TreeEdit", Map.of("file", "A.java",
                "items", List.of(Map.of("op", "replaceMember", "method", "A#a", "code", "void a() {}"))))));
        Branch three = new Branch("b", "d", 0.9, List.of(new Action("TreeEdit", Map.of("file", "A.java",
                "items", List.of(Map.of("op", "replaceMember", "method", "A#b", "code", "void b() {}"))))));
        assertNotEquals(actionSig(five), actionSig(three));
        assertEquals(actionSig(five), actionSig(new Branch("c", "e", 0.5, five.actions())));
        Branch read = new Branch("b", "d", 0.9, List.of(new Action("Bash", Map.of("command", "ls", "workingDir", "/a"))));
        assertEquals(actionSig(read), actionSig(new Branch("b", "d", 0.9,
                List.of(new Action("Bash", Map.of("command", "ls", "workingDir", "/b"))))), "a salient key signs as before");
    }

    private static String actionSig(Branch b) {
        return ResultSummaries.actionSignature(b);
    }
}
