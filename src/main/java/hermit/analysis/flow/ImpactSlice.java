package hermit.analysis.flow;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.sym.Scope;
import hermit.analysis.sym.Symbol;
import hermit.analysis.sym.SymbolResolver;
import hermit.analysis.sym.TypeInference;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forward-slice impact analysis (see docs/design-impact-slice.md): from a statement, follow the
 * VALUE it produces through uses, assignments, call arguments and returns - across files via the
 * resolved call graph - and mark (one level of) control dependence. Bounded by hops and total
 * nodes; truncation is always explicit.
 */
public final class ImpactSlice {
    private static final Set<NodeType> CONTROL = Set.of(NodeType.IF, NodeType.WHILE, NodeType.DO,
            NodeType.FOR, NodeType.FOR_EACH, NodeType.SWITCH, NodeType.SWITCH_FUNCTION,
            NodeType.TERNARY);

    private final CodeGraph graph;
    private final List<String> out = new ArrayList<>();
    private final Set<Symbol> seenSymbols = new HashSet<>();
    private final Set<Node> seenNodes = new HashSet<>();
    private final int maxHops;
    private final int maxNodes;
    private boolean truncated;
    private final Map<String, List<Ref>> referencesByName = new HashMap<>();   // per followed field name

    private ImpactSlice(CodeGraph graph, int maxHops, int maxNodes) {
        this.graph = graph;
        this.maxHops = maxHops;
        this.maxNodes = maxNodes;
    }

    /** Slice forward from the innermost statement starting on {@code line} of the matching file. */
    public static List<String> of(CodeGraph graph, String fileSuffix, int line, int maxHops, int maxNodes) {
        ImpactSlice s = new ImpactSlice(graph, maxHops, maxNodes);
        Node seed = s.findStatement(fileSuffix, line);
        if (seed == null) {
            return List.of("no statement starts at " + fileSuffix + ":" + line);
        }
        s.emit(0, "seed", render(seed), s.fileOf(seed), line);
        s.slice(seed, 1);
        if (s.truncated) {
            s.out.add("… truncated (limits: " + maxHops + " hops, " + maxNodes + " nodes)");
        }
        return s.out;
    }

    private Node findStatement(String fileSuffix, int line) {
        for (Path f : graph.sources().files()) {
            if (!f.toString().endsWith(fileSuffix)) {
                continue;
            }
            var tree = graph.treeOf(f);
            if (tree == null) {
                continue;
            }
            List<Node> hits = new ArrayList<>();
            tree.root().walk(n -> {
                if (n.type().isStatement() && n.firstCodeToken() != null && n.firstCodeToken().line() == line) {
                    hits.add(n);
                }
            });
            if (!hits.isEmpty()) {
                return hits.get(hits.size() - 1);   // DFS order: the last hit is the innermost
            }
        }
        return null;
    }

    /** What the seed statement PRODUCES, then follow each product. */
    private void slice(Node stmt, int depth) {
        switch (stmt.type()) {
            case VAR_DECLARATION -> {
                for (Node d : stmt.children(NodeType.VAR_DECLARATOR)) {
                    Symbol s = symbolOfDeclarator(d);
                    if (s != null) {
                        followSymbol(s, d, depth);
                    }
                }
            }
            case RETURN -> followReturn(stmt, depth);
            default -> {
                // assignment or bare call inside an expression statement
                Node assign = firstDescendant(stmt, NodeType.ASSIGNMENT);
                if (assign != null) {
                    consumeAssignment(assign, depth);
                    return;
                }
                Node call = firstDescendant(stmt, NodeType.METHOD_CALL);
                if (call == null) {
                    call = firstDescendant(stmt, NodeType.NEW_INSTANCE);
                }
                if (call != null) {
                    for (CodeGraph.Target t : targetsAt(call)) {
                        if (t.source() != null) {
                            emit(depth, "call", t.source().spec() + " [" + t.how() + "]",
                                    t.source().file(), t.source().line());
                        }
                    }
                    consume(call, depth);   // the call's RESULT may flow onward too
                }
            }
        }
    }

