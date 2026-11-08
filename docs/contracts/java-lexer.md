# Java Lexer Contract

Package: `hermit.analysis.lex`

## TokenType
An enum classifying lexical tokens.

### Constant Groups
- **Literals**: `INTEGER_LITERAL`, `HEX_LITERAL`, `BINARY_LITERAL`, `OCTAL_LITERAL`, `FLOAT_LITERAL`, `CHAR_LITERAL`, `STRING_LITERAL`, `TEXT_BLOCK`.
- **Keywords**: Reserved keywords (e.g., `IF`, `ELSE`, `RETURN`, `CLASS`). Contextual keywords (`var`, `yield`, etc.) are lexed as `IDENTIFIER`.
- **Operators**: Arithmetic, logical, assignment (e.g., `PLUS`, `EQUALS`, `ARROW`). Shift operators `>>` and `>>>` are lexed as separate `GREATER_THAN` tokens; their assignment forms (`>>=`, `>>>=`) are merged.
- **Separators**: `DOT`, `SEMICOLON`, `COMMA`, etc.
- **Brackets**: `OPENING_ROUND_BRACKET`, `CLOSING_ROUND_BRACKET`, `OPENING_SQUARE_BRACKET`, `CLOSING_SQUARE_BRACKET`, `OPENING_CURLY_BRACKET`, `CLOSING_CURLY_BRACKET`. Angle brackets (`<`, `>`) are lexed as `LESS_THAN`/`GREATER_THAN`; angle bracket types are assigned by the second pass.
- **Trivia**: `WHITESPACE` (one token per run of spaces, tabs, form feeds and line breaks; a CR LF pair is one line break), `SINGLE_LINE_COMMENT`, `MULTI_LINE_COMMENT` (whitespace/comments kept for round-tripping).
- **EOF**: `END_OF_FILE`.
- **Derived Types**: Types like `CLOSING_ANGLE_BRACKET` are assigned only by the second (BNF) pass.

### Public Methods
- `fixedText()`: Returns the canonical lexeme for fixed tokens (keywords, operators, separators, brackets); null for literals/identifiers/trivia.
- `category()`: Returns the `Category` enum value (`LITERAL`, `KEYWORD`, `OPERATOR`, `SEPARATOR`, `BRACKET`, `TRIVIA`, `EOF`).
- `isTrivia()`: Returns true if the category is `TRIVIA`.
- `isKeyword()`: Returns true if the category is `KEYWORD`.
- `isLiteral()`: Returns true if the category is `LITERAL`.
- `isOperator()`: Returns true if the category is `OPERATOR`.
- `isOpeningBracket()`: Returns true for opening brackets.
- `isClosingBracket()`: Returns true for closing brackets.
- `matchingBracketType()`: Returns the matching bracket type (e.g., `CLOSING_ROUND_BRACKET` for `OPENING_ROUND_BRACKET`).
- `keyword(String)`: Static method returning the keyword token type for a string, or null.

## Token
Represents one lexical token.

### Constructor
- `Token(TokenType type, String text, int line, int column, int startOffset)`: Creates a token. `line` and `column` are 1-based; `startOffset` is 0-based. Tabs count as 4 columns.

### Accessors and Methods
- `type()`: Returns the current `TokenType`.
- `retype(TokenType newType)`: Mutates the type (used by the BNF pass for angle brackets).
- `text()`: Returns the exact source lexeme.
- `line()`: Returns the 1-based line number.
- `column()`: Returns the 1-based column number (tab = 4 cols).
- `startOffset()`: Returns the 0-based start index in source.
- `endOffset()`: Returns the 0-based index past the last character (`startOffset + text.length()`).
- `matchingBracket()`: Returns the linked bracket token (opener/closer pair), or null.
- `setMatchingBracket(Token other)`: Links this token to its matching bracket.
- `isTrivia()`: Returns true if the token type is trivia.
- `is(TokenType t)`: Returns true if the token type equals `t`.
- `toString()`: Returns the raw source text (enables exact round-trip).
- `describe()`: Returns a debug string `TYPE("text")@line:col`.

## LexException
A runtime exception for lexical errors (unterminated literals, illegal characters, mismatched brackets).

### Constructor
- `LexException(String message, int line, int column)`: Creates an exception with 1-based position.

### Accessors
- `line()`: Returns the 1-based line number of the error.
- `column()`: Returns the 1-based column number of the error.

## JavaLexer
The first pass lexer turning source text into a flat token stream.

### Public Entry Points
- `JavaLexer(String source)`: Constructor.
- `lex(String source)`: Static convenience method returning a list of tokens (including trivia and `END_OF_FILE`).
- `tokenize()`: Returns the list of tokens.

### Guarantees
- **Round-trip**: Concatenating `text()` of all tokens reproduces the original source exactly, including trivia.
- **Positions**: 1-based line/column; tabs advance column by 4.
- **Brackets**: `()`, `[]`, `{}` are matched and linked via `matchingBracket`. Angle brackets are not linked in this pass.
- **Errors**: Lexical errors throw `LexException` with position info.

## Test Outcomes (`JavaLexerTest`, 13 methods)
1. `roundTripsExactSourceIncludingTrivia`: Token texts concatenated equal the original source string exactly.
2. `keywordsVsIdentifiers_contextualKeywordsAreIdentifiers`: Reserved keywords map to their specific types; contextual keywords (`var`, `yield`) map to `IDENTIFIER`; literals (`true`, `false`, `null`) map to their types.
3. `numericLiterals`: Integer, hex, binary, octal, and float literals are correctly typed; `0 .foo` is parsed as integer + dot + identifier.
4. `stringsCharsAndCommentsDoNotLeakKeywords`: Keywords inside strings, chars, or comments are not recognized as keywords; escaped quotes/chars work.
5. `angleAndShiftRules`: `<` and `>` are single tokens; `<=`, `>=`, `<<`, `<<=` are merged; `>>` and `>>>` are separate `GREATER_THAN`; `>>=` and `>>>=` are merged; nested generics use separate `>` tokens.
6. `operatorsAndSeparators`: Compound assignments, increment/decrement, arrow, double-colon, ellipsis, at-sign, equality, and logical operators are correctly typed.
7. `lineAndColumnWithTabIsFourColumns`: A tab at column 1 makes the next token start at column 5; newlines reset column to 1.
8. `bracketsAreMatchedAndLinked`: Openers and closers are linked bidirectionally; nested different bracket types work.
9. `malformedBracketsThrow`: Unclosed openers, unmatched closers, and intersecting/mismatched brackets throw `LexException`.
10. `unterminatedRegionsThrow`: Unterminated block comments, strings, and multi-line strings throw `LexException`.
11. `backslashEscapesTheClosingDelimiterSoTheLiteralContinues`: Escaped quotes/backslashes inside literals keep them open; text blocks with escaped `"""` work.
12. `backslashBeforeNewlineIsIllegalInStringAndCharButLegalInTextBlock`: Backslash-newline in strings/chars throws; in text blocks it is a legal line continuation.
13. `blockTypesDoNotIntersect_whicheverStartedFinishesFirst`: Comments/strings inside other literals are just text; regions do not bleed; round-trip holds.
