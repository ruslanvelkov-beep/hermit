package hermit.text.expr;

/**
 * A comparison read from text by {@link ExprParser#parseComparison}: two expressions and the operator between them,
 * one of {@code <}, {@code <=}, {@code >}, {@code >=}, {@code =} and {@code !=} (the typed signs and {@code ==} read as
 * these).
 */
public record Comparison(Expr left, String op, Expr right) {}
