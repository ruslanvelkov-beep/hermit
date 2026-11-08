package hermit.text.model;

import java.util.List;
import java.util.Map;

/**
 * One entry of the date layer (the owner's design of 2026-11-04): what a span with a DATE reading writes (a DATE span,
 * or a pair such as 3/4 with DATE alternatives), the full dates it can be, and its state, as the date checker resolves
 * them. The span's own value stays as the recognizer gives it. A written part is null when the text does not write it;
 * a relative date ({@code today}) writes none. The tense is the one the date's clause gives it, or null (the owner,
 * 2026-11-05). The date is the one the span resolves to ({@code "2026-10-20"}), or null.
 */
public record DateResolution(String spanId, Integer year, Integer month, Integer day, String weekday, Tense tense,
        List<Candidate> candidates, State state, String date) {

    /**
     * A span's state: PRECISE (the day, month and year are written, the date exists, and a written weekday agrees),
     * INVALID (the written parts hold under no year and no order), DISAMBIGUATED (a part is derived and exactly one
     * candidate is correct), AMBIGUOUS (a part is derived and more than one candidate is correct), UNMATCHED (a part is
     * derived and no candidate is correct), ASSUMED (a part is derived and no written part tests the candidates) or
     * UNVERIFIABLE (no candidate).
     */
    public enum State { PRECISE, INVALID, DISAMBIGUATED, AMBIGUOUS, UNMATCHED, ASSUMED, UNVERIFIABLE }

    /**
     * How a candidate fits the written parts: COMPUTED_CORRECT (its day exists and agrees with every written part),
     * COMPUTED_INCORRECT (its day does not exist, or the written weekday is not its weekday) or UNTESTED (its day
     * exists and no written part tests what it derives).
     */
    public enum Fit { COMPUTED_CORRECT, COMPUTED_INCORRECT, UNTESTED }

    /**
     * Where a candidate lies against the current moment (the clock's day in the document's zone): PAST before that
     * day, PRESENT on it, FUTURE after it.
     */
    public enum Time { PAST, PRESENT, FUTURE }

    /**
     * The tense that the date's clause gives it: PAST allows a candidate whose time is PAST or PRESENT, PRESENT only
     * PRESENT, FUTURE PRESENT or FUTURE; the cue is the words that give it ({@code "was"}, {@code "will release"},
     * {@code "since"}).
     */
    public record Tense(Time time, String cue) {

        /** Whether this tense allows a candidate whose time is {@code candidate}. */
        public boolean allows(Time candidate) {
            return candidate == Time.PRESENT || candidate == time;
        }
    }

    /**
     * How a candidate's time fits the tense: FITS when the tense allows it, CONFLICTS when not. A candidate has none
     * when the entry has no tense or the candidate has no time.
     */
    public enum TenseFit { FITS, CONFLICTS }

    /**
     * One full date that the span can be, which need not exist: its year, month and day, where each derived part came
     * from (a part's name to its source: {@code year} from {@code text}, {@code source}, {@code clock} or
     * {@code boundary}, {@code order} from {@code value} or {@code alternative}, {@code date} from {@code clock}), its
     * fit, its time (null without a clock, or when its day does not exist), and how its time fits the tense (or
     * null).
     */
    public record Candidate(int year, int month, int day, Map<String, String> derived, Fit fit, Time time,
            TenseFit tense) {}
}
