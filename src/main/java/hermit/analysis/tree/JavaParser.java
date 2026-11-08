package hermit.analysis.tree;

import hermit.analysis.lex.JavaLexer;
import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;

import java.util.ArrayList;
import java.util.List;

public final class JavaParser {

    private JavaParser() {}

    // ---- Entry points ----

    public static ParseTree parse(String source) {
        List<Token> tokens = JavaLexer.lex(source);
        Cursor cursor = new Cursor(tokens);
        Node cu = parseCompilationUnit(cursor);
        Token code = cursor.peekCode();
        if (code.type() != TokenType.END_OF_FILE) {
            throw new ParseException("unexpected input after compilation unit", code.line(), code.column());
        }
        return new ParseTree(cu);
    }

    public static Node parseStatementSnippet(String source) {
        Cursor cursor = new Cursor(JavaLexer.lex(source));
        Node stmt = parseStatement(cursor);
        checkTrailing(cursor);
        return stmt;
    }

    public static List<Node> parseStatementsSnippet(String source) {
        Cursor cursor = new Cursor(JavaLexer.lex(source));
        List<Node> stmts = new ArrayList<>();
        while (cursor.peekCode().type() != TokenType.END_OF_FILE) {
            stmts.add(parseStatement(cursor));
        }
        // Reject comment-only snippets so an edit cannot report success without inserting a statement.
        if (stmts.isEmpty()) {
            Token end = cursor.peekCode();
            throw new ParseException("expected a statement, found end of input", end.line(), end.column());
        }
        return stmts;
    }

    public static Node parseExpressionSnippet(String source) {
        Cursor cursor = new Cursor(JavaLexer.lex(source));
        Node expr = parseExpression(cursor);
        checkTrailing(cursor);
        return expr;
    }

    public static Node parseMemberSnippet(String source) {
        Cursor cursor = new Cursor(JavaLexer.lex(source));
        Node member = parseMember(cursor, new Node(NodeType.FRAGMENT));
        // a member's end-of-line comment ("int count;   // how many") belongs to the member written, as the reference
        // parser keeps it; it was dropped with the trivia after the member
        cursor.takeTrivia().forEach(member::add);
        checkTrailing(cursor);
        return member;
    }

    // parseImportSnippet uses a holder node because parseImport takes a parent
    public static Node parseImportSnippet(String source) {
        Cursor cursor = new Cursor(JavaLexer.lex(source));
        Node holder = new Node(NodeType.FRAGMENT);
        parseImport(cursor, holder);
        checkTrailing(cursor);
        return holder.children().get(0);
    }

    // ---- Cursor and helpers ----

    private static class Cursor {
        private final List<Token> tokens;
        private int pos = 0;
        // Keep token changes in the order they were made.
        private final List<Mutation> journal = new ArrayList<>();

        Cursor(List<Token> tokens) {
            this.tokens = tokens;
            if (tokens.isEmpty()) {
                this.tokens.add(new Token(TokenType.END_OF_FILE, "", 1, 1, 0));
            }
        }

        /** The trivia at the cursor (whitespace and comments), taken. */
        List<Token> takeTrivia() {
            List<Token> taken = new ArrayList<>();
            while (pos < tokens.size() && tokens.get(pos).isTrivia()) {
                taken.add(tokens.get(pos++));
            }
            return taken;
        }

        // peekCode handles end of list safely
        Token peekCode() {
            int p = pos;
            while (p < tokens.size() && tokens.get(p).isTrivia()) {
                p++;
            }
            if (p < tokens.size()) {
                return tokens.get(p);
            }
            return tokens.get(tokens.size() - 1); // EOF
        }

        void expect(TokenType type) {
            Token t = takeCode();
            if (t.type() != type) {
                throw new ParseException("expected " + type.fixedText() + ", found " + t.type().fixedText(),
                               t.line(), t.column());
            }
        }

        Token takeCode() {
            while (pos < tokens.size() && tokens.get(pos).isTrivia()) {
                pos++;
            }
            if (pos >= tokens.size()) {
                return tokens.get(tokens.size() - 1);
            }
            Token t = tokens.get(pos);
            pos++;
            return t;
        }

        boolean match(TokenType type) {
            Token code = peekCode();
            return code.type() == type;
        }

        // isModifier uses a switch for specific keywords only
        boolean isModifier(Token t) {
            if (t.type() == TokenType.AT) {
                return true;
            }
            switch (t.type()) {
                case PUBLIC: case PROTECTED: case PRIVATE: case STATIC: case FINAL:
                case ABSTRACT: case NATIVE: case SYNCHRONIZED: case TRANSIENT:
                case VOLATILE: case STRICTFP: case DEFAULT:
                    return true;
                default:
                    return false;
            }
        }

        // Remember a token's state before a speculative change.
        private record Mutation(Token token, TokenType oldType, Token oldMatch) {}

        // Remember the position and journal size at a speculative boundary.
        private record Mark(int pos, int journalSize) {}

        // Record the token's state before changing its type or matching bracket.
        void mutate(Token token) {
            journal.add(new Mutation(token, token.type(), token.matchingBracket()));
        }

        // Mark the current position and token changes.
        Mark mark() {
            return new Mark(pos, journal.size());
        }

        // Restore the position and undo token changes, newest first.
        void rollback(Mark mark) {
            pos = mark.pos();
            for (int k = journal.size() - 1; k >= mark.journalSize(); k--) {
                Mutation mutation = journal.get(k);
                mutation.token().retype(mutation.oldType());
                mutation.token().setMatchingBracket(mutation.oldMatch());
            }
            journal.subList(mark.journalSize(), journal.size()).clear();
        }

        // Journal a matching-bracket change before applying it.
        void setMatchingBracket(Token token, Token matchingBracket) {
            mutate(token);
            token.setMatchingBracket(matchingBracket);
        }
    }

    private static void take(Cursor cursor, Node into) {
        while (true) {
            if (cursor.pos >= cursor.tokens.size()) break;
            Token t = cursor.tokens.get(cursor.pos);
            if (t.isTrivia()) {
                into.add(t);
                cursor.pos++;
            } else {
                break;
            }
        }
        if (cursor.pos < cursor.tokens.size()) {
            Token t = cursor.tokens.get(cursor.pos);
            into.add(t);
            cursor.pos++;
            if (into.token() == null && isKeywordOf(into.type(), t.type())) {
                into.setPrincipal(t);
            }
        }
    }

    private static void checkTrailing(Cursor cursor) {
        Token code = cursor.peekCode();
        if (code.type() != TokenType.END_OF_FILE) {
            throw new ParseException("unexpected trailing input", code.line(), code.column());
        }
    }

    // ---- Compilation Unit ----

    private static Node parseCompilationUnit(Cursor cursor) {
        Node cu = new Node(NodeType.COMPILATION_UNIT);
        // Do not take leading trivia here; it swallows 'package'.
        // Handle trailing tokens in the EOF branch.
        while (true) {
            Token code = cursor.peekCode();
            if (code.type() == TokenType.PACKAGE) {
                parsePackageDeclaration(cursor, cu);
            } else if (code.type() == TokenType.IMPORT) {
                parseImport(cursor, cu);
            } else if (code.type() == TokenType.END_OF_FILE) {
                // Add remaining tokens (trailing trivia + EOF) for round-trip
                while (cursor.pos < cursor.tokens.size()) {
                    cu.add(cursor.tokens.get(cursor.pos++));
                }
                break;
            } else {
                parseTypeDeclaration(cursor, cu);
            }
        }
        return cu;
    }

    private static void parsePackageDeclaration(Cursor cursor, Node cu) {
        Node pkg = new Node(NodeType.PACKAGE_DECLARATION);
        take(cursor, pkg); // 'package'
        take(cursor, pkg); // first identifier
        while (cursor.match(TokenType.DOT)) {
            take(cursor, pkg); // '.'
            take(cursor, pkg); // identifier
        }
        expectSemicolonOrTake(cursor, pkg);
        cu.add(pkg);
    }

    private static void parseImport(Cursor cursor, Node cu) {
        Node imp = new Node(NodeType.IMPORT_DECLARATION);
        take(cursor, imp); // 'import'
        if (cursor.match(TokenType.STATIC)) {
            take(cursor, imp); // 'static'
        }
        parseImportName(cursor, imp);
        expectSemicolonOrTake(cursor, imp);
        cu.add(imp);
    }

