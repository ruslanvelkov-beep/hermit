package hermit.analysis.sym;

import hermit.analysis.tree.Node;

/**
 * One resolvable name: a type, a variable (local/parameter/field/binder/enum constant/record component)
 * or a method. A symbol is backed EITHER by a source declaration {@link #declaration() Node} (declared in
 * the parsed file) or by a classpath {@link #clazz() Class} (JDK / compiled project classes) — or by both
 * being null for names that are known to exist but not loadable (e.g. a single-type import whose class is
 * not on the classpath: the import is still authoritative about the name).
 */
public final class Symbol {

    public enum Kind { TYPE, VARIABLE, METHOD }

    private final Kind kind;
    private final String name;
    private final Node declaration;    // source declaration node, or null for classpath symbols
    private final Class<?> clazz;      // TYPE: the class itself; VARIABLE/METHOD: the owning class; nullable
    private final int declOffset;      // source offset where visibility starts; -1 = whole scope

    private Symbol(Kind kind, String name, Node declaration, Class<?> clazz, int declOffset) {
        this.kind = kind;
        this.name = name;
        this.declaration = declaration;
        this.clazz = clazz;
        this.declOffset = declOffset;
    }

    public static Symbol type(String name, Node declaration) {
        return new Symbol(Kind.TYPE, name, declaration, null, -1);
    }

    public static Symbol type(String name, Class<?> clazz) {
        return new Symbol(Kind.TYPE, name, null, clazz, -1);
    }

    /** A type known by name only (e.g. named by a single-type import but not loadable). */
    public static Symbol typeByName(String name) {
        return new Symbol(Kind.TYPE, name, null, null, -1);
    }

    /** A variable visible from {@code declOffset} on (locals, binders); -1 = the whole scope (params, fields). */
    public static Symbol variable(String name, Node declaration, int declOffset) {
        return new Symbol(Kind.VARIABLE, name, declaration, null, declOffset);
    }

    /** A field/constant found reflectively on a classpath type. */
    public static Symbol variable(String name, Class<?> owner) {
        return new Symbol(Kind.VARIABLE, name, null, owner, -1);
    }

    public static Symbol method(String name, Node declaration) {
        return new Symbol(Kind.METHOD, name, declaration, null, -1);
    }

    /** A method found reflectively on a classpath type. */
    public static Symbol method(String name, Class<?> owner) {
        return new Symbol(Kind.METHOD, name, null, owner, -1);
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public Node declaration() {
        return declaration;
    }

    public Class<?> clazz() {
        return clazz;
    }

    public int declOffset() {
        return declOffset;
    }

    @Override
    public String toString() {
        return kind + " " + name
                + (clazz != null ? " [" + clazz.getName() + "]" : "")
                + (declaration != null ? " (declared " + declaration.type() + ")" : "");
    }
}
