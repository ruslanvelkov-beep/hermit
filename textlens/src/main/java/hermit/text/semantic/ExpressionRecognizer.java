package hermit.text.semantic;

import hermit.text.expr.ExprParser;
import hermit.text.model.Span;
import hermit.text.model.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arithmetic expressions and comparisons (milestone A3 of the annotation plan, section 6.1), read with
 * {@link ExprParser}.
 * <ul>
 *   <li>EXPRESSION (rule {@code expression}): the longest run of tokens on one line, made of NUMBER tokens that are
 *       plain numerals (digits with an optional fraction), parentheses, the arithmetic operators of
 *       {@link #ARITHMETIC} and the comparison operators of {@link #OPERATORS}, that holds at least one operator
 *       between two numbers and that {@link ExprParser#parse} or {@link ExprParser#parseComparison} reads whole;
 *       valued with the run's text as written ({@code 2 + 2 = 5}). The ASCII {@code +}, {@code -}, {@code *} and
 *       {@code /} count as operators only with whitespace on both sides, or when the run's token right before them is
 *       a closing parenthesis or the token right after them an opening one ({@code (3/4)/2}), so {@code 10-27} and
 *       {@code 03/04/2026} are not expressions; but a fraction ({@link #fraction}: {@code 3/4}) is an operand, one
 *       number, and a run that holds one needs another operator ({@code 3/4 = 0.75}, {@code 1 - 3/4}; {@code 3/4}
 *       alone is a pair's, {@link PairRecognizer}). A run neither starts at the second number of a pair
 *       ({@link #joined}) nor ends at its first, so {@code 1 - 10-25} is not an expression. A CODE_SPAN token whose
 *       inside text (between the backticks) {@link #holdsComparison} ({@code `duplicate != null`}) or is
 *       {@link #arithmetic} ({@code `1+2`}, {@code `(3/4)/2`}) is an expression too, valued with the inside text; its
 *       span covers the backticks.</li>
 *   <li>COMPARISON (rule {@code comparison}): an operator of {@link #OPERATORS}, or a phrase of {@link #PHRASES} in any
 *       case, right before a NUMBER token or a word of {@link NumberRecognizer#NUMBER_WORDS} in any case
 *       ({@code more than one}), and not right after a number nor before a pair's first number ({@code = 3/4}),
 *       valued {@code {"op": ">=", "value": "0.95"}} with the operator as {@link ExprParser#parseComparison} reports
 *       it and the number's decimal value ({@link NumberRecognizer#decimalOf}) or the word's.</li>
 * </ul>
 * Spans come in text order, ids {@code e1}, {@code e2}, ...
 */
public final class ExpressionRecognizer implements ConceptRecognizer {

    /** The arithmetic operators: the ASCII ones, the minus sign, the times and dot operators, the division sign. */
    public static final List<String> ARITHMETIC = List.of("+", "-", "*", "/", "\u2212", "\u00d7", "\u22c5", "\u00f7");
    /** The comparison operators as written, and as reported. */
    public static final Map<String, String> OPERATORS = Map.of("<", "<", "<=", "<=", ">", ">", ">=", ">=", "=", "=",
            "==", "=", "!=", "!=", "\u2264", "<=", "\u2265", ">=", "\u2260", "!=");
    /** Comparison phrases and their operators. */
    public static final Map<String, String> PHRASES = Map.ofEntries(Map.entry("at most", "<="),
            Map.entry("at least", ">="), Map.entry("more than", ">"), Map.entry("greater than", ">"),
            Map.entry("less than", "<"), Map.entry("fewer than", "<"), Map.entry("up to", "<="),
            Map.entry("no more than", "<="), Map.entry("no less than", ">="), Map.entry("over", ">"),
            Map.entry("under", "<"), Map.entry("above", ">"), Map.entry("below", "<"));

    /** The spans of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Span> recognize(Input input) {
        List<Span> spans = new ArrayList<>();
        int size = input.tokens().size();
        for (int i = 0; i < size;) {
            Token token = input.tokens().get(i);
            String tokenText = Spans.surface(input, i, i);
            if (token.shape() == Token.Shape.CODE_SPAN) {
                String inside = tokenText.substring(1, tokenText.length() - 1);
                if (holdsComparison(inside) || arithmetic(inside)) {
                    spans.add(Spans.over(input, i, i, "e" + (spans.size() + 1),
                            Span.Concept.EXPRESSION, Spans.JSON.textNode(inside), List.of(), "expression"));
                }
                i++;
                continue;
            }
            int expressionEnd = -1;
            int numbers = 0;
            int operators = 0;
            if (!joined(input, i, -1)) {
                for (int end = i; end < size; end++) {
                    String part = Spans.surface(input, end, end);
                    boolean number = input.tokens().get(end).shape() == Token.Shape.NUMBER
                            && part.matches("[0-9]+(?:\\.[0-9]+)?");
                    boolean isFraction = number && fraction(input, end);
                    boolean arithmetic = ARITHMETIC.contains(part);
                    boolean comparison = OPERATORS.containsKey(part);
                    boolean parenthesis = part.equals("(") || part.equals(")");
                    if (!number && !arithmetic && !comparison && !parenthesis) {
                        break;
                    }
                    if (arithmetic && part.length() == 1 && "+-*/".contains(part)) {
                        int start = input.tokens().get(end).range().start();
                        int finish = input.tokens().get(end).range().end();
                        boolean beforeSpace = start > 0 && Character.isWhitespace(input.text().charAt(start - 1));
                        boolean afterSpace = finish < input.text().length()
                                && Character.isWhitespace(input.text().charAt(finish));
                        boolean afterClosing = end > i && Spans.surface(input, end - 1, end - 1).equals(")");
                        boolean beforeOpening = end + 1 < size
                                && Spans.surface(input, end + 1, end + 1).equals("(");
                        if ((!beforeSpace || !afterSpace) && !afterClosing && !beforeOpening) {
                            break;
                        }
                    }
                    if (isFraction) {
                        numbers++;
                        end += 2;
                    } else {
                        if (number) {
                            numbers++;
                        }
                        if (arithmetic || comparison) {
                            operators++;
                        }
                    }
                    if (numbers < 2 || operators == 0 || joined(input, end, 1)) {
                        continue;
                    }
                    String candidate = Spans.surface(input, i, end);
                    if (candidate.indexOf('\n') >= 0 || candidate.indexOf('\r') >= 0) {
                        break;
                    }
                    try {
                        try {
                            ExprParser.parseComparison(candidate);
                        } catch (IllegalArgumentException noComparison) {
                            ExprParser.parse(candidate);
                        }
                        expressionEnd = end;
                    } catch (IllegalArgumentException ignored) {
                        // A longer allowed run may complete the expression.
                    }
                }
            }
            if (expressionEnd >= i) {
                String value = Spans.surface(input, i, expressionEnd);
                spans.add(Spans.over(input, i, expressionEnd, "e" + (spans.size() + 1),
                        Span.Concept.EXPRESSION, Spans.JSON.textNode(value), List.of(), "expression"));
                i = expressionEnd + 1;
                continue;
            }
            int phraseEnd = -1;
            String operator = null;
            for (Map.Entry<String, String> entry : PHRASES.entrySet()) {
                String[] words = entry.getKey().split(" ");
                int end = i + words.length - 1;
                if (end >= size) {
                    continue;
                }
                String surface = Spans.surface(input, i, end).toLowerCase(Locale.ROOT);
                if (surface.equals(entry.getKey()) && (phraseEnd < i || end > phraseEnd)) {
                    phraseEnd = end;
                    operator = entry.getValue();
                }
            }
            if (phraseEnd < i) {
                operator = OPERATORS.get(Spans.surface(input, i, i));
                if (operator != null) {
                    phraseEnd = i;
                }
            }
            int numberIndex = phraseEnd + 1;
            boolean followsNumber = i > 0 && input.tokens().get(i - 1).shape() == Token.Shape.NUMBER;
            if (phraseEnd >= i && numberIndex < size && !followsNumber && !joined(input, numberIndex, 1)) {
                String numberText = Spans.surface(input, numberIndex, numberIndex);
                String decimal = null;
                if (input.tokens().get(numberIndex).shape() == Token.Shape.NUMBER) {
                    decimal = NumberRecognizer.decimalOf(numberText);
                } else if (NumberRecognizer.NUMBER_WORDS.containsKey(numberText.toLowerCase(Locale.ROOT))) {
                    decimal = NumberRecognizer.wordsValue(List.of(numberText));
                }
                if (decimal != null) {
                    spans.add(Spans.over(input, i, numberIndex, "e" + (spans.size() + 1),
                            Span.Concept.COMPARISON,
                            Spans.JSON.objectNode().put("op", operator).put("value", decimal),
                            List.of(), "comparison"));
                    i = numberIndex + 1;
                    continue;
                }
            }
            i++;
        }
        return List.copyOf(spans);
    }

    /**
     * Whether token {@code i} starts a fraction: it, a {@code /} token and a NUMBER token ({@link #joined}), and not
     * part of a longer chain, so no other number is joined to either of its numbers ({@code 03/04/2026}).
     */
    public static boolean fraction(ConceptRecognizer.Input input, int i) {
        return joined(input, i, 1)
                && Spans.surface(input, i + 1, i + 1).equals("/")
                && !joined(input, i, -1)
                && !joined(input, i + 2, 1);
    }

    /**
     * Whether token {@code i} is a NUMBER joined to another NUMBER beyond it in {@code direction} (1: after it, -1:
     * before it): both plain numerals (digits with an optional fraction), a token between them that is one of
     * {@code /}, {@code -}, {@code ..}, {@code .} and {@code :}, and no whitespace between the three ({@code 3/4},
     * {@code 10-25}, {@code 1.2.3}).
     */
    public static boolean joined(ConceptRecognizer.Input input, int i, int direction) {
        if (direction != 1 && direction != -1) {
            return false;
        }
        int separator = i + direction;
        int other = i + 2 * direction;
        if (i < 0 || i >= input.tokens().size() || other < 0 || other >= input.tokens().size()) {
            return false;
        }
        Token firstToken = input.tokens().get(i);
        Token secondToken = input.tokens().get(other);
        String first = Spans.surface(input, i, i);
        String second = Spans.surface(input, other, other);
        String between = Spans.surface(input, separator, separator);
        return firstToken.shape() == Token.Shape.NUMBER
                && secondToken.shape() == Token.Shape.NUMBER
                && first.matches("[0-9]+(?:\\.[0-9]+)?")
                && second.matches("[0-9]+(?:\\.[0-9]+)?")
                && List.of("/", "-", "..", ".", ":").contains(between)
                && Spans.touches(input, Math.min(i, separator))
                && Spans.touches(input, Math.min(separator, other));
    }

    /**
     * Whether the text between a code span's backticks, {@code inside}, is arithmetic: stripped of surrounding
     * whitespace, it holds only digits, periods, whitespace, parentheses and the characters of {@link #ARITHMETIC} and
     * of {@link #OPERATORS}' keys and {@code !}, at least two numbers (runs of digits with an optional fraction), and
     * {@link ExprParser#parseComparison} or {@link ExprParser#parse} reads it whole; but not a pair alone, two numbers
     * and a separator ({@link PairRecognizer#CODE_PAIR}: {@code `3/4`} is a pair's), not three or more numbers joined
     * by {@code /}, {@code -}, {@code .} or {@code :} alone ({@code 03/04/2026}, {@code 2026-10-27}), and not a
     * month-day as ISO 8601 writes it ({@code --10-25}). In code the ASCII operators need no whitespace
     * ({@code `1+2`}).
     */
    public static boolean arithmetic(String inside) {
        String text = inside.strip();
        if (text.isEmpty() || PairRecognizer.CODE_PAIR.matcher(text).matches()
                || text.matches("--[0-9]+-[0-9]+")
                || text.matches("[0-9]+(?:[/\\-.:][0-9]+){2,}")) {
            return false;
        }
        if (!text.matches("[0-9.\\s()!+\\-*/<=>^|~%&×⋅÷−≤≥≠]+")) {
            return false;
        }
        Matcher numbers = Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(text);
        int count = 0;
        while (numbers.find()) {
            count++;
        }
        if (count < 2) {
            return false;
        }
        try {
            try {
                ExprParser.parseComparison(text);
            } catch (IllegalArgumentException noComparison) {
                ExprParser.parse(text);
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * Whether {@code text} holds one of the comparison operators of {@link #OPERATORS} other than {@code =}, with
     * whitespace right before and right after it ({@code duplicate != null}; not {@code Map<K, V>} or {@code x = 1}).
     */
    public static boolean holdsComparison(String text) {
        for (String operator : OPERATORS.keySet()) {
            if (operator.equals("=")) {
                continue;
            }
            int at = text.indexOf(operator);
            while (at >= 0) {
                int after = at + operator.length();
                if (at > 0 && after < text.length()
                        && Character.isWhitespace(text.charAt(at - 1))
                        && Character.isWhitespace(text.charAt(after))) {
                    return true;
                }
                at = text.indexOf(operator, at + 1);
            }
        }
        return false;
    }
}
