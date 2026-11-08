package hermit.analysis.sym;

import hermit.analysis.tree.Node;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Represents a lexical scope for resolving symbols (variables, types, methods) within a compilation unit.
 */
public final class Scope {

    public enum Kind { UNIT, TYPE, METHOD, BLOCK, LAMBDA, FOR, TRY, CATCH, CASE }

    public Kind kind() {
        return kind;
    }

    public String staticImportOwnerOf(String member) {
        for (Scope s = this; s != null; s = s.parent) {
            String owner = s.staticSingleImports.get(member);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    public List<String> staticWildcardOwnerFqns() {
        List<String> out = new ArrayList<>();
        for (Scope s = this; s != null; s = s.parent) {
            for (String owner : s.staticWildcardOwners) {
                if (!out.contains(owner)) {
                    out.add(owner);
                }
            }
        }
        return out;
    }

    public Scope parent() {
        return parent;
    }

    public Node owner() {
        return owner;
    }

    public List<Symbol> declaredVariables() {
        return List.copyOf(variables);
    }

    public Map<String, Symbol> declaredTypes() {
        return Map.copyOf(types);
    }

    public List<Symbol> declaredMethods() {
        return List.copyOf(methods);
    }

    public Symbol resolveVariable(String name, int atOffset) {
        Symbol best = null;
        for (Symbol v : variables) {
            if (!v.name().equals(name)) {
                continue;
            }
            if (v.declOffset() < 0) {
                if (best == null) {
                    best = v;   // params/fields - whole-scope visibility, lowest precedence
                }
            } else if (v.declOffset() <= atOffset
                    && (best == null || best.declOffset() < v.declOffset())) {
                best = v;
            }
        }
        if (best != null) {
            return best;
        }
        if (kind == Kind.TYPE) {
            Symbol inherited = inheritedField(name, new HashSet<>());
            if (inherited != null) {
                return inherited;
            }
        }
        if (kind == Kind.UNIT) {
            Symbol viaStatic = staticImportedField(name);
            if (viaStatic != null) {
                return viaStatic;
            }
        }
        return parent != null ? parent.resolveVariable(name, atOffset) : null;
    }

    public Symbol resolveType(String simpleName) {
        Symbol own = types.get(simpleName);
        if (own != null) {
            return own;
        }
        if (kind == Kind.TYPE) {
            Symbol nested = inheritedType(simpleName, new HashSet<>());
            if (nested != null) {
                return nested;
            }
        }
        if (kind == Kind.UNIT) {
            return unitType(simpleName);
        }
        return parent != null ? parent.resolveType(simpleName) : null;
    }

    public List<Symbol> resolveMethods(String name) {
        List<Symbol> out = new ArrayList<>();
        collectMethods(name, out, new HashSet<>());
        return out;
    }

    public boolean membersProvable() {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.kind == Kind.TYPE && !s.membersClosedDeep()) {
                return false;
            }
            if (s.kind == Kind.UNIT && s.staticsOpen) {
                return false;
            }
        }
        return true;
    }

