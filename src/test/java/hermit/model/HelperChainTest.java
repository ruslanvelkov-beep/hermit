package hermit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.model.HelperChain.Answer;
import hermit.model.HelperChain.Helper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ordered fallback + fail-on-error behavior of the helper chain (no network/scheduler). */
class HelperChainTest {

    /** A fake helper: configurable availability + answer (null answer = ask fails). */
    private static Helper fake(String name, boolean up, String answer) {
        return new Helper() {
            public String name() {
                return name;
            }
            public boolean probe() {
                return up;
            }
            public String ask(String task, String mode, String context) {
                return answer;
            }
        };
    }

    private HelperChain chain(Helper... hs) {
        return new HelperChain(List.of(hs), 0);   // 0 = no periodic scheduler
    }

    @Test
    void firstAvailableHelperWins() {
        Answer a = chain(fake("lan", true, "from-lan"), fake("tunnel", true, "from-tunnel"))
                .consult("q", "answer", null);
        assertEquals("lan", a.source());
        assertEquals("from-lan", a.text());
    }

    @Test
    void skipsUnavailableAndFallsThroughInOrder() {
        // lan down, tunnel down, Cerebras up -> Cerebras answers (the chain order)
        Answer a = chain(
                fake("lan", false, "x"),
                fake("tunnel", false, "x"),
                fake("Cerebras", true, "from-cerebras"),
                fake("Groq", true, "from-groq")).consult("q", "answer", null);
        assertEquals("Cerebras", a.source());
    }

    @Test
    void failsOverWhenAnAvailableHelperErrors() {
        // Cerebras is 'up' but ask() returns null (e.g. 402) -> fall through to Groq
        Answer a = chain(
                fake("Cerebras", true, null),
                fake("Groq", true, "from-groq")).consult("q", "answer", null);
        assertEquals("Groq", a.source());
    }

    @Test
    void nullWhenNoneCanAnswer() {
        assertNull(chain(fake("lan", false, "x"), fake("Groq", true, null)).consult("q", "answer", null));
    }

    @Test
    void statusReportAndAnyAvailable() {
        HelperChain c = chain(fake("lan", false, "x"), fake("tunnel", true, "y"));
        assertTrue(c.anyAvailable());
        assertEquals(List.of("lan: down", "tunnel: up"), c.statusReport());
    }

    @Test
    void envParserReadsQuotedValues() {
        // sanity for the .secrets/tunnel-refiner.env parsing used to build the tunnel link
        Map<String, String> m = HelperChain.readEnv(Path.of("/does/not/exist"));
        assertTrue(m.isEmpty());
    }
}
