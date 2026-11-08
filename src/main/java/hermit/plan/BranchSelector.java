/* Determines the best next step based on defined criteria (highest confidence). */
package hermit.plan;

import java.util.List;

public class BranchSelector {
    public Branch selectBest(List<Branch> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        Branch best = options.get(0);
        for (Branch branch : options) {
            if (branch.confidence() > best.confidence()) {
                best = branch;
            }
        }
        return best;
    }
}
