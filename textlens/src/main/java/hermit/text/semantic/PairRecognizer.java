package hermit.text.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import hermit.text.lex.WordCategories;
import hermit.text.model.Span;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two numbers joined by a touching separator (the owner, 2026-11-04): {@code 3/4}, {@code 2-20}, {@code 2..20}. One
 * span over the three tokens, rule {@code pair}, keeps every reading: its concept and value are the first reading's,
 * and its alternatives are the others, in this order of preference. The numbers are NUMBER tokens written as plain
 * numerals (digits with an optional fraction).
 * <ul>
 *   <li>A slash between numbers ({@code 3/4}, {@code 03/04}, {@code 10/11}, {@code 120/128}): the EXPRESSION valued
 *       with the text as written ({@code "3/4"}), but not for a divisor 0; for integers whose first is at most the
 *       second, and the second not 0, the TALLY ({@code 3/4}: 3 of 4, the owner, 2026-11-05); and for integers of one
 *       or two digits the DATE readings day first ({@code 03/04}: {@code "--04-03"}) and month first
 *       ({@code "--03-04"}), each once and only when that day exists in some year ({@link DateTimeRecognizer#exists}).
 *       The dates come first, then the expression and the tally, when both numbers are written with two digits
 *       ({@code 03/04}, {@code 10/11}, {@code 20/02}) or one of {@link DateTimeRecognizer#DATE_CUES} comes right
 *       before, in any case; else the expression, the tally and the dates come in this order ({@code 3/4}).</li>
 *   <li>A hyphen between numbers ({@code 2-20}, {@code 02-20}, {@code 0.5-1.5}): the EXPRESSION valued with the text as
 *       written; for integers of one or two digits, the DATE readings month first ({@code 02-20}: {@code "--02-20"})
 *       and day first, each once and only when that day exists in some year; the RANGE when the first number is below
 *       the second; and for integers the SECTION, valued {@code {"parts": ["2", "20"]}} (paragraph 2, article 20).
 *       The dates come first when a number is written with a leading zero ({@code 02-20}) or a date cue comes right
 *       before ({@code since 10-25}); else the expression comes first, then the dates, the range and the
 *       section.</li>
 *   <li>Two dots between numbers ({@code 2..20}, {@code 0.5..1.5}): the RANGE only, when the first number is below the
 *       second.</li>
 * </ul>
 * A RANGE is valued {@code {"from": "2", "to": "20", "kind": "integers"}}, the numbers as
 * {@link NumberRecognizer#decimalOf} gives them; its kind is {@code reals} when a number has a fraction or the two
 * integers are consecutive ({@code 0-1}, {@code 1..2}: a range of integers holds more than its two ends). The SECTION's
 * parts are decimals in the same way, and so are a TALLY's count and total, valued {@code {"count": "3", "of": "4"}}.
 *
 * <p>Its context drops a pair's DATE readings (the owner, 2026-11-05), keeping the others in their order:
 * <ul>
 *   <li>arithmetic ({@link #inArithmetic}): {@code (3/4)/2}, {@code (3/4) + (1/2)}, {@code 3/4 = 0.75},
 *       {@code x = 3/4}; brackets alone keep them ({@code (3/4)}), and so does a TIME noun before an equals sign
 *       ({@code deadline = 3/4});</li>
 *   <li>a noun after it that has no time component ({@link #beforeTimelessNoun}): {@code 2-5 objects},
 *       {@code from 2-5 seconds}; {@code the 10/25 meeting} and {@code the 10-25 events} keep them, and so does a pair
 *       with a number written with a leading zero ({@code the 11-03 sweep}).</li>
 * </ul>
 * A pair right before a name ({@link #nameEnd}; the owner, 2026-11-06: {@code the 9/11 Commission}) keeps its DATE
 * readings, which come first; with one, the name is a NAME span too (rule {@code name}) over the pair and the name,
 * valued with the text as written ({@code "9/11 Commission"}), and the pair's span nests in it.
 * A CODE_SPAN token whose text between its backticks is two such numbers and a separator alone ({@code `3/4`},
 * {@code `03/04`}, {@code `2-5`}; {@link #CODE_PAIR}) is a pair too (the owner, 2026-11-05: backquotes hold code, which
 * keeps only standard dates): its span covers the backticks, its values hold the text between them, it has a DATE
 * reading only when both numbers are written with two digits ({@code `03/04`}, {@code `10/25`}), and its context
 * drops nothing.
 *
 * <p>There is no span when a pair has no reading, or when it is part of a longer
 * chain: a touching {@code /}, {@code -}, {@code ..}, {@code .} or {@code :} token with a touching NUMBER token beyond
 * it on either side ({@code 2026-02-20}, {@code 1/2/3}, {@code 10:00-12:00}). Spans come in text order, a NAME before
 * the pair it holds, ids {@code p1}, {@code p2}, ...
 */
public final class PairRecognizer implements ConceptRecognizer {

    /** The separators of a pair. */
    public static final Set<String> SEPARATORS = Set.of("/", "-", "..");

    /** A pair alone between backticks: the first number, the separator, the second number. */
    public static final Pattern CODE_PAIR = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(/|-|\\.\\.)([0-9]+(?:\\.[0-9]+)?)");

    /** The opening brackets, which arithmetic around a pair may stand outside of. */
    public static final Set<String> OPENING = Set.of("(", "[");

    /** The closing brackets. */
    public static final Set<String> CLOSING = Set.of(")", "]");

    /** The spans of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Span> recognize(Input input) {
        List<Span> spans = new ArrayList<>();
        List<String> chainSeparators = List.of("/", "-", "..", ".", ":");
        int size = input.tokens().size();
        for (int i = 0; i < size; i++) {
            Token token = input.tokens().get(i);
            boolean codeSpan = token.shape() == Token.Shape.CODE_SPAN;
            Matcher codeMatch = codeSpan
                    ? CODE_PAIR.matcher(Spans.surface(input, i, i).substring(1,
                            Spans.surface(input, i, i).length() - 1))
                    : null;
            if (!codeSpan && i + 2 >= size || codeSpan && !codeMatch.matches()) {
                continue;
            }
            if (!codeSpan && (token.shape() != Token.Shape.NUMBER
                    || input.tokens().get(i + 2).shape() != Token.Shape.NUMBER
                    || !Spans.touches(input, i) || !Spans.touches(input, i + 1))) {
                continue;
            }
            String separator = codeSpan ? codeMatch.group(2) : Spans.surface(input, i + 1, i + 1);
            if (!SEPARATORS.contains(separator)) {
                continue;
            }
            boolean chainedLeft = !codeSpan && i >= 2
                    && input.tokens().get(i - 2).shape() == Token.Shape.NUMBER
                    && chainSeparators.contains(Spans.surface(input, i - 1, i - 1))
                    && Spans.touches(input, i - 2) && Spans.touches(input, i - 1);
            boolean chainedRight = !codeSpan && i + 4 < size
                    && chainSeparators.contains(Spans.surface(input, i + 3, i + 3))
                    && input.tokens().get(i + 4).shape() == Token.Shape.NUMBER
                    && Spans.touches(input, i + 2) && Spans.touches(input, i + 3);
            if (chainedLeft || chainedRight) {
                continue;
            }
            String firstText = codeSpan ? codeMatch.group(1) : Spans.surface(input, i, i);
            String secondText = codeSpan ? codeMatch.group(3) : Spans.surface(input, i + 2, i + 2);
            if (!firstText.matches("[0-9]+(?:\\.[0-9]+)?")
                    || !secondText.matches("[0-9]+(?:\\.[0-9]+)?")) {
                continue;
            }
            String first = NumberRecognizer.decimalOf(firstText);
            String second = NumberRecognizer.decimalOf(secondText);
            if (first == null || second == null) {
                continue;
            }
            boolean integers = firstText.matches("[0-9]+") && secondText.matches("[0-9]+");
            boolean shortIntegers = firstText.matches("[0-9]{1,2}")
                    && secondText.matches("[0-9]{1,2}");
            boolean standardCodeDate = codeSpan && firstText.matches("[0-9]{2}")
                    && secondText.matches("[0-9]{2}");
            boolean dateCue = !codeSpan && i > 0
                    && DateTimeRecognizer.DATE_CUES.contains(Spans.lower(input, i - 1));
            List<Span.Concept> concepts = new ArrayList<>();
            List<JsonNode> values = new ArrayList<>();
            List<Span.Concept> dates = new ArrayList<>();
            List<JsonNode> dateValues = new ArrayList<>();
            if (shortIntegers && (!codeSpan || standardCodeDate)) {
                int left = Integer.parseInt(firstText);
                int right = Integer.parseInt(secondText);
                int[][] orders = separator.equals("/")
                        ? new int[][] {{right, left}, {left, right}}
                        : new int[][] {{left, right}, {right, left}};
                for (int[] order : orders) {
                    String date = dateValue(order[0], order[1]);
                    if (date != null && dateValues.stream().noneMatch(value -> value.asText().equals(date))) {
                        dates.add(Span.Concept.DATE);
                        dateValues.add(Spans.JSON.textNode(date));
                    }
                }
            }
            int last = codeSpan ? i : i + 2;
            int nameLast = !codeSpan && !dates.isEmpty() ? nameEnd(input, last) : -1;
            boolean datesFirst = nameLast >= 0 || (separator.equals("/")
                    ? firstText.length() == 2 && secondText.length() == 2 || dateCue
                    : (firstText.length() > 1 && firstText.startsWith("0"))
                            || (secondText.length() > 1 && secondText.startsWith("0")) || dateCue);
            if (!separator.equals("..") && datesFirst) {
                concepts.addAll(dates);
                values.addAll(dateValues);
            }
            String written = codeSpan ? codeMatch.group() : Spans.surface(input, i, i + 2);
            if (separator.equals("-") || separator.equals("/") && !second.equals("0")) {
                concepts.add(Span.Concept.EXPRESSION);
                values.add(Spans.JSON.textNode(written));
            }
            BigDecimal lower = new BigDecimal(first);
            BigDecimal upper = new BigDecimal(second);
            if (separator.equals("/") && integers && !second.equals("0") && lower.compareTo(upper) <= 0) {
                concepts.add(Span.Concept.TALLY);
                values.add(Spans.JSON.objectNode().put("count", first).put("of", second));
            }
            if (!separator.equals("..") && !datesFirst) {
                concepts.addAll(dates);
                values.addAll(dateValues);
            }
            if (!separator.equals("/") && lower.compareTo(upper) < 0) {
                boolean consecutive = integers && upper.subtract(lower).compareTo(BigDecimal.ONE) == 0;
                String kind = !integers || consecutive ? "reals" : "integers";
                concepts.add(Span.Concept.RANGE);
                values.add(Spans.JSON.objectNode().put("from", first).put("to", second).put("kind", kind));
            }
            if (separator.equals("-") && integers) {
                concepts.add(Span.Concept.SECTION);
                values.add(Spans.JSON.objectNode().set("parts", Spans.JSON.arrayNode().add(first).add(second)));
            }
            boolean leadingZero = firstText.length() > 1 && firstText.startsWith("0")
                    || secondText.length() > 1 && secondText.startsWith("0");
            if (!codeSpan && (inArithmetic(input, i, last)
                    || nameLast < 0 && !leadingZero && beforeTimelessNoun(input, last))) {
                for (int reading = concepts.size() - 1; reading >= 0; reading--) {
                    if (concepts.get(reading) == Span.Concept.DATE) {
                        concepts.remove(reading);
                        values.remove(reading);
                    }
                }
            }
            if (concepts.isEmpty()) {
                continue;
            }
            if (nameLast >= 0 && concepts.contains(Span.Concept.DATE)) {
                String name = Spans.surface(input, i, nameLast);
                spans.add(Spans.over(input, i, nameLast, "p" + (spans.size() + 1), Span.Concept.NAME,
                        Spans.JSON.textNode(name), List.of(), "name"));
            }
            List<JsonNode> alternatives = new ArrayList<>();
            for (int reading = 1; reading < concepts.size(); reading++) {
                alternatives.add(Spans.alternative(concepts.get(reading), values.get(reading)));
            }
            spans.add(Spans.over(input, i, last, "p" + (spans.size() + 1), concepts.get(0), values.get(0),
                    alternatives, "pair"));
            if (!codeSpan) {
                i += 2;
            }
        }
        return List.copyOf(spans);
    }

    /**
     * Whether the tokens {@code first} to {@code last} stand in arithmetic. Before them, past the opening brackets
     * right before them: an operator of {@link ExpressionRecognizer#ARITHMETIC} whose token before it is a NUMBER or a
     * closing bracket, or one of {@link ExpressionRecognizer#OPERATORS} whose token before it is not a TIME noun
     * ({@link #timeNoun}). Or after them, past the closing brackets right after them: an arithmetic operator whose
     * token after it is a NUMBER or an opening bracket, or one of the operators. Whitespace does not matter.
     */
    static boolean inArithmetic(ConceptRecognizer.Input input, int first, int last) {
        int before = first - 1;
        while (before >= 0 && OPENING.contains(Spans.surface(input, before, before))) {
            before--;
        }
        if (before >= 0) {
            String operator = Spans.surface(input, before, before);
            int operand = before - 1;
            if (ExpressionRecognizer.ARITHMETIC.contains(operator) && operand >= 0
                    && (input.tokens().get(operand).shape() == Token.Shape.NUMBER
                    || CLOSING.contains(Spans.surface(input, operand, operand)))) {
                return true;
            }
            if (ExpressionRecognizer.OPERATORS.containsKey(operator)
                    && (operand < 0 || !timeNoun(input, operand))) {
                return true;
            }
        }
        int after = last + 1;
        while (after < input.tokens().size() && CLOSING.contains(Spans.surface(input, after, after))) {
            after++;
        }
        if (after >= input.tokens().size()) {
            return false;
        }
        String operator = Spans.surface(input, after, after);
        if (ExpressionRecognizer.OPERATORS.containsKey(operator)) {
            return true;
        }
        int operand = after + 1;
        return ExpressionRecognizer.ARITHMETIC.contains(operator) && operand < input.tokens().size()
                && (input.tokens().get(operand).shape() == Token.Shape.NUMBER
                || OPENING.contains(Spans.surface(input, operand, operand)));
    }

    /**
     * Whether a noun without a time component follows token {@code last}: the token after it, or the one after that
     * when there is one and the token after it is tagged ADJ (its first candidate), is a unit (one of
     * {@link NumberRecognizer#DATA_UNITS} as written, a key of {@link DateTimeRecognizer#DURATION_UNITS} in lower case,
     * or a WORD tagged NOUN that {@link WordCategories#ofNoun} gives UNIT), or is a WORD tagged NOUN that is not a TIME
     * noun.
     */
    static boolean beforeTimelessNoun(ConceptRecognizer.Input input, int last) {
        int noun = last + 1;
        if (noun >= input.tokens().size()) {
            return false;
        }
        if (input.tokens().get(noun).tag() == Tag.ADJ) {
            noun++;
        }
        if (noun >= input.tokens().size()) {
            return false;
        }
        String written = Spans.surface(input, noun, noun);
        String lower = written.toLowerCase(Locale.ROOT);
        if (NumberRecognizer.DATA_UNITS.contains(written)
                || DateTimeRecognizer.DURATION_UNITS.containsKey(lower)) {
            return true;
        }
        Token token = input.tokens().get(noun);
        if (token.shape() != Token.Shape.WORD || token.tag() != Tag.NOUN) {
            return false;
        }
        Set<WordCategories.Category> categories = WordCategories.standard().ofNoun(written);
        return categories.contains(WordCategories.Category.UNIT)
                || !categories.contains(WordCategories.Category.TIME);
    }

    /**
     * The last token of a name right after token {@code last}, or -1 when none follows: a run of capitalised words,
     * each a WORD tagged NOUN or PROPN (its first candidate) whose first letter is upper case and whose second is lower
     * case ({@code Commission}, {@code Commission Staff}), that the input does not read in lower case
     * ({@link ConceptRecognizer.Input#lowerCase}: {@code Pick 2-5 Objects} as a title) and that names no month or
     * weekday ({@link DateTimeRecognizer#month}, {@link DateTimeRecognizer#weekday}: {@code 3-4 May}).
     */
    static int nameEnd(ConceptRecognizer.Input input, int last) {
        int end = last;
        for (int i = last + 1; i < input.tokens().size(); i++) {
            Token token = input.tokens().get(i);
            String word = Spans.surface(input, i, i);
            if (token.shape() != Token.Shape.WORD
                    || token.tag() != Tag.NOUN && token.tag() != Tag.PROPN
                    || word.length() < 2
                    || !Character.isUpperCase(word.charAt(0))
                    || !Character.isLowerCase(word.charAt(1))
                    || input.lowerCase().contains(token.id())
                    || DateTimeRecognizer.month(word) != null || DateTimeRecognizer.weekday(word) != null) {
                break;
            }
            end = i;
        }
        return end == last ? -1 : end;
    }

    /** Whether token {@code i} is a WORD that {@link WordCategories#ofNoun} gives TIME. */
    static boolean timeNoun(ConceptRecognizer.Input input, int i) {
        if (i < 0 || i >= input.tokens().size()) {
            return false;
        }
        Token token = input.tokens().get(i);
        return token.shape() == Token.Shape.WORD
                && WordCategories.standard().ofNoun(Spans.surface(input, i, i))
                        .contains(WordCategories.Category.TIME);
    }

    private static String dateValue(int month, int day) {
        String value = String.format(Locale.ROOT, "--%02d-%02d", month, day);
        return DateTimeRecognizer.exists(value) ? value : null;
    }
}
