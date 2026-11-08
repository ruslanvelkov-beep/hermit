package hermit.analysis.tree;

import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * One node of the concrete parse tree. A node owns an ordered list of PARTS, each either a {@link Token}
 * (including whitespace/comment trivia) or a child {@code Node} — so {@link #toString()} is simply the
 * recursive concatenation of parts and reproduces the original source exactly for an unmodified tree.
 *
 * <p>Every node knows its {@link #parent()} (tree is traversable in both directions), its
 * {@link #token() principal token} (the IF keyword for an IF node, the method name for a METHOD_CALL,
 * the declared identifier for declarations, the operator for binary/assignment expressions…), and has a
 * nullable {@link #inferredClass()} slot filled by the type-inference pass.
 */
public final class Node {

    private NodeType type;
    private Node parent;
    private final List<Object> parts = new ArrayList<>();   // Token | Node, in exact source order
    private Token principal;
    private Class<?> inferredClass;                          // filled by the type-inference pass (nullable)

    public Node(NodeType type) {
        this.type = type;
    }

    // ---- identity ----------------------------------------------------------------------------------

    public NodeType type() {
        return type;
    }

    /** Re-type this node (e.g. SWITCH → SWITCH_FUNCTION once an arrow label is seen). */
    void retype(NodeType newType) {
        this.type = newType;
    }

    public Node parent() {
        return parent;
    }

    /** The principal/representative token of this node (see class javadoc); may be null for containers. */
    public Token token() {
        return principal;
    }

    void setPrincipal(Token token) {
        this.principal = token;
    }

    /** The principal token's text — the natural "name" for declarations, calls and identifiers. */
    public String name() {
        return principal == null ? null : principal.text();
    }

    public Class<?> inferredClass() {
        return inferredClass;
    }

    public void setInferredClass(Class<?> c) {
        this.inferredClass = c;
    }

    // ---- structure ---------------------------------------------------------------------------------

    public void add(Token t) {
        parts.add(t);
    }

    public void add(Node child) {
        child.parent = this;
        parts.add(child);
    }

    // ---- mutation primitives (public for the tree editor in hermit.analysis.edit) --------------------

    /** Identity index of a part (token or child node) in this node's parts, or -1. */
    public int partIndexOf(Object part) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) == part) {
                return i;
            }
        }
        return -1;
    }

    /** Insert a part at {@code index}, re-parenting when it is a node. */
    public void insertPart(int index, Object part) {
        if (part instanceof Node n) {
            n.parent = this;
        }
        parts.add(index, part);
    }

    /** Remove the part at {@code index}. */
    public void removePartAt(int index) {
        parts.remove(index);
    }

    /** Replace the part at {@code index}, re-parenting when the new part is a node. */
    public void replacePartAt(int index, Object part) {
        if (part instanceof Node n) {
            n.parent = this;
        }
        parts.set(index, part);
    }

    /** Prepend a token (synthesized leading trivia for an inserted node). */
    public void prependToken(Token t) {
        parts.add(0, t);
    }

    /**
     * Detach and return this subtree's leading trivia run, in order - recursing into leading child
     * nodes, where the parser may have attached it. Only safe on freshly parsed nodes that are not
     * yet part of a snapshotted tree (child parts are mutated without journaling).
     */
    public List<Token> detachLeadingTrivia() {
        List<Token> out = new ArrayList<>();
        Node n = this;
        while (true) {
            while (!n.parts.isEmpty() && n.parts.get(0) instanceof Token t && t.isTrivia()) {
                out.add(t);
                n.parts.remove(0);
            }
            if (!n.parts.isEmpty() && n.parts.get(0) instanceof Node child) {
                n = child;
                continue;
            }
            return out;
        }
    }

    /** A snapshot of the parts list (for the editor's rollback journal). */
    public List<Object> partsSnapshot() {
        return new ArrayList<>(parts);
    }

    /** Restore a previously taken snapshot, re-parenting node parts back to this node. */
    public void restoreParts(List<Object> snapshot) {
        parts.clear();
        parts.addAll(snapshot);
        for (Object p : parts) {
            if (p instanceof Node n) {
                n.parent = this;
            }
        }
    }

    /** Move all parts of this node into {@code target} (in order), re-parenting child nodes. */
    void transplantInto(Node target) {
        for (Object p : parts) {
            if (p instanceof Node n) {
                target.add(n);
            } else {
                target.add((Token) p);
            }
        }
        parts.clear();
    }

    /** The ordered parts: each element is a {@link Token} or a {@link Node}. Unmodifiable view. */
    public List<Object> parts() {
        return Collections.unmodifiableList(parts);
    }

    /** Direct child nodes, in order (tokens skipped). */
    public List<Node> children() {
        List<Node> out = new ArrayList<>();
        for (Object p : parts) {
            if (p instanceof Node n) {
                out.add(n);
            }
        }
        return out;
    }

    /** Direct child nodes of the given type. */
    public List<Node> children(NodeType t) {
        List<Node> out = new ArrayList<>();
        for (Object p : parts) {
            if (p instanceof Node n && n.type == t) {
                out.add(n);
            }
        }
        return out;
    }

    /** First direct child of the given type, or null. */
    public Node child(NodeType t) {
        for (Object p : parts) {
            if (p instanceof Node n && n.type == t) {
                return n;
            }
        }
        return null;
    }

    /** Tokens that are DIRECT parts of this node (children's tokens not included). */
    public List<Token> tokens() {
        List<Token> out = new ArrayList<>();
        for (Object p : parts) {
            if (p instanceof Token t) {
                out.add(t);
            }
        }
        return out;
    }

    /** True if a token of the given type appears among this node's direct parts (e.g. a PUBLIC modifier). */
    public boolean hasModifier(TokenType t) {
        for (Object p : parts) {
            if (p instanceof Token tok && tok.type() == t) {
                return true;
            }
        }
        return false;
    }

    /** Pre-order traversal of this node and all descendants. */
    public void walk(Consumer<Node> visitor) {
        visitor.accept(this);
        for (Object p : parts) {
            if (p instanceof Node n) {
                n.walk(visitor);
            }
        }
    }

    /** All descendant nodes (including this one) of the given type, pre-order. */
    public List<Node> descendants(NodeType t) {
        List<Node> out = new ArrayList<>();
        walk(n -> {
            if (n.type == t) {
                out.add(n);
            }
        });
        return out;
    }

    /** The first token of this subtree in source order (trivia included), or null if empty. */
    public Token firstToken() {
        for (Object p : parts) {
            if (p instanceof Token t) {
                return t;
            }
            Token inner = ((Node) p).firstToken();
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    /** The first NON-TRIVIA token of this subtree — the position code actually starts at. */
    public Token firstCodeToken() {
        for (Object p : parts) {
            if (p instanceof Token t) {
                if (!t.isTrivia()) {
                    return t;
                }
            } else {
                Token inner = ((Node) p).firstCodeToken();
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    /** The last token of this subtree in source order, or null if empty. */
    public Token lastToken() {
        for (int k = parts.size() - 1; k >= 0; k--) {
            Object p = parts.get(k);
            if (p instanceof Token t) {
                return t;
            }
            Token inner = ((Node) p).lastToken();
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    // ---- rendering -----------------------------------------------------------------------------------

    /** Exact source text of this subtree: the recursive concatenation of all parts. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        appendTo(sb);
        return sb.toString();
    }

    private void appendTo(StringBuilder sb) {
        for (Object p : parts) {
            if (p instanceof Token t) {
                sb.append(t.text());
            } else {
                ((Node) p).appendTo(sb);
            }
        }
    }

    /** Indented one-node-per-line structural dump (types + names), for debugging and error feedback. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        dump(sb, 0);
        return sb.toString();
    }

    private void dump(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth)).append(type);
        if (principal != null) {
            sb.append(" '").append(principal.text()).append('\'');
        }
        Token first = firstToken();
        if (first != null) {
            sb.append("  @").append(first.line()).append(':').append(first.column());
        }
        sb.append('\n');
        for (Object p : parts) {
            if (p instanceof Node n) {
                n.dump(sb, depth + 1);
            }
        }
    }
}
