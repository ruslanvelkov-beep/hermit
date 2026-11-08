package hermit.text.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hermit.text.expr.Rational;
import hermit.text.model.Check;
import hermit.text.model.Span;
import hermit.text.semantic.NumberRecognizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Totals and units (milestone A5 of the annotation plan, section 7: "took 2 minutes, 90 seconds and 1 min, 4.5 minutes
 * in total"), checker {@code unit}. A statement is a total: a DURATION or QUANTITY span followed by the words
 * {@code in total} or {@code altogether}, or preceded by {@code a total of}, in a sentence that holds at least two
 * spans of the total's dimension before it; those spans are its parts. The dimensions are time (a DURATION), data size
 * (a QUANTITY in one of {@link NumberRecognizer#DATA_UNITS}) and money (a QUANTITY in a currency, one dimension for
 * each currency code). A span of another dimension is no part, so amounts whose units do not match are never added up.
 * A reading holds when the parts add up to the total exactly, in seconds ({@link #seconds}), in bytes ({@link #bytes})
 * or in the currency's units: KB, MB, GB and TB are read both as powers of 1000 and as powers of 1024 (two readings
 * when the statement uses one of them), every other unit one way. The status comes from {@link Checks#status}, with the
 * reasons {@code total-matches}, {@code total-mismatch} and {@code ambiguous-units}; a duration in months or years has
 * no exact length and makes the statement UNVERIFIABLE (reason {@code calendar-units}). Evidence:
 * {@code {"dimension": "time", "parts": 3, "readings": [{"sum": "270", "total": "270"}]}}, the sums and the totals as
 * {@link Rational#toString} writes them. Targets: the parts in text order, then the total. Checks come in text order.
 */
public final class UnitChecker implements Checker {

    private static final Pattern DURATION_PATTERN = Pattern.compile("P(?:([0-9]+(?:\\.[0-9]+)?)D)?"
            + "(?:T(?:([0-9]+(?:\\.[0-9]+)?)H)?(?:([0-9]+(?:\\.[0-9]+)?)M)?"
            + "(?:([0-9]+(?:\\.[0-9]+)?)S)?)?");
    private static final Pattern DATA_UNIT_PATTERN = Pattern.compile("([KMGT])(i)?B");

    /** {@code unit}. */
    @Override
    public String name() {
        return "unit";
    }

    /** The checks of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Check> check(Input input) {
        List<Check> checks = new ArrayList<>();
        for (Span total : input.spans()) {
            String dimension = unitDimension(total);
            if (dimension == null || !unitTotalMarked(total, input)) {
                continue;
            }
            List<Span> parts = new ArrayList<>();
            for (Span span : input.spans()) {
                if (span == total || span.range().start() >= total.range().start()) {
                    continue;
                }
                if (Checks.sentenceOf(input, span) != Checks.sentenceOf(input, total)) {
                    continue;
                }
                if (dimension.equals(unitDimension(span))) {
                    parts.add(span);
                }
            }
            if (parts.size() < 2) {
                continue;
            }
            boolean calendarUnits = false;
            List<Boolean> readings = new ArrayList<>();
            ArrayNode readingEvidence = Checks.JSON.arrayNode();
            int readingCount = "data".equals(dimension) && unitAmbiguousPrefix(parts, total) ? 2 : 1;
            for (int reading = 0; reading < readingCount; reading++) {
                boolean binary = reading == 1;
                Rational sum = Rational.ZERO;
                boolean exact = true;
                for (Span part : parts) {
                    Rational value = unitNormalized(part, dimension, binary);
                    if (value == null) {
                        exact = false;
                        calendarUnits = "time".equals(dimension);
                        break;
                    }
                    sum = sum.add(value);
                }
                Rational expected = unitNormalized(total, dimension, binary);
                if (expected == null) {
                    exact = false;
                    calendarUnits = "time".equals(dimension);
                }
                if (exact) {
                    readings.add(sum.equals(expected));
                    ObjectNode evidence = Checks.JSON.objectNode();
                    evidence.put("sum", sum.toString());
                    evidence.put("total", expected.toString());
                    readingEvidence.add(evidence);
                }
            }
            Check.Status status = calendarUnits ? Check.Status.UNVERIFIABLE : Checks.status(readings);
            String reason = calendarUnits ? "calendar-units"
                    : status == Check.Status.CONSISTENT ? "total-matches"
                    : status == Check.Status.INCONSISTENT ? "total-mismatch" : "ambiguous-units";
            ObjectNode evidence = Checks.JSON.objectNode();
            evidence.put("dimension", dimension);
            evidence.put("parts", parts.size());
            evidence.set("readings", readingEvidence);
            List<Span> targets = new ArrayList<>(parts);
            targets.add(total);
            checks.add(Checks.check(name(), status, targets, evidence, List.of(), List.of(reason)));
        }
        return checks;
    }

    /**
     * The length in seconds of a duration as the recognizers write it in ISO 8601 ({@code PT2M}, {@code PT2.5H},
     * {@code P2D}, {@code PT0.5S}): days, hours, minutes and seconds; null for months, years or anything else.
     */
    public static Rational seconds(String duration) {
        if (duration == null) {
            return null;
        }
        Matcher matcher = DURATION_PATTERN.matcher(duration);
        if (!matcher.matches()) {
            return null;
        }
        boolean found = false;
        Rational result = Rational.ZERO;
        long[] factors = {86_400L, 3_600L, 60L, 1L};
        for (int group = 1; group <= factors.length; group++) {
            if (matcher.group(group) != null) {
                found = true;
                result = result.add(Rational.parse(matcher.group(group))
                        .multiply(Rational.of(factors[group - 1], 1)));
            }
        }
        return found ? result : null;
    }

    /**
     * The bytes in one {@code unit} of data size: {@code B}, {@code byte} and {@code bytes} one; {@code KB},
     * {@code MB}, {@code GB} and {@code TB} powers of 1024 when {@code binary}, else of 1000; {@code KiB} to
     * {@code TiB} always powers of 1024; null for any other unit.
     */
    public static Rational bytes(String unit, boolean binary) {
        if (unit == null) {
            return null;
        }
        if ("B".equals(unit) || "byte".equals(unit) || "bytes".equals(unit)) {
            return Rational.ONE;
        }
        Matcher matcher = DATA_UNIT_PATTERN.matcher(unit);
        if (!matcher.matches()) {
            return null;
        }
        int power = switch (matcher.group(1)) {
            case "K" -> 1;
            case "M" -> 2;
            case "G" -> 3;
            case "T" -> 4;
            default -> 0;
        };
        long base = matcher.group(2) != null || binary ? 1024L : 1000L;
        long factor = 1L;
        for (int i = 0; i < power; i++) {
            factor *= base;
        }
        return Rational.of(factor, 1);
    }

    private static boolean unitTotalMarked(Span span, Input input) {
        int first = Checks.firstToken(input, span);
        int last = Checks.lastToken(input, span);
        return "in".equals(Checks.word(input, last + 1))
                && "total".equals(Checks.word(input, last + 2))
                || "altogether".equals(Checks.word(input, last + 1))
                || "a".equals(Checks.word(input, first - 3))
                && "total".equals(Checks.word(input, first - 2))
                && "of".equals(Checks.word(input, first - 1));
    }

    private static String unitDimension(Span span) {
        if (span.concept() == Span.Concept.DURATION) {
            return "time";
        }
        if (span.concept() != Span.Concept.QUANTITY || span.value() == null || !span.value().isObject()) {
            return null;
        }
        String unit = span.value().path("unit").asText();
        if (bytes(unit, false) != null) {
            return "data";
        }
        String upper = unit.toUpperCase(Locale.ROOT);
        return upper.matches("[A-Z]{3}") ? upper : null;
    }

    private static boolean unitAmbiguousPrefix(List<Span> parts, Span total) {
        for (Span span : parts) {
            JsonNode value = span.value();
            if (value != null && value.path("unit").asText().matches("[KMGT]B")) {
                return true;
            }
        }
        JsonNode value = total.value();
        return value != null && value.path("unit").asText().matches("[KMGT]B");
    }

    private static Rational unitNormalized(Span span, String dimension, boolean binary) {
        JsonNode value = span.value();
        if ("time".equals(dimension)) {
            return value != null && value.isTextual() ? seconds(value.asText()) : null;
        }
        if (value == null || !value.isObject()) {
            return null;
        }
        String unit = value.path("unit").asText();
        Rational amount;
        try {
            amount = Rational.parse(value.path("amount").asText());
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if ("data".equals(dimension)) {
            Rational factor = bytes(unit, binary);
            return factor == null ? null : amount.multiply(factor);
        }
        return dimension.equals(unit.toUpperCase(Locale.ROOT)) ? amount : null;
    }
}
