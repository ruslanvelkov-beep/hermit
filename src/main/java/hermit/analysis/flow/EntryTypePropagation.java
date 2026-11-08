package hermit.analysis.flow;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.lex.Token;
import hermit.analysis.sym.Scope;
import hermit.analysis.sym.Symbol;
import hermit.analysis.sym.SymbolResolver;
import hermit.analysis.sym.TypeHierarchy;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry-point type propagation (see docs/design-type-propagation.md): variable type analysis
 * seeded at ONE method - allocation sites contribute their exact types, sets flow through
 * assignments, call arguments and returns, and every call site reachable from the entry resolves
 * per element of its receiver's type-set (exact dispatch, no cone). Where no allocation is visible
 * (injected/mocked/entry parameters) the site honestly falls back to the CHA cone, tagged.
 */
public final class EntryTypePropagation {

    /** An abstract runtime type: a hierarchy entry, or a Mockito mock of a named type. */
    record AType(TypeHierarchy.TypeEntry entry, String mockOf) {
        @Override
        public String toString() {
            return mockOf != null ? "mock:" + mockOf : entry.name();
        }
    }

    /** One flow-resolved dispatch target of a call site. */
    public record FlowTarget(CodeGraph.MethodRef target, String tag) {}

    private final CodeGraph graph;
    private final TypeHierarchy hierarchy;
    private final Map<Symbol, Set<AType>> varTypes = new HashMap<>();
    private final Map<Node, Set<AType>> returnTypes = new IdentityHashMap<>();
    private final Map<Node, List<FlowTarget>> siteTargets = new IdentityHashMap<>();
    private final Set<Node> analyzed = new LinkedHashSet<>();
    private boolean changed;

    private EntryTypePropagation(CodeGraph graph) {
        this.graph = graph;
        this.hierarchy = graph.hierarchy();
    }

    /** Analyze the cone reachable from {@code entrySpec} to a fixed point. */
    /** Default budget of the entry-cone analysis: methods analysed / wall-clock milliseconds. */
    public static final int DEFAULT_MAX_METHODS = 4000;
    public static final long DEFAULT_TIME_BUDGET_MS = 20_000;

    /** Matches CodeGraph's DEFAULT_REACH_BUDGET reach budget. */
    private static final int DEFAULT_REACH_BUDGET = 200_000;

    private boolean truncated;
    private String truncationReason = "";

    public static EntryTypePropagation analyze(CodeGraph graph, String entrySpec) {
        return analyze(graph, entrySpec, DEFAULT_MAX_METHODS, DEFAULT_TIME_BUDGET_MS);
    }

    /**
     * Bounded analysis (bug report Defect 4: an unbounded run on an 11k-file index took the server down). The fixpoint
     * stops - and the result says so via {@link #truncated()} - once the cone exceeds {@code maxMethods} or the time
     * budget is spent; a truncated analysis answers reach questions with {@code budget}, never {@code no-path}.
     */
    public static EntryTypePropagation analyze(CodeGraph graph, String entrySpec, int maxMethods, long timeBudgetMs) {
        EntryTypePropagation f = new EntryTypePropagation(graph);
        long deadline = timeBudgetMs == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + timeBudgetMs;
        List<CodeGraph.MethodRef> entries = graph.locate(entrySpec);
        for (CodeGraph.MethodRef m : entries) {
            f.seedEnclosingClassFields(m.decl());
            f.analyzed.add(m.decl());
        }
        for (int pass = 0; pass < 40; pass++) {
            if (f.analyzed.size() > maxMethods) {
                f.truncated = true;
                f.truncationReason = f.analyzed.size() + " methods > budget " + maxMethods;
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                f.truncated = true;
                f.truncationReason = "time budget " + timeBudgetMs + " ms spent after " + pass + " pass(es)";
                break;
            }
            f.changed = false;
            for (Node m : new ArrayList<>(f.analyzed)) {
                f.transfer(m);
                if (System.currentTimeMillis() > deadline) {
                    break;
                }
            }
            if (!f.changed) {
                break;
            }
            if (pass == 39) {
                f.truncated = true;
                f.truncationReason = "no fixpoint after 40 passes";
            }
        }
        return f;
    }

    /** True when the analysis stopped on its budget - its cone, and any reach over it, is partial. */
    public boolean truncated() {
        return truncated;
    }

    public String truncationReason() {
        return truncationReason;
    }