    /** Every use of the symbol after its definition, each followed into its consumption. */
    private void followSymbol(Symbol s, Node from, int depth) {
        if (depth > maxHops || !seenSymbols.add(s)) {
            return;
        }
        List<Node> uses = new ArrayList<>();
        for (Ref r : candidateReferences(s)) {
            if (denotes(r, s) && !isOverwriteTarget(r.node())) {
                uses.add(r.node());
            }
        }
        for (Node use : uses) {
            if (budgetExhausted()) {
                return;
            }
            emit(depth, "use", "'" + s.name() + "' in " + render(enclosingStatement(use)),
                    fileOf(use), lineOf(use));
            consume(use, depth + 1);
        }
    }

    /** How the VALUE at {@code n} is consumed by its enclosing context. */
    private void consume(Node n, int depth) {
        if (depth > maxHops || budgetExhausted() || !seenNodes.add(n)) {
            return;
        }
        for (Node p = n.parent(), child = n; p != null; child = p, p = p.parent()) {
            switch (p.type()) {
                case VAR_DECLARATOR -> {
                    Symbol s = symbolOfDeclarator(p);
                    if (s != null) {
                        emit(depth, "assign", "'" + p.name() + "'", fileOf(p), lineOf(p));
                        followSymbol(s, p, depth + 1);
                    }
                    return;
                }
                case ASSIGNMENT -> {
                    if (p.children().indexOf(child) == 0) {
                        return;   // the target, not the value
                    }
                    consumeAssignment(p, depth);
                    // the assignment's own value IS the RHS (`a = b = v`, `use(x = v)`): keep walking
                }
                case METHOD_CALL, NEW_INSTANCE -> {
                    int arg = argumentIndex(p, child);
                    if (arg >= 0) {
                        followIntoParameters(p, arg, depth);
                        return;
                    }
                    // we are the RECEIVER - the call's result carries our influence; keep walking
                }
                case RETURN -> {
                    followReturn(p, depth);
                    return;
                }
                default -> {
                    if (CONTROL.contains(p.type()) && isConditionChild(p, child)) {
                        emit(depth, "control", p.type().toString().toLowerCase() + " condition",
                                fileOf(p), lineOf(p));
                        return;
                    }
                    if (p.type().isStatement() || p.type() == NodeType.BLOCK
                            || p.type().isTypeDeclaration()) {
                        return;   // value not consumed further on this path
                    }
                }
            }
        }
    }

    private void consumeAssignment(Node assign, int depth) {
        List<Node> kids = assign.children();
        if (kids.isEmpty()) {
            return;
        }
        Node lhs = kids.get(0);
        if (lhs.type() == NodeType.IDENTIFIER_EXPRESSION && lhs.name() != null
                && scopeOf(lhs) != null && lhs.token() != null) {
            Symbol s = scopeOf(lhs).resolveVariable(lhs.name(), lhs.token().startOffset());
            if (s != null) {
                emit(depth, "assign", "'" + lhs.name() + "'", fileOf(lhs), lineOf(lhs));
                followSymbol(s, lhs, depth + 1);
                return;
            }
        }
        if (lhs.type() == NodeType.FIELD_ACCESS) {   // this.f = v / other.f = v
            Path file = fileOf(lhs);
            Symbol f = file == null ? null : fieldOf(lhs, file);
            if (f != null) {
                emit(depth, "assign", "'" + lhs.name() + "'", file, lineOf(lhs));
                followSymbol(f, lhs, depth + 1);
                return;
            }
        }
        emit(depth, "field-write", render(lhs) + " (uses not enumerated)", fileOf(lhs), lineOf(lhs));
    }

    /** The value is argument {@code arg} of a resolved call: continue inside each callee. */
    private void followIntoParameters(Node call, int arg, int depth) {
        for (CodeGraph.Target t : targetsAt(call)) {
            if (t.source() == null || "declaration".equals(t.how())) {
                continue;
            }
            List<Node> params = t.source().decl().children(NodeType.PARAMETER);
            if (arg >= params.size()) {
                continue;   // varargs tail - parameter-level tracking stops here
            }
            Node param = params.get(arg);
            Scope: {
                var scope = ownScopeOf(t.source().decl());
                if (scope == null || param.name() == null) {
                    break Scope;
                }
                Symbol s = scope.resolveVariable(param.name(), Integer.MAX_VALUE);
                if (s != null) {
                    emit(depth, "param", "'" + param.name() + "' of " + t.source().spec()
                            + ("impl".equals(t.how()) ? " [impl]" : ""), t.source().file(),
                            t.source().line());
                    followSymbol(s, param, depth + 1);
                }
            }
        }
    }

