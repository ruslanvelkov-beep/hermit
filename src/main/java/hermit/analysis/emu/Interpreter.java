package hermit.analysis.emu;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.sym.Scope;
import hermit.analysis.sym.Symbol;
import hermit.analysis.sym.SymbolResolver;
import hermit.analysis.sym.TypeHierarchy;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Test-scoped abstract interpreter over parse trees (see docs/design-emulator.md): predicts what a
 * test would do - invocations, branches, stub hits, throws/catches, assertion verdicts - WITHOUT
 * compiling or executing project code. Values are tiered (concrete / symbolic / unknown); Mockito
 * stubbing in the test IS the model of the mocked boundary; concrete JDK values are modeled by
 * reflective invocation over a package whitelist. Every imprecision becomes a trace event, and the
 * verdict advertises its own unknown count.
 */
public final class Interpreter {

    /** The prediction: a verdict line, the trace, and how many unknowns the path accumulated. */
    public record Report(String verdict, List<String> trace, int unknowns) {}

    // ---- values --------------------------------------------------------------------------------------------

    /** A concrete Java value (numbers normalized to Long/Double; may hold real JDK objects). */
    record Concrete(Object v) {}

    /** A named unknown; identity-comparable (x == x is true even when x's value is not known). */
    record Sym(int id, String origin) {}

    record Unknown(String reason) {}

    /** An enum constant known by name - comparable without loading the class. */
    record EnumConst(String type, String name) {}

    /** A source-typed object with interpreted fields. */
    static final class ObjVal {
        final TypeHierarchy.TypeEntry type;
        final Map<String, Object> fields = new HashMap<>();

        ObjVal(TypeHierarchy.TypeEntry type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return "obj:" + (type == null ? "?" : type.name());
        }
    }

    /** A Mockito mock: stubs registered by the test, invocations recorded for verify(). */
    static final class MockVal {
        final String typeName;
        final List<Stub> stubs = new ArrayList<>();
        final List<Invocation> invocations = new ArrayList<>();

        MockVal(String typeName) {
            this.typeName = typeName;
        }

        @Override
        public String toString() {
            return "mock:" + typeName;
        }
    }

    record Invocation(String method, List<Object> args) {}

    static final class Stub {
        final Invocation pattern;   // args may be MatcherVal
        Object returns;
        Object throwsValue;
        LambdaVal answer;           // the willAnswer lambda WITH its captured frame

        Stub(Invocation pattern) {
            this.pattern = pattern;
        }
    }

    static final class CaptorVal {
        final List<Object> captured = new ArrayList<>();
    }

    /** A type used as a value (static calls, X.class); {@code entry} resolved at creation when possible. */
    record ClassVal(String typeName, TypeHierarchy.TypeEntry entry) {}

    /** The OneOf runtime of generated message classes - a jar class, so modelled intrinsically: (kind, value). */
    record OneOfVal(Object kind, Object value) {}

    /** An interpreted array (array initializer / for-each source). */
    record ArrayVal(List<Object> elements) {}

    /** AssertJ chain target from assertThatThrownBy/assertThatCode: the caught throw, or none.
     *  cleanPath = the lambda ran with no new unknowns, so "nothing thrown" is provable. */
    record ThrownAssertVal(EmuThrow thrown, boolean cleanPath) {}

    record MatcherVal(String kind, Object value) {}          // kind: any | eq | captor

    record StubbingVal(MockVal mock, Invocation invocation) {}

    record VerifyVal(MockVal mock) {}

    record LambdaVal(Node node, Frame frame) {}

    /** The config-framework model: a fluent builder's key→value map (see docs/design-emulator.md). */
    static final class ConfigVal {
        final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public String toString() {
            return "config" + values.keySet();
        }
    }

    private static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    // ---- state ---------------------------------------------------------------------------------------------

    private final CodeGraph graph;

    private final TypeHierarchy hierarchy;

    private final List<String> trace = new ArrayList<>();

    private final Map<String, Map<String, Object>> statics = new HashMap<>();

    private final Map<Node, Path> rootFiles = new IdentityHashMap<>();

    private int steps;

    private int unknowns;

    private int heldAssertions;

    private int symIds;

    private Invocation lastMockInvocation;

    private MockVal lastMockReceiver;

    private String failedAssertion;

    private int undecidableAssertions;

    private final int stepBudget;

    // trace is a head+tail ring: the FIRST half of the cap is kept verbatim, then a rolling tail -
    // the interesting events (the switch, the assertion) are usually LATE, so the tail must survive
    private final int traceCap = Math.max(40, Integer.getInteger("hermit.emu.trace", 400));

    private final ArrayDeque<String> traceTail = new ArrayDeque<>();

    private long elided;

    // interpretation depth cap: generated code (message builders/codecs) produces call chains deep
    private int depth;

    private final int depthCap = Math.max(8, Integer.getInteger("hermit.emu.depth", 96));

    static final class Frame {
        final ObjVal self;
        final Map<Symbol, Object> locals = new HashMap<>();
        /** Parameters the CALLER passed a {@code null} literal - the only nulls a call may throw NPE on. */
        final Set<Symbol> nullLiterals = new HashSet<>();
        /** a branch in THIS body was skipped whose arm returns or throws - a later return is not provably the
         *  result the real run would produce, and a body that ends without one certainly is not. */
        boolean skippedTransfer;
        /** The type whose static initializer this frame evaluates - an unqualified call resolves against it. */
        TypeHierarchy.TypeEntry enclosing;

        Frame(ObjVal self) {
            this.self = self;
        }
    }

    /** Statement/loop completion. */
    private record Done(String kind, Object value) {
        static final Done NORMAL = new Done("normal", null);
    }

    /** An emulated Java exception unwinding through interpreted frames. */
    static final class EmuThrow extends RuntimeException {
        final Object value;
        final String typeName;
        final TypeHierarchy.TypeEntry type;

        EmuThrow(Object value, String typeName, TypeHierarchy.TypeEntry type) {
            super(typeName, null, false, false);
            this.value = value;
            this.typeName = typeName;
            this.type = type;
        }
    }

    private Interpreter(CodeGraph graph, int stepBudget) {
        this.graph = graph;
        this.hierarchy = graph.hierarchy();
        this.stepBudget = stepBudget;
        for (Path f : graph.sources().files()) {
            var tree = graph.treeOf(f);
            if (tree != null) {
                rootFiles.put(tree.root(), f);
            }
        }
    }

    /**
     * The nearest enclosing scope of a node, from its file's resolver: the rewrite keeps scopes in the resolver, where the
     * reference kept them on the node.
     */
    private Scope scopeOf(Node n) {
        SymbolResolver r = resolverOf(n);
        return r == null ? null : r.scopeOf(n);
    }

    /** The scope a node owns (a block, a method, a type), from its file's resolver; null when it owns none. */
    private Scope ownScopeOf(Node n) {
        SymbolResolver r = resolverOf(n);
        return r == null ? null : r.ownScope(n);
    }

    /** The resolver of the file a node belongs to, found by its tree's root; null for a node of no indexed file. */
    private SymbolResolver resolverOf(Node n) {
        Node root = n;
        while (root.parent() != null) {
            root = root.parent();
        }
        Path file = rootFiles.get(root);
        return file == null ? null : graph.sources().resolverFor(file);
    }

    // ---- entry ---------------------------------------------------------------------------------------------

    /** Emulate {@code Type#testMethod}: fields (mocks included) → @BeforeEach chain → the method. */
    public static Report run(CodeGraph graph, String testSpec) {
        return run(graph, testSpec, Integer.getInteger("hermit.emu.steps", 200000));
    }

    public static Report run(CodeGraph graph, String testSpec, int stepBudget) {
        Interpreter emu = new Interpreter(graph, stepBudget);
        try {
            return emu.runTest(testSpec);
        } catch (EmuThrow t) {
            return emu.report("PREDICTED THROW: " + t.typeName + " escapes the test");
        } catch (Budget b) {
            return emu.report("UNDECIDED: step budget exhausted (" + emu.stepBudget + ")");
        } catch (Throwable t) {
            return emu.report("EMULATOR ERROR: " + t);
        }
    }

    private static final class Budget extends RuntimeException {}

    private Report runTest(String spec) {
        List<CodeGraph.MethodRef> found = graph.locate(spec);
        if (found.isEmpty()) {
            return report("no such method: " + spec);
        }
        Node method = found.get(0).decl();
        Node classDecl = enclosingType(method);
        TypeHierarchy.TypeEntry entry = classDecl == null ? null : hierarchy.entryOf(classDecl);
        ObjVal self = new ObjVal(entry);
        if (entry != null) {
            initFields(entry, self);
        }
        Frame frame = new Frame(self);
        if (entry != null) {
            runLifecycle(entry, self, "BeforeEach");
        }
        event("test " + spec);
        Done d = execBody(method, frame);
        if (failedAssertion != null) {
            return report("PREDICTED RED: " + failedAssertion);
        }
        if (undecidableAssertions > 0) {
            return report("UNDECIDED: " + undecidableAssertions + " assertion(s) undecidable");
        }
        if ("return".equals(d.kind()) || "normal".equals(d.kind())) {
            if (heldAssertions > 0) {
                return report("PREDICTED GREEN: all " + heldAssertions + " evaluated assertion(s) hold");
            }
            // Nothing was actually checked. Only a fully-interpreted path proves a vacuous pass;
            // with unknowns, the asserting code was likely never REACHED - that is not a green.
            return unknowns == 0
                    ? report("PREDICTED GREEN: fully interpreted, nothing thrown (no assertions in the path)")
                    : report("UNDECIDED: no assertions were evaluated — the asserting path was never reached");
        }
        return report("UNDECIDED: abnormal completion " + d.kind());
    }

    private Report report(String verdict) {
        List<String> t = new ArrayList<>(trace);
        if (elided > 0) {
            t.add("… " + elided + " event(s) elided (raise -Dhermit.emu.trace) …");
        }
        t.addAll(traceTail);
        return new Report(verdict + (unknowns > 0 ? "  (" + unknowns + " unknowns on the path)" : ""),
                t, unknowns);
    }

    /** Field initializers up the SOURCE supertype chain (root first); @Mock fields become mocks. */
    private void initFields(TypeHierarchy.TypeEntry entry, ObjVal self) {
        List<TypeHierarchy.TypeEntry> chain = new ArrayList<>();
        for (TypeHierarchy.TypeEntry e = entry; e != null && e.decl() != null; ) {
            chain.add(0, e);
            TypeHierarchy.TypeEntry next = null;
            for (TypeHierarchy.TypeEntry s : e.supertypes()) {
                if (s.decl() != null && !s.isInterface()) {
                    next = s;
                    break;
                }
            }
            e = next;
        }
        for (TypeHierarchy.TypeEntry e : chain) {
            Node body = e.decl().child(NodeType.CLASS_BODY);
            if (body == null) {
                continue;
            }
            Frame init = new Frame(self);
            for (Node fd : body.children(NodeType.FIELD_DECLARATION)) {
                boolean isStatic = hasToken(fd, TokenType.STATIC);
                for (Node d : fd.children(NodeType.VAR_DECLARATOR)) {
                    if (d.name() == null) {
                        continue;
                    }
                    List<Node> kids = d.children();
                    if (isStatic && !kids.isEmpty()) {
                        // A JVM runs <clinit> ONCE per class. Evaluating a static initializer on every `new`
                        // made a generated builder's `DEFAULT = newBuilder().build()` construct another instance
                        // each time, recursing to the depth cap and exhausting the step budget on values no test
                        // reads (bug report #2 item 3). Initialized statics belong to the lazy, cycle-guarded
                        // path in fieldValue(), which runs them at most once and only when something reads them.
                        continue;
                    }
                    Object value;
                    if (!kids.isEmpty()) {
                        value = eval(kids.get(kids.size() - 1), init);
                    } else if (isMockAnnotated(fd)) {
                        value = new MockVal(declaredTypeName(fd));
                        event("@Mock field " + d.name() + " = " + value);
                    } else {
                        value = defaultFor(declaredTypeName(fd));
                    }
                    if (isStatic) {
                        statics.computeIfAbsent(e.fqn(), k -> new HashMap<>()).put(d.name(), value);
                    } else {
                        self.fields.put(d.name(), value);
                    }
                }
            }
        }
    }

    private void runLifecycle(TypeHierarchy.TypeEntry entry, ObjVal self, String annotation) {
        List<TypeHierarchy.TypeEntry> chain = new ArrayList<>();
        for (TypeHierarchy.TypeEntry e = entry; e != null && e.decl() != null; ) {
            chain.add(0, e);
            TypeHierarchy.TypeEntry next = null;
            for (TypeHierarchy.TypeEntry s : e.supertypes()) {
                if (s.decl() != null && !s.isInterface()) {
                    next = s;
                    break;
                }
            }
            e = next;
        }
        for (TypeHierarchy.TypeEntry e : chain) {
            Node body = e.decl().child(NodeType.CLASS_BODY);
            if (body == null) {
                continue;
            }
            for (Node m : body.children(NodeType.METHOD_DECLARATION)) {
                if (hasAnnotation(m, annotation)) {
                    event("@" + annotation + " " + m.name());
                    execBody(m, new Frame(self));
                }
            }
        }
    }

    // ---- statements ----------------------------------------------------------------------------------------

    private Done execBody(Node methodDecl, Frame frame) {
        Node body = methodDecl.child(NodeType.BLOCK);
        return body == null ? Done.NORMAL : exec(body, frame);
    }

