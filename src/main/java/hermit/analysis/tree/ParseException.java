package hermit.analysis.tree;

/**
 * A syntax error with a 1-based source position and a message naming what was expected vs found —
 * the message quality matters because Hermit iterates on these errors when generating code.
 * Also thrown (and caught) internally by speculative parses; the parser rolls back and tries the
 * next alternative, so only errors on the finally-chosen path escape to the caller.
 */
public class ParseException extends RuntimeException {

    private final int line;
    private final int column;

    public ParseException(String message, int line, int column) {
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
