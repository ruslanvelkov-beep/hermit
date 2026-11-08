# Hermit

A general-purpose coding agent for Java projects, written in Java.

## What it does

Hermit lets language models propose and deterministic tools decide:

- Models write plans, edits, critiques and answers. They can be local models behind an OpenAI-compatible endpoint, or
  hosted models reached through an escalation ladder.
- Before a plan runs, rules and review lenses check it.
- Edits go through a validated parse-tree editor.
- An answer is released only when its claims are backed by tool results.

## What is in this repository

| Area | Packages | What it provides |
|---|---|---|
| Java analysis | `analysis.lex`, `analysis.tree`, `analysis.sym` | Java lexer, parser and symbol and type resolution. It needs no compiler and works on code that does not compile. |
| Call graph | `analysis.graph` | Callers, callees, reachability, dispatch candidates, tests that reach a method, and validation of names and arity |
| Flow | `analysis.flow` | Type propagation, exception flow and forward impact slices |
| Editing | `analysis.edit`, `tool.analysis` | Transactional parse-tree edits that are re-validated before they are kept |
| Emulation | `analysis.emu` | A bounded interpreter that predicts a test's outcome without compiling it |
| Planning and review | `plan`, `review` | Plan schema and parser, repetition guards, review lenses and a filter for invalid objections |
| Models | `model`, `model.escalation` | Endpoints, a helper chain, a router for hosted providers and an escalation ladder |
| Answers and claims | `answer`, `evidence`, `claims` | Answer gates, citation checks and a claim ledger |
| Text annotation (TextLens) | `text`, in the `textlens` module | Layout, tokenizer, part-of-speech tagger with a learned sentence search, concept spans, markers, clauses and consistency checks; a library of its own that the agent depends on |
| Runtime | `runtime`, `context`, `checkpoint` | The agent loop, tiered history, and resumable checkpoints |

## Status

The components above are implemented and covered by the test suite. The command-line entry point (`hermit.app.Main`)
does not yet run the full agent.

## Requirements

- JDK 21 or newer
- An OpenAI-compatible model endpoint for the planning and review stages. The default is `http://localhost:1234/v1`,
  set with `-Dhermit.baseUrl`; the model is set with `-Dhermit.model`.
- Hosted models are optional. Their keys are read from the environment, from `~/.hermit/`, or from files that are
  ignored by git in the working directory (`.secrets/`, `*.api.key`).

## Build and test

```bash
./gradlew build
./gradlew test
```

The call graph has its own command-line front end:

```bash
java -cp build/classes/java/main hermit.cli.GraphCommand --root src/main/java callers 'Type#method'
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the package layout and how the components interact
- [docs/contracts/](docs/contracts/): the contracts of the lexer, parser, symbols, call graph and planning values
- [docs/plans/TEXT-ANNOTATION-PLAN.md](docs/plans/TEXT-ANNOTATION-PLAN.md): the design of the text annotator
- [TEXTLENS.md](TEXTLENS.md): TextLens, the text annotator as a tool of its own: its command, its server and its Claude Code hook
- [docs/TDD-WORKFLOW.md](docs/TDD-WORKFLOW.md): the test-first workflow used for every change

## License

Hermit is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as
published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version
(SPDX: `GPL-3.0-or-later`). See [LICENSE](LICENSE).
