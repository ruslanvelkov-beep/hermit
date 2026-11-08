# Java Symbol Layer Contract

Package: `hermit.analysis.sym`

The symbol layer provides the semantic model for Java source code: names, types, scopes, and inference.
It sits between the parser (tree) and the runtime/analysis tools. All state lives in the resolver,
not in the tree nodes.

## Types

### Symbol
A lightweight handle for a named entity: type, variable, or method.
Public API:
- `Symbol.type(String name, Node declaration)` / `typeByName(String)` / `type(Class<?> clazz)`
- `Symbol.variable(String name, Node declaration, int declOffset)` / `variable(Class<?> owner)`
- `Symbol.method(String name, Node declaration)` / `method(Class<?> owner)`
- `Kind kind()`, `String name()`, `Node declaration()`, `Class<?> clazz()`, `int declOffset()`

### TypeRef
A reference to a type, carrying its class (for library types), source scope/declaration (for source types),
array dimensions, and type arguments. Supports UNKNOWN and NULL sentinels.
Public API:
- `TypeRef.of(Class<?> clazz)` / `of(Class<?>, Map<String, TypeRef>, int dims)`
- `TypeRef.ofSource(Scope typeScope, Node decl, int dims)` / `ofPackage(String prefix)`
- `withDims(int)`, `clazz()`, `source()`, `sourceDecl()`, `dims()`, `typeArgs()`
- `packagePrefix()`, `isUnknown()`, `isNullLiteral()`, `isPackage()`, `isArray()`, `isPrimitive()`
- `toClass()`, `describe()`, `toString()`

### Scope
A lexical scope with a kind (UNIT, TYPE, METHOD, BLOCK, LAMBDA, FOR, TRY, CATCH, CASE).
Holds declared variables, types, methods, and static imports. Supports resolution of names
with offset-aware variable lookup and simple-name type lookup.
Public API:
- `Kind kind()`, `parent()`, `owner()`
- `staticImportOwnerOf(String member)`, `staticWildcardOwnerFqns()`
- `declaredVariables()`, `declaredTypes()`, `declaredMethods()`
- `resolveVariable(String name, int atOffset)`, `resolveType(String simpleName)`
- `resolveMethods(String name)`
- `membersProvable()`, `typesProvable()` — indicate whether the scope's members/types are fully resolved
- `Set<String> visibleTypeNames()` — the simple names of the types this scope can name without qualification

### ClasspathIndex
Reads compiled classes from the classpath. Provides package existence, FQN lookup by simple name,
binary name conversion, and class loading.
Public API:
- `ClasspathIndex.system()` / `withExtra(List<Path> extraEntries)`
- `classesIn(String pkg)`, `fqnsOf(String simpleName)`, `hasPackage(String pkg)`
- `isPackageRoot(String name)`, `binaryNameOf(String dotted)`, `hasClass(String dotted)`
- `load(String dotted)`

### SourceIndex
A project-wide index of `.java` files, mapping packages and simple names to source declarations.
Supports lazy full resolution of source types for cross-file symbol resolution.
Public API:
- `SourceIndex.build(List<Path> roots, ClasspathIndex classpath)`
- `refresh(Path file)`, `hasPackage(String pkg)`, `typesInPackage(String pkg)`
- `fqnsOf(String simpleName)`, `resolveType(String pkg, String simpleName)`
- `resolverFor(Path file)`, `files()`, `treeOf(Path file)`, `fileCount()`

### SymbolResolver
The central state holder. Builds the scope chain for a ParseTree, registers declarations,
links supertypes (source and classpath), and validates names. State lives here, not in nodes.
Public API:
- `SymbolResolver.resolve(ParseTree)` / with ClasspathIndex / with SourceIndex
- `index()`, `typeInference()`, `unitScope()`
- `scopeOf(Node n)` — returns the Scope enclosing a node
- `symbolOf(Node n)` — returns the resolved Symbol for a node (replaces node.resolvedSymbol())
- `expansionOf(Node importDecl)` — expands wildcard imports
- `relinkSupertypes()` — re-resolves pending supertype links; returns count of newly linked
- `unlinkedSupertypes()` — lists supertypes that could not be resolved
- `validate()` / `validate(Node subtree)` — returns list of provable problems

### TypeHierarchy
Builds the full type hierarchy from a SourceIndex: supertype/subtype edges, method visibility,
dispatch candidates. Used for call resolution and override analysis.
Public API:
- `TypeHierarchy.build(SourceIndex sources)`
- `libraryEntry(Class<?> c)`, `entries(String name)`, `entryOf(Node typeDecl)`
- `sourceEntries()`, `allDeclaredMethods(TypeEntry)`, `declaredMethods(TypeEntry)`
- `visibleMethods(TypeEntry)` — declared + inherited, with override shadowing
- `resolveCall(TypeEntry, String name, int argCount, List<String> argTypes)`
- `resolveCall(TypeEntry, String name, int argCount)` — arity-only resolution
- `parameterTypeNames(MethodDecl)`, `dispatchCandidates(TypeEntry, String name, int argCount)`
- `isSubtypeOf(TypeEntry sub, TypeEntry sup)`

### TypeInference
Computes TypeRef for every expression node. Validates member existence and argument arity where
types are provable. Substitutes generic returns one level deep. Publishes loadable results on nodes.
Public API:
- `validate(Node subtree)` — returns list of provable problems
- `returnTypeMismatch(Node ret)` — checks return type against method signature
- `typeOf(Node n)` — returns the inferred TypeRef for a node
- `typeRefOfTypeNode(Node typeNode)` — resolves a type literal to TypeRef
- `fieldOf(Node fieldAccess)` — returns the resolved Symbol for a field access

## Provability Rule

A problem is reported only when it is PROVABLE in a closed context. In an open context
(unknown supertype, unindexed package, unloadable class), the resolver stays silent.
No hallucinated errors: if a supertype cannot be resolved, lookups that fail against it
are accepted. This is the contract for mutation: reject what can be proven wrong, keep honest code.

## State Location

Symbol-layer state lives in the SymbolResolver, not in the tree. The new Node class keeps
`inferredClass` (for loadable types) but holds no scope and no resolved symbol. The resolver
answers `scopeOf(node)` and `symbolOf(node)`. This separation allows the resolver to be rebuilt
without touching the tree, and supports incremental re-resolution.

Across files: a declaration of another file has no entry in this resolver's map. A resolver built with a SourceIndex
therefore also records every scope it creates in the index (package-private `recordScope(owner, scope)`),
and looks up a declaration it does not own there (`scopeOf(owner)`); this is how the scope of a file that is still
being resolved in a cycle is found. SourceIndex.resolveType tests that map.

## Further problems reported

Besides unknown names, the resolver and type inference report:
- an unqualified call to an instance method of an enclosing type from a static context is a problem (SymbolResolver);
- an unknown type names the types in scope, and a missing method names the methods in scope, closest spellings first
  (SymbolResolver, with `Scope.visibleTypeNames()`);
- a missing field or method names the members the receiver's type has, and a nested name written where a joined type
  name was meant names that type (TypeInference);
- an operator or a condition whose operand provably cannot take it (`!` on a non-boolean, `&&`/`||` with a
  non-boolean, `==`/`!=` between boolean and a type that cannot be one, a non-boolean condition) is a problem;
- a call that returns void used where a value is needed is a problem;
- a private field or method used outside the top-level class that declares it is a problem, with an accessor hint.
