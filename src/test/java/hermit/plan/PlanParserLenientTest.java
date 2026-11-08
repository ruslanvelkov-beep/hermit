package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import org.junit.jupiter.api.Test;

/** The planner must tolerate the JSON quirks LLMs emit when embedding a program
 * in an action parameter - the class of failure that looped a planning turn to
 * the iteration cap ("Unrecognized character escape ''"). */
class PlanParserLenientTest {

    private final PlanParser parser = new PlanParser();

    @Test
    void parsesPlanWithBackslashApostropheInEmbeddedCode() {
        // The exact killer: \' inside a JSON string (illegal in strict JSON).
        String json = "{\"branches\":[{\"name\":\"fetch BTC\",\"description\":\"d\","
                + "\"confidence\":0.9,\"actions\":[{\"tool\":\"SyntaxCheck\",\"parameters\":"
                + "{\"language\":\"python\",\"code\":\"print(\\'BTC: \\'+str(p))\"}}]}]}";
        ParsedPlan plan = parser.parse(json);
        assertNotNull(plan, "plan with \' should parse leniently, not return null");
        assertFalse(plan.isFinal());
        assertEquals(1, plan.branches().size());
        Action a = plan.branches().get(0).actions().get(0);
        assertEquals("SyntaxCheck", a.toolName());
        // \' decoded to a plain apostrophe in the parameter value.
        assertTrue(String.valueOf(a.parameters().get("code")).contains("'BTC: '"),
                String.valueOf(a.parameters().get("code")));
    }

    @Test
    void parsesPlanWithLiteralNewlineInsideCodeString() {
        // A real multi-line program: literal newline inside the JSON string.
        String json = "{\"branches\":[{\"name\":\"x\",\"description\":\"d\",\"confidence\":0.8,"
                + "\"actions\":[{\"tool\":\"RunProgram\",\"parameters\":{\"command\":\"echo a\nrun b\"}}]}]}";
        ParsedPlan plan = parser.parse(json);
        assertNotNull(plan);
        assertTrue(String.valueOf(plan.branches().get(0).actions().get(0).parameters().get("command"))
                .contains("run b"));
    }

    @Test
    void balancedExtractionIgnoresTrailingCodeBlockWithBraces() {
        // JSON object followed by prose/code containing braces: the first BALANCED
        // {...} is taken, so the trailing braces don't corrupt the boundaries.
        ParsedPlan plan = parser.parse("{\"final_answer\":\"ok\"}\nnotes: d = {'a': {'b': 1}}");
        assertNotNull(plan);
        assertTrue(plan.isFinal());
        assertEquals("ok", plan.finalAnswer());
    }

    @Test
    void parsesFinalAnswerWithSingleQuotedJson() {
        // Some models emit single-quoted JSON strings.
        ParsedPlan plan = parser.parse("{'final_answer': 'BTC is 65000 USD'}");
        assertNotNull(plan);
        assertTrue(plan.isFinal());
        assertEquals("BTC is 65000 USD", plan.finalAnswer());
    }

    @Test
    void anActionLeftUnclosedBeforeTheNextOneIsClosedAtTheComma() {
        String raw = "Shape A - write and commit.\n\n{\"branches\": [\n"
                + "  {\"name\": \"write\", \"confidence\": 1.0, \"actions\": [\n"
                + "    {\"tool\": \"Bash\", \"parameters\": {\"command\": "
                + "\"cat > docs/A.md <<'EOF'\\n{ not json }\\nEOF\\n\"},\n"
                + "    {\"tool\": \"Bash\", \"parameters\": {\"command\": "
                + "\"git add docs/A.md && git commit -m 'Add A'\"}}\n  ]}]";
        ParsedPlan plan = new PlanParser().parse(raw);
        assertNotNull(plan);
        assertEquals(2, plan.branches().get(0).actions().size());
        assertTrue(String.valueOf(plan.branches().get(0).actions().get(0).parameters().get("command"))
                .contains("{ not json }"), "braces inside the string are left alone");
    }
}
