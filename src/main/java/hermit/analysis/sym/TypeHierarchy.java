package hermit.analysis.sym;

import hermit.analysis.lex.TokenType;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseTree;
import java.lang.reflect.Method;
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
import java.util.Objects;
import java.util.Set;

/**
 * Builds the full type hierarchy from a SourceIndex: supertype/subtype edges, method visibility, and dispatch
 * candidates.
 */
public final class TypeHierarchy {
    /** One type: a source declaration ({@code decl != null}) or a library class ({@code clazz != null}). */
    public static final class TypeEntry {
        private final String name;
        private final String fqn;
        private final Node decl;          // type decl node, or the CLASS_BODY of an anonymous class
        private final Class<?> clazz;
        private final boolean iface;
        private final List<TypeEntry> supertypes = new ArrayList<>();
        private final List<TypeEntry> subtypes = new ArrayList<>();

        private TypeEntry(String name, String fqn, Node decl, Class<?> clazz, boolean iface) {
            this.name = name;
            this.fqn = fqn;
            this.decl = decl;
            this.clazz = clazz;
            this.iface = iface;
        }

        public String name() {
            return name;
        }

        public String fqn() {
            return fqn;
        }

        public Node decl() {
            return decl;
        }

        public Class<?> clazz() {
            return clazz;
        }

        public boolean isInterface() {
            return iface;
        }

        public List<TypeEntry> supertypes() {
            return List.copyOf(supertypes);
        }

        public List<TypeEntry> subtypes() {
            return List.copyOf(subtypes);
        }

        @Override
        public String toString() {
            return fqn + (iface ? " (interface)" : "") + (clazz != null ? " [library]" : "");
        }
    }

    /**
     * One callable: a source declaration ({@code decl}) or a library {@link Method} ({@code reflect}).
     * {@code concrete} = it has a body (interface {@code default} methods included) - an abstract
     * declaration is a dispatch TARGET SPEC, never a runtime dispatch candidate.
     */
    public record MethodDecl(TypeEntry owner, String name, int params, boolean varargs,
            Node decl, Method reflect, boolean concrete) {
        public String spec() {
            return owner.name() + "#" + name;
        }

        @Override
        public String toString() {
            return spec() + "(" + params + (varargs ? "+" : "") + " args)"
                    + (reflect != null ? " [library]" : "") + (concrete ? "" : " [abstract]");
        }
    }

    public static TypeHierarchy build(SourceIndex sources) {
        TypeHierarchy h = new TypeHierarchy();
        List<Node> anonBodies = new ArrayList<>();
        for (Path f : sources.files()) {
            ParseTree tree = sources.treeOf(f);
            if (tree == null) {
                continue;
            }
            try {
                sources.resolverFor(f);   // scopes (and their supertype links) exist only after this
            } catch (Throwable ignored) {
                // a broken file keeps its entries but contributes no hierarchy edges
            }
            String pkg = tree.packageName();
            tree.root().walk(n -> {
                if (n.type().isTypeDeclaration() && n.name() != null) {
                    h.register(new TypeEntry(n.name(), fqnOf(pkg, n), n, null,
                            n.type() == NodeType.INTERFACE_DECLARATION
                                    || n.type() == NodeType.ANNOTATION_TYPE_DECLARATION));
                } else if (n.type() == NodeType.CLASS_BODY && n.parent() != null
                        && (n.parent().type() == NodeType.NEW_INSTANCE
                                || n.parent().type() == NodeType.ENUM_CONSTANT)) {
                    anonBodies.add(n);
                }
            });
        }
        // Second pass: supertypes that could not be linked while their file was being resolved (a resolution cycle -
        // e.g. an interface statically importing a member of its implementation) get retried now that every file has
        // its scopes. Without this retry such an `implements` edge would be missing, and every walk over subtype edges
        // (subtypes, dispatch candidates) would miss the implementation.
        for (Path f : sources.files()) {
            SymbolResolver r = sources.resolverFor(f);
            if (r != null) {
                r.relinkSupertypes();
            }
        }
        // anonymous/enum-constant bodies: overriders that dispatch resolution must see
        for (Node body : anonBodies) {
            String outer = enclosingTypeName(body);
            int line = body.firstToken() == null ? 0 : body.firstToken().line();
            String name = (outer == null ? "" : outer) + "$anon";
            h.register(new TypeEntry(name, name + "@" + line, body, null, false));
        }
        for (TypeEntry e : List.copyOf(h.byDecl.values())) {
            h.link(e, sources);
        }
        return h;
    }

