package hermit.analysis.sym;

import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes a TypeRef for every expression node and validates member existence and argument arity where types are
 * provable.
 */
public final class TypeInference {
    /**
     * Infer types for every expression under {@code subtree} and return the provable problems found
     * (missing members, impossible arities). Fills {@link Node#inferredClass()} as a side effect.
     */
    public List<String> validate(Node subtree) {
        subtree.walk(n -> {
            try {
                if (isExpression(n.type())) {
                    typeOf(n);
                    String misuse = operatorMisuse(n);
                    if (misuse == null) {
                        misuse = voidValueMisuse(n);
                    }
                    if (misuse != null) {
                        problems.add(misuse);
                    }
                } else if (n.type() == NodeType.IF || n.type() == NodeType.WHILE || n.type() == NodeType.DO) {
                    String misuse = conditionMisuse(n);
                    if (misuse != null) {
                        problems.add(misuse);
                    }
                } else if (n.type() == NodeType.VAR_DECLARATION || n.type() == NodeType.FIELD_DECLARATION) {
                    for (Node d : n.children(NodeType.VAR_DECLARATOR)) {
                        declaratorType(d);   // resolves 'var' and publishes inferredClass on the declarator
                    }
                } else if (n.type() == NodeType.RETURN) {
                    String mismatch = returnTypeMismatch(n);
                    if (mismatch != null) {
                        problems.add(mismatch);
                    }
                }
            } catch (Throwable ignored) {
                // one node's inference failing (broken classpath linkage) must not abort the pass
            }
        });
        return List.copyOf(problems);
    }

    /**
     * A return whose expression has a PROVABLE type that cannot be assigned to the enclosing method's declared
     * (library) return type is a problem - for example a String returned from a method declared to return
     * {@code Optional<Integer>}. Anything open - a generic or source return type, an unknown expression - stays
     * accepted.
     */
    public String returnTypeMismatch(Node ret) {
        Node expr = null;
        for (Node c : ret.children()) {
            if (isExpression(c.type())) {
                expr = c;
                break;
            }
        }
        if (expr == null) {
            return null;
        }
        Node method = ret.parent();
        while (method != null && method.type() != NodeType.METHOD_DECLARATION && method.type() != NodeType.LAMBDA) {
            method = method.parent();
        }
        if (method == null || method.type() != NodeType.METHOD_DECLARATION) {
            return null;   // a lambda's return belongs to the lambda's own function type
        }
        Node typeNode = method.child(NodeType.TYPE);
        if (typeNode == null) {
            return null;
        }
        TypeRef declared = typeRefOfTypeNode(typeNode);
        Class<?> want = declared.clazz();
        // numeric returns are not provable: overload choice (Math.min(long,long) for ints) shifts the inferred
        // width - only reference types outside the number/box family are judged
        if (want == null || declared.dims() != 0 || want == void.class || want.isPrimitive() || want == Object.class
                || Number.class.isAssignableFrom(want) || want == Boolean.class || want == Character.class) {
            return null;
        }
        TypeRef actual = typeOf(expr);
        Class<?> have = actual.isUnknown() || actual.isNullLiteral() ? null : actual.toClass();
        if (have == null || have.isPrimitive() || Number.class.isAssignableFrom(have) || have == Boolean.class
                || have == Character.class) {
            return null;
        }
        // only a FINAL class is provable: an interface or open class the inference names (IntStream for a chained
        // stream) may be the erased or wrongly-overloaded view of what the expression really is
        if (!Modifier.isFinal(have.getModifiers()) || have.isArray()) {
            return null;
        }
        if (!want.isAssignableFrom(have)) {
            return pos(ret) + " return type mismatch: the method returns " + declared.describe()
                    + " but this statement returns " + actual.describe();
        }
        return null;
    }

    public TypeRef typeOf(Node n) {
        TypeRef cached = memo.get(n);
        if (cached != null) {
            return cached;
        }
        if (!inProgress.add(n)) {
            return TypeRef.UNKNOWN;   // cycle guard (e.g. var x = x)
        }
        TypeRef t;
        try {
            t = compute(n);
        } catch (Throwable e) {
            // inference must never crash a caller - incl. linkage Errors from broken classpaths
            t = TypeRef.UNKNOWN;
        } finally {
            inProgress.remove(n);
        }
        memo.put(n, t);
        Class<?> c = t.toClass();
        if (c != null) {
            n.setInferredClass(c);
        }
        return t;
    }

    public TypeRef typeRefOfTypeNode(Node typeNode) {
        if (typeNode == null) {
            return TypeRef.UNKNOWN;
        }
        Token pr = typeNode.token();
        if (pr == null) {
            return TypeRef.UNKNOWN;
        }
        int dims = (int) typeNode.tokens().stream()
        .filter(t -> t.type() == TokenType.OPENING_SQUARE_BRACKET).count();
        Class<?> prim = PRIMITIVE_CLASSES.get(pr.type());
        if (prim != null) {
            return TypeRef.of(prim, Map.of(), dims);
        }
        if (pr.type() != TokenType.IDENTIFIER) {
            return TypeRef.UNKNOWN;
        }
        String dotted = dottedName(typeNode);
        if (dotted.isEmpty() || dotted.equals("var")) {
            return TypeRef.UNKNOWN;
        }
        Scope sc = resolver.scopeOf(typeNode);
        Symbol resolved = null;
        if (!dotted.contains(".") && sc != null) {
            resolved = sc.resolveType(dotted);
        }
        Class<?> clazz = null;
        if (resolved != null) {
            if (resolved.clazz() != null) {
                clazz = resolved.clazz();
            } else if (resolved.declaration() != null) {
                if (resolved.declaration().type() == NodeType.TYPE_PARAMETER) {
                    return TypeRef.UNKNOWN;
                }
                Scope ds = resolver.ownScope(resolved.declaration());
                return ds != null
                        ? TypeRef.ofSource(ds, resolved.declaration(), dims)
                        : TypeRef.UNKNOWN;
            }
        }
        if (clazz == null) {
            clazz = index.load(dotted);
        }
        if (clazz == null) {
            return TypeRef.UNKNOWN;
        }
        Map<String, TypeRef> args = typeArgumentsFor(clazz, typeNode);
        return TypeRef.of(clazz, args, dims);
    }

    public Symbol fieldOf(Node fieldAccess) {
        if (fieldAccess.type() != NodeType.FIELD_ACCESS || fieldAccess.name() == null) {
            return null;
        }
        Node receiver = firstExpr(fieldAccess);
        if (receiver == null) {
            return null;
        }
        TypeRef recv = typeOf(receiver);
        if (recv.isArray() || recv.source() == null) {
            return null;
        }
        return recv.source().memberField(fieldAccess.name());
    }