    private static void parseImportName(Cursor cursor, Node into) {
        take(cursor, into); // first identifier
        while (cursor.match(TokenType.DOT)) {
            take(cursor, into); // '.'
            if (cursor.peekCode().type() == TokenType.STAR) {
                take(cursor, into); // '*'
            } else {
                take(cursor, into); // identifier
            }
        }
    }

    private static void expectSemicolonOrTake(Cursor cursor, Node into) {
        while (cursor.pos < cursor.tokens.size() && cursor.tokens.get(cursor.pos).isTrivia()) {
            into.add(cursor.tokens.get(cursor.pos));
            cursor.pos++;
        }
        if (cursor.pos < cursor.tokens.size() && cursor.tokens.get(cursor.pos).type() == TokenType.SEMICOLON) {
            into.add(cursor.tokens.get(cursor.pos));
            cursor.pos++;
        }
    }

    // ---- Type Declarations ----

    private static Node parseTypeDeclaration(Cursor cursor, Node parent) {
        Node type = new Node(NodeType.FRAGMENT);
        takeModifiers(cursor, type);
        return parseTypeDeclarationRest(cursor, type, parent);
    }

    private static void parseTypeParameters(Cursor cursor, Node into) {
        while (true) {
            Node param = new Node(NodeType.TYPE_PARAMETER);
            Token name = cursor.peekCode();
            param.setPrincipal(name);
            take(cursor, param); // name
            into.add(param);
            if (cursor.match(TokenType.EXTENDS)) {
                take(cursor, param); // 'extends'
                parseTypeReferenceInto(cursor, param);
            }
            if (cursor.match(TokenType.COMMA)) {
                take(cursor, into); // ','
            } else {
                break;
            }
        }
    }

    private static Node parseClassBody(Cursor cursor) {
        Node body = new Node(NodeType.CLASS_BODY);
        take(cursor, body); // '{'
        while (true) {
            Token code = cursor.peekCode();
            if (code.type() == TokenType.CLOSING_CURLY_BRACKET) {
                take(cursor, body);
                break;
            }
            if (code.type() == TokenType.END_OF_FILE) {
                throw new ParseException("unexpected end of input", code.line(), code.column());
            }
            parseMember(cursor, body);
        }
        return body;
    }

    // ---- Members ----

    private static Node parseMember(Cursor cursor, Node parent) {
        Node member = new Node(NodeType.FRAGMENT);
        // Do not take here; modifier loop handles it.
        takeModifiers(cursor, member);
        if (cursor.match(TokenType.LESS_THAN)) {
            take(cursor, member); // '<'
            parseTypeParameters(cursor, member);
            take(cursor, member); // '>'
        }
        Token code = cursor.peekCode();
        Token after = codeTokenAfter(cursor, code);
        if (isTypeDeclarationKeyword(cursor, code)) {
            return parseTypeDeclarationRest(cursor, member, parent);
        }
        if (code.type() == TokenType.IDENTIFIER && after.type() == TokenType.OPENING_ROUND_BRACKET) {
            member.retype(NodeType.CONSTRUCTOR_DECLARATION);
            member.setPrincipal(code);
            take(cursor, member); // constructor name
            parseMethodRest(cursor, member);
            parent.add(member);
            return member;
        }
        if (code.type() == TokenType.IDENTIFIER && after.type() == TokenType.OPENING_CURLY_BRACKET) {
            member.retype(NodeType.COMPACT_CONSTRUCTOR);
            member.setPrincipal(code);
            take(cursor, member); // constructor name
            parseBlock(cursor, member);
            parent.add(member);
            return member;
        }
        if (code.type() == TokenType.VOID || isTypeStart(code)) {
            Node typeRef = new Node(NodeType.TYPE);
            parseTypeWithArgs(cursor, typeRef);
            member.add(typeRef);
            Token name = cursor.peekCode();
            if (codeTokenAfter(cursor, name).type() == TokenType.OPENING_ROUND_BRACKET) {
                member.setPrincipal(name);
                take(cursor, member); // name
                member.retype(NodeType.METHOD_DECLARATION);
                parseMethodRest(cursor, member);
            } else {
                member.retype(NodeType.FIELD_DECLARATION);
                parseFieldRest(cursor, member);
            }
        } else if (code.type() == TokenType.OPENING_CURLY_BRACKET) {
            member.retype(NodeType.INITIALIZER_BLOCK);
            take(cursor, member); // '{'
            while (!cursor.match(TokenType.CLOSING_CURLY_BRACKET)) {
                parseStatement(cursor, member);
            }
            take(cursor, member); // '}'
        } else {
            throw new ParseException("expected member", code.line(), code.column());
        }
        parent.add(member);
        return member;
    }

    private static void parseMethodRest(Cursor cursor, Node method) {
        take(cursor, method); // '('
        parseParameters(cursor, method);
        take(cursor, method); // ')'
        if (cursor.match(TokenType.THROWS)) {
            take(cursor, method); // 'throws'
            parseTypeReferenceInto(cursor, method);
            while (cursor.match(TokenType.COMMA)) {
                take(cursor, method); // ','
                parseTypeReferenceInto(cursor, method);
            }
        }
        if (cursor.match(TokenType.SEMICOLON)) {
            take(cursor, method);
        } else {
            parseBlock(cursor, method);
        }
    }

    private static void parseParameters(Cursor cursor, Node method) {
        if (cursor.peekCode().type() == TokenType.CLOSING_ROUND_BRACKET) {
            return;
        }
        while (true) {
            Node param = new Node(NodeType.PARAMETER);
            takeModifiers(cursor, param);
            parseTypeReferenceInto(cursor, param);
            if (cursor.match(TokenType.ELLIPSIS)) {
                take(cursor, param);
            }
            param.setPrincipal(cursor.peekCode());
            take(cursor, param);
            while (cursor.match(TokenType.OPENING_SQUARE_BRACKET)) {
                take(cursor, param);
                take(cursor, param);
            } // name
            method.add(param);
            if (cursor.match(TokenType.COMMA)) {
                take(cursor, method); // ','
            } else {
                break;
            }
        }
    }

    private static void parseFieldRest(Cursor cursor, Node field) {
        parseDeclarator(cursor, field);
        while (cursor.match(TokenType.COMMA)) {
            take(cursor, field);
            parseDeclarator(cursor, field);
        }
        expectSemicolonOrTake(cursor, field);
    }

    // ---- Expressions ----

    private static Node parseExpression(Cursor cursor) {
        Node left = parseBinaryExpression(cursor, 0);
        if (isAssignmentOperator(cursor.peekCode().type())) {
            Node assignment = new Node(NodeType.ASSIGNMENT);
            assignment.add(left);
            assignment.setPrincipal(cursor.peekCode());
            take(cursor, assignment); // consume operator
            assignment.add(parseExpression(cursor));
            return assignment;
        }
        if (cursor.match(TokenType.QUESTION)) {
            Node ternary = new Node(NodeType.TERNARY);
            ternary.add(left);
            take(cursor, ternary);
            ternary.add(parseExpression(cursor));
            take(cursor, ternary);
            ternary.add(parseExpression(cursor));
            return ternary;
        }
        return left;
    }

    private static Node parseBinaryExpression(Cursor cursor, int minPrec) {
        Node left = parseUnaryExpression(cursor);
        while (true) {
            if (minPrec <= 7 && cursor.match(TokenType.INSTANCEOF)) {
                Node instanceOf = new Node(NodeType.INSTANCEOF);
                instanceOf.add(left);
                instanceOf.setPrincipal(cursor.peekCode());
                take(cursor, instanceOf);
                if (cursor.match(TokenType.FINAL)) {
                    take(cursor, instanceOf);
                }
                Node type = new Node(NodeType.TYPE);
                type.setPrincipal(cursor.peekCode());
                parseTypeWithArgs(cursor, type);
                if (cursor.match(TokenType.IDENTIFIER)) {
                    Node pattern = new Node(NodeType.TYPE_PATTERN);
                    pattern.add(type);
                    pattern.setPrincipal(cursor.peekCode());
                    take(cursor, pattern);
                    instanceOf.add(pattern);
                } else {
                    instanceOf.add(type);
                }
                left = instanceOf;
                continue;
            }
            int prec = precedence(operatorAt(cursor));
            if (prec < 0 || prec < minPrec) break;
            Node bin = new Node(NodeType.BINARY_EXPRESSION);
            bin.add(left);
            bin.setPrincipal(takeOperator(cursor, bin));
            bin.add(parseBinaryExpression(cursor, prec + 1));
            left = bin;
        }
        return left;
    }

