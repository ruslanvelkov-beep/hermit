package hermit.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A helper that fails is marked down with its reason in the log, not silently. */
class HelperChainFailureTest {

    @Test
    void aFailingHelperIsMarkedDownWithItsReasonLogged() {
        HelperChain.Helper flaky = new HelperChain.Helper() {
            @Override public String name() { return "lan"; }
            @Override public boolean probe() { return true; }
            @Override public String ask(String task, String mode, String context) {
                throw new IllegalStateException("RemoteAgent call failed: HttpTimeoutException request timed out");
            }
        };
        PrintStream err = System.err;
        String disabledBefore = System.getProperty("hermit.chain.disabled");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            System.clearProperty("hermit.chain.disabled");
            HelperChain chain = new HelperChain(List.of(flaky), 0);
            assertTrue(chain.anyAvailable(), "probed up");
            assertNull(chain.consult("review this", "answer", null));
            assertFalse(chain.anyAvailable(), "marked down after the failure");
            assertNull(chain.consult("again", "answer", null));
        } finally {
            System.setErr(err);
            if (disabledBefore == null) {
                System.clearProperty("hermit.chain.disabled");
            } else {
                System.setProperty("hermit.chain.disabled", disabledBefore);   // leave the JVM as it was
            }
        }
        String log = captured.toString();
        assertTrue(log.contains("[HelperChain] lan failed after") && log.contains("HttpTimeoutException")
                && log.contains("marked down"), log);
        assertTrue(log.contains("no helper is up"), log);
    }

    // A LAN helper's prompt is cut to its memory budget, opening and end kept
    @Test
    void aPromptIsCutToTheHelpersBudgetKeepingItsOpeningAndEnd() {
        String prompt = "You are a SECOND REVIEWER. " + "evidence ".repeat(2000)
                + " PROPOSED VERDICT: REFUTED. Respond with ONE JSON object.";
        String fitted = HelperChain.fitToBudget(prompt, 6000);
        assertTrue(fitted.length() <= 6000, "" + fitted.length());
        assertTrue(fitted.startsWith("You are a SECOND REVIEWER."), fitted.substring(0, 40));
        assertTrue(fitted.endsWith("Respond with ONE JSON object."), fitted.substring(fitted.length() - 60));
        assertTrue(fitted.contains("chars cut to fit this helper's memory"), fitted);
        String shortOne = prompt.substring(0, 100);
        assertSame(shortOne, HelperChain.fitToBudget(shortOne, 6000),
                "a short prompt passes through untouched");
        assertNull(HelperChain.fitToBudget(null, 10));
    }
}
