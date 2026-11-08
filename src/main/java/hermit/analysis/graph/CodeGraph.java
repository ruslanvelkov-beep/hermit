package hermit.analysis.graph;

import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.sym.Scope;
import hermit.analysis.sym.SourceIndex;
import hermit.analysis.sym.Symbol;
import hermit.analysis.sym.SymbolResolver;
import hermit.analysis.sym.TypeHierarchy;
import hermit.analysis.sym.TypeInference;
import hermit.analysis.sym.TypeRef;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A whole-project call graph built from parse trees, without compiling and without a database. Every {@code .java}
 * file under the roots is parsed, resolved and type-inferred, producing:
 * <ul>
 *   <li>method and constructor declarations indexed by simple name and by {@code Type#name};</li>
 *   <li>call sites (method calls, method references, constructor invocations) with their enclosing declaration and,
 *       when inference knows it, the receiver's type;</li>
 *   <li>{@link #callersOf}, {@link #calleesOf} and breadth-first {@link #reach} over those edges.</li>
 * </ul>
 *
 * <p>Precision model: edges are name-based, sharpened by the receiver's inferred type when it is known: a deliberate
 * over-approximation in the style of class-hierarchy analysis, which suits the question "could this test reach that
 * method?". The graph works on code that does not compile yet, and {@link #refresh} re-indexes one edited file.
 */
public final class CodeGraph {

    /** One method/constructor/initializer declaration. */
    public record MethodRef(String typeName, String methodName, Path file, int line, Node decl) {
        public String spec() {
            return typeName + "#" + methodName;
        }

        @Override
        public String toString() {
            return spec() + " (" + file.getFileName() + ":" + line + ")";
        }
    }

    /** One invocation: {@code caller} invokes something named {@code calleeName} at AST node {@code site}. */
    public record CallSite(MethodRef caller, String calleeName, String receiverType, Path file, int line,
            Node site) {
        @Override
        public String toString() {
            return caller.spec() + " -> " + (receiverType != null ? receiverType + "." : "") + calleeName
                    + " (" + file.getFileName() + ":" + line + ")";
        }
    }

    /**
     * One RESOLVED dispatch candidate of a call site: a source declaration ({@code source}, with its
     * decl {@link Node} inside) or a library method ({@code library}). {@code how} says why it is a
     * candidate: {@code exact} (declared on the receiver's type), {@code inherited} (found up the
     * hierarchy), {@code impl} (an override/implementation in the receiver's subtype cone),
     * {@code ctor}/{@code default-ctor}, or {@code name-only} (receiver unresolvable - the honest
     * over-approximation).
     */
    public record Target(String typeName, String methodName, MethodRef source,
            java.lang.reflect.Method library, String how) {
        @Override
        public String toString() {
            return typeName + "#" + methodName + " [" + how + (library != null ? ", library" : "") + "]";
        }
    }

    /** A reverse edge: {@code site} was resolved to call the queried declaration ({@code how} as above). */
    public record CallerEdge(CallSite site, String how) {}

    /**
     * Outcome of a reachability search. {@code status}: {@code resolved} (a route over resolved dispatch edges),
     * {@code low-confidence} (the only route needs {@code nameOnlyHops} hops through name-only targets - unresolved
     * receivers matched by method name), {@code no-path} (the search exhausted every frontier: no route exists in the
     * indexed code), {@code depth-bound} (frontier still open when {@code maxDepth} was hit - "don't know"),
     * {@code budget} (search truncated by the node budget - "don't know"), {@code start-not-found}.
     */
    public record ReachResult(boolean reachable, List<String> path, String status, int nameOnlyHops) {
        public ReachResult(boolean reachable, List<String> path) {
            this(reachable, path, reachable ? "resolved"
                    : (!path.isEmpty() && path.get(0).startsWith("start not found") ? "start-not-found" : "no-path"), 0);
        }

        /** A reachable answer that rests on resolved edges only. */
        public boolean confident() {
            return reachable && "resolved".equals(status);
        }

        /** True when NOT REACHABLE means "don't know" rather than "no route exists". */
        public boolean undecided() {
            return !reachable && ("depth-bound".equals(status) || "budget".equals(status));
        }

        /** One line that keeps "no route exists" apart from "don't know" and labels guessed routes. */
        public String describe(int maxDepth) {
            if (reachable) {
                return confident()
                        ? "REACHABLE: " + String.join(" -> ", path)
                        : "REACHABLE (LOW CONFIDENCE — " + nameOnlyHops + " name-only hop(s) through unresolved receivers; "
                                + "no fully resolved route exists): " + String.join(" -> ", path);
            }
            return switch (status) {
                case "start-not-found" -> "NOT REACHABLE: " + path.get(0);
                case "depth-bound" -> "NOT REACHABLE within depth " + maxDepth + " (frontier still open — raise maxDepth; "
                        + "this is 'don't know', not 'no path')";
                case "budget" -> "NOT REACHABLE within the node budget (search truncated — raise the budget or narrow the "
                        + "question; this is 'don't know', not 'no path')";
                default -> "NOT REACHABLE: no path exists in the indexed code (search exhausted within depth " + maxDepth + ")";
            };
        }
    }

    private final SourceIndex sources;
    private final Map<Path, Boolean> indexed = new LinkedHashMap<>();
    private final Map<String, List<MethodRef>> methodsBySimpleName = new HashMap<>();
    private final Map<String, List<MethodRef>> methodsBySpec = new HashMap<>();
    private final Map<Node, MethodRef> declToRef = new IdentityHashMap<>();
    private final Map<String, List<CallSite>> callSitesByCalleeName = new HashMap<>();
    private final Map<Node, List<CallSite>> callSitesByCallerDecl = new HashMap<>();
    private TypeHierarchy hierarchy;
    private final Map<Node, List<Target>> targetsBySite = new IdentityHashMap<>();
    private final Map<Node, List<CallerEdge>> callerEdgesByDecl = new IdentityHashMap<>();
    private boolean resolved;
    private final Map<String, List<Target>> nameOnlyCache = new HashMap<>();
    /** Name-only fan-out above this many candidates is skipped: a hop through 11 000 same-named methods is noise. */
    static final int NAME_ONLY_FANOUT_CAP = 50;
    /** Default node budget of a reach search. */
    static final int DEFAULT_REACH_BUDGET = 200_000;

    private CodeGraph(SourceIndex sources) {
        this.sources = sources;
    }

    /** Parse and index every {@code .java} file under the roots. All files share one project-wide
     * {@link SourceIndex}, so receiver types resolve across files.
     */
    public static CodeGraph build(List<Path> roots, ClasspathIndex index) throws IOException {
        CodeGraph g = new CodeGraph(SourceIndex.build(roots, index));
        for (Path f : g.sources.files().stream().sorted().toList()) {
            g.indexFile(f);
        }
        return g;
    }

    /** The project-wide source symbol table backing this graph. */
    public SourceIndex sources() {
        return sources;
    }

    /** Re-parse ONE file (after an edit) and replace its contribution to graph and source table. */
    public void refresh(Path file) throws IOException {
        removeFile(file);
        sources.refresh(file);
        if (Files.isRegularFile(file)) {
            indexFile(file);
        }
        resolved = false;   // hierarchy + dispatch targets are rebuilt lazily on the next query
        hierarchy = null;
    }

    public int fileCount() {
        return indexed.size();
    }

    /** The parse tree of an indexed file, or null. */
    public ParseTree treeOf(Path file) {
        return sources.treeOf(file);
    }

    /** The simple names of the indexed types that declare at least one method in a file {@code keep} accepts. */
    public Set<String> typeNames(Predicate<Path> keep) {
        Set<String> out = new HashSet<>();
        for (List<MethodRef> refs : methodsBySpec.values()) {
            for (MethodRef m : refs) {
                if (keep.test(m.file())) {
                    out.add(m.typeName());
                }
            }
        }
        return out;
    }

    /** Declarations matching {@code spec}: a simple method name, or {@code Type#method}. */
    public List<MethodRef> locate(String spec) {
        if (spec.contains("#")) {
            return List.copyOf(methodsBySpec.getOrDefault(spec, List.of()));
        }
        return List.copyOf(methodsBySimpleName.getOrDefault(spec, List.of()));
    }

    /**
     * Call sites that may invoke {@code spec} ({@code method} or {@code Type#method}). With a type, sites
     * whose receiver type is KNOWN and different are excluded; unknown-receiver sites are kept
     * (over-approximation - absence of type knowledge must not hide a caller).
     */
    public List<CallSite> callersOf(String spec) {
        String type = null;
        String name = spec;
        int hash = spec.indexOf('#');
        if (hash >= 0) {
            type = spec.substring(0, hash);
            name = spec.substring(hash + 1);
        }
        List<CallSite> raw = callSitesByCalleeName.getOrDefault(name, List.of());
        if (type == null) {
            return List.copyOf(raw);
        }
        List<CallSite> out = new ArrayList<>();
        for (CallSite cs : raw) {
            if (cs.receiverType() == null || cs.receiverType().equals(type)) {
                out.add(cs);
            }
        }
        return out;
    }

    /** The call sites INSIDE the given declaration. */
    public List<CallSite> calleesOf(MethodRef m) {
        return List.copyOf(callSitesByCallerDecl.getOrDefault(m.decl(), List.of()));
    }

    /**
     * BFS over RESOLVED call edges: can {@code fromSpec} transitively invoke {@code toSpec}? Returns the
     * shortest path ({@code A#m -> B#n -> C#target}); a hop taken through a polymorphic call is annotated
     * {@code [one of N dispatch candidates]} so interface-heavy reachability is qualified, not asserted.
     */
    public ReachResult reach(String fromSpec, String toSpec, int maxDepth) {
        return reach(fromSpec, toSpec, maxDepth, DEFAULT_REACH_BUDGET);
    }

    /**
     * Two-phase search: first over resolved edges only, where a route found is the answer; only when none exists are
     * name-only targets admitted (with a capped fan-out), and the result is labelled {@code low-confidence} with the
     * number of such hops.
     */
    public ReachResult reach(String fromSpec, String toSpec, int maxDepth, int nodeBudget) {
        ensureResolved();
        List<MethodRef> starts = locate(fromSpec);
        Set<String> targets = new HashSet<>();
        for (MethodRef t : locate(toSpec)) {
            targets.add(t.spec());
        }
        if (starts.isEmpty()) {
            return new ReachResult(false, List.of("start not found: " + fromSpec), "start-not-found", 0);
        }
        ReachResult resolved = search(starts, targets, maxDepth, nodeBudget, false);
        if (resolved.reachable()) {
            return resolved;
        }
        ReachResult loose = search(starts, targets, maxDepth, nodeBudget, true);
        if (loose.reachable()) {
            return loose;
        }
        // not reachable either way: "don't know" statuses win over "no path" - a depth/budget stop in EITHER phase
        // means a route may still exist
        String status = "no-path";
        for (ReachResult r : List.of(resolved, loose)) {
            if ("budget".equals(r.status())) {
                status = "budget";
            } else if ("depth-bound".equals(r.status()) && !"budget".equals(status)) {
                status = "depth-bound";
            }
        }
        return new ReachResult(false, List.of(), status, 0);
    }

    /** The class/interface hierarchy backing dispatch resolution (built lazily, refreshed with the graph). */
    public TypeHierarchy hierarchy() {
        ensureHierarchy();   // hierarchy/methods/impls queries don't need the full per-site resolution
        return hierarchy;
    }

    /** The resolved dispatch candidates of a call site (never null; may be name-only fallbacks). */
    public List<Target> targetsOf(CallSite cs) {
        ensureResolved();
        return List.copyOf(targetsBySite.getOrDefault(cs.site(), List.of()));
    }

    /**
     * Reverse edges: the call sites RESOLVED to dispatch to {@code spec} ({@code Type#method} or a
     * simple name). Unlike the name-based {@link #callersOf}, a same-named method on an unrelated
     * type does not appear here.
     */
    public List<CallerEdge> callerEdges(String spec) {
        ensureResolved();
        List<CallerEdge> out = new ArrayList<>();
        for (MethodRef m : locate(spec)) {
            out.addAll(callerEdgesByDecl.getOrDefault(m.decl(), List.of()));
        }
        return out;
    }

    /** Dispatch candidates of {@code Type#method} seen as a virtual/interface call (any arity). */
    public List<Target> dispatchCandidatesOf(String spec) {
        ensureResolved();
        int hash = spec.indexOf('#');
        if (hash <= 0) {
            return List.of();
        }
        List<Target> out = new ArrayList<>();
        for (TypeHierarchy.TypeEntry e : hierarchy.entries(spec.substring(0, hash))) {
            out.addAll(toTargets(e, hierarchy.dispatchCandidates(e, spec.substring(hash + 1), -1)));
        }
        return out;
    }

    private void indexFile(Path file) {
        Path key = file.toAbsolutePath().normalize();
        ParseTree tree;
        TypeInference types;
        SymbolResolver resolver;
        try {
            tree = sources.treeOf(key);
            resolver = sources.resolverFor(key);
            if (tree == null || resolver == null) {
                return;
            }
            types = resolver.typeInference();
            types.validate(tree.root());   // fills inferred types; problems are not this graph's concern
        } catch (Throwable e) {
            // unparseable (mid-edit) or linkage-broken classpath - the file drops out until it recovers
            return;
        }
        indexed.put(key, Boolean.TRUE);
        Map<Node, MethodRef> declRefs = new HashMap<>();
        tree.root().walk(n -> {
            if (n.type() == NodeType.METHOD_DECLARATION || n.type() == NodeType.CONSTRUCTOR_DECLARATION
                    || n.type() == NodeType.COMPACT_CONSTRUCTOR) {
                String typeName = enclosingTypeName(n);
                String methodName = n.type() == NodeType.METHOD_DECLARATION ? n.name() : "<init>";
                if (typeName == null || methodName == null) {
                    return;
                }
                Token t = n.token();
                MethodRef ref = new MethodRef(typeName, methodName, key, t == null ? 0 : t.line(), n);
                declRefs.put(n, ref);
                declToRef.put(n, ref);
                methodsBySimpleName.computeIfAbsent(methodName, k -> new ArrayList<>()).add(ref);
                methodsBySpec.computeIfAbsent(ref.spec(), k -> new ArrayList<>()).add(ref);
            }
        });
        tree.root().walk(n -> {
            String calleeName;
            String receiverType = null;
            switch (n.type()) {
                case METHOD_CALL -> {
                    Token pr = n.token();
                    if (pr == null) {
                        return;
                    }
                    calleeName = pr.text();
                    receiverType = receiverTypeName(n, types, resolver);
                }
                case METHOD_REFERENCE -> {
                    Token pr = n.token();
                    if (pr == null) {
                        return;
                    }
                    calleeName = "new".equals(pr.text()) ? "<init>" : pr.text();
                    receiverType = receiverTypeName(n, types, resolver);
                }
                case NEW_INSTANCE -> {
                    calleeName = "<init>";
                    Node typeNode = n.child(NodeType.TYPE);
                    receiverType = typeNode == null ? null : typeNode.name();
                }
                default -> {
                    return;
                }
            }
            Node enclosing = enclosingDeclaration(n);
            MethodRef caller = enclosing == null ? null : declRefs.get(enclosing);
            if (caller == null) {
                return;   // field initializers etc. - out of scope for the caller graph
            }
            Token pr = n.token();
            CallSite cs = new CallSite(caller, calleeName, receiverType, key, pr == null ? 0 : pr.line(), n);
            callSitesByCalleeName.computeIfAbsent(calleeName, k -> new ArrayList<>()).add(cs);
            callSitesByCallerDecl.computeIfAbsent(caller.decl(), k -> new ArrayList<>()).add(cs);
        });
    }

    private static String enclosingTypeName(Node decl) {
        for (Node p = decl.parent(); p != null; p = p.parent()) {
            if (p.type().isTypeDeclaration() && p.name() != null) {
                return p.name();
            }
        }
        return null;
    }

    /** The receiver's type name for a qualified call, from inference (null = unknown/unqualified…). */
    private static String receiverTypeName(Node call, TypeInference types, SymbolResolver resolver) {
        Token pr = call.token();
        for (Node c : call.children()) {
            Token first = c.firstToken();
            if (first != null && pr != null && first.startOffset() < pr.startOffset()
                    && c.type() != NodeType.TYPE) {
                TypeRef t = types.typeOf(c);
                if (t.clazz() != null && t.dims() == 0) {
                    return safeSimpleName(t.clazz());
                }
                if (t.source() != null && t.sourceDecl() != null && t.sourceDecl().name() != null) {
                    return t.sourceDecl().name();
                }
                if (c.type() == NodeType.NEW_INSTANCE) {
                    // a type of another file is not visible to this file's inference,
                    // but `new C().target()` names its type in the source
                    Node typeNode = c.child(NodeType.TYPE);
                    if (typeNode != null) {
                        return typeNode.name();
                    }
                }
                return null;
            }
        }
        // unqualified: the enclosing type, unless a static import provides the name (callers filters sites
        // by this name, so naming the enclosing class would hide every call of an imported method)
        Node enclosing = call;
        while (enclosing != null && !enclosing.type().isTypeDeclaration()) {
            enclosing = enclosing.parent();
        }
        String enclosingName = enclosing == null ? null : enclosing.name();
        Scope scope = resolver.scopeOf(call);
        String member = pr == null ? null : pr.text();
        if (scope == null || member == null) {
            return enclosingName;
        }
        if (enclosing != null && declaresMethodByName(enclosing, member)) {
            return enclosingName;   // JLS 15.12.1: a visible local method shadows the import
        }
        String owner = scope.staticImportOwnerOf(member);
        if (owner == null) {
            for (String wildcard : scope.staticWildcardOwnerFqns()) {
                owner = wildcard;
                break;
            }
        }
        return owner == null ? enclosingName : owner.substring(owner.lastIndexOf('.') + 1);
    }

    /** Does this type declaration (or a nested/enclosing one in the same file) declare {@code name}? */
    private static boolean declaresMethodByName(Node typeDecl, String name) {
        for (Node m : typeDecl.descendants(NodeType.METHOD_DECLARATION)) {
            if (name.equals(m.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link Class#getSimpleName()} can TRIGGER CLASS LOADING (it resolves the declaring class, whose
     * supertypes may be missing on a partial classpath) - parse the name instead when it throws.
     */
    private static String safeSimpleName(Class<?> c) {
        try {
            return c.getSimpleName();
        } catch (Throwable t) {
            String name = c.getName();
            int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
            return cut < 0 ? name : name.substring(cut + 1);
        }
    }

    /** The method/constructor declaration a node belongs to (lambdas attribute to their enclosing method). */
    private static Node enclosingDeclaration(Node n) {
        for (Node p = n.parent(); p != null; p = p.parent()) {
            NodeType t = p.type();
            if (t == NodeType.METHOD_DECLARATION || t == NodeType.CONSTRUCTOR_DECLARATION
                    || t == NodeType.COMPACT_CONSTRUCTOR) {
                return p;
            }
        }
        return null;
    }

    /** Build just the type hierarchy (enough for hierarchy/impls/methods queries). */
    private void ensureHierarchy() {
        if (hierarchy == null) {
            hierarchy = TypeHierarchy.build(sources);
        }
    }

    /** The scope of a node of an indexed file, from that file's resolver; null when the file has none. */
    private Scope scopeAt(Path file, Node n) {
        SymbolResolver r = sources.resolverFor(file);
        return r == null ? null : r.scopeOf(n);
    }

    /** The symbol the file's resolver recorded for a node, or null. */
    private Symbol symbolAt(Path file, Node n) {
        SymbolResolver r = sources.resolverFor(file);
        return r == null ? null : r.symbolOf(n);
    }

    /** The receiver's hierarchy entry: inferred type of the qualifier, else the enclosing type. */
    private TypeHierarchy.TypeEntry receiverEntry(CallSite cs) {
        Node site = cs.site();
        if (site.type() == NodeType.NEW_INSTANCE) {
            Node typeNode = site.child(NodeType.TYPE);
            if (typeNode != null && typeNode.name() != null && scopeAt(cs.file(), site) != null) {
                Symbol s = scopeAt(cs.file(), site).resolveType(typeNode.name());
                if (s != null) {
                    return entryOfSymbol(s);
                }
            }
            return null;
        }
        Token pr = site.token();
        for (Node c : site.children()) {
            Token first = c.firstToken();
            if (first != null && pr != null && first.startOffset() < pr.startOffset()
                    && c.type() != NodeType.TYPE) {
                // a static call's qualifier resolves to a TYPE symbol; otherwise infer the value's type
                Symbol qualifier = symbolAt(cs.file(), c);
                if (qualifier != null && qualifier.kind() == Symbol.Kind.TYPE) {
                    return entryOfSymbol(qualifier);
                }
                TypeInference types = typesFor(cs.file());
                if (types == null) {
                    return null;
                }
                TypeRef t = types.typeOf(c);
                if (t.clazz() != null && t.dims() == 0) {
                    return hierarchy.libraryEntry(t.clazz());
                }
                if (t.sourceDecl() != null) {
                    return hierarchy.entryOf(t.sourceDecl());
                }
                if (c.type() == NodeType.NEW_INSTANCE) {
                    Node typeNode = c.child(NodeType.TYPE);
                    Scope scope = scopeAt(cs.file(), c);
                    if (typeNode != null && typeNode.name() != null && scope != null) {
                        Symbol s = scope.resolveType(typeNode.name());
                        if (s != null) {
                            return entryOfSymbol(s);
                        }
                    }
                }
                // static-call qualifier (Type.m()) whose symbol wasn't materialized: a bare identifier
                // that is NOT a visible variable but IS a visible type names the receiver directly
                Scope scope = scopeAt(cs.file(), c);
                if (c.type() == NodeType.IDENTIFIER_EXPRESSION && c.name() != null && scope != null
                        && first != null
                        && scope.resolveVariable(c.name(), first.startOffset()) == null) {
                    Symbol s = scope.resolveType(c.name());
                    if (s != null) {
                        return entryOfSymbol(s);
                    }
                }
                return null;
            }
        }
        // unqualified: the enclosing type first (JLS 15.12.1: a visible method shadows a static import), then the
        // compilation unit's static imports; a statically imported call attributed to the enclosing class would be
        // missing from the reverse index
        Node enclosing = site;
        while (enclosing != null && !enclosing.type().isTypeDeclaration()) {
            enclosing = enclosing.parent();
        }
        TypeHierarchy.TypeEntry enclosingEntry = enclosing == null ? null : hierarchy.entryOf(enclosing);
        if (enclosingEntry != null
                && !hierarchy.dispatchCandidates(enclosingEntry, cs.calleeName(), argCountOf(site)).isEmpty()) {
            return enclosingEntry;
        }
        TypeHierarchy.TypeEntry imported = staticImportEntry(cs.file(), site, cs.calleeName());
        return imported != null ? imported : enclosingEntry;
    }

    /** The owner of a single or on-demand {@code import static} providing {@code member}, as a hierarchy entry. */
    private TypeHierarchy.TypeEntry staticImportEntry(Path file, Node site, String member) {
        Scope scope = scopeAt(file, site);
        if (scope == null || member == null) {
            return null;
        }
        String owner = scope.staticImportOwnerOf(member);
        if (owner != null) {
            TypeHierarchy.TypeEntry e = entryOfFqnOrSimple(owner);
            if (e != null) {
                return e;
            }
        }
        for (String wildcard : scope.staticWildcardOwnerFqns()) {
            TypeHierarchy.TypeEntry e = entryOfFqnOrSimple(wildcard);
            if (e != null && !hierarchy.declaredMethods(e).isEmpty()
                    && hierarchy.declaredMethods(e).keySet().stream()
                            .anyMatch(k -> k.equals(member) || k.startsWith(member + "/"))) {
                return e;
            }
        }
        return null;
    }

    /** A hierarchy entry for a dotted FQN, falling back to its simple name (single-root indexes are unqualified). */
    private TypeHierarchy.TypeEntry entryOfFqnOrSimple(String fqn) {
        List<TypeHierarchy.TypeEntry> byFqn = hierarchy.entries(fqn);
        if (!byFqn.isEmpty()) {
            return byFqn.get(0);
        }
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        List<TypeHierarchy.TypeEntry> bySimple = hierarchy.entries(simple);
        return bySimple.isEmpty() ? null : bySimple.get(0);
    }

    private TypeHierarchy.TypeEntry entryOfSymbol(Symbol s) {
        if (s.declaration() != null) {
            return hierarchy.entryOf(s.declaration());
        }
        if (s.clazz() != null) {
            return hierarchy.libraryEntry(s.clazz());
        }
        return null;
    }

    private TypeInference typesFor(Path file) {
        try {
            SymbolResolver r = sources.resolverFor(file);
            return r == null ? null : r.typeInference();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Argument count of a call/ctor node; -1 for method references (arity depends on the context). */
    private static int argCountOf(Node site) {
        if (site.type() == NodeType.METHOD_REFERENCE) {
            return -1;
        }
        Token pr = site.token();
        if (site.type() == NodeType.NEW_INSTANCE) {
            int paren = -1;
            for (Token t : site.tokens()) {
                if (t.type() == TokenType.OPENING_ROUND_BRACKET) {
                    paren = t.startOffset();
                    break;
                }
            }
            if (paren < 0) {
                return -1;
            }
            int n = 0;
            for (Node c : site.children()) {
                Token first = c.firstToken();
                if (first != null && first.startOffset() > paren
                        && c.type() != NodeType.CLASS_BODY && c.type() != NodeType.TYPE) {
                    n++;
                }
            }
            return n;
        }
        if (pr == null) {
            return -1;
        }
        int n = 0;
        for (Node c : site.children()) {
            Token first = c.firstToken();
            if (first != null && first.startOffset() > pr.endOffset()
                    && c.type() != NodeType.CLASS_BODY && c.type() != NodeType.TYPE) {
                n++;
            }
        }
        return n;
    }

    /** The callees found in a method declaration. */
    public List<CallSite> calleesOfDecl(Node decl) {
        return callSitesByCallerDecl.getOrDefault(decl, List.of());
    }

    private void ensureResolved() {
        if (resolved) {
            return;
        }
        hierarchy = TypeHierarchy.build(sources);
        nameOnlyCache.clear();
        targetsBySite.clear();
        callerEdgesByDecl.clear();
        for (List<CallSite> sites : callSitesByCallerDecl.values()) {
            for (CallSite cs : sites) {
                List<Target> ts = resolveSite(cs);
                targetsBySite.put(cs.site(), ts);
                for (Target t : ts) {
                    // reverse edges only for RESOLVED dispatch - recording one edge per name-only
                    // candidate explodes quadratically once generated code is indexed (a bare
                    // `build()` matches thousands of generated builders); the callers query already
                    // falls back to name matching when no resolved edge exists
                    if (t.source() != null && !"name-only".equals(t.how())) {
                        callerEdgesByDecl.computeIfAbsent(t.source().decl(), k -> new ArrayList<>())
                                .add(new CallerEdge(cs, t.how()));
                    }
                }
            }
        }
        resolved = true;
    }

    /** Resolve ONE call site to its dispatch candidates (see {@link Target#how()} for the vocabulary). */
    private List<Target> resolveSite(CallSite cs) {
        Node site = cs.site();
        int argCount = argCountOf(site);
        // SINGLE-ALLOCATION NARROWING: a receiver that provably holds exactly one allocation has an
        // EXACT runtime type - no subtype cone, one candidate, tagged [alloc]
        if (!"<init>".equals(cs.calleeName())) {
            Node recvExpr = receiverExprOf(site);
            TypeHierarchy.TypeEntry exact = recvExpr == null ? null : allocationNarrowed(cs.file(), recvExpr);
            if (exact != null) {
                TypeHierarchy.MethodDecl d = hierarchy.resolveCall(exact, cs.calleeName(), argCount);
                if (d != null && d.concrete()) {
                    MethodRef src = d.decl() == null ? null : declToRef.get(d.decl());
                    if (src != null || d.reflect() != null) {
                        return List.of(new Target(d.owner().name(), d.name(), src, d.reflect(), "alloc"));
                    }
                }
            }
        }

        TypeHierarchy.TypeEntry receiver = receiverEntry(cs);
        if (receiver == null) {
            return nameOnlyTargets(cs);
        }
        if ("<init>".equals(cs.calleeName())) {
            return constructorTargets(receiver, argCount);
        }
        List<TypeHierarchy.MethodDecl> candidates =
                hierarchy.dispatchCandidates(receiver, cs.calleeName(), argCount);
        if (candidates.isEmpty()) {
            // open world (unresolvable supertypes) - the name-based over-approximation must survive
            return nameOnlyTargets(cs);
        }
        List<Target> out = toTargets(receiver, candidates);
        return out.isEmpty() ? nameOnlyTargets(cs) : out;
    }

    private List<Target> toTargets(TypeHierarchy.TypeEntry receiver, List<TypeHierarchy.MethodDecl> candidates) {
        List<Target> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            TypeHierarchy.MethodDecl d = candidates.get(i);
            String how = !d.concrete() ? "declaration"
                    : i > 0 ? "impl" : d.owner() == receiver ? "exact" : "inherited";
            MethodRef src = d.decl() == null ? null : declToRef.get(d.decl());
            if (src == null && d.reflect() == null) {
                continue;   // e.g. a record-component accessor - no body to traverse into
            }
            out.add(new Target(d.owner().name(), d.name(), src, d.reflect(), how));
        }
        return out;
    }

    private List<Target> constructorTargets(TypeHierarchy.TypeEntry receiver, int argCount) {
        List<Target> out = new ArrayList<>();
        if (receiver.decl() != null) {
            Node body = receiver.decl().type() == NodeType.CLASS_BODY
                    ? receiver.decl() : receiver.decl().child(NodeType.CLASS_BODY);
            if (body != null) {
                for (Node c : body.children(NodeType.CONSTRUCTOR_DECLARATION)) {
                    int params = c.children(NodeType.PARAMETER).size();
                    if (argCount < 0 || params == argCount) {
                        MethodRef src = declToRef.get(c);
                        if (src != null) {
                            out.add(new Target(receiver.name(), "<init>", src, null, "ctor"));
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new Target(receiver.name(), "<init>", null, null, "default-ctor"));
        }
        return out;
    }

    /**
     * The fallback when the receiver's type is not provable: every same-named source method. The list
     * is cached per (name, receiver type) and shared across sites: a bare {@code build()} can match
     * thousands of generated builders, and a list per call site would fill the heap.
     */
    private List<Target> nameOnlyTargets(CallSite cs) {
        String key = cs.calleeName() + "|" + (cs.receiverType() == null ? "" : cs.receiverType());
        return nameOnlyCache.computeIfAbsent(key, k -> {
            List<MethodRef> byName = methodsBySimpleName.getOrDefault(cs.calleeName(), List.of());
            List<MethodRef> pick = byName;
            if (cs.receiverType() != null) {
                List<MethodRef> filtered = new ArrayList<>();
                for (MethodRef m : byName) {
                    if (m.typeName().equals(cs.receiverType())) {
                        filtered.add(m);
                    }
                }
                if (!filtered.isEmpty()) {
                    pick = filtered;
                }
            }
            List<Target> out = new ArrayList<>(pick.size());
            for (MethodRef m : pick) {
                out.add(new Target(m.typeName(), m.methodName(), m, null, "name-only"));
            }
            return List.copyOf(out);
        });
    }

    private record Step(MethodRef at, List<String> path, int nameOnly) {}

    private ReachResult search(List<MethodRef> starts, Set<String> targets, int maxDepth, int nodeBudget,
            boolean allowNameOnly) {
        Deque<Step> queue = new ArrayDeque<>();
        Set<Node> visited = new HashSet<>();
        for (MethodRef s : starts) {
            queue.add(new Step(s, List.of(s.spec()), 0));
            visited.add(s.decl());
        }
        boolean depthBound = false;
        int expanded = 0;
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            if (step.path().size() > maxDepth) {
                depthBound = true;   // this frontier was cut, not exhausted
                continue;
            }
            if (++expanded > nodeBudget) {
                return new ReachResult(false, List.of(), "budget", 0);
            }
            for (CallSite cs : calleesOf(step.at())) {
                List<Target> ts = targetsBySite.getOrDefault(cs.site(), List.of());
                // only CONCRETE overriders count toward the polymorphism fan-out - the abstract
                // declaration is a target spec, never something that runs
                long concrete = ts.stream()
                        .filter(t -> t.source() != null && !"declaration".equals(t.how()))
                        .count();
                boolean nameOnlySite = !ts.isEmpty() && ts.stream().allMatch(t -> "name-only".equals(t.how()));
                if (nameOnlySite && (!allowNameOnly || ts.size() > NAME_ONLY_FANOUT_CAP)) {
                    continue;   // phase 1 ignores guesses; phase 2 skips absurd fan-outs
                }
                for (Target t : ts) {
                    MethodRef next = t.source();
                    if (next == null || !visited.add(next.decl())) {
                        continue;
                    }
                    boolean guess = "name-only".equals(t.how());
                    String hop = next.spec()
                            + (concrete > 1 && !"declaration".equals(t.how())
                                    ? " [one of " + concrete + " dispatch candidates, " + t.how() + "]"
                                    : guess ? " [name-only]" : "");
                    List<String> path = new ArrayList<>(step.path());
                    path.add(hop);
                    int nameOnly = step.nameOnly() + (guess ? 1 : 0);
                    if (targets.contains(next.spec())) {
                        return new ReachResult(true, path, nameOnly > 0 ? "low-confidence" : "resolved", nameOnly);
                    }
                    queue.add(new Step(next, path, nameOnly));
                }
            }
        }
        return new ReachResult(false, List.of(), depthBound ? "depth-bound" : "no-path", 0);
    }

    private void removeFile(Path file) {
        Path key = file.toAbsolutePath().normalize();
        if (indexed.remove(key) == null) {
            return;
        }
        methodsBySimpleName.values().forEach(l -> l.removeIf(m -> m.file().equals(key)));
        methodsBySpec.values().forEach(l -> l.removeIf(m -> m.file().equals(key)));
        callSitesByCalleeName.values().forEach(l -> l.removeIf(c -> c.file().equals(key)));
        callSitesByCallerDecl.keySet().removeIf(decl ->
                callSitesByCallerDecl.get(decl).stream().anyMatch(c -> c.file().equals(key)));
        declToRef.values().removeIf(m -> m.file().equals(key));
    }

    /** The qualifier expression of a call/method-reference (null for unqualified calls and ctors). */
    private static Node receiverExprOf(Node site) {
        if (site.type() == NodeType.NEW_INSTANCE) {
            return null;
        }
        Token pr = site.token();
        if (pr == null) {
            return null;
        }
        for (Node c : site.children()) {
            Token first = c.firstToken();
            if (first != null && first.startOffset() < pr.startOffset() && c.type() != NodeType.TYPE) {
                return c;
            }
        }
        return null;
    }

    /**
     * The EXACT runtime type of a receiver that provably holds a single allocation: the receiver is
     * {@code new X(...)} itself, or a local initialized with one and never reassigned, or a FINAL
     * field so initialized. Anonymous classes narrow to their own entry (their overrides included).
     * Returns null when nothing is provable - the caller falls back to the hierarchy cone.
     */
    private TypeHierarchy.TypeEntry allocationNarrowed(Path file, Node receiverExpr) {
        if (receiverExpr.type() == NodeType.NEW_INSTANCE) {
            return entryOfAllocation(file, receiverExpr);
        }
        if (receiverExpr.type() != NodeType.IDENTIFIER_EXPRESSION || receiverExpr.name() == null
                || scopeAt(file, receiverExpr) == null || receiverExpr.token() == null) {
            return null;
        }
        Symbol v = scopeAt(file, receiverExpr)
                .resolveVariable(receiverExpr.name(), receiverExpr.token().startOffset());
        if (v == null || v.declaration() == null
                || v.declaration().type() != NodeType.VAR_DECLARATOR) {
            return null;
        }
        Node declarator = v.declaration();
        List<Node> init = declarator.children();
        if (init.size() != 1 || init.get(0).type() != NodeType.NEW_INSTANCE) {
            return null;
        }
        Node holder = declarator.parent();
        if (holder == null) {
            return null;
        }
        if (holder.type() == NodeType.FIELD_DECLARATION) {
            boolean isFinal = holder.tokens().stream()
                    .anyMatch(t -> t.type() == TokenType.FINAL);
            if (!isFinal) {
                return null;   // a non-final field can be reassigned from anywhere
            }
        } else {
            Node enclosing = enclosingDeclaration(declarator);
            if (enclosing == null || reassignedWithin(enclosing, v, sources.resolverFor(file))) {
                return null;
            }
        }
        return entryOfAllocation(file, init.get(0));
    }

    private TypeHierarchy.TypeEntry entryOfAllocation(Path file, Node newInstance) {
        Node body = newInstance.child(NodeType.CLASS_BODY);
        if (body != null) {
            return hierarchy.entryOf(body);   // anonymous class: ITS overrides are the runtime truth
        }
        Node typeNode = newInstance.child(NodeType.TYPE);
        if (typeNode != null && typeNode.name() != null && scopeAt(file, newInstance) != null) {
            Symbol s = scopeAt(file, newInstance).resolveType(typeNode.name());
            if (s != null) {
                return entryOfSymbol(s);
            }
        }
        return null;
    }

    /** Is {@code v} the target of any assignment inside {@code enclosingDecl}'s subtree? */
    private static boolean reassignedWithin(Node enclosingDecl, Symbol v, SymbolResolver resolver) {
        boolean[] hit = {false};
        enclosingDecl.walk(n -> {
            if (hit[0] || n.type() != NodeType.ASSIGNMENT) {
                return;
            }
            List<Node> kids = n.children();
            if (kids.isEmpty()) {
                return;
            }
            Node lhs = kids.get(0);
            if (lhs.type() == NodeType.IDENTIFIER_EXPRESSION && lhs.name() != null
                    && resolver != null && resolver.scopeOf(lhs) != null && lhs.token() != null
                    && resolver.scopeOf(lhs).resolveVariable(lhs.name(), lhs.token().startOffset()) == v) {
                hit[0] = true;
            }
        });
        return hit[0];
    }

    /** All method references in the graph. */
    public List<MethodRef> allMethods() {
        return List.copyOf(declToRef.values());
    }

    /** The caller edges for a method declaration. */
    public List<CallerEdge> callerEdgesOf(Node decl) {
        return callerEdgesByDecl.getOrDefault(decl, List.of());
    }

    /** The method reference for a declaration. */
    public MethodRef refOf(Node decl) {
        return declToRef.get(decl);
    }

    /** The type inference results for a source file. */
    public TypeInference typeInferenceFor(Path file) {
        return typesFor(file);
    }
}
