# Hermit Text Annotation Design

## Goals and non-goals

Annotate report text without losing its original positions. Identify factual parts, normalize supported concepts, and check them using reproducible rules. Preserve every token in the claim-splitting input, including text that has no recognized concept.

Separate three operations:

- **Recognition:** what a span appears to mean.
- **Checking:** whether a supported relation is consistent with deterministic evidence.
- **Claim adjudication:** whether an atomic claim holds, is refuted, or remains unproven.

Reviewer agreement and model confidence are not evidence. A valid number, date, or identifier does not establish the surrounding assertion.

Non-goals: unrestricted natural-language understanding, a complete grammar, general symbolic mathematics, or proving runtime behavior from an identifier’s existence.

## Architecture

Use Java 21 records and sealed interfaces, Jackson for JSON, and JUnit 5. Add no other core dependencies.

| Package | Responsibility |
|---|---|
| `hermit.text` | `TextAnnotator`, configuration, pipeline orchestration |
| `hermit.text.model` | Documents, tokens, spans, relations, checks, provenance |
| `hermit.text.lex` | Offset-preserving tokenizer and sentence splitter |
| `hermit.text.pos` | Lexicons, tagging rules, contextual disambiguation |
| `hermit.text.semantic` | Concept recognizers, normalization, relation extraction |
| `hermit.text.expr` | Bounded expression parser and exact evaluator |
| `hermit.text.check` | Deterministic checkers and evidence records |
| `hermit.text.port` | Model, code-index, clock/context integration contracts |
| `hermit.text.integration` | Claim-splitter adapter and answer-gate adapter |

Pipeline: tokenize → split sentences → tag → recognize spans → extract relations → check → emit JSON and claim input. Recognition and tagging may share immutable lexical features.

## Offsets, tokens, and sentences

Use half-open UTF-16 offsets into the unchanged Java `String`. Document this convention for consumers. Preserve whitespace through the original text and offset gaps; do not normalize before computing offsets. Traverse Unicode by code point without splitting surrogate pairs.

Recognize words, punctuation, operators, quoted text, and code-shaped forms. Keep qualified identifiers, paths, versions, and command flags intact when their shape is clear. Expression parsing can create subspans within a token. Never discard an unknown character.

Sentence splitting considers punctuation, newlines, quotes, parentheses, abbreviations, decimals, versions, paths, and code blocks. A period in `1.4` is not a sentence boundary. Record uncertain boundaries rather than silently merging unrelated claims. Boundaries must not drop or duplicate non-whitespace text.

## Data model

`AnnotationDocument` contains:

- Schema and ruleset versions; text; language/locale; captured clock instant and zone; optional code-index revision.
- Sentences with IDs, ranges, and ordered token IDs.
- Tokens with range, surface text, POS tag, alternatives, source, confidence, and rule/model provenance.
- Concept spans with IDs, ranges, class, normalized value, source, confidence, and optional alternative interpretations.
- Relations linking spans: equality, comparison, total, duration, version comparison, or code reference.
- Checks with target IDs, checker/version, status, evidence, assumptions, and reason codes.
- Coverage records assigning each token to a recognized span or residual text.

Allow nested and overlapping spans: a weekday inside a date, a number inside a quantity, or a version next to a product. Normalized values are typed JSON objects, not strings requiring another parser. Store exact numeric values as decimal strings.

A confidence expresses recognition certainty, not factual truth. Sources are `rules` or `model`; deterministic validation of a model proposal does not change its origin. Normalization and checking have separate provenance.

### Short JSON example

Compact token rows below have columns `[start,end,tag]`; surfaces come from the original text. `defaults` supplies explicit inherited provenance for tokens and spans. The fixed clock is illustrative; production captures the injected system clock once per document.

