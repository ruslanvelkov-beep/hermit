# Planning Values Contract

This document defines the data structures and selection logic for the agent's planning phase.
All types reside in the `hermit.plan` package, except for `Action` which resides in `hermit.api`.

## 1. Action

A single, actionable step for the agent to perform.

**Package:** `hermit.api`
**Type:** Record
**Signatures:**
```java
public record Action(String toolName, Map<String, Object> parameters)
```
- `toolName`: The name of the tool to invoke.
- `parameters`: A map of arguments for the tool.

## 2. Branch

One proposed path in the decision tree: a named strategy with a confidence score and concrete actions.

**Package:** `hermit.plan`
**Type:** Record
**Signatures:**
```java
public record Branch(String name, String description, double confidence, List<Action> actions)
```
- `name`: Identifier for the branch.
- `description`: Human-readable explanation of the strategy.
- `confidence`: A score between 0.0 and 1.0 indicating likelihood of success.
- `actions`: The list of actions to execute if this branch is selected.

## 3. PlanNode

A single node in the planning tree, capable of holding multiple potential next steps (branches).

**Package:** `hermit.plan`
**Type:** Class
**Fields:**
- `nodeName`: String identifier.
- `goal`: String description of the objective.
- `context`: Map<String, Object> containing gathered information or decisions.
- `reasoningPath`: String describing the thought process for this step.
- `branches`: List<Branch> of potential next steps.

**Signatures:**
```java
public PlanNode(String nodeName, String goal, Map<String, Object> context)
public String getNodeName()
public String getGoal()
public Map<String, Object> getContext()
public String getReasoningPath()
public void setReasoningPath(String path)
public List<Branch> getBranches()
public void setBranches(List<Branch> branches)
```
- Constructor initializes `nodeName`, `goal`, `context`. Initializes `branches` to an empty list. `reasoningPath` is null by default.
- Getters return the respective field values.
- `setReasoningPath` updates the reasoning path.
- `getBranches` returns the list of branches.
- `setBranches` replaces the current list of branches.

## 4. BranchSelector

Determines the best next step based on defined criteria (highest confidence).

**Package:** `hermit.plan`
**Type:** Class
**Signatures:**
```java
public Branch selectBest(List<Branch> options)
```
- `options`: The list of available branches from a node.
- Returns: The best Branch, or null if no valid options exist.

**Outcomes:**
- Highest confidence wins: The branch with the maximum `confidence` value is returned.
- Null or empty input gives null: If `options` is null or empty, the method returns null.
- Tie behavior: If multiple branches share the highest confidence, one of them is returned (implementation-dependent, typically the first encountered in stream order).

## 5. Test Outcomes

The following outcomes are asserted by `hermit.plan.BranchSelectorTest` (5 methods).

- `selectBest_whenOptionsIsNull_returnsNull`: Passing null to `selectBest` returns null.
- `selectBest_whenOptionsIsEmpty_returnsNull`: Passing an empty list to `selectBest` returns null.
- `selectBest_returnsHighestConfidenceBranch`: Passing a list with branches of varying confidence returns the branch with the highest confidence value.
- `selectBest_returnsOneOfTiedBranches`: Passing a list where multiple branches share the highest confidence returns one of those tied branches (non-null, matching confidence).
- `selectBest_returnsSingleBranchWhenOnlyOneExists`: Passing a list with a single branch returns that branch.
