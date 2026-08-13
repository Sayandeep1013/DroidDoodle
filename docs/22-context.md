# 22 — Context Assembly

Module: `:core-agent`.

The prompt is assembled from six ordered blocks. The ordering is fixed and
load-bearing: everything static comes first so the KV cache prefix stays valid
across turns (see `25-inference.md` §4).

```
┌ 1  system rules        static      ~250 tok  ─┐ cached prefix
└ 2  tool descriptions   static      ~530 tok  ─┘
  3  board digest        per-turn    4-250 tok
  4  reference table     per-turn      9 tok when non-empty
  5  recent turns        per-turn    ~150 tok (not yet exercised by the suite)
  6  user message        per-turn     ~10 tok
```

Budget target: **≤1200 tokens** for a typical turn, leaving comfortable room in
a 4k window for the plan output. Blocks 1-2 grew from ~500 to ~780 tokens on
2026-08-13 when worked examples and closed-vocabulary hints were added (§2,
§3) — a measured, deliberate trade of headroom for correctness, not drift.
Blocks 3-4 are measured from real Prompt Suite traces (the tokenizer has been
wired since before this document was last accurate); block 5 is still a design
estimate because no suite case runs a second turn. The worst observed total —
`BOARD_20` fixtures at ~250 board tokens — now lands around 1040, still under
budget but with less slack than before. If a future addition pushes a typical
turn over 1200, something in blocks 1-2 has to be cut to pay for it.

---

## 1. Hard budget enforcement

The assembler takes a `maxContextTokens` limit and degrades in a **fixed
priority order** when over budget. It never silently overflows.

Blocks are shed in this order:

1. Recent turns, oldest first, down to zero.
2. Board digest, dropping the nodes furthest from the viewport centre, replaced
   by a count line.
3. If still over budget → `CONTEXT_OVERFLOW`, surfaced to the user as a real
   error.

Tool descriptions and system rules are never shed. A model missing part of its
tool menu produces confidently wrong calls, which is a worse failure than
refusing the turn.

Every shed decision is recorded in the trace. A turn that went wrong because the
model was not told about a node must be diagnosable as such rather than looking
like a reasoning failure.

## 2. Block 1 — system rules

Static, terse, imperative. It states that the model outputs a plan of tool
calls, that ids come from the digest, that `$k` references earlier steps, and
that omitted optional arguments keep their current values.

**Update, 2026-08-13:** it now contains three worked examples, reversing the
original decision below. The two on-device Prompt Suite runs before this
change both landed at 20% with zero grammar violations — meaning the grammar
was never the bottleneck, the model's *choice* among grammar-valid outputs
was. The traces showed why: asked for a relative position the model emitted
`{"auto":true}` in every observed case rather than `{"rel":"NORTH_OF",...}`;
asked to record a fact it invented a `{"attribute":"x","value":"y"}` shape
lifted from `find`'s unrelated flat convention instead of the plain fact map
`update_node.set` actually takes; asked for several things it emitted one
`create_node` step and stopped, tokens nowhere near exhausted. None of this is
a grammar defect — every one of those outputs parses. It is the exact failure
mode this section's original reasoning left untested. Three examples (one
multi-step chain with a relative placement and a `connect`, one fact-map
write, one `respond`) now demonstrate the shapes above. Cost: roughly 130
tokens, not the 300–600 estimated below, because a compact worked example is
cheaper than a prose-style demonstration. See `results/README.md` for the
before/after.

Original reasoning, kept for the record: it does **not** contain few-shot
examples. Under grammar-constrained decoding the structural work examples
usually do is already guaranteed, so their cost — roughly 300–600 tokens on
every turn, permanently — buys only style. If Prompt Suite results later show
a specific reasoning failure that examples fix, they can be added deliberately
and their cost measured against the improvement.

## 3. Block 2 — tool descriptions

Rendered from `ToolSchema`, not hand-written, so they cannot drift.

One line per tool plus one line per argument.