    public boolean typesProvable() {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.kind == Kind.TYPE && !s.membersClosedDeep()) {
                return false;   // an unresolved (grand-)supertype could contribute inherited nested types
            }
            if (s.kind == Kind.UNIT) {
                return !s.typesOpen;
            }
        }
        return true;
    }

    private final Kind kind;

    private final Scope parent;

    private final Node owner;

    private final List<Symbol> variables = new ArrayList<>();

    private final Map<String, Symbol> types = new LinkedHashMap<>();

    private final List<Symbol> methods = new ArrayList<>();

    // TYPE scopes: resolved supertypes + openness
    final List<Scope> superScopes = new ArrayList<>();

    final List<Class<?>> superClasses = new ArrayList<>();

    // the EXTENDS supertype specifically (what bare `super` means)
    Scope superclassScope;

    Class<?> superclassClass;

    boolean openMembers;

    // UNIT scope: import machinery
    ClasspathIndex index;

    // project-wide source symbol table (nullable)
    SourceIndex sources;

    String packageName = "";

    // simple name -> fqn
    final Map<String, String> singleImports = new LinkedHashMap<>();

    final List<String> wildcardPackages = new ArrayList<>();

    // member name -> owner fqn
    final Map<String, String> staticSingleImports = new LinkedHashMap<>();

    final List<String> staticWildcardOwners = new ArrayList<>();

    // an unknown wildcard package / module import → type absence unprovable
    boolean typesOpen;

    // a static-import owner is unloadable → member absence unprovable
    boolean staticsOpen;

    // The resolver's map from a declaration node to its scope.
    Function<Node, Scope> scopes;

    Scope(Kind kind, Scope parent, Node owner) {
        this.kind = kind;
        this.parent = parent;
        this.owner = owner;
    }

    void addVariable(Symbol s) {
        variables.add(s);
    }

    void addType(Symbol s) {
        types.putIfAbsent(s.name(), s);
    }

    void addMethod(Symbol s) {
        methods.add(s);
    }

    private Symbol reflectField(Class<?> c, String name) {
        try {
            for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getName().equals(name)) {
                        return Symbol.variable(name, c);
                    }
                }
            }
            for (Field f : c.getFields()) {   // interface constants
                if (f.getName().equals(name)) {
                    return Symbol.variable(name, c);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Symbol inheritedField(String name, Set<Scope> visited) {
        if (!visited.add(this)) {
            return null;
        }
        for (Scope sup : superScopes) {
            for (Symbol v : sup.variables) {
                if (v.name().equals(name)) {
                    return v;
                }
            }
            Symbol deeper = sup.inheritedField(name, visited);
            if (deeper != null) {
                return deeper;
            }
        }
        for (Class<?> sup : superClasses) {
            Symbol f = reflectField(sup, name);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** The scope of a project SOURCE type named by a static-import owner FQN, or null. */
    Scope sourceOwnerScope(String ownerFqn) {
        if (sources == null || ownerFqn == null) {
            return null;
        }
        int dot = ownerFqn.lastIndexOf('.');
        Symbol t = sources.resolveType(dot < 0 ? "" : ownerFqn.substring(0, dot), ownerFqn.substring(dot + 1));
        return t == null || t.declaration() == null ? null : scopes == null ? null : scopes.apply(t.declaration());
    }

    /** A FIELD of this type (own or inherited) - unlike resolveVariable, never walks to outer scopes. */
    Symbol memberField(String name) {
        for (Symbol v : variables) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        return inheritedField(name, new HashSet<>());
    }

    private Symbol staticImportedField(String name) {
        String owner1 = staticSingleImports.get(name);
        if (owner1 != null) {
            Class<?> c = index == null ? null : index.load(owner1);
            if (c == null) {
                Scope ownerScope = sourceOwnerScope(owner1);
                Symbol declared = ownerScope == null ? null : ownerScope.memberField(name);
                if (declared != null) {
                    return declared;   // a project-source constant: typed by its declaration
                }
                return Symbol.variable(name, (Class<?>) null);   // named by the import - authoritative
            }
            Symbol f = reflectField(c, name);
            return f != null ? f : Symbol.variable(name, c);
        }
        for (String ownerFqn : staticWildcardOwners) {
            Class<?> c = index == null ? null : index.load(ownerFqn);
            if (c != null) {
                Symbol f = reflectField(c, name);
                if (f != null) {
                    return f;
                }
            } else {
                Scope ownerScope = sourceOwnerScope(ownerFqn);
                Symbol f = ownerScope == null ? null : ownerScope.memberField(name);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    /** Can the WHOLE hierarchy of {@code c} be reflected without linkage errors? (Probe once, cheaply.) */
    static boolean fullyIntrospectable(Class<?> c) {
        try {
            for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
                cur.getDeclaredMethods();
                cur.getDeclaredFields();
            }
            c.getMethods();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Symbol inheritedType(String name, Set<Scope> visited) {
        if (!visited.add(this)) {
            return null;
        }
        for (Scope sup : superScopes) {
            Symbol t = sup.types.get(name);
            if (t != null) {
                return t;
            }
            Symbol deeper = sup.inheritedType(name, visited);
            if (deeper != null) {
                return deeper;
            }
        }
        for (Class<?> sup : superClasses) {
            try {
                for (Class<?> nested : sup.getClasses()) {
                    if (nested.getSimpleName().equals(name)) {
                        return Symbol.type(name, nested);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Symbol unitType(String name) {
        String fqn = singleImports.get(name);
        if (fqn != null) {
            // A single-type import is authoritative even when the class isn't loadable.
            Class<?> c = index == null ? null : index.load(fqn);
            if (c != null) {
                return Symbol.type(name, c);
            }
            if (sources != null) {
                int dot = fqn.lastIndexOf('.');
                Symbol s = sources.resolveType(dot < 0 ? "" : fqn.substring(0, dot), fqn.substring(dot + 1));
                if (s != null) {
                    return s;
                }
            }
            return Symbol.typeByName(fqn);
        }
        // `import static Owner.Nested;` can import a NESTED TYPE, not just fields/methods
        String staticOwner = staticSingleImports.get(name);
        if (staticOwner != null) {
            Class<?> oc = index == null ? null : index.load(staticOwner);
            if (oc != null) {
                try {
                    for (Class<?> nested : oc.getDeclaredClasses()) {
                        if (nested.getSimpleName().equals(name)) {
                            return Symbol.type(name, nested);
                        }
                    }
                } catch (Throwable ignored) {
                }
            } else {
                Symbol ownerType = null;
                if (sources != null) {
                    int dot = staticOwner.lastIndexOf('.');
                    ownerType = sources.resolveType(dot < 0 ? "" : staticOwner.substring(0, dot),
                            staticOwner.substring(dot + 1));
                }
                if (ownerType != null && ownerType.declaration() != null) {
                    Scope ownerScope = scopes == null ? null : scopes.apply(ownerType.declaration());
                    if (ownerScope != null) {
                        Symbol nested = ownerScope.declaredTypes().get(name);
                        if (nested != null) {
                            return nested;
                        }
                        // owner verified and has no such nested type → the import names a field/method
                    } else {
                        // owner unverifiable - the import is authoritative about the name existing
                        return Symbol.typeByName(staticOwner + "." + name);
                    }
                } else {
                    // owner unverifiable - the import is authoritative about the name existing
                    return Symbol.typeByName(staticOwner + "." + name);
                }
            }
        }
        if (!packageName.isEmpty() && index != null && index.classesIn(packageName).contains(name)) {
            Class<?> c = index.load(packageName + "." + name);
            return c != null ? Symbol.type(name, c) : Symbol.typeByName(packageName + "." + name);
        }
        if (sources != null) {
            // same-package SOURCE siblings (works for the default package too)
            Symbol s = sources.resolveType(packageName, name);
            if (s != null) {
                return s;
            }
        }
        for (String pkg : wildcardPackages) {
            if (index != null && index.classesIn(pkg).contains(name)) {
                Class<?> c = index.load(pkg + "." + name);
                return c != null ? Symbol.type(name, c) : Symbol.typeByName(pkg + "." + name);
            }
            if (sources != null) {
                Symbol s = sources.resolveType(pkg, name);
                if (s != null) {
                    return s;
                }
            }
        }
        if (index != null && index.classesIn("java.lang").contains(name)) {
            Class<?> c = index.load("java.lang." + name);
            return c != null ? Symbol.type(name, c) : Symbol.typeByName("java.lang." + name);
        }
        return null;
    }

    /** A NESTED TYPE of this type (own or inherited) - unlike resolveType, never walks to outer scopes. */
    Symbol memberNestedType(String name) {
        Symbol own = types.get(name);
        if (own != null) {
            return own;
        }
        return inheritedType(name, new HashSet<>());
    }

    private boolean membersClosedDeep(Set<Scope> visited) {
        if (!visited.add(this)) {
            return true;
        }
        if (openMembers) {
            return false;
        }
        for (Scope sup : superScopes) {
            if (!sup.membersClosedDeep(visited)) {
                return false;
            }
        }
        return true;
    }

    private static void reflectMethods(Class<?> c, String name, List<Symbol> out) {
        try {
            Map<String, Method> seen = new HashMap<>();
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name)) {
                    seen.putIfAbsent(m.toString(), m);
                }
            }
            for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (m.getName().equals(name) && !Modifier.isPrivate(m.getModifiers())) {
                        seen.putIfAbsent(m.toString(), m);
                    }
                }
            }
            for (Method ignored : seen.values()) {
                out.add(Symbol.method(name, c));
            }
        } catch (Throwable ignored) {
        }
    }

    /** True when this type's FULL hierarchy is enumerable (no unresolved supertype anywhere). */
    boolean membersClosedDeep() {
        return membersClosedDeep(new HashSet<>());
    }

    private void collectMethods(String name, List<Symbol> out, Set<Scope> visited) {
        for (Symbol m : methods) {
            if (m.name().equals(name)) {
                out.add(m);
            }
        }
        if (kind == Kind.TYPE) {
            collectInheritedMethods(name, out, visited);
        }
        if (kind == Kind.UNIT) {
            collectStaticImportedMethods(name, out);
        }
        if (parent != null) {
            parent.collectMethods(name, out, visited);
        }
    }

    private void collectInheritedMethods(String name, List<Symbol> out, Set<Scope> visited) {
        if (!visited.add(this)) {
            return;
        }
        for (Scope sup : superScopes) {
            for (Symbol m : sup.methods) {
                if (m.name().equals(name)) {
                    out.add(m);
                }
            }
            sup.collectInheritedMethods(name, out, visited);
        }
        for (Class<?> sup : superClasses) {
            reflectMethods(sup, name, out);
        }
    }

    private void collectStaticImportedMethods(String name, List<Symbol> out) {
        String owner1 = staticSingleImports.get(name);
        if (owner1 != null) {
            Class<?> c = index == null ? null : index.load(owner1);
            if (c == null) {
                // the owner may be a PROJECT SOURCE type (a spec verbs class, a fixtures helper): its
                // declared methods carry declarations, so the call's return type infers and chains on the
                // result are checked - instead of an untyped "authoritative" placeholder
                Scope ownerScope = sourceOwnerScope(owner1);
                List<Symbol> declared = ownerScope == null ? List.of() : ownerScope.memberMethods(name);
                if (declared.isEmpty()) {
                    out.add(Symbol.method(name, (Class<?>) null));   // named by the import - authoritative
                } else {
                    out.addAll(declared);
                }
            } else {
                int before = out.size();
                reflectMethods(c, name, out);
                if (out.size() == before) {
                    out.add(Symbol.method(name, c));   // import names it; trust the import
                }
            }
        }
        for (String ownerFqn : staticWildcardOwners) {
            Class<?> c = index == null ? null : index.load(ownerFqn);
            if (c != null) {
                reflectMethods(c, name, out);
            } else {
                Scope ownerScope = sourceOwnerScope(ownerFqn);
                if (ownerScope != null) {
                    out.addAll(ownerScope.memberMethods(name));
                }
            }
        }
    }

    /** METHODS of this type (own or inherited) - unlike resolveMethods, never walks to outer scopes. */
    List<Symbol> memberMethods(String name) {
        List<Symbol> out = new ArrayList<>();
        for (Symbol m : methods) {
            if (m.name().equals(name)) {
                out.add(m);
            }
        }
        collectInheritedMethods(name, out, new HashSet<>());
        return out;
    }

    /**
     * The simple names of the types this scope can see by their simple names: the declared and nested types of the
     * enclosing scopes, the single-type imports and the same-package types.
     */
    public Set<String> visibleTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Scope s = this; s != null; s = s.parent) {
            names.addAll(s.types.keySet());
            if (s.kind == Kind.UNIT) {
                names.addAll(s.singleImports.keySet());
                if (s.sources != null) {
                    names.addAll(s.sources.typesInPackage(s.packageName));
                }
                if (!s.packageName.isEmpty()) {
                    names.addAll(s.index.classesIn(s.packageName));
                }
            }
        }
        return names;
    }
}
