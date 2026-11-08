/* A single node in the planning tree, capable of holding multiple potential next steps (branches). */
package hermit.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlanNode {
    private final String nodeName;
    private final String goal;
    private final Map<String, Object> context;
    private String reasoningPath;
    private List<Branch> branches = new ArrayList<>();

    public PlanNode(String nodeName, String goal, Map<String, Object> context) {
        this.nodeName = nodeName;
        this.goal = goal;
        this.context = context;
    }

    public String getNodeName() { return nodeName; }
    public String getGoal() { return goal; }
    public Map<String, Object> getContext() { return context; }
    public String getReasoningPath() { return reasoningPath; }
    public void setReasoningPath(String path) { this.reasoningPath = path; }
    public List<Branch> getBranches() { return branches; }
    public void setBranches(List<Branch> branches) { this.branches = branches; }
}