**Update, 2026-08-13:** closed-vocabulary arguments (`type`, `relation`,
`layout`, `color`, `size`, the `set_setting` key) now spell their domain
directly in the argument's `description`, e.g. "PLACE, CHARACTER, OBJECT,
NOTE, or GROUP". The original decision to omit them, kept below, assumed the
grammar's structural guarantee was the thing worth protecting. It measured
correctly and reasoned incorrectly: on-device traces showed the model
producing the wrong *in-domain* token when the domain was invisible — `GROUP`
for what should have been a `NOTE`, `BLOCKS` for what should have been a
`CONNECTS` edge, and `set_setting` essentially never attempted at all against
ten unguessable dotted keys. A grammar that only prevents the impossible does
nothing about the merely wrong. The relative-placement and fact-map argument
shapes are demonstrated by block 1's worked examples instead of spelled out
here, since they are structures, not flat enumerations.

Original reasoning, kept for the record: enum domains were omitted from the
text, because the grammar already makes out-of-domain values impossible to
emit and restating them doubles the block's size. That deletion was the main
reason a ten-tool menu fit in ~350 tokens; it now costs closer to ~530.

## 4. Block 3 — board digest

A compact line format, not JSON. Braces, quotes and repeated key names cost
roughly 40% more tokens for identical information.

```
n1 place "Village" @0,0
n2 char "Borin" ~blacksmith @1,0 {secret=vampire}
n3 note "Grappling hook" @0,2 #blue
n7 place "Castle" @-1,0 *
e n1>n2 contains, n1-n3 connects, n2>n7 owns
```

Grammar of a node line:

```
<id> <type> "<label>" [~<kind>] @<row>,<col> [{k=v,k=v}] [#<color>] [*]
```

- `type` is lowercased and abbreviated: `place`, `char`, `obj`, `note`, `group`.
- `~kind` is omitted when blank.
- `{…}` is omitted when there are no attributes.
- `#color` is omitted when `DEFAULT`.
- `size` is **never** included — it is presentation-only and the model has no
  reason to reason about it.
- `*` marks a node included because the reference table names it, not because it
  is in the viewport.

Edges are one line, `>` for directed and `-` for symmetric `CONNECTS`. Only
edges with **both** endpoints present in the digest appear; a dangling half-edge
would invite the model to reference an id it cannot see.

### Selection

1. All nodes inside the current viewport, ordered by `(row, col)`.
2. Plus any node named in the reference table, marked `*`.
3. If the result exceeds `agent.digest_max_nodes` (default 25), keep the first
   25 in that order and append:

   ```
   … 14 more nodes off-view. use find to locate them.
   ```

The digest is what makes a 200-node board tractable for a 1B model: it is told
about what is on screen, plus exactly what it might be referring to, and given a
tool for the rest.

## 5. Block 4 — the reference table

```
refs: last_created=n7 last_modified=n3 selected=n5
```

Present only when non-empty. Fields:

| Key | Meaning |
|---|---|
| `last_created` | Node created by the most recent successful turn; the last one if several |
| `last_modified` | Node most recently updated, moved or connected |
| `selected` | Node the user has tapped, if any |

**This table is how anaphora is resolved, and it is deliberately not the model's
job.** "Move that one to the left" resolves by lookup. Asking a 1B to track
referents across conversational turns is the fastest way to make it look
incompetent, whereas three deterministic key-value pairs make it look sharp.
This is the intent document's rule applied at its highest-leverage point.

The table is computed by the runtime from committed turns. It is never written
by the model.

## 6. Block 5 — recent turns

The last `agent.history_turns` (default 2) exchanges, each rendered as:

```
> create a village with a tavern
  created n1 "Village" @0,0, n2 "Tavern" @0,1
> put a blacksmith in it
  created n3 "Borin" @1,0; connect n1>n3 contains
```

Outcomes are **summarised from the executed diff, not from the model's own
output**. Feeding a model its own prior generations back compounds its
mistakes; feeding it what actually happened corrects them.

A turn that failed renders its error, because knowing the last attempt failed
and why is exactly what a repair turn needs:

```
> put the castle north of the village
  FAILED step 1: cell r-1c0 is taken by n9
```

## 7. Block 6 — user message

Verbatim, trimmed, capped at 512 characters. Longer input is rejected before
inference rather than truncated, since a truncated instruction produces a
confidently wrong plan.

## 8. Determinism

Context assembly is a pure function of `(board, viewport, refs, history,
userMessage, settings)`. No clock reads, no iteration over unordered
collections, no ambient state — per architecture rule R4.

Identical inputs must produce a byte-identical prompt. Snapshot tests over
assembled prompts are a primary regression signal, and they only work if this
holds.
