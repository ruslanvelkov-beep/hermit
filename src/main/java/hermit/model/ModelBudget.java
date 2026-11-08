package hermit.model;

import java.util.HashMap;
import java.util.Map;

/**
 * A model's own input budget, in tokens: {@code -Dhermit.model.inputTokens=<model>=<tokens>,...}. A caller sizes its
 * prompt for the kind of model it asks (a hosted model's window); a model with a budget of its own is sent no more than
 * that, whichever caller asks it. GPT-OSS 120B starts at 32k tokens, though its window is larger: a bigger context is
 * not a better answer by itself.
 */
public final class ModelBudget {

    private ModelBudget() {}

    /** The model's configured input budget in tokens, or null when it has none. */
    public static Integer inputTokens(String model) {
        return budgets().get(model);
    }

    /** The characters a prompt to {@code model} may hold: its own budget, or {@code fallbackTokens} when it has none. */
    public static int chars(String model, int fallbackTokens) {
        Integer own = inputTokens(model);
        return (int) ((own != null ? own : fallbackTokens) * HostedBudget.CHARS_PER_TOKEN);
    }

    /** The prompt, cut to the model's own budget when it has one and the prompt is longer: the opening and the end kept. */
    public static String fit(String model, String prompt) {
        Integer own = inputTokens(model);
        int max = own == null ? Integer.MAX_VALUE : chars(model, own);
        if (prompt == null || prompt.length() <= max) {
            return prompt;
        }
        String cut = "\n…[" + (prompt.length() - max) + " chars cut to fit " + model + "'s input budget]…\n";
        int head = Math.min(6000, max / 3);
        int tail = Math.max(0, max - head - cut.length());
        System.err.printf("[ModelBudget] %s: %d of %d chars sent (%d tokens)%n", model, max, prompt.length(), own);
        return prompt.substring(0, head) + cut + prompt.substring(prompt.length() - tail);
    }

    private static Map<String, Integer> budgets() {
        Map<String, Integer> out = new HashMap<>();
        for (String entry : System.getProperty("hermit.model.inputTokens", "").split(",")) {
            int eq = entry.lastIndexOf('=');
            if (eq > 0) {
                try {
                    out.put(entry.substring(0, eq).strip(), Integer.parseInt(entry.substring(eq + 1).strip()));
                } catch (NumberFormatException ignored) {
                    // a malformed entry gives that model no budget
                }
            }
        }
        return out;
    }
}
