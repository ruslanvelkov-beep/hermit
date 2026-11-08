# Hermit Architecture

Hermit is a general-purpose Java coding agent. It operates as an explicit state machine,
processing requests through structured phases of planning, review, and execution.
The system is designed for determinism, inspectability, and safe resumption.

## Package Layout

The source code resides under `src/main/java/hermit/`, and the text annotator (TextLens) under `textlens/src/main/java/hermit/text/`. Packages are organized by
responsibility, enforcing clear boundaries between infrastructure, logic, and interfaces.

| Package | Responsibility |
|---|---|
| `api` | Agent contract, requests, events, action values |
| `text` | Text annotation, the `textlens` module (a library of its own): layout, tokens, tags, concept spans, markers, clauses, dates and checks; code-fence parsing |
| `model` | Endpoint configuration, HTTP transport, profiles, helper chain, routing contracts, usage |
| `model.escalation` | Optional escalation policy; implemented last |
| `plan` | Plan schema, parser, prompts, branch selection, repetition limits |
| `review` | Review panels, deterministic plan checks, reviewer contracts |
| `tool.api` | Tool schemas, registry, capability availability |
| `workspace` | Paths, local processes, command policy, Gradle project location |
| `context` | Turn context, packing, activity recording |
| `evidence` | Immutable observations, citations, execution records, external-reader ports |
| `checkpoint` | Turn snapshots, durable checkpoints, resume rules |
| `answer` | Formatting and final-answer gates |
| `runtime` | Agent state machine and tool execution orchestration |
| `analysis.lex` | Java tokens and lexer |
| `analysis.tree` | Parse trees and grammar |
| `analysis.sym` | Scopes, symbols, types and resolution |
| `analysis.graph` | Project call graph and queries |
| `analysis.flow` | Type propagation, exception flow and impact analysis |
| `analysis.edit` | Validated transactional tree editing |
| `analysis.emu` | Bounded interpretation for synthetic test analysis |
| `memory` | Verified snippets, embeddings, SQLite storage |
| `generate` | Code/test generation stages and review |
| `claims` | Claim mapping, evidence decisions, claim ledger |
| `tool.*` | Thin planner-facing adapters over the above services |
| `search` | Search-provider interface only |
| `eval` | Generic harnesses using new synthetic data only |
| `cli`, `tui` | User interfaces |
| `app` | Composition root and concrete cross-subsystem adapters |

## Dependency Order and Rules

Dependencies flow from low-level infrastructure to high-level orchestration.
An arrow indicates a permissible dependency direction.

1. `api`, `text`, configuration/value types: JDK only where practical.
2. `analysis.lex`.
3. `analysis.tree` depends on `analysis.lex`.
4. `analysis.sym` depends on `analysis.tree`.
5. `analysis.graph` depends on `analysis.sym`; `analysis.flow` depends on `analysis.graph`.
6. `analysis.edit` depends on `analysis.tree`, `analysis.sym`; `analysis.emu` depends on `analysis.sym`.
7. `model`, `workspace`, `evidence`, `tool.api`, `checkpoint` depend on low-level contracts.
8. `memory` depends on `analysis.tree`, `model`, `evidence`; `context` depends on `evidence`, `model` contracts.
9. `plan`, `review` depend on `api`, `model`, `tool.api`; `generate` depends on `review`, `analysis.edit`.
10. `claims` depends on `evidence`, `analysis.*`, `model` through evidence-production ports, not tools.
11. Concrete `tool.*` depend on `tool.api` and their service packages.
12. `answer`, `runtime` depend on contracts, `plan`, `review`, `context`, `checkpoint`, `evidence`.
13. `cli`, `tui`, `eval` depend on public service contracts.
14. `app` depends on everything needed for wiring.

Key rules:
- No tool implementation imports `runtime`.
- Claims request test execution through ports; they do not call planner-facing tools.
- `app.EvidenceWiring` connects claims to real processes, source readers and scratch worktrees.
- The runtime depends on routing/review/claim interfaces, not escalation or provider implementations.
- Parse trees do not hold mutable symbol-layer ownership. A separate semantic model associates trees with resolved symbols.
- UI events expose plans, progress and review summaries—not private model reasoning.
- A package-dependency check is enforced during the scaffold phase; cycles are rejected.

## Runtime State Machine

The runtime operates as an explicit state machine with the following flow:

1. **Request**: Ingest the user or system request.
2. **Context**: Load and pack relevant context from history and workspace.
3. **Plan**: Generate a structured plan (schema: steps, tools, expected outcomes).
4. **Review**: Pass the plan through review panels for deterministic checks.
5. **Deterministic Checks**: Validate syntax, scope, and resource constraints.
6. **Approval**: Wait for explicit approval if required by policy or uncertainty.
7. **Tool Execution**: Execute approved tool actions sequentially or in parallel groups.
8. **Recorded Result**: Capture outputs, errors, and side effects as immutable evidence.
9. **Context Update**: Merge results into the turn context.
10. **Next Step or Final Gate**: Proceed to the next step if more work is needed, or enter the final gate.

Every seam (transition between states) supports:
- **Pause**: Halt execution for inspection.
- **Inspect**: View current state, context, and pending actions.
- **Retry**: Re-execute the failed or paused step.
- **Resume**: Continue from the last stable checkpoint.

## Boundaries: Models, Tools, and Evidence

- **Models**: Provide generative capabilities (text, code). They are stateless endpoints configured via `model` package. They do not hold persistent state.
- **Tools**: Execute specific actions (file I/O, compilation, search). They are deterministic or probabilistic but always produce structured outputs. Tools do not import `runtime`.
- **Evidence**: Immutable records of observations, citations, and execution results. Stored in `evidence` package. External readers can access evidence ports for auditing.

The **final-answer gate** requires:
- All claims in the answer must be backed by evidence records.
- Deterministic checks must pass for any code or configuration presented.
- No unresolved tool executions or pending approvals.
- If claim evaluation is missing, the answer is not silently approved; it requires explicit supervisor confirmation.

## Checkpoint Semantics

Checkpoints capture both:
1. **Intent**: The planned actions and expected outcomes before execution.
2. **Result**: The actual outputs and side effects after execution.

A resumed operation with uncertain side effects (e.g., partial file writes, external API calls) requires reconciliation. The supervisor must confirm whether to apply the recorded result or revert and retry. This ensures consistency across interruptions.

## Composition Root

The application entry point is `hermit.app.Main`. The `app` package serves as the composition root, wiring together:
- Concrete implementations of interfaces.
- Cross-subsystem adapters (e.g., `EvidenceWiring` connecting claims to processes).
- Configuration and lifecycle management.

No business logic resides in `app`; it only coordinates dependencies. This allows for testing and modular replacement of components.
