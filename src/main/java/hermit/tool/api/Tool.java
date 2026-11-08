package hermit.tool.api;

import java.util.Map;

/**
 * Contract for any tool that the Coding Agent can utilize.
 * This ensures all tools provide consistent methods for the Agent to interact with them.
 */
public interface Tool {

    /**
     * Provides a human-readable description of the tool and how to use it.
     * @return A string containing the full documentation/README for the tool.
     */
    String getDocumentation();

    /**
     * Executes the tool with the given parameters.
     * @param parameters A map of string keys to the necessary values for execution.
     * @return A Map containing the structured result/output from the tool.
     */
    Map<String, Object> execute(Map<String, Object> parameters);

}
