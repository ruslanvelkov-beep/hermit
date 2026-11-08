package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hermit.text.expr.Rational;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnitCheckerTest {

    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("UTC"), "en");

    private static List<Check> checks(String text) {
        return new UnitChecker().check(Checkers.input(text, CONTEXT));
    }

    private static Check only(String text) {
        List<Check> checks = checks(text);
        assertEquals(1, checks.size(), text);
        return checks.get(0);
    }

    @Test
    void unitDimensionsMustMatch() {
        Check check = only("We downloaded 2 GB, waited 3 minutes and downloaded 1 GB, 3 GB in total.");
        assertEquals("unit", check.checker());
        assertEquals(Check.Status.CONSISTENT, check.status(), "the minutes are no part of a total in GB");
        assertEquals(3, check.targets().size(), "two parts and the total");
        assertEquals("data", check.evidence().path("dimension").asText());
        assertEquals(2, check.evidence().path("parts").asInt());
    }

    @Test
    void durationsAddUpExactly() {
        Check check = only("It took 2 minutes, 90 seconds and 1 min, 4.5 minutes in total.");
        assertEquals(Check.Status.CONSISTENT, check.status());
        assertEquals(List.of("total-matches"), check.reasons());
        assertEquals("270", check.evidence().path("readings").get(0).path("sum").asText());
        assertEquals("270", check.evidence().path("readings").get(0).path("total").asText());
    }

    @Test
    void aWrongTotalIsInconsistent() {
        Check check = only("It took 2 minutes and 1 hour, 3 minutes in total.");
        assertEquals(Check.Status.INCONSISTENT, check.status());
        assertEquals(List.of("total-mismatch"), check.reasons());
    }

    @Test
    void decimalAndBinaryPrefixesCanDisagree() {
        Check check = only("We used 1 KB and 1000 B, 2 KB in total.");
        assertEquals(Check.Status.UNVERIFIABLE, check.status(), "true for 1000 bytes a KB, false for 1024");
        assertEquals(List.of("ambiguous-units"), check.reasons());
        assertEquals(Check.Status.CONSISTENT, only("We used 2 GB and 3 GB, 5 GB in total.").status());
        assertEquals(Check.Status.INCONSISTENT, only("We used 1 GiB and 1 GiB, 3 GiB in total.").status());
    }

    @Test
    void monthsHaveNoExactLength() {
        Check check = only("The trip took 2 months and 1 month, 3 months in total.");
        assertEquals(Check.Status.UNVERIFIABLE, check.status());
        assertEquals(List.of("calendar-units"), check.reasons());
    }

    @Test
    void currenciesAddUpInTheirOwnUnits() {
        assertEquals(Check.Status.CONSISTENT, only("It cost 5 USD and 3 USD, a total of 8 USD.").status());
        assertEquals(List.of(), checks("It cost 5 USD and 3 EUR, 8 USD in total."), "one part in US dollars");
    }

    @Test
    void secondsAndBytes() {
        assertEquals(Rational.of(120, 1), UnitChecker.seconds("PT2M"));
        assertEquals(Rational.of(9000, 1), UnitChecker.seconds("PT2.5H"));
        assertEquals(Rational.of(172_800, 1), UnitChecker.seconds("P2D"));
        assertEquals(Rational.of(1, 2), UnitChecker.seconds("PT0.5S"));
        assertNull(UnitChecker.seconds("P2M"), "two months");
        assertEquals(Rational.of(1000, 1), UnitChecker.bytes("KB", false));
        assertEquals(Rational.of(1024, 1), UnitChecker.bytes("KB", true));
        assertEquals(Rational.of(1_073_741_824, 1), UnitChecker.bytes("GiB", false));
        assertEquals(Rational.ONE, UnitChecker.bytes("B", true));
        assertNull(UnitChecker.bytes("USD", false));
    }

    @Test
    void aSinglePartIsNoTotal() {
        assertEquals(List.of(), checks("It took 2 minutes, 2 minutes in total."));
    }
}
