package hermit.analysis.sym;

import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseTree;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The central state holder that builds the scope chain for a ParseTree, registers declarations, links supertypes, and
 * validates names.
 */
public final class SymbolResolver {
    public static SymbolResolver resolve(ParseTree tree) {
        return resolve(tree, ClasspathIndex.system(), null);
    }

    public static SymbolResolver resolve(ParseTree tree, ClasspathIndex index) {
        return resolve(tree, index, null);
    }

    public static SymbolResolver resolve(ParseTree tree, ClasspathIndex index, SourceIndex sources) {
        SymbolResolver r = new SymbolResolver(tree, index, sources);
        r.build(tree.root(), null);
        r.linkSupertypes();
        return r;
    }

    public ClasspathIndex index() {
        return index;
    }

    public TypeInference typeInference() {
        return new TypeInference(this);
    }

    public Scope unitScope() {
        return unitScope;
    }

    public Scope scopeOf(Node n) {
        for (Node cur = n; cur != null; cur = cur.parent()) {
            Scope s = ownScope(cur);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    public Symbol symbolOf(Node n) {
        return resolvedSymbols.get(n);
    }

    public Set<String> expansionOf(Node importDecl) {
        String name = ParseTree.importedName(importDecl);
        TreeSet<String> expansion = new TreeSet<>();
        if (ParseTree.isWildcardImport(importDecl)) {
            String owner = name.substring(0, name.length() - 2);
            if (ParseTree.isStaticImport(importDecl)) {
                Class<?> ownerClass = index.load(owner);
                if (ownerClass != null) {
                    for (java.lang.reflect.Field field : ownerClass.getFields()) {
                        expansion.add(field.getName());
                    }
                    for (java.lang.reflect.Method method : ownerClass.getMethods()) {
                        expansion.add(method.getName());
                    }
                }
            } else {
                expansion.addAll(index.classesIn(owner));
                if (sources != null) {
                    expansion.addAll(sources.typesInPackage(owner));
                }
            }
        } else {
            expansion.add(name.substring(name.lastIndexOf('.') + 1));
        }
        return expansion;
    }

    public int relinkSupertypes() {
        int linked = 0;
        for (PendingSuper ps : new ArrayList<>(deferred)) {
            if (linkOne(ps)) {
                deferred.remove(ps);
                linked++;
            }
        }
        return linked;
    }

    public List<String> unlinkedSupertypes() {
        List<String> out = new ArrayList<>();
        for (PendingSuper ps : deferred) {
            out.add(dottedName(ps.typeNode()));
        }
        return out;
    }

    public List<String> validate() {
        return validate(tree.root());
    }

    public List<String> validate(Node subtree) {
        List<String> out = new ArrayList<>();
        check(subtree, out);
        return out;
    }

    // The parsed compilation unit resolved by this resolver.
    private final ParseTree tree;

    // The classpath used to resolve imported types.
    private final ClasspathIndex index;

    // The optional index of source types.
    private final SourceIndex sources;

    // Creates a resolver for a tree and its available indexes.
    private SymbolResolver(ParseTree tree, ClasspathIndex index, SourceIndex sources) {
        this.tree = tree;
        this.index = index;
        this.sources = sources;
    }

    // The scope owning the whole compilation unit.
    private Scope unitScope;

    // Each node's owned scope; State Location: the Node itself holds no scope.
    private final Map<Node, Scope> ownScopes = new IdentityHashMap<>();

    // A supertype reference deferred until its type scope exists.
    private record PendingSuper(Scope typeScope, Node typeNode, boolean isExtends) {}

    // Deferred supertype links, resolved after the walk.
    private final List<PendingSuper> pending = new ArrayList<>();

    // Creates a scope, records it under its owner, and gives it this map's lookup as its scopes function.
    private Scope newScope(Scope.Kind kind, Scope parent, Node owner) {
        Scope scope = new Scope(kind, parent, owner);
        if (owner != null) {
            ownScopes.put(owner, scope);
            if (sources != null) sources.recordScope(owner, scope);
        }
        scope.scopes = this::ownScope;
        return scope;
    }

    // Walks the tree: the compilation unit gets its own scope; everything else inherits the current one.
    private void build(Node n, Scope current) {
        switch (n.type()) {
            case COMPILATION_UNIT -> buildUnit(n);
            case CLASS_DECLARATION, INTERFACE_DECLARATION, ENUM_DECLARATION,
                 RECORD_DECLARATION, ANNOTATION_TYPE_DECLARATION, IMPLICIT_CLASS -> buildType(n, current);
            case ENUM_CONSTANT -> buildEnumConstant(n, current);
            case METHOD_DECLARATION, CONSTRUCTOR_DECLARATION, COMPACT_CONSTRUCTOR -> buildMethod(n, current);
            case FIELD_DECLARATION -> buildField(n, current);
            case VAR_DECLARATION -> buildLocal(n, current);
            case TYPE_PATTERN -> buildPattern(n, current);
            case BLOCK -> buildChildren(n, newScope(Scope.Kind.BLOCK, current, n));
            case FOR, FOR_EACH -> buildChildren(n, newScope(Scope.Kind.FOR, current, n));
            case TRY -> buildTry(n, current);
            case CATCH -> buildCatch(n, current);
            case LAMBDA -> buildLambda(n, current);
            case SWITCH, SWITCH_FUNCTION -> buildChildren(n, newScope(Scope.Kind.BLOCK, current, n));
            case CASE -> buildCase(n, current);
            case NEW_INSTANCE -> buildNewInstance(n, current);
            case IMPORT_DECLARATION, PACKAGE_DECLARATION, MODULE_DECLARATION -> { }
            default -> buildChildren(n, current);
        }
    }

    // Registers every import of the compilation unit, opening the type/static space when unprovable.
    private void processImports(Scope unit) {
        for (Node imp : tree.imports()) {
            String name = ParseTree.importedName(imp);
            boolean wildcard = name.endsWith(".*");
            if (ParseTree.isModuleImport(imp)) {
                unit.typesOpen = true;   // module imports bring whole exported packages - treat as open
                continue;
            }
            if (ParseTree.isStaticImport(imp)) {
                if (wildcard) {
                    String owner = name.substring(0, name.length() - 2);
                    unit.staticWildcardOwners.add(owner);
                    if (index.load(owner) == null && !isSourceType(owner)) {
                        unit.staticsOpen = true;
                    }
                } else {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        String owner = name.substring(0, dot);
                        unit.staticSingleImports.put(name.substring(dot + 1), owner);
                        if (index.load(owner) == null && !isSourceType(owner)) {
                            unit.staticsOpen = true;
                        }
                    }
                }
            } else if (wildcard) {
                String pkg = name.substring(0, name.length() - 2);
                unit.wildcardPackages.add(pkg);
                if (!index.hasPackage(pkg) && (sources == null || !sources.hasPackage(pkg))) {
                    unit.typesOpen = true;
                }
            } else {
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    unit.singleImports.put(name.substring(dot + 1), name);
                }
            }
        }
    }