```json
{
  "schemaVersion": 1,
  "ruleset": "text-1",
  "text": "On Mon Oct 20 the 3 runs took 2+2=5 minutes in Hermit 1.4.",
  "context": {
    "instant": "2026-10-22T00:00:00Z",
    "zone": "UTC",
    "locale": "en"
  },
  "defaults": {"source": "rules", "confidence": 1.0},
  "tokenColumns": ["start", "end", "tag"],
  "sentences": [{
    "id": "s1", "range": [0, 58],
    "tokens": [
      [0,2,"ADP"], [3,6,"PROPN"], [7,10,"PROPN"], [11,13,"NUM"],
      [14,17,"DET"], [18,19,"NUM"], [20,24,"NOUN"], [25,29,"VERB"],
      [30,31,"NUM"], [31,32,"SYM"], [32,33,"NUM"], [33,34,"SYM"],
      [34,35,"NUM"], [36,43,"NOUN"], [44,46,"ADP"],
      [47,53,"PROPN"], [54,57,"NUM"], [57,58,"PUNCT"]
    ]
  }],
  "spans": [
    {"id":"d","range":[3,13],"class":"DATE",
     "value":{"iso":"2026-10-20","statedWeekday":"MONDAY"}},
    {"id":"w","range":[3,6],"class":"WEEKDAY","value":"MONDAY"},
    {"id":"n","range":[18,24],"class":"QUANTITY",
     "value":{"number":"3","unit":"run"}},
    {"id":"e","range":[30,35],"class":"EXPRESSION",
     "value":{"op":"eq","left":{"op":"add","args":[
       {"integer":"2"},{"integer":"2"}]},"right":{"integer":"5"}}},
    {"id":"u","range":[36,43],"class":"UNIT","value":"minute"},
    {"id":"p","range":[47,53],"class":"PRODUCT","value":"Hermit"},
    {"id":"v","range":[54,57],"class":"VERSION",
     "value":{"segments":["1","4"],"scheme":"dotted"}}
  ],
  "checks": [
    {"target":"d","checker":"weekday-1","status":"inconsistent",
     "evidence":{"computedWeekday":"TUESDAY","statedWeekday":"MONDAY"},
     "assumptions":[{"kind":"missingYear","value":2026,
                     "source":"context.instant@context.zone"}]},
    {"target":"e","checker":"exact-expression-1","status":"inconsistent",
     "evidence":{"left":"4","right":"5"},"assumptions":[]}
  ],
  "coveragePolicy": "all tokens not covered by spans remain residual claim input"
}
```

October 20, 2026 is Tuesday; Monday is October 19. Report the mismatch without silently changing the date. The count, elapsed-time assertion, and product-version reference still require their own claim evidence.

## POS tagger choice

Use a small rule-and-lexicon tagger initially, with a coarse Universal POS-style tag set and an explicit `X`/unresolved state.

Apply closed-class lists first, then numeral and code-shape rules, a small domain lexicon, suffix rules, and local context. Suffixes are hints: `-ing` alone does not establish a verb. Keep multiple candidates for unresolved cases. POS and concept classes are independent: an identifier may be a noun or proper noun.

A small statistical alternative is a Java-implemented averaged-perceptron tagger trained offline on an openly licensed, reviewed corpus, such as an appropriate Universal Dependencies English treebank. Training is outside core; inference needs only weights and Java code.

- **Rules:** small, inspectable, reproducible, easy to adapt to code text; require explicit maintenance and leave more ambiguity.
- **Statistical:** better contextual coverage on ordinary prose; adds corpus/license review, a training pipeline, a weight artifact, and domain-drift evaluation.

Recommend rules first. Establish held-out prose-and-code benchmarks before adding statistical inference. Neither tagger should decide factual truth.

## Concepts and model consultation

Recognizers cover number words and numerals; units and percentages; formulas and comparisons; dates, times, weekdays and durations; versions; identifiers, paths, commands and flags; products and tools; personal names; and bounded quantities such as “more than” and “at most.”

Use domain lexicons for Hermit, Gradle, and Java. Recognize personal names conservatively; capitalization is not identity evidence. Retain ambiguity between, for example, a decimal and a version. Unsupported text remains residual claim input, not omitted content.

`ModelAnnotationPort` may propose a tag for an ambiguous token and, optionally, candidate concept spans. Supply a bounded context, existing candidates, and an output schema. Record model identity, request hash, response, and confidence.

Validate enum values, exact ranges and surfaces, span boundaries, and normalization types. Reparse proposed dates and expressions locally. Reject out-of-range references and invalid syntax. A plausible name or identifier remains only a candidate until independently resolved. Model unavailability leaves uncertainty intact. Cache proposals for replay; deterministic checks never delegate decisions to a model.

## Deterministic checker rules

All checks return `consistent`, `inconsistent`, or `unverifiable`. Consistency applies only to the checked relation. Missing inputs, unsupported syntax, and competing interpretations produce `unverifiable`, with a reason—not an assumed success.

### Dates and times

Use strict `java.time` parsing. For a missing year, use the year of the captured clock instant in the configured zone and attach that assumption to every dependent result. Compare the stated weekday with `LocalDate.getDayOfWeek()`.

Impossible calendar dates are inconsistent once their interpretation is established. Do not guess whether `03/04` means March 4 or April 3 without an explicit locale/date-order policy; preserve both candidates otherwise. Locale controls names, decimal separators, and grouping. Do not rely on the JVM default locale.

A local date needs no instant conversion. Time-of-day comparisons require dates and zones when relevant. Preserve explicit offsets. Reject conflicting offsets/zones; daylight-saving gaps are invalid local instants, while overlaps need an offset or remain unverifiable.

### Arithmetic, comparisons, and number words

Parse an allowlisted grammar with precedence, parentheses, unary signs, arithmetic, and comparison operators. Use `BigInteger`, exact `BigDecimal` inputs, and reduced rational numbers for division; never binary floating point. Bound digits, nesting, and evaluation work.

Evaluate closed expressions exactly. Unknown variables need supplied bindings. Division by zero is inconsistent as an assertion of a defined value. Unsupported notation is unverifiable.

