package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Span;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DateTimeRecognizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 20:00 UTC on 28 October 2026 is already 29 October in Tokyo. */
    private static final AnnotationDocument.Context TOKYO =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T20:00:00Z"), ZoneId.of("Asia/Tokyo"), "en");

    private static List<Span> spans(String text, AnnotationDocument.Context context) {
        return new DateTimeRecognizer().recognize(ConceptRecognizers.input(text, context));
    }

    /** Each span as {@code CONCEPT surface = value}. */
    private static List<String> found(String text) {
        return spans(text, TOKYO).stream()
                .map(s -> s.concept() + " " + text.substring(s.range().start(), s.range().end()) + " = " + s.value())
                .toList();
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void monthsAndWeekdaysNeedTheirCapital() {
        assertEquals(10, DateTimeRecognizer.month("Oct."));
        assertEquals(9, DateTimeRecognizer.month("Sept"));
        assertEquals(5, DateTimeRecognizer.month("May"));
        assertNull(DateTimeRecognizer.month("may"));
        assertEquals(4, DateTimeRecognizer.weekday("Thu"));
        assertEquals(7, DateTimeRecognizer.weekday("Sunday"));
        assertNull(DateTimeRecognizer.weekday("sun"));
    }

    @Test
    void isoDatesAreDates() {
        assertEquals(List.of("DATE 2026-10-28 = \"2026-10-28\""), found("Merged on 2026-10-28, green."));
        assertEquals(List.of("DATE 2026-02-30 = \"2026-02-30\""), found("Not on 2026-02-30 either."),
                "a day that does not exist keeps its written numbers");
        List<Span> spans = spans("Due 2026-11-04.", TOKYO);
        assertEquals("d1", spans.get(0).id());
        assertEquals("date", spans.get(0).provenance().rule());
    }

    @Test
    void namedMonthDatesWithAndWithoutAYear() {
        assertEquals(List.of("DATE October 20, 2026 = \"2026-10-20\"", "DATE Nov 3 = \"--11-03\""),
                found("It began on October 20, 2026 and ends Nov 3."));
        assertEquals(List.of("DATE 29 Oct 2026 = \"2026-10-29\""), found("The credit expires 29 Oct 2026."));
        assertEquals(List.of(), found("It may 5 times fail."));
    }

    @Test
    void anOrdinalDayAndOfBelongToTheDate() {
        assertEquals(List.of("DATE Oct 20th = \"--10-20\""), found("We met on Oct 20th in Sofia."));
        assertEquals(List.of("DATE 20th of October = \"--10-20\""), found("We met on the 20th of October in Sofia."));
        assertEquals(List.of("DATE 10 of July = \"--07-10\""), found("The meeting is on 10 of July in Sofia."));
        assertEquals(List.of("DATE 1st Nov 2026 = \"2026-11-01\""), found("Due 1st Nov 2026 in Sofia."));
        assertEquals(List.of("DATE Sun, Dec 29th = \"--12-29\"", "WEEKDAY Sun = \"SUNDAY\""),
                found("Shipped Sun, Dec 29th in Sofia."));
        assertEquals(List.of(), found("It was the 3rd in Sofia."));
    }

    @Test
    void aWeekdayBeforeADateBelongsToItAndNests() {
        assertEquals(List.of("DATE Thu 29 Oct = \"--10-29\"", "WEEKDAY Thu = \"THURSDAY\""),
                found("Apply it Thu 29 Oct at the latest."));
        assertEquals(List.of("DATE Monday, Nov 2 = \"--11-02\"", "WEEKDAY Monday = \"MONDAY\""),
                found("The week resets Monday, Nov 2."));
        assertEquals(List.of("WEEKDAY Friday = \"FRIDAY\""), found("We ship on Friday."));
    }

    @Test
    void aDayThatDoesNotExistKeepsItsWrittenNumbers() {
        assertEquals(List.of("DATE Feb 29, 2026 = \"2026-02-29\""), found("It was Feb 29, 2026, they said."));
        assertEquals(List.of("DATE 31/04/2026 = \"2026-04-31\""), found("Due 31/04/2026 at the latest."));
        assertEquals(List.of("DATE Mon Oct 32 = \"--10-32\"", "WEEKDAY Mon = \"MONDAY\""), found("We met Mon Oct 32."));
        assertEquals(List.of("DATE Feb 30 = \"--02-30\""), found("We met on Feb 30."));
    }

    @Test
    void anImpossibleDayWithoutAYearAWeekdayOrACueIsNoDate() {
        assertEquals(List.of(), found("In February 30 tests ran."));
        assertEquals(List.of(), found("In March 45 tests failed."));
        assertEquals(List.of(), found("Due 13/13/2026 then."), "no month 13 in either order");
        assertEquals(List.of(), found("Open since 02-30."), "a month-day after a cue must exist");
    }

    @Test
    void existsKnowsTheDaysOfEachMonth() {
        assertTrue(DateTimeRecognizer.exists("2026-02-28"));
        assertTrue(DateTimeRecognizer.exists("--02-29"), "a leap day exists in some year");
        assertFalse(DateTimeRecognizer.exists("2026-02-29"));
        assertFalse(DateTimeRecognizer.exists("--02-30"));
        assertFalse(DateTimeRecognizer.exists("--10-32"));
        assertFalse(DateTimeRecognizer.exists("2026-04-31"));
    }

    @Test
    void ambiguousDateRetainsCandidates() {
        Span s = spans("Release 03/04/2026 then.", TOKYO).get(0);
        assertEquals(json("\"2026-04-03\""), s.value(), "day first (the owner, 2026-11-04)");
        assertEquals(List.of(json("{\"concept\": \"DATE\", \"value\": \"2026-03-04\"}")), s.alternatives());
        AnnotationDocument.Context american = new AnnotationDocument.Context(TOKYO.instant(), TOKYO.zone(), "en-US");
        Span month = spans("Release 03/04/2026 then.", american).get(0);
        assertEquals(json("\"2026-03-04\""), month.value());
        assertEquals(List.of(json("{\"concept\": \"DATE\", \"value\": \"2026-04-03\"}")), month.alternatives());
        assertEquals(List.of(), spans("Release 25/12/2026 then.", TOKYO).get(0).alternatives());
        assertEquals(json("\"2026-12-25\""), spans("Release 25/12/2026 then.", TOKYO).get(0).value());
        assertEquals(List.of(), spans("Release 04/04/2026 then.", TOKYO).get(0).alternatives());
    }

    @Test
    void relativeDaysKeepTheDayTheClockGives() {
        List<Span> spans = spans("Yesterday it failed; tonight it runs.", TOKYO);
        assertEquals(json("{\"relative\": \"yesterday\"}"), spans.get(0).value());
        assertEquals(List.of(json("{\"concept\": \"DATE\", \"value\": \"2026-10-28\"}")), spans.get(0).alternatives());
        assertEquals(json("{\"relative\": \"tonight\"}"), spans.get(1).value());
        assertEquals(List.of(json("{\"concept\": \"DATE\", \"value\": \"2026-10-29\"}")), spans.get(1).alternatives());
        assertEquals("relative-date", spans.get(1).provenance().rule());
        assertEquals(List.of(), spans("It runs tomorrow.", null).get(0).alternatives(), "no clock, no day");
    }

    @Test
    void clockTimes() {
        assertEquals(List.of("TIME 22:12 = \"22:12\"", "TIME 3:10 = \"03:10\"", "TIME noon = \"12:00\""),
                found("Restart at 22:12, again at 3:10 and at noon."));
        assertEquals(List.of(), found("Scores of 25:61 and 3:2 are no times."));
    }

    @Test
    void durationsInIso() {
        assertEquals(List.of("DURATION 40 minutes = \"PT40M\"", "DURATION 2.5 hours = \"PT2.5H\"",
                "DURATION two days = \"P2D\"", "DURATION a week = \"P7D\""),
                found("It took 40 minutes, then 2.5 hours, two days and a week."));
        assertEquals(List.of("DURATION 5-hour = \"PT5H\"", "DURATION 500 ms = \"PT0.5S\"",
                "DURATION 3 months = \"P3M\""), found("The 5-hour window, 500 ms apart, for 3 months."));
        assertEquals("duration", spans("Wait 15 min.", TOKYO).get(0).provenance().rule());
    }

    @Test
    void numbersWithoutAUnitAreNoDurations() {
        assertEquals(List.of(), found("It took 40 tries and two runs."));
    }

    @Test
    void aMonthDayWithoutAYearIsReadAsAPair() {
        assertEquals(List.of(), found("Open since 03-14, closed after 04-02."), "PairRecognizer reads them");
        assertEquals(List.of(), found("Due 03/04 then."));
    }

    @Test
    void moreFormsWithAYear() {
        assertEquals(List.of("DATE 2026-2-5 = \"2026-02-05\""), found("Merged on 2026-2-5, green."));
        assertEquals(List.of("DATE 2026/10/28 = \"2026-10-28\""), found("Merged on 2026/10/28, green."));
        assertEquals(List.of("DATE 28-10-2026 = \"2026-10-28\""), found("Merged on 28-10-2026, green."));
        assertEquals(List.of("DATE 28.10.2026 = \"2026-10-28\""), found("Merged on 28.10.2026, green."));
        Span dotted = spans("Merged on 03.04.2026, green.", TOKYO).get(0);
        assertEquals(json("\"2026-04-03\""), dotted.value());
        assertEquals(List.of(json("{\"concept\": \"DATE\", \"value\": \"2026-03-04\"}")), dotted.alternatives());
    }

    @Test
    void aDateWithItsYearInBackquotesIsADate() {
        assertEquals(List.of("DATE `2026-10-27` = \"2026-10-27\"", "DATE `2026-2-5` = \"2026-02-05\""),
                found("Tagged `2026-10-27` and `2026-2-5`."));
        Span slashed = spans("Run it with `3/4/2026` as the date.", TOKYO).get(0);
        assertEquals(json("\"2026-04-03\""), slashed.value());
        assertEquals(List.of(json("{\"concept\": \"DATE\", \"value\": \"2026-03-04\"}")), slashed.alternatives());
        assertEquals("date", slashed.provenance().rule());
        assertEquals(List.of("DATE `2026-02-30` = \"2026-02-30\""), found("Use `2026-02-30` here."),
                "a day that does not exist keeps its written numbers");
        assertEquals(List.of(), found("Run `date 2026-10-27`, `logs/2026-10-27` and `--10-25`."), "a date inside code");
    }

    @Test
    void aDateBesideAMathSignIsAnExpressionFirst() {
        Span sum = spans("So 2026-2-30 + 1 is 1995.", TOKYO).get(0);
        assertEquals(Span.Concept.EXPRESSION, sum.concept());
        assertEquals(json("\"2026-2-30\""), sum.value());
        assertEquals(List.of(json("{\"concept\": \"DATE\", \"value\": \"2026-02-30\"}")), sum.alternatives());
        assertEquals(List.of("EXPRESSION 2026-02-30 = \"2026-02-30\"", "EXPRESSION 2026-10-28 = \"2026-10-28\""),
                found("Set x = 2026-02-30 and 2026-10-28*2."));
        assertEquals(List.of("EXPRESSION 2026-10-28 = \"2026-10-28\"", "DATE 2026-10-28 = \"2026-10-28\""),
                found("Then 2026-10-28 - 5, and 2026-10-28 - done."), "a spaced hyphen before a word is a dash");
        assertEquals(List.of("DATE 2026-10-28 = \"2026-10-28\"", "DATE 2026-10-30 = \"2026-10-30\""),
                found("From 2026-10-28/2026-10-30 on."), "a touching slash is no math sign");
        Span code = spans("Set `x = 2026-02-30` there.", TOKYO).get(0);
        assertEquals(Span.Concept.EXPRESSION, code.concept());
        assertEquals(json("\"x = 2026-02-30\""), code.value());
        assertEquals(List.of(json("{\"concept\": \"DATE\", \"value\": \"2026-02-30\"}")), code.alternatives());
        assertTrue(DateTimeRecognizer.mathBesideInText("2026-2-30+1", 0, 9));
        assertFalse(DateTimeRecognizer.mathBesideInText("2026-10-28 - done", 0, 10));
    }

    @Test
    void aRelativePeriodIsADate() {
        assertEquals(List.of("DATE last week = {\"relative\":\"last week\"}",
                "DATE Next Month = {\"relative\":\"next month\"}"),
                found("The wiki moved last week. Next Month starts."));
        Span period = spans("Moved last week.", TOKYO).get(0);
        assertEquals(List.of(), period.alternatives());
        assertEquals("relative-date", period.provenance().rule());
    }
}
