package hermit.analysis.edit;

import hermit.analysis.lex.JavaLexer;
import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.sym.SourceIndex;
import hermit.analysis.sym.SymbolResolver;
import hermit.analysis.tree.JavaParser;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseException;
import hermit.analysis.tree.ParseTree;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Transactional mutations over a {@link ParseTree}. Operations take SOURCE STRINGS, parse them through the
 * real parser (a malformed snippet fails immediately with a positioned {@link ParseException}), and splice
 * the resulting nodes into the tree:
 * <ul>
 *   <li>insert/replace/delete statements and expressions (replaced sub-expressions stay INLINE on their
 *       line; inserted statements/members render on fresh lines with the surrounding indentation);</li>
 *   <li>add/delete imports and top-level types; add members to a type body.</li>
 * </ul>
 *
 * <p>Edits apply immediately (the caller can inspect {@link #render()} mid-edit) and are journaled.
 * {@link #commit()} renders the tree, RE-PARSES the rendered source and re-runs symbol resolution + type
 * inference on it; if that introduces any problem not present in the baseline (unknown names, missing
 * members, impossible arities), it throws {@link EditCommitException} carrying the positioned problems - the
 * edits stay in place so the caller can fix forward, or {@link #rollback()} restores the pre-edit tree.
 * On success the editor swaps to the freshly parsed (fully re-analyzed) tree - callers must re-locate
 * their anchor nodes afterwards.
 */
public final class TreeEditor {

    private ParseTree tree;
    private final ClasspathIndex index;
    private final SourceIndex sources;
    private Map<String, Integer> baseline;
    private final List<Snapshot> journal = new ArrayList<>();
    /** Words that open a statement, never a method declaration. */
    private static final Set<String> STATEMENT_WORDS =
            Set.of("return", "throw", "new", "else", "yield", "assert", "case", "do");
    /** The keywords a declaration's modifiers are made of, annotations aside (the parser checks them in a switch). */
    private static final Set<TokenType> MODIFIER_KEYWORDS = EnumSet.of(TokenType.PUBLIC, TokenType.PROTECTED,
            TokenType.PRIVATE, TokenType.STATIC, TokenType.FINAL, TokenType.ABSTRACT, TokenType.NATIVE,
            TokenType.SYNCHRONIZED, TokenType.TRANSIENT, TokenType.VOLATILE, TokenType.STRICTFP, TokenType.DEFAULT);

    private TreeEditor(ParseTree tree, ClasspathIndex index, SourceIndex sources) {
        this.tree = tree;
        this.index = index;
        this.sources = sources;
        this.baseline = normalizedCounts(computeProblems(tree));
    }

    private record Snapshot(Node node, List<Object> parts) {}

    private List<String> computeProblems(ParseTree t) {
        SymbolResolver r = SymbolResolver.resolve(t, index, sources);
        List<String> problems = new ArrayList<>(r.validate());
        problems.addAll(r.typeInference().validate(t.root()));
        return problems;
    }

    private static Map<String, Integer> normalizedCounts(List<String> problems) {
        Map<String, Integer> out = new HashMap<>();
        for (String p : problems) {
            out.merge(normalize(p), 1, Integer::sum);
        }
        return out;
    }

    /** Positions shift as text is inserted - baseline problems are compared position-blind. */
    private static String normalize(String problem) {
        return problem.replaceFirst("^\\[[^\\]]*\\]\\s*", "");
    }

    public static TreeEditor of(ParseTree tree) {
        return new TreeEditor(tree, ClasspathIndex.system(), null);
    }

    public static TreeEditor of(ParseTree tree, ClasspathIndex index) {
        return new TreeEditor(tree, index, null);
    }

    /** An editor whose commit-validation ALSO sees sibling source files (cross-file strictness). */
    public static TreeEditor of(ParseTree tree, ClasspathIndex index, SourceIndex sources) {
        return new TreeEditor(tree, index, sources);
    }

    /** The current tree (after a successful {@link #commit()} this is the freshly re-parsed tree). */
    public ParseTree tree() {
        return tree;
    }

    /** The current rendered source, edits included. */
    public String render() {
        return tree.toString();
    }

    /** The method/constructor body block of a declaration node. */
    public static Node bodyOf(Node declaration) {
        return declaration.child(NodeType.BLOCK);
    }

    /**
     * Parse {@code source} as one or more statements and insert them BEFORE {@code anchor}
     * (same indentation, source order kept). Returns the LAST inserted statement.
     */
    public Node insertStatementBefore(Node anchor, String source) {
        Node last = null;
        for (Node stmt : parsedFor(anchor, source)) {
            last = insertOneAt(anchor, stmt, 0);   // each lands right before the anchor → order kept
        }
        return last;
    }

    /**
     * Parse {@code source} as one or more statements and insert them AFTER {@code anchor}
     * (same indentation, source order kept). Returns the LAST inserted statement.
     */
    public Node insertStatementAfter(Node anchor, String source) {
        Node prev = anchor;
        for (Node stmt : parsedFor(anchor, source)) {
            prev = insertOneAt(prev, stmt, 1);
        }
        return prev;
    }

    /**
     * The statements {@code source} holds, indented for the place of {@code anchor}; switch cases when the anchor is a
     * case (rewrite tidy, 2026-10-25: the case of a new op could not be added to a switch).
     */
    private static List<Node> parsedFor(Node anchor, String source) {
        String code = reindentBody(source.strip(), leadingIndentOf(anchor));
        if (anchor.type() != NodeType.CASE) {
            return JavaParser.parseStatementsSnippet(code);
        }
        Node wrapper = JavaParser.parseStatementSnippet("switch (x) {\n" + code + "\n}");
        List<Node> cases = wrapper.children(NodeType.CASE);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("the code is not a switch case: " + source.strip());
        }
        return cases;
    }

    /** Parse {@code source} as one or more statements and append them at the END of {@code block}. */
    public Node insertStatementAtEnd(Node block, String source) {
        if (block.type() != NodeType.BLOCK && block.type() != NodeType.CASE) {
            throw new IllegalArgumentException("expected a BLOCK (or CASE) node, got " + block.type());
        }
        List<Node> stmts = block.children();
        Node lastStmt = null;
        for (Node c : stmts) {
            if (c.type().isStatement() || c.type().isTypeDeclaration()) {
                lastStmt = c;
            }
        }
        // rewrite drive, parser slice 030j: statements written at column 0 kept their inner lines there (FM-035 for
        // inserted statements); the whole snippet is shifted to the block's indentation
        List<Node> parsed = JavaParser.parseStatementsSnippet(reindentBody(source.strip(), lineIndentOf(block) + "    "));
        Node prev = lastStmt;
        for (Node stmt : parsed) {
            if (prev == null) {
                snapshot(block);
                String outerIndent = lineIndentOf(block);
                String sameLine = normalizeLeadingTrivia(stmt, "\n" + outerIndent + "    ");
                spliceBeforeClosingBrace(block, stmt, sameLine != null ? sameLine : "\n" + outerIndent + "    ",
                        "\n" + outerIndent);
                prev = stmt;
            } else {
                prev = insertOneAt(prev, stmt, 1);
            }
        }
        return prev;
    }

    /**
     * Replace a whole statement with one or more parsed from {@code source} (keeps the line's
     * indentation; extra statements are inserted after the first).
     */
    public Node replaceStatement(Node oldStatement, String source) {
        List<Node> parsed = parsedFor(oldStatement, source);
        normalizeLeadingTrivia(parsed.get(0), "\n" + leadingIndentOf(oldStatement));
        Node prev = replaceNode(oldStatement, parsed.get(0));
        for (Node stmt : parsed.subList(1, parsed.size())) {
            prev = insertOneAt(prev, stmt, 1);
        }
        return prev;
    }

    /**
     * Replace EVERY statement of a method's body with the ones parsed from {@code source} (F195). A whole
     * method handed in by mistake - annotations, signature, braces - is reduced to its body first: the author
     * that needs this op is exactly the one that will paste the full method.
     */
    public Node replaceBody(Node methodDecl, String source) {
        Node block = bodyOf(methodDecl);
        if (block == null) {
            throw new IllegalArgumentException("method has no body");
        }
        String body = bodyStatementsOf(source);
        if (body.isBlank()) {
            throw new IllegalArgumentException("replaceBody needs the new body's statements in `code`");
        }
        // Rewrite drive, parser slice 030c: a body written at column 0 kept its inner lines at column 4 while each
        // statement's first line was moved to the body's indentation; the whole body is shifted together
        String indent = lineIndentOf(block) + "    ";
        body = reindentBody(body, indent);
        List<Node> parsed = JavaParser.parseStatementsSnippet(body);   // parse BEFORE changing anything
        if (parsed.size() == 1 && parsed.get(0).type() == NodeType.BLOCK) {
            // Rewrite drive, parser slice 030e: a body handed in as "{ ... }" became a block nested inside the
            // method's own block; the text inside those braces is the body
            String braced = body.strip();
            body = reindentBody(braced.substring(1, braced.length() - 1).strip(), indent);
            JavaParser.parseStatementsSnippet(body);
        }
        // The statements were inserted one by one, and the body lost its blank lines, a comment above its first
        // statement and the continuation indent of a statement with no comment above it. The method's own text,
        // with the new body between its braces, replaces the method: the body stays as written.
        String method = methodDecl.toString();
        String braces = block.toString().stripLeading();
        int open = method.lastIndexOf(braces);
        if (open < 0 || !method.substring(open).equals(braces)) {
            throw new IllegalStateException("the body of " + methodDecl.name() + " is not at the end of its declaration");
        }
        Node member = replaceMember(methodDecl, method.substring(0, open).stripLeading() + "{\n" + indent + body + "\n"
                + lineIndentOf(block) + "}");
        Node last = member;
        for (Node c : bodyOf(member).children()) {
            if (c.type().isStatement() || c.type().isTypeDeclaration()) {
                last = c;
            }
        }
        return last;
    }

    /** {@code @Test void m() { a; b; }} → {@code a; b;}; a bare statement list is returned as is. */
    public static String bodyStatementsOf(String source) {
        String s = source == null ? "" : source.strip();
        if (s.isEmpty()) {
            return s;
        }
        // F196: drop LEADING comment lines first - a pasted javadoc turned into // comments carries {@code …}
        // braces, and the first '{' of the text must be the signature's, not a comment's
        String[] lines = s.split("\n", -1);
        int start = 0;
        boolean inBlock = false;
        while (start < lines.length) {
            String t = lines[start].strip();
            if (inBlock) {
                if (t.contains("*/")) {
                    inBlock = false;
                }
                start++;
            } else if (t.isEmpty() || t.startsWith("//")) {
                start++;
            } else if (t.startsWith("/*")) {
                inBlock = !t.contains("*/");
                start++;
            } else {
                break;
            }
        }
        String rest = start >= lines.length ? "" : String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
        if (rest.isBlank()) {
            return s;   // comments only - let the parser say so
        }
        // a method declaration: something before the first '{' that reads as a signature, ending in ')' or throws X
        int open = rest.indexOf('{');
        int close = rest.lastIndexOf('}');
        if (open > 0 && close > open) {
            // the comments before the first statement were dropped from every body with a brace in it, not only from
            // a whole method handed in; they go only with the signature
            String head = rest.substring(0, open).strip();
            if (head.matches("(?s)(@\\w+(\\([^)]*\\))?\\s*)*(public|protected|private|static|final|synchronized|"
                    + "abstract|default|native|\\s)*[\\w<>\\[\\],.? ]+\\s+\\w+\\s*\\([^)]*\\)(\\s*throws\\s+[\\w.,\\s]+)?")
                    && !opensAStatement(head)) {
                return rest.substring(open + 1, close).strip();
            }
        }
        // Rewrite drive, task 040e: a body written without its opening brace but with its closing one; the one
        // closing brace too many is the method's own, and is dropped
        if (s.endsWith("}") && braceBalance(s) == -1) {
            return s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    /**
     * Replace a sub-expression IN PLACE with one parsed from {@code source} - an inline edit: the
     * surrounding tokens and the line's formatting are preserved.
     */
    public Node replaceExpression(Node oldExpression, String source) {
        return replaceNode(oldExpression, JavaParser.parseExpressionSnippet(
                continuedAt(source.strip(), lineIndentOf(oldExpression))));
    }

    /**
     * Rewrite tidy (2026-10-25): a call written over two lines replaced a case's expression, and its second line kept
     * the column the code gave it. The lines of {@code code} after the first, laid out under a line indented
     * {@code indent}: an expression that ends in a closing brace (a lambda's block, an anonymous class) closes at that
     * indent with its other lines as the code places them against the brace; any other continues a double indent (eight
     * columns) in. The lines keep their indentation relative to each other.
     */
    static String continuedAt(String code, String indent) {
        String[] lines = code.split("\n", -1);
        if (lines.length == 1) {
            return code;
        }
        String last = lines[lines.length - 1];
        int base;
        String to;
        if (last.strip().startsWith("}")) {
            base = last.length() - last.stripLeading().length();
            to = indent;
        } else {
            base = Integer.MAX_VALUE;
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isBlank()) {
                    base = Math.min(base, lines[i].length() - lines[i].stripLeading().length());
                }
            }
            to = indent + "        ";
        }
        StringBuilder sb = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            sb.append('\n');
            if (!lines[i].isBlank()) {
                int lead = lines[i].length() - lines[i].stripLeading().length();
                sb.append(to).append(" ".repeat(Math.max(0, lead - base))).append(lines[i].stripLeading());
            }
        }
        return sb.toString();
    }

    /** Delete a statement / member / import / type declaration node (its leading trivia goes with it). */
    public void delete(Node node) {
        Node parent = requireParent(node);
        int idx = parent.partIndexOf(node);
        if (idx < 0) {
            throw new IllegalArgumentException("node is not a direct part of its parent");
        }
        snapshot(parent);
        dropEndOfLineComment(parent, idx);
        parent.removePartAt(idx);
    }

    /** Parse {@code source} as a class-body member and add it at the end of {@code typeDecl}'s body. */
    public Node insertMember(Node typeDecl, String source) {
        // Rewrite drive, task 035a: a port wrote three fields in one item and was refused for the second member, and
        // two methods that call each other (FM-045) could not be added one at a time. Text that is several complete
        // members is inserted member by member; the whole program is validated once, after all of them.
        List<String> several = separateMembers(source);
        if (several.size() > 1) {
            Node first = null;
            for (String one : several) {
                Node inserted = insertMember(typeDecl, one);
                first = first == null ? inserted : first;
            }
            return first;
        }
        source = withoutTrailingText(source);
        Node body = typeDecl.type() == NodeType.CLASS_BODY ? typeDecl : typeDecl.child(NodeType.CLASS_BODY);
        if (body == null) {
            throw new IllegalArgumentException("no CLASS_BODY under " + typeDecl.type());
        }
        List<Node> members = body.children();
        // F170 (drive 2V): a plan whose `method` equalled its `templateMethod` inserted an EMPTY DUPLICATE of the
        // existing test - two methods with one name and arity do not compile, and the edit destroyed a real test's
        // file. A duplicate member is refused here, where every insertMember path passes.
        Node parsedFirst = JavaParser.parseMemberSnippet(source.strip());
        String duplicate = duplicateMemberName(members, parsedFirst);
        // Rewrite drive, task 035c: a re-plan inserted again two members the previous run had already added; the
        // duplicate refused its whole item. A member identical to one already there is already done.
        Node same = identicalMember(members, parsedFirst);
        if (same != null) {
            return same;
        }
        if (duplicate != null) {
            throw new IllegalArgumentException("the type already declares " + duplicate
                    + " — a duplicate method does not compile. To EDIT the existing member use a statement op "
                    + "(insertAtEnd/replaceStatement on that method); to add a NEW one, give it a different name.");
        }
        // F96: a multi-line member arrives with its own (usually column-0) layout; every line after the first
        // must carry the body indent too, or the file fails formatting checks and reads as garbage
        String indent = !members.isEmpty() ? leadingIndentOf(members.get(members.size() - 1)) : lineIndentOf(body) + "    ";
        Node member = JavaParser.parseMemberSnippet(reindentContinuation(source, indent));
        if (!members.isEmpty()) {
            Node last = members.get(members.size() - 1);
            Node after = placeAfter(members, member);
            snapshot(body);
            if (after != null && after != last) {
                // a member after `after` keeps its own spacing; an end-of-line comment of `after`, which the parser
                // gives to that member, stays on `after`'s line
                List<Token> comment = takeEndOfLineComment(body, body.partIndexOf(after));
                if (!comment.isEmpty()) {
                    snapshot(after);
                    comment.forEach(after::add);
                }
                int at = body.partIndexOf(after) + 1;
                boolean fieldAfterField = after.type() == NodeType.FIELD_DECLARATION
                        && member.type() == NodeType.FIELD_DECLARATION;
                member.prependToken(ws((fieldAfterField ? "\n" : "\n\n") + leadingIndentOf(after)));
                body.insertPart(at, member);
                return member;
            }
            // Rewrite drive, task 049a2: a field after a last member that is a field stays next to it
            boolean fieldAfterField = last.type() == NodeType.FIELD_DECLARATION
                    && member.type() == NodeType.FIELD_DECLARATION;
            member.prependToken(ws((fieldAfterField ? "\n" : "\n\n") + leadingIndentOf(last)));
            List<Token> comment = takeEndOfLineComment(body, body.partIndexOf(last));
            if (!comment.isEmpty()) {
                snapshot(last);
                comment.forEach(last::add);   // the last member's end-of-line comment stays on its line
            }
            body.insertPart(afterOwnLineComments(body, body.partIndexOf(last) + 1), member);
            return member;
        }
        snapshot(body);
        String outerIndent = lineIndentOf(body);
        // Rewrite drive, task 049a2: the first member of a top-level class comes after a blank line, as in every class
        // of the port; a nested or anonymous class keeps it on the next line
        boolean topLevel = typeDecl.parent() != null && typeDecl.parent().type() == NodeType.COMPILATION_UNIT;
        spliceBeforeClosingBrace(body, member, (topLevel ? "\n\n" : "\n") + outerIndent + "    ", "\n" + outerIndent);
        return member;
    }

    /**
     * Rewrite drive, task 040d: a port had to make a private method package-private, and no op changed a member's
     * declaration: replaceBody keeps it and changes only the body. The member is replaced whole, in place, its comments
     * included; the whitespace before it (its blank line and indentation) stays.
     */
    public Node replaceMember(Node oldMember, String source) {
        Node body = requireParent(oldMember);
        String before = leadingWhitespaceOf(oldMember);
        Node member = JavaParser.parseMemberSnippet(reindentContinuation(withoutTrailingText(source), leadingIndentOf(oldMember)));
        // Rewrite drive, task 044g2: an item for a test method left out its @Test, and the replaced test would have
        // stopped running. The member is replaced with its annotations, so code that drops one of them is refused.
        List<String> dropped = annotationNames(oldMember);
        dropped.removeAll(annotationNames(member));
        if (!dropped.isEmpty()) {
            throw new IllegalArgumentException("the code drops the annotation(s) " + dropped + " of " + oldMember.name()
                    + "; replaceMember replaces the annotations too, so write them in the code");
        }
        int idx = body.partIndexOf(oldMember);
        if (idx < 0) {
            throw new IllegalArgumentException("the member is not a direct part of its type body");
        }
        snapshot(body);
        // once the file was written and read again, the end-of-line comment of the line before opened the replaced
        // member's trivia and went with it, and the new member joined that line; it stays there
        List<Token> lineEnd = idx > 0 ? takeEndOfLineComment(body, idx - 1) : List.of();
        if (!lineEnd.isEmpty()) {
            before = leadingWhitespaceOf(oldMember);
        }
        dropEndOfLineComment(body, idx);
        body.removePartAt(idx);
        member.prependToken(ws(before));
        body.insertPart(idx, member);
        for (int i = lineEnd.size() - 1; i >= 0; i--) {
            body.insertPart(idx, lineEnd.get(i));
        }
        return member;
    }

    /**
     * Rewrite drive, task 053 (planning): a fix left a method unused, and no op deleted a member. The member goes with its
     * comments; the end-of-line comment of the part before it ({@code int count;   // how many}), which the parser gives
     * to the member, stays on that line.
     */
    public void deleteMember(Node member) {
        Node body = requireParent(member);
        int idx = body.partIndexOf(member);
        if (idx < 1) {
            throw new IllegalArgumentException("the member is not a direct part of its type body");
        }
        List<Token> previousLine = takeEndOfLineComment(body, idx - 1);
        delete(member);
        for (int i = previousLine.size() - 1; i >= 0; i--) {
            body.insertPart(idx, previousLine.get(i));
        }
    }

    /**
     * Rewrite drive, tidy pass: members inserted one after another, and stubs replaced in place, left methods with no
     * blank line between them. Every member of {@code typeDecl} after the first starts after one blank line, except a
     * field that follows a field, which keeps its own spacing.
     */
    public void spaceMembers(Node typeDecl) {
        Node body = typeDecl.type() == NodeType.CLASS_BODY ? typeDecl : typeDecl.child(NodeType.CLASS_BODY);
        if (body == null) {
            throw new IllegalArgumentException("not a type with a body: " + typeDecl.type());
        }
        Node previous = null;
        for (Node member : body.children()) {
            if (previous != null && !(previous.type() == NodeType.FIELD_DECLARATION
                    && member.type() == NodeType.FIELD_DECLARATION)) {
                String lead = leadingWhitespaceOf(member);
                if (lead.chars().filter(ch -> ch == '\n').count() == 1) {
                    snapshot(member);
                    member.prependToken(ws("\n"));
                }
            }
            previous = member;
        }
    }

    /**
     * Rewrite tidy (2026-10-25): the rewrite's edit tool had every member at column 0 since its scaffold, and no op
     * could indent them. Every line of {@code typeDecl}'s body moves by the same number of columns, so that its first
     * member starts four columns in from the declaration's line. The lines keep their indentation relative to each
     * other, so a text block's value is unchanged; blank lines and the closing brace's line stay as they are.
     */
    public void indentMembers(Node typeDecl) {
        Node body = typeDecl.type() == NodeType.CLASS_BODY ? typeDecl : typeDecl.child(NodeType.CLASS_BODY);
        if (body == null) {
            throw new IllegalArgumentException("not a type with a body: " + typeDecl.type());
        }
        Node first = body.children().stream().filter(TreeEditor::startsLine).findFirst().orElse(null);
        if (first == null) {
            return;
        }
        int shift = lineIndentOf(body.parent()).length() + 4 - leadingIndentOf(first).length();
        if (shift == 0) {
            return;
        }
        List<Node> holders = new ArrayList<>();
        List<Integer> places = new ArrayList<>();
        collectTokenPlaces(body, holders, places);
        Map<Integer, Token> moved = new LinkedHashMap<>();
        for (int k = 0; k < holders.size(); k++) {
            Token t = (Token) holders.get(k).parts().get(places.get(k));
            if (!t.text().contains("\n")) {
                continue;
            }
            // a whitespace run's last line goes on with the next token: the closing brace's line stays put
            boolean beforeClose = k == holders.size() - 2;
            String[] lines = t.text().split("\n", -1);
            StringBuilder text = new StringBuilder(lines[0]);
            for (int i = 1; i < lines.length; i++) {
                boolean last = i == lines.length - 1;
                boolean stays = last ? t.type() == TokenType.WHITESPACE && beforeClose : lines[i].isBlank();
                text.append('\n').append(stays ? lines[i] : shifted(lines[i], shift));
            }
            moved.put(k, new Token(t.type(), text.toString(), t.line(), t.column(), -1));
        }
        Set<Node> saved = Collections.newSetFromMap(new IdentityHashMap<>());
        moved.forEach((k, token) -> {
            if (saved.add(holders.get(k))) {
                snapshot(holders.get(k));
            }
            holders.get(k).replacePartAt(places.get(k), token);
        });
    }

    /** A line moved {@code shift} columns; refused when it has fewer leading spaces than a negative shift takes. */
    private static String shifted(String line, int shift) {
        if (shift > 0) {
            return " ".repeat(shift) + line;
        }
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        if (spaces < -shift) {
            throw new IllegalArgumentException("the line '" + line.strip() + "' is indented " + spaces
                    + " columns, less than the " + -shift + " the type's members move left");
        }
        return line.substring(-shift);
    }

    /** Each token under {@code node}, in order, as its holder and its index there. */
    private static void collectTokenPlaces(Node node, List<Node> holders, List<Integer> places) {
        List<Object> parts = node.parts();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) instanceof Node child) {
                collectTokenPlaces(child, holders, places);
            } else {
                holders.add(node);
                places.add(i);
            }
        }
    }

    /**
     * Rewrite tidy (2026-10-25): a stray comment before the edit tool's closing brace could not be taken away, since a
     * member appended at the end went before it. The index after the comments that stand on their own lines from
     * {@code from} on, up to the next code; an end-of-line comment of the part before stays with it.
     */
    private static int afterOwnLineComments(Node body, int from) {
        List<Object> parts = body.parts();
        int end = from;
        boolean ownLine = false;
        for (int i = from; i < parts.size() && parts.get(i) instanceof Token t && t.isTrivia(); i++) {
            if (t.type() == TokenType.WHITESPACE) {
                ownLine |= t.text().contains("\n");
            } else if (ownLine) {
                end = i + 1;
            }
        }
        return end;
    }

    /**
     * Add an import. {@code name} is the imported name, optionally prefixed with {@code static}:
     * {@code java.util.List}, {@code java.util.*}, {@code static java.util.Objects.requireNonNull}.
     */
    public Node addImport(String name) {
        Node imp = JavaParser.parseImportSnippet("import " + name.trim() + ";");
        Node root = tree.root();
        List<Node> imports = tree.imports();
        // Rewrite drive, tidy pass: each import went after the last one, so the ported files carried their imports in
        // the order the edit items added them (java.util among hermit.*), and one added twice was written twice. An
        // import already there is not added again; a new one goes before the first import that sorts after it.
        String key = importKey(imp);
        Node next = null;
        for (Node existing : imports) {
            String k = importKey(existing);
            if (k.equals(key)) {
                return existing;
            }
            if (next == null && k.compareTo(key) > 0) {
                next = existing;
            }
        }
        snapshot(root);
        if (next != null) {
            snapshot(next);
            moveLeadingTrivia(next, imp);
            // Rewrite drive, task 045a: the static imports are a group of their own, with a blank line after it.
            // Between two imports, whitespace alone is set by the groups; the first import keeps the file's spacing.
            int at = imports.indexOf(next);
            if (at > 0 && imp.parts().get(0) instanceof Token t && t.type() == TokenType.WHITESPACE
                    && imp.parts().size() > 1 && !(imp.parts().get(1) instanceof Token u && u.isTrivia())) {
                imp.removePartAt(0);
                imp.prependToken(ws(importGap(imports.get(at - 1), imp)));
            }
            next.prependToken(ws(importGap(imp, next)));
            root.insertPart(root.partIndexOf(next), imp);
        } else if (!imports.isEmpty()) {
            Node last = imports.get(imports.size() - 1);
            imp.prependToken(ws(importGap(last, imp)));
            root.insertPart(root.partIndexOf(last) + 1, imp);
        } else {
            Node pkg = root.child(NodeType.PACKAGE_DECLARATION);
            if (pkg != null) {
                imp.prependToken(ws("\n\n"));
                root.insertPart(root.partIndexOf(pkg) + 1, imp);
            } else {
                // No package, no imports: the import becomes the first construct. The separation
                // belongs to the DISPLACED first construct (so later imports can chain cleanly).
                Object firstPart = root.parts().isEmpty() ? null : root.parts().get(0);
                if (firstPart instanceof Node firstNode) {
                    snapshot(firstNode);
                    firstNode.prependToken(ws("\n\n"));
                }
                root.insertPart(0, imp);
            }
        }
        return imp;
    }

    /**
     * Rewrite drive, tidy pass: the ported files carried their imports in the order the edit items added them, some
     * twice. The imports become one block sorted as the reference files are (static imports first, then by name, with
     * a blank line between the two groups), each once; the block starts where the first import was, with its leading
     * whitespace. Imports that carry a comment, and module imports, are refused: sorting would move or lose them.
     */
    public void sortImports() {
        List<Node> imports = tree.imports();
        if (imports.isEmpty()) {
            return;
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Node imp : imports) {
            if (ParseTree.isModuleImport(imp)) {
                throw new IllegalArgumentException("a module import is not sorted: " + imp.toString().strip());
            }
            List<Token> leading = new ArrayList<>();
            collectLeadingTokens(imp, leading);
            if (leading.stream().takeWhile(Token::isTrivia).anyMatch(t -> t.type() != TokenType.WHITESPACE)) {
                throw new IllegalArgumentException("an import carries a comment, which sorting would move: "
                        + imp.toString().strip());
            }
            sorted.putIfAbsent(importKey(imp), "import " + (imp.hasModifier(TokenType.STATIC) ? "static " : "")
                    + ParseTree.importedName(imp) + ";");
        }
        Node root = tree.root();
        String lead = leadingWhitespaceOf(imports.get(0));
        int at = root.partIndexOf(imports.get(0));
        snapshot(root);
        for (Node imp : imports) {
            root.removePartAt(root.partIndexOf(imp));
        }
        int i = 0;
        Node previous = null;
        for (String text : sorted.values()) {
            Node imp = JavaParser.parseImportSnippet(text);
            imp.prependToken(ws(previous == null ? lead : importGap(previous, imp)));
            root.insertPart(at + i, imp);
            previous = imp;
            i++;
        }
    }

    /** Delete an import declaration node. */
    public void deleteImport(Node importDecl) {
        delete(importDecl);
    }

    /** Parse {@code source} as a type declaration and add it at the end of the compilation unit. */
    public Node addType(String source) {
        Node decl = JavaParser.parseMemberSnippet(source);
        if (!decl.type().isTypeDeclaration()) {
            throw new IllegalArgumentException("not a type declaration: " + decl.type());
        }
        Node root = tree.root();
        snapshot(root);
        int idx = root.partsSnapshot().size();
        // insert before the trailing trivia + EOF
        List<Object> parts = root.parts();
        while (idx > 0 && (!(parts.get(idx - 1) instanceof Node))) {
            idx--;
        }
        decl.prependToken(ws("\n\n"));
        root.insertPart(idx, decl);
        return decl;
    }

    /** Delete a top-level type declaration. */
    public void deleteType(Node typeDecl) {
        delete(typeDecl);
    }

    /**
     * Validate the CURRENT edited state and return the problems it would be rejected for - the
     * newly-introduced ones only (problems already present before editing are tolerated). Empty list
     * means {@link #commit()} will succeed.
     */
    public List<String> problems() {
        String rendered = render();
        ParseTree fresh;
        try {
            fresh = ParseTree.parse(rendered);
        } catch (RuntimeException e) {
            return List.of(e.getMessage());
        }
        return subtractBaseline(computeProblems(fresh));
    }

    /**
     * Render → re-parse → re-resolve → re-infer. If the edited program is correct (no NEW problems), the
     * editor swaps to the fresh fully-analyzed tree, the journal clears, and the tree is returned.
     * Otherwise throws {@link EditCommitException} with the positioned problems; edits stay applied.
     */
    public ParseTree commit() {
        String rendered = render();
        ParseTree fresh;
        try {
            fresh = ParseTree.parse(rendered);
        } catch (RuntimeException e) {
            throw new EditCommitException(List.of(e.getMessage()), rendered);
        }
        List<String> all = computeProblems(fresh);
        List<String> introduced = subtractBaseline(all);
        if (!introduced.isEmpty()) {
            throw new EditCommitException(introduced, rendered);
        }
        this.tree = fresh;
        this.baseline = normalizedCounts(all);
        journal.clear();
        return fresh;
    }

    /** Undo every uncommitted edit, restoring the tree to its last committed (or initial) state. */
    public void rollback() {
        for (int i = journal.size() - 1; i >= 0; i--) {
            Snapshot s = journal.get(i);
            s.node().restoreParts(s.parts());
        }
        journal.clear();
    }

    /** Shift a body's lines after the first to {@code indent}, keeping their indentation relative to each other (the
     *  first line was stripped, so it does not count); the first line is placed by the statement insertion. */
    static String reindentBody(String body, String indent) {
        String[] lines = body.split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                common = Math.min(common, lines[i].length() - lines[i].stripLeading().length());
            }
        }
        if (common == Integer.MAX_VALUE) {
            return body;
        }
        // a single statement over several lines, as a plan writes it (its first line not indented), had its
        // continuation lines moved to the statement's own indent; they go a double indent in
        if (endOfFirstStatement(lines) == lines.length - 1 && !endsStatement(lines[0])) {
            indent = indent + "        ";
        }
        StringBuilder sb = new StringBuilder(lines[0].strip());
        for (int i = 1; i < lines.length; i++) {
            sb.append('\n');
            if (!lines[i].isBlank()) {
                sb.append(indent).append(lines[i].substring(common));
            }
        }
        return sb.toString();
    }

    /** The line that ends the snippet's first statement: the first one ending in ';', '{' or '}', or the last line. */
    private static int endOfFirstStatement(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (endsStatement(lines[i])) {
                return i;
            }
        }
        return lines.length - 1;
    }

    /** A line that ends a statement or opens a block, or a comment or annotation line, which a statement follows. */
    private static boolean endsStatement(String line) {
        String t = line.strip();
        return t.endsWith(";") || t.endsWith("{") || t.endsWith("}") || t.startsWith("//") || t.startsWith("/*")
                || t.startsWith("*") || t.startsWith("@");
    }

    private List<String> subtractBaseline(List<String> problems) {
        Map<String, Integer> budget = new HashMap<>(baseline);
        List<String> out = new ArrayList<>();
        for (String p : problems) {
            String n = normalize(p);
            Integer left = budget.get(n);
            if (left != null && left > 0) {
                budget.put(n, left - 1);
            } else {
                out.add(p);
            }
        }
        return out;
    }

    private void snapshot(Node node) {
        journal.add(new Snapshot(node, node.partsSnapshot()));
    }

    private static Node requireParent(Node node) {
        Node p = node.parent();
        if (p == null) {
            throw new IllegalArgumentException("node has no parent (is it the root?)");
        }
        return p;
    }

    private static Token ws(String text) {
        return new Token(TokenType.WHITESPACE, text, 1, 1, -1);
    }

    /** Move the leading trivia run of {@code from} onto the front of {@code to} (inline replacements). */
    /**
     * Move {@code from}'s leading trivia run onto {@code to} - recursing into leading child nodes,
     * where the parser may have attached it. Every mutated level is journaled for rollback.
     */
    private void moveLeadingTrivia(Node from, Node to) {
        List<Token> moved = new ArrayList<>();
        Node n = from;
        while (true) {
            if (!n.parts().isEmpty() && n.parts().get(0) instanceof Token t0 && t0.isTrivia()) {
                snapshot(n);
                while (!n.parts().isEmpty() && n.parts().get(0) instanceof Token t && t.isTrivia()) {
                    moved.add(t);
                    n.removePartAt(0);
                }
            }
            if (!n.parts().isEmpty() && n.parts().get(0) instanceof Node child) {
                n = child;
                continue;
            }
            break;
        }
        for (int i = moved.size() - 1; i >= 0; i--) {
            to.prependToken(moved.get(i));
        }
    }

    /**
     * Insert {@code node} just before the container's closing brace, giving it {@code leading} trivia; when
     * the brace has no own newline, {@code closingIndent} is appended so the brace lands on a fresh line.
     */
    private static void spliceBeforeClosingBrace(Node container, Node node, String leading, String closingIndent) {
        List<Object> parts = container.parts();
        int closeIdx = -1;
        for (int i = parts.size() - 1; i >= 0; i--) {
            if (parts.get(i) instanceof Token t && t.type() == TokenType.CLOSING_CURLY_BRACKET) {
                closeIdx = i;
                break;
            }
        }
        if (closeIdx < 0) {
            throw new IllegalArgumentException("container has no closing brace");
        }
        node.prependToken(ws(leading));
        boolean braceHasNewline = closeIdx > 0
                && parts.get(closeIdx - 1) instanceof Token t
                && t.isTrivia() && t.text().contains("\n");
        if (braceHasNewline) {
            container.insertPart(closeIdx - 1, node);
        } else {
            // Rewrite drive, scaffold task 034a: a one-line stub body "{ throw ...; }" replaced by several statements
            // became "{ " + statements with a blank line between the first two + "return x;}". The spaces left before
            // the brace are dropped, and the brace's newline goes into the block AFTER the node, not into the node,
            // so statements appended after it come before the brace's line.
            while (closeIdx > 0 && parts.get(closeIdx - 1) instanceof Token t && t.isTrivia() && t.text().isBlank()) {
                container.removePartAt(closeIdx - 1);
                closeIdx--;
            }
            container.insertPart(closeIdx, node);
            container.insertPart(closeIdx + 1, ws(closingIndent));
        }
    }

    /** Depth-first tokens from the start of {@code node} up to (and including) the first non-trivia one. */
    private static boolean collectLeadingTokens(Node node, List<Token> out) {
        for (Object p : node.parts()) {
            if (p instanceof Token t) {
                out.add(t);
                if (!t.isTrivia()) {
                    return true;
                }
            } else if (collectLeadingTokens((Node) p, out)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The indentation of the line {@code node} starts on: taken from the node's LEADING trivia (which may
     * live inside its first child), else derived from its first significant token's column.
     */
    /**
     * The indentation of the LINE the node starts on (tail of the nearest preceding newline -
     * non-trivia tokens in between are skipped). The right base for a block's OUTER indent:
     * {@link #leadingIndentOf} would fall back to the column of a preceding token, which for a
     * same-line {@code {} } aligns to the brace instead of the declaration.
     */
    private static String lineIndentOf(Node node) {
        for (Node n = node; n != null; n = n.parent()) {
            List<Token> leading = new ArrayList<>();
            collectLeadingTokens(n, leading);
            for (Token t : leading) {
                if (!t.isTrivia()) {
                    break;   // this level starts mid-line - the newline is further up
                }
                int nl = t.text().lastIndexOf('\n');
                if (nl >= 0) {
                    return t.text().substring(nl + 1);
                }
            }
        }
        return "";
    }

    private static String leadingIndentOf(Node node) {
        List<Token> leading = new ArrayList<>();
        collectLeadingTokens(node, leading);
        for (Token t : leading) {
            if (t.isTrivia()) {
                String text = t.text();
                int nl = text.lastIndexOf('\n');
                if (nl >= 0) {
                    return text.substring(nl + 1);
                }
                continue;   // whitespace without a newline - keep scanning
            }
            return " ".repeat(Math.max(0, t.column() - 1));
        }
        return "";
    }

    /**
     * Re-home a freshly parsed statement's leading trivia: the snippet's own whitespace would fight
     * the indentation we add, so it is dropped; comments are kept, each re-indented on its own line.
     * Rewrite drive, task 045e3: a comment that stood on the previous statement's line (the parser gives it to this
     * statement) stays there: the spacing before it is returned, for the caller to use instead of a line break;
     * null when there is no such comment.
     */
    private static String normalizeLeadingTrivia(Node stmt, String prefix) {
        List<Token> lead = stmt.detachLeadingTrivia();
        String sameLine = lead.size() > 1 && lead.get(0).type() == TokenType.WHITESPACE
                && !lead.get(0).text().contains("\n") && lead.get(1).type() == TokenType.SINGLE_LINE_COMMENT
                ? lead.get(0).text() : null;
        // a blank line before a comment or before the statement itself stays (statements
        // inserted together lost every blank line between them)
        List<Token> comments = new ArrayList<>();
        List<Boolean> blankBefore = new ArrayList<>();
        int newlines = 0;
        for (Token t : lead) {
            if (t.type() == TokenType.WHITESPACE) {
                newlines += (int) t.text().chars().filter(c -> c == '\n').count();
                continue;
            }
            comments.add(t);
            blankBefore.add(newlines > 1);
            newlines = 0;
        }
        boolean blankBeforeStatement = newlines > 1;
        for (int k = comments.size() - 1; k >= 0; k--) {
            boolean blankAfter = k == comments.size() - 1 ? blankBeforeStatement : blankBefore.get(k + 1);
            stmt.prependToken(ws(blankAfter ? "\n" + prefix : prefix));
            stmt.prependToken(comments.get(k));
        }
        boolean blankFirst = comments.isEmpty() ? blankBeforeStatement : blankBefore.get(0);
        return sameLine != null ? sameLine : blankFirst ? "\n" + prefix : null;
    }

    private Node replaceNode(Node oldNode, Node newNode) {
        Node parent = requireParent(oldNode);
        int idx = parent.partIndexOf(oldNode);
        if (idx < 0) {
            throw new IllegalArgumentException("node is not a direct part of its parent");
        }
        snapshot(parent);
        snapshot(oldNode);
        moveLeadingTrivia(oldNode, newNode);
        parent.replacePartAt(idx, newNode);
        return newNode;
    }

    private Node insertOneAt(Node anchor, Node stmt, int offset) {
        Node parent = requireParent(anchor);
        int idx = parent.partIndexOf(anchor);
        if (idx < 0) {
            throw new IllegalArgumentException("anchor is not a direct part of its parent");
        }
        snapshot(parent);
        String prefix = "\n" + leadingIndentOf(anchor);
        String sameLine = normalizeLeadingTrivia(stmt, prefix);
        stmt.prependToken(ws(sameLine != null ? sameLine : prefix));
        parent.insertPart(idx + offset, stmt);
        return stmt;
    }

    /**
     * Rewrite drive, task 045e2: "return switch (node) {" has the shape of a signature (type "return", name
     * "switch"), and so do "return new Box() {" and "else if (a) {". A head whose name before its parameters is a
     * keyword, or whose first word opens a statement, begins a statement and not a method.
     */
    private static boolean opensAStatement(String head) {
        String[] words = head.substring(0, head.lastIndexOf('(')).strip().split("\\s+");
        String name = words[words.length - 1];
        return TokenType.keyword(name) != null || STATEMENT_WORDS.contains(words[0]);
    }

    /** Opening minus closing braces outside string and character literals and comments. */
    static int braceBalance(String code) {
        int balance = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < code.length() && code.charAt(i) != c; i++) {
                    if (code.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                while (i < code.length() && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 1;
            } else if (c == '{') {
                balance++;
            } else if (c == '}') {
                balance--;
            }
        }
        return balance;
    }

    /** Remove and return the end-of-line comment of the part at {@code idx} (with the spacing before it), or none. */
    private List<Token> takeEndOfLineComment(Node parent, int idx) {
        if (idx + 1 >= parent.parts().size()) {
            return List.of();
        }
        Node holder = parent;
        int at = idx + 1;
        while (holder.parts().get(at) instanceof Node inner) {
            if (inner.parts().isEmpty()) {
                return List.of();
            }
            holder = inner;
            at = 0;
        }
        List<Object> parts = holder.parts();
        int comment = at;
        if (parts.get(comment) instanceof Token w && w.type() == TokenType.WHITESPACE && !w.text().contains("\n")) {
            comment++;
        }
        List<Token> taken = new ArrayList<>();
        if (comment < parts.size() && parts.get(comment) instanceof Token c && c.type() == TokenType.SINGLE_LINE_COMMENT) {
            snapshot(holder);
            for (int i = comment; i >= at; i--) {
                taken.add(0, (Token) parts.get(i));
                holder.removePartAt(i);
            }
        }
        return taken;
    }

    /**
     * Rewrite drive, tidy pass: the parser hands a member's end-of-line comment ({@code boolean open;   // why}) to the
     * part after it, so members replaced or deleted left their comments behind, and in one ported file eight of them
     * ended up on one line before the class's closing brace. The comment on the last line of the part at {@code idx}
     * is that part's: it is dropped with it, and the line break after it stays.
     */
    private void dropEndOfLineComment(Node parent, int idx) {
        takeEndOfLineComment(parent, idx);
    }

    /**
     * Rewrite drive, parser slice 030j2: a re-authored member ended in a sentence of explanation after its closing
     * brace, and every attempt was refused as "unexpected trailing input". Text after the first complete member that
     * is not Java (no member, annotation or modifier follows) is dropped; a second member still makes the refusal.
     */
    static String withoutTrailingText(String source) {
        String s = source == null ? "" : source.strip();
        try {
            JavaParser.parseMemberSnippet(s);
            return s;
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("trailing")) {
                return s;   // a different fault: the caller's parse reports it
            }
        }
        for (int i = s.indexOf('}'); i >= 0; i = s.indexOf('}', i + 1)) {
            String head = s.substring(0, i + 1);
            try {
                JavaParser.parseMemberSnippet(head);
            } catch (RuntimeException notYet) {
                continue;
            }
            String rest = s.substring(i + 1).strip();
            boolean javaFollows = rest.startsWith("@") || rest.startsWith("//") || rest.startsWith("/*")
                    || rest.matches("(?s)(public|private|protected|static|final|abstract|void|class|interface|enum|record)\\b.*");
            if (!javaFollows) {
                try {
                    JavaParser.parseMemberSnippet(rest);
                    javaFollows = true;
                } catch (RuntimeException prose) {
                    // not a member: explanation text
                }
            }
            return javaFollows ? s : head;
        }
        return s;
    }

    /**
     * Rewrite drive, task 048a: every inserted member went to the end of the class, so a port's fields and constructor
     * came after its methods. A field goes after the last field; a constructor after the last constructor, else after
     * the last field; anything else, or a member of a type without such members, at the end (null: no member before).
     */
    private static Node placeAfter(List<Node> members, Node member) {
        Node lastField = null;
        Node lastConstructor = null;
        for (Node m : members) {
            if (m.type() == NodeType.FIELD_DECLARATION) {
                lastField = m;
            } else if (m.type() == NodeType.CONSTRUCTOR_DECLARATION) {
                lastConstructor = m;
            }
        }
        if (member.type() == NodeType.FIELD_DECLARATION && lastField != null) {
            return lastField;
        }
        if (member.type() == NodeType.CONSTRUCTOR_DECLARATION) {
            return lastConstructor != null ? lastConstructor : lastField;
        }
        return null;
    }

    /** The members of {@code source} when it is two or more complete members (each with its comments), else empty. */
    static List<String> separateMembers(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        try {
            ParseTree wrapper = ParseTree.parse("class TreeEditorMembers {\n" + source.strip() + "\n}\n");
            Node body = wrapper.typeDeclarations().get(0).child(NodeType.CLASS_BODY);
            List<Node> members = body == null ? List.of() : body.children();
            if (members.size() < 2) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Node m : members) {
                out.add(m.toString().strip());
            }
            return out;
        } catch (RuntimeException notMembers) {
            return List.of();   // prose, a fragment or one member: the single-member path decides
        }
    }

    private static void appendCode(Node n, StringBuilder sb) {
        for (Object part : n.parts()) {
            if (part instanceof Token t) {
                if (!t.isTrivia()) {
                    sb.append(t.text()).append(' ');
                }
            } else if (part instanceof Node child) {
                appendCode(child, sb);
            }
        }
    }

    /** {@code name(arity)} when {@code member} is a method the type already declares with the same parameter count
     *  (constructors and fields included), else null. */
    static String duplicateMemberName(List<Node> members, Node member) {
        if (member == null || member.name() == null) {
            return null;
        }
        int arity = member.children(NodeType.PARAMETER).size();
        boolean isMethod = member.type() == NodeType.METHOD_DECLARATION
                || member.type() == NodeType.CONSTRUCTOR_DECLARATION;
        for (Node existing : members) {
            if (existing.type() != member.type() || existing.name() == null
                    || !existing.name().equals(member.name())) {
                continue;
            }
            // agent loop port, plan B (2026-10-25): an overload with as many parameters of other types is no duplicate
            if (!isMethod || existing.children(NodeType.PARAMETER).size() == arity
                    && erasedParameterTypes(existing).equals(erasedParameterTypes(member))) {
                return member.name() + (isMethod ? "(" + arity + " param" + (arity == 1 ? "" : "s") + ")" : "");
            }
        }
        return null;
    }

    /** A method's parameter types as Java compares overloads: simple names, generics erased, varargs as arrays. */
    private static List<String> erasedParameterTypes(Node method) {
        List<String> types = new ArrayList<>();
        for (Node parameter : method.children(NodeType.PARAMETER)) {
            Node type = parameter.child(NodeType.TYPE);
            String t = type == null ? "" : type.toString().replaceAll("<.*>", "").replaceAll("\\s+", "");
            t = t.substring(t.lastIndexOf('.', t.indexOf('[') < 0 ? t.length() : t.indexOf('[')) + 1);
            types.add(parameter.hasModifier(TokenType.ELLIPSIS) ? t + "[]" : t);
        }
        return types;
    }

    /** Re-indent a member's continuation lines to {@code indent} (the first line is positioned by the caller);
     *  the member's own relative indentation is preserved by stripping its common leading whitespace first. */
    static String reindentContinuation(String source, String indent) {
        String[] lines = source.strip().split("\n", -1);
        if (lines.length == 1) {
            return lines[0];
        }
        int common = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            int lead = 0;
            while (lead < lines[i].length() && (lines[i].charAt(lead) == ' ' || lines[i].charAt(lead) == '\t')) {
                lead++;
            }
            common = Math.min(common, lead);
        }
        if (common == Integer.MAX_VALUE) {
            common = 0;
        }
        // rewrite drive, tidy pass (2026-10-21): with no closing line to measure from (a field, a declaration without a
        // body), the common indent was the continuation's own, so a Set's continuation lines came out at the field's
        // indent. Such a declaration's comment, annotation and first code lines stay at the member's indent, and the
        // lines after them continue a double indent (eight columns) in, measured among themselves.
        boolean bodyless = !lines[lines.length - 1].strip().startsWith("}");
        int firstCode = 0;
        while (firstCode < lines.length - 1 && isCommentOrAnnotationLine(lines[firstCode])) {
            firstCode++;
        }
        int continued = Integer.MAX_VALUE;
        for (int i = firstCode + 1; bodyless && i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                continued = Math.min(continued, lines[i].length() - lines[i].stripLeading().length());
            }
        }
        StringBuilder sb = new StringBuilder(lines[0].strip());
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            sb.append('\n');
            if (!l.isBlank()) {
                if (bodyless && i > firstCode) {
                    sb.append(indent).append("        ").append(l.substring(Math.min(continued, l.length())));
                    continue;
                }
                String rest = l.substring(Math.min(common, l.length()));
                // lines that close or continue the member body sit one level INSIDE the member unless they are
                // the closing brace of the member itself
                sb.append(indent).append(rest);
            }
        }
        return sb.toString();
    }

    /** Every code token of {@code n}'s subtree, space-separated (whitespace and comments aside). */
    private static String codeOnly(Node n) {
        StringBuilder sb = new StringBuilder();
        appendCode(n, sb);
        return sb.toString();
    }

    /** The existing member whose code (whitespace and comments aside) is the same as {@code member}'s, or null. */
    static Node identicalMember(List<Node> members, Node member) {
        if (member == null) {
            return null;
        }
        String want = codeOnly(member);
        for (Node existing : members) {
            if (existing.type() == member.type() && codeOnly(existing).equals(want)) {
                return existing;
            }
        }
        return null;
    }

    private static List<String> annotationNames(Node member) {
        List<String> names = new ArrayList<>();
        for (Node a : member.children(NodeType.ANNOTATION)) {
            names.add("@" + a.name());
        }
        return names;
    }

    /** The whitespace a node starts with, up to its first comment or code token. */
    private static String leadingWhitespaceOf(Node node) {
        List<Token> leading = new ArrayList<>();
        collectLeadingTokens(node, leading);
        StringBuilder sb = new StringBuilder();
        for (Token t : leading) {
            if (t.type() != TokenType.WHITESPACE) {
                break;
            }
            sb.append(t.text());
        }
        return sb.toString();
    }

    /** The order of imports: static ones first, then by the imported name. */
    private static String importKey(Node imp) {
        return (imp.hasModifier(TokenType.STATIC) ? "0 " : "1 ") + ParseTree.importedName(imp);
    }

    /** The whitespace between two adjacent imports: a blank line where the static imports end, else a line break. */
    private static String importGap(Node before, Node after) {
        return before.hasModifier(TokenType.STATIC) && !after.hasModifier(TokenType.STATIC) ? "\n\n" : "\n";
    }

    /**
     * Rewrite drive, tidy pass (2026-10-21): a port's fields and constructor came after its methods, and no op moved a
     * member. {@code member} moves, with its comments, annotations and end-of-line comment, to just before {@code before},
     * a member of the same type: on the next line when both are fields, else after a blank line. The end-of-line comment
     * of the line before its old place stays on that line.
     */
    public void moveMember(Node member, Node before) {
        Node body = requireParent(member);
        if (member == before) {
            return;
        }
        if (before.parent() != body) {
            throw new IllegalArgumentException("the member to move before is not in the same type body");
        }
        int idx = body.partIndexOf(member);
        if (idx < 1) {
            throw new IllegalArgumentException("the member is not a direct part of its type body");
        }
        List<Token> previousLine = takeEndOfLineComment(body, idx - 1);
        List<Token> ownLine = takeEndOfLineComment(body, idx);
        snapshot(body);
        body.removePartAt(idx);
        for (int i = previousLine.size() - 1; i >= 0; i--) {
            body.insertPart(idx, previousLine.get(i));
        }
        String indent = leadingIndentOf(before);
        layOutLeadingTrivia(member, "\n" + indent);
        if (!ownLine.isEmpty()) {
            snapshot(member);
            ownLine.forEach(member::add);
        }
        // once the file was written and read again, a moved field's end-of-line comment opened the trivia of the member
        // after it, and the next field moved before that member went in front of the comment, which then ended the
        // wrong line. The comment that ends the line before {@code before} stays on that line.
        List<Token> lineEnd = takeEndOfLineComment(body, body.partIndexOf(before) - 1);
        int at = body.partIndexOf(before);
        Node previous = null;
        for (int i = at - 1; i >= 0 && previous == null; i--) {
            if (body.parts().get(i) instanceof Node n) {
                previous = n;
            }
        }
        boolean fieldAfterField = previous != null && previous.type() == NodeType.FIELD_DECLARATION
                && member.type() == NodeType.FIELD_DECLARATION;
        member.prependToken(ws((fieldAfterField ? "\n" : "\n\n") + indent));
        body.insertPart(at, member);
        for (int i = lineEnd.size() - 1; i >= 0; i--) {
            body.insertPart(at, lineEnd.get(i));
        }
    }

    /**
     * Rewrite drive, tidy pass (2026-10-21): fields the reference keeps together had blank lines between them, and a
     * class line had none after it. The member gets {@code blankLines} blank lines before it (before its comments); an
     * end-of-line comment of the line before it stays on that line.
     */
    public void spaceBefore(Node member, int blankLines) {
        Node body = requireParent(member);
        int idx = body.partIndexOf(member);
        if (idx < 1) {
            throw new IllegalArgumentException("the member is not a direct part of its type body");
        }
        String indent = memberIndentOf(body, member);
        List<Token> previousLine = takeEndOfLineComment(body, idx - 1);
        if (!previousLine.isEmpty()) {
            snapshot(body);
            for (int i = previousLine.size() - 1; i >= 0; i--) {
                body.insertPart(idx, previousLine.get(i));
            }
        }
        layOutLeadingTrivia(member, "\n" + indent);
        member.prependToken(ws("\n".repeat(Math.max(0, blankLines) + 1) + indent));
    }

    /**
     * The indentation of a member's line: its own when it starts a line, else that of a member of the same body that
     * does (a member that shared a line was spaced with the column it started at).
     */
    private static String memberIndentOf(Node body, Node member) {
        if (startsLine(member)) {
            return leadingIndentOf(member);
        }
        for (Node other : body.children()) {
            if (other != member && startsLine(other)) {
                return leadingIndentOf(other);
            }
        }
        return leadingIndentOf(member);
    }

    /** Whether a node's leading trivia holds a line break, so that it starts its own line. */
    private static boolean startsLine(Node node) {
        List<Token> leading = new ArrayList<>();
        collectLeadingTokens(node, leading);
        for (Token t : leading) {
            if (!t.isTrivia()) {
                return false;
            }
            if (t.text().contains("\n")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replace the modifiers of a type's declaration ({@code "public final"}, or {@code ""} for none); the type's comment
     * and its annotations stay. Rewrite drive t115: a class the port called from another package had to become public,
     * and no op changed a type's own modifiers.
     */
    public void setTypeModifiers(Node typeDecl, String modifiers) {
        setModifiers(typeDecl, modifiers);
    }

    /**
     * Replace the modifiers of a method, constructor or field declaration, as {@link #setTypeModifiers} does for a type.
     * Rewrite drive, task 125: the agent loop moved to another package than the helpers it calls, and no op changed a
     * member's modifiers.
     */
    public void setMemberModifiers(Node member, String modifiers) {
        setModifiers(member, modifiers);
    }

    private void setModifiers(Node decl, String modifiers) {
        List<Token> wanted = new ArrayList<>();
        for (Token t : JavaLexer.lex(modifiers == null ? "" : modifiers.strip())) {
            if (t.type() == TokenType.END_OF_FILE || t.isTrivia()) {
                continue;
            }
            if (!MODIFIER_KEYWORDS.contains(t.type())) {
                throw new IllegalArgumentException("not a modifier: " + t.text());
            }
            wanted.add(t);
        }
        snapshot(decl);
        // past the leading comment and the annotations to the modifiers, each removed with the whitespace after it
        int i = 0;
        boolean removed = false;
        while (i < decl.parts().size()) {
            Object part = decl.parts().get(i);
            if (part instanceof Node n ? n.type() == NodeType.ANNOTATION : ((Token) part).isTrivia()) {
                i++;
                continue;
            }
            if (!(part instanceof Token t) || !MODIFIER_KEYWORDS.contains(t.type())) {
                break;
            }
            removed = true;
            decl.removePartAt(i);
            while (i < decl.parts().size() && decl.parts().get(i) instanceof Token w
                    && w.type() == TokenType.WHITESPACE) {
                decl.removePartAt(i);
            }
        }
        // the new modifiers go before the first token of the part that follows (a member's type, say), past the trivia
        // that leads it; that part may hold the line break before the member, or the space after a removed modifier
        Node holder = decl;
        int at = i;
        while (at < holder.parts().size() && holder.parts().get(at) instanceof Node child) {
            snapshot(child);
            holder = child;
            at = 0;
            while (at < holder.parts().size() && holder.parts().get(at) instanceof Token w && w.isTrivia()) {
                if (removed && w.type() == TokenType.WHITESPACE && !w.text().contains("\n")) {
                    holder.removePartAt(at);
                } else {
                    at++;
                }
            }
        }
        for (Token t : wanted.reversed()) {
            holder.insertPart(at, ws(" "));
            holder.insertPart(at, t);
        }
    }

    /**
     * Rewrite drive, tidy pass (2026-10-21): four class comments ran past 120 columns, and no op replaced a type's own
     * comment. The comments before the type's declaration are replaced by {@code comment} (a comment, nothing else);
     * the whitespace before them stays.
     */
    public void replaceTypeComment(Node typeDecl, String comment) {
        String c = comment == null ? "" : comment.strip();
        List<Token> lexed = new ArrayList<>();
        for (Token t : JavaLexer.lex(c)) {
            if (t.type() != TokenType.END_OF_FILE) {
                lexed.add(t);
            }
        }
        if (c.isEmpty() || lexed.stream().anyMatch(t -> !t.isTrivia())) {
            throw new IllegalArgumentException("the code is not a comment: " + c);
        }
        String indent = lineIndentOf(typeDecl);
        List<Token> lead = takeLeadingTrivia(typeDecl);
        StringBuilder before = new StringBuilder();
        for (Token t : lead) {
            if (t.type() != TokenType.WHITESPACE) {
                break;
            }
            before.append(t.text());
        }
        typeDecl.prependToken(ws("\n" + indent));
        String[] lines = c.split("\n", -1);
        StringBuilder text = new StringBuilder(lines[0].strip());
        for (int i = 1; i < lines.length; i++) {
            text.append('\n').append(indent).append(lines[i].strip().startsWith("*") ? " " + lines[i].strip() : lines[i].strip());
        }
        for (Token t : List.copyOf(JavaLexer.lex(text.toString())).reversed()) {
            if (t.type() != TokenType.END_OF_FILE) {
                typeDecl.prependToken(t);
            }
        }
        typeDecl.prependToken(ws(before.toString()));
    }

    /** Take a node's leading trivia run (recursing into leading child nodes), each mutated level journaled. */
    private List<Token> takeLeadingTrivia(Node node) {
        List<Token> taken = new ArrayList<>();
        Node n = node;
        while (true) {
            if (!n.parts().isEmpty() && n.parts().get(0) instanceof Token t0 && t0.isTrivia()) {
                snapshot(n);
                while (!n.parts().isEmpty() && n.parts().get(0) instanceof Token t && t.isTrivia()) {
                    taken.add(t);
                    n.removePartAt(0);
                }
            }
            if (!n.parts().isEmpty() && n.parts().get(0) instanceof Node child) {
                n = child;
                continue;
            }
            return taken;
        }
    }

    /**
     * A member's leading trivia laid out again: its whitespace dropped, each comment on a line of its own. A blank line
     * after a comment stays (rewrite drive, task 123: a section's heading ran into the member's javadoc, or into the
     * member itself, once the member was moved).
     */
    private void layOutLeadingTrivia(Node member, String lineBreak) {
        List<Token> lead = takeLeadingTrivia(member);
        snapshot(member);
        for (int k = lead.size() - 1; k >= 0; k--) {
            Token t = lead.get(k);
            if (t.type() != TokenType.WHITESPACE) {
                boolean blankAfter = k + 1 < lead.size() && lead.get(k + 1).type() == TokenType.WHITESPACE
                        && lead.get(k + 1).text().indexOf('\n') != lead.get(k + 1).text().lastIndexOf('\n');
                member.prependToken(ws(blankAfter ? "\n" + lineBreak : lineBreak));
                member.prependToken(t);
            }
        }
    }

    /** A line of a member's comments or annotations, which sit at the member's own indent. */
    private static boolean isCommentOrAnnotationLine(String line) {
        String t = line.strip();
        return t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("@");
    }
}