    /** The value reaches a return: it flows to every RESOLVED caller of the enclosing method. */
    private void followReturn(Node from, int depth) {
        Node decl = enclosingDeclaration(from);
        if (decl == null || depth > maxHops) {
            return;
        }
        for (CodeGraph.CallerEdge e : graph.callerEdgesOf(decl)) {
            if (budgetExhausted()) {
                return;
            }
            Node site = e.site().site();
            emit(depth, "return-to-caller", e.site().caller().spec(), e.site().file(), e.site().line());
            consume(site, depth + 1);
        }
    }

    private List<CodeGraph.Target> targetsAt(Node call) {
        for (CodeGraph.CallSite cs : graph.calleesOfDecl(enclosingDeclaration(call))) {
            if (cs.site() == call) {
                // F117: a name-only target is every method of that NAME in the repo (the receiver did not
                // resolve) - following `logger.error(...)` into ContractAction#error etc. is noise, not impact
                List<CodeGraph.Target> resolved = new ArrayList<>();
                for (CodeGraph.Target t : graph.targetsOf(cs)) {
                    if (!"name-only".equals(t.how())) {
                        resolved.add(t);
                    }
                }
                return resolved;
            }
        }
        return List.of();
    }

    private Symbol symbolOfDeclarator(Node d) {
        if (d.name() == null || scopeOf(d) == null || d.token() == null) {
            return null;
        }
        return scopeOf(d).resolveVariable(d.name(), d.token().startOffset());
    }

    /** A reference site and its file (the file types a qualified access's receiver). */
    private record Ref(Node node, Path file) { }

    /**
     * Where uses of the symbol can be. Locals/params: the enclosing method. Fields: EVERY indexed file - a
     * non-private field is read by subclasses, same-package types and {@code other.f} accesses anywhere, and
     * {@link #denotes} keeps only the references that resolve to this very symbol. The declaring file comes
     * first, so its uses survive the node budget as before.
     */
    private List<Ref> candidateReferences(Symbol s) {
        Node decl = s.declaration();
        if (decl == null) {
            return List.of();
        }
        if (decl.parent() != null && decl.parent().type() == NodeType.FIELD_DECLARATION) {
            Path home = fileOf(decl);
            List<Ref> refs = new ArrayList<>(referencesNamed(s.name()));
            refs.sort(Comparator.comparing(r -> !r.file().equals(home)));   // stable: declaring file first
            return refs;
        }
        Node method = enclosingDeclaration(decl);
        Node region = method != null ? method : decl.parent();
        if (region == null) {
            return List.of();
        }
        List<Ref> refs = new ArrayList<>();
        region.walk(n -> {
            if (n.type() == NodeType.IDENTIFIER_EXPRESSION && s.name().equals(n.name())) {
                refs.add(new Ref(n, null));
            }
        });
        return refs;
    }

    /**
     * Every bare-name and qualified-member reference NAMED {@code name} across the index - one walk of all trees
     * per name, cached for this slice (only the names actually followed are held, not every identifier).
     */
    private List<Ref> referencesNamed(String name) {
        return referencesByName.computeIfAbsent(name, k -> {
            List<Ref> refs = new ArrayList<>();
            for (Path f : graph.sources().files().stream().sorted().toList()) {
                var tree = graph.treeOf(f);
                if (tree == null) {
                    continue;
                }
                tree.root().walk(n -> {
                    if ((n.type() == NodeType.IDENTIFIER_EXPRESSION || n.type() == NodeType.FIELD_ACCESS)
                            && k.equals(n.name())) {
                        refs.add(new Ref(n, f));
                    }
                });
            }
            return refs;
        });
    }

    /** The reference resolves to exactly this symbol - a same-named local or another type's field does not. */
    private boolean denotes(Ref r, Symbol s) {
        Node n = r.node();
        if (!s.name().equals(n.name()) || n.token() == null) {
            return false;
        }
        if (n.type() == NodeType.IDENTIFIER_EXPRESSION) {
            return scopeOf(n) != null && n.token().startOffset() != s.declOffset()
                    && scopeOf(n).resolveVariable(n.name(), n.token().startOffset()) == s;
        }
        return n.type() == NodeType.FIELD_ACCESS && r.file() != null && fieldOf(n, r.file()) == s;
    }

