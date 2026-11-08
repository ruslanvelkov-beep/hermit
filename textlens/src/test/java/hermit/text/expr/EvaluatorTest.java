package hermit.text.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EvaluatorTest {

    @Test
    void twoPlusTwoIsFourExactly() {
        assertEquals(Rational.of(4, 1), Evaluator.valueOf("2 + 2"));
        assertEquals(Rational.parse("0.3"), Evaluator.valueOf("0.1 + 0.2"));
        assertEquals(Rational.of(14, 1), Evaluator.valueOf("2 + 3 * 4"));
    }

    @Test
    void divisionStaysExact() {
        assertEquals(Rational.of(1, 3), Evaluator.valueOf("1 / 3"));
        assertEquals(Rational.ONE, Evaluator.valueOf("(1 / 3) * 3"));
        assertThrows(ArithmeticException.class, () -> Evaluator.valueOf("5 / (2 - 2)"));
    }

    @Test
    void negationAndScientificNumerals() {
        assertEquals(Rational.of(-2, 1), Evaluator.valueOf("-(5 - 3)"));
        assertEquals(Rational.parse("0.000000003"), Evaluator.valueOf("3 * 1e-9"));
    }

    @Test
    void comparisonsHoldOrNot() {
        assertFalse(Evaluator.holds(ExprParser.parseComparison("2 + 2 = 5")));
        assertTrue(Evaluator.holds(ExprParser.parseComparison("2 + 2 = 4")));
        assertTrue(Evaluator.holds(ExprParser.parseComparison("0.1 + 0.2 == 0.3")));
        assertTrue(Evaluator.holds(ExprParser.parseComparison("10 > 9")));
        assertFalse(Evaluator.holds(ExprParser.parseComparison("1 / 3 >= 0.34")));
        assertTrue(Evaluator.holds(ExprParser.parseComparison("3 \u2260 4")));
        assertTrue(Evaluator.holds(ExprParser.parseComparison("4 <= 4")));
    }

    @Test
    void evaluateReadsAParsedTree() {
        Expr e = ExprParser.parse("(7 - 2) \u00d7 3");
        assertEquals(Rational.of(15, 1), Evaluator.evaluate(e));
    }
}
