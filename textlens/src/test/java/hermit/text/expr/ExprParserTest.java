package hermit.text.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExprParserTest {

    /** The expression in prefix form, to see its structure: {@code (+ 2 (* 3 4))}. */
    private static String tree(Expr e) {
        return switch (e) {
            case Expr.Num n -> n.text();
            case Expr.Neg n -> "(neg " + tree(n.operand()) + ")";
            case Expr.Binary b -> "(" + b.op() + " " + tree(b.left()) + " " + tree(b.right()) + ")";
        };
    }

    @Test
    void precedenceAndAssociativity() {
        assertEquals("(+ 2 (* 3 4))", tree(ExprParser.parse("2 + 3 * 4")));
        assertEquals("(- (- 10 4) 3)", tree(ExprParser.parse("10 - 4 - 3")));
        assertEquals("(/ (/ 8 4) 2)", tree(ExprParser.parse("8/4/2")));
        assertEquals("(* (+ 2 3) 4)", tree(ExprParser.parse("(2 + 3) * 4")));
    }

    @Test
    void typedSignsReadAsTheirOperators() {
        assertEquals("(* 2.5 4)", tree(ExprParser.parse("2.5 \u00d7 4")));
        assertEquals("(/ 9 3)", tree(ExprParser.parse("9 \u00f7 3")));
        assertEquals("(- 7 2)", tree(ExprParser.parse("7 \u2212 2")));
        assertEquals("(- 7 2)", tree(ExprParser.parse("7 \u2013 2")));
        assertEquals("(* 6 7)", tree(ExprParser.parse("6\u22c57")));
    }

    @Test
    void unarySignsAndExponents() {
        assertEquals("(+ (neg 3) 5)", tree(ExprParser.parse("-3 + 5")));
        assertEquals("(* 2 (neg (neg 1)))", tree(ExprParser.parse("2 * --1")));
        assertEquals("4", tree(ExprParser.parse("+4")));
        Expr e = ExprParser.parse("1e-9 * 3");
        assertEquals("(* 1e-9 3)", tree(e));
    }

    @Test
    void nodesKeepTheirRanges() {
        Expr e = ExprParser.parse(" 12 + (3) ");
        Expr.Binary b = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(1, b.start());
        assertEquals(8, b.end());
        assertEquals(1, b.left().start());
        assertEquals(3, b.left().end());
        assertEquals(7, b.right().start());
        assertEquals(8, b.right().end());
    }

    @Test
    void comparisonsReadTheirOperator() {
        Comparison c = ExprParser.parseComparison("2 + 2 = 5");
        assertEquals("=", c.op());
        assertEquals("(+ 2 2)", tree(c.left()));
        assertEquals("5", tree(c.right()));
        assertEquals("<=", ExprParser.parseComparison("3 \u2264 4").op());
        assertEquals(">=", ExprParser.parseComparison("3 >= 4").op());
        assertEquals("!=", ExprParser.parseComparison("3 \u2260 4").op());
        assertEquals("=", ExprParser.parseComparison("3 == 4").op());
        assertEquals("<", ExprParser.parseComparison("3<4").op());
    }

    @Test
    void anythingElseIsRefusedWithItsIndex() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExprParser.parse("2 + x"));
        assertTrue(e.getMessage().contains("index 4"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ExprParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ExprParser.parse("(2 + 3"));
        assertThrows(IllegalArgumentException.class, () -> ExprParser.parse("2 3"));
        assertThrows(IllegalArgumentException.class, () -> ExprParser.parse("2 = 3"));
        assertThrows(IllegalArgumentException.class, () -> ExprParser.parseComparison("2 + 3"));
    }

    @Test
    void sizeAndDepthAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> ExprParser.parse("1" + " + 1".repeat(50)));
        String deep = "(".repeat(ExprParser.MAX_DEPTH + 1) + "1" + ")".repeat(ExprParser.MAX_DEPTH + 1);
        assertThrows(IllegalArgumentException.class, () -> ExprParser.parse(deep));
        String ok = "(".repeat(ExprParser.MAX_DEPTH) + "1" + ")".repeat(ExprParser.MAX_DEPTH);
        assertEquals("1", tree(ExprParser.parse(ok)));
    }
}
