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

class EntryTypePropagationTest {

    @TempDir
    Path dir;

    private CodeGraph graph() throws IOException {
        Files.writeString(dir.resolve("Service.java"), """
                interface Service {
                    void process();
                }
                """);
        Files.writeString(dir.resolve("Fast.java"), """
                class Fast implements Service {
                    public void process() { fastOnly(); }
                    void fastOnly() { }
                }
                """);
        Files.writeString(dir.resolve("Slow.java"), """
                class Slow implements Service {
                    public void process() { slowOnly(); }
                    void slowOnly() { }
                }
                """);
        Files.writeString(dir.resolve("Wiring.java"), """
                class Wiring {
                    Service make() {
                        return new Fast();
                    }

                    void entryDirect() {
                        Service s = new Fast();
                        drive(s);
                    }

                    void entryFactory() {
                        Service s = make();
                        drive(s);
                    }

                    void entryBoth(boolean b) {
                        Service s = b ? new Fast() : new Slow();
                        drive(s);
                    }

                    void entryInjected(Service s) {
                        drive(s);
                    }

                    void drive(Service s) {
                        s.process();
                    }
                }
                """);
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    @Test
    void entryWiringNarrowsInterfaceDispatch() throws IOException {
        CodeGraph g = graph();

        // entry-free CHA: reach says "one of 2 dispatch candidates"
        CodeGraph.ReachResult cha = g.reach("Wiring#entryDirect", "Fast#fastOnly", 6);
        assertTrue(cha.reachable());
        assertTrue(cha.path().stream().anyMatch(p -> p.contains("dispatch candidates")), cha.path().toString());

        // flow from THIS entry: the Fast allocation flows through drive() - monomorphic, confirmed
        EntryTypePropagation direct = EntryTypePropagation.analyze(g, "Wiring#entryDirect");
        CodeGraph.ReachResult flow = direct.reach("Wiring#entryDirect", "Fast#fastOnly", 6);
        assertTrue(flow.reachable(), flow.path().toString());
        assertTrue(flow.path().stream().anyMatch(p -> p.contains("[flow-confirmed]")), flow.path().toString());
        assertFalse(direct.reach("Wiring#entryDirect", "Slow#slowOnly", 6).reachable(),
                "Slow is NOT wired by this entry");

        // return-flow through a factory narrows the same way
        EntryTypePropagation factory = EntryTypePropagation.analyze(g, "Wiring#entryFactory");
        assertTrue(factory.reach("Wiring#entryFactory", "Fast#fastOnly", 6).reachable());
        assertFalse(factory.reach("Wiring#entryFactory", "Slow#slowOnly", 6).reachable());

        // both impls wired → both reachable, hop says "flow: one of 2"
        EntryTypePropagation both = EntryTypePropagation.analyze(g, "Wiring#entryBoth");
        CodeGraph.ReachResult r2 = both.reach("Wiring#entryBoth", "Slow#slowOnly", 6);
        assertTrue(r2.reachable(), r2.path().toString());
        assertTrue(r2.path().stream().anyMatch(p -> p.contains("flow: one of 2")), r2.path().toString());

        // no allocation visible (entry parameter) → honest cone fallback, tagged
        EntryTypePropagation injected = EntryTypePropagation.analyze(g, "Wiring#entryInjected");
        CodeGraph.ReachResult r3 = injected.reach("Wiring#entryInjected", "Fast#fastOnly", 6);
        assertTrue(r3.reachable(), r3.path().toString());
        assertTrue(r3.path().stream().anyMatch(p -> p.contains("injected — cone of 2")), r3.path().toString());
    }

    @Test
    void mockedFieldsAreABoundaryNotACone() throws IOException {
        Files.writeString(dir.resolve("Iface.java"), """
                interface Iface {
                    void go();
                }
                """);
        Files.writeString(dir.resolve("Impl.java"), """
                class Impl implements Iface {
                    public void go() { deep(); }
                    void deep() { }
                }
                """);
        Files.writeString(dir.resolve("MockTest.java"), """
                class MockTest {
                    void test() {
                        Iface m = mock(Iface.class);
                        m.go();
                    }

                    static Iface mock(Object c) {
                        return null;
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        EntryTypePropagation flow = EntryTypePropagation.analyze(g, "MockTest#test");
        assertFalse(flow.reach("MockTest#test", "Impl#deep", 6).reachable(),
                "a mocked receiver dispatches to no source impl");
    }

    @Test
    void analysisStopsAtItsBudgetAndSaysSo() throws IOException {
        // Bug report Defect 4: flowreach on an 11k-file index ran unbounded and took the server down
        Files.writeString(dir.resolve("Chain.java"), """
                class Chain {
                    void a() { b(); }
                    void b() { c(); }
                    void c() { d(); }
                    void d() { }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        EntryTypePropagation full = EntryTypePropagation.analyze(g, "Chain#a");
        assertFalse(full.truncated(), "a 4-method cone fits any sane budget");
        assertTrue(full.reach("Chain#a", "Chain#d", 10).reachable());
        EntryTypePropagation tiny = EntryTypePropagation.analyze(g, "Chain#a", 2, Long.MAX_VALUE);
        assertTrue(tiny.truncated(), "a 2-method budget cannot hold the 4-method cone");
        CodeGraph.ReachResult r = tiny.reach("Chain#a", "Chain#d", 10);
        assertFalse(r.reachable());
        assertEquals("budget", r.status(), "a truncated analysis must not claim 'no path': " + r);
    }
}