    // default precedence is -1 so non-operators don't match
    private static int getPrecedence(Token t) {
        switch (t.type()) {
            case LOGICAL_OR: return 1;
            case LOGICAL_AND: return 2;
            case EQUALS: case NOT_EQUALS: return 3;
            case LESS_THAN: case GREATER_THAN:
            case LESS_THAN_OR_EQUAL: case GREATER_THAN_OR_EQUAL: return 4;
            case PLUS: case MINUS: return 5;
            case STAR: case SLASH: case PERCENT: return 6;
            default: return -1;
        }
    }

    private static Node parseUnaryExpression(Cursor cursor) {
        Token t = cursor.peekCode();
        if (t.type() == TokenType.LOGICAL_NOT || t.type() == TokenType.TILDE ||
            t.type() == TokenType.PLUS || t.type() == TokenType.MINUS ||
            t.type() == TokenType.INCREMENT || t.type() == TokenType.DECREMENT) {
            Node node = new Node(NodeType.UNARY_EXPRESSION);
            node.setPrincipal(cursor.peekCode());
            take(cursor, node);
            node.add(parseUnaryExpression(cursor));
            return node;
        }
        return parseSelectors(cursor, parsePrimaryExpression(cursor));
    }

    private static Node parsePrimaryExpression(Cursor cursor) {
        Token code = cursor.peekCode();
        if (isLambdaAhead(cursor)) {
            return parseLambda(cursor);
        }
        if (code.type().isLiteral() || code.type() == TokenType.TRUE || code.type() == TokenType.FALSE
                       || code.type() == TokenType.NULL) {
            Node lit = new Node(NodeType.LITERAL);
            lit.setPrincipal(code);
            take(cursor, lit);
            return lit;
        }
        if (code.type() == TokenType.SWITCH) {
            return parseSwitch(cursor, null);
        }
        if (isPrimitive(code.type()) && (codeTokenAfter(cursor, code).type() == TokenType.DOT
                       || codeTokenAfter(cursor, code).type() == TokenType.OPENING_SQUARE_BRACKET)) {
            Node typeNode = new Node(NodeType.TYPE);
            typeNode.setPrincipal(code);
            take(cursor, typeNode);
            return typeNode;
        }
        if (code.type() == TokenType.NEW) {
            return parseNewInstance(cursor);
        }
        if (code.type() == TokenType.IDENTIFIER || code.type() == TokenType.THIS || code.type() == TokenType.SUPER) {
            Node ident = new Node(code.type() == TokenType.THIS ? NodeType.THIS_EXPRESSION
                           : code.type() == TokenType.SUPER ? NodeType.SUPER_EXPRESSION : NodeType.IDENTIFIER_EXPRESSION);
            ident.setPrincipal(code);
            take(cursor, ident);
            if (cursor.match(TokenType.OPENING_ROUND_BRACKET)) {
                ident.retype(NodeType.METHOD_CALL);
                take(cursor, ident); // '('
                parseArguments(cursor, ident);
                take(cursor, ident); // ')'
            }
            return ident;
        }
        if (code.type() == TokenType.OPENING_ROUND_BRACKET) {
            Node cast = tryParseCast(cursor);
            if (cast != null) {
                return cast;
            }
            Node paren = new Node(NodeType.PARENTHESIZED);
            take(cursor, paren); // '('
            parseExpressionInto(cursor, paren);
            take(cursor, paren); // ')'
            return paren;
        }
        throw new ParseException("expected expression", code.line(), code.column());
    }

    private static void parseArguments(Cursor cursor, Node into) {
        if (cursor.peekCode().type() == TokenType.CLOSING_ROUND_BRACKET) {
            return;
        }
        while (true) {
            parseExpressionInto(cursor, into);
            if (cursor.match(TokenType.COMMA)) {
                take(cursor, into);
            } else {
                break;
            }
        }
    }

    private static void parseAnnotationArgs(Cursor cursor, Node into) {
        if (cursor.peekCode().type() == TokenType.CLOSING_ROUND_BRACKET) {
            return;
        }
        while (true) {
            parseExpressionInto(cursor, into);
            if (cursor.match(TokenType.COMMA)) {
                take(cursor, into);
            } else {
                break;
            }
        }
    }

    // ---- Statements ----

    private static Node parseStatement(Cursor cursor) {
        return parseStatement(cursor, null);
    }

    private static Node parseStatement(Cursor cursor, Node parent) {
        Token code = cursor.peekCode();
        if (code.type() == TokenType.RETURN) {
            Node ret = new Node(NodeType.RETURN);
            take(cursor, ret); // 'return'
            if (!cursor.match(TokenType.SEMICOLON)) {
                parseExpressionInto(cursor, ret);
            }
            expectSemicolonOrTake(cursor, ret);
            if (parent != null) parent.add(ret);
            return ret;
        }
        if (code.type() == TokenType.IF) {
            return parseIfStatement(cursor, parent);
        }
        if (code.type() == TokenType.WHILE) {
            return parseWhile(cursor, parent);
        }
        if (code.type() == TokenType.DO) {
            return parseDo(cursor, parent);
        }
        if (code.type() == TokenType.FOR) {
            return parseFor(cursor, parent);
        }
        if (code.type() == TokenType.TRY) {
            return parseTry(cursor, parent);
        }
        if (code.type() == TokenType.SYNCHRONIZED) {
            Node block = new Node(NodeType.SYNCHRONIZED_BLOCK);
            take(cursor, block);
            take(cursor, block);
            parseExpressionInto(cursor, block);
            take(cursor, block);
            parseBlock(cursor, block);
            if (parent != null) {
                parent.add(block);
            }
            return block;
        }
        if (code.type() == TokenType.SWITCH) {
            return parseSwitch(cursor, parent);
        }
        if (code.type() == TokenType.BREAK || code.type() == TokenType.CONTINUE) {
            Node statement = new Node(code.type() == TokenType.BREAK ? NodeType.BREAK : NodeType.CONTINUE);
            take(cursor, statement);
            if (cursor.peekCode().type() == TokenType.IDENTIFIER) {
                take(cursor, statement);
            }
            expectSemicolonOrTake(cursor, statement);
            if (parent != null) {
                parent.add(statement);
            }
            return statement;
        }
        if (code.type() == TokenType.IDENTIFIER && code.text().equals("yield")
                       && codeTokenAfter(cursor, code).type() != TokenType.ASSIGNMENT) {
            Node yieldNode = new Node(NodeType.YIELD);
            take(cursor, yieldNode);
            parseExpressionInto(cursor, yieldNode);
            expectSemicolonOrTake(cursor, yieldNode);
            if (parent != null) {
                parent.add(yieldNode);
            }
            return yieldNode;
        }
        if (code.type() == TokenType.THROW) {
            Node statement = new Node(NodeType.THROW);
            take(cursor, statement); // 'throw'
            parseExpressionInto(cursor, statement);
            expectSemicolonOrTake(cursor, statement);
            if (parent != null) parent.add(statement);
            return statement;
        }
        if (code.type() == TokenType.OPENING_CURLY_BRACKET) {
            Node block = parseBlock(cursor, parent);
            return block;
        }
        if (isTypeDeclarationKeyword(cursor, code)) {
            // a local class, interface, enum or record declaration
            return parseTypeDeclaration(cursor, parent);
        }
        if (cursor.isModifier(code)) {
            // Reject member modifiers here so a field is not accepted as a statement.
            if (!code.text().equals("final") && !code.text().equals("@")) {
                Token t = code;
                while (cursor.isModifier(t) && !t.text().equals("@")) {
                    t = codeTokenAfter(cursor, t);
                }
                if (!isTypeDeclarationKeyword(cursor, t)) {
                    throw new ParseException("'" + code.text()
                            + "' declares a member: a statement starts with a type, final or an annotation",
                            code.line(), code.column());
                }
            }
            Node decl = parseVarDeclaration(cursor, parent);
            return decl;
        }
        Token afterCode = codeTokenAfter(cursor, code);
        if (code.type() == TokenType.IDENTIFIER && afterCode != null && afterCode.type() == TokenType.COLON) {
            Node labeled = new Node(NodeType.LABELED_STATEMENT);
            labeled.setPrincipal(code);
            take(cursor, labeled);
            take(cursor, labeled);
            parseStatement(cursor, labeled);
            if (parent != null) parent.add(labeled);
            return labeled;
        }
        if (isTypeStart(code)) {
            Node decl = tryParseDeclaration(cursor);
            if (decl != null) {
                if (parent != null) parent.add(decl);
                return decl;
            }
        }
        Node expr = new Node(NodeType.EXPRESSION_STATEMENT);
        expr.add(parseExpression(cursor));
        expectSemicolonOrTake(cursor, expr);
        if (parent != null) parent.add(expr);
        return expr;
    }

