package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionCheckerTest {

    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("UTC"), "en");

    private static List<Check> checks(String text) {
        return new VersionChecker().check(Checkers.input(text, CONTEXT));
    }

    private static Check only(String text) {
        List<Check> checks = checks(text);
        assertEquals(1, checks.size(), text);
        return checks.get(0);
    }

    @Test
    void versionTenExceedsNine() {
        Check check = only("Version 10 exceeds version 9.");
        assertEquals("version", check.checker());
        assertEquals(Check.Status.CONSISTENT, check.status(), "as versions and as numbers");
        assertEquals(List.of("order-holds"), check.reasons());
        assertEquals("10", check.evidence().path("left").asText());
        assertEquals("exceeds", check.evidence().path("phrase").asText());
        assertEquals("9", check.evidence().path("right").asText());
        assertEquals(2, check.targets().size());
    }

    @Test
    void olderThanComparesVersionsNotDecimals() {
        Check check = only("1.10 is older than 1.9.");
        assertEquals(Check.Status.INCONSISTENT, check.status(), "1.10 is the newer version");
        assertEquals(List.of("order-fails"), check.reasons());
    }

    @Test
    void newerThanReadsEachPartAsANumber() {
        assertEquals(Check.Status.CONSISTENT, only("Release 2.10 is newer than release 2.9.").status());
        assertEquals(Check.Status.CONSISTENT, only("v3.2 precedes v3.10.").status());
    }

    @Test
    void exceedsWithDisagreeingReadingsIsUnverifiable() {
        Check check = only("1.10 exceeds 1.9.");
        assertEquals(Check.Status.UNVERIFIABLE, check.status(), "true of versions, false of decimals");
        assertEquals(List.of("readings-disagree"), check.reasons());
        assertEquals(Check.Status.CONSISTENT, only("2.5 exceeds 2.4.").status(), "true of both");
    }

    @Test
    void aMissingPartIsZero() {
        assertEquals(Check.Status.INCONSISTENT, only("Version 2.0 is newer than 2.0.0.").status());
    }

    @Test
    void compareOrdersPartByPart() {
        assertTrue(VersionChecker.compare("1.10", "1.9") > 0);
        assertTrue(VersionChecker.compare("v3.2", "3.10") < 0);
        assertEquals(0, VersionChecker.compare("2.0", "2.0.0"));
        assertNull(VersionChecker.compare("1.x", "1.0"));
    }

    @Test
    void withoutAPhraseNothingIsChecked() {
        assertEquals(List.of(), checks("Version 2.10 and 2.9 are both supported."));
        assertEquals(List.of(), checks("1.10 is newer than the old one."));
    }
}
