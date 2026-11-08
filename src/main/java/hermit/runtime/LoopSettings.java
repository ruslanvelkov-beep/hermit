package hermit.runtime;

import hermit.checkpoint.TurnSnapshot;
import hermit.model.ContextWindow;
import hermit.model.HelperChain;
import hermit.model.HostedBudget;
import hermit.model.HostedPlatforms;
import hermit.plan.TaskPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The agent loop's settings read from system properties (history budget, helper context, planner models, task plan,
 * revision point), how long a rate-limited primary is left alone, whether helpers write corrective plans first, and the
 * repository root of a file.
 */
public final class LoopSettings {

    private LoopSettings() {
    }

    /**
     * The characters the planner's prompt may hold (the owner, 2026-10-26): its window ({@code -Dhermit.prompt.windowTokens},
     * 32,768: the local model, hosted Qwen and GPT-OSS-20B) less the room for its reply ({@code -Dhermit.maxTokens}, 8,192),
     * in characters; {@code -Dhermit.prompt.maxChars} overrides.
     */
    static int promptChars() {
        int window = Integer.getInteger("hermit.prompt.windowTokens", 32_768);
        int reply = Integer.getInteger("hermit.maxTokens", 8_192);
        return Integer.getInteger("hermit.prompt.maxChars", (int) ((window - reply) * HostedBudget.CHARS_PER_TOKEN));
    }

    /** The characters the history may take: what the rest of the prompt leaves of {@code promptChars}, at least 8,000. */
    static int historyChars(int promptChars, int restOfPrompt) {
        return Math.max(8_000, promptChars - restOfPrompt);
    }

    static int defaultHistoryBudget() {
        int tokens = ContextWindow.tokens();          // real context size, not assumed
        return Math.min(Math.max(4000, tokens * 3 / 2), 40_000);
    }

    /**
     * How much context a helper reads: on a hosted router two thirds of the prompt it may be sent (the rest holds the plan
     * and the instructions), on the local network {@code lanCap}, the window its machine has room for.
     */
    public static int helperContextChars(int lanCap) {
        return HostedPlatforms.platformOf(HelperChain.lanBaseUrl()) != null ? HelperChain.lanPromptChars() * 2 / 3 : lanCap;
    }

    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** The models the step planner is picked from: {@code -Dhermit.planner.models}, the lighter first; empty when unset. */
    static List<String> plannerModels() {
        return Arrays.stream(System.getProperty("hermit.planner.models", "").split(","))
                .map(String::strip).filter(m -> !m.isEmpty()).toList();
    }

    /**
     * The step planner planned the task plan's current step, and the review panel refused the plan twice for leaving
     * out the request's later steps. With a task plan, the panel judges a plan against its current step.
     */
    static String taskPlanReviewNote(TurnSnapshot live) {
        return TaskPlan.active(live) && !TaskPlan.finished(live) ? TaskPlan.block(live)
                + "REVIEW NOTE: the agent works the task plan one step at a time; this plan is for the CURRENT step only. "
                + "Judge it against that step: the steps after it come later and are not missing from this plan.\n" : "";
    }

    static boolean taskPlanEnabled() {
        return Boolean.getBoolean("hermit.plan.task");
    }

    /** Iterations on a step before its current and later steps are rewritten: -Dhermit.plan.reviseAfter (3). */
    static int reviseAfter() {
        return Math.max(1, Integer.getInteger("hermit.plan.reviseAfter", 3));
    }

    /** Whether the primary's last failure was a rate limit, and for how long it is left alone. */
    public static long exhaustionMillisFor(String failure) {
        if (failure == null || !failure.contains("429")) {
            return 0;
        }
        return failure.matches("(?is).*(per day|TPD|tokens_per_day|daily).*") ? 60 * 60 * 1000L : 2 * 60 * 1000L;
    }

    /** The helper chain writes corrective plans only when no plan-escalation model is configured. */
    public static boolean authorViaHelpersFirst(boolean escalationConfigured) {
        return !escalationConfigured;
    }

    /** The enclosing git repository of {@code file} (the nearest ancestor with a .git entry), else its directory. */
    public static Path repoRootOf(Path file) {
        Path dir = file == null ? null : (Files.isDirectory(file) ? file : file.getParent());
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                return p;
            }
        }
        return dir;
    }
}