    private static Node parseBlock(Cursor cursor) {
        return parseBlock(cursor, null);
    }

    private static Node parseBlock(Cursor cursor, Node parent) {
        Node block = new Node(NodeType.BLOCK);
        take(cursor, block); // '{'
        while (!cursor.match(TokenType.CLOSING_CURLY_BRACKET)) {
            parseStatement(cursor, block);
        }
        take(cursor, block); // '}'
        if (parent != null) parent.add(block);
        return block;
    }

    // ---- Helpers ----

    private static void parseTypeReferenceInto(Cursor cursor, Node into) {
        Node type = new Node(NodeType.TYPE);
        parseTypeWithArgs(cursor, type);
        into.add(type);
    }

    private static boolean isTypeStart(Token t) {
        if (t.type() == TokenType.IDENTIFIER) return true;
        if (t.type().category() == TokenType.Category.KEYWORD) {
            String text = t.text();
            return !"static".equals(text) && !"final".equals(text) && !"abstract".equals(text) &&
                   !"public".equals(text) && !"private".equals(text) && !"protected".equals(text) &&
                   !"native".equals(text) && !"synchronized".equals(text) && !"strictfp".equals(text) &&
                   !"transient".equals(text) && !"volatile".equals(text) && !"record".equals(text);
        }
        return false;
    }

    private static void parseExpressionInto(Cursor cursor, Node into) {
        Node expr = parseExpression(cursor);
        into.add(expr);
    }

    // Parse if-then-else statement, nesting else-if chains
    private static Node parseIfStatement(Cursor cursor, Node parent) {
        Node ifNode = new Node(NodeType.IF);
        take(cursor, ifNode);
        // 'if'
        take(cursor, ifNode);
        // '('
        parseExpressionInto(cursor, ifNode);
        take(cursor, ifNode);
        // ')'
        Node thenNode = new Node(NodeType.THEN);
        ifNode.add(thenNode);
        parseStatement(cursor, thenNode);
        if (cursor.match(TokenType.ELSE)) {
            Node elseNode = new Node(NodeType.ELSE);
            ifNode.add(elseNode);
            take(cursor, elseNode); // 'else'
            parseStatement(cursor, elseNode);
        }
        if (parent != null) parent.add(ifNode);
        return ifNode;
    }

    // Parse type with optional type arguments
    private static void parseTypeWithArgs(Cursor cursor, Node into) {
        Token code = cursor.peekCode();
        if (code.type() == TokenType.INT || code.type() == TokenType.LONG || code.type() == TokenType.SHORT ||
            code.type() == TokenType.BYTE || code.type() == TokenType.CHAR || code.type() == TokenType.DOUBLE ||
            code.type() == TokenType.FLOAT || code.type() == TokenType.BOOLEAN || code.type() == TokenType.VOID) {
            if (into.type() == NodeType.TYPE) {
                into.setPrincipal(code);
            }
            take(cursor, into);
        } else if (code.type() == TokenType.IDENTIFIER) {
            Token last = code;
            take(cursor, into);
            while (cursor.match(TokenType.DOT)) {
                take(cursor, into);
                last = cursor.peekCode();
                take(cursor, into);
            }
            if (into.type() == NodeType.TYPE) {
                into.setPrincipal(last);
            }
        } else {
            throw new ParseException("expected type", code.line(), code.column());
        }
        // Handle type arguments
        if (cursor.match(TokenType.LESS_THAN) || cursor.match(TokenType.OPENING_ANGLE_BRACKET)) {
            parseTypeArguments(cursor, into);
        }
        // Handle array dimensions
        while (cursor.match(TokenType.OPENING_SQUARE_BRACKET)
                       && codeTokenAfter(cursor, cursor.peekCode()).type() == TokenType.CLOSING_SQUARE_BRACKET) {
            take(cursor, into);
            take(cursor, into); // ']'
        }
    }

    // Parse one variable declarator, including an optional initializer.
    private static void parseDeclarator(Cursor cursor, Node declaration) {
        Token name = cursor.peekCode();
        if (name.type() != TokenType.IDENTIFIER) {
            throw new ParseException("expected identifier", name.line(), name.column());
        }
        Node declarator = new Node(NodeType.VAR_DECLARATOR);
        declarator.setPrincipal(name);
        if (declaration.name() == null) {
            declaration.setPrincipal(name);
        }
        take(cursor, declarator);
        while (cursor.match(TokenType.OPENING_SQUARE_BRACKET)) {
            take(cursor, declarator);
            take(cursor, declarator);
        }
        if (cursor.match(TokenType.ASSIGNMENT)) {
            take(cursor, declarator);
            // an expression, or an array initializer { ... }
            parseElementValue(cursor, declarator);
        }
        declaration.add(declarator);
    }

    // Parse a variable declaration's type, declarators, and terminating semicolon.
    private static void parseDeclarationRest(Cursor cursor, Node declaration) {
        Node type = new Node(NodeType.TYPE);
        type.setPrincipal(cursor.peekCode());
        parseTypeWithArgs(cursor, type);
        declaration.add(type);
        parseDeclarator(cursor, declaration);
        while (cursor.match(TokenType.COMMA)) {
            take(cursor, declaration);
            parseDeclarator(cursor, declaration);
        }
        Token next = cursor.peekCode();
        if (next.type() != TokenType.SEMICOLON) {
            throw new ParseException("expected ';'", next.line(), next.column());
        }
        take(cursor, declaration);
    }

    // Parse a declaration that may begin with modifiers.
    private static Node parseVarDeclaration(Cursor cursor, Node parent) {
        Node declaration = new Node(NodeType.VAR_DECLARATION);
        takeModifiers(cursor, declaration);
        parseDeclarationRest(cursor, declaration);
        if (parent != null) {
            parent.add(declaration);
        }
        return declaration;
    }

    // Speculatively parse a declaration and restore the cursor if it is not one.
    private static Node tryParseDeclaration(Cursor cursor) {
        Cursor.Mark mark = cursor.mark();
        try {
            Node declaration = new Node(NodeType.VAR_DECLARATION);
            parseDeclarationRest(cursor, declaration);
            return declaration;
        } catch (ParseException notADeclaration) {
            cursor.rollback(mark);
            return null;
        }
    }

    // Parse while loop
    private static Node parseWhile(Cursor cursor, Node parent) {
        Node loop = new Node(NodeType.WHILE);
        take(cursor, loop); // 'while'
        take(cursor, loop); // '('
        parseExpressionInto(cursor, loop);
        take(cursor, loop); // ')'
        parseStatement(cursor, loop);
        if (parent != null) parent.add(loop);
        return loop;
    }

    // Parse do-while loop
    private static Node parseDo(Cursor cursor, Node parent) {
        Node loop = new Node(NodeType.DO);
        take(cursor, loop); // 'do'
        parseStatement(cursor, loop);
        take(cursor, loop); // 'while'
        take(cursor, loop); // '('
        parseExpressionInto(cursor, loop);
        take(cursor, loop); // ')'
        expectSemicolonOrTake(cursor, loop);
        if (parent != null) parent.add(loop);
        return loop;
    }

    // Try to parse enhanced-for variable header
    private static Node tryParseForEachVariable(Cursor cursor) {
        Cursor.Mark mark = cursor.mark();
        try {
            Node declaration = new Node(NodeType.VAR_DECLARATION);
            while (cursor.isModifier(cursor.peekCode())) {
                take(cursor, declaration);
            }
            Node type = new Node(NodeType.TYPE);
            type.setPrincipal(cursor.peekCode());
            parseTypeWithArgs(cursor, type);
            declaration.add(type);
            parseDeclarator(cursor, declaration);
            if (cursor.match(TokenType.COLON)) {
                return declaration;
            }
        } catch (ParseException e) {
            // not a for-each header
        }
        cursor.rollback(mark);
        return null;
    }

