package hermit.analysis.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.sym.ClasspathIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExceptionFlowTest {

    @TempDir
    Path dir;

    private CodeGraph graph(String... sources) throws IOException {
        for (int i = 0; i < sources.length; i++) {
            String name = sources[i].replaceAll("(?s).*?(class|interface) (\\w+).*", "$2");
            Files.writeString(dir.resolve(name + ".java"), sources[i]);
        }
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    private int lineContaining(String file, String needle) throws IOException {
        List<String> lines = Files.readAllLines(dir.resolve(file));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        throw new AssertionError("'" + needle + "' not found in " + file);
    }

    @Test
    void throwsPropagateThroughCallersUntilCaught() throws IOException {
        CodeGraph g = graph("""
                class A {
                    void deep() {
                        throw new IllegalStateException("boom");
                    }

                    void middle() {
                        deep();
                    }

                    void top() {
                        try {
                            middle();
                        } catch (IllegalStateException e) {
                            recover();
                        }
                    }

                    void recover() { }
                }
                """);
        ExceptionFlow flow = ExceptionFlow.of(g);

        List<ExceptionFlow.Escape> deep = flow.escapesOf("A#deep");
        assertEquals(1, deep.size(), deep.toString());
        assertEquals("IllegalStateException", deep.get(0).typeName());
        assertEquals("thrown", deep.get(0).tier());
        assertEquals("unchecked", flow.checkedness(deep.get(0).type()));

        List<ExceptionFlow.Escape> middle = flow.escapesOf("A#middle");
        assertEquals(1, middle.size(), middle.toString());
        assertEquals("propagated", middle.get(0).tier());
        assertTrue(middle.get(0).via().contains("A#deep"), middle.get(0).via());

        // top absorbs it - nothing escapes, and the catch reports the arrival
        assertEquals(List.of(), flow.escapesOf("A#top"));
        int catchLine = lineContaining("A.java", "catch (IllegalStateException");
        List<ExceptionFlow.CatchReport> catches = flow.catchesAt("A.java", catchLine);
        assertEquals(1, catches.size(), catches.toString());
        assertEquals(List.of("IllegalStateException"), catches.get(0).declared());
        assertTrue(catches.get(0).receives().stream()
                        .anyMatch(c -> c.typeName().equals("IllegalStateException")),
                catches.get(0).toString());
    }

    @Test
    void rethrowMultiCatchAndPartialAbsorption() throws IOException {
        CodeGraph g = graph("""
                class B {
                    void source() throws Exception {
                        throw new Exception("wide");
                    }

                    void narrowCatch() throws Exception {
                        try {
                            source();
                        } catch (RuntimeException e) {
                            log(e);
                        }
                    }

                    void rethrower() {
                        try {
                            risky();
                        } catch (IllegalArgumentException | IllegalStateException e) {
                            throw e;
                        }
                    }

                    void risky() {
                        throw new IllegalArgumentException("x");
                    }

                    void log(Object o) { }
                }
                """);
        ExceptionFlow flow = ExceptionFlow.of(g);

        // Exception escapes past catch(RuntimeException) - partial absorption keeps it escaping
        List<ExceptionFlow.Escape> narrow = flow.escapesOf("B#narrowCatch");
        assertTrue(narrow.stream().anyMatch(e -> e.typeName().equals("Exception")), narrow.toString());

        // `throw e` of a multi-catch parameter rethrows BOTH declared types
        List<ExceptionFlow.Escape> re = flow.escapesOf("B#rethrower");
        assertTrue(re.stream().anyMatch(e -> e.typeName().equals("IllegalArgumentException")
                        && e.tier().equals("rethrown")), re.toString());
        assertTrue(re.stream().anyMatch(e -> e.typeName().equals("IllegalStateException")), re.toString());
    }

    @Test
    void libraryDeclaredExceptionsAndInterfaceUnions() throws IOException {
        CodeGraph g = graph("""
                interface Work {
                    void run();
                }
                """, """
                class Loud implements Work {
                    public void run() {
                        throw new IllegalStateException("loud");
                    }
                }
                """, """
                class Quiet implements Work {
                    public void run() { }
                }
                """, """
                class Driver {
                    void drive(Work w) {
                        w.run();
                    }

                    void io() throws java.io.IOException {
                        java.nio.file.Files.readString(java.nio.file.Path.of("x"));
                    }
                }
                """);
        ExceptionFlow flow = ExceptionFlow.of(g);

        // the interface call unions the candidates' escapes: Loud's ISE arrives at drive()
        List<ExceptionFlow.Escape> drive = flow.escapesOf("Driver#drive");
        assertTrue(drive.stream().anyMatch(e -> e.typeName().equals("IllegalStateException")),
                drive.toString());

        // a library callee contributes its DECLARED checked exceptions
        List<ExceptionFlow.Escape> io = flow.escapesOf("Driver#io");
        assertTrue(io.stream().anyMatch(e -> e.typeName().equals("IOException")
                        && e.tier().equals("library-declared")), io.toString());
        assertEquals("checked", flow.checkedness(
                io.stream().filter(e -> e.typeName().equals("IOException")).findFirst()
                        .orElseThrow().type()));
    }

    @Test
    void deadCatchAndRecursionTerminate() throws IOException {
        CodeGraph g = graph("""
                class C {
                    void loop(int n) {
                        if (n > 0) {
                            loop(n - 1);
                        }
                        throw new IllegalStateException("always");
                    }

                    void dead() {
                        try {
                            harmless();
                        } catch (IllegalArgumentException e) {
                            cleanup();
                        }
                    }

                    void harmless() { }

                    void cleanup() { }
                }
                """);
        ExceptionFlow flow = ExceptionFlow.of(g);

        // self-recursion reaches a fixed point and still reports the throw
        List<ExceptionFlow.Escape> loop = flow.escapesOf("C#loop");
        assertEquals(1, loop.size(), loop.toString());

        // the catch around harmless() receives nothing - dead
        List<ExceptionFlow.CatchReport> dead = flow.catchesAt("C.java",
                lineContaining("C.java", "catch (IllegalArgumentException"));
        assertEquals(1, dead.size(), dead.toString());
        assertTrue(dead.get(0).receives().isEmpty(), dead.get(0).toString());
        assertFalse(flow.escapesOf("C#dead").stream().anyMatch(e -> !e.tier().equals("declared")),
                "nothing escapes dead()");
    }
}
