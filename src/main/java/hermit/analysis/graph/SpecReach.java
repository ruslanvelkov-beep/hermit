package hermit.analysis.graph;

import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.sym.TypeHierarchy;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spec reachability: which spec verbs (the static factories of the verb classes a spec test is written with) reach a
 * given production method. Computed PURELY from the parse trees, in three joins:
 *
 * <ol>
 *   <li><b>reverse callers</b> - walk {@code Callee → Caller} edges from the target until a method of a
 *       HANDLER type is reached (the class that the request/query dispatcher hands the request to);</li>
 *   <li><b>dispatch table</b> - the dispatcher's {@code switch (request.kind()) { case KIND ->
 *       handlers.accessor(); … }} joined with the handler records' components gives handler type → KIND
 *       (e.g. {@code CheckoutHandler → CHECKOUT});</li>
 *   <li><b>verb catalogue</b> - every spec op class declares {@code <functionality type> type()}; the verb
 *       factories are the static methods returning that op class. KIND and functionality are matched by
 *       name modulo case and underscores ({@code SHELF_LOOKUP ≡ ShelfLookup}).</li>
 * </ol>
 *
 * The dispatcher, record and functionality type names are the project's {@link SpecConventions}. Everything is
 * reported with provenance; when no handler is on any caller chain the topmost callers (entry points) are reported
 * instead, so "not spec-reachable" is an explicit answer, not silence.
 */
public final class SpecReach {

    /** A spec verb: {@code checkoutBook() [CommandVerbs] → SpecCheckout (Checkout)}. */
    public record Verb(String name, String owner, String ownerFqn, String signature, String opClass,
            String functionality) {
        public String render() {
            return name + signature + "  [" + owner + "] → " + opClass + " (" + functionality + ")";
        }
    }

    /** One way in: a handler method, its dispatch KIND, the call path from it down to the target, verbs. */
    public record Route(String handlerMethod, String kind, List<String> path, List<Verb> verbs,
            String kindMatch) {}

    public record Result(String target, List<Route> routes, List<String> entryPoints,
            List<String> testSupportEntryPoints, List<String> notes) {
        /** Kept for callers that do not distinguish the two buckets. */
        public Result(String target, List<Route> routes, List<String> entryPoints, List<String> notes) {
            this(target, routes, entryPoints, List.of(), notes);
        }

        public boolean specReachable() {
            return routes.stream().anyMatch(r -> !r.verbs().isEmpty());
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            if (routes.isEmpty()) {
                sb.append("NO HANDLER on any caller chain of ").append(target).append('\n');
            }
            for (Route r : routes) {
                sb.append(r.handlerMethod()).append("  [kind ").append(r.kind() == null ? "?" : r.kind())
                        .append(r.kindMatch() == null ? "" : ", " + r.kindMatch()).append("]  path: ")
                        .append(String.join(" <- ", r.path())).append('\n');
                if (r.verbs().isEmpty()) {
                    sb.append("    verbs: (none found for this kind)\n");
                }
                for (Verb v : r.verbs()) {
                    sb.append("    verb: ").append(v.render()).append('\n');
                }
            }
            if (!entryPoints.isEmpty()) {
                sb.append("entry points (production callers with no callers): ")
                        .append(String.join(", ", entryPoints)).append('\n');
            }
            if (!testSupportEntryPoints.isEmpty()) {
                // these reach the method STATICALLY through a shared caller, and they live in test sources
                sb.append("test-support entry points (test sources / spec infrastructure — they reach the method "
                        + "statically via a shared caller, which is NOT evidence they exercise this path; use "
                        + "CodeGraph action=tests for ranked candidates): ")
                        .append(String.join(", ", testSupportEntryPoints)).append('\n');
            }
            for (String n : notes) {
                sb.append("note: ").append(n).append('\n');
            }
            return sb.toString();
        }
    }

    private final CodeGraph graph;
    private final SpecConventions conventions = SpecConventions.load();
    private final TypeHierarchy hierarchy;
    /** handler simple type → dispatch KINDs (usually one). */
    private final Map<String, Set<String>> kindsByHandler = new LinkedHashMap<>();
    /** op class simple name → functionality constant name (from its {@code type()} method). */
    private final Map<String, String> functionalityByOp = new LinkedHashMap<>();
    /** all verb factories found (static methods returning an op class). */
    private final List<Verb> verbs = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private SpecReach(CodeGraph graph) {
        this.graph = graph;
        this.hierarchy = graph.hierarchy();
        buildDispatchTable();
        buildVerbCatalogue();
    }

