package hermit.text.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import hermit.text.expr.Rational;
import hermit.text.model.Span;
import hermit.text.model.Token;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dates, times, weekdays and durations (milestone A3 of the annotation plan, section 6.1). Month and weekday names are
 * matched with a capital first letter ({@code May} is a month, {@code may} is not), with or without a final period.
 * <ul>
 *   <li>DATE (rule {@code date}), valued {@code "2026-10-28"}, or {@code "--10-28"} without a year, with a month from 1
 *       to 12 and a day other than 0. A day that does not exist in its month (in its year when it has one, else in any
 *       year: {@code Feb 30}, {@code Oct 32}, {@code Feb 29, 2026}) keeps its written numbers ({@code "--02-30"},
 *       {@code "2026-02-29"}; see {@link #exists}), so that a checker can call the date invalid. Such a date is a date
 *       only when a year is written, a weekday comes right before it, or one of {@link #DATE_CUES} comes right before
 *       it in any case ({@code on Feb 30}): {@code In February 30 tests ran} holds no date. The forms:
 *     <ul>
 *       <li>a year, a month and a day as NUMBER tokens joined by touching {@code -} or {@code /} tokens, year first
 *           ({@code 2026-10-28}, {@code 2026-2-5}, {@code 2026/10/28}), the month and the day of one or two
 *           digits;</li>
 *       <li>a month name with a day after it and an optional year ({@code Oct 28}, {@code October 20, 2026}), or a day
 *           with a month name and an optional year after it ({@code 29 Oct}, {@code 29 October 2026}); the day may be
 *           written as an ordinal, one token with {@code st}, {@code nd}, {@code rd} or {@code th} after its digits
 *           ({@code Oct 20th}, {@code 20th Oct}), and {@code of} may come between a day and a month name after it
 *           ({@code 10 of July}, {@code 20th of October}: the span starts at the day, after any {@code the}, the
 *           owner, 2026-11-05); a weekday right before either form, with or without a comma, belongs to the date
 *           ({@code Thu 29 Oct});</li>
 *       <li>two numbers of one or two digits and a four-digit year, joined by touching {@code /} tokens or by
 *           touching {@code -} tokens, or written as one token with dots ({@code 03/04/2026}, {@code 03-04-2026},
 *           {@code 03.04.2026}): day first, or month first when the document's locale is {@code en-US}; when the
 *           other order is also a date, and a different one, it is the alternative.</li>
 *       <li>a CODE_SPAN token whose text between its backticks is a {@link #NUMERIC_DATE} alone, the first or the
 *           third form above ({@code `2026-10-27`}, {@code `3/4/2026`}, {@code `2026-02-30`}): a year makes it a date
 *           in code too (the owner, 2026-11-06). The span covers the backticks, and the values are those of the text
 *           between them.</li>
 *     </ul>
 *     A date of the first or the third form beside a math sign (the owner, 2026-11-06: {@code 2026-2-30 + 1},
 *     {@code x = 2026-02-30}; {@link #mathBeside}) is an EXPRESSION first, valued with the text as written, and its
 *     DATE values come after it as alternatives. In backquotes, a CODE_SPAN token whose text between its backticks
 *     holds a {@link #NUMERIC_DATE} beside a math sign in that text ({@link #mathBesideInText}:
 *     {@code `x = 2026-02-30`}), or whose date alone has a math sign beside the token, is such an EXPRESSION over the
 *     token, valued with the text between the backticks, its alternatives the DATE values of each NUMERIC_DATE in that
 *     text in order.
 *     A month and a day without a year joined by a slash or a hyphen ({@code 03/04}, {@code 10-25}) are read by
 *     {@link PairRecognizer}, with their other readings.
 *   </li>
 *   <li>DATE (rule {@code relative-date}): one of {@link #RELATIVE_DAYS}, any case, valued
 *       {@code {"relative": "<word in lower case>"}}, with the alternative DATE that the word means on the day of the
 *       document's captured clock in its zone (none when the document has no clock); also one of
 *       {@link #RELATIVE_PERIODS}, its two words in any case ({@code last week}), valued
 *       {@code {"relative": "last week"}} in lower case, with no alternative.</li>
 *   <li>WEEKDAY (rule {@code weekday}): one of {@link #WEEKDAYS} or its abbreviations in
 *       {@link #WEEKDAY_ABBREVIATIONS}, valued with the day in capitals ({@code "THURSDAY"}); also inside a date, where
 *       it nests.</li>
 *   <li>TIME (rule {@code time}): an hour (0 to 23) and two-digit minutes (00 to 59) as NUMBER tokens joined by a
 *       touching {@code :} token, valued {@code "22:12"} (the hour with two digits); {@code noon} gives
 *       {@code "12:00"} and {@code midnight} {@code "00:00"}.</li>
 *   <li>DURATION (rule {@code duration}): an amount followed by one of {@link #DURATION_UNITS} ({@code 40 minutes},
 *       {@code 2.5 hours}, {@code two days}, {@code a week}), or an IDENT token made of digits, a hyphen and a unit
 *       in the singular ({@code 5-hour}); the amount is a NUMBER token, number words, {@code a} or {@code an}. Valued
 *       in ISO 8601 with the amount as a decimal: {@code "PT40M"}, {@code "PT2.5H"}, {@code "P2D"}; a week is seven
 *       days ({@code "P7D"}), a millisecond a thousandth of a second ({@code "PT0.5S"} for 500 ms).</li>
 * </ul>
 * Spans come in text order (a date before the weekday it holds), ids {@code d1}, {@code d2}, ...
 */
public final class DateTimeRecognizer implements ConceptRecognizer {

    /** The months, January first. */
    public static final List<String> MONTHS = List.of("January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December");
    /** Abbreviated months and their numbers. */
    public static final Map<String, Integer> MONTH_ABBREVIATIONS = Map.ofEntries(Map.entry("Jan", 1),
            Map.entry("Feb", 2), Map.entry("Mar", 3), Map.entry("Apr", 4), Map.entry("Jun", 6), Map.entry("Jul", 7),
            Map.entry("Aug", 8), Map.entry("Sep", 9), Map.entry("Sept", 9), Map.entry("Oct", 10), Map.entry("Nov", 11),
            Map.entry("Dec", 12));
    /** The weekdays, Monday first. */
    public static final List<String> WEEKDAYS = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
            "Saturday", "Sunday");
    /** Abbreviated weekdays and their numbers (Monday is 1). */
    public static final Map<String, Integer> WEEKDAY_ABBREVIATIONS = Map.ofEntries(Map.entry("Mon", 1),
            Map.entry("Tue", 2), Map.entry("Tues", 2), Map.entry("Wed", 3), Map.entry("Thu", 4), Map.entry("Thur", 4),
            Map.entry("Thurs", 4), Map.entry("Fri", 5), Map.entry("Sat", 6), Map.entry("Sun", 7));
    /** The relative day words and their offsets from the day of the captured clock. */
    public static final Map<String, Integer> RELATIVE_DAYS = Map.of("today", 0, "tonight", 0, "tomorrow", 1,
            "yesterday", -1);
    /** The relative periods of two words, in lower case. */
    public static final List<String> RELATIVE_PERIODS = List.of("last week", "next week", "last month", "next month",
            "last year", "next year");
    /** The words, in lower case, after which a month-day such as {@code 10-25} is a date. */
    public static final List<String> DATE_CUES = List.of("since", "on", "until", "by", "from", "before", "after");
    /**
     * The duration units (matched in lower case) and their ISO 8601 designators: {@code S}, {@code M} and {@code H}
     * are time units (after {@code T}), {@code D}, {@code W} (written as seven days), {@code MO} (months) and
     * {@code Y} date units, {@code MS} milliseconds.
     */
    public static final Map<String, String> DURATION_UNITS = Map.ofEntries(Map.entry("millisecond", "MS"),
            Map.entry("milliseconds", "MS"), Map.entry("ms", "MS"), Map.entry("second", "S"), Map.entry("seconds", "S"),
            Map.entry("sec", "S"), Map.entry("secs", "S"), Map.entry("minute", "M"), Map.entry("minutes", "M"),
            Map.entry("min", "M"), Map.entry("mins", "M"), Map.entry("hour", "H"), Map.entry("hours", "H"),
            Map.entry("hr", "H"), Map.entry("hrs", "H"), Map.entry("day", "D"), Map.entry("days", "D"),
            Map.entry("week", "W"), Map.entry("weeks", "W"), Map.entry("month", "MO"), Map.entry("months", "MO"),
            Map.entry("year", "Y"), Map.entry("years", "Y"));
    /**
     * A date of numbers with its year, as the first and the third forms of the class javadoc write it, with no digit,
     * period, slash, colon or hyphen right before or after it ({@code 2026-2-30}, {@code 3/4/2026}).
     */
    public static final Pattern NUMERIC_DATE = Pattern.compile("(?<![0-9./:-])(?:[0-9]{4}([-/])[0-9]{1,2}\\1[0-9]{1,2}"
            + "|[0-9]{1,2}([/.-])[0-9]{1,2}\\2[0-9]{4})(?![0-9./:-])");
    private static final Pattern DIGIT_HYPHEN_UNIT = Pattern.compile("([0-9]+)-([A-Za-z]+)");
    private static final Pattern ISO_DATE = Pattern.compile(
            "([0-9]{4})([-/])([0-9]{1,2})\\2([0-9]{1,2})");
    private static final Pattern SLASH_DATE = Pattern.compile(
            "([0-9]{1,2})([/.-])([0-9]{1,2})\\2([0-9]{4})");
    private static final Pattern NAMED_PREFIX = Pattern.compile("^([A-Za-z]+\\.?)(?:,)?\\s+(.+)$");
    private static final Pattern MONTH_FIRST = Pattern.compile(
            "([A-Za-z]+\\.?)\\s+([0-9]{1,2})(?:st|nd|rd|th)?(?:(?:,\\s*|\\s+)([0-9]{4}))?");
    private static final Pattern DAY_FIRST = Pattern.compile(
            "([0-9]{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?([A-Za-z]+\\.?)(?:\\s+([0-9]{4}))?");

    /** The spans of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Span> recognize(Input input) {
        List<Span> spans = new ArrayList<>();
        int size = input.tokens().size();
        String locale = input.context() == null ? "en" : input.context().locale();
        for (int i = 0; i < size;) {
            String text = Spans.surface(input, i, i);
            String lower = text.toLowerCase(Locale.ROOT);
            if (input.tokens().get(i).shape() == Token.Shape.CODE_SPAN && text.length() >= 2) {
                String code = text.substring(1, text.length() - 1);
                Matcher numeric = NUMERIC_DATE.matcher(code);
                if (numeric.matches()) {
                    List<String> values = dateValues(code, locale);
                    if (!values.isEmpty()) {
                        List<JsonNode> alternatives = new ArrayList<>();
                        boolean expression = mathBeside(input, i, i);
                        if (expression) {
                            for (String value : values) {
                                alternatives.add(Spans.alternative(Span.Concept.DATE,
                                        Spans.JSON.textNode(value)));
                            }
                            spans.add(Spans.over(input, i, i, "d0", Span.Concept.EXPRESSION,
                                    Spans.JSON.textNode(code), alternatives, "date"));
                        } else {
                            for (int value = 1; value < values.size(); value++) {
                                alternatives.add(Spans.alternative(Span.Concept.DATE,
                                        Spans.JSON.textNode(values.get(value))));
                            }
                            spans.add(Spans.over(input, i, i, "d0", Span.Concept.DATE,
                                    Spans.JSON.textNode(values.get(0)), alternatives, "date"));
                        }
                        i++;
                        continue;
                    }
                }
                numeric.reset();
                List<JsonNode> alternatives = new ArrayList<>();
                while (numeric.find()) {
                    List<String> values = dateValues(numeric.group(), locale);
                    if (!values.isEmpty()
                            && mathBesideInText(code, numeric.start(), numeric.end())) {
                        for (String value : values) {
                            alternatives.add(Spans.alternative(Span.Concept.DATE,
                                    Spans.JSON.textNode(value)));
                        }
                    }
                }
                if (!alternatives.isEmpty()) {
                    spans.add(Spans.over(input, i, i, "d0", Span.Concept.EXPRESSION,
                            Spans.JSON.textNode(code), alternatives, "date"));
                }
                if (!spans.isEmpty()
                        && spans.get(spans.size() - 1).range().equals(input.tokens().get(i).range())) {
                    i++;
                    continue;
                }
            }
            int dateEnd = -1;
            List<String> dates = List.of();
            for (int end = i; end < size && end <= i + 8; end++) {
                if (!dateTokens(input, i, end)) {
                    continue;
                }
                List<String> candidate = dateValues(Spans.surface(input, i, end), locale);
                if (!candidate.isEmpty() && unmistakableDate(input, i, candidate)) {
                    dateEnd = end;
                    dates = candidate;
                }
            }
            if (dateEnd >= i) {
                String written = Spans.surface(input, i, dateEnd);
                boolean expression = NUMERIC_DATE.matcher(written).matches()
                        && mathBeside(input, i, dateEnd);
                List<JsonNode> alternatives = new ArrayList<>();
                if (expression) {
                    for (String date : dates) {
                        alternatives.add(Spans.alternative(Span.Concept.DATE,
                                Spans.JSON.textNode(date)));
                    }
                    spans.add(Spans.over(input, i, dateEnd, "d0", Span.Concept.EXPRESSION,
                            Spans.JSON.textNode(written), alternatives, "date"));
                } else {
                    if (dates.size() > 1) {
                        alternatives.add(Spans.alternative(Span.Concept.DATE,
                                Spans.JSON.textNode(dates.get(1))));
                    }
                    spans.add(Spans.over(input, i, dateEnd, "d0", Span.Concept.DATE,
                            Spans.JSON.textNode(dates.get(0)), alternatives, "date"));
                }
                Integer day = weekday(text);
                if (day != null) {
                    String value = WEEKDAYS.get(day - 1).toUpperCase(Locale.ROOT);
                    spans.add(Spans.over(input, i, i, "d0", Span.Concept.WEEKDAY,
                            Spans.JSON.textNode(value), List.of(), "weekday"));
                }
                i = dateEnd + 1;
                continue;
            }
            if (i + 1 < size) {
                String period = lower + " " + Spans.lower(input, i + 1);
                if (RELATIVE_PERIODS.contains(period)) {
                    spans.add(Spans.over(input, i, i + 1, "d0", Span.Concept.DATE,
                            Spans.JSON.objectNode().put("relative", period), List.of(), "relative-date"));
                    i += 2;
                    continue;
                }
            }
            if (RELATIVE_DAYS.containsKey(lower)) {
                List<JsonNode> alternatives = new ArrayList<>();
                if (input.context() != null && input.context().instant() != null
                        && input.context().zone() != null) {
                    LocalDate date = input.context().instant().atZone(input.context().zone()).toLocalDate()
                            .plusDays(RELATIVE_DAYS.get(lower));
                    alternatives.add(Spans.alternative(Span.Concept.DATE,
                            Spans.JSON.textNode(date.toString())));
                }
                spans.add(Spans.over(input, i, i, "d0", Span.Concept.DATE,
                        Spans.JSON.objectNode().put("relative", lower), alternatives, "relative-date"));
                i++;
                continue;
            }
            Integer day = weekday(text);
            if (day != null) {
                String value = WEEKDAYS.get(day - 1).toUpperCase(Locale.ROOT);
                spans.add(Spans.over(input, i, i, "d0", Span.Concept.WEEKDAY,
                        Spans.JSON.textNode(value), List.of(), "weekday"));
                i++;
                continue;
            }
            if (lower.equals("noon") || lower.equals("midnight")) {
                String value = lower.equals("noon") ? "12:00" : "00:00";
                spans.add(Spans.over(input, i, i, "d0", Span.Concept.TIME,
                        Spans.JSON.textNode(value), List.of(), "time"));
                i++;
                continue;
            }
            if (i + 2 < size && input.tokens().get(i).shape() == Token.Shape.NUMBER
                    && input.tokens().get(i + 2).shape() == Token.Shape.NUMBER
                    && Spans.surface(input, i + 1, i + 1).equals(":")
                    && Spans.touches(input, i) && Spans.touches(input, i + 1)) {
                String minuteText = Spans.surface(input, i + 2, i + 2);
                if (text.matches("[0-9]{1,2}") && minuteText.matches("[0-9]{2}")) {
                    int hour = Integer.parseInt(text);
                    int minute = Integer.parseInt(minuteText);
                    if (hour <= 23 && minute <= 59) {
                        String value = String.format(Locale.ROOT, "%02d:%02d", hour, minute);
                        spans.add(Spans.over(input, i, i + 2, "d0", Span.Concept.TIME,
                                Spans.JSON.textNode(value), List.of(), "time"));
                        i += 3;
                        continue;
                    }
                }
            }
            Matcher hyphen = DIGIT_HYPHEN_UNIT.matcher(text);
            if (input.tokens().get(i).shape() == Token.Shape.IDENT && hyphen.matches()) {
                String unit = hyphen.group(2).toLowerCase(Locale.ROOT);
                boolean singular = List.of("millisecond", "second", "minute", "hour", "day", "week",
                        "month", "year").contains(unit);
                String value = singular ? durationValue(hyphen.group(1), unit) : null;
                if (value != null) {
                    spans.add(Spans.over(input, i, i, "d0", Span.Concept.DURATION,
                            Spans.JSON.textNode(value), List.of(), "duration"));
                    i++;
                    continue;
                }
            }
            int durationEnd = -1;
            String duration = null;
            for (int end = i + 1; end < size; end++) {
                String amount = durationAmount(input, i, end - 1);
                String unit = Spans.lower(input, end);
                String value = durationValue(amount, unit);
                if (value != null) {
                    durationEnd = end;
                    duration = value;
                    break;
                }
                if (!NumberRecognizer.NUMBER_WORDS.containsKey(unit)
                        && !NumberRecognizer.SCALES.containsKey(unit) && !unit.equals("and")
                        && !unit.matches("[a-z]+-[a-z]+")) {
                    break;
                }
            }
            if (durationEnd >= 0) {
                spans.add(Spans.over(input, i, durationEnd, "d0", Span.Concept.DURATION,
                        Spans.JSON.textNode(duration), List.of(), "duration"));
                i = durationEnd + 1;
            } else {
                i++;
            }
        }
        spans.sort(Comparator.comparingInt((Span span) -> span.range().start())
                .thenComparing(Comparator.comparingInt((Span span) -> span.range().end()).reversed()));
        List<Span> result = new ArrayList<>();
        for (Span span : spans) {
            result.add(new Span("d" + (result.size() + 1), span.range(), span.concept(), span.value(),
                    span.alternatives(), span.provenance()));
        }
        return List.copyOf(result);
    }

    /**
     * Whether a math sign is beside the tokens {@code first} to {@code last} of {@code input}: the token right before
     * them or right after them, on their line, is one of {@link ExpressionRecognizer#ARITHMETIC} or a key of
     * {@link ExpressionRecognizer#OPERATORS}, touching them or not ({@code 2026-2-30+1}, {@code x = 2026-02-30}); a
     * {@code -} or a {@code /} counts only when whitespace is on both of its sides and a NUMBER token or a bracket
     * ({@code )} or {@code ]} before it, {@code (} or {@code [} after it) lies beyond it on the same line
     * ({@code 2026-10-28 - 5}; not the dash of {@code 2026-10-28 - done}, the path of {@code logs/2026-10-28}, nor a
     * list item's {@code -} at a line's start).
     */
    public static boolean mathBeside(Input input, int first, int last) {
        if (input == null || first < 0 || last < first || last >= input.tokens().size()) {
            return false;
        }
        return mathTokenBeside(input, first - 1, first, true)
                || mathTokenBeside(input, last + 1, last, false);
    }

    /**
     * Whether a math sign is beside the characters {@code start} (inclusive) to {@code end} (exclusive) of
     * {@code text}, as {@link #mathBeside} reads it among tokens: an operator right before or right after them, spaces
     * allowed between ({@code x = 2026-02-30}, {@code 2026-2-30+1}); a {@code -} or a {@code /} only with spaces on
     * both of its sides and a digit or a bracket beyond it. No line break comes between.
     */
    public static boolean mathBesideInText(String text, int start, int end) {
        if (text == null || start < 0 || end < start || end > text.length()) {
            return false;
        }
        int left = start - 1;
        while (left >= 0 && text.charAt(left) != '\n' && Character.isWhitespace(text.charAt(left))) {
            left--;
        }
        if (left >= 0 && text.charAt(left) != '\n') {
            String operator = String.valueOf(text.charAt(left));
            if (left > 0) {
                String pair = text.substring(left - 1, left + 1);
                if (ExpressionRecognizer.OPERATORS.containsKey(pair)) {
                    operator = pair;
                }
            }
            if (ExpressionRecognizer.ARITHMETIC.contains(operator)
                    || ExpressionRecognizer.OPERATORS.containsKey(operator)) {
                if (!operator.equals("-") && !operator.equals("/")) {
                    return true;
                }
                int before = left - 1;
                int after = left + 1;
                boolean spaced = before >= 0 && after < text.length()
                        && Character.isWhitespace(text.charAt(before))
                        && Character.isWhitespace(text.charAt(after));
                while (before >= 0 && text.charAt(before) != '\n'
                        && Character.isWhitespace(text.charAt(before))) {
                    before--;
                }
                if (spaced && before >= 0
                        && (Character.isDigit(text.charAt(before)) || text.charAt(before) == ')'
                        || text.charAt(before) == ']')) {
                    return true;
                }
            }
        }
        int right = end;
        while (right < text.length() && text.charAt(right) != '\n'
                && Character.isWhitespace(text.charAt(right))) {
            right++;
        }
        if (right >= text.length() || text.charAt(right) == '\n') {
            return false;
        }
        String operator = String.valueOf(text.charAt(right));
        if (right + 1 < text.length()) {
            String pair = text.substring(right, right + 2);
            if (ExpressionRecognizer.OPERATORS.containsKey(pair)) {
                operator = pair;
            }
        }
        if (!ExpressionRecognizer.ARITHMETIC.contains(operator)
                && !ExpressionRecognizer.OPERATORS.containsKey(operator)) {
            return false;
        }
        if (!operator.equals("-") && !operator.equals("/")) {
            return true;
        }
        int before = right - 1;
        int after = right + 1;
        boolean spaced = before >= 0 && after < text.length()
                && Character.isWhitespace(text.charAt(before))
                && Character.isWhitespace(text.charAt(after));
        while (after < text.length() && text.charAt(after) != '\n'
                && Character.isWhitespace(text.charAt(after))) {
            after++;
        }
        return spaced && after < text.length()
                && (Character.isDigit(text.charAt(after)) || text.charAt(after) == '('
                || text.charAt(after) == '[');
    }

    /** The month that {@code word} names, 1 to 12, as the class javadoc matches it; null for any other word. */
    public static Integer month(String word) {
        if (word == null || word.isEmpty()) {
            return null;
        }
        String name = word.endsWith(".") ? word.substring(0, word.length() - 1) : word;
        int full = MONTHS.indexOf(name);
        if (full >= 0) {
            return full + 1;
        }
        return MONTH_ABBREVIATIONS.get(name);
    }

    /** The weekday that {@code word} names, 1 (Monday) to 7, as the class javadoc matches it; null for any other. */
    public static Integer weekday(String word) {
        if (word == null || word.isEmpty()) {
            return null;
        }
        String name = word.endsWith(".") ? word.substring(0, word.length() - 1) : word;
        int full = WEEKDAYS.indexOf(name);
        if (full >= 0) {
            return full + 1;
        }
        return WEEKDAY_ABBREVIATIONS.get(name);
    }

    /**
     * Whether the day of a DATE value exists in its month: in its year ({@code "2026-02-28"}), or for a date without a
     * year in any year ({@code "--02-29"}, a leap day); false for {@code "2026-02-29"}, {@code "--02-30"} and
     * {@code "--10-32"}.
     */
    public static boolean exists(String value) {
        try {
            if (value.startsWith("--")) {
                int month = Integer.parseInt(value.substring(2, 4));
                int day = Integer.parseInt(value.substring(5, 7));
                LocalDate.of(2000, month, day);
            } else {
                LocalDate.parse(value);
            }
            return true;
        } catch (DateTimeException | NumberFormatException | IndexOutOfBoundsException e) {
            return false;
        }
    }

    private static String calendarValue(int year, int month, int day, boolean hasYear) {
        if (month < 1 || month > 12 || day == 0) {
            return null;
        }
        return hasYear
                ? String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
                : String.format(Locale.ROOT, "--%02d-%02d", month, day);
    }

    private static List<String> dateValues(String text, String locale) {
        Matcher iso = ISO_DATE.matcher(text);
        if (iso.matches()) {
            String value = calendarValue(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(3)),
                    Integer.parseInt(iso.group(4)), true);
            return value == null ? List.of() : List.of(value);
        }
        Matcher slash = SLASH_DATE.matcher(text);
        if (slash.matches()) {
            int first = Integer.parseInt(slash.group(1));
            int second = Integer.parseInt(slash.group(3));
            int year = Integer.parseInt(slash.group(4));
            String monthFirst = calendarValue(year, first, second, true);
            String dayFirst = calendarValue(year, second, first, true);
            boolean us = "en-US".equals(locale);
            String primary = us ? monthFirst : dayFirst;
            String alternative = us ? dayFirst : monthFirst;
            if (primary == null) {
                return alternative == null ? List.of() : List.of(alternative);
            }
            return alternative == null || alternative.equals(primary)
                    ? List.of(primary) : List.of(primary, alternative);
        }
        String named = text;
        Matcher prefix = NAMED_PREFIX.matcher(named);
        if (prefix.matches() && weekday(prefix.group(1)) != null) {
            named = prefix.group(2);
        }
        Matcher monthFirst = MONTH_FIRST.matcher(named);
        Matcher dayFirst = DAY_FIRST.matcher(named);
        Integer monthNumber;
        int day;
        String yearText;
        if (monthFirst.matches()) {
            monthNumber = month(monthFirst.group(1));
            day = Integer.parseInt(monthFirst.group(2));
            yearText = monthFirst.group(3);
        } else if (dayFirst.matches()) {
            monthNumber = month(dayFirst.group(2));
            day = Integer.parseInt(dayFirst.group(1));
            yearText = dayFirst.group(3);
        } else {
            return List.of();
        }
        if (monthNumber == null) {
            return List.of();
        }
        String value = calendarValue(yearText == null ? 2000 : Integer.parseInt(yearText), monthNumber,
                day, yearText != null);
        return value == null ? List.of() : List.of(value);
    }

    private static boolean dateTokens(Input input, int from, int to) {
        if (from == to) {
            String text = Spans.surface(input, from, to);
            if (ISO_DATE.matcher(text).matches() || SLASH_DATE.matcher(text).matches()) {
                Token.Shape shape = input.tokens().get(from).shape();
                return shape == Token.Shape.NUMBER || shape == Token.Shape.VERSION;
            }
        }
        for (int i = from; i <= to; i++) {
            String text = Spans.surface(input, i, i);
            if (text.matches("[0-9]+")) {
                if (input.tokens().get(i).shape() != Token.Shape.NUMBER) {
                    return false;
                }
            } else if (text.matches("[0-9]{1,2}(?:st|nd|rd|th)")) {
                continue;
            } else if (text.equals("of")) {
                continue;
            } else if (text.equals("-") || text.equals("/")) {
                if (i == from || i == to || !Spans.touches(input, i - 1) || !Spans.touches(input, i)) {
                    return false;
                }
            } else if (!text.equals(",") && !text.equals(".") && month(text) == null && weekday(text) == null) {
                return false;
            }
        }
        return true;
    }

    private static String durationValue(String amount, String unit) {
        String designator = DURATION_UNITS.get(unit);
        if (amount == null || designator == null) {
            return null;
        }
        Rational value;
        try {
            value = Rational.parse(amount);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (designator.equals("W")) {
            value = value.multiply(Rational.of(7, 1));
            designator = "D";
        } else if (designator.equals("MS")) {
            value = value.divide(Rational.of(1000, 1));
            designator = "S";
        }
        String prefix = designator.equals("S") || designator.equals("M") || designator.equals("H")
                ? "PT" : "P";
        if (designator.equals("MO")) {
            designator = "M";
        }
        return prefix + value.toDecimalString() + designator;
    }

    private static String durationAmount(Input input, int from, int to) {
        if (from == to) {
            String word = Spans.lower(input, from);
            if (word.equals("a") || word.equals("an")) {
                return "1";
            }
            if (input.tokens().get(from).shape() == Token.Shape.NUMBER) {
                return NumberRecognizer.decimalOf(Spans.surface(input, from, from));
            }
        }
        List<String> words = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            String word = Spans.surface(input, i, i);
            String lower = word.toLowerCase(Locale.ROOT);
            boolean leadingNumber = i == from && input.tokens().get(i).shape() == Token.Shape.NUMBER;
            if (!leadingNumber && !NumberRecognizer.NUMBER_WORDS.containsKey(lower)
                    && !NumberRecognizer.SCALES.containsKey(lower) && !lower.equals("and")
                    && !lower.matches("[a-z]+-[a-z]+")) {
                return null;
            }
            words.add(word);
        }
        return NumberRecognizer.wordsValue(words);
    }

    private static boolean unmistakableDate(Input input, int from, List<String> dates) {
        if (dates.stream().anyMatch(DateTimeRecognizer::exists)
                || dates.stream().anyMatch(value -> !value.startsWith("--"))) {
            return true;
        }
        String first = Spans.surface(input, from, from);
        return weekday(first) != null
                || from > 0 && DATE_CUES.contains(Spans.lower(input, from - 1));
    }

    private static boolean mathTokenBeside(Input input, int at, int date, boolean before) {
        int size = input.tokens().size();
        if (at < 0 || at >= size) {
            return false;
        }
        String operator = Spans.surface(input, at, at);
        if (operator.equals("**") || !ExpressionRecognizer.ARITHMETIC.contains(operator)
                && !ExpressionRecognizer.OPERATORS.containsKey(operator)) {
            return false;
        }
        int betweenStart = before ? input.tokens().get(at).range().end()
                : input.tokens().get(date).range().end();
        int betweenEnd = before ? input.tokens().get(date).range().start()
                : input.tokens().get(at).range().start();
        String between = input.text().substring(betweenStart, betweenEnd);
        if (between.indexOf('\n') >= 0 || between.indexOf('\r') >= 0) {
            return false;
        }
        if (!operator.equals("-") && !operator.equals("/")) {
            return true;
        }
        int beyond = before ? at - 1 : at + 1;
        if (beyond < 0 || beyond >= size || Spans.touches(input, at - 1) || Spans.touches(input, at)) {
            return false;
        }
        int beyondStart = before ? input.tokens().get(beyond).range().end()
                : input.tokens().get(at).range().end();
        int beyondEnd = before ? input.tokens().get(at).range().start()
                : input.tokens().get(beyond).range().start();
        String gap = input.text().substring(beyondStart, beyondEnd);
        if (gap.indexOf('\n') >= 0 || gap.indexOf('\r') >= 0) {
            return false;
        }
        Token token = input.tokens().get(beyond);
        if (token.shape() == Token.Shape.NUMBER) {
            return true;
        }
        String bracket = Spans.surface(input, beyond, beyond);
        return before ? bracket.equals(")") || bracket.equals("]")
                : bracket.equals("(") || bracket.equals("[");
    }
}
