package hermit.analysis.lex;

/**
 * One lexical token: its {@link TokenType}, the exact source {@code text} (so a token stream round-trips to
 * the original file), and 1-based {@code line}/{@code column} of its first character (a tab counts as 4
 * columns, every other character as 1, per the module design). {@code startOffset} is the 0-based index of
 * the first character in the source.
 *
 * <p>Two fields are mutable because the second (BNF) pass rewrites them:
 * <ul>
 *   <li>{@link #type} — e.g. a {@link TokenType#GREATER_THAN} may be re-typed to
 *       {@link TokenType#CLOSING_ANGLE_BRACKET}; whitespace/comments stay trivia.</li>
 *   <li>{@link #matchingBracket} — for {@code ( ) [ ] { }} the lexer links each opener to its closer and
 *       vice-versa; for angle brackets the BNF pass links them.</li>
 * </ul>
 */
public final class Token {

    private TokenType type;              // mutable: re-typed by the BNF pass (angle brackets, merged shifts)
    private final String text;          // exact source lexeme (incl. quotes/comment markers/whitespace runs)
    private final int line;             // 1-based
    private final int column;           // 1-based (tab = 4 columns)
    private final int startOffset;      // 0-based char index into the source
    private Token matchingBracket;      // the paired bracket token, or null

    public Token(TokenType type, String text, int line, int column, int startOffset) {
        this.type = type;
        this.text = text;
        this.line = line;
        this.column = column;
        this.startOffset = startOffset;
    }

    public TokenType type() {
        return type;
    }

    /** Re-type this token (used only by the BNF pass, e.g. GREATER_THAN → CLOSING_ANGLE_BRACKET). */
    public void retype(TokenType newType) {
        this.type = newType;
    }

    public String text() {
        return text;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public int startOffset() {
        return startOffset;
    }

    /** 0-based index just past the last character of this token. */
    public int endOffset() {
        return startOffset + text.length();
    }

    public Token matchingBracket() {
        return matchingBracket;
    }

    public void setMatchingBracket(Token other) {
        this.matchingBracket = other;
    }

    public boolean isTrivia() {
        return type.isTrivia();
    }

    public boolean is(TokenType t) {
        return type == t;
    }

    /** The raw source text — a stream of tokens joined by {@code toString()} reproduces the file exactly. */
    @Override
    public String toString() {
        return text;
    }

    /** Human-readable form for debugging/error messages: {@code TYPE("text")@line:col}. */
    public String describe() {
        String shown = text;
        if (shown.length() > 32) {
            shown = shown.substring(0, 29) + "...";
        }
        shown = shown.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
        return type + "(\"" + shown + "\")@" + line + ":" + column;
    }
}
