package hermit.plan;

import java.util.List;

/**
 * Result of parsing one LLM planning response. Exactly one shape is set:
 * finalAnswer (Shape B, terminate with an answer), clarification (Shape C,
 * terminate by asking the user a question), or branches (Shape A, continue).
 */
public record ParsedPlan(String finalAnswer, String clarification, List<Branch> branches) {

    public boolean isFinal() {
        return finalAnswer != null;
    }

    public boolean isClarify() {
        return clarification != null;
    }
}
