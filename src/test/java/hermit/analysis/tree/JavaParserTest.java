package hermit.analysis.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaParserTest {

    /** All tokens of a subtree in exact source order (crossing into child nodes). */
    private static List<Token> allTokens(Node n) {
        List<Token> out = new ArrayList<>();
        collect(n, out);
        return out;
    }

    private static void collect(Node n, List<Token> out) {
        for (Object p : n.parts()) {
            if (p instanceof Token t) {
                out.add(t);
            } else {
                collect((Node) p, out);
            }
        }
    }

    private static void assertRoundTrip(String src) {
        assertEquals(src, ParseTree.parse(src).toString(), "round-trip mismatch");
    }

    @Test
    void roundTripsASmallClassExactly() {
        String src = """
                package com.example.app;

                import java.util.List;   // a comment
                import java.util.*;
                import static java.util.Map.entry;

                /** Javadoc. */
                public final class Sample extends Base implements A, B {
                    private static final int LIMIT = 42;   // trailing comment

                    int compute(int a, String b) throws java.io.IOException {
                        return a + b.length();
                    }
                }
                """;
        ParseTree t = ParseTree.parse(src);
        assertEquals(src, t.toString());
        assertEquals("com.example.app", t.packageName());
        assertEquals(3, t.imports().size());
        assertEquals("java.util.List", ParseTree.importedName(t.imports().get(0)));
        assertTrue(ParseTree.isWildcardImport(t.imports().get(1)));
        assertTrue(ParseTree.isStaticImport(t.imports().get(2)));
        Node sample = t.findType("Sample");
        assertNotNull(sample);
        assertEquals(NodeType.CLASS_DECLARATION, sample.type());
        assertTrue(sample.hasModifier(TokenType.PUBLIC));
        assertTrue(sample.hasModifier(TokenType.FINAL));
        Node compute = t.findMethod("Sample", "compute");
        assertNotNull(compute);
        assertEquals(2, compute.children(NodeType.PARAMETER).size());
        Node field = sample.descendants(NodeType.FIELD_DECLARATION).get(0);
        assertEquals("LIMIT", field.name());
    }

    @Test
    void ifThenElseStructureMatchesTheSpec() {
        Node n = JavaParser.parseStatementSnippet("if (a > b) { x(); } else if (c) y(); else z();");
        assertEquals(NodeType.IF, n.type());
        assertNotNull(n.child(NodeType.THEN), "IF must contain a THEN wrapper");
        Node elseNode = n.child(NodeType.ELSE);
        assertNotNull(elseNode, "IF must contain the ELSE wrapper");
        Node elseIf = elseNode.child(NodeType.IF);
        assertNotNull(elseIf, "else-if: the ELSE node starts with a nested IF");
        assertNotNull(elseIf.child(NodeType.ELSE), "the chain ends with a final else");
        // parent pointers are set
        assertSame(n, n.child(NodeType.THEN).parent());
    }

    @Test
    void varDeclarationsHaveTypeAndDeclarators() {
        Node n = JavaParser.parseStatementSnippet("final Map<String, Integer> a = init(), b;");
        assertEquals(NodeType.VAR_DECLARATION, n.type());
        assertNotNull(n.child(NodeType.TYPE));
        List<Node> decls = n.children(NodeType.VAR_DECLARATOR);
        assertEquals(2, decls.size());
        assertEquals("a", decls.get(0).name());
        assertEquals("b", decls.get(1).name());
        assertEquals(NodeType.METHOD_CALL, decls.get(0).children().get(0).type());
        assertEquals("a", n.name(), "the declaration's principal is its first declarator");
        // 'var' works too
        Node v = JavaParser.parseStatementSnippet("var x = compute(1);");
        assertEquals(NodeType.VAR_DECLARATION, v.type());
        assertEquals("var", v.child(NodeType.TYPE).name());
    }

    @Test
    void loopsParse() {
        assertEquals(NodeType.FOR, JavaParser.parseStatementSnippet("for (int i = 0; i < n; i++) work(i);").type());
        Node fe = JavaParser.parseStatementSnippet("for (final String s : list) use(s);");
        assertEquals(NodeType.FOR_EACH, fe.type());
        assertNotNull(fe.child(NodeType.VAR_DECLARATION), "for-each holds a VAR_DECLARATION per the spec");
        assertEquals(NodeType.WHILE, JavaParser.parseStatementSnippet("while (ok()) step();").type());
        assertEquals(NodeType.DO, JavaParser.parseStatementSnippet("do { step(); } while (ok());").type());
        assertEquals(NodeType.FOR, JavaParser.parseStatementSnippet("for (;;) spin();").type());
    }

    @Test
    void oldSwitchVsNewSwitch() {
        Node oldStyle = JavaParser.parseStatementSnippet("""
                switch (x) {
                    case 1:
                    case 2: run(); break;
                    default: other();
                }""");
        assertEquals(NodeType.SWITCH, oldStyle.type());
        assertEquals(3, oldStyle.children(NodeType.CASE).size());

        Node newStyle = JavaParser.parseStatementSnippet("""
                switch (x) {
                    case 1, 2 -> run();
                    case Integer i when i > 10 -> big(i);
                    case Point(var a, var b) -> use(a, b);
                    case null, default -> other();
                }""");
        assertEquals(NodeType.SWITCH_FUNCTION, newStyle.type());
        List<Node> cases = newStyle.children(NodeType.CASE);
        assertEquals(4, cases.size());
        assertNotNull(cases.get(1).child(NodeType.TYPE_PATTERN), "case with a type pattern");
        Node rec = cases.get(2).child(NodeType.RECORD_PATTERN);
        assertNotNull(rec, "case with a record pattern");
        assertEquals(2, rec.children(NodeType.TYPE_PATTERN).size(), "var a / var b sub-patterns");

        Node expr = JavaParser.parseExpressionSnippet("switch (k) { case A -> 1; default -> { yield 2; } }");
        assertEquals(NodeType.SWITCH_FUNCTION, expr.type());
    }

    @Test
    void angleBracketsAreRetypedAndMatched() {
        Node n = JavaParser.parseStatementSnippet("Map<String, List<Integer>> m = new HashMap<>();");
        List<Token> toks = allTokens(n);
        List<Token> closers = toks.stream().filter(t -> t.type() == TokenType.CLOSING_ANGLE_BRACKET).toList();
        List<Token> openers = toks.stream().filter(t -> t.type() == TokenType.OPENING_ANGLE_BRACKET).toList();
        assertEquals(3, openers.size(), "Map<, List< and the diamond <");
        assertEquals(3, closers.size());
        for (Token c : closers) {
            assertNotNull(c.matchingBracket(), "every closing angle bracket links to its opener");
            assertEquals(TokenType.OPENING_ANGLE_BRACKET, c.matchingBracket().type());
            assertSame(c, c.matchingBracket().matchingBracket(), "the link is reciprocal");
        }
        // no leftover LESS_THAN/GREATER_THAN operators in a pure type context
        assertTrue(toks.stream().noneMatch(t -> t.type() == TokenType.LESS_THAN
                || t.type() == TokenType.GREATER_THAN));
    }

    @Test
    void shiftsMergeButComparisonsStay() {
        Node sh = JavaParser.parseExpressionSnippet("a >> 2");
        assertEquals(NodeType.BINARY_EXPRESSION, sh.type());
        assertEquals(TokenType.SHIFT_RIGHT, sh.token().type());
        assertEquals("a >> 2", sh.toString(), "merged token preserves the text");

        Node ush = JavaParser.parseExpressionSnippet("a >>> 2");
        assertEquals(TokenType.SHIFT_RIGHT_PRESERVING_SIGN, ush.token().type());

        Node cmp = JavaParser.parseExpressionSnippet("a > b");
        assertEquals(TokenType.GREATER_THAN, cmp.token().type());

        Node lt = JavaParser.parseExpressionSnippet("f(a < b, c > d)");
        assertEquals(NodeType.METHOD_CALL, lt.type());
        assertEquals(2, lt.children(NodeType.BINARY_EXPRESSION).size(), "two relational arguments, no generics");

        Node shl = JavaParser.parseExpressionSnippet("a << 3");
        assertEquals(TokenType.SHIFT_LEFT, shl.token().type());
    }

    @Test
    void receiverChainsNest() {
        Node chain = JavaParser.parseExpressionSnippet("var1.method1(a, b).method2(c).field3");
        assertEquals(NodeType.FIELD_ACCESS, chain.type());
        Node m2 = chain.children().get(0);
        assertEquals(NodeType.METHOD_CALL, m2.type());
        assertEquals("method2", m2.name());
        Node m1 = m2.children().get(0);
        assertEquals("method1", m1.name());
        assertEquals(NodeType.IDENTIFIER_EXPRESSION, m1.children().get(0).type());
    }

    @Test
    void prefixPostfixAndAssignmentOperators() {
        assertEquals(NodeType.ASSIGNMENT, JavaParser.parseExpressionSnippet("x += 2").type());
        assertEquals(NodeType.POSTFIX_EXPRESSION, JavaParser.parseExpressionSnippet("i++").type());
        assertEquals(NodeType.UNARY_EXPRESSION, JavaParser.parseExpressionSnippet("!done").type());
        assertEquals(NodeType.CAST, JavaParser.parseExpressionSnippet("(List<String>) o").type());
        assertEquals(NodeType.BINARY_EXPRESSION, JavaParser.parseExpressionSnippet("(a) - b").type(),
                "(a) - b is a subtraction, not a cast");
        assertEquals(NodeType.CAST, JavaParser.parseExpressionSnippet("(int) - b").type(),
                "(int) - b IS a primitive cast");
    }

    @Test
    void conditionalAndInstanceofExpressions() {
        assertEquals(NodeType.TERNARY, JavaParser.parseExpressionSnippet("a ? b : c").type());
        assertEquals(NodeType.INSTANCEOF, JavaParser.parseExpressionSnippet("o instanceof Map<?, ?> mm").type());
    }

    @Test
    void methodReferencesClassLiteralsAndTypeArguments() {
        assertEquals(NodeType.METHOD_REFERENCE, JavaParser.parseExpressionSnippet("String::valueOf").type());
        assertEquals(NodeType.METHOD_REFERENCE, JavaParser.parseExpressionSnippet("ArrayList::new").type());
        assertEquals(NodeType.CLASS_LITERAL, JavaParser.parseExpressionSnippet("String[].class").type());
        assertEquals(NodeType.CLASS_LITERAL, JavaParser.parseExpressionSnippet("int.class").type());
        assertEquals(NodeType.METHOD_CALL, JavaParser.parseExpressionSnippet("Collections.<String>emptyList()").type());
    }

    @Test
    void arrayAccessAndArrayCreation() {
        assertEquals(NodeType.ARRAY_ACCESS, JavaParser.parseExpressionSnippet("xs[i + 1]").type());
        assertEquals(NodeType.NEW_ARRAY, JavaParser.parseExpressionSnippet("new int[3][]").type());
        assertEquals(NodeType.NEW_ARRAY, JavaParser.parseExpressionSnippet("new Path[] {a, b}").type());
    }

    @Test
    void lambdasParse() {
        Node l1 = JavaParser.parseExpressionSnippet("x -> x + 1");
        assertEquals(NodeType.LAMBDA, l1.type());
        assertEquals(1, l1.children(NodeType.PARAMETER).size());

        Node l2 = JavaParser.parseExpressionSnippet("(a, b) -> { return a + b; }");
        assertEquals(NodeType.LAMBDA, l2.type());
        assertEquals(2, l2.children(NodeType.PARAMETER).size());
        assertNotNull(l2.child(NodeType.BLOCK));

        Node l3 = JavaParser.parseExpressionSnippet("(String s, int n) -> s.repeat(n)");
        assertEquals(NodeType.LAMBDA, l3.type());
        assertNotNull(l3.children(NodeType.PARAMETER).get(0).child(NodeType.TYPE), "typed lambda parameter");

        Node l4 = JavaParser.parseExpressionSnippet("() -> {}");
        assertEquals(NodeType.LAMBDA, l4.type());

        Node cast = JavaParser.parseExpressionSnippet("(Runnable) () -> {}");
        assertEquals(NodeType.CAST, cast.type());
        assertNotNull(cast.child(NodeType.LAMBDA), "cast of a lambda");
    }

    @Test
    void tryCatchFinallyAndResources() {
        Node t = JavaParser.parseStatementSnippet("""
                try (var in = open(); out) {
                    io();
                } catch (java.io.IOException | RuntimeException e) {
                    log(e);
                } finally {
                    close();
                }""");
        assertEquals(NodeType.TRY, t.type());
        assertEquals(2, t.children(NodeType.RESOURCE).size());
        Node c = t.child(NodeType.CATCH);
        assertNotNull(c);
        assertEquals(2, c.children(NodeType.TYPE).size(), "multi-catch types");
        assertNotNull(t.child(NodeType.FINALLY));
    }

    @Test
    void anonymousClassesAndEnumsAndRecords() {
        String src = """
                package p;

                sealed interface Shape permits Circle, Square {}

                record Circle(double radius) implements Shape {
                    Circle {
                        if (radius < 0) throw new IllegalArgumentException("negative");
                    }
                }

                final class Square implements Shape {}

                enum Op {
                    PLUS("+") { int apply(int a, int b) { return a + b; } },
                    MINUS("-") { int apply(int a, int b) { return a - b; } };

                    private final String sign;

                    Op(String sign) { this.sign = sign; }

                    abstract int apply(int a, int b);
                }

                class Holder {
                    Runnable r = new Runnable() {
                        @Override public void run() {}
                    };
                    java.util.List<String> l = new java.util.ArrayList<>() {};
                }
                """;
        ParseTree t = ParseTree.parse(src);
        assertEquals(src, t.toString());
        assertEquals(NodeType.RECORD_DECLARATION, t.findType("Circle").type());
        assertNotNull(t.findType("Circle").descendants(NodeType.COMPACT_CONSTRUCTOR).get(0));
        Node op = t.findType("Op");
        assertEquals(NodeType.ENUM_DECLARATION, op.type());
        assertEquals(2, op.child(NodeType.CLASS_BODY).children(NodeType.ENUM_CONSTANT).size());
        // both enum-constant bodies + the two anonymous classes in Holder count as types
        long anon = t.allTypes().stream().filter(n -> n.type() == NodeType.NEW_INSTANCE).count();
        assertEquals(2, anon);
    }

    @Test
    void annotationsEverywhere() {
        String src = """
                @Deprecated
                @SuppressWarnings({"unchecked", "raw"})
                @Custom(name = "x", order = 3)
                class A {
                    @Override
                    public String toString() { return "A"; }

                    void m(@NonNull final String s, int... rest) {}
                }
                """;
        ParseTree t = ParseTree.parse(src);
        assertEquals(src, t.toString());
        Node a = t.findType("A");
        assertEquals(3, a.children(NodeType.ANNOTATION).size());
        Node m = t.findMethod("A", "m");
        assertEquals(1, m.children(NodeType.PARAMETER).get(0).children(NodeType.ANNOTATION).size());
    }

    @Test
    void snippetsRejectTrailingGarbage() {
        assertThrows(ParseException.class, () -> JavaParser.parseExpressionSnippet("a + b; extra"));
        assertThrows(ParseException.class, () -> JavaParser.parseStatementSnippet("int x = 1; int y = 2;"));
    }

    @Test
    void parseErrorsCarryPositionAndExpectation() {
        // Lexically fine (brackets balance), syntactically broken: no name after the type.
        ParseException e = assertThrows(ParseException.class,
                () -> ParseTree.parse("class C { void m() { int = 5; } }"));
        assertTrue(e.getMessage().contains("expected"), e.getMessage());
        assertTrue(e.line() >= 1);
        // A truncated expression also reports a position, not a crash.
        ParseException e2 = assertThrows(ParseException.class,
                () -> JavaParser.parseExpressionSnippet("a +"));
        assertTrue(e2.line() >= 1);
    }

    @Test
    void textBlocksAndCommentsSurviveRoundTrip() {
        String src = """
                class T {
                    /* block
                       comment */
                    String q = \"""
                            hello "world"
                            \""";  // tail
                }
                """;
        assertRoundTrip(src);
    }

    @Test
    void selfParsesAllHermitSourcesExactly() throws IOException {
        Path root = Path.of("src");
        assumeTrue(Files.isDirectory(root), "run from the hermit project dir");
        List<Path> files;
        try (var s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(files.isEmpty());
        for (Path f : files) {
            String src = Files.readString(f);
            ParseTree t;
            try {
                t = ParseTree.parse(src);
            } catch (RuntimeException e) {
                throw new AssertionError("parse failed for " + f + ": " + e.getMessage(), e);
            }
            assertEquals(src, t.toString(), "round-trip mismatch for " + f);
            assertFalse(t.root().children().isEmpty(), "empty tree for " + f);
        }
    }

    @Test
    void aSwitchWithAMissingOrWrongDelimiterIsAParseErrorThatNamesIt() {
        String[][] cases = {
            {"switch k { case 1: break; }", "expected '(' after 'switch'"},
            {"switch (a b) { case 1: break; }", "expected ')' after the switch selector"},
            {"switch (k) case 1: break;", "expected '{' to open the switch body"},
            {"switch (k) { foo: break; }", "expected 'case' at a switch label"},
            {"switch (k) { case 1 break; }", "expected ':' after the case label"},
        };
        for (String[] c : cases) {
            ParseException e = assertThrows(ParseException.class, () -> JavaParser.parseStatementSnippet(c[0]), c[0]);
            assertTrue(e.getMessage().contains(c[1]), c[0] + ": " + e.getMessage());
        }
    }

    @Test
    void anAnnotatedLocalVariableIsADeclarationWithItsAnnotations() {
        Node declaration = JavaParser.parseStatementSnippet("@SuppressWarnings(\"x\") final int v = 1;");
        assertEquals(NodeType.VAR_DECLARATION, declaration.type());
        assertEquals("SuppressWarnings", declaration.child(NodeType.ANNOTATION).name());
        assertEquals("v", declaration.child(NodeType.VAR_DECLARATOR).name());
    }

    @Test
    void aLocalRecordOrClassIsATypeDeclarationOfTheBlock() {
        Node block = JavaParser.parseStatementSnippet("{ record Step(int at) { } class Local { } Step s = new Step(1); }");
        List<Node> statements = block.children();
        assertEquals(NodeType.RECORD_DECLARATION, statements.get(0).type());
        assertEquals("Step", statements.get(0).name());
        assertEquals(NodeType.CLASS_DECLARATION, statements.get(1).type());
        assertEquals(NodeType.VAR_DECLARATION, statements.get(2).type());
    }

    @Test
    void anExpressionOrSeveralInitializeAForLoop() {
        Node block = JavaParser.parseStatementSnippet("{ for (i++; i < n; i++) { } for (i = 0, j = n; i < j; i++, j--) { } }");
        List<Node> statements = block.children();
        assertEquals(2, statements.size());
        assertEquals(NodeType.FOR, statements.get(0).type());
        assertEquals(NodeType.FOR, statements.get(1).type());
        assertEquals("for (i = 0, j = n; i < j; i++, j--) { }", statements.get(1).toString().strip());
    }

    /** A parenthesized comparison that opens with a name must stay a comparison. */
    @Test
    void aParenthesizedComparisonThatOpensWithANameStaysAComparison() {
        Node n = JavaParser.parseExpressionSnippet("(a < b)");
        assertEquals(NodeType.PARENTHESIZED, n.type());
        Node inner = n.children().get(0);
        assertEquals(NodeType.BINARY_EXPRESSION, inner.type());
        assertEquals(TokenType.LESS_THAN, inner.token().type());

        Node or = JavaParser.parseExpressionSnippet("(a.b < c) || (d < e && f)");
        assertTrue(allTokens(or).stream().noneMatch(t -> t.type() == TokenType.OPENING_ANGLE_BRACKET
                || t.type() == TokenType.CLOSING_ANGLE_BRACKET), "no angle brackets in an expression context");

        Node chain = JavaParser.parseExpressionSnippet("(a < b > c)");
        assertTrue(allTokens(chain).stream().noneMatch(t -> t.type() == TokenType.OPENING_ANGLE_BRACKET
                || t.type() == TokenType.CLOSING_ANGLE_BRACKET), "no angle brackets in an expression context");

        assertNotNull(JavaParser.parseStatementSnippet("if ((i < n)) { return; }"));
    }
}
