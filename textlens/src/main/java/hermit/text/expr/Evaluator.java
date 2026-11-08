package hermit.text.expr;

/** Evaluates what {@link ExprParser} read, exactly (milestone A3 of the annotation plan). */
public final class Evaluator {

    private Evaluator() {}

    /** The exact value of {@code expr}; an ArithmeticException when it divides by zero. */
    public static Rational evaluate(Expr expr) {
        return switch (expr) {
            case Expr.Num number -> number.value();
            case Expr.Neg negation -> evaluate(negation.operand()).negate();
            case Expr.Binary binary -> {
                Rational left = evaluate(binary.left());
                Rational right = evaluate(binary.right());
                yield switch (binary.op()) {
                    case '+' -> left.add(right);
                    case '-' -> left.subtract(right);
                    case '*' -> left.multiply(right);
                    case '/' -> left.divide(right);
                    default -> throw new IllegalArgumentException("Unknown operator: " + binary.op());
                };
            }
        };
    }

    /** Whether {@code comparison} holds for the exact values of its two sides. */
    public static boolean holds(Comparison comparison) {
        int order = evaluate(comparison.left()).compareTo(evaluate(comparison.right()));
        return switch (comparison.op()) {
            case "<" -> order < 0;
            case "<=" -> order <= 0;
            case ">" -> order > 0;
            case ">=" -> order >= 0;
            case "=" -> order == 0;
            case "!=" -> order != 0;
            default -> throw new IllegalArgumentException("Unknown comparison operator: " + comparison.op());
        };
    }

    /** The value of the whole of {@code text} as {@link ExprParser#parse} reads it. */
    public static Rational valueOf(String text) {
        return evaluate(ExprParser.parse(text));
    }
}
