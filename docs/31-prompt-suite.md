# 31 — Prompt Suite

The behavioural benchmark. Backs intent criteria P1–P5.

---

## 1. Two modes, one fixture set

The same fixtures run in two modes, and confusing them would make the results
meaningless.

| Mode | Engine | What it proves | Where it runs |
|---|---|---|---|
| **RUNTIME** | `MockEngine` with a scripted plan per case | The runtime executes a *correct* plan correctly — placement, validation, cascade, undo, confirmation | CI, every push, no device |
| **MODEL** | Real `LlamaEngine` | The model *produces* a correct plan from natural language | On device, manually, results committed |

RUNTIME mode is a strict regression gate: any failure is a bug and the build
goes red. MODEL mode is a measurement: its pass rate is a research result, not a
gate. A red RUNTIME suite means we broke something. A 60% MODEL pass rate means
we learned something.

## 2. Fixture format

```kotlin
data class SuiteCase(
    val id: String,                    // "create-03"
    val category: Category,
    val initialBoard: BoardFixture,    // named starting state
    val history: List<String>,         // prior user messages, for anaphora cases
    val message: String,               // the prompt under test
    val scriptedPlan: String,          // RUNTIME mode: the JSON envelope
    val assertions: List<Assertion>,
    val expectedOutcome: Outcome,
)
```

Nodes are referenced in assertions **by label**, never by id, since ids depend
on allocation order.

### Assertions

```
nodeExists(label, type?, kind?)
nodeAbsent(label)
nodeCount(n)
attrEquals(label, key, value)
cellEquals(label, row, col)
northOf(a, b) | southOf(a, b) | eastOf(a, b) | westOf(a, b)
edgeExists(from, to, type)
edgeAbsent(from, to, type?)
boardMatchesSnapshot(name)
outcomeIs(outcome)
settingEquals(key, value)
confirmationRequested(nodeLabels)
```

Directional assertions check the **inequality**, not exact adjacency —
`northOf(a, b)` asserts `a.row < b.row`, matching the guarantee in
`20-world-model.md` §7. Cases needing exact adjacency use `cellEquals` and
declare a starting board where the target cell is free.

### Starting boards

Four named fixtures, so cases stay readable:

- `EMPTY` — no nodes.
- `VILLAGE` — `Village @0,0`, `Tavern @0,1`, `Borin @1,0` (character, kind
  `blacksmith`), with `Village CONTAINS Tavern` and `Village CONTAINS Borin`.
- `CROWDED` — `VILLAGE` plus nodes filling every cell in rows −1..1, columns
  −1..1, used to force placement failures.
- `BOARD_20` — twenty nodes spread beyond an 8×8 viewport, used for digest
  truncation and `find`.

## 3. Cases

