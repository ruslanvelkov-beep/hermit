package hermit.claims;

import hermit.evidence.Evidence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One atomic claim about the code under analysis - from the request, an answer draft, a reviewer's dispute or a plan
 * - with its mapping to code and its evidence. Plain public fields: the claim travels in the turn's snapshot
 * (Jackson) and in checkpoints.
 */
public class Claim {

    public enum Status { OPEN, SUPPORTED, REFUTED, UNDECIDED }

    /** Where a claim came from. */
    public enum Origin { REQUEST, ANSWER, REVIEWER, PLAN }

    public String id;
    public String text;
    public Origin origin;
    public String parentId;               // the compound claim it was split from, or null
    public Status status = Status.OPEN;
    public int iteration;                 // the iteration it was added
    /** The code hypothesis: entry, targets, path, condition, observable, expected (see the claim inspector). */
    public Map<String, Object> hypothesis = new LinkedHashMap<>();
    public List<Evidence> evidence = new ArrayList<>();
    /** Reviewer disputes and other notes; a note never decides a claim, only evidence does. */
    public List<String> notes = new ArrayList<>();
    /** The atomic claims a compound claim was split into; the compound is decided by them (one refuted part
     * refutes it). */
    public List<Claim> parts = new ArrayList<>();
    /** Why a claim stayed UNDECIDED and what would settle it. */
    public String undecidedReason;

    public Claim() {}

    public Claim(String id, String text, Origin origin, int iteration) {
        this.id = id;
        this.text = text;
        this.origin = origin;
        this.iteration = iteration;
    }

    public boolean decided() {
        return status == Status.SUPPORTED || status == Status.REFUTED;
    }

    /** The evidence that decided the claim, or null. */
    public Evidence decidingEvidence() {
        for (int i = evidence.size() - 1; i >= 0; i--) {
            if (evidence.get(i).decides) {
                return evidence.get(i);
            }
        }
        return null;
    }
}
