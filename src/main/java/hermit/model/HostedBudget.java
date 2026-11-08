package hermit.model;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much of the turn a hosted model is sent. The caps the local helpers need (a 16 GB machine: 2.5k-14k characters)
 * starve a hosted model with a 128k-token window, so a hosted prompt gets its own budget, case by case:
 * {@code -Dhermit.hosted.inputTokens} (default 32k) for the usual payload, and up to
 * {@code -Dhermit.hosted.inputCeilingTokens} (default 96k: the window less 32k for reasoning and the answer)
 * when the payload carries reads of several source files - code under analysis is what a stronger model needs whole.
 */
public final class HostedBudget {

    private HostedBudget() {}

    /** Code and logs tokenize denser than prose; a conservative ratio keeps the budget inside the window. */
    public static final double CHARS_PER_TOKEN = 3.2;
    static final int SOURCE_HEAVY_FILES = 3;
    private static final Pattern SOURCE_FILE = Pattern.compile("([A-Z][A-Za-z0-9_]*)\\.(?:java|kt|proto)\\b");

    static int baseTokens() {
        return Integer.getInteger("hermit.hosted.inputTokens", 32_000);
    }

    static int ceilingTokens() {
        return Math.max(baseTokens(), Integer.getInteger("hermit.hosted.inputCeilingTokens", 96_000));
    }

    /** Distinct source files the payload names (reads, paths, citations). */
    static int sourceFilesIn(String payload) {
        Set<String> names = new HashSet<>();
        Matcher m = SOURCE_FILE.matcher(payload == null ? "" : payload);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names.size();
    }

    /**
     * The characters of {@code payload} a hosted prompt may carry: the base budget,
     * the ceiling for source-heavy payloads.
     */
    public static int charsFor(String payload) {
        int tokens = sourceFilesIn(payload) >= SOURCE_HEAVY_FILES ? ceilingTokens() : baseTokens();
        return (int) (tokens * CHARS_PER_TOKEN);
    }

    /** Logs what a hosted call is sent: its size against the budget, and whether the payload had to be cut. */
    public static void note(String point, String payload, int sentChars) {
        int full = payload == null ? 0 : payload.length();
        System.err.printf("[HostedBudget] %s: %d of %d chars sent (~%d tokens)%s%n", point, Math.min(full, sentChars), full,
                (int) (Math.min(full, sentChars) / CHARS_PER_TOKEN), full > sentChars ? " — CUT" : "");
        ModelRouter.traceHostedInput(point, full, Math.min(full, sentChars), sourceFilesIn(payload));
    }
}
