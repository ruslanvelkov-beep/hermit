package hermit.tool.analysis;

import hermit.analysis.edit.EditCommitException;
import hermit.analysis.edit.TreeEditor;
import hermit.analysis.lex.LexException;
import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.sym.SourceIndex;
import hermit.analysis.tree.JavaParser;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseException;
import hermit.analysis.tree.ParseTree;
import hermit.tool.api.Tool;
import hermit.tool.graph.CodeGraphTool;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ONE parse-tree edit per call, committed transactionally: the code snippet is parsed by the real Java
 * parser, spliced into the file's tree, and the whole edited program is re-validated (names, members,
 * arities). Only a VALID program reaches disk - on rejection the file is untouched and the positioned
 * problems come back for the caller to iterate on. This is the primitive of incremental code generation:
 * commit per expression, feedback per failure.
 */
public class TreeEditTool implements Tool {

    private static final int FOCUS_CAP = 8000;
    /** the content each edited file had before its most recent successful edit in this process. */
    private static final Map<Path, String> ORIGINALS = new ConcurrentHashMap<>();
    /** A snippet that opens with a member modifier - a field or method declaration, never a statement. */
    private static final Pattern MEMBER_MODIFIER = Pattern.compile(
            "^\\s*(?:public|protected|private|static|abstract|synchronized|transient|volatile|default)\\s+\\S");
    // a type used as a qualifier (TokenType.ELLIPSIS) without its import is reported as an
    // unknown VARIABLE; a capitalized one is looked up as a type too (imported only when exactly one type has the name)
    private static final Pattern UNKNOWN_TYPE =
            Pattern.compile("unknown type '([A-Za-z0-9_]+)'|unknown variable '([A-Z][A-Za-z0-9_]*)'");
    /**
     * The insertMember target when {@code type} is omitted: the class named in {@code method}
     * (Type#method), else the file's sole type declaration - reject only when truly ambiguous.
     */
    private static final Set<String> STATEMENT_OPS = Set.of("insertBefore", "insertAfter",
            "replaceStatement", "deleteStatement", "replaceExpression");

    /** The outcome of one edit application (shared with TreeCodegenTool). */
    public record Outcome(boolean ok, String rendered, String focusText, List<String> problems) {}

    @Override
    public String getDocumentation() {
        return "TreeEdit: apply ONE validated edit to a Java file via its parse tree — the edit is parsed, spliced, "
                + "and the WHOLE program re-checked (unknown names/members/arities are rejected with positioned "
                + "problems and the file is NOT written; fix and retry). Parameters: {\"file\": \"<path, absolute or "
                + "relative to projectDir>\", \"op\": \"insertAtEnd\"|\"insertAtStart\"|\"insertBefore\"|"
                + "\"insertAfter\"|\"replaceStatement\"|\"replaceExpression\"|\"deleteStatement\"|\"replaceBody\"|"
                + "\"replaceMember\"|\"deleteMember\"|\"insertMember\"|\"addImport\"|\"deleteImport\"|\"sortImports\"|"
                + "\"spaceMembers\"|\"addType\" (replaceMember replaces a whole method, declaration and comments "
                + "included; replaceBody only its body; deleteMember removes a member named by \"method\", its "
                + "comments included; moveMember moves the member \"method\" names to just before the member "
                + "\"anchor\" names (a field that shares its name with a method is named with its declaration as "
                + "\"code\"); spaceBefore gives the member \"method\" names \"code\" blank lines before it (\"0\" or "
                + "\"1\"); replaceTypeComment replaces the comment of the type \"type\" names by \"code\"; "
                + "typeModifiers sets the modifiers of the type \"type\" names to \"code\" (\"public final\", or \"\" "
                + "for none); memberModifiers sets those of the member \"method\" names to \"code\"; indentMembers "
                + "indents the members of the type \"type\" names one level below its declaration), "
                + "\"code\": \"<the statement/expression/member/import/type source>\", \"method\": \"<Type#method "
                + "hosting the edit>\" (for statement/expression ops), \"anchor\": \"<text of the existing "
                + "statement/expression to insert at / replace / delete>\" (for before/after/replace/delete), "
                + "\"type\": \"<type name>\" (for insertMember), \"projectDir\", \"classesDir\" (optional)}. Several "
                + "edits of ONE file go in ONE action: {\"file\": \"<path>\", \"items\": [{\"op\": ..., \"code\": "
                + "..., \"method\"/\"anchor\"/\"type\" as above}, ...]}, applied in order, all or none (a failed item "
                + "leaves the file as it was and names the item). Returns the enclosing method's full text after a "
                + "successful edit.";
    }