| id | Category | Board | Message | Key assertions |
|---|---|---|---|---|
| create-01 | Create | EMPTY | create a village | nodeExists(Village, PLACE), nodeCount(1) |
| create-02 | Create | EMPTY | make a note that says grappling hook | nodeExists(grappling hook, NOTE) |
| create-03 | Create | VILLAGE | add a castle | nodeCount(4) |
| multi-01 | Compose | EMPTY | create a village with a tavern and a blacksmith | nodeCount(3), nodeExists(Tavern), nodeExists(blacksmith kind) — **intent P1** |
| multi-02 | Compose | EMPTY | make a dungeon with three rooms | nodeCount(4), 3 CONTAINS edges |
| multi-03 | Compose | VILLAGE | add a forest north of the village and a river between them | nodeCount(5), northOf(Forest, Village) |
| multi-04 | Compose | EMPTY | create five frogs | nodeCount(5) |
| modify-01 | Modify | VILLAGE | make the blacksmith secretly a vampire | attrEquals(Borin, secret, vampire), nodeCount(3) — **intent P2** |
| modify-02 | Modify | VILLAGE | rename the tavern to The Rusty Anchor | nodeExists(The Rusty Anchor), nodeAbsent(Tavern) |
| modify-03 | Modify | VILLAGE | make the village blue | style color BLUE |
| move-01 | Spatial | VILLAGE | move the castle north of the village | nodeCount(3) unchanged; plan is `respond` only — no castle exists and the grammar cannot name one |
| move-02 | Spatial | VILLAGE + Castle @2,2 | put the castle north of the village | cellEquals(Castle, −1, 0) — **intent P3** |
| move-03 | Spatial | VILLAGE | move the tavern west of the village | westOf(Tavern, Village) |
| move-04 | Spatial | CROWDED | move the tavern north of the village | northOf holds, cell ≠ −1,0 (fallback search) |
| connect-01 | Relation | VILLAGE | the blacksmith is afraid of frogs | attr or FEARS edge |
| connect-02 | Relation | VILLAGE | connect the tavern to the blacksmith | edgeExists(Tavern, Borin, CONNECTS) |
| connect-03 | Relation | VILLAGE | the blacksmith owns the tavern | edgeExists(Borin, Tavern, OWNS) |
| delete-01 | Delete | VILLAGE | delete the tavern | nodeAbsent(Tavern), nodeCount(2) |
| delete-02 | Delete | VILLAGE | delete the village | confirmationRequested — container rule |
| delete-03 | Delete | BOARD_20 | delete everything except the village | confirmationRequested, threshold exceeded |
| anaph-01 | Reference | VILLAGE | history: "add a castle" → make it red | last_created resolves; Castle is red |
| anaph-02 | Reference | VILLAGE | history: "add a castle" → move that west of the village | westOf(Castle, Village) |
| anaph-03 | Reference | VILLAGE | history: "make the blacksmith a vampire" → give him a hammer too | targets Borin |
| anaph-04 | Reference | VILLAGE | undo that | boardMatchesSnapshot(VILLAGE) — **intent P4** |
| arrange-01 | Layout | BOARD_20 | line up the characters in a row | all CHARACTER share a row |
| arrange-02 | Layout | BOARD_20 | put the important ones on the left | CLUSTER_LEFT applied |
| setting-01 | Settings | VILLAGE | make yourself more creative | settingEquals(model.temperature, raised) |
| setting-02 | Settings | VILLAGE | stop asking me before deleting things | agent.confirm_threshold raised |
| fail-01 | Failure | CROWDED | put a castle north of the village | PARTIAL or REJECTED, error names the occupied cell |
| fail-02 | Failure | VILLAGE | put the village inside the tavern | CONTAINMENT_CYCLE |
| fail-03 | Failure | VILLAGE | delete n99 | RUNTIME-only, `OutputCheck.None`; asserts the executor rejects UNKNOWN_NODE even though the grammar makes it unemittable |
| find-01 | Retrieval | BOARD_20 | make the dragon angry | find as step 1, exactly one retrieval round |
| ambig-01 | Ambiguity | VILLAGE | make it better | respond asking for clarification, board unchanged |
| ambig-02 | Ambiguity | EMPTY | what is the capital of France | board unchanged; no world mutation |

`ambig-02` is not a trivia test. It checks that an out-of-world question does not
produce spurious board mutations — a small model's most likely failure when
handed something its tools cannot address.

## 4. Scoring

A case **passes** only when every assertion holds and the outcome matches.
Partial credit is not recorded; a plan that creates two of three requested nodes
failed.

MODEL mode reports:

- overall pass rate,
- pass rate per category,
- median and p90 turn latency,
- mean prompt and output tokens,
- count of grammar violations (expected: zero — any occurrence is a grammar
  defect, per `23-agent-runtime.md` §4).

Results are committed to `results/<model-id>-<date>.md` alongside the exported
traces. Committing them is what turns an impression into a comparison, and it is
how intent criterion P5 is satisfied.

## 5. Execution

- RUNTIME mode is an ordinary JUnit suite in `:core-agent`, part of the `jvm` CI
  job.
- MODEL mode runs through a hidden developer screen that executes all cases
  sequentially against the loaded model and exports a results bundle. It is not
  part of CI, since it needs a device and a model file.
