package hermit.text.expr;

/**
 * An arithmetic expression read from text by {@link ExprParser}: a number, a negation, or a binary operation of
 * {@code +}, {@code -}, {@code *} or {@code /}. Every node keeps the text range it was read from.
 */
public sealed interface Expr {

    /** Where in the parsed text the node lies: half-open character indices. */
    int start();

    /** The end of the node in the parsed text, exclusive. */
    int end();

    /** A number as written ({@code 3}, {@code 0.95}, {@code 1e-9}) and its exact value. */
    record Num(Rational value, String text, int start, int end) implements Expr {}

    /** Minus its operand. */
    record Neg(Expr operand, int start, int end) implements Expr {}

    /** {@code left op right}, the operator one of {@code + - * /} (a typed minus, times or division sign as one). */
    record Binary(char op, Expr left, Expr right, int start, int end) implements Expr {}
}
