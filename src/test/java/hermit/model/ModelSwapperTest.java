package hermit.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Swap mode is OFF unless -Dhermit.swap.enabled=true, so on any normal host (and in CI)
 * the ensure* calls must be inert no-ops - they must never shell out or throw.
 */
class ModelSwapperTest {

    @Test
    void disabledByDefault() {
        assertFalse(ModelSwapper.enabled(), "swap must be off unless explicitly enabled");
    }

    @Test
    void ensureCallsAreNoOpsWhenDisabled() {
        assertDoesNotThrow(ModelSwapper::ensureDriver);
        assertDoesNotThrow(ModelSwapper::ensureRefiner);
    }

    @Test
    void exposesDefaultModelKeys() {
        assertEquals("qwen2.5-coder-7b-instruct", ModelSwapper.driverModel());
        assertEquals("qwen2.5-coder-14b-instruct-mlx", ModelSwapper.refinerModel());
    }
}
