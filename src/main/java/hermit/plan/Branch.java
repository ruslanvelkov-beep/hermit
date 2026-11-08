/* One proposed path in the decision tree: a named strategy with a confidence score and concrete actions. */
package hermit.plan;

import hermit.api.Action;
import java.util.List;

public record Branch(String name, String description, double confidence, List<Action> actions) {}
