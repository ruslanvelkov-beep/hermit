package hermit.tool.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The registry and the planner guidance agree on which tools exist in a session. */
class ToolRegistryTest {

    private final String saved = System.getProperty("hermit.claims.enabled");

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty("hermit.claims.enabled");
        } else {
            System.setProperty("hermit.claims.enabled", saved);
        }
    }

    @Test
    void theClaimsToolIsRegisteredOnlyWhenClaimsAreEnabled() {
        System.clearProperty("hermit.claims.enabled");
        ToolRegistry without = new ToolRegistry();
        assertNull(without.getTool("ClaimInspector"));
        assertNotNull(without.getTool("CodeGraph"));
        assertNotNull(without.getTool("TreeEdit"));
        System.setProperty("hermit.claims.enabled", "true");
        ToolRegistry with = new ToolRegistry();
        assertNotNull(with.getTool("ClaimInspector"));
    }
}