    // Is fqn a type declared in the project sources (so its statics are enumerable)?
    private boolean isSourceType(String fqn) {
        if (sources == null) {
            return false;
        }
        int dot = fqn.lastIndexOf('.');
        return sources.resolveType(dot < 0 ? "" : fqn.substring(0, dot), fqn.substring(dot + 1)) != null;
    }

    /** Build every child of n in scope. */
    private void buildChildren(Node n, Scope scope) {
        for (Node c : n.children()) {
            build(c, scope);
        }
    }

    /**
     * A compilation unit: the UNIT scope with the package, the imports and the indexes; its types are open when no
     * index knows the package.
     */
    private void buildUnit(Node n) {
        unitScope = newScope(Scope.Kind.UNIT, null, n);
        unitScope.index = index;
        unitScope.sources = sources;
        unitScope.packageName = tree.packageName();
        if (!unitScope.packageName.isEmpty() && !index.hasPackage(unitScope.packageName)
                && (sources == null || !sources.hasPackage(unitScope.packageName))) {
            unitScope.typesOpen = true;
        }
        processImports(unitScope);
        buildChildren(n, unitScope);
    }

    /**
     * A type declaration: its name in the enclosing scope, and a TYPE scope that holds its type parameters, its record
     * components (as fields and accessors) and, for an enum, values and valueOf; its supertypes are recorded for
     * linking.
     */
    private void buildType(Node n, Scope current) {
        if (n.name() != null && current != null) {
            current.addType(Symbol.type(n.name(), n));
        }
        Scope ts = newScope(Scope.Kind.TYPE, current, n);
        ts.superClasses.add(Object.class);
        for (Node tp : n.children(NodeType.TYPE_PARAMETER)) {
            ts.addType(Symbol.type(tp.name(), tp));
        }
        for (Node rc : n.children(NodeType.RECORD_COMPONENT)) {
            ts.addVariable(Symbol.variable(rc.name(), rc, -1));
            ts.addMethod(Symbol.method(rc.name(), rc));
        }
        if (n.type() == NodeType.ENUM_DECLARATION) {
            ts.superClasses.add(Enum.class);
            ts.addMethod(Symbol.method("values", n));
            ts.addMethod(Symbol.method("valueOf", n));
        }
        recordSupertypes(n, ts);
        buildChildren(n, ts);
    }

