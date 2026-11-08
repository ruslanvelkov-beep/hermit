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
import java.util.List;
import org.junit.jupiter.api.Test;

class TypeInferenceTest {

    private record Setup(ParseTree tree, SymbolResolver resolver, TypeInference types, List<String> problems) {}

    private static Setup run(String src) {
        ParseTree tree = ParseTree.parse(src);
        SymbolResolver r = SymbolResolver.resolve(tree);
        TypeInference ti = r.typeInference();
        List<String> problems = ti.validate(tree.root());
        return new Setup(tree, r, ti, problems);
    }

    /** The inferredClass of the FIRST node of the given type whose principal text matches. */
    private static Class<?> inferred(Setup s, NodeType nt, String principal) {
        return node(s, nt, principal).inferredClass();
    }

    private static Node node(Setup s, NodeType nt, String principal) {
        return s.tree().root().descendants(nt).stream()
                .filter(n -> principal.equals(n.name()))
                .findFirst().orElseThrow(() -> new AssertionError("no " + nt + " '" + principal + "'"));
    }

    @Test
    void literalsAndVarInference() {
        Setup s = run("""
                class A {
                    void m() {
                        var s = "hello";
                        var n = 42;
                        var l = 42L;
                        var d = 3.14;
                        var f = 3.14f;
                        var b = true;
                        var c = 'x';
                    }
                }
                """);
        assertEquals(List.of(), s.problems());
        assertEquals(String.class, inferred(s, NodeType.VAR_DECLARATOR, "s"));
        assertEquals(int.class, inferred(s, NodeType.VAR_DECLARATOR, "n"));
        assertEquals(long.class, inferred(s, NodeType.VAR_DECLARATOR, "l"));
        assertEquals(double.class, inferred(s, NodeType.VAR_DECLARATOR, "d"));
        assertEquals(float.class, inferred(s, NodeType.VAR_DECLARATOR, "f"));
        assertEquals(boolean.class, inferred(s, NodeType.VAR_DECLARATOR, "b"));
        assertEquals(char.class, inferred(s, NodeType.VAR_DECLARATOR, "c"));
    }

    @Test
    void invocationChainsCarryTypes() {
        Setup s = run("""
                class A {
                    int m(String input) {
                        return input.substring(1).trim().length();
                    }
                }
                """);
        assertEquals(List.of(), s.problems());
        assertEquals(String.class, inferred(s, NodeType.METHOD_CALL, "substring"));
        assertEquals(String.class, inferred(s, NodeType.METHOD_CALL, "trim"));
        assertEquals(int.class, inferred(s, NodeType.METHOD_CALL, "length"));
    }

    @Test
    void genericSubstitutionThroughDeclaredTypeArguments() {
        Setup s = run("""
                import java.util.List;
                import java.util.Map;
                class A {
                    void m(List<String> list, Map<String, Integer> map) {
                        int a = list.get(0).length();
                        int b = map.get("k").intValue();
                        var head = list.get(0);
                    }
                }
                """);
        assertEquals(List.of(), s.problems());
        assertEquals(String.class, inferred(s, NodeType.METHOD_CALL, "get"));
        assertEquals(int.class, inferred(s, NodeType.METHOD_CALL, "length"));
        assertEquals(int.class, inferred(s, NodeType.METHOD_CALL, "intValue"));
        assertEquals(String.class, inferred(s, NodeType.VAR_DECLARATOR, "head"));
    }

    @Test
    void varChainedIntoValidation() {
        Setup s = run("""
                import java.util.ArrayList;
                class A {
                    void m() {
                        var list = new ArrayList<String>();
                        list.get(0).nosuchmethod();
                    }
                }
                """);
        assertEquals(1, s.problems().size(), s.problems().toString());
        assertTrue(s.problems().get(0).contains("String has no method 'nosuchmethod'"), s.problems().get(0));
    }

