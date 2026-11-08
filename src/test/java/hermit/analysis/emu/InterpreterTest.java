package hermit.analysis.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.sym.ClasspathIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterpreterTest {

    @TempDir
    Path dir;

    private CodeGraph graph(String... sources) throws IOException {
        for (String src : sources) {
            String name = src.replaceAll("(?s).*?(class|interface) (\\w+).*", "$2");
            Files.writeString(dir.resolve(name + ".java"), src);
        }
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    @Test
    void arithmeticBranchesAndAssertionsFold() throws IOException {
        CodeGraph g = graph("""
                class T1 {
                    void greenTest() {
                        int a = 20;
                        int b = a * 2 + 2;
                        if (b > 40) {
                            assertEquals(42, b);
                        }
                        String s = "v" + a;
                        assertEquals("v20", s);
                    }

                    void redTest() {
                        int a = 1 + 1;
                        assertEquals(3, a);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report green = Interpreter.run(g, "T1#greenTest");
        assertTrue(green.verdict().startsWith("PREDICTED GREEN"), green.verdict() + "\n" + green.trace());

        Interpreter.Report red = Interpreter.run(g, "T1#redTest");
        assertTrue(red.verdict().startsWith("PREDICTED RED"), red.verdict());
        assertTrue(red.verdict().contains("expected 3 but would be 2"), red.verdict());
    }

    @Test
    void sourceCallsInterpretAndExceptionsUnwindToCatches() throws IOException {
        CodeGraph g = graph("""
                class T2 {
                    int doubled(int x) {
                        return x * 2;
                    }

                    void explode() {
                        throw new IllegalStateException("boom");
                    }

                    void catchTest() {
                        int r = doubled(21);
                        boolean caught = false;
                        try {
                            explode();
                        } catch (IllegalStateException e) {
                            caught = true;
                        }
                        assertTrue(caught);
                        assertEquals(42, r);
                    }

                    void escapeTest() {
                        explode();
                    }

                    static void assertEquals(Object e, Object a) { }

                    static void assertTrue(boolean b) { }
                }
                """);
        Interpreter.Report ok = Interpreter.run(g, "T2#catchTest");
        assertTrue(ok.verdict().startsWith("PREDICTED GREEN"), ok.verdict() + "\n" + ok.trace());
        assertTrue(ok.trace().stream().anyMatch(t -> t.startsWith("caught IllegalStateException")),
                ok.trace().toString());

        Interpreter.Report thrown = Interpreter.run(g, "T2#escapeTest");
        assertTrue(thrown.verdict().startsWith("PREDICTED THROW: IllegalStateException"),
                thrown.verdict());
    }

    @Test
    void mockitoStubbingCaptorsAndVerdicts() throws IOException {
        // the T4 shape in miniature: stub an interface, drive source code, capture, assert
        CodeGraph g = graph("""
                interface Store {
                    long fetch(String key);

                    void save(String key, long value);
                }
                """, """
                class Engine {
                    private final Store store;

                    Engine(Store store) {
                        this.store = store;
                    }

                    void bump(String key) {
                        long v = store.fetch(key);
                        store.save(key, v + 1);
                    }
                }
                """, """
                class T3 {
                    void captorTest() {
                        Store store = mock(Store.class);
                        given(store.fetch(any())).willReturn(41L);
                        Engine engine = new Engine(store);
                        engine.bump("k");
                        ArgumentCaptor captor = ArgumentCaptor.forClass(Long.class);
                        verify(store).save(any(), captor.capture());
                        assertEquals(42L, captor.getValue());
                    }

                    void throwingStubTest() {
                        Store store = mock(Store.class);
                        given(store.fetch(any())).willThrow(new IllegalStateException("down"));
                        Engine engine = new Engine(store);
                        boolean caught = false;
                        try {
                            engine.bump("k");
                        } catch (IllegalStateException e) {
                            caught = true;
                        }
                        assertTrue(caught);
                    }

                    void wrongExpectationTest() {
                        Store store = mock(Store.class);
                        given(store.fetch(any())).willReturn(41L);
                        Engine engine = new Engine(store);
                        engine.bump("k");
                        ArgumentCaptor captor = ArgumentCaptor.forClass(Long.class);
                        verify(store).save(any(), captor.capture());
                        assertEquals(41L, captor.getValue());
                    }

                    static void assertEquals(Object e, Object a) { }

                    static void assertTrue(boolean b) { }
                }
                """);
        Interpreter.Report ok = Interpreter.run(g, "T3#captorTest");
        assertTrue(ok.verdict().startsWith("PREDICTED GREEN"), ok.verdict() + "\n" + ok.trace());
        assertTrue(ok.trace().stream().anyMatch(t -> t.startsWith("stub hit: Store#fetch")),
                ok.trace().toString());

        Interpreter.Report thrown = Interpreter.run(g, "T3#throwingStubTest");
        assertTrue(thrown.verdict().startsWith("PREDICTED GREEN"), thrown.verdict() + "\n" + thrown.trace());

        Interpreter.Report red = Interpreter.run(g, "T3#wrongExpectationTest");
        assertTrue(red.verdict().startsWith("PREDICTED RED"), red.verdict());
        assertTrue(red.verdict().contains("expected 41 but would be 42"), red.verdict());
    }

    @Test
    void reflectiveJdkModelingAndHonestUnknowns() throws IOException {
        CodeGraph g = graph("""
                import java.time.Instant;
                import java.util.List;

                class T4 {
                    void instantMath() {
                        Instant now = Instant.ofEpochSecond(1000);
                        Instant earlier = now.minusSeconds(5);
                        assertEquals(995L, earlier.getEpochSecond());
                        List items = List.of("a", "b");
                        assertEquals(2, items.size());
                    }

                    void unknownFlows() {
                        int x = mystery();
                        assertEquals(7, x);
                    }

                    static void assertEquals(Object e, Object a) { }

                    native int mystery();
                }
                """);
        Interpreter.Report ok = Interpreter.run(g, "T4#instantMath");
        assertTrue(ok.verdict().startsWith("PREDICTED GREEN"), ok.verdict() + "\n" + ok.trace());

        Interpreter.Report undecided = Interpreter.run(g, "T4#unknownFlows");
        assertTrue(undecided.verdict().contains("unknowns on the path"), undecided.verdict());
        assertTrue(undecided.trace().stream().anyMatch(t -> t.contains("undecidable")),
                undecided.trace().toString());
    }

    @Test
    void switchesOverEnumsLiteralsAndExpressionsInterpret() throws IOException {
        // arrow statements, multi-label + default, expression form with yield, colon fallthrough
        CodeGraph g = graph("""
                enum Kind { PREPARE, EXECUTE, ABORT }
                """, """
                class T6 {
                    void arrowStatement() {
                        Kind k = Kind.EXECUTE;
                        String label = "";
                        switch (k) {
                            case PREPARE -> label = "p";
                            case EXECUTE, ABORT -> label = "ea";
                            default -> label = "?";
                        }
                        assertEquals("ea", label);
                    }

                    void expressionForm() {
                        Kind k = Kind.PREPARE;
                        String viaExpr = switch (k) {
                            case PREPARE -> "p";
                            default -> {
                                yield "d";
                            }
                        };
                        assertEquals("p", viaExpr);
                        Kind other = Kind.ABORT;
                        String viaYield = switch (other) {
                            case PREPARE -> "p";
                            default -> {
                                yield "d";
                            }
                        };
                        assertEquals("d", viaYield);
                    }

                    void colonFallthrough() {
                        int n = 1;
                        int x = 0;
                        switch (n) {
                            case 1:
                                x = 1;
                            case 2:
                                x = x + 10;
                                break;
                            default:
                                x = 99;
                        }
                        assertEquals(11, x);
                    }

                    void undecidableSelectorIsGraceful() {
                        Kind k = mystery();
                        int after = 0;
                        switch (k) {
                            case PREPARE -> after = -1;
                            default -> after = -2;
                        }
                        after = 5;
                        assertEquals(5, after);
                    }

                    native Kind mystery();

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        assertTrue(Interpreter.run(g, "T6#arrowStatement").verdict().startsWith("PREDICTED GREEN"),
                Interpreter.run(g, "T6#arrowStatement").verdict());
        assertTrue(Interpreter.run(g, "T6#expressionForm").verdict().startsWith("PREDICTED GREEN"),
                Interpreter.run(g, "T6#expressionForm").verdict() + "\n" + Interpreter.run(g, "T6#expressionForm").trace());
        assertTrue(Interpreter.run(g, "T6#colonFallthrough").verdict().startsWith("PREDICTED GREEN"),
                Interpreter.run(g, "T6#colonFallthrough").verdict());
        Interpreter.Report graceful = Interpreter.run(g, "T6#undecidableSelectorIsGraceful");
        assertTrue(graceful.verdict().startsWith("PREDICTED GREEN"), graceful.verdict());
        assertTrue(graceful.trace().stream().anyMatch(t -> t.contains("switch selector")
                        && t.contains("undecidable")), graceful.trace().toString());
    }

    @Test
    void infiniteRecursionHitsTheDepthCapNotTheJvmStack() throws IOException {
        // deep interpreted call chains (generated builders/codecs) must degrade to an unknown
        // at the depth cap - never a StackOverflowError.
        CodeGraph g = graph("""
                class T7 {
                    int deep(int n) {
                        return deep(n + 1);
                    }

                    void t() {
                        int x = deep(0);
                        assertEquals(1, x);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "T7#t");
        assertTrue(r.verdict().startsWith("UNDECIDED"), r.verdict());
        assertTrue(r.trace().stream().anyMatch(t -> t.contains("depth cap")), r.trace().toString());
    }

    @Test
    void traceKeepsHeadAndTailWhenCapped() throws IOException {
        System.setProperty("hermit.emu.trace", "40");
        try {
            CodeGraph g = graph("""
                    class T8 {
                        void noisy() {
                            long i = 0;
                            while (i < 300) {
                                i = bump(i);
                            }
                            assertEquals(300L, i);
                        }

                        long bump(long i) {
                            return i + 1;
                        }

                        static void assertEquals(Object e, Object a) { }
                    }
                    """);
            Interpreter.Report r = Interpreter.run(g, "T8#noisy");
            assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict());
            String all = String.join("\n", r.trace());
            assertTrue(all.contains("event(s) elided"), all);
            assertTrue(r.trace().get(r.trace().size() - 1).contains("assertEquals holds"),
                    "the TAIL (the assertion) survives the ring: " + r.trace().get(r.trace().size() - 1));
        } finally {
            System.clearProperty("hermit.emu.trace");
        }
    }

    @Test
    void stepBudgetIsExplicit() throws IOException {
        CodeGraph g = graph("""
                class T5 {
                    void spin() {
                        long i = 0;
                        while (i < 100000) {
                            i = i + 1;
                        }
                    }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "T5#spin", 500);
        assertTrue(r.verdict().startsWith("UNDECIDED: step budget exhausted"), r.verdict());
        assertEquals(true, r.trace().size() > 0);
    }

    @Test
    void oneOfIntrinsicDrivesGeneratedAccessorsAndSwitch() throws IOException {
        CodeGraph g = graph("""
                class T14 {
                    enum ShapeKind { UNKNOWN_SHAPE, CIRCLE, SQUARE }
                    enum PayloadOneofType { UNSET, SHAPE }

                    static class Shape {
                        final ShapeKind shapeKind;
                        Shape(ShapeKind k) { this.shapeKind = k; }
                        ShapeKind shapeKind() { return shapeKind; }
                    }

                    static class Msg {
                        final OneOf<PayloadOneofType> payload;
                        Msg(OneOf<PayloadOneofType> payload) { this.payload = payload; }
                        Shape shape() {
                            return payload.kind() == PayloadOneofType.SHAPE ? (Shape) payload.as() : null;
                        }
                        static Builder newBuilder() { return new Builder(); }
                        static class Builder {
                            private OneOf<PayloadOneofType> payload;
                            Builder shape(Shape s) {
                                this.payload = new OneOf<>(T14.PayloadOneofType.SHAPE, s);
                                return this;
                            }
                            Msg build() { return new Msg(payload); }
                        }
                    }

                    String describe(Msg m) {
                        Shape s = m.shape();
                        return switch (s.shapeKind()) {
                            case SQUARE -> "square";
                            case CIRCLE -> "circle";
                            default -> "other";
                        };
                    }

                    void oneOfTest() {
                        Msg m = Msg.newBuilder().shape(new Shape(ShapeKind.SQUARE)).build();
                        assertEquals("square", describe(m));
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "T14#oneOfTest");
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict() + "\n" + r.trace());
        assertTrue(r.trace().stream().anyMatch(t -> t.contains("takes ShapeKind.SQUARE")), String.valueOf(r.trace()));
    }

    @Test
    void executableAssertionsRunTheLambda() throws IOException {
        CodeGraph g = graph("""
                class T15 {
                    void ok() { int x = 1; }
                    void boom() { throw new IllegalStateException("boom"); }
                    int mystery() {
                        Object o = System.getenv("x");
                        if (o == null) { throw new IllegalStateException("maybe"); }
                        return 1;
                    }

                    void greenNoThrow() { assertDoesNotThrow(() -> ok()); }
                    void redNoThrow() { assertDoesNotThrow(() -> boom()); }
                    void greenThrows() { assertThrows(IllegalStateException.class, () -> boom()); }
                    void redThrows() { assertThrows(IllegalStateException.class, () -> ok()); }
                    void undecidedThrows() { assertThrows(IllegalStateException.class, () -> mystery()); }

                    static void assertDoesNotThrow(Runnable r) { }
                    static void assertThrows(Class<?> c, Runnable r) { }
                }
                """);
        assertTrue(Interpreter.run(g, "T15#greenNoThrow").verdict().startsWith("PREDICTED GREEN"));
        Interpreter.Report red = Interpreter.run(g, "T15#redNoThrow");
        assertTrue(red.verdict().startsWith("PREDICTED RED"), red.verdict());
        assertTrue(red.verdict().contains("IllegalStateException"), red.verdict());
        assertTrue(Interpreter.run(g, "T15#greenThrows").verdict().startsWith("PREDICTED GREEN"));
        assertTrue(Interpreter.run(g, "T15#redThrows").verdict().startsWith("PREDICTED RED"));
        Interpreter.Report und = Interpreter.run(g, "T15#undecidedThrows");
        assertTrue(!und.verdict().startsWith("PREDICTED RED"), und.verdict());
    }

    @Test
    void instanceofFoldsWithPatternBinding() throws IOException {
        CodeGraph g = graph("""
                class T16 {
                    enum Kind { A, B }

                    static Kind fromObject(Object obj) {
                        if (obj == null) {
                            return Kind.B;
                        } else if (obj instanceof Kind k) {
                            return k;
                        } else {
                            return Kind.B;
                        }
                    }

                    void instanceofTest() {
                        assertEquals("A", fromObject(Kind.A).name());
                        assertEquals("B", fromObject(null).name());
                        assertEquals("B", fromObject("nope").name());
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "T16#instanceofTest");
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict() + "\n" + r.trace());
    }

    @Test
    void forEachOverArraysIterates() throws IOException {
        CodeGraph g = graph("""
                class T17 {
                    enum Kind { A, B, C }

                    void forEachTest() {
                        Kind[] kinds = {Kind.A, Kind.B};
                        String acc = "";
                        for (Kind k : kinds) {
                            acc = acc + k.name();
                        }
                        assertEquals("AB", acc);
                        assertEquals(2, kinds.length);
                        assertEquals("B", kinds[1].name());
                        int stopped = 0;
                        for (Kind k : new Kind[] {Kind.A, Kind.B, Kind.C}) {
                            if (k == Kind.B) {
                                break;
                            }
                            stopped = stopped + 1;
                        }
                        assertEquals(1, stopped);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "T17#forEachTest");
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict() + "\n" + r.trace());
        assertTrue(r.verdict().contains("all 4 evaluated"), r.verdict());
    }

    @Test
    void assertThatThrownByModelsTheChain() throws IOException {
        CodeGraph g = graph("""
                class T18 {
                    void boom() { throw new IllegalStateException("boom"); }
                    void ok() { int x = 1; }

                    void greenChain() {
                        assertThatThrownBy(() -> boom()).isInstanceOf(IllegalStateException.class);
                    }
                    void redNothingThrown() {
                        assertThatThrownBy(() -> ok()).isInstanceOf(IllegalStateException.class);
                    }
                    void redWrongType() {
                        assertThatThrownBy(() -> boom()).isInstanceOf(NumberFormatException.class);
                    }
                    void undecidedLink() {
                        assertThatThrownBy(() -> boom()).hasMessage("boom");
                    }
                    void greenCode() {
                        assertThatCode(() -> ok()).doesNotThrowAnyException();
                    }

                    static Object assertThatThrownBy(Runnable r) { return null; }
                    static Object assertThatCode(Runnable r) { return null; }
                }
                """);
        Interpreter.Report green = Interpreter.run(g, "T18#greenChain");
        assertTrue(green.verdict().startsWith("PREDICTED GREEN"), green.verdict() + "\n" + green.trace());
        assertTrue(Interpreter.run(g, "T18#redNothingThrown").verdict().startsWith("PREDICTED RED"));
        Interpreter.Report wrong = Interpreter.run(g, "T18#redWrongType");
        assertTrue(wrong.verdict().startsWith("PREDICTED RED"), wrong.verdict());
        Interpreter.Report und = Interpreter.run(g, "T18#undecidedLink");
        assertTrue(und.verdict().startsWith("UNDECIDED"), und.verdict());
        assertTrue(Interpreter.run(g, "T18#greenCode").verdict().startsWith("PREDICTED GREEN"));
    }

    @Test
    void vacuousGreenDemotesToUndecided() throws IOException {
        CodeGraph g = graph("""
                class T19 {
                    void vacuous() {
                        for (String s : unknownSource()) {
                            assertEquals("x", s);
                        }
                    }

                    void honestNoAsserts() {
                        int x = 1 + 1;
                    }

                    static Iterable<String> unknownSource() { return null; }
                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report vac = Interpreter.run(g, "T19#vacuous");
        assertTrue(vac.verdict().startsWith("UNDECIDED: no assertions were evaluated"),
                vac.verdict() + "\n" + vac.trace());
        Interpreter.Report clean = Interpreter.run(g, "T19#honestNoAsserts");
        assertTrue(clean.verdict().startsWith("PREDICTED GREEN: fully interpreted"), clean.verdict());
    }

    @Test
    void staticImportedFixtureHelpersInterpretAsAssertions() throws IOException {
        String helper = """
                package fix;
                public class Helpers {
                    public interface ThrowingRunnable { void run(); }

                    public static void assertThrowsCode(ThrowingRunnable r, String expected) {
                        try {
                            r.run();
                            throw new AssertionError("expected " + expected + " but nothing was thrown");
                        } catch (IllegalStateException e) {
                            if (!e.getMessage().equals(expected)) {
                                throw new AssertionError("wrong code");
                            }
                        }
                    }
                }
                """;
        // NB: single file per class - write the helper under its own name
        Files.writeString(dir.resolve("Helpers.java"), helper);
        CodeGraph g = graph("""
                import static fix.Helpers.assertThrowsCode;

                class T20 {
                    void boom() { throw new IllegalStateException("CODE_A"); }

                    void greenGuard() {
                        assertThrowsCode(() -> boom(), "CODE_A");
                    }

                    void undecidedNoThrow() {
                        Object o = System.getenv("x");
                        if (o == null) {
                            boom();
                        }
                        assertThrowsCode(() -> { }, "CODE_A");
                    }
                }
                """);
        Interpreter.Report green = Interpreter.run(g, "T20#greenGuard");
        assertTrue(green.verdict().startsWith("PREDICTED GREEN: all 1 evaluated"),
                green.verdict() + "\n" + green.trace());
        // the helper reaches its AssertionError, but the path has unknowns → honest UNDECIDED
        Interpreter.Report und = Interpreter.run(g, "T20#undecidedNoThrow");
        assertTrue(und.verdict().startsWith("UNDECIDED"), und.verdict() + "\n" + und.trace());
    }

    @Test
    void environmentReachingJdkCallsNeverExecute() throws IOException {
        CodeGraph g = graph("""
                class T21 {
                    void sandboxTest() {
                        ProcessBuilder pb = new ProcessBuilder("definitely-not-a-command");
                        Object proc = pb.start();
                        Object cls = Class.forName("java.util.HashMap");
                        Object prefs = java.util.prefs.Preferences.userRoot();
                        assertEquals(1, 1);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "T21#sandboxTest");
        // every environment-reaching call must degrade to an unknown, never execute
        assertTrue(r.trace().stream().anyMatch(t -> t.contains("new ProcessBuilder")), String.valueOf(r.trace()));
        assertTrue(r.trace().stream().anyMatch(t -> t.contains("'forName'")), String.valueOf(r.trace()));
        assertTrue(r.trace().stream().anyMatch(t -> t.contains("'userRoot'")), String.valueOf(r.trace()));
        assertTrue(r.trace().stream().noneMatch(t -> t.contains("reflect")), String.valueOf(r.trace()));
        assertTrue(r.verdict().startsWith("PREDICTED GREEN") || r.verdict().startsWith("UNDECIDED"), r.verdict());
    }

    @Test
    void staticInitializersDoNotRerunOnEveryInstantiation() throws IOException {
        // Bug report #2 item 3: initFields evaluated STATIC initializers on every `new`, so a generated builder's
        // `DEFAULT = newBuilder().build()` constructed another instance each time and recursed to the depth cap -
        // every fixture heavy with generated message classes came back UNDECIDED with tens of thousands of unknowns
        CodeGraph g = graph("""
                class Stamp {
                    static final Stamp DEFAULT = newBuilder().build();
                    long seconds;

                    Stamp(long seconds) {
                        this.seconds = seconds;
                    }

                    static Builder newBuilder() { return new Builder(); }

                    static class Builder {
                        long seconds;

                        Builder seconds(long s) {
                            this.seconds = s;
                            return this;
                        }

                        Stamp build() { return new Stamp(seconds); }
                    }
                }
                """, """
                class StampTest {
                    void buildsAStamp() {
                        Stamp t = Stamp.newBuilder().seconds(7).build();
                        assertEquals(7L, t.seconds);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "StampTest#buildsAStamp");
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict() + "\n" + r.trace());
        String trace = String.join("\n", r.trace());
        assertFalse(trace.contains("depth cap"), "no runaway recursion:\n" + trace);
        // the static is not evaluated at all while nothing reads it
        assertFalse(trace.contains("DEFAULT"), trace);
    }

    @Test
    void aStaticWithoutAnInitializerKeepsItsDefaultValue() throws IOException {
        // the same change must not turn an uninitialised static into an unknown
        CodeGraph g = graph("""
                class Counters {
                    static int hits;
                    static final int LIMIT = 3;

                    int read() { return hits; }
                }
                """, """
                class CountersTest {
                    void readsZero() {
                        Counters c = new Counters();
                        assertEquals(0, c.read());
                        assertEquals(3, Counters.LIMIT);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "CountersTest#readsZero");
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict() + "\n" + r.trace());
    }

    @Test
    void assertEqualsComparesValueObjectsByTheirFields() throws IOException {
        // Bug report #2 item 2, second half: with the right overload chosen, two structurally equal generated
        // objects still compared unequal because assertEquals used reference identity
        CodeGraph g = graph("""
                class Money {
                    long cents;

                    Money(long cents) { this.cents = cents; }

                    public boolean equals(Object other) { return true; }
                }
                """, """
                class Coin {
                    long cents;

                    Coin(long cents) { this.cents = cents; }
                }
                """, """
                class MoneyTest {
                    void equalValuesHold() {
                        assertEquals(new Money(5), new Money(5));
                    }

                    void differingValuesFail() {
                        assertEquals(new Money(5), new Money(6));
                    }

                    void withoutEqualsItStaysIdentity() {
                        assertEquals(new Coin(5), new Coin(5));
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        assertTrue(Interpreter.run(g, "MoneyTest#equalValuesHold").verdict().startsWith("PREDICTED GREEN"),
                Interpreter.run(g, "MoneyTest#equalValuesHold").verdict());
        assertTrue(Interpreter.run(g, "MoneyTest#differingValuesFail").verdict().startsWith("PREDICTED RED"),
                Interpreter.run(g, "MoneyTest#differingValuesFail").verdict());
        // a class with no field-based equality keeps reference semantics rather than guessing
        assertTrue(Interpreter.run(g, "MoneyTest#withoutEqualsItStaysIdentity").verdict().startsWith("PREDICTED RED"),
                Interpreter.run(g, "MoneyTest#withoutEqualsItStaysIdentity").verdict());
    }

    @Test
    void sameArityOverloadsArePickedByArgumentType() throws IOException {
        // Bug report #2 item 2: methods were resolved by name+arity only, so a generated builder's
        // dueAt(Stamp) and dueAt(Stamp.Builder) collapsed into one entry. The emulator
        // stored a Builder in the Stamp field and predicted RED for a test that passes
        CodeGraph g = graph("""
                class Stamp2 {
                    long seconds;

                    Stamp2(long seconds) { this.seconds = seconds; }

                    static B newBuilder() { return new B(); }

                    static class B {
                        long sec;

                        B seconds(long s) {
                            this.sec = s;
                            return this;
                        }

                        Stamp2 build() { return new Stamp2(sec); }
                    }
                }
                """, """
                class Reminder {
                    Stamp2 due;

                    static RB newBuilder() { return new RB(); }

                    Stamp2 dueAt() { return due; }

                    static class RB {
                        Stamp2 value;

                        RB dueAt(Stamp2 v) {
                            this.value = v;
                            return this;
                        }

                        RB dueAt(Stamp2.B b) {
                            this.value = b.build();
                            return this;
                        }

                        Reminder build() {
                            Reminder out = new Reminder();
                            out.due = value;
                            return out;
                        }
                    }
                }
                """, """
                class ReminderTest {
                    void picksTheBuilderOverload() {
                        Reminder reminder = Reminder.newBuilder().dueAt(Stamp2.newBuilder().seconds(5)).build();
                        assertEquals(5L, reminder.dueAt().seconds);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "ReminderTest#picksTheBuilderOverload");
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"),
                r.verdict() + "\n" + String.join("\n", r.trace()));
    }

    @Test
    void fullyQualifiedReferencesResolveAsTypes() throws IOException {
        // Bug report #2 item 4: generated code writes `com.acme...Codec` fully qualified and the evaluator walked it
        // as identifier -> field -> field, emitting "? identifier 'com'" 1154 times in one run
        Path pkg = dir.resolve("a/b");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Codec.java"), """
                package a.b;

                public class Codec {
                    public static final int VALUE = 7;
                }
                """);
        Files.writeString(dir.resolve("QualifiedTest.java"), """
                class QualifiedTest {
                    void readsAQualifiedStatic() {
                        assertEquals(7, a.b.Codec.VALUE);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        Interpreter.Report r = Interpreter.run(g, "QualifiedTest#readsAQualifiedStatic");
        String trace = String.join("\n", r.trace());
        assertFalse(trace.contains("identifier 'a'"), trace);
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict() + "\n" + trace);
    }

    @Test
    void theBundledRulesAdmitOnlyTheJdkValueTypes() {
        List<Interpreter.Rule> rules = Interpreter.defaultRules();
        assertTrue(Interpreter.whitelistedName("java.util.ArrayList", rules));
        assertTrue(Interpreter.whitelistedName("java.time.Instant", rules));
        assertTrue(Interpreter.whitelistedName("java.util.concurrent.atomic.AtomicInteger", rules));
        // anything that can reach the environment stays out
        assertFalse(Interpreter.whitelistedName("java.lang.ProcessBuilder", rules));
        assertFalse(Interpreter.whitelistedName("java.lang.System", rules));
        assertFalse(Interpreter.whitelistedName("java.lang.Thread", rules));
        assertFalse(Interpreter.whitelistedName("java.lang.reflect.Method", rules));
        assertFalse(Interpreter.whitelistedName("java.util.concurrent.ExecutorService", rules));
        assertFalse(Interpreter.whitelistedName("java.io.File", rules));
        // a library's value types are not admitted unless a project's rules name them
        assertFalse(Interpreter.whitelistedName("com.example.buffer.Bytes", rules));
        // calls that would make a prediction depend on the clock or chance are never run
        assertTrue(Interpreter.skippedMethod("nanoTime", rules));
        assertTrue(Interpreter.skippedMethod("randomUUID", rules));
        assertFalse(Interpreter.skippedMethod("plusSeconds", rules));
    }

    @Test
    void aProjectsRulesComeBeforeTheBundledOnes() throws IOException {
        // Bug report #2 item 5: a library's byte-buffer value type (jar-only) was unknown, so every generated builder's
        // byte comparison was undecidable. The admitted library types were hard-coded; they are a project's rules now
        Path file = dir.resolve("project.rules");
        Files.writeString(file, """
                # the library's in-memory value types; its streams reach the environment
                allow ^com\\.example\\.buffer\\.(Bytes|BufferedData)$
                deny  ^java\\.util\\.Random$
                """);
        List<Interpreter.Rule> rules = Interpreter.loadRules(file.toString());
        assertTrue(Interpreter.whitelistedName("com.example.buffer.Bytes", rules));
        assertTrue(Interpreter.whitelistedName("com.example.buffer.BufferedData", rules));
        assertFalse(Interpreter.whitelistedName("com.example.stream.WritableStreamingData", rules));
        assertFalse(Interpreter.whitelistedName("java.util.Random", rules), "a project's deny precedes the bundled allow");
        assertTrue(Interpreter.whitelistedName("java.util.ArrayList", rules), "the bundled rules still follow");
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> Interpreter.parseRules("admit ^x$", "bad.rules"));
        assertTrue(bad.getMessage().contains("bad.rules:1"), bad.getMessage());
    }

    @Test
    void anUnpopulatedFieldIsUnknownNotNull() throws IOException {
        // guard for the fix above: the emulator leaves a fixture field it could not model at its default, and
        // treating that as null threw a FALSE NullPointerException on a real test base
        CodeGraph g = graph("""
                class Holder {
                    Object builder;

                    void use() {
                        builder.toString();
                        assertEquals(1, 1);
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """, """
                class HolderTest {
                    void doesNotThrow() {
                        new Holder().use();
                    }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "HolderTest#doesNotThrow");
        String trace = String.join("\n", r.trace());
        assertFalse(trace.contains("throw NullPointerException"), trace);
        assertFalse(r.verdict().startsWith("PREDICTED THROW"), r.verdict() + "\n" + trace);
    }

    @Test
    void aCallOnNullThrowsNullPointerException() throws IOException {
        // The acceptance case of bug report #2 item 3: assertThatThrownBy(() -> new X(null, null)) stayed
        // undecidable because invoking a method on null produced an unknown instead of the NPE the test asserts
        CodeGraph g = graph("""
                class Shelf {
                    Object books;

                    Shelf(Map sections) {
                        this.books = sections.get("BOOKS");
                    }
                }
                """, """
                class ShelfTest {
                    void rejectsNullSections() {
                        assertThatThrownBy(() -> new Shelf(null)).isInstanceOf(NullPointerException.class);
                    }

                    static Object assertThatThrownBy(Runnable r) { return null; }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "ShelfTest#rejectsNullSections");
        String trace = String.join("\n", r.trace());
        assertTrue(trace.contains("throw NullPointerException"), trace);
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict() + "\n" + trace);
    }

    @Test
    void anUnknownSideMakesAssertEqualsUndecidableNotRed() throws IOException {
        // assertEquals(expectedInfo, <unknown>) was "PREDICTED RED: expected obj:Info but
        // would be unknown(...)" and satisfied an expect=red gate for a test that runs green
        CodeGraph g = graph("""
                class T9 {
                    Info expected() { return new Info(); }

                    void t() {
                        Object response = External.fetch();
                        assertEquals(expected(), response.hashCode());
                    }

                    static void assertEquals(Object e, Object a) { }
                    static class Info { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "T9#t");
        assertTrue(r.verdict().startsWith("UNDECIDED"), r.verdict());
        assertFalse(r.verdict().startsWith("PREDICTED RED"), r.verdict());
    }

    // ---- emulator bug report #3 (2026-09-09) ------------------------------------------------------------------

    @Test
    void aBodyThatCompletesWithoutReturningIsUnknownNotNull() throws IOException {
        // a real validation method ended in an if/else whose arms were both skipped; the
        // body completed without a return, the call yielded null, `validationResult == OK` decided FALSE against it
        // and a real, passing test was reported as PREDICTED THROW. Every completing path of a non-void method returns,
        // so falling off the end proves a deciding branch was skipped - the result is unknown.
        CodeGraph g = graph("""
                class Validator {
                    Store store;

                    Code validate(String id) {
                        Item item = store.get(id);
                        if (item == null) {
                            return Code.BAD;
                        }
                        if (item.flagged()) {
                            return Code.BAD;
                        } else {
                            return Code.OK;
                        }
                    }

                    Code validateWithTrailingReturn(String id) {
                        Item item = store.get(id);
                        if (item.flagged()) {
                            return Code.BAD;
                        }
                        return Code.OK;
                    }

                    static String check(Validator v, String id) {
                        Code result = v.validate(id);
                        if (result == Code.OK) {
                            return "ok";
                        } else {
                            throw new IllegalStateException("not ok: " + result);
                        }
                    }

                    static String checkTrailing(Validator v, String id) {
                        Code result = v.validateWithTrailingReturn(id);
                        if (result == Code.OK) {
                            return "ok";
                        } else {
                            throw new IllegalStateException("not ok: " + result);
                        }
                    }
                }
                enum Code { OK, BAD }
                interface Item { boolean flagged(); }
                interface Store { Item get(String id); }
                """, """
                class ValidatorTest {
                    void fallThrough() {
                        assertEquals("ok", Validator.check(new Validator(), "id"));
                    }

                    void trailingReturnAfterSkippedBranch() {
                        assertEquals("ok", Validator.checkTrailing(new Validator(), "id"));
                    }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "ValidatorTest#fallThrough");
        String trace = String.join("\n", r.trace());
        assertFalse(r.verdict().startsWith("PREDICTED THROW"), r.verdict() + "\n" + trace);
        assertTrue(r.verdict().startsWith("UNDECIDED"), r.verdict());
        assertTrue(trace.contains("fell through Validator#validate"), trace);
        // a return REACHED after a skipped branch that itself returns is not provably the result either
        Interpreter.Report t = Interpreter.run(g, "ValidatorTest#trailingReturnAfterSkippedBranch");
        assertTrue(t.verdict().startsWith("UNDECIDED"), t.verdict() + "\n" + String.join("\n", t.trace()));
    }

    @Test
    void explicitConstructorInvocationsRunTheDelegatedConstructor() throws IOException {
        // `this(...)` / `super(...)` were unresolved calls; the delegated constructor never ran,
        // the fields kept their defaults and assertEquals("x", new Delegating("x").get()) was a false PREDICTED RED
        CodeGraph g = graph("""
                class Delegating {
                    private final String s;
                    private final int n;
                    Delegating(final String s) { this(s, 7); }
                    Delegating(final String s, final int n) { this.s = s; this.n = n; }
                    String get() { return s; }
                    int n() { return n; }
                }
                class Base {
                    private final String t;
                    Base(final String t) { this.t = t; }
                    String get() { return t; }
                }
                class Derived extends Base {
                    private final int k;
                    Derived(final String t) { super(t); this.k = 3; }
                    int k() { return k; }
                }
                record Rec(String a, int b) {
                    Rec(final String a) { this(a, 7); }
                }
                class Failing extends RuntimeException {
                    private final Object status;
                    Failing(final Object status) { this(status, null); }
                    Failing(final Object status, final Object rollback) { super("m"); this.status = status; }
                    Object getStatus() { return status; }
                }
                """, """
                class ProbeTest {
                    void delegatingCtorField()   { assertEquals("x", new Delegating("x").get()); }
                    void delegatingCtorDefault() { assertEquals(7, new Delegating("x").n()); }
                    void superCtorField()        { assertEquals("x", new Derived("x").get()); }
                    void superCtorOwnField()     { assertEquals(3, new Derived("x").k()); }
                    void recordDelegation()      { assertEquals(7, new Rec("x").b()); }
                    void librarySuperIsSilent()  { assertEquals("X", new Failing("X").getStatus()); }
                    void librarySuperStateIsUnknown() { assertEquals("m", new Failing("X").getMessage()); }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        for (String m : List.of("delegatingCtorField", "delegatingCtorDefault", "superCtorField", "superCtorOwnField",
                "recordDelegation", "librarySuperIsSilent")) {
            Interpreter.Report r = Interpreter.run(g, "ProbeTest#" + m);
            assertTrue(r.verdict().startsWith("PREDICTED GREEN"), m + ": " + r.verdict() + "\n" + String.join("\n", r.trace()));
        }
        // the base class has no source: its state (the message) is unknown, never a decisive null
        Interpreter.Report base = Interpreter.run(g, "ProbeTest#librarySuperStateIsUnknown");
        assertFalse(base.verdict().startsWith("PREDICTED RED"), base.verdict() + "\n" + String.join("\n", base.trace()));
        assertTrue(String.join("\n", base.trace()).contains("no source"), String.join("\n", base.trace()));
    }

    @Test
    void delegatedObjectsCompareByTheirRealFieldsAndNotEqualsIsModelled() throws IOException {
        // two records built through a delegating constructor had ALL fields at their
        // defaults and compared equal - a real failing assertEquals was a false PREDICTED GREEN; assertNotEquals,
        // assertSame and assertNotSame were not assertions at all
        CodeGraph g = graph("""
                class Delegating {
                    private final String s;
                    Delegating(final String s) { this(s, 7); }
                    Delegating(final String s, final int n) { this.s = s; }
                    String get() { return s; }
                }
                record Rec(String a, int b) {
                    Rec(final String a) { this(a, 7); }
                }
                """, """
                class ProbeEqTest {
                    void differentContentEqualsMustFail()   { assertEquals(new Rec("x"), new Rec("y")); }
                    void differentContentMustNotBeEqual()   { assertNotEquals(new Rec("x"), new Rec("y")); }
                    void sameContentIsEqual()               { assertEquals(new Rec("x"), new Rec("x")); }
                    void stringsFromDelegationMustDiffer()  { assertEquals(new Delegating("a").get(), new Delegating("b").get()); }
                    void sameInstance()                     { Rec r = new Rec("x"); assertSame(r, r); }
                    void distinctInstances()                { assertNotSame(new Rec("x"), new Rec("x")); }
                    void equalButNotSameFails()             { assertSame(new Rec("x"), new Rec("x")); }

                    static void assertEquals(Object e, Object a) { }
                    static void assertNotEquals(Object e, Object a) { }
                    static void assertSame(Object e, Object a) { }
                    static void assertNotSame(Object e, Object a) { }
                }
                """);
        assertTrue(Interpreter.run(g, "ProbeEqTest#differentContentEqualsMustFail").verdict().startsWith("PREDICTED RED"));
        assertTrue(Interpreter.run(g, "ProbeEqTest#differentContentMustNotBeEqual").verdict().startsWith("PREDICTED GREEN"));
        assertTrue(Interpreter.run(g, "ProbeEqTest#sameContentIsEqual").verdict().startsWith("PREDICTED GREEN"));
        assertTrue(Interpreter.run(g, "ProbeEqTest#stringsFromDelegationMustDiffer").verdict().startsWith("PREDICTED RED"));
        assertTrue(Interpreter.run(g, "ProbeEqTest#sameInstance").verdict().startsWith("PREDICTED GREEN"));
        assertTrue(Interpreter.run(g, "ProbeEqTest#distinctInstances").verdict().startsWith("PREDICTED GREEN"));
        Interpreter.Report notSame = Interpreter.run(g, "ProbeEqTest#equalButNotSameFails");
        assertTrue(notSame.verdict().startsWith("PREDICTED RED"), notSame.verdict());
        assertTrue(notSame.verdict().contains("is not the same instance"), notSame.verdict());
    }

    @Test
    void aStaticInitializerResolvesUnqualifiedCallsAgainstItsOwnType() throws IOException {
        // `DEFAULT = newBuilder().build()` - the static-initializer frame has no self, so the
        // unqualified call got a null receiver and the value read from DEFAULT was unknown
        CodeGraph g = graph("""
                class Widget {
                    static final Widget DEFAULT = newBuilder().build();
                    private final int size;
                    private Widget(final int size) { this.size = size; }
                    static Builder newBuilder() { return new Builder(); }
                    int size() { return size; }

                    static class Builder {
                        private int size = 5;
                        Builder size(final int s) { this.size = s; return this; }
                        Widget build() { return new Widget(size); }
                    }
                }
                """, """
                class WidgetTest {
                    void staticInitializerSeesItsOwnStatics() { assertEquals(5, Widget.DEFAULT.size()); }

                    static void assertEquals(Object e, Object a) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "WidgetTest#staticInitializerSeesItsOwnStatics");
        String trace = String.join("\n", r.trace());
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict() + "\n" + trace);
        assertFalse(trace.contains("'newBuilder' at") && trace.contains("on null"), trace);
    }

    @Test
    void aNestedEnumConstantReachedThroughItsOuterClassIsTheSameConstant() throws IOException {
        // a generated builder writes
        // `new OneOf<>(Message.ContentOneOfType.TEXT, x)` and hasText() compares
        // `content.kind() == ContentOneOfType.TEXT`; the outer-qualified spelling lost its nested type, read as
        // `Message.TEXT`, and the comparison decided FALSE - a passing test was PREDICTED RED
        CodeGraph g = graph("""
                class Message {
                    enum ContentOneOfType { UNSET, TEXT }
                    private final Object author;
                    private final Pair<ContentOneOfType> content;

                    Message(Object author, Pair<ContentOneOfType> content) {
                        this.author = author;
                        this.content = content;
                    }

                    boolean hasText() {
                        return content.kind() == ContentOneOfType.TEXT;
                    }

                    Object storedKind() {
                        return content.kind();
                    }

                    static Builder newBuilder() {
                        return new Builder();
                    }

                    static final class Builder {
                        private Object author;
                        private Pair<ContentOneOfType> content = new Pair<>(ContentOneOfType.UNSET, null);

                        Builder text(Object text) {
                            this.content = new Pair<>(Message.ContentOneOfType.TEXT, text);
                            return this;
                        }

                        Message build() {
                            return new Message(author, content);
                        }
                    }
                }
                """, """
                class Pair<K> {
                    private final K kind;
                    private final Object value;

                    Pair(K kind, Object value) {
                        this.kind = kind;
                        this.value = value;
                    }

                    K kind() {
                        return kind;
                    }
                }
                """, """
                class MessageTest {
                    void builderSetsTheKind() {
                        Message message = Message.newBuilder().text(new Object()).build();
                        assertTrue(message.hasText());
                        assertEquals(Message.ContentOneOfType.TEXT, message.storedKind());
                        assertEquals(Message.ContentOneOfType.TEXT, Message.ContentOneOfType.TEXT);
                    }

                    void aMissingConstantIsNotInvented() {
                        assertEquals("x", Message.NO_SUCH_CONSTANT);
                    }

                    static void assertEquals(Object e, Object a) { }
                    static void assertTrue(boolean b) { }
                }
                """);
        Interpreter.Report r = Interpreter.run(g, "MessageTest#builderSetsTheKind");
        assertTrue(r.verdict().startsWith("PREDICTED GREEN"), r.verdict() + "\n" + String.join("\n", r.trace()));
        assertTrue(r.verdict().contains("all 3 evaluated"), r.verdict());
        // a constant the fully visible source does not declare is unknown, never a decisive named value
        Interpreter.Report missing = Interpreter.run(g, "MessageTest#aMissingConstantIsNotInvented");
        assertFalse(missing.verdict().startsWith("PREDICTED RED"), missing.verdict());
        assertTrue(String.join("\n", missing.trace()).contains("no static 'NO_SUCH_CONSTANT' on Message"),
                String.join("\n", missing.trace()));
    }
}
