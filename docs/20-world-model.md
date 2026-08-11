# 20 — World Model

Module: `:core-model` (types) and `:core-world` (behaviour).

---

## 1. Coordinates

Cells use **signed integers**. There is no origin corner and no array backing.

```kotlin
data class Cell(val row: Int, val col: Int)
```

- `row` increases **southward**. `col` increases **eastward**.
- Bounds: `-32 <= row <= 32` and `-32 <= col <= 32`, giving a 65×65 addressable
  field. Anything outside is `OUT_OF_BOUNDS`.
- The board has no stored dimensions. **Extent is derived** from occupied cells.

Signed coordinates exist to avoid a specific bug class: with 0-based indices,
placing something north of row 0 forces every node on the board to shift, which
corrupts undo records and invalidates ids held in the reference table. Signed
coordinates make northward growth a no-op.

**One node per cell.** Occupancy is the collision model, and it is total: no two
nodes may share a cell. This is what makes collision detection a map lookup.

## 2. Node

```kotlin
data class Node(
    val id: NodeId,                       // "n1", "n2", … monotonic per board
    val type: NodeType,                   // closed enum
    val kind: String,                     // free text, may be blank
    val label: String,                    // free text, non-blank
    val cell: Cell,
    val attributes: Map<String, String>,  // free-form
    val style: Style,
)

enum class NodeType { PLACE, CHARACTER, OBJECT, NOTE, GROUP }

data class Style(val color: Color, val size: Size)
enum class Color { DEFAULT, RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, GRAY }
enum class Size  { SMALL, MEDIUM, LARGE }
```

This is decision D3 made concrete. `type` is closed because the engine reasons
about it — it drives rendering, iconography, and which edges are legal. `kind`,
`label` and `attributes` are open because the engine never reasons about them;
they are payload. A blacksmith is `type=CHARACTER, kind="blacksmith"`. A
vampire secret is `attributes["secret"] = "vampire"`.

### Field limits

Enforced at validation, chosen to bound the context budget in `22-context.md`:

| Field | Limit |
|---|---|
| `label` | 1–48 characters, non-blank after trim |
| `kind` | 0–32 characters |
| `attributes` | at most 12 entries; keys 1–24 chars, values 0–96 chars |
| attribute keys | lowercase `[a-z0-9_]+`, normalised on write |

Exceeding a limit is a validation error, never a silent truncation. Silent
truncation would make the trace lie about what happened.

### Ids

`NodeId` is a value class wrapping a `String` of the form `n<positive integer>`.
Counters are **per board and monotonic, never reused** — deleting `n7` does not
free the id. Reuse would let a stale reference in the reference table or an undo
record silently address a different node.

Short ids are a deliberate context-budget decision: every id appears in the
viewport digest and in every tool argument, so `n7` versus a UUID is a
meaningful token saving across a full prompt.

## 3. Edge

```kotlin
data class Edge(
    val id: EdgeId,                 // "e1", "e2", … monotonic per board
    val type: EdgeType,
    val from: NodeId,
    val to: NodeId,
    val label: String,              // free text; required non-blank when type == CUSTOM
)

enum class EdgeType { CONTAINS, CONNECTS, KNOWS, FEARS, OWNS, BLOCKS, CUSTOM }
```

- All edge types are **directed** except `CONNECTS`, which is symmetric. For
  `CONNECTS`, an edge from A to B and one from B to A are the same edge;
  creating the second is a no-op that returns the existing edge id.
- Duplicate edges — same `type`, `from`, `to` — are rejected as
  `DUPLICATE_EDGE`.
- Self-edges are rejected as `SELF_EDGE`.
- `CUSTOM` requires a non-blank `label` of 1–32 characters. This is the escape
  hatch for relations the closed enum does not cover, and it keeps the enum from
  growing every time the model wants to say something new.

## 4. Containment is an edge

There is **no spatial nesting**. A blacksmith inside a village is a `CONTAINS`
edge from the village node to the blacksmith node. Both occupy their own cells
on the same flat grid.

The renderer draws a tinted convex hull around a container and its members. That
is a presentation concern with no representation in state.

This is a load-bearing decision. Nested coordinate systems — cells inside cells —
require every spatial operation to know which frame it is operating in, which is
exactly the kind of thing a 1B model gets wrong and which makes spatial unit
tests combinatorial. A flat grid keeps `north_of` meaning one thing everywhere.

### Containment invariants

- **I1** — The `CONTAINS` graph must remain acyclic. Any operation closing a
  cycle fails with `CONTAINMENT_CYCLE`.
