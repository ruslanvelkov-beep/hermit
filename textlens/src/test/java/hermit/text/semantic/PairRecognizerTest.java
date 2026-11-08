package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hermit.text.model.Span;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pairs of numbers with every reading (the owner, 2026-11-04), and the context that drops their dates. */
class PairRecognizerTest {

    /** Each span as {@code surface: CONCEPT value | CONCEPT value ...}, its own reading first. */
    private static List<String> found(String text) {
        List<String> found = new ArrayList<>();
        for (Span span : new PairRecognizer().recognize(ConceptRecognizers.input(text, null))) {
            StringBuilder line = new StringBuilder(text.substring(span.range().start(), span.range().end()) + ": "
                    + span.concept() + " " + span.value());
            span.alternatives().forEach(a -> line.append(" | ").append(a.get("concept").asText()).append(' ')
                    .append(a.get("value")));
            found.add(line.toString());
        }
        return found;
    }

    @Test
    void threeQuartersIsAFractionFirstThenATallyThenTheDates() {
        assertEquals(List.of("3/4: EXPRESSION \"3/4\" | TALLY {\"count\":\"3\",\"of\":\"4\"} | DATE \"--04-03\" "
                + "| DATE \"--03-04\""), found("It was 3/4."));
    }

    @Test
    void twoDigitsEachPutTheDatesFirstDayFirst() {
        assertEquals(List.of("03/04: DATE \"--04-03\" | DATE \"--03-04\" | EXPRESSION \"03/04\" | TALLY "
                + "{\"count\":\"3\",\"of\":\"4\"}"), found("It was 03/04."));
        assertEquals(List.of("10/11: DATE \"--11-10\" | DATE \"--10-11\" | EXPRESSION \"10/11\" | TALLY "
                + "{\"count\":\"10\",\"of\":\"11\"}"), found("It was 10/11."),
                "the 10th of November before the 11th of October");
        assertEquals(List.of("20/02: DATE \"--02-20\" | EXPRESSION \"20/02\""), found("It was 20/02."), "no month 20");
    }

    @Test
    void aTallyNeedsIntegersWithTheCountAtMostTheTotal() {
        assertEquals(List.of("5/5: EXPRESSION \"5/5\" | TALLY {\"count\":\"5\",\"of\":\"5\"} | DATE \"--05-05\""),
                found("All 5/5 passed."));
        assertEquals(List.of("5/5: DATE \"--05-05\" | EXPRESSION \"5/5\" | TALLY {\"count\":\"5\",\"of\":\"5\"}"),
                found("Due on 5/5."), "a date cue puts the date first");
        assertEquals(List.of("0/4: EXPRESSION \"0/4\" | TALLY {\"count\":\"0\",\"of\":\"4\"}"), found("Passed 0/4."));
        assertEquals(List.of("120/128: EXPRESSION \"120/128\" | TALLY {\"count\":\"120\",\"of\":\"128\"}"),
                found("Passed 120/128."), "no date for three digits");
        assertEquals(List.of("7/5: EXPRESSION \"7/5\" | DATE \"--05-07\" | DATE \"--07-05\""), found("It was 7/5."),
                "7 is more than 5");
        assertEquals(List.of("1.5/2: EXPRESSION \"1.5/2\""), found("It was 1.5/2."), "no tally of a decimal");
    }

    @Test
    void aHyphenIsAnExpressionFirstWithEveryOtherReading() {
        assertEquals(List.of("2-20: EXPRESSION \"2-20\" | DATE \"--02-20\" | RANGE {\"from\":\"2\",\"to\":\"20\","
                + "\"kind\":\"integers\"} | SECTION {\"parts\":[\"2\",\"20\"]}"), found("It was 2-20."));
    }

    @Test
    void aLeadingZeroOrADateCuePutsTheDateFirst() {
        assertEquals(List.of("02-20: DATE \"--02-20\" | EXPRESSION \"02-20\" | RANGE {\"from\":\"2\",\"to\":\"20\","
                + "\"kind\":\"integers\"} | SECTION {\"parts\":[\"2\",\"20\"]}"), found("It was 02-20."));
        assertEquals(List.of("10-25: DATE \"--10-25\" | EXPRESSION \"10-25\" | RANGE {\"from\":\"10\",\"to\":\"25\","
                + "\"kind\":\"integers\"} | SECTION {\"parts\":[\"10\",\"25\"]}"), found("Open since 10-25."));
    }

    @Test
    void twoDotsMakeARangeOnly() {
        assertEquals(List.of("2..20: RANGE {\"from\":\"2\",\"to\":\"20\",\"kind\":\"integers\"}"),
                found("It was 2..20."));
        assertEquals(List.of("0.5..1.5: RANGE {\"from\":\"0.5\",\"to\":\"1.5\",\"kind\":\"reals\"}"),
                found("It was 0.5..1.5."));
        assertEquals(List.of(), found("It was 5..3."), "the first number must be below the second");
    }

