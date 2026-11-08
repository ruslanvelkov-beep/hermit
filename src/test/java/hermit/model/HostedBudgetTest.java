package hermit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A hosted prompt's input budget: 32k tokens by default,
 * up to the ceiling when the payload carries several source files.
 */
class HostedBudgetTest {

    @AfterEach
    void reset() {
        System.clearProperty("hermit.hosted.inputTokens");
        System.clearProperty("hermit.hosted.inputCeilingTokens");
    }

    @Test
    void theBudgetIs32kTokensAndTheCeilingForSourceHeavyPayloads() {
        assertEquals((int) (32_000 * HostedBudget.CHARS_PER_TOKEN),
                HostedBudget.charsFor("a plain context with one Foo.java read"));
        String sources = "Bash => 36: ... PageCache.java\nBash => 76: ... ThumbnailRenderer.java\n"
                + "Bash => 201: ... ImageLoader.java\n";
        assertEquals(3, HostedBudget.sourceFilesIn(sources));
        assertEquals((int) (96_000 * HostedBudget.CHARS_PER_TOKEN), HostedBudget.charsFor(sources));
        assertTrue(HostedBudget.charsFor(sources) > 16_000 * 4, "always more than 16k tokens");
    }

    @Test
    void bothLimitsAreConfigurableAndTheCeilingNeverFallsBelowTheBase() {
        System.setProperty("hermit.hosted.inputTokens", "48000");
        System.setProperty("hermit.hosted.inputCeilingTokens", "20000");
        assertEquals((int) (48_000 * HostedBudget.CHARS_PER_TOKEN),
                HostedBudget.charsFor("A.java B.java C.java D.java"));
    }
}