- **I2** — A node may have at most one incoming `CONTAINS` edge. Adding a second
  fails with `ALREADY_CONTAINED`. Membership is exclusive.
- **I3** — Containment depth is capped at 4. Deeper nesting fails with
  `CONTAINMENT_TOO_DEEP`, bounding both rendering work and digest size.
- **I4** — Containment does not constrain position. A contained node may sit
  anywhere. The renderer draws the hull wherever the members are, however
  scattered.

`GROUP` is an ordinary node type that conventionally has no meaning beyond
containing things. It is not a special case in code.

## 5. Board

```kotlin
data class Board(
    val nodes: Map<NodeId, Node>,
    val edges: Map<EdgeId, Edge>,
    val occupancy: Map<Cell, NodeId>,   // derived; maintained as an index
    val nextNodeSeq: Int,
    val nextEdgeSeq: Int,
)
```

`Board` is an immutable value. Every mutation returns a new instance. Structural
sharing makes this cheap at the scale involved, and immutability is what makes
undo a matter of holding a reference rather than replaying a log.

`occupancy` is **derived on demand from `nodes`**, not stored. The original
design called for a maintained index; deriving it makes desynchronisation
impossible by construction, which is strictly stronger. The invariant left for
property tests is the one that can still be violated: that no operation ever
places two nodes in the same cell.

Deleting a container removes its contents. That is more surprising than
orphaning them, which is precisely why the confirmation gate in
`23-agent-runtime.md` §7 fires on any container deletion regardless of count —
"delete the village" should mean what a person means by it, with the blast
radius shown before it happens.

**Board size cap: 200 nodes.** Beyond this, `create_node` fails with
`BOARD_FULL`. The cap is not a performance limit — it is an honesty limit. A
board larger than this cannot be meaningfully summarised inside a 1B model's
context window, and pretending otherwise produces confident nonsense.

## 6. Viewport

```kotlin
data class Viewport(val top: Int, val left: Int, val rows: Int, val cols: Int)
```

Default `Viewport(top = -4, left = -4, rows = 8, cols = 8)`, an 8×8 window around
the origin. `rows` and `cols` are each clamped to 1–16.

The viewport is UI state, not board state, but it is an **input to context
assembly** — it determines which nodes the model is told about. See
`22-context.md`.

## 7. Placement

The model never emits coordinates for relative placement. It states intent; the
engine resolves it. This is the intent document's rule that anything
deterministic belongs to the runtime.

```kotlin
sealed interface Placement {
    data class Relative(val relation: Relation, val ref: NodeId) : Placement
    data class Absolute(val cell: Cell) : Placement
    data object Auto : Placement
}

enum class Relation { NORTH_OF, SOUTH_OF, EAST_OF, WEST_OF, NEXT_TO }
```

### Resolution algorithm

Fully deterministic. Given identical board and placement, the result is always
identical — there is no randomness and no tie-breaking by iteration order of a
hash map.

**`Absolute(cell)`**
1. Out of bounds → `OUT_OF_BOUNDS`.
2. Occupied → `CELL_OCCUPIED`.
3. Otherwise return `cell`.

Absolute placement never searches for an alternative. If the model asked for an
exact cell, silently moving it elsewhere would make the trace misleading.

**`Relative(relation, ref)`**
1. `ref` unknown → `UNKNOWN_REF`.
2. For `NEXT_TO`, try the four orthogonal neighbours of the reference in the
   fixed order **E, W, S, N** and return the first free in-bounds cell. If all
   four are taken, fall through to the ring search in step 4 centred on the
   reference.
3. For the four directional relations, generate candidates that **preserve the
   relation**, ordered by **Manhattan distance from the reference**, then by
   primary-axis distance, then by lateral distance, then by lateral sign in the
   fixed order negative-then-positive. For `NORTH_OF` from `(r, c)`:

   ```
   sum 1   (r-1,c)
   sum 2   (r-1,c-1)  (r-1,c+1)  (r-2,c)
   sum 3   (r-1,c-2)  (r-1,c+2)  (r-2,c-1)  (r-2,c+1)  (r-3,c)
   sum 4   (r-1,c-3)  (r-1,c+3)  (r-2,c-2)  (r-2,c+2)  (r-3,c-1) …
   ```

   Both primary-axis distance and lateral offset are capped at 4. The first
   free in-bounds candidate wins.

   Note that the sequence returns to nearer rows at wider lateral offsets
   before advancing further north — `(r-1,c-2)` precedes `(r-2,c-1)`. That is
   what "nearest free cell preserving the direction" means under a Manhattan
   metric, and it is worth stating explicitly because an earlier draft of this
   document showed a truncated example implying a strict row-by-row walk, which
   the rule does not produce.
