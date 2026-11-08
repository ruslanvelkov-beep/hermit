package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hermit.text.model.Clause;
import hermit.text.model.DateResolution;
import hermit.text.model.Span;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The tense that a date's clause gives it (the owner, 2026-11-05), without a clock. */
class TenseReaderTest {

    /** Each DATE span of {@code text} as {@code surface: TIME cue}, or {@code surface: none}. */
    private static List<String> tenses(String text) {
        Checker.Input input = Checkers.input(text, null);
        List<Clause> clauses = TenseReader.clauses(input);
        List<String> found = new ArrayList<>();
        for (Span span : input.spans()) {
            if (span.concept() == Span.Concept.DATE) {
                DateResolution.Tense tense = TenseReader.read(input, clauses, span);
                found.add(text.substring(span.range().start(), span.range().end()) + ": "
                        + (tense == null ? "none" : tense.time() + " " + tense.cue()));
            }
        }
        return found;
    }

    @Test
    void aPastVerbOrAPerfectMakesThePast() {
        assertEquals(List.of("Wed Dec 29: PAST was"), tenses("Wed Dec 29 was the release."));
        assertEquals(List.of("Sun Dec 30: PAST shipped"), tenses("We shipped on Sun Dec 30."));
        assertEquals(List.of("Oct 20: PAST met"), tenses("We met on Oct 20 in Sofia."));
        assertEquals(List.of("Oct 20: PAST have released"), tenses("We have released it on Oct 20."));
        assertEquals(List.of("Oct 20: PAST should have released"), tenses("We should have released on Oct 20."));
    }

    @Test
    void eachClauseHasItsOwnTense() {
        assertEquals(List.of("Oct 20: PAST released", "Nov 20: FUTURE will release"),
                tenses("We released 1.2 on Oct 20 and will release 1.3 on Nov 20."));
    }

    @Test
    void thePresentSpeaksOfTodayOrLater() {
        assertEquals(List.of("10 July: FUTURE is"), tenses("The meeting is on 10 July, in Sofia."));
        assertEquals(List.of("Oct 20: FUTURE starts"), tenses("It starts on Oct 20 in Sofia."));
        assertEquals(List.of("Oct 20: FUTURE should release"), tenses("We should release on Oct 20 at noon."));
    }

    @Test
    void thePresentContinuousIsTheFutureUnlessItIsNow() {
        assertEquals(List.of("Oct 20: FUTURE are releasing"), tenses("We are releasing on Oct 20 at noon."));
        assertEquals(List.of("Oct 20: PRESENT are releasing now"), tenses("We are releasing it now, on Oct 20."));
        assertEquals(List.of("Oct 20: FUTURE will be releasing"), tenses("We will not be releasing on Oct 20 at all."));
    }

    @Test
    void anIngWordWithoutBeIsNoVerbGroup() {
        assertEquals(List.of("Oct 20: PAST ran"), tenses("The job ran 3 steps, echoing the log on Oct 20 at noon."));
    }

    @Test
    void aCalendarFactATargetInThePastOrAConditionalGivesNone() {
        assertEquals(List.of("Oct 20: none"), tenses("Oct 20 is a Monday."));
        assertEquals(List.of("Oct 20: none"), tenses("Oct 20 will be a Tuesday."));
        assertEquals(List.of("January 15th: PAST were signed"),
                tenses("January 15th -- We were signed up for Saturday's class."), "a weekday elsewhere");
        assertEquals(List.of("Oct 20: none"), tenses("The release was moved to Oct 20 at noon."));
        assertEquals(List.of("Oct 20: FUTURE will move"), tenses("We will move the release to Oct 20 at noon."));
        assertEquals(List.of("Oct 20: none"), tenses("They were going to release on Oct 20 at noon."));
        assertEquals(List.of("Oct 20: FUTURE are going"), tenses("They are going to release on Oct 20 at noon."));
        assertEquals(List.of("Oct 20: none"), tenses("We would release on Oct 20 at noon."));
        assertEquals(List.of("Oct 20: none"), tenses("Released: Oct 20"));
    }

    @Test
    void contractionsGiveTheirVerbs() {
        assertEquals(List.of("Oct 20: FUTURE 'll ship"), tenses("We'll ship it on Oct 20 at noon."));
        assertEquals(List.of("Oct 20: FUTURE won't ship"), tenses("We won't ship it on Oct 20 at noon."));
        assertEquals(List.of("Oct 20: PAST didn't ship"), tenses("We didn't ship it on Oct 20 at noon."));
        assertEquals(List.of("Oct 20: FUTURE 're releasing"), tenses("They're releasing it on Oct 20 at noon."));
        assertEquals(List.of("Oct 20: PAST 'd shipped"), tenses("She'd shipped it on Oct 20 at noon."));
        assertEquals(List.of("Oct 20: none"), tenses("She'd ship it on Oct 20 at noon."), "would");
        assertEquals(List.of("Oct 20: none"), tenses("It's shipped on Oct 20 at noon."), "is or has");
        assertEquals(List.of("Oct 20: PAST 's been shipped"), tenses("It's been shipped on Oct 20 at noon."));
    }

    @Test
    void sinceMakesThePast() {
        assertEquals(List.of("Oct 20: PAST since"), tenses("The branch has been open since Oct 20 at noon."));
    }
}
