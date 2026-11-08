package hermit.text.expr;

/**
 * Reads arithmetic expressions and comparisons as they appear in text (milestone A3 of the annotation plan, section 3:
 * bounded exact arithmetic). Numbers are decimal numerals as {@link Rational#parse} reads them; the operators are
 * {@code +}, {@code -} (also the minus sign U+2212 and the en dash U+2013 between operands), {@code *} (also the times
 * sign U+00D7 and the dot operator U+22C5) and {@code /} (also the division sign U+00F7), with the usual precedence,
 * left to right, a unary minus or plus, and parentheses. Spaces between the parts are allowed. The text is refused with
 * an IllegalArgumentException that names the character index when it is longer than {@link #MAX_LENGTH}, nests more
 * than {@link #MAX_DEPTH} parentheses or unary signs deep, is empty, or holds anything else.
 */
public final class ExprParser {

    /** The longest text the parser reads. */
    public static final int MAX_LENGTH = 200;
    /** The deepest nesting of parentheses and unary signs the parser reads. */
    public static final int MAX_DEPTH = 32;

    /** The expression that is the whole of {@code text}, with node ranges into {@code text}. */
    public static Expr parse(String text) {
        State state = initialState(text);
        Expr result = expression(state, 1);
        skipSpaces(state);
        if (state.index != text.length()) {
            throw error(state);
        }
        return result;
    }

    /**
     * The comparison that is the whole of {@code text}: an expression, one operator and an expression. The operators
     * are {@code <}, {@code <=}, {@code >}, {@code >=}, {@code =}, {@code ==} and {@code !=}, and the signs U+2264,
     * U+2265 and U+2260; each is reported as {@code <}, {@code <=}, {@code >}, {@code >=}, {@code =} or {@code !=}.
     */
    public static Comparison parseComparison(String text) {
        State state = initialState(text);
        Expr left = expression(state, 1);
        String op = comparisonOperator(state);
        Expr right = expression(state, 1);
        skipSpaces(state);
        if (state.index != text.length()) {
            throw error(state);
        }
        return new Comparison(left, op, right);
    }

    private static final class State {
        private final String text;
        private int index;
        private int depth;

        private State(String text) {
            this.text = text;
        }
    }

    private static IllegalArgumentException error(State state) {
        return new IllegalArgumentException("Invalid expression at index " + state.index);
    }

    private static void skipSpaces(State state) {
        while (state.index < state.text.length()
                && Character.isWhitespace(state.text.charAt(state.index))) {
            state.index++;
        }
    }

    private static boolean digit(char value) {
        return value >= '0' && value <= '9';
    }

    private static State initialState(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Invalid expression at index 0");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid expression at index " + MAX_LENGTH);
        }
        return new State(text);
    }

    private static Expr number(State state) {
        int start = state.index;
        while (state.index < state.text.length() && digit(state.text.charAt(state.index))) {
            state.index++;
        }
        if (state.index < state.text.length() && state.text.charAt(state.index) == '.') {
            state.index++;
            while (state.index < state.text.length() && digit(state.text.charAt(state.index))) {
                state.index++;
            }
        }
        if (state.index == start) {
            throw error(state);
        }
        if (state.index < state.text.length()
                && (state.text.charAt(state.index) == 'e' || state.text.charAt(state.index) == 'E')) {
            state.index++;
            if (state.index < state.text.length()
                    && (state.text.charAt(state.index) == '+' || state.text.charAt(state.index) == '-')) {
                state.index++;
            }
            int exponentStart = state.index;
            while (state.index < state.text.length() && digit(state.text.charAt(state.index))) {
                state.index++;
            }
            if (state.index == exponentStart) {
                throw error(state);
            }
        }
        String numeral = state.text.substring(start, state.index);
        try {
            return new Expr.Num(Rational.parse(numeral), numeral, start, state.index);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid numeral at index " + start, exception);
        }
    }

    private static char operator(char value) {
        return switch (value) {
            case '-', '\u2212', '\u2013' -> '-';
            case '*', '\u00d7', '\u22c5' -> '*';
            case '/', '\u00f7' -> '/';
            case '+' -> '+';
            default -> '\0';
        };
    }

    private static Expr expression(State state, int minimum) {
        skipSpaces(state);
        if (state.index == state.text.length()) {
            throw error(state);
        }
        int start = state.index;
        char first = state.text.charAt(state.index);
        Expr left;
        if (first == '(' || first == '+' || first == '-' || first == '\u2212') {
            if (state.depth >= MAX_DEPTH) {
                throw error(state);
            }
            state.depth++;
            state.index++;
            try {
                if (first == '(') {
                    left = expression(state, 1);
                    skipSpaces(state);
                    if (state.index == state.text.length() || state.text.charAt(state.index) != ')') {
                        throw error(state);
                    }
                    state.index++;
                } else {
                    Expr operand = expression(state, 3);
                    left = first == '+' ? operand : new Expr.Neg(operand, start, operand.end());
                }
            } finally {
                state.depth--;
            }
        } else {
            left = number(state);
        }
        while (true) {
            skipSpaces(state);
            if (state.index == state.text.length()) {
                return left;
            }
            char op = operator(state.text.charAt(state.index));
            int precedence = op == '+' || op == '-' ? 1 : op == '*' || op == '/' ? 2 : 0;
            if (precedence < minimum) {
                return left;
            }
            state.index++;
            Expr right = expression(state, precedence + 1);
            left = new Expr.Binary(op, left, right, left.start(), right.end());
        }
    }

    private static String comparisonOperator(State state) {
        skipSpaces(state);
        if (state.index == state.text.length()) {
            throw error(state);
        }
        char first = state.text.charAt(state.index);
        String op = switch (first) {
            case '<' -> "<";
            case '>' -> ">";
            case '=' -> "=";
            case '!' -> "!";
            case '\u2264' -> "<=";
            case '\u2265' -> ">=";
            case '\u2260' -> "!=";
            default -> throw error(state);
        };
        state.index++;
        if (first == '<' || first == '>' || first == '=' || first == '!') {
            boolean equals = state.index < state.text.length() && state.text.charAt(state.index) == '=';
            if (equals) {
                state.index++;
                op = first == '=' ? "=" : op + "=";
            } else if (first == '!') {
                throw error(state);
            }
        }
        return op;
    }
}
