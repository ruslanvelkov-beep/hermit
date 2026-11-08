package hermit.text.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import hermit.text.TextLens;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TextLens as a Claude Code Stop hook: the turn read from the transcript, the log, blocking only when asked. */
class ClaudeCodeHookTest {

    static final String CLOCK = "2026-10-28T10:00:00Z";

    static String prompt(String text) {
        return "{\"type\": \"user\", \"message\": {\"role\": \"user\", \"content\": \"" + text + "\"}}";
    }

    static String toolResult(String text) {
        return "{\"type\": \"user\", \"message\": {\"role\": \"user\", \"content\": [{\"type\": \"tool_result\", "
                + "\"tool_use_id\": \"t1\", \"content\": [{\"type\": \"text\", \"text\": \"" + text + "\"}]}]}}";
    }

    static String assistant(String type, String text) {
        String block = type.equals("text") ? "{\"type\": \"text\", \"text\": \"" + text + "\"}"
                : "{\"type\": \"" + type + "\", \"id\": \"t1\", \"name\": \"Bash\", \"input\": {}}";
        return "{\"type\": \"assistant\", \"message\": {\"role\": \"assistant\", \"content\": [" + block + "]}}";
    }

    /** A transcript in {@code dir} with {@code lines}, and the hook's JSON naming it. */
    static String hook(Path dir, boolean active, String... lines) throws IOException {
        Path transcript = dir.resolve("session.jsonl");
        Files.writeString(transcript, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return "{\"session_id\": \"s1\", \"transcript_path\": \"" + transcript + "\", \"hook_event_name\": \"Stop\", "
                + "\"stop_hook_active\": " + active + "}";
    }

    static TextLensCommandTest.Run run(Path log, String input, String... more) {
        List<String> args = new ArrayList<>(List.of("--log", log.toString(), "--clock", CLOCK, "--zone", "UTC"));
        args.addAll(List.of(more));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ClaudeCodeHook.run(args, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new TextLensCommandTest.Run(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    static List<JsonNode> logged(Path log) throws IOException {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : Files.readAllLines(log)) {
            lines.add(TextLensServer.JSON.readTree(line));
        }
        return lines;
    }

    @Test
    void theTurnIsTheAnswerAfterTheLastUserEntryWithItsSources() {
        ClaudeCodeHook.Turn turn = ClaudeCodeHook.lastTurn(List.of(
                prompt("an old request"), assistant("text", "An old answer."),
                prompt("When was the release?"), assistant("text", "Let me look."), assistant("tool_use", ""),
                toolResult("Sent 07/18/2000 08:58 AM"), "not json",
                "{\"type\": \"user\", \"isMeta\": true, \"message\": {\"content\": \"a reminder\"}}",
                assistant("thinking", ""), assistant("text", "It was on Thursday, July 6."),
                "{\"type\": \"assistant\", \"isSidechain\": true, \"message\": {\"content\": [{\"type\": \"text\", "
                        + "\"text\": \"a subagent\"}]}}",
                assistant("text", "Nothing else changed."), "{\"type\": \"last-prompt\", \"lastPrompt\": \"x\"}"));
        assertEquals("It was on Thursday, July 6.\n\nNothing else changed.", turn.answer());
        assertEquals(List.of("When was the release?", "Sent 07/18/2000 08:58 AM"), turn.sources());
        assertNull(ClaudeCodeHook.lastTurn(List.of(prompt("Hello"), assistant("tool_use", ""))));
        assertNull(ClaudeCodeHook.lastTurn(List.of()));
    }

    @Test
    void theHookLogsTheFindingsAndLetsClaudeStop(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("logs/claude-code.jsonl");
        String input = hook(dir, false, prompt("When?"), assistant("text", "The release was on Mon Oct 20, 2026."));
        TextLensCommandTest.Run run = run(log, input, "--server", "0");
        assertEquals(new TextLensCommandTest.Run(0, "", ""), run);
        run(log, input, "--server", "0");
        List<JsonNode> lines = logged(log);
        assertEquals(2, lines.size());
        assertEquals("{\"time\":\"2026-10-28T10:00:00Z\",\"session\":\"s1\",\"characters\":36,"
                + "\"checks\":{\"INCONSISTENT\":1},\"findings\":[{\"checker\":\"date\","
                + "\"sentence\":\"October 20, 2026 was a Tuesday, not a Monday.\"}]}", lines.get(0).toString());
    }

    @Test
    void aBlockingHookBlocksTheStopOnce(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("claude-code.jsonl");
        TextLensCommandTest.Run blocked = run(log, hook(dir, false, prompt("When?"),
                assistant("text", "The release was on Mon Oct 20, 2026.")), "--server", "0", "--block");
        assertEquals(0, blocked.code());
        JsonNode decision = TextLensServer.JSON.readTree(blocked.out());
        assertEquals("block", decision.path("decision").asText());
        assertEquals("TextLens found: October 20, 2026 was a Tuesday, not a Monday. Check these statements before "
                + "you finish.", decision.path("reason").asText());
        assertEquals("", run(log, hook(dir, true, prompt("When?"),
                assistant("text", "The release was on Mon Oct 20, 2026.")), "--server", "0", "--block").out(),
                "the stop the hook already blocked");
        assertEquals("", run(log, hook(dir, false, prompt("When?"), assistant("text", "We met on Tue Oct 20.")),
                "--server", "0", "--block").out(), "no findings");
    }

    @Test
    void aFailureIsLoggedAndTheHookStillExitsZero(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("claude-code.jsonl");
        TextLensCommandTest.Run run = run(log, "{\"session_id\": \"s2\", \"transcript_path\": \""
                + dir.resolve("missing.jsonl") + "\"}", "--server", "0");
        assertEquals(new TextLensCommandTest.Run(0, "", ""), run);
        JsonNode line = logged(log).get(0);
        assertEquals("s2", line.path("session").asText());
        assertTrue(line.path("error").asText().contains("missing.jsonl"), line.toString());
        assertEquals(0, run(log, hook(dir, false, prompt("Hello")), "--server", "0").code());
        assertEquals(1, logged(log).size(), "a turn without an answer is not logged");
    }

    @Test
    void aUsageErrorExitsOneWithoutBlocking(@TempDir Path dir) {
        TextLensCommandTest.Run run = run(dir.resolve("log.jsonl"), "{}", "--colour", "red");
        assertEquals(1, run.code());
        assertTrue(run.err().startsWith("textlens: unknown option: --colour\nusage: textlens hook claude-code"),
                run.err());
        assertEquals("", run.out());
        assertFalse(Files.exists(dir.resolve("log.jsonl")));
    }

    @Test
    void theHookAsksARunningServer(@TempDir Path dir) throws Exception {
        TextLensServer server = new TextLensServer(TextLens.standard(), 0, 0);
        try {
            Path log = dir.resolve("claude-code.jsonl");
            Path local = dir.resolve("local.jsonl");
            String input = hook(dir, false, prompt("When?"), assistant("text", "The release was on Mon Oct 20, 2026."));
            assertEquals(0, run(log, input, "--server", String.valueOf(server.port())).code());
            assertEquals(0, run(local, input, "--server", "0").code());
            assertEquals(logged(local), logged(log));
            assertEquals("{\"requests\": 1}", TextLensServerTest.get(server.port(), "/stats").body());
        } finally {
            server.stop();
        }
    }

    @Test
    void theTurnReadsEachEntrysTypeAndAnyToolResultBlock() {
        ClaudeCodeHook.Turn turn = ClaudeCodeHook.lastTurn(List.of(
                "{\"type\": \"user\", \"message\": {\"content\": \"When?\"}}",
                "{\"type\": \"user\", \"message\": {\"content\": [{\"type\": \"tool_result\", \"content\": "
                        + "\"Sent 07/18/2000\"}, {\"type\": \"text\", \"text\": \"a note beside the result\"}]}}",
                "{\"type\": \"assistant\", \"message\": {\"content\": [{\"type\": \"text\", \"text\": "
                        + "\"It was July 6.\"}]}}"));
        assertEquals(new ClaudeCodeHook.Turn("It was July 6.", List.of("When?", "Sent 07/18/2000")), turn,
                "no role in the messages, and a user entry with a tool_result block is a tool result");
        assertEquals(new ClaudeCodeHook.Turn("Resumed.", List.of()), ClaudeCodeHook.lastTurn(List.of(
                "{\"type\": \"assistant\", \"message\": {\"content\": [{\"type\": \"text\", \"text\": "
                        + "\"Resumed.\"}]}}")), "an answer before any prompt");
    }
}
