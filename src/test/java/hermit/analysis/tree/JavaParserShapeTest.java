package hermit.analysis.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The parse tree's shape matches the reference parser's for the same source: node types, their nesting and their
 * principal names. The symbol layer, the code graph and the tree editor are ported from code that reads these shapes,
 * so each expected text below is the reference parser's dump of the source, positions removed.
 */
class JavaParserShapeTest {

    /** The tree's shape (node types and principal names, one per line, indented), without positions. */
    private static String shape(String source) {
        StringBuilder sb = new StringBuilder();
        for (String line : ParseTree.parse(source).root().dump().split("\n")) {
            sb.append(line.replaceAll("@[0-9:]+", "").stripTrailing()).append('\n');
        }
        return sb.toString().strip();
    }

    @Test
    void typeDeclarationsAndMembersHaveTheReferenceShape() {
        assertEquals("""
                COMPILATION_UNIT
                  CLASS_DECLARATION 'A'
                    TYPE_PARAMETER 'T'
                    TYPE 'B'
                      TYPE 'T'
                    TYPE 'C'
                    CLASS_BODY
                      FIELD_DECLARATION 'f'
                        TYPE 'int'
                        VAR_DECLARATOR 'f'
                        VAR_DECLARATOR 'g'
                          LITERAL '1'
                      METHOD_DECLARATION 'm'
                        TYPE 'void'
                        PARAMETER 'p'
                          TYPE 'int'
                        PARAMETER 'l'
                          TYPE 'List'
                            TYPE 'String'
                        BLOCK""", shape("class A<T> extends B<T> implements C { int f, g = 1; void m(int p, java.util.List<String> l) { } }"));
    }

    @Test
    void statementsHaveTheReferenceShape() {
        assertEquals("""
                COMPILATION_UNIT
                  CLASS_DECLARATION 'S'
                    CLASS_BODY
                      METHOD_DECLARATION 'm'
                        TYPE 'void'
                        PARAMETER 'o'
                          TYPE 'Object'
                        BLOCK
                          EXPRESSION_STATEMENT
                            METHOD_CALL 'use'
                              IDENTIFIER_EXPRESSION 'o'
                          IF 'if'
                            INSTANCEOF 'instanceof'
                              IDENTIFIER_EXPRESSION 'o'
                              TYPE_PATTERN 's'
                                TYPE 'String'
                            THEN
                              BLOCK
                          FOR 'for'
                            VAR_DECLARATION 'i'
                              TYPE 'int'
                              VAR_DECLARATOR 'i'
                                LITERAL '0'
                            BINARY_EXPRESSION '<'
                              IDENTIFIER_EXPRESSION 'i'
                              LITERAL '1'
                            POSTFIX_EXPRESSION '++'
                              IDENTIFIER_EXPRESSION 'i'
                            BLOCK
                          RETURN 'return'""", shape("class S { void m(Object o) { use(o); if (o instanceof String s) { } for (int i = 0; i < 1; i++) { } return; } }"));
    }

    @Test
    void resourcesLambdasCreationsAndCasesHaveTheReferenceShape() {
        assertEquals("""
                COMPILATION_UNIT
                  CLASS_DECLARATION 'T'
                    CLASS_BODY
                      METHOD_DECLARATION 'm'
                        TYPE 'void'
                        BLOCK
                          TRY 'try'
                            RESOURCE
                              VAR_DECLARATION 'r'
                                TYPE 'var'
                                VAR_DECLARATOR 'r'
                                  METHOD_CALL 'open'
                            BLOCK
                            CATCH 'catch'
                              TYPE 'IllegalStateException'
                              BLOCK
                          VAR_DECLARATION 'x'
                            TYPE 'Runnable'
                            VAR_DECLARATOR 'x'
                              LAMBDA '->'
                                BLOCK
                          VAR_DECLARATION 'y'
                            TYPE 'Object'
                            VAR_DECLARATOR 'y'
                              NEW_INSTANCE 'new'
                                TYPE 'Object'
                          VAR_DECLARATION 'z'
                            TYPE 'int'
                            VAR_DECLARATOR 'z'
                              SWITCH_FUNCTION 'switch'
                                LITERAL '1'
                                CASE 'case'
                                  LITERAL '1'
                                  LITERAL '2'
                                CASE 'default'
                                  LITERAL '3'""", shape("class T { void m() { try (var r = open()) { } catch (IllegalStateException e) { } Runnable x = () -> { }; Object y = new Object(); int z = switch (1) { case 1 -> 2; default -> 3; }; } }"));
    }

