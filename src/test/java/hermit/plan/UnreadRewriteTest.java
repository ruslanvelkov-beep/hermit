package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.sym.ClasspathIndex;
import hermit.api.Action;
import hermit.tool.analysis.TreeEditTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** edits that rewrite code the turn never read are refused, with the lines to read. */
class UnreadRewriteTest {

    private static final String SOURCE = """
            class P {
                static void take(Cursor cursor, Node into) {
                    if (cursor.pos < cursor.tokens.size()) {
                        Token t = cursor.tokens.get(cursor.pos);
                        into.add(t);
                        cursor.pos++;
                    }
                }

                static Node parseCatch(Cursor cursor) {
                    Node node = new Node(NodeType.CATCH);
                    take(cursor, node);
                    node.setPrincipal(cursor.peek());
                    return node;
                }
            }
            """;

    @TempDir
    Path dir;

    private Action codegen(Map<String, Object> item) {
        return new Action("TreeCodegen", Map.of("file", "src/P.java", "method", "P#take", "items", List.of(item)));
    }

    @Test
    void theLinesAnEditRewritesAreTheAnchoredStatementOrTheBody() {
        assertArrayEquals(new int[] {3, 7}, TreeEditTool.rewrittenLines(SOURCE, "replaceStatement", "P#take(Cursor, Node)",
                "if (cursor.pos < cursor.tokens.size())"));
        assertArrayEquals(new int[] {10, 15}, TreeEditTool.rewrittenLines(SOURCE, "replaceBody", "P#parseCatch", null));
        assertNull(TreeEditTool.rewrittenLines(SOURCE, "insertMember", null, null), "an insert rewrites nothing");
        assertArrayEquals(new int[] {10, 15}, TreeEditTool.rewrittenLines(SOURCE, "replaceMember", "P#parseCatch", null));
        assertNull(TreeEditTool.rewrittenLines(SOURCE, "replaceBody", "P#missing", null), "the tool reports it");
    }

    @Test
    void aMethodNameShortOfItsParametersIsRefusedWhenOverloadsShareIt() {
        // "R#resolve" took the first of three overloads, and the body landed in the wrong one
        String overloads = """
                class R {
                    static R resolve(String t) { return resolve(t, 1); }
                    static R resolve(String t, int i) { return null; }
                }
                """;
        TreeEditTool.Outcome out = TreeEditTool.apply(overloads, ClasspathIndex.system(), null, "replaceBody", "R#resolve", null, null,
                "{ return null; }");
        assertTrue(!out.ok() && String.join(" ", out.problems()).contains("R#resolve(String, int)"),
                String.valueOf(out.problems()));
        assertTrue(TreeEditTool.apply(overloads, ClasspathIndex.system(), null, "replaceBody", "R#resolve(String, int)", null, null,
                "{ return new R(); }").ok());
    }

    @Test
    void aRewriteOfABodyOnlyLocatedIsRefusedWithItsLines() throws IOException {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/P.java"), SOURCE);
        String located = "Current request: give catch its principal.\n[Iteration 1] Tool results: {Bash={output="
                + "src/P.java:10 — `static Node parseCatch(Cursor cursor) {`}}";
        List<Action> plan = List.of(codegen(Map.of("op", "replaceBody", "method", "P#parseCatch", "code", "{ return null; }")));
        String issue = RepeatGuard.unreadRewriteIssue(plan, located, dir);
        assertTrue(issue != null && issue.contains("sed -n '10,15p' src/P.java"), String.valueOf(issue));
    }

    @Test
    void aRewriteOfCodeTheTurnReadPasses() throws IOException {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/P.java"), SOURCE);
        String read = "Current request: give catch its principal.\n[Iteration 1] Tool results: {Bash={output="
                + String.join("\n", SOURCE.lines().toList().subList(9, 15)) + "}}";
        List<Action> plan = List.of(codegen(Map.of("op", "replaceBody", "method", "P#parseCatch", "code", "{ return null; }")),
                new Action("TreeEdit", Map.of("op", "insertMember", "file", "src/P.java", "type", "P", "code", "int x;")));
        assertNull(RepeatGuard.unreadRewriteIssue(plan, read, dir));
        List<Action> statement = List.of(codegen(Map.of("op", "replaceStatement", "method", "P#take(Cursor, Node)",
                "anchor", "if (cursor.pos < cursor.tokens.size())", "code", "if (true) { }")));
        String issue = RepeatGuard.unreadRewriteIssue(statement, read, dir);
        assertTrue(issue != null && issue.contains("sed -n '3,7p' src/P.java"), String.valueOf(issue));
        assertEquals(null, RepeatGuard.unreadRewriteIssue(statement, read + "\n" + SOURCE, dir));
    }
}