    /**
     * a plan sent one TreeEdit action with "items" and no "op", as TreeCodegen takes them.
     * The items run in order on the action's file, each with the action's parameters under its own; when one fails,
     * the file is put back as it was and the error names that item.
     */
    private Map<String, Object> executeItems(Map<String, Object> parameters, List<?> items) {
        Path file = Path.of(str(parameters.get("file")));
        String projectDir = firstNonBlank(str(parameters.get("projectDir")), System.getProperty("hermit.projectDir"));
        if (!file.isAbsolute() && projectDir != null) {
            file = Path.of(projectDir).resolve(file);
        }
        String original;
        try {
            original = Files.readString(file);
        } catch (IOException e) {
            return Map.of("status", "Error", "message", "file not found: " + file);
        }
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Map<?, ?> item)) {
                return Map.of("status", "Error", "message", "item " + (i + 1) + " is not an object");
            }
            Map<String, Object> one = new LinkedHashMap<>(parameters);
            one.remove("items");
            item.forEach((k, v) -> one.put(String.valueOf(k), v));
            Map<String, Object> result = execute(one);
            if (!"Success".equals(result.get("status"))) {
                try {
                    Files.writeString(file, original);
                } catch (IOException e) {
                    // the error below still names the item; the file may hold the items before it
                }
                Map<String, Object> err = new LinkedHashMap<>(result);
                err.put("message", "item " + (i + 1) + " of " + items.size() + " failed, and the file is as it was: "
                        + result.get("message"));
                return err;
            }
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "Success");
        ok.put("file", file.toString());
        ok.put("items", items.size());
        return ok;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String projectDir = firstNonBlank(str(parameters.get("projectDir")),
                System.getProperty("hermit.projectDir"));
        String fileParam = str(parameters.get("file"));
        String op = str(parameters.get("op"));
        String code = str(parameters.get("code"));
        String typeParam = str(parameters.get("type"));
        // `op=addType, type=<the whole class text>` - the declaration was handed over as the type
        // name; a declaration in `type` IS the code when none is given
        if ((code == null || code.isBlank()) && typeParam != null && looksLikeDeclaration(typeParam)) {
            code = typeParam;
            typeParam = null;
        }
        if (fileParam != null && parameters.get("items") instanceof List<?> items && !items.isEmpty()) {
            return executeItems(parameters, items);
        }
        if (fileParam == null || op == null) {
            return Map.of("status", "Error", "message", "file and op are required.");
        }
        Path file = Path.of(fileParam);
        if (!file.isAbsolute() && projectDir != null) {
            file = Path.of(projectDir).resolve(fileParam);
        }
        file = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return Map.of("status", "Error", "message", "file not found: " + file);
        }
        String classesDir = firstNonBlank(str(parameters.get("classesDir")),
                System.getProperty("hermit.classesDir"));
        try {
            String source = Files.readString(file);
            SourceIndex sources = null;
            if (projectDir != null && Files.isDirectory(Path.of(projectDir))) {
                sources = CodeGraphTool.graphFor(projectDir, classesDir).sources();
            }
            Outcome outcome = apply(source, CodeGraphTool.indexFor(classesDir), sources, op,
                    str(parameters.get("method")), typeParam,
                    str(parameters.get("anchor")), code);
            if (!outcome.ok()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", "Error");
                err.put("message", "edit rejected — the file was NOT modified; fix the code and retry");
                err.put("problems", outcome.problems());
                return err;
            }
            ORIGINALS.put(file, source);   // the pre-edit content, for a revert when the module stops compiling
            Files.writeString(file, outcome.rendered());
            CodeGraphTool.invalidate(file);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", "Success");
            ok.put("op", op);
            ok.put("file", file.toString());
            ok.put("focus", cap(outcome.focusText()));
            return ok;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** True for text that declares a type or member (modifiers then class/interface/enum/record). */
    public static boolean looksLikeDeclaration(String text) {
        return text != null && text.strip().matches("(?s)^(?:(?:public|private|protected|static|final|abstract|sealed)\\s+)*(?:class|interface|enum|record)\\s+\\w+.*");
    }

    /** the pre-edit contents kept for reverts, as plain strings (for a step checkpoint). */
    public static Map<String, String> originalsSnapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        ORIGINALS.forEach((k, v) -> out.put(k.toString(), v));
        return out;
    }

    /** restores the pre-edit contents from a step checkpoint (replaces what is held now). */
    public static void restoreOriginals(Map<String, String> snapshot) {
        ORIGINALS.clear();
        if (snapshot != null) {
            snapshot.forEach((k, v) -> ORIGINALS.put(Path.of(k), v));
        }
    }

    /**
     * an edit that passed validation left the module uncompilable (`contents.getBytes()`
     * on a byte[] whose type the index could not see), and every later plan worked on a broken file. The pre-edit
     * content is restored; returns true when there was something to restore.
     */
    public static boolean revertLastEdit(Path file) {
        Path key = file.toAbsolutePath().normalize();
        String original = ORIGINALS.remove(key);
        if (original == null) {
            return false;
        }
        try {
            Files.writeString(key, original);
            CodeGraphTool.invalidate(key);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- the shared edit engine ------------------------------------------------------------------------
    /**
     * Parse {@code source}, apply one operation, and commit. Never touches disk. {@code focusText} of a
     * successful outcome is the enclosing method's (or new member's/type's) rendered text - the string the
     * critics review. With a {@code sources} table, validation also sees sibling source files.
     *
     * The author's CODE was correct ("Instant startedAt = NOW;") and only its op name was
     * invented - insertStatement, replace, insert - three attempts in a row. The intent is unambiguous, so obvious
     * synonyms map to the real op (an anchor decides between the positional forms) instead of failing the item.
     *
     * `when(x).willThrow(y)` mixes Mockito's classic and BDD styles and does not compile; the
     * repair author writes it constantly. Both chains are normalised to the BDD form the test classes use.
     * Only in code: string literals, text blocks and comments stay as written (a ported test passed chains to this
     * method as strings, and the edit turned its inputs into its expected outputs).
     */
    static String mockitoStyle(String code) {
        if (code == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        StringBuilder masked = new StringBuilder(code.length());
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            int end = -1;
            if (c == '/' && i + 1 < n && (code.charAt(i + 1) == '/' || code.charAt(i + 1) == '*')) {
                boolean line = code.charAt(i + 1) == '/';
                end = line ? code.indexOf('\n', i) : code.indexOf("*/", i + 2);
                end = end < 0 ? n : line ? end : end + 2;
            } else if (code.startsWith("\"\"\"", i)) {
                end = literalEnd(code, i + 3, "\"\"\"");
            } else if (c == '"' || c == '\'') {
                end = literalEnd(code, i + 1, String.valueOf(c));
            }
            if (end < 0) {
                masked.append(c);
                i++;
            } else {
                masked.append('\uE000').append(kept.size()).append('\uE001');
                kept.add(code.substring(i, end));
                i = end;
            }
        }
        String out = masked.toString().replaceAll(
                "\\bwhen\\((.+?)\\)\\.will(Throw|Return|Answer|CallRealMethod|DoNothing)\\(", "given($1).will$2(");
        out = out.replaceAll("\\bgiven\\((.+?)\\)\\.then(Throw|Return|Answer|CallRealMethod)\\(", "given($1).will$2(");
        Matcher placeholder = Pattern.compile("\uE000(\\d+)\uE001").matcher(out);
        StringBuilder restored = new StringBuilder(code.length());
        while (placeholder.find()) {
            String original = kept.get(Integer.parseInt(placeholder.group(1)));
            placeholder.appendReplacement(restored, Matcher.quoteReplacement(original));
        }
        placeholder.appendTail(restored);
        return restored.toString();
    }

    /**
     * a placeholder anchor ("<the quoted statement…>") was refused before the tool ran, so the
     * planner never saw what it could anchor on and repeated the placeholder. The method's LEAF statements, in body
     * order (compound blocks skipped), for the executor to append to its refusal.
     */
    public static List<String> anchorableStatements(Path file, String methodSpec, int max) {
        List<String> out = new ArrayList<>();
        try {
            ParseTree tree = ParseTree.parse(Files.readString(file));
            Node method = methodNode(tree, simpleSpec(methodSpec));
            for (Node st : statementsIn(method)) {
                if (st.type() == NodeType.CASE || !st.descendants(NodeType.BLOCK).isEmpty()) {
                    continue;
                }
                String text = statementDisplay(st);
                out.add(text.length() > 160 ? text.substring(0, 160) + "…" : text);
                if (out.size() >= max) {
                    break;
                }
            }
        } catch (Exception e) {
            // unreadable file / unknown method - nothing to suggest
        }
        return out;
    }

    /**
     * the first and last line (1-based) of the code an edit rewrites from its current text -
     * the anchored statement of a replaceStatement, the body of a replaceBody - or null when the edit rewrites nothing
     * that exists, or its target cannot be found (the edit itself then reports that).
     */
    public static int[] rewrittenLines(String source, String op, String methodSpec, String anchor) {
        if (methodSpec == null || !("replaceStatement".equals(op) || "replaceBody".equals(op) || "replaceMember".equals(op))) {
            return null;
        }
        try {
            Node method = methodNode(ParseTree.parse(source), simpleSpec(methodSpec));
            Node target = "replaceBody".equals(op) ? method.child(NodeType.BLOCK) : "replaceMember".equals(op) ? method
                    : anchor == null ? null : findStatement(method, anchor);
            if (target == null || target.firstCodeToken() == null || target.lastToken() == null) {
                return null;
            }
            return new int[] {target.firstCodeToken().line(), target.lastToken().line()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code com.acme.pkg.Type#m} → {@code Type#m}; {@code com.acme.Outer.Inner} → {@code Outer.Inner}; null stays null. */
    static String simpleSpec(String spec) {
        if (spec == null) {
            return null;
        }
        String s = spec.strip();
        int hash = s.indexOf('#');
        String type = hash >= 0 ? s.substring(0, hash) : s;
        String rest = hash >= 0 ? s.substring(hash) : "";
        String[] parts = type.split("\\.");
        int first = 0;
        while (first < parts.length - 1 && !parts[first].isEmpty() && Character.isLowerCase(parts[first].charAt(0))) {
            first++;   // package segments
        }
        StringBuilder t = new StringBuilder();
        for (int i = first; i < parts.length; i++) {
            if (t.length() > 0) {
                t.append('.');
            }
            t.append(parts[i]);
        }
        String out = t + rest;
        // `TaskQueue.drainPending` - the dotted Type.method form the planner
        // writes most often - was "method not found"; a trailing lower-case segment after a Type is the method
        if (hash < 0 && out.contains(".")) {
            int dot = out.lastIndexOf('.');
            String head = out.substring(0, dot);
            String last = out.substring(dot + 1);
            String headLast = head.substring(head.lastIndexOf('.') + 1);
            if (!last.isEmpty() && Character.isLowerCase(last.charAt(0)) && !headLast.isEmpty()
                    && Character.isUpperCase(headLast.charAt(0))) {
                return head + "#" + last;
            }
        }
        return out;
    }

    /**
     * Typographic dashes and quotes → their ASCII forms, in code and comments; fatal in code. Inside string and
     * character literals and text blocks they are part of the value and stay. A test's expected text had an em dash
     * before "the types in scope:", and the dash became a hyphen, which no problem carries, so the test could never
     * pass.
     */
    static String asciiPunctuation(String code) {
        if (code == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(code.length());
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < n && (code.charAt(i + 1) == '/' || code.charAt(i + 1) == '*')) {
                boolean line = code.charAt(i + 1) == '/';
                int end = line ? code.indexOf('\n', i) : code.indexOf("*/", i + 2);
                end = end < 0 ? n : line ? end : end + 2;
                out.append(asciiForms(code.substring(i, end)));
                i = end;
            } else if (code.startsWith("\"\"\"", i)) {
                int end = literalEnd(code, i + 3, "\"\"\"");
                out.append(code, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = literalEnd(code, i + 1, String.valueOf(c));
                out.append(code, i, end);
                i = end;
            } else if (c == '“' || c == '”' || c == '‘' || c == '’') {
                // a typographic quote in code opens a literal: it runs to the next quote of either form
                char ascii = c == '“' || c == '”' ? '"' : '\'';
                String closers = ascii == '"' ? "\"“”" : "'‘’";
                int end = i + 1;
                while (end < n && closers.indexOf(code.charAt(end)) < 0 && code.charAt(end) != '\n') {
                    end += code.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end, n);
                out.append(ascii).append(code, i + 1, end);
                if (end < n && code.charAt(end) != '\n') {
                    out.append(ascii);
                    end++;
                }
                i = end;
            } else {
                out.append(asciiForms(String.valueOf(c)));
                i++;
            }
        }
        return out.toString();
    }

    private static String asciiForms(String text) {
        return text.replace('—', '-').replace('–', '-').replace('−', '-')
        .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
        .replace(' ', ' ');
    }

    /** The index after the {@code closer} that ends a literal whose content starts at {@code from}, or the code's end. */
    private static int literalEnd(String code, int from, String closer) {
        int i = from;
        while (i < code.length()) {
            if (code.charAt(i) == '\\') {
                i += 2;
            } else if (code.startsWith(closer, i)) {
                return i + closer.length();
            } else if (code.charAt(i) == '\n' && closer.length() == 1) {
                return i;   // an unclosed one-line literal ends at its line
            } else {
                i++;
            }
        }
        return code.length();
    }

    public static String canonicalOp(String op, String anchor) {
        if (op == null) {
            return null;
        }
        boolean anchored = anchor != null && !anchor.isBlank();
        return switch (op.trim().toLowerCase(Locale.ROOT)) {
            case "insert", "add", "insertstatement", "addstatement", "appendstatement", "append" ->
                    anchored ? "insertAfter" : "insertAtEnd";
            case "replace", "replaceline", "modify", "update", "edit" -> "replaceStatement";
            case "replacebody", "rewritebody", "replacemethodbody", "rewritemethod", "rewrite", "setbody", "body",
                    "replacemethod" -> "replaceBody";
            case "replacemember", "replacedeclaration", "rewritemember", "replacewhole" -> "replaceMember";
            case "deletemember", "removemember", "deletemethod", "removemethod", "deletefield", "removefield" -> "deleteMember";
            case "remove", "delete", "deleteline", "removestatement", "deletestmt", "removeline" -> "deleteStatement";
            case "addmember", "insertfield", "insertmethod", "addmethod", "addfield" -> "insertMember";
            case "import", "addstaticimport" -> "addImport";
            case "deleteimport", "removeimport" -> "deleteImport";
            case "sortimports", "orderimports", "organizeimports" -> "sortImports";
            case "spacemembers", "blanklinesbetweenmembers" -> "spaceMembers";
            default -> op;
        };
    }

    public static Outcome apply(String source, ClasspathIndex index, SourceIndex sources,
            String rawOp, String methodSpec, String typeName, String anchor, String rawCode) {
        // Fourteen items in one run died on `illegal character '-'` - the author copies the
        // typographic dashes and quotes of its own instructions into code. Normalise them; and code that IS an
        // import statement is an addImport whatever op the author named.
        String code = mockitoStyle(asciiPunctuation(rawCode));
        String op = canonicalOp(rawOp, anchor);
        // `method=com.example….TaskQueue#drainPending` failed with
        // "method not found" - the generator normalises qualified specs, this tool did not, and the coaching echoed the
        // qualified name back. Package prefixes (lower-case segments) are dropped; a nested type keeps its outer name.
        methodSpec = simpleSpec(methodSpec);
        typeName = simpleSpec(typeName);
        // a deleteImport stays a deleteImport: made an addImport, the unused import it named stayed and the tool
        // answered Success
        if (code != null && code.strip().startsWith("import ") && !"deleteImport".equals(op)) {
            op = "addImport";
        }
        ParseTree tree = ParseTree.parse(source);
        TreeEditor editor = TreeEditor.of(tree, index, sources);
        String focusName = null;
        try {
            // `replaceStatement` without `method` was refused although the anchor
            // occurs in exactly one method of the file - the anchor names the method then
            if ((methodSpec == null || methodSpec.isBlank()) && anchor != null && !anchor.isBlank()
                    && STATEMENT_OPS.contains(op)) {
                methodSpec = methodContainingAnchor(tree, anchor);
            }
            // A replaceBody without `method` whose code is the whole method
            // was refused for the missing parameter three times; the declaration in the code names its target
            if ("replaceBody".equals(op) && (methodSpec == null || methodSpec.isBlank()) && code != null) {
                methodSpec = specOfDeclaration(tree, typeName, code);
            }
            switch (op) {
                case "addImport" -> editor.addImport(stripImport(require(code, "code")));
                case "sortImports" -> editor.sortImports();
                case "spaceMembers" -> editor.spaceMembers(findType(tree, memberTargetType(tree, typeName, methodSpec)));
                case "deleteImport" -> {
                    Node imp = findImport(tree, require(code, "code"));
                    editor.deleteImport(imp);
                }
                case "addType" -> {
                    Node t = editor.addType(require(code, "code"));
                    focusName = t.name();
                }
                case "insertMember" -> {
                    Node type = findType(tree, memberTargetType(tree, typeName, methodSpec));
                    Node m = editor.insertMember(type, require(code, "code"));
                    String unusable = instanceMethodOfUninstantiableClass(type, m, source);
                    if (unusable != null) {
                        throw new IllegalArgumentException(unusable);
                    }
                    focusName = m.name();
                }
                case "insertAtEnd", "insertAtStart" -> {
                    Node body = bodyOf(tree, require(methodSpec, "method"));
                    if (op.equals("insertAtEnd")) {
                        editor.insertStatementAtEnd(body, require(code, "code"));
                    } else {
                        List<Node> stmts = statementsOf(body);
                        if (stmts.isEmpty()) {
                            editor.insertStatementAtEnd(body, require(code, "code"));
                        } else {
                            editor.insertStatementBefore(stmts.get(0), require(code, "code"));
                        }
                    }
                }
                case "insertBefore", "insertAfter" -> {
                    Node anchorStmt = findStatement(methodNode(tree, require(methodSpec, "method")),
                            require(anchor, "anchor"));
                    if (op.equals("insertBefore")) {
                        editor.insertStatementBefore(anchorStmt, require(code, "code"));
                    } else {
                        editor.insertStatementAfter(anchorStmt, require(code, "code"));
                    }
                }
                case "replaceBody" -> editor.replaceBody(methodNode(tree, require(methodSpec, "method")),
                        require(code, "code"));
                case "replaceMember" -> editor.replaceMember(memberNode(tree, require(methodSpec, "method"), code),
                        require(code, "code"));
                case "replaceStatement" -> editor.replaceStatement(
                        findStatement(methodNode(tree, require(methodSpec, "method")), require(anchor, "anchor")),
                        require(code, "code"));
                case "deleteMember" -> editor.deleteMember(memberNode(tree, require(methodSpec, "method")));
                case "moveMember" -> editor.moveMember(memberNode(tree, require(methodSpec, "method"), code),
                        memberNode(tree, require(anchor, "anchor")));
                case "spaceBefore" -> editor.spaceBefore(memberNode(tree, require(methodSpec, "method")),
                        Integer.parseInt(require(code, "code").strip()));
                case "replaceTypeComment" -> editor.replaceTypeComment(findType(tree, require(typeName, "type")),
                        require(code, "code"));
                case "typeModifiers" -> editor.setTypeModifiers(findType(tree, require(typeName, "type")),
                        code == null ? "" : code);
                case "memberModifiers" -> editor.setMemberModifiers(memberNode(tree, require(methodSpec, "method")),
                        code == null ? "" : code);
                case "indentMembers" -> editor.indentMembers(findType(tree, require(typeName, "type")));
                case "deleteStatement" -> editor.delete(
                        findStatement(methodNode(tree, require(methodSpec, "method")), require(anchor, "anchor")));
                case "replaceExpression" -> {
                    Node method = methodNode(tree, require(methodSpec, "method"));
                    String a = require(anchor, "anchor");
                    try {
                        editor.replaceExpression(findExpression(method, a), require(code, "code"));
                    } catch (IllegalArgumentException noExpr) {
                        // The model often hands a STATEMENT (or several) to replaceExpression -
                        // coerce the single-statement case, coach the rest
                        if (hasTopLevelSemicolon(a)) {
                            throw new IllegalArgumentException(noExpr.getMessage()
                                    + " — the anchor contains SEVERAL statements; use one item per "
                                    + "statement (op=replaceStatement or deleteStatement, anchor = ONE "
                                    + "statement's exact text)");
                        }
                        Node stmt;
                        try {
                            stmt = findStatement(method, a);
                        } catch (IllegalArgumentException noStmt) {
                            throw noExpr;
                        }
                        String c = require(code, "code").strip();
                        editor.replaceStatement(stmt,
                                c.endsWith(";") || c.endsWith("}") ? c : c + ";");
                    }
                }
                default -> throw new IllegalArgumentException("unknown op '" + op + "'");
            }
            ParseTree fresh;
            try {
                fresh = editor.commit();
            } catch (EditCommitException first) {
                // AUTO-IMPORT: an "unknown type 'X'" whose simple name exists at exactly ONE classpath
                // FQN is imported for the model (fix-forward on the editor), then the commit retries.
                // Ambiguous names get their candidates listed in the problem instead.
                List<String> enriched = autoImport(editor, index, sources, first.problems());
                if (enriched == null) {
                    fresh = editor.commit();   // imports added - a second failure propagates normally
                } else {
                    return new Outcome(false, null, null, withGrounding(enriched, tree, methodSpec));
                }
            }
            return new Outcome(true, fresh.toString(), focusText(fresh, methodSpec, typeName, focusName), List.of());
        } catch (EditCommitException e) {
            return new Outcome(false, null, null, withGrounding(e.problems(), tree, methodSpec));
        } catch (IllegalArgumentException | ParseException | LexException e) {
            return new Outcome(false, null, null, List.of(coachSnippetError(op, code, e.getMessage())));
        }
    }

    /**
     * every fix plan - the local planner's and the escalated one's - used names the
     * class does not have (cachedSummary, expectedPageCount, SummaryResponse): they were editing a method they had
     * never read. A rejection for unknown names now carries the method as it stands (header, statements) and the
     * class's fields, so the next edit can be written from the real names.
     */
    static List<String> withGrounding(List<String> problems, ParseTree tree, String methodSpec) {
        if (problems == null || problems.isEmpty() || methodSpec == null || !methodSpec.contains("#")) {
            return problems;
        }
        boolean unknownName = problems.stream().anyMatch(p -> p.contains("unknown variable") || p.contains("unknown type")
                || p.contains("has no method") || p.contains("no visible method"));
        if (!unknownName) {
            return problems;
        }
        try {
            String[] tp = methodSpec.split("#", 2);
            Node method = tree.findMethod(tp[0], tp[1]);
            if (method == null) {
                return problems;
            }
            StringBuilder g = new StringBuilder("GROUNDING — write the edit from the REAL names. The method as it stands: ");
            String header = method.toString();
            int brace = header.indexOf('{');
            g.append((brace > 0 ? header.substring(0, brace) : header).replaceAll("\\s+", " ").strip()).append(" { ");
            Node body = TreeEditor.bodyOf(method);
            int shown = 0;
            if (body != null) {
                for (Node st : statementsOf(body)) {
                    String text = st.toString().replaceAll("\\s+", " ").strip();
                    g.append(text.length() > 160 ? text.substring(0, 160) + "…" : text).append(' ');
                    if (++shown >= 14) {
                        g.append("… ");
                        break;
                    }
                }
            }
            g.append("} ");
            Node type = tree.findType(tp[0]);
            if (type != null) {
                List<String> fields = new ArrayList<>();
                for (Node f : type.descendants(NodeType.FIELD_DECLARATION)) {
                    String text = f.toString().replaceAll("\\s+", " ").strip();
                    fields.add(text.length() > 90 ? text.substring(0, 90) + "…" : text);
                    if (fields.size() >= 8) {
                        break;
                    }
                }
                if (!fields.isEmpty()) {
                    g.append("Fields of ").append(tp[0]).append(": ").append(String.join(" ", fields));
                }
            }
            List<String> out = new ArrayList<>(problems);
            out.add(g.toString());
            return out;
        } catch (Exception e) {
            return problems;
        }
    }

    // `final var x = …;` is a LOCAL declaration - `final` is not a member signal
    private static Set<String> identifiersOf(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("[A-Za-z_]\\w{2,}").matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String x : a) {
            if (b.contains(x)) {
                n++;
            }
        }
        return n;
    }

    /** Redirects the two commonest snippet-shape mistakes (whole file / member code in a statement op). */
    private static String coachSnippetError(String op, String code, String msg) {
        String c = code == null ? "" : code.strip();
        boolean statementOp = switch (op) {
            case "insertAtEnd", "insertAtStart", "insertBefore", "insertAfter",
                    "replaceStatement", "replaceExpression" -> true;
            default -> false;
        };
        if (statementOp && (c.startsWith("package ") || c.startsWith("import "))) {
            return msg + " — the code looks like a WHOLE FILE; an item carries only statements. "
                    + "Use op=addImport for imports and op=insertMember for methods.";
        }
        if (statementOp && c.startsWith("@")) {
            return msg + " — annotated declarations are class MEMBERS, not statements; "
                    + "use op=insertMember with the full method.";
        }
        if (statementOp && MEMBER_MODIFIER.matcher(c).find()) {
            // the author sent "private Instant startedAt;" as a STATEMENT four times; the
            // member coaching only recognised code starting with an annotation
            String head = c.length() > 90 ? c.substring(0, 90) + "…" : c;
            return msg + " — «" + head + "» is a FIELD/member declaration (it starts with a modifier), not a "
                    + "statement. Either add it as a member (op=insertMember, code = the field or method) or, if you "
                    + "want a LOCAL variable inside the method, drop the modifier: 'Instant startedAt = …;'.";
        }
        if (statementOp && (c.startsWith("//") || c.startsWith("/*"))) {
            // the author's code is a comment - alone ("// stub X to throw"), or with the statement
            // on the SAME line ("// note; foo();"), which a // comment swallows to the end of the line. Reported as
            // "INCOMPLETE" before, which sent the author chasing a cut-off that did not exist.
            boolean swallowed = c.startsWith("//") && c.indexOf('\n') < 0 && c.contains(";");
            String head = c.length() > 100 ? c.substring(0, 100) + "…" : c;
            return msg + " — the code is a COMMENT, not a statement: «" + head + "». "
                    + (swallowed ? "A // comment swallows everything to the END OF ITS LINE, so the statement after "
                            + "the note is comment too — put the statement on its OWN line (or drop the note)."
                            : "Write the Java statement itself (a note like '// stub X' edits nothing).");
        }
        if (msg != null && msg.contains("END_OF_FILE")) {
            // the item's code stops mid-expression: the model's string was cut off (length) or broken by an
            // unescaped quote/newline in the JSON - the fix is a COMPLETE, shorter snippet
            return msg + " — the code is INCOMPLETE (it ends mid-expression). Emit the WHOLE statement, and keep each "
                    + "item to ONE short statement; escape quotes and newlines inside the JSON string.";
        }
        return msg;
    }

    /** The candidate FQN whose package shares the longest prefix with the file's existing imports - only when a
     *  single candidate wins (ties or no imports → null, and the ambiguity is reported as before). */
    static String byPackageAffinity(List<String> candidates, List<Node> imports) {
        List<String> imported = new ArrayList<>();
        for (Node imp : imports) {
            String n = ParseTree.importedName(imp);
            if (n != null) {
                imported.add(n);
            }
        }
        String best = null;
        int bestLen = 0;
        boolean tie = false;
        for (String c : candidates) {
            String pkg = c.substring(0, Math.max(0, c.lastIndexOf('.')));
            int len = 0;
            for (String i : imported) {
                len = Math.max(len, commonPrefixSegments(pkg, i));
            }
            if (len > bestLen) {
                bestLen = len;
                best = c;
                tie = false;
            } else if (len == bestLen && len > 0) {
                tie = true;
            }
        }
        return tie || bestLen == 0 ? null : best;
    }

    private static int commonPrefixSegments(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = 0;
        while (n < x.length && n < y.length && x[n].equals(y[n])) {
            n++;
        }
        return n;
    }

    /**
     * Add imports for uniquely-resolvable unknown types; returns null when at least one import was
     * added (caller retries the commit), else the problems enriched with import candidates.
     *
     * a stub class named a same-package class that the next edit was to create; the
     * only class of that simple name on the classpath was the tool's OWN (hermit.analysis.sym.SymbolResolver), so it
     * was imported into the project and the build failed. The tool's own classes are on its classpath, not the
     * project's: they are never import candidates, unless the project's sources declare that package (the tool editing
     * itself).
     */
    static boolean isToolOwnClass(String fqn, SourceIndex sources) {
        String[] parts = TreeEditTool.class.getPackageName().split("\\.");
        String root = parts[0];   // "hermit"
        if (!fqn.startsWith(root + ".")) {
            return false;
        }
        int dot = fqn.lastIndexOf('.');
        return sources == null || !sources.hasPackage(dot < 0 ? "" : fqn.substring(0, dot));
    }

    public static List<String> autoImport(TreeEditor editor, ClasspathIndex index,
            SourceIndex sources, List<String> problems) {
        boolean imported = false;
        List<String> enriched = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String p : problems) {
            var m = UNKNOWN_TYPE.matcher(p);
            String note = p;
            while (m.find()) {
                String simple = m.group(1) != null ? m.group(1) : m.group(2);
                if (!done.add(simple)) {
                    continue;
                }
                // classpath (compiled deps/JDK) UNION project source types - a type may live in either
                LinkedHashSet<String> fqns = new LinkedHashSet<>(index.fqnsOf(simple));
                if (sources != null) {
                    fqns.addAll(sources.fqnsOf(simple));
                }
                // JDK-internal / hidden packages are never legitimate imports - drop them so they don't
                // create phantom ambiguity against a real project or library type of the same simple name
                fqns.removeIf(fqn -> fqn.startsWith("jdk.internal.") || fqn.startsWith("sun.")
                        || fqn.startsWith("com.sun.") || fqn.contains(".internal."));
                fqns.removeIf(fqn -> isToolOwnClass(fqn, sources));
                // java.util.stream.Node, a package-private JDK class, was imported for the
                // project's own Node (the file's java.util imports gave it the package affinity); a class that is not
                // public cannot be imported from another package
                fqns.removeIf(fqn -> {
                    Class<?> c = index.load(fqn);
                    return c != null && !Modifier.isPublic(c.getModifiers());
                });
                List<String> candidates = new ArrayList<>(fqns);
                if (candidates.size() > 1) {
                    // `State` is ambiguous repo-wide, but a file that already imports com.acme.state.*
                    // and com.acme.* wants com.acme.state.State - pick by package affinity with the
                    // file's existing imports when exactly one candidate wins
                    String affine = byPackageAffinity(candidates, editor.tree().imports());
                    if (affine != null) {
                        candidates = List.of(affine);
                    }
                }
                if (candidates.size() == 1) {
                    editor.addImport(candidates.get(0));
                    imported = true;
                } else if (candidates.size() > 1) {
                    note += " — add an addImport item; candidates: "
                            + candidates.subList(0, Math.min(5, candidates.size()));
                }
            }
            enriched.add(note);
        }
        return imported ? null : enriched;
    }

    // ---- node addressing ---------------------------------------------------------------------------------

    /**
     * replaceMember could reach only methods, so a field's comment could not be put back.
     * "Type#name" names the method of that name, or, when the type has none, the field that declares that name.
     */
    private static Node memberNode(ParseTree tree, String spec) {
        return memberNode(tree, spec, null);
    }

    /**
     * As above, but when {@code code} declares a field, "Type#name" names the field even though a method shares its
     * name (the follower's field helperOff and its method helperOff()). So does "Type#name;", in the ops whose code is
     * not a declaration (spaceBefore, memberModifiers).
     */
    private static Node memberNode(ParseTree tree, String spec, String code) {
        int hash = spec.indexOf('#');
        if (hash > 0 && spec.indexOf('(') < 0) {
            Node type = tree.findType(spec.substring(0, hash));
            boolean fieldNamed = spec.strip().endsWith(";");
            String name = spec.substring(hash + 1).strip().replaceAll(";$", "").strip();
            Node body = type == null ? null : type.child(NodeType.CLASS_BODY);
            if (body != null && name.equals("static")) {
                // "Type#static" names the type's static initializer, which has no name of its own to move it or
                // anchor a member on
                for (Node init : body.children(NodeType.INITIALIZER_BLOCK)) {
                    if (init.hasModifier(TokenType.STATIC)) {
                        return init;
                    }
                }
            }
            boolean method = body != null && body.children(NodeType.METHOD_DECLARATION).stream()
                    .anyMatch(m -> name.equals(m.name()));
            if (body != null && (!method || fieldNamed || declaresField(code))) {
                for (Node field : body.children(NodeType.FIELD_DECLARATION)) {
                    if (field.children(NodeType.VAR_DECLARATOR).stream().anyMatch(d -> name.equals(d.name()))) {
                        return field;
                    }
                }
                // a nested record or class is a member too ("TestReach#Route")
                for (Node member : body.children()) {
                    if (member.type().isTypeDeclaration() && name.equals(member.name())) {
                        return member;
                    }
                }
            }
        }
        return methodNode(tree, spec);
    }

    /** Whether {@code code} is one field declaration. */
    private static boolean declaresField(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        try {
            return JavaParser.parseMemberSnippet(code.strip()).type() == NodeType.FIELD_DECLARATION;
        } catch (RuntimeException notAMember) {
            return false;
        }
    }

    private static Node methodNode(ParseTree tree, String spec) {
        // A parameter list in the spec ("Type#name(Cursor, Node)") picks one of several overloads; without one, the
        // first method of that name is taken. Before, the list was compared as part of the name, so it never matched.
        String nameSpec = spec;
        List<String> wantedParameters = null;
        int paren = spec.indexOf('(');
        if (paren > 0 && spec.strip().endsWith(")")) {
            nameSpec = spec.substring(0, paren).strip();
            wantedParameters = parameterTypes(spec.substring(paren + 1, spec.strip().length() - 1));
        }
        List<Node> candidates;
        if (nameSpec.contains("#")) {
            String[] parts = nameSpec.split("#", 2);
            Node type = tree.findType(parts[0]);
            String name = parts[1];
            candidates = type == null ? List.of() : type.descendants(NodeType.METHOD_DECLARATION).stream()
                    .filter(n -> name.equals(n.name())).toList();
            if (candidates.isEmpty() && type != null && name.equals(type.name())) {
                // "Type#Type" names the type's own constructors; a stub
                // constructor could not be replaced, since only methods were found by name
                Node body = type.child(NodeType.CLASS_BODY);
                candidates = body == null ? List.of() : body.children(NodeType.CONSTRUCTOR_DECLARATION);
            }
        } else {
            String name = nameSpec;
            candidates = tree.root().descendants(NodeType.METHOD_DECLARATION).stream()
                    .filter(n -> name.equals(n.name())).toList();
        }
        Node m = null;
        if (wantedParameters == null && candidates.size() > 1) {
            // "SymbolResolver#resolve" took the first of three overloads, and the body meant
            // for the three-parameter one went into the one-parameter one. A name that overloads share is refused.
            String type = nameSpec.contains("#") ? nameSpec.substring(0, nameSpec.indexOf('#')) + "#" : "";
            List<String> specs = new ArrayList<>();
            for (Node c : candidates) {
                specs.add(type + c.name() + "(" + String.join(", ", declaredParameterTypes(c)) + ")");
            }
            throw new IllegalArgumentException(candidates.size() + " methods are named '" + spec + "': " + specs
                    + " — name one with its parameter types, exactly as listed");
        }
        if (wantedParameters == null) {
            m = candidates.isEmpty() ? null : candidates.get(0);
        } else {
            for (Node c : candidates) {
                if (declaredParameterTypes(c).equals(wantedParameters)) {
                    m = c;
                    break;
                }
            }
        }
        if (m == null) {
            // name the REAL class - a small model copies "<the class>" literally into the next item
            String cls = spec.contains("#") ? spec.substring(0, spec.indexOf('#'))
                    : (tree.typeDeclarations().isEmpty() || tree.typeDeclarations().get(0).name() == null
                            ? "TheClass" : tree.typeDeclarations().get(0).name());
            // the suggested spec was built by prefixing the type onto the RAW name, so a dotted or
            // parenthesised input produced "TaskQueueTest#TaskQueueTest.testX()" - advice that cannot work
            throw new IllegalArgumentException("method not found: " + spec
                    + " — if this is a NEW method, create it FIRST with an insertMember item"
                    + " (op=insertMember, type=" + cls + ", code=the full method skeleton), then edit it with "
                    + "method=" + cls + "#" + simpleMethodName(spec) + ".");
        }
        return m;
    }

    /** "Type#name(T1, T2)" for code that is one whole method declaration, or null when it is not one. */
    static String specOfDeclaration(ParseTree tree, String typeName, String code) {
        Node m;
        try {
            m = JavaParser.parseMemberSnippet(code.strip());
        } catch (RuntimeException notAMember) {
            return null;
        }
        if (m.type() != NodeType.METHOD_DECLARATION || m.name() == null) {
            return null;
        }
        String type = typeName != null && !typeName.isBlank() ? typeName
                : tree.typeDeclarations().isEmpty() ? null : tree.typeDeclarations().get(0).name();
        if (type == null) {
            return null;
        }
        List<String> parameters = new ArrayList<>();
        for (Node parameter : m.children(NodeType.PARAMETER)) {
            Node t = parameter.child(NodeType.TYPE);
            parameters.add(simpleTypeName(t == null ? "" : t.toString()));
        }
        return type + "#" + m.name() + "(" + String.join(", ", parameters) + ")";
    }

    /** The simple type names of a parameter list as written in a spec: "Cursor, java.util.List<Node>" -> [Cursor, List]. */
    static List<String> parameterTypes(String list) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : list.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(simpleTypeName(cur.toString()));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(simpleTypeName(cur.toString()));
        }
        return out;
    }

    /**
     * The simple parameter types a method or constructor declares; a variable arity one ends in "...", as the spec
     * writes it ("WriteProgramTool#run(String...)" was not found).
     */
    static List<String> declaredParameterTypes(Node method) {
        List<String> declared = new ArrayList<>();
        for (Node parameter : method.children(NodeType.PARAMETER)) {
            Node t = parameter.child(NodeType.TYPE);
            String type = simpleTypeName(t == null ? "" : t.toString());
            declared.add(parameter.hasModifier(TokenType.ELLIPSIS) ? type + "..." : type);
        }
        return declared;
    }

    /**
     * "Type#name(parameter types)" of the one method or constructor {@code code} declares,
     * "Type#name" of the field it declares, or null when it declares no member.
     */
    public static String memberSpec(String typeName, String code) {
        Node m;
        try {
            m = JavaParser.parseMemberSnippet(code.strip());
        } catch (RuntimeException e) {
            return null;
        }
        if (m.type() == NodeType.METHOD_DECLARATION || m.type() == NodeType.CONSTRUCTOR_DECLARATION) {
            List<String> types = declaredParameterTypes(m);
            String name = m.type() == NodeType.CONSTRUCTOR_DECLARATION ? typeName : m.name();
            return name == null ? null : typeName + "#" + name + "(" + String.join(", ", types) + ")";
        }
        if (m.type() == NodeType.FIELD_DECLARATION) {
            Node declarator = m.child(NodeType.VAR_DECLARATOR);
            return declarator == null || declarator.name() == null ? null : typeName + "#" + declarator.name();
        }
        return null;
    }

    /** A type's simple name for overload matching: no generics, package, modifiers or parameter name; arrays kept. */
    static String simpleTypeName(String type) {
        StringBuilder noGenerics = new StringBuilder();
        int depth = 0;
        for (char c : type.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0) {
                noGenerics.append(c);
            }
        }
        List<String> words = new ArrayList<>(List.of(noGenerics.toString().strip().split("\\s+")));
        words.removeIf(w -> w.equals("final") || w.startsWith("@") || w.isEmpty());
        if (words.size() > 1) {
            words.remove(words.size() - 1);   // "Cursor cursor": the parameter name
        }
        String s = String.join("", words);
        int end = s.indexOf('[') >= 0 ? s.indexOf('[') : (s.indexOf("...") >= 0 ? s.indexOf("...") : s.length());
        int dot = s.lastIndexOf('.', end - 1);
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** The bare method name in a spec: no package, no type, no parameter list. */
    static String simpleMethodName(String spec) {
        String s = spec == null ? "" : spec.trim();
        int paren = s.indexOf('(');
        if (paren > 0) {
            s = s.substring(0, paren);
        }
        int cut = Math.max(s.lastIndexOf('#'), s.lastIndexOf('.'));
        return cut >= 0 ? s.substring(cut + 1) : s;
    }

    private static Node bodyOf(ParseTree tree, String spec) {
        Node body = TreeEditor.bodyOf(methodNode(tree, spec));
        if (body == null) {
            throw new IllegalArgumentException("method has no body: " + spec);
        }
        return body;
    }

    /** the spec of the one method whose body holds a statement matching {@code anchor}; null when none. */
    static String methodContainingAnchor(ParseTree tree, String anchor) {
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        tree.root().walk(n -> {
            if (n.type() != NodeType.METHOD_DECLARATION) {
                return;
            }
            Node enclosing = n.parent();
            while (enclosing != null && !enclosing.type().isTypeDeclaration()) {
                enclosing = enclosing.parent();
            }
            try {
                findStatement(n, anchor);
                matches.add((enclosing == null || enclosing.name() == null ? "?" : enclosing.name()) + "#" + n.name());
            } catch (IllegalArgumentException notHere) {
                // this method does not hold the anchor
            }
        });
        if (matches.size() == 1) {
            return matches.iterator().next();
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("'method' is required — the anchor occurs in several methods: " + matches
                    + "; name the one to edit (method=Type#name)");
        }
        return null;
    }

    private static String memberTargetType(ParseTree tree, String typeName, String methodSpec) {
        if (typeName != null && !typeName.isBlank() && tree.findType(typeName) == null) {
            // `type=ConcurrentHashMap` - the FIELD's Java type - for an insertMember into
            // the file's only class; `type` names the class to edit, and an unambiguous file supplies it
            String fallback = methodSpec != null && methodSpec.contains("#") ? methodSpec.substring(0, methodSpec.indexOf('#'))
                    : tree.typeDeclarations().size() == 1 ? tree.typeDeclarations().get(0).name() : null;
            if (fallback != null && tree.findType(fallback) != null) {
                return fallback;
            }
            throw new IllegalArgumentException("type not found: " + typeName + " — `type` names the CLASS to insert into ("
                    + tree.typeDeclarations().stream().map(Node::name).limit(6).toList() + "), not the member's Java type");
        }
        if (typeName != null && !typeName.isBlank()) {
            return typeName;
        }
        if (methodSpec != null && methodSpec.contains("#")) {
            return methodSpec.substring(0, methodSpec.indexOf('#'));
        }
        List<Node> types = tree.typeDeclarations();
        if (types.size() == 1) {
            return types.get(0).name();
        }
        throw new IllegalArgumentException("'type' is required for this op — the file declares "
                + types.size() + " types: "
                + types.stream().map(Node::name).limit(6).toList());
    }

    private static Node findType(ParseTree tree, String name) {
        Node t = tree.findType(name);
        if (t == null) {
            throw new IllegalArgumentException("type not found: " + name);
        }
        return t;
    }

    private static Node findImport(ParseTree tree, String name) {
        String wanted = stripImport(name);
        for (Node imp : tree.imports()) {
            if (ParseTree.importedName(imp).equals(wanted)) {
                return imp;
            }
        }
        throw new IllegalArgumentException("import not found: " + wanted);
    }

    private static List<Node> statementsOf(Node body) {
        List<Node> out = new ArrayList<>();
        for (Node c : body.children()) {
            if (c.type().isStatement() || c.type().isTypeDeclaration()) {
                out.add(c);
            }
        }
        return out;
    }

    /** The statement inside {@code method} whose text matches {@code anchor} (exact first, contains next). */
    /** a ';' INSIDE a lambda body / parentheses / string does not make an anchor multi-statement -
     *  `assertThrows(X.class, () -> { subject.run(); })` is ONE statement. Only a top-level ';' (depth 0, before
     *  the trailing one) means several statements. */
    static boolean hasTopLevelSemicolon(String anchor) {
        String a = anchor.strip().replaceFirst(";\\s*$", "");
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                quote = c;
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ';' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    private static Node findStatement(Node method, String anchor) {
        String a = anchor.strip();
        if (a.startsWith("//") || a.startsWith("/*")) {
            // the author copies `// comment\nstatement;` pairs - or the comment alone - out of the
            // template as anchors. Anchor on the statement text that follows the comment; a comment-only anchor
            // resolves to the statement the comment is attached to (the next one, or the one it trails).
            String rest = stripLeadingComments(a);
            if (!rest.isBlank()) {
                return findStatement(method, rest);
            }
            // `// assertThrows(...);` - the author writes the STATEMENT with comment markers in front
            // (it had just "commented it out" in a rejected item). If the text inside the markers is a statement of the
            // body, that statement is the anchor.
            String inner = commentContent(a);
            if (!inner.isBlank()) {
                String want = normalize(inner);
                List<Node> body = statementsIn(method);
                List<Node> hits = body.stream().filter(n -> codeText(n).equals(want)).toList();
                if (hits.isEmpty()) {
                    hits = body.stream().filter(n -> codeText(n).contains(want)).toList();
                }
                if (hits.size() == 1) {
                    return hits.get(0);
                }
            }
            Node attached = statementAtComment(method, a);
            if (attached != null) {
                return attached;
            }
            throw new IllegalArgumentException("the anchor is a COMMENT that does not appear in " + describe(method)
                    + " — anchors must be statement text; anchor on the statement NEXT TO the comment instead");
        }
        String want = normalize(anchor);
        List<Node> all = statementsIn(method);
        for (Node n : all) {
            if (codeText(n).equals(want) || normalize(n.toString()).equals(want)) {
                return n;
            }
        }
        // match on CODE text: a statement's source text carries its leading trivia (the comment above it), which must
        // not make it match an anchor naming that comment (F131 regression: `// consume it` deleted the NEXT statement)
        List<Node> contains = all.stream().filter(n -> codeText(n).contains(want)).toList();
        if (contains.size() == 1) {
            return contains.get(0);
        }
        // the anchor 'boolean isCast = ...' named ONE statement, but the try and if
        // statements that enclose it contain its text too, so "3 statements match" refused every anchor that statement
        // could have. When the matches nest, the innermost one is the statement the anchor names.
        List<Node> innermost = contains.stream()
                .filter(n -> contains.stream().noneMatch(o -> o != n && encloses(n, o)))
                .toList();
        if (innermost.size() == 1) {
            return innermost.get(0);
        }
        if (contains.size() > 1) {
            throw new IllegalArgumentException(
                    contains.size() + " statements match '" + anchor + "' — use a longer, unique anchor");
        }
        // the anchor is a comment's TEXT (slashes dropped) - anchor on the statement that comment introduces
        Node viaComment = statementAtComment(method, anchor);
        if (viaComment != null) {
            return viaComment;
        }
        // COACH the iterating author: say what IS anchorable (or that the body is empty).
        if (all.isEmpty()) {
            throw new IllegalArgumentException("no statement matching '" + anchor + "' — the body of "
                    + describe(method) + " is EMPTY; use op=insertAtEnd (no anchor) to add the first "
                    + "statement. Anchors match STATEMENTS inside the body, never the method signature.");
        }
        // the anchor `alarmClock.setLastTickTime(now);` missed the real
        // two-argument call, and the coaching listed the method's FIRST three statements - none of them the target.
        // Show the statements CLOSEST to the attempted anchor (shared identifiers), so the author can copy the right one.
        // the enclosing try/if blocks outranked the target because they CONTAIN all its words -
        // leaf statements (no nested block) come first; a compound statement is never what an author copies
        List<Node> ranked = new ArrayList<>(all);
        Set<String> anchorIds = identifiersOf(anchor);
        ranked.sort((x, y) -> {
            boolean lx = x.descendants(NodeType.BLOCK).isEmpty();
            boolean ly = y.descendants(NodeType.BLOCK).isEmpty();
            if (lx != ly) {
                return lx ? -1 : 1;
            }
            return Integer.compare(overlap(identifiersOf(statementDisplay(y)), anchorIds),
                    overlap(identifiersOf(statementDisplay(x)), anchorIds));
        });
        boolean close = !ranked.isEmpty() && overlap(identifiersOf(statementDisplay(ranked.get(0))), anchorIds) >= 2;
        // with NO close match (the anchor was symbolic text), three statements tell the author
        // nothing - list up to twelve leaf statements so the right one is on the list
        int show = close ? 3 : 12;
        StringBuilder available = new StringBuilder();
        for (int i = 0; i < Math.min(show, ranked.size()); i++) {
            String shown = statementDisplay(ranked.get(i));
            available.append("\n  - ").append(shown.length() > 140 ? shown.substring(0, 140) + "…" : shown);
        }
        throw new IllegalArgumentException("no statement matching '" + anchor + "' in " + describe(method)
                + " (anchors match statements INSIDE the body, not the signature). "
                + (close ? "The CLOSEST existing statements — copy one EXACTLY as the anchor:" : "Anchorable statements include:")
                + available);
    }

    /** A statement's coach-text: its own code, leading comment lines stripped (they aren't anchorable). */
    private static String statementDisplay(Node n) {
        String[] lines = n.toString().strip().split("\n");
        int i = 0;
        while (i < lines.length - 1) {
            String l = lines[i].strip();
            if (l.isEmpty() || l.startsWith("//") || l.startsWith("/*") || l.startsWith("*")) {
                i++;
            } else {
                break;
            }
        }
        return normalize(String.join(" ", Arrays.copyOfRange(lines, i, lines.length)));
    }

    /** The expression inside {@code method} whose text matches {@code anchor} (exact, else unique contains). */
    private static Node findExpression(Node method, String anchor) {
        String want = normalize(anchor);
        List<Node> exact = new ArrayList<>();
        List<Node> contains = new ArrayList<>();
        method.walk(n -> {
            switch (n.type()) {
                case IDENTIFIER_EXPRESSION, LITERAL, THIS_EXPRESSION, PARENTHESIZED, METHOD_CALL,
                        FIELD_ACCESS, ARRAY_ACCESS, CLASS_LITERAL, ASSIGNMENT, TERNARY, BINARY_EXPRESSION,
                        UNARY_EXPRESSION, POSTFIX_EXPRESSION, CAST, INSTANCEOF, NEW_INSTANCE, NEW_ARRAY,
                        LAMBDA, METHOD_REFERENCE -> {
                    String norm = normalize(n.toString());
                    if (norm.equals(want)) {
                        exact.add(n);
                    } else if (norm.contains(want)) {
                        contains.add(n);
                    }
                }
                default -> { }
            }
        });
        if (!exact.isEmpty()) {
            return exact.get(0);
        }
        if (contains.size() == 1) {
            return contains.get(0);
        }
        throw new IllegalArgumentException(contains.isEmpty()
                ? "no expression matching '" + anchor + "' in " + describe(method)
                : "ambiguous expression anchor '" + anchor + "' — quote the exact expression text");
    }

    /**
     * a new helper lost its 'static' in a re-plan; the next item's call to it from a
     * static method was refused three times, and that item could not fix a declaration an earlier item had committed.
     * A class whose constructors are all private, whose other methods are all static and that nothing in its file
     * creates has no instance, so an instance method added to it can never be called: the member itself is refused.
     */
    static String instanceMethodOfUninstantiableClass(Node type, Node member, String source) {
        if (type == null || member == null || type.type() != NodeType.CLASS_DECLARATION
                || member.type() != NodeType.METHOD_DECLARATION || member.hasModifier(TokenType.STATIC)
                || member.hasModifier(TokenType.ABSTRACT)) {
            return null;
        }
        List<Node> constructors = ownMembers(type, NodeType.CONSTRUCTOR_DECLARATION);
        List<Node> methods = new ArrayList<>(ownMembers(type, NodeType.METHOD_DECLARATION));
        methods.remove(member);
        if (constructors.isEmpty() || methods.isEmpty()
                || constructors.stream().anyMatch(c -> !c.hasModifier(TokenType.PRIVATE))
                || methods.stream().anyMatch(x -> !x.hasModifier(TokenType.STATIC))
                || source.contains("new " + type.name() + "(")) {
            return null;
        }
        return "the class " + type.name() + " has only private constructors and only static methods, and nothing creates"
                + " an instance of it, so the instance method '" + member.name() + "' could never be called — declare it"
                + " static";
    }

    /** The members of {@code type} itself (not of its nested or anonymous classes). */
    private static List<Node> ownMembers(Node type, NodeType kind) {
        List<Node> out = new ArrayList<>();
        for (Node d : type.descendants(kind)) {
            Node owner = d.parent();
            while (owner != null && !owner.type().isTypeDeclaration()
                    && !(owner.type() == NodeType.NEW_INSTANCE && owner.child(NodeType.CLASS_BODY) != null)) {
                owner = owner.parent();
            }
            if (owner == type) {
                out.add(d);
            }
        }
        return out;
    }

    private static String focusText(ParseTree fresh, String methodSpec, String typeName, String focusName) {
        try {
            // an inserted member's focus was the text of the round's target method
            // (passed along as `method`), so the critics read one call where six new helpers had been inserted. The
            // inserted member is the focus; the method is the focus of an edit inside it.
            if (methodSpec != null && focusName == null) {
                return methodNode(fresh, methodSpec).toString().strip();
            }
            if (focusName != null) {
                Node t = fresh.findType(focusName);
                if (t != null) {
                    return t.toString().strip();
                }
                for (Node m : fresh.root().descendants(NodeType.METHOD_DECLARATION)) {
                    if (focusName.equals(m.name())) {
                        return m.toString().strip();
                    }
                }
                for (Node f : fresh.root().descendants(NodeType.FIELD_DECLARATION)) {
                    if (focusName.equals(f.name())) {
                        return f.toString().strip();
                    }
                }
            }
            if (typeName != null) {
                Node t = fresh.findType(typeName);
                if (t != null) {
                    return t.toString().strip();
                }
            }
        } catch (RuntimeException ignored) {
            // focus is best-effort
        }
        return "";
    }

    /** The body statements of a method (direct children of blocks/cases), in source order. */
    /** True when {@code inner} lies inside {@code outer}. */
    private static boolean encloses(Node outer, Node inner) {
        for (Node a = inner.parent(); a != null; a = a.parent()) {
            if (a == outer) {
                return true;
            }
        }
        return false;
    }

    private static List<Node> statementsIn(Node method) {
        List<Node> all = new ArrayList<>();
        method.walk(n -> {
            if (n.type().isStatement() && n.parent() != null
                    && (n.parent().type() == NodeType.BLOCK || n.parent().type() == NodeType.CASE)) {
                all.add(n);
            } else if (n.type() == NodeType.CASE) {
                all.add(n);   // an anchor on a case named the whole switch
            }
        });
        return all;
    }

    /** Drop leading {@code // ...} lines and {@code /* ... *}{@code /} blocks; what remains (stripped) is returned. */
    static String stripLeadingComments(String text) {
        String t = text == null ? "" : text.strip();
        while (true) {
            if (t.startsWith("//")) {
                int nl = t.indexOf('\n');
                t = nl < 0 ? "" : t.substring(nl + 1).strip();
            } else if (t.startsWith("/*")) {
                int end = t.indexOf("*/");
                t = end < 0 ? "" : t.substring(end + 2).strip();
            } else {
                return t;
            }
        }
    }

    /** The text inside a comment-marked anchor: leading {@code //} / {@code /* ... *}{@code /} markers removed and a
     *  trailing {@code // note} on the same line dropped. */
    static String commentContent(String anchor) {
        String t = anchor == null ? "" : anchor.strip();
        if (t.startsWith("/*")) {
            int end = t.indexOf("*/");
            t = end < 0 ? t.substring(2) : t.substring(2, end);
        } else if (t.startsWith("//")) {
            t = t.substring(2);
        }
        t = t.strip();
        // a trailing note after the statement on the same line: "stmt(); // Temporarily disabled …"
        int semi = t.indexOf(';');
        int note = t.indexOf("//");
        if (note > 0 && semi >= 0 && note > semi) {
            t = t.substring(0, note).strip();
        }
        return t;
    }

    /** A statement's code with trivia (comments/whitespace) removed and whitespace normalised. */
    private static String codeText(Node n) {
        List<Token> toks = new ArrayList<>();
        flatTokens(n, toks);
        StringBuilder sb = new StringBuilder();
        for (Token t : toks) {
            if (t.type() == TokenType.WHITESPACE) {
                sb.append(' ');
            } else if (!t.type().isTrivia()) {
                sb.append(t.text());
            }
        }
        return normalize(sb.toString());
    }

    /** All tokens of a subtree in source order, trivia included. */
    private static void flatTokens(Node n, List<Token> out) {
        for (Object p : n.parts()) {
            if (p instanceof Token t) {
                out.add(t);
            } else {
                flatTokens((Node) p, out);
            }
        }
    }

    /** the statement a comment is attached to - the statement FOLLOWING a comment on its own line(s), or the
     *  statement a trailing comment shares its line with. Null when no comment in the body matches. */
    static Node statementAtComment(Node method, String comment) {
        String want = normalize(comment.replace("*/", "").replace("/*", "").replace("//", ""));
        if (want.isEmpty()) {
            return null;
        }
        List<Node> all = statementsIn(method);
        List<Token> toks = new ArrayList<>();
        flatTokens(method, toks);
        for (int i = 0; i < toks.size(); i++) {
            Token t = toks.get(i);
            if (t.type() != TokenType.SINGLE_LINE_COMMENT
                    && t.type() != TokenType.MULTI_LINE_COMMENT) {
                continue;
            }
            String have = normalize(t.text().replace("*/", "").replace("/*", "").replace("//", ""));
            if (have.isEmpty() || !(have.contains(want) || want.contains(have))) {
                continue;
            }
            // trailing comment: only whitespace WITHOUT a newline between the previous code token and the comment
            boolean trailing = false;
            for (int j = i - 1; j >= 0; j--) {
                Token prev = toks.get(j);
                if (prev.type() == TokenType.WHITESPACE) {
                    if (prev.text().contains("\n")) {
                        break;
                    }
                    continue;
                }
                trailing = !prev.type().isTrivia();
                break;
            }
            Node before = null;
            for (Node stmt : all) {
                Token first = stmt.firstCodeToken();
                int idx = indexOfToken(toks, first);
                if (idx > i) {
                    return trailing && before != null ? before : stmt;
                }
                before = stmt;
            }
            return trailing ? before : null;
        }
        return null;
    }

    private static int indexOfToken(List<Token> toks, Token t) {
        for (int i = 0; i < toks.size(); i++) {
            if (toks.get(i) == t) {
                return i;
            }
        }
        return -1;
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String describe(Node method) {
        return method.name() == null ? method.type().toString() : "method '" + method.name() + "'";
    }

    private static String stripImport(String s) {
        String t = s.trim();
        if (t.startsWith("import ")) {
            t = t.substring("import ".length());
        }
        if (t.endsWith(";")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.trim();
    }

    private static String require(String v, String what) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("'" + what + "' is required for this op");
        }
        return v;
    }

    private static String cap(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= FOCUS_CAP ? s : s.substring(0, FOCUS_CAP) + "\n// ...(truncated)";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... ss) {
        for (String s : ss) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
