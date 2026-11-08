package hermit.analysis.flow;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.sym.Scope;
import hermit.analysis.sym.Symbol;
import hermit.analysis.sym.SymbolResolver;
import hermit.analysis.sym.TypeHierarchy;
import hermit.analysis.sym.TypeInference;
import hermit.analysis.sym.TypeRef;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exception-flow analysis over the resolved call graph:
 * per-method ESCAPE SETS (which exception types can propagate out, tiered by how we know),
 * per-catch ARRIVALS (what a catch clause can actually receive - a catch receiving nothing is
 * dead), and provenance chains. Computed to a fixed point with a caller-driven worklist; checked
 * vs unchecked classified through the type hierarchy.
 *
 * <p>Precision stance: provable events only - explicit {@code throw}, rethrown catch parameters,
 * resolved callees' escapes, and library methods' declared exceptions. No heuristic NPE/IOOBE
 * guessing. Over-approximation markers ({@code name-only}, {@code unresolved}) are never dropped.
 * Lambda bodies attribute to their enclosing method (consistent with the call graph); nested and
 * anonymous class bodies are separate methods and analyzed as such.
 */
public final class ExceptionFlow {

    /** One exception type escaping a method: how we know ({@code tier}) and where it came from. */
    public record Escape(String typeName, TypeHierarchy.TypeEntry type, String tier,
            Path file, int line, String via) {
        @Override
        public String toString() {
            return typeName + " [" + tier + "] — " + via;
        }
    }

    /** One arrival at a catch clause ({@code partial} = the catch takes only a subtype slice). */
    public record CaughtAt(String typeName, String tier, Path fromFile, int fromLine, boolean partial) {}

    /** A catch clause: its position, declared types, and everything the analysis saw arrive. */
    public record CatchReport(Path file, int line, List<String> declared, List<CaughtAt> receives) {}

    private final CodeGraph graph;
    private final TypeHierarchy hierarchy;
    private final Map<Node, Map<String, Escape>> escaped = new IdentityHashMap<>();
    private final Map<Node, Map<Node, List<CaughtAt>>> caughtBySource = new IdentityHashMap<>();
    private final TypeHierarchy.TypeEntry runtimeException;
    private final TypeHierarchy.TypeEntry error;

    private final Map<Node, Path> rootFiles = new IdentityHashMap<>();

    ExceptionFlow(CodeGraph graph, TypeHierarchy hierarchy) {
        this.graph = graph;
        this.hierarchy = hierarchy;
        this.runtimeException = hierarchy.libraryEntry(RuntimeException.class);
        this.error = hierarchy.libraryEntry(Error.class);
        for (Path f : graph.sources().files()) {
            var tree = graph.treeOf(f);
            if (tree != null) {
                rootFiles.put(tree.root(), f);
            }
        }
        compute();
    }

    /** Creates exception-flow analysis for the graph. */
    public static ExceptionFlow of(CodeGraph graph) {
        return new ExceptionFlow(graph, graph.hierarchy());
    }

    /** The computed escapes of {@code Type#method} (or a simple name), one entry per type name. */
    public List<Escape> escapesOf(String spec) {
        List<Escape> out = new ArrayList<>();
        for (CodeGraph.MethodRef m : graph.locate(spec)) {
            out.addAll(escaped.getOrDefault(m.decl(), Map.of()).values());
        }
        return out;
    }

    /** The method's own {@code throws} clause - the declared contract, not the computed truth. */
    public List<String> declaredThrows(String spec) {
        List<String> out = new ArrayList<>();
        for (CodeGraph.MethodRef m : graph.locate(spec)) {
            for (Node t : throwsClauseTypes(m.decl())) {
                if (t.name() != null) {
                    out.add(t.name());
                }
            }
        }
        return out;
    }

