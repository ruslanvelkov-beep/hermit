package hermit.analysis.tree;

/**
 * Every kind of {@link Node} the parser builds. The tree is a CONCRETE syntax tree: every token of the
 * source (including whitespace/comment trivia) lands in exactly one node's parts, so {@code toString()}
 * reproduces the file. Comments are preserved as trivia {@code Token}s attached to the nearest node —
 * they act as separators during parsing but round-trip on output (no wrapper node needed).
 */
public enum NodeType {

    // ---- structure --------------------------------------------------------------------------------
    COMPILATION_UNIT,
    PACKAGE_DECLARATION,
    IMPORT_DECLARATION,           // incl. static and module imports; wildcard flag via tokens
    MODULE_DECLARATION,
    MODULE_DIRECTIVE,             // requires / exports / opens / uses / provides
    CLASS_DECLARATION,
    INTERFACE_DECLARATION,
    ENUM_DECLARATION,
    RECORD_DECLARATION,
    ANNOTATION_TYPE_DECLARATION,  // @interface
    IMPLICIT_CLASS,               // compact source file (JEP 512): members with no enclosing declaration
    CLASS_BODY,                   // { members } — one per type declaration (and per anonymous class)
    ENUM_CONSTANT,
    FIELD_DECLARATION,
    METHOD_DECLARATION,
    CONSTRUCTOR_DECLARATION,
    COMPACT_CONSTRUCTOR,          // record compact constructor: modifiers Name { ... }
    INITIALIZER_BLOCK,            // static / instance initializer
    PARAMETER,
    RECORD_COMPONENT,
    TYPE_PARAMETER,
    TYPE,                         // any type reference: primitive, (qualified) class type, arrays, wildcards
    ANNOTATION,

    // ---- statements -------------------------------------------------------------------------------
    BLOCK,
    EMPTY_STATEMENT,
    EXPRESSION_STATEMENT,
    VAR_DECLARATION,              // local variable declaration: modifiers TYPE declarator(, declarator)* ;
    VAR_DECLARATOR,               // one name [dims] [= initializer]
    IF,                           // 'if' '(' condition ')' THEN [ELSE]
    THEN,                         // the if-branch statement
    ELSE,                         // 'else' + statement (which may itself be an IF — else-if chains)
    FOR,                          // basic for
    FOR_EACH,                     // enhanced for — contains a VAR_DECLARATION for the loop variable
    WHILE,
    DO,
    SWITCH,                       // old-style (colon labels)
    SWITCH_FUNCTION,              // new-style (arrow labels), statement or expression
    CASE,                         // one case/default group incl. its body
    TRY,
    RESOURCE,                     // one try-with-resources resource
    CATCH,
    FINALLY,
    RETURN,
    THROW,
    BREAK,
    CONTINUE,
    YIELD,
    ASSERT,
    SYNCHRONIZED_BLOCK,
    LABELED_STATEMENT,

    // ---- expressions ------------------------------------------------------------------------------
    IDENTIFIER_EXPRESSION,        // a bare name used as an expression
    LITERAL,                      // any literal token (number/char/string/text block/true/false/null)
    THIS_EXPRESSION,
    SUPER_EXPRESSION,
    PARENTHESIZED,
    METHOD_CALL,                  // receiver? '.'? [typeArgs] name ( args ) — also this(...)/super(...)
    FIELD_ACCESS,                 // receiver '.' name
    ARRAY_ACCESS,                 // receiver '[' index ']'
    CLASS_LITERAL,                // Type.class / int[].class / void.class
    ASSIGNMENT,                   // one node type for ALL assignment operators (the operator is the token)
    TERNARY,                      // cond ? a : b
    BINARY_EXPRESSION,            // one node type for all infix operators (operator = principal token)
    UNARY_EXPRESSION,             // prefix + - ! ~ ++ --
    POSTFIX_EXPRESSION,           // postfix ++ --
    CAST,                         // ( Type [& Type]* ) operand
    INSTANCEOF,                   // expr instanceof (Type | pattern)
    NEW_INSTANCE,                 // new [typeArgs] Type ( args ) [anonymous CLASS_BODY]
    NEW_ARRAY,                    // new Type dims [initializer]
    ARRAY_INITIALIZER,            // { v, v, ... }
    LAMBDA,                       // params -> (expression | block)
    METHOD_REFERENCE,             // receiver :: [typeArgs] (name | new)
    TYPE_PATTERN,                 // Type binder (instanceof / case patterns)
    RECORD_PATTERN,               // Type ( pattern, ... )

    // ---- internal ---------------------------------------------------------------------------------
    FRAGMENT;                     // scratch container while the final node kind is still unknown

    public boolean isTypeDeclaration() {
        return this == CLASS_DECLARATION || this == INTERFACE_DECLARATION || this == ENUM_DECLARATION
                || this == RECORD_DECLARATION || this == ANNOTATION_TYPE_DECLARATION || this == IMPLICIT_CLASS;
    }

    public boolean isStatement() {
        return switch (this) {
            case BLOCK, EMPTY_STATEMENT, EXPRESSION_STATEMENT, VAR_DECLARATION, IF, FOR, FOR_EACH, WHILE, DO,
                    SWITCH, SWITCH_FUNCTION, TRY, RETURN, THROW, BREAK, CONTINUE, YIELD, ASSERT,
                    SYNCHRONIZED_BLOCK, LABELED_STATEMENT -> true;
            default -> false;
        };
    }
}