    /**
     * An enum constant: a variable of the enum's scope; a constant body gets a TYPE scope whose supertype is the enum.
     */
    private void buildEnumConstant(Node n, Scope current) {
        if (current != null && n.name() != null) {
            current.addVariable(Symbol.variable(n.name(), n, -1));
        }
        Node body = n.child(NodeType.CLASS_BODY);
        for (Node c : n.children()) {
            if (c == body) {
                Scope anon = newScope(Scope.Kind.TYPE, current, body);
                anon.superClasses.add(Object.class);
                if (current != null) {
                    anon.superScopes.add(current);
                    anon.superclassScope = current;
                }
                build(c, anon);
            } else {
                build(c, current);
            }
        }
    }

    /**
     * A method or constructor: a method's name in the type's scope, and a METHOD scope that holds its type parameters
     * and parameters.
     */
    private void buildMethod(Node n, Scope current) {
        if (n.type() == NodeType.METHOD_DECLARATION && current != null && n.name() != null) {
            current.addMethod(Symbol.method(n.name(), n));
        }
        Scope ms = newScope(Scope.Kind.METHOD, current, n);
        for (Node tp : n.children(NodeType.TYPE_PARAMETER)) {
            ms.addType(Symbol.type(tp.name(), tp));
        }
        for (Node pm : n.children(NodeType.PARAMETER)) {
            registerParam(ms, pm);
        }
        buildChildren(n, ms);
    }

    /** Register a parameter variable in the method scope. */
    private void registerParam(Scope scope, Node pm) {
        Token name = pm.token();
        if (name != null && name.type() == TokenType.IDENTIFIER && !"_".equals(name.text())) {
            scope.addVariable(Symbol.variable(name.text(), pm, -1));
        }
    }

    /** Record extends/implements TYPE children for phase-2 linking. */
    private void recordSupertypes(Node decl, Scope ts) {
        boolean inSupers = false;
        boolean extendsClause = false;
        boolean isClass = decl.type() == NodeType.CLASS_DECLARATION;
        for (Object p : decl.parts()) {
            if (p instanceof Token t) {
                if (t.type() == TokenType.EXTENDS) {
                    inSupers = true;
                    extendsClause = true;
                } else if (t.type() == TokenType.IMPLEMENTS) {
                    inSupers = true;
                    extendsClause = false;
                } else if (t.type() == TokenType.IDENTIFIER && t.text().equals("permits")) {
                    inSupers = false;
                }
            } else if (p instanceof Node c && c.type() == NodeType.TYPE && inSupers) {
                pending.add(new PendingSuper(ts, c, extendsClause && isClass));
            }
        }
    }

    /**
     * A field declaration: each declarator except '_' is a variable of the type's scope, visible wherever the type is.
     */
    private void buildField(Node n, Scope current) {
        if (current != null) {
            for (Node d : n.children(NodeType.VAR_DECLARATOR)) {
                if (!"_".equals(d.name())) {
                    current.addVariable(Symbol.variable(d.name(), d, -1));
                }
            }
        }
        buildChildren(n, current);
    }

