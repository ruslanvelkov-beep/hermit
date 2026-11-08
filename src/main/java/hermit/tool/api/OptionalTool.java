package hermit.tool.api;

/**
 * A tool that depends on an external resource which may not be present (a remote
 * endpoint on the LAN, a service, a device). It is registered and ON by default,
 * but AUTO-DETECTS availability: the agent probes it at startup and, when the
 * resource is unreachable, surfaces a yellow notice instead of failing silently.
 */
public interface OptionalTool {

    /** Probe the external dependency; true if the tool can actually be used now. */
    boolean isAvailable();

    /** Short human label for the dependency, e.g. the endpoint being probed. */
    String endpointLabel();
}
