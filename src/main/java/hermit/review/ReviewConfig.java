package hermit.review;

import hermit.model.EndpointPolicy;

/**
 * When the optional reviewers run. The plan-review guard and the repeat detector send the request, the context and the
 * plans to a structured reviewer: they run when one is configured, and in a local-only run only when asked for
 * ({@code -Dhermit.review.guardrails=true}).
 */
public final class ReviewConfig {

    private ReviewConfig() {}

    public static boolean guardrailsEnabled(StructuredReviewer reviewer) {
        return reviewer != null && (!EndpointPolicy.localOnly() || Boolean.getBoolean("hermit.review.guardrails"));
    }
}
