package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** With hermit.prompt.stableFirst the planner prompt keeps its unchanging part first, so a prompt cache can reuse it. */
class StableFirstPromptTest {

    @AfterEach
    void restore() {
        System.clearProperty("hermit.prompt.stableFirst");
    }

    private static String prompt(String context, String ledger) {
        return new PlanPromptBuilder().generatePrompt(new PlanNode("n", "goal", Map.of()), context, ledger);
    }

    private static int commonPrefix(String a, String b) {
        int n = 0;
        while (n < Math.min(a.length(), b.length()) && a.charAt(n) == b.charAt(n)) {
            n++;
        }
        return n;
    }

    @Test
    void theGrowingContextAndTheLedgerComeAfterTheRules() {
        System.setProperty("hermit.prompt.stableFirst", "true");
        String first = prompt("[Iteration 1] read A", "FAILED: x\n");
        String second = prompt("[Iteration 1] read A\n[Iteration 2] read B", "FAILED: x\nFAILED: y\n");
        int rules = first.indexOf("--- AGENT CONTRACT ---");
        int context = first.indexOf("CONTEXT SO FAR:\n[Iteration 1]");
        assertTrue(rules > 0 && context > rules, "the context follows the rules");
        assertTrue(first.indexOf("FAILED: x") > context, "the ledger follows the context");
        assertTrue(first.strip().endsWith("as specified above."), first.substring(first.length() - 200));
        int earlierContextEnd = first.indexOf("[Iteration 1] read A") + "[Iteration 1] read A".length();
        int shared = commonPrefix(first, second);
        assertTrue(shared >= earlierContextEnd && shared <= earlierContextEnd + 1,
                "everything up to the end of the earlier context is shared: " + shared + " vs " + earlierContextEnd);
    }

    @Test
    void theDefaultOrderIsUnchanged() {
        String prompt = prompt("ctx", null);
        assertTrue(prompt.indexOf("CONTEXT SO FAR:\nctx") < prompt.indexOf("--- TOOL SELECTION ---"));
        assertTrue(!prompt.contains("Respond now with ONE JSON object"));
    }
}
