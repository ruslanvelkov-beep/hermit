package hermit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphCommandTest {

    @TempDir
    Path dir;

    @BeforeEach
    void fixture() throws IOException {
        Files.writeString(dir.resolve("A.java"), """
                class A {
                    void run() { B.helper(); }
                }
                """);
        Files.writeString(dir.resolve("B.java"), """
                class B {
                    static void helper() { new C().target(); }
                }
                """);
        Files.writeString(dir.resolve("C.java"), """
                class C {
                    void target() { }
                }
                """);
    }

    private record Cli(int exit, String out) {}

    private Cli cli(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] full = new String[args.length + 2];
        full[0] = "--root";
        full[1] = dir.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        int code = GraphCommand.run(full, new PrintStream(out), new PrintStream(err));
        return new Cli(code, out.toString());
    }

    @Test
    void locateReachCallersAndExitCodes() throws Exception {
        Cli locate = cli("locate", "target");
        assertEquals(0, locate.exit());
        assertTrue(locate.out().contains("C#target") && locate.out().contains("C.java:2"), locate.out());

        Cli reach = cli("reach", "A#run", "C#target");
        assertEquals(0, reach.exit());
        assertTrue(reach.out().contains("REACHABLE: A#run -> B#helper -> C#target"), reach.out());

        Cli noReach = cli("reach", "C#target", "A#run");
        assertEquals(1, noReach.exit());
        assertTrue(noReach.out().contains("NOT REACHABLE"), noReach.out());

        Cli callers = cli("callers", "C#target");
        assertEquals(0, callers.exit());
        assertTrue(callers.out().contains("B#helper"), callers.out());

        Cli missing = cli("locate", "nowhere");
        assertEquals(1, missing.exit());
    }

    @Test
    void validateFlagsAHallucinationAndUsageErrorsExitTwo() throws Exception {
        assertEquals(0, cli("validate").exit(), "clean fixture is quiet");

        Files.writeString(dir.resolve("Bad.java"), """
                class Bad {
                    int go() { return missingVar; }
                }
                """);
        Cli bad = cli("validate", "Bad.java");
        assertEquals(1, bad.exit());
        assertTrue(bad.out().contains("unknown variable 'missingVar'"), bad.out());

        assertEquals(2, GraphCommand.run(new String[] {"locate", "x"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())), "no --root is a usage error");
    }

    @Test
    void testsCommandIsAvailableAndEveryCommandIsDocumented() throws Exception {
        // the tests query is a command, and every command is documented in the usage text
        Files.createDirectories(dir.resolve("src/test/java"));
        Files.writeString(dir.resolve("src/test/java/CTest.java"), """
                class CTest {
                    @Test
                    void coversTarget() { new C().target(); }
                }
                """);
        Cli t = cli("tests", "C#target");
        assertEquals(0, t.exit(), t.out());
        assertTrue(t.out().contains("CTest#coversTarget"), t.out());
        assertTrue(t.out().contains("score="), "candidates are ranked: " + t.out());

        Cli none = cli("tests", "C#unreached");
        assertEquals(1, none.exit(), none.out());

        assertEquals(10, GraphCommand.COMMANDS.size(), "the commands: " + GraphCommand.COMMANDS);
        for (String c : GraphCommand.COMMANDS) {
            assertTrue(GraphCommand.USAGE.contains("  " + c + " "), "command '" + c + "' is missing from USAGE");
        }
        assertEquals(2, cli("nowhere-command").exit(), "an unknown command is a usage error");
    }
}