    /** Which verbs reach {@code targetSpec} (walking at most {@code maxDepth} caller hops). */
    public static Result of(CodeGraph graph, String targetSpec, int maxDepth) {
        SpecReach h = new SpecReach(graph);
        return h.routesTo(targetSpec, maxDepth);
    }

    /** The verb catalogue filtered by a functionality/kind/op-class/verb-name fragment (case-insensitive). */
    public static List<Verb> verbs(CodeGraph graph, String query) {
        SpecReach h = new SpecReach(graph);
        if (query == null || query.isBlank()) {
            return List.copyOf(h.verbs);
        }
        String q = norm(query);
        List<Verb> out = new ArrayList<>();
        for (Verb v : h.verbs) {
            if (norm(v.name()).contains(q) || norm(v.opClass()).contains(q)
                    || norm(v.functionality()).contains(q)) {
                out.add(v);
            }
        }
        return out;
    }

    /** All handler types the dispatch tables know, with their kinds (for callers wanting the raw table). */
    public static Map<String, Set<String>> dispatchTable(CodeGraph graph) {
        return new SpecReach(graph).kindsByHandler;
    }

    /** Verbs whose op dispatches to {@code handlerType} (by KIND match). */
    public static List<Verb> verbsForHandler(CodeGraph graph, String handlerType) {
        SpecReach h = new SpecReach(graph);
        List<Verb> out = new ArrayList<>();
        for (String kind : h.kindsByHandler.getOrDefault(handlerType, Set.of())) {
            out.addAll(h.verbsForKind(kind).verbs);
        }
        return out;
    }

    // ---- 1. reverse callers ----------------------------------------------------------------------------------

