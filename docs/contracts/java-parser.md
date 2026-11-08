# Java parser contract

Package: `hermit.analysis.tree`

This contract describes the concrete-syntax tree layer: the second pass that turns a
`hermit.analysis.lex` token stream into a tree, on top of the lexer contract. It fixes the
node kinds, the public tree API, the parser entry points and their guarantees.

## NodeType

`NodeType` is an enum of node kinds, grouped by role:

- **structure**: `COMPILATION_UNIT`, `PACKAGE_DECLARATION`, `IMPORT_DECLARATION` (including
  static and module imports; the wildcard is visible in the tokens), `MODULE_DIRECTIVE`
  (requires / exports / opens / uses / provides), `MODULE_DECLARATION`, `CLASS_DECLARATION`,
  `INTERFACE_DECLARATION`, `ENUM_DECLARATION`, `RECORD_DECLARATION`,
  `ANNOTATION_TYPE_DECLARATION`, `IMPLICIT_CLASS` (compact source file: members with no
  enclosing declaration), `CLASS_BODY` (one per type declaration and per anonymous class),
  `METHOD_DECLARATION`, `CONSTRUCTOR_DECLARATION`, `COMPACT_CONSTRUCTOR`, `FIELD_DECLARATION`,
  `INITIALIZER_BLOCK` (static or instance), `PARAMETER`, `TYPE_PARAMETER`,
  `TYPE_PARAMETER_LIST`, `MODIFIER`, `ANNOTATION`, `TYPE` (any type reference: primitive,
  qualified class type, array, wildcard).
- **statements**: `BLOCK`, `VAR_DECLARATION` (modifiers, type, one or more declarators),
  `VAR_DECLARATOR` (one name, dimensions, optional initializer), `EXPRESSION_STATEMENT`,
  `IF` with `THEN` and `ELSE` children (an else-if chain nests through `ELSE`), `FOR`
  (basic), `FOR_EACH` (enhanced; holds a `VAR_DECLARATION` for the loop variable), `WHILE`,
  `DO_WHILE`, `SWITCH` (colon labels), `SWITCH_FUNCTION` (arrow labels; statement or
  expression), `CASE` (one case/default group including its body), `BREAK`, `CONTINUE`,
  `RETURN`, `THROW`, `TRY`, `CATCH`, `FINALLY`, `RESOURCE` (one try-with-resources resource),
  `YIELD`, `SYNCHRONIZED`, `ASSERT`, `LABELED_STATEMENT`, `EMPTY_STATEMENT`.
- **expressions**: `IDENTIFIER_EXPRESSION`, `LITERAL` (any literal token),
  `PARENTHESIZED`, `METHOD_CALL` (receiver, optional type arguments, name, arguments; also
  `this(...)` / `super(...)`), `FIELD_ACCESS`, `ARRAY_ACCESS`, `CLASS_LITERAL`,
  `ASSIGNMENT` (one node kind for all assignment operators; the operator is the principal
  token), `TERNARY`, `BINARY_EXPRESSION` (one node kind for all infix operators),
  `UNARY_EXPRESSION` (prefix `+ - ! ~ ++ --`), `POSTFIX_EXPRESSION`, `CAST`,
  `INSTANCEOF`, `NEW_INSTANCE` (with optional anonymous `CLASS_BODY`), `NEW_ARRAY`,
  `ARRAY_INITIALIZER`, `LAMBDA`, `METHOD_REFERENCE`, `TYPE_PATTERN`, `RECORD_PATTERN`,
  `FRAGMENT` (internal scratch container used while the final node kind is still unknown).

## Node

`Node` is a mutable tree node. It holds only structure: one `NodeType`, an optional principal
`Token`, an ordered list of *parts* and links to its parent. Symbol-layer state is deliberately
absent: a `Node` has no scope and no resolved-symbol accessors. A separate semantic model
associates trees with symbols.

Public API:

- `Node(NodeType type)` — create an empty node of that kind.
- `type()`, `token()`, `name()` — kind, principal token, and the name where the kind has one.
- `parent()` — the node's parent, or `null` for the root.
- `parts()` — the ordered mixed list of child nodes and tokens.
- `children()` / `children(NodeType t)` / `child(NodeType t)` — the child nodes, all of them,
  by kind, or the first of a kind.
- `tokens()` — the tokens of the subtree, in source order.
- `hasModifier(TokenType t)` — whether a modifier of that token kind is present.
- `walk(Consumer<Node> visitor)` — pre-order traversal of this node and its descendants.
- `descendants(NodeType t)` — the descendants of that kind.
- `firstToken()`, `firstCodeToken()` (first token that is not trivia), `lastToken()`.
- `toString()` — reproduces the source text of the subtree exactly.
- `dump()` — a debug rendering of the tree.

`toString()` is the round-trip guarantee: because every consumed token is owned by exactly one
node, the concatenation of tokens in a subtree is the original text.

## ParseTree

`ParseTree` wraps a parsed compilation unit.

