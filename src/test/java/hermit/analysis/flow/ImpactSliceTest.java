package hermit.analysis.flow;

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

class ImpactSliceTest {

    @TempDir
    Path dir;

    private CodeGraph graph() throws IOException {
        Files.writeString(dir.resolve("Calc.java"), """
                class Calc {
                    int base;

                    int seedValue() {
                        int start = 10;
                        int doubled = start * 2;
                        if (doubled > 5) {
                            log(doubled);
                        }
                        return doubled;
                    }

                    void log(int shown) {
                        int msg = shown + 1;
                        use(msg);
                    }

                    void use(int x) { }

                    void caller() {
                        int got = seedValue();
                        use(got);
                    }
                }
                """);
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    private int line(String needle) throws IOException {
        List<String> lines = Files.readAllLines(dir.resolve("Calc.java"));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        throw new AssertionError(needle);
    }

    @Test
    void sliceFollowsAssignArgsControlReturnsAndCallers() throws IOException {
        CodeGraph g = graph();
        List<String> impact = ImpactSlice.of(g, "Calc.java", line("int start = 10;"), 8, 80);
        String all = String.join("\n", impact);

        assertTrue(all.contains("[assign] 'doubled'"), all);
        assertTrue(all.contains("[control] if condition"), all);
        assertTrue(all.contains("[param] 'shown' of Calc#log"), all);
        assertTrue(all.contains("[assign] 'msg'"), all);
        assertTrue(all.contains("[param] 'x' of Calc#use"), all);
        assertTrue(all.contains("[return-to-caller] Calc#caller"), all);
        assertTrue(all.contains("[assign] 'got'"), all);
    }

    @Test
    void boundsAreExplicit() throws IOException {
        CodeGraph g = graph();
        List<String> impact = ImpactSlice.of(g, "Calc.java", line("int start = 10;"), 8, 4);
        assertTrue(impact.get(impact.size() - 1).contains("truncated"), impact.toString());
        List<String> missing = ImpactSlice.of(g, "Calc.java", 9999, 8, 10);
        assertTrue(missing.get(0).contains("no statement"), missing.toString());
    }

    // ---- fields: readers outside the declaring file, qualified accesses, overwrites --------------------------

    private CodeGraph fieldGraph() throws IOException {
        Files.writeString(dir.resolve("Base.java"), """
                class Base {
                    protected int limit;
                    private int secret;

                    void seed(int v) {
                        limit = v;
                    }

                    int ownRead() {
                        return limit * 2;
                    }

                    void reset() {
                        limit = 0;
                    }

                    void bump() {
                        limit += 1;
                    }

                    void seedQualified() {
                        int w = 7;
                        this.secret = w;
                    }

                    int peekSecret() {
                        return this.secret + 1;
                    }
                }
                """);
        Files.writeString(dir.resolve("Sub.java"), """
                class Sub extends Base {
                    int subRead() {
                        return limit + 1;
                    }
                }
                """);
        Files.writeString(dir.resolve("Peer.java"), """
                class Peer {
                    int peek(Base b) {
                        return b.limit;
                    }
                }
                """);
        Files.writeString(dir.resolve("Other.java"), """
                class Other {
                    int limit;

                    int unrelated() {
                        return limit;
                    }

                    int unrelatedQualified(Other o) {
                        return o.limit;
                    }
                }
                """);
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    private int line(String file, String needle) throws IOException {
        List<String> lines = Files.readAllLines(dir.resolve(file));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        throw new AssertionError(file + ": " + needle);
    }

    private static boolean usesAt(List<String> slice, String file, int line) {
        return slice.stream().anyMatch(l -> l.contains("[use]") && l.endsWith("  " + file + ":" + line));
    }

    @Test
    void inheritedFieldReadInAnotherFileIsAUse() throws IOException {
        // the design's v1 limit: field uses were searched in the declaring file only, so a subclass reading an
        // inherited protected field (a batch operation reading the expectedStatus field of its base operation)
        // was silently missing
        CodeGraph g = fieldGraph();
        List<String> slice = ImpactSlice.of(g, "Base.java", line("Base.java", "limit = v;"), 6, 200);
        assertTrue(usesAt(slice, "Base.java", line("Base.java", "return limit * 2;")), slice.toString());
        assertTrue(usesAt(slice, "Sub.java", line("Sub.java", "return limit + 1;")), slice.toString());
    }

    @Test
    void qualifiedReadOfTheFieldIsAUse() throws IOException {
        CodeGraph g = fieldGraph();
        List<String> slice = ImpactSlice.of(g, "Base.java", line("Base.java", "limit = v;"), 6, 200);
        assertTrue(usesAt(slice, "Peer.java", line("Peer.java", "return b.limit;")), slice.toString());
    }

    @Test
    void aSameNamedFieldOfAnotherTypeIsNotAUse() throws IOException {
        CodeGraph g = fieldGraph();
        List<String> slice = ImpactSlice.of(g, "Base.java", line("Base.java", "limit = v;"), 6, 200);
        assertTrue(slice.stream().noneMatch(l -> l.contains("Other.java")), slice.toString());
    }

    @Test
    void aPlainOverwriteIsNotAUseButACompoundAssignmentIs() throws IOException {
        CodeGraph g = fieldGraph();
        List<String> slice = ImpactSlice.of(g, "Base.java", line("Base.java", "limit = v;"), 6, 200);
        assertFalse(usesAt(slice, "Base.java", line("Base.java", "limit = v;")), slice.toString());
        assertFalse(usesAt(slice, "Base.java", line("Base.java", "limit = 0;")), slice.toString());
        assertTrue(usesAt(slice, "Base.java", line("Base.java", "limit += 1;")), slice.toString());
    }

    @Test
    void aQualifiedFieldWriteContinuesTheSlice() throws IOException {
        CodeGraph g = fieldGraph();
        List<String> slice = ImpactSlice.of(g, "Base.java", line("Base.java", "int w = 7;"), 6, 200);
        String all = String.join("\n", slice);
        assertTrue(all.contains("[assign] 'secret'"), all);
        assertFalse(all.contains("[field-write]"), all);
        assertTrue(usesAt(slice, "Base.java", line("Base.java", "return this.secret + 1;")), all);
    }

    @Test
    void aChainedAssignmentFeedsEveryTarget() throws IOException {
        // `a = b = v`: the inner assignment's value is v, so v reaches a as well as b
        Files.writeString(dir.resolve("Chain.java"), """
                class Chain {
                    int f;

                    void run() {
                        int v = 3;
                        int a;
                        int b;
                        a = b = v;
                        this.f = b = v;
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        List<String> slice = ImpactSlice.of(g, "Chain.java", 5, 6, 200);
        String all = String.join("\n", slice);
        assertTrue(all.contains("[assign] 'b'"), all);
        assertTrue(all.contains("[assign] 'a'"), all);
        assertTrue(all.contains("[assign] 'f'"), all);
    }

    @Test
    void nameOnlyCallTargetsAreNotFollowed() throws IOException {
        // F117 (drive 2K): impact from `logger.error(...)` fanned out to every `error` method in the repo
        Files.writeString(dir.resolve("Svc.java"), """
                class Svc {
                    Logger logger;   // unresolvable type (no Logger on the index) → name-only targets
                    void work() {
                        int v = 41;
                        logger.error(v);
                    }
                }
                """);
        Files.writeString(dir.resolve("Other.java"), """
                class Other {
                    void error(Object x) { sink(x); }
                    void sink(Object x) { }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        List<String> slice = ImpactSlice.of(g, "Svc.java", 5, 6, 200);
        assertTrue(slice.stream().noneMatch(l -> l.contains("Other#error")), slice.toString());
    }
}
