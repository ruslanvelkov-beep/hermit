package hermit.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks cloud-LLM token usage so Hermit can report it after every cloud call and
 * route work to whichever platform has more headroom. Holds two things per platform:
 * cumulative session usage (summed from each response's {@code usage.total_tokens}),
 * and the last-known remaining/limit reported by the provider's rate-limit headers
 * (Cerebras exposes per-day; Groq exposes per-minute).
 */
public final class UsageLedger {

    /** One cloud call's accounting, parsed from the response body + headers. */
    public record CallUsage(
            String platform, String model,
            int promptTokens, int completionTokens, int totalTokens, int reasoningTokens,
            Long remainingTokens, Long limitTokens, String window, String resetHint) {}

    private record PlatformState(AtomicLong sessionTokens, AtomicLong calls) {}

    private static final Map<String, PlatformState> STATE = new ConcurrentHashMap<>();
    private static final Map<String, CallUsage> LAST = new ConcurrentHashMap<>();

    private UsageLedger() {}

    public static void record(CallUsage u) {
        PlatformState s = STATE.computeIfAbsent(u.platform(),
                k -> new PlatformState(new AtomicLong(), new AtomicLong()));
        s.sessionTokens().addAndGet(u.totalTokens());
        s.calls().incrementAndGet();
        LAST.put(u.platform(), u);
    }

    private static final ConcurrentHashMap<String, Long> EXHAUSTED_UNTIL = new ConcurrentHashMap<>();

    /** The platform answered a daily-quota 429 - nothing goes to it before {@code untilMillis}. */
    public static void markExhausted(String platform, long untilMillis) {
        EXHAUSTED_UNTIL.put(platform, untilMillis);
    }

    /** Tests: forget every platform's usage and exhaustion. */
    static void resetForTests() {
        STATE.clear();
        LAST.clear();
        EXHAUSTED_UNTIL.clear();
    }

    public static boolean isExhausted(String platform) {
        Long until = EXHAUSTED_UNTIL.get(platform);
        return until != null && until > System.currentTimeMillis();
    }

    public static long sessionTokens(String platform) {
        PlatformState s = STATE.get(platform);
        return s == null ? 0 : s.sessionTokens().get();
    }

    /** Last-known remaining tokens for a platform (or null if never called). */
    public static Long lastRemaining(String platform) {
        CallUsage u = LAST.get(platform);
        return u == null ? null : u.remainingTokens();
    }

    /** One-line usage summary for a single call, e.g. for the tool result. */
    public static String formatCall(CallUsage u) {
        StringBuilder sb = new StringBuilder();
        sb.append(u.platform()).append('/').append(u.model())
                .append(": this call ").append(u.totalTokens()).append(" tok (")
                .append(u.promptTokens()).append(" in + ").append(u.completionTokens()).append(" out");
        if (u.reasoningTokens() > 0) {
            sb.append(", incl ").append(u.reasoningTokens()).append(" reasoning");
        }
        sb.append(')');
        if (u.remainingTokens() != null) {
            sb.append("; remaining ").append(u.remainingTokens());
            if (u.limitTokens() != null) {
                sb.append('/').append(u.limitTokens());
            }
            if (u.window() != null) {
                sb.append(" per ").append(u.window());
            }
            if (u.resetHint() != null && !u.resetHint().isBlank()) {
                sb.append(" (resets in ").append(u.resetHint()).append(')');
            }
        }
        sb.append("; session total on ").append(u.platform()).append(": ")
                .append(sessionTokens(u.platform())).append(" tok");
        return sb.toString();
    }

    /** Multi-platform session summary (for a /cloud-usage command or after-answer line). */
    public static String sessionSummary() {
        if (STATE.isEmpty()) {
            return "Cloud usage: none this session.";
        }
        StringBuilder sb = new StringBuilder("Cloud token usage this session:");
        for (Map.Entry<String, PlatformState> e : orderedState().entrySet()) {
            String p = e.getKey();
            sb.append("\n  ").append(p).append(": ")
                    .append(e.getValue().sessionTokens().get()).append(" tok in ")
                    .append(e.getValue().calls().get()).append(" call(s)");
            CallUsage last = LAST.get(p);
            if (last != null && last.remainingTokens() != null) {
                sb.append("; ~").append(last.remainingTokens());
                if (last.limitTokens() != null) {
                    sb.append('/').append(last.limitTokens());
                }
                sb.append(" tok remaining");
                if (last.window() != null) {
                    sb.append(" per ").append(last.window());
                }
            }
        }
        return sb.toString();
    }

    private static Map<String, PlatformState> orderedState() {
        return new LinkedHashMap<>(STATE);
    }
}
