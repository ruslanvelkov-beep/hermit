# Test-First Workflow

This workflow governs every change to the agent.
Work in small, reviewable tasks. Prepare the contract and tests before implementation.

## 1. Establish the contract first

Before writing a class, create or update `docs/contracts/{area}.md`.
Record the exact signatures and outcomes the tests will exercise.
Include constructors, methods, parameter and return types, enum values,
exceptions, visible side effects, and relevant input and output shapes.
Do not guess missing signatures or infer behavior that no test states.
Describe asserted outcomes, including boundary cases and failure behavior.
The contract is the shared basis for the tests, the dummy API, and the implementation.
Review it before adding the class. If new evidence changes it, review it again.

## 2. Add signature-complete dummies

Create the class with the public signatures listed in the contract.
Include the constructors and types needed for test compilation.
A behavioral method in a dummy throws exactly
`UnsupportedOperationException("not implemented")`.
Records, enums, constructors, and accessors may work mechanically.
This permission does not extend to behavior methods.
A dummy must never return plausible success or perform a hidden substitute operation.
Unimplemented capabilities must not look available to the planner.
The dummy exposes the API and expected red result; it is not early implementation.

## 3. Write the tests with synthetic fixtures

For each test:

1. State the general invariant being tested in one sentence.
2. Write it against the contract's API: constructors, methods, enums, exceptions,
   data fields, and visible side effects.
3. Use newly invented, ordinary names and values.
4. Keep assertions precise.
5. Compile it against the dummies and observe the intended red result.

Write new minimal programs for fixtures. Keep only the structure the invariant needs:
overloads, inheritance, scope, branching, exception propagation, source positions,
concurrency ordering, result shape, and failure and retry behavior.
Calculate expected source positions from the fixture itself.
Prefer local HTTP fixtures, temporary directories, fake clocks,
and scripted model responses to live services. Seed randomness.
Leave out a scenario that has no defensible general invariant.

## 4. Record the expected red state

First verify that test sources compile:
```sh
./gradlew testClasses
```
Then run the new test class in isolation, substituting its actual name:
```sh
./gradlew test --tests {class}
```
Expected red requires successful compilation and failures only from dummies:
`UnsupportedOperationException: not implemented`.
Investigate compilation failures, assertion failures, unrelated exceptions,
and infrastructure problems; none qualifies as expected red.
Do not assert the dummy exception as the desired behavior.

## 5. Implement in small slices

Implement behavior in slices of about 80–180 lines, typically covering 3–8 test methods.
Each slice has one clear purpose and preserves the contract.
Run focused class tests after each slice, then the broader relevant suite.
Continue until the complete class satisfies its contract and all its tests pass.

## 6. Prevent regressions

Every passing class stays green.
Before completing a task, run the focused tests and the whole suite.
Fix any regression before completion; unrelated failures are not expected red.
Do not silence failures, weaken assertions, or change expected results to fit code.

## 7. Forbidden shortcuts

Never:
- disable tests;
- turn precise assertions into “not null” checks;
- catch all exceptions;
- return fake success from dummies;
- assert the dummy exception as the desired behavior;
- change expected results to match the implementation; or
- count an unavailable live service as a passing integration.

## 8. Review and commit

Work on one task at a time and change only the files it names.
Review each plan before it runs.
A change to the files, the intended behavior, or the acceptance checks means the plan is reviewed again.
Review the diff and acceptance results, then make one commit per task with a short, neutral message.
A task is complete when the suite passes and the commit is made.