    /** Catch clauses in files ending with {@code fileSuffix} whose {@code catch} sits on {@code line}. */
    public List<CatchReport> catchesAt(String fileSuffix, int line) {
        List<CatchReport> out = new ArrayList<>();
        for (Path f : graph.sources().files()) {
            if (!f.toString().endsWith(fileSuffix)) {
                continue;
            }
            var tree = graph.treeOf(f);
            if (tree == null) {
                continue;
            }
            tree.root().walk(n -> {
                if (n.type() != NodeType.CATCH || n.firstCodeToken() == null
                        || n.firstCodeToken().line() != line) {
                    return;
                }
                List<String> declared = new ArrayList<>();
                for (Node t : n.children(NodeType.TYPE)) {
                    if (t.name() != null) {
                        declared.add(t.name());
                    }
                }
                List<CaughtAt> receives = new ArrayList<>();
                caughtBySource.getOrDefault(n, Map.of()).values().forEach(receives::addAll);
                out.add(new CatchReport(f, line, declared, receives));
            });
        }
        return out;
    }

    /** Every catch clause inside {@code Type#method} - the line-free entry point for "what does this
     * method swallow" (the model rarely knows the catch's line number). */
    public List<CatchReport> catchesIn(String methodSpec) {
        List<CatchReport> out = new ArrayList<>();
        for (CodeGraph.MethodRef m : graph.locate(methodSpec)) {
            Path file = m.file();
            m.decl().walk(n -> {
                if (n.type() != NodeType.CATCH || n.firstCodeToken() == null) {
                    return;
                }
                int line = n.firstCodeToken().line();
                List<String> declared = new ArrayList<>();
                for (Node t : n.children(NodeType.TYPE)) {
                    if (t.name() != null) {
                        declared.add(t.name());
                    }
                }
                List<CaughtAt> receives = new ArrayList<>();
                caughtBySource.getOrDefault(n, Map.of()).values().forEach(receives::addAll);
                out.add(new CatchReport(file, line, declared, receives));
            });
        }
        return out;
    }

    /** Is the exception type unchecked (RuntimeException/Error), null-safe ("?" when unresolved)? */
    public String checkedness(TypeHierarchy.TypeEntry t) {
        if (t == null) {
            return "?";
        }
        return hierarchy.isSubtypeOf(t, runtimeException) || hierarchy.isSubtypeOf(t, error)
                ? "unchecked" : "checked";
    }

    private void compute() {
        Deque<Node> work = new ArrayDeque<>();
        Set<Node> queued = new HashSet<>();
        for (CodeGraph.MethodRef m : graph.allMethods()) {
            if (queued.add(m.decl())) {
                work.add(m.decl());
            }
        }
        int guard = 0;
        int cap = queued.size() * 50 + 1000;
        while (!work.isEmpty() && guard++ < cap) {
            Node decl = work.poll();
            queued.remove(decl);
            Map<Node, List<CaughtAt>> catchHits = new IdentityHashMap<>();
            Map<String, Escape> fresh = computeEscapes(decl, catchHits);
            // replace this method's catch contributions (recomputation must not duplicate)
            for (Map<Node, List<CaughtAt>> perSource : caughtBySource.values()) {
                perSource.remove(decl);
            }
            catchHits.forEach((catchNode, hits) ->
                    caughtBySource.computeIfAbsent(catchNode, k -> new IdentityHashMap<>())
                            .put(decl, hits));
            Map<String, Escape> prev = escaped.put(decl, fresh);
            if (prev == null || !prev.keySet().equals(fresh.keySet())) {
                for (CodeGraph.CallerEdge e : graph.callerEdgesOf(decl)) {
                    Node caller = e.site().caller().decl();
                    if (queued.add(caller)) {
                        work.add(caller);
                    }
                }
            }
        }
    }