    public TypeEntry libraryEntry(Class<?> c) {
        if (c == null) {
            return null;
        }
        TypeEntry cached = byClass.get(c);
        if (cached != null) {
            return cached;
        }
        TypeEntry e = new TypeEntry(safeSimpleName(c), c.getName(), null, c, c.isInterface());
        byClass.put(c, e);
        // registered BEFORE linking - cycles in bad metadata can't recurse forever
        bySimpleName.computeIfAbsent(e.name, k -> new ArrayList<>()).add(e);
        byFqn.putIfAbsent(e.fqn, e);
        try {
            Class<?> sup = c.getSuperclass();
            if (sup != null) {
                TypeEntry p = libraryEntry(sup);
                e.supertypes.add(p);
                p.subtypes.add(e);
            }
            for (Class<?> i : c.getInterfaces()) {
                TypeEntry p = libraryEntry(i);
                e.supertypes.add(p);
                p.subtypes.add(e);
            }
        } catch (Throwable ignored) {
            // partial classpath - the entry stays, its lineage is simply unknown
        }
        return e;
    }

    public List<TypeEntry> entries(String name) {
        TypeEntry exact = byFqn.get(name);
        if (exact != null) {
            return List.of(exact);
        }
        return List.copyOf(bySimpleName.getOrDefault(name, List.of()));
    }

    public TypeEntry entryOf(Node typeDecl) {
        return byDecl.get(typeDecl);
    }

    public List<TypeEntry> sourceEntries() {
        return List.copyOf(byFqn.values()).stream().filter(e -> e.decl != null).toList();
    }

    /**
     * EVERY method a type declares, overloads included. {@link #declaredMethods} is keyed by name/arity and so
     * collapses same-arity overloads; overload selection needs them all.
     */
    public List<MethodDecl> allDeclaredMethods(TypeEntry e) {
        List<MethodDecl> out = new ArrayList<>();
        if (e.clazz != null) {
            try {
                for (Method m : e.clazz.getMethods()) {
                    out.add(new MethodDecl(e, m.getName(), m.getParameterCount(), m.isVarArgs(), null, m,
                            !java.lang.reflect.Modifier.isAbstract(m.getModifiers())));
                }
            } catch (Throwable ignored) {
                // partial classpath
            }
            return out;
        }
        if (e.decl == null) {
            return out;
        }
        Node body = e.decl.type() == NodeType.CLASS_BODY ? e.decl : e.decl.child(NodeType.CLASS_BODY);
        if (body != null) {
            for (Node m : body.children(NodeType.METHOD_DECLARATION)) {
                if (m.name() == null) {
                    continue;
                }
                List<Node> params = m.children(NodeType.PARAMETER);
                boolean varargs = !params.isEmpty() && params.get(params.size() - 1).tokens().stream()
                        .anyMatch(t -> t.type() == TokenType.ELLIPSIS);
                out.add(new MethodDecl(e, m.name(), params.size(), varargs, m, null,
                        m.child(NodeType.BLOCK) != null));
            }
        }
        return out;
    }

    /** The methods DECLARED directly on {@code e} (record accessors included), keyed name/arity. */
    public Map<String, MethodDecl> declaredMethods(TypeEntry e) {
        return declaredCache.computeIfAbsent(e, TypeHierarchy::computeDeclared);
    }

    /** Declared + inherited methods visible on {@code e} - the nearest declaration (override) wins. */
    public Map<String, MethodDecl> visibleMethods(TypeEntry e) {
        Map<String, MethodDecl> cached = visibleCache.get(e);
        if (cached != null) {
            return cached;
        }
        Map<String, MethodDecl> out = new LinkedHashMap<>();
        Deque<TypeEntry> queue = new ArrayDeque<>();
        Set<TypeEntry> seen = new HashSet<>();
        queue.add(e);
        while (!queue.isEmpty()) {
            TypeEntry at = queue.poll();
            if (!seen.add(at)) {
                continue;
            }
            declaredMethods(at).forEach(out::putIfAbsent);   // BFS: nearer declarations land first
            queue.addAll(at.supertypes);
        }
        visibleCache.put(e, out);
        return out;
    }