    private Done exec(Node n, Frame frame) {
        if (++steps > stepBudget) {
            throw new Budget();
        }
        switch (n.type()) {
            case BLOCK -> {
                for (Node c : n.children()) {
                    if (c.type().isStatement() || c.type().isTypeDeclaration()) {
                        Done d = exec(c, frame);
                        if (!"normal".equals(d.kind())) {
                            return d;
                        }
                    }
                }
                return Done.NORMAL;
            }
            case VAR_DECLARATION -> {
                for (Node d : n.children(NodeType.VAR_DECLARATOR)) {
                    Symbol s = declaratorSymbol(d);
                    List<Node> kids = d.children();
                    Object v = kids.isEmpty() ? NULL : eval(kids.get(kids.size() - 1), frame);
                    if (s != null) {
                        frame.locals.put(s, v);
                    }
                }
                return Done.NORMAL;
            }
            case EXPRESSION_STATEMENT -> {
                for (Node c : n.children()) {
                    eval(c, frame);
                }
                return Done.NORMAL;
            }
            case IF -> {
                Node cond = null;
                for (Node c : n.children()) {
                    if (c.type() != NodeType.THEN && c.type() != NodeType.ELSE) {
                        cond = c;
                        break;
                    }
                }
                Object v = cond == null ? unknown("if without condition") : eval(cond, frame);
                Boolean b = asBoolean(v);
                if (b == null) {
                    unknown("branch condition at " + pos(n) + " undecidable — skipping both arms");
                    if (containsTransfer(n.child(NodeType.THEN)) || containsTransfer(n.child(NodeType.ELSE))) {
                        frame.skippedTransfer = true;   // one of the skipped arms returns or throws
                    }
                    return Done.NORMAL;
                }
                Node untaken = n.child(b ? NodeType.ELSE : NodeType.THEN);
                if (untaken != null && throwsAssertionError(untaken)) {
                    heldAssertions++;
                    event("assertion guard holds at " + pos(n));
                }
                Node arm = n.child(b ? NodeType.THEN : NodeType.ELSE);
                if (arm != null) {
                    for (Node c : arm.children()) {
                        if (c.type().isStatement()) {
                            Done d = exec(c, frame);
                            if (!"normal".equals(d.kind())) {
                                return d;
                            }
                        }
                    }
                }
                return Done.NORMAL;
            }
            case RETURN -> {
                Object v = NULL;
                for (Node c : n.children()) {
                    v = eval(c, frame);
                }
                return new Done("return", v);
            }
            case THROW -> {
                Object v = n.children().isEmpty() ? NULL : eval(n.children().get(0), frame);
                EmuThrow t = toThrow(v, n);
                // A source-level assertion failure (fixture helpers throw AssertionError) is a REAL
                // red only on a fully-decided path; with unknowns the guarded throw the helper
                // expected may simply have been skipped - same honesty rule as F65's verify.
                if (unknowns > 0 && t.typeName != null
                        && (t.typeName.endsWith("AssertionError") || t.typeName.endsWith("AssertionFailedError"))) {
                    undecidableAssertions++;
                    event("assertion throw " + t.typeName + " at " + pos(n) + " under " + unknowns
                            + " unknown(s) — UNDECIDED, not a confirmed failure");
                    return Done.NORMAL;
                }
                throw t;
            }
            case TRY -> {
                return execTry(n, frame);
            }
            case WHILE, DO, FOR -> {
                return execLoop(n, frame);
            }
            case FOR_EACH -> {
                return execForEach(n, frame);
            }
            case SWITCH, SWITCH_FUNCTION -> {
                Done d = execSwitch(n, frame);
                // an arm's expression body yields a value - discarded in statement position
                return "yield".equals(d.kind()) ? Done.NORMAL : d;
            }
            case YIELD -> {
                Object v = NULL;
                for (Node c : n.children()) {
                    v = eval(c, frame);
                }
                return new Done("yield", v);
            }
            case EMPTY_STATEMENT, ASSERT, BREAK, CONTINUE -> {
                if (n.type() == NodeType.BREAK) {
                    return new Done("break", null);
                }
                if (n.type() == NodeType.CONTINUE) {
                    return new Done("continue", null);
                }
                return Done.NORMAL;
            }
            default -> {
                if (n.type().isStatement()) {
                    unknown("statement " + n.type() + " at " + pos(n) + " not interpreted");
                }
                return Done.NORMAL;
            }
        }
    }

    private Done execTry(Node n, Frame frame) {
        Done result;
        try {
            Node block = n.child(NodeType.BLOCK);
            result = block == null ? Done.NORMAL : exec(block, frame);
        } catch (EmuThrow t) {
            for (Node c : n.children(NodeType.CATCH)) {
                for (Node ct : c.children(NodeType.TYPE)) {
                    TypeHierarchy.TypeEntry catchType = resolveType(ct);
                    boolean matches = catchType != null && t.type != null
                            ? hierarchy.isSubtypeOf(t.type, catchType)
                            : ct.name() != null && (ct.name().equals(t.typeName)
                                    || "Exception".equals(ct.name()) || "Throwable".equals(ct.name()));
                    if (matches) {
                        event("caught " + t.typeName + " at " + pos(c));
                        Frame cf = frame;
                        Symbol binder = catchBinder(c);
                        if (binder != null) {
                            cf.locals.put(binder, t.value == null ? NULL : t.value);
                        }
                        Node cb = c.child(NodeType.BLOCK);
                        Done d = cb == null ? Done.NORMAL : exec(cb, cf);
                        runFinally(n, frame);
                        return d;
                    }
                }
            }
            runFinally(n, frame);
            throw t;
        }
        runFinally(n, frame);
        return result;
    }

    private void runFinally(Node tryNode, Frame frame) {
        Node fin = tryNode.child(NodeType.FINALLY);
        if (fin != null) {
            Node fb = fin.child(NodeType.BLOCK);
            if (fb != null) {
                exec(fb, frame);
            }
        }
    }

    private Done execLoop(Node n, Frame frame) {
        // v1: interpret only loops whose condition stays concrete; bound iterations
        for (int i = 0; i < 1000; i++) {
            Node cond = loopCondition(n);
            Boolean b = cond == null ? null : asBoolean(eval(cond, frame));
            if (b == null) {
                unknown("loop at " + pos(n) + " not interpreted (condition undecidable)");
                return Done.NORMAL;
            }
            if (!b) {
                return Done.NORMAL;
            }
            Node body = n.child(NodeType.BLOCK);
            if (body == null) {
                return Done.NORMAL;
            }
            Done d = exec(body, frame);
            if ("break".equals(d.kind())) {
                return Done.NORMAL;
            }
            if ("return".equals(d.kind())) {
                return d;
            }
            for (Node c : n.children()) {   // basic-for updates: expression children after the block
                if (c.type() == NodeType.EXPRESSION_STATEMENT) {
                    exec(c, frame);
                }
            }
        }
        unknown("loop at " + pos(n) + " exceeded 1000 iterations");
        return Done.NORMAL;
    }

    private Done execForEach(Node n, Frame frame) {
        Node iterExpr = loopCondition(n);   // the first non-declaration expression child
        Object it = iterExpr == null ? null : eval(iterExpr, frame);
        List<Object> items = elementsOf(it);
        if (items == null) {
            unknown("for-each at " + pos(n) + " not interpreted (iterable undecidable)");
            return Done.NORMAL;
        }
        Symbol binder = forEachBinder(n);
        Node body = n.child(NodeType.BLOCK);
        if (body == null) {
            List<Node> kids = n.children();
            Node last = kids.isEmpty() ? null : kids.get(kids.size() - 1);
            body = last != null && last.type().isStatement() ? last : null;
        }
        event("for-each at " + pos(n) + " over " + items.size() + " element(s)");
        for (Object v : items) {
            if (binder != null) {
                frame.locals.put(binder, v);
            }
            if (body == null) {
                continue;
            }
            Done d = exec(body, frame);
            if ("break".equals(d.kind())) {
                return Done.NORMAL;
            }
            if ("return".equals(d.kind()) || "yield".equals(d.kind())) {
                return d;
            }
        }
        return Done.NORMAL;
    }