    @Test
    void thisSuperAndLabelsHaveTheReferenceShape() {
        assertEquals("""
                COMPILATION_UNIT
                  CLASS_DECLARATION 'K'
                    TYPE 'B'
                    CLASS_BODY
                      FIELD_DECLARATION 'f'
                        TYPE 'int'
                        VAR_DECLARATOR 'f'
                      CONSTRUCTOR_DECLARATION 'K'
                        PARAMETER 'f'
                          TYPE 'int'
                        BLOCK
                          EXPRESSION_STATEMENT
                            METHOD_CALL 'this'
                              IDENTIFIER_EXPRESSION 'f'
                              LITERAL '1'
                      CONSTRUCTOR_DECLARATION 'K'
                        PARAMETER 'f'
                          TYPE 'int'
                        PARAMETER 'g'
                          TYPE 'int'
                        BLOCK
                          EXPRESSION_STATEMENT
                            METHOD_CALL 'super'
                              IDENTIFIER_EXPRESSION 'f'
                          EXPRESSION_STATEMENT
                            ASSIGNMENT '='
                              FIELD_ACCESS 'f'
                                THIS_EXPRESSION 'this'
                              IDENTIFIER_EXPRESSION 'f'
                          VAR_DECLARATION 'o'
                            TYPE 'Object'
                            VAR_DECLARATOR 'o'
                              THIS_EXPRESSION 'this'
                          EXPRESSION_STATEMENT
                            METHOD_CALL 'm'
                              SUPER_EXPRESSION 'super'
                          EXPRESSION_STATEMENT
                            ASSIGNMENT '='
                              FIELD_ACCESS 'f'
                                THIS_EXPRESSION 'this'
                                  IDENTIFIER_EXPRESSION 'K'
                              LITERAL '2'
                          EXPRESSION_STATEMENT
                            METHOD_CALL 'm'
                              SUPER_EXPRESSION 'super'
                                IDENTIFIER_EXPRESSION 'K'
                          LABELED_STATEMENT 'outer'
                            FOR 'for'
                              BLOCK
                                BREAK 'break'""", shape("class K extends B { int f; K(int f) { this(f, 1); } K(int f, int g) { super(f); this.f = f; Object o = this; super.m(); K.this.f = 2; K.super.m(); outer: for (;;) { break outer; } } }"));
    }

    @Test
    void genericMethodsAndConstructorsHaveTheReferenceShape() {
        assertEquals("""
                COMPILATION_UNIT
                  CLASS_DECLARATION 'G'
                    CLASS_BODY
                      METHOD_DECLARATION 'id'
                        TYPE_PARAMETER 'T'
                        TYPE 'T'
                        PARAMETER 't'
                          TYPE 'T'
                        BLOCK
                          RETURN 'return'
                            IDENTIFIER_EXPRESSION 't'
                      CONSTRUCTOR_DECLARATION 'G'
                        TYPE_PARAMETER 'K'
                        TYPE_PARAMETER 'V'
                          TYPE 'Comparable'
                            TYPE 'V'
                        PARAMETER 'k'
                          TYPE 'K'
                        PARAMETER 'v'
                          TYPE 'V'
                        BLOCK
                      METHOD_DECLARATION 'each'
                        TYPE_PARAMETER 'E'
                        TYPE 'void'
                        PARAMETER 'l'
                          TYPE 'List'
                            TYPE 'E'
                        BLOCK""", shape("class G { <T> T id(T t) { return t; } <K, V extends Comparable<V>> G(K k, V v) { } static <E> void each(java.util.List<E> l) { } }"));
    }

    @Test
    void arrayDimensionsAfterNamesAndRecordVarargsHaveTheReferenceShape() {
        assertEquals("""
                COMPILATION_UNIT
                  CLASS_DECLARATION 'D'
                    CLASS_BODY
                      FIELD_DECLARATION 'a'
                        TYPE 'int'
                        VAR_DECLARATOR 'a'
                        VAR_DECLARATOR 'b'
                      METHOD_DECLARATION 'm'
                        TYPE 'void'
                        PARAMETER 'args'
                          TYPE 'String'
                        BLOCK
                          VAR_DECLARATION 'x'
                            TYPE 'int'
                            VAR_DECLARATOR 'x'
                              ARRAY_INITIALIZER
                                LITERAL '1'
                      RECORD_DECLARATION 'P'
                        RECORD_COMPONENT 'x'
                          TYPE 'int'
                        RECORD_COMPONENT 'rest'
                          TYPE 'Object'
                        CLASS_BODY""", shape("class D { int a[], b; void m(String args[]) { int x[] = {1}; } record P(int x, Object... rest) { } }"));
    }

    @Test
    void qualifiedInstanceCreationHasTheReferenceShape() {
        assertEquals("""
                COMPILATION_UNIT
                  CLASS_DECLARATION 'C'
                    CLASS_BODY
                      METHOD_DECLARATION 'm'
                        TYPE 'void'
                        PARAMETER 'o'
                          TYPE 'Outer'
                        BLOCK
                          VAR_DECLARATION 'a'
                            TYPE 'Object'
                            VAR_DECLARATOR 'a'
                              NEW_ARRAY 'new'
                                TYPE 'String'
                                ARRAY_INITIALIZER
                                  LITERAL '"a"'
                          VAR_DECLARATION 'b'
                            TYPE 'Object'
                            VAR_DECLARATOR 'b'
                              NEW_INSTANCE 'new'
                                IDENTIFIER_EXPRESSION 'o'
                                TYPE 'Inner'""", shape("class C { void m(Outer o) { Object a = new String[] {\"a\"}; Object b = o.new Inner(); } }"));
    }

    @Test
    void wildcardTypeArgumentsHaveTheReferenceShape() {
        assertEquals("""
                COMPILATION_UNIT
                  CLASS_DECLARATION 'W'
                    CLASS_BODY
                      FIELD_DECLARATION 'a'
                        TYPE 'List'
                          TYPE '?'
                            TYPE 'Number'
                        VAR_DECLARATOR 'a'
                      FIELD_DECLARATION 'b'
                        TYPE 'List'
                          TYPE '?'
                            TYPE 'Integer'
                        VAR_DECLARATOR 'b'
                      FIELD_DECLARATION 'c'
                        TYPE 'List'
                          TYPE '?'
                        VAR_DECLARATOR 'c'""", shape("class W { java.util.List<? extends Number> a; java.util.List<? super Integer> b; java.util.List<?> c; }"));
    }
}