    /** A local declaration: each declarator except '_' is a variable of the current scope from its own position on. */
    private void buildLocal(Node n, Scope current) {
        if (current != null) {
            for (Node d : n.children(NodeType.VAR_DECLARATOR)) {
                if (!"_".equals(d.name()) && d.token() != null) {
                    current.addVariable(Symbol.variable(d.name(), d, d.token().startOffset()));
                }
            }
        }
        buildChildren(n, current);
    }

    /** A type pattern: its binder is a variable of the current scope from its position on. */
    private void buildPattern(Node n, Scope current) {
        Token binder = n.token();
        if (current != null && binder != null && binder.type() == TokenType.IDENTIFIER) {
            current.addVariable(Symbol.variable(binder.text(), n, binder.startOffset()));
        }
        buildChildren(n, current);
    }

    /**
     * A try statement: its resources and block get a TRY scope; its catch and finally clauses are built in the
     * enclosing scope.
     */
    private void buildTry(Node n, Scope current) {
        Scope ts = newScope(Scope.Kind.TRY, current, n);
        for (Node c : n.children()) {
            if (c.type() == NodeType.CATCH || c.type() == NodeType.FINALLY) {
                build(c, current);
            } else {
                build(c, ts);
            }
        }
    }

    /** A catch clause: a CATCH scope that holds the caught exception's variable. */
    private void buildCatch(Node n, Scope current) {
        Scope cs = newScope(Scope.Kind.CATCH, current, n);
        for (Token t : n.tokens()) {
            if (t.type() == TokenType.IDENTIFIER) {
                cs.addVariable(Symbol.variable(t.text(), n, -1));
                break;
            }
        }
        buildChildren(n, cs);
    }

    /** A lambda: a LAMBDA scope that holds its parameters. */
    private void buildLambda(Node n, Scope current) {
        Scope ls = newScope(Scope.Kind.LAMBDA, current, n);
        for (Node pm : n.children(NodeType.PARAMETER)) {
            registerParam(ls, pm);
        }
        buildChildren(n, ls);
    }

    /** A switch case: an arrow case gets a CASE scope of its own; a colon case shares the switch block's scope. */
    private void buildCase(Node n, Scope current) {
        boolean arrow = n.tokens().stream().anyMatch(t -> t.type() == TokenType.ARROW);
        Scope cs = arrow ? newScope(Scope.Kind.CASE, current, n) : current;
        buildChildren(n, cs);
    }

    /**
     * An instance creation: an anonymous class body gets a TYPE scope whose supertype is linked later; the other parts
     * are built in the enclosing scope.
     */
    private void buildNewInstance(Node n, Scope current) {
        Node body = n.child(NodeType.CLASS_BODY);
        for (Node c : n.children()) {
            if (c == body) {
                Scope anon = newScope(Scope.Kind.TYPE, current, body);
                anon.superClasses.add(Object.class);
                Node superType = n.child(NodeType.TYPE);
                if (superType != null) {
                    pending.add(new PendingSuper(anon, superType, true));
                }
                build(c, anon);
            } else {
                build(c, current);
            }
        }
    }

    /** Supertypes that could not be linked to a scope or class when this file was resolved - typically a source type
     *  still mid-resolution (a cycle: the interface statically imports a member of its implementation, so resolving
     *  the interface resolves the implementation before the interface has scopes). Retried by
     *  {@link #relinkSupertypes()} once every file is resolved. */
    private final List<PendingSuper> deferred = new ArrayList<>();

    /**
     * Links each supertype recorded during the build to its scope or class; one that cannot be linked yet waits in
     * {@code deferred} for {@link #relinkSupertypes()}.
     */
    private void linkSupertypes() {
        for (PendingSuper ps : pending) {
            if (!linkOne(ps)) {
                deferred.add(ps);
            }
        }
    }

