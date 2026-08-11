# 21 — Tool Vocabulary

Module: `:core-agent` (registry), `:core-grammar` (schema → GBNF).

Ten tools. The count is a budget, not a coincidence: every tool's name and
argument schema occupies context on every single turn, and a 1B model's
selection accuracy degrades as the menu grows. A new tool must justify its
permanent token cost.

---

## 1. The plan envelope

A turn produces exactly one JSON object:

```json
{ "steps": [ { "tool": "create_node", "args": { … } } ] }
```

- `steps` holds 1 to `agent.max_steps` entries (default 8, see
  `26-settings.md`).
- Order is execution order.
- The grammar makes any other shape unrepresentable — there is no parser
  fallback, no repair regex, and no "try to extract JSON from prose". If
  decoding succeeded, the envelope is valid by construction.

## 2. Node references

Any argument typed `NodeRef` accepts one of two forms:

| Form | Meaning |
|---|---|
| `"n7"` | A node existing on the board when the turn began |
| `"$3"` | The node created by step 3 of *this* plan, 1-indexed |

**Existing ids are enumerated into the grammar each turn.** The grammar is
rebuilt per turn with the live id set as literal alternatives, so a hallucinated
node id is not merely rejected at validation — it is impossible to emit. This is
the single highest-value application of decision D6.

**Step references solve the forward-reference problem.** Under plan-then-execute
there is no observation feedback, so a plan that creates a village and then
places a tavern next to it cannot know the village's id. `$1` names it. Without
this, multi-node construction — the headline interaction — would be impossible
in one turn, and the strategy would collapse into one-node-per-turn.

Resolution rules, enforced by the executor:

- `$k` is valid only when `k` is strictly less than the current step index, and
  step `k` is a `create_node` that succeeded. Otherwise `UNRESOLVED_STEP_REF`.
- The grammar restricts `$k` alternatives to `$1` … `$(max_steps - 1)`.
  Ordering is checked at execution, not by the grammar.

## 3. The tools

Argument names are deliberately short and concrete. Optional arguments are
marked `?` and may be omitted entirely.

### `create_node`

```
type        PLACE | CHARACTER | OBJECT | NOTE | GROUP
label       string, 1–48 chars
kind?       string, 0–32 chars
at?         Placement, default Auto
attributes? map<string,string>, max 12 entries
color?      Color enum, default DEFAULT
size?       Size enum, default MEDIUM
```

Maps to `Board.addNode`. Its resolved cell and allocated id are recorded so
later steps can reach it via `$k`.

### `update_node`

```
node        NodeRef
label?      string
kind?       string
set?        map<string,string>   merged into attributes
unset?      list<string>         attribute keys to remove
color?      Color
size?       Size
```

`set` merges rather than replaces; `unset` removes named keys. There is no
whole-map replacement, because replacement makes a small model silently destroy
attributes it did not mention. Omitting an argument always means "leave alone".

This is the tool behind "make the blacksmith secretly a vampire" —
`set: {secret: "vampire"}`.

### `move_node`

```
node        NodeRef
to          Placement
```

### `delete_node`

```
node        NodeRef
```

Cascades to incident edges. Subject to the confirmation gate in
`23-agent-runtime.md`.

### `connect`

```
from        NodeRef
to          NodeRef
relation    CONTAINS | CONNECTS | KNOWS | FEARS | OWNS | BLOCKS | CUSTOM
label?      string, 1–32 chars; required when relation is CUSTOM
```

The argument is named `relation` rather than `type` to avoid colliding with
`create_node`'s `type` in the model's attention. Small models conflate
identically-named arguments across tools.

### `disconnect`

```
from        NodeRef
to          NodeRef
relation?   EdgeType, omitted means all edges between the pair
```

### `find`

```
text?       substring, case-insensitive, matched against label and kind
type?       NodeType
kind?       exact match, case-insensitive
attribute?  string "key" or "key=value"
```

Returns matching node ids with their labels. At least one argument is required;
an argument-less `find` is `INVALID_ARGS`.

**`find` may appear only as step 1 of a plan**, enforced by the grammar. When
present the runtime executes it, injects the results into context, and re-plans
**exactly once**. Any remaining steps in the original plan are discarded.

This is the concession plan-then-execute has to make. Without observation
feedback the model cannot use a retrieval result mid-plan, so retrieval is
lifted into a bounded pre-pass instead. The single re-plan cap is what stops it
becoming an unbounded loop through the back door. Retrieval rounds are counted
and recorded in the trace.

### `arrange`

```
nodes       list<NodeRef>, 1–50 entries
layout      ROW | COLUMN | GRID | CLUSTER_LEFT | CLUSTER_RIGHT
```

Atomic — see `20-world-model.md` §8.

### `set_setting`

```
key         enum of agent-writable setting keys, see 26-settings.md
value       string, coerced and range-checked against the key's declared type
```

The self-modification hook. Only keys explicitly marked agent-writable are in
the enum, so the grammar cannot express a write to a protected setting.

### `respond`

```
text        string, 1–280 chars
```

**Optional and rarely needed.** When a plan produces no `respond` step, the UI
shows a summary generated deterministically from the board diff. The model
should call it only to ask a question, decline, or explain something the diff
cannot convey.

Making prose optional saves roughly 30–60 output tokens on a typical turn, which
is a meaningful fraction of turn latency at 1B decode speeds. It also avoids
displaying small-model prose when a factual diff summary is both cheaper and
more accurate.

When present, `respond` must be the final step; the grammar enforces this. A
plan consisting of `respond` alone is legal and is the correct output for a
question, a refusal, or a request for clarification — cases where the right
number of world mutations is zero.

## 4. Placement in tool arguments

`Placement` serialises as exactly one of:

```json
{ "rel": "NORTH_OF", "ref": "n3" }
{ "cell": { "row": 1, "col": -2 } }
{ "auto": true }
```

The grammar admits only these three shapes. Semantics are in
`20-world-model.md` §7.

## 5. The registry

```kotlin
interface Tool {
    val name: String
    val schema: ToolSchema        // drives grammar, validation, and docs
    fun execute(args: ToolArgs, ctx: ExecContext): Result<ToolEffect, ToolError>
}
```

`ToolSchema` is the **single source of truth**. Three artefacts are derived from
it and never hand-written:

1. the GBNF grammar (`:core-grammar`),
2. runtime argument validation,
3. the tool descriptions rendered into the prompt.

This is intent criterion L5. A hand-maintained grammar drifts from its tool the
first time an argument is renamed, and the resulting failure — a model emitting
a valid-looking call the executor rejects — is slow and confusing to diagnose. A
snapshot test over the generated grammar makes any drift visible in the diff.

## 6. Tool errors

```kotlin
enum class ToolError {
    INVALID_ARGS, UNRESOLVED_STEP_REF, UNKNOWN_SETTING, SETTING_OUT_OF_RANGE,
    CONFIRMATION_REQUIRED, WORLD_ERROR,   // wraps a WorldError
}
```

Every error carries a message written for a small model to act on during a
repair turn, following the same rule as `20-world-model.md` §9.