    // Expression kinds
    private static boolean isExpression(NodeType t) {
        return switch (t) {
            case IDENTIFIER_EXPRESSION, LITERAL, THIS_EXPRESSION, SUPER_EXPRESSION, PARENTHESIZED,
                    METHOD_CALL, FIELD_ACCESS, ARRAY_ACCESS, CLASS_LITERAL, ASSIGNMENT, TERNARY,
                    BINARY_EXPRESSION, UNARY_EXPRESSION, POSTFIX_EXPRESSION, CAST, INSTANCEOF,
                    NEW_INSTANCE, NEW_ARRAY, LAMBDA, METHOD_REFERENCE -> true;
            default -> false;
        };
    }

    // Primitive type mapping
    private static final Map<TokenType, Class<?>> PRIMITIVE_CLASSES = Map.of(
            TokenType.BOOLEAN, boolean.class, TokenType.BYTE, byte.class, TokenType.SHORT, short.class,
            TokenType.INT, int.class, TokenType.LONG, long.class, TokenType.CHAR, char.class,
            TokenType.FLOAT, float.class, TokenType.DOUBLE, double.class, TokenType.VOID, void.class);

    // Arity check
    private static boolean arityMatches(int params, boolean varargs, int args) {
        return args == params || (varargs && args >= params - 1);
    }

    // Numeric rank for promotion
    private static final Map<Class<?>, Integer> NUMERIC_RANK = Map.of(
            byte.class, 1, short.class, 2, char.class, 2, int.class, 3,
            long.class, 4, float.class, 5, double.class, 6);

    // Type identity check
    private static boolean is(TypeRef t, Class<?> c) {
        return t.dims() == 0 && t.clazz() == c;
    }

