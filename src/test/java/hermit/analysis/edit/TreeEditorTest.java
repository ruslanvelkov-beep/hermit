package hermit.analysis.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.tree.JavaParser;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseException;
import hermit.analysis.tree.ParseTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeEditorTest {

    // Default package: the classpath index can PROVE what exists here, so type-level
    // hallucinations are flaggable too (an unknown named package would honestly open type checks).
    private static final String BASE = """
            class A {
                int compute() { return 21; }

                void run() {
                    int base = compute();
                    use(base);
                }

                void use(int x) {}
            }
            """;

    private static TreeEditor editor(String src) {
        return TreeEditor.of(ParseTree.parse(src));
    }

    @Test
    void insertedStatementSeesEarlierLocalsAndCommits() {
        TreeEditor ed = editor(BASE);
        Node anchor = ed.tree().findMethod("A", "run").child(NodeType.BLOCK)
                .children(NodeType.VAR_DECLARATION).get(0);
        ed.insertStatementAfter(anchor, "int twice = base * 2;");
        ParseTree fresh = ed.commit();
        String out = fresh.toString();
        assertTrue(out.contains("        int base = compute();\n        int twice = base * 2;\n"),
                "inserted on its own line with the anchor's indentation:\n" + out);
        assertNotNull(fresh.findMethod("A", "run"), "committed tree is fully re-parsed and navigable");
        assertNotSame(ed.tree(), null);
    }

    @Test
    void multiStatementBlocksInsertInOrderEverywhere() {
        // an LLM naturally writes several statements per block — every statement op takes them
        TreeEditor ed = editor(BASE);
        Node anchor = ed.tree().findMethod("A", "run").child(NodeType.BLOCK)
                .children(NodeType.VAR_DECLARATION).get(0);
        ed.insertStatementAfter(anchor, "int a = base + 1;\n// the pair\nint b = a * 2;");
        String out = ed.commit().toString();
        assertTrue(out.contains("        int base = compute();\n        int a = base + 1;\n"
                        + "        // the pair\n        int b = a * 2;\n        use(base);"),
                "in order, re-indented, comment kept:\n" + out);

        // before: both land before the anchor, order kept
        TreeEditor ed2 = editor(BASE);
        Node use = ed2.tree().findMethod("A", "run").child(NodeType.BLOCK)
                .children(NodeType.EXPRESSION_STATEMENT).get(0);
        ed2.insertStatementBefore(use, "int p = 1;\nint q = p + base;");
        String out2 = ed2.commit().toString();
        assertTrue(out2.indexOf("int p = 1;") < out2.indexOf("int q = p + base;")
                        && out2.indexOf("int q = p + base;") < out2.indexOf("use(base);"),
                out2);

        // atEnd into an EMPTY body: splice + chain
        TreeEditor ed3 = editor("class B {\n    void go() {\n    }\n}\n");
        ed3.insertStatementAtEnd(TreeEditor.bodyOf(ed3.tree().findMethod("B", "go")),
                "int x = 1;\nint y = x + 1;\nint z = y;");
        String out3 = ed3.commit().toString();
        assertTrue(out3.contains("        int x = 1;\n        int y = x + 1;\n        int z = y;\n"),
                out3);

        // replaceStatement with two statements: first replaces, second follows
        TreeEditor ed4 = editor(BASE);
        Node target = ed4.tree().findMethod("A", "run").child(NodeType.BLOCK)
                .children(NodeType.EXPRESSION_STATEMENT).get(0);
        ed4.replaceStatement(target, "use(base + 1);\nuse(base + 2);");
        String out4 = ed4.commit().toString();
        assertTrue(out4.contains("        use(base + 1);\n        use(base + 2);\n"), out4);

        // rollback across a chained multi-insert restores the exact original
        TreeEditor ed5 = editor(BASE);
        String before = ed5.render();
        Node b5 = TreeEditor.bodyOf(ed5.tree().findMethod("A", "run"));
        ed5.insertStatementAtEnd(b5, "int m = missing1;\nint n = missing2;");
        assertThrows(EditCommitException.class, ed5::commit);
        ed5.rollback();
        assertEquals(before, ed5.render());
    }

    @Test
    void hallucinatedNamesAreRejectedAndRollbackRestores() {
        TreeEditor ed = editor(BASE);
        String before = ed.render();
        Node body = TreeEditor.bodyOf(ed.tree().findMethod("A", "run"));
        ed.insertStatementAtEnd(body, "int x = missingVar + 1;");
        EditCommitException e = assertThrows(EditCommitException.class, ed::commit);
        assertEquals(1, e.problems().size(), e.problems().toString());
        assertTrue(e.problems().get(0).contains("unknown variable 'missingVar'"), e.problems().get(0));
        // edits stay applied for fix-forward...
        assertTrue(ed.render().contains("missingVar"));
        // ...but rollback restores the original source exactly
        ed.rollback();
        assertEquals(before, ed.render());
    }

    @Test
    void fixForwardIterationLikeHermitWould() {
        TreeEditor ed = editor(BASE);
        Node body = TreeEditor.bodyOf(ed.tree().findMethod("A", "run"));
        ed.insertStatementAtEnd(body, "int h = helper(21);");
        // first attempt fails: helper doesn't exist
        EditCommitException e = assertThrows(EditCommitException.class, ed::commit);
        assertTrue(e.problems().get(0).contains("no visible method 'helper'"));
        // fix forward: add the member, commit again — the whole edit set validates together
        ed.insertMember(ed.tree().findType("A"), "int helper(int v) { return v * 2; }");
        ParseTree fresh = ed.commit();
        assertNotNull(fresh.findMethod("A", "helper"));
        assertTrue(fresh.toString().contains("int h = helper(21);"));
    }

    @Test
    void arityAndMemberProblemsBlockCommit() {
        TreeEditor ed = editor(BASE);
        Node body = TreeEditor.bodyOf(ed.tree().findMethod("A", "run"));
        ed.insertStatementAtEnd(body, "String s = \"a\".substring(1, 2, 3);");
        EditCommitException e = assertThrows(EditCommitException.class, ed::commit);
        assertTrue(e.problems().get(0).contains("no overload of 'substring' on String takes 3 arguments"),
                e.problems().toString());
    }

    @Test
    void replaceExpressionIsInlineAndValidated() {
        TreeEditor ed = editor(BASE);
        Node init = ed.tree().findMethod("A", "run").descendants(NodeType.METHOD_CALL).stream()
                .filter(n -> "compute".equals(n.name()))
                .findFirst().orElseThrow();
        ed.replaceExpression(init, "compute() + 1");
        ParseTree fresh = ed.commit();
        assertTrue(fresh.toString().contains("        int base = compute() + 1;\n"),
                "inline replacement keeps the line: " + fresh.toString());

        // and a bad replacement is rejected
        TreeEditor ed2 = editor(BASE);
        Node init2 = ed2.tree().findMethod("A", "run").descendants(NodeType.METHOD_CALL).stream()
                .filter(n -> "compute".equals(n.name()))
                .findFirst().orElseThrow();
        ed2.replaceExpression(init2, "conjure()");
        EditCommitException e = assertThrows(EditCommitException.class, ed2::commit);
        assertTrue(e.problems().get(0).contains("no visible method 'conjure'"));
    }

    @Test
    void deletingAUsedDeclarationIsRejectedDeletingUnusedIsFine() {
        TreeEditor ed = editor(BASE);
        Node decl = ed.tree().findMethod("A", "run").child(NodeType.BLOCK)
                .children(NodeType.VAR_DECLARATION).get(0);
        ed.delete(decl);
        EditCommitException e = assertThrows(EditCommitException.class, ed::commit);
        assertTrue(e.problems().get(0).contains("unknown variable 'base'"), e.problems().toString());
        ed.rollback();

        // deleting the USE first, then the declaration, commits fine
        Node use = ed.tree().findMethod("A", "run").child(NodeType.BLOCK)
                .children(NodeType.EXPRESSION_STATEMENT).get(0);
        ed.delete(use);
        Node decl2 = ed.tree().findMethod("A", "run").child(NodeType.BLOCK)
                .children(NodeType.VAR_DECLARATION).get(0);
        ed.delete(decl2);
        ParseTree fresh = ed.commit();
        assertTrue(!fresh.toString().contains("base"));
    }

    @Test
    void importsAddAndDelete() {
        TreeEditor ed = editor(BASE);
        Node body = TreeEditor.bodyOf(ed.tree().findMethod("A", "run"));
        ed.insertStatementAtEnd(body, "List<String> l = new ArrayList<>();");
        List<String> dry = ed.problems();
        assertTrue(dry.stream().anyMatch(p -> p.contains("unknown type 'List'")), dry.toString());
        ed.addImport("java.util.List");
        ed.addImport("java.util.ArrayList");
        ParseTree fresh = ed.commit();
        String out = fresh.toString();
        assertTrue(out.contains("import java.util.ArrayList;\nimport java.util.List;"), "sorted: " + out);
        assertEquals(2, fresh.imports().size());

        // deleting a needed import is rejected
        TreeEditor ed2 = TreeEditor.of(fresh);
        ed2.deleteImport(fresh.imports().get(1));
        EditCommitException e = assertThrows(EditCommitException.class, ed2::commit);
        assertTrue(e.problems().get(0).contains("unknown type 'List'"));
    }

    @Test
    void anImportGoesToItsSortedPlaceAndIsNotAddedTwice() {
        // rewrite drive, tidy pass: imports went after the last one, in the order the items added them, some twice
        TreeEditor ed = editor("package p;\n\nimport hermit.b.Node;\nimport java.util.List;\n\nclass A {}\n");
        ed.addImport("hermit.a.Token");
        ed.addImport("java.util.ArrayList");
        ed.addImport("java.util.Set");
        ed.addImport("java.util.List");
        ed.addImport("static java.util.Objects.requireNonNull");
        assertEquals("package p;\n\nimport static java.util.Objects.requireNonNull;\n\nimport hermit.a.Token;\n"
                + "import hermit.b.Node;\nimport java.util.ArrayList;\nimport java.util.List;\nimport java.util.Set;\n\n"
                + "class A {}\n", ed.tree().toString());
        // Rewrite drive, task 045a: imports added to a file without any were one group; the static imports are a
        // group of their own, with a blank line after it.
        TreeEditor empty = editor("package p;\n\nclass A {}\n");
        empty.addImport("java.util.List");
        empty.addImport("static java.util.Objects.requireNonNull");
        empty.addImport("org.junit.Test");
        empty.addImport("static java.util.Objects.isNull");
        empty.addImport("static java.util.Objects.equals");
        assertEquals("package p;\n\nimport static java.util.Objects.equals;\nimport static java.util.Objects.isNull;\n"
                + "import static java.util.Objects.requireNonNull;\n\nimport java.util.List;\nimport org.junit.Test;\n\n"
                + "class A {}\n", empty.tree().toString());
    }

    @Test
    void sortImportsMakesOneSortedBlockWithEachImportOnce() {
        TreeEditor ed = editor("package p;\n\nimport java.util.Map;\nimport hermit.b.Node;\nimport java.util.List;\n"
                + "import java.util.Map;\nimport static java.util.Objects.requireNonNull;\n\nclass A {}\n");
        ed.sortImports();
        assertEquals("package p;\n\nimport static java.util.Objects.requireNonNull;\n\nimport hermit.b.Node;\n"
                + "import java.util.List;\nimport java.util.Map;\n\nclass A {}\n", ed.tree().toString());
        TreeEditor commented = editor("import java.util.Map;\n// the list\nimport java.util.List;\nclass A {}\n");
        assertThrows(IllegalArgumentException.class, commented::sortImports, "a comment would move");
    }

    @Test
    void aMembersEndOfLineCommentGoesWithItAndMethodsGetABlankLineBetweenThem() {
        // rewrite drive, tidy pass: replaced members left their end-of-line comments behind, eight of them ending up on
        // one line before a class's closing brace, and inserted methods had no blank line between them
        TreeEditor ed = editor("class A {\n    int a;   // the count\n    int b;\n    void m() {\n    }    // stray\n}\n");
        Node type = ed.tree().findType("A");
        Node body = type.child(NodeType.CLASS_BODY);
        ed.replaceMember(body.children(NodeType.FIELD_DECLARATION).get(0), "long a;");
        ed.replaceMember(ed.tree().findMethod("A", "m"), "void m() {\n}");
        assertEquals("class A {\n    long a;\n    int b;\n    void m() {\n    }\n}\n", ed.tree().toString());
        ed.delete(body.children(NodeType.FIELD_DECLARATION).get(1));
        assertEquals("class A {\n    long a;\n    void m() {\n    }\n}\n", ed.tree().toString());

        TreeEditor spaced = editor("class A {\n    int a;\n    int b;\n    void m() {\n    }\n    void n() {\n    }\n\n"
                + "    void o() {\n    }\n}\n");
        spaced.spaceMembers(spaced.tree().findType("A"));
        assertEquals("class A {\n    int a;\n    int b;\n\n    void m() {\n    }\n\n    void n() {\n    }\n\n"
                + "    void o() {\n    }\n}\n", spaced.tree().toString());
    }

    @Test
    void aDeletedMemberTakesItsCommentsAndLeavesThePreviousLinesComment() {
        // rewrite drive, task 053 (planning): a fix left a method unused, and no op deleted a member
        TreeEditor ed = editor("class A {\n    int a;   // the count\n\n    // the old way\n    void m() {\n    }\n\n"
                + "    void n() {\n    }\n}\n");
        ed.deleteMember(ed.tree().findMethod("A", "m"));
        assertEquals("class A {\n    int a;   // the count\n\n    void n() {\n    }\n}\n", ed.tree().toString());
        assertNotNull(ed.commit());
    }

    @Test
    void aReplacedMemberKeepsItsAnnotationsOrIsRefused() {
        // rewrite drive, task 044g2: an item for a test method left out its @Test
        TreeEditor ed = editor("class A {\n    @Deprecated\n    void m() {\n    }\n}\n");
        Node m = ed.tree().findMethod("A", "m");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ed.replaceMember(m, "void m() {\n}"));
        assertTrue(e.getMessage().contains("@Deprecated"), e.getMessage());
        ed.replaceMember(m, "@Deprecated\nvoid m() {\n    // kept\n}");
        assertTrue(ed.tree().toString().contains("@Deprecated\n    void m() {\n        // kept\n    }"), ed.tree().toString());
    }

    @Test
    void staticImportsWork() {
        TreeEditor ed = editor(BASE);
        Node body = TreeEditor.bodyOf(ed.tree().findMethod("A", "run"));
        ed.insertStatementAtEnd(body, "requireNonNull(this);");
        assertThrows(EditCommitException.class, ed::commit);
        ed.addImport("static java.util.Objects.requireNonNull");
        assertTrue(ed.commit().toString().contains("import static java.util.Objects.requireNonNull;"));
    }

    @Test
    void addTypeAndUseIt() {
        TreeEditor ed = editor(BASE);
        ed.addType("""
                class Extra {
                    static int give() { return 5; }
                }""");
        Node body = TreeEditor.bodyOf(ed.tree().findMethod("A", "run"));
        ed.insertStatementAtEnd(body, "int g = Extra.give();");
        ParseTree fresh = ed.commit();
        assertNotNull(fresh.findType("Extra"));
        assertTrue(fresh.toString().endsWith("class Extra {\n    static int give() { return 5; }\n}\n"),
                "type appended at the end:\n" + fresh.toString());
    }

    @Test
    void malformedSnippetsFailAtInsertTimeWithPositions() {
        TreeEditor ed = editor(BASE);
        Node body = TreeEditor.bodyOf(ed.tree().findMethod("A", "run"));
        assertThrows(ParseException.class, () -> ed.insertStatementAtEnd(body, "int x = ;"));
        assertThrows(ParseException.class, () -> ed.insertMember(ed.tree().findType("A"), "void ()broken{}"));
        // nothing was applied by the failed inserts
        assertEquals(BASE, ed.render());
    }

    @Test
    void preexistingProblemsAreToleratedAsBaseline() {
        String withGhost = """
                class B {
                    void m() { int x = ghost; }
                }
                """;
        TreeEditor ed = editor(withGhost);
        Node body = TreeEditor.bodyOf(ed.tree().findMethod("B", "m"));
        ed.insertStatementAtEnd(body, "int ok = x + 1;");
        ParseTree fresh = ed.commit();   // the OLD 'ghost' problem is baseline — only NEW ones block
        assertTrue(fresh.toString().contains("int ok = x + 1;"));
    }

    @Test
    void aOneLineBodyReplacedByStatementsGetsItsOwnLines() {
        // rewrite drive, scaffold task 034a: a one-line stub body replaced by two statements kept "{ ", gained a blank
        // line between the statements and closed as "return x;}"
        String src = "class A {\n    int f() { throw new RuntimeException(\"x\"); }\n    int g() { return 1; }\n}\n";
        for (String code : new String[] {"int x = 1;\nreturn x;", "{\n    int x = 1;\n    return x;\n}"}) {
            ParseTree t = ParseTree.parse(src);
            TreeEditor e = TreeEditor.of(t);
            e.replaceBody(t.findMethod("A", "f"), code);
            assertEquals("class A {\n    int f() {\n        int x = 1;\n        return x;\n    }\n    int g() { return 1; }\n}\n",
                    e.render(), code);
        }
    }

    @Test
    void aMemberInsertedIntoAOneLineClassGetsItsOwnLines() {
        ParseTree t = ParseTree.parse("class A { }\n");
        TreeEditor e = TreeEditor.of(t);
        e.insertMember(t.findType("A"), "int f() { return 1; }");
        assertEquals("class A {\n\n    int f() { return 1; }\n}\n", e.render());
    }

    @Test
    void severalMembersInOneInsertAreInsertedTogether() {
        // rewrite drive, task 035a (three fields in one item) and FM-045 (two methods that call each other)
        ParseTree t = ParseTree.parse("class A {\n    int x;\n}\n");
        TreeEditor e = TreeEditor.of(t);
        e.insertMember(t.findType("A"), "// the kind\nprivate final int kind = 1;\nprivate final int parent = 2;\n"
                + "int even(int n) { return n == 0 ? 1 : odd(n - 1); }\nint odd(int n) { return n == 0 ? 0 : even(n - 1); }");
        String out = e.render();
        assertTrue(out.contains("    // the kind\n    private final int kind = 1;"), out);
        assertTrue(out.contains("private final int parent = 2;") && out.contains("int even(int n)") && out.contains("int odd(int n)"), out);
        assertEquals(List.of(), e.problems(), "validated together: each method finds the other");
    }

    @Test
    void aMemberIdenticalToOneAlreadyThereIsAlreadyDone() {
        // rewrite drive, task 035c: a re-plan inserted again members the previous run had added
        ParseTree t = ParseTree.parse("class A {\n    int f() { return 1; }\n}\n");
        TreeEditor e = TreeEditor.of(t);
        e.insertMember(t.findType("A"), "int f() {\n    return 1;\n}");
        assertEquals("class A {\n    int f() { return 1; }\n}\n", e.render());
        assertThrows(IllegalArgumentException.class, () -> e.insertMember(t.findType("A"), "int f() { return 2; }"),
                "a DIFFERENT member of the same name and arity is still refused");
    }

    @Test
    void aBodyWithItsClosingBraceButNotItsOpeningOneLosesTheClosingBrace() {
        // rewrite drive, task 040e: the replaceBody code was the body's statements and the method's closing brace
        assertEquals("if (a) {\n    return 1;\n}\nreturn 2;",
                TreeEditor.bodyStatementsOf("if (a) {\n    return 1;\n}\nreturn 2;\n}"));
        assertEquals("String s = \"}\";\nreturn s;", TreeEditor.bodyStatementsOf("String s = \"}\";\nreturn s;"),
                "a brace inside a string does not count");
        assertEquals("if (a) {\n    b();\n}", TreeEditor.bodyStatementsOf("if (a) {\n    b();\n}"), "a balanced body stays");
    }

    @Test
    void aFieldGoesAfterTheLastFieldAndAConstructorAfterTheFields() {
        // Rewrite drive, task 048a: every inserted member went to the end of the class, so a port's fields and
        // constructor came after its methods
        TreeEditor ed = editor("class A {\n    int a;   // the count\n\n    void m() {\n    }\n}\n");
        ed.insertMember(ed.tree().findType("A"), "int b;");
        ed.insertMember(ed.tree().findType("A"), "A() {\n}");
        ed.insertMember(ed.tree().findType("A"), "void n() {\n}");
        ed.insertMember(ed.tree().findType("A"), "A(int b) {\n    this.b = b;\n}");
        assertEquals("class A {\n    int a;   // the count\n    int b;\n\n    A() {\n    }\n\n    A(int b) {\n"
                + "        this.b = b;\n    }\n\n    void m() {\n    }\n\n    void n() {\n    }\n}\n", ed.tree().toString());
    }

    @Test
    void anEmptyClassGetsItsMembersAfterABlankLineAndFieldsStayTogether() {
        // Rewrite drive, task 049a2: the first member of a new class came right after the class line, and a field
        // inserted after the last member, itself a field, got a blank line before it
        TreeEditor ed = editor("class A {\n}\n");
        ed.insertMember(ed.tree().findType("A"), "int a;");
        ed.insertMember(ed.tree().findType("A"), "int b;");
        ed.insertMember(ed.tree().findType("A"), "void m() {\n}");
        assertEquals("class A {\n\n    int a;\n    int b;\n\n    void m() {\n    }\n}\n", ed.tree().toString());
    }

    @Test
    void aStatementsEndOfLineCommentStaysOnItsLine() {
        // Rewrite drive, task 045e3: "take(cursor, into); // '<'" in a replaced body came out with the comment on a
        // line of its own; the parser gives the comment to the next statement, whose insertion broke the line
        ParseTree t = ParseTree.parse("class A {\n    void m() {\n        a();\n    }\n}\n");
        TreeEditor ed = TreeEditor.of(t);
        ed.replaceBody(t.findMethod("A", "m"), "b(); // first\nc();  // second\n// own line\nd();");
        assertEquals("class A {\n    void m() {\n        b(); // first\n        c();  // second\n        // own line\n"
                + "        d();\n    }\n}\n", ed.render());
        ParseTree u = ParseTree.parse("class A {\n    void m() {\n        a();\n    }\n}\n");
        TreeEditor ed2 = TreeEditor.of(u);
        ed2.insertStatementAfter(TreeEditor.bodyOf(u.findMethod("A", "m")).children().get(0), "x(); // after\ny();");
        assertEquals("class A {\n    void m() {\n        a();\n        x(); // after\n        y();\n    }\n}\n",
                ed2.render());
    }

    @Test
    void aBodyThatOpensWithAStatementAndABraceIsNotTakenForAMethod() {
        // Rewrite drive, task 045e2: "return switch (node) {" read as a method signature (type "return", name
        // "switch"), so the body lost its first line and its last brace and no longer parsed
        String returnSwitch = "return switch (node) {\n    case 1 -> token == 1;\n    default -> false;\n};";
        assertEquals(returnSwitch, TreeEditor.bodyStatementsOf(returnSwitch));
        String anonymous = "return new Runnable() {\n    public void run() {\n    }\n};";
        assertEquals(anonymous, TreeEditor.bodyStatementsOf(anonymous));
        String elseIf = "else if (a) {\n    b();\n}";
        assertEquals(elseIf, TreeEditor.bodyStatementsOf(elseIf));
        assertEquals("return 1;", TreeEditor.bodyStatementsOf("@Override\npublic int f(int a) {\n    return 1;\n}"),
                "a method handed in whole is still reduced to its body");
        ParseTree t = ParseTree.parse("class A {\n    static boolean k(int node, int token) {\n        return false;\n    }\n}\n");
        TreeEditor ed = TreeEditor.of(t);
        ed.replaceBody(t.findMethod("A", "k"), returnSwitch);
        assertEquals("class A {\n    static boolean k(int node, int token) {\n        return switch (node) {\n"
                + "            case 1 -> token == 1;\n            default -> false;\n        };\n    }\n}\n", ed.render());
    }

    @Test
    void aMovedMemberTakesItsCommentsToItsNewPlace() {
        // rewrite drive, tidy pass: a port's fields and constructor came after its methods, and no op moved a member
        TreeEditor ed = editor("class A {\n    record R(int x) {}\n\n    /** Runs. */\n    void run() {\n    }   // ran\n\n"
                + "    void stop() {\n    }\n\n    private int a;   // the count\n\n    private int b;\n\n    A() {\n    }\n}\n");
        Node type = ed.tree().findType("A");
        ed.moveMember(ed.tree().findMethod("A", "run"), type.child(NodeType.CLASS_BODY).children(NodeType.CONSTRUCTOR_DECLARATION).get(0));
        ed.moveMember(ed.tree().findMethod("A", "stop"), type.child(NodeType.CLASS_BODY).children(NodeType.CONSTRUCTOR_DECLARATION).get(0));
        assertEquals("class A {\n    record R(int x) {}\n\n    private int a;   // the count\n\n    private int b;\n\n"
                + "    /** Runs. */\n    void run() {\n    }   // ran\n\n    void stop() {\n    }\n\n    A() {\n    }\n}\n", ed.tree().toString());
        ed.spaceBefore(type.child(NodeType.CLASS_BODY).children(NodeType.FIELD_DECLARATION).get(1), 0);
        ed.spaceBefore(type.child(NodeType.CLASS_BODY).children().get(0), 1);
        assertEquals("class A {\n\n    record R(int x) {}\n\n    private int a;   // the count\n    private int b;\n\n"
                + "    /** Runs. */\n    void run() {\n    }   // ran\n\n    void stop() {\n    }\n\n    A() {\n    }\n}\n", ed.tree().toString());
        assertNotNull(ed.commit());
    }

    @Test
    void aFieldsContinuationLinesStayADoubleIndentIn() {
        // rewrite drive, tidy pass: a ported Set field's continuation lines came out at the field's own indent; with no
        // closing line to measure from, a declaration without a body continues a double indent (eight columns) in
        TreeEditor ed = editor("class A {\n    static final java.util.Set<String> S = java.util.Set.of(\"a\");\n}\n");
        Node field = ed.tree().findType("A").child(NodeType.CLASS_BODY).children(NodeType.FIELD_DECLARATION).get(0);
        ed.replaceMember(field, "static final java.util.Set<String> S = java.util.Set.of(\"a\",\n        \"b\",\n        \"c\");");
        assertEquals("class A {\n    static final java.util.Set<String> S = java.util.Set.of(\"a\",\n            \"b\",\n"
                + "            \"c\");\n}\n", ed.tree().toString());
        assertEquals("void m() {\n    x();\n}", TreeEditor.reindentContinuation("void m() {\n        x();\n    }", ""),
                "a member with a body keeps measuring from its closing line");
    }

    @Test
    void aTypesOwnCommentIsReplaced() {
        // rewrite drive, tidy pass: four class comments ran past 120 columns, and no op replaced a type's own comment
        TreeEditor ed = editor("import java.util.List;\n\n/** One long line about the class. */\nfinal class A {\n    List<String> l;\n}\n");
        ed.replaceTypeComment(ed.tree().findType("A"), "/**\n * Two lines\n * about the class.\n */");
        assertEquals("import java.util.List;\n\n/**\n * Two lines\n * about the class.\n */\nfinal class A {\n    List<String> l;\n}\n",
                ed.tree().toString());
        assertThrows(IllegalArgumentException.class, () -> ed.replaceTypeComment(ed.tree().findType("A"), "int x;"));
        assertNotNull(ed.commit());
    }

    @Test
    void aTypesModifiersAreReplacedAndItsCommentAndAnnotationsStay() {
        // a class the port called from another package had to become public, and no op changed a
        // type's own modifiers
        TreeEditor ed = editor("/** A. */\nfinal class A {\n}\n\n@Deprecated\nclass B {\n}\n");
        ed.setTypeModifiers(ed.tree().findType("A"), "public final");
        ed.setTypeModifiers(ed.tree().findType("B"), "public");
        assertEquals("/** A. */\npublic final class A {\n}\n\n@Deprecated\npublic class B {\n}\n", ed.tree().toString());
        ed.setTypeModifiers(ed.tree().findType("A"), "");
        assertEquals("/** A. */\nclass A {\n}\n\n@Deprecated\npublic class B {\n}\n", ed.tree().toString());
        assertThrows(IllegalArgumentException.class, () -> ed.setTypeModifiers(ed.tree().findType("A"), "public void"));
        assertNotNull(ed.commit());
    }

    @Test
    void aReplacedBodyKeepsItsCommentsBlankLinesAndContinuationLinesAsWritten() {
        // a body written as a plan writes it (its first line not indented) lost the comment above its first
        // statement, its blank lines and the continuation indent of a statement with no comment above it
        String src = "class A {\n    /** Doc. */\n    String m(String p) {\n        // first\n        String a = p.trim();\n"
                + "        if (a.isEmpty()) {\n            return a;\n        }\n\n"
                + "        StringBuilder b = new StringBuilder()\n                .append(a);\n\n"
                + "        // last\n        return b.toString();\n    }\n\n    int n() { return 1; }\n}\n";
        String code = "// first\n        String a = p.trim();\n        if (a.isEmpty()) {\n            return a;\n        }\n\n"
                + "        StringBuilder b = new StringBuilder()\n                .append(a);\n\n"
                + "        // changed\n        return b.toString();";
        ParseTree t = ParseTree.parse(src);
        TreeEditor e = TreeEditor.of(t);
        e.replaceBody(t.findMethod("A", "m"), code);
        assertEquals(src.replace("// last", "// changed"), e.render());
    }

    @Test
    void aStatementWrittenOverTwoLinesKeepsItsContinuationLineIndented() {
        // a statement wrapped at 120 columns, its first line not indented as a plan writes it, came out with its
        // continuation line at the statement's own indent
        String src = "class A {\n    String m(String s) {\n        if (s.isEmpty()) {\n            return \"x\";\n        }\n"
                + "        return s;\n    }\n}\n";
        for (String continuation : new String[] {"                    + \" (more)\");", "        + \" (more)\");"}) {
            ParseTree t = ParseTree.parse(src);
            TreeEditor e = TreeEditor.of(t);
            Node ret = TreeEditor.bodyOf(t.findMethod("A", "m")).children(NodeType.IF).get(0).child(NodeType.THEN)
                    .child(NodeType.BLOCK).children(NodeType.RETURN).get(0);
            e.replaceStatement(ret, "return String.join(\"x\", s\n" + continuation);
            assertEquals(src.replace("return \"x\";", "return String.join(\"x\", s\n                    + \" (more)\");"),
                    e.render(), continuation);
        }
        ParseTree t = ParseTree.parse(src);
        TreeEditor e = TreeEditor.of(t);
        e.insertStatementAtEnd(TreeEditor.bodyOf(t.findMethod("A", "m")), "String u = s\n        + s;\nint n = 1;");
        assertTrue(e.render().contains("        return s;\n        String u = s\n                + s;\n        int n = 1;\n"),
                e.render());
    }

    @Test
    void aMemberThatSharesALineGetsItsOwnLineAtTheMembersIndent() {
        // two fields on one line and a constructor after a field were spaced with the column the member
        // started at as their indentation
        String src = "class A {\n    int a;   int b;\n    int c;    A() {\n    }\n}\n";
        ParseTree t = ParseTree.parse(src);
        TreeEditor e = TreeEditor.of(t);
        Node body = t.findType("A").child(NodeType.CLASS_BODY);
        List<Node> members = body.children();
        e.spaceBefore(members.get(1), 0);
        e.spaceBefore(members.get(3), 1);
        assertEquals("class A {\n    int a;\n    int b;\n    int c;\n\n    A() {\n    }\n}\n", e.render());
    }

    @Test
    void aTypesMembersAreIndentedOneLevelBelowItsDeclaration() {
        // rewrite tidy (2026-10-25): the rewrite's edit tool had every member at column 0 since its scaffold, and no op
        // could indent them; the lines keep their indentation relative to each other, so a text block's value stays
        String src = "/** A. */\nclass A {\n\nprivate int a;   // the count\n/**\n * Runs.\n */\nvoid run() {\n"
                + "    String s = \"\"\"\n        x\n\n          y\n        \"\"\";\n}\n\n"
                + "static class B {\n    int b;\n}\n}\n";
        TreeEditor ed = editor(src);
        ed.indentMembers(ed.tree().findType("A"));
        assertEquals("/** A. */\nclass A {\n\n    private int a;   // the count\n    /**\n     * Runs.\n     */\n"
                + "    void run() {\n        String s = \"\"\"\n            x\n\n              y\n"
                + "            \"\"\";\n    }\n\n"
                + "    static class B {\n        int b;\n    }\n}\n", ed.render());
        assertNotNull(ed.commit());
        TreeEditor again = editor(ed.render());
        again.indentMembers(again.tree().findType("A"));
        assertEquals(ed.render(), again.render(), "members one level in already stay where they are");
        TreeEditor deep = editor("class A {\n        int a;\n\n        void m() {\n        }\n}\n");
        deep.indentMembers(deep.tree().findType("A"));
        assertEquals("class A {\n    int a;\n\n    void m() {\n    }\n}\n", deep.render());
        TreeEditor ragged = editor("class A {\n        int a;\n  int b;\n}\n");
        assertThrows(IllegalArgumentException.class, () -> ragged.indentMembers(ragged.tree().findType("A")),
                "a line indented less than the shift is refused");
    }

    @Test
    void aMemberAppendedAtTheEndGoesAfterACommentOnItsOwnLineThere() {
        // rewrite tidy (2026-10-25): a stray comment before the edit tool's closing brace could not be taken away: a
        // member appended at the end went before it, so no member that could be deleted ever held the comment
        TreeEditor ed = editor("class A {\n    void m() {\n    }   // m's\n\n    // the end\n}\n");
        ed.insertMember(ed.tree().findType("A"), "void n() {\n}");
        assertEquals("class A {\n    void m() {\n    }   // m's\n\n    // the end\n\n    void n() {\n    }\n}\n",
                ed.render());
        TreeEditor plain = editor("class A {\n    void m() {\n    }   // m's\n}\n");
        plain.insertMember(plain.tree().findType("A"), "void n() {\n}");
        assertEquals("class A {\n    void m() {\n    }   // m's\n\n    void n() {\n    }\n}\n", plain.render());
    }

    @Test
    void anOverloadWithAsManyParametersOfOtherTypesIsNotADuplicate() {
        // agent loop port, plan B (2026-10-25): oversizedReadIssue(List, Path) was refused as a duplicate of
        // oversizedReadIssue(List, String), since only the number of parameters was compared
        String src = "class A {\n    static int f(java.util.List<String> a, String b) {\n        return 1;\n    }\n}\n";
        TreeEditor ed = editor(src);
        ed.insertMember(ed.tree().findType("A"), "static int f(java.util.List<String> a, java.nio.file.Path b) {\n"
                + "    return 2;\n}");
        assertTrue(ed.render().contains("static int f(java.util.List<String> a, java.nio.file.Path b)"), ed.render());
        TreeEditor same = editor(src);
        assertThrows(IllegalArgumentException.class, () -> same.insertMember(same.tree().findType("A"),
                "static int f(java.util.List<Integer> c, String d) {\n    return 3;\n}"),
                "the same parameter types (generics erased) are a duplicate");
    }
}
