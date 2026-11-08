package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArithmeticCheckerTest {

    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("UTC"), "en");

    private static List<Check> checks(String text) {
        return new ArithmeticChecker().check(Checkers.input(text, CONTEXT));
    }

    private static List<Check.Status> statuses(String text) {
        return checks(text).stream().map(Check::status).toList();
    }

    @Test
    void twoPlusTwoIsNotFive() {
        List<Check> checks = checks("We computed 2 + 2 = 5.");
        assertEquals(1, checks.size());
        Check check = checks.get(0);
        assertEquals("arithmetic", check.checker());
        assertEquals(Check.Status.INCONSISTENT, check.status());
        assertEquals(List.of("does-not-hold"), check.reasons());
        assertEquals("4", check.evidence().path("left").asText());
        assertEquals("=", check.evidence().path("op").asText());
        assertEquals("5", check.evidence().path("right").asText());
        assertEquals(1, check.targets().size());
    }

    @Test
    void exactDecimalsHold() {
        Check check = checks("The sum 0.1 + 0.2 = 0.3 holds.").get(0);
        assertEquals(Check.Status.CONSISTENT, check.status(), "exact, not binary floating point");
        assertEquals(List.of("holds"), check.reasons());
        assertEquals("3/10", check.evidence().path("left").asText());
    }

    @Test
    void comparisonsAreChecked() {
        assertEquals(List.of(Check.Status.CONSISTENT), statuses("We checked that 3 * 4 > 11."));
        assertEquals(List.of(Check.Status.INCONSISTENT, Check.Status.CONSISTENT),
                statuses("We checked that 2 * 3 < 5 and 10 / 4 = 2.5 too."));
    }

    @Test
    void aDivisionByZeroIsUnverifiable() {
        Check check = checks("Then 7 / 0 = 1 is wrong.").get(0);
        assertEquals(Check.Status.UNVERIFIABLE, check.status());
        assertEquals(List.of("division-by-zero"), check.reasons());
        assertEquals("{\"op\":\"=\"}", check.evidence().toString());
    }

    @Test
    void anExpressionWithoutAComparisonIsNotChecked() {
        assertEquals(List.of(), checks("The values 2 + 2 and 5 differ."));
    }

    @Test
    void expressionsAreCheckedInTextOrder() {
        List<Check> checks = checks("First 1 + 1 = 2, then 2 + 2 = 5.");
        assertEquals(List.of(Check.Status.CONSISTENT, Check.Status.INCONSISTENT),
                checks.stream().map(Check::status).toList());
        assertEquals(2, checks.stream().map(c -> c.targets().get(0)).distinct().count());
    }
}