    // Boxing mappings
    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class,
            char.class, Character.class, int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class);

    // Box a primitive class
    private static Class<?> box(Class<?> c) {
        return c != null && c.isPrimitive() ? BOXES.getOrDefault(c, c) : c;
    }

    // Unbox a wrapper class
    private static Class<?> unbox(Class<?> c) {
        if (c == null) {
            return null;
        }
        for (Map.Entry<Class<?>, Class<?>> e : BOXES.entrySet()) {
            if (e.getValue() == c) {
                return e.getKey();
            }
        }
        return c;
    }

    // First expression child
    private static Node firstExpr(Node n) {
        for (Node c : n.children()) {
            if (isExpression(c.type()) || c.type() == NodeType.ARRAY_INITIALIZER) {
                return c;
            }
        }
        return null;
    }

    // Receiver of a qualified call: a child that ends before the method-name token.
    private static Node receiverOf(Node call, Token principal) {
        for (Node c : call.children()) {
            Token first = c.firstToken();
            if (first != null && first.startOffset() < principal.startOffset()
                    && c.type() != NodeType.TYPE) {
                return c;
            }
        }
        return null;
    }

    // Argument expressions: children positioned after afterOffset (excluding class bodies).
    private static List<Node> argumentsAfter(Node n, int afterOffset) {
        List<Node> out = new ArrayList<>();
        for (Node c : n.children()) {
            Token first = c.firstToken();
            if (first != null && first.startOffset() > afterOffset
                    && c.type() != NodeType.CLASS_BODY && c.type() != NodeType.TYPE) {
                out.add(c);
            }
        }
        return out;
    }

    // Constructor arguments of a NEW_INSTANCE: children after the opening parenthesis.
    private static List<Node> constructorArgs(Node n) {
        int paren = -1;
        for (Token t : n.tokens()) {
            if (t.type() == TokenType.OPENING_ROUND_BRACKET) {
                paren = t.startOffset();
                break;
            }
        }
        if (paren < 0) {
            return List.of();
        }
        return argumentsAfter(n, paren);
    }

    // Dotted name from type node tokens
    private static String dottedName(Node typeNode) {
        StringBuilder sb = new StringBuilder();
        for (Token t : typeNode.tokens()) {
            if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.DOT) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    // Position string for a node
    private static String pos(Node n) {
        Token f = n.token() != null ? n.token() : n.firstToken();
        return f == null ? "[?]" : "[" + f.line() + ":" + f.column() + "]";
    }

    private static Map<String, TypeRef> methodTypeArgs(Method m, List<TypeRef> argTypes) {
        Map<String, TypeRef> out = new HashMap<>();
        Type[] params = m.getGenericParameterTypes();
        for (int i = 0; i < params.length && i < argTypes.size(); i++) {
            // reflection hands out EQUAL, not identical, Method objects - compare with equals, never ==
            if (params[i] instanceof TypeVariable<?> tv && m.equals(tv.getGenericDeclaration())
                    && argTypes.get(i) != null && !argTypes.get(i).isUnknown()) {
                out.putIfAbsent(tv.getName(), argTypes.get(i));
            }
        }
        return out;
    }

    /** Materialize a reflective {@link Type} under the receiver's type-argument map (one level deep). */
    private TypeRef substitute(Type t, Map<String, TypeRef> typeArgs) {
        if (t instanceof Class<?> c) {
            return TypeRef.of(c);
        }
        if (t instanceof TypeVariable<?> tv) {
            TypeRef bound = typeArgs.get(tv.getName());
            return bound != null ? bound : TypeRef.UNKNOWN;
        }
        if (t instanceof ParameterizedType pt) {
            Class<?> raw = pt.getRawType() instanceof Class<?> rc ? rc : null;
            if (raw == null) {
                return TypeRef.UNKNOWN;
            }
            TypeVariable<?>[] vars = raw.getTypeParameters();
            Type[] actual = pt.getActualTypeArguments();
            Map<String, TypeRef> args = new HashMap<>();
            for (int i = 0; i < vars.length && i < actual.length; i++) {
                args.put(vars[i].getName(), substitute(actual[i], typeArgs));
            }
            return TypeRef.of(raw, args, 0);
        }
        if (t instanceof GenericArrayType ga) {
            TypeRef comp = substitute(ga.getGenericComponentType(), typeArgs);
            return comp.isUnknown() ? TypeRef.UNKNOWN : comp.withDims(comp.dims() + 1);
        }
        return TypeRef.UNKNOWN;   // wildcards etc.
    }

    // ---- reflection (defensive) ------------------------------------------------------------------------------------

    private static Field reflectField(Class<?> c, String name) {
        try {
            for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getName().equals(name)) {
                        return f;
                    }
                }
            }
            for (Field f : c.getFields()) {
                if (f.getName().equals(name)) {
                    return f;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Name-matched callable methods of {@code c} (interfaces get Object's too); null = reflection failed.
     * Bridge/synthetic methods are dropped, and same-signature declarations from different levels of the
     * hierarchy are DEDUPED keeping the most specific return type - so a covariant override like
     * {@code StringBuilder append(char)} wins over the interface's {@code Appendable append(char)}.
     */
    private static List<Method> reflectMethods(Class<?> c, String name) {
        try {
            Map<String, Method> bySignature = new LinkedHashMap<>();
            List<Method> raw = new ArrayList<>();
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name)) {
                    raw.add(m);
                }
            }
            for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (m.getName().equals(name) && !Modifier.isPrivate(m.getModifiers())) {
                        raw.add(m);
                    }
                }
            }
            if (c.isInterface()) {
                for (Method m : Object.class.getMethods()) {
                    if (m.getName().equals(name)) {
                        raw.add(m);
                    }
                }
            }
            for (Method m : raw) {
                if (m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                StringBuilder sig = new StringBuilder(m.getName());
                for (Class<?> p : m.getParameterTypes()) {
                    sig.append('|').append(p.getName());
                }
                Method prev = bySignature.get(sig.toString());
                if (prev == null
                        || (prev.getReturnType() != m.getReturnType()
                                && prev.getReturnType().isAssignableFrom(m.getReturnType()))) {
                    bySignature.put(sig.toString(), m);   // keep the most specific return type
                }
            }
            return new ArrayList<>(bySignature.values());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> nestedClass(Class<?> c, String name) {
        try {
            for (Class<?> nested : c.getDeclaredClasses()) {
                if (nested.getSimpleName().equals(name)) {
                    return nested;
                }
            }
            for (Class<?> nested : c.getClasses()) {
                if (nested.getSimpleName().equals(name)) {
                    return nested;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean membersEnumerable(Class<?> c) {
        try {
            c.getMethods();
            c.getDeclaredFields();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // Chooses the first overload compatible with the argument types.
    private Method chooseOverload(List<Method> byArity, List<TypeRef> argTypes) {
        for (Method m : byArity) {
            if (argsCompatible(m, argTypes)) {
                return m;
            }
        }
        return byArity.get(0);
    }

    // Checks argument compatibility against an executable's parameter types.
    private boolean argsCompatible(Executable m, List<TypeRef> argTypes) {
        try {
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < argTypes.size(); i++) {
                Class<?> p = i < params.length ? params[i]
                        : params.length > 0 ? params[params.length - 1].getComponentType() : null;
                if (p == null) {
                    continue;
                }
                if (m.isVarArgs() && i == params.length - 1 && argTypes.size() == params.length) {
                    // could be the array itself or the first vararg element - accept either
                    continue;
                }
                if (!assignable(argTypes.get(i), p)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    // Tests whether a type can be assigned to a parameter, including primitive widening.
    private boolean assignable(TypeRef arg, Class<?> param) {
        if (arg.isUnknown()) {
            return true;
        }
        if (arg.isNullLiteral()) {
            return !param.isPrimitive();
        }
        Class<?> a = arg.toClass();
        if (a == null) {
            return true;   // source types: hierarchy compat is approximate - accept
        }
        if (param.isAssignableFrom(a)) {
            return true;
        }
        Class<?> boxedArg = box(a);
        Class<?> boxedParam = box(param);
        if (boxedParam.isAssignableFrom(boxedArg)) {
            return true;
        }
        // primitive widening
        Integer ra = NUMERIC_RANK.get(unbox(a));
        Integer rp = NUMERIC_RANK.get(unbox(param));
        return ra != null && rp != null && ra <= rp;
    }

    // Applies unary numeric promotion.
    private TypeRef promoteUnary(TypeRef t) {
        Class<?> c = unbox(t.toClass());
        Integer rank = c == null ? null : NUMERIC_RANK.get(c);
        if (rank == null) {
            return t.isUnknown() ? TypeRef.UNKNOWN : t;
        }
        return rank <= 3 ? TypeRef.of(int.class) : TypeRef.of(c);
    }

    // Applies binary numeric promotion.
    private TypeRef promoteBinary(TypeRef l, TypeRef r) {
        Class<?> a = unbox(l.toClass());
        Class<?> b = unbox(r.toClass());
        Integer ra = a == null ? null : NUMERIC_RANK.get(a);
        Integer rb = b == null ? null : NUMERIC_RANK.get(b);
        if (ra == null || rb == null) {
            return TypeRef.UNKNOWN;
        }
        int rank = Math.max(Math.max(ra, rb), 3);   // at least int
        for (Map.Entry<Class<?>, Integer> e : NUMERIC_RANK.entrySet()) {
            if (e.getValue() == rank && e.getKey() != char.class) {
                return TypeRef.of(e.getKey());
            }
        }
        return TypeRef.of(int.class);
    }

    // The resolver owns scope information for nodes.
    private final SymbolResolver resolver;

    // Classpath used to load runtime types.
    private final ClasspathIndex index;

    // Memoized inferred types for expression nodes.
    private final Map<Node, TypeRef> memo = new IdentityHashMap<>();

    // Nodes currently being inferred, to prevent recursive inference.
    private final Set<Node> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());

    // Provable problems found during inference.
    private final List<String> problems = new ArrayList<>();

    // Construct inference state from the shared symbol resolver.
    TypeInference(SymbolResolver resolver) {
        this.resolver = resolver;
        this.index = resolver.index();
    }

    // Find the nearest enclosing type scope for a node.
    private Scope enclosingTypeScope(Node n) {
        for (Scope s = resolver.scopeOf(n); s != null; s = s.parent()) {
            if (s.kind() == Scope.Kind.TYPE) {
                return s;
            }
        }
        return null;
    }

    // Find the source type that encloses a declaration, such as an enum constant.
    private TypeRef enclosingTypeOfDecl(Node enumConstant) {
        for (Node p = enumConstant.parent(); p != null; p = p.parent()) {
            Scope ownScope = resolver.ownScope(p);
            if (ownScope != null && ownScope.kind() == Scope.Kind.TYPE) {
                return TypeRef.ofSource(ownScope, p, 0);
            }
        }
        return TypeRef.UNKNOWN;
    }

    // Convert a resolved type symbol into the type reference used by inference.
    private TypeRef typeUseOf(Symbol t) {
        if (t.clazz() != null) {
            return TypeRef.of(t.clazz());
        }
        if (t.declaration() != null) {
            if (t.declaration().type() == NodeType.TYPE_PARAMETER) {
                return TypeRef.UNKNOWN;
            }
            Scope s = resolver.ownScope(t.declaration());
            if (s != null) {
                return TypeRef.ofSource(s, t.declaration(), 0);
            }
        }
        return TypeRef.UNKNOWN;
    }

    // Resolve generic arguments positionally against a runtime class's type variables.
    private Map<String, TypeRef> typeArgumentsFor(Class<?> clazz, Node typeNode) {
        List<Node> argNodes = typeNode.children(NodeType.TYPE);
        TypeVariable<?>[] vars;
        try {
            vars = clazz.getTypeParameters();
        } catch (Throwable t) {
            return Map.of();
        }
        if (argNodes.isEmpty() || argNodes.size() != vars.length) {
            return Map.of();
        }
        Map<String, TypeRef> out = new LinkedHashMap<>();
        for (int i = 0; i < vars.length; i++) {
            out.put(vars[i].getName(), typeRefOfTypeNode(argNodes.get(i)));
        }
        return out;
    }

    private TypeRef compute(Node n) {
        return switch (n.type()) {
            case LITERAL -> literalType(n);
            case IDENTIFIER_EXPRESSION -> identifierType(n);
            case THIS_EXPRESSION -> thisType(n);
            case SUPER_EXPRESSION -> superType(n);
            case PARENTHESIZED -> firstExpr(n) != null ? typeOf(firstExpr(n)) : TypeRef.UNKNOWN;
            case FIELD_ACCESS -> fieldAccessType(n);
            case METHOD_CALL -> methodCallType(n);
            case ARRAY_ACCESS -> arrayAccessType(n);
            case CLASS_LITERAL -> classLiteralType(n);
            case ASSIGNMENT -> firstExpr(n) != null ? typeOf(firstExpr(n)) : TypeRef.UNKNOWN;
            case TERNARY -> ternaryType(n);
            case BINARY_EXPRESSION -> binaryType(n);
            case UNARY_EXPRESSION -> unaryType(n);
            case POSTFIX_EXPRESSION -> firstExpr(n) != null ? promoteUnary(typeOf(firstExpr(n))) : TypeRef.UNKNOWN;
            case CAST -> typeRefOfTypeNode(n.child(NodeType.TYPE));
            case INSTANCEOF -> TypeRef.of(boolean.class);
            case NEW_INSTANCE -> newInstanceType(n);
            case NEW_ARRAY -> newArrayType(n);
            case TYPE -> typeRefOfTypeNode(n);   // primitive/type primary of a class literal chain
            default -> TypeRef.UNKNOWN;          // lambdas, method refs, switch expressions, initializers
        };
    }

    // ---- leaves ----------------------------------------------------------------------------------------

    private TypeRef literalType(Node n) {
        Token t = n.token();
        if (t == null) {
            return TypeRef.UNKNOWN;
        }
        String text = t.text();
        return switch (t.type()) {
            case INTEGER_LITERAL, HEX_LITERAL, BINARY_LITERAL, OCTAL_LITERAL ->
                    (text.endsWith("l") || text.endsWith("L")) ? TypeRef.of(long.class) : TypeRef.of(int.class);
            case FLOAT_LITERAL ->
                    (text.endsWith("f") || text.endsWith("F")) ? TypeRef.of(float.class) : TypeRef.of(double.class);
            case CHAR_LITERAL -> TypeRef.of(char.class);
            case STRING_LITERAL, TEXT_BLOCK -> TypeRef.of(String.class);
            case TRUE, FALSE -> TypeRef.of(boolean.class);
            case NULL -> TypeRef.NULL;
            default -> TypeRef.UNKNOWN;
        };
    }

    private TypeRef identifierType(Node n) {
        String name = n.name();
        Scope sc = resolver.scopeOf(n);
        if (name == null || sc == null) {
            return TypeRef.UNKNOWN;
        }
        Symbol s = resolver.symbolOf(n);
        if (s == null) {
            s = sc.resolveVariable(name, n.token().startOffset());
            if (s == null) {
                Symbol t = sc.resolveType(name);
                if (t != null) {
                    s = t;
                }
            }
            if (s != null) {
                resolver.recordSymbol(n, s);
            }
        }
        if (s == null) {
            return index.isPackageRoot(name) ? TypeRef.ofPackage(name) : TypeRef.UNKNOWN;
        }
        return switch (s.kind()) {
            case VARIABLE -> variableType(s);
            case TYPE -> typeUseOf(s);
            case METHOD -> TypeRef.UNKNOWN;
        };
    }

    private TypeRef variableType(Symbol s) {
        Node d = s.declaration();
        if (d == null) {
            // reflected field: re-reflect for its (generic) type
            if (s.clazz() != null) {
                Field f = reflectField(s.clazz(), s.name());
                if (f != null) {
                    try {
                        return substitute(f.getGenericType(), Map.of());
                    } catch (Throwable ignored) {
                        return TypeRef.UNKNOWN;
                    }
                }
            }
            return TypeRef.UNKNOWN;
        }
        return switch (d.type()) {
            case VAR_DECLARATOR -> declaratorType(d);
            case PARAMETER, RECORD_COMPONENT -> {
                TypeRef t = typeRefOfTypeNode(d.child(NodeType.TYPE));
                boolean varargs = d.tokens().stream().anyMatch(tok -> tok.type() == TokenType.ELLIPSIS);
                yield varargs ? t.withDims(t.dims() + 1) : t;
            }
            case TYPE_PATTERN, CATCH -> typeRefOfTypeNode(d.child(NodeType.TYPE));
            case ENUM_CONSTANT -> enclosingTypeOfDecl(d);
            default -> TypeRef.UNKNOWN;
        };
    }

    /** Type of one declarator: the declaration's TYPE (or the initializer's type for {@code var}). */
    private TypeRef declaratorType(Node declarator) {
        TypeRef cached = memo.get(declarator);
        if (cached != null) {
            return cached;
        }
        Node decl = declarator.parent();
        Node typeNode = decl == null ? null : decl.child(NodeType.TYPE);
        TypeRef t;
        if (typeNode != null && "var".equals(typeNode.name())) {
            Node init = firstExpr(declarator);
            t = init != null ? typeOf(init) : TypeRef.UNKNOWN;
            if (t.isNullLiteral()) {
                t = TypeRef.UNKNOWN;
            }
            Class<?> c = t.toClass();
            if (c != null) {
                typeNode.setInferredClass(c);   // resolve the 'var' itself, per the spec
            }
        } else {
            t = typeRefOfTypeNode(typeNode);
            // C-style extra dims on the declarator: int x[];
            long extra = declarator.tokens().stream()
                    .filter(tok -> tok.type() == TokenType.OPENING_SQUARE_BRACKET).count();
            if (extra > 0) {
                t = t.withDims(t.dims() + (int) extra);
            }
        }
        memo.put(declarator, t);
        Class<?> c = t.toClass();
        if (c != null) {
            declarator.setInferredClass(c);
        }
        return t;
    }

    private TypeRef thisType(Node n) {
        // Outer.this: the qualifier IS the type
        for (Node c : n.children()) {
            TypeRef q = typeOf(c);
            if (!q.isUnknown() && !q.isPackage()) {
                return q;
            }
        }
        Scope ts = enclosingTypeScope(n);
        return ts != null ? TypeRef.ofSource(ts, ts.owner(), 0) : TypeRef.UNKNOWN;
    }

    private TypeRef superType(Node n) {
        // Qualified super (Interface.super.m() / Outer.super.m()): the qualifier IS the super context -
        // never fall back to the class's extends supertype.
        for (Node c : n.children()) {
            TypeRef q = typeOf(c);
            return (!q.isUnknown() && !q.isPackage()) ? q : TypeRef.UNKNOWN;
        }
        Scope ts = enclosingTypeScope(n);
        if (ts == null) {
            return TypeRef.UNKNOWN;
        }
        // bare super = the EXTENDS superclass specifically (never an implements interface)
        if (ts.superclassScope != null) {
            return TypeRef.ofSource(ts.superclassScope, ts.superclassScope.owner(), 0);
        }
        if (ts.superclassClass != null) {
            return TypeRef.of(ts.superclassClass);
        }
        return ts.membersClosedDeep() ? TypeRef.of(Object.class) : TypeRef.UNKNOWN;
    }

    // Array access and class literal type inference.
    private TypeRef arrayAccessType(Node n) {
        Node receiver = firstExpr(n);
        if (receiver == null) {
            return TypeRef.UNKNOWN;
        }
        TypeRef recv = typeOf(receiver);
        return recv.isArray() ? recv.withDims(recv.dims() - 1) : TypeRef.UNKNOWN;
    }

    private TypeRef classLiteralType(Node n) {
        Node receiver = firstExpr(n);
        TypeRef inner = receiver != null ? typeOf(receiver) : TypeRef.UNKNOWN;
        return TypeRef.of(Class.class, Map.of("T", inner), 0);
    }

    // New array type inference.
    private TypeRef newArrayType(Node n) {
        Node typeNode = n.child(NodeType.TYPE);
        TypeRef component = typeRefOfTypeNode(typeNode);
        int dims = 0;
        for (Token t : n.tokens()) {
            if (t.type() == TokenType.OPENING_SQUARE_BRACKET) {
                dims++;
            }
        }
        return component.withDims(component.dims() + Math.max(dims, 1));
    }

    // Operator type inference.
    private TypeRef ternaryType(Node n) {
        List<Node> kids = n.children();
        if (kids.size() < 3) {
            return TypeRef.UNKNOWN;
        }
        TypeRef a = typeOf(kids.get(1));
        TypeRef b = typeOf(kids.get(2));
        if (a.isNullLiteral()) {
            return b;
        }
        if (b.isNullLiteral()) {
            return a;
        }
        if (a.toClass() != null && a.toClass() == b.toClass()) {
            return a;
        }
        return TypeRef.UNKNOWN;
    }

    private TypeRef binaryType(Node n) {
        Token op = n.token();
        List<Node> kids = n.children();
        if (op == null || kids.size() < 2) {
            return TypeRef.UNKNOWN;
        }
        TypeRef l = typeOf(kids.get(0));
        TypeRef r = typeOf(kids.get(1));
        return switch (op.type()) {
            case EQUALS, NOT_EQUALS, LESS_THAN, GREATER_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN_OR_EQUAL,
                    LOGICAL_AND, LOGICAL_OR -> TypeRef.of(boolean.class);
            case PLUS -> (is(l, String.class) || is(r, String.class))
                    ? TypeRef.of(String.class) : promoteBinary(l, r);
            case MINUS, STAR, SLASH, PERCENT -> promoteBinary(l, r);
            case AMPERSAND, PIPE, CARET -> (is(l, boolean.class) || is(r, boolean.class))
                    ? TypeRef.of(boolean.class) : promoteBinary(l, r);
            case SHIFT_LEFT, SHIFT_RIGHT, SHIFT_RIGHT_PRESERVING_SIGN -> promoteUnary(l);
            default -> TypeRef.UNKNOWN;
        };
    }

    private TypeRef unaryType(Node n) {
        Token op = n.token();
        Node operand = firstExpr(n);
        if (op == null || operand == null) {
            return TypeRef.UNKNOWN;
        }
        if (op.type() == TokenType.LOGICAL_NOT) {
            return TypeRef.of(boolean.class);
        }
        return promoteUnary(typeOf(operand));
    }

    /**
     * Infers the type of a field access, including package-qualified types and nested types.
     */
    private TypeRef fieldAccessType(Node n) {
        Node receiver = firstExpr(n);
        String name = n.name();
        if (receiver == null || name == null) {
            return TypeRef.UNKNOWN;
        }
        TypeRef recv = typeOf(receiver);
        if (recv.isPackage()) {
            String joined = recv.packagePrefix() + "." + name;
            Class<?> c = index.load(joined);
            if (c != null) {
                return TypeRef.of(c);
            }
            return index.hasPackage(joined) ? TypeRef.ofPackage(joined) : TypeRef.UNKNOWN;
        }
        if (recv.isUnknown() || recv.isNullLiteral()) {
            return TypeRef.UNKNOWN;
        }
        if (recv.isArray()) {
            if (name.equals("length")) {
                return TypeRef.of(int.class);
            }
            problems.add(pos(n) + " " + recv.describe() + " has no field '" + name + "'");
            return TypeRef.UNKNOWN;
        }
        if (recv.clazz() != null) {
            Field f = reflectField(recv.clazz(), name);
            if (f != null) {
                try {
                    return substitute(f.getGenericType(), recv.typeArgs());
                } catch (Throwable ignored) {
                    return TypeRef.UNKNOWN;
                }
            }
            Class<?> nested = nestedClass(recv.clazz(), name);
            if (nested != null) {
                return TypeRef.of(nested);
            }
            if (membersEnumerable(recv.clazz())) {
                problems.add(pos(n) + " " + recv.describe() + " has no field '" + name + "'" + joinedTypeHint(n, receiver, name)
                        + knownNames(recv, false, name));
            }
            return TypeRef.UNKNOWN;
        }
        if (recv.source() != null) {
            Symbol f = recv.source().memberField(name);
            if (f != null) {
                if (privateElsewhere(n, f)) {
                    problems.add(pos(n) + " field '" + name + "' is private in " + recv.describe()
                            + accessorHint(recv.source(), name));
                }
                return variableType(f);
            }
            Symbol nested = recv.source().memberNestedType(name);   // own AND inherited nested types
            if (nested != null) {
                return typeUseOf(nested);
            }
            if (recv.source().membersClosedDeep()) {
                problems.add(pos(n) + " " + recv.describe() + " has no field '" + name + "'" + joinedTypeHint(n, receiver, name)
                        + knownNames(recv, false, name));
            }
            return TypeRef.UNKNOWN;
        }
        return TypeRef.UNKNOWN;
    }

    /**
     * Shared arity check + return-type inference over mixed (source/reflected) method symbols. An
     * arity mismatch is only FLAGGED when the hierarchy is {@code closed} - an unseen overload could
     * otherwise live in an unresolvable supertype.
     */
    private TypeRef checkCandidatesAndInfer(Node n, List<Symbol> candidates, String name,
            List<TypeRef> argTypes, String receiverDesc, boolean closed) {
        List<TypeRef> arityMatches = new ArrayList<>();
        boolean allShapesKnown = true;
        for (Symbol m : candidates) {
            MethodShape shape = shapeOf(m);
            if (shape == null && m.clazz() != null) {
                // several reflected overloads behind one symbol (a static import collapses them): pick by arity and
                // bind method type variables from the arguments - `requireNonNull(x)` has three overloads
                List<Method> reflected = reflectMethods(m.clazz(), m.name());
                if (reflected != null && reflected.size() > 1) {
                    for (Method rm : reflected) {
                        if (arityMatches(rm.getParameterCount(), rm.isVarArgs(), argTypes.size())) {
                            TypeRef ret;
                            try {
                                ret = substitute(rm.getGenericReturnType(), methodTypeArgs(rm, argTypes));
                            } catch (Throwable ignored) {
                                ret = TypeRef.UNKNOWN;
                            }
                            arityMatches.add(ret);
                        }
                    }
                    continue;
                }
            }
            if (shape == null) {
                allShapesKnown = false;
                continue;
            }
            if (arityMatches(shape.params(), shape.varargs(), argTypes.size())) {
                TypeRef ret = shape.returnType();
                if (ret.isUnknown() && shape.reflect() != null) {
                    // generic identity helpers (Objects.requireNonNull, requireNonNullElse …): bind T from the args
                    try {
                        ret = substitute(shape.reflect().getGenericReturnType(), methodTypeArgs(shape.reflect(), argTypes));
                    } catch (Throwable ignored) {
                        ret = TypeRef.UNKNOWN;
                    }
                }
                arityMatches.add(ret);
            }
        }
        if (arityMatches.isEmpty()) {
            if (allShapesKnown && closed) {
                problems.add(pos(n) + " no overload of '" + name + "'"
                        + (receiverDesc != null ? " on " + receiverDesc : "")
                        + " takes " + argTypes.size() + " argument" + (argTypes.size() == 1 ? "" : "s"));
            }
            return TypeRef.UNKNOWN;
        }
        TypeRef first = arityMatches.get(0);
        for (TypeRef t : arityMatches) {
            if (t.toClass() != first.toClass() || t.source() != first.source()) {
                return TypeRef.UNKNOWN;   // ambiguous overload returns - stay honest
            }
        }
        return first;
    }

    private record MethodShape(int params, boolean varargs, TypeRef returnType, Method reflect) {
        MethodShape(int params, boolean varargs, TypeRef returnType) {
            this(params, varargs, returnType, null);
        }
    }

    /** The callable shape of a method symbol, or null when unknowable (authoritative imports etc.). */
    private MethodShape shapeOf(Symbol m) {
        Node d = m.declaration();
        if (d != null) {
            switch (d.type()) {
                case METHOD_DECLARATION -> {
                    List<Node> params = d.children(NodeType.PARAMETER);
                    boolean varargs = params.stream().anyMatch(p -> p.tokens().stream()
                            .anyMatch(t -> t.type() == TokenType.ELLIPSIS));
                    return new MethodShape(params.size(), varargs,
                            typeRefOfTypeNode(d.child(NodeType.TYPE)));
                }
                case RECORD_COMPONENT -> {
                    TypeRef ret = typeRefOfTypeNode(d.child(NodeType.TYPE));
                    boolean varargs = d.tokens().stream().anyMatch(t -> t.type() == TokenType.ELLIPSIS);
                    return new MethodShape(0, false, varargs ? ret.withDims(ret.dims() + 1) : ret);
                }
                case ENUM_DECLARATION -> {
                    // the synthetic values() / valueOf(String)
                    Scope es = resolver.ownScope(d);
                    TypeRef enumRef = es != null ? TypeRef.ofSource(es, d, 0) : TypeRef.UNKNOWN;
                    if (m.name().equals("values")) {
                        return new MethodShape(0, false, enumRef.withDims(1));
                    }
                    return new MethodShape(1, false, enumRef);
                }
                default -> {
                    return null;
                }
            }
        }
        if (m.clazz() != null) {
            List<Method> reflected = reflectMethods(m.clazz(), m.name());
            if (reflected != null && reflected.size() == 1) {
                Method rm = reflected.get(0);
                TypeRef ret;
                try {
                    ret = substitute(rm.getGenericReturnType(), Map.of());
                } catch (Throwable t) {
                    ret = TypeRef.UNKNOWN;
                }
                return new MethodShape(rm.getParameterCount(), rm.isVarArgs(), ret, rm);
            }
            if (reflected != null && !reflected.isEmpty()) {
                // Several overloads behind ONE symbol (static imports collapse them): a single
                // MethodShape can't represent that honestly - treat as unknown so it never flags.
                return null;
            }
        }
        return null;
    }

    private TypeRef methodCallType(Node n) {
        Token pr = n.token();
        if (pr == null || pr.type() != TokenType.IDENTIFIER) {
            return TypeRef.UNKNOWN;   // this(...) / super(...)
        }
        String name = pr.text();
        List<Node> args = argumentsAfter(n, pr.endOffset());
        List<TypeRef> argTypes = args.stream().map(this::typeOf).toList();

        Node receiver = receiverOf(n, pr);
        if (receiver == null) {
            return unqualifiedCallType(n, name, argTypes);
        }
        TypeRef recv = typeOf(receiver);
        if (recv.isUnknown() || recv.isNullLiteral() || recv.isPackage()) {
            return TypeRef.UNKNOWN;
        }
        if (recv.isArray()) {
            if (name.equals("clone")) {
                return recv;
            }
            recv = TypeRef.of(Object.class);   // arrays expose Object's methods
        }
        if (recv.clazz() != null) {
            return classReceiverCall(n, recv, name, argTypes);
        }
        if (recv.source() != null) {
            return sourceReceiverCall(n, recv, name, argTypes);
        }
        return TypeRef.UNKNOWN;
    }

    private TypeRef classReceiverCall(Node n, TypeRef recv, String name, List<TypeRef> argTypes) {
        List<Method> candidates = reflectMethods(recv.clazz(), name);
        if (candidates == null) {
            return TypeRef.UNKNOWN;   // reflection failed - open
        }
        if (candidates.isEmpty()) {
            problems.add(pos(n) + " " + recv.describe() + " has no method '" + name + "'" + knownNames(recv, true, name));
            return TypeRef.UNKNOWN;
        }
        List<Method> byArity = new ArrayList<>();
        for (Method m : candidates) {
            if (arityMatches(m.getParameterCount(), m.isVarArgs(), argTypes.size())) {
                byArity.add(m);
            }
        }
        if (byArity.isEmpty()) {
            problems.add(pos(n) + " no overload of '" + name + "' on " + recv.describe()
                    + " takes " + argTypes.size() + " argument" + (argTypes.size() == 1 ? "" : "s"));
            return TypeRef.UNKNOWN;
        }
        Method chosen = chooseOverload(byArity, argTypes);
        try {
            // a METHOD type variable in the return type (<T> T requireNonNull(T)) is bound by the argument it also types -
            // without this, `var x = requireNonNull(y)` left `x` unknown and every call on it name-only
            Map<String, TypeRef> vars = new HashMap<>(recv.typeArgs());
            vars.putAll(methodTypeArgs(chosen, argTypes));
            return substitute(chosen.getGenericReturnType(), vars);
        } catch (Throwable ignored) {
            return TypeRef.UNKNOWN;
        }
    }

    private TypeRef sourceReceiverCall(Node n, TypeRef recv, String name, List<TypeRef> argTypes) {
        boolean closed = recv.source().membersClosedDeep();
        List<Symbol> candidates = recv.source().memberMethods(name);
        if (candidates.isEmpty()) {
            if (closed) {
                problems.add(pos(n) + " " + recv.describe() + " has no method '" + name + "'" + knownNames(recv, true, name));
            }
            return TypeRef.UNKNOWN;
        }
        if (candidates.stream().allMatch(c -> privateElsewhere(n, c))) {
            problems.add(pos(n) + " method '" + name + "' is private in " + recv.describe());
        }
        return checkCandidatesAndInfer(n, candidates, name, argTypes, recv.describe(), closed);
    }

    private TypeRef unqualifiedCallType(Node n, String name, List<TypeRef> argTypes) {
        Scope sc = resolver.scopeOf(n);
        if (sc == null) {
            return TypeRef.UNKNOWN;
        }
        List<Symbol> candidates = sc.resolveMethods(name);
        if (candidates.isEmpty()) {
            return TypeRef.UNKNOWN;   // existence is M3's report
        }
        return checkCandidatesAndInfer(n, candidates, name, argTypes, null, sc.membersProvable());
    }

    private TypeRef newInstanceType(Node n) {
        Node typeNode = n.child(NodeType.TYPE);
        TypeRef type = typeRefOfTypeNode(typeNode);
        Node body = n.child(NodeType.CLASS_BODY);
        Scope bodyScope = body != null ? resolver.ownScope(body) : null;
        validateConstructor(n, type, body != null);
        if (bodyScope != null) {
            return TypeRef.ofSource(bodyScope, n, 0);   // the anonymous class itself
        }
        return type;
    }

    private void validateConstructor(Node n, TypeRef type, boolean anonymous) {
        int argCount = constructorArgs(n).size();
        if (type.clazz() != null && type.dims() == 0) {
            Class<?> c = type.clazz();
            try {
                if (c.isInterface() || Modifier.isAbstract(c.getModifiers()) || c.isEnum()) {
                    return;   // anonymous impls / invalid-anyway cases - not arity's business
                }
                // Non-static member classes get a synthetic leading outer-instance parameter in
                // reflection (outer.new Inner() ↔ Inner(Outer)); accept both arities.
                int synthetic = (c.isMemberClass() || c.isLocalClass()) && !Modifier.isStatic(c.getModifiers())
                        ? 1 : 0;
                Constructor<?>[] ctors = c.getDeclaredConstructors();
                for (Constructor<?> ctor : ctors) {
                    if (arityMatches(ctor.getParameterCount(), ctor.isVarArgs(), argCount)
                            || (synthetic == 1
                                    && arityMatches(ctor.getParameterCount() - 1, ctor.isVarArgs(), argCount))) {
                        return;
                    }
                }
                problems.add(pos(n) + " no constructor of " + type.describe() + " takes " + argCount
                        + " argument" + (argCount == 1 ? "" : "s"));
            } catch (Throwable ignored) {
                // reflection failed - open
            }
            return;
        }
        if (type.source() != null && type.dims() == 0 && type.sourceDecl() != null) {
            Node decl = type.sourceDecl();
            NodeType k = decl.type();
            if (k == NodeType.INTERFACE_DECLARATION || k == NodeType.ANNOTATION_TYPE_DECLARATION
                    || k == NodeType.ENUM_DECLARATION) {
                return;
            }
            List<int[]> shapes = new ArrayList<>();   // {params, varargs}
            for (Node ctor : decl.descendants(NodeType.CONSTRUCTOR_DECLARATION)) {
                if (ctor.parent() != null && ctor.parent().parent() == decl) {
                    List<Node> ps = ctor.children(NodeType.PARAMETER);
                    boolean va = ps.stream().anyMatch(p -> p.tokens().stream()
                            .anyMatch(t -> t.type() == TokenType.ELLIPSIS));
                    shapes.add(new int[] {ps.size(), va ? 1 : 0});
                }
            }
            if (k == NodeType.RECORD_DECLARATION) {
                // the canonical constructor always exists, even when secondary ctors are declared -
                // and it is varargs when the last component is (record TestCase(Status s, Object... vs))
                List<Node> comps = decl.children(NodeType.RECORD_COMPONENT);
                boolean canonicalVarargs = !comps.isEmpty() && comps.get(comps.size() - 1).tokens().stream()
                        .anyMatch(t -> t.type() == TokenType.ELLIPSIS);
                shapes.add(new int[] {comps.size(), canonicalVarargs ? 1 : 0});
            } else if (shapes.isEmpty()) {
                shapes.add(new int[] {0, 0});   // implicit default constructor
            }
            for (int[] s : shapes) {
                if (arityMatches(s[0], s[1] == 1, argCount)) {
                    return;
                }
            }
            problems.add(pos(n) + " no constructor of " + type.describe() + " takes " + argCount
                    + " argument" + (argCount == 1 ? "" : "s"));
        }
    }

    /**
     * The members the receiver's type does have, the closest spellings to {@code wanted} first (at most 40 shown), so a
     * missing-member problem lets a corrected edit name a real member instead of guessing again.
     */
    static String knownNames(TypeRef recv, boolean methods, String wanted) {
        Set<String> names = new LinkedHashSet<>();
        try {
            if (recv.source() != null) {
                for (Symbol sym : methods ? recv.source().declaredMethods() : recv.source().declaredVariables()) {
                    names.add(sym.name());
                }
            } else if (recv.clazz() != null) {
                if (methods) {
                    for (Method m : recv.clazz().getMethods()) {
                        names.add(m.getName());
                    }
                } else {
                    for (Field f : recv.clazz().getFields()) {
                        names.add(f.getName());
                    }
                }
            }
        } catch (Throwable ignored) {
            return "";
        }
        if (names.isEmpty()) {
            return "";
        }
        List<String> ranked = new ArrayList<>(names);
        String w = wanted.toLowerCase(Locale.ROOT);
        ranked.sort(Comparator.comparingInt((String x) -> -sharedLetters(w, x.toLowerCase(Locale.ROOT)))
                .thenComparing(x -> x));
        List<String> shown = ranked.subList(0, Math.min(40, ranked.size()));
        return " — its " + (methods ? "methods" : "fields") + ": " + String.join(", ", shown)
                + (ranked.size() > shown.size() ? ", … (" + ranked.size() + " in all)" : "");
    }

    /** How many letters of {@code a} occur in {@code b}, each counted once per occurrence. */
    static int sharedLetters(String a, String b) {
        int[] counts = new int[128];
        for (char c : b.toCharArray()) {
            if (c < 128) {
                counts[c]++;
            }
        }
        int shared = 0;
        for (char c : a.toCharArray()) {
            if (c < 128 && counts[c] > 0) {
                counts[c]--;
                shared++;
            }
        }
        return shared;
    }

    /**
     * When the receiver is a simple name and that name joined to the missing member names a visible type (a nested
     * name written where one compound type name was meant), the problem names that type.
     */
    private String joinedTypeHint(Node access, Node receiver, String name) {
        if (receiver.type() != NodeType.IDENTIFIER_EXPRESSION || receiver.name() == null) {
            return "";
        }
        Scope sc = resolver.scopeOf(access);
        if (sc == null) {
            return "";
        }
        String joined = receiver.name() + name;
        return sc.resolveType(joined) != null ? " — did you mean the type '" + joined + "'?" : "";
    }

    /**
     * An operator whose operand provably cannot take it is a problem: '!', '&&', '||' and a '?:' condition on a known
     * non-boolean type, and '=='/'!=' between a boolean and a type that cannot be compared with one. Unknown types stay
     * silent, as everywhere in this pass.
     */
    String operatorMisuse(Node n) {
        Token op = n.token();
        if (n.type() == NodeType.UNARY_EXPRESSION && op != null && op.type() == TokenType.LOGICAL_NOT) {
            Node operand = firstExpr(n);
            if (operand != null && notBoolean(typeOf(operand))) {
                return pos(n) + " operator '!' needs a boolean, but its operand '" + text(operand) + "' is "
                        + typeOf(operand).describe();
            }
        }
        if (n.type() == NodeType.BINARY_EXPRESSION && op != null) {
            List<Node> kids = n.children();
            if (kids.size() < 2) {
                return null;
            }
            TypeRef l = typeOf(kids.get(0));
            TypeRef r = typeOf(kids.get(1));
            if (op.type() == TokenType.LOGICAL_AND || op.type() == TokenType.LOGICAL_OR) {
                Node bad = notBoolean(l) ? kids.get(0) : notBoolean(r) ? kids.get(1) : null;
                if (bad != null) {
                    return pos(n) + " operator '" + op.text() + "' needs booleans, but '" + text(bad) + "' is "
                            + typeOf(bad).describe();
                }
            }
            if (op.type() == TokenType.EQUALS || op.type() == TokenType.NOT_EQUALS) {
                if ((isBooleanType(l) && notComparableWithBoolean(r)) || (isBooleanType(r) && notComparableWithBoolean(l))) {
                    return pos(n) + " operator '" + op.text() + "' cannot compare " + l.describe() + " with "
                            + r.describe() + " ('" + text(kids.get(0)) + "' and '" + text(kids.get(1)) + "')";
                }
            }
        }
        if (n.type() == NodeType.TERNARY) {
            Node condition = firstExpr(n);
            if (condition != null && notBoolean(typeOf(condition))) {
                return pos(n) + " the condition of '?:' must be a boolean, but '" + text(condition) + "' is "
                        + typeOf(condition).describe();
            }
        }
        return null;
    }

    /** An if/while/do condition of a provably non-boolean type. */
    String conditionMisuse(Node statement) {
        Node condition = firstExpr(statement);
        if (condition != null && notBoolean(typeOf(condition))) {
            return pos(statement) + " the condition must be a boolean, but '" + text(condition) + "' is "
                    + typeOf(condition).describe();
        }
        return null;
    }

    /** A known type that is not boolean or Boolean (an array, a source-declared type, any other class). */
    private static boolean notBoolean(TypeRef t) {
        if (t.isUnknown() || t.isNullLiteral() || t.isPackage()) {
            return false;
        }
        if (t.dims() > 0 || t.source() != null) {
            return true;
        }
        Class<?> c = t.clazz();
        return c != null && c != boolean.class && c != Boolean.class;
    }

    private static boolean isBooleanType(TypeRef t) {
        return !t.isUnknown() && t.dims() == 0 && (t.clazz() == boolean.class || t.clazz() == Boolean.class);
    }

    /** Known, not boolean, and not a supertype of Boolean (Object, Serializable and Comparable compare by boxing). */
    private static boolean notComparableWithBoolean(TypeRef t) {
        if (!notBoolean(t)) {
            return false;
        }
        Class<?> c = t.clazz();
        return c == null || t.dims() > 0 || !c.isAssignableFrom(Boolean.class);
    }

    private static String text(Node n) {
        String s = n.toString().strip().replaceAll("\\s+", " ");
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    /**
     * A call that provably returns void, used where a value is needed (an argument, a receiver, an initializer, an
     * operand, a returned value, a condition), is a problem. A void call stays legal as a statement, as a lambda body
     * and in a for-loop's update list.
     */
    String voidValueMisuse(Node n) {
        if (n.type() != NodeType.METHOD_CALL || n.parent() == null || !VALUE_CONTEXTS.contains(n.parent().type())) {
            return null;
        }
        TypeRef t = typeOf(n);
        if (t.isUnknown() || t.dims() != 0 || t.clazz() != void.class) {
            return null;
        }
        return pos(n) + " '" + text(n) + "' returns void, so it has no value to use in "
                + n.parent().type().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + " — call it as a statement of its own";
    }

    /** The parents in which an expression must have a value. */
    private static final Set<NodeType> VALUE_CONTEXTS = EnumSet.of(NodeType.METHOD_CALL,
            NodeType.NEW_INSTANCE, NodeType.FIELD_ACCESS, NodeType.ARRAY_ACCESS, NodeType.VAR_DECLARATOR,
            NodeType.ASSIGNMENT, NodeType.RETURN, NodeType.BINARY_EXPRESSION, NodeType.UNARY_EXPRESSION,
            NodeType.TERNARY, NodeType.CAST, NodeType.PARENTHESIZED, NodeType.INSTANCEOF, NodeType.IF,
            NodeType.WHILE, NodeType.DO);

    /** A private field or method is reachable only inside the top-level class that declares it. */
    static boolean privateElsewhere(Node site, Symbol member) {
        Node decl = member.declaration();
        if (decl == null) {
            return false;
        }
        Node holder = decl.type() == NodeType.VAR_DECLARATOR ? decl.parent() : decl;
        if (holder == null || holder.type() != NodeType.FIELD_DECLARATION && holder.type() != NodeType.METHOD_DECLARATION
                || !holder.hasModifier(TokenType.PRIVATE)) {
            return false;
        }
        Node here = outermostType(site);
        Node there = outermostType(holder);
        return here != null && there != null && here != there;
    }

    /** The top-level type declaration that contains {@code n}, or null. */
    private static Node outermostType(Node n) {
        Node outer = null;
        for (Node a = n; a != null; a = a.parent()) {
            if (a.type().isTypeDeclaration()) {
                outer = a;
            }
        }
        return outer;
    }

    /** " - call its method 'name()'" when the type has a no-argument method of that name (or a getter), else "". */
    private static String accessorHint(Scope type, String name) {
        for (String m : List.of(name, "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1),
                "is" + Character.toUpperCase(name.charAt(0)) + name.substring(1))) {
            for (Symbol s : type.memberMethods(m)) {
                Node d = s.declaration();
                if (d != null && !d.hasModifier(TokenType.PRIVATE) && d.children(NodeType.PARAMETER).isEmpty()) {
                    return " — call its method '" + m + "()'";
                }
            }
        }
        return "";
    }
}