- `static ParseTree parse(String source)` — parse a whole compilation unit.
- `root()` — the `COMPILATION_UNIT` node.
- `packageName()` — the declared package, or `null`.
- `imports()` — the import declaration nodes.
- `importedName(Node importDecl)`, `isModuleImport(Node)`, `isStaticImport(Node)`,
  `isWildcardImport(Node)` — static helpers describing an import declaration.
- `typeDeclarations()` — the top-level type declarations.
- `allTypes()` — every type declaration in the tree.
- `findType(String simpleName)`, `findMethod(String typeSimpleName, String methodName)`.
- `toString()` — the exact source text of the unit.

## ParseException

`ParseException` is unchecked and describes a syntax error. Its message carries the 1-based
line and column of the offending token and what the parser expected there, so a caller can point
at the position and say what was missing.

## JavaParser

`JavaParser` is the entry-point facade over the recursive-descent pass. Public entry points:

- `parse(String source)` — parse a whole compilation unit; the source must be fully consumed.
- `parseStatement(String source)` — parse exactly one statement.
- `parseStatements(String source)` — parse a sequence of statements (a block body).
- `parseExpression(String source)` — parse exactly one expression.
- `parseMember(String source)` — parse exactly one member declaration.
- `parseImport(String source)` — parse exactly one import declaration.

Guarantees:

- Exact round trip: `parse(source).toString().equals(source)` for any accepted source, and the
  same for every node's `toString()`.
- Snippet parsers reject trailing input: after the snippet is parsed, any remaining significant
  token raises `ParseException`; comments and whitespace do not count as trailing input.
- Errors carry position and expectation: `ParseException` names the line and column of the
  offending token and what was expected.

## Test outcomes (`JavaParserTest`)

1. `roundTripsASmallClassExactly` — a small class parses and its `toString()` equals the source.
2. `ifThenElseStructureMatchesTheSpec` — `IF` has `THEN` and `ELSE` children; an else-if chain
   nests through `ELSE`.
3. `varDeclarationsHaveTypeAndDeclarators` — a `VAR_DECLARATION` exposes its type and its
   `VAR_DECLARATOR` children.
4. `loopsParse` — basic `FOR` and enhanced `FOR_EACH` produce the expected shapes.
5. `oldSwitchVsNewSwitch` — colon labels produce `SWITCH` / `CASE`, arrow labels produce
   `SWITCH_FUNCTION`.
6. `angleBracketsAreRetypedAndMatched` — type-context angle brackets become
   `OPENING_ANGLE_BRACKET` / `CLOSING_ANGLE_BRACKET` and are linked through
   `matchingBracket()`.
7. `shiftsMergeButComparisonsStay` — adjacent `>` merge into one shift token in expression
   context, while comparisons keep separate tokens.
8. Expression shapes, one test per family (they were one test, `expressionShapes`, split so each
   family can be made to pass in its own step):
   - `receiverChainsNest` — in `a.m1(x).m2(y).f`, each call or field access wraps its receiver as
     its first child and names the member as its principal token;
   - `prefixPostfixAndAssignmentOperators` — assignment, postfix, prefix (unary) and cast
     expressions, including `(a) - b` (a subtraction) against `(int) - b` (a cast);
   - `conditionalAndInstanceofExpressions` — `TERNARY` and `INSTANCEOF` (with a binding);
   - `methodReferencesClassLiteralsAndTypeArguments` — `METHOD_REFERENCE` (a method or `new`),
     `CLASS_LITERAL` (array and primitive types) and a call with explicit type arguments;
   - `arrayAccessAndArrayCreation` — `ARRAY_ACCESS` and `NEW_ARRAY` (dimensions or an
     initializer).
9. `lambdasParse` — typed and inferred lambda parameters parse into `LAMBDA` nodes.
10. `tryCatchFinallyAndResources` — `TRY`, `CATCH`, `FINALLY` and `RESOURCE` nodes have the
    expected children.
11. `anonymousClassesAndEnumsAndRecords` — anonymous class bodies, enum declarations and
    record declarations parse with the expected members.
12. `annotationsEverywhere` — annotations on types, members, parameters and local
    declarations parse and round-trip.
13. `snippetsRejectTrailingGarbage` — a snippet parser raises `ParseException` on trailing
    input.
14. `parseErrorsCarryPositionAndExpectation` — a malformed declaration raises `ParseException`
    whose message names the position and what was expected.
15. `textBlocksAndCommentsSurviveRoundTrip` — text blocks and comments are preserved by
    `toString()`.
16. `selfParsesAllHermitSourcesExactly` — every source file of this repository parses and
    round-trips exactly, covering real sources.

## Tree shapes

The tree's shape (node types, nesting and principal names) is fixed:
declarations carry their type parameters, supertypes, field declarators and parameter types as nodes; an expression
statement is wrapped in EXPRESSION_STATEMENT; an instanceof binder is a TYPE_PATTERN; a keyword statement's principal
is its keyword; a resource holds a VAR_DECLARATION; 'this' and 'super' are THIS_EXPRESSION and SUPER_EXPRESSION
(a qualified 'K.this' holds K); a labeled statement is a LABELED_STATEMENT whose principal is the label. JavaParserShapeTest checks these shapes
against recorded dumps; the symbol layer, the code graph and the tree editor read them.