4. If no candidate is free → `NO_FREE_CELL`.

The invariant this guarantees is directional, not exact. After a successful
`NORTH_OF`, `placed.row < ref.row` **always** holds; `placed.row == ref.row - 1`
holds **when that cell was free**, which is the common case and the precondition
used by the Prompt Suite. Preserving the axis rather than spiralling in all
directions is what keeps "north of" from quietly meaning "somewhere near".

**`Auto`**
1. Empty board → `Cell(0, 0)`.
2. Otherwise compute the centroid of occupied cells, rounding each coordinate
   half-up, and ring-search outward from it by Chebyshev distance, starting at
   radius 0. Each ring is walked **clockwise from its north-west corner**.
   Return the first free in-bounds cell.

   A clockwise ring walk is specified rather than "compass order" because
   compass order only determines an ordering at radius 1; beyond that a ring
   holds more than eight cells and the phrase stops being a specification.
3. Radius is capped at 8 → `NO_FREE_CELL`.

## 8. Operations

`:core-world` exposes exactly these, each returning
`Result<BoardChange, WorldError>`:

| Operation | Notes |
|---|---|
| `addNode(type, kind, label, placement, attributes, style)` | Resolves placement, allocates id |
| `updateNode(id, label?, kind?, attributes?, style?)` | Null argument means leave unchanged |
| `moveNode(id, placement)` | Vacates the old cell before resolving, so a node may move relative to itself |
| `removeNode(id)` | Cascades into everything transitively contained, plus all edges incident to any removed node |
| `addEdge(type, from, to, label)` | Enforces I1, I2, I3, duplicate and self-edge rules |
| `removeEdge(from, to, type?)` | Null type removes all edges between the pair |
| `arrange(ids, layout)` | Repositions a set; see below |

`moveNode` vacating first is not an optimisation — without it, moving a node
`NEXT_TO` something adjacent to itself can fail against its own occupancy.

### `arrange`

```kotlin
enum class ArrangeLayout { ROW, COLUMN, GRID, CLUSTER_LEFT, CLUSTER_RIGHT }
```

Input order of `ids` is preserved as placement order in all layouts.

- `ROW` — one row, consecutive columns, starting at the westmost current cell of
  the set, on the row of the set's first node.
- `COLUMN` — one column, consecutive rows, mirroring `ROW`.
- `GRID` — a square-ish block, `ceil(sqrt(n))` columns wide, filled left to
  right then top to bottom, anchored at the set's north-west-most current cell.
- `CLUSTER_LEFT` / `CLUSTER_RIGHT` — packs the set into a column-major block on
  the western or eastern side of the current board extent, two columns wide.
  This is what serves "put the important ones on the left".

`arrange` operates as a single atomic transaction: all target cells are computed
against a board with the whole set lifted out, so members never collide with
each other. If any target is blocked by a **non-member** node, the whole
operation fails with `ARRANGE_BLOCKED` and nothing moves.

## 9. Errors

```kotlin
enum class WorldError {
    OUT_OF_BOUNDS, CELL_OCCUPIED, NO_FREE_CELL, UNKNOWN_REF, UNKNOWN_NODE,
    UNKNOWN_EDGE, DUPLICATE_EDGE, SELF_EDGE, CONTAINMENT_CYCLE,
    ALREADY_CONTAINED, CONTAINMENT_TOO_DEEP, BOARD_FULL, INVALID_FIELD,
    ARRANGE_BLOCKED,
}
```

Every error carries a short human-readable message. That message is fed back to
the model verbatim on a repair turn, so it is written for a small model to act
on: `"cell r1c2 is taken by n4"` rather than `"CELL_OCCUPIED"`.

## 10. Undo

```kotlin
data class BoardChange(
    val board: Board,           // resulting state
    val diff: List<CellDelta>,  // for rendering and trace
)
```

Undo does **not** replay inverse operations. Each committed turn pushes the
**previous immutable `Board` reference** onto an undo stack, depth 20. Undo pops
and restores. Because `Board` is immutable with structural sharing, this is
cheap and exactly correct — it cannot drift from the real prior state the way a
hand-written inverse operation can.

Undo granularity is **one turn, not one tool call**. A plan that creates a
village, a tavern and a smith is undone in a single step, which matches what the
user perceives as one action. Turn boundaries and partial-failure interaction are
specified in `23-agent-runtime.md`.

The redo stack holds the same references and is cleared on any new committed
turn.
