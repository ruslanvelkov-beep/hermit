package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.tool.api.Tool;
import hermit.tool.api.ToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The AVAILABLE TOOLS section is the registry's own documentation, so the planner is offered exactly the tools that run. */
class PlanPromptToolsTest {

    @Test
    void theToolSectionIsTheRegistrysDocumentation() {
        ToolRegistry registry = new ToolRegistry();
        String section = new PlanPromptBuilder(registry).availableToolsSection();
        assertTrue(section.startsWith("--- AVAILABLE TOOLS ---\n"), section);
        for (String name : registry.names()) {
            String first = registry.getTool(name).getDocumentation().strip().lines().findFirst().orElse("");
            assertTrue(section.contains("\n- " + first), name + " is offered with its own documentation");
        }
        // one entry per tool: a documentation's further lines are indented, never a new "- " entry
        assertEquals(registry.names().size(), section.lines().filter(l -> l.startsWith("- ")).count(), section);
    }

    @Test
    void aToolsContractKeepsItsFurtherLinesAndStopsAtTheNextTool() {
        ToolRegistry registry = new ToolRegistry();
        String doc = registry.getTool("HindsightDebugger").getDocumentation().strip();
        String last = doc.lines().reduce((a, b) -> b).orElse("").strip();
        String contract = new PlanPromptBuilder(registry).toolContracts("{\"tool\": \"HindsightDebugger\", \"parameters\": {}}");
        assertTrue(contract.startsWith("- HindsightDebugger:"), contract);
        assertTrue(contract.contains(last), contract);
        assertFalse(contract.contains("- FileClassifier:"), contract);
    }

    @Test
    void aToolLeftOutOfTheRegistryLeavesThePrompt() {
        String old = System.getProperty("hermit.chain.disabled");
        System.setProperty("hermit.chain.disabled", "true");
        try {
            String section = new PlanPromptBuilder(new ToolRegistry()).availableToolsSection();
            assertFalse(section.contains("- RemoteAgent:"), section);
            assertFalse(section.contains("- BigBrother:"), section);
            assertTrue(section.contains("- Bash:"), section);
        } finally {
            if (old == null) {
                System.clearProperty("hermit.chain.disabled");
            } else {
                System.setProperty("hermit.chain.disabled", old);
            }
        }
    }

    @Test
    void everyToolAPromptNamesIsRegistered() {
        ToolRegistry registry = new ToolRegistry();
        PlanPromptBuilder prompts = new PlanPromptBuilder(registry);
        // the prompts' own text: the tool section is each tool's own documentation (CodeGraph's says it needs no CodeQL)
        String plan = prompts.generatePrompt(new PlanNode("n", "goal", Map.of()), "ctx");
        StringBuilder all = new StringBuilder(plan.replace(prompts.availableToolsSection(), ""));
        for (String lens : PlanPromptBuilder.REVIEW_LENSES) {
            all.append(prompts.reviewPrompt(lens, "ctx", "{}"));
        }
        all.append(prompts.reviewPrompt("holistic", "ctx", "{}"));
        // the reference's tools the rewrite has not registered: ported later, or dropped by the plan
        for (String tool : List.of("CodeQL", "CodeQlQuery", "AssembleContext", "PathProof", "GenerateTest", "GenerateCode",
                "TddCycle", "TreeCodegen", "Byteman", "WebSearch", "ReadFile")) {
            if (!registry.names().contains(tool)) {
                assertFalse(Pattern.compile("\\b" + tool + "\\b").matcher(all).find(), tool + " is named but not registered");
            }
        }
    }

    @Test
    void aGuardedPassageArrivesWithItsTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.getAllTools().remove("TreeCodegen");   // registered since its port: the test starts without it
        PlanPromptBuilder prompts = new PlanPromptBuilder(registry);
        assertFalse(prompts.reviewPrompt("completeness", "ctx", "{}").contains("PARAMETER SEMANTICS"));
        registry.getAllTools().put("TreeCodegen", new Tool() {
            @Override
            public String getDocumentation() {
                return "TreeCodegen: generates code.";
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> parameters) {
                return Map.of();
            }
        });
        String review = prompts.reviewPrompt("completeness", "ctx", "{}");
        assertTrue(review.contains("in TreeCodegen, `method` is the NEW/edited method"), review);
        String plan = prompts.generatePrompt(new PlanNode("n", "goal", Map.of()), "ctx");
        assertTrue(plan.contains("code-authoring action — TreeCodegen (unit tests, edits)"), plan);
    }
}