    /** Link one extends/implements clause; false when it could not be tied to a scope or class (worth retrying). The
     *  scope is marked open-membered on every failure so validation stays lenient about members it cannot see; a
     *  late success leaves that flag alone. */
    private boolean linkOne(PendingSuper ps) {
        String dotted = dottedName(ps.typeNode());
        if (dotted.isEmpty() || dotted.equals("var")) {
            ps.typeScope().openMembers = true;
            return true;   // nothing to retry
        }
        Symbol resolved = null;
        if (!dotted.contains(".")) {
            Scope from = ps.typeScope().parent();
            resolved = from != null ? from.resolveType(dotted) : null;
        } else if (sources != null) {
            int dot = dotted.lastIndexOf('.');
            resolved = sources.resolveType(dotted.substring(0, dot), dotted.substring(dot + 1));
        }
        if (resolved == null || (resolved.declaration() == null && resolved.clazz() == null)) {
            Class<?> c = index.load(dotted);
            if (c != null) {
                resolved = Symbol.type(simpleOf(dotted), c);
            }
        }
        if (resolved == null) {
            ps.typeScope().openMembers = true;
            return false;
        }
        if (resolved.declaration() != null) {
            Scope s = ownScope(resolved.declaration());
            if (s == null) {
                ps.typeScope().openMembers = true;   // source type mid-resolution - retry after the build
                return false;
            }
            if (!ps.typeScope().superScopes.contains(s)) {
                ps.typeScope().superScopes.add(s);
            }
            if (ps.isExtends() && ps.typeScope().superclassScope == null) {
                ps.typeScope().superclassScope = s;
            }
            return true;
        }
        if (resolved.clazz() != null) {
            if (!ps.typeScope().superClasses.contains(resolved.clazz())) {
                ps.typeScope().superClasses.add(resolved.clazz());
            }
            if (ps.isExtends() && ps.typeScope().superclassClass == null) {
                ps.typeScope().superclassClass = resolved.clazz();
            }
            if (!Scope.fullyIntrospectable(resolved.clazz())) {
                // the class is loadable but its own hierarchy isn't (missing deps) - members open
                ps.typeScope().openMembers = true;
            }
            return true;
        }
        ps.typeScope().openMembers = true;   // known by name only - members not enumerable
        return false;
    }