    /** The flow-resolved targets of every call site inside {@code methodSpec} (empty = not in the cone). */
    public List<FlowTarget> targetsAt(CodeGraph.CallSite site) {
        return List.copyOf(siteTargets.getOrDefault(site.site(), List.of()));
    }

    public int analyzedMethods() {
        return analyzed.size();
    }

    /**
     * Reach over FLOW-RESOLVED edges: hops are {@code [flow-confirmed]} (singleton),
     * {@code [flow: one of N]}, or {@code [injected - cone of N]} where no allocation was visible.
     */
    public CodeGraph.ReachResult reach(String fromSpec, String toSpec, int maxDepth) {
        List<CodeGraph.MethodRef> starts = graph.locate(fromSpec);
        Set<String> targets = new HashSet<>();
        for (CodeGraph.MethodRef t : graph.locate(toSpec)) {
            targets.add(t.spec());
        }
        if (starts.isEmpty()) {
            return new CodeGraph.ReachResult(false, List.of("start not found: " + fromSpec));
        }
        record Step(CodeGraph.MethodRef at, List<String> path) {}
        Deque<Step> queue = new ArrayDeque<>();
        Set<Node> visited = new HashSet<>();
        for (CodeGraph.MethodRef s : starts) {
            queue.add(new Step(s, List.of(s.spec())));
            visited.add(s.decl());
        }
        boolean depthBound = false;
        int expanded = 0;
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            if (step.path().size() > maxDepth) {
                depthBound = true;
                continue;
            }
            if (++expanded > DEFAULT_REACH_BUDGET) {
                return new CodeGraph.ReachResult(false, List.of(), "budget", 0);
            }
            for (CodeGraph.CallSite cs : graph.calleesOfDecl(step.at().decl())) {
                List<FlowTarget> ts = siteTargets.getOrDefault(cs.site(), List.of());
                long flowCount = ts.stream().filter(t -> t.tag().startsWith("flow")).count();
                for (FlowTarget t : ts) {
                    CodeGraph.MethodRef next = t.target();
                    if (!visited.add(next.decl())) {
                        continue;
                    }
                    String hop = next.spec() + " ["
                            + ("flow".equals(t.tag()) && flowCount == 1 ? "flow-confirmed"
                                    : "flow".equals(t.tag()) ? "flow: one of " + flowCount
                                    : t.tag()) + "]";
                    List<String> path = new ArrayList<>(step.path());
                    path.add(hop);
                    if (targets.contains(next.spec())) {
                        return new CodeGraph.ReachResult(true, path);
                    }
                    queue.add(new Step(next, path));
                }
            }
        }
        return new CodeGraph.ReachResult(false, List.of(),
                truncated ? "budget" : depthBound ? "depth-bound" : "no-path", 0);
    }

    /** The entry class's fields: allocations, mock() initializers and @Mock annotations seed sets. */
    private void seedEnclosingClassFields(Node method) {
        Node type = enclosingType(method);
        if (type == null) {
            return;
        }
        Node body = type.child(NodeType.CLASS_BODY);
        if (body == null) {
            return;
        }
        for (Node fd : body.children(NodeType.FIELD_DECLARATION)) {
            boolean mockAnnotated = fd.children(NodeType.ANNOTATION).stream()
                    .anyMatch(a -> "Mock".equals(a.name()) || "MockBean".equals(a.name()));
            for (Node d : fd.children(NodeType.VAR_DECLARATOR)) {
                Symbol s = symbolOf(d);
                if (s == null) {
                    continue;
                }
                if (mockAnnotated) {
                    Node t = fd.child(NodeType.TYPE);
                    add(s, new AType(null, t == null || t.name() == null ? "?" : t.name()));
                } else if (!d.children().isEmpty()) {
                    addAll(s, typesOf(d.children().get(d.children().size() - 1), method));
                }
            }
        }
    }

    private void transfer(Node method) {
        Node body = method.child(NodeType.BLOCK);
        if (body == null) {
            return;
        }
        walk(body, method);
    }

    private void walk(Node n, Node method) {
        if (n.type() == NodeType.CLASS_BODY || n.type().isTypeDeclaration()) {
            return;   // nested types are their own methods
        }
        switch (n.type()) {
            case VAR_DECLARATION -> {
                for (Node d : n.children(NodeType.VAR_DECLARATOR)) {
                    Symbol s = symbolOf(d);
                    if (s != null && !d.children().isEmpty()) {
                        addAll(s, typesOf(d.children().get(d.children().size() - 1), method));
                    }
                }
            }
            case ASSIGNMENT -> {
                List<Node> kids = n.children();
                if (kids.size() >= 2) {
                    Set<AType> rhs = typesOf(kids.get(kids.size() - 1), method);
                    Symbol s = lvalueSymbol(kids.get(0));
                    if (s != null) {
                        addAll(s, rhs);
                    }
                }
            }
            case RETURN -> {
                for (Node c : n.children()) {
                    Set<AType> t = typesOf(c, method);
                    Set<AType> rt = returnTypes.computeIfAbsent(method, k -> new LinkedHashSet<>());
                    if (rt.addAll(t)) {
                        changed = true;
                    }
                }
            }
            case METHOD_CALL, NEW_INSTANCE, METHOD_REFERENCE -> {
                typesOf(n, method);   // resolves the site + pours arguments as a side effect
            }
            default -> { /* recurse */ }
        }
        for (Node c : n.children()) {
            walk(c, method);
        }
    }

    /** The type-set an expression can hold; resolves call sites and pours arguments on the way. */
    private Set<AType> typesOf(Node n, Node method) {
        switch (n.type()) {
            case NEW_INSTANCE -> {
                Node body = n.child(NodeType.CLASS_BODY);
                if (body != null) {
                    TypeHierarchy.TypeEntry anon = hierarchy.entryOf(body);
                    return anon == null ? Set.of() : Set.of(new AType(anon, null));
                }
                Node t = n.child(NodeType.TYPE);
                if (t != null && t.name() != null && scopeOf(n) != null) {
                    Symbol s = scopeOf(n).resolveType(t.name());
                    TypeHierarchy.TypeEntry e = entryOf(s);
                    if (e != null) {
                        return Set.of(new AType(e, null));
                    }
                }
                return Set.of();
            }
            case IDENTIFIER_EXPRESSION -> {
                Symbol s = useSymbol(n);
                return s == null ? Set.of() : varTypes.getOrDefault(s, Set.of());
            }
            case FIELD_ACCESS -> {
                Symbol s = useSymbol(n);   // resolved field symbols share identity across files
                return s == null ? Set.of() : varTypes.getOrDefault(s, Set.of());
            }
            case PARENTHESIZED, CAST -> {
                Set<AType> out = Set.of();
                for (Node c : n.children()) {
                    if (c.type() != NodeType.TYPE) {
                        out = typesOf(c, method);
                    }
                }
                return out;
            }
            case TERNARY -> {
                List<Node> kids = n.children();
                Set<AType> out = new LinkedHashSet<>();
                for (int i = 1; i < kids.size(); i++) {
                    out.addAll(typesOf(kids.get(i), method));
                }
                return out;
            }
            case METHOD_CALL -> {
                return resolveCallSite(n, method);
            }
            default -> {
                return Set.of();
            }
        }
    }

    /** Resolve one call site under the current sets: dispatch per receiver type, pour arguments. */
    private Set<AType> resolveCallSite(Node site, Node method) {
        Token pr = site.token();
        if (pr == null) {
            return Set.of();
        }
        String name = pr.text();
        Node recvNode = null;
        List<Node> argNodes = new ArrayList<>();
        for (Node c : site.children()) {
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
        // Mockito boundary: mock(X.class) IS an allocation of a mock
        if (recvNode == null && "mock".equals(name) && !argNodes.isEmpty()) {
            Node classArg = argNodes.get(0);
            String mocked = classArg.children().isEmpty() ? null : classArg.children().get(0).name();
            return Set.of(new AType(null, mocked == null ? "?" : mocked));
        }

        Set<AType> receivers = recvNode == null
                ? enclosingTypeSet(site)
                : typesOf(recvNode, method);

        List<FlowTarget> resolved = new ArrayList<>();
        Set<AType> results = new LinkedHashSet<>();
        boolean mockBoundary = false;
        for (AType r : receivers) {
            if (r.mockOf() != null) {
                mockBoundary = true;
                continue;   // a mock dispatches to no source - the stubbing intercepts
            }
            TypeHierarchy.MethodDecl d = hierarchy.resolveCall(r.entry(), name, argNodes.size());
            if (d == null || d.decl() == null) {
                continue;
            }
            CodeGraph.MethodRef ref = graph.refOf(d.decl());
            if (ref == null) {
                continue;
            }
            resolved.add(new FlowTarget(ref, "flow"));
            pourArguments(d.decl(), argNodes, method);
            if (analyzed.add(ref.decl())) {
                changed = true;
            }
            Set<AType> rt = returnTypes.get(ref.decl());
            if (rt != null) {
                results.addAll(rt);
            }
        }
        if (resolved.isEmpty() && !mockBoundary) {
            // no allocation visible - honest CHA cone fallback, tagged
            for (CodeGraph.CallSite cs : graph.calleesOfDecl(method)) {
                if (cs.site() == site) {
                    for (CodeGraph.Target t : graph.targetsOf(cs)) {
                        if (t.source() != null && !"declaration".equals(t.how())) {
                            resolved.add(new FlowTarget(t.source(),
                                    "injected — cone of " + countConcrete(cs)));
                            if (analyzed.add(t.source().decl())) {
                                changed = true;
                            }
                        }
                    }
                    break;
                }
            }
        }
        List<FlowTarget> prev = siteTargets.put(site, resolved);
        if (prev == null || prev.size() != resolved.size()) {
            changed = true;
        }
        return results;
    }

    private long countConcrete(CodeGraph.CallSite cs) {
        return graph.targetsOf(cs).stream()
                .filter(t -> t.source() != null && !"declaration".equals(t.how()))
                .count();
    }

    private void pourArguments(Node calleeDecl, List<Node> argNodes, Node method) {
        List<Node> params = calleeDecl.children(NodeType.PARAMETER);
        var scope = ownScopeOf(calleeDecl);
        if (scope == null) {
            return;
        }
        for (int i = 0; i < params.size() && i < argNodes.size(); i++) {
            if (params.get(i).name() == null) {
                continue;
            }
            Symbol p = scope.resolveVariable(params.get(i).name(), Integer.MAX_VALUE);
            if (p != null) {
                addAll(p, typesOf(argNodes.get(i), method));
            }
        }
    }

    private Set<AType> enclosingTypeSet(Node site) {
        Node type = enclosingType(site);
        TypeHierarchy.TypeEntry e = type == null ? null : hierarchy.entryOf(type);
        return e == null ? Set.of() : Set.of(new AType(e, null));
    }

    private void add(Symbol s, AType t) {
        if (varTypes.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(t)) {
            changed = true;
        }
    }

    private void addAll(Symbol s, Set<AType> ts) {
        if (!ts.isEmpty() && varTypes.computeIfAbsent(s, k -> new LinkedHashSet<>()).addAll(ts)) {
            changed = true;
        }
    }

    private Symbol symbolOf(Node declarator) {
        if (declarator.name() == null || scopeOf(declarator) == null || declarator.token() == null) {
            return null;
        }
        return scopeOf(declarator).resolveVariable(declarator.name(), declarator.token().startOffset());
    }

    private Symbol useSymbol(Node n) {
        if (n.name() == null || scopeOf(n) == null || n.token() == null) {
            return null;
        }
        return scopeOf(n).resolveVariable(n.name(), n.token().startOffset());
    }

    private Symbol lvalueSymbol(Node lhs) {
        if (lhs.type() == NodeType.IDENTIFIER_EXPRESSION || lhs.type() == NodeType.FIELD_ACCESS) {
            return useSymbol(lhs);
        }
        return null;
    }

    private TypeHierarchy.TypeEntry entryOf(Symbol s) {
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

    private static Node enclosingType(Node n) {
        for (Node p = n.parent(); p != null; p = p.parent()) {
            if (p.type().isTypeDeclaration()) {
                return p;
            }
        }
        return null;
    }

    private Path fileOf(Node n) {
        Node root = n;
        while (root.parent() != null) {
            root = root.parent();
        }
        for (Path file : graph.sources().files()) {
            if (graph.treeOf(file).root() == root) {
                return file;
            }
        }
        return null;
    }

    /** The scope of a node, from its file's resolver; null when the file has none. */
    private Scope scopeOf(Node n) {
        Path file = fileOf(n);
        if (file == null) {
            return null;
        }
        SymbolResolver r = graph.sources().resolverFor(file);
        return r == null ? null : r.scopeOf(n);
    }

    /**
     * The scope a declaration owns (its parameters and locals), from its file's resolver;
     * null when the file has none.
     */
    private Scope ownScopeOf(Node decl) {
        Path file = fileOf(decl);
        if (file == null) {
            return null;
        }
        SymbolResolver r = graph.sources().resolverFor(file);
        return r == null ? null : r.ownScope(decl);
    }
}