    // Parse for loop (basic and enhanced)
    private static Node parseFor(Cursor cursor, Node parent) {
        Node loop = new Node(NodeType.FOR);
        take(cursor, loop); // 'for'
        take(cursor, loop); // '('

        Node variable = tryParseForEachVariable(cursor);
        if (variable != null) {
            loop.retype(NodeType.FOR_EACH);
            loop.add(variable);
            take(cursor, loop); // ':'
            parseExpressionInto(cursor, loop);
        } else {
            // Basic for
            if (cursor.match(TokenType.SEMICOLON)) {
                take(cursor, loop);
            } else if (cursor.isModifier(cursor.peekCode())) {
                parseVarDeclaration(cursor, loop);
            } else {
                // an identifier starts a declaration (int i = 0) or an expression (i++, i = 0)
                Node decl = isTypeStart(cursor.peekCode()) ? tryParseDeclaration(cursor) : null;
                if (decl != null) {
                    loop.add(decl);
                } else {
                    parseExpressionInto(cursor, loop);
                    while (cursor.match(TokenType.COMMA)) {   // for (i = 0, j = n; ...)
                        take(cursor, loop);
                        parseExpressionInto(cursor, loop);
                    }
                    expectSemicolonOrTake(cursor, loop);
                }
            }

            if (!cursor.match(TokenType.SEMICOLON)) {
                parseExpressionInto(cursor, loop);
            }
            take(cursor, loop); // ';'

            while (!cursor.match(TokenType.CLOSING_ROUND_BRACKET)) {
                parseExpressionInto(cursor, loop);
                if (cursor.match(TokenType.COMMA)) {
                    take(cursor, loop);
                }
            }
        }

        take(cursor, loop); // ')'
        parseStatement(cursor, loop);
        if (parent != null) parent.add(loop);
        return loop;
    }

    // Count consecutive GREATER_THAN tokens that are adjacent (endOffset == next startOffset)
    private static int adjacentGreaterThans(Cursor cursor) {
        int pos = cursor.pos;
        while (pos < cursor.tokens.size() && cursor.tokens.get(pos).isTrivia()) {
            pos++;
        }
        int count = 0;
        int lastEnd = -1;
        while (count < 3 && pos < cursor.tokens.size()) {
            Token t = cursor.tokens.get(pos);
            if (!t.is(TokenType.GREATER_THAN)) break;
            if (count > 0 && lastEnd != t.startOffset()) break;
            count++;
            lastEnd = t.endOffset();
            pos++;
        }
        return count;
    }

    // Determine the operator type at the current cursor position, merging >> and >>>
    private static TokenType operatorAt(Cursor cursor) {
        int count = adjacentGreaterThans(cursor);
        if (count == 3) return TokenType.SHIFT_RIGHT_PRESERVING_SIGN;
        if (count == 2) return TokenType.SHIFT_RIGHT;
        return cursor.peekCode().type();
    }

    // Take the operator token, merging adjacent '>' into >> or >>> if needed
    private static Token takeOperator(Cursor cursor, Node into) {
        int count = adjacentGreaterThans(cursor);
        if (count < 2) {
            Token t = cursor.peekCode();
            take(cursor, into);
            return t;
        }
        // Merge trivia and the '>' tokens
        int pos = cursor.pos;
        while (pos < cursor.tokens.size() && cursor.tokens.get(pos).isTrivia()) {
            into.add(cursor.tokens.get(pos));
            pos++;
        }
        Token first = cursor.tokens.get(pos);
        TokenType shiftType = (count == 3) ? TokenType.SHIFT_RIGHT_PRESERVING_SIGN : TokenType.SHIFT_RIGHT;
        Token merged = new Token(shiftType, ">".repeat(count), first.line(), first.column(), first.startOffset());
        cursor.pos = pos + count;
        into.add(merged);
        return merged;
    }

    // Return precedence level for the given operator type
    private static int precedence(TokenType type) {
        switch (type) {
            case LOGICAL_OR: return 1;
            case LOGICAL_AND: return 2;
            case PIPE: return 3;
            case CARET: return 4;
            case AMPERSAND: return 5;
            case EQUALS: case NOT_EQUALS: return 6;
            case LESS_THAN: case GREATER_THAN: case LESS_THAN_OR_EQUAL: case GREATER_THAN_OR_EQUAL: return 7;
            case SHIFT_LEFT: case SHIFT_RIGHT: case SHIFT_RIGHT_PRESERVING_SIGN: return 8;
            case PLUS: case MINUS: return 9;
            case STAR: case SLASH: case PERCENT: return 10;
            default: return -1;
        }
    }

    // Parse an object creation expression.
    private static Node parseNewInstance(Cursor cursor) {
        Node creation = new Node(NodeType.NEW_INSTANCE);
        take(cursor, creation);
        Node type = new Node(NodeType.TYPE);
        type.setPrincipal(cursor.peekCode());
        parseTypeWithArgs(cursor, type);
        creation.add(type);
        if (cursor.match(TokenType.OPENING_SQUARE_BRACKET) || cursor.match(TokenType.OPENING_CURLY_BRACKET)) {
            creation.retype(NodeType.NEW_ARRAY);
            while (cursor.match(TokenType.OPENING_SQUARE_BRACKET)) {
                take(cursor, creation);
                if (!cursor.match(TokenType.CLOSING_SQUARE_BRACKET)) {
                    parseExpressionInto(cursor, creation);
                }
                take(cursor, creation);
            }
            if (cursor.match(TokenType.OPENING_CURLY_BRACKET)) {
                parseElementValue(cursor, creation);
            }
            return creation;
        }
        take(cursor, creation); // '('
        parseArguments(cursor, creation);
        take(cursor, creation);
        if (cursor.match(TokenType.OPENING_CURLY_BRACKET)) { creation.add(parseClassBody(cursor)); } // ')'
        return creation;
    }

    /** Parse a resource in a try-with-resources statement. */
    private static Node parseResource(Cursor cursor) {
        Cursor.Mark mark = cursor.mark();
        try {
            Node resource = new Node(NodeType.RESOURCE);
            Node declaration = new Node(NodeType.VAR_DECLARATION);
            while (cursor.isModifier(cursor.peekCode())) {
                take(cursor, declaration);
            }
            Node type = new Node(NodeType.TYPE);
            type.setPrincipal(cursor.peekCode());
            parseTypeWithArgs(cursor, type);
            declaration.add(type);
            parseDeclarator(cursor, declaration);
            resource.add(declaration);
            return resource;
        } catch (ParseException notADeclaration) {
            cursor.rollback(mark);
            Node resource = new Node(NodeType.RESOURCE);
            parseExpressionInto(cursor, resource);
            return resource;
        }
    }

    /** Parse a catch clause, supporting multi-catch. */
    private static Node parseCatch(Cursor cursor) {
        Node clause = new Node(NodeType.CATCH);
        take(cursor, clause);
        // 'catch'
        take(cursor, clause);
        // '('
        while (cursor.isModifier(cursor.peekCode())) {
            take(cursor, clause);
        }
        while (true) {
            Node type = new Node(NodeType.TYPE);
            type.setPrincipal(cursor.peekCode());
            parseTypeWithArgs(cursor, type);
            clause.add(type);
            if (!cursor.match(TokenType.PIPE)) {
                break;
            }
            take(cursor, clause); // '|'
        }
        take(cursor, clause);
        // variable name
        take(cursor, clause);
        // ')'
        parseBlock(cursor, clause);
        return clause;
    }

    /** Parse a try statement with resources, catch clauses, and finally. */
    private static Node parseTry(Cursor cursor, Node parent) {
        Node tryNode = new Node(NodeType.TRY);
        take(cursor, tryNode); // 'try'
        if (cursor.match(TokenType.OPENING_ROUND_BRACKET)) {
            take(cursor, tryNode); // '('
            while (!cursor.match(TokenType.CLOSING_ROUND_BRACKET)) {
                tryNode.add(parseResource(cursor));
                if (cursor.match(TokenType.SEMICOLON)) {
                    take(cursor, tryNode);
                }
            }
            take(cursor, tryNode); // ')'
        }
        parseBlock(cursor, tryNode);
        while (cursor.match(TokenType.CATCH)) {
            tryNode.add(parseCatch(cursor));
        }
        if (cursor.match(TokenType.FINALLY)) {
            Node finallyNode = new Node(NodeType.FINALLY);
            take(cursor, finallyNode); // 'finally'
            parseBlock(cursor, finallyNode);
            tryNode.add(finallyNode);
        }
        if (parent != null) parent.add(tryNode);
        return tryNode;
    }

