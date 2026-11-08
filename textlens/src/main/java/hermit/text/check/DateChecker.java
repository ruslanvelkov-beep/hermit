package hermit.text.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hermit.text.model.Check;
import hermit.text.model.Clause;
import hermit.text.model.DateResolution;
import hermit.text.model.Span;
import hermit.text.semantic.DateTimeRecognizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dates and their weekdays (milestone A5 of the annotation plan, section 7: "Mon Oct 20" in 2026 is a Tuesday), checker
 * {@code date}, over the date layer that {@link #resolve} gives (the owner's design of 2026-11-04: what a date writes,
 * the full dates it can be, and its state). A statement is
 * <ul>
 *   <li>a DATE span that holds a WEEKDAY span (A3 puts a weekday right before a date inside it:
 *       {@code Mon Oct 20});</li>
 *   <li>a DATE span, a comma, optionally {@code a} or {@code an}, and a WEEKDAY span right after
 *       ({@code 03/04/2026, a Friday});</li>
 *   <li>a relative DATE span (valued {@code {"relative": ...}}), then {@code is}, {@code was} or a comma, then a
 *       WEEKDAY span ({@code Today is Thursday});</li>
 *   <li>a DATE span without a weekday whose state is INVALID or UNMATCHED: its day exists in none of its candidates
 *       ({@code on Feb 30}, {@code Feb 29} in a year without one).</li>
 * </ul>
 * The status comes from the date's state: CONSISTENT for PRECISE, DISAMBIGUATED and AMBIGUOUS, INCONSISTENT for
 * INVALID and UNMATCHED, UNVERIFIABLE for ASSUMED and UNVERIFIABLE. The reason is {@code weekday-matches} when the
 * check is consistent; {@code no-such-day} when it is inconsistent and no candidate's day exists, else
 * {@code weekday-mismatch}; {@code no-clock} when it is unverifiable. Evidence: the stated weekday (null without one),
 * the state in lower case, the written parts that are not null, the tense (its time in lower case and its cue, or null),
 * and one reading per candidate: its date as written with its year, its weekday (null when that day does not exist),
 * its fit in lower case with a hyphen, its time in lower case (null when it has none) and its tense fit in lower case
 * (null when it has none): {@code {"stated": "MONDAY", "state": "unmatched", "written": {"month": 10, "day": 20},
 * "tense": {"time": "past", "cue": "was"}, "readings": [{"date": "2026-10-20", "weekday": "TUESDAY", "fit":
 * "computed-incorrect", "time": "past", "tense": "fits"}]}}. The assumptions are the candidates' derived years, each of the kind {@code year} with the year as its
 * value and its source, in candidate order and once each.
 * Targets: the date span, then the weekday span when there is one. Checks come in the order of the dates.
 */
public final class DateChecker implements Checker {

    /**
     * A year that a text mentions, at the character {@code position} where the mention starts, with the month that the
     * mention gives (1 to 12), or 0 when it gives none ({@code in 2002}).
     */
    public record YearMention(int position, int year, int month) {}

