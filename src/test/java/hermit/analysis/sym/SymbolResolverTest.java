package hermit.analysis.sym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SymbolResolverTest {

    private static List<String> validate(String src) {
        ParseTree t = ParseTree.parse(src);
        return SymbolResolver.resolve(t).validate();
    }

    @Test
    void importsResolveAndWildcardsExpand() {
        String src = """
                package p;
                import java.util.List;
                import java.util.*;
                import static java.util.Objects.requireNonNull;
                class A {
                    List<String> l;
                    Map<String, Integer> m;
                    void go(Object o) { requireNonNull(o); }
                }
                """;
        ParseTree t = ParseTree.parse(src);
        SymbolResolver r = SymbolResolver.resolve(t);
        assertEquals(List.of(), r.validate());
        Set<String> expansion = r.expansionOf(t.imports().get(1));
        assertTrue(expansion.contains("Map"), "wildcard expansion lists the package's real types");
        assertTrue(expansion.contains("ArrayList"));
        assertEquals(src, t.toString(), "the import still prints as *");
    }

    @Test
    void localVisibilityIsPositional() {
        String bad = """
                class A { void m() { use(b); int b = 1; } void use(int x) {} }
                """;
        List<String> problems = validate(bad);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("unknown variable 'b'"), problems.get(0));

        String good = """
                class A { void m() { int b = 1; use(b); } void use(int x) {} }
                """;
        assertEquals(List.of(), validate(good));
    }

    @Test
    void fieldsAreVisibleRegardlessOfOrderAndMethodsAcrossTheType() {
        String src = """
                class A {
                    void before() { count = helper(); }
                    int helper() { return 1; }
                    int count;
                }
                """;
        assertEquals(List.of(), validate(src));
    }

    @Test
    void inheritanceFromSameFileAndFromTheJdk() {
        String src = """
                import java.util.ArrayList;
                class Base { protected int shared; void ping() {} }
                class Kid extends Base { void go() { ping(); int x = shared; } }
                class Coll extends ArrayList<String> { void go() { clear(); int n = size(); } }
                """;
        assertEquals(List.of(), validate(src));
    }

    @Test
    void shadowingResolvesToTheNearestDeclaration() {
        String src = """
                class A {
                    int value;
                    void m(int value) { int x = value; }
                }
                """;
        ParseTree t = ParseTree.parse(src);
        SymbolResolver r = SymbolResolver.resolve(t);
        assertEquals(List.of(), r.validate());
        Node use = t.root().descendants(NodeType.IDENTIFIER_EXPRESSION).stream()
                .filter(n -> "value".equals(n.name()))
                .findFirst().orElseThrow();
        assertNotNull(r.symbolOf(use));
        assertEquals(NodeType.PARAMETER, r.symbolOf(use).declaration().type(),
                "the parameter shadows the field");
    }

    @Test
    void hallucinatedNamesAreFlaggedInClosedContexts() {
        String src = """
                class A {
                    void m() {
                        int x = undefinedVar;
                        Fooo f = null;
                        nope();
                    }
                }
                """;
        List<String> problems = validate(src);
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("unknown variable 'undefinedVar'"));
        assertTrue(problems.get(1).contains("unknown type 'Fooo'"));
        assertTrue(problems.get(2).contains("no visible method 'nope'"));
        // positions are 1-based line:col
        assertTrue(problems.get(0).startsWith("[3:"), problems.get(0));
    }

    @Test
    void openContextsSuppressUnprovableProblems() {
        // Unresolvable supertype → members open: mystery() and hidden may be inherited.
        String open1 = """
                class A extends SomeUnknownBase {
                    void m() { mystery(); int x = hidden; }
                }
                """;
        List<String> p1 = SymbolResolver.resolve(ParseTree.parse(open1)).validate();
        assertTrue(p1.stream().noneMatch(s -> s.contains("mystery") || s.contains("hidden")), p1.toString());

        // Unknown wildcard package → types open: Fooo may live there.
        String open2 = """
                import com.nowhere.unknownpkg.*;
                class A { Fooo f; }
                """;
        assertEquals(List.of(), validate(open2));
    }

    @Test
    void patternBindersFollowPosition() {
        String good = """
                class A {
                    void m(Object o) {
                        if (o instanceof String s) { int n = s.length(); }
                        switch (o) {
                            case Integer i -> use(i);
                            default -> { }
                        }
                    }
                    void use(int x) {}
                }
                """;
        assertEquals(List.of(), validate(good));

        String bad = """
                class A {
                    void m(Object o) {
                        int n = s.length();
                        if (o instanceof String s) { }
                    }
                }
                """;
        List<String> problems = validate(bad);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("unknown variable 's'"));
    }

    @Test
    void lambdaParametersScopeToTheLambda() {
        String good = """
                import java.util.List;
                class A { void m(List<String> l) { l.forEach(x -> sink(x)); } void sink(Object o) {} }
                """;
        assertEquals(List.of(), validate(good));

        String bad = """
                import java.util.List;
                class A { void m(List<String> l) { l.forEach(x -> sink(x)); sink(x); } void sink(Object o) {} }
                """;
        List<String> problems = validate(bad);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("unknown variable 'x'"));
    }

    @Test
    void staticWildcardImportsBringConstantsAndMethods() {
        String src = """
                import static hermit.analysis.lex.TokenType.*;
                class A {
                    Object k() { return IDENTIFIER; }
                    Object m() { return keyword("if"); }
                }
                """;
        assertEquals(List.of(), validate(src));
    }

    @Test
    void enumSyntheticsAndRecordComponents() {
        String src = """
                enum Color {
                    RED, GREEN;
                    static Color first() { return values()[0]; }
                    Color next() { return valueOf("GREEN"); }
                }
                record Point(int x, int y) {
                    Point {
                        if (x < 0 || y < 0) throw new IllegalArgumentException();
                    }
                    int sum() { return x() + y(); }
                    int direct() { return x + y; }
                }
                """;
        assertEquals(List.of(), validate(src));
    }

    @Test
    void tryResourcesAndCatchParams() {
        String src = """
                class A {
                    void m() {
                        try (var r = open()) { r.toString(); }
                        catch (RuntimeException e) { e.printStackTrace(); }
                    }
                    AutoCloseable open() { return null; }
                }
                """;
        assertEquals(List.of(), validate(src));
    }

    @Test
    void sameNamedBindersInSiblingBranchesResolveToTheNearestPreceding() {
        // Two pattern binders named `x` in sibling branches: each use site must bind to ITS branch's
        // binder (nearest preceding declaration), not the first registered one.
        String src = """
                class A {
                    int m(Object o) {
                        if (o instanceof String x) {
                            return x.length();
                        }
                        if (o instanceof Integer x) {
                            return x.intValue();
                        }
                        return 0;
                    }
                }
                """;
        ParseTree t = ParseTree.parse(src);
        SymbolResolver r = SymbolResolver.resolve(t);
        List<String> problems = new ArrayList<>(r.validate());
        problems.addAll(r.typeInference().validate(t.root()));
        assertEquals(List.of(), problems);
    }

    @Test
    void selfReferencingInitializersAreFlagged() {
        // F52a: `var x = x;` / a lambda reading its own variable — javac rejects both
        List<String> p1 = validate("""
                class A {
                    void m() {
                        var intervalStart = intervalStart;
                    }
                }
                """);
        assertEquals(1, p1.size(), p1.toString());
        assertTrue(p1.get(0).contains("'intervalStart' is read in its own initializer"), p1.get(0));

        List<String> p2 = validate("""
                class A {
                    void m() {
                        Runnable r = () -> r.run();
                    }
                }
                """);
        assertTrue(p2.stream().anyMatch(s -> s.contains("'r' is read in its own initializer")), p2.toString());

        // legal shapes: chained assignment inside the initializer, later self-assignment, field self-use
        assertEquals(List.of(), validate("""
                class A {
                    void m() {
                        int x = x = 2;
                        int y = 1;
                        y = y + 1;
                    }
                }
                """));
    }

    @Test
    void duplicateLocalDeclarationsAreFlagged() {
        // F52b: re-declaring a visible local/parameter — javac rejects; fields MAY be shadowed
        List<String> p1 = validate("""
                class A {
                    void m(int count) {
                        int captor = 1;
                        if (count > 0) {
                            int captor = 2;
                            use(captor);
                        }
                        int count = 3;
                        use(count);
                    }
                    void use(int x) { }
                }
                """);
        assertEquals(2, p1.size(), p1.toString());
        assertTrue(p1.get(0).contains("'captor' is already defined"), p1.get(0));
        assertTrue(p1.get(1).contains("'count' is already defined"), p1.get(1));

        // legal: disjoint sibling scopes, field shadowing, fresh method scopes in anonymous classes
        assertEquals(List.of(), validate("""
                class A {
                    int shade;
                    void m() {
                        if (hashCode() > 0) { int x = 1; use(x); } else { int x = 2; use(x); }
                        for (int i = 0; i < 2; i++) { }
                        for (int i = 0; i < 3; i++) { }
                        int shade = 5;
                        use(shade);
                        Runnable r = new Runnable() {
                            public void run() { int shade = 7; use(shade); }
                        };
                        r.run();
                    }
                    void use(int x) { }
                }
                """));
    }

    @Test
    void inheritedNestedTypesAreAccessibleThroughTheSubtype() {
        String src = """
                interface Ops {
                    enum Action { CHARGE, REFUND }
                }
                class HandleOps implements Ops {
                    Object go() { return HandleOps.Action.CHARGE; }
                }
                """;
        ParseTree t = ParseTree.parse(src);
        SymbolResolver r = SymbolResolver.resolve(t);
        List<String> problems = new ArrayList<>(r.validate());
        problems.addAll(r.typeInference().validate(t.root()));
        assertEquals(List.of(), problems,
                "a nested type declared in a supertype is reachable via the subtype's name");
    }

    @Test
    void selfValidatesAllHermitSourcesQuietly() throws IOException {
        Path root = Path.of("src");
        assumeTrue(Files.isDirectory(root), "run from the hermit project dir");
        List<Path> files;
        try (var s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(files.isEmpty());
        StringBuilder all = new StringBuilder();
        for (Path f : files) {
            ParseTree t = ParseTree.parse(Files.readString(f));
            List<String> problems;
            try {
                problems = SymbolResolver.resolve(t).validate();
            } catch (RuntimeException e) {
                throw new AssertionError("resolver crashed on " + f + ": " + e, e);
            }
            for (String p : problems) {
                all.append(f).append(' ').append(p).append('\n');
            }
        }
        assertEquals("", all.toString(), "hermit's own sources must validate with zero problems");
    }

    @Test
    void aReturnWhoseProvableTypeCannotBeAssignedToTheDeclaredReturnTypeIsAProblem() {
        // F281 (drive 2M/317): `return memo.get(fileID);` (a String) in a method returning Optional<FileInfo> passed
        List<String> bad = validate("""
                import java.util.Map;
                import java.util.Optional;
                class H {
                    private Map<Long, String> memo = new java.util.HashMap<>();
                    Optional<String> info(long id) {
                        if (memo.containsKey(id)) {
                            return memo.get(id);
                        }
                        return Optional.of("x");
                    }
                }
                """);
        assertTrue(bad.stream().anyMatch(m -> m.contains("return type mismatch") && m.contains("Optional")), bad.toString());
        List<String> good = validate("""
                import java.util.Optional;
                class H {
                    Optional<String> info(long id) { return Optional.of("x"); }
                    String name() { return null; }
                    Object any() { return "s"; }
                    long count() { return 3; }
                    <T> T generic(T t) { return t; }
                    void run() { return; }
                }
                """);
        assertFalse(good.stream().anyMatch(m -> m.contains("return type mismatch")), good.toString());
    }

    @Test
    void anInstanceMethodCalledFromAStaticContextIsRefused() {
        String src = """
                class Ledger {
                    static int tally;
                    Marker inspect(int amount) { return null; }
                    static Marker produce() { return inspect(1); }
                    static { inspect(2); }
                    Marker ordinary() { return inspect(3); }
                    Runnable deferred() { return () -> inspect(4); }
                    static class Cabinet {
                        void task() { inspect(5); }
                        void local() {}
                        static void launch() { local(); }
                    }
                    class Chamber {
                        void task() { inspect(6); }
                    }
                    static Object anonymous() {
                        return new Object() {
                            void assist() {}
                            void task() { assist(); }
                        };
                    }
                    static class Marker {}
                }
                """;
        List<String> problems = validate(src);
        List<String> refused = problems.stream().filter(p -> p.contains("cannot be called from a static context")).toList();
        assertEquals(4, refused.size(), problems.toString());
        assertTrue(refused.get(0).startsWith("[4:"), refused.toString());   // a static method
        assertTrue(refused.get(1).startsWith("[5:"), refused.toString());   // a static initializer
        assertTrue(refused.get(2).startsWith("[9:"), refused.toString());   // a static nested class
        assertTrue(refused.get(3).startsWith("[11:"), refused.toString());  // a static method of the nested class
        assertTrue(refused.get(0).contains("non-static method 'inspect'"), refused.get(0));
    }

    @Test
    void aStaticOverloadOrAnInheritedMethodIsLeftToTheCompiler() {
        String src = """
                class Ledger extends java.util.ArrayList<String> {
                    void dispatch(int amount) {}
                    static void dispatch(String label) {}
                    static void invoke() { dispatch("ready"); }
                }
                """;
        assertTrue(validate(src).stream().noneMatch(p -> p.contains("static context")), validate(src).toString());
    }

    @Test
    void anUnknownTypeNamesTheTypesInScopeClosestFirst() {
        String src = """
                import java.util.Map;
                class Ledger {
                    static class Parcel {}
                    enum Mode { ACTIVE }
                    void evaluate() {
                        ImaginaryPacket packet = null;
                    }
                }
                """;
        List<String> problems = validate(src);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("unknown type 'ImaginaryPacket' — the types in scope: "), problems.get(0));
        assertTrue(problems.get(0).contains("Parcel") && problems.get(0).contains("Mode")
                && problems.get(0).contains("Map"), problems.get(0));
    }

    @Test
    void aMissingMethodNamesTheMethodsInScopeClosestFirst() {
        String src = """
                class Ledger {
                    void paintCanvas() {
                        paintMissing();
                    }
                    void paintFrame() {}
                    static class Section {
                        void task() { paintGhost(); }
                        void paintBranch() {}
                    }
                }
                """;
        List<String> problems = validate(src);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("no visible method 'paintMissing' — the methods in scope: paintCanvas, "),
                problems.get(0));
        assertTrue(problems.get(1).contains("no visible method 'paintGhost' — the methods in scope: paintBranch, "),
                problems.get(1));
        assertTrue(problems.get(1).contains("paintFrame"), "the enclosing type's methods are in scope too");
    }
}