    @Test
    void missingMembersAndBadAritiesAreFlagged() {
        Setup s = run("""
                class A {
                    void m(String s) {
                        s.nosuch();
                        s.substring(1, 2, 3);
                        int n = s.length;
                        Math.maxx(1, 2);
                        Math.max(1);
                    }
                }
                """);
        List<String> p = s.problems();
        assertEquals(5, p.size(), p.toString());
        assertTrue(p.get(0).contains("String has no method 'nosuch'"));
        assertTrue(p.get(1).contains("no overload of 'substring' on String takes 3 arguments"));
        assertTrue(p.get(2).contains("String has no field 'length'"));
        assertTrue(p.get(3).contains("Math has no method 'maxx'"));
        assertTrue(p.get(4).contains("no overload of 'max' on Math takes 1 argument"));
    }

    @Test
    void unknownReceiversStayQuiet() {
        Setup s = run("""
                class A extends SomethingUnresolvable {
                    void m() {
                        mystery().chainAnything().deeply(1, 2, 3);
                        unknownField.whatever();
                    }
                }
                """);
        assertEquals(List.of(), s.problems());
    }

    @Test
    void unqualifiedCallArity() {
        Setup s = run("""
                class A {
                    void use(int x) {}
                    void two(int x, int y) {}
                    void varargs(String fmt, Object... rest) {}
                    void m() {
                        use(1);
                        two(1, 2);
                        varargs("a");
                        varargs("a", 1, 2, 3);
                        use();
                        two(1, 2, 3);
                    }
                }
                """);
        List<String> p = s.problems();
        assertEquals(2, p.size(), p.toString());
        assertTrue(p.get(0).contains("no overload of 'use' takes 0 arguments"), p.get(0));
        assertTrue(p.get(1).contains("no overload of 'two' takes 3 arguments"), p.get(1));
    }

    @Test
    void sourceTypeChains() {
        Setup s = run("""
                class B {
                    void go() {}
                    String label() { return "b"; }
                }
                class A {
                    B make() { return new B(); }
                    void m() {
                        make().go();
                        int n = make().label().length();
                        make().missing();
                    }
                }
                """);
        assertEquals(1, s.problems().size(), s.problems().toString());
        assertTrue(s.problems().get(0).contains("B has no method 'missing'"));
        assertEquals(int.class, inferred(s, NodeType.METHOD_CALL, "length"));
    }

    @Test
    void constructorArity() {
        Setup s = run("""
                class C {
                    C(int x) {}
                }
                record Point(int x, int y) {}
                class A {
                    void m() {
                        new C(1);
                        new C();
                        new Point(1, 2);
                        new Point(1);
                        new StringBuilder("seed");
                        new String(1, 2, 3, 4, 5);
                    }
                }
                """);
        List<String> p = s.problems();
        assertEquals(3, p.size(), p.toString());
        assertTrue(p.get(0).contains("no constructor of C takes 0 arguments"));
        assertTrue(p.get(1).contains("no constructor of Point takes 1 argument"));
        assertTrue(p.get(2).contains("no constructor of String takes 5 arguments"));
    }

    @Test
    void staticChainsFieldsAndClassLiterals() {
        Setup s = run("""
                class A {
                    void m() {
                        System.out.println("x");
                        String n = Integer.valueOf(3).toString();
                        var k = String.class.getName();
                        int len = new int[3].length;
                        String first = new String[] {"a"}[0];
                    }
                }
                """);
        assertEquals(List.of(), s.problems());
        assertEquals(String.class, inferred(s, NodeType.VAR_DECLARATOR, "k"));
        assertEquals(int.class, inferred(s, NodeType.FIELD_ACCESS, "length"));
    }

