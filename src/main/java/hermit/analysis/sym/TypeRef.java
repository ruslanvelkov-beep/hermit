package hermit.analysis.sym;

import hermit.analysis.tree.Node;
import java.util.Collections;
import java.util.Map;

/**
 * The inferred type of an expression. Four shapes:
 * <ul>
 *   <li><b>class-backed</b> - a loadable {@link Class} (JDK / compiled project classes), optionally with a
 *       single-level map of its type variables to argument TypeRefs ({@code List<String>} → {@code {E→String}}),
 *       so {@code get(int)} substitutes to {@code String};</li>
 *   <li><b>source-backed</b> - a type declared in the parsed file, known through its {@link Scope};</li>
 *   <li><b>package prefix</b> - the head of a fully-qualified chain ({@code java} → {@code java.util} → a
 *       class);</li>
 *   <li><b>UNKNOWN / NULL</b> - no information (generics we can't substitute, open hierarchies, the
 *       {@code null} literal). Unknown types make downstream checks silent, never wrong.</li>
 * </ul>
 * Array types are any of the above plus {@link #dims()} dimensions.
 */
public final class TypeRef {

    public static final TypeRef UNKNOWN = new TypeRef(null, null, null, 0, Map.of(), null);
    /** The type of the {@code null} literal — assignable to any reference type. */
    public static final TypeRef NULL = new TypeRef(null, null, null, 0, Map.of(), null);

    private final Class<?> clazz;                 // raw class, or null
    private final Scope source;                   // TYPE scope of a source-declared type, or null
    private final Node sourceDecl;                // its declaration node, or null
    private final int dims;                       // array dimensions stacked on top
    private final Map<String, TypeRef> typeArgs;  // type-variable name -> argument (may be empty)
    private final String packagePrefix;           // non-null for a package-prefix pseudo type

    private TypeRef(Class<?> clazz, Scope source, Node sourceDecl, int dims,
            Map<String, TypeRef> typeArgs, String packagePrefix) {
        this.clazz = clazz;
        this.source = source;
        this.sourceDecl = sourceDecl;
        this.dims = dims;
        this.typeArgs = typeArgs;
        this.packagePrefix = packagePrefix;
    }

    public static TypeRef of(Class<?> clazz) {
        return of(clazz, Map.of(), 0);
    }

    public static TypeRef of(Class<?> clazz, Map<String, TypeRef> typeArgs, int dims) {
        if (clazz == null) {
            return UNKNOWN;
        }
        // normalize: an array Class folds into dims over its component
        while (clazz.isArray()) {
            clazz = clazz.getComponentType();
            dims++;
        }
        return new TypeRef(clazz, null, null, dims, Map.copyOf(typeArgs), null);
    }

    public static TypeRef ofSource(Scope typeScope, Node decl, int dims) {
        return new TypeRef(null, typeScope, decl, dims, Map.of(), null);
    }

    public static TypeRef ofPackage(String prefix) {
        return new TypeRef(null, null, null, 0, Map.of(), prefix);
    }

    public TypeRef withDims(int newDims) {
        if (newDims == dims) {
            return this;
        }
        return new TypeRef(clazz, source, sourceDecl, newDims, typeArgs, packagePrefix);
    }

    public Class<?> clazz() {
        return clazz;
    }

    public Scope source() {
        return source;
    }

    public Node sourceDecl() {
        return sourceDecl;
    }

    public int dims() {
        return dims;
    }

    public Map<String, TypeRef> typeArgs() {
        return Collections.unmodifiableMap(typeArgs);
    }

    public String packagePrefix() {
        return packagePrefix;
    }

    public boolean isUnknown() {
        return this == UNKNOWN || (clazz == null && source == null && packagePrefix == null && this != NULL);
    }

    public boolean isNullLiteral() {
        return this == NULL;
    }

    public boolean isPackage() {
        return packagePrefix != null;
    }

    public boolean isArray() {
        return dims > 0;
    }

    public boolean isPrimitive() {
        return dims == 0 && clazz != null && clazz.isPrimitive();
    }

    /** Materialize as a {@link Class} (arrays included), or null when not class-backed. */
    public Class<?> toClass() {
        if (clazz == null) {
            return null;
        }
        Class<?> c = clazz;
        for (int i = 0; i < dims; i++) {
            c = c.arrayType();
        }
        return c;
    }

    /** Human-readable name for problem messages. */
    public String describe() {
        String base;
        if (this == NULL) {
            base = "null";
        } else if (packagePrefix != null) {
            base = "package " + packagePrefix;
        } else if (clazz != null) {
            // getSimpleName() can trigger class loading on a partial classpath — parse the name instead
            String name;
            try {
                name = clazz.getSimpleName();
            } catch (Throwable t) {
                name = clazz.getName();
                int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
                name = cut < 0 ? name : name.substring(cut + 1);
            }
            base = name;
        } else if (sourceDecl != null && sourceDecl.name() != null) {
            base = sourceDecl.name();
        } else if (source != null) {
            base = "<anonymous>";
        } else {
            base = "<unknown>";
        }
        return base + "[]".repeat(Math.max(0, dims));
    }

    @Override
    public String toString() {
        return describe();
    }
}