    /** The month names and abbreviations in lower case, and their numbers. */
    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("january", 1),
            Map.entry("february", 2), Map.entry("march", 3), Map.entry("april", 4), Map.entry("may", 5),
            Map.entry("june", 6), Map.entry("july", 7), Map.entry("august", 8), Map.entry("september", 9),
            Map.entry("october", 10), Map.entry("november", 11), Map.entry("december", 12), Map.entry("jan", 1),
            Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4), Map.entry("jun", 6), Map.entry("jul", 7),
            Map.entry("aug", 8), Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("oct", 10), Map.entry("nov", 11),
            Map.entry("dec", 12));

    /** The words after which a number from 1900 to 2099 is a year ({@code in 2002}). */
    private static final Set<String> YEAR_WORDS = Set.of("in", "since", "until", "by", "during");

    /** A source line that can mention a year: four digits from 1900 to 2099, or a short date ({@code 7/12/00}). */
    private static final Pattern YEAR_LINE = Pattern.compile("\\b(19|20)\\d{2}\\b|\\b\\d{1,2}/\\d{1,2}/\\d{2}\\b");

    /** {@code date}. */
    @Override
    public String name() {
        return "date";
    }

    /** The checks of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Check> check(Input input) {
        Map<String, Span> spans = new HashMap<>();
        for (Span span : input.spans()) {
            spans.put(span.id(), span);
        }
        List<Check> checks = new ArrayList<>();
        for (DateResolution date : resolve(input)) {
            Span span = spans.get(date.spanId());
            Span weekday = weekdayForDate(input, span);
            boolean inconsistent = date.state() == DateResolution.State.INVALID
                    || date.state() == DateResolution.State.UNMATCHED;
            boolean noDay = date.candidates().stream()
                    .noneMatch(candidate -> DateTimeRecognizer.exists(iso(candidate)));
            if (weekday == null && !(inconsistent && noDay)) {
                continue;
            }
            Check.Status status = switch (date.state()) {
                case PRECISE, DISAMBIGUATED, AMBIGUOUS -> Check.Status.CONSISTENT;
                case INVALID, UNMATCHED -> Check.Status.INCONSISTENT;
                case ASSUMED, UNVERIFIABLE -> Check.Status.UNVERIFIABLE;
            };
            String reason = status == Check.Status.CONSISTENT ? "weekday-matches"
                    : status == Check.Status.INCONSISTENT ? (noDay ? "no-such-day" : "weekday-mismatch") : "no-clock";
            ObjectNode evidence = Checks.JSON.objectNode();
            if (date.weekday() == null) {
                evidence.putNull("stated");
            } else {
                evidence.put("stated", date.weekday());
            }
            evidence.put("state", date.state().name().toLowerCase(Locale.ROOT));
            ObjectNode written = evidence.putObject("written");
            if (date.year() != null) {
                written.put("year", date.year());
            }
            if (date.month() != null) {
                written.put("month", date.month());
            }
            if (date.day() != null) {
                written.put("day", date.day());
            }
            if (date.tense() == null) {
                evidence.putNull("tense");
            } else {
                ObjectNode tense = evidence.putObject("tense");
                tense.put("time", date.tense().time().name().toLowerCase(Locale.ROOT));
                tense.put("cue", date.tense().cue());
            }
            ArrayNode readings = evidence.putArray("readings");
            List<Check.Assumption> assumptions = new ArrayList<>();
            for (DateResolution.Candidate candidate : date.candidates()) {
                ObjectNode reading = readings.addObject();
                reading.put("date", iso(candidate));
                if (DateTimeRecognizer.exists(iso(candidate))) {
                    reading.put("weekday", LocalDate.parse(iso(candidate)).getDayOfWeek().name());
                } else {
                    reading.putNull("weekday");
                }
                reading.put("fit", candidate.fit().name().toLowerCase(Locale.ROOT).replace('_', '-'));
                if (candidate.time() == null) {
                    reading.putNull("time");
                } else {
                    reading.put("time", candidate.time().name().toLowerCase(Locale.ROOT));
                }
                if (candidate.tense() == null) {
                    reading.putNull("tense");
                } else {
                    reading.put("tense", candidate.tense().name().toLowerCase(Locale.ROOT));
                }
                String source = candidate.derived().get("year");
                Check.Assumption year = new Check.Assumption("year", Checks.JSON.numberNode(candidate.year()), source);
                if (source != null && !assumptions.contains(year)) {
                    assumptions.add(year);
                }
            }
            checks.add(Checks.check(name(), status, weekday == null ? List.of(span) : List.of(span, weekday),
                    evidence, assumptions, List.of(reason)));
        }
        return checks;
    }

    /**
     * The date layer of {@code input}: one {@link DateResolution} for each span that is a DATE span or has a DATE
     * alternative ({@code 03/04}, or {@code 3/4} read first as a fraction), in span order (the owner's design of
     * 2026-11-04). The span's DATE readings are its value when it is a DATE span, then each DATE alternative that
     * reads another date; more than one reading leaves the order derived.
     * <ul>
     *   <li>The written parts: those of the first DATE reading. A reading {@code "2026-10-20"} writes the year, the
     *       month and the day, {@code "--10-20"} the month and the day, a relative date none of them. The weekday is
     *       the value of the WEEKDAY span of the date's statement (see the class javadoc), or null.</li>
     *   <li>The candidates, reading by reading in their order:
     *     <ul>
     *       <li>for a reading with its year: the reading itself; when there is more than one reading, it derives
     *           {@code order}, from {@code value} when the reading is the span's value and {@code alternative}
     *           otherwise, else it derives nothing;</li>
     *       <li>for a reading without a year: one for each candidate year of its month, which derives {@code year}
     *           from its source, and {@code order} as above when there is more than one reading.
     *           The candidate years are each distinct year of {@link #yearMentions} of the input ({@code text}), then
     *           each further one of its {@link Checker.Input#sourceMentions} ({@code source}); when there is none, the
     *           year of the clock's day in the document's zone ({@code clock}); then the years of the year boundary
     *           ({@code boundary}). A reference point is a mention with a month, in the text and then in the sources,
     *           and the clock's day when the clock gives the year: one in January or February adds the year before it
     *           for a date in October, November or December, and one in November or December adds the year after it
     *           for a date in January or February. Each year comes once, with its first source;</li>
     *       <li>for a relative date: each DATE alternative (the day that the clock gives), which derives {@code date}
     *           from {@code clock}.</li>
     *     </ul>
     *   </li>
     *   <li>The fit of a candidate: COMPUTED_INCORRECT when its day does not exist ({@link DateTimeRecognizer#exists})
     *       or a written weekday is not its weekday; else COMPUTED_CORRECT when there is a written weekday or the
     *       candidate derives nothing, and UNTESTED otherwise. Its time: PAST, PRESENT or FUTURE as it lies before, on
     *       or after the clock's day in the document's zone; null without a clock or when its day does not exist.</li>
     *   <li>The tense: the one {@link TenseReader#read} gives the span in the clauses of {@link TenseReader#clauses}
     *       (the owner, 2026-11-05), or null. A candidate's tense fit: FITS when the tense allows its time, CONFLICTS
     *       when not, null when there is no tense or the candidate has no time. The kept candidates are those that do
     *       not conflict, or all of them when every one conflicts: the tense never leaves a date without
     *       candidates.</li>
     *   <li>The state: INVALID, without candidates, when every reading is a month and a day without a year that exist
     *       in no year ({@code --02-30}); else UNVERIFIABLE without candidates; else, over the kept candidates:
     *       PRECISE or INVALID when they derive nothing, as the first is COMPUTED_CORRECT or not; else DISAMBIGUATED
     *       with exactly one COMPUTED_CORRECT candidate, AMBIGUOUS with more than one, ASSUMED with UNTESTED ones; and
     *       when every one is COMPUTED_INCORRECT, UNMATCHED when one derives a year or a date, else INVALID (only the
     *       order was derived).</li>
     *   <li>The date ({@code "2026-10-20"}): the COMPUTED_CORRECT kept candidate's when the state is PRECISE or
     *       DISAMBIGUATED, the kept candidate's when the state is ASSUMED with one kept candidate, else null.</li>
     * </ul>
     */
    public static List<DateResolution> resolve(Checker.Input input) {
        List<DateResolution> resolutions = new ArrayList<>();
        LocalDate clock = input.context() == null || input.context().instant() == null ? null
                : input.context().instant().atZone(input.context().zone()).toLocalDate();
        List<Clause> clauses = TenseReader.clauses(input);
        for (Span span : input.spans()) {
            List<String> readings = new ArrayList<>();
            List<String> sources = new ArrayList<>();
            boolean relative = false;
            String value = dateText(span.value());
            if (span.concept() == Span.Concept.DATE) {
                if (span.value() != null && span.value().isObject() && span.value().has("relative")) {
                    relative = true;
                } else if (value != null && (value.matches("\\d{4}-\\d{2}-\\d{2}")
                        || value.matches("--\\d{2}-\\d{2}")) && !readings.contains(value)) {
                    readings.add(value);
                    sources.add("value");
                }
            }
            for (JsonNode alternative : span.alternatives()) {
                if (!"DATE".equals(alternative.path("concept").asText())) {
                    continue;
                }
                String alternativeText = dateText(alternative);
                if (alternativeText != null
                        && (alternativeText.matches("\\d{4}-\\d{2}-\\d{2}")
                        || alternativeText.matches("--\\d{2}-\\d{2}"))
                        && !readings.contains(alternativeText)) {
                    readings.add(alternativeText);
                    sources.add("alternative");
                }
            }
            if (span.concept() != Span.Concept.DATE && readings.isEmpty()) {
                continue;
            }
            DateResolution.Tense tense = TenseReader.read(input, clauses, span);
            boolean ordered = readings.size() > 1;
            Integer year = null;
            Integer month = null;
            Integer day = null;
            if (!relative && !readings.isEmpty()) {
                String first = readings.get(0);
                if (first.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    year = Integer.parseInt(first.substring(0, 4));
                    month = Integer.parseInt(first.substring(5, 7));
                    day = Integer.parseInt(first.substring(8, 10));
                } else {
                    month = Integer.parseInt(first.substring(2, 4));
                    day = Integer.parseInt(first.substring(5, 7));
                }
            }
            Span weekdaySpan = weekdayForDate(input, span);
            String weekday = weekdaySpan == null ? null : weekdaySpan.value().asText().toUpperCase(Locale.ROOT);
            List<int[]> raw = new ArrayList<>();
            List<Map<String, String>> derived = new ArrayList<>();
            if (relative) {
                for (JsonNode alternative : span.alternatives()) {
                    String alternativeText = dateText(alternative);
                    if (alternativeText != null && alternativeText.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        raw.add(new int[] {Integer.parseInt(alternativeText.substring(0, 4)),
                                Integer.parseInt(alternativeText.substring(5, 7)),
                                Integer.parseInt(alternativeText.substring(8, 10))});
                        derived.add(Map.of("date", "clock"));
                    }
                }
            } else {
                List<YearMention> textMentions = yearMentions(input);
                List<YearMention> sourceMentions = input.sourceMentions();
                for (int index = 0; index < readings.size(); index++) {
                    String reading = readings.get(index);
                    String readingSource = sources.get(index);
                    Map<String, String> orderDerived = ordered
                            ? Map.of("order", readingSource) : Map.of();
                    if (reading.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        raw.add(new int[] {Integer.parseInt(reading.substring(0, 4)),
                                Integer.parseInt(reading.substring(5, 7)),
                                Integer.parseInt(reading.substring(8, 10))});
                        derived.add(orderDerived);
                    } else {
                        int readingMonth = Integer.parseInt(reading.substring(2, 4));
                        int readingDay = Integer.parseInt(reading.substring(5, 7));
                        Map<Integer, String> years = new LinkedHashMap<>();
                        List<YearMention> references = new ArrayList<>();
                        for (YearMention mention : textMentions) {
                            years.putIfAbsent(mention.year(), "text");
                            if (mention.month() != 0) {
                                references.add(mention);
                            }
                        }
                        for (YearMention mention : sourceMentions) {
                            years.putIfAbsent(mention.year(), "source");
                            if (mention.month() != 0) {
                                references.add(mention);
                            }
                        }
                        if (years.isEmpty() && clock != null) {
                            years.put(clock.getYear(), "clock");
                            references.add(new YearMention(0, clock.getYear(), clock.getMonthValue()));
                        }
                        for (YearMention reference : references) {
                            if (reference.month() <= 2 && readingMonth >= 10) {
                                years.putIfAbsent(reference.year() - 1, "boundary");
                            } else if (reference.month() >= 11 && readingMonth <= 2) {
                                years.putIfAbsent(reference.year() + 1, "boundary");
                            }
                        }
                        if (existsInAnyYear(readingMonth, readingDay)) {
                            for (Map.Entry<Integer, String> candidateYear : years.entrySet()) {
                                raw.add(new int[] {candidateYear.getKey(), readingMonth, readingDay});
                                Map<String, String> candidateDerived = new LinkedHashMap<>();
                                if (ordered) {
                                    candidateDerived.put("order", readingSource);
                                }
                                candidateDerived.put("year", candidateYear.getValue());
                                derived.add(candidateDerived);
                            }
                        }
                    }
                }
            }
            List<DateResolution.Candidate> candidates = new ArrayList<>();
            for (int index = 0; index < raw.size(); index++) {
                int[] parts = raw.get(index);
                String candidateIso = String.format(Locale.ROOT, "%04d-%02d-%02d", parts[0], parts[1], parts[2]);
                boolean exists = DateTimeRecognizer.exists(candidateIso);
                boolean agrees = exists && (weekday == null
                        || LocalDate.parse(candidateIso).getDayOfWeek().name().equals(weekday));
                DateResolution.Fit fit = !agrees ? DateResolution.Fit.COMPUTED_INCORRECT
                        : weekday != null || derived.get(index).isEmpty() ? DateResolution.Fit.COMPUTED_CORRECT
                        : DateResolution.Fit.UNTESTED;
                DateResolution.Time time = null;
                if (clock != null && exists) {
                    int comparison = LocalDate.parse(candidateIso).compareTo(clock);
                    time = comparison < 0 ? DateResolution.Time.PAST
                            : comparison == 0 ? DateResolution.Time.PRESENT : DateResolution.Time.FUTURE;
                }
                DateResolution.TenseFit tenseFit = tense == null || time == null ? null
                        : tense.allows(time) ? DateResolution.TenseFit.FITS : DateResolution.TenseFit.CONFLICTS;
                candidates.add(new DateResolution.Candidate(parts[0], parts[1], parts[2], derived.get(index), fit,
                        time, tenseFit));
            }
            List<DateResolution.Candidate> kept = new ArrayList<>();
            for (DateResolution.Candidate candidate : candidates) {
                if (candidate.tense() != DateResolution.TenseFit.CONFLICTS) {
                    kept.add(candidate);
                }
            }
            if (kept.isEmpty()) {
                kept.addAll(candidates);
            }
            DateResolution.State state;
            if (candidates.isEmpty()) {
                boolean everyReadingImpossible = !readings.isEmpty();
                for (String reading : readings) {
                    if (reading.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        everyReadingImpossible = false;
                        break;
                    }
                    int readingMonth = Integer.parseInt(reading.substring(2, 4));
                    int readingDay = Integer.parseInt(reading.substring(5, 7));
                    if (existsInAnyYear(readingMonth, readingDay)) {
                        everyReadingImpossible = false;
                        break;
                    }
                }
                state = everyReadingImpossible ? DateResolution.State.INVALID
                        : DateResolution.State.UNVERIFIABLE;
            } else if (kept.stream().allMatch(candidate -> candidate.derived().isEmpty())) {
                state = kept.get(0).fit() == DateResolution.Fit.COMPUTED_CORRECT
                        ? DateResolution.State.PRECISE : DateResolution.State.INVALID;
            } else {
                long correct = kept.stream().filter(candidate ->
                        candidate.fit() == DateResolution.Fit.COMPUTED_CORRECT).count();
                boolean untested = kept.stream()
                        .anyMatch(candidate -> candidate.fit() == DateResolution.Fit.UNTESTED);
                if (correct == 1) {
                    state = DateResolution.State.DISAMBIGUATED;
                } else if (correct > 1) {
                    state = DateResolution.State.AMBIGUOUS;
                } else if (untested) {
                    state = DateResolution.State.ASSUMED;
                } else {
                    boolean contextual = kept.stream().anyMatch(candidate ->
                            candidate.derived().containsKey("year") || candidate.derived().containsKey("date"));
                    state = contextual ? DateResolution.State.UNMATCHED : DateResolution.State.INVALID;
                }
            }
            String resolved = null;
            if (state == DateResolution.State.PRECISE || state == DateResolution.State.DISAMBIGUATED) {
                for (DateResolution.Candidate candidate : kept) {
                    if (candidate.fit() == DateResolution.Fit.COMPUTED_CORRECT) {
                        resolved = iso(candidate);
                        break;
                    }
                }
            } else if (state == DateResolution.State.ASSUMED && kept.size() == 1) {
                resolved = iso(kept.get(0));
            }
            resolutions.add(new DateResolution(span.id(), year, month, day, weekday, tense, candidates, state,
                    resolved));
        }
        return resolutions;
    }

    /**
     * The years {@code input} mentions, in text order: the year and the month of each DATE span valued as a full date
     * ({@code "2001-05-07"}, whether or not that day exists), at the span's start, then those of each of its DATE
     * alternatives valued as a full date, at the same start ({@code 01/10/2002}: October, then January, the other
     * order); and each NUMBER span written as four digits from 1900 to 2099 whose previous token is a month name
     * ({@code July 2001}, {@code Sept 2001}: with that month) or {@code in}, {@code since}, {@code until}, {@code by}
     * or {@code during} ({@code in 2002}: month 0), without regard to case, at the number's start.
     */
    public static List<YearMention> yearMentions(Checker.Input input) {
        List<YearMention> mentions = new ArrayList<>();
        for (Span span : input.spans()) {
            if (span.concept() == Span.Concept.DATE) {
                String normalized = dateText(span.value());
                if (normalized != null && normalized.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    mentions.add(new YearMention(span.range().start(), Integer.parseInt(normalized.substring(0, 4)),
                            Integer.parseInt(normalized.substring(5, 7))));
                    for (JsonNode alternative : span.alternatives()) {
                        if (!Span.Concept.DATE.name().equals(alternative.path("concept").asText())) {
                            continue;
                        }
                        normalized = dateText(alternative.get("value"));
                        if (normalized != null && normalized.matches("\\d{4}-\\d{2}-\\d{2}")) {
                            mentions.add(new YearMention(span.range().start(),
                                    Integer.parseInt(normalized.substring(0, 4)),
                                    Integer.parseInt(normalized.substring(5, 7))));
                        }
                    }
                }
                continue;
            }
            if (span.concept() != Span.Concept.NUMBER) {
                continue;
            }
            String surface = span.range().surfaceIn(input.text());
            if (!surface.matches("(19|20)\\d{2}")) {
                continue;
            }
            // a number that starts the text has no word before it, and Map.of(...).get refuses the null
            String previous = Checks.word(input, Checks.firstToken(input, span) - 1);
            if (previous != null && MONTHS.containsKey(previous)) {
                mentions.add(new YearMention(span.range().start(), Integer.parseInt(surface), MONTHS.get(previous)));
            } else if (previous != null && YEAR_WORDS.contains(previous)) {
                mentions.add(new YearMention(span.range().start(), Integer.parseInt(surface), 0));
            }
        }
        mentions.sort((left, right) -> Integer.compare(left.position(), right.position()));
        return mentions;
    }

    /**
     * The distinct mentions (by year and month) that {@code sources} hold, in the order of their first mention: the
     * sources in their order, each line by line. Only a line that holds four digits from 1900 to 2099 or a short date
     * ({@code 7/12/00}) is read, as {@link #yearMentions} of its own standard input without a clock, so a mention's
     * position is one within its line: a tool result can be long, and the annotator reads many short texts faster
     * than one long one. A null source mentions nothing.
     */
    public static List<YearMention> sourceMentions(List<String> sources) {
        List<YearMention> mentions = new ArrayList<>();
        Set<List<Integer>> seen = new HashSet<>();
        for (String source : sources) {
            if (source == null) {
                continue;
            }
            for (String line : source.split("\n")) {
                if (!YEAR_LINE.matcher(line).find()) {
                    continue;
                }
                for (YearMention mention : yearMentions(Checkers.input(line, null))) {
                    if (seen.add(List.of(mention.year(), mention.month()))) {
                        mentions.add(mention);
                    }
                }
            }
        }
        return mentions;
    }

    /** A candidate's date as written with its year ({@code "2026-02-30"} when that day does not exist). */
    private static String iso(DateResolution.Candidate candidate) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", candidate.year(), candidate.month(), candidate.day());
    }

    private static String dateText(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isObject()) {
            for (String field : List.of("date", "value")) {
                JsonNode nested = value.get(field);
                if (nested != null && nested.isTextual()) {
                    return nested.asText();
                }
            }
        }
        return null;
    }

    private static Span weekdayForDate(Input input, Span date) {
        for (Span span : input.spans()) {
            if (span.concept() == Span.Concept.WEEKDAY
                    && date.range().start() <= span.range().start()
                    && span.range().end() <= date.range().end()) {
                return span;
            }
        }
        // a date that ends the text has no next word: Checks.word gives null, which Set.of(...).contains refuses
        int token = Checks.lastToken(input, date) + 1;
        boolean relative = date.value().isObject() && date.value().has("relative");
        if (relative) {
            String link = Checks.word(input, token);
            if (link == null || !Set.of("is", "was", ",").contains(link)) {
                return null;
            }
            token++;
        } else {
            if (!",".equals(Checks.word(input, token))) {
                return null;
            }
            token++;
            String article = Checks.word(input, token);
            if ("a".equals(article) || "an".equals(article)) {
                token++;
            }
        }
        return Checks.spanAt(input, token, Set.of(Span.Concept.WEEKDAY));
    }

    private static boolean existsInAnyYear(Integer month, Integer day) {
        if (month == null || day == null) {
            return false;
        }
        for (int year = 2000; year < 2400; year++) {
            String value = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
            if (DateTimeRecognizer.exists(value)) {
                return true;
            }
        }
        return false;
    }
}