    /**
     * Overload-aware resolution: {@link #visibleMethods} is keyed by name/arity, so same-arity overloads collapse and
     * the last one inserted wins - a call whose argument fits only another overload of the same arity would resolve to
     * the wrong declaration. {@code argTypes} carries the SIMPLE type name of each argument value
     * (null = unknown, matches anything). When the types single out one candidate it is returned; otherwise the
     * arity-only answer stands, so callers never lose a resolution they used to get.
     */
    public MethodDecl resolveCall(TypeEntry e, String name, int argCount, List<String> argTypes) {
        MethodDecl byArity = resolveCall(e, name, argCount);
        if (argTypes == null || argTypes.isEmpty() || argTypes.stream().allMatch(Objects::isNull)) {
            return byArity;
        }
        List<MethodDecl> candidates = new ArrayList<>();
        for (TypeEntry at : selfAndSupertypes(e)) {
            for (MethodDecl d : allDeclaredMethods(at)) {
                if (d.name().equals(name) && arityOk(d, argCount) && !candidates.contains(d)) {
                    candidates.add(d);
                }
            }
        }
        if (candidates.size() < 2) {
            return byArity;
        }
        List<MethodDecl> exact = new ArrayList<>();
        for (MethodDecl d : candidates) {
            List<String> params = parameterTypeNames(d);
            if (params.size() != argTypes.size()) {
                continue;
            }
            boolean all = true;
            for (int i = 0; i < params.size(); i++) {
                String want = params.get(i);
                String got = argTypes.get(i);
                if (got == null || want == null) {
                    continue;   // unknown on either side matches
                }
                if (!simpleOf(want).equals(simpleOf(got))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                exact.add(d);
            }
        }
        return exact.size() == 1 ? exact.get(0) : byArity;
    }

    /** The declared parameter type names of a method (source or reflected); null for an unreadable one. */
    public List<String> parameterTypeNames(MethodDecl d) {
        List<String> out = new ArrayList<>();
        if (d.decl() != null) {
            for (Node p : d.decl().children(NodeType.PARAMETER)) {
                Node t = p.child(NodeType.TYPE);
                out.add(t == null ? null : t.toString().strip().replaceAll("\\s+", ""));
            }
            return out;
        }
        if (d.reflect() != null) {
            for (Class<?> c : d.reflect().getParameterTypes()) {
                out.add(c.getSimpleName());
            }
        }
        return out;
    }

    /**
     * The visible method {@code name} on {@code e} accepting {@code argCount} arguments (or null).
     * {@code argCount < 0} means "any arity" (method references don't know theirs).
     */
    public MethodDecl resolveCall(TypeEntry e, String name, int argCount) {
        Map<String, MethodDecl> visible = visibleMethods(e);
        if (argCount >= 0) {
            MethodDecl exact = visible.get(key(name, argCount));
            if (exact != null) {
                return exact;
            }
        }
        for (MethodDecl d : visible.values()) {
            if (d.name().equals(name) && arityOk(d, argCount)) {
                return d;
            }
        }
        return null;
    }

    /**
     * DISPATCH CANDIDATES for {@code name(argCount)} on a receiver statically typed {@code e}: the
     * visible declaration itself plus every override/implementation declared in {@code e}'s subtype
     * cone (anonymous classes included). Order: static target first, then cone in BFS order.
     */
    public List<MethodDecl> dispatchCandidates(TypeEntry e, String name, int argCount) {
        List<MethodDecl> out = new ArrayList<>();
        MethodDecl statically = resolveCall(e, name, argCount);
        if (statically != null) {
            out.add(statically);
        }
        Deque<TypeEntry> queue = new ArrayDeque<>(e.subtypes);
        Set<TypeEntry> seen = new HashSet<>();
        seen.add(e);
        while (!queue.isEmpty()) {
            TypeEntry at = queue.poll();
            if (!seen.add(at)) {
                continue;
            }
            for (MethodDecl d : declaredMethods(at).values()) {
                if (d.name().equals(name) && arityOk(d, argCount)
                        && (statically == null || d.decl() != statically.decl()
                                || d.reflect() != statically.reflect())) {
                    out.add(d);
                }
            }
            queue.addAll(at.subtypes);
        }
        return out;
    }

    /** Is {@code sub} the same type as, or a transitive subtype of, {@code sup}? */
    public boolean isSubtypeOf(TypeEntry sub, TypeEntry sup) {
        if (sub == null || sup == null) {
            return false;
        }
        if (sub == sup) {
            return true;
        }
        if (sub.clazz != null && sup.clazz != null) {
            try {
                return sup.clazz.isAssignableFrom(sub.clazz);
            } catch (Throwable ignored) {
                // partial classpath - fall through to the entry walk
            }
        }
        Deque<TypeEntry> queue = new ArrayDeque<>(sub.supertypes);
        Set<TypeEntry> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeEntry at = queue.poll();
            if (at == sup) {
                return true;
            }
            if (seen.add(at)) {
                queue.addAll(at.supertypes);
            }
        }
        return false;
    }

    private final Map<Node, TypeEntry> byDecl = new IdentityHashMap<>();

    private final Map<Class<?>, TypeEntry> byClass = new IdentityHashMap<>();

    private final Map<String, List<TypeEntry>> bySimpleName = new HashMap<>();

    private final Map<String, TypeEntry> byFqn = new LinkedHashMap<>();

    private final Map<TypeEntry, Map<String, MethodDecl>> declaredCache = new IdentityHashMap<>();

    private final Map<TypeEntry, Map<String, MethodDecl>> visibleCache = new IdentityHashMap<>();

