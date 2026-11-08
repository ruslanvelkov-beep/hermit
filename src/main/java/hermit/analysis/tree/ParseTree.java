package hermit.analysis.tree;

import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import java.util.ArrayList;
import java.util.List;

/**
 * A parsed Java compilation unit: the {@link #root()} COMPILATION_UNIT node plus convenience accessors
 * for the package, imports and the type declarations (top-level, nested, anonymous). {@link #toString()}
 * reproduces the original source exactly while the tree is unmodified; after mutations (milestone 5) it
 * renders the edited program.
 */
public final class ParseTree {

    private final Node root;

    ParseTree(Node root) {
        this.root = root;
    }

    /** Lex + parse a whole compilation unit. */
    public static ParseTree parse(String source) {
        return JavaParser.parse(source);
    }

    public Node root() {
        return root;
    }

    /** The declared package name ("" for the default package). */
    public String packageName() {
        Node pkg = root.child(NodeType.PACKAGE_DECLARATION);
        if (pkg == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Token t : pkg.tokens()) {
            if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.DOT) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /** All import declarations, in order. */
    public List<Node> imports() {
        return root.children(NodeType.IMPORT_DECLARATION);
    }

    /** The imported name of an IMPORT_DECLARATION (e.g. {@code java.util.List} or {@code java.util.*}). */
    public static String importedName(Node importDecl) {
        StringBuilder sb = new StringBuilder();
        boolean skippedModule = false;
        for (Token t : importDecl.tokens()) {
            switch (t.type()) {
                case IDENTIFIER -> {
                    // `import module java.base;` — the contextual 'module' keyword is not part of the name.
                    if (!skippedModule && sb.isEmpty() && t.text().equals("module") && isModuleImport(importDecl)) {
                        skippedModule = true;
                    } else {
                        sb.append(t.text());
                    }
                }
                case DOT, STAR -> sb.append(t.text());
                default -> { /* import/static keywords, semicolon, trivia */ }
            }
        }
        return sb.toString();
    }

    /** True for a module import declaration ({@code import module java.base;}, JEP 511). */
    public static boolean isModuleImport(Node importDecl) {
        List<Token> ids = importDecl.tokens().stream()
                .filter(t -> t.type() == TokenType.IDENTIFIER)
                .toList();
        return ids.size() >= 2 && ids.get(0).text().equals("module")
                && !importDecl.hasModifier(TokenType.STATIC);
    }

    /** True if the import is a {@code static} import. */
    public static boolean isStaticImport(Node importDecl) {
        return importDecl.hasModifier(TokenType.STATIC);
    }

    /** True if the import ends in {@code .*}. */
    public static boolean isWildcardImport(Node importDecl) {
        return importedName(importDecl).endsWith("*");
    }

    /** Top-level type declarations only. */
    public List<Node> typeDeclarations() {
        List<Node> out = new ArrayList<>();
        for (Node c : root.children()) {
            if (c.type().isTypeDeclaration()) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * ALL types defined in this file: top-level, nested and local declarations, plus anonymous classes
     * (a NEW_INSTANCE that carries a CLASS_BODY). Lambdas are reachable via
     * {@code root().descendants(NodeType.LAMBDA)}.
     */
    public List<Node> allTypes() {
        List<Node> out = new ArrayList<>();
        root.walk(n -> {
            if (n.type().isTypeDeclaration()) {
                out.add(n);
            } else if (n.type() == NodeType.NEW_INSTANCE && n.child(NodeType.CLASS_BODY) != null) {
                out.add(n);
            }
        });
        return out;
    }

    /**
     * The first declared type with the given simple name (searches nested/local types too), or null. A dotted name
     * ("Outer.Inner") names a nested type by the types around it: "Outer.Exec#Exec" was not
     * found.
     */
    public Node findType(String name) {
        int dot = name.lastIndexOf('.');
        String simpleName = name.substring(dot + 1);
        for (Node t : allTypes()) {
            if (simpleName.equals(t.name()) && (dot < 0 || enclosedBy(t, name.substring(0, dot)))) {
                return t;
            }
        }
        return null;
    }

    /** True when the types around {@code type}, innermost last, end with the dotted {@code outer}. */
    private static boolean enclosedBy(Node type, String outer) {
        String[] names = outer.split("\\.");
        Node at = type.parent();
        for (int i = names.length - 1; i >= 0; i--) {
            while (at != null && !at.type().isTypeDeclaration()) {
                at = at.parent();
            }
            if (at == null || !names[i].equals(at.name())) {
                return false;
            }
            at = at.parent();
        }
        return true;
    }

    /** The first method named {@code methodName} inside the type named {@code typeSimpleName}, or null. */
    public Node findMethod(String typeSimpleName, String methodName) {
        Node type = findType(typeSimpleName);
        if (type == null) {
            return null;
        }
        for (Node m : type.descendants(NodeType.METHOD_DECLARATION)) {
            if (methodName.equals(m.name())) {
                return m;
            }
        }
        return null;
    }

    /** The exact source (or, after mutations, the edited program). */
    @Override
    public String toString() {
        return root.toString();
    }
}