Parse controlled number-word grammar, including hyphens and documented uses of “and.” Distinguish a precise dozen from vague “dozens.” Normalize `more than` to `>` and `at most` to `<=`.

“About” and “roughly” attach an approximation qualifier. Use a tolerance only if the text or an explicit domain policy supplies one; record its source. Otherwise do not invent a threshold.

### Units and percentages

Maintain a small dimensioned unit registry with exact scale factors and explicit affine conversions where supported. Compare only compatible dimensions. Distinguish bytes from bits, decimal from binary prefixes, percentages from percentage-point changes, and calendar months from fixed seconds.

Ambiguous units need context. A percentage requires a known base to compute an absolute quantity. Report incompatible dimensions as inconsistent only when the claimed relation is clear.

### Versions

Parse versions independently of decimal numbers. Select an explicit product scheme: semantic versioning, a supported Java version scheme, or documented dotted-number ordering. Under semantic versioning, apply prerelease precedence and ignore build metadata for ordering.

Do not treat `1.10` as decimal `1.1`, or silently convert `1.4` into semantic version `1.4.0`. Unknown schemes and missing comparison operands remain unverifiable.

### Totals

Extract a total relation only when wording establishes a total and its included, non-overlapping terms. Normalize units, then add exactly. Account explicitly for exclusions and qualifiers.

Do not sum every number in a sentence. “Three runs took five minutes” gives neither individual durations nor enough information to verify a total. Ambiguous membership or overlap is unverifiable.

### Code references

Query a read-only `CodeIndexPort` pinned to a repository revision. Resolve paths relative to declared workspace/module roots; apply the index’s case policy. Resolve types, overloads, fields, and test names with available scope and signatures.

A confirmed absence refutes an existence claim only if index coverage is complete for that scope. Partial coverage, generated code not indexed, or multiple matches is unverifiable. Existing names do not prove call paths or behavior; those require graph edges, source evidence, or relevant test results. Do not execute commands merely to check their spelling.

### Durations

Compare stated elapsed time with end instant minus start instant, after exact unit conversion. Never infer midnight rollover, zone, or date without recording an authorized assumption. Distinguish elapsed time from wall-clock/calendar duration across daylight-saving changes.

A negative elapsed interval is inconsistent when chronological start/end roles are explicit. Rounded timestamps need a stated precision policy; otherwise boundary cases remain unverifiable.

## Claim integration and final-answer gate

Give the splitter all tokens, spans, relations, qualifiers, negation, attribution, and residual text. Create atomic obligations for checkable relations, not just isolated words; nested spans share evidence rather than duplicating claims.

Map code-related obligations to indexed files, symbols, call edges, and tests. Attach check IDs and evidence to each part:

- `inconsistent` → refuted, when the checked assertion is actually asserted by that part.
- `unverifiable` → unproven.
- `consistent` → holds only for that narrow relation; other predicates still need evidence.

A quoted or negated equation is not automatically asserted. The gate blocks refuted factual parts and unqualified presentation of unproven parts. Reannotate edits before release.

## Test-first delivery

Estimates are focused engineering days; write each phase’s first tests before implementation.

| Phase | Days | First JUnit 5 tests |
|---|---:|---|
| Offsets and JSON contracts | 2 | `utf16RangesRoundTrip`, `versionPeriodDoesNotSplit`, `everyTokenRetained` |
| POS rules and model port | 2 | `closedClassTagsStable`, `suffixAmbiguityRetained`, `invalidModelRangeRejected` |
| Concepts and normalization | 3 | `exampleSpansMatchOffsets`, `numberWordsNormalize`, `ambiguousDateRetainsCandidates` |
| Exact/date/unit checks | 3 | `monOct20UsesClockYear`, `twoPlusTwoIsNotFive`, `decimalAdditionIsExact`, `dstOverlapNeedsOffset`, `unitDimensionsMustMatch` |
| Relations and code mapping | 3 | `totalsRequireMembership`, `versionTenExceedsNine`, `durationNeedsDate`, `partialIndexCannotProveAbsence` |
| Claim gate and replay | 2 | `inconsistentPartRefuted`, `quotedEquationNotAsserted`, `unverifiablePartStaysUnproven`, `fixedContextReplaysIdentically` |

Add parameterized boundary tests, seeded randomized offset/parser tests, JSON round trips, and frozen regression fixtures.

## Risks and limits

- **Missed concepts:** preserve residual coverage and report recognition gaps.
- **Overconfident interpretation:** retain alternatives; distinguish recognition confidence from proof.
- **Parsing cost:** bound input size, expression depth, digits, and model context.
- **Temporal drift:** capture clock/zone once and record all inferred fields.
- **Stale code evidence:** pin index revision and expose coverage.
- **Model variability:** keep consultation optional, validate proposals, and retain replay records.
- **Scope growth:** use versioned registries and narrow checker contracts; unsupported cases remain unproven.
