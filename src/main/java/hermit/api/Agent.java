package hermit.api;

/**
 * Contract for the main agent responsible for planning, reasoning (CoT),
 * and orchestrating tool usage.
 */
public interface Agent {

    /**
     * The main entry point for the agent to process a user request.
     * @param userRequest A string describing the goal (e.g., "Refactor this function for better performance").
     * @return The final result or status message.
     */
    String processRequest(String userRequest);
}
