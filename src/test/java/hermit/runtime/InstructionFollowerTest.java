package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.api.Action;
import hermit.tool.api.ToolRegistry;
import hermit.tool.process.BashTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The instruction follower against a scripted model; Bash steps run for real in a temporary directory. */
class InstructionFollowerTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    /** The prompts the scripted model was given. */
    final List<String> prompts = new ArrayList<>();

    InstructionFollower follower(String reply) {
        return new InstructionFollower(prompt -> {
            prompts.add(prompt);
            return reply;
        }, tool -> tool + " documentation", null);
    }

    Action bash(String command) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("command", command);
        p.put("workingDir", dir.toString());
        return new Action("Bash", p);
    }

    /** Runs an instruction or a step as the executor would: Bash for real, anything else answers Success. */
    static final Function<Action, Map<String, Object>> RUN = a -> "Bash".equals(a.toolName())
            ? new BashTool().execute(a.parameters()) : Map.of("status", "Success", "ran", a.parameters());

    static String split(String why, String... stepJson) {
        return "{\"split\": true, \"why\": \"" + why + "\", \"steps\": [" + String.join(",", stepJson) + "]}";
    }

    static String bashStep(String command, String why) {
        try {
            return "{\"tool\": \"Bash\", \"parameters\": {\"command\": " + MAPPER.writeValueAsString(command)
                    + "}, \"why\": \"" + why + "\"}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anInstructionThatOnlyReadsRunsAsGivenWithoutAskingTheModel() {
        InstructionFollower f = follower("{\"split\": true}");
        Map<String, Object> r = f.follow(bash("ls -la | head -3 && test -d ."), RUN);
        assertEquals("Success", r.get("status"));
        assertTrue(prompts.isEmpty());
        assertNull(r.get("followed"), "as given: the tool's own result");
        Map<String, Object> edit = f.follow(new Action("TreeEdit", Map.of("file", "A.java",
                "items", List.of(Map.of("op", "addImport", "code", "import a.B;"), Map.of("op", "addImport", "code", "import a.C;")))), RUN);
        assertEquals("Success", edit.get("status"));
        assertTrue(prompts.isEmpty(), "TreeEdit items already apply all or none and name the failed item");
    }

    @Test
    void aChainIsSplitIntoItsCommandsWhichStopAtTheFirstThatFails() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "x");
        InstructionFollower f = follower(split("three commands, each with its own result",
                bashStep("cp a.txt b.txt", "copy"), bashStep("grep -q y b.txt", "check the copy"),
                bashStep("rm a.txt", "remove the original")));
        Map<String, Object> r = f.follow(bash("cp a.txt b.txt && grep -q y b.txt && rm a.txt"), RUN);

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("cp a.txt b.txt && grep -q y b.txt && rm a.txt"), prompts.get(0));
        assertTrue(prompts.get(0).contains("Bash documentation"));
        assertEquals("NonZeroExit", r.get("status"));
        String message = String.valueOf(r.get("message"));
        assertTrue(message.contains("split this instruction into 3 steps (three commands, each with its own result)"), message);
        assertTrue(message.contains("step 2 of 3 failed: `grep -q y b.txt`"), message);
        assertTrue(message.contains("did not run"), message);
        assertTrue(Files.exists(dir.resolve("b.txt")), "step 1 ran");
        assertTrue(Files.exists(dir.resolve("a.txt")), "step 3 did not run after step 2 failed");
        assertEquals("cp a.txt b.txt && grep -q y b.txt && rm a.txt", r.get("command"));
        assertTrue(String.valueOf(r.get("followed")).contains("not run"), String.valueOf(r.get("followed")));
    }

    @Test
    void theStepsKeepTheInstructionsOtherParameters() {
        List<Action> ran = new ArrayList<>();
        InstructionFollower f = follower(split("two commands", bashStep("true", "one"), bashStep("echo ok", "two")));
        Map<String, Object> r = f.follow(bash("true && echo ok"), a -> {
            ran.add(a);
            return Map.of("status", "Success", "output", "done", "exitCode", 0);
        });
        assertEquals("Success", r.get("status"));
        assertEquals(List.of("true", "echo ok"), ran.stream().map(a -> a.parameters().get("command")).toList());
        assertTrue(ran.stream().allMatch(a -> dir.toString().equals(a.parameters().get("workingDir"))));
        assertTrue(String.valueOf(r.get("message")).contains("ran this instruction as 2 steps"), String.valueOf(r.get("message")));
    }

    // the user's example: "append T to file X" is find the file, confirm it exists, then append
    @Test
    void aSplitMayAddReadOnlyChecksAndAFailedCheckStopsTheInstruction() {
        InstructionFollower f = follower(split("appending needs the file to exist",
                bashStep("test -f notes.md", "the file must exist"), bashStep("printf 'T\\n' >> notes.md", "append")));
        Map<String, Object> r = f.follow(bash("printf 'T\\n' >> notes.md"), RUN);
        assertEquals("NonZeroExit", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).contains("step 1 of 2 failed: `test -f notes.md`"), String.valueOf(r.get("message")));
        assertFalse(Files.exists(dir.resolve("notes.md")), "nothing was appended to a file that was not there");
    }

    // the live model wrote a printf's \n as a line break in its JSON: the step is still the instruction's command, and
    // the instruction's own text is what runs
    @Test
    void aStepIsMatchedAcrossEscapesAndSpacingAndRunsTheInstructionsOwnText() {
        List<Action> ran = new ArrayList<>();
        String first = "printf 'a\\n' > one.txt";
        String second = "printf '| row |\\n' >> two.txt";
        InstructionFollower f = follower(split("two writes", bashStep("printf 'a\n'  > one.txt", "one"),
                bashStep("printf '| row |\n' >> two.txt", "two")));
        Map<String, Object> r = f.follow(bash(first + " && " + second), a -> {
            ran.add(a);
            return Map.of("status", "Success", "output", "", "exitCode", 0);
        });
        assertEquals(List.of(first, second), ran.stream().map(a -> a.parameters().get("command")).toList());
        assertTrue(String.valueOf(r.get("message")).contains("as 2 steps"), String.valueOf(r.get("message")));
    }

    @Test
    void aSplitThatChangesDropsOrAddsAnEditRunsTheInstructionAsGiven() {
        String given = "printf 'a\\n' > one.txt && printf 'b\\n' > two.txt";
        for (String reply : List.of(
                split("changed", bashStep("printf 'a\\n' > one.txt", "1"), bashStep("printf 'B\\n' > two.txt", "2")),
                split("dropped", bashStep("printf 'a\\n' > one.txt", "1")),
                split("added", bashStep("printf 'a\\n' > one.txt", "1"), bashStep("rm -f x", "x"), bashStep("printf 'b\\n' > two.txt", "2")),
                split("reordered", bashStep("printf 'b\\n' > two.txt", "2"), bashStep("printf 'a\\n' > one.txt", "1")),
                "no JSON at all", "")) {
            Map<String, Object> r = follower(reply).follow(bash(given), RUN);
            assertEquals("Success", r.get("status"), reply);
            assertEquals(given, r.get("command"), "the instruction itself ran: " + reply);
            assertNull(r.get("followed"), reply);
        }
        assertEquals(6, prompts.size());
    }

    @Test
    void aCommandThatChangesTheShellOrIsNotAPlainChainRunsAsGivenWithoutAskingTheModel() {
        InstructionFollower f = follower(split("x", bashStep("true", "x")));
        for (String command : List.of("cd sub && touch x", "export A=1 && touch x", "touch x || touch y", "touch x; touch y",
                "touch x\ntouch y", "cat > x <<'EOF'\na && b\nEOF", "touch x & touch y")) {
            f.follow(bash(command), a -> Map.of("status", "Success"));
        }
        assertTrue(prompts.isEmpty(), prompts.toString());
    }

    @Test
    void aReplyThatKeepsTheInstructionWholeRunsItAsGiven() {
        Map<String, Object> asGiven = follower("{\"split\": false}").follow(bash("printf 'a' > a.txt"), RUN);
        assertEquals("Success", asGiven.get("status"));
        assertNull(asGiven.get("followed"));
        Map<String, Object> oneStep = follower(split("one", bashStep("printf 'a' > a.txt", "the whole")))
                .follow(bash("printf 'a' > a.txt"), RUN);
        assertNull(oneStep.get("followed"), "one step that is the instruction is no split");
        Map<String, Object> noModel = new InstructionFollower(p -> null, t -> "", null).follow(bash("printf 'a' > b.txt"), RUN);
        assertEquals("Success", noModel.get("status"), "no answer from the model: as given");
        assertTrue(Files.exists(dir.resolve("b.txt")));
    }

    @Test
    void aTreeCodegenTaskIsSplitIntoPartsAndOnlyTheLastRunsTheInstructionsTest() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("task", "add a counter field and a method that increments it");
        p.put("file", "src/main/java/a/Counter.java");
        p.put("method", "Counter#increment");
        p.put("projectDir", "/project");
        p.put("testFilter", "a.CounterTest");
        p.put("expectTestOutcome", "green");
        List<Action> ran = new ArrayList<>();
        InstructionFollower f = follower("{\"split\": true, \"why\": \"a field, then the method that uses it\", \"steps\": ["
                + "{\"tool\": \"TreeCodegen\", \"parameters\": {\"task\": \"add a counter field\", \"method\": \"Counter#count\"}, \"why\": \"the field\"},"
                + "{\"tool\": \"TreeCodegen\", \"parameters\": {\"task\": \"add a method that increments it\", \"method\": \"Counter#increment\"}, \"why\": \"the method\"}]}");
        Map<String, Object> r = f.follow(new Action("TreeCodegen", p), a -> {
            ran.add(a);
            return Map.of("status", "Success", "testOutcome", "green");
        });
        assertEquals("Success", r.get("status"));
        assertEquals("green", r.get("testOutcome"), "the last step's result is the instruction's");
        assertEquals(2, ran.size());
        Map<String, Object> first = ran.get(0).parameters();
        assertEquals("add a counter field", first.get("task"));
        assertEquals("/project", first.get("projectDir"));
        assertNull(first.get("testFilter"), "the test is the whole instruction's, so it runs after the last part");
        assertNull(first.get("expectTestOutcome"));
        assertEquals("a.CounterTest", ran.get(1).parameters().get("testFilter"));
        assertEquals("Counter#increment", ran.get(1).parameters().get("method"));
    }

    @Test
    void everyInstructionIsTracedWithWhatTheFollowerDid() throws Exception {
        Path trace = dir.resolve("follower.jsonl");
        InstructionFollower f = new InstructionFollower(prompt -> split("two", bashStep("true", "one"), bashStep("true", "two")),
                tool -> "", trace);
        f.follow(bash("true && true"), RUN);
        f.follow(bash("printf x > y.txt"), RUN);
        f.follow(bash("cat y.txt"), RUN);
        List<String> rows = Files.readAllLines(trace);
        assertEquals(3, rows.size(), String.join("\n", rows));
        JsonNode first = MAPPER.readTree(rows.get(0));
        assertEquals("split", first.path("decision").asText());
        assertEquals(2, first.path("steps").size());
        assertTrue(MAPPER.readTree(rows.get(1)).path("decision").asText().startsWith("as given: the plan"),
                rows.get(1));
        assertEquals("as given: it only reads", MAPPER.readTree(rows.get(2)).path("decision").asText());
    }

    // a failed instruction goes to DeepSeek Flash half the time; a test that ran and failed is an
    // outcome for the planners, not a failure to follow
    @Test
    void aFailedInstructionIsNamedForTheHelperButAFailedTestIsNot() {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("Bash", Map.of("status", "Success", "output", "ok"));
        assertNull(InstructionFollower.failure(results));
        results.put("Bash#2", Map.of("status", "NonZeroExit", "exitCode", 1, "failedTests", "a.BTest > c FAILED"));
        assertNull(InstructionFollower.failure(results), "a red test is the planners' to judge");
        results.put("TreeEdit", Map.of("status", "Error", "message", "item 2 of 3 failed, and the file is as it was: no method x"));
        assertEquals("TreeEdit: item 2 of 3 failed, and the file is as it was: no method x", InstructionFollower.failure(results));
    }

    // DeepSeek is set aside only when it is out of funds; a timeout or a server error is not that
    @Test
    void onlyARefusalForWantOfFundsSetsTheHelperAside() {
        assertTrue(InstructionFollower.outOfFunds("deepseek-flash: HTTP 402: {\"error\":{\"message\":\"Insufficient Balance\"}}"));
        assertTrue(InstructionFollower.outOfFunds("deepseek-flash: HTTP 400: insufficient balance on the account"));
        assertFalse(InstructionFollower.outOfFunds("deepseek-flash: HttpTimeoutException: request timed out"));
        assertFalse(InstructionFollower.outOfFunds("deepseek-flash: HTTP 500: scripted"));
        assertFalse(InstructionFollower.outOfFunds("deepseek-flash: HTTP 429: rate limited"));
        assertFalse(InstructionFollower.outOfFunds(null));
    }

    // when DeepSeek is out of funds, it is set aside at once, and stays aside in later runs until the user removes the
    // marker file
    @Test
    void aHelperThatDoesNotAnswerIsSetAsideUntilItsMarkerIsRemoved() throws Exception {
        Path marker = dir.resolve("deepseek-off.txt");
        System.setProperty("hermit.follower.helperOff", marker.toString());
        try {
            InstructionFollower.resetHelper();
            assertNull(InstructionFollower.helperOff());
            assertEquals(0.5, InstructionFollower.helpChance());
            InstructionFollower.setHelperOff("deepseek-flash: HTTP 402: Insufficient Balance");
            assertTrue(InstructionFollower.helperOff().contains("Insufficient Balance"));
            assertTrue(Files.readString(marker).contains("Insufficient Balance"));
            InstructionFollower.resetHelper();   // a new run
            assertTrue(InstructionFollower.helperOff().contains("Insufficient Balance"), "the marker keeps it aside");
            Files.delete(marker);
            assertNull(InstructionFollower.helperOff());
        } finally {
            System.clearProperty("hermit.follower.helperOff");
            InstructionFollower.resetHelper();
        }
    }

    @Test
    void theExecutorHandsAPlansInstructionsToTheFollowerAndItsOwnActionsNot() throws Exception {
        ToolExecutor executor = new ToolExecutor(new ToolRegistry());
        List<String> asked = new ArrayList<>();
        executor.setFollower(new InstructionFollower(prompt -> {
            asked.add(prompt);
            return split("two", bashStep("printf a > a.txt", "a"), bashStep("printf b > b.txt", "b"));
        }, tool -> "", null));
        Map<String, Object> plan = executor.executeInstructions(List.of(bash("printf a > a.txt && printf b > b.txt")));
        assertEquals(1, asked.size());
        assertTrue(String.valueOf(((Map<?, ?>) plan.get("Bash")).get("followed")).contains("printf b > b.txt"));
        assertEquals("b", Files.readString(dir.resolve("b.txt")));
        executor.executeActions(List.of(bash("printf c > c.txt && printf d > d.txt")));
        assertEquals(1, asked.size(), "Hermit's own actions (a build, a commit) are not instructions");
    }
}