    // Returns whether the token type is a primitive type.
    private static boolean isPrimitive(TokenType type) {
        return type == TokenType.BOOLEAN || type == TokenType.BYTE || type == TokenType.CHAR || type == TokenType.SHORT
                       || type == TokenType.INT || type == TokenType.LONG || type == TokenType.FLOAT
                       || type == TokenType.DOUBLE || type == TokenType.VOID;
    }

    // Finds the next non-trivia token after the supplied token.
    private static Token codeTokenAfter(Cursor cursor, Token token) {
        int index = cursor.tokens.indexOf(token) + 1;
        while (index < cursor.tokens.size()) {
            Token next = cursor.tokens.get(index++);
            if (!next.type().isTrivia()) {
                return next;
            }
        }
        return null;
    }

    // Checks whether the upcoming tokens begin a lambda expression.
    private static boolean isLambdaAhead(Cursor cursor) {
        Token first = cursor.peekCode();
        if (first.type() == TokenType.IDENTIFIER) {
            Token next = codeTokenAfter(cursor, first);
            return next != null && next.type() == TokenType.ARROW;
        }
        if (first.type() == TokenType.OPENING_ROUND_BRACKET && first.matchingBracket() != null) {
            Token next = codeTokenAfter(cursor, first.matchingBracket());
            return next != null && next.type() == TokenType.ARROW;
        }
        return false;
    }

    private static void parseLambdaParameter(Cursor cursor, Node lambda) {
        Node parameter = new Node(NodeType.PARAMETER);
        while (cursor.isModifier(cursor.peekCode())) {
            take(cursor, parameter);
        }
        Token first = cursor.peekCode();
        Token next = codeTokenAfter(cursor, first);
        boolean typed = (next != null && next.type() != TokenType.COMMA
                       && next.type() != TokenType.CLOSING_ROUND_BRACKET && next.type() != TokenType.ARROW);
        if (typed) {
            Node type = new Node(NodeType.TYPE);
            type.setPrincipal(first);
            parseTypeWithArgs(cursor, type);
            parameter.add(type);
        }
        parameter.setPrincipal(cursor.peekCode());
        take(cursor, parameter);
        lambda.add(parameter);
    }

    private static Node parseLambda(Cursor cursor) {
        Node lambda = new Node(NodeType.LAMBDA);
        if (cursor.match(TokenType.IDENTIFIER)) {
            parseLambdaParameter(cursor, lambda);
        } else {
            take(cursor, lambda);
            while (cursor.peekCode().type() != TokenType.CLOSING_ROUND_BRACKET) {
                parseLambdaParameter(cursor, lambda);
                if (cursor.peekCode().type() == TokenType.COMMA) {
                    take(cursor, lambda);
                }
            }
            take(cursor, lambda);
        }
        take(cursor, lambda);
        if (cursor.peekCode().type() == TokenType.OPENING_CURLY_BRACKET) {
            parseBlock(cursor, lambda);
        } else {
            parseExpressionInto(cursor, lambda);
        }
        return lambda;
    }

    // Try to parse a cast expression, returning null if it's not one.
    private static Node tryParseCast(Cursor cursor) {
        Cursor.Mark mark = cursor.mark();
        try {
            Node cast = new Node(NodeType.CAST);
            take(cursor, cast); // '('
            Token first = cursor.peekCode();
            Node type = new Node(NodeType.TYPE);
            type.setPrincipal(first);
            parseTypeWithArgs(cursor, type);
            cast.add(type);
            if (cursor.peekCode().type() == TokenType.CLOSING_ROUND_BRACKET) {
                Token next = codeTokenAfter(cursor, cursor.peekCode());
                boolean isCast = isPrimitive(first.type()) ||
                (next != null && (next.type().isLiteral() || next.type() == TokenType.IDENTIFIER ||
                    next.type() == TokenType.OPENING_ROUND_BRACKET || next.type() == TokenType.LOGICAL_NOT ||
                    next.type() == TokenType.TILDE || next.type() == TokenType.THIS ||
                    next.type() == TokenType.NEW || next.type() == TokenType.SUPER ||
                    next.type() == TokenType.NULL || next.type() == TokenType.TRUE ||
                    next.type() == TokenType.FALSE));
                if (isCast) {
                    take(cursor, cast); // ')'
                    cast.add(parseUnaryExpression(cursor));
                    return cast;
                }
            }
        } catch (ParseException e) {
            // Not a cast
        }
        cursor.rollback(mark);
        return null;
    }

    // Parse an annotation element value, including array initializers.
    private static void parseElementValue(Cursor cursor, Node into) {
        if (cursor.match(TokenType.OPENING_CURLY_BRACKET)) {
            Node array = new Node(NodeType.ARRAY_INITIALIZER);
            take(cursor, array);
            while (!cursor.match(TokenType.CLOSING_CURLY_BRACKET)) {
                parseElementValue(cursor, array);
                if (cursor.match(TokenType.COMMA)) {
                    take(cursor, array);
                }
            }
            take(cursor, array);
            into.add(array);
        } else {
            parseExpressionInto(cursor, into);
        }
    }

    // Parse an annotation and its optional element-value arguments.
    private static Node parseAnnotation(Cursor cursor) {
        Node annotation = new Node(NodeType.ANNOTATION);
        take(cursor, annotation);
        annotation.setPrincipal(cursor.peekCode());
        take(cursor, annotation);
        while (cursor.match(TokenType.DOT)) {
            take(cursor, annotation);
            annotation.setPrincipal(cursor.peekCode());
            take(cursor, annotation);
        }
        if (cursor.match(TokenType.OPENING_ROUND_BRACKET)) {
            take(cursor, annotation);
            while (!cursor.match(TokenType.CLOSING_ROUND_BRACKET)) {
                if (cursor.peekCode().type() == TokenType.IDENTIFIER
                        && codeTokenAfter(cursor, cursor.peekCode()).type() == TokenType.ASSIGNMENT) {
                    take(cursor, annotation);
                    take(cursor, annotation);
                }
                parseElementValue(cursor, annotation);
                if (cursor.match(TokenType.COMMA)) {
                    take(cursor, annotation);
                }
            }
            take(cursor, annotation);
        }
        return annotation;
    }

    // Preserve annotations as direct children while taking declaration modifiers.
    private static void takeModifiers(Cursor cursor, Node into) {
        while (true) {
            Token code = cursor.peekCode();
            if (cursor.match(TokenType.AT)) {
                into.add(parseAnnotation(cursor));
            } else if (cursor.isModifier(code)) {
                take(cursor, into);
            } else if (code.type() == TokenType.IDENTIFIER
                    && "sealed".equals(code.text())
                    && isTypeDeclarationKeyword(cursor, codeTokenAfter(cursor, code))) {
                take(cursor, into);
            } else if (code.type() == TokenType.IDENTIFIER
                    && "non".equals(code.text())
                    && codeTokenAfter(cursor, code).type() == TokenType.MINUS) {
                take(cursor, into); // 'non'
                take(cursor, into); // '-'
                take(cursor, into); // 'sealed'
            } else {
                break;
            }
        }
    }

    // Recognize class, interface, enum, and contextual record declaration keywords.
    private static boolean isTypeDeclarationKeyword(Cursor cursor, Token t) {
        return t.type() == TokenType.CLASS
                || t.type() == TokenType.INTERFACE
                || t.type() == TokenType.ENUM
                || (t.type() == TokenType.IDENTIFIER
                        && "record".equals(t.text())
                        && codeTokenAfter(cursor, t).type() == TokenType.IDENTIFIER);
    }