    /**
     * The dotted name a type node spells with its own identifiers and dots, such as {@code java.util.Map.Entry}; type
     * arguments are child nodes and are left out.
     */
    private static String dottedName(Node typeNode) {
        StringBuilder sb = new StringBuilder();
        for (Token t : typeNode.tokens()) {
            if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.DOT) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /** The last segment of a dotted name: {@code Entry} for {@code java.util.Map.Entry}. */
    private static String simpleOf(String dotted) {
        int dot = dotted.lastIndexOf('.');
        return dot < 0 ? dotted : dotted.substring(dot + 1);
    }

    // Stores symbols resolved for nodes, since Node itself has no resolved-symbol state.
    private final Map<Node, Symbol> resolvedSymbols = new IdentityHashMap<>();

    // Checks a node and recursively checks its children.
    private void check(Node n, List<String> out) {
        switch (n.type()) {
            case IMPORT_DECLARATION, PACKAGE_DECLARATION, MODULE_DECLARATION, MODULE_DIRECTIVE -> {
                return;
            }
            case IDENTIFIER_EXPRESSION -> {
                checkIdentifier(n, out);
                return;
            }
            case VAR_DECLARATION -> {
                checkDuplicateLocal(n, out);
            }
            case METHOD_CALL -> {
                checkCall(n, out);
            }
            case TYPE -> {
                checkTypeRef(n, out);
            }
            case ANNOTATION -> {
                checkAnnotation(n, out);
            }
            case RETURN -> {
                // A return whose provable type cannot be assigned to the method's declared return type
                String mismatch = typeInference().returnTypeMismatch(n);
                if (mismatch != null) {
                    out.add(mismatch);
                }
            }
            default -> { }
        }
        for (Node c : n.children()) {
            check(c, out);
        }
    }

    // Resolves an identifier and reports unknown names or reads in their own initializer.
    private void checkIdentifier(Node n, List<String> out) {
        Node parent = n.parent();
        if (parent != null && parent.type() == NodeType.CASE) {
            return;
        }
        String name = n.name();
        Scope sc = scopeOf(n);
        if (name == null || sc == null) {
            return;
        }
        Symbol v = sc.resolveVariable(name, n.token().startOffset());
        if (v != null) {
            if (v.declOffset() >= 0 && v.declaration() != null
                    && v.declaration().type() == NodeType.VAR_DECLARATOR
                    && isWithin(n, v.declaration())
                    && !underAssignment(n, v.declaration())) {
                out.add(pos(n) + " variable '" + name + "' is read in its own initializer");
                return;
            }
            resolvedSymbols.put(n, v);
            return;
        }
        Symbol t = sc.resolveType(name);
        if (t != null) {
            resolvedSymbols.put(n, t);
            return;
        }
        if (looksGenerated(name)) {
            return;
        }
        if (isReceiverHead(n)) {
            if (index.isPackageRoot(name) || !sc.typesProvable()) {
                return;
            }
            if (!name.isEmpty() && Character.isLowerCase(name.charAt(0))
                    && n.parent().type() == NodeType.FIELD_ACCESS
                    && n.parent().name() != null && !n.parent().name().isEmpty()
                    && Character.isUpperCase(n.parent().name().charAt(0))) {
                return;
            }
            if (sc.membersProvable()) {
                out.add(pos(n) + " unknown variable '" + name + "'");
            }
            return;
        }
        if (sc.membersProvable()) {
            out.add(pos(n) + " unknown variable '" + name + "'");
        }
    }

    // Tests whether a node is within an ancestor.
    private static boolean isWithin(Node n, Node ancestor) {
        for (Node a = n; a != null; a = a.parent()) {
            if (a == ancestor) {
                return true;
            }
        }
        return false;
    }

    // Tests whether an assignment occurs between a node and its enclosing declaration.
    private static boolean underAssignment(Node n, Node stop) {
        for (Node a = n.parent(); a != null && a != stop; a = a.parent()) {
            if (a.type() == NodeType.ASSIGNMENT) {
                return true;
            }
        }
        return false;
    }

    // Recognizes names commonly generated by annotation processors.
    private static boolean looksGenerated(String name) {
        return name.endsWith("_") || name.matches("Dagger\\p{Upper}.*");
    }

    // Tests whether an identifier is the receiver head of a qualified expression.
    private static boolean isReceiverHead(Node n) {
        Node p = n.parent();
        if (p == null) {
            return false;
        }
        NodeType t = p.type();
        if (t != NodeType.FIELD_ACCESS && t != NodeType.METHOD_CALL && t != NodeType.METHOD_REFERENCE
                && t != NodeType.CLASS_LITERAL) {
            return false;
        }
        List<Node> kids = p.children();
        if (kids.isEmpty() || kids.get(0) != n) {
            return false;
        }
        Token principal = p.token();
        Token first = n.firstToken();
        return principal == null || first == null || first.startOffset() < principal.startOffset();
    }

    // Formats the source position of a node.
    private static String pos(Node n) {
        Token f = n.firstToken();
        return f == null ? "[?]" : "[" + f.line() + ":" + f.column() + "]";
    }

    /** javac: a local may not re-declare a name already visible as a local/parameter (fields may shadow). */
    private void checkDuplicateLocal(Node n, List<String> out) {
        Scope sc = scopeOf(n);
        if (sc == null) {
            return;
        }
        decls:
        for (Node d : n.children(NodeType.VAR_DECLARATOR)) {
            String name = d.name();
            if (name == null || "_".equals(name) || d.token() == null) {
                continue;
            }
            int at = d.token().startOffset();
            for (Scope s = sc; s != null && s.kind() != Scope.Kind.TYPE && s.kind() != Scope.Kind.UNIT;
                    s = s.parent()) {
                for (Symbol v : s.declaredVariables()) {
                    if (v.name().equals(name) && v.declaration() != d
                            && (v.declaration() == null
                                    || v.declaration().type() != NodeType.TYPE_PATTERN)
                            && (v.declOffset() < 0 || v.declOffset() < at)) {
                        // (pattern binders are exempt: sibling-branch binders share this scope but
                        // proving branch disjointness would need flow analysis)
                        out.add(pos(d) + " variable '" + name + "' is already defined in this method");
                        continue decls;
                    }
                }
            }
        }
    }

    /**
     * An unqualified method call: records the method it names, and reports an instance method called from a static
     * context, or a name that no enclosing scope declares when the members are provable (with the methods in scope). A
     * qualified call is left to type inference.
     */
    private void checkCall(Node n, List<String> out) {
        Token pr = n.token();
        if (pr == null || pr.type() != TokenType.IDENTIFIER) {
            return;
        }
        if (isQualifiedCall(n, pr)) {
            return;
        }
        Scope sc = scopeOf(n);
        if (sc == null) {
            return;
        }
        List<Symbol> ms = sc.resolveMethods(pr.text());
        if (!ms.isEmpty()) {
            resolvedSymbols.put(n, ms.get(0));
            if (instanceMethodFromStaticContext(n, ms)) {
                out.add(pos(n) + " non-static method '" + pr.text() + "' cannot be called from a static context"
                        + " (declare it static, or call it on an instance)");
            }
            return;
        }
        if (sc.membersProvable()) {
            out.add(pos(n) + " no visible method '" + pr.text() + "'" + methodsInScope(sc, pr.text()));
        }
    }

    /** A type reference: resolves the name it spells, except {@code var}. */
    private void checkTypeRef(Node n, List<String> out) {
        Token pr = n.token();
        if (pr == null || pr.type() != TokenType.IDENTIFIER) {
            return;
        }
        Scope sc = scopeOf(n);
        if (sc == null) {
            return;
        }
        String dotted = dottedName(n);
        if (dotted.isEmpty() || dotted.equals("var")) {
            return;
        }
        resolveDottedType(n, dotted, sc, out);
    }

    /** An annotation: resolves the type its name spells, the part before any arguments. */
    private void checkAnnotation(Node n, List<String> out) {
        StringBuilder sb = new StringBuilder();
        for (Object p : n.parts()) {
            if (p instanceof Token t) {
                if (t.type() == TokenType.OPENING_ROUND_BRACKET) {
                    break;
                }
                if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.DOT) {
                    sb.append(t.text());
                }
            }
        }
        Scope sc = scopeOf(n);
        if (sb.isEmpty() || sc == null) {
            return;
        }
        resolveDottedType(n, sb.toString(), sc, out);
    }

