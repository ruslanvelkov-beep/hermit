# Java Call Graph Contract

Package: `hermit.analysis.graph`

The call graph is built from parse trees, without compiling and without a database: every .java file
under the roots is parsed, resolved and type-inferred, and the graph indexes method and constructor
declarations (by simple name and by Type#name) and call sites (method calls, method references,
constructor invocations), each with its enclosing declaration and, when inference knows it, the receiver's
type. Edges are name-based and sharpened by the receiver's type: a deliberate over-approximation in the
style of class-hierarchy analysis, which suits the question "could this test reach that method?". It works
on code that does not compile yet, and refresh re-indexes one edited file.

## Types

### CodeGraph
The central index of declarations and call sites, built from source roots and a classpath index.
Public API:
- `static build(List<Path> roots, ClasspathIndex index) throws IOException`
- `sources()` — the source index holding parsed trees and resolvers
- `refresh(Path file) throws IOException` — re-index one edited file
- `fileCount()` — number of indexed source files
- `treeOf(Path)` — the parse tree for a file
- `typeNames(Predicate<Path> accept)` — type names matching the predicate
- `locate(String spec)` — find a declaration by a simple method name or by "Type#name"
- `callersOf(String spec)` — call sites that invoke the target
- `calleesOf(MethodRef m)` — call sites invoked by the method
- `reach(String from, String to, int maxDepth)` — check if target is reachable from source
- `reach(String from, String to, int maxDepth, int nodeBudget)` — with visit budget
- `hierarchy()` — the computed type hierarchy
- `targetsOf(CallSite site)` — possible targets for a call site
- `callerEdges(String spec)` — edges from callers to the target
- `dispatchCandidatesOf(String spec)` — override/impl candidates for a virtual call

Records:
- `MethodRef(typeName, methodName, file, line, decl)` with `spec()` = "Type#name"
- `CallSite(caller, calleeName, receiverType, file, line, site)`
- `Target(typeName, methodName, source, library, how)` where how is one of:
  exact, inherited, impl, declaration, ctor, default-ctor, name-only, alloc
- `CallerEdge(site, how)`
- `ReachResult(reachable, path, status, nameOnlyHops)` with `confident()`, `undecided()`
  and `describe(int maxDepth)`, where status is one of: resolved, low-confidence, no-path,
  depth-bound, budget, start-not-found

### TestReach
Determines which tests transitively exercise a method, ranked by relevance.
Public API:
- `static of(CodeGraph graph, String targetSpec, int maxDepth)`
- `static of(CodeGraph graph, String targetSpec, int maxDepth, int maxVisited)`

Records:
- `Route(testMethod, path, overlap, shared, score, direct)` with `hops()` and `render()`
- `Result(target, tests, testHelpers, notes)` with `exercised()`

A test method is one annotated with a JUnit test annotation: Test, ParameterizedTest,
RepeatedTest, TestFactory or TestTemplate.

### GraphQueries
Text answers for the graph's queries, each returning `Result(text, found)`: found is whether the query found something (for validate: whether there are problems).
Public API: locate, callers, callees, reach, hierarchy, impls, methods, validate, tests, stats.

### GraphServer
Serves the queries over HTTP on 127.0.0.1 from ONE thread, with refresh and shutdown endpoints
and an idle timeout.

### GraphCommand
The command-line form of the same ten queries, with exit codes 0 (found), 1 (not found) and
2 (usage error).

## State Location

A Node holds no scope and no resolved symbol. CodeGraph asks the resolver of the node's file, `sources.resolverFor(file)`:
`scopeOf(n)` and `symbolOf(n)`. It must be that cached resolver, because its type inference validated
the file during indexing and recorded the symbols. The two static helpers that read a scope,
`receiverTypeName` and `reassignedWithin`, take that resolver as a parameter.

## Package Rules

analysis.graph depends on analysis.sym; analysis.flow depends on analysis.graph, never the reverse.
So CodeGraph has no `exceptions()` accessor and no exception-flow field; the accessors the flow
analyses need (`allMethods`, `callerEdgesOf`, `refOf`, `typeInferenceFor`) are there for
analysis.flow. GraphQueries keeps the graph-only queries listed above; the queries that
need flow analysis or the interpreter (escapes, catches, throwpath, impact, flowreach, emulate)
live in analysis.flow and analysis.emu.

## Concurrency

Queries fill lazy state (the type hierarchy, resolved targets, caller edges, a cache of name-only
targets) without locking, so a CodeGraph is not safe for concurrent use; GraphServer serves every
request from one thread. A refresh drops the resolved state, and the next query that needs it
resolves the whole project again.
