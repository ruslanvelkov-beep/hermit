package hermit.analysis.graph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.sym.ClasspathIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphQueriesTest {

    @TempDir
    Path dir;

    private CodeGraph graph() throws IOException {
        Files.writeString(dir.resolve("Port.java"), """
                interface Port {
                    void send();
                }
                """);
        Files.writeString(dir.resolve("Adapter.java"), """
                interface Adapter {
                    void deliver();
                }
                class RealAdapter implements Adapter {
                    public void deliver() { }
                }
                """);
        Files.writeString(dir.resolve("A.java"), """
                class A {
                    void run() { B.helper(); }
                }
                class B {
                    static void helper() { new C().target(); }
                }
                class C {
                    void target() { }
                }
                """);
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    @Test
    void implsFlagsAnInterfaceMethodWithoutAnyIndexedImplementation() throws IOException {
        // an interface method whose cone holds no implementation is flagged as suspicious, not answered with the
        // declaration alone: a lost supertype edge produces exactly that shape
        CodeGraph g = graph();
        String orphan = GraphQueries.impls(g, "Port#send").text();
        assertTrue(orphan.contains("[declaration]"), orphan);
        assertTrue(orphan.contains("SUSPICIOUS"), orphan);
        String implemented = GraphQueries.impls(g, "Adapter#deliver").text();
        assertTrue(implemented.contains("RealAdapter#deliver"), implemented);
        assertFalse(implemented.contains("SUSPICIOUS"), implemented);
    }

    @Test
    void reachMessagesDistinguishNoPathFromADepthBound() throws IOException {
        CodeGraph g = graph();
        String none = GraphQueries.reach(g, "C#target", "A#run", 12).text();
        assertTrue(none.contains("no path exists"), none);
        String bound = GraphQueries.reach(g, "A#run", "C#target", 1).text();
        assertTrue(bound.contains("depth 1") && (bound.contains("frontier still open") || bound.contains("raise maxDepth")), bound);
        String ok = GraphQueries.reach(g, "A#run", "C#target", 12).text();
        assertTrue(ok.startsWith("REACHABLE: A#run -> B#helper -> C#target"), ok);
    }
}
