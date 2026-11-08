# TextLens

TextLens annotates English text and reports what in it does not hold. It reads the layout (titles, paragraphs, lists,
code), the tokens and sentences, the part-of-speech tags, the concept spans (numbers, dates, times, versions, code
references, hosts, expressions, products), the logical markers, the clauses and the dates, and it runs consistency
checks over them: a weekday that does not fit its date, a sum that is false, parts that do not add up to a stated
total, a version said to be newer than a later one.

An inconsistent check that the text asserts is a **finding**. A check inside a quotation or an example in backquotes
is not one: the text quotes it, it does not assert it. TextLens never refuses a text. A host decides what to do with
the findings, and the hosts in this repository only record them.

TextLens is the `textlens` Gradle subproject (the packages `hermit.text.*`). It depends on Jackson only; the agent
depends on it.

## Build

```bash
./gradlew :textlens:installDist   # the command: textlens/build/install/textlens/bin/textlens
./gradlew :textlens:jar           # the library: textlens/build/libs/textlens-0.1.0.jar
```

The jar names its main class; run it with Jackson on the class path, or use the installed command, which brings it.

## Models

The tagger works with its rules alone. Two model files make it more accurate. They are kept outside the repository with
their licences: the weights are derived from the UD English EWT treebank (CC BY-SA 4.0), and the search model also
holds WordNet's word lists.

| Option | System property | Model |
|---|---|---|
| `--weights <file>` | `hermit.pos.weights` | the reranker's weights, which choose among the rules' tags |
| `--search <file>` | `hermit.pos.search` | the sentence search, which chooses the tags of a whole sentence; it replaces the reranker |

## Commands

The text comes on standard input, the answer goes to standard output.

```bash
textlens annotate [options] < text   # the annotation document, as JSON
textlens check [options] < text      # the findings, one per line: the checker, a colon, the sentence
textlens tag [options] < text        # each token and its tag, separated by a tab
textlens serve [options]             # the resident server
textlens hook claude-code [options]  # the Claude Code Stop hook (see below)
```

| Option | Meaning |
|---|---|
| `--clock <instant>` | the clock, an ISO-8601 instant (default now); dates without a year take it from the clock |
| `--zone <id>` | the time zone (default the system's) |
| `--source <file>` | a text the input was written from, such as a request or a tool result; the years it mentions settle the input's dates without a year (repeatable) |
| `--server <port>` | annotate on the server running at that port |
| `--port <port>`, `--idle-minutes <n>` | `serve`: the port (default 7471) and the idle minutes before it stops (default 30, 0 never) |

Exit codes: 0 done, 1 `check` found a finding, 2 a usage error, 3 the annotation failed.

```bash
$ echo 'The release was on Mon Oct 20, 2026.' | textlens check --clock 2026-10-28T10:00:00Z --zone UTC
date: October 20, 2026 was a Tuesday, not a Monday.
```

## Resident server

Every command starts afresh and loads the models. `textlens serve` loads them once and answers over HTTP on 127.0.0.1:

| Request | Answer |
|---|---|
| `POST /annotate` | the annotation document |
| `POST /check` | `{"findings": [{"checker", "targets", "sentence"}, ...]}` |
| `GET /stats` | `{"requests": n}` |
| `GET /shutdown` | stops the server |

The body of a `POST` is `{"text": ..., "sources": [...], "clock": ..., "zone": ...}`; only the text is required.

```bash
textlens serve --weights reranker.jsonl.gz --search search.jsonl.gz &
curl -s -X POST localhost:7471/check -d '{"text": "We know that 2 + 2 = 5."}'
```

## Claude Code

`textlens hook claude-code` is a Stop hook. When Claude Code is about to stop, it reads the session's transcript,
takes the last answer (the assistant's text after the last user entry) and its sources (the last prompt and the tool
results after it), annotates the answer and appends one JSON line to `~/.textlens/claude-code.jsonl`: the time, the
session, the answer's length, the checks by status and the findings. It asks a running server first (port 7471) and
annotates in its own process when none answers.

```json
{
  "hooks": {
    "Stop": [{"hooks": [{"type": "command", "command": "textlens hook claude-code"}]}]
  }
}
```

The hook only records. With `--block` it keeps Claude Code from stopping once when the answer has findings, and gives
it their sentences to check; without it, the hook never changes what Claude Code does. Its own failures go to the log,
and it exits 0. Its options: `--log <file>`, `--server <port>` (0 for none), `--weights`, `--search`, `--clock`,
`--zone` and `--block`.

## In Java

```java
TextLens lens = TextLens.standard();
AnnotationDocument document = lens.annotate(text, sources, Instant.now(), ZoneId.systemDefault());
List<Findings.Finding> findings = lens.findings(document);
```

The document's JSON follows the annotation schema, version 1, with its additive layers (`hermit.text.model.AnnotationDocument`).
