package hermit.analysis.lex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavaLexerTest {

    /** Non-trivia, non-EOF token types in order — the "significant" stream. */
    private static List<TokenType> sig(String src) {
        return JavaLexer.lex(src).stream()
                .filter(t -> !t.isTrivia() && t.type() != TokenType.END_OF_FILE)
                .map(Token::type)
                .toList();
    }

    private static List<Token> sigTokens(String src) {
        return JavaLexer.lex(src).stream()
                .filter(t -> !t.isTrivia() && t.type() != TokenType.END_OF_FILE)
                .toList();
    }

    @Test
    void roundTripsExactSourceIncludingTrivia() {
        String src = "package a.b;\n\n// hi\nclass C {\n\tint x = 3; /* c */\n}\n";
        String rebuilt = JavaLexer.lex(src).stream().map(Token::text).reduce("", String::concat);
        assertEquals(src, rebuilt, "token texts (incl. trivia) must concatenate back to the exact source");
    }

    @Test
    void keywordsVsIdentifiers_contextualKeywordsAreIdentifiers() {
        assertEquals(List.of(TokenType.IF, TokenType.ELSE, TokenType.RETURN, TokenType.CLASS), sig("if else return class"));
        // var/yield/record/sealed/when are CONTEXTUAL — lexed as identifiers
        for (Token t : sigTokens("var yield record sealed when")) {
            assertEquals(TokenType.IDENTIFIER, t.type(), t.text() + " should be a contextual identifier");
        }
        assertEquals(List.of(TokenType.TRUE, TokenType.FALSE, TokenType.NULL), sig("true false null"));
    }

    @Test
    void numericLiterals() {
        assertEquals(List.of(TokenType.INTEGER_LITERAL), sig("123"));
        assertEquals(List.of(TokenType.INTEGER_LITERAL), sig("1_000_000L"));
        assertEquals(List.of(TokenType.HEX_LITERAL), sig("0xFF_ECl"));
        assertEquals(List.of(TokenType.BINARY_LITERAL), sig("0b1010"));
        assertEquals(List.of(TokenType.OCTAL_LITERAL), sig("0755"));
        assertEquals(List.of(TokenType.FLOAT_LITERAL), sig("3.14"));
        assertEquals(List.of(TokenType.FLOAT_LITERAL), sig("1e-9"));
        assertEquals(List.of(TokenType.FLOAT_LITERAL), sig("10f"));
        assertEquals(List.of(TokenType.FLOAT_LITERAL), sig("0x1.8p3"));
        // member access on an integer must not be swallowed as a float
        assertEquals(List.of(TokenType.INTEGER_LITERAL, TokenType.DOT, TokenType.IDENTIFIER), sig("0 .foo"));
    }

    @Test
    void stringsCharsAndCommentsDoNotLeakKeywords() {
        assertEquals(List.of(TokenType.STRING_LITERAL), sig("\"class if for\""));
        assertEquals(List.of(TokenType.STRING_LITERAL), sig("\"a \\\" b\""));      // escaped quote inside
        assertEquals(List.of(TokenType.CHAR_LITERAL), sig("'\\''"));               // escaped single quote
        assertEquals(List.of(TokenType.TEXT_BLOCK), sig("\"\"\"\nhi \"x\" if\n\"\"\""));
        // a // inside a string is not a comment; a keyword inside a comment is not a keyword
        assertEquals(List.of(TokenType.STRING_LITERAL), sig("\"http://x\""));
        assertEquals(List.of(), sig("// class if\n"));
        assertEquals(List.of(), sig("/* class if */"));
    }

    @Test
    void angleAndShiftRules() {
        assertEquals(List.of(TokenType.LESS_THAN), sig("<"));
        assertEquals(List.of(TokenType.GREATER_THAN), sig(">"));
        assertEquals(List.of(TokenType.LESS_THAN_OR_EQUAL), sig("<="));
        assertEquals(List.of(TokenType.GREATER_THAN_OR_EQUAL), sig(">="));
        assertEquals(List.of(TokenType.SHIFT_LEFT), sig("<<"));
        assertEquals(List.of(TokenType.SHIFT_LEFT_ASSIGNMENT), sig("<<="));
        // '>>' and '>>>' WITHOUT '=' stay as separate GREATER_THAN tokens (BNF pass resolves them)
        assertEquals(List.of(TokenType.GREATER_THAN, TokenType.GREATER_THAN), sig(">>"));
        assertEquals(List.of(TokenType.GREATER_THAN, TokenType.GREATER_THAN, TokenType.GREATER_THAN), sig(">>>"));
        // but the assignment forms are merged directly
        assertEquals(List.of(TokenType.SHIFT_RIGHT_ASSIGNMENT), sig(">>="));
        assertEquals(List.of(TokenType.SHIFT_RIGHT_PRESERVING_SIGN_ASSIGNMENT), sig(">>>="));
        // a nested-generic close is two separate GREATER_THAN (angle brackets decided later)
        assertEquals(
                List.of(TokenType.IDENTIFIER, TokenType.LESS_THAN, TokenType.IDENTIFIER, TokenType.LESS_THAN,
                        TokenType.IDENTIFIER, TokenType.GREATER_THAN, TokenType.GREATER_THAN),
                sig("Map<K<Integer>>"));
    }

    @Test
    void operatorsAndSeparators() {
        assertEquals(
                List.of(TokenType.PLUS_ASSIGNMENT, TokenType.MINUS_ASSIGNMENT, TokenType.STAR_ASSIGNMENT,
                        TokenType.SLASH_ASSIGNMENT, TokenType.PERCENT_ASSIGNMENT),
                sig("+= -= *= /= %="));
        assertEquals(List.of(TokenType.INCREMENT, TokenType.DECREMENT), sig("++ --"));
        assertEquals(List.of(TokenType.ARROW, TokenType.DOUBLE_COLON, TokenType.ELLIPSIS, TokenType.AT), sig("-> :: ... @"));
        assertEquals(List.of(TokenType.EQUALS, TokenType.NOT_EQUALS, TokenType.LOGICAL_AND, TokenType.LOGICAL_OR),
                sig("== != && ||"));
    }

    @Test
    void lineAndColumnWithTabIsFourColumns() {
        // "\tif" : the tab occupies columns 1..4, so 'if' starts at column 5
        List<Token> toks = sigTokens("\tif");
        Token ifTok = toks.get(0);
        assertEquals(TokenType.IF, ifTok.type());
        assertEquals(1, ifTok.line());
        assertEquals(5, ifTok.column());

        // second line starts at column 1
        Token x = sigTokens("a\n  x").get(1);
        assertEquals(TokenType.IDENTIFIER, x.type());
        assertEquals(2, x.line());
        assertEquals(3, x.column());
    }

    @Test
    void bracketsAreMatchedAndLinked() {
        List<Token> toks = JavaLexer.lex("f(a[b]{c})");
        Token open = toks.stream().filter(t -> t.type() == TokenType.OPENING_ROUND_BRACKET).findFirst().orElseThrow();
        assertNotNull(open.matchingBracket());
        assertEquals(TokenType.CLOSING_ROUND_BRACKET, open.matchingBracket().type());
        assertSame(open, open.matchingBracket().matchingBracket(), "closer must point back to its opener");
        // nesting of different types is fine
        assertTrue(sig("([{}])").size() == 6);
    }

    @Test
    void malformedBracketsThrow() {
        assertThrows(LexException.class, () -> JavaLexer.lex("("), "unclosed opener");
        assertThrows(LexException.class, () -> JavaLexer.lex(")"), "unmatched closer");
        assertThrows(LexException.class, () -> JavaLexer.lex("( ]"), "intersecting/mismatched brackets");
        assertThrows(LexException.class, () -> JavaLexer.lex("([)]"), "intersecting brackets");
    }

    @Test
    void unterminatedRegionsThrow() {
        assertThrows(LexException.class, () -> JavaLexer.lex("/* no end"), "unterminated block comment");
        assertThrows(LexException.class, () -> JavaLexer.lex("\"no end"), "unterminated string");
        assertThrows(LexException.class, () -> JavaLexer.lex("\"line\nbreak\""), "string cannot span lines");
    }

    @Test
    void backslashEscapesTheClosingDelimiterSoTheLiteralContinues() {
        // "\""  — an escaped double quote inside a string: still ONE string literal
        assertEquals(List.of(TokenType.STRING_LITERAL), sig("\"\\\"\""));
        // "\\"  — the first backslash escapes the second; the literal then closes normally
        assertEquals(List.of(TokenType.STRING_LITERAL), sig("\"\\\\\""));
        // "a\"b\\c" — mixed escapes mid-literal
        assertEquals(List.of(TokenType.STRING_LITERAL), sig("\"a\\\"b\\\\c\""));
        // '\'' and '\\' — escaped closing quote / escaped backslash in char literals
        assertEquals(List.of(TokenType.CHAR_LITERAL), sig("'\\''"));
        assertEquals(List.of(TokenType.CHAR_LITERAL), sig("'\\\\'"));
        // two literals in a row, each with escapes — the boundary is found correctly
        assertEquals(List.of(TokenType.CHAR_LITERAL, TokenType.PLUS, TokenType.STRING_LITERAL),
                sig("'\\'' + \"\\\\\""));
        // text block: escaped """ inside does not close it
        assertEquals(List.of(TokenType.TEXT_BLOCK), sig("\"\"\"\na \\\"\"\" b\n\"\"\""));
    }

    @Test
    void backslashBeforeNewlineIsIllegalInStringAndCharButLegalInTextBlock() {
        // string: "abc\<newline>def" — \<line terminator> is not a valid escape (JLS §3.10.5)
        assertThrows(LexException.class, () -> JavaLexer.lex("\"abc\\\ndef\""));
        assertThrows(LexException.class, () -> JavaLexer.lex("\"abc\\\r\ndef\""));
        // char: '\<newline>'
        assertThrows(LexException.class, () -> JavaLexer.lex("'\\\n'"));
        // text block: \<newline> is a legal LINE CONTINUATION (JLS §3.10.6) — lexes as one TEXT_BLOCK
        assertEquals(List.of(TokenType.TEXT_BLOCK), sig("\"\"\"\na\\\nb\"\"\""));
    }

    @Test
    void blockTypesDoNotIntersect_whicheverStartedFinishesFirst() {
        // a comment-opener inside a STRING is just characters — the string finishes first
        assertEquals(List.of(TokenType.STRING_LITERAL), sig("\"/*\""));
        assertEquals(List.of(TokenType.STRING_LITERAL, TokenType.IDENTIFIER), sig("\"/*\" x"));
        // a string inside a BLOCK COMMENT is just comment text — the comment finishes first
        List<Token> toks = JavaLexer.lex("/* \"abc\" */");
        assertEquals(1, toks.stream().filter(t -> t.type() == TokenType.MULTI_LINE_COMMENT).count());
        assertEquals(0, toks.stream().filter(t -> t.type() == TokenType.STRING_LITERAL).count());
        // an (unterminated-looking) quote inside a LINE COMMENT never starts a string
        assertEquals(List.of(TokenType.IDENTIFIER), sig("// \"abc\nx"));
        // a comment-opener inside a LINE COMMENT never starts a block comment (no unterminated error)
        assertEquals(List.of(TokenType.IDENTIFIER), sig("// /*\nx"));
        // a double quote inside a CHAR literal is just a character
        assertEquals(List.of(TokenType.CHAR_LITERAL), sig("'\"'"));
        // a line-comment opener and a single quote inside a STRING are just characters
        assertEquals(List.of(TokenType.STRING_LITERAL), sig("\"//'\""));
        // after a real comment closes, a string containing "*/" is a string (regions don't bleed)
        assertEquals(List.of(TokenType.STRING_LITERAL), sig("/* c */ \"*/\""));
        // a block-comment opener inside a TEXT BLOCK is just text
        assertEquals(List.of(TokenType.TEXT_BLOCK), sig("\"\"\"\n/* not a comment */ \"quotes\"\n\"\"\""));
        // and everything above still round-trips exactly
        String src = "/* \"s\" */ \"/* '\" '\"' // tail";
        String rebuilt = JavaLexer.lex(src).stream().map(Token::text).reduce("", String::concat);
        assertEquals(src, rebuilt);
    }

    @Test
    void aRunOfWhitespaceWithItsLineBreaksIsOneToken() {
        List<Token> tokens = new JavaLexer("{\n    x\r\n\ty }").tokenize();
        assertEquals(TokenType.WHITESPACE, tokens.get(1).type());
        assertEquals("\n    ", tokens.get(1).text(), "the line break and the indentation after it");
        assertEquals(2, tokens.get(2).line());
        assertEquals(5, tokens.get(2).column());
        assertEquals("\r\n\t", tokens.get(3).text());
        assertEquals(3, tokens.get(4).line(), "\\r\\n is one line break");
        assertEquals(5, tokens.get(4).column());
    }
}
