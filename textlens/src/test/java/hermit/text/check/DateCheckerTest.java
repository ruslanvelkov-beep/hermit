package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import hermit.text.model.DateResolution;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DateCheckerTest {

    /** Wednesday, 28 October 2026, 10:00 UTC. */
    private static final AnnotationDocument.Context CONTEXT = context("2026-10-28T10:00:00Z", "UTC");

    private static AnnotationDocument.Context context(String instant, String zone) {
        return new AnnotationDocument.Context(instant == null ? null : Instant.parse(instant), ZoneId.of(zone), "en");
    }

    private static List<Check> checks(String text, AnnotationDocument.Context context) {
        return new DateChecker().check(Checkers.input(text, context));
    }

    private static Check only(String text, AnnotationDocument.Context context) {
        List<Check> checks = checks(text, context);
        assertEquals(1, checks.size(), text);
        return checks.get(0);
    }

    private static Check onlyWithSources(String text, List<String> sources) {
        List<Check> checks = new DateChecker().check(Checkers.input(text, CONTEXT, sources));
        assertEquals(1, checks.size(), text);
        return checks.get(0);
    }

    private static Check.Assumption year(int year, String source) {
        return new Check.Assumption("year", Checks.JSON.numberNode(year), source);
    }

    @Test
    void monOct20UsesClockYear() {
        Check check = only("We met on Mon Oct 20 to plan it.", CONTEXT);
        assertEquals("date", check.checker());
        assertEquals(Check.Status.INCONSISTENT, check.status());
        assertEquals(2, check.targets().size(), "the date and the weekday");
        assertEquals(List.of("weekday-mismatch"), check.reasons());
        assertEquals("year", check.assumptions().get(0).kind());
        assertEquals(2026, check.assumptions().get(0).value().asInt());
        assertEquals("clock", check.assumptions().get(0).source());
        assertEquals("MONDAY", check.evidence().path("stated").asText());
        assertEquals("2026-10-20", check.evidence().path("readings").get(0).path("date").asText());
        assertEquals("TUESDAY", check.evidence().path("readings").get(0).path("weekday").asText());
    }

    @Test
    void theRightWeekdayIsConsistent() {
        Check check = only("We met on Tue Oct 20 to plan it.", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, check.status());
        assertEquals(List.of("weekday-matches"), check.reasons());
    }

    @Test
    void aDateWithItsYearAssumesNothing() {
        Check check = only("The release was on Mon Oct 20, 2025.", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, check.status(), "20 October 2025 was a Monday");
        assertEquals(List.of(), check.assumptions());
    }

    @Test
    void theYearBeforeIsACandidateInJanuary() {
        Check check = only("Tue Dec 29 was the release.", context("2027-01-02T10:00:00Z", "UTC"));
        assertEquals(Check.Status.CONSISTENT, check.status(), "29 December 2026, four days before the clock");
        assertEquals("disambiguated", check.evidence().path("state").asText());
        assertEquals(List.of(year(2027, "clock"), year(2026, "boundary")), check.assumptions());
        List<DateResolution> dates = DateChecker.resolve(Checkers.input("Tue Dec 29 was the release.",
                context("2027-01-02T10:00:00Z", "UTC")));
        assertEquals(List.of(DateResolution.Time.FUTURE, DateResolution.Time.PAST),
                dates.get(0).candidates().stream().map(DateResolution.Candidate::time).toList(),
                "a sentence-level step weighs the tense later");
        Check october = only("We met on Wed Dec 31.", CONTEXT);
        assertEquals(Check.Status.INCONSISTENT, october.status(), "no year before in October: 31 December 2026 is a "
                + "Thursday");
        assertEquals(List.of(year(2026, "clock")), october.assumptions());
    }

    @Test
    void theYearAfterIsACandidateInDecember() {
        Check check = only("We meet on Tue Jan 5.", context("2026-12-20T10:00:00Z", "UTC"));
        assertEquals(Check.Status.CONSISTENT, check.status(), "5 January 2027 is a Tuesday, 5 January 2026 a Monday");
        assertEquals("disambiguated", check.evidence().path("state").asText());
        assertEquals(List.of(year(2026, "clock"), year(2027, "boundary")), check.assumptions());
    }

    @Test
    void todayIsTheClocksDayInTheDocumentsZone() {
        String text = "Today is Monday.";
        assertEquals(Check.Status.CONSISTENT, only(text, context("2026-03-29T21:30:00Z", "Europe/Sofia")).status(),
                "00:30 on Monday 30 March in Sofia, an hour after the clocks went forward");
        assertEquals(Check.Status.INCONSISTENT, only(text, context("2026-03-29T21:30:00Z", "UTC")).status(),
                "still Sunday in UTC");
        assertEquals(Check.Status.CONSISTENT, only("Yesterday was Tuesday.", CONTEXT).status());
    }

    @Test
    void aWeekdayAfterADateIsChecked() {
        Check check = only("On 03/14/2026, a Saturday, we shipped.", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, check.status());
        assertEquals(2, check.targets().size());
    }

    @Test
    void aWeekdayPicksTheOrderOfASlashDate() {
        Check check = only("On 03/04/2026, a Friday, we shipped.", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, check.status(), "3 April 2026 is a Friday, 4 March a Wednesday");
        assertEquals("disambiguated", check.evidence().path("state").asText());
        assertEquals(List.of("weekday-matches"), check.reasons());
        assertEquals(2, check.evidence().path("readings").size());
        assertEquals("computed-correct", check.evidence().path("readings").get(0).path("fit").asText(), "day first");
        assertEquals(List.of(), check.assumptions(), "the order is derived, not a year");
        Check neither = only("On 03/04/2026, a Monday, we shipped.", CONTEXT);
        assertEquals(Check.Status.INCONSISTENT, neither.status());
        assertEquals("invalid", neither.evidence().path("state").asText(), "only the order was derived");
    }

    @Test
    void withoutAClockAYearlessDateIsUnverifiable() {
        Check check = only("We met on Mon Oct 20 to plan it.", context(null, "UTC"));
        assertEquals(Check.Status.UNVERIFIABLE, check.status());
        assertEquals(List.of("no-clock"), check.reasons());
    }

    @Test
    void aYearInTheTextComesBeforeTheClock() {
        Check check = only("After the meeting on May 7, 2000, how about Thursday, July 6?", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, check.status(), "6 July 2000 was a Thursday; 6 July 2026 is a Monday");
        assertEquals(List.of(year(2000, "text")), check.assumptions());
    }

    @Test
    void everyYearOfTheTextIsACandidate() {
        Check check = only("In 1999 we planned it; in 2000 we met on Thursday, July 6.", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, check.status(), "6 July 1999 was a Tuesday, 6 July 2000 a Thursday");
        assertEquals(List.of(year(1999, "text"), year(2000, "text")), check.assumptions());
        Check after = only("We met on Thursday, July 6, as the July 2000 plan said.", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, after.status());
        assertEquals(List.of(year(2000, "text")), after.assumptions());
    }

    @Test
    void theTextAndTheSourcesGiveCandidates() {
        Check check = onlyWithSources("How about Thursday, July 6?", List.of("Sent 07/18/2000 08:58 AM"));
        assertEquals(Check.Status.CONSISTENT, check.status());
        assertEquals(List.of(year(2000, "source")), check.assumptions());
        Check both = onlyWithSources("In 2001 we said Friday, July 6.", List.of("Sent 07/18/2000 08:58 AM"));
        assertEquals(Check.Status.CONSISTENT, both.status(), "6 July 2001 was a Friday, 6 July 2000 a Thursday");
        assertEquals(List.of(year(2001, "text"), year(2000, "source")), both.assumptions());
    }

    @Test
    void aWeekdayPicksOneOfTheSourceYears() {
        Check check = onlyWithSources("How about Thursday, July 6?",
                List.of("Sent 07/18/2000 08:58 AM", "The plan for July 2001 is fine."));
        assertEquals(Check.Status.CONSISTENT, check.status(), "a Thursday in 2000, a Friday in 2001");
        assertEquals("disambiguated", check.evidence().path("state").asText());
        assertEquals(List.of(year(2000, "source"), year(2001, "source")), check.assumptions());
    }

    @Test
    void withoutAYearInTheTextOrTheSourcesTheClockDecides() {
        Check check = onlyWithSources("We met on Mon Oct 20 to plan it.", List.of("No year is named here."));
        assertEquals(Check.Status.INCONSISTENT, check.status(), "20 October 2026 is a Tuesday");
        assertEquals(List.of(year(2026, "clock")), check.assumptions());
    }

    @Test
    void aLeapDayInACandidateYearWithoutOneIsUnmatched() {
        Check check = only("In 2001 we met on Tue Feb 29.", context("2028-03-01T10:00:00Z", "UTC"));
        assertEquals(Check.Status.INCONSISTENT, check.status(), "no 29 February in 2001, and the clock is not asked");
        assertEquals("unmatched", check.evidence().path("state").asText());
        assertEquals(List.of("no-such-day"), check.reasons());
        assertEquals(List.of(year(2001, "text")), check.assumptions());
    }

    @Test
    void yearMentionsAreFullDatesAndYearsAfterAMonthOrAPreposition() {
        String text = "Since 1999, by May 2001 and on 2002-03-04 we sold 2003 units.";
        assertEquals(List.of(new DateChecker.YearMention(text.indexOf("1999"), 1999, 0),
                new DateChecker.YearMention(text.indexOf("2001"), 2001, 5),
                new DateChecker.YearMention(text.indexOf("2002"), 2002, 3)),
                DateChecker.yearMentions(Checkers.input(text, CONTEXT)));
        assertEquals(List.of(), DateChecker.yearMentions(Checkers.input("2005 was a good year.", CONTEXT)),
                "a number that starts the text has no word before it");
    }

    @Test
    void aSlashDateMentionsTheMonthOfEachOrder() {
        String text = "Sent on 01/10/2002 and on 13/10/2002.";
        assertEquals(List.of(new DateChecker.YearMention(text.indexOf("01/"), 2002, 10),
                new DateChecker.YearMention(text.indexOf("01/"), 2002, 1),
                new DateChecker.YearMention(text.indexOf("13/"), 2002, 10)),
                DateChecker.yearMentions(Checkers.input(text, CONTEXT)), "13/10/2002 has no other order");
    }

    @Test
    void sourceMentionsAreDistinctByYearAndMonth() {
        assertEquals(List.of(new DateChecker.YearMention(3, 2001, 0), new DateChecker.YearMention(16, 2000, 7),
                new DateChecker.YearMention(20, 1999, 0)), DateChecker.sourceMentions(
                Arrays.asList("In 2001, and on 2000-07-18.", null, "Since 2001, then in 1999.")));
        assertEquals(List.of(), DateChecker.sourceMentions(List.of()));
    }

    @Test
    void aWrittenDateIsPreciseOrInvalid() {
        Check precise = only("It shipped on Tue Oct 20, 2026.", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, precise.status());
        assertEquals("precise", precise.evidence().path("state").asText());
        Check invalid = only("It shipped on Mon Oct 20, 2026.", CONTEXT);
        assertEquals(Check.Status.INCONSISTENT, invalid.status());
        assertEquals("invalid", invalid.evidence().path("state").asText());
        assertEquals(List.of("weekday-mismatch"), invalid.reasons());
        assertEquals(List.of(), invalid.assumptions());
    }

    @Test
    void anImpossibleDayIsInvalidWithoutAWeekday() {
        Check check = only("We met on Feb 30.", CONTEXT);
        assertEquals(Check.Status.INCONSISTENT, check.status());
        assertEquals("invalid", check.evidence().path("state").asText());
        assertEquals(List.of("no-such-day"), check.reasons());
        assertEquals(1, check.targets().size(), "the date alone");
        assertEquals(0, check.evidence().path("readings").size(), "no year makes it exist");
        Check written = only("It was due on 2026-02-29.", CONTEXT);
        assertEquals(Check.Status.INCONSISTENT, written.status());
        assertEquals(List.of("no-such-day"), written.reasons());
    }

    @Test
    void aDateWithoutAWeekdayIsAssumedAndNotChecked() {
        List<DateResolution> dates = DateChecker.resolve(Checkers.input("We ship on Nov 3.", CONTEXT));
        assertEquals(DateResolution.State.ASSUMED, dates.get(0).state());
        assertEquals("2026-11-03", dates.get(0).date());
        assertEquals(DateResolution.Fit.UNTESTED, dates.get(0).candidates().get(0).fit());
        assertEquals(List.of(), checks("We ship on Nov 3.", CONTEXT));
    }

    @Test
    void severalFittingYearsAreAmbiguous() {
        Check check = only("In 2015 and in 2026 the release fell on Tue Oct 20.", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, check.status(), "20 October was a Tuesday in both years");
        assertEquals("ambiguous", check.evidence().path("state").asText());
    }

    @Test
    void aMentionWithAMonthIsAReferencePoint() {
        Check check = onlyWithSources("We shipped on Sun Dec 30.", List.of("Sent 2002-01-10 08:58 AM"));
        assertEquals(Check.Status.CONSISTENT, check.status(), "30 December 2001 was a Sunday, in 2002 a Monday");
        assertEquals(List.of(year(2002, "source"), year(2001, "boundary")), check.assumptions());
        Check bare = onlyWithSources("We shipped on Sun Dec 30.", List.of("Sent in 2002."));
        assertEquals(Check.Status.INCONSISTENT, bare.status(), "a bare year is no reference point");
    }

    @Test
    void bothOrdersOfASourceDateAreReferencePoints() {
        Check check = onlyWithSources("We shipped on Sun Dec 30.", List.of("Sent 01/10/2002 08:58 AM"));
        assertEquals(Check.Status.CONSISTENT, check.status(), "10 January 2002, the other order, adds 2001");
        assertEquals(List.of(year(2002, "source"), year(2001, "boundary")), check.assumptions());
    }

    @Test
    void thePastTenseKeepsThePastCandidates() {
        Check check = only("Wed Dec 29 was the release.", context("2027-01-02T10:00:00Z", "UTC"));
        assertEquals(Check.Status.INCONSISTENT, check.status(), "29 December 2027, a Wednesday, is still to come");
        assertEquals("unmatched", check.evidence().path("state").asText());
        assertEquals("{\"time\":\"past\",\"cue\":\"was\"}", check.evidence().path("tense").toString());
        List<String> fits = new ArrayList<>();
        check.evidence().path("readings").forEach(reading -> fits.add(reading.path("tense").asText()));
        assertEquals(List.of("conflicts", "fits"), fits);
    }

    @Test
    void theTensePicksTheYearOfADateWithoutAWeekday() {
        AnnotationDocument.Context jan2 = context("2027-01-02T10:00:00Z", "UTC");
        assertEquals("2026-12-29", DateChecker.resolve(Checkers.input("We shipped on Dec 29 at noon.", jan2)).get(0)
                .date());
        assertEquals("2027-12-29", DateChecker.resolve(Checkers.input("We will ship on Dec 29 at noon.", jan2)).get(0)
                .date());
        DateResolution present = DateChecker.resolve(Checkers.input("Wed Dec 29 is the release.", jan2)).get(0);
        assertEquals(DateResolution.State.DISAMBIGUATED, present.state());
        assertEquals(new DateResolution.Tense(DateResolution.Time.FUTURE, "is"), present.tense());
    }

    @Test
    void theTenseNeverLeavesADateWithoutCandidates() {
        DateResolution date = DateChecker.resolve(Checkers.input("We shipped on Wed Oct 20, 2027 at noon.", CONTEXT))
                .get(0);
        assertEquals(DateResolution.State.PRECISE, date.state(), "the written date stands against the tense");
        assertEquals(DateResolution.TenseFit.CONFLICTS, date.candidates().get(0).tense());
    }

    @Test
    void aPairGetsACandidateForEachOrderAndYear() {
        Check check = only("We met on 03/04, a Friday.", CONTEXT);
        assertEquals(Check.Status.CONSISTENT, check.status(), "3 April 2026 is a Friday, 4 March a Wednesday");
        assertEquals("disambiguated", check.evidence().path("state").asText());
        List<DateResolution> dates = DateChecker.resolve(Checkers.input("We met on 03/04, a Friday.", CONTEXT));
        assertEquals("2026-04-03", dates.get(0).date());
        assertEquals(List.of(Map.of("order", "value", "year", "clock"),
                Map.of("order", "alternative", "year", "clock")),
                dates.get(0).candidates().stream().map(DateResolution.Candidate::derived).toList());
    }

    @Test
    void aFractionFirstStillGetsADateEntry() {
        List<DateResolution> dates = DateChecker.resolve(Checkers.input("It was 3/4 of the runs.", CONTEXT));
        assertEquals(1, dates.size());
        assertEquals(DateResolution.State.ASSUMED, dates.get(0).state());
        assertEquals(List.of("2026-04-03", "2026-03-04"), dates.get(0).candidates().stream()
                .map(c -> String.format("%04d-%02d-%02d", c.year(), c.month(), c.day())).toList());
        assertEquals(List.of(Map.of("order", "alternative", "year", "clock"),
                Map.of("order", "alternative", "year", "clock")),
                dates.get(0).candidates().stream().map(DateResolution.Candidate::derived).toList());
        assertEquals(List.of(), checks("It was 3/4 of the runs.", CONTEXT), "no weekday, so no statement");
    }

    @Test
    void aDateWithoutAWeekdayIsNotChecked() {
        assertEquals(List.of(), checks("The release was on Oct 20, 2026.", CONTEXT));
        assertEquals(List.of(), checks("Today we shipped.", CONTEXT));
    }

    @Test
    void aDateThatEndsTheTextIsNoStatement() {
        assertEquals(List.of(), checks("We ship tonight", CONTEXT));
        assertEquals(List.of(), checks("We shipped today", CONTEXT));
        assertEquals(List.of(), checks("The release was on Oct 20, 2026,", CONTEXT));
    }
}
