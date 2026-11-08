package hermit.text.check;

import com.fasterxml.jackson.databind.JsonNode;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import hermit.text.model.Span;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What would refuse a text (plan section 7): the inconsistent checks of the {@link #REFUSING} checkers that the text
 * asserts, each with the sentence that states it. TextLens only reports them, and a host decides what to do with them
 * (the owner, 2026-11-07: every host only records them for now). An unverifiable check is never a finding, and neither
 * is a {@link #quoted} one nor an {@link #example}: the text quotes that text, it does not assert it.
 */
public final class Findings {

    /**
     * The checkers whose inconsistent result is a finding. The count checker is not one of them: it stays in the shadow
     * until it tells a count of the listed items from a number that only introduces a list (the shadow replay of
     * 2026-11-04).
     */
    public static final Set<String> REFUSING = Set.of("date", "arithmetic", "unit", "version");
    /** The words that introduce an example, in lower case: the code span right after them is one ({@link #example}). */
    static final List<String> EXAMPLE_CUES = List.of("as in", "e.g.", "for example", "for instance", "such as", "like");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH);

    /** One finding: the check's checker and targets, and the sentence that states it ({@link #sentence}). */
    public record Finding(String checker, List<String> targets, String sentence) {}

    private Findings() {
    }

    /** The text of the span {@code spanId} as {@code document} writes it; the id when it is no span. */
    private static String surface(AnnotationDocument document, String spanId) {
        for (Span span : document.spans()) {
            if (span.id().equals(spanId)) {
                return span.range().surfaceIn(document.text());
            }
        }
        return spanId;
    }

    /** The English name of {@code month} (1 is January). */
    private static String monthName(int month) {
        return Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /** {@code weekday} as a sentence writes it: MONDAY as Monday. */
    private static String day(String weekday) {
        return weekday.isEmpty() ? weekday
                : weekday.charAt(0) + weekday.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Whether one of {@link #EXAMPLE_CUES} ends right before the token {@code code}, a comma or a colon between. */
    private static boolean exampleCueBefore(AnnotationDocument document, int code) {
        int last = code - 1;
        if (last >= 0) {
            String separator = document.tokens().get(last).range().surfaceIn(document.text());
            if (separator.equals(",") || separator.equals(":")) {
                last--;
            }
        }
        for (String cue : EXAMPLE_CUES) {
            String[] words = cue.split(" ");
            int first = last - words.length + 1;
            if (first < 0) {
                continue;
            }
            boolean matches = true;
            for (int word = 0; word < words.length; word++) {
                String written = document.tokens().get(first + word).range().surfaceIn(document.text());
                if (!written.toLowerCase(Locale.ROOT).equals(words[word])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every target of {@code check} lies inside the range of a QUOTATION marker of {@code document} (its
     * quotation marks included); false for a check with no targets, a target that is no span or token of the document,
     * and a document without markers.
     */
    static boolean quoted(AnnotationDocument document, Check check) {
        if (check.targets().isEmpty() || document.layers() == null || document.layers().markers() == null
                || document.layers().markers().isEmpty()) {
            return false;
        }
        for (String target : check.targets()) {
            var range = document.spans().stream().filter(span -> span.id().equals(target)).map(span -> span.range())
                    .findFirst().orElseGet(() -> document.tokens().stream()
                            .filter(token -> token.id().equals(target)).map(token -> token.range()).findFirst()
                            .orElse(null));
            if (range == null || document.layers().markers().stream()
                    .filter(marker -> "QUOTATION".equals(marker.markerClass().name()))
                    .noneMatch(marker -> marker.range().contains(range))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every target of {@code check} lies inside a CODE_SPAN token of {@code document} that gives an example
     * (the owner, 2026-11-06): one that comes right after one of {@link #EXAMPLE_CUES}, in any case, with at most a
     * comma or a colon between them ({@code as in `2 + 2 = 5`}, {@code e.g., `03/04`}), or right after a comma, an
     * {@code or} or an {@code and} (or a comma and one of the two) that follows such a token
     * ({@code such as `1/2`, `3/4` or `5/6`}). False for a check with no targets and for a target that is no span or
     * token of the document.
     */
    static boolean example(AnnotationDocument document, Check check) {
        if (check.targets().isEmpty()) {
            return false;
        }
        List<Integer> examples = new ArrayList<>();
        int previousCode = -1;
        boolean previousExample = false;
        for (int i = 0; i < document.tokens().size(); i++) {
            var token = document.tokens().get(i);
            String written = token.range().surfaceIn(document.text());
            if (written.length() < 2 || written.charAt(0) != '`'
                    || written.charAt(written.length() - 1) != '`') {
                continue;
            }
            boolean introduced = exampleCueBefore(document, i);
            boolean continued = false;
            if (previousCode >= 0 && previousExample) {
                var previous = document.tokens().get(previousCode);
                String between = document.text().substring(previous.range().end(), token.range().start())
                        .toLowerCase(Locale.ROOT).strip();
                continued = between.equals(",") || between.equals("or") || between.equals("and")
                        || between.equals(", or") || between.equals(", and");
            }
            previousExample = introduced || continued;
            if (previousExample) {
                examples.add(i);
            }
            previousCode = i;
        }
        for (String target : check.targets()) {
            var range = document.spans().stream().filter(span -> span.id().equals(target)).map(span -> span.range())
                    .findFirst().orElseGet(() -> document.tokens().stream()
                            .filter(token -> token.id().equals(target)).map(token -> token.range()).findFirst()
                            .orElse(null));
            if (range == null) {
                return false;
            }
            boolean inside = false;
            for (int example : examples) {
                if (document.tokens().get(example).range().contains(range)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                return false;
            }
        }
        return true;
    }

    /**
     * The check's own sentence, as a finding states it ("October 20, 2026 is a Tuesday, not a Monday."). A date
     * check states the readings whose tense does not conflict (all of them when every one conflicts), a past one with
     * {@code was} ("December 29, 2026 was a Tuesday, not a Wednesday.").
     */
    public static String sentence(AnnotationDocument document, Check check) {
        JsonNode evidence = check.evidence();
        String first = check.targets().isEmpty() ? "" : surface(document, check.targets().get(0));
        switch (check.checker()) {
            case "date" -> {
                List<JsonNode> readings = new ArrayList<>();
                for (JsonNode reading : evidence.path("readings")) {
                    if (!"conflicts".equals(reading.path("tense").asText())) {
                        readings.add(reading);
                    }
                }
                if (readings.isEmpty()) {
                    evidence.path("readings").forEach(readings::add);
                }
                List<String> weekdays = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                String firstVerb = null;
                for (JsonNode reading : readings) {
                    String date = reading.path("date").asText();
                    if (reading.path("weekday").isTextual()) {
                        String verb = "past".equals(reading.path("time").asText()) ? "was" : "is";
                        if (firstVerb == null) {
                            firstVerb = verb;
                        }
                        String link = weekdays.isEmpty() || !verb.equals(firstVerb) ? " " + verb + " a " : " a ";
                        weekdays.add(LocalDate.parse(date).format(LONG_DATE) + link
                                + day(reading.path("weekday").asText()));
                    } else {
                        missing.add(monthName(Integer.parseInt(date.substring(5, 7))) + " "
                                + Integer.parseInt(date.substring(8, 10)) + ", " + date.substring(0, 4));
                    }
                }
                if (weekdays.isEmpty() && missing.isEmpty()) {
                    JsonNode written = evidence.path("written");
                    return "There is no " + monthName(written.path("month").asInt()) + " "
                            + written.path("day").asInt() + ".";
                }
                List<String> sentences = new ArrayList<>();
                if (!weekdays.isEmpty()) {
                    sentences.add(String.join(" and ", weekdays) + (evidence.path("stated").isTextual()
                            ? ", not a " + day(evidence.path("stated").asText()) : "") + ".");
                }
                if (!missing.isEmpty()) {
                    sentences.add(String.join(" and ", missing) + (missing.size() == 1 ? " does" : " do")
                            + " not exist.");
                }
                return String.join(" ", sentences);
            }
            case "arithmetic" -> {
                return first + " does not hold: " + evidence.path("left").asText() + " "
                        + evidence.path("op").asText() + " " + evidence.path("right").asText() + " is false.";
            }
            case "count" -> {
                return "The text announces " + evidence.path("stated").asInt()
                        + " items, but the list that follows has " + evidence.path("listed").asInt() + ".";
            }
            case "unit" -> {
                JsonNode reading = evidence.path("readings").path(0);
                String unit = switch (evidence.path("dimension").asText()) {
                    case "time" -> "seconds";
                    case "data" -> "bytes";
                    default -> evidence.path("dimension").asText();
                };
                return "The parts add up to " + reading.path("sum").asText() + " " + unit
                        + ", not the stated total of " + reading.path("total").asText() + " " + unit + ".";
            }
            case "version" -> {
                return evidence.path("left").asText() + " is not " + evidence.path("phrase").asText() + " "
                        + evidence.path("right").asText() + ".";
            }
            default -> {
                return "The " + check.checker() + " check is inconsistent: " + evidence + ".";
            }
        }
    }

    /** The findings of {@code document}, in the order of its checks. */
    public static List<Finding> of(AnnotationDocument document) {
        List<Finding> findings = new ArrayList<>();
        for (Check check : document.checks()) {
            if (check.status() == Check.Status.INCONSISTENT && REFUSING.contains(check.checker())
                    && !quoted(document, check) && !example(document, check)) {
                findings.add(new Finding(check.checker(), check.targets(), sentence(document, check)));
            }
        }
        return findings;
    }
}