    private Map<String, Escape> computeEscapes(Node decl, Map<Node, List<CaughtAt>> catchHits) {
        Map<String, Escape> out = new LinkedHashMap<>();
        Node body = decl.child(NodeType.BLOCK);
        if (body == null) {
            // abstract/interface declaration: the declared contract IS its escape surface
            for (Node t : throwsClauseTypes(decl)) {
                String name = t.name();
                if (name != null) {
                    out.putIfAbsent(name, new Escape(name, resolveTypeName(t, name), "declared",
                            fileOf(decl), lineOf(decl), "declared in the throws clause"));
                }
            }
            return out;
        }
        // 1) explicit throw statements (nested class bodies are separate methods - skipped)
        collectThrows(body, decl, out, catchHits);
        // 2) resolved callees' escapes + library callees' declared exceptions. Name-only fallback
        // targets contribute ONE explicit unknown marker instead of a union - over-approximation
        // is right for reachability, but a false union would drown an exception REPORT in noise.
        for (CodeGraph.CallSite cs : graph.calleesOfDecl(decl)) {
            for (CodeGraph.Target t : graph.targetsOf(cs)) {
                if ("name-only".equals(t.how())) {
                    route(cs.site(), decl, "?", null, "unresolved-call", cs.file(), cs.line(),
                            "call to '" + cs.calleeName() + "' at " + cs.file().getFileName() + ":"
                                    + cs.line() + " did not resolve — its exceptions are unknown",
                            out, catchHits);
                    break;   // one marker per site, not one per name-matched method
                }
                if (t.source() != null) {
                    for (Escape e : escaped.getOrDefault(t.source().decl(), Map.of()).values()) {
                        String via = cap(t.source().spec() + " (" + cs.file().getFileName() + ":"
                                + cs.line() + ") ← " + e.via(), 300);
                        route(cs.site(), decl, e.typeName(), e.type(),
                                "declared".equals(e.tier()) ? "declared" : "propagated",
                                cs.file(), cs.line(), via, out, catchHits);
                    }
                }
                if (t.library() != null) {
                    try {
                        for (Class<?> ex : t.library().getExceptionTypes()) {
                            TypeHierarchy.TypeEntry entry = hierarchy.libraryEntry(ex);
                            route(cs.site(), decl, entry.name(), entry, "library-declared",
                                    cs.file(), cs.line(),
                                    "declared by " + t.typeName() + "#" + t.methodName()
                                            + ", called at " + cs.file().getFileName() + ":" + cs.line(),
                                    out, catchHits);
                        }
                    } catch (Throwable ignored) {
                        // partial classpath - exception metadata unavailable
                    }
                }
            }
        }
        return out;
    }

    private void collectThrows(Node n, Node decl, Map<String, Escape> out,
            Map<Node, List<CaughtAt>> catchHits) {
        if (n.type() == NodeType.CLASS_BODY || n.type().isTypeDeclaration()) {
            return;   // nested/anonymous types own their throws
        }
        if (n.type() == NodeType.THROW) {
            for (Thrown t : thrownTypes(n)) {
                route(n, decl, t.name(), t.entry(), t.tier(), fileOf(n), lineOf(n),
                        t.tier() + " at " + fileOf(n).getFileName() + ":" + lineOf(n), out, catchHits);
            }
        }
        for (Node c : n.children()) {
            collectThrows(c, decl, out, catchHits);
        }
    }

    private record Thrown(String name, TypeHierarchy.TypeEntry entry, String tier) {}

    private List<Thrown> thrownTypes(Node throwNode) {
        Node expr = throwNode.children().isEmpty() ? null : throwNode.children().get(0);
        if (expr == null) {
            return List.of();
        }
        if (expr.type() == NodeType.NEW_INSTANCE) {
            Node typeNode = expr.child(NodeType.TYPE);
            String name = typeNode == null ? null : typeNode.name();
            if (name != null) {
                return List.of(new Thrown(name, resolveTypeName(typeNode, name), "thrown"));
            }
        }
        if (expr.type() == NodeType.IDENTIFIER_EXPRESSION && expr.name() != null
                && scopeOf(expr) != null && expr.token() != null) {
            Symbol v = scopeOf(expr).resolveVariable(expr.name(), expr.token().startOffset());
            if (v != null && v.declaration() != null && v.declaration().type() == NodeType.CATCH) {
                List<Thrown> out = new ArrayList<>();
                for (Node t : v.declaration().children(NodeType.TYPE)) {
                    if (t.name() != null) {
                        out.add(new Thrown(t.name(), resolveTypeName(t, t.name()), "rethrown"));
                    }
                }
                return out;
            }
        }
        // anything else: try inference, degrade to unresolved (never a wrong answer)
        TypeInference types = graph.typeInferenceFor(fileOf(throwNode));
        if (types != null) {
            try {
                TypeRef t = types.typeOf(expr);
                if (t.clazz() != null) {
                    TypeHierarchy.TypeEntry entry = hierarchy.libraryEntry(t.clazz());
                    return List.of(new Thrown(entry.name(), entry, "thrown"));
                }
                if (t.sourceDecl() != null && t.sourceDecl().name() != null) {
                    return List.of(new Thrown(t.sourceDecl().name(),
                            hierarchy.entryOf(t.sourceDecl()), "thrown"));
                }
            } catch (Throwable ignored) {
                // inference over broken context - degrade below
            }
        }
        return List.of(new Thrown("?", null, "unresolved"));
    }

