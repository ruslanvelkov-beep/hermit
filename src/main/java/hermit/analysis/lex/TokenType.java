package hermit.analysis.lex;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every kind of lexical token a {@link JavaLexer} can produce, plus a few types that only the second
 * (BNF) pass assigns by re-typing existing tokens (the angle brackets and the merged right-shift
 * operators). Modelled on the Java 25 lexical structure (JLS §3) with the token-merging conventions
 * from the module design:
 *
 * <ul>
 *   <li>{@code <} / {@code >} are always lexed as {@link #LESS_THAN} / {@link #GREATER_THAN}; the BNF
 *       pass decides whether a given one is really an {@link #OPENING_ANGLE_BRACKET} /
 *       {@link #CLOSING_ANGLE_BRACKET}, or whether adjacent {@code >} tokens merge into
 *       {@link #SHIFT_RIGHT} / {@link #SHIFT_RIGHT_PRESERVING_SIGN}.</li>
 *   <li>{@code <<} is always safe as {@link #SHIFT_LEFT} (nested generic openings are never adjacent).</li>
 *   <li>{@code >>=} and {@code >>>=} are lexed directly as their assignment operators (they can never be a
 *       run of closing angle brackets in valid Java); {@code >>} / {@code >>>} without a trailing {@code =}
 *       are left as separate {@link #GREATER_THAN} tokens for the BNF pass to resolve.</li>
 * </ul>
 *
 * <p>Contextual keywords (var, yield, record, sealed, permits, when, module, requires, …) are NOT listed
 * here: per the JLS they are lexed as {@link #IDENTIFIER} and only recognised as keywords in context by
 * the parser.
 */
public enum TokenType {

    // ---- variable-text tokens (no fixed lexeme) --------------------------------------------------
    IDENTIFIER(null, Category.IDENTIFIER),
    INTEGER_LITERAL(null, Category.LITERAL),      // decimal int/long: digits with optional '_' and L/l suffix
    HEX_LITERAL(null, Category.LITERAL),          // 0x…  (hex digits, optional '_', optional L/l)
    BINARY_LITERAL(null, Category.LITERAL),       // 0b…
    OCTAL_LITERAL(null, Category.LITERAL),        // 0… (leading zero, octal digits)
    FLOAT_LITERAL(null, Category.LITERAL),        // floating point (…., exponent, f/F/d/D suffix, hex float)
    CHAR_LITERAL(null, Category.LITERAL),         // '…'
    STRING_LITERAL(null, Category.LITERAL),       // "…"
    TEXT_BLOCK(null, Category.LITERAL),           // """ … """

    // ---- trivia (whitespace + comments): separators, but kept so the source round-trips ----------
    WHITESPACE(null, Category.TRIVIA),
    SINGLE_LINE_COMMENT(null, Category.TRIVIA),   // // …
    MULTI_LINE_COMMENT(null, Category.TRIVIA),    // /* … */  (incl. /** … */ javadoc)

    // ---- reserved keywords (JLS §3.9) ------------------------------------------------------------
    ABSTRACT("abstract", Category.KEYWORD), ASSERT("assert", Category.KEYWORD),
    BOOLEAN("boolean", Category.KEYWORD), BREAK("break", Category.KEYWORD),
    BYTE("byte", Category.KEYWORD), CASE("case", Category.KEYWORD), CATCH("catch", Category.KEYWORD),
    CHAR("char", Category.KEYWORD), CLASS("class", Category.KEYWORD), CONST("const", Category.KEYWORD),
    CONTINUE("continue", Category.KEYWORD), DEFAULT("default", Category.KEYWORD), DO("do", Category.KEYWORD),
    DOUBLE("double", Category.KEYWORD), ELSE("else", Category.KEYWORD), ENUM("enum", Category.KEYWORD),
    EXTENDS("extends", Category.KEYWORD), FINAL("final", Category.KEYWORD), FINALLY("finally", Category.KEYWORD),
    FLOAT("float", Category.KEYWORD), FOR("for", Category.KEYWORD), GOTO("goto", Category.KEYWORD),
    IF("if", Category.KEYWORD), IMPLEMENTS("implements", Category.KEYWORD), IMPORT("import", Category.KEYWORD),
    INSTANCEOF("instanceof", Category.KEYWORD), INT("int", Category.KEYWORD), INTERFACE("interface", Category.KEYWORD),
    LONG("long", Category.KEYWORD), NATIVE("native", Category.KEYWORD), NEW("new", Category.KEYWORD),
    PACKAGE("package", Category.KEYWORD), PRIVATE("private", Category.KEYWORD), PROTECTED("protected", Category.KEYWORD),
    PUBLIC("public", Category.KEYWORD), RETURN("return", Category.KEYWORD), SHORT("short", Category.KEYWORD),
    STATIC("static", Category.KEYWORD), STRICTFP("strictfp", Category.KEYWORD), SUPER("super", Category.KEYWORD),
    SWITCH("switch", Category.KEYWORD), SYNCHRONIZED("synchronized", Category.KEYWORD), THIS("this", Category.KEYWORD),
    THROW("throw", Category.KEYWORD), THROWS("throws", Category.KEYWORD), TRANSIENT("transient", Category.KEYWORD),
    TRY("try", Category.KEYWORD), VOID("void", Category.KEYWORD), VOLATILE("volatile", Category.KEYWORD),
    WHILE("while", Category.KEYWORD),
    // reserved literal-keywords
    TRUE("true", Category.KEYWORD), FALSE("false", Category.KEYWORD), NULL("null", Category.KEYWORD),
    // the reserved single underscore (JLS §3.9)
    UNDERSCORE("_", Category.KEYWORD),

    // ---- brackets (matched as pairs by the lexer for () [] {}) -----------------------------------
    OPENING_ROUND_BRACKET("(", Category.BRACKET), CLOSING_ROUND_BRACKET(")", Category.BRACKET),
    OPENING_SQUARE_BRACKET("[", Category.BRACKET), CLOSING_SQUARE_BRACKET("]", Category.BRACKET),
    OPENING_CURLY_BRACKET("{", Category.BRACKET), CLOSING_CURLY_BRACKET("}", Category.BRACKET),
    // angle brackets: same lexeme as LESS_THAN/GREATER_THAN, assigned only by the BNF pass
    OPENING_ANGLE_BRACKET("<", Category.BRACKET), CLOSING_ANGLE_BRACKET(">", Category.BRACKET),

    // ---- separators ------------------------------------------------------------------------------
    SEMICOLON(";", Category.SEPARATOR), COMMA(",", Category.SEPARATOR), DOT(".", Category.SEPARATOR),
    ELLIPSIS("...", Category.SEPARATOR), AT("@", Category.SEPARATOR), DOUBLE_COLON("::", Category.SEPARATOR),
    ARROW("->", Category.SEPARATOR),

    // ---- operators -------------------------------------------------------------------------------
    ASSIGNMENT("=", Category.OPERATOR),
    PLUS("+", Category.OPERATOR), MINUS("-", Category.OPERATOR), STAR("*", Category.OPERATOR),
    SLASH("/", Category.OPERATOR), PERCENT("%", Category.OPERATOR),
    INCREMENT("++", Category.OPERATOR), DECREMENT("--", Category.OPERATOR),
    PLUS_ASSIGNMENT("+=", Category.OPERATOR), MINUS_ASSIGNMENT("-=", Category.OPERATOR),
    STAR_ASSIGNMENT("*=", Category.OPERATOR), SLASH_ASSIGNMENT("/=", Category.OPERATOR),
    PERCENT_ASSIGNMENT("%=", Category.OPERATOR),
    AMPERSAND("&", Category.OPERATOR), PIPE("|", Category.OPERATOR), CARET("^", Category.OPERATOR),
    TILDE("~", Category.OPERATOR),
    AND_ASSIGNMENT("&=", Category.OPERATOR), OR_ASSIGNMENT("|=", Category.OPERATOR),
    XOR_ASSIGNMENT("^=", Category.OPERATOR),
    LOGICAL_AND("&&", Category.OPERATOR), LOGICAL_OR("||", Category.OPERATOR), LOGICAL_NOT("!", Category.OPERATOR),
    EQUALS("==", Category.OPERATOR), NOT_EQUALS("!=", Category.OPERATOR),
    LESS_THAN("<", Category.OPERATOR), GREATER_THAN(">", Category.OPERATOR),
    LESS_THAN_OR_EQUAL("<=", Category.OPERATOR), GREATER_THAN_OR_EQUAL(">=", Category.OPERATOR),
    SHIFT_LEFT("<<", Category.OPERATOR), SHIFT_LEFT_ASSIGNMENT("<<=", Category.OPERATOR),
    SHIFT_RIGHT(">>", Category.OPERATOR),                             // derived (BNF pass)
    SHIFT_RIGHT_ASSIGNMENT(">>=", Category.OPERATOR),
    SHIFT_RIGHT_PRESERVING_SIGN(">>>", Category.OPERATOR),            // derived (BNF pass)
    SHIFT_RIGHT_PRESERVING_SIGN_ASSIGNMENT(">>>=", Category.OPERATOR),
    QUESTION("?", Category.OPERATOR), COLON(":", Category.OPERATOR),

    // ---- end of input ----------------------------------------------------------------------------
    END_OF_FILE(null, Category.EOF);

    /** Broad classification, useful for the parser and for skipping trivia. */
    public enum Category { LITERAL, IDENTIFIER, KEYWORD, OPERATOR, SEPARATOR, BRACKET, TRIVIA, EOF }

    private final String fixedText;   // canonical lexeme for keywords/operators/separators/brackets; null otherwise
    private final Category category;

    TokenType(String fixedText, Category category) {
        this.fixedText = fixedText;
        this.category = category;
    }

    /** The canonical lexeme for a fixed-text token (keyword/operator/separator/bracket), else null. */
    public String fixedText() {
        return fixedText;
    }

    public Category category() {
        return category;
    }

    public boolean isTrivia() {
        return category == Category.TRIVIA;
    }

    public boolean isKeyword() {
        return category == Category.KEYWORD;
    }

    public boolean isLiteral() {
        return category == Category.LITERAL;
    }

    public boolean isOperator() {
        return category == Category.OPERATOR;
    }

    /** True for {@code ( [ { <}. */
    public boolean isOpeningBracket() {
        return this == OPENING_ROUND_BRACKET || this == OPENING_SQUARE_BRACKET
                || this == OPENING_CURLY_BRACKET || this == OPENING_ANGLE_BRACKET;
    }

    /** True for {@code ) ] } >}. */
    public boolean isClosingBracket() {
        return this == CLOSING_ROUND_BRACKET || this == CLOSING_SQUARE_BRACKET
                || this == CLOSING_CURLY_BRACKET || this == CLOSING_ANGLE_BRACKET;
    }

    /** For an opening bracket, its matching closing type (and vice-versa); null if not a bracket. */
    public TokenType matchingBracketType() {
        return switch (this) {
            case OPENING_ROUND_BRACKET -> CLOSING_ROUND_BRACKET;
            case CLOSING_ROUND_BRACKET -> OPENING_ROUND_BRACKET;
            case OPENING_SQUARE_BRACKET -> CLOSING_SQUARE_BRACKET;
            case CLOSING_SQUARE_BRACKET -> OPENING_SQUARE_BRACKET;
            case OPENING_CURLY_BRACKET -> CLOSING_CURLY_BRACKET;
            case CLOSING_CURLY_BRACKET -> OPENING_CURLY_BRACKET;
            case OPENING_ANGLE_BRACKET -> CLOSING_ANGLE_BRACKET;
            case CLOSING_ANGLE_BRACKET -> OPENING_ANGLE_BRACKET;
            default -> null;
        };
    }

    /** All reserved keywords (incl. true/false/null and the reserved underscore), by lexeme. */
    public static final Map<String, TokenType> KEYWORDS = Stream.of(values())
            .filter(t -> t.category == Category.KEYWORD)
            .collect(Collectors.toUnmodifiableMap(t -> t.fixedText, t -> t));

    /** The keyword type for {@code s}, or null if {@code s} is not a reserved keyword. */
    public static TokenType keyword(String s) {
        return KEYWORDS.get(s);
    }
}