    private TypeHierarchy() {
    }

    private void register(TypeEntry e) {
        byDecl.put(e.decl, e);
        bySimpleName.computeIfAbsent(e.name, k -> new ArrayList<>()).add(e);
        byFqn.putIfAbsent(e.fqn, e);
    }

    private static String safeSimpleName(Class<?> c) {
        try {
            return c.getSimpleName();
        } catch (Throwable t) {
            String name = c.getName();
            int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
            return cut < 0 ? name : name.substring(cut + 1);
        }
    }

    private void link(TypeEntry e, SourceIndex sources) {
        Scope s = sources.scopeOf(e.decl);
        if (s == null) {
            return;
        }
        for (Scope sup : s.superScopes) {
            TypeEntry parent = byDecl.get(sup.owner());
            if (parent != null && parent != e) {
                e.supertypes.add(parent);
                parent.subtypes.add(e);
            }
        }
        for (Class<?> sup : s.superClasses) {
            TypeEntry parent = libraryEntry(sup);
            if (parent != null) {
                e.supertypes.add(parent);
                parent.subtypes.add(e);
            }
        }
    }

    private static String fqnOf(String pkg, Node decl) {
        StringBuilder nest = new StringBuilder(decl.name());
        for (Node p = decl.parent(); p != null; p = p.parent()) {
            if (p.type().isTypeDeclaration() && p.name() != null) {
                nest.insert(0, p.name() + ".");
            }
        }
        return pkg == null || pkg.isEmpty() ? nest.toString() : pkg + "." + nest;
    }

    private static String enclosingTypeName(Node n) {
        for (Node p = n.parent(); p != null; p = p.parent()) {
            if (p.type().isTypeDeclaration() && p.name() != null) {
                return p.name();
            }
        }
        return null;
    }

    private static Map<String, MethodDecl> computeDeclared(TypeEntry e) {
        Map<String, MethodDecl> out = new LinkedHashMap<>();
        if (e.clazz != null) {
            try {
                for (Method m : e.clazz.getMethods()) {
                    MethodDecl d = new MethodDecl(e, m.getName(), m.getParameterCount(),
                            m.isVarArgs(), null, m,
                            !java.lang.reflect.Modifier.isAbstract(m.getModifiers()));
                    out.putIfAbsent(key(d.name(), d.params()), d);
                }
            } catch (Throwable ignored) {
                // partial classpath - no members enumerable
            }
            return out;
        }
        Node body = e.decl.type() == NodeType.CLASS_BODY ? e.decl : e.decl.child(NodeType.CLASS_BODY);
        if (body != null) {
            for (Node m : body.children(NodeType.METHOD_DECLARATION)) {
                if (m.name() == null) {
                    continue;
                }
                List<Node> params = m.children(NodeType.PARAMETER);
                boolean varargs = !params.isEmpty() && params.get(params.size() - 1).tokens().stream()
                        .anyMatch(t -> t.type() == TokenType.ELLIPSIS);
                out.putIfAbsent(key(m.name(), params.size()),
                        new MethodDecl(e, m.name(), params.size(), varargs, m, null,
                                m.child(NodeType.BLOCK) != null));
            }
        }
        for (Node rc : e.decl.children(NodeType.RECORD_COMPONENT)) {
            if (rc.name() != null) {
                out.putIfAbsent(key(rc.name(), 0),
                        new MethodDecl(e, rc.name(), 0, false, rc, null, true));
            }
        }
        return out;
    }

    private static String key(String name, int params) {
        return name + "/" + params;
    }

    // Checks whether a method accepts the requested arity.
    private static boolean arityOk(MethodDecl d, int argCount) {
        return argCount < 0 || d.params() == argCount || d.varargs() && argCount >= d.params() - 1;
    }

    /** {@code a.b.C.Inner} / {@code C.Inner} / {@code List<X>} → {@code Inner} / {@code List}. */
    private static String simpleOf(String typeName) {
        String t = typeName.trim();
        int lt = t.indexOf('<');
        if (lt > 0) {
            t = t.substring(0, lt);
        }
        int cut = Math.max(t.lastIndexOf('.'), t.lastIndexOf('$'));
        return cut < 0 ? t : t.substring(cut + 1);
    }

    /** {@code e} and every supertype, in BFS order. */
    private List<TypeEntry> selfAndSupertypes(TypeEntry e) {
        List<TypeEntry> out = new ArrayList<>();
        Deque<TypeEntry> queue = new ArrayDeque<>();
        queue.add(e);
        while (!queue.isEmpty()) {
            TypeEntry at = queue.poll();
            if (at == null || out.contains(at)) {
                continue;
            }
            out.add(at);
            queue.addAll(at.supertypes());
        }
        return out;
    }
}
