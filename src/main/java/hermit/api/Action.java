/* A single, actionable step for the agent to perform. */
package hermit.api;

import java.util.Map;

public record Action(String toolName, Map<String, Object> parameters) {}