    @Test
    void consecutiveIntegersAreARangeOfRealsOnly() {
        assertEquals(List.of("0..1: RANGE {\"from\":\"0\",\"to\":\"1\",\"kind\":\"reals\"}"), found("It was 0..1."));
        assertEquals(List.of("1-2: EXPRESSION \"1-2\" | DATE \"--01-02\" | DATE \"--02-01\" | RANGE {\"from\":\"1\","
                + "\"to\":\"2\",\"kind\":\"reals\"} | SECTION {\"parts\":[\"1\",\"2\"]}"), found("It was 1-2."));
    }

    @Test
    void aRangeNeedsTheFirstNumberBelowTheSecond() {
        assertEquals(List.of("5-3: EXPRESSION \"5-3\" | DATE \"--05-03\" | DATE \"--03-05\" | SECTION "
                + "{\"parts\":[\"5\",\"3\"]}"), found("It was 5-3."));
        assertEquals(List.of("0.5-1.5: EXPRESSION \"0.5-1.5\" | RANGE {\"from\":\"0.5\",\"to\":\"1.5\",\"kind\":"
                + "\"reals\"}"), found("It was 0.5-1.5."), "no date and no section for decimals");
    }

    @Test
    void aChainOrAPairWithoutAReadingIsNoSpan() {
        assertEquals(List.of(), found("Merged on 2026-02-20."));
        assertEquals(List.of(), found("Due 03/04/2026."));
        assertEquals(List.of(), found("It was 1/2/3."));
        assertEquals(List.of(), found("Open 10:00-12:00."));
        assertEquals(List.of(), found("It was 3/0."), "no date, and no division by zero");
    }

    @Test
    void arithmeticAroundAPairDropsItsDates() {
        String fraction = "3/4: EXPRESSION \"3/4\" | TALLY {\"count\":\"3\",\"of\":\"4\"}";
        String half = "1/2: EXPRESSION \"1/2\" | TALLY {\"count\":\"1\",\"of\":\"2\"}";
        assertEquals(List.of(fraction), found("The result (3/4)/2 is small."));
        assertEquals(List.of(fraction, half), found("Add (3/4) + (1/2) here."));
        assertEquals(List.of(fraction, half), found("Add (3/4)+(1/2) here."));
        assertEquals(List.of(fraction), found("Note that 3/4 = 0.75 here."));
        assertEquals(List.of(fraction), found("Note that 3/4=0.75 here."));
        assertEquals(List.of(fraction), found("Set x = 3/4 here."));
        assertEquals(List.of(fraction), found("Is 1 - 3/4 right?"));
        assertEquals(List.of(fraction), found("Is 3/4 - 1 right?"));
    }

    @Test
    void bracketsAloneATimeNounBeforeAnEqualsSignOrADashKeepTheDates() {
        String all = "3/4: EXPRESSION \"3/4\" | TALLY {\"count\":\"3\",\"of\":\"4\"} | DATE \"--04-03\" "
                + "| DATE \"--03-04\"";
        assertEquals(List.of(all), found("The ratio (3/4) was fine."));
        assertEquals(List.of(all), found("The deadline = 3/4 for everyone."));
        assertEquals(List.of(all), found("Done - 3/4 at the latest."));
    }

    @Test
    void aNounWithoutATimeComponentAfterAPairDropsItsDates() {
        String range = "2-5: EXPRESSION \"2-5\" | RANGE {\"from\":\"2\",\"to\":\"5\",\"kind\":\"integers\"} "
                + "| SECTION {\"parts\":[\"2\",\"5\"]}";
        assertEquals(List.of(range), found("Pick 2-5 objects from the list."));
        assertEquals(List.of(range), found("Pick 2-5 small objects from the list."));
        assertEquals(List.of(range), found("It takes from 2-5 seconds."));
        assertEquals(List.of(range), found("It takes 2-5 MB."));
    }