    private Result routesTo(String targetSpec, int maxDepth) {
        List<CodeGraph.MethodRef> targets = graph.locate(targetSpec);
        if (targets.isEmpty()) {
            return new Result(targetSpec, List.of(), List.of(), List.of("target not found: " + targetSpec));
        }
        record Step(CodeGraph.MethodRef at, List<String> path) {}
        Deque<Step> queue = new ArrayDeque<>();
        Set<Node> visited = new HashSet<>();
        for (CodeGraph.MethodRef t : targets) {
            queue.add(new Step(t, List.of(t.spec())));
            visited.add(t.decl());
        }
        Map<String, Route> routes = new LinkedHashMap<>();
        LinkedHashSet<String> entryPoints = new LinkedHashSet<>();
        LinkedHashSet<String> testSupport = new LinkedHashSet<>();
        boolean truncated = false;
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            if (isHandlerMethod(step.at())) {
                addRoute(routes, step.at(), step.path());
                continue;   // a handler is an endpoint of the reverse walk
            }
            if (step.path().size() > maxDepth) {
                truncated = true;
                continue;
            }
            List<CodeGraph.MethodRef> callers = callersOf(step.at());
            if (callers.isEmpty()) {
                if (!step.at().spec().equals(targetSpec) || targets.size() == 1) {
                    (isTestSupport(step.at()) ? testSupport : entryPoints).add(step.at().spec());
                }
                continue;
            }
            for (CodeGraph.MethodRef c : callers) {
                if (!visited.add(c.decl())) {
                    continue;
                }
                List<String> path = new ArrayList<>(step.path());
                path.add(c.spec());
                queue.add(new Step(c, path));
            }
        }
        List<String> out = new ArrayList<>(notes);
        if (truncated) {
            out.add("caller walk truncated at depth " + maxDepth + " on some branches");
        }
        if (kindsByHandler.isEmpty()) {
            out.add("no dispatch table found (" + String.join("/", conventions.dispatchers()) + " + handlers records) "
                    + "— handlers recognised by *" + conventions.handlerSuffix() + " naming + entry method only");
        }
        List<String> eps = cap(entryPoints);
        List<String> testEps = cap(testSupport);
        return new Result(targetSpec, new ArrayList<>(routes.values()), eps, testEps, out);
    }

    private static List<String> cap(Collection<String> specs) {
        List<String> out = new ArrayList<>(specs);
        if (out.size() > 12) {
            int more = out.size() - 12;
            out = new ArrayList<>(out.subList(0, 12));
            out.add("… " + more + " more");
        }
        return out;
    }

    /**
     * The spec reach report once called spec infrastructure (extensions, the verb classes, execution listeners)
     * "production callers" because it matched only {@code *Test#} names. Spec infrastructure lives in test sources (or
     * a spec-test module) and in the packages the conventions name - classify by SOURCE SET and package, never by the
     * type's name alone.
     */
    static boolean isTestSupport(CodeGraph.MethodRef m) {
        String path = m.file() == null ? "" : m.file().toString().replace('\\', '/');
        if (path.contains("/src/test/") || path.contains("/src/testFixtures/") || path.contains("/src/itest/")
                || path.contains("/src/integrationTest/") || path.contains("/testFixtures/")) {
            return true;
        }
        for (String marker : SpecConventions.load().specSourceMarkers()) {
            if (path.contains(marker)) {
                return true;
            }
        }
        return m.typeName().endsWith("Test") || m.typeName().endsWith("Tests");
    }

    private List<CodeGraph.MethodRef> callersOf(CodeGraph.MethodRef m) {
        LinkedHashSet<CodeGraph.MethodRef> out = new LinkedHashSet<>();
        for (CodeGraph.CallerEdge e : graph.callerEdges(m.spec())) {
            out.add(e.site().caller());
        }
        if (out.isEmpty()) {
            // no RESOLVED reverse edge (interface method, or the callee resolves name-only): fall back to
            // name+receiver matching so a route is still found - tagged in the notes once
            for (CodeGraph.CallSite s : graph.callersOf(m.spec())) {
                out.add(s.caller());
            }
            if (!out.isEmpty() && !notes.contains("some caller hops are name-matched (unresolved dispatch)")) {
                notes.add("some caller hops are name-matched (unresolved dispatch)");
            }
        }
        return new ArrayList<>(out);
    }

    private boolean isHandlerMethod(CodeGraph.MethodRef m) {
        if (kindsByHandler.containsKey(m.typeName())) {
            // a handler's private helpers are walked up to the dispatcher-invoked entry method
            return conventions.handlerEntryMethods().contains(m.methodName());
        }
        // fallback when no dispatch table exists in this repo
        return kindsByHandler.isEmpty() && m.typeName().endsWith(conventions.handlerSuffix())
                && conventions.handlerEntryMethods().contains(m.methodName());
    }

    private void addRoute(Map<String, Route> routes, CodeGraph.MethodRef handler, List<String> path) {
        if (routes.containsKey(handler.spec())) {
            return;
        }
        Set<String> kinds = kindsByHandler.getOrDefault(handler.typeName(), Set.of());
        if (kinds.isEmpty()) {
            routes.put(handler.spec(), new Route(handler.spec(), null, path, List.of(), null));
            return;
        }
        for (String kind : kinds) {
            KindVerbs kv = verbsForKind(kind);
            routes.put(handler.spec() + "@" + kind,
                    new Route(handler.spec(), kind, path, kv.verbs, kv.matchNote));
        }
    }

    // ---- 2. dispatch table --------------------------------------------------------------------------------------

    private void buildDispatchTable() {
        // record components: accessor name → handler type, per handlers record
        Map<String, String> handlerByAccessor = new HashMap<>();
        for (String rec : conventions.handlerRecords()) {
            for (TypeHierarchy.TypeEntry e : hierarchy.entries(rec)) {
                if (e.decl() == null) {
                    continue;
                }
                for (Node comp : e.decl().descendants(NodeType.RECORD_COMPONENT)) {
                    Node type = comp.child(NodeType.TYPE);
                    if (comp.name() != null && type != null && type.name() != null) {
                        handlerByAccessor.put(comp.name(), type.name());
                    }
                }
            }
        }
        // dispatcher switch arms: case KIND -> handlers.accessor()
        for (String disp : conventions.dispatchers()) {
            for (TypeHierarchy.TypeEntry e : hierarchy.entries(disp)) {
                if (e.decl() == null) {
                    continue;
                }
                List<Node> switches = new ArrayList<>(e.decl().descendants(NodeType.SWITCH_FUNCTION));
                switches.addAll(e.decl().descendants(NodeType.SWITCH));
                for (Node sw : switches) {
                    for (Node c : sw.children(NodeType.CASE)) {
                        int op = operatorOffset(c);
                        List<String> labels = new ArrayList<>();
                        String accessor = null;
                        for (Node k : c.children()) {
                            Token first = k.firstCodeToken();
                            boolean isLabel = first != null && op >= 0 && first.startOffset() < op;
                            if (isLabel && k.name() != null) {
                                labels.add(k.name());
                            } else if (!isLabel) {
                                for (Node call : callsIn(k)) {
                                    if (handlerByAccessor.containsKey(call.name())) {
                                        accessor = call.name();
                                    }
                                }
                            }
                        }
                        if (accessor != null) {
                            String handler = handlerByAccessor.get(accessor);
                            for (String kind : labels) {
                                kindsByHandler.computeIfAbsent(handler, k -> new LinkedHashSet<>()).add(kind);
                            }
                        }
                    }
                }
            }
        }
    }

    private static List<Node> callsIn(Node n) {
        List<Node> out = new ArrayList<>();
        if (n.type() == NodeType.METHOD_CALL) {
            out.add(n);
        }
        out.addAll(n.descendants(NodeType.METHOD_CALL));
        return out;
    }

    private static int operatorOffset(Node caseNode) {
        for (Token t : caseNode.tokens()) {
            if (t.type() == TokenType.ARROW || t.type() == TokenType.COLON) {
                return t.startOffset();
            }
        }
        return -1;
    }

    // ---- 3. verb catalogue ---------------------------------------------------------------------------------------

    private void buildVerbCatalogue() {
        // op classes: any source type with a `type()` method returning a constant of the functionality type
        for (TypeHierarchy.TypeEntry e : hierarchy.sourceEntries()) {
            Node decl = e.decl();
            if (decl == null) {
                continue;
            }
            for (Node m : methodsOf(decl)) {
                if (!"type".equals(m.name()) || !m.children(NodeType.PARAMETER).isEmpty()) {
                    continue;
                }
                Node ret = m.child(NodeType.TYPE);
                if (ret == null || !conventions.functionalityType().equals(ret.name())) {
                    continue;
                }
                String constant = returnedConstant(m);
                if (constant != null) {
                    functionalityByOp.put(e.name(), constant);
                }
            }
        }
        // verb factories: static methods returning an op class, declared in a *Verbs (or any) class
        for (TypeHierarchy.TypeEntry e : hierarchy.sourceEntries()) {
            Node decl = e.decl();
            if (decl == null) {
                continue;
            }
            for (Node m : methodsOf(decl)) {
                if (!m.hasModifier(TokenType.STATIC) || m.name() == null) {
                    continue;
                }
                Node ret = m.child(NodeType.TYPE);
                if (ret == null || ret.name() == null || !functionalityByOp.containsKey(ret.name())) {
                    continue;
                }
                verbs.add(new Verb(m.name(), e.name(), e.fqn(), signatureOf(m), ret.name(),
                        functionalityByOp.get(ret.name())));
            }
        }
    }

    private static List<Node> methodsOf(Node typeDecl) {
        Node body = typeDecl.child(NodeType.CLASS_BODY);
        return body == null ? List.of() : body.children(NodeType.METHOD_DECLARATION);
    }

    private static String returnedConstant(Node method) {
        for (Node r : method.descendants(NodeType.RETURN)) {
            for (Node v : r.children()) {
                if ((v.type() == NodeType.IDENTIFIER_EXPRESSION || v.type() == NodeType.FIELD_ACCESS)
                        && v.name() != null) {
                    return v.name();
                }
            }
        }
        return null;
    }

    private static String signatureOf(Node method) {
        List<String> ps = new ArrayList<>();
        for (Node p : method.children(NodeType.PARAMETER)) {
            ps.add(p.toString().strip().replaceAll("\\s+", " "));
        }
        return "(" + String.join(", ", ps) + ")";
    }

    private record KindVerbs(List<Verb> verbs, String matchNote) {}

    private KindVerbs verbsForKind(String kind) {
        String k = norm(kind);
        List<Verb> exact = new ArrayList<>();
        List<Verb> prefix = new ArrayList<>();
        for (Verb v : verbs) {
            String f = norm(v.functionality());
            if (f.equals(k)) {
                exact.add(v);
            } else if (k.startsWith(f) || f.startsWith(k)) {
                prefix.add(v);
            }
        }
        if (!exact.isEmpty()) {
            return new KindVerbs(exact, null);
        }
        if (!prefix.isEmpty()) {
            return new KindVerbs(prefix, "functionality matched by prefix");
        }
        return new KindVerbs(List.of(), null);
    }

    public static String norm(String s) {
        return s == null ? "" : s.replace("_", "").toUpperCase(Locale.ROOT);
    }
}