    /** The elements of an interpreted iterable, or null if undecidable. */
    private List<Object> elementsOf(Object it) {
        if (it instanceof ArrayVal a) {
            return a.elements();
        }
        if (it instanceof Concrete c) {
            Object v = c.v();
            if (v instanceof Collection<?> col) {
                List<Object> out = new ArrayList<>();
                for (Object x : col) {
                    out.add(concretize(x));
                }
                return out;
            }
            if (v != null && v.getClass().isArray()) {
                int len = Array.getLength(v);
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < len; i++) {
                    out.add(concretize(Array.get(v, i)));
                }
                return out;
            }
        }
        return null;
    }

    private Symbol forEachBinder(Node forEach) {
        Node decl = forEach.child(NodeType.VAR_DECLARATION);
        if (decl != null) {
            for (Node d : decl.children(NodeType.VAR_DECLARATOR)) {
                Symbol sym = declaratorSymbol(d);
                if (sym != null) {
                    return sym;
                }
            }
        }
        return null;
    }

    private Node loopCondition(Node loop) {
        for (Node c : loop.children()) {
            if (!c.type().isStatement() && c.type() != NodeType.BLOCK
                    && c.type() != NodeType.VAR_DECLARATION) {
                return c;
            }
        }
        return null;
    }

    // ---- switches (see docs/design-emulator.md; the F68 interpreter) -----------------------------------------

    /** One case group: labels before the {@code ->}/{@code :} operator, body after; default has none. */
    private record Arm(boolean isDefault, boolean arrow, List<Node> labels, List<Node> body) {}

    private List<Arm> armsOf(Node switchNode) {
        List<Arm> out = new ArrayList<>();
        for (Node c : switchNode.children()) {
            if (c.type() != NodeType.CASE) {
                continue;
            }
            boolean isDefault = c.token() != null && "default".equals(c.token().text());
            int op = -1;
            boolean arrow = false;
            for (Token t : c.tokens()) {
                if (t.type() == TokenType.ARROW) {
                    op = t.startOffset();
                    arrow = true;
                    break;
                }
                if (t.type() == TokenType.COLON) {
                    op = t.startOffset();
                    break;
                }
            }
            List<Node> labels = new ArrayList<>();
            List<Node> body = new ArrayList<>();
            for (Node k : c.children()) {
                Token first = k.firstCodeToken();
                if (first != null && op >= 0 && first.startOffset() < op) {
                    labels.add(k);
                } else {
                    body.add(k);
                }
            }
            out.add(new Arm(isDefault, arrow, labels, body));
        }
        return out;
    }

    /** Does this case label select the (concrete) value? Enum constants match by NAME. */
    private boolean labelMatches(Node label, Object sel, Frame frame) {
        if (sel instanceof Concrete c && c.v() instanceof EnumConst e
                && (label.type() == NodeType.IDENTIFIER_EXPRESSION
                        || label.type() == NodeType.FIELD_ACCESS)) {
            return e.name().equals(label.name());
        }
        if (label.type() == NodeType.LITERAL) {
            Boolean eq = referenceEquals(literal(label), sel);
            return eq != null && eq;
        }
        if (label.type() == NodeType.IDENTIFIER_EXPRESSION || label.type() == NodeType.FIELD_ACCESS) {
            // a constant reference (static final, static import) - evaluate and compare
            Boolean eq = referenceEquals(eval(label, frame), sel);
            return eq != null && eq;
        }
        return false;   // patterns / null labels - not interpreted (v1)
    }

    /**
     * Execute a switch (statement or expression form). Returns NORMAL, a propagated completion, or
     * {@code yield} carrying the selected arm's value (for expression position). A selector the
     * emulation cannot make concrete degrades to a logged skip - never a hard stop.
     */
    private Done execSwitch(Node n, Frame frame) {
        Node selNode = null;
        for (Node c : n.children()) {
            if (c.type() != NodeType.CASE) {
                selNode = c;
                break;
            }
        }
        Object sel = selNode == null ? NULL : eval(selNode, frame);
        if (!(sel instanceof Concrete)) {
            unknown("switch selector at " + pos(n) + " undecidable — skipping the switch");
            if (containsTransfer(n)) {
                frame.skippedTransfer = true;   // F322
            }
            return Done.NORMAL;
        }
        List<Arm> arms = armsOf(n);
        int match = -1;
        int def = -1;
        outer:
        for (int i = 0; i < arms.size(); i++) {
            Arm a = arms.get(i);
            if (a.isDefault()) {
                def = i;
                continue;
            }
            for (Node l : a.labels()) {
                if (labelMatches(l, sel, frame)) {
                    match = i;
                    break outer;
                }
            }
        }
        if (match < 0) {
            match = def;
        }
        if (match < 0) {
            return Done.NORMAL;   // no matching case, no default - the statement is a no-op
        }
        Arm arm = arms.get(match);
        event("switch at " + pos(n) + " takes " + (arm.isDefault() ? "default" : render(sel)));
        if (arm.arrow()) {
            return execArmBody(arm, frame);
        }
        // colon form: FALLTHROUGH from the matched group until a break (or an abrupt completion)
        for (int i = match; i < arms.size(); i++) {
            for (Node st : arms.get(i).body()) {
                Done d = st.type().isStatement() || st.type() == NodeType.BLOCK
                        ? exec(st, frame) : new Done("normal", eval(st, frame));
                if ("break".equals(d.kind())) {
                    return Done.NORMAL;
                }
                if (!"normal".equals(d.kind())) {
                    return d;
                }
            }
        }
        return Done.NORMAL;
    }

    /** An arrow arm's body: an expression yields its value; a block runs (its `yield` carries out). */
    private Done execArmBody(Arm arm, Frame frame) {
        for (Node b : arm.body()) {
            if (b.type() == NodeType.BLOCK || b.type().isStatement()) {
                Done d = exec(b, frame);
                if ("break".equals(d.kind())) {
                    return Done.NORMAL;
                }
                if (!"normal".equals(d.kind())) {
                    return d;
                }
            } else {
                return new Done("yield", eval(b, frame));
            }
        }
        return Done.NORMAL;
    }

    /** A switch in EXPRESSION position: the selected arm's value, or Unknown when undecidable. */
    private Object evalSwitch(Node n, Frame frame) {
        Done d = execSwitch(n, frame);
        if ("yield".equals(d.kind())) {
            return d.value() == null ? NULL : d.value();
        }
        return unknown("switch expression at " + pos(n) + " produced no value");
    }

    // ---- expressions ---------------------------------------------------------------------------------------

    Object eval(Node n, Frame frame) {
        if (++steps > stepBudget) {
            throw new Budget();
        }
        switch (n.type()) {
            case LITERAL -> {
                return literal(n);
            }
            case PARENTHESIZED -> {
                Object v = NULL;
                for (Node c : n.children()) {
                    v = eval(c, frame);
                }
                return v;
            }
            case THIS_EXPRESSION -> {
                return frame.self == null ? unknown("this outside an instance") : frame.self;
            }
            case IDENTIFIER_EXPRESSION -> {
                return identifier(n, frame);
            }
            case FIELD_ACCESS -> {
                return fieldAccess(n, frame);
            }
            case ASSIGNMENT -> {
                return assignment(n, frame);
            }
            case BINARY_EXPRESSION -> {
                return binary(n, frame);
            }
            case UNARY_EXPRESSION -> {
                Object v = n.children().isEmpty() ? NULL : eval(n.children().get(0), frame);
                String op = n.token() == null ? "" : n.token().text();
                if (v instanceof Concrete c) {
                    if ("!".equals(op) && c.v() instanceof Boolean b) {
                        return new Concrete(!b);
                    }
                    if ("-".equals(op) && c.v() instanceof Long l) {
                        return new Concrete(-l);
                    }
                    if ("-".equals(op) && c.v() instanceof Double d) {
                        return new Concrete(-d);
                    }
                }
                return unknown("unary " + op + " on " + render(v));
            }
            case TERNARY -> {
                List<Node> kids = n.children();
                if (kids.size() == 3) {
                    Boolean b = asBoolean(eval(kids.get(0), frame));
                    if (b != null) {
                        return eval(kids.get(b ? 1 : 2), frame);
                    }
                }
                return unknown("ternary at " + pos(n) + " undecidable");
            }
            case CAST -> {
                Object v = NULL;
                for (Node c : n.children()) {
                    if (c.type() != NodeType.TYPE) {
                        v = eval(c, frame);
                    }
                }
                return v;
            }
            case METHOD_CALL -> {
                return call(n, frame);
            }
            case NEW_INSTANCE -> {
                return newInstance(n, frame);
            }
            case LAMBDA, METHOD_REFERENCE -> {
                return new LambdaVal(n, frame);
            }
            case CLASS_LITERAL -> {
                for (Node c : n.children()) {
                    if (c.name() != null) {
                        Symbol ts = scopeOf(n) == null ? null : scopeOf(n).resolveType(c.name());
                        return new ClassVal(c.name(), entryOfSymbol(ts));
                    }
                }
                return new ClassVal("?", null);
            }
            case SWITCH_FUNCTION, SWITCH -> {
                return evalSwitch(n, frame);
            }
            case NEW_ARRAY -> {
                Node init = n.child(NodeType.ARRAY_INITIALIZER);
                if (init != null) {
                    return eval(init, frame);
                }
                return unknown("new array at " + pos(n) + " without initializer");
            }
            case ARRAY_INITIALIZER -> {
                List<Object> items = new ArrayList<>();
                for (Node c : n.children()) {
                    if (!c.type().isStatement()) {
                        items.add(eval(c, frame));
                    }
                }
                return new ArrayVal(List.copyOf(items));
            }
            case ARRAY_ACCESS -> {
                Object arr = eval(n.children().get(0), frame);
                Object idx = n.children().size() > 1 ? eval(n.children().get(1), frame) : null;
                if (arr instanceof ArrayVal a && idx instanceof Concrete ci && ci.v() instanceof Number num) {
                    int i = num.intValue();
                    if (i >= 0 && i < a.elements().size()) {
                        return a.elements().get(i);
                    }
                }
                return unknown("array access at " + pos(n) + " undecidable");
            }
            case INSTANCEOF -> {
                Object v = eval(n.children().get(0), frame);
                Node second = n.children().size() > 1 ? n.children().get(1) : null;
                if (second != null && second.type() == NodeType.RECORD_PATTERN) {
                    return unknown("record pattern at " + pos(n) + " not interpreted");
                }
                Node typeNode = second != null && second.type() == NodeType.TYPE_PATTERN
                        ? second.child(NodeType.TYPE)
                        : second;
                Boolean is = instanceOf(v, typeNode);
                if (is == null) {
                    return unknown("instanceof at " + pos(n) + " undecidable");
                }
                if (is && second != null && second.type() == NodeType.TYPE_PATTERN) {
                    Symbol binder = patternBinder(second);
                    if (binder != null) {
                        frame.locals.put(binder, v);
                    }
                }
                event("instanceof at " + pos(n) + " → " + is);
                return new Concrete(is);
            }
            default -> {
                return unknown("expression " + n.type() + " at " + pos(n) + " not interpreted");
            }
        }
    }

    private Object literal(Node n) {
        Token t = n.token();
        if (t == null) {
            return NULL;
        }
        String s = t.text();
        try {
            if ("null".equals(s)) {
                return NULL;
            }
            if ("true".equals(s) || "false".equals(s)) {
                return new Concrete(Boolean.parseBoolean(s));
            }
            if (s.startsWith("\"")) {
                return new Concrete(s.substring(1, s.length() - 1)
                        .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\"));
            }
            if (s.startsWith("'")) {
                return new Concrete((long) s.charAt(1));
            }
            String num = s.replace("_", "");
            if (num.endsWith("L") || num.endsWith("l")) {
                return new Concrete(Long.parseLong(num.substring(0, num.length() - 1)));
            }
            if (num.contains(".") || num.endsWith("d") || num.endsWith("D")
                    || num.endsWith("f") || num.endsWith("F")) {
                return new Concrete(Double.parseDouble(num.replaceAll("[dDfF]$", "")));
            }
            if (num.startsWith("0x") || num.startsWith("0X")) {
                return new Concrete(Long.parseLong(num.substring(2), 16));
            }
            return new Concrete(Long.parseLong(num));
        } catch (Exception e) {
            return unknown("literal '" + s + "'");
        }
    }

    private Object identifier(Node n, Frame frame) {
        String name = n.name();
        if (name == null || scopeOf(n) == null || n.token() == null) {
            return unknown("unresolvable identifier");
        }
        Symbol s = scopeOf(n).resolveVariable(name, n.token().startOffset());
        if (s != null) {
            if (frame.locals.containsKey(s)) {
                return frame.locals.get(s);
            }
            Node decl = s.declaration();
            if (decl != null && decl.parent() != null
                    && decl.parent().type() == NodeType.FIELD_DECLARATION) {
                return fieldValue(decl, frame);
            }
            if (frame.self != null && frame.self.fields.containsKey(name)) {
                return frame.self.fields.get(name);
            }
            if (decl != null && decl.type() == NodeType.ENUM_CONSTANT) {
                Node owner = enclosingType(decl);
                return new Concrete(new EnumConst(
                        owner == null || owner.name() == null ? "?" : owner.name(), name));
            }
            Object constant = staticImportConstant(n, name);
            if (constant != null) {
                return constant;
            }
            return unknown("variable '" + name + "' has no tracked value");
        }
        Symbol t = scopeOf(n).resolveType(name);
        if (t != null) {
            return new ClassVal(name, entryOfSymbol(t));
        }
        Object constant = staticImportConstant(n, name);
        if (constant != null) {
            return constant;
        }
        return unknown("identifier '" + name + "'");
    }

    /** An ALL_CAPS static-imported constant: the real field where loadable, a named constant else. */
    private Object staticImportConstant(Node n, String name) {
        String owner = scopeOf(n) == null ? null : scopeOf(n).staticImportOwnerOf(name);
        if (owner == null || !name.matches("[A-Z][A-Z0-9_]*")) {
            return null;
        }
        try {
            Class<?> c = Class.forName(owner, false, getClass().getClassLoader());
            if (whitelisted(c)) {
                return concretize(c.getField(name).get(null));
            }
        } catch (Throwable ignored) {
            // not loadable - fall through to the named-constant abstraction
        }
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        return new Concrete(new EnumConst(simple, name));
    }

    /** Classes whose static initializers have already been run in this emulation - a JVM runs {@code <clinit>}
     *  once, and re-entering it must see the in-progress (default) values, not recurse. */
    private final Set<String> staticsInitialized = new HashSet<>();

    private Object fieldValue(Node declarator, Frame frame) {
        Node fd = declarator.parent();
        String name = declarator.name();
        if (hasToken(fd, TokenType.STATIC)) {
            Node type = enclosingType(fd);
            TypeHierarchy.TypeEntry e = type == null ? null : hierarchy.entryOf(type);
            if (e != null) {
                Map<String, Object> env = statics.computeIfAbsent(e.fqn(), k -> new HashMap<>());
                if (staticsInitialized.add(e.fqn())) {
                    // GENERATED classes (protobuf codegen) hold DEFAULT-instance statics whose initializers
                    // cascade through dozens of other generated ctors - eager init burns the whole
                    // depth/step budget on values no test asserts. They are evaluated LAZILY,
                    // one field per first read, below.
                    if (!isGenerated(fd)) {
                        Frame init = new Frame(null);
                        init.enclosing = enclosingType(fd) == null ? null : hierarchy.entryOf(enclosingType(fd));   // F325
                        for (Node d2 : fd.parent().children(NodeType.FIELD_DECLARATION)) {
                            if (hasToken(d2, TokenType.STATIC)) {
                                for (Node dd : d2.children(NodeType.VAR_DECLARATOR)) {
                                    List<Node> kids = dd.children();
                                    if (dd.name() != null && !kids.isEmpty()) {
                                        env.put(dd.name(), eval(kids.get(kids.size() - 1), init));
                                    }
                                }
                            }
                        }
                    }
                }
                if (isGenerated(fd) && !env.containsKey(name)) {
                    for (Node d2 : fd.parent().children(NodeType.FIELD_DECLARATION)) {
                        if (!hasToken(d2, TokenType.STATIC)) {
                            continue;
                        }
                        for (Node dd : d2.children(NodeType.VAR_DECLARATOR)) {
                            List<Node> kids = dd.children();
                            if (name.equals(dd.name()) && !kids.isEmpty()) {
                                env.put(name, unknown("static '" + name + "' (init cycle)"));
                                Frame init2 = new Frame(null);
                                init2.enclosing = enclosingType(fd) == null ? null : hierarchy.entryOf(enclosingType(fd));   // F325
                                env.put(name, eval(kids.get(kids.size() - 1), init2));
                            }
                        }
                    }
                }
                Object v = env.get(name);
                if (v != null) {
                    return v;
                }
            }
            return unknown("static field '" + name + "'");
        }
        if (frame.self != null && frame.self.fields.containsKey(name)) {
            return frame.self.fields.get(name);
        }
        return unknown("field '" + name + "' has no tracked value");
    }

    private Object fieldAccess(Node n, Frame frame) {
        String name = n.name();
        Node recvNode = n.children().isEmpty() ? null : n.children().get(0);
        if (recvNode == null || name == null) {
            return unknown("field access at " + pos(n));
        }
        // A fully-qualified reference (generated code writes `com.acme.pkg.Codec.FIELD` and
        // `new com.acme.pkg.Codec()`) is a TYPE, not a variable followed by field reads: the old walk emitted
        // "? identifier 'com'" and a field-of-unknown chain for every segment (bug report #2 item 4).
        TypeHierarchy.TypeEntry qualified = qualifiedTypeOf(recvNode);
        Object recv = qualified != null
                ? new ClassVal(qualified.name(), qualified)
                : eval(recvNode, frame);
        if (recv instanceof ArrayVal av && "length".equals(name)) {
            return concretize(av.elements().size());
        }
        if (recv instanceof ObjVal o) {
            return o.fields.getOrDefault(name, unknown("field '" + name + "' of " + o));
        }
        if (recv instanceof ClassVal cv) {
            // Type.CONSTANT: enum constant or a whitelisted static
            TypeHierarchy.TypeEntry e = cv.entry() != null ? cv.entry() : firstEntry(cv.typeName());
            if (e != null && e.clazz() != null) {
                try {
                    Field f = e.clazz().getField(name);
                    if (Modifier.isStatic(f.getModifiers())) {
                        Object v = f.get(null);
                        return v != null && v.getClass().isEnum()
                                ? new Concrete(new EnumConst(cv.typeName(), name))
                                : concretize(v);
                        }
                } catch (Throwable ignored) {
                    // fall through
                }
                for (Class<?> nested : e.clazz().getDeclaredClasses()) {
                    if (name.equals(nested.getSimpleName())) {
                        return new ClassVal(name, hierarchy.libraryEntry(nested));
                    }
                }
            }
            if (e != null && e.decl() != null) {
                boolean isEnumConst = e.decl().type() == NodeType.ENUM_DECLARATION;
                if (isEnumConst) {
                    return new Concrete(new EnumConst(cv.typeName(), name));
                }
                Map<String, Object> env = statics.get(e.fqn());
                if (env != null && env.containsKey(name)) {
                    return env.get(name);
                }
                // a SOURCE static read through its type name (`a.b.Codec.VALUE`, `Type.CONSTANT`) goes through the
                // same lazy, cycle-guarded initializer path as an unqualified read (bug report #2 item 4)
                Node staticDecl = staticDeclaratorOf(e.decl(), name);
                if (staticDecl != null) {
                    return fieldValue(staticDecl, new Frame(null));
                }
                Node nested = nestedTypeDecl(e.decl(), name);
                if (nested != null) {
                    return new ClassVal(name, hierarchy.entryOf(nested));
                }
                // the type's source is fully visible and declares no such static, constant or nested type -
                // a made-up constant here decided comparisons; the honest value is unknown
                return unknown("no static '" + name + "' on " + cv.typeName() + " at " + pos(n));
            }
            return new Concrete(new EnumConst(cv.typeName(), name));   // best effort: named constant of a library type
        }
        if (recv instanceof Concrete c && c.v() != null) {
            try {
                Field f = c.v().getClass().getField(name);
                return concretize(f.get(c.v()));
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return unknown("field '" + name + "' of " + render(recv));
    }

    private Object assignment(Node n, Frame frame) {
        List<Node> kids = n.children();
        if (kids.size() < 2) {
            return NULL;
        }
        Object v = eval(kids.get(kids.size() - 1), frame);
        Node lhs = kids.get(0);
        if (lhs.type() == NodeType.IDENTIFIER_EXPRESSION && lhs.name() != null
                && scopeOf(lhs) != null && lhs.token() != null) {
            Symbol s = scopeOf(lhs).resolveVariable(lhs.name(), lhs.token().startOffset());
            if (s != null && (frame.locals.containsKey(s)
                    || s.declaration() == null
                    || s.declaration().parent() == null
                    || s.declaration().parent().type() != NodeType.FIELD_DECLARATION)) {
                frame.locals.put(s, v);
                return v;
            }
            if (frame.self != null) {
                frame.self.fields.put(lhs.name(), v);
                return v;
            }
        }
        if (lhs.type() == NodeType.FIELD_ACCESS && lhs.name() != null && !lhs.children().isEmpty()) {
            Object recv = eval(lhs.children().get(0), frame);
            if (recv instanceof ObjVal o) {
                o.fields.put(lhs.name(), v);
                return v;
            }
        }
        unknown("assignment target at " + pos(n) + " not tracked");
        return v;
    }

    private Object binary(Node n, Frame frame) {
        List<Node> kids = n.children();
        if (kids.size() != 2) {
            return unknown("binary shape at " + pos(n));
        }
        String op = n.token() == null ? "?" : n.token().text();
        Object l = eval(kids.get(0), frame);
        if ("&&".equals(op)) {
            Boolean lb = asBoolean(l);
            if (lb != null && !lb) {
                return new Concrete(false);
            }
            Object r = eval(kids.get(1), frame);
            Boolean rb = asBoolean(r);
            return lb != null && rb != null ? new Concrete(lb && rb)
                    : unknown("&& at " + pos(n));
        }
        if ("||".equals(op)) {
            Boolean lb = asBoolean(l);
            if (lb != null && lb) {
                return new Concrete(true);
            }
            Object r = eval(kids.get(1), frame);
            Boolean rb = asBoolean(r);
            return lb != null && rb != null ? new Concrete(lb || rb)
                    : unknown("|| at " + pos(n));
        }
        Object r = eval(kids.get(1), frame);
        if ("==".equals(op) || "!=".equals(op)) {
            Boolean eq = referenceEquals(l, r);
            if (eq == null) {
                return unknown(op + " at " + pos(n) + " undecidable");
            }
            return new Concrete("==".equals(op) == eq);
        }
        if (l instanceof Concrete cl && r instanceof Concrete cr) {
            Object result = arith(op, cl.v(), cr.v());
            if (result != null) {
                return new Concrete(result);
            }
        }
        return unknown("'" + op + "' on " + render(l) + ", " + render(r));
    }

    private static Object arith(String op, Object a, Object b) {
        if (a instanceof String || b instanceof String) {
            return "+".equals(op) ? String.valueOf(stringify(a)) + stringify(b) : null;
        }
        if (a instanceof Long x && b instanceof Long y) {
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> y == 0 ? null : x / y;
                case "%" -> y == 0 ? null : x % y;
                case ">" -> x > y;
                case "<" -> x < y;
                case ">=" -> x >= y;
                case "<=" -> x <= y;
                default -> null;
            };
        }
        if (a instanceof Number x && b instanceof Number y) {
            double d1 = x.doubleValue();
            double d2 = y.doubleValue();
            return switch (op) {
                case "+" -> d1 + d2;
                case "-" -> d1 - d2;
                case "*" -> d1 * d2;
                case "/" -> d1 / d2;
                case ">" -> d1 > d2;
                case "<" -> d1 < d2;
                case ">=" -> d1 >= d2;
                case "<=" -> d1 <= d2;
                default -> null;
            };
        }
        return null;
    }

    private static Object stringify(Object v) {
        return v instanceof EnumConst e ? e.name() : v;
    }

    /** Null / concrete / enum / sym-identity equality; null when undecidable. */
    /**
     * Equality as {@code assertEquals} means it - {@code equals}, not {@code ==}. Two objects of the same type
     * whose equality is FIELD-BASED (a record, or a class that declares {@code equals}, as generated value types
     * do) compare structurally; anything else falls back to reference equality. Bug report #2 item 2 surfaced
     * this: once the right overload was chosen, two structurally equal Timestamps still compared unequal.
     */
    private Boolean valueEquals(Object l, Object r) {
        if (l instanceof Unknown || r instanceof Unknown) {
            // assertEquals(expectedInfo, <unknown>) was reported as PREDICTED RED ("expected
            // obj:Info but would be unknown(...)") and satisfied an expect=red gate for a test that runs green.
            // An unknown side makes the comparison undecidable, never a failure.
            return null;
        }
        if (l instanceof ObjVal a && r instanceof ObjVal b && a != b) {
            if (a.type == null || b.type == null) {
                return null;
            }
            if (a.type != b.type) {
                return null;   // different types: an equals we cannot see may still say either thing
            }
            if (!fieldBasedEquality(a.type)) {
                return l == r;
            }
            Set<String> names = new LinkedHashSet<>(a.fields.keySet());
            names.addAll(b.fields.keySet());
            boolean allEqual = true;
            for (String f : names) {
                Boolean eq = valueEquals(a.fields.get(f) == null ? NULL : a.fields.get(f),
                        b.fields.get(f) == null ? NULL : b.fields.get(f));
                if (eq == null) {
                    return null;   // an unknown field makes the comparison undecidable, never false
                }
                allEqual &= eq;
            }
            return allEqual;
        }
        return referenceEquals(l, r);
    }

    /** Does this type derive equality from its fields - a record, or a class that declares {@code equals}? */
    private boolean fieldBasedEquality(TypeHierarchy.TypeEntry e) {
        if (e.decl() == null) {
            return false;
        }
        if (e.decl().type() == NodeType.RECORD_DECLARATION) {
            return true;
        }
        for (TypeHierarchy.MethodDecl d : hierarchy.allDeclaredMethods(e)) {
            if ("equals".equals(d.name()) && d.params() == 1) {
                return true;
            }
        }
        return false;
    }

    private Boolean referenceEquals(Object l, Object r) {
        if (l == r) {
            return true;
        }
        boolean ln = l == NULL;
        boolean rn = r == NULL;
        if (ln || rn) {
            if (l instanceof Unknown || r instanceof Unknown) {
                return null;
            }
            return ln && rn;
        }
        if (l instanceof Concrete cl && r instanceof Concrete cr) {
            return Objects.equals(cl.v(), cr.v());
        }
        if (l instanceof Sym && r instanceof Sym) {
            return l.equals(r);
        }
        if (l instanceof MockVal || r instanceof MockVal
                || l instanceof ObjVal || r instanceof ObjVal) {
            return l == r;
        }
        return null;
    }

    private Boolean asBoolean(Object v) {
        return v instanceof Concrete c && c.v() instanceof Boolean b ? b : null;
    }

    // ---- calls ---------------------------------------------------------------------------------------------

    private Object call(Node n, Frame frame) {
        Token pr = n.token();
        if (pr == null) {
            return unknown("call shape at " + pos(n));
        }
        String name = pr.text();
        Node recvNode = null;
        List<Node> argNodes = new ArrayList<>();
        for (Node c : n.children()) {
            Token first = c.firstToken();
            if (first == null || c.type() == NodeType.TYPE) {
                continue;
            }
            if (first.startOffset() < pr.startOffset()) {
                recvNode = c;
            } else {
                argNodes.add(c);
            }
        }

        // ---- Mockito & assertion statics (recognized before evaluation where order matters) ----
        Object mockito = mockitoStatic(name, recvNode, argNodes, frame, n);
        if (mockito != SKIP) {
            return mockito;
        }

        Object recv = recvNode == null ? null : eval(recvNode, frame);
        List<Object> args = new ArrayList<>();
        for (Node a : argNodes) {
            args.add(eval(a, frame));
        }

        // `this(...)` / `super(...)` as the first statement of a constructor body
        if (recvNode == null && frame.self != null && ("this".equals(name) || "super".equals(name))) {
            return explicitConstructorInvocation(name, args, argNodes, frame, n);
        }

        // stubbing/verify chains
        if (recv instanceof StubbingVal sv) {
            return stubbingChain(sv, name, args, argNodes, frame);
        }
        if (recv instanceof VerifyVal vv) {
            return verifyInvocation(vv, name, args, n);
        }
        if (recv instanceof CaptorVal cap) {
            if ("capture".equals(name)) {
                return new MatcherVal("captor", cap);
            }
            if ("getValue".equals(name)) {
                if (cap.captured.isEmpty()) {
                    return unknown("captor.getValue() with nothing captured");
                }
                return cap.captured.get(cap.captured.size() - 1);
            }
            if ("getAllValues".equals(name)) {
                return new Concrete(new ArrayList<>(cap.captured));
            }
        }
        if (recv instanceof ConfigVal cfg) {
            return configCall(cfg, name, args, n);
        }
        if (recv instanceof MockVal mock) {
            return mockInvocation(mock, name, args, n);
        }
        if (recv instanceof LambdaVal lam) {
            return invokeLambda(lam, args);
        }
        if (recv instanceof OneOfVal oneOf) {
            return switch (name) {
                case "kind" -> oneOf.kind();
                case "value", "as" -> oneOf.value();
                default -> unknown("call '" + name + "' on OneOf at " + pos(n));
            };
        }
        if (recv instanceof ThrownAssertVal ta) {
            return assertJThrowChain(ta, name, args, n);
        }
        if (recv instanceof Concrete ce && ce.v() instanceof EnumConst ec
                && ("name".equals(name) || "toString".equals(name)) && args.isEmpty()) {
            return new Concrete(ec.name());
        }
        if (recv instanceof Concrete cev && cev.v() instanceof EmuExceptionValue ev
                && ("getMessage".equals(name) || "getLocalizedMessage".equals(name)) && args.isEmpty()) {
            return ev.message();
        }
        if (recv instanceof Concrete && "equals".equals(name) && args.size() == 1) {
            Boolean eq = referenceEquals(recv, args.get(0));
            if (eq != null) {
                return new Concrete(eq);
            }
        }

        // assertions
        Object assertion = assertion(name, args, n);
        if (assertion != SKIP) {
            return assertion;
        }

        // common static-imported utilities (unqualified): identity/null-guard pass-throughs
        if (recv == null) {
            switch (name) {
                case "requireNonNull", "checkNotNull" -> {
                    if (!args.isEmpty()) {
                        return args.get(0);
                    }
                }
                case "requireNonNullElse" -> {
                    if (args.size() == 2) {
                        return args.get(0) == NULL ? args.get(1) : args.get(0);
                    }
                }
                case "emptyList", "emptyIterator", "emptyMap", "emptySet" -> {
                    return concretize(switch (name) {
                        case "emptyList" -> Collections.emptyList();
                        case "emptyIterator" -> Collections.emptyIterator();
                        case "emptyMap" -> Collections.emptyMap();
                        default -> Collections.emptySet();
                    });
                }
                default -> { /* fall through */ }
            }
        }
        // source dispatch: on the VALUE's type when known, else the graph's single candidate
        if (recv instanceof ObjVal o && o.type != null) {
            TypeHierarchy.MethodDecl d = hierarchy.resolveCall(o.type, name, args.size(), argTypeNames(args));
            if (d != null && d.decl() != null) {
                if (d.decl().type() == NodeType.RECORD_COMPONENT) {
                    return o.fields.getOrDefault(name, unknown("record component '" + name + "'"));
                }
                return invokeSource(d.decl(), o, args, n);
            }
        }
        if (recv == null && frame.self != null && frame.self.type != null) {
            TypeHierarchy.MethodDecl d =
                    hierarchy.resolveCall(frame.self.type, name, args.size(), argTypeNames(args));
            if (d != null && d.decl() != null) {
                return invokeSource(d.decl(), frame.self, args, n);
            }
        }
        if (recv == null && frame.self == null && frame.enclosing != null) {
            // a static initializer has no `self`; an unqualified call in it is a
            // static method of the enclosing type (`DEFAULT = newBuilder().build()`)
            TypeHierarchy.MethodDecl d = hierarchy.resolveCall(frame.enclosing, name, args.size(), argTypeNames(args));
            if (d != null && d.decl() != null) {
                return invokeSource(d.decl(), null, args, n);
            }
        }
        if (recv == null) {
            // static-imported helper (e.g. a fixtures assertion): resolve through the file's imports
            Node target = staticImportTarget(n, name, args.size());
            if (target != null) {
                return invokeSource(target, null, args, n);
            }
        }
        if (recv instanceof ClassVal cv) {
            if ("create".equals(name) && cv.typeName().endsWith("ConfigBuilder")) {
                event("config builder " + cv.typeName());
                return new ConfigVal();
            }
            TypeHierarchy.TypeEntry e = cv.entry() != null ? cv.entry() : firstEntry(cv.typeName());
            if (e != null && e.decl() != null) {
                TypeHierarchy.MethodDecl d = hierarchy.resolveCall(e, name, args.size(), argTypeNames(args));
                if (d != null && d.decl() != null) {
                    return invokeSource(d.decl(), null, args, n);
                }
            }
            Object reflected = reflectStatic(e, name, args, n);
            if (reflected != SKIP) {
                return reflected;
            }
        }

        // concrete JDK receiver: reflective model over the whitelist
        if (recv instanceof Concrete c && c.v() != null) {
            Object reflected = reflectVirtual(c.v(), name, args, n);
            if (reflected != SKIP) {
                return reflected;
            }
        }

        if (recv == NULL && definitelyNull(recvNode, frame)) {
            // Invoking anything on null throws - modelling it as "unknown" left every
            // assertThatThrownBy(() -> new X(null)) undecidable although that IS the asserted behaviour
            // (the acceptance case of bug report #2 item 3). Only a null the SOURCE puts there counts: a
            // field the emulator merely failed to model is unknown, not null, and must not throw.
            throw npe("call '" + name + "' on null at " + pos(n), n);
        }
        return unknown("call '" + name + "' at " + pos(n) + " on " + render(recv));
    }

    /**
     * Is this receiver expression null BY THE SOURCE's doing - a {@code null} literal, or a parameter/local the
     * caller passed null? A field or an unmodelled call result that merely holds the emulator's default is NOT:
     * treating those as null produced a false THROW on a fixture field the emulator had not populated.
     */
    private boolean definitelyNull(Node recvNode, Frame frame) {
        if (recvNode == null) {
            return false;
        }
        String text = recvNode.toString().strip();
        if (text.equals("null")) {
            return true;
        }
        if (recvNode.type() != NodeType.IDENTIFIER_EXPRESSION || recvNode.name() == null) {
            return false;
        }
        // a local/parameter binding in THIS frame that holds null (fields live on the object, not the frame)
        if (frame == null || scopeOf(recvNode) == null || recvNode.token() == null) {
            return false;
        }
        Symbol sym = scopeOf(recvNode).resolveVariable(recvNode.name(), recvNode.token().startOffset());
        return sym != null && frame.nullLiterals.contains(sym) && frame.locals.get(sym) == NULL;
    }

    /** The argument expression nodes of a call/new site, in order. */
    private static List<Node> argumentNodes(Node site) {
        List<Node> out = new ArrayList<>();
        Token pr = site.token();
        for (Node c : site.children()) {
            Token first = c.firstToken();
            if (first != null && (pr == null || first.startOffset() > pr.endOffset())
                    && c.type() != NodeType.CLASS_BODY && c.type() != NodeType.TYPE) {
                out.add(c);
            }
        }
        return out;
    }

    /** A modelled {@link NullPointerException} carrying {@code why} as its message. */
    private EmuThrow npe(String why, Node site) {
        Class<?> c = NullPointerException.class;
        TypeHierarchy.TypeEntry type = hierarchy.libraryEntry(c);
        Object value = new Concrete(new EmuExceptionValue("NullPointerException", type, new Concrete(why)));
        event("throw NullPointerException at " + pos(site) + " (" + why + ")");
        return new EmuThrow(value, "NullPointerException", type);
    }

    private static final Object SKIP = new Object();

    private Object mockitoStatic(String name, Node recvNode, List<Node> argNodes, Frame frame, Node site) {
        boolean unqualifiedOrMockito = recvNode == null
                || (recvNode.name() != null
                        && recvNode.name().matches("Mockito|BDDMockito|ArgumentCaptor|lenient"));
        if (!unqualifiedOrMockito) {
            return SKIP;
        }
        switch (name) {
            case "mock" -> {
                String type = argNodes.isEmpty() ? "?" : classArgName(argNodes.get(0), frame);
                MockVal m = new MockVal(type);
                event("mock(" + type + ")");
                return m;
            }
            case "given", "when" -> {
                if (!argNodes.isEmpty()) {
                    eval(argNodes.get(0), frame);   // runs the mock invocation, recording it
                }
                if (lastMockReceiver != null) {
                    // Mockito semantics: the stubbing's inner call is NOT a real invocation
                    lastMockReceiver.invocations.remove(lastMockInvocation);
                    StubbingVal sv = new StubbingVal(lastMockReceiver, lastMockInvocation);
                    lastMockReceiver = null;
                    return sv;
                }
                return unknown("given()/when() without a mock invocation");
            }
            case "verify" -> {
                if (!argNodes.isEmpty()) {
                    Object m = eval(argNodes.get(0), frame);
                    if (m instanceof MockVal mv) {
                        return new VerifyVal(mv);
                    }
                }
                return unknown("verify() of a non-mock");
            }
            case "lenient" -> {
                return new ClassVal("Mockito", null);   // lenient().when(...) - when() resolves as static-ish
            }
            case "forClass" -> {
                return new CaptorVal();
            }
            case "any", "anyLong", "anyInt", "anyString", "anyBoolean", "notNull", "isNull", "isNotNull" -> {
                return new MatcherVal("any", null);
            }
            case "eq" -> {
                return new MatcherVal("eq", argNodes.isEmpty() ? NULL : eval(argNodes.get(0), frame));
            }
            case "withSettings", "extraInterfaces", "verifyNoInteractions", "verifyNoMoreInteractions",
                    "clearInvocations", "reset", "doCallRealMethod" -> {
                return new Concrete("mock-settings");
            }
            default -> {
                return SKIP;
            }
        }
    }

    private Object stubbingChain(StubbingVal sv, String name, List<Object> args, List<Node> argNodes,
            Frame frame) {
        Stub stub = new Stub(sv.invocation());
        switch (name) {
            case "willReturn", "thenReturn" -> stub.returns = args.isEmpty() ? NULL : args.get(0);
            case "willThrow", "thenThrow" -> stub.throwsValue = args.isEmpty() ? NULL : args.get(0);
            case "willAnswer", "thenAnswer" -> {
                if (!args.isEmpty() && args.get(0) instanceof LambdaVal lam) {
                    stub.answer = lam;
                }
            }
            case "willCallRealMethod", "willDoNothing" -> stub.returns = NULL;
            default -> {
                return sv;   // unknown chain link - keep the stubbing alive
            }
        }
        sv.mock().stubs.add(stub);
        event("stub " + sv.mock().typeName + "#" + sv.invocation().method()
                + (stub.throwsValue != null ? " willThrow" : stub.answer != null ? " willAnswer" : " willReturn"));
        return sv;
    }

    private Object verifyInvocation(VerifyVal vv, String name, List<Object> args, Node site) {
        List<Invocation> matches = new ArrayList<>();
        for (Invocation inv : vv.mock().invocations) {
            if (inv.method().equals(name) && inv.args().size() == args.size()) {
                matches.add(inv);
            }
        }
        if (matches.isEmpty()) {
            // A verify that finds no matching call is a REAL red ONLY if we fully evaluated the path.
            // If unknowns forced branches/calls to be skipped, we may simply not have REACHED the call -
            // that is UNDECIDED, not a failure. Confusing the two lets a spurious red satisfy an
            // expectTestOutcome=red gate (masking a test that never exercises the code) - see F65.
            if (unknowns > 0) {
                undecidableAssertions++;
                event("verify " + vv.mock().typeName + "#" + name + " NOT reached under "
                        + unknowns + " unknown(s) — UNDECIDED, not a confirmed failure");
                return NULL;
            }
            failedAssertion = "verify(" + vv.mock().typeName + ")." + name
                    + " — the mock never received that call (at " + pos(site) + ")";
            event("VERIFY FAILS: " + failedAssertion);
            return NULL;
        }
        Invocation matched = matches.get(matches.size() - 1);
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) instanceof MatcherVal m && "captor".equals(m.kind())
                    && m.value() instanceof CaptorVal cap) {
                cap.captured.add(matched.args().get(i));
            }
        }
        heldAssertions++;
        event("verify " + vv.mock().typeName + "#" + name + " ok (" + matches.size() + " invocation(s))");
        return NULL;
    }

    private Object mockInvocation(MockVal mock, String name, List<Object> args, Node site) {
        Invocation inv = new Invocation(name, args);
        boolean stubbingMode = args.stream().anyMatch(a -> a instanceof MatcherVal);
        if (!stubbingMode) {
            mock.invocations.add(inv);
        }
        lastMockInvocation = inv;
        lastMockReceiver = mock;
        // replay: LAST matching stub wins
        for (int i = mock.stubs.size() - 1; i >= 0; i--) {
            Stub stub = mock.stubs.get(i);
            if (stubMatches(stub.pattern, inv)) {
                if (stub.throwsValue != null) {
                    event("stub hit: " + mock.typeName + "#" + name + " throws");
                    throw toThrow(stub.throwsValue, site);
                }
                if (stub.answer != null) {
                    event("stub hit: " + mock.typeName + "#" + name + " answers");
                    return invokeLambda(stub.answer, List.of(new Concrete("invocation")));
                }
                event("stub hit: " + mock.typeName + "#" + name);
                return stub.returns == null ? NULL : stub.returns;
            }
        }
        if (!stubbingMode) {
            event("unstubbed " + mock.typeName + "#" + name + " → default");
        }
        return NULL;   // Mockito default answer
    }

    private boolean stubMatches(Invocation pattern, Invocation actual) {
        if (!pattern.method().equals(actual.method()) || pattern.args().size() != actual.args().size()) {
            return false;
        }
        for (int i = 0; i < pattern.args().size(); i++) {
            Object p = pattern.args().get(i);
            Object a = actual.args().get(i);
            if (p instanceof MatcherVal m) {
                if ("eq".equals(m.kind()) && !Objects.equals(m.value(), a)) {
                    return false;
                }
                continue;   // any/captor match everything
            }
            if (p instanceof Unknown || a instanceof Unknown) {
                continue;   // an unevaluable side matches leniently - prediction over precision
            }
            Boolean eq = referenceEquals(p, a);
            if (eq == null || !eq) {
                if (a instanceof MatcherVal) {
                    continue;   // both sides are stubbing patterns
                }
                return false;
            }
        }
        return true;
    }

    private Object invokeLambda(LambdaVal lam, List<Object> args) {
        Node n = lam.node();
        if (n.type() == NodeType.METHOD_REFERENCE) {
            return unknown("method reference invocation");
        }
        if (depth >= depthCap) {
            return unknown("interpretation depth cap (" + depthCap + ") in a lambda");
        }
        Frame lf = new Frame(lam.frame() == null ? null : lam.frame().self);
        if (lam.frame() != null) {
            lf.locals.putAll(lam.frame().locals);
        }
        List<Node> params = n.children(NodeType.PARAMETER);
        for (int i = 0; i < params.size() && i < args.size(); i++) {
            Symbol s = declaratorSymbol(params.get(i));
            if (s == null && params.get(i).name() != null && scopeOf(params.get(i)) != null) {
                s = scopeOf(params.get(i)).resolveVariable(params.get(i).name(), Integer.MAX_VALUE);
            }
            if (s != null) {
                lf.locals.put(s, args.get(i));
            }
        }
        Node block = n.child(NodeType.BLOCK);
        if (block != null) {
            depth++;
            try {
                Done d = exec(block, lf);
                return "return".equals(d.kind()) ? d.value() : NULL;
            } finally {
                depth--;
            }
        }
        // expression lambda: the body is the child after the arrow (last non-parameter child)
        Node body = null;
        for (Node c : n.children()) {
            if (c.type() != NodeType.PARAMETER && c.type() != NodeType.TYPE) {
                body = c;
            }
        }
        // single-identifier parameters may parse as IDENTIFIER_EXPRESSION before the arrow: bind by position
        if (body != null && params.isEmpty() && !args.isEmpty()) {
            List<Node> idents = n.children(NodeType.IDENTIFIER_EXPRESSION);
            if (idents.size() >= 2 || (idents.size() == 1 && idents.get(0) != body)) {
                Node p0 = idents.get(0);
                if (scopeOf(p0) != null && p0.name() != null && p0.token() != null) {
                    Symbol s = scopeOf(p0).resolveVariable(p0.name(), p0.token().startOffset());
                    if (s != null) {
                        lf.locals.put(s, args.get(0));
                    }
                }
            }
        }
        return body == null ? NULL : eval(body, lf);
    }

    private Object invokeSource(Node methodDecl, ObjVal self, List<Object> args, Node site) {
        if (methodDecl.child(NodeType.BLOCK) == null) {
            return unknown("call to body-less " + specOf(methodDecl) + " (abstract/native)");
        }
        if (depth >= depthCap) {
            return unknown("interpretation depth cap (" + depthCap + ") at " + specOf(methodDecl)
                    + " — raise -Dhermit.emu.depth");
        }
        Frame callee = new Frame(self);
        List<Node> params = methodDecl.children(NodeType.PARAMETER);
        List<Node> argNodes = site == null ? List.of() : argumentNodes(site);
        for (int i = 0; i < params.size(); i++) {
            Symbol s = paramSymbol(methodDecl, params.get(i));
            if (s != null) {
                callee.locals.put(s, i < args.size() ? args.get(i) : NULL);
                if (i < argNodes.size() && "null".equals(argNodes.get(i).toString().strip())) {
                    callee.nullLiterals.add(s);   // the SOURCE passed null here
                }
            }
        }
        event("→ " + specOf(methodDecl));
        depth++;
        try {
            Done d = execBody(methodDecl, callee);
            boolean nonVoid = returnsValue(methodDecl);
            if ("return".equals(d.kind())) {
                if (nonVoid && callee.skippedTransfer) {
                    return unknown("return of " + specOf(methodDecl)
                            + " after a skipped branch that returns or throws — another path may have decided");
                }
                return d.value();
            }
            // a non-void body that completes without a `return` is not "null" -
            // javac guarantees every completing path of a non-void method returns, so reaching the end proves the
            // emulator skipped the deciding branch (a validation method ended in an if/else whose arms
            // were both skipped; the null then decided `== OK` as false and produced a false PREDICTED THROW).
            return nonVoid
                    ? unknown("fell through " + specOf(methodDecl) + " — every path returns, so a deciding branch was skipped")
                    : NULL;
        } finally {
            depth--;
        }
    }

    /** Whether {@code methodDecl} declares a non-void return type (a constructor never does). */
    private static boolean returnsValue(Node methodDecl) {
        if (methodDecl.type() != NodeType.METHOD_DECLARATION) {
            return false;
        }
        Node t = methodDecl.child(NodeType.TYPE);
        return t == null || !"void".equals(t.name());
    }

    /** Does this subtree contain a return or throw statement? */
    private static boolean containsTransfer(Node arm) {
        return arm != null && (!arm.descendants(NodeType.RETURN).isEmpty() || !arm.descendants(NodeType.THROW).isEmpty());
    }

    private Object newInstance(Node n, Frame frame) {
        Node typeNode = n.child(NodeType.TYPE);
        String typeName = typeNode == null ? "?" : typeNode.name();
        List<Object> args = new ArrayList<>();
        Token paren = null;
        for (Token t : n.tokens()) {
            if (t.type() == TokenType.OPENING_ROUND_BRACKET) {
                paren = t;
                break;
            }
        }
        for (Node c : n.children()) {
            Token first = c.firstToken();
            if (first != null && paren != null && first.startOffset() > paren.startOffset()
                    && c.type() != NodeType.CLASS_BODY && c.type() != NodeType.TYPE) {
                args.add(eval(c, frame));
            }
        }
        Node body = n.child(NodeType.CLASS_BODY);
        if (body != null) {
            ObjVal anon = new ObjVal(hierarchy.entryOf(body));
            event("new " + typeName + "() {anonymous}");
            return anon;
        }
        TypeHierarchy.TypeEntry e = typeNode != null && scopeOf(typeNode) != null && typeName != null
                ? entryOfSymbol(scopeOf(typeNode).resolveType(typeName)) : null;
        if (e != null && e.decl() != null) {
            ObjVal obj = new ObjVal(e);
            initFields(e, obj);   // primitive defaults + field initializers up the source chain
            List<Node> components = e.decl().children(NodeType.RECORD_COMPONENT);
            if (!components.isEmpty() && components.size() == args.size()) {
                for (int i = 0; i < components.size(); i++) {
                    if (components.get(i).name() != null) {
                        obj.fields.put(components.get(i).name(), args.get(i));
                    }
                }
                event("new " + typeName + " (record, " + args.size() + " components)");
                return obj;
            }
            Node ctor = constructorFor(e, args);
            if (ctor != null) {
                event("new " + typeName + "(" + args.size() + " args)");
                runConstructor(ctor, obj, args, argumentNodes(n), typeName);
                return obj;
            }
            event("new " + typeName + "() [no interpreted ctor]");
            return obj;
        }
        // library type with concrete args → real construction over the whitelist
        if (e != null && e.clazz() != null && allConcrete(args) && whitelisted(e.clazz())) {
            try {
                for (Constructor<?> ctor : e.clazz().getConstructors()) {
                    if (ctor.getParameterCount() == args.size()) {
                        Object v = ctor.newInstance(unwrap(args));
                        return concretize(v);
                    }
                }
            } catch (Throwable ignored) {
                // fall through
            }
        }
        if (typeName != null && (typeName.endsWith("Exception") || typeName.endsWith("Error"))) {
            TypeHierarchy.TypeEntry ex = firstEntry(typeName);
            Object msg = !args.isEmpty() && args.get(0) instanceof Concrete mc && mc.v() instanceof String
                    ? args.get(0)
                    : NULL;
            return new Concrete(new EmuExceptionValue(typeName, ex, msg));
        }
        if ("OneOf".equals(typeName) && args.size() >= 2) {
            return new OneOfVal(args.get(0), args.get(1));
        }
        return unknown("new " + typeName + " at " + pos(n));
    }

    /**
     * The source constructor of {@code e} for these arguments: the one whose arity matches, and among several of the
     * same arity the first whose declared parameter types accept the argument values.
     */
    private Node constructorFor(TypeHierarchy.TypeEntry e, List<Object> args) {
        Node cb = e.decl() == null ? null : e.decl().child(NodeType.CLASS_BODY);
        if (cb == null) {
            return null;
        }
        List<Node> byArity = new ArrayList<>();
        for (Node ctor : cb.children(NodeType.CONSTRUCTOR_DECLARATION)) {
            if (ctor.children(NodeType.PARAMETER).size() == args.size()) {
                byArity.add(ctor);
            }
        }
        if (byArity.size() <= 1) {
            return byArity.isEmpty() ? null : byArity.get(0);
        }
        for (Node ctor : byArity) {
            List<Node> params = ctor.children(NodeType.PARAMETER);
            boolean ok = true;
            for (int i = 0; i < params.size() && ok; i++) {
                Node t = params.get(i).child(NodeType.TYPE);
                ok = accepts(t == null ? null : t.name(), args.get(i));
            }
            if (ok) {
                return ctor;
            }
        }
        return byArity.get(0);
    }

    /** Can a parameter declared as {@code typeName} take {@code v}? Unknowns and unmodelled values always can. */
    private static boolean accepts(String typeName, Object v) {
        if (typeName == null || v instanceof Unknown || v instanceof ObjVal || v instanceof MockVal) {
            return true;
        }
        if (v == NULL) {
            return !typeName.matches("int|long|short|byte|char|boolean|double|float");
        }
        if (v instanceof Concrete c) {
            Object x = c.v();
            if (x instanceof String) {
                return typeName.matches("String|CharSequence|Object|T|E|K|V");
            }
            if (x instanceof Boolean) {
                return typeName.matches("boolean|Boolean|Object");
            }
            if (x instanceof Number) {
                return typeName.matches("int|long|short|byte|double|float|Integer|Long|Short|Byte|Double|Float|Number|Object");
            }
        }
        return true;
    }

    /** Runs a source constructor body on {@code obj} with {@code args} bound to its parameters (depth-capped). */
    private void runConstructor(Node ctor, ObjVal obj, List<Object> args, List<Node> argNodes, String typeName) {
        Frame cf = new Frame(obj);
        List<Node> params = ctor.children(NodeType.PARAMETER);
        for (int i = 0; i < params.size(); i++) {
            Symbol s = paramSymbol(ctor, params.get(i));
            if (s != null) {
                cf.locals.put(s, i < args.size() ? args.get(i) : NULL);
                if (i < argNodes.size() && "null".equals(argNodes.get(i).toString().strip())) {
                    cf.nullLiterals.add(s);   // `new X(null)` - the source's own null
                }
            }
        }
        if (depth < depthCap) {
            depth++;
            try {
                execBody(ctor, cf);
            } finally {
                depth--;
            }
        } else {
            unknown("ctor depth cap at new " + typeName);
        }
    }

    /**
     * An explicit constructor invocation. {@code this(args)} re-enters constructor selection on the SAME object;
     * {@code super(args)} runs the source superclass's constructor on it. A superclass without source
     * (RuntimeException) is not interpreted and leaves nothing behind: its fields are absent from the object, so
     * reading them stays unknown - never null - and a JDK base can never decide an assertion.
     */
    private Object explicitConstructorInvocation(String name, List<Object> args, List<Node> argNodes, Frame frame,
            Node site) {
        ObjVal self = frame.self;
        TypeHierarchy.TypeEntry target;
        if ("this".equals(name)) {
            target = self.type;
        } else {
            target = sourceSuperclass(self.type);
            if (target == null) {
                event("super(" + args.size() + " args) → " + librarySuperName(self.type)
                        + " [no source — the base class's state stays unknown]");
                return NULL;
            }
        }
        if (target == null || target.decl() == null) {
            return unknown(name + "(" + args.size() + " args) at " + pos(site) + " — no source constructor");
        }
        // a record's canonical constructor is implicit: this(a, b) with one argument per component assigns them
        List<Node> components = target.decl().children(NodeType.RECORD_COMPONENT);
        if (!components.isEmpty() && components.size() == args.size()) {
            for (int i = 0; i < components.size(); i++) {
                if (components.get(i).name() != null) {
                    self.fields.put(components.get(i).name(), args.get(i));
                }
            }
            event(name + "(" + args.size() + " args) → " + target.name() + " (record canonical constructor)");
            return NULL;
        }
        Node ctor = constructorFor(target, args);
        if (ctor == null) {
            return unknown(name + "(" + args.size() + " args) at " + pos(site) + " — no constructor of " + target.name()
                    + " takes " + args.size() + " argument(s)");
        }
        event(name + "(" + args.size() + " args) → " + target.name() + " constructor");
        runConstructor(ctor, self, args, argNodes, target.name());
        return NULL;
    }

    /** The nearest superclass that has source, or null when the chain leaves the indexed sources at once. */
    private static TypeHierarchy.TypeEntry sourceSuperclass(TypeHierarchy.TypeEntry e) {
        if (e == null) {
            return null;
        }
        for (TypeHierarchy.TypeEntry s : e.supertypes()) {
            if (s.decl() != null && !s.isInterface()) {
                return s;
            }
        }
        return null;
    }

    private static String librarySuperName(TypeHierarchy.TypeEntry e) {
        if (e != null) {
            for (TypeHierarchy.TypeEntry s : e.supertypes()) {
                if (!s.isInterface()) {
                    return s.name();
                }
            }
        }
        return "a library type";
    }

    /** A constructed exception value (source or unmodelled library exception). */
    record EmuExceptionValue(String typeName, TypeHierarchy.TypeEntry type, Object message) {}

    /** The fluent config protocol + getConfigData materialization from the config record's SOURCE. */
    private Object configCall(ConfigVal cfg, String name, List<Object> args, Node site) {
        switch (name) {
            case "withValue", "withSource" -> {
                if (args.size() == 2) {
                    cfg.values.put(asString(args.get(0)), asString(args.get(1)));
                }
                return cfg;
            }
            case "getOrCreateConfig", "build", "getConfiguration", "createConfig" -> {
                return cfg;
            }
            case "getConfigData" -> {
                if (!args.isEmpty() && args.get(0) instanceof ClassVal cv) {
                    return configRecord(cfg, cv.typeName(), site);
                }
                return unknown("getConfigData with a non-class argument at " + pos(site));
            }
            case "getValue" -> {
                String raw = args.isEmpty() ? null : cfg.values.get(asString(args.get(0)));
                return raw == null ? unknown("config key at " + pos(site)) : new Concrete(raw);
            }
            default -> {
                event("config builder call '" + name + "' ignored (fluent pass-through)");
                return cfg;
            }
        }
    }

    /** Materialize a config record: @ConfigData prefix + components, values from the map or defaults. */
    private Object configRecord(ConfigVal cfg, String recordName, Node site) {
        TypeHierarchy.TypeEntry e = firstEntry(recordName);
        if (e == null || e.decl() == null) {
            return unknown("config record " + recordName + " not in the analyzed sources");
        }
        String prefix = annotationString(e.decl(), "ConfigData");
        ObjVal obj = new ObjVal(e);
        for (Node comp : e.decl().children(NodeType.RECORD_COMPONENT)) {
            if (comp.name() == null) {
                continue;
            }
            String key = (prefix == null ? "" : prefix + ".") + comp.name();
            String raw = cfg.values.get(key);
            if (raw == null) {
                raw = annotationString(comp, "ConfigProperty");   // the declared defaultValue
            }
            Node typeNode = comp.child(NodeType.TYPE);
            String typeName = typeNode == null ? "" : String.valueOf(typeNode.name());
            obj.fields.put(comp.name(), raw == null
                    ? unknown("config key '" + key + "' has no value or default")
                    : convertConfigValue(raw, typeName));
        }
        event("config record " + recordName + (prefix != null ? " [" + prefix + "]" : ""));
        return obj;
    }

    private Object convertConfigValue(String raw, String typeName) {
        if ("true".equals(raw) || "false".equals(raw)) {
            return new Concrete(Boolean.parseBoolean(raw));
        }
        if (raw.matches("-?\\d+")) {
            return new Concrete(Long.parseLong(raw));
        }
        if (raw.matches("-?\\d*\\.\\d+")) {
            return new Concrete(Double.parseDouble(raw));
        }
        if ("String".equals(typeName) || typeName.isEmpty()) {
            return new Concrete(raw);
        }
        return new Concrete(new EnumConst(typeName, raw));   // enums and typed configs by name
    }

    /** The first string-literal argument of the named annotation on {@code decl}, or null. */
    private static String annotationString(Node decl, String annotationName) {
        for (Node a : decl.children(NodeType.ANNOTATION)) {
            if (annotationName.equals(a.name())) {
                for (Token t : a.tokens()) {
                    String text = t.text();
                    if (text.startsWith("\"")) {
                        return text.substring(1, text.length() - 1);
                    }
                }
                for (Node c : a.children()) {
                    Token first = c.firstCodeToken();
                    if (first != null && first.text().startsWith("\"")) {
                        return first.text().substring(1, first.text().length() - 1);
                    }
                }
            }
        }
        return null;
    }

    private static String asString(Object v) {
        if (v instanceof Concrete c) {
            Object x = c.v();
            return x instanceof EnumConst e ? e.name() : String.valueOf(x);
        }
        return String.valueOf(v);
    }

    // ---- assertions ----------------------------------------------------------------------------------------

    private Object assertion(String name, List<Object> args, Node site) {
        switch (name) {
            case "assertEquals" -> {
                if (args.size() >= 2) {
                    Boolean eq = valueEquals(args.get(0), args.get(1));
                    if (eq == null) {
                        undecidableAssertions++;
                        unknown("assertEquals at " + pos(site) + " undecidable");
                    } else if (!eq) {
                        failedAssertion = "assertEquals at " + pos(site) + " — expected "
                                + render(args.get(0)) + " but would be " + render(args.get(1));
                        event("ASSERTION FAILS: " + failedAssertion);
                    } else {
                        heldAssertions++;
                        event("assertEquals holds at " + pos(site));
                    }
                }
                return NULL;
            }
            case "assertNotEquals" -> {
                // unmodelled - a test made only of assertNotEquals reported "no
                // assertions were evaluated"
                if (args.size() >= 2) {
                    Boolean eq = valueEquals(args.get(0), args.get(1));
                    if (eq == null) {
                        undecidableAssertions++;
                        unknown("assertNotEquals at " + pos(site) + " undecidable");
                    } else if (eq) {
                        failedAssertion = "assertNotEquals at " + pos(site) + " — both would be " + render(args.get(0));
                        event("ASSERTION FAILS: " + failedAssertion);
                    } else {
                        heldAssertions++;
                        event("assertNotEquals holds at " + pos(site));
                    }
                }
                return NULL;
            }
            case "assertSame", "assertNotSame" -> {
                if (args.size() >= 2) {
                    Boolean same = referenceEquals(args.get(0), args.get(1));
                    boolean want = "assertSame".equals(name);
                    if (same == null) {
                        undecidableAssertions++;
                        unknown(name + " at " + pos(site) + " undecidable");
                    } else if (same != want) {
                        failedAssertion = name + " at " + pos(site) + " — " + render(args.get(0)) + (same ? " is " : " is not ")
                                + "the same instance as " + render(args.get(1));
                        event("ASSERTION FAILS: " + failedAssertion);
                    } else {
                        heldAssertions++;
                        event(name + " holds at " + pos(site));
                    }
                }
                return NULL;
            }
            case "assertTrue", "assertFalse" -> {
                Boolean b = args.isEmpty() ? null : asBoolean(args.get(0));
                boolean want = "assertTrue".equals(name);
                if (b == null) {
                    undecidableAssertions++;
                    unknown(name + " at " + pos(site) + " undecidable");
                } else if (b != want) {
                    failedAssertion = name + " at " + pos(site) + " — condition is " + b;
                    event("ASSERTION FAILS: " + failedAssertion);
                } else {
                    heldAssertions++;
                    event(name + " holds at " + pos(site));
                }
                return NULL;
            }
            case "assertDoesNotThrow" -> {
                if (args.isEmpty() || !(args.get(0) instanceof LambdaVal lam)) {
                    return SKIP;
                }
                try {
                    Object r = invokeLambda(lam, List.of());
                    heldAssertions++;
                    event("assertDoesNotThrow holds at " + pos(site));
                    return r;
                } catch (EmuThrow t) {
                    failedAssertion = "assertDoesNotThrow at " + pos(site) + " — " + t.typeName + " thrown";
                    event("ASSERTION FAILS: " + failedAssertion);
                    return NULL;
                }
            }
            case "assertThrows", "assertThrowsExactly" -> {
                if (args.size() < 2 || !(args.get(1) instanceof LambdaVal lam)) {
                    return SKIP;
                }
                int before = unknowns;
                try {
                    invokeLambda(lam, List.of());
                    // No interpreted throw. Only a fully-decided path proves "nothing thrown".
                    if (unknowns > before) {
                        undecidableAssertions++;
                        return unknown(name + " at " + pos(site)
                                + " undecidable — nothing thrown but the path has unknowns");
                    }
                    failedAssertion = name + " at " + pos(site) + " — nothing thrown";
                    event("ASSERTION FAILS: " + failedAssertion);
                    return NULL;
                } catch (EmuThrow t) {
                    Boolean match = throwMatches(t, args.get(0));
                    if (match == null) {
                        undecidableAssertions++;
                        unknown(name + " at " + pos(site) + " — thrown " + t.typeName
                                + " vs expected type undecidable");
                    } else if (!match) {
                        failedAssertion = name + " at " + pos(site) + " — " + t.typeName + " thrown instead";
                        event("ASSERTION FAILS: " + failedAssertion);
                    } else {
                        heldAssertions++;
                        event(name + " holds at " + pos(site) + " (" + t.typeName + ")");
                    }
                    return t.value == null ? NULL : t.value;
                }
            }
            case "assertThatThrownBy" -> {
                if (args.isEmpty() || !(args.get(0) instanceof LambdaVal lam)) {
                    return SKIP;
                }
                int before = unknowns;
                try {
                    invokeLambda(lam, List.of());
                    if (unknowns > before) {
                        undecidableAssertions++;
                        return unknown("assertThatThrownBy at " + pos(site)
                                + " undecidable — nothing thrown but the path has unknowns");
                    }
                    failedAssertion = "assertThatThrownBy at " + pos(site) + " — nothing thrown";
                    event("ASSERTION FAILS: " + failedAssertion);
                    return new ThrownAssertVal(null, true);
                } catch (EmuThrow t) {
                    event("assertThatThrownBy at " + pos(site) + " caught " + t.typeName);
                    return new ThrownAssertVal(t, unknowns == before);
                }
            }
            case "assertThatCode" -> {
                if (args.isEmpty() || !(args.get(0) instanceof LambdaVal lam)) {
                    return SKIP;
                }
                int before = unknowns;
                try {
                    invokeLambda(lam, List.of());
                    return new ThrownAssertVal(null, unknowns == before);
                } catch (EmuThrow t) {
                    return new ThrownAssertVal(t, unknowns == before);
                }
            }
            case "fail" -> {
                failedAssertion = "fail(" + (args.isEmpty() ? "" : render(args.get(0))) + ") reached at " + pos(site);
                event("ASSERTION FAILS: " + failedAssertion);
                return NULL;
            }
            case "assertNull", "assertNotNull" -> {
                boolean isNull = !args.isEmpty() && args.get(0) == NULL;
                boolean want = "assertNull".equals(name);
                if (!args.isEmpty() && (args.get(0) instanceof Unknown)) {
                    unknown(name + " at " + pos(site) + " undecidable");
                } else if (isNull != want) {
                    failedAssertion = name + " at " + pos(site);
                    event("ASSERTION FAILS: " + failedAssertion);
                } else {
                    heldAssertions++;
                    event(name + " holds at " + pos(site));
                }
                return NULL;
            }
            default -> {
                return SKIP;
            }
        }
    }

    // ---- reflective JDK modeling -----------------------------------------------------------------------------

    /** Pure in-memory JDK value types only: nothing that can reach the environment (files,
     *  processes, threads, class loading, prefs/logging sinks) may execute for real. */
    private static boolean whitelisted(Class<?> c) {
        return whitelistedName(c.getName());
    }

    /**
     * One rule of the whitelist: {@code allow} or {@code deny} a class name, or {@code skip-method} a method name,
     * when its pattern finds a match in it. The first class rule that matches decides; a name no rule matches is
     * denied.
     */
    record Rule(String kind, Pattern pattern) {}

    /** The bundled rules: the pure in-memory JDK value packages, and the calls that read the clock or chance. */
    private static final String DEFAULT_RULES = "/emu/whitelist.rules";

    private static volatile List<Rule> rulesInForce;

    /**
     * The rules in force: those of the files {@code -Dhermit.emu.rules} names (comma-separated, in order), then the
     * bundled ones. Bug report #2 item 5: a library's in-memory byte-buffer value type (jar-only) was unknown, so
     * every generated builder's byte comparison was undecidable (866 unknowns, 1221 skipped branch arms in one run).
     * Its types were admitted by name in this class; a project names its library value types (or denies a JDK one)
     * in its own rules file instead.
     */
    static List<Rule> rules() {
        List<Rule> r = rulesInForce;
        if (r == null) {
            r = loadRules(System.getProperty("hermit.emu.rules", ""));
            rulesInForce = r;
        }
        return r;
    }

    /** The rules of the named files (comma-separated, in order), then the bundled ones. */
    static List<Rule> loadRules(String paths) {
        List<Rule> out = new ArrayList<>();
        for (String p : paths.split(",")) {
            String file = p.strip();
            if (!file.isEmpty()) {
                try {
                    out.addAll(parseRules(Files.readString(Path.of(file)), file));
                } catch (IOException e) {
                    throw new UncheckedIOException("emulator rules " + file + ": " + e.getMessage(), e);
                }
            }
        }
        out.addAll(defaultRules());
        return List.copyOf(out);
    }

    static List<Rule> defaultRules() {
        try (InputStream in = Interpreter.class.getResourceAsStream(DEFAULT_RULES)) {
            if (in == null) {
                throw new IllegalStateException("the bundled emulator rules are missing: " + DEFAULT_RULES);
            }
            return parseRules(new String(in.readAllBytes(), StandardCharsets.UTF_8), DEFAULT_RULES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One rule per line, {@code allow|deny|skip-method <regex>}; blank lines and '#' lines are skipped. */
    static List<Rule> parseRules(String text, String source) {
        List<Rule> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            String where = source + ":" + (i + 1) + ": ";
            if (parts.length < 2 || !Set.of("allow", "deny", "skip-method").contains(parts[0])) {
                throw new IllegalArgumentException(where + "expected 'allow', 'deny' or 'skip-method' and a pattern, "
                        + "found '" + line + "'");
            }
            try {
                out.add(new Rule(parts[0], Pattern.compile(parts[1])));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(where + e.getDescription() + " in '" + parts[1] + "'", e);
            }
        }
        return out;
    }

    /** The whitelist as a NAME policy, so it can be tested without the class on the classpath. */
    static boolean whitelistedName(String name) {
        return whitelistedName(name, rules());
    }

    static boolean whitelistedName(String name, List<Rule> rules) {
        for (Rule r : rules) {
            if (!r.kind().equals("skip-method") && r.pattern().matcher(name).find()) {
                return r.kind().equals("allow");
            }
        }
        return false;
    }

    /** A call whose result would depend on the clock or chance is never run for real, on any class. */
    static boolean skippedMethod(String name, List<Rule> rules) {
        for (Rule r : rules) {
            if (r.kind().equals("skip-method") && r.pattern().matcher(name).find()) {
                return true;
            }
        }
        return false;
    }

    private Object reflectVirtual(Object recv, String name, List<Object> args, Node site) {
        if (!whitelisted(recv.getClass()) || skippedMethod(name, rules())) {
            return SKIP;
        }
        try {
            for (Method m : recv.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.size()
                        && !Modifier.isStatic(m.getModifiers()) && compatible(m, args)) {
                    Method callable = publicVariant(m);
                    if (callable != null) {
                        return concretize(callable.invoke(recv, unwrapFor(callable, args)));
                    }
                }
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new EmuThrow(new Concrete(new EmuExceptionValue(cause.getClass().getSimpleName(),
                    hierarchy.libraryEntry(cause.getClass()),
                    cause.getMessage() == null ? NULL : new Concrete(cause.getMessage()))),
                    cause.getClass().getSimpleName(), hierarchy.libraryEntry(cause.getClass()));
        } catch (Throwable ignored) {
            // fall through to unknown
        }
        return SKIP;
    }

    private Object reflectStatic(TypeHierarchy.TypeEntry e, String name, List<Object> args, Node site) {
        if (e == null || e.clazz() == null || !whitelisted(e.clazz()) || skippedMethod(name, rules())) {
            // pass-through models for null-guards on non-concrete values
            if (("requireNonNull".equals(name) || "requireNonNullElse".equals(name)) && !args.isEmpty()) {
                return args.get(0);
            }
            return SKIP;
        }
        try {
            for (Method m : e.clazz().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.size()
                        && Modifier.isStatic(m.getModifiers()) && compatible(m, args)) {
                    return concretize(m.invoke(null, unwrapFor(m, args)));
                }
            }
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new EmuThrow(new Concrete(new EmuExceptionValue(cause.getClass().getSimpleName(),
                    hierarchy.libraryEntry(cause.getClass()),
                    cause.getMessage() == null ? NULL : new Concrete(cause.getMessage()))),
                    cause.getClass().getSimpleName(), hierarchy.libraryEntry(cause.getClass()));
        } catch (Throwable ignored) {
            // fall through
        }
        return SKIP;
    }

    /** The same method on a PUBLIC declaring type (package-private impls like List.of()'s reject access). */
    private static Method publicVariant(Method m) {
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
            return m;
        }
        for (Class<?> c = m.getDeclaringClass(); c != null; c = c.getSuperclass()) {
            if (Modifier.isPublic(c.getModifiers())) {
                try {
                    return c.getMethod(m.getName(), m.getParameterTypes());
                } catch (NoSuchMethodException ignored) {
                    // keep looking
                }
            }
            for (Class<?> i : c.getInterfaces()) {
                try {
                    return i.getMethod(m.getName(), m.getParameterTypes());
                } catch (NoSuchMethodException ignored) {
                    // keep looking
                }
            }
        }
        return null;
    }

    private static boolean allConcrete(List<Object> args) {
        return args.stream().allMatch(a -> a instanceof Concrete || a == NULL);
    }

    /**
     * Can the reflective method take these emulator values? Concrete values must fit the declared
     * parameter (boxing included); DOMAIN values (mocks, source objects) ride through ONLY as
     * {@code Object}-typed parameters (generic erasure - collections, iterators), never where the
     * library would actually inspect them.
     */
    private static boolean compatible(Method m, List<Object> args) {
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            Object a = args.get(i);
            Class<?> p = params[i];
            if (a == NULL) {
                if (p.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (a instanceof Concrete c) {
                if (!fits(p, c.v())) {
                    return false;
                }
                continue;
            }
            if (a instanceof Unknown) {
                return false;
            }
            if (p != Object.class) {
                return false;   // a domain value where the library expects something real
            }
        }
        return true;
    }

    private static boolean fits(Class<?> p, Object v) {
        if (v == null) {
            return !p.isPrimitive();
        }
        if (p.isInstance(v)) {
            return true;
        }
        if (p == Object.class) {
            return true;
        }
        if ((p == long.class || p == Long.class || p == int.class || p == Integer.class
                || p == short.class || p == byte.class) && v instanceof Long) {
            return true;
        }
        if ((p == double.class || p == Double.class || p == float.class) && v instanceof Number) {
            return true;
        }
        if ((p == boolean.class || p == Boolean.class) && v instanceof Boolean) {
            return true;
        }
        if ((p == char.class || p == Character.class) && v instanceof Long) {
            return true;
        }
        return false;
    }

    private static Object[] unwrap(List<Object> args) {
        Object[] out = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            Object a = args.get(i);
            out[i] = a == NULL ? null : a instanceof Concrete c ? c.v() : a;
        }
        return out;
    }

    /** Unwrap + numeric narrowing so a Long literal fits an int/short/char parameter. */
    private static Object[] unwrapFor(Method m, List<Object> args) {
        Object[] out = unwrap(args);
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (out[i] instanceof Long l) {
                Class<?> p = params[i];
                if (p == int.class || p == Integer.class) {
                    out[i] = l.intValue();
                } else if (p == short.class || p == Short.class) {
                    out[i] = l.shortValue();
                } else if (p == byte.class || p == Byte.class) {
                    out[i] = l.byteValue();
                } else if (p == char.class || p == Character.class) {
                    out[i] = (char) l.longValue();
                } else if (p == double.class || p == Double.class) {
                    out[i] = l.doubleValue();
                } else if (p == float.class || p == Float.class) {
                    out[i] = l.floatValue();
                }
            } else if (out[i] instanceof Double d && (params[i] == float.class || params[i] == Float.class)) {
                out[i] = d.floatValue();
            }
        }
        return out;
    }

    private Object concretize(Object v) {
        if (v == null) {
            return NULL;
        }
        if (v instanceof MockVal || v instanceof ObjVal || v instanceof Unknown || v instanceof Sym
                || v instanceof CaptorVal || v instanceof LambdaVal || v instanceof Concrete
                || v instanceof ConfigVal || v instanceof ClassVal || v instanceof VerifyVal || v instanceof OneOfVal
                || v instanceof ArrayVal || v instanceof ThrownAssertVal
                || v instanceof StubbingVal || v instanceof MatcherVal) {
            return v;   // emulator-domain values ride through JDK calls and collections unchanged
        }
        if (v instanceof Integer i) {
            return new Concrete((long) i);
        }
        if (v instanceof Float f) {
            return new Concrete((double) f);
        }
        if (v.getClass().isEnum()) {
            return new Concrete(new EnumConst(v.getClass().getSimpleName(), ((Enum<?>) v).name()));
        }
        return new Concrete(v);
    }

    // ---- misc ----------------------------------------------------------------------------------------------

    private EmuThrow toThrow(Object v, Node site) {
        String typeName = "?";
        TypeHierarchy.TypeEntry type = null;
        if (v instanceof Concrete c && c.v() instanceof EmuExceptionValue ev) {
            typeName = ev.typeName();
            type = ev.type();
        } else if (v instanceof Concrete c && c.v() instanceof Throwable t) {
            typeName = t.getClass().getSimpleName();
            type = hierarchy.libraryEntry(t.getClass());
        } else if (v instanceof ObjVal o && o.type != null) {
            typeName = o.type.name();
            type = o.type;
        }
        event("throw " + typeName + " at " + pos(site));
        return new EmuThrow(v, typeName, type);
    }

    private String classArgName(Node argNode, Frame frame) {
        Object v = eval(argNode, frame);
        if (v instanceof ClassVal cv) {
            return cv.typeName();
        }
        return argNode.name() == null ? render(v) : argNode.name();
    }

    private Symbol catchBinder(Node catchNode) {
        for (Token t : catchNode.tokens()) {
            if (t.type() == TokenType.IDENTIFIER) {
                var scope = ownScopeOf(catchNode);
                if (scope != null) {
                    return scope.resolveVariable(t.text(), Integer.MAX_VALUE);
                }
            }
        }
        return null;
    }

    private Symbol declaratorSymbol(Node d) {
        if (d.name() == null || scopeOf(d) == null || d.token() == null) {
            return null;
        }
        return scopeOf(d).resolveVariable(d.name(), d.token().startOffset());
    }

    private Symbol paramSymbol(Node methodDecl, Node param) {
        var scope = ownScopeOf(methodDecl);
        if (scope == null || param.name() == null) {
            return null;
        }
        return scope.resolveVariable(param.name(), Integer.MAX_VALUE);
    }

    private TypeHierarchy.TypeEntry resolveType(Node typeNode) {
        if (typeNode.name() == null || scopeOf(typeNode) == null) {
            return null;
        }
        return entryOfSymbol(scopeOf(typeNode).resolveType(typeNode.name()));
    }

    private TypeHierarchy.TypeEntry entryOfSymbol(Symbol s) {
        if (s == null) {
            return null;
        }
        if (s.declaration() != null) {
            return hierarchy.entryOf(s.declaration());
        }
        if (s.clazz() != null) {
            return hierarchy.libraryEntry(s.clazz());
        }
        return null;
    }

    /** Does the arm contain a top-level {@code throw new *AssertionError*} or {@code fail(...)}? */
    private static boolean throwsAssertionError(Node arm) {
        for (Node c : arm.children()) {
            Node stmt = c;
            if (stmt.type() == NodeType.BLOCK) {
                return throwsAssertionError(stmt);
            }
            if (stmt.type() == NodeType.THROW) {
                for (Token t : subtreeTokens(stmt)) {
                    String x = t.text();
                    if (t.type() == TokenType.IDENTIFIER
                            && (x.endsWith("AssertionError") || x.endsWith("AssertionFailedError"))) {
                        return true;
                    }
                }
            }
            if (stmt.type() == NodeType.EXPRESSION_STATEMENT) {
                for (Node e : stmt.children()) {
                    if (e.type() == NodeType.METHOD_CALL && "fail".equals(e.name())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<Token> subtreeTokens(Node n) {
        List<Token> out = new ArrayList<>(n.tokens());
        for (Node c : n.children()) {
            out.addAll(subtreeTokens(c));
        }
        return out;
    }

    /** Resolve an unqualified call through the file's static imports to a source method decl. */
    private Node staticImportTarget(Node site, String name, int argCount) {
        Node root = site;
        while (root.parent() != null) {
            root = root.parent();
        }
        for (Node im : root.children(NodeType.IMPORT_DECLARATION)) {
            if (!hasToken(im, TokenType.STATIC)) {
                continue;
            }
            List<String> segs = new ArrayList<>();
            boolean wildcard = false;
            for (Token t : im.tokens()) {
                if (t.type() == TokenType.IDENTIFIER) {
                    segs.add(t.text());
                } else if (t.type() == TokenType.STAR) {
                    wildcard = true;
                }
            }
            String typeSimple;
            String typeFqn;
            if (wildcard) {
                if (segs.isEmpty()) {
                    continue;
                }
                typeSimple = segs.get(segs.size() - 1);
                typeFqn = String.join(".", segs);
            } else {
                if (segs.size() < 2 || !segs.get(segs.size() - 1).equals(name)) {
                    continue;
                }
                typeSimple = segs.get(segs.size() - 2);
                typeFqn = String.join(".", segs.subList(0, segs.size() - 1));
            }
            for (TypeHierarchy.TypeEntry e : hierarchy.entries(typeSimple)) {
                if (e.decl() == null || !typeFqn.equals(e.fqn())) {
                    continue;
                }
                TypeHierarchy.MethodDecl d = hierarchy.resolveCall(e, name, argCount);
                if (d != null && d.decl() != null) {
                    return d.decl();
                }
            }
        }
        return null;
    }

    /** The declarator of a STATIC field {@code name} declared directly in {@code typeDecl}, or null. */
    private static Node staticDeclaratorOf(Node typeDecl, String name) {
        Node body = typeDecl.type() == NodeType.CLASS_BODY ? typeDecl : typeDecl.child(NodeType.CLASS_BODY);
        if (body == null) {
            return null;
        }
        for (Node fd : body.children(NodeType.FIELD_DECLARATION)) {
            if (!hasToken(fd, TokenType.STATIC)) {
                continue;
            }
            for (Node d : fd.children(NodeType.VAR_DECLARATOR)) {
                if (name.equals(d.name())) {
                    return d;
                }
            }
        }
        return null;
    }

    /** The type a pure dotted NAME chain denotes ({@code com.acme.pkg.Codec}), or null when it is not one. */
    private TypeHierarchy.TypeEntry qualifiedTypeOf(Node chain) {
        String dotted = dottedNameOf(chain);
        if (dotted == null || !dotted.contains(".")) {
            return null;
        }
        // a local variable of the leading name wins (`config.value.x` is field access, not a type)
        String head = dotted.substring(0, dotted.indexOf('.'));
        if (scopeOf(chain) != null && chain.token() != null
                && scopeOf(chain).resolveVariable(head, chain.token().startOffset()) != null) {
            return null;
        }
        TypeHierarchy.TypeEntry byFqn = firstEntry(dotted);
        if (byFqn != null) {
            return byFqn;
        }
        // progressively shorter prefixes: com.acme.pkg.Outer.Inner → the longest that names a type, then the REST
        // of the segments must be nested types of it. F326 (emulator bug report #3, item E): `Message.ContentOneOfType
        // .TEXT` resolved the prefix `Message` and dropped `.ContentOneOfType`, so the constant was read as
        // `Message.TEXT` and compared unequal to `ContentOneOfType.TEXT` - a decisive false verdict.
        for (int cut = dotted.lastIndexOf('.'); cut > 0; cut = dotted.lastIndexOf('.', cut - 1)) {
            TypeHierarchy.TypeEntry e = firstEntry(dotted.substring(0, cut));
            if (e != null) {
                for (String seg : dotted.substring(cut + 1).split("\\.")) {
                    TypeHierarchy.TypeEntry inner = nestedTypeEntry(e, seg);
                    if (inner == null) {
                        return null;   // the rest is not a type path - leave it to field access on the prefix
                    }
                    e = inner;
                }
                return e;
            }
        }
        return null;
    }

    /** The nested type {@code name} of {@code outer} (source or library), else null. */
    private TypeHierarchy.TypeEntry nestedTypeEntry(TypeHierarchy.TypeEntry outer, String name) {
        if (outer.decl() != null) {
            Node nested = nestedTypeDecl(outer.decl(), name);
            return nested == null ? null : hierarchy.entryOf(nested);
        }
        if (outer.clazz() != null) {
            for (Class<?> nested : outer.clazz().getDeclaredClasses()) {
                if (name.equals(nested.getSimpleName())) {
                    return hierarchy.libraryEntry(nested);
                }
            }
        }
        return null;
    }

    /** {@code a.b.C} for a chain of plain names/field accesses, else null. */
    private static String dottedNameOf(Node n) {
        if (n.type() == NodeType.IDENTIFIER_EXPRESSION) {
            return n.name();
        }
        if (n.type() != NodeType.FIELD_ACCESS || n.name() == null || n.children().isEmpty()) {
            return null;
        }
        String prefix = dottedNameOf(n.children().get(0));
        return prefix == null ? null : prefix + "." + n.name();
    }

    private TypeHierarchy.TypeEntry firstEntry(String name) {
        List<TypeHierarchy.TypeEntry> es = hierarchy.entries(name);
        return es.isEmpty() ? null : es.get(0);
    }

    private static boolean hasToken(Node n, TokenType type) {
        for (Token t : n.tokens()) {
            if (t.type() == type) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMockAnnotated(Node fieldDecl) {
        for (Node a : fieldDecl.children(NodeType.ANNOTATION)) {
            if ("Mock".equals(a.name()) || "MockBean".equals(a.name())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotation(Node decl, String name) {
        for (Node a : decl.children(NodeType.ANNOTATION)) {
            if (name.equals(a.name())) {
                return true;
            }
        }
        return false;
    }

    private static Object defaultFor(String typeName) {
        return switch (typeName == null ? "" : typeName) {
            case "boolean" -> new Concrete(false);
            case "int", "long", "short", "byte", "char" -> new Concrete(0L);
            case "double", "float" -> new Concrete(0.0);
            default -> NULL;
        };
    }

    private static String declaredTypeName(Node fieldDecl) {
        Node t = fieldDecl.child(NodeType.TYPE);
        return t == null || t.name() == null ? "?" : t.name();
    }

    private static Node enclosingType(Node n) {
        for (Node p = n.parent(); p != null; p = p.parent()) {
            if (p.type().isTypeDeclaration()) {
                return p;
            }
        }
        return null;
    }

    private String specOf(Node methodDecl) {
        Node t = enclosingType(methodDecl);
        return (t == null || t.name() == null ? "?" : t.name()) + "#"
                + (methodDecl.name() == null ? "<init>" : methodDecl.name());
    }

    private Object unknown(String reason) {
        unknowns++;
        event("? " + reason);
        return new Unknown(reason);
    }

    private void event(String s) {
        if (trace.size() < traceCap / 2) {
            trace.add(s);
            return;
        }
        if (traceTail.size() >= traceCap - traceCap / 2) {
            traceTail.pollFirst();
            elided++;
        }
        traceTail.addLast(s);
    }

    /** Decide {@code v instanceof typeNode}; null = can't tell. */
    private Boolean instanceOf(Object v, Node typeNode) {
        if (typeNode == null) {
            return null;
        }
        if (v == NULL) {
            return false;
        }
        TypeHierarchy.TypeEntry target = resolveType(typeNode);
        String targetName = typeNode.name();
        TypeHierarchy.TypeEntry vt = null;
        String vName = null;
        if (v instanceof ObjVal o) {
            vt = o.type;
            vName = o.type == null ? null : o.type.name();
        } else if (v instanceof MockVal m) {
            vt = firstEntry(m.typeName);   // a mock of X is an X (proxy subclass)
            vName = m.typeName;
        } else if (v instanceof OneOfVal) {
            vName = "OneOf";
        } else if (v instanceof Concrete c) {
            Object x = c.v();
            if (x == null) {
                return false;
            }
            if (x instanceof EnumConst ec) {
                vt = firstEntry(ec.type());
                vName = ec.type();
            } else if (x instanceof EmuExceptionValue ev) {
                vt = ev.type();
                vName = ev.typeName();
            } else {
                if (target != null && target.clazz() != null) {
                    return target.clazz().isInstance(x);
                }
                vt = hierarchy.libraryEntry(x.getClass());
                vName = x.getClass().getSimpleName();
            }
        } else {
            return null;   // Unknown, Sym, lambdas, …
        }
        if (vt != null && target != null) {
            return hierarchy.isSubtypeOf(vt, target);
        }
        if (vName != null && vName.equals(targetName)) {
            return true;   // name-identical; a supertype relation we can't see stays null below
        }
        return null;
    }

    /** The pattern variable declared by a TYPE_PATTERN (its principal token). */
    private Symbol patternBinder(Node pattern) {
        Token t = pattern.token();
        if (t == null) {
            return null;
        }
        var scope = ownScopeOf(pattern) != null ? ownScopeOf(pattern) : scopeOf(pattern);
        return scope == null ? null : scope.resolveVariable(t.text(), Integer.MAX_VALUE);
    }

    /** A member type declaration named {@code name} inside {@code decl} (Outer.Nested access). */
    private static Node nestedTypeDecl(Node decl, String name) {
        for (Node c : decl.children()) {
            if (isTypeDecl(c) && name.equals(c.name())) {
                return c;
            }
            for (Node cc : c.children()) {
                if (isTypeDecl(cc) && name.equals(cc.name())) {
                    return cc;
                }
            }
        }
        return null;
    }

    private static boolean isTypeDecl(Node n) {
        return n.type() == NodeType.CLASS_DECLARATION || n.type() == NodeType.ENUM_DECLARATION
                || n.type() == NodeType.INTERFACE_DECLARATION || n.type() == NodeType.RECORD_DECLARATION;
    }

    /** AssertJ chained checks on a caught (or absent) throw; unmodelled links are honest undecideds. */
    private Object assertJThrowChain(ThrownAssertVal ta, String name, List<Object> args, Node site) {
        switch (name) {
            case "isInstanceOf", "isExactlyInstanceOf", "isInstanceOfAny" -> {
                if (ta.thrown() == null) {
                    failedAssertion = name + " at " + pos(site) + " — nothing was thrown";
                    event("ASSERTION FAILS: " + failedAssertion);
                    return ta;
                }
                boolean anyTrue = false;
                boolean anyNull = false;
                for (Object a : args) {
                    Boolean m = throwMatches(ta.thrown(), a);
                    if (m == null) {
                        anyNull = true;
                    } else if (m) {
                        anyTrue = true;
                    }
                }
                if (anyTrue) {
                    heldAssertions++;
                    event(name + " holds at " + pos(site) + " (" + ta.thrown().typeName + ")");
                } else if (anyNull) {
                    undecidableAssertions++;
                    unknown(name + " at " + pos(site) + " — thrown " + ta.thrown().typeName
                            + " vs expected type undecidable");
                } else {
                    failedAssertion = name + " at " + pos(site) + " — " + ta.thrown().typeName + " thrown instead";
                    event("ASSERTION FAILS: " + failedAssertion);
                }
                return ta;
            }
            case "doesNotThrowAnyException" -> {
                if (ta.thrown() != null) {
                    failedAssertion = name + " at " + pos(site) + " — " + ta.thrown().typeName + " thrown";
                    event("ASSERTION FAILS: " + failedAssertion);
                } else if (ta.cleanPath()) {
                    heldAssertions++;
                    event(name + " holds at " + pos(site));
                } else {
                    undecidableAssertions++;
                    unknown(name + " at " + pos(site) + " undecidable — the path has unknowns");
                }
                return ta;
            }
            default -> {
                undecidableAssertions++;
                unknown("assertj '" + name + "' at " + pos(site) + " not modelled — undecided");
                return ta;
            }
        }
    }

    /** Does the interpreted throw match the asserted exception class? null = can't tell. */
    private Boolean throwMatches(EmuThrow t, Object expected) {
        if (!(expected instanceof ClassVal cv)) {
            return null;
        }
        if (cv.entry() != null && t.type != null) {
            return hierarchy.isSubtypeOf(t.type, cv.entry());
        }
        if (cv.typeName() != null && cv.typeName().equals(t.typeName)) {
            return true; // exact name match; a supertype match we can't see stays null below
        }
        return null;
    }

    /** Is this node from a generated source file (indexed from build/generated)? */
    /** The SIMPLE type name of each argument value, or null where the value carries no type (bug report #2 item 2). */
    private List<String> argTypeNames(List<Object> args) {
        List<String> out = new ArrayList<>();
        for (Object a : args) {
            out.add(typeNameOfValue(a));
        }
        return out;
    }

    private String typeNameOfValue(Object v) {
        if (v instanceof ObjVal o) {
            return o.type == null ? null : o.type.name();
        }
        if (v instanceof MockVal m) {
            return m.typeName;
        }
        if (v instanceof ClassVal c) {
            return "Class";
        }
        if (v instanceof ArrayVal) {
            return null;
        }
        if (v == null || v instanceof Unknown) {
            return null;
        }
        if (v instanceof String) {
            return "String";
        }
        if (v instanceof Integer) {
            return "int";
        }
        if (v instanceof Long) {
            return "long";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Double) {
            return "double";
        }
        return v.getClass().getSimpleName();
    }

    private boolean isGenerated(Node n) {
        Node root = n;
        while (root.parent() != null) {
            root = root.parent();
        }
        Path f = rootFiles.get(root);
        return f != null && f.toString().contains("/build/generated/");
    }

    private String pos(Node n) {
        Token t = n.firstCodeToken();
        Node root = n;
        while (root.parent() != null) {
            root = root.parent();
        }
        Path f = rootFiles.get(root);
        return (f == null ? "?" : f.getFileName()) + ":" + (t == null ? 0 : t.line());
    }

    static String render(Object v) {
        if (v == null || v == NULL) {
            return "null";
        }
        if (v instanceof Concrete c) {
            Object x = c.v();
            if (x instanceof EnumConst e) {
                return e.type() + "." + e.name();
            }
            String s = String.valueOf(x);
            return s.length() > 60 ? s.substring(0, 60) + "…" : s;
        }
        if (v instanceof Sym s) {
            return "sym#" + s.id() + "(" + s.origin() + ")";
        }
        if (v instanceof Unknown u) {
            return "unknown(" + u.reason() + ")";
        }
        return String.valueOf(v);
    }
}