    /**
     * Walk the event outward through enclosing try/catch structure; record absorptions on the
     * catches, and register the escape on {@code out} only if it survives to the method boundary.
     */
    private void route(Node event, Node decl, String typeName, TypeHierarchy.TypeEntry type,
            String tier, Path file, int line, String via, Map<String, Escape> out,
            Map<Node, List<CaughtAt>> catchHits) {
        Node child = event;
        for (Node p = event.parent(); p != null && p != decl; child = p, p = p.parent()) {
            if (p.type() == NodeType.TRY
                    && child.type() != NodeType.CATCH && child.type() != NodeType.FINALLY) {
                for (Node c : p.children(NodeType.CATCH)) {
                    for (Node ct : c.children(NodeType.TYPE)) {
                        TypeHierarchy.TypeEntry catchType =
                                ct.name() == null ? null : resolveTypeName(ct, ct.name());
                        if (type != null && catchType != null) {
                            if (hierarchy.isSubtypeOf(type, catchType)) {
                                catchHits.computeIfAbsent(c, k -> new ArrayList<>())
                                        .add(new CaughtAt(typeName, tier, file, line, false));
                                return;   // fully absorbed
                            }
                            if (hierarchy.isSubtypeOf(catchType, type)) {
                                // the catch takes a subtype slice; the rest keeps escaping
                                catchHits.computeIfAbsent(c, k -> new ArrayList<>())
                                        .add(new CaughtAt(typeName, tier, file, line, true));
                            }
                        }
                        // unresolved thrown/catch type: absorption unprovable - keep escaping
                    }
                }
            }
        }
        out.putIfAbsent(typeName, new Escape(typeName, type, tier, file, line, via));
    }

    /** The TYPE nodes after the {@code throws} keyword in a declaration's own token stream. */
    private static List<Node> throwsClauseTypes(Node decl) {
        int throwsOffset = -1;
        for (Token t : decl.tokens()) {
            if (t.type() == TokenType.THROWS) {
                throwsOffset = t.startOffset();
                break;
            }
        }
        if (throwsOffset < 0) {
            return List.of();
        }
        List<Node> out = new ArrayList<>();
        for (Node c : decl.children(NodeType.TYPE)) {
            Token first = c.firstToken();
            if (first != null && first.startOffset() > throwsOffset) {
                out.add(c);
            }
        }
        return out;
    }

    private TypeHierarchy.TypeEntry resolveTypeName(Node at, String name) {
        if (scopeOf(at) == null) {
            return null;
        }
        Symbol s = scopeOf(at).resolveType(name);
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

    private Path fileOf(Node n) {
        Node p = n;
        while (p.parent() != null) {
            p = p.parent();
        }
        return rootFiles.getOrDefault(p, Path.of("?"));
    }

    private static int lineOf(Node n) {
        Token t = n.firstCodeToken();
        return t == null ? 0 : t.line();
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** The scope of a node, from its file's resolver; null when the file has none. */
    private Scope scopeOf(Node n) {
        SymbolResolver r = graph.sources().resolverFor(fileOf(n));
        return r == null ? null : r.scopeOf(n);
    }
}