    @Test
    void promotionAndOperators() {
        Setup s = run("""
                class A {
                    void m(int i, long l, double d, String s) {
                        var a = i + i;
                        var b = i + l;
                        var c = l + d;
                        var e = s + i;
                        var f = i < l;
                        var g = !f;
                        var h = i << 2;
                        var j = (byte) 1 + (byte) 2;
                    }
                }
                """);
        assertEquals(List.of(), s.problems());
        assertEquals(int.class, inferred(s, NodeType.VAR_DECLARATOR, "a"));
        assertEquals(long.class, inferred(s, NodeType.VAR_DECLARATOR, "b"));
        assertEquals(double.class, inferred(s, NodeType.VAR_DECLARATOR, "c"));
        assertEquals(String.class, inferred(s, NodeType.VAR_DECLARATOR, "e"));
        assertEquals(boolean.class, inferred(s, NodeType.VAR_DECLARATOR, "f"));
        assertEquals(boolean.class, inferred(s, NodeType.VAR_DECLARATOR, "g"));
        assertEquals(int.class, inferred(s, NodeType.VAR_DECLARATOR, "h"));
        assertEquals(int.class, inferred(s, NodeType.VAR_DECLARATOR, "j"));
    }

    @Test
    void thisFieldsAndPatternsAndCasts() {
        Setup s = run("""
                import java.util.List;
                class A {
                    List<String> items;
                    void m(Object o) {
                        int n = this.items.get(0).length();
                        if (o instanceof String str) {
                            int k = str.length();
                        }
                        int c = ((String) o).length();
                        for (String each : items) {
                            each.trim();
                        }
                    }
                }
                """);
        assertEquals(List.of(), s.problems());
        assertEquals(int.class, inferred(s, NodeType.METHOD_CALL, "length"));
    }

    @Test
    void enumsAndInterfaceObjectMethods() {
        Setup s = run("""
                import java.util.List;
                enum Color {
                    RED, GREEN;
                    Color other() { return values()[0]; }
                }
                class A {
                    void m(List<String> l, Color c) {
                        var cls = l.getClass();
                        String s = c.name();
                        int i = c.ordinal();
                        Color[] all = Color.values();
                    }
                }
                """);
        assertEquals(List.of(), s.problems());
        assertNotNull(inferred(s, NodeType.METHOD_CALL, "getClass"));
    }

