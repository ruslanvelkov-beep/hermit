package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanParserTest {
    private final PlanParser parser = new PlanParser();

    @Test
    void parsesTheCanonicalBranchShape() {
        ParsedPlan p = parser.parse("""
                {"branches":[{"name":"Look","description":"d","confidence":0.9,
                  "actions":[{"tool":"CodeGraph","parameters":{"action":"escapes","symbol":"A#m"}}]}]}
                """);
        assertNotNull(p);
        assertEquals(1, p.branches().size());
        assertEquals("CodeGraph", p.branches().get(0).actions().get(0).toolName());
    }

    @Test
    void toleratesBareToolCallShape() {
        // told to "call TreeCodegen with params", the model emits a bare tool call.
        ParsedPlan p = parser.parse("""
                ```json
                {"tool":"TreeCodegen","parameters":{"file":"X.java","method":"X#m","expectTestOutcome":"red"}}
                ```
                """);
        assertNotNull(p, "bare tool call must be wrapped into a single branch");
        assertEquals(1, p.branches().size());
        Action a = p.branches().get(0).actions().get(0);
        assertEquals("TreeCodegen", a.toolName());
        assertEquals("red", a.parameters().get("expectTestOutcome"));
    }

    @Test
    void toleratesOpenAiArgumentsKeyIncludingStringified() {
        ParsedPlan obj = parser.parse(
                "{\"tool\":\"Bash\",\"arguments\":{\"command\":\"git status\"}}");
        assertNotNull(obj);
        assertEquals("git status", obj.branches().get(0).actions().get(0).parameters().get("command"));

        ParsedPlan str = parser.parse(
                "{\"tool\":\"Bash\",\"arguments\":\"{\\\"command\\\":\\\"git log\\\"}\"}");
        assertNotNull(str, "stringified arguments must parse");
        assertEquals("git log", str.branches().get(0).actions().get(0).parameters().get("command"));
    }

    @Test
    void toleratesTopLevelActionsArray() {
        ParsedPlan p = parser.parse("""
                {"actions":[{"tool":"CodeGraph","parameters":{"action":"locate","symbol":"m"}}]}
                """);
        assertNotNull(p);
        assertEquals("CodeGraph", p.branches().get(0).actions().get(0).toolName());
    }

    @Test
    void stillHonoursFinalAnswerAndClarifyAndRejectsEmpty() {
        assertEquals("done", parser.parse("{\"final_answer\":\"done\"}").finalAnswer());
        assertEquals("which?", parser.parse("{\"clarify\":\"which?\"}").clarification());
        assertNull(parser.parse("{\"note\":\"nothing actionable\"}"),
                "an object with no answer/clarify/branches/tool/actions is still rejected");
    }

    @Test
    void toleratesCommentsAndTrailingCommas() {
        // the 3-bit driver annotates plans with // comments (right where it left a placeholder) and
        // trailing commas; Jackson's strict default rejected the whole plan, 16 iterations in a row.
        ParsedPlan p = parser.parse("""
                ```json
                {"branches":[{"name":"Look","description":"d","confidence":0.9,
                  "actions":[
                    {"tool":"CodeGraph","parameters":{"action":"escapes","symbol":"A#m"}}, // reach the catch
                    {"tool":"SpecTestComposer","parameters":{"action":"reach","target":"A#m",}}, # yaml-style too
                  ],
                }]}
                ```
                """);
        assertNotNull(p, "comments/trailing commas must not reject the plan");
        assertEquals(2, p.branches().get(0).actions().size());
        assertEquals("SpecTestComposer", p.branches().get(0).actions().get(1).toolName());
    }

    @Test
    void unquotedJavaExpressionsInArraysAreQuoted() {
        // "ops": [openShelf().inRoom(MAIN), findBook("dune").hasTitle(t)]
        String fixed = PlanParser.quoteUnquotedArrayItems(
                "{\"ops\": [openShelf().inRoom(MAIN), findBook(\"dune\").hasTitle(t)], "
                        + "\"n\": [1, 2], \"s\": [\"a\", \"b\"], \"o\": [{\"x\": 1}]}");
        assertTrue(fixed.contains("\"openShelf().inRoom(MAIN)\""), fixed);
        assertTrue(fixed.contains("\"findBook(\\\"dune\\\").hasTitle(t)\""), fixed);
        assertTrue(fixed.contains("[1, 2]") && fixed.contains("[\"a\", \"b\"]")
                && fixed.contains("[{\"x\": 1}]"), fixed);
        ParsedPlan p = parser.parse("""
                {"branches":[{"name":"API","description":"d","confidence":0.9,
                  "actions":[{"tool":"TreeCodegen","parameters":{"action":"compose","testName":"t",
                    "ops":[openShelf().inRoom(MAIN), findBook("dune")], "target":"A#m"}}]}]}
                """);
        assertNotNull(p, "the plan must parse after quoting the unquoted ops");
        Object ops = p.branches().get(0).actions().get(0).parameters().get("ops");
        assertTrue(ops instanceof List<?> l && l.size() == 2, String.valueOf(ops));
    }

    @Test
    void javaStyleStringConcatenationIsJoined() {
        assertEquals("{\"code\":\"abc\"}",
                PlanParser.joinConcatenatedStrings("{\"code\":\"a\" + \"b\" + \"c\"}"));
        assertEquals("{\"code\":\"one\\ntwo\"}",
                PlanParser.joinConcatenatedStrings("{\"code\":\"one\\n\" +\n        \"two\"}"));
        // a plain payload is untouched
        assertEquals("{\"a\":\"b\"}", PlanParser.joinConcatenatedStrings("{\"a\":\"b\"}"));
    }

    @Test
    void theShapeLabelIsStrippedFromAFinalAnswer() {
        // the answer began "**Shape B (final_answer):**"
        assertEquals("The defect is at line 686.",
                PlanParser.stripShapeLabel("**Shape B (final_answer):**\n\nThe defect is at line 686."));
        assertEquals("All good.", PlanParser.stripShapeLabel("final_answer: All good."));
        assertEquals("Shape B is the terminal form.", PlanParser.stripShapeLabel("Shape B is the terminal form."));
        assertEquals("The defect is at line 686.", PlanParser.stripShapeLabel("The defect is at line 686."));
    }

    // the model's native Harmony tool call is a single-action plan
    @Test
    void aHarmonyToolCallEnvelopeIsASingleActionPlan() {
        PlanParser p = new PlanParser();
        String raw = "<|channel|>commentary to=SpecTestComposer <|constrain|>json<|message|>{\"action\":\"compose\","
                + "\"newSuite\":{\"dir\":\"library/spec-tests/src/main/java\","
                + "\"package\":\"com.example.scenarios\","
                + "\"className\":\"BookLookupSpec\"},\"testName\":\"t\","
                + "\"ops\":[\"findBook('dune').hasTitle(t)\"],"
                + "\"target\":\"Catalog#findByTitle\"}";
        ParsedPlan plan = p.parse(raw);
        assertNotNull(plan);
        assertFalse(plan.isFinal());
        assertEquals(1, plan.branches().size());
        Action a = plan.branches().get(0).actions().get(0);
        assertEquals("SpecTestComposer", a.toolName());
        assertEquals("compose", a.parameters().get("action"));
        assertEquals("BookLookupSpec", ((Map<?, ?>) a.parameters().get("newSuite")).get("className"));
        // a namespaced tool name and a trailing <|call|> token
        ParsedPlan ns = p.parse(
                "<|channel|>commentary to=functions.Bash <|constrain|>json<|message|>{\"command\":\"ls\"}<|call|>");
        assertEquals("Bash", ns.branches().get(0).actions().get(0).toolName());
        // the analysis channel with a bare constraint word after the tool name
        ParsedPlan bare = p.parse(
                "<|channel|>analysis to=LocateFile code<|message|>{\"name\":\"*Suite.java\",\"root\":\"/r\"}");
        assertEquals("LocateFile", bare.branches().get(0).actions().get(0).toolName());
        assertEquals("*Suite.java", bare.branches().get(0).actions().get(0).parameters().get("name"));
        // k=v tokens after the tool name are parameters; a non-JSON message is a Bash command
        ParsedPlan kv = p.parse("<|channel|>analysis to=CodeGraph action=locate code<|message|>"
                + "{\"symbol\":\"RecipeBook#addRecipe\"}");
        Action kva = kv.branches().get(0).actions().get(0);
        assertEquals("CodeGraph", kva.toolName());
        assertEquals("locate", kva.parameters().get("action"));
        assertEquals("RecipeBook#addRecipe", kva.parameters().get("symbol"));
        ParsedPlan sh = p.parse("<|channel|>analysis to=Bash code<|message|>"
                + "sed -n '150,220p' /r/RecipeBook.java<|call|>");
        Action sha = sh.branches().get(0).actions().get(0);
        assertEquals("Bash", sha.toolName());
        assertEquals("sed -n '150,220p' /r/RecipeBook.java", sha.parameters().get("command"));
        // the final channel is a final answer
        ParsedPlan fin = p.parse(
                "<|channel|>final<|message|>The test was composed and committed as abc1234.<|return|>");
        assertTrue(fin.isFinal());
        assertTrue(fin.finalAnswer().startsWith("The test was composed"));
        // ordinary JSON plans are untouched
        assertNotNull(p.parse("{\"final_answer\": \"done\"}").finalAnswer());
    }

    // exec/bash/sh are the Bash tool; a case-different real tool name is that tool
    @Test
    void shellAliasesAndCaseVariantsAreCanonicalToolNames() {
        PlanParser p = new PlanParser();
        assertEquals("Bash", p.parse("{\"tool\": \"exec\", \"parameters\": {\"command\": \"ls\"}}")
                .branches().get(0).actions().get(0).toolName());
        assertEquals("Bash", p.parse("{\"branches\": [{\"name\": \"b\", \"confidence\": 1, "
                        + "\"actions\": [{\"tool\": \"bash\", \"parameters\": {\"command\": \"ls\"}}]}]}")
                .branches().get(0).actions().get(0).toolName());
        assertEquals("Bash", p.parse("<|channel|>analysis to=exec code<|message|>sed -n '1,5p' f")
                .branches().get(0).actions().get(0).toolName());
        assertEquals("LocateFile", PlanParser.canonicalTool("locatefile"));
        assertEquals("CodeGraph", PlanParser.canonicalTool("codegraph"));
        assertEquals("ReadFile", PlanParser.canonicalTool("ReadFile"), "an unknown name stays as is for the refusal");
    }

    // GPT-OSS's container.exec call carries the command as {"cmd":["bash","-lc",script]}
    @Test
    void aShellCmdArrayIsTheBashCommand() {
        PlanParser p = new PlanParser();
        ParsedPlan h = p.parse("<|channel|>analysis to=container.exec code<|message|>"
                + "{\"cmd\":[\"bash\",\"-lc\",\"sed -n '320,360p' /r/RecipeBook.java\"]}");
        Action a = h.branches().get(0).actions().get(0);
        assertEquals("Bash", a.toolName());
        assertEquals("sed -n '320,360p' /r/RecipeBook.java", a.parameters().get("command"));
        assertNull(a.parameters().get("cmd"));
        // the JSON shapes too; an argv array that is not a shell wrapper is its words, quoted where needed
        Action j = p.parse("{\"tool\": \"Bash\", \"parameters\": {\"cmd\": [\"ls\", \"-la\", \"my dir\"]}}")
                .branches().get(0).actions().get(0);
        assertEquals("ls -la 'my dir'", j.parameters().get("command"));
        Action s = p.parse("{\"actions\": [{\"tool\": \"exec\", \"parameters\": {\"cmd\": \"git status\"}}]}")
                .branches().get(0).actions().get(0);
        assertEquals("Bash", s.toolName());
        assertEquals("git status", s.parameters().get("command"));
        // the argv array under `command` itself
        Action c = p.parse("{\"tool\": \"Bash\", \"parameters\": {\"command\": "
                + "[\"bash\", \"-lc\", \"sed -n '150,260p' /r/A.java\"]}}")
                .branches().get(0).actions().get(0);
        assertEquals("sed -n '150,260p' /r/A.java", c.parameters().get("command"));
        // a real command is left alone, and other tools are untouched
        assertEquals("ls", p.parse("{\"tool\": \"Bash\", \"parameters\": {\"command\": \"ls\", \"cmd\": \"rm\"}}")
                .branches().get(0).actions().get(0).parameters().get("command"));
        assertNull(p.parse("{\"tool\": \"LocateFile\", \"parameters\": {\"cmd\": \"x\", \"name\": \"A.java\"}}")
                .branches().get(0).actions().get(0).parameters().get("command"));
    }

    // a JSON final answer whose text contains a Harmony envelope is the JSON plan
    @Test
    void aJsonPlanContainingAHarmonyEnvelopeInAValueIsTheJsonPlan() {
        PlanParser p = new PlanParser();
        ParsedPlan plan = p.parse("{\"final_answer\": \"<|channel|>commentary to=repo_browser.bash "
                + "code<|message|>sed -n '300,380p' /r/A.java\"}");
        assertTrue(plan.isFinal());
        assertTrue(plan.finalAnswer().contains("<|channel|>"));
        // a bare envelope is still a plan
        assertEquals("Bash", p.parse("<|channel|>commentary to=Bash code<|message|>ls<|call|>")
                .branches().get(0).actions().get(0).toolName());
    }

    // a synonym of LocateFile's one file-name parameter is that parameter
    @Test
    void aLocateFileSynonymBecomesName() {
        PlanParser p = new PlanParser();
        Action a = p.parse("{\"tool\": \"LocateFile\", \"parameters\": {\"filename\": \"PantryIndex.java\"}}")
                .branches().get(0).actions().get(0);
        assertEquals("PantryIndex.java", a.parameters().get("name"));
        assertNull(a.parameters().get("filename"));
        Action kept = p.parse("{\"tool\": \"LocateFile\", \"parameters\": {\"name\": \"A.java\", "
                + "\"file\": \"B.java\"}}")
        .branches().get(0).actions().get(0);
        assertEquals("A.java", kept.parameters().get("name"), "an explicit name wins");
    }

    // a plan one closing brace short is closed and parsed, in a bare or a Harmony-prefixed text
    @Test
    void aPlanMissingItsFinalClosingBraceIsClosedAndParsed() {
        PlanParser parser = new PlanParser();
        String weather = "{\"branches\":[{\"name\":\"GetCurrentWeather\","
                + "\"description\":\"Fetch current weather for Sofia, Bulgaria using web search.\","
                + "\"confidence\":0.9,\"actions\":[{\"tool\":\"WebSearch\",\"parameters\":"
                + "{\"action\":\"research\",\"query\":\"current weather in Sofia, Bulgaria\"}}]}]";
        ParsedPlan plan = parser.parse(weather);
        assertNotNull(plan, "the text is closed and parsed");
        assertEquals("WebSearch", plan.branches().get(0).actions().get(0).toolName());
        assertEquals("current weather in Sofia, Bulgaria",
                plan.branches().get(0).actions().get(0).parameters().get("query"));
        String remote = "<|channel|>final <|constrain|>shape A<|message|>"
                + "{\"branches\":[{\"name\":\"RunGitStatus\","
                + "\"description\":\"Execute git status on the remote machine\","
                + "\"confidence\":1.0,\"actions\":[{\"tool\":\"Bash\",\"parameters\":"
                + "{\"command\":\"git status\",\"remote\":\"user@host:/w/repo\"}}]}]";
        ParsedPlan remotePlan = parser.parse(remote);
        assertNotNull(remotePlan);
        assertEquals("Bash", remotePlan.branches().get(0).actions().get(0).toolName());
        assertEquals("git status", remotePlan.branches().get(0).actions().get(0).parameters().get("command"));
        assertEquals("{\"a\":[1,2]}", PlanParser.closeOpenBrackets("{\"a\":[1,2"));
        assertEquals("{\"a\":[{\"b\":1}]}", PlanParser.closeOpenBrackets("{\"a\":[{\"b\":1},"),
                "a dangling comma goes before the closers");
        assertNull(PlanParser.closeOpenBrackets("{\"a\":[1,2]}"), "nothing open: nothing to close");
        assertNull(PlanParser.closeOpenBrackets("{\"a\":\"unterminated"),
                "inside a string: not a prefix that can be closed");
        assertEquals("{\"a\":[1]}", PlanParser.closeOpenBrackets("{\"a\":[1}"),
                "a closer of the wrong kind becomes the expected one, then the rest is closed");
        assertEquals("{\"a\":[1]}", PlanParser.closeOpenBrackets("{\"a\":[1]}}"), "an extra closer is dropped");
        assertEquals("{\"a\":[1]}", PlanParser.closeOpenBrackets("{\"a\":[1 <|return|>"),
                "a Harmony end token after the JSON is not part of it");
        String wrongCloser = "<|channel|>final <|constrain|>json<|message|>"
                + "{\"branches\":[{\"name\":\"RunGitStatus\",\"description\":\"Execute git status\","
                + "\"confidence\":1.0,\"actions\":[{\"tool\":\"Bash\",\"parameters\":"
                + "{\"command\":\"git status\"}}]} }";
        ParsedPlan wrongCloserPlan = parser.parse(wrongCloser);
        assertNotNull(wrongCloserPlan, "the branch array was closed with a brace: repaired and parsed");
        assertEquals("git status",
                wrongCloserPlan.branches().get(0).actions().get(0).parameters().get("command"));
        assertNull(PlanParser.closeOpenBrackets("{\"a\":\"x}\",\"b\":[\"]\"]}"),
                "brackets inside strings do not count");
    }

    // a Harmony `container.exec` call whose JSON payload carried a
    // stray `]` with no `[` ever opened - `{"command":"sed … | cat -n"],"timeout": 10000}` - closed the root object
    // early at that `]` (there being nothing else open to blame it on), stranding `,"timeout": 10000}` after the
    // object had already ended; three iterations of this burned the whole unparsed-plan budget and halted the run.
    @Test
    void aStrayClosingBracketWithNoMatchingOpenerIsDroppedNotConverted() {
        assertEquals("{\"command\":\"x\",\"timeout\":10000}",
        PlanParser.closeOpenBrackets("{\"command\":\"x\"],\"timeout\":10000}"),
        "the stray ']' opens nothing and closes nothing — dropped so the object closes "
                + "on its own final brace");
        PlanParser parser = new PlanParser();
        ParsedPlan plan = parser.parse("<|channel|>analysis to=container.exec code<|message|>"
                + "{\"command\":\"sed -n '60,120p' /r/ShoppingList.java | cat -n\"],\"timeout\": 10000}");
        assertNotNull(plan, "the stray bracket must not sink the whole tool call");
        Action a = plan.branches().get(0).actions().get(0);
        assertEquals("Bash", a.toolName());
        assertEquals("sed -n '60,120p' /r/ShoppingList.java | cat -n", a.parameters().get("command"));
    }

    // a bare tool call's branch is named after the action and its first value, not just the tool
    @Test
    void aBareToolCallBranchIsNamedAfterItsActionAndValue() {
        assertEquals("Run CodeGraph locate RecipeBook",
                PlanParser.runBranchName(new Action("CodeGraph",
                        Map.of("action", "locate", "symbol", "RecipeBook"))));
        assertEquals("Run Bash sed -n '30,80p' /w/A.java",
                PlanParser.runBranchName(new Action("Bash", Map.of("command", "sed -n '30,80p' /w/A.java"))));
        assertEquals("Run LocateFile", PlanParser.runBranchName(new Action("LocateFile", Map.of())));
        ParsedPlan plan = new PlanParser().parse(
                "<|channel|>commentary to=CodeGraph <|constrain|>json<|message|>{\"action\":\"callers\","
                        + "\"symbol\":\"X#y\"}");
        assertNotNull(plan);
        assertEquals("Run CodeGraph callers X#y", plan.branches().get(0).name());
    }
}
