package hermit.analysis.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.sym.ClasspathIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeGraphTest {

    @TempDir
    Path dir;

    private CodeGraph graph() throws IOException {
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
                    void unrelated() { }
                }
                """);
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    @Test
    void locateCallersCalleesAndReach() throws IOException {
        CodeGraph g = graph();
        assertEquals(3, g.fileCount());

        assertEquals("C#target", g.locate("target").get(0).spec());
        assertEquals(1, g.locate("B#helper").size());

        List<CodeGraph.CallSite> callers = g.callersOf("C#target");
        assertEquals(1, callers.size());
        assertEquals("B#helper", callers.get(0).caller().spec());
        assertEquals("C", callers.get(0).receiverType());

        List<CodeGraph.CallSite> callees = g.calleesOf(g.locate("A#run").get(0));
        assertEquals(1, callees.size());
        assertEquals("helper", callees.get(0).calleeName());

        CodeGraph.ReachResult reach = g.reach("A#run", "C#target", 10);
        assertTrue(reach.reachable(), reach.path().toString());
        assertEquals(List.of("A#run", "B#helper", "C#target"), reach.path());

        assertFalse(g.reach("C#target", "A#run", 10).reachable(), "no reverse edge");
        assertFalse(g.reach("A#run", "C#unrelated", 10).reachable());
    }

    @Test
    void interfaceCallsResolveToImplementationCandidates() throws IOException {
        Files.writeString(dir.resolve("Service.java"), """
                interface Service {
                    void process();
                }
                """);
        Files.writeString(dir.resolve("Fast.java"), """
                class Fast implements Service {
                    public void process() { }
                }
                """);
        Files.writeString(dir.resolve("Slow.java"), """
                class Slow implements Service {
                    public void process() { deep(); }
                    void deep() { }
                }
                """);
        Files.writeString(dir.resolve("App.java"), """
                class App {
                    void main(Service s) { s.process(); }
                    void process() { }   // same name, UNRELATED type — must not join the candidates
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());

        // Caller -> Callee: the interface call yields the declaration + both implementations
        CodeGraph.CallSite site = g.calleesOf(g.locate("App#main").get(0)).get(0);
        List<CodeGraph.Target> targets = g.targetsOf(site);
        assertTrue(targets.stream().anyMatch(t -> "Service".equals(t.typeName())), targets.toString());
        assertTrue(targets.stream().anyMatch(t -> "Fast".equals(t.typeName())
                        && "impl".equals(t.how())), targets.toString());
        assertTrue(targets.stream().anyMatch(t -> "Slow".equals(t.typeName())), targets.toString());
        assertFalse(targets.stream().anyMatch(t -> "App".equals(t.typeName())),
                "the unrelated same-named method is NOT a candidate: " + targets);
        assertTrue(targets.stream().allMatch(t -> t.source() != null && t.source().decl() != null),
                "source targets carry their declaration Node");

        // Callee -> Callers: resolved reverse edges, again without the name-only impostor
        assertEquals(1, g.callerEdges("Fast#process").size());
        assertEquals("App#main", g.callerEdges("Fast#process").get(0).site().caller().spec());
        assertEquals(List.of(), g.callerEdges("App#process"),
                "nobody calls the unrelated process()");

        // polymorphic reach is qualified, not asserted
        CodeGraph.ReachResult r = g.reach("App#main", "Slow#deep", 5);
        assertTrue(r.reachable(), r.path().toString());
        assertTrue(r.path().stream().anyMatch(p -> p.contains("dispatch candidates")),
                "the interface hop is annotated: " + r.path());
    }

    @Test
    void singleAllocationReceiversResolveMonomorphically() throws IOException {
        Files.writeString(dir.resolve("Service.java"), """
                interface Service {
                    void process();
                }
                """);
        Files.writeString(dir.resolve("Fast.java"), """
                class Fast implements Service {
                    public void process() { }
                }
                """);
        Files.writeString(dir.resolve("Slow.java"), """
                class Slow implements Service {
                    public void process() { }
                }
                """);
        Files.writeString(dir.resolve("Use.java"), """
                class Use {
                    private final Service fixed = new Fast();

                    void viaLocal() {
                        Service s = new Slow();
                        s.process();
                    }

                    void viaField() {
                        fixed.process();
                    }

                    void viaAnon() {
                        Service s = new Service() {
                            public void process() { helper(); }
                        };
                        s.process();
                    }

                    void reassigned(boolean b) {
                        Service s = new Fast();
                        if (b) {
                            s = new Slow();
                        }
                        s.process();
                    }

                    void helper() { }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());

        // a never-reassigned local: exactly ONE candidate, the allocated class
        List<CodeGraph.Target> local = g.targetsOf(g.calleesOf(g.locate("Use#viaLocal").get(0)).stream()
                .filter(c -> c.calleeName().equals("process")).findFirst().orElseThrow());
        assertEquals(1, local.size(), local.toString());
        assertEquals("Slow", local.get(0).typeName());
        assertEquals("alloc", local.get(0).how());

        // a final field: same
        List<CodeGraph.Target> field = g.targetsOf(g.calleesOf(g.locate("Use#viaField").get(0)).get(0));
        assertEquals(1, field.size(), field.toString());
        assertEquals("Fast", field.get(0).typeName());

        // an anonymous class: narrows to ITS override, and reach flows through its body unannotated
        List<CodeGraph.Target> anon = g.targetsOf(g.calleesOf(g.locate("Use#viaAnon").get(0)).stream()
                .filter(c -> c.calleeName().equals("process")).findFirst().orElseThrow());
        assertEquals(1, anon.size(), anon.toString());
        assertTrue(anon.get(0).typeName().contains("$anon"), anon.toString());
        CodeGraph.ReachResult r = g.reach("Use#viaAnon", "Use#helper", 4);
        assertTrue(r.reachable(), r.path().toString());
        assertFalse(r.path().stream().anyMatch(p -> p.contains("dispatch candidates")), r.path().toString());

        // a REASSIGNED local must keep the full cone — narrowing would be unsound
        List<CodeGraph.Target> wide = g.targetsOf(g.calleesOf(g.locate("Use#reassigned").get(0)).stream()
                .filter(c -> c.calleeName().equals("process")).findFirst().orElseThrow());
        assertTrue(wide.stream().anyMatch(t -> "Fast".equals(t.typeName()))
                        && wide.stream().anyMatch(t -> "Slow".equals(t.typeName())),
                "both impls stay candidates: " + wide);
    }

    @Test
    void libraryCallsResolveToReflectMethods() throws IOException {
        Files.writeString(dir.resolve("U.java"), """
                import java.util.ArrayList;

                class U {
                    int use() {
                        ArrayList<String> l = new ArrayList<>();
                        l.add("x");
                        return l.size();
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        List<CodeGraph.CallSite> calls = g.calleesOf(g.locate("U#use").get(0));
        CodeGraph.CallSite add = calls.stream()
                .filter(c -> c.calleeName().equals("add")).findFirst().orElseThrow();
        List<CodeGraph.Target> targets = g.targetsOf(add);
        assertTrue(targets.stream().anyMatch(t -> t.library() != null
                        && "add".equals(t.library().getName())),
                "library callee resolves to a java.lang.reflect.Method: " + targets);
    }

    @Test
    void refreshReindexesAnEditedFile() throws IOException {
        CodeGraph g = graph();
        assertTrue(g.reach("A#run", "C#target", 10).reachable());
        Files.writeString(dir.resolve("A.java"), """
                class A {
                    void run() { }
                }
                """);
        g.refresh(dir.resolve("A.java"));
        assertFalse(g.reach("A#run", "C#target", 10).reachable(), "the edge disappeared with the edit");
        assertTrue(g.reach("B#helper", "C#target", 10).reachable(), "other files untouched");
    }

    @Test
    void reachPrefersResolvedRoutesAndLabelsNameOnlyOnes() throws IOException {
        // A route through name-only targets must not hide a resolved route, and the result says which kind it found
        Files.writeString(dir.resolve("A.java"), """
                class A {
                    void run() { B.helper(); ghost.target(); }
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
        Files.writeString(dir.resolve("D.java"), """
                class D {
                    void go() { ghost.target(); }
                }
                """);
        Files.writeString(dir.resolve("F.java"), """
                class F {
                    void deep() { new G().mid(); }
                }
                class G {
                    void mid() { new C().target(); }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        // the resolved 2-hop route wins over the 1-hop name-only guess
        CodeGraph.ReachResult resolved = g.reach("A#run", "C#target", 10);
        assertTrue(resolved.reachable(), resolved.toString());
        assertEquals(List.of("A#run", "B#helper", "C#target"), resolved.path());
        assertEquals("resolved", resolved.status(), resolved.toString());
        assertEquals(0, resolved.nameOnlyHops());
        // only a name-only route exists → reachable, but labelled low confidence
        CodeGraph.ReachResult guess = g.reach("D#go", "C#target", 10);
        assertTrue(guess.reachable(), guess.toString());
        assertEquals("low-confidence", guess.status(), guess.toString());
        assertEquals(1, guess.nameOnlyHops(), guess.toString());
        assertTrue(guess.path().get(1).contains("name-only"), guess.path().toString());
        // no path at all vs. a depth bound are different answers
        CodeGraph.ReachResult none = g.reach("C#target", "A#run", 10);
        assertFalse(none.reachable());
        assertEquals("no-path", none.status(), none.toString());
        CodeGraph.ReachResult bound = g.reach("F#deep", "C#target", 1);
        assertFalse(bound.reachable());
        assertEquals("depth-bound", bound.status(), bound.toString());
        // at depth 1 the resolved route is out of reach, so the name-only guess is offered — labelled as such
        CodeGraph.ReachResult shortcut = g.reach("A#run", "C#target", 1);
        assertTrue(shortcut.reachable() && "low-confidence".equals(shortcut.status()), shortcut.toString());
        // the two-arg constructor keeps its meaning for existing callers
        assertEquals("resolved", new CodeGraph.ReachResult(true, List.of("x")).status());
        assertEquals("no-path", new CodeGraph.ReachResult(false, List.of()).status());
    }

    @Test
    void staticImportedCallsResolveToTheImportedOwner() throws IOException {
        // An unqualified call to a statically imported method belongs to the imported owner, not to the enclosing
        // class; otherwise the reverse index misses it and `callers` on a utility method is empty
        Files.writeString(dir.resolve("Util.java"), """
                class Util {
                    static void validateTrue(boolean ok) { }
                    static int scale(int v) { return v; }
                }
                """);
        Files.writeString(dir.resolve("Wild.java"), """
                class Wild {
                    static void mustExist(Object o) { }
                }
                """);
        Files.writeString(dir.resolve("Caller.java"), """
                import static Util.validateTrue;
                import static Wild.*;

                class Caller {
                    void handle() {
                        validateTrue(true);
                        mustExist(this);
                        own();
                    }

                    void own() { }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());

        // the single static import is a RESOLVED reverse edge, not a name match
        List<CodeGraph.CallerEdge> validate = g.callerEdges("Util#validateTrue");
        assertEquals(1, validate.size(), validate.toString());
        assertEquals("Caller#handle", validate.get(0).site().caller().spec());
        // and the call site reports the owner as its receiver
        CodeGraph.CallSite site = g.calleesOf(g.locate("Caller#handle").get(0)).stream()
                .filter(c -> c.calleeName().equals("validateTrue")).findFirst().orElseThrow();
        assertEquals("Util", site.receiverType(), site.toString());

        // an on-demand static import resolves too
        assertEquals(1, g.callerEdges("Wild#mustExist").size(), g.callerEdges("Wild#mustExist").toString());

        // control: an unqualified call to the enclosing class's own method still resolves to it
        List<CodeGraph.CallerEdge> own = g.callerEdges("Caller#own");
        assertEquals(1, own.size(), own.toString());
        assertEquals("Caller#handle", own.get(0).site().caller().spec());
    }

    @Test
    void theEnclosingClassWinsOverAStaticImportOfTheSameName() throws IOException {
        // JLS 15.12.1: a method visible in the enclosing hierarchy shadows a static import of the same name
        Files.writeString(dir.resolve("Helper.java"), """
                class Helper {
                    static void check(boolean ok) { }
                }
                """);
        Files.writeString(dir.resolve("Local.java"), """
                import static Helper.check;

                class Local {
                    void run() { check(true); }

                    void check(boolean ok) { }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        assertEquals(1, g.callerEdges("Local#check").size(), "the local method wins");
        assertTrue(g.callerEdges("Helper#check").isEmpty(), "the import is shadowed");
    }

    @Test
    void callThroughAVarAliasOfAComponentFieldResolvesToTheImplementation() throws IOException {
        // A call through a `var` alias of a field whose type is an interface: the receiver of the next call is the
        // return type of the interface's method, so the route to the implementation resolves
        Files.writeString(dir.resolve("Registry.java"), """
                interface Registry {
                    Store store();
                }
                """);
        Files.writeString(dir.resolve("Store.java"), """
                interface Store {
                    void save(String key);
                }
                """);
        Files.writeString(dir.resolve("DiskStore.java"), """
                class DiskStore implements Store {
                    public void save(String key) { Journal.append(key); }
                }
                """);
        Files.writeString(dir.resolve("Journal.java"), """
                class Journal {
                    static void append(String line) { }
                }
                """);
        Files.writeString(dir.resolve("Service.java"), """
                import java.util.Objects;
                class Service {
                    private final Registry registry;
                    Service(Registry registry) { this.registry = registry; }
                    void handle(String key) {
                        var current = registry;
                        current.store().save(key);
                    }
                    void handleChecked(String key) {
                        var current = Objects.requireNonNull(registry, "registry");
                        current.store().save(key);
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        CodeGraph.CallSite save = g.calleesOf(g.locate("Service#handle").get(0)).stream()
                .filter(c -> c.calleeName().equals("save")).findFirst().orElseThrow();
        assertEquals("Store", save.receiverType(), "the receiver of save() is the interface method's return type");
        CodeGraph.ReachResult r = g.reach("Service#handle", "Journal#append", 10);
        assertTrue(r.reachable(), r.toString());
        assertEquals("resolved", r.status(), "a fully resolved route must exist: " + r);
        assertEquals(List.of("Service#handle", "DiskStore#save", "Journal#append"), r.path());
        // through requireNonNull: T is bound by the argument, so the receiver is still Store
        CodeGraph.CallSite checked = g.calleesOf(g.locate("Service#handleChecked").get(0)).stream()
                .filter(c -> c.calleeName().equals("save")).findFirst().orElseThrow();
        assertEquals("Store", checked.receiverType(), "requireNonNull(T, String) returns its argument's type");
        CodeGraph.ReachResult r2 = g.reach("Service#handleChecked", "Journal#append", 10);
        assertEquals("resolved", r2.status(), r2.toString());
    }
}
