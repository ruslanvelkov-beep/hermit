package hermit.text.check;

import com.fasterxml.jackson.databind.node.ObjectNode;
import hermit.text.expr.Comparison;
import hermit.text.expr.Evaluator;
import hermit.text.expr.ExprParser;
import hermit.text.expr.Rational;
import hermit.text.model.Check;
import hermit.text.model.Span;
import java.util.ArrayList;
import java.util.List;

/**
 * Arithmetic (milestone A5 of the annotation plan, section 7: 2 + 2 = 5), checker {@code arithmetic}. A statement is an
 * EXPRESSION span whose value {@link ExprParser#parseComparison} reads whole: two expressions and a comparison operator
 * between them. It is CONSISTENT when {@link Evaluator#holds} says the comparison holds, exactly (reason
 * {@code holds}), and INCONSISTENT when it does not (reason {@code does-not-hold}); a division by zero on either side
 * makes it UNVERIFIABLE (reason {@code division-by-zero}). An expression without a comparison states nothing and is
 * not checked.
 * Evidence: {@code {"left": "4", "op": "=", "right": "5"}}, the operator as the parser reports it and the two sides'
 * exact values as {@link Rational#toString} writes them (only the operator on a division by zero). Target: the span.
 * Checks come in text order.
 */
public final class ArithmeticChecker implements Checker {

    /** {@code arithmetic}. */
    @Override
    public String name() {
        return "arithmetic";
    }

    /** The checks of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Check> check(Input input) {
        List<Check> checks = new ArrayList<>();
        for (Span span : input.spans()) {
            if (span.concept() != Span.Concept.EXPRESSION) {
                continue;
            }
            Comparison comparison;
            try {
                comparison = ExprParser.parseComparison(span.value().asText());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ObjectNode evidence = Checks.JSON.objectNode();
            Check.Status status;
            String reason;
            try {
                Rational left = Evaluator.evaluate(comparison.left());
                Rational right = Evaluator.evaluate(comparison.right());
                boolean holds = Evaluator.holds(comparison);
                evidence.put("left", left.toString());
                evidence.put("op", comparison.op());
                evidence.put("right", right.toString());
                status = holds ? Check.Status.CONSISTENT : Check.Status.INCONSISTENT;
                reason = holds ? "holds" : "does-not-hold";
            } catch (ArithmeticException ignored) {
                evidence.put("op", comparison.op());
                status = Check.Status.UNVERIFIABLE;
                reason = "division-by-zero";
            }
            checks.add(Checks.check(name(), status, List.of(span), evidence, List.of(), List.of(reason)));
        }
        return checks;
    }
}
