package hermit.text.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hermit.text.model.Check;
import hermit.text.model.Span;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Version order (milestone A5 of the annotation plan, section 7: 1.10 is newer than 1.9), checker {@code version}. A
 * statement is, in one sentence, a NUMBER or VERSION span, then optionally {@code is}, {@code was}, {@code are} or
 * {@code were}, then one of {@link #PHRASES}, then optionally {@code version}, {@code release} or {@code build}, then a
 * NUMBER or VERSION span, nothing else between them. A side's version reading is its VERSION value or VERSION
 * alternative, else its NUMBER value when that is a whole number (a version of one part); its number reading is its
 * NUMBER value or NUMBER alternative. The phrases of {@link #VERSION_PHRASES} compare the version readings of the two
 * sides; {@code exceeds} compares the version readings and the number readings, each pair that both sides have being
 * one reading of the statement. Versions are ordered by {@link #compare}, numbers by their exact values; the status
 * comes from {@link Checks#status}, with the reasons {@code order-holds}, {@code order-fails} and
 * {@code readings-disagree}. A statement without a reading is UNVERIFIABLE with the reason {@code not-a-version}.
 * Evidence: {@code {"left": "2.10", "phrase": "newer than", "right": "2.9"}}, the two spans as written. Targets: the
 * two spans. Checks come in text order.
 */
public final class VersionChecker implements Checker {

    /** The phrases and the order they state: 1 when the left side is the greater, -1 when it is the lesser. */
    public static final Map<String, Integer> PHRASES = Map.of("newer than", 1, "later than", 1, "older than", -1,
            "earlier than", -1, "supersedes", 1, "precedes", -1, "exceeds", 1);
    /** The phrases that compare versions only. */
    public static final Set<String> VERSION_PHRASES = Set.of("newer than", "later than", "older than", "earlier than",
            "supersedes", "precedes");

    /** {@code version}. */
    @Override
    public String name() {
        return "version";
    }

    /** The checks of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Check> check(Input input) {
        List<Check> checks = new ArrayList<>();
        for (Span left : input.spans()) {
            if (!versionSide(left)) {
                continue;
            }
            int token = Checks.lastToken(input, left) + 1;
            if (token < input.tokens().size() && link(Checks.word(input, token))) {
                token++;
            }
            int phraseStart = token;
            String found = null;
            for (String phrase : PHRASES.keySet()) {
                String[] words = phrase.split(" ");
                boolean matches = true;
                for (int k = 0; k < words.length; k++) {
                    if (!words[k].equals(Checks.word(input, phraseStart + k))) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    found = phrase;
                    break;
                }
            }
            if (found == null) {
                continue;
            }
            int after = phraseStart + found.split(" ").length;
            if (after < input.tokens().size() && label(Checks.word(input, after))) {
                after++;
            }
            Span right = null;
            for (Span span : input.spans()) {
                if (versionSide(span) && Checks.firstToken(input, span) == after) {
                    right = span;
                    break;
                }
            }
            if (right == null) {
                continue;
            }
            List<Boolean> readings = new ArrayList<>();
            String leftText = left.range().surfaceIn(input.text());
            String rightText = right.range().surfaceIn(input.text());
            Integer versionOrder = compare(leftText, rightText);
            if (versionOrder != null) {
                readings.add(Integer.signum(versionOrder) == PHRASES.get(found));
            }
            if (!VERSION_PHRASES.contains(found)) {
                for (String a : numberValues(left)) {
                    for (String b : numberValues(right)) {
                        try {
                            int numberOrder = new BigDecimal(a).compareTo(new BigDecimal(b));
                            readings.add(Integer.signum(numberOrder) == PHRASES.get(found));
                        } catch (NumberFormatException ignored) {
                            // not a number reading
                        }
                    }
                }
            }
            Check.Status status = Checks.status(readings);
            String reason = status == Check.Status.CONSISTENT ? "order-holds"
                    : status == Check.Status.INCONSISTENT ? "order-fails" : "readings-disagree";
            ObjectNode evidence = Checks.JSON.objectNode();
            evidence.put("left", leftText);
            evidence.put("phrase", found);
            evidence.put("right", rightText);
            checks.add(Checks.check(name(), status, List.of(left, right), evidence, List.of(), List.of(reason)));
        }
        return checks;
    }

    /**
     * The order of two versions, part by part as whole numbers after an optional leading {@code v} or {@code V}, a
     * missing part counting as zero ({@code 2.0} and {@code 2.0.0} are the same): negative, zero or positive as
     * {@code a} is older than, the same as or newer than {@code b}; null when either is no such version.
     */
    public static Integer compare(String a, String b) {
        if (a == null || b == null) {
            return null;
        }
        String left = a.matches("[vV].*") ? a.substring(1) : a;
        String right = b.matches("[vV].*") ? b.substring(1) : b;
        if (!left.matches("[0-9]+(?:\\.[0-9]+)*") || !right.matches("[0-9]+(?:\\.[0-9]+)*")) {
            return null;
        }
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            BigInteger leftPart = i < leftParts.length ? new BigInteger(leftParts[i]) : BigInteger.ZERO;
            BigInteger rightPart = i < rightParts.length ? new BigInteger(rightParts[i]) : BigInteger.ZERO;
            int order = leftPart.compareTo(rightPart);
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }

    private static boolean versionSide(Span span) {
        return span.concept() == Span.Concept.NUMBER || span.concept() == Span.Concept.VERSION;
    }

    private static boolean label(String word) {
        return "version".equals(word) || "release".equals(word) || "build".equals(word);
    }

    private static boolean link(String word) {
        return "is".equals(word) || "was".equals(word) || "are".equals(word) || "were".equals(word);
    }

    private static List<String> numberValues(Span span) {
        List<String> values = new ArrayList<>();
        JsonNode value = span.value();
        if (value != null && value.isTextual()) {
            values.add(value.asText());
        }
        for (JsonNode alternative : span.alternatives()) {
            if (alternative.isTextual() && !values.contains(alternative.asText())) {
                values.add(alternative.asText());
            }
        }
        return values;
    }
}
