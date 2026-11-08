package hermit.tool.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.edit.TreeEditor;
import hermit.analysis.graph.CodeGraph;
import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.tree.ParseTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeEditToolTest {

    @TempDir
    Path dir;

    private Path file;
    private final TreeEditTool tool = new TreeEditTool();

    @BeforeEach
    void setUp() throws IOException {
        file = dir.resolve("A.java");
        Files.writeString(file, """
                class A {
                    int compute() { return 21; }

                    void run() {
                        int base = compute();
                        use(base);
                    }

                    void use(int x) {}
                }
                """);
    }

    private Map<String, Object> call(Map<String, Object> params) {
        Map<String, Object> p = new HashMap<>(params);
        p.put("projectDir", dir.toString());
        p.put("file", file.toString());
        return tool.execute(p);
    }

    @Test
    void successfulEditWritesTheFileAndReturnsTheMethod() throws IOException {
        Map<String, Object> r = call(Map.of(
                "op", "insertAfter", "method", "A#run",
                "anchor", "int base = compute();",
                "code", "int twice = base * 2;"));
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(Files.readString(file).contains("int twice = base * 2;"));
        assertTrue(String.valueOf(r.get("focus")).contains("int twice = base * 2;"),
                "the enclosing method's text comes back for inspection/critics");
    }

    @Test
    void rejectedEditLeavesTheFileUntouchedAndReportsProblems() throws IOException {
        String before = Files.readString(file);
        Map<String, Object> r = call(Map.of(
                "op", "insertAtEnd", "method", "A#run",
                "code", "int x = missingVar + compute(1, 2);"));
        assertEquals("Error", r.get("status"));
        @SuppressWarnings("unchecked")
        List<String> problems = (List<String>) r.get("problems");
        assertNotNull(problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown variable 'missingVar'")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("no overload of 'compute'")),
                problems.toString());
        assertEquals(before, Files.readString(file), "nothing was written");
    }

    @Test
    void replaceExpressionAndDeleteStatement() throws IOException {
        Map<String, Object> r1 = call(Map.of(
                "op", "replaceExpression", "method", "A#run",
                "anchor", "compute()", "code", "compute() + 1"));
        assertEquals("Success", r1.get("status"), r1.toString());
        assertTrue(Files.readString(file).contains("int base = compute() + 1;"));

        Map<String, Object> r2 = call(Map.of(
                "op", "deleteStatement", "method", "A#run", "anchor", "use(base);"));
        assertEquals("Success", r2.get("status"), r2.toString());
        Map<String, Object> r3 = call(Map.of(
                "op", "deleteStatement", "method", "A#run", "anchor", "int base"));
        assertEquals("Success", r3.get("status"), r3.toString());
        assertTrue(!Files.readString(file).contains("base"));
    }

    @Test
    void importsAndMembersAndTypes() throws IOException {
        assertEquals("Success", call(Map.of("op", "addType",
                "code", "class Extra { static int give() { return 5; } }")).get("status"));
        assertEquals("Success", call(Map.of("op", "insertMember", "type", "A",
                "code", "int helper(int v) { return v * 2; }")).get("status"));
        assertEquals("Success", call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "int g = helper(Extra.give());")).get("status"));
        assertEquals("Success", call(Map.of("op", "addImport", "code", "java.util.List")).get("status"));
        assertEquals("Success", call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "List<Integer> l = null;")).get("status"));
        String out = Files.readString(file);
        assertTrue(out.contains("import java.util.List;"), out);
        assertTrue(out.contains("int g = helper(Extra.give());"));
    }

    @Test
    void ambiguousAnchorsAreRejectedWithGuidance() {
        call(Map.of("op", "insertAtEnd", "method", "A#run", "code", "use(base);"));   // now two use(base);
        Map<String, Object> r = call(Map.of(
                "op", "deleteStatement", "method", "A#run", "anchor", "use(base)"));
        assertEquals("Error", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).contains("use a longer, unique anchor")
                        || String.valueOf(r.get("problems")).contains("use a longer, unique anchor"),
                r.toString());
    }

    @Test
    void coachedErrorsForNewMethodsAndEmptyBodies() throws IOException {
        // statement op into a method that doesn't exist → coached toward insertMember
        Map<String, Object> r1 = call(Map.of("op", "insertAtEnd", "method", "A#brandNew",
                "code", "int x = 1;"));
        assertEquals("Error", r1.get("status"));
        String msg1 = String.valueOf(r1.get("message")) + String.valueOf(r1.get("problems"));
        assertTrue(msg1.contains("create it FIRST with an insertMember"), r1.toString());

        // anchor into an EMPTY method → coached toward insertAtEnd
        call(Map.of("op", "insertMember", "type", "A", "code", "void fresh() {\n    }"));
        Map<String, Object> r2 = call(Map.of("op", "insertAfter", "method", "A#fresh",
                "anchor", "void fresh() {", "code", "int x = 1;"));
        assertEquals("Error", r2.get("status"));
        String msg2 = String.valueOf(r2.get("message")) + String.valueOf(r2.get("problems"));
        assertTrue(msg2.contains("EMPTY") && msg2.contains("insertAtEnd"), r2.toString());

        // bad anchor into a NON-empty method → the anchorable statements are listed
        Map<String, Object> r3 = call(Map.of("op", "insertAfter", "method", "A#run",
                "anchor", "void run() {", "code", "int x = 1;"));
        assertEquals("Error", r3.get("status"));
        String msg3 = String.valueOf(r3.get("message")) + String.valueOf(r3.get("problems"));
        assertTrue(msg3.contains("Anchorable statements") && msg3.contains("int base = compute();"),
                r3.toString());
    }

    @Test
    void insertMemberInfersTheTargetTypeWhenUnambiguous() throws IOException {
        // no 'type' → fall back to the class named in 'method', else the file's sole type
        Map<String, Object> r1 = call(Map.of("op", "insertMember", "method", "A#run",
                "code", "int viaMethod() { return 1; }"));
        assertEquals("Success", r1.get("status"), r1.toString());

        Map<String, Object> r2 = call(Map.of("op", "insertMember",
                "code", "int viaSoleType() { return 2; }"));
        assertEquals("Success", r2.get("status"), r2.toString());

        String out = Files.readString(file);
        assertTrue(out.contains("int viaMethod()") && out.contains("int viaSoleType()"), out);
    }

    @Test
    void multiStatementCodeBlocksAreAcceptedAsOneItem() throws IOException {
        // the model naturally writes several statements per item - the op takes them all
        Map<String, Object> r = call(Map.of(
                "op", "insertAtEnd", "method", "A#run",
                "code", "int a = base + 1;\nint b = a * 2;\nuse(b);"));
        assertEquals("Success", r.get("status"), r.toString());
        String out = Files.readString(file);
        assertTrue(out.contains("        int a = base + 1;\n        int b = a * 2;\n        use(b);"),
                out);
    }

    @Test
    void unknownSiblingSourceTypesAreAutoImported() throws IOException {
        // a type that lives ONLY in project source (another package/module), not on the compiled
        // classpath - auto-import must consult the SourceIndex, not just the classpath.
        Path other = dir.resolve("Widget.java");
        Files.writeString(other, "package com.other.parts;\n\npublic class Widget { public int size() { return 1; } }\n");
        Map<String, Object> r = call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "Widget w = new Widget();\nint s = w.size();"));
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(Files.readString(file).contains("import com.other.parts.Widget;"),
                Files.readString(file));
    }

    @Test
    void unknownTypesWithAUniqueClasspathHomeAreAutoImported() throws IOException {
        // the model writes types without imports; a unique candidate is imported FOR it
        Map<String, Object> r = call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "CopyOnWriteArrayList<Integer> l = new CopyOnWriteArrayList<>();"));
        assertEquals("Success", r.get("status"), r.toString());
        String out = Files.readString(file);
        assertTrue(out.contains("import java.util.concurrent.CopyOnWriteArrayList;"), out);

        // an ambiguous simple name is resolved by PACKAGE AFFINITY with the file's imports - this file now
        // imports java.util.concurrent.CopyOnWriteArrayList, so `List` is java.util.List (no guess without affinity)
        Map<String, Object> r2 = call(Map.of("file", file.toString(), "op", "insertAtEnd", "method", "A#run",
                "code", "List raw = null;"));
        assertEquals("Success", r2.get("status"), r2.toString());
        assertTrue(Files.readString(file).contains("import java.util.List;"), Files.readString(file));
    }

    @Test
    void statementShapedReplaceExpressionAnchorsAreCoercedOrCoached() throws IOException {
        // replaceExpression with a whole-statement anchor → behaves as replaceStatement
        Map<String, Object> r1 = call(Map.of(
                "op", "replaceExpression", "method", "A#run",
                "anchor", "use(base);", "code", "use(base + 41)"));
        assertEquals("Success", r1.get("status"), r1.toString());
        assertTrue(Files.readString(file).contains("use(base + 41);"));

        // several statements in one anchor → coached to split
        Map<String, Object> r2 = call(Map.of(
                "op", "replaceExpression", "method", "A#run",
                "anchor", "int base = compute(); use(base + 41);", "code", "use(1);"));
        assertEquals("Error", r2.get("status"));
        String msg = String.valueOf(r2.get("message")) + String.valueOf(r2.get("problems"));
        assertTrue(msg.contains("SEVERAL statements") && msg.contains("one item per"), r2.toString());
    }

    @Test
    void commentAnchorsAndFileOrMemberShapedCodeAreCoached() {
        // comments are trivia, not anchorable statements
        Map<String, Object> r1 = call(Map.of("op", "insertAfter", "method", "A#run",
                "anchor", "// the base value", "code", "int x = 1;"));
        assertEquals("Error", r1.get("status"));
        assertTrue(String.valueOf(r1.get("problems")).contains("anchor is a COMMENT"), r1.toString());

        // whole-file code in a statement op
        Map<String, Object> r2 = call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "package t;\n\nclass X { }"));
        assertEquals("Error", r2.get("status"));
        assertTrue(String.valueOf(r2.get("problems")).contains("WHOLE FILE"), r2.toString());

        // annotated member code in a statement op
        Map<String, Object> r3 = call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "@Deprecated\nvoid extra() { }"));
        assertEquals("Error", r3.get("status"));
        assertTrue(String.valueOf(r3.get("problems")).contains("insertMember"), r3.toString());
    }

    @Test
    void commentPrefixedAnchorsResolveToTheirStatement() throws IOException {
        // the author copies `// comment\nstatement;` pairs out of the template as anchors
        Files.writeString(file, """
                class A {
                    int compute() { return 21; }

                    void run() {
                        // the base value
                        int base = compute();
                        use(base); // consume it
                        /* done */
                        use(0);
                    }

                    void use(int x) {}
                }
                """);
        Map<String, Object> r1 = call(Map.of("op", "insertAfter", "method", "A#run",
                "anchor", "// the base value\nint base = compute();", "code", "int twice = base * 2;"));
        assertEquals("Success", r1.get("status"), r1.toString());
        assertTrue(Files.readString(file).contains("int base = compute();\n        int twice = base * 2;"),
                Files.readString(file));

        // a comment-only anchor names the statement it introduces …
        Map<String, Object> r2 = call(Map.of("op", "replaceStatement", "method", "A#run",
                "anchor", "/* done */", "code", "use(1);"));
        assertEquals("Success", r2.get("status"), r2.toString());
        assertTrue(Files.readString(file).contains("use(1);") && !Files.readString(file).contains("use(0);"));

        // … or, for a trailing comment, the statement sharing its line
        Map<String, Object> r3 = call(Map.of("op", "deleteStatement", "method", "A#run",
                "anchor", "// consume it", "code", ""));
        assertEquals("Success", r3.get("status"), r3.toString());
        assertTrue(!Files.readString(file).contains("use(base);"), Files.readString(file));

        // the comment's TEXT without slashes resolves the same way
        Map<String, Object> r3b = call(Map.of("op", "insertBefore", "method", "A#run",
                "anchor", "the base value", "code", "use(3);"));
        assertEquals("Success", r3b.get("status"), r3b.toString());
        String after = Files.readString(file);
        assertTrue(after.indexOf("use(3);") >= 0 && after.indexOf("use(3);") < after.indexOf("int base = compute();"), after);

        // a comment that is not in the body is still coached
        Map<String, Object> r4 = call(Map.of("op", "insertAfter", "method", "A#run",
                "anchor", "// nowhere", "code", "use(2);"));
        assertEquals("Error", r4.get("status"));
        assertTrue(String.valueOf(r4.get("problems")).contains("anchor is a COMMENT"), r4.toString());
    }

    @Test
    void commentMarkedStatementAnchorsResolveToTheStatement() throws IOException {
        // the author anchored on "// use(base); // Temporarily disabled" meaning the statement use(base);
        Map<String, Object> r = call(Map.of("op", "replaceStatement", "method", "A#run",
                "anchor", "// use(base); // Temporarily disabled due to compilation issues", "code", "use(base + 1);"));
        assertEquals("Success", r.get("status"), r.toString());
        String after = Files.readString(file);
        assertTrue(after.contains("use(base + 1);") && !after.contains("use(base);"), after);
        assertEquals("use(base);", TreeEditTool.commentContent("// use(base); // Temporarily disabled"));
        assertEquals("int x = 1;", TreeEditTool.commentContent("/* int x = 1; */"));
        assertEquals("a note", TreeEditTool.commentContent("// a note"));
    }

    @Test
    void commentShapedCodeIsCoachedAsACommentNotACutOff() {
        // "// stub X to throw" alone, and "// note; stmt();" on ONE line, were reported as INCOMPLETE
        Map<String, Object> r1 = call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "// Stub fetchNextPage to throw"));
        assertEquals("Error", r1.get("status"));
        String p1 = String.valueOf(r1.get("problems"));
        assertTrue(p1.contains("is a COMMENT") && !p1.contains("INCOMPLETE"), r1.toString());

        Map<String, Object> r2 = call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "// Verify the counter advances to the limit; use(base);"));
        assertEquals("Error", r2.get("status"));
        String p2 = String.valueOf(r2.get("problems"));
        assertTrue(p2.contains("OWN line") && !p2.contains("INCOMPLETE"), r2.toString());

        // a comment on its own line followed by the statement is fine
        Map<String, Object> r3 = call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "// consume once more\nuse(base);"));
        assertEquals("Success", r3.get("status"), r3.toString());
    }

    @Test
    void stripLeadingCommentsHelper() {
        assertEquals("int x = 1;", TreeEditTool.stripLeadingComments("// a\n/* b */ int x = 1;"));
        assertEquals("", TreeEditTool.stripLeadingComments("// only a note"));
        assertEquals("", TreeEditTool.stripLeadingComments("// note; code();"));
        assertEquals("code();", TreeEditTool.stripLeadingComments("code();"));
    }

    @Test
    void aDuplicateMemberIsRefused() throws IOException {
        // insertMember added a SECOND `void run()` (an empty duplicate of the existing method),
        // which cannot compile - the whole-program validation let it through
        String before = Files.readString(file);
        Map<String, Object> dup = call(Map.of("op", "insertMember", "type", "A",
                "code", "void run() { }"));
        assertEquals("Error", dup.get("status"), dup.toString());
        assertTrue(String.valueOf(dup.get("problems")).contains("already declares")
                        || String.valueOf(dup.get("message")).contains("already declares"), dup.toString());
        assertEquals(before, Files.readString(file), "nothing was written");

        // a different arity is a legitimate overload
        Map<String, Object> overload = call(Map.of("op", "insertMember", "type", "A",
                "code", "void run(int times) { }"));
        assertEquals("Success", overload.get("status"), overload.toString());
        // and a genuinely new name is fine
        assertEquals("Success", call(Map.of("op", "insertMember", "type", "A",
                "code", "void runTwice() { }")).get("status"));
    }

    @Test
    void theNewMethodAdviceUsesTheBareMethodName() {
        // the advice read "method=A#A.newOne()" - the type prefixed onto the raw name
        Map<String, Object> r = call(Map.of("op", "insertAtEnd", "method", "A.newOne()", "code", "int x = 1;"));
        assertEquals("Error", r.get("status"), r.toString());
        String msg = String.valueOf(r.get("problems")) + String.valueOf(r.get("message"));
        assertTrue(msg.contains("method=A#newOne."), msg);
        assertFalse(msg.contains("A#A."), msg);
        assertEquals("newOne", TreeEditTool.simpleMethodName("com.x.A#newOne(int, String)"));
        assertEquals("newOne", TreeEditTool.simpleMethodName("A.newOne()"));
        assertEquals("newOne", TreeEditTool.simpleMethodName("newOne"));
    }

    @Test
    void aFieldDeclarationInAStatementOpIsCoached() {
        // "private Instant startedAt;" was sent as a statement four times running
        Map<String, Object> r = call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "private int counter; private int other;"));
        assertEquals("Error", r.get("status"), r.toString());
        String msg = String.valueOf(r.get("problems")) + String.valueOf(r.get("message"));
        assertTrue(msg.contains("FIELD/member declaration") && msg.contains("drop the modifier"), msg);
        // a real local declaration still works
        assertEquals("Success", call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "int counter = 0;")).get("status"));
    }

    @Test
    void inventedOpNamesMapToTheRealOps() throws IOException {
        // the author's code was correct and only the op name was invented - insertStatement,
        // replace, insert - three attempts in a row, then it gave up
        assertEquals("Success", call(Map.of("op", "insertStatement", "method", "A#run",
                "code", "int extra = 1;")).get("status"));
        assertTrue(Files.readString(file).contains("int extra = 1;"));
        assertEquals("Success", call(Map.of("op", "replace", "method", "A#run",
                "anchor", "int extra = 1;", "code", "int extra = 2;")).get("status"));
        assertTrue(Files.readString(file).contains("int extra = 2;"));
        assertEquals("Success", call(Map.of("op", "remove", "method", "A#run",
                "anchor", "int extra = 2;", "code", "")).get("status"));
        assertFalse(Files.readString(file).contains("int extra"));
        // the mapping itself: an anchor decides between the positional forms
        assertEquals("insertAtEnd", TreeEditTool.canonicalOp("insert", null));
        assertEquals("insertAfter", TreeEditTool.canonicalOp("insert", "use(base);"));
        assertEquals("insertMember", TreeEditTool.canonicalOp("addMethod", null));
        assertEquals("addImport", TreeEditTool.canonicalOp("import", null));
        // a real op is untouched, and a genuinely unknown one still fails
        assertEquals("insertAtEnd", TreeEditTool.canonicalOp("insertAtEnd", null));
        assertEquals("frobnicate", TreeEditTool.canonicalOp("frobnicate", null));
    }

    @Test
    void replaceBodyRewritesAWholeMethodBody() throws IOException {
        // twenty drives showed the author cannot do anchored surgery on a cloned test, but it CAN write a
        // whole method body - replaceBody swaps every statement of an existing method for the given ones
        Map<String, Object> r = call(Map.of("op", "replaceBody", "method", "A#run",
                "code", "int twice = compute() * 2;\nuse(twice);"));
        assertEquals("Success", r.get("status"), r.toString());
        String out = Files.readString(file);
        assertTrue(out.contains("int twice = compute() * 2;") && out.contains("use(twice);"), out);
        assertFalse(out.contains("int base = compute();"), "the old body is gone:\n" + out);
        // a whole METHOD handed to replaceBody is reduced to its body
        r = call(Map.of("op", "replaceBody", "method", "A#run",
                "code", "@Test\nvoid run() {\n    use(3);\n}"));
        assertEquals("Success", r.get("status"), r.toString());
        out = Files.readString(file);
        assertTrue(out.contains("use(3);") && !out.contains("use(twice);"), out);
        assertEquals(1, out.split("void run\\(\\)").length - 1, "still exactly one run():\n" + out);
        // synonyms map onto it
        assertEquals("replaceBody", TreeEditTool.canonicalOp("rewriteBody", null));
        assertEquals("replaceBody", TreeEditTool.canonicalOp("replaceMethodBody", null));
    }

    @Test
    void replaceBodyReducesAWholeMethodEvenBehindCommentsWithBraces() {
        // the author sent the template's javadoc as // comments - containing {@code ...} braces -
        // then @Test, the signature and the body; the first '{' was inside a comment and the reduction failed
        String pasted = "// The {@code nextAlarm} after the first snooze must equal\n"
                + "// {@code lastAlarm + snoozeMinutes}. This test uses {@code snoozeMinutes = 9}\n"
                + "//\n"
                + "// <p>Under the old formula, {@code nextAlarm = lastAlarm + 5}.\n"
                + "@Test\n"
                + "void testX() {\n"
                + "    int twice = compute() * 2;\n"
                + "    use(twice);   // a trailing note with a { brace\n"
                + "}";
        String body = TreeEditor.bodyStatementsOf(pasted);
        assertTrue(body.startsWith("int twice = compute() * 2;"), body);
        assertTrue(body.contains("use(twice);"), body);
        assertFalse(body.contains("@Test") || body.contains("void testX"), body);
        Map<String, Object> r = call(Map.of("op", "replaceBody", "method", "A#run", "code", pasted));
        assertEquals("Success", r.get("status"), r.toString());
    }

    @Test
    void typographicPunctuationIsNormalisedAndImportCodeIsAnImport() throws IOException {
        // `illegal character '-'` x14 - dashes copied from the instructions into code; and an
        // import sent as an anchored statement
        Map<String, Object> r = call(Map.of("op", "insertAtEnd", "method", "A#run",
                "code", "int twice = base \u2212 1; // note \u2014 with a dash and \u201cquotes\u201d"));
        assertEquals("Success", r.get("status"), r.toString());
        String out = Files.readString(file);
        assertTrue(out.contains("int twice = base - 1;"), out);
        assertFalse(out.contains("\u2014") || out.contains("\u201c"), out);
        r = call(Map.of("op", "insertAfter", "method", "A#run", "anchor", "use(base);",
                "code", "import java.util.List;"));
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(Files.readString(file).startsWith("import java.util.List;") || Files.readString(file).contains("\nimport java.util.List;"),
                Files.readString(file));
        assertEquals("a - b", TreeEditTool.asciiPunctuation("a \u2013 b"));
    }

    @Test
    void deleteImportRemovesTheImportItsCodeNames() throws IOException {
        // deleteImport with the import line as code became an addImport (the rule for import code), so the unused
        // import stayed and the tool answered Success
        Files.writeString(file, "import java.util.List;\nimport java.util.Map;\nimport java.util.Set;\n\n"
                + "class A {\n    List<String> names;\n}\n");
        Map<String, Object> r = call(Map.of("op", "deleteImport", "code", "import java.util.Map;"));
        assertEquals("Success", r.get("status"), r.toString());
        r = call(Map.of("op", "removeImport", "code", "import java.util.Set;"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("import java.util.List;\n\nclass A {\n    List<String> names;\n}\n", Files.readString(file));
    }

    @Test
    void replaceMemberReachesAFieldByItsName() throws IOException {
        Files.writeString(file, "class A {\n    int count;   // stale\n\n    void m() {\n        count++;\n    }\n}\n");
        Map<String, Object> r = call(Map.of("op", "replaceMember", "method", "A#count",
                "code", "// how many were seen\nint count;"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    // how many were seen\n    int count;\n\n    void m() {\n        count++;\n    }\n}\n",
                Files.readString(file));
    }

    // a lexer fix left a scan method unused, and no op could delete a member
    @Test
    void deleteMemberRemovesAMemberWithItsComment() throws IOException {
        Files.writeString(file, "class A {\n    int count;   // how many\n\n    // the old way\n    void m() {\n    }\n\n"
                + "    void n() {\n    }\n}\n");
        Map<String, Object> r = call(Map.of("op", "deleteMember", "method", "A#m"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    int count;   // how many\n\n    void n() {\n    }\n}\n", Files.readString(file));
        r = call(Map.of("op", "deleteMember", "method", "A#count"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n\n    void n() {\n    }\n}\n", Files.readString(file));
        assertEquals("deleteMember", TreeEditTool.canonicalOp("removeMember", null));
    }

    // CodeGraph's fields and constructor came after its methods, fields the
    // reference keeps together had blank lines between them, and four class comments ran past 120 columns
    @Test
    void membersMoveAndSpaceAndATypesCommentIsReplaced() throws IOException {
        Files.writeString(file, "/** A long comment. */\nclass A {\n    record R(int x) {}\n\n    void run() {\n    }\n\n"
                + "    private int a;\n\n    private int b;\n\n    A() {\n    }\n}\n");
        Map<String, Object> r = call(Map.of("items", List.of(
                Map.of("op", "moveMember", "method", "A#run", "anchor", "A#A()"),
                Map.of("op", "spaceBefore", "method", "A#b", "code", "0"),
                Map.of("op", "spaceBefore", "method", "A#R", "code", "1"),
                Map.of("op", "replaceTypeComment", "type", "A", "code", "/**\n * A short\n * comment.\n */"))));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("/**\n * A short\n * comment.\n */\nclass A {\n\n    record R(int x) {}\n\n    private int a;\n    private int b;\n\n"
                + "    void run() {\n    }\n\n    A() {\n    }\n}\n", Files.readString(file));
    }

    @Test
    void aTypeIsMadePublicByItsModifiers() throws IOException {
        // a class the port called from another package had to become public
        Files.writeString(file, "/** A. */\nfinal class A {\n    static int x() {\n        return 1;\n    }\n}\n");
        Map<String, Object> r = call(Map.of("op", "typeModifiers", "type", "A", "code", "public final"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("/** A. */\npublic final class A {\n    static int x() {\n        return 1;\n    }\n}\n", Files.readString(file));
    }

    @Test
    void aConstructorIsReachedAsTypeHashType() throws IOException {
        // a stub constructor could not be replaced; only methods were found by name
        Files.writeString(file, "class A {\n    private final int x;\n\n    A(int x) {\n"
                + "        throw new UnsupportedOperationException();\n    }\n\n    A() {\n        this(0);\n    }\n}\n");
        Map<String, Object> r = call(Map.of("op", "replaceMember", "method", "A#A(int)",
                "code", "/** Holds x. */\nA(int x) {\n    this.x = x;\n}"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    private final int x;\n\n    /** Holds x. */\n    A(int x) {\n        this.x = x;\n    }\n\n"
                + "    A() {\n        this(0);\n    }\n}\n", Files.readString(file));
        Map<String, Object> ambiguous = call(Map.of("op", "replaceBody", "method", "A#A", "code", "this(1);"));
        assertEquals("Error", ambiguous.get("status"), "two constructors: name one with its parameter types");
    }

    @Test
    void aNestedTypeIsReachedByItsDottedNameAndAVarargsMethodByItsSpec() throws IOException {
        // "Outer.Exec#Exec(int)" and "Outer#run(String...)" were "method not found", and the
        // error advised that dotted form
        Files.writeString(file, "class Outer {\n    static final class Exec {\n        final int exit;\n\n"
                + "        Exec(int exit) {\n            throw new UnsupportedOperationException();\n        }\n    }\n\n"
                + "    void run(String... cmd) {\n        throw new UnsupportedOperationException();\n    }\n}\n");
        Map<String, Object> r = call(Map.of("items", List.of(
                Map.of("op", "replaceStatement", "method", "Outer.Exec#Exec(int)",
                        "anchor", "throw new UnsupportedOperationException();", "code", "this.exit = exit;"),
                Map.of("op", "replaceStatement", "method", "Outer#run(String...)",
                        "anchor", "throw new UnsupportedOperationException();", "code", "run();"))));
        assertEquals("Success", r.get("status"), r.toString());
        String after = Files.readString(file);
        assertTrue(after.contains("        Exec(int exit) {\n            this.exit = exit;\n        }"), after);
        assertTrue(after.contains("    void run(String... cmd) {\n        run();\n    }"), after);
        Map<String, Object> elsewhere = call(Map.of("op", "replaceBody", "method", "Other.Exec#Exec(int)",
                "code", "this.exit = 0;"));
        assertEquals("Error", elsewhere.get("status"), "no type Other encloses Exec");
    }

    @Test
    void oneActionMayCarryItemsThatRunInOrderAndAllOrNone() throws IOException {
        // a plan sent one TreeEdit action with "items" and no "op", and TreeEdit refused it
        Files.writeString(file, "package p;\n");
        Map<String, Object> r = call(Map.of("items", List.of(
                Map.of("op", "addImport", "code", "java.util.Map"),
                Map.of("op", "addType", "code", "final class A {\n}"),
                Map.of("op", "insertMember", "type", "A", "code", "Map<String, String> m;"))));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("package p;\n\nimport java.util.Map;\n\nfinal class A {\n\n    Map<String, String> m;\n}\n",
                Files.readString(file));
        String before = Files.readString(file);
        Map<String, Object> bad = call(Map.of("items", List.of(
                Map.of("op", "insertMember", "type", "A", "code", "int n;"),
                Map.of("op", "insertMember", "type", "A", "code", "int k = ;"))));
        assertEquals("Error", bad.get("status"), bad.toString());
        assertTrue(String.valueOf(bad.get("message")).contains("item 2"), bad.toString());
        assertEquals(before, Files.readString(file), "a failed item leaves the file as it was");
    }

    // the documentation said "apply ONE validated edit" and never named items, so the
    // planners sent one action per edit or an "edits" parameter TreeEdit does not have, and a critic refused items
    @Test
    void theDocumentationNamesItems() {
        String doc = new TreeEditTool().getDocumentation();
        assertTrue(doc.contains("\"items\""), doc);
        assertTrue(doc.contains("all or none"), doc);
    }

    @Test
    void spaceMembersIsAnOpOnAType() throws IOException {
        Files.writeString(file, "class A {\n    void m() {\n    }\n    void n() {\n    }\n}\n");
        Map<String, Object> r = call(Map.of("op", "spaceMembers", "type", "A"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    void m() {\n    }\n\n    void n() {\n    }\n}\n", Files.readString(file));
    }

    @Test
    void sortImportsIsAnOpWithoutCode() throws IOException {
        Files.writeString(file, "import java.util.Map;\nimport java.util.List;\n\nclass A {\n"
                + "    List<String> l;\n    Map<String, String> m;\n}\n");
        Map<String, Object> r = call(Map.of("op", "sortImports"));
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(Files.readString(file).startsWith("import java.util.List;\nimport java.util.Map;\n\nclass A {"),
                Files.readString(file));
        assertEquals("sortImports", TreeEditTool.canonicalOp("organizeImports", null));
    }

    @Test
    void typographicPunctuationInsideALiteralIsPartOfItsValueAndStays() {
        // an expected "\u2014 the types in scope:" became "- the types in scope:"
        assertEquals("assertTrue(p.contains(\"x \u2014 y\")); // a - b",
                TreeEditTool.asciiPunctuation("assertTrue(p.contains(\"x \u2014 y\")); // a \u2014 b"));
        assertEquals("char d = '\u2014'; int k = a - 1;", TreeEditTool.asciiPunctuation("char d = '\u2014'; int k = a \u2212 1;"));
        String block = "String s = \"\"\"\n    a \u2014 \"b\" \u201cc\u201d\n    \"\"\"; int k = a - 1;";
        assertEquals(block, TreeEditTool.asciiPunctuation(block.replace("a - 1", "a \u2013 1")));
        assertEquals("String e = \"q\\\" \u2014\"; // don't", TreeEditTool.asciiPunctuation("String e = \"q\\\" \u2014\"; // don\u2019t"));
        assertEquals("String t = \"typed \u2014 here\";", TreeEditTool.asciiPunctuation("String t = \u201ctyped \u2014 here\u201d;"),
                "a typographic quote in code opens a literal, and the literal keeps its content");
    }

    @Test
    void finalLocalsAreStatementsAndMixedMockitoStylesAreNormalised() throws IOException {
        // `final var …` was coached as a FIELD; `when(x).willThrow(y)` mixes styles
        Map<String, Object> r = call(Map.of("op", "insertAtEnd", "method", "A#run", "code", "final var twice = base * 2;"));
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(Files.readString(file).contains("final var twice = base * 2;"));
        assertEquals("given(it.next()).willThrow(new RuntimeException(\"boom\"));",
                TreeEditTool.mockitoStyle("when(it.next()).willThrow(new RuntimeException(\"boom\"));"));
        assertEquals("given(a.b()).willReturn(1);", TreeEditTool.mockitoStyle("given(a.b()).thenReturn(1);"));
        assertEquals("when(a.b()).thenReturn(1);", TreeEditTool.mockitoStyle("when(a.b()).thenReturn(1);"), "a pure classic chain is untouched");
        // a real field declaration is still coached
        Map<String, Object> f = call(Map.of("op", "insertAtEnd", "method", "A#run", "code", "private int counter;"));
        assertEquals("Error", f.get("status"), f.toString());
    }

    @Test
    void theMockitoStyleLeavesStringsAndCommentsAsWritten() {
        // a ported test passed Mockito chains to mockitoStyle as strings, the edit rewrote
        // those strings into the expected output, and the test compared the normal form with itself
        String quoted = "assertEquals(\"given(a).willThrow(e);\", mockitoStyle(\"when(a).willThrow(e);\")); "
                + "// `when(x).willThrow(y)` mixes styles";
        assertEquals(quoted, TreeEditTool.mockitoStyle(quoted));
        String mixed = "when(a.b(\"when(c).willReturn(2)\")).willReturn(1); /* when(d).willReturn(3) */";
        assertEquals("given(a.b(\"when(c).willReturn(2)\")).willReturn(1); /* when(d).willReturn(3) */",
                TreeEditTool.mockitoStyle(mixed));
        String block = "String s = \"\"\"\n        given(a).thenReturn(1);\n        \"\"\";";
        assertEquals(block, TreeEditTool.mockitoStyle(block));
    }

    @Test
    void aNearMissAnchorIsCoachedWithTheClosestStatements() throws IOException {
        // the wrong-arity anchor got the method's FIRST statements listed, not the target
        Files.writeString(file, """
                class A {
                    void run() {
                        int a = 1;
                        int b = 2;
                        try {
                            int c = 3;
                        } catch (Exception e) {
                            if (mode == LOUD) {
                                speaker.setVolume(level, channel);
                            }
                        }
                    }
                }
                """);
        Map<String, Object> r = call(Map.of("op", "replaceStatement", "method", "A#run",
                "anchor", "speaker.setVolume(level);", "code", "speaker.setVolume(maxLevel, channel);"));
        assertEquals("Error", r.get("status"), r.toString());
        String msg = String.valueOf(r.get("problems"));
        assertTrue(msg.contains("CLOSEST existing statements"), msg);
        // the nested LEAF is listed first, not the enclosing try/if blocks that merely contain its words
        int leaf = msg.indexOf("speaker.setVolume(level, channel);");
        int tryBlock = msg.indexOf("- try {");
        assertTrue(leaf >= 0 && (tryBlock < 0 || leaf < tryBlock), msg);
    }

    @Test
    void aPlaceholderAnchorRefusalCanListTheMethodsLeafStatements() {
        // the planner repeated "<the quoted statement…>" as the anchor - it never saw the options
        List<String> stmts = TreeEditTool.anchorableStatements(file, "A#run", 10);
        assertEquals(List.of("int base = compute();", "use(base);"), stmts);
        assertTrue(TreeEditTool.anchorableStatements(file, "A#nope", 10).isEmpty());
    }

    @Test
    void qualifiedMethodSpecsAreAccepted() throws IOException {
        // a fully-qualified spec failed with "method not found"
        assertEquals("Playlist#nextTrack",
                TreeEditTool.simpleSpec("com.example.media.player.queue.Playlist#nextTrack"));
        // the dotted Type.method form the planner writes most often
        assertEquals("Playlist#nextTrack",
                TreeEditTool.simpleSpec("Playlist.nextTrack"));
        assertEquals("Playlist#nextTrack",
                TreeEditTool.simpleSpec("com.example.media.Playlist.nextTrack"));
        assertEquals("Outer.Inner", TreeEditTool.simpleSpec("com.acme.Outer.Inner"), "a nested TYPE is not a method");
        assertEquals("Outer.Inner#m", TreeEditTool.simpleSpec("com.acme.Outer.Inner#m"));
        assertEquals("A", TreeEditTool.simpleSpec("A"));
        Map<String, Object> r = call(Map.of("op", "insertAtEnd", "method", "t.pkg.A#run", "code", "int extra = 1;"));
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(Files.readString(file).contains("int extra = 1;"));
    }

    @Test
    void malformedSnippetsFailWithPositions() throws IOException {
        String before = Files.readString(file);
        Map<String, Object> r = call(Map.of("op", "insertAtEnd", "method", "A#run", "code", "int x = ;"));
        assertEquals("Error", r.get("status"));
        assertEquals(before, Files.readString(file));
    }

    @Test
    void ambiguousTypeIsImportedByPackageAffinity() {
        // `Season` had several repo-wide candidates; the test file already imports com.acme.garden.*
        ParseTree tree = ParseTree.parse("""
                package t;
                import com.acme.garden.spi.PlantCatalog;
                import com.acme.garden.sensors.Reading;
                import java.time.Instant;
                class GreenhouseTest { }
                """);
        String pick = TreeEditTool.byPackageAffinity(
                List.of("com.example.weather.Season", "com.acme.garden.Season", "org.other.Season"), tree.imports());
        assertEquals("com.acme.garden.Season", pick);
        // no affinity at all → still ambiguous
        assertEquals(null, TreeEditTool.byPackageAffinity(List.of("a.b.X", "c.d.X"), tree.imports()));
    }

    @Test
    void aLambdaBodyIsNotSeveralStatements() {
        // the anchor `assertThrows(RuntimeException.class, () -> { subject.run(); })` was
        // misdiagnosed as "SEVERAL statements" because of the ';' inside the lambda
        assertTrue(!TreeEditTool.hasTopLevelSemicolon(
                "assertThrows(RuntimeException.class, () -> { subject.process(queue, batch, items -> {}); });"));
        assertTrue(TreeEditTool.hasTopLevelSemicolon("a = 1; b = 2;"));
        assertTrue(!TreeEditTool.hasTopLevelSemicolon("log.info(\"a; b\");"));
    }

    @Test
    void theMembersJavaTypeIsNotTheTargetTypeAndAnUnambiguousFileSuppliesIt() throws IOException {
        // type=ConcurrentHashMap for an insertMember into the file's only class
        Path f = dir.resolve("Handler.java");
        Files.writeString(f, "package t;\n\nclass Handler {\n    void m() { }\n}\n");
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        TreeEditTool.Outcome out = TreeEditTool.apply(Files.readString(f), ClasspathIndex.system(), g.sources(),
                "insertMember", null, "ConcurrentHashMap",
                null, "private static final java.util.Map<String, String> cache = new java.util.HashMap<>();");
        assertTrue(out.ok(), out.problems().toString());
        assertTrue(out.rendered().contains("cache = new java.util.HashMap<>();"), out.rendered());
    }

    @Test
    void anAnchorInsideNestedStatementsNamesTheInnermostOne() throws IOException {
        // "3 statements match" for a statement whose if and try contain its text
        Path f = dir.resolve("P.java");
        Files.writeString(f, """
                package t;

                class P {
                    boolean m(int a, int b) {
                        try {
                            if (a > 0) {
                                boolean isCast = a > b ||
                                    b == 0;
                                return isCast;
                            }
                        } catch (RuntimeException e) {
                            return false;
                        }
                        return true;
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        TreeEditTool.Outcome out = TreeEditTool.apply(Files.readString(f), ClasspathIndex.system(),
                g.sources(), "replaceStatement", "P#m", null, "boolean isCast = a > b ||",
                "boolean isCast = a > b || b == 0 || b == 1;");
        assertTrue(out.ok(), out.problems().toString());
        assertTrue(out.rendered().contains("boolean isCast = a > b || b == 0 || b == 1;"), out.rendered());
        assertTrue(out.rendered().contains("if (a > 0) {") && out.rendered().contains("try {"), "the enclosing statements stay");
    }

    @Test
    void theToolsOwnClassesAreNeverImportedIntoAnotherProject() throws IOException {
        // a stub naming a same-package class not yet written got the tool's own
        // hermit.analysis.sym.SymbolResolver imported, and the build failed
        Path f = dir.resolve("Index.java");
        Files.writeString(f, "package p.sym;\n");
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        TreeEditTool.Outcome out = TreeEditTool.apply(Files.readString(f), ClasspathIndex.system(),
                g.sources(), "addType", null, null, null, "public final class Index { SymbolResolver resolverFor() { return null; } }");
        assertFalse(out.ok() && out.rendered().contains("import hermit."), out.rendered());
        assertTrue(TreeEditTool.isToolOwnClass("hermit.analysis.sym.SymbolResolver", g.sources()));
        assertFalse(TreeEditTool.isToolOwnClass("java.util.List", g.sources()));
    }

    @Test
    void anInstanceMethodAddedToAClassThatIsNeverInstantiatedIsRefused() throws IOException {
        // a helper lost its 'static'; the next item's call to it was refused three
        // times, and that item could not fix the declaration an earlier item had committed
        Path f = dir.resolve("Parser.java");
        Files.writeString(f, """
                package t;

                final class Parser {
                    private Parser() {}

                    static int parse(String s) { return s.length(); }

                    static class Cursor {
                        int pos;
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        TreeEditTool.Outcome instance = TreeEditTool.apply(Files.readString(f), ClasspathIndex.system(),
                g.sources(), "insertMember", null, "Parser", null, "void helper(String s) { }");
        assertFalse(instance.ok());
        assertTrue(instance.problems().toString().contains("the instance method 'helper' could never be called — declare it static"),
                instance.problems().toString());
        TreeEditTool.Outcome statik = TreeEditTool.apply(Files.readString(f), ClasspathIndex.system(),
                g.sources(), "insertMember", null, "Parser", null, "private static void helper(String s) { }");
        assertTrue(statik.ok(), statik.problems().toString());
        TreeEditTool.Outcome nested = TreeEditTool.apply(Files.readString(f), ClasspathIndex.system(),
                g.sources(), "insertMember", null, "Cursor", null, "int peek() { return pos; }");
        assertTrue(nested.ok(), "a nested class with a default constructor takes instance methods: " + nested.problems());
    }

    @Test
    void anAnchorFoundInExactlyOneMethodNamesTheMethod() throws IOException {
        // replaceStatement without `method` for an anchor that lives in one method only
        Path f = dir.resolve("Handler.java");
        Files.writeString(f, """
                package t;

                class Handler {
                    Object infoForFile(long id) {
                        final var contents = store.getFull(id);
                        return contents;
                    }

                    Object other(long id) {
                        final var meta = store.meta(id);
                        return meta;
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        TreeEditTool.Outcome out = TreeEditTool.apply(Files.readString(f), ClasspathIndex.system(), g.sources(),
                "replaceStatement", null, null, "final var contents = store.getFull(id);", "final var contents = String.valueOf(id);");
        assertTrue(out.ok(), out.problems().toString());
        assertTrue(out.rendered().contains("final var contents = String.valueOf(id);"), out.rendered());
        // an anchor present in several methods still needs `method`
        TreeEditTool.Outcome ambiguous = TreeEditTool.apply(Files.readString(f), ClasspathIndex.system(), g.sources(),
                "deleteStatement", null, null, "return contents;", "");
        assertTrue(ambiguous.ok() || ambiguous.problems().toString().contains("method"), ambiguous.problems().toString());
        TreeEditTool.Outcome twice = TreeEditTool.apply(
                Files.readString(f).replace("return meta;", "return contents;"), ClasspathIndex.system(), g.sources(),
                "deleteStatement", null, null, "return contents;", "");
        assertFalse(twice.ok());
        assertTrue(twice.problems().toString().contains("several methods"), twice.problems().toString());
    }

    @Test
    void aRejectionForUnknownNamesCarriesTheMethodAsItStands() throws IOException {
        // every fix plan used names the class does not have - it was never read
        Path f = dir.resolve("ReportBuilder.java");
        Files.writeString(f, """
                package t;

                class ReportBuilder {
                    private final Archive pageArchive = new Archive();

                    java.util.Optional<String> summaryFor(long reportId) {
                        final var pages = pageArchive.load(reportId);
                        final var digest = checksum(pages);
                        return java.util.Optional.of(digest);
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        TreeEditTool.Outcome out = TreeEditTool.apply(Files.readString(f), ClasspathIndex.system(), g.sources(),
                "insertBefore", "ReportBuilder#summaryFor", null, "final var pages = pageArchive.load(reportId);",
                "if (cachedSummary != null && cachedCount == expectedPageCount) { return cachedSummary; }");
        assertFalse(out.ok());
        String all = String.join(" | ", out.problems());
        assertTrue(all.contains("unknown variable 'cachedSummary'"), all);
        assertTrue(all.contains("GROUNDING"), all);
        assertTrue(all.contains("summaryFor(long reportId)"), all);
        assertTrue(all.contains("final var digest = checksum(pages);"), all);
        assertTrue(all.contains("Fields of ReportBuilder: private final Archive pageArchive"), all);
    }

    @Test
    void aDeclarationHandedOverAsTheTypeIsTheCodeAndAnEditCanBeReverted() throws IOException {
        // op=addType with the whole class in `type`; the pre-edit content is restorable
        assertTrue(TreeEditTool.looksLikeDeclaration("private static final class CacheEntry { final String hash; }"));
        assertTrue(TreeEditTool.looksLikeDeclaration("record Pair(int a, int b) {}"));
        assertFalse(TreeEditTool.looksLikeDeclaration("CatalogSearch"));
        assertFalse(TreeEditTool.looksLikeDeclaration("java.util.concurrent.ConcurrentHashMap"));
        Path f = dir.resolve("Handler.java");
        String original = "package t;\n\nclass Handler {\n    void m() { }\n}\n";
        Files.writeString(f, original);
        TreeEditTool tool = new TreeEditTool();
        Map<String, Object> params = new HashMap<>();
        params.put("file", f.toString());
        params.put("op", "addType");
        params.put("type", "class CacheEntry { final String hash = \"x\"; }");
        params.put("projectDir", dir.toString());
        Map<String, Object> res = tool.execute(params);
        assertEquals("Success", res.get("status"), res.toString());
        assertTrue(Files.readString(f).contains("class CacheEntry"), Files.readString(f));
        assertTrue(TreeEditTool.revertLastEdit(f));
        assertEquals(original, Files.readString(f));
        assertFalse(TreeEditTool.revertLastEdit(f), "nothing left to revert");
    }

    @Test
    void aParameterListInTheMethodSpecPicksTheOverload() throws IOException {
        Files.writeString(file, """
                class A {
                    int parse(String s) { return parse(s, 0); }

                    int parse(String s, int from) { return from; }
                }
                """);
        Map<String, Object> r = call(Map.of(
                "op", "replaceBody", "method", "A#parse(String, int)",
                "code", "return from + 1;"));
        assertEquals("Success", r.get("status"), r.toString());
        String after = Files.readString(file);
        assertTrue(after.contains("int parse(String s) { return parse(s, 0); }"), after);
        assertTrue(after.contains("return from + 1;"), after);
        Map<String, Object> named = call(Map.of(
                "op", "replaceBody", "method", "A#parse(java.lang.String s)",
                "code", "return parse(s, 2);"));
        assertEquals("Success", named.get("status"), named.toString());
        assertTrue(Files.readString(file).contains("return parse(s, 2);"));
        Map<String, Object> missing = call(Map.of(
                "op", "replaceBody", "method", "A#parse(int)", "code", "return 0;"));
        assertEquals("Error", missing.get("status"), "no overload takes (int)");
    }

    @Test
    void parameterTypesAreReducedToSimpleNames() {
        assertEquals(List.of("Cursor", "Node"), TreeEditTool.parameterTypes("Cursor, Node"));
        assertEquals(List.of("Map", "int[]", "Node..."),
                TreeEditTool.parameterTypes("java.util.Map<String, List<Node>> m, final int[] xs, Node... rest"));
        assertEquals(List.of(), TreeEditTool.parameterTypes(" "));
    }

    @Test
    void aReplaceBodyWithoutMethodTakesItsTargetFromTheDeclarationInTheCode() throws IOException {
        Files.writeString(file, """
                class A {
                    int parse(String s) { return parse(s, 0); }

                    int parse(String s, int from) { return from; }
                }
                """);
        Map<String, Object> r = call(Map.of(
                "op", "replaceBody",
                "code", "// a comment line first\nprivate int parse(String s, int from) {\n    return from + 2;\n}"));
        assertEquals("Success", r.get("status"), r.toString());
        String after = Files.readString(file);
        assertTrue(after.contains("int parse(String s) { return parse(s, 0); }"), after);
        assertTrue(after.contains("return from + 2;"), after);
        Map<String, Object> bodyOnly = call(Map.of("op", "replaceBody", "code", "return 1;"));
        assertEquals("Error", bodyOnly.get("status"), "a bare body still needs `method`");
    }

    @Test
    void aBodyHandedInWithItsOwnBracesIsNotNestedInAnotherBlock() throws IOException {
        Map<String, Object> r = call(Map.of(
                "op", "replaceBody", "method", "A#compute",
                "code", "{\n    int x = 20;\n    return x + 1;\n}"));
        assertEquals("Success", r.get("status"), r.toString());
        String after = Files.readString(file);
        assertTrue(after.contains("return x + 1;"), after);
        assertFalse(after.replaceAll("\\s+", "").contains("{{"), "no block inside the body's block:\n" + after);
    }

    @Test
    void aBodyWrittenAtColumnZeroIsIndentedAsAWhole() throws IOException {
        Map<String, Object> r = call(Map.of(
                "op", "replaceBody", "method", "A#compute",
                "code", "int x = 20;\nif (x > 1) {\n    return x;\n}\nreturn 1;"));
        assertEquals("Success", r.get("status"), r.toString());
        String after = Files.readString(file);
        assertTrue(after.contains("        if (x > 1) {\n            return x;\n        }\n        return 1;"), after);
        Map<String, Object> deep = call(Map.of(
                "op", "replaceBody", "method", "A#compute",
                "code", "int y = 2;\n                if (y > 1) {\n                    return y;\n                }\n                return 0;"));
        assertEquals("Success", deep.get("status"), deep.toString());
        assertTrue(Files.readString(file).contains("        if (y > 1) {\n            return y;\n        }\n        return 0;"),
                Files.readString(file));
    }

    @Test
    void insertedStatementsWrittenAtColumnZeroAreIndentedAsAWhole() throws IOException {
        Map<String, Object> r = call(Map.of(
                "op", "insertAfter", "method", "A#run", "anchor", "int base = compute();",
                "code", "if (base > 1) {\n    use(base);\n}"));
        assertEquals("Success", r.get("status"), r.toString());
        String after = Files.readString(file);
        assertTrue(after.contains("        int base = compute();\n        if (base > 1) {\n            use(base);\n        }"), after);
        Map<String, Object> replaced = call(Map.of(
                "op", "replaceStatement", "method", "A#run", "anchor", "use(base);",
                "code", "if (base > 2) {\n    use(base + 1);\n}"));
        assertEquals("Success", replaced.get("status"), replaced.toString());
        assertTrue(Files.readString(file).contains("            if (base > 2) {\n                use(base + 1);\n            }"),
                Files.readString(file));
    }

    @Test
    void anExplanationAfterAnInsertedMemberIsDropped() throws IOException {
        Map<String, Object> r = call(Map.of(
                "op", "insertMember", "type", "A",
                "code", "int twice(int x) {\n    return x * 2;\n}\n\ngiven the test, this doubles the value."));
        assertEquals("Success", r.get("status"), r.toString());
        String after = Files.readString(file);
        assertTrue(after.contains("return x * 2;") && !after.contains("given the test"), after);
        Map<String, Object> two = call(Map.of(
                "op", "insertMember", "type", "A",
                "code", "int one() { return 1; }\nint two() { return 2; }"));
        // several complete members in one item are inserted together
        assertEquals("Success", two.get("status"), two.toString());
        String both = Files.readString(file);
        assertTrue(both.contains("int one() { return 1; }") && both.contains("int two() { return 2; }"), both);
    }

    // a static initializer has no name, so a port could insert one but never move it into place or anchor a
    // member on it; "Type#static" names it
    @Test
    void aStaticInitializerIsNamedTypeHashStatic() throws IOException {
        Map<String, Object> r = call(Map.of("op", "insertMember", "type", "A",
                "code", "static final java.util.Set<String> NAMES = new java.util.HashSet<>();\n\n"
                        + "static {\n    NAMES.add(\"a\");\n}"));
        assertEquals("Success", r.get("status"), r.toString());
        Map<String, Object> moved = call(Map.of("op", "moveMember", "method", "A#static", "anchor", "A#run"));
        assertEquals("Success", moved.get("status"), moved.toString());
        Map<String, Object> field = call(Map.of("op", "moveMember", "method", "A#NAMES", "anchor", "A#static"));
        assertEquals("Success", field.get("status"), field.toString());
        String after = Files.readString(file);
        int names = after.indexOf("NAMES = new"), init = after.indexOf("static {"), run = after.indexOf("void run()");
        assertTrue(names < init && init < run, after);
        Map<String, Object> replaced = call(Map.of("op", "replaceMember", "method", "A#static",
                "code", "static {\n    NAMES.add(\"b\");\n}"));
        assertEquals("Success", replaced.get("status"), replaced.toString());
        String last = Files.readString(file);
        assertTrue(last.contains("NAMES.add(\"b\");") && !last.contains("NAMES.add(\"a\");"), last);
    }

    @Test
    void aClassThatIsNotPublicIsNeverAnImportCandidate() {
        // the project's records named Node, and java.util.stream.Node, a package-private
        // JDK class, was imported for it beside the project's own Node; the build failed
        ClasspathIndex index = ClasspathIndex.system();
        Assumptions.assumeTrue(index.fqnsOf("Node").contains("java.util.stream.Node"),
                "this JDK has no java.util.stream.Node");
        TreeEditor editor = TreeEditor.of(
                ParseTree.parse("package p;\n\nimport java.util.List;\nimport java.util.Set;\n\nclass A {\n}\n"));
        List<String> left = TreeEditTool.autoImport(editor, index, null, List.of("unknown type 'Node'"));
        assertFalse(editor.render().contains("java.util.stream.Node"), editor.render());
        assertTrue(left == null || left.stream().noneMatch(p -> p.contains("java.util.stream.Node")), String.valueOf(left));
    }

    // an action carried "op": "addImport" next to its items; TreeEdit ran that one import and
    // left the items out without a word, and the plan's class was never written. Items run whenever there are some, and
    // the action's own op is the default of an item without one.
    @Test
    void anActionsOwnOpDoesNotHideItsItems() throws IOException {
        Files.writeString(file, "package p;\n");
        Map<String, Object> r = call(Map.of("op", "addImport", "code", "java.util.Map", "items", List.of(
                Map.of("code", "java.util.List"),
                Map.of("op", "addType", "code", "final class A {\n}"))));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals(2, r.get("items"), r.toString());
        assertEquals("package p;\n\nimport java.util.List;\n\nfinal class A {\n}\n", Files.readString(file));
    }

    // fields moved one after another before a constructor, the file written and read again
    // after each, left their end-of-line comments on the last field moved
    @Test
    void fieldsMovedBeforeAMemberKeepTheirEndOfLineComments() throws IOException {
        Files.writeString(file, "package p;\n\npublic final class A {\n    public A(int a, int b) {\n    }\n\n    void m() {\n    }\n}\n");
        for (String[] f : new String[][] {{"private static final int M = 1;", "M"}, {"private final int a;   // the first", "a"},
                {"private final int b;    // the second", "b"}, {"private final int c;", "c"}}) {
            assertEquals("Success", call(Map.of("op", "insertMember", "type", "A", "code", f[0])).get("status"), f[1]);
            assertEquals("Success", call(Map.of("op", "moveMember", "method", "A#" + f[1], "anchor", "A#A")).get("status"), f[1]);
        }
        String out = Files.readString(file);
        assertTrue(out.contains("    private final int a;   // the first\n    private final int b;    // the second\n"
                + "    private final int c;\n"), out);
    }

    // the checkpoint's field lastDecision sits between two methods, next to its method
    // lastDecision(), and an inserted field lands with the other fields; "Type#lastDecision" moved the method, so the
    // code given says the field is meant, as it does for replaceMember
    @Test
    void moveMemberReachesAFieldThatSharesItsNameWithAMethod() throws IOException {
        Files.writeString(file, "class A {\n    private int count;\n    private String last;\n\n    void run() {\n    }\n\n"
                + "    String last() {\n        return last;\n    }\n}\n");
        Map<String, Object> r = call(Map.of("op", "moveMember", "method", "A#last", "code", "private String last;",
                "anchor", "A#last()"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    private int count;\n\n    void run() {\n    }\n\n    private String last;\n\n"
                + "    String last() {\n        return last;\n    }\n}\n", Files.readString(file));
    }

    // statements inserted together came out with every blank line between them dropped
    @Test
    void insertedStatementsKeepTheBlankLinesBetweenThem() throws IOException {
        Files.writeString(file, "class A {\n    void m() {\n        int a = 1;\n        a++;\n    }\n}\n");
        Map<String, Object> r = call(Map.of("op", "insertBefore", "method", "A#m", "anchor", "a++;",
                "code", "a += 2;\n\n// the second step\n\na += 3;\n\na += 4;"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    void m() {\n        int a = 1;\n        a += 2;\n\n        // the second step\n\n"
                + "        a += 3;\n\n        a += 4;\n        a++;\n    }\n}\n", Files.readString(file));
    }

    // spaceBefore takes the blank-line count as its code, so a field that shares its name with a
    // method could not be named there; "Type#name;" names the field in every op
    @Test
    void aSpecEndingInASemicolonNamesTheField() throws IOException {
        Files.writeString(file, "class A {\n    private int count;\n    private String last;\n\n    String last() {\n"
                + "        return last;\n    }\n}\n");
        Map<String, Object> r = call(Map.of("items", List.of(
                Map.of("op", "spaceBefore", "method", "A#last;", "code", "1"),
                Map.of("op", "memberModifiers", "method", "A#last;", "code", "private final"),
                Map.of("op", "memberModifiers", "method", "A#last", "code", "public"))));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    private int count;\n\n    private final String last;\n\n    public String last() {\n"
                + "        return last;\n    }\n}\n", Files.readString(file));
    }

    // the agent loop sits in another package than the helpers it calls, and no op could make a
    // member public
    @Test
    void memberModifiersSetsTheModifiersOfAMethodAFieldAndAConstructor() throws IOException {
        Files.writeString(file, "class A {\n    static final int LIMIT = 3;\n    int count;\n\n    A() {\n    }\n\n"
                + "    /** Doc. */\n    @Deprecated\n    static String name(int x) {\n        return \"\";\n    }\n\n"
                + "    String other() {\n        return name(LIMIT);\n    }\n}\n");
        Map<String, Object> r = call(Map.of("items", List.of(
                Map.of("op", "memberModifiers", "method", "A#name", "code", "public static"),
                Map.of("op", "memberModifiers", "method", "A#other", "code", "public"),
                Map.of("op", "memberModifiers", "method", "A#LIMIT", "code", "public static final"),
                Map.of("op", "memberModifiers", "method", "A#count", "code", "private"),
                Map.of("op", "memberModifiers", "method", "A#A()", "code", "public"))));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    public static final int LIMIT = 3;\n    private int count;\n\n    public A() {\n    }\n\n"
                + "    /** Doc. */\n    @Deprecated\n    public static String name(int x) {\n        return \"\";\n    }\n\n"
                + "    public String other() {\n        return name(LIMIT);\n    }\n}\n", Files.readString(file));
    }

    // a section's heading comment, a blank line and the member's javadoc (or the member itself)
    // came out without the blank line once the member was moved
    @Test
    void aMovedMemberKeepsTheBlankLinesAfterItsComments() throws IOException {
        Files.writeString(file, "class A {\n    void a() {\n    }\n\n    void b() {\n    }\n\n    // ---- a section ----\n\n"
                + "    /** Doc. */\n    void m() {\n    }\n\n    // ---- another ----\n\n    void n() {\n    }\n}\n");
        Map<String, Object> r = call(Map.of("items", List.of(Map.of("op", "moveMember", "method", "A#m", "anchor", "A#b"),
                Map.of("op", "moveMember", "method", "A#n", "anchor", "A#b"))));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    void a() {\n    }\n\n    // ---- a section ----\n\n    /** Doc. */\n    void m() {\n    }\n\n"
                + "    // ---- another ----\n\n    void n() {\n    }\n\n    void b() {\n    }\n}\n", Files.readString(file));
    }

    // two fields replaced one after the other, each with its end-of-line comment, came out on
    // one line, the first comment lost
    @Test
    void fieldsReplacedOneAfterAnotherKeepTheirLinesAndComments() throws IOException {
        Files.writeString(file, "package p;\n\npublic final class A {\n    private final int a;\n    private final int b;   // b's\n"
                + "    private final int c;\n\n    public A() {\n        a = 1;\n        b = 2;\n        c = 3;\n    }\n}\n");
        assertEquals("Success", call(Map.of("op", "replaceMember", "method", "A#a", "code", "private final int a;   // the first")).get("status"));
        assertEquals("Success", call(Map.of("op", "replaceMember", "method", "A#b", "code", "private final int b;    // the second")).get("status"));
        String out = Files.readString(file);
        assertTrue(out.contains("    private final int a;   // the first\n    private final int b;    // the second\n"
                + "    private final int c;\n"), out);
    }

    // the follower's field helperOff could not get its comment back, since "Type#helperOff" named the method of that
    // name; the code given says which of the two is meant
    @Test
    void replaceMemberReachesAFieldThatSharesItsNameWithAMethod() throws IOException {
        Files.writeString(file, "class A {\n    private static String off;\n\n    static String off() {\n"
                + "        return off;\n    }\n}\n");
        Map<String, Object> r = call(Map.of("op", "replaceMember", "method", "A#off",
                "code", "private static String off;   // why it is off"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    private static String off;   // why it is off\n\n    static String off() {\n"
                + "        return off;\n    }\n}\n", Files.readString(file));
        r = call(Map.of("op", "replaceMember", "method", "A#off",
                "code", "static String off() {\n    return null;\n}"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    private static String off;   // why it is off\n\n    static String off() {\n"
                + "        return null;\n    }\n}\n", Files.readString(file));
    }

    // every member of the edit tool sat at column 0, and no op could indent them
    @Test
    void indentMembersIndentsATypesBody() throws IOException {
        Files.writeString(file, "class A {\nint a;\n\nvoid m() {\n    a++;\n}\n}\n");
        Map<String, Object> r = call(Map.of("op", "indentMembers", "type", "A"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    int a;\n\n    void m() {\n        a++;\n    }\n}\n", Files.readString(file));
    }

    // the case of a new op could not be added to the edit tool's switch: an anchor on a case named the whole switch,
    // and the code was parsed as statements
    @Test
    void switchCasesAreInsertedAndReplacedNextToACase() throws IOException {
        Files.writeString(file, "class A {\n    int m(String op) {\n        switch (op) {\n"
                + "            case \"a\" -> m(\"b\");\n            case \"b\" -> m(\"c\");\n"
                + "            default -> m(\"d\");\n        }\n        return 0;\n    }\n}\n");
        Map<String, Object> r = call(Map.of("items", List.of(
                Map.of("op", "insertAfter", "method", "A#m", "anchor", "case \"a\" -> m(\"b\");",
                        "code", "case \"x\" -> m(\"y\");\ncase \"y\" -> m(\"z\");"),
                Map.of("op", "replaceStatement", "method", "A#m", "anchor", "case \"b\" -> m(\"c\");",
                        "code", "case \"b\" -> m(\"bb\");"),
                Map.of("op", "insertBefore", "method", "A#m", "anchor", "default -> m(\"d\");",
                        "code", "case \"c\" -> m(\"cc\");"))));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    int m(String op) {\n        switch (op) {\n"
                + "            case \"a\" -> m(\"b\");\n            case \"x\" -> m(\"y\");\n"
                + "            case \"y\" -> m(\"z\");\n            case \"b\" -> m(\"bb\");\n"
                + "            case \"c\" -> m(\"cc\");\n            default -> m(\"d\");\n        }\n"
                + "        return 0;\n    }\n}\n", Files.readString(file));
    }

    // a stray comment before the edit tool's closing brace could not be taken away
    @Test
    void aCommentBeforeTheClosingBraceGoesWithAMemberAppendedAfterIt() throws IOException {
        Files.writeString(file, "class A {\n    void m() {\n    }\n\n        // stray\n}\n");
        Map<String, Object> r = call(Map.of("items", List.of(
                Map.of("op", "insertMember", "type", "A", "code", "void x() {\n}"),
                Map.of("op", "deleteMember", "method", "A#x"))));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("class A {\n    void m() {\n    }\n}\n", Files.readString(file));
    }

    // a call written over two lines replaced a case's expression, and its second line kept the column the code gave
    // it (8) instead of following the line the expression stands on
    @Test
    void anExpressionOverSeveralLinesFollowsTheIndentOfItsLine() throws IOException {
        Files.writeString(file, "class A {\n    int m(String op) {\n        switch (op) {\n"
                + "            case \"a\" -> f(1, 2);\n            default -> f(0, 0);\n        }\n"
                + "        Runnable r = null;\n        return 0;\n    }\n\n"
                + "    int f(int a, int b) {\n        return a + b;\n    }\n}\n");
        Map<String, Object> r = call(Map.of("items", List.of(
                Map.of("op", "replaceExpression", "method", "A#m", "anchor", "f(1, 2)", "code", "f(1 + 1,\n        2)"),
                Map.of("op", "replaceExpression", "method", "A#m", "anchor", "null",
                        "code", "() -> {\n    f(3, 4);\n}"))));
        assertEquals("Success", r.get("status"), r.toString());
        String out = Files.readString(file);
        assertTrue(out.contains("            case \"a\" -> f(1 + 1,\n                    2);\n"), out);
        assertTrue(out.contains("        Runnable r = () -> {\n            f(3, 4);\n        };\n"), out);
    }

}
