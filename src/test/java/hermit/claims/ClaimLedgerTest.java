package hermit.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.evidence.Evidence;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The turn's claims: one entry per claim, matched across restatements, rendered with their evidence. */
class ClaimLedgerTest {

    @Test
    void aRestatedClaimIsTheSameEntryAndANegatedOneIsNot() {
        List<Claim> ledger = new ArrayList<>();
        Claim a = ClaimLedger.add(ledger,
                "sameShelf does not compare the genre field or the author field",
                Claim.Origin.REQUEST, 0, null);
        Claim b = ClaimLedger.add(ledger,
                "sameShelf does not compare the author field or the genre field.",
                Claim.Origin.ANSWER, 3, null);
        assertSame(a, b);
        assertEquals(1, ledger.size());
        Claim c = ClaimLedger.add(ledger,
                "The second librarian cannot withdraw the loan the first one opened",
                Claim.Origin.ANSWER, 4, null);
        assertNull(ClaimLedger.match(ledger,
                "The second librarian can withdraw the loan the first one opened"),
                "a flipped claim is a different claim");
        assertEquals("C2", c.id);
        assertTrue(ClaimLedger.negated("the handler doesn't check it")
                && !ClaimLedger.negated("it is not true that it does not"));
    }

    @Test
    void theBlockShowsEachClaimWithItsEvidenceOrWhatIsMissing() {
        List<Claim> ledger = new ArrayList<>();
        Claim a = ClaimLedger.add(ledger, "Withdrawing a loan requires its desk stamp",
                Claim.Origin.REQUEST, 0, null);
        Evidence p = new Evidence(Evidence.Kind.STATIC, Claim.Status.SUPPORTED, true,
                "check requires deskStampOrThrow()");
        p.anchors.add("LoanDesk.java:76");
        a.evidence.add(p);
        a.status = Claim.Status.SUPPORTED;
        Claim b = ClaimLedger.add(ledger, "Extending a loan requires its desk stamp",
                Claim.Origin.ANSWER, 2, null);
        b.notes.add("reviewer: no line shows it");
        String block = ClaimLedger.render(ledger);
        assertTrue(block.contains("C1 [SUPPORTED] (request) Withdrawing a loan")
                && block.contains("evidence (STATIC): check requires")
                && block.contains("LoanDesk.java:76"), block);
        assertTrue(block.contains("C2 [OPEN] (answer)")
                && block.contains("TO MAP — disputed: reviewer"), block);
        assertTrue(ClaimLedger.requestClaimsMapped(ledger), "only the request's claims gate the verdict");
        assertEquals("", ClaimLedger.render(List.of()));
    }
}