    // Parse a type declaration after its modifiers have been consumed.
    private static Node parseTypeDeclarationRest(Cursor cursor, Node type, Node parent) {
        Token code = cursor.peekCode();
        if (code.type() == TokenType.CLASS) {
            type.retype(NodeType.CLASS_DECLARATION);
            take(cursor, type); // 'class'
        } else if (code.type() == TokenType.INTERFACE) {
            type.retype(NodeType.INTERFACE_DECLARATION);
            take(cursor, type); // 'interface'
        } else if (code.type() == TokenType.ENUM) {
            type.retype(NodeType.ENUM_DECLARATION);
            take(cursor, type); // 'enum'
        } else if (code.type() == TokenType.IDENTIFIER && "record".equals(code.text())) {
            type.retype(NodeType.RECORD_DECLARATION);
            take(cursor, type); // 'record'
        } else {
            throw new ParseException("expected type declaration", code.line(), code.column());
        }
        // Set principal before taking name, so name() works
        type.setPrincipal(cursor.peekCode());
        take(cursor, type); // name

        if (cursor.match(TokenType.LESS_THAN)) {
            take(cursor, type); // '<'
            parseTypeParameters(cursor, type);
            take(cursor, type); // '>'
        }

        if (type.type() == NodeType.RECORD_DECLARATION && cursor.match(TokenType.OPENING_ROUND_BRACKET)) {
            take(cursor, type); // '('
            while (!cursor.match(TokenType.CLOSING_ROUND_BRACKET)) {
                Node component = new Node(NodeType.RECORD_COMPONENT);
                takeModifiers(cursor, component);
                Node componentType = new Node(NodeType.TYPE);
                componentType.setPrincipal(cursor.peekCode());
                parseTypeWithArgs(cursor, componentType);
                component.add(componentType);
                if (cursor.match(TokenType.ELLIPSIS)) {
                    take(cursor, component);
                }
                component.setPrincipal(cursor.peekCode());
                take(cursor, component); // component name
                type.add(component);
                if (cursor.match(TokenType.COMMA)) {
                    take(cursor, type); // ','
                }
            }
            take(cursor, type); // ')'
        }

        if (cursor.match(TokenType.EXTENDS)) {
            take(cursor, type); // 'extends'
            parseTypeReferenceInto(cursor, type);
            while (cursor.match(TokenType.COMMA)) {
                take(cursor, type); // ','
                parseTypeReferenceInto(cursor, type);
            }
        }

        if (cursor.match(TokenType.IMPLEMENTS)) {
            take(cursor, type); // 'implements'
            parseTypeReferenceInto(cursor, type);
            while (cursor.match(TokenType.COMMA)) {
                take(cursor, type); // ','
                parseTypeReferenceInto(cursor, type);
            }
        }

        Token next = cursor.peekCode();
        if (next.type() == TokenType.IDENTIFIER && "permits".equals(next.text())) {
            take(cursor, type); // 'permits'
            parseTypeReferenceInto(cursor, type);
            while (cursor.match(TokenType.COMMA)) {
                take(cursor, type); // ','
                parseTypeReferenceInto(cursor, type);
            }
        }

        Node body = type.type() == NodeType.ENUM_DECLARATION ? parseEnumBody(cursor) : parseClassBody(cursor);
        type.add(body);
        parent.add(type);
        return type;
    }

    /** Check if a token type is an assignment operator. */
    private static boolean isAssignmentOperator(TokenType type) {
        return type == TokenType.ASSIGNMENT ||
               type == TokenType.PLUS_ASSIGNMENT ||
               type == TokenType.MINUS_ASSIGNMENT ||
               type == TokenType.STAR_ASSIGNMENT ||
               type == TokenType.SLASH_ASSIGNMENT ||
               type == TokenType.PERCENT_ASSIGNMENT ||
               type == TokenType.AND_ASSIGNMENT ||
               type == TokenType.OR_ASSIGNMENT ||
               type == TokenType.XOR_ASSIGNMENT ||
               type == TokenType.SHIFT_LEFT_ASSIGNMENT ||
               type == TokenType.SHIFT_RIGHT_ASSIGNMENT ||
               type == TokenType.SHIFT_RIGHT_PRESERVING_SIGN_ASSIGNMENT;
    }

    /** Parse an enum body: constants, optional semicolon, then members. */
    private static Node parseEnumBody(Cursor cursor) {
        Node body = new Node(NodeType.CLASS_BODY);
        take(cursor, body); // '{'
        while (cursor.peekCode().type() != TokenType.SEMICOLON
                       && cursor.peekCode().type() != TokenType.CLOSING_CURLY_BRACKET) {
            Node constant = new Node(NodeType.ENUM_CONSTANT);
            takeModifiers(cursor, constant);
            constant.setPrincipal(cursor.peekCode());
            take(cursor, constant); // name
            if (cursor.match(TokenType.OPENING_ROUND_BRACKET)) {
                take(cursor, constant); // '('
                parseArguments(cursor, constant);
                take(cursor, constant); // ')'
            }
            if (cursor.match(TokenType.OPENING_CURLY_BRACKET)) {
                constant.add(parseClassBody(cursor));
            }
            body.add(constant);
            if (cursor.match(TokenType.COMMA)) {
                take(cursor, body); // ','
            }
        }
        if (cursor.match(TokenType.SEMICOLON)) {
            take(cursor, body); // ';'
        }
        while (cursor.peekCode().type() != TokenType.CLOSING_CURLY_BRACKET) {
            parseMember(cursor, body);
        }
        take(cursor, body); // '}'
        return body;
    }

    // Check whether a type pattern starts at the current token.
    private static boolean isPatternAhead(Cursor cursor) {
        Cursor.Mark mark = cursor.mark();
        try {
            Node scratch = new Node(NodeType.TYPE);
            parseTypeWithArgs(cursor, scratch);
            Token next = cursor.peekCode();
            return next.type() == TokenType.IDENTIFIER || next.type() == TokenType.OPENING_ROUND_BRACKET;
        } catch (ParseException e) {
            return false;
        } finally {
            cursor.rollback(mark);
        }
    }

    // Parse a type pattern or record pattern.
    private static Node parsePattern(Cursor cursor) {
        Node pattern = new Node(NodeType.TYPE_PATTERN);
        Node typeNode = new Node(NodeType.TYPE);
        parseTypeWithArgs(cursor, typeNode);
        pattern.add(typeNode);
        pattern.setPrincipal(typeNode.firstCodeToken());
        if (cursor.match(TokenType.OPENING_ROUND_BRACKET)) {
            pattern.retype(NodeType.RECORD_PATTERN);
            take(cursor, pattern);
            while (!cursor.match(TokenType.CLOSING_ROUND_BRACKET)) {
                pattern.add(parsePattern(cursor));
                if (cursor.match(TokenType.COMMA)) {
                    take(cursor, pattern);
                } else {
                    break;
                }
            }
            take(cursor, pattern);
        } else {
            Token binder = cursor.peekCode();
            pattern.setPrincipal(binder);
            take(cursor, pattern);
        }
        return pattern;
    }

    // Parse case labels and an optional when guard.
    private static void parseCaseLabels(Cursor cursor, Node group) {
        takeExpected(cursor, group, TokenType.CASE, "at a switch label");
        while (true) {
            Token token = cursor.peekCode();
            if (token.type() == TokenType.DEFAULT || token.type() == TokenType.NULL) {
                take(cursor, group);
            } else if (isPatternAhead(cursor)) {
                group.add(parsePattern(cursor));
            } else {
                Token after = codeTokenAfter(cursor, token);
                if (token.type() == TokenType.IDENTIFIER && (after.type() == TokenType.ARROW
                               || after.type() == TokenType.COMMA || after.type() == TokenType.COLON)) {
                    Node idExpr = new Node(NodeType.IDENTIFIER_EXPRESSION);
                    idExpr.setPrincipal(token);
                    take(cursor, idExpr);
                    group.add(idExpr);
                } else {
                    parseExpressionInto(cursor, group);
                }
            }
            if (!cursor.match(TokenType.COMMA)) {
                break;
            }
            take(cursor, group);
        }
        Token next = cursor.peekCode();
        if (next.type() == TokenType.IDENTIFIER && next.text().equals("when")) {
            take(cursor, group);
            parseExpressionInto(cursor, group);
        }
    }

    // Parse the statements or expression belonging to a case group.
    private static void parseCaseBody(Cursor cursor, Node group, boolean arrow) {
        if (arrow) {
            Token token = cursor.peekCode();
            if (token.type() == TokenType.OPENING_CURLY_BRACKET) {
                parseBlock(cursor, group);
            } else if (token.type() == TokenType.THROW) {
                parseStatement(cursor, group);
            } else {
                parseExpressionInto(cursor, group);
                expectSemicolonOrTake(cursor, group);
            }
        } else {
            while (true) {
                Token token = cursor.peekCode();
                if (token.type() == TokenType.CASE || token.type() == TokenType.DEFAULT
                               || token.type() == TokenType.CLOSING_CURLY_BRACKET) {
                    break;
                }
                parseStatement(cursor, group);
            }
        }
    }

