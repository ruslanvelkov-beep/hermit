package hermit.analysis.lex;

/**
 * A lexical error with 1-based position: an unterminated comment / string / char / text block, an illegal
 * character, or mismatched/intersecting brackets. Carries {@code line}/{@code column} so callers (and
 * ultimately Hermit's iterate-on-feedback loop) get an actionable message.
 */
public class LexException extends RuntimeException {

    private final int line;
    private final int column;

    public LexException(String message, int line, int column) {
        super("[" + line + ":" + column + "] " + message);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