    /**
     * Resolves a type name and records the type: a simple name through the scope, a dotted name through the classpath
     * or its first segment. An unknown name is reported only where the scope's types are provable, a simple one with
     * the types in scope; generated names are skipped.
     */
    private void resolveDottedType(Node n, String dotted, Scope sc, List<String> out) {
        if (!dotted.contains(".")) {
            Symbol t = sc.resolveType(dotted);
            if (t != null) {
                resolvedSymbols.put(n, t);
                return;
            }
            if (looksGenerated(dotted)) {
                return;
            }
            if (sc.typesProvable()) {
                out.add(pos(n) + " unknown type '" + dotted + "'" + typesInScope(sc, dotted));
            }
            return;
        }
        if (index.hasClass(dotted)) {
            Class<?> c = index.load(dotted);
            resolvedSymbols.put(n, c != null ? Symbol.type(simpleOf(dotted), c) : Symbol.typeByName(dotted));
            return;
        }
        String head = dotted.substring(0, dotted.indexOf('.'));
        if (sc.resolveType(head) != null) {
            return;
        }
        if (index.isPackageRoot(head)) {
            return;
        }
        if (!head.isEmpty() && Character.isLowerCase(head.charAt(0))) {
            return;
        }
        if (sc.typesProvable()) {
            out.add(pos(n) + " unknown type '" + dotted + "'");
        }
    }

