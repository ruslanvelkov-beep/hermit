package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.claims.Claim;
import hermit.claims.ClaimsGate;
import hermit.evidence.Evidence;
import hermit.review.ReviewPanel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The claims of a request and the claims a second reviewer disputes, as the claims gate reads them. */
class AgentClaimsTest {

    @Test
    void theRequestsClaimsAreItsFindingWhenItHasOne() {
        String request = "Context first.\nTHE FINDING\nResetting a thermostat needs its service code.\n"
                + "YOUR TASK\nDecide it.";
        assertEquals("THE FINDING\nResetting a thermostat needs its service code.\n", ClaimsGate.claimsTextOf(request));
        assertEquals("Is X called twice?", ClaimsGate.claimsTextOf("Is X called twice?"));
        assertEquals("", ClaimsGate.claimsTextOf(null));
    }

    @Test
    void eachDisputedClaimKeepsItsReason() {
        String issue = "A second reviewer (DeepSeek) read your verdict against the evidence and finds these claims "
                + "have NO line behind them or a line CONTRADICTS them: "
                + "Resetting a thermostat requires its service code — no line shows it | "
                + "sameSetpoint ignores the zone — SetpointUtils.java:40 compares it; "
                + "the verdict does not follow from the supported claims alone — note. Drop each such claim or "
                + "cite the line that shows it, then re-decide the verdict.";
        List<String[]> disputes = ClaimsGate.disputesOf(issue);
        assertEquals(2, disputes.size());
        assertEquals("Resetting a thermostat requires its service code", disputes.get(0)[0]);
        assertEquals("no line shows it", disputes.get(0)[1]);
        assertEquals("sameSetpoint ignores the zone", disputes.get(1)[0]);
        assertEquals("SetpointUtils.java:40 compares it", disputes.get(1)[1]);
        assertTrue(ClaimsGate.disputesOf("A second reviewer finds that the verdict does not follow").isEmpty());
        assertTrue(ClaimsGate.disputesOf(null).isEmpty());
    }

    @Test
    void aChangedRulingIsReviewedAgainPastTheBudgetButNotForever() {
        assertTrue(ReviewPanel.reviewVerdict(1, 2, null, "FALSE POSITIVE"));
        assertTrue(!ReviewPanel.reviewVerdict(2, 2, "FALSE POSITIVE", "FALSE POSITIVE"), "the same ruling again is not");
        assertTrue(ReviewPanel.reviewVerdict(2, 2, "FALSE POSITIVE", "TRUE POSITIVE"));
        assertTrue(!ReviewPanel.reviewVerdict(5, 2, "FALSE POSITIVE", "TRUE POSITIVE"), "three extra reviews at most");
    }

    @Test
    void anUndecidedClaimStatedAsSupportedIsCaught() {
        Claim c17 = new Claim("C17", "Queued adjustments apply to the neighbouring zone",
                Claim.Origin.REQUEST, 0);
        c17.status = Claim.Status.UNDECIDED;
        Claim c4 = new Claim("C4", "sameSetpoint compares the target temperature", Claim.Origin.REQUEST, 0);
        c4.status = Claim.Status.SUPPORTED;
        String overstated = "VERDICT\n\n1. Identity (Supported): sameSetpoint compares the target temperature (C4).\n"
                + "2. Zone redirection (Supported): queued adjustments apply to the neighbouring zone (Claim C17).";
        assertEquals(List.of("C17"), ClaimsGate.undecidedStatedAsFact(overstated, List.of(c17, c4)));
        String honest = "VERDICT\n\n1. Identity: sameSetpoint compares the target temperature (C4).\n"
                + "2. Whether queued adjustments apply to the neighbouring zone (C17) is undecided: no line shows it.";
        assertTrue(ClaimsGate.undecidedStatedAsFact(honest, List.of(c17, c4)).isEmpty());
        String overridden = "VERDICT\n\n4. Downstream impact: queued adjustments apply to the neighbouring zone "
                + "(Claim C17 is Undecided in the ledger but the mechanism is proven by C9).";
        assertEquals(List.of("C17"), ClaimsGate.undecidedStatedAsFact(overridden, List.of(c17, c4)));
        String restated = "VERDICT\n\n3. Impact (Supported): the controller applies them without checks. "
                + "Queued adjustments apply to the neighbouring zone. So the finding holds.";
        assertEquals(List.of("C17"), ClaimsGate.undecidedStatedAsFact(restated, List.of(c17, c4)));
    }

    @Test
    void aRefusalQuotesTheSentenceThatRestatedTheRefutedClaim() {
        Claim c = new Claim("C32", "A second tenant cannot reuse the first tenant's door code",
                Claim.Origin.ANSWER, 3);
        Evidence p = new Evidence(Evidence.Kind.LOGIC, Claim.Status.REFUTED, true,
                "the code can be typed on any keypad; entering it is what opens the door");
        c.evidence.add(p);
        c.status = Claim.Status.REFUTED;
        String note = ClaimsGate.refutedNote(c, "It cannot reuse the door code, so no clash.");
        assertTrue(note.contains("your sentence \"It cannot reuse the door code, so no clash.\"")
                && note.contains("entering it"), note);
    }
}
