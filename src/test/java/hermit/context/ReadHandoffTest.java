package hermit.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.Action;
import hermit.model.escalation.EscalationLadder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An oversized read is handed to the hosted ladder for an extract, not cut. */
class ReadHandoffTest {

    @TempDir
    Path dir;

    private static Map<String, Object> bashResult(String output) {
        return Map.of("status", "Success", "exitCode", 0, "output", output);
    }

    private Function<String, EscalationLadder.Reply> extractor(List<String> prompts) {
        return prompt -> {
            prompts.add(prompt);
            return new EscalationLadder.Reply("SymbolResolver.java:44: public static SymbolResolver resolve(ParseTree tree)",
                    "luna", "flex", "extract");
        };
    }

    @Test
    void anOversizedReadUnderAnAllowedRootBecomesAMarkedExtract() throws IOException {
        Path ref = Files.createDirectories(dir.resolve("reference/sym"));
        String command = "REF=" + ref + " && for f in A B; do sed -n '1,40p' $REF/$f.java; grep -n 'public' $REF/$f.java; done";
        List<Action> actions = List.of(new Action("Bash", Map.of("command", command, "workingDir", dir.toString())));
        String big = "x".repeat(31_572);
        List<String> prompts = new ArrayList<>();
        List<ReadHandoff.Note> notes = new ArrayList<>();
        Map<String, Object> out = ReadHandoff.apply(actions, Map.of("Bash", bashResult(big)), "write the contract", 8000,
                List.of(dir), dir, extractor(prompts), dir.resolve("saved"), notes);
        String output = String.valueOf(((Map<?, ?>) out.get("Bash")).get("output"));
        assertTrue(output.startsWith("[EXTRACT by luna of a 31572-character output"), output);
        assertTrue(output.contains("public static SymbolResolver resolve(ParseTree tree)"), output);
        assertTrue(output.length() <= 8000);
        assertEquals(1, notes.size());
        assertEquals(31_572, notes.get(0).outputChars());
        assertTrue(Files.readString(notes.get(0).fullOutput()).contains(big), "the full output is kept for review");
        assertTrue(prompts.get(0).contains("THE AGENT'S TASK:\nwrite the contract") && prompts.get(0).contains(big));
    }

    @Test
    void aPathOutsideTheRootsIsNeverSent() {
        String big = "y".repeat(20_000);
        List<String> prompts = new ArrayList<>();
        for (String command : List.of("cat /etc/hosts", "sed -n '1,400p' ../outside/File.java", "cd /tmp && cat a.txt",
                "cat $UNSET/File.java", "cat ~/notes.txt")) {
            List<Action> actions = List.of(new Action("Bash", Map.of("command", command, "workingDir", dir.toString())));
            Map<String, Object> results = Map.of("Bash", bashResult(big));
            List<ReadHandoff.Note> notes = new ArrayList<>();
            assertEquals(results, ReadHandoff.apply(actions, results, "task", 8000, List.of(dir), dir, extractor(prompts),
                    null, notes), command);
            assertTrue(notes.isEmpty(), command);
        }
        assertTrue(prompts.isEmpty(), "nothing outside the roots reached the hosted model");
    }

    @Test
    void sedExpressionsAreNotPathsButARealPathEndingInASlashIs() {
        assertTrue(ReadHandoff.pathsUnder("sed -n '/private static Node parse/,/^    }/p' src/A.java", dir, List.of(dir)));
        assertTrue(ReadHandoff.pathsUnder("sed -n '/^\\/\\*\\*/,/^ \\*\\//p' src/A.java ; grep -n '^    public\\|^public' src/A.java",
                dir, List.of(dir)));
        assertFalse(ReadHandoff.pathsUnder("ls /outside/the/roots/", dir, List.of(dir)));
        assertFalse(ReadHandoff.pathsUnder("cat src/A.java", dir.resolve("..").normalize(), List.of(dir)),
                "a working directory outside the roots");
    }

    @Test
    void aShortOutputAFailedCallOrNoRootsLeaveTheResultAsItIs() {
        List<Action> actions = List.of(new Action("Bash", Map.of("command", "cat src/A.java", "workingDir", dir.toString())));
        Map<String, Object> small = Map.of("Bash", bashResult("short"));
        List<ReadHandoff.Note> notes = new ArrayList<>();
        assertEquals(small, ReadHandoff.apply(actions, small, "t", 8000, List.of(dir), dir, extractor(new ArrayList<>()), null, notes));
        Map<String, Object> big = Map.of("Bash", bashResult("z".repeat(9000)));
        assertEquals(big, ReadHandoff.apply(actions, big, "t", 8000, List.of(dir), dir, prompt -> null, null, notes));
        assertEquals(big, ReadHandoff.apply(actions, big, "t", 8000, List.of(dir), dir, prompt -> {
            throw new IllegalStateException("rate limited");
        }, null, notes));
        assertSame(big, ReadHandoff.apply(actions, big, "t", 8000, List.of(), dir, extractor(new ArrayList<>()), null, notes));
        assertTrue(notes.isEmpty());
    }

    @Test
    void theSecondBashOfAPlanIsMatchedToItsOwnResult() {
        List<Action> actions = List.of(new Action("Bash", Map.of("command", "cat src/Small.java", "workingDir", dir.toString())),
                new Action("TreeEdit", Map.of("op", "addImport", "code", "java.util.List")),
                new Action("Bash", Map.of("command", "cat src/Big.java", "workingDir", dir.toString())));
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("Bash", bashResult("small"));
        results.put("TreeEdit", Map.of("status", "Success"));
        results.put("Bash#2", bashResult("b".repeat(9000)));
        List<ReadHandoff.Note> notes = new ArrayList<>();
        Map<String, Object> out = ReadHandoff.apply(actions, results, "t", 8000, List.of(dir), dir,
                extractor(new ArrayList<>()), null, notes);
        assertEquals("small", ((Map<?, ?>) out.get("Bash")).get("output"));
        assertTrue(String.valueOf(((Map<?, ?>) out.get("Bash#2")).get("output")).startsWith("[EXTRACT"));
        assertEquals("Bash#2", notes.get(0).key());
    }
}