    @Test
    void aTimeNounOrALeadingZeroKeepsTheDates() {
        assertEquals(List.of("10-25: EXPRESSION \"10-25\" | DATE \"--10-25\" | RANGE {\"from\":\"10\",\"to\":"
                + "\"25\",\"kind\":\"integers\"} | SECTION {\"parts\":[\"10\",\"25\"]}"),
                found("The 10-25 meetings were long."));
        assertEquals(List.of("10/25: DATE \"--10-25\" | EXPRESSION \"10/25\" | TALLY {\"count\":\"10\",\"of\":"
                + "\"25\"}"), found("The 10/25 meeting was long."));
        assertEquals(List.of("10/25: DATE \"--10-25\" | EXPRESSION \"10/25\" | TALLY {\"count\":\"10\",\"of\":"
                + "\"25\"}"), found("The 10/25 release was late."));
        assertEquals(List.of("10/25: DATE \"--10-25\" | EXPRESSION \"10/25\" | TALLY {\"count\":\"10\",\"of\":"
                + "\"25\"}"), found("The 10/25 plot was foiled."), "a plot has a time, as an event has");
        assertEquals(List.of("11-03: DATE \"--11-03\" | DATE \"--03-11\" | EXPRESSION \"11-03\" | SECTION "
                + "{\"parts\":[\"11\",\"3\"]}"), found("The 11-03 sweep took the files."), "a leading zero");
    }

    @Test
    void aPairBeforeANameKeepsItsDatesFirstAndNamesItWithTheName() {
        assertEquals(List.of("9/11 Commission: NAME \"9/11 Commission\"", "9/11: DATE \"--11-09\" | DATE \"--09-11\" | "
                + "EXPRESSION \"9/11\" | TALLY {\"count\":\"9\",\"of\":\"11\"}"),
                found("He testified before the 9/11 Commission."));
        assertEquals("9/11 Commission Staff: NAME \"9/11 Commission Staff\"",
                found("The 9/11 Commission Staff concluded it.").get(0));
        assertEquals(List.of("2.5-3: EXPRESSION \"2.5-3\" | RANGE {\"from\":\"2.5\",\"to\":\"3\",\"kind\":\"reals\"}"),
                found("The 2.5-3 Commission met."), "no date, so no name");
    }

    @Test
    void aTitlesCapitalsAndAMonthOrAWeekdayMakeNoName() {
        List<String> range = List.of("2-5: EXPRESSION \"2-5\" | RANGE {\"from\":\"2\",\"to\":\"5\",\"kind\":"
                + "\"integers\"} | SECTION {\"parts\":[\"2\",\"5\"]}");
        assertEquals(range, found("Pick 2-5 Objects"), "a title's words read in lower case: no name, so no date");
        assertEquals(range, found("# Pick 2-5 Objects\n\nThen sort them by size.\n"));
        assertEquals(range, found("Pick 2-5 Objects\nWe sort them by size.\n"), "a line settled as a title");
        assertEquals("9/11 Commission: NAME \"9/11 Commission\"",
                found("The 9/11 Commission Staff\n\nThe 9/11 Commission met.\n").get(0), "the prose writes Commission");
        assertEquals(List.of("3-4: DATE \"--03-04\" | DATE \"--04-03\" | EXPRESSION \"3-4\" | RANGE {\"from\":\"3\","
                + "\"to\":\"4\",\"kind\":\"reals\"} | SECTION {\"parts\":[\"3\",\"4\"]}"),
                found("It was on 3-4 June here."), "a month is the date's own");
        assertEquals(1, found("We met on Monday 3-4 Friday.").size(), "a weekday");
    }

    @Test
    void aPairAloneInBackquotesKeepsOnlyStandardDates() {
        assertEquals(List.of("`3/4`: EXPRESSION \"3/4\" | TALLY {\"count\":\"3\",\"of\":\"4\"}",
                "`03/04`: DATE \"--04-03\" | DATE \"--03-04\" | EXPRESSION \"03/04\" | TALLY {\"count\":\"3\",\"of\":"
                        + "\"4\"}"), found("Run `3/4` and `03/04` in the shell."));
        assertEquals(List.of("`2-5`: EXPRESSION \"2-5\" | RANGE {\"from\":\"2\",\"to\":\"5\",\"kind\":\"integers\"} "
                + "| SECTION {\"parts\":[\"2\",\"5\"]}", "`10/25`: DATE \"--10-25\" | EXPRESSION \"10/25\" | TALLY "
                + "{\"count\":\"10\",\"of\":\"25\"}", "`1..3`: RANGE {\"from\":\"1\",\"to\":\"3\",\"kind\":"
                + "\"integers\"}"), found("Try `2-5`, `10/25` and `1..3` objects."));
        assertEquals(List.of(), found("Try `x = 3/4` and `3/4/5`."));
    }

    @Test
    void thePairReplacesItsNumbersInTheStandardSpans() {
        String text = "It was 2-20.";
        List<String> spans = ConceptRecognizers.standard().recognize(ConceptRecognizers.input(text, null)).stream()
                .map(s -> s.concept() + " " + text.substring(s.range().start(), s.range().end())).toList();
        assertEquals(List.of("EXPRESSION 2-20"), spans);
    }
}
