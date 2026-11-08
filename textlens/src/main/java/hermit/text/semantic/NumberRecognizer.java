package hermit.text.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import hermit.text.expr.Rational;
import hermit.text.model.Span;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Numbers, percentages and quantities (milestone A3 of the annotation plan, section 6.1).
 * <ul>
 *   <li>NUMBER: a NUMBER token (rule {@code number}) that is not a commit hash ({@link CodeRefRecognizer#digitHash}),
 *       valued by {@link #decimalOf}; when it is a plain two-part decimal ({@code 0.95}, {@code 1.10}: digits, one
 *       period, digits, nothing else) it keeps the alternative VERSION with the token as written. A run of number words
 *       (rule {@code number-word}), valued by {@link #wordsValue}: the longest run that it reads, with {@code and}
 *       allowed after {@code hundred}, {@code thousand}, {@code million} or {@code billion}; {@code one} alone counts
 *       only when the next token is {@code of}, in any case, or its first candidate is NOUN, PROPN, ADJ or NUM.</li>
 *   <li>PERCENT (rule {@code percent}): a number (token or words) followed by a {@code %} token that touches it, or by
 *       the word {@code percent}, or by {@code per cent}; valued by the number.</li>
 *   <li>QUANTITY (rule {@code quantity}): a number followed by one of {@link #DATA_UNITS} or {@link #CURRENCY_WORDS},
 *       as written, valued {@code {"amount": "12", "unit": "GB"}} (a currency word gives its code); or one of
 *       {@link #CURRENCY_SIGNS} touching a NUMBER token after it ({@code $9.99}), valued with the sign's code.</li>
 * </ul>
 * A number inside a percentage or a quantity is not reported on its own, and a NUMBER token that
 * {@link #labelNumber} reads as a label (a list, heading, section, step or row number) is not a span at all: it names
 * a place, not an amount. Spans come in text order, ids {@code n1}, {@code n2}, ...
 */
public final class NumberRecognizer implements ConceptRecognizer {

    /** The cardinal number words below a hundred and their values; a hyphenated pair ({@code twenty-one}) adds up. */
    public static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(Map.entry("zero", 0), Map.entry("one", 1),
            Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6),
            Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14),
            Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
            Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40),
            Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80),
            Map.entry("ninety", 90));
    /** The scale words and what they multiply by. */
    public static final Map<String, Long> SCALES = Map.of("hundred", 100L, "thousand", 1_000L, "million", 1_000_000L,
            "billion", 1_000_000_000L);
    /** Units of data size, matched as written. */
    public static final List<String> DATA_UNITS = List.of("B", "KB", "MB", "GB", "TB", "KiB", "MiB", "GiB", "TiB",
            "byte", "bytes");
    /** Currency signs written before an amount, and their codes. */
    public static final Map<String, String> CURRENCY_SIGNS = Map.of("$", "USD", "\u20ac", "EUR", "\u00a3", "GBP");
    /** Currency words written after an amount, and their codes. */
    public static final Map<String, String> CURRENCY_WORDS = Map.of("USD", "USD", "EUR", "EUR", "GBP", "GBP",
            "dollars", "USD", "euros", "EUR");
    /** The words after which a number is a label, in lower case: {@code step 9}, {@code Sections 1 and 3}. */
    public static final List<String> LABEL_WORDS = List.of("section", "sections", "step", "steps", "item", "items",
            "phase", "phases", "row", "rows", "task", "tasks", "milestone", "milestones", "chapter", "chapters",
            "round", "rounds", "stage", "stages", "figure", "figures");
    /** A plain two-part decimal numeral. */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("[0-9]+\\.[0-9]+");
    /** A hyphenated number word below a hundred. */
    private static final Pattern HYPHENATED_WORD = Pattern.compile("[a-z]+-[a-z]+");

    /** The spans of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Span> recognize(Input input) {
        List<Span> spans = new ArrayList<>();
        int nextId = 1;
        for (int i = 0; i < input.tokens().size();) {
            String surface = Spans.surface(input, i, i);
            String amount = null;
            int numberEnd = i;
            String numberRule = "number";
            if (input.tokens().get(i).shape() == Token.Shape.NUMBER) {
                if (CodeRefRecognizer.digitHash(input, i) || labelNumber(input, i)) {
                    i++;
                    continue;
                }
                amount = decimalOf(surface);
                int end = wordRunEnd(input, i);
                if (end > i) {
                    List<String> words = new ArrayList<>();
                    for (int j = i; j <= end; j++) {
                        words.add(Spans.surface(input, j, j));
                    }
                    String combined = wordsValue(words);
                    if (combined != null) {
                        amount = combined;
                        numberEnd = end;
                        numberRule = "number";
                    }
                }
            } else {
                int end = wordRunEnd(input, i);
                if (end >= i) {
                    List<String> words = new ArrayList<>();
                    for (int j = i; j <= end; j++) {
                        words.add(Spans.surface(input, j, j));
                    }
                    amount = wordsValue(words);
                    numberEnd = end;
                    numberRule = "number-word";
                    if (end == i && Spans.lower(input, i).equals("one") && !countedAfterOne(input, i)) {
                        amount = null;
                    }
                }
            }
            if (amount == null) {
                String sign = CURRENCY_SIGNS.get(surface);
                if (sign != null && i + 1 < input.tokens().size() && Spans.touches(input, i)
                        && input.tokens().get(i + 1).shape() == Token.Shape.NUMBER) {
                    String signedAmount = decimalOf(Spans.surface(input, i + 1, i + 1));
                    if (signedAmount != null) {
                        spans.add(Spans.over(input, i, i + 1, "n" + nextId++, Span.Concept.QUANTITY,
                                quantityValue(signedAmount, sign), List.of(), "quantity"));
                        i += 2;
                        continue;
                    }
                }
                i++;
                continue;
            }
            int end = numberEnd;
            Span.Concept concept = Span.Concept.NUMBER;
            JsonNode value = Spans.JSON.textNode(amount);
            String rule = numberRule;
            if (end + 1 < input.tokens().size() && Spans.surface(input, end + 1, end + 1).equals("%")
                    && Spans.touches(input, end)) {
                end++;
                concept = Span.Concept.PERCENT;
                rule = "percent";
            } else if ("percent".equals(Spans.lower(input, end + 1))) {
                end++;
                concept = Span.Concept.PERCENT;
                rule = "percent";
            } else if ("per".equals(Spans.lower(input, end + 1)) && "cent".equals(Spans.lower(input, end + 2))) {
                end += 2;
                concept = Span.Concept.PERCENT;
                rule = "percent";
            } else if (end + 1 < input.tokens().size()) {
                String unit = Spans.surface(input, end + 1, end + 1);
                String currency = CURRENCY_WORDS.get(unit);
                if (DATA_UNITS.contains(unit) || currency != null) {
                    end++;
                    concept = Span.Concept.QUANTITY;
                    value = quantityValue(amount, currency == null ? unit : currency);
                    rule = "quantity";
                }
            }
            List<JsonNode> alternatives = new ArrayList<>();
            if (concept == Span.Concept.NUMBER && numberEnd == i && plainDecimal(surface)) {
                alternatives.add(Spans.alternative(Span.Concept.VERSION, Spans.JSON.textNode(surface)));
            }
            spans.add(Spans.over(input, i, end, "n" + nextId++, concept, value, alternatives, rule));
            i = end + 1;
        }
        return List.copyOf(spans);
    }

    /**
     * The value of a NUMBER token's text as a decimal string: thousands groups dropped, a {@code k} or {@code K} suffix
     * times a thousand, {@code M} a million, {@code B} a billion, an exponent written out, a plus sign dropped
     * ({@code 3,500} gives {@code "3500"}, {@code 78k} {@code "78000"}, {@code 1e-9} {@code "0.000000001"},
     * {@code -2.50} {@code "-2.5"}); null when the text is no such number.
     */
    public static String decimalOf(String text) {
        if (text == null) {
            return null;
        }
        String numeral = text.replace(",", "");
        BigInteger multiplier = BigInteger.ONE;
        if (!numeral.isEmpty()) {
            char suffix = numeral.charAt(numeral.length() - 1);
            if (suffix == 'k' || suffix == 'K') {
                multiplier = BigInteger.valueOf(1_000L);
                numeral = numeral.substring(0, numeral.length() - 1);
            } else if (suffix == 'M') {
                multiplier = BigInteger.valueOf(1_000_000L);
                numeral = numeral.substring(0, numeral.length() - 1);
            } else if (suffix == 'B') {
                multiplier = BigInteger.valueOf(1_000_000_000L);
                numeral = numeral.substring(0, numeral.length() - 1);
            }
        }
        try {
            Rational value = Rational.parse(numeral).multiply(Rational.fraction(multiplier, BigInteger.ONE));
            return value.toDecimalString();
        } catch (IllegalArgumentException | ArithmeticException e) {
            return null;
        }
    }

    /**
     * The value of number words as a decimal string ({@code three} gives {@code "3"}, {@code twenty-one} {@code "21"},
     * {@code two hundred and five} {@code "205"}, {@code 3 thousand} {@code "3000"}): words of {@link #NUMBER_WORDS}
     * and {@link #SCALES}, any case, an {@code and} after a scale word, and a leading NUMBER token's value; null when
     * the words are not all of one number.
     */
    public static String wordsValue(List<String> words) {
        if (words.isEmpty()) {
            return null;
        }
        long total = 0;
        long group = 0;
        boolean valueExpected = true;
        boolean scaleSeen = false;
        boolean leadingNumber = false;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i).toLowerCase(Locale.ROOT);
            if (i == 0) {
                String decimal = decimalOf(word);
                if (decimal != null && !decimal.contains(".")) {
                    try {
                        group = Long.parseLong(decimal);
                        valueExpected = false;
                        leadingNumber = true;
                        continue;
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
            try {
                if (i == 1 && leadingNumber && !SCALES.containsKey(word)) {
                    return null;
                }
                if (word.contains("-")) {
                    String[] parts = word.split("-", -1);
                    if (parts.length != 2 || !NUMBER_WORDS.containsKey(parts[0])
                            || !NUMBER_WORDS.containsKey(parts[1]) || NUMBER_WORDS.get(parts[0]) < 20
                            || NUMBER_WORDS.get(parts[0]) % 10 != 0 || NUMBER_WORDS.get(parts[1]) >= 10) {
                        return null;
                    }
                    group = Math.addExact(group, (long) NUMBER_WORDS.get(parts[0]) + NUMBER_WORDS.get(parts[1]));
                    valueExpected = false;
                } else if (word.equals("hundred")) {
                    if (valueExpected || group == 0 || group >= 10) {
                        return null;
                    }
                    group = Math.multiplyExact(group, 100L);
                    valueExpected = false;
                    scaleSeen = true;
                } else if (SCALES.containsKey(word)) {
                    long scale = SCALES.get(word);
                    if (scale == 100L || valueExpected || group == 0) {
                        return null;
                    }
                    total = Math.addExact(total, Math.multiplyExact(group, scale));
                    group = 0;
                    valueExpected = true;
                    scaleSeen = true;
                } else if (NUMBER_WORDS.containsKey(word)) {
                    int value = NUMBER_WORDS.get(word);
                    if (!valueExpected && value < 100) {
                        if (value >= 20 && value % 10 == 0 && group % 100 < 20) {
                            group = Math.addExact(group, value);
                        } else if (value < 10 && group % 100 >= 20 && group % 10 == 0) {
                            group = Math.addExact(group, value);
                        } else {
                            return null;
                        }
                    } else {
                        group = Math.addExact(group, value);
                    }
                    valueExpected = false;
                } else if (word.equals("and")) {
                    if (!scaleSeen || valueExpected || i + 1 >= words.size()) {
                        return null;
                    }
                    valueExpected = true;
                } else {
                    return null;
                }
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        if (valueExpected && !SCALES.containsKey(words.get(words.size() - 1).toLowerCase(Locale.ROOT))) {
            return null;
        }
        try {
            return Long.toString(Math.addExact(total, group));
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    /**
     * Whether token {@code i}, a NUMBER token, is a label rather than an amount:
     * <ul>
     *   <li>the number of a list item or a heading: the first token of its sentence ({@link Spans#sentenceTokens})
     *       that is no SYMBOL or PUNCT token, with a {@code .} or {@code )} token touching it and no token touching
     *       that one ({@code 2. The first step}, {@code ## 11. Questions});</li>
     *   <li>the only token of a table row's first cell: the first such token of its sentence, with a {@code |} token
     *       right before it and right after it ({@code | 007 | Implement ... |});</li>
     *   <li>a number right after one of {@link #LABEL_WORDS}, in any case ({@code step 9}, {@code Sections 1}), or
     *       right after {@code and}, {@code or}, {@code to}, a comma, a hyphen or an en dash that follows such a label
     *       number ({@code Sections 1 and 3}, {@code steps 4-6}).</li>
     * </ul>
     * False for any other token, and when there is no token {@code i}.
     */
    public static boolean labelNumber(Input input, int i) {
        if (i < 0 || i >= input.tokens().size()
                || input.tokens().get(i).shape() != Token.Shape.NUMBER) {
            return false;
        }
        List<Integer> sentence = Spans.sentenceTokens(input, i);
        if (sentence.isEmpty()) {
            return false;
        }
        int first = -1;
        for (int index : sentence) {
            Token.Shape shape = input.tokens().get(index).shape();
            if (shape != Token.Shape.SYMBOL && shape != Token.Shape.PUNCT) {
                first = index;
                break;
            }
        }
        if (i == first && i + 1 < input.tokens().size()) {
            String after = Spans.surface(input, i + 1, i + 1);
            if ((after.equals(".") || after.equals(")")) && Spans.touches(input, i)
                    && !Spans.touches(input, i + 1)) {
                return true;
            }
            if (i > 0 && Spans.surface(input, i - 1, i - 1).equals("|") && after.equals("|")) {
                return true;
            }
        }
        if (i > 0 && LABEL_WORDS.contains(Spans.lower(input, i - 1))) {
            return true;
        }
        if (i < 2) {
            return false;
        }
        String connector = Spans.surface(input, i - 1, i - 1).toLowerCase(Locale.ROOT);
        if (!List.of("and", "or", "to", ",", "-", "\u2013").contains(connector)) {
            return false;
        }
        return labelNumber(input, i - 2);
    }

    private static boolean plainDecimal(String text) {
        return PLAIN_DECIMAL.matcher(text).matches();
    }

    private static boolean countedAfterOne(Input input, int i) {
        if (i + 1 >= input.tokens().size()) {
            return false;
        }
        if (Spans.lower(input, i + 1).equals("of")) {
            return true;
        }
        Tag tag = input.tokens().get(i + 1).tag();
        return tag == Tag.NOUN || tag == Tag.PROPN || tag == Tag.ADJ || tag == Tag.NUM;
    }

    private static int wordRunEnd(Input input, int start) {
        int best = start - 1;
        List<String> words = new ArrayList<>();
        for (int i = start; i < input.tokens().size(); i++) {
            String word = Spans.surface(input, i, i);
            String lower = word.toLowerCase(Locale.ROOT);
            if (i == start && input.tokens().get(i).shape() == Token.Shape.NUMBER) {
                words.add(word);
            } else if (NUMBER_WORDS.containsKey(lower) || SCALES.containsKey(lower) || lower.equals("and")
                    || HYPHENATED_WORD.matcher(lower).matches()) {
                words.add(word);
            } else {
                break;
            }
            if (wordsValue(words) != null) {
                best = i;
            }
        }
        return best;
    }

    private static JsonNode quantityValue(String amount, String unit) {
        return Spans.JSON.objectNode().put("amount", amount).put("unit", unit);
    }
}