    /** Whether a call has a receiver: a child that starts before the method name, as in {@code a.b()}. */
    private static boolean isQualifiedCall(Node call, Token principal) {
        for (Node c : call.children()) {
            Token first = c.firstToken();
            if (first != null && first.startOffset() < principal.startOffset()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The scope this owner node opens, from this resolver or, for a node of another file, from the source index; null
     * when neither has one.
     */
    public Scope ownScope(Node owner) {
        Scope s = ownScopes.get(owner);
        if (s != null) {
            return s;
        }
        if (sources != null) {
            return sources.scopeOf(owner);
        }
        return null;
    }

    /** Records the symbol a node resolves to. */
    void recordSymbol(Node n, Symbol s) {
        resolvedSymbols.put(n, s);
    }

    /**
     * The types a scope sees by simple name, the closest spellings to {@code wanted} first (at most 30 shown), as a
     * hint for an unknown-type problem.
     */
    static String typesInScope(Scope sc, String wanted) {
        List<String> ranked = new ArrayList<>(sc.visibleTypeNames());
        if (ranked.isEmpty()) {
            return "";
        }
        String w = wanted.toLowerCase(Locale.ROOT);
        ranked.sort(Comparator.comparingInt(
                        (String x) -> -TypeInference.sharedLetters(w, x.toLowerCase(Locale.ROOT)))
                .thenComparing(x -> x));
        List<String> shown = ranked.subList(0, Math.min(30, ranked.size()));
        return " — the types in scope: " + String.join(", ", shown)
                + (ranked.size() > shown.size() ? ", … (" + ranked.size() + " in all)" : "");
    }

    /**
     * The methods the enclosing scopes declare, the closest spellings to {@code wanted} first (at most 40 shown), as a
     * hint for a missing-method problem.
     */
    static String methodsInScope(Scope sc, String wanted) {
        Set<String> names = new LinkedHashSet<>();
        for (Scope s = sc; s != null; s = s.parent()) {
            for (Symbol m : s.declaredMethods()) {
                names.add(m.name());
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        List<String> ranked = new ArrayList<>(names);
        String w = wanted.toLowerCase(Locale.ROOT);
        ranked.sort(Comparator.comparingInt(
                        (String x) -> -TypeInference.sharedLetters(w, x.toLowerCase(Locale.ROOT)))
                .thenComparing(x -> x));
        List<String> shown = ranked.subList(0, Math.min(40, ranked.size()));
        return " — the methods in scope: " + String.join(", ", shown)
                + (ranked.size() > shown.size() ? ", … (" + ranked.size() + " in all)" : "");
    }

    /**
     * An unqualified call is refused when every candidate is an instance method of one enclosing type and a static
     * member, or a static nested type, stands between the call and that type, so no instance of it is at hand.
     */
    static boolean instanceMethodFromStaticContext(Node call, List<Symbol> candidates) {
        Node owner = null;
        for (Symbol m : candidates) {
            Node decl = m.declaration();
            if (decl == null || decl.type() != NodeType.METHOD_DECLARATION || decl.hasModifier(TokenType.STATIC)) {
                return false;   // a reflected method, or a static overload: javac decides
            }
            Node type = declaringType(decl);
            if (type == null || owner != null && owner != type) {
                return false;
            }
            owner = type;
        }
        boolean staticContext = false;
        for (Node a = call.parent(); a != null; a = a.parent()) {
            if (a == owner) {
                return staticContext;
            }
            NodeType t = a.type();
            if ((t == NodeType.METHOD_DECLARATION || t == NodeType.FIELD_DECLARATION || t == NodeType.INITIALIZER_BLOCK)
                    && a.hasModifier(TokenType.STATIC)) {
                staticContext = true;
            } else if (t.isTypeDeclaration() && (a.hasModifier(TokenType.STATIC) || t == NodeType.RECORD_DECLARATION
                    || t == NodeType.ENUM_DECLARATION || t == NodeType.INTERFACE_DECLARATION
                    || t == NodeType.ANNOTATION_TYPE_DECLARATION)) {
                staticContext = true;   // a nested type that has no enclosing instance
            }
        }
        return false;   // the method is inherited, not declared by an enclosing type: javac decides
    }

    /** The type declaration (or anonymous class) that declares a member, or null. */
    private static Node declaringType(Node member) {
        for (Node a = member.parent(); a != null; a = a.parent()) {
            if (a.type().isTypeDeclaration()
                    || a.type() == NodeType.NEW_INSTANCE && a.child(NodeType.CLASS_BODY) != null) {
                return a;
            }
        }
        return null;
    }
}