    // Parse one case group and report whether it uses an arrow.
    private static boolean parseSwitchCase(Cursor cursor, Node switchNode) {
        Node group = new Node(NodeType.CASE);
        if (cursor.peekCode().type() == TokenType.DEFAULT) {
            take(cursor, group);
        } else {
            parseCaseLabels(cursor, group);
        }
        boolean arrow = cursor.peekCode().type() == TokenType.ARROW;
        takeExpected(cursor, group, arrow ? TokenType.ARROW : TokenType.COLON, "after the case label");
        parseCaseBody(cursor, group, arrow);
        switchNode.add(group);
        return arrow;
    }

    // Parse a switch statement or switch expression.
    private static Node parseSwitch(Cursor cursor, Node parent) {
        Node switchNode = new Node(NodeType.SWITCH);
        take(cursor, switchNode);
        takeExpected(cursor, switchNode, TokenType.OPENING_ROUND_BRACKET, "after 'switch'");
        parseExpressionInto(cursor, switchNode);
        takeExpected(cursor, switchNode, TokenType.CLOSING_ROUND_BRACKET, "after the switch selector");
        takeExpected(cursor, switchNode, TokenType.OPENING_CURLY_BRACKET, "to open the switch body");
        boolean arrows = false;
        while (cursor.peekCode().type() != TokenType.CLOSING_CURLY_BRACKET) {
            arrows |= parseSwitchCase(cursor, switchNode);
        }
        take(cursor, switchNode);
        if (arrows) {
            switchNode.retype(NodeType.SWITCH_FUNCTION);
        }
        if (parent != null) {
            parent.add(switchNode);
        }
        return switchNode;
    }

    // Wrap the operand in the postfix operators, member accesses and calls that follow it.
    private static Node parseSelectors(Cursor cursor, Node node) {
        while (true) {
            Token next = cursor.peekCode();
            if (next.type() == TokenType.OPENING_SQUARE_BRACKET
                           && codeTokenAfter(cursor, next).type() != TokenType.CLOSING_SQUARE_BRACKET) {
                Node access = new Node(NodeType.ARRAY_ACCESS);
                access.add(node);
                take(cursor, access);
                parseExpressionInto(cursor, access);
                take(cursor, access);
                node = access;
                continue;
            }
            if (next.type() == TokenType.DOT && codeTokenAfter(cursor, next) != null
                           && codeTokenAfter(cursor, next).type() == TokenType.NEW) {
                // a qualified creation, outer.new Inner(): the receiver and the '.' go first in the creation
                Node dot = new Node(NodeType.FRAGMENT);
                take(cursor, dot);
                Node creation = parseNewInstance(cursor);
                creation.insertPart(0, node);
                int index = 1;
                for (Object part : dot.parts()) {
                    creation.insertPart(index++, part);
                }
                node = creation;
                continue;
            }
            if (next.type() == TokenType.INCREMENT || next.type() == TokenType.DECREMENT) {
                Node post = new Node(NodeType.POSTFIX_EXPRESSION);
                post.add(node);
                take(cursor, post);
                node = post;
            } else if (next.type() == TokenType.DOUBLE_COLON) {
                Node reference = new Node(NodeType.METHOD_REFERENCE);
                reference.add(node);
                take(cursor, reference);
                if (cursor.match(TokenType.LESS_THAN)) {
                    parseTypeArguments(cursor, reference);
                }
                Token nameToken = cursor.peekCode();
                reference.setPrincipal(nameToken);
                take(cursor, reference);
                node = reference;
            } else if (next.type() == TokenType.DOT) {
                Node access = new Node(NodeType.FIELD_ACCESS);
                access.add(node);
                take(cursor, access); // '.'
                if (cursor.match(TokenType.CLASS)) {
                    access.retype(NodeType.CLASS_LITERAL);
                    Token classToken = cursor.peekCode();
                    access.setPrincipal(classToken);
                    take(cursor, access); // 'class'
                } else {
                    if (cursor.match(TokenType.LESS_THAN)) {
                        parseTypeArguments(cursor, access);
                    }
                    Token nameToken = cursor.peekCode();
                    access.setPrincipal(nameToken);
                    take(cursor, access);
                    if (nameToken.type() == TokenType.THIS) {
                        access.retype(NodeType.THIS_EXPRESSION);
                    } else if (nameToken.type() == TokenType.SUPER) {
                        access.retype(NodeType.SUPER_EXPRESSION);
                    } // name
                    if (cursor.match(TokenType.OPENING_ROUND_BRACKET)) {
                        access.retype(NodeType.METHOD_CALL);
                        take(cursor, access); // '('
                        parseArguments(cursor, access);
                        take(cursor, access); // ')'
                    }
                }
                node = access;
            } else if (next.type() == TokenType.OPENING_SQUARE_BRACKET
                           && codeTokenAfter(cursor, next).type() == TokenType.CLOSING_SQUARE_BRACKET) {
                take(cursor, node); // '['
                take(cursor, node); // ']'
            } else {
                return node;
            }
        }
    }

    // Parse type arguments after '<'.
    private static void parseTypeArguments(Cursor cursor, Node into) {
        Token opening = cursor.peekCode();
        cursor.mutate(opening);
        opening.retype(TokenType.OPENING_ANGLE_BRACKET);
        take(cursor, into); // '<'
        while (!(cursor.match(TokenType.GREATER_THAN) || cursor.match(TokenType.CLOSING_ANGLE_BRACKET))) {
            Node argType = new Node(NodeType.TYPE);
            argType.setPrincipal(cursor.peekCode());
            if (cursor.match(TokenType.QUESTION)) {
                take(cursor, argType);
                if (cursor.match(TokenType.EXTENDS) || cursor.match(TokenType.SUPER)) {
                    take(cursor, argType);
                    // the bound is a type of its own, inside the ?
                    Node bound = new Node(NodeType.TYPE);
                    bound.setPrincipal(cursor.peekCode());
                    parseTypeWithArgs(cursor, bound);
                    argType.add(bound);
                }
            } else {
                parseTypeWithArgs(cursor, argType);
            }
            into.add(argType);
            if (cursor.match(TokenType.COMMA)) {
                take(cursor, into);
            }
        }
        Token closing = cursor.peekCode();
        cursor.mutate(closing);
        closing.retype(TokenType.CLOSING_ANGLE_BRACKET);
        cursor.setMatchingBracket(opening, closing);
        cursor.setMatchingBracket(closing, opening);
        take(cursor, into); // '>'
    }

    /** Returns true if token is the keyword for node's type. */
    private static boolean isKeywordOf(NodeType node, TokenType token) {
        return switch (node) {
            case IF -> token == TokenType.IF;
            case FOR, FOR_EACH -> token == TokenType.FOR;
            case WHILE -> token == TokenType.WHILE;
            case DO -> token == TokenType.DO;
            case RETURN -> token == TokenType.RETURN;
            case THROW -> token == TokenType.THROW;
            case BREAK -> token == TokenType.BREAK;
            case CONTINUE -> token == TokenType.CONTINUE;
            case TRY -> token == TokenType.TRY;
            case CATCH -> token == TokenType.CATCH;
            case FINALLY -> token == TokenType.FINALLY;
            case SWITCH, SWITCH_FUNCTION -> token == TokenType.SWITCH;
            case CASE -> token == TokenType.CASE || token == TokenType.DEFAULT;
            case SYNCHRONIZED_BLOCK -> token == TokenType.SYNCHRONIZED;
            case NEW_INSTANCE, NEW_ARRAY -> token == TokenType.NEW;
            case LAMBDA -> token == TokenType.ARROW;
            case POSTFIX_EXPRESSION -> token == TokenType.INCREMENT || token == TokenType.DECREMENT;
            default -> false;
        };
    }

    // Take the next code token, which must be of the given type; otherwise the input is a parse error.
    private static void takeExpected(Cursor cursor, Node into, TokenType type, String context) {
        Token next = cursor.peekCode();
        if (next.type() != type) {
            throw new ParseException("expected '" + type.fixedText() + "' " + context, next.line(), next.column());
        }
        take(cursor, into);
    }
}