    @Test
    void deepValidatesAllHermitSourcesQuietly() throws IOException {
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
            SymbolResolver r = SymbolResolver.resolve(t);
            List<String> problems;
            try {
                problems = r.typeInference().validate(t.root());
            } catch (RuntimeException e) {
                throw new AssertionError("type inference crashed on " + f + ": " + e, e);
            }
            for (String p : problems) {
                all.append(f).append(' ').append(p).append('\n');
            }
        }
        assertEquals("", all.toString(), "hermit's own sources must deep-validate with zero problems");
    }

    @Test
    void operatorsWhoseOperandsCannotTakeThemAreProblems() {
        Setup s = run("""
                enum Tint { LIGHT, DARK }
                class Pebble {
                    Tint shade() { return Tint.LIGHT; }
                }
                class Scenario {
                    Pebble pebble() { return new Pebble(); }
                    void verify(String label, int count, boolean active, Object payload) {
                        boolean first = !pebble().shade() == Tint.DARK;
                        boolean second = label && active;
                        boolean third = count == true;
                        if (label) { }
                        while (count) { }
                        int fourth = label ? 1 : 2;
                    }
                }
                """);
        List<String> p = s.problems();
        assertTrue(p.stream().anyMatch(x -> x.contains("operator '!' needs a boolean") && x.contains("Tint")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("operator '&&' needs booleans") && x.contains("String")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("operator '==' cannot compare int with boolean")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("the condition must be a boolean") && x.contains("'label'")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("the condition must be a boolean") && x.contains("'count'")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("the condition of '?:' must be a boolean")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("operator '==' cannot compare boolean with Tint")),
                "the negation operand and the resulting comparison both need diagnostics: " + p);
        assertEquals(7, p.size(), p.toString());
    }

    @Test
    void aMissingMemberThatJoinsTheReceiverIntoATypeNameNamesThatType() {
        Setup s = run("""
                enum VesselState { ACTIVE }
                class Vessel {
                    VesselState state;
                }
                class Client {
                    Object retrieve() { return Vessel.State.ACTIVE; }
                }
                """);
        assertTrue(s.problems().stream().anyMatch(x -> x.contains("Vessel has no field 'State' — did you mean the type 'VesselState'?")),
                s.problems().toString());
    }

    @Test
    void aPrivateMemberOfAnotherTopLevelClassIsAProblem() {
        Setup s = run("""
                enum Hue { RED }
                class Vault {
                    private Hue shade;
                    private int reveal() { return 1; }
                    Hue shade() { return shade; }
                    static class Companion {
                        Hue inspect(Vault item) { return item.shade; }
                    }
                }
                class Client {
                    boolean compare(Vault item) { return item.shade == Hue.RED; }
                    int read(Vault item) { return item.reveal(); }
                    Hue allowed(Vault item) { return item.shade(); }
                }
                """);
        List<String> p = s.problems().stream().filter(x -> x.contains("is private in")).toList();
        assertEquals(2, p.size(), s.problems().toString());
        assertTrue(p.get(0).contains("field 'shade' is private in Vault — call its method 'shade()'"), p.toString());
        assertTrue(p.get(1).contains("method 'reveal' is private in Vault"), p.toString());
    }

    @Test
    void aVoidCallUsedAsAValueIsAProblem() {
        Setup s = run("""
                class Parcel {
                    void attach(Object value) { }
                }
                class Client {
                    static void consume(Parcel item) { }
                    static Parcel create() { return new Parcel(); }
                    void verify(Parcel item) {
                        item.attach(consume(item));
                        Object value = consume(item);
                        boolean matches = consume(item) == null;
                        consume(item).toString();
                        if (consume(item)) { }
                    }
                    Object result(Parcel item) { return consume(item); }
                }
                """);
        List<String> p = s.problems().stream().filter(x -> x.contains("returns void")).toList();
        assertEquals(6, p.size(), s.problems().toString());
        assertTrue(p.get(0).contains("'consume(item)' returns void, so it has no value to use in method call"), p.toString());
    }

    @Test
    void aVoidCallAsAStatementALambdaBodyOrAForUpdateStaysSilent() {
        Setup s = run("""
                class Client {
                    static void record(int value) { }
                    void verify(java.util.List<String> words) {
                        record(1);
                        Runnable action = () -> record(2);
                        for (int index = 0; index < 3; index++, record(index)) { }
                        words.forEach(word -> System.out.println(word));
                        switch (words.size()) { case 0 -> record(3); default -> record(4); }
                    }
                }
                """);
        assertTrue(s.problems().stream().noneMatch(x -> x.contains("returns void")), s.problems().toString());
    }

    @Test
    void correctBooleanOperatorsAndUnknownOperandsStaySilent() {
        Setup s = run("""
                class Checker {
                    boolean ready() { return true; }
                    void verify(boolean active, Boolean wrapped, Object mystery, java.util.List<String> words, String label) {
                        boolean first = !active && wrapped || !ready();
                        boolean second = mystery == true;
                        boolean third = active != wrapped;
                        boolean fourth = !words.isEmpty() && label.isEmpty();
                        boolean fifth = !unresolvedCall();
                        if (active) { }
                        while (!active) { }
                        int sixth = active ? 1 : 2;
                    }
                }
                """);
        assertTrue(s.problems().stream().noneMatch(x -> x.contains("operator") || x.contains("condition")),
                s.problems().toString());
    }

    @Test
    void aMissingMemberProblemNamesTheMembersThatExist() {
        Setup s = run("""
                enum Direction { NORTH_BOUNDARY, SOUTH_BOUNDARY, CENTER_MARK }
                class Client {
                    Direction direction = Direction.NB;
                    String word = "x".lenght();
                }
                """);
        List<String> p = s.problems();
        assertTrue(p.stream().anyMatch(x -> x.contains("has no field 'NB' — its fields: ")
                && x.contains("NORTH_BOUNDARY")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("has no method 'lenght' — its methods: ")
                && x.contains("length")), p.toString());
    }
}
