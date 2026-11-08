package hermit.answer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the deterministic {@link ResultFormatter}s and dispatches a tool result to
 * the first one that can format it. Empty Optional means "no deterministic answer
 * available - fall back to the model."
 */
public final class FormatterRegistry {

    private final List<ResultFormatter> formatters = List.of(
            new CodeHostResultFormatter());

    /** Deterministic answer for a successful tool result, if any formatter handles it. */
    public Optional<String> format(String toolName, Map<String, Object> result) {
        if (toolName == null || result == null) {
            return Optional.empty();
        }
        for (ResultFormatter f : formatters) {
            if (f.canFormat(toolName, result)) {
                try {
                    return Optional.ofNullable(f.format(toolName, result));
                } catch (Exception ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
