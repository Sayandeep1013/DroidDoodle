# 23 — Agent Runtime

Module: `:core-agent`. Pure Kotlin, no I/O, fully testable against `MockEngine`.

---

## 1. Strategy interface

```kotlin
interface LoopStrategy {
    val id: String
    suspend fun run(request: TurnRequest, deps: TurnDeps): TurnResult
}
```

Three implementations are declared. Only the first is built for the MVP; the
others exist as registered stubs that throw `NotImplementedError`, which keeps
the seam honest — a seam nobody has ever passed a second implementation through
is usually the wrong shape.

| Strategy | Status |
|---|---|
| `PlanThenExecuteStrategy` | MVP default (decision D4) |
| `ReActStrategy` | Phase 2 stub |
| `SingleShotStrategy` | Phase 2 stub |

The strategy is selected by the `agent.loop_strategy` setting. Satisfying intent
criterion L2 means a strategy may touch only `TurnDeps`; it may not reach into
tool definitions or `:core-world` directly.

## 2. Turn lifecycle

`PlanThenExecuteStrategy` runs these phases in order. Each records into the
trace.

```
 1  ASSEMBLE     build the prompt              22-context.md
 2  GRAMMAR      build GBNF for this turn      25-inference.md §3
 3  GENERATE     one constrained generation
 4  PARSE        deserialise the envelope
 5  RETRIEVE     if step 1 is find → run, re-assemble, once only
 6  VALIDATE     static checks over the whole plan
 7  CONFIRM      suspend for the user if the gate trips
 8  EXECUTE      steps in order, halting on first failure
 9  COMMIT       undo entry, refs, history
10  TRACE        persist the record
```

### Phase 4 — parse

Deserialisation is expected to be infallible: the grammar makes malformed output
unrepresentable. If it fails anyway, that is a **grammar defect**, not a model
defect. It is recorded as `GRAMMAR_VIOLATION`, the turn aborts with the board
untouched, and the raw output is preserved verbatim in the trace for diagnosis.

There is no regex extraction, no JSON repair, and no retry-on-parse-failure.
Those mechanisms hide exactly the defect we want reported.

### Phase 5 — retrieval

If step 1 is `find`, the runtime executes it, appends the results to context as
a `found:` block, discards the remaining steps, and returns to phase 1. This
happens **at most once per turn** (`retrievalRounds <= 1`), and the count is
recorded. A second `find` in the re-planned output is a static validation error,
`RETRIEVAL_EXHAUSTED`.

### Phase 6 — static validation

Checked across the whole plan before anything mutates:

- argument types and field limits (`20-world-model.md` §2),
- `$k` ordering — `k < currentStep`, and step `k` is a `create_node`,
- setting keys exist, are agent-writable, and values are in range,
- `respond`, if present, is the final step,
- `find` does not appear after step 1,
- step count within `agent.max_steps`.

**Any static failure rejects the entire plan. The board is not touched and no
undo entry is created.** These are checks that cannot become true later, so
partially applying a plan already known to be broken only creates mess to clean
up.

### Phase 7 — confirmation gate

Computed after validation, before execution. Confirmation is required when
either holds:

- the plan's `delete_node` steps would remove more than
  `agent.confirm_threshold` nodes in total (default 3), counting cascade
  deletions of contained descendants; or
- any `delete_node` targets a node with at least one outgoing `CONTAINS` edge,
  regardless of count.

The second rule exists because deleting a container is the one action whose
blast radius the user is least likely to have pictured.

The runtime suspends and surfaces the exact list of nodes to be destroyed.
Declining aborts the turn with the board untouched. Confirmation state is part
of `TurnResult`, not a UI callback, so it stays testable headlessly.

### Phase 8 — execution

Steps execute in order against a working `Board`. Dynamic failures — cell
occupied, containment cycle, unresolvable placement — can only be detected here,
because earlier steps change what is true.

On the first failure the runtime **halts and keeps what already succeeded.**

Partial commit is a deliberate choice over all-or-nothing. Watching a village
appear and a smith fail to place is both friendlier and more instructive than
watching nothing happen, and it matches how a person would react to running out
of room. The trace records exactly which step failed, so the outcome is never
ambiguous.

### Phase 9 — commit

Only if at least one step mutated the board:

1. push the pre-turn `Board` reference onto the undo stack (depth 20),
2. clear the redo stack,
3. recompute the reference table (`22-context.md` §5),
4. append the turn summary to history.

A turn where every step failed, or which contained only `respond`, commits
nothing and creates no undo entry. Undo must never consume a step that did not
change anything.

## 3. Repair turns

When execution halts on a dynamic failure, the runtime **does not retry
automatically** unless `agent.auto_repair` is enabled (default **off**).

With it off, the failure is reported and the next user message naturally carries
the failure into context via block 5, so the user may simply say "try again"
with the model now knowing what went wrong.

With it on, the runtime re-plans once with the error in context, capped at one
repair per turn. Repairs are counted in the trace.

The default is off because a hidden extra inference doubles worst-case turn
latency on a device already measured in seconds per turn, and because for a
learning lab an observed failure is more valuable than a silently patched one.

## 4. Runaway protection

Three independent bounds, all recorded in the trace when hit:

| Bound | Default | Setting |
|---|---|---|
| Steps per plan | 8 | `agent.max_steps` |
| Retrieval rounds per turn | 1 | not configurable |
| Repair rounds per turn | 0 or 1 | `agent.auto_repair` |

The maximum inferences for a single user message is therefore **three**: initial
plan, one retrieval re-plan, one repair. This ceiling is a hard property of the
strategy, not an emergent behaviour, which is what makes worst-case latency
predictable.

## 5. Turn result

```kotlin
data class TurnResult(
    val outcome: Outcome,              // OK | PARTIAL | REJECTED | ABORTED | AWAITING_CONFIRMATION
    val board: Board,                  // resulting state; unchanged on REJECTED/ABORTED
    val executed: List<StepOutcome>,
    val failure: FailureInfo?,
    val summary: String,               // deterministic, diff-derived
    val respondText: String?,          // only when the model called respond
    val trace: TraceRecord,
)
```

`summary` is generated from the diff, never by the model — see `21-tools.md` §3.
`"created Village, Tavern; connected Village → Tavern"` is cheaper and more
accurate than asking a 1B to narrate its own work.

## 6. Determinism

Given a fixed `MockEngine` script and identical inputs, a turn produces an
identical `TurnResult` and an identical `TraceRecord` apart from injected
timestamps. `Clock` and `IdGenerator` are injected (architecture rule R4).

This is what lets the Prompt Suite run as ordinary CI assertions rather than as
a flaky integration test.