    /** The source field a qualified access denotes, typed by its own file's inference; null when unresolvable. */
    private Symbol fieldOf(Node fieldAccess, Path file) {
        TypeInference types = graph.typeInferenceFor(file);
        return types == null ? null : types.fieldOf(fieldAccess);
    }

    /** The target of a plain {@code x = v} overwrites the value rather than using it ({@code x += v} uses it). */
    private static boolean isOverwriteTarget(Node n) {
        Node p = n.parent();
        return p != null && p.type() == NodeType.ASSIGNMENT && p.children().indexOf(n) == 0
                && p.token() != null && p.token().type() == TokenType.ASSIGNMENT;
    }

    private static int argumentIndex(Node call, Node child) {
        Token pr = call.token();
        int after;
        if (call.type() == NodeType.NEW_INSTANCE) {
            after = -1;
            for (Token t : call.tokens()) {
                if (t.type() == TokenType.OPENING_ROUND_BRACKET) {
                    after = t.startOffset();
                    break;
                }
            }
            if (after < 0) {
                return -1;
            }
        } else if (pr != null) {
            after = pr.endOffset();
        } else {
            return -1;
        }
        int idx = 0;
        for (Node c : call.children()) {
            Token first = c.firstToken();
            if (first == null || first.startOffset() <= after || c.type() == NodeType.CLASS_BODY
                    || c.type() == NodeType.TYPE) {
                continue;
            }
            if (c == child) {
                return idx;
            }
            idx++;
        }
        return -1;
    }

    private static boolean isConditionChild(Node control, Node child) {
        return child.type() != NodeType.THEN && child.type() != NodeType.ELSE
                && child.type() != NodeType.BLOCK && child.type() != NodeType.CASE
                && child.type() != NodeType.VAR_DECLARATION;
    }

    private static Node enclosingStatement(Node n) {
        for (Node p = n; p != null; p = p.parent()) {
            if (p.type().isStatement()) {
                return p;
            }
        }
        return n;
    }

    private static Node enclosingDeclaration(Node n) {
        for (Node p = n; p != null; p = p.parent()) {
            if (p.type() == NodeType.METHOD_DECLARATION || p.type() == NodeType.CONSTRUCTOR_DECLARATION
                    || p.type() == NodeType.COMPACT_CONSTRUCTOR) {
                return p;
            }
        }
        return null;
    }

    private static Node firstDescendant(Node n, NodeType type) {
        Node[] found = {null};
        n.walk(c -> {
            if (found[0] == null && c.type() == type) {
                found[0] = c;
            }
        });
        return found[0];
    }

    private boolean budgetExhausted() {
        if (out.size() >= maxNodes) {
            truncated = true;
            return true;
        }
        return false;
    }

    private void emit(int depth, String kind, String what, Path file, int line) {
        if (out.size() < maxNodes) {
            out.add("  ".repeat(depth) + "[" + kind + "] " + what + "  "
                    + (file == null ? "?" : file.getFileName()) + ":" + line);
        } else {
            truncated = true;
        }
    }

    private Path fileOf(Node n) {
        Node root = n;
        while (root.parent() != null) {
            root = root.parent();
        }
        for (Path f : graph.sources().files()) {
            var tree = graph.treeOf(f);
            if (tree != null && tree.root() == root) {
                return f;
            }
        }
        return null;
    }

    /** The node's scope belongs to its file's resolver. */
    private Scope scopeOf(Node n) {
        Path file = fileOf(n);
        if (file == null) {
            return null;
        }
        SymbolResolver r = graph.sources().resolverFor(file);
        return r == null ? null : r.scopeOf(n);
    }

    /** The declaration's own scope belongs to its file's resolver. */
    private Scope ownScopeOf(Node decl) {
        Path file = fileOf(decl);
        if (file == null) {
            return null;
        }
        SymbolResolver r = graph.sources().resolverFor(file);
        return r == null ? null : r.ownScope(decl);
    }

    private static int lineOf(Node n) {
        Token t = n.firstCodeToken();
        return t == null ? 0 : t.line();
    }

    private static String render(Node n) {
        if (n == null) {
            return "?";
        }
        String s = n.toString().strip().replaceAll("\\s+", " ");
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }
}
