package hermit.analysis.lex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lexer for Java source code.
 */
public final class JavaLexer {

    // Lookup maps built once from TokenType enum
    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        for (TokenType t : TokenType.values()) {
            String ft = t.fixedText();
            if (ft != null) {
                // Keywords include true, false, null, _, and reserved words
                if (t.category() == TokenType.Category.KEYWORD) {
                    KEYWORDS.put(ft, t);
                }
            }
        }
    }

    private final String source;
    private int pos = 0;
    private int line = 1;
    private int column = 1;

    public JavaLexer(String source) {
        this.source = source;
    }

    public static List<Token> lex(String source) {
        return new JavaLexer(source).tokenize();
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (source.startsWith("\"\"\"", pos)) {
                tokens.add(scanTextBlock());
                continue;
            }
            if (c == '"') {
                tokens.add(scanStringLiteral());
                continue;
            }
            if (c == '\'') {
                tokens.add(scanCharLiteral());
                continue;
            }

            // Whitespace: a run of spaces, tabs, form feeds and line breaks is one token
            if (c == ' ' || c == '\t' || c == '\f' || c == '\n' || c == '\r') {
                tokens.add(scanWhitespace());
                continue;
            }

            // Comments and Division (/)
            if (c == '/') {
                Token t = scanCommentOrSlash();
                tokens.add(t);
                continue;
            }

            // Fixed multi-character tokens (operators, separators, brackets)
            Token fixedToken = scanFixedToken();
            if (fixedToken != null) {
                handleBracket(fixedToken);
                tokens.add(fixedToken);
                continue;
            }

            // Identifiers and Keywords
            if (Character.isJavaIdentifierStart(c)) {
                tokens.add(scanIdentifier());
                continue;
            }

            // Numeric Literals (integers and floats)
            if (Character.isDigit(c)) {
                tokens.add(scanNumericLiteral());
                continue;
            }

            // Dot can start a float (e.g. .5)
            if (c == '.') {
                tokens.add(scanDotOrFloat());
                continue;
            }

            // Unknown character (skip for robustness, though valid Java shouldn't have these)
            advanceChar(c);
        }
        checkUnclosedBrackets();
        tokens.add(new Token(TokenType.END_OF_FILE, "", line, column, pos));
        return tokens;
    }

    // Helper to create a token with current position info
    private Token makeToken(TokenType type, String text) {
        return new Token(type, text, line, column, pos);
    }

    // Advance position by one character, updating line/column
    private void advanceChar(char c) {
        if (c == '\n' || c == '\r') {
            line++;
            column = 1;
        } else if (c == '\t') {
            column += 4; // Tab counts as 4 columns
        } else {
            column++;
        }
        pos++;
    }

    // Scan a run of whitespace: spaces, tabs, form feeds and line breaks (\r\n is one line break)
    private Token scanWhitespace() {
        int start = pos;
        int startLine = line;
        int startCol = column;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\r' && pos + 1 < source.length() && source.charAt(pos + 1) == '\n') {
                advanceChar(c);
                pos++;   // the \n of \r\n: the line was counted at the \r
            } else if (c == ' ' || c == '\t' || c == '\f' || c == '\n' || c == '\r') {
                advanceChar(c);
            } else {
                break;
            }
        }
        String text = source.substring(start, pos);
        return new Token(TokenType.WHITESPACE, text, startLine, startCol, start);
    }

    // Scan comment (line or block) or return SLASH token if not a comment
    private Token scanCommentOrSlash() {
        {
    int start = pos;
    int startLine = line;
    int startCol = column;
    if (pos + 1 < source.length() && source.charAt(pos + 1) == '/') {
        advanceChar('/');
        advanceChar('/');
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\n' || c == '\r') {
                break;
            }
            advanceChar(c);
        }
        return new Token(TokenType.SINGLE_LINE_COMMENT, source.substring(start, pos), startLine, startCol, start);
    }
    if (pos + 1 < source.length() && source.charAt(pos + 1) == '*') {
        advanceChar('/');
        advanceChar('*');
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '*' && pos + 1 < source.length() && source.charAt(pos + 1) == '/') {
                advanceChar('*');
                advanceChar('/');
                return new Token(TokenType.MULTI_LINE_COMMENT, source.substring(start, pos), startLine, startCol, start);
            }
            advanceChar(c);
            if (c == '\r' && pos < source.length() && source.charAt(pos) == '\n') {
                pos++;
            }
        }
        throw new LexException("unterminated block comment", startLine, startCol);
    }
    return scanFixedToken();
}
    }

    // Scan the longest fixed operator, separator, or bracket token at the current position.
    private Token scanFixedToken() {
        int start = pos;
        int startLine = line;
        int startCol = column;
        TokenType best = null;
        int bestLength = 0;
        for (TokenType type : TokenType.values()) {
            String fixed = type.fixedText();
            if (fixed == null) continue;
            
            // Only consider operators, separators, and brackets
            TokenType.Category category = type.category();
            if (category != TokenType.Category.OPERATOR && 
                category != TokenType.Category.SEPARATOR && 
                category != TokenType.Category.BRACKET) {
                continue;
            }

            // Exclude '>>' and '>>>' from direct matching; they remain separate GREATER_THAN tokens
            if (fixed.equals(">>") || fixed.equals(">>>") || type.name().endsWith("ANGLE_BRACKET")) { continue; }

            // Max length 4, must be longer than current best to enforce longest match
            if (fixed.length() > 4 || fixed.length() <= bestLength) {
                continue;
            }

            // Check if source starts with this fixed text at current position
            if (source.startsWith(fixed, pos)) {
                best = type;
                bestLength = fixed.length();
            }
        }

        if (best == null) {
            return null;
        }

        String text = source.substring(start, start + bestLength);
        for (int i = 0; i < bestLength; i++) {
            advanceChar(source.charAt(pos));
        }
        return new Token(best, text, startLine, startCol, start);
    }

    // Scan identifier or keyword
    private Token scanIdentifier() {
        int start = pos;
        int startLine = line;
        int startCol = column;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isJavaIdentifierPart(c)) {
                advanceChar(c);
            } else {
                break;
            }
        }
        String text = source.substring(start, pos);
        // Lookup keyword, default to IDENTIFIER for contextual keywords
        TokenType type = KEYWORDS.getOrDefault(text, TokenType.IDENTIFIER);
        return new Token(type, text, startLine, startCol, start);
    }

    // Scan numeric literal (integer or float)
    private Token scanNumericLiteral() {
        int start = pos;
        int startLine = line;
        int startCol = column;

        // Check for hex or binary prefix
        if (source.charAt(pos) == '0' && pos + 1 < source.length()) {
            char next = source.charAt(pos + 1);
            if (next == 'x' || next == 'X') {
                return scanHexLiteral(start, startLine, startCol);
            }
            if (next == 'b' || next == 'B') {
                return scanBinaryLiteral(start, startLine, startCol);
            }
        }

        // Otherwise, it's decimal or octal (if starts with 0)
        return scanDecimalOrOctalLiteral(start, startLine, startCol);
    }

    // Scan hex literal (0x...)
    private Token scanHexLiteral(int start, int startLine, int startCol) {
        advanceChar('0');
        advanceChar(source.charAt(pos)); // consume x/X
        boolean isFloat = false;

        // Consume hex digits
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (isHexDigit(c) || c == '_') {
                advanceChar(c);
            } else if (c == '.' && pos + 1 < source.length() && isHexDigit(source.charAt(pos + 1))) {
                // Hex float: consume dot and fractional part
                isFloat = true;
                advanceChar('.');
                while (pos < source.length()) {
                    char fc = source.charAt(pos);
                    if (isHexDigit(fc) || fc == '_') {
                        advanceChar(fc);
                    } else {
                        break;
                    }
                }
            } else {
                break;
            }
        }

        // Check for exponent p/P
        if (pos < source.length() && (source.charAt(pos) == 'p' || source.charAt(pos) == 'P')) {
            isFloat = true;
            advanceChar(source.charAt(pos));
            if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
                advanceChar(source.charAt(pos));
            }
            while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '_')) {
                advanceChar(source.charAt(pos));
            }
        }

        // Check for suffix
        if (pos < source.length()) {
            char suffix = source.charAt(pos);
            boolean hasFloatSuffix = (suffix == 'f' || suffix == 'F' || suffix == 'd' || suffix == 'D');
            boolean hasIntSuffix = (suffix == 'l' || suffix == 'L');
            if (hasFloatSuffix) {
                isFloat = true;
                advanceChar(suffix);
            } else if (hasIntSuffix) {
                // Only consume L/l if not a float
                if (!isFloat) {
                    advanceChar(suffix);
                }
            }
        }

        String text = source.substring(start, pos);
        return new Token(isFloat ? TokenType.FLOAT_LITERAL : TokenType.HEX_LITERAL, text, startLine, startCol, start);
    }

    // Scan binary literal (0b...)
    private Token scanBinaryLiteral(int start, int startLine, int startCol) {
        advanceChar('0');
        advanceChar(source.charAt(pos)); // consume b/B
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '0' || c == '1' || c == '_') {
                advanceChar(c);
            } else {
                break;
            }
        }

        // Check for suffix
        if (pos < source.length() && (source.charAt(pos) == 'l' || source.charAt(pos) == 'L')) {
            advanceChar(source.charAt(pos));
        }

        String text = source.substring(start, pos);
        return new Token(TokenType.BINARY_LITERAL, text, startLine, startCol, start);
    }

    // Scan decimal or octal literal
    private Token scanDecimalOrOctalLiteral(int start, int startLine, int startCol) {
        boolean startsWithZero = source.charAt(pos) == '0';

        // Consume digits and underscores
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isDigit(c) || c == '_') {
                advanceChar(c);
            } else {
                break;
            }
        }

        boolean isFloat = false;

        // Check for dot (fractional part)
        if (pos < source.length() && source.charAt(pos) == '.') {
            // Peek ahead: if next char is a digit, it's a float
            if (pos + 1 < source.length() && Character.isDigit(source.charAt(pos + 1))) {
                isFloat = true;
                advanceChar('.');
                // Consume fractional digits
                while (pos < source.length()) {
                    char c = source.charAt(pos);
                    if (Character.isDigit(c) || c == '_') {
                        advanceChar(c);
                    } else {
                        break;
                    }
                }
            }
            // If dot is not followed by digit, leave it for next token (e.g. "0 .foo")
        }

        // Check for exponent (e/E)
        if (pos < source.length() && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
            isFloat = true;
            advanceChar(source.charAt(pos));
            if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
                advanceChar(source.charAt(pos));
            }
            while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
                advanceChar(source.charAt(pos));
            }
        }

        // Check for suffix (f/F/d/D/L/l)
        if (pos < source.length()) {
            char c = source.charAt(pos);
            boolean hasFloatSuffix = (c == 'f' || c == 'F' || c == 'd' || c == 'D');
            boolean hasIntSuffix = (c == 'l' || c == 'L');
            if (hasFloatSuffix) {
                isFloat = true;
                advanceChar(c);
            } else if (hasIntSuffix) {
                // Only consume L/l if not a float
                if (!isFloat) {
                    advanceChar(c);
                }
            }
        }

        String text = source.substring(start, pos);

        if (isFloat) {
            return new Token(TokenType.FLOAT_LITERAL, text, startLine, startCol, start);
        }

        // Integer type: octal if starts with 0 AND has more than one digit (or contains non-zero digits)
        // Java: 0 is decimal, 0755 is octal.
        boolean isOctal = startsWithZero && text.length() > 1;

        if (isOctal) {
            return new Token(TokenType.OCTAL_LITERAL, text, startLine, startCol, start);
        }

        return new Token(TokenType.INTEGER_LITERAL, text, startLine, startCol, start);
    }

    // Scan dot: if followed by digit, it's a float starting with .; otherwise DOT token
    private Token scanDotOrFloat() {
        int start = pos;
        int startLine = line;
        int startCol = column;
        advanceChar('.');

        if (pos < source.length() && Character.isDigit(source.charAt(pos))) {
            // It's a float like .5
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (Character.isDigit(c) || c == '_') {
                    advanceChar(c);
                } else {
                    break;
                }
            }

            // Check for exponent
            if (pos < source.length() && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
                advanceChar(source.charAt(pos));
                if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
                    advanceChar(source.charAt(pos));
                }
                while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
                    advanceChar(source.charAt(pos));
                }
            }

            // Check for suffix
            if (pos < source.length()) {
                char c = source.charAt(pos);
                if (c == 'f' || c == 'F' || c == 'd' || c == 'D') {
                    advanceChar(c);
                }
            }

            String text = source.substring(start, pos);
            return new Token(TokenType.FLOAT_LITERAL, text, startLine, startCol, start);
        }

        // Not a float; return DOT token
        String text = source.substring(start, pos);
        return new Token(TokenType.DOT, text, startLine, startCol, start);
    }

    // Helper: check if char is a hex digit
    private boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // Scan a string, consuming each escape together with its following character.
    private Token scanStringLiteral() {
        int start = pos;
        int startLine = line;
        int startCol = column;
        advanceChar('"');
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\n' || c == '\r') {
                throw new LexException("line break in string literal", startLine, startCol);
            }
            advanceChar(c);
            if (c == '"') {
                return new Token(TokenType.STRING_LITERAL, source.substring(start, pos), startLine, startCol, start);
            }
            if (c == '\\') {
                if (pos >= source.length()) {
                    throw new LexException("unterminated string literal", startLine, startCol);
                }
                char escaped = source.charAt(pos);
                if (escaped == '\n' || escaped == '\r') {
                    throw new LexException("line break in string literal", startLine, startCol);
                }
                advanceChar(escaped);
            }
        }
        throw new LexException("unterminated string literal", startLine, startCol);
    }

    // Scan a character literal without treating escaped quotes as delimiters.
    private Token scanCharLiteral() {
        int start = pos;
        int startLine = line;
        int startCol = column;
        advanceChar('\'');
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\n' || c == '\r') {
                throw new LexException("line break in character literal", startLine, startCol);
            }
            advanceChar(c);
            if (c == '\'') {
                return new Token(TokenType.CHAR_LITERAL, source.substring(start, pos), startLine, startCol, start);
            }
            if (c == '\\') {
                if (pos >= source.length()) {
                    throw new LexException("unterminated character literal", startLine, startCol);
                }
                char escaped = source.charAt(pos);
                if (escaped == '\n' || escaped == '\r') {
                    throw new LexException("line break in character literal", startLine, startCol);
                }
                advanceChar(escaped);
            }
        }
        throw new LexException("unterminated character literal", startLine, startCol);
    }

    // Scan a text block, preserving escapes and counting CRLF as one line break.
    private Token scanTextBlock() {
        int start = pos;
        int startLine = line;
        int startCol = column;
        advanceChar('"');
        advanceChar('"');
        advanceChar('"');
        while (pos < source.length()
                && (source.charAt(pos) == ' ' || source.charAt(pos) == '\t' || source.charAt(pos) == '\f')) {
            advanceChar(source.charAt(pos));
        }
        if (pos >= source.length() || (source.charAt(pos) != '\n' && source.charAt(pos) != '\r')) {
            throw new LexException("text block opening requires a line break", startLine, startCol);
        }
        while (pos < source.length()) {
            if (source.startsWith("\"\"\"", pos)) {
                advanceChar('"');
                advanceChar('"');
                advanceChar('"');
                return new Token(TokenType.TEXT_BLOCK, source.substring(start, pos), startLine, startCol, start);
            }
            char c = source.charAt(pos);
            advanceChar(c);
            if (c == '\r' && pos < source.length() && source.charAt(pos) == '\n') {
                pos++;
            }
            if (c == '\\') {
                if (pos >= source.length()) {
                    throw new LexException("unterminated text block", startLine, startCol);
                }
                char escaped = source.charAt(pos);
                advanceChar(escaped);
                if (escaped == '\r' && pos < source.length() && source.charAt(pos) == '\n') {
                    pos++;
                }
            }
        }
        throw new LexException("unterminated text block", startLine, startCol);
    }

    private final Deque<Token> bracketStack = new ArrayDeque<>();

    /** Links an opening bracket and its closing bracket to each other. */
    private static void linkBrackets(Token opener, Token closer) {
        opener.setMatchingBracket(closer);
        closer.setMatchingBracket(opener);
    }

    /**
     * Checks each closing bracket against the innermost open one: a closing bracket with no opener, or of
     * another kind, is a lexical error; a matching pair is linked.
     */
    private void handleBracket(Token token) {
        TokenType t = token.type();
        boolean isOpen = t == TokenType.OPENING_ROUND_BRACKET
                || t == TokenType.OPENING_SQUARE_BRACKET
                || t == TokenType.OPENING_CURLY_BRACKET;
        boolean isClose = t == TokenType.CLOSING_ROUND_BRACKET
                || t == TokenType.CLOSING_SQUARE_BRACKET
                || t == TokenType.CLOSING_CURLY_BRACKET;
        if (isOpen) {
            bracketStack.push(token);
            return;
        }
        if (!isClose) {
            return;
        }
        Token opener = bracketStack.peek();
        if (opener == null) {
            throw new LexException("unmatched closing bracket", token.line(), token.column());
        }
        boolean matches = (opener.type() == TokenType.OPENING_ROUND_BRACKET && t == TokenType.CLOSING_ROUND_BRACKET)
                || (opener.type() == TokenType.OPENING_SQUARE_BRACKET && t == TokenType.CLOSING_SQUARE_BRACKET)
                || (opener.type() == TokenType.OPENING_CURLY_BRACKET && t == TokenType.CLOSING_CURLY_BRACKET);
        if (!matches) {
            throw new LexException("mismatched closing bracket", token.line(), token.column());
        }
        bracketStack.pop();
        linkBrackets(opener, token);
    }

    /** At the end of the input, an opening bracket that was never closed is a lexical error. */
    private void checkUnclosedBrackets() {
        Token opener = bracketStack.peek();
        if (opener != null) {
            throw new LexException("unclosed opening bracket", opener.line(), opener.column());
        }
    }
}
