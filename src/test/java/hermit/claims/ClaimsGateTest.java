package hermit.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.evidence.Evidence;
import hermit.review.ReviewPanel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The claims of a request and the claims a second reviewer disputes, as the claims gate reads them. */
class ClaimsGateTest {

    @Test
    void theRequestsClaimsAreItsFindingWhenItHasOne() {
        String request = "Context first.\nTHE FINDING\nArchiving a document needs its editor role.\n"
                + "YOUR TASK\nDecide it.";
        assertEquals("THE FINDING\nArchiving a document needs its editor role.\n", ClaimsGate.claimsTextOf(request));
        assertEquals("Is X called twice?", ClaimsGate.claimsTextOf("Is X called twice?"));
        assertEquals("", ClaimsGate.claimsTextOf(null));
    }

    @Test
    void eachDisputedClaimKeepsItsReason() {
        String issue = "A second reviewer (DeepSeek) read your verdict against the evidence and finds these claims "
                + "have NO line behind them or a line CONTRADICTS them: "
                + "Archiving a document requires its editor role — no line shows it | "
                + "sameRevision ignores the author — RevisionUtils.java:40 compares it; "
                + "the verdict does not follow from the supported claims alone — note. Drop each such claim or "
                + "cite the line that shows it, then re-decide the verdict.";
        List<String[]> disputes = ClaimsGate.disputesOf(issue);
        assertEquals(2, disputes.size());
        assertEquals("Archiving a document requires its editor role", disputes.get(0)[0]);
        assertEquals("no line shows it", disputes.get(0)[1]);
        assertEquals("sameRevision ignores the author", disputes.get(1)[0]);
        assertEquals("RevisionUtils.java:40 compares it", disputes.get(1)[1]);
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
        Claim c17 = new Claim("C17", "Late edits land on the archived copy",
                Claim.Origin.REQUEST, 0);
        c17.status = Claim.Status.UNDECIDED;
        Claim c4 = new Claim("C4", "sameRevision compares the checksum", Claim.Origin.REQUEST, 0);
        c4.status = Claim.Status.SUPPORTED;
        String overstated = "VERDICT\n\n1. Identity (Supported): sameRevision compares the checksum (C4).\n"
                + "2. Edit redirection (Supported): late edits land on the archived copy (Claim C17).";
        assertEquals(List.of("C17"), ClaimsGate.undecidedStatedAsFact(overstated, List.of(c17, c4)));
        String honest = "VERDICT\n\n1. Identity: sameRevision compares the checksum (C4).\n"
                + "2. Whether late edits land on the archived copy (C17) is undecided: no line shows it.";
        assertTrue(ClaimsGate.undecidedStatedAsFact(honest, List.of(c17, c4)).isEmpty());
        String overridden = "VERDICT\n\n4. Downstream impact: late edits land on the archived copy (Claim C17 is "
                + "Undecided in the ledger but the mechanism is proven by C9).";
        assertEquals(List.of("C17"), ClaimsGate.undecidedStatedAsFact(overridden, List.of(c17, c4)));
        String restated = "VERDICT\n\n3. Impact (Supported): the store saves them without checks. Late edits land on "
                + "the archived copy. So the finding holds.";
        assertEquals(List.of("C17"), ClaimsGate.undecidedStatedAsFact(restated, List.of(c17, c4)));
    }

    @Test
    void aRefusalQuotesTheSentenceThatRestatedTheRefutedClaim() {
        Claim c = new Claim("C32", "A second user cannot reproduce the first user's share link",
                Claim.Origin.ANSWER, 3);
        Evidence p = new Evidence(Evidence.Kind.LOGIC, Claim.Status.REFUTED, true,
                "the link can be pasted into any message; opening it is what grants access");
        c.evidence.add(p);
        c.status = Claim.Status.REFUTED;
        String note = ClaimsGate.refutedNote(c, "It cannot reproduce the share link, so no overlap.");
        assertTrue(note.contains("your sentence \"It cannot reproduce the share link, so no overlap.\"")
                && note.contains("opening it"), note);
    }
}
