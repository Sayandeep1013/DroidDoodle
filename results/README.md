# Results

Measured runs, committed as they happen. A poor result is a finding and gets
published as one — see `docs/40-IMPLEMENTATION-PLAN.md`, P10.

## `gemma-3-1b-it-qat-q4_0-2026-08-13-run3`

**7 of 35 passed (20.0%), same as run 2. Zero grammar violations. Median turn
11.4s.** The headline number did not move, but the composition of the failures
did, and that composition is the actual finding.

The three fixes queued after run 2 landed as expected: `create-01`, `multi-02`
and `multi-04` no longer hit the step-1 reference trap (`create-03` still
fails, but now for an unrelated reason). No case was thrown; the denominator
held at 35.

**The flat pass rate sent this run's analysis into the traces rather than the
summary, and that is where the real signal was.** Cross-referencing all 28
failing `rawOutput` values against the `PlanEnvelopeChecker` output showed a
pattern the numbers alone hid: the model is not failing to reason about the
board. It is reliably guessing the wrong *shape* for anything the system
prompt only described in prose rather than showed.

- **Relative placement.** Every case needing `{"rel":"NORTH_OF",...}"` instead
  got `{"auto":true}` (`move-02`, `move-03` — passed only because auto
  placement happened to land west by luck, `move-04`, `anaph-02`, `delete-03`,
  `fail-01`). `move-04` and `connect-01`/`connect-03` show a second pattern on
  top of this: when the model doesn't know how to express the request, it
  defaults to acting on `n1` (the first node listed) with placeholder
  arguments, rather than the node the message actually named.
- **The fact-map shape.** `modify-01` ("make the blacksmith secretly a
  vampire") wrote `"set":{"attribute":"color","value":"red"}` — a shape
  borrowed from `find`'s unrelated flat `attribute=value` convention, applied
  to `update_node.set`, which just wants `{"secret":"vampire"}`. Same failure
  in `connect-01`/`connect-03` and `ambig-01`.
- **Enum vocabulary invisible by design** (`docs/22-context.md` §3, as
  written before this run). `create-02` wrote `type: GROUP` for a note it
  should have typed `NOTE`; `multi-02` typed a dungeon room `GROUP` too;
  `connect-02` invented the edge type `BLOCKS` where `CONNECTS` was needed;
  `setting-01`/`setting-02` never attempted `set_setting` at all against ten
  unguessable dotted keys; `arrange-02` never attempted `arrange`.
- **Single-step truncation on compound requests.** Every multi-node case
  starting from an empty board (`multi-01`, `multi-02`, `multi-04`, and
  `fail-04`'s "make a lot of things") produced exactly one `create_node` step
  and stopped, with `outputTokens` nowhere near the 384 budget. This is P1's
  failure mode directly: `multi-01` ("village with a tavern and a blacksmith")
  produced one node.
- **The reference table, ignored.** `anaph-01` and `anaph-02` both target `n1`
  instead of `refs.lastCreated`, the same "default to the first node" pattern
  as above. `docs/22-context.md` §5 calls this table the highest-leverage
  point for anaphora; on this evidence it was not being read at all.
- **`respond` almost never chosen.** `ambig-01`, `ambig-02`, `move-01`, and
  `find-01` (which had everything it needed already in the digest, no `find`
  required) all needed `respond` and instead got a hallucinated action.
- **Two assertions were too weak to catch any of this.** `modify-03` ("make
  the village blue") passed while the model wrote `"kind":"blue"` and never
  touched `color`; `anaph-01` passed while the model edited the wrong node
  entirely. Both checked only outcome and node count. Fixed in this commit —
  see below — which is why the true baseline this run establishes is
  arguably *below* 20%, not at it.

None of this is a grammar defect. Every one of the outputs above **parses**;
`PlanEnvelopeChecker` and the sampler still agree. It is model choice among
grammar-valid outputs, and the traces show *why* the model chose wrong: it was
never shown the shapes or the vocabulary it needed, only told about them in
prose (`docs/22-context.md` §2 flagged this exact possibility and deferred it
pending evidence — this run is that evidence).

**Fixed in the same commit as this run, not yet re-measured on device:**
three worked examples in the system prompt (relative placement, fact map,
`respond`), closed-vocabulary hints on `type`/`relation`/`layout`/`color`/
`size`/the `set_setting` key, a casing bug in the old placement prose
("north_of" vs the grammar's `NORTH_OF`), and the two weakened assertions
above. See `docs/22-context.md` §2-3 for the reasoning and the token-budget
accounting. **This is a hypothesis, reasoned from every failing trace in this
run, not a verified fix** — RUNTIME mode and `ContextAssemblerTest` are green,
which proves the prompt still assembles correctly and deterministically. It
does not prove a real model produces different output. Only a fourth device
run does that.

| | run 2 | run 3 |
|---|---|---|
| prefill (median) | 512 ms | 560 ms |
| decode (median) | 8,940 ms | 10,290 ms |
| turn total (median) | 10,809 ms | 11,432 ms |

Latency moved a little, within noise; nothing this run changed the decode
path. Decode remains the bottleneck at ~4.7 tok/s (mean 61 output tokens over
a 10.3s median decode).

## `gemma-3-1b-it-qat-q4_0-2026-08-12-run2`

The first run whose numbers mean anything: the grammar fix and KV prefix reuse
both landed before it.

**7 of 35 passed (20%). Zero grammar violations. Median turn 10.8s.**

Zero grammar violations is the P8 acceptance criterion, and it is the first
evidence that `PlanEnvelopeChecker` and the real llama.cpp sampler agree. The
grammar held.

Prefix reuse worked, and by more than expected:

| | run 1 | run 2 |
|---|---|---|
| prefill | 14,110 ms | **512 ms** |
| decode | 10,464 ms | 8,940 ms |
| turn total | 25,083 ms | **10,809 ms** |
| prefill share | 56% | **14%** |

Median reuse was 97% of the prompt, and only the very first round of the run was
cold. Turn latency fell 57%. **Decode is now the bottleneck** at ~4.9 tok/s, and
no amount of caching touches that — it is a function of the model and the
device, which makes it P10's question rather than an engineering one.

### The 20% is a real number, with two caveats now fixed

Three cases (`create-01`, `multi-02`, `multi-04`) failed as *"step 1 argument
'node' refers to $1, which has not run yet"*. That is a trap of our own making.
On an empty board `existing` has no alternatives, so `noderef` collapses to
`stepref` alone — every relative reference the grammar offers at step 1 is
guaranteed to fail static validation. The model is being led somewhere it can
only lose. `docs/25-inference.md` §3 anticipated this and judged it rare; it is
in fact 9% of the suite. Fixing it needs the grammar unrolled per step index so
step 1 has no steprefs at all.

`multi-01` failed partly because the model wrote "village" and the edge and
position assertions were case-sensitive while `nodeExists` was not — the failure
text printed `have: [village]` directly beside "no node labelled 'Village'".
Fixed; label casing is cosmetic and the suite measures structure.

## `gemma-3-1b-it-qat-q4_0-2026-08-12` (run 1, void)

The first run against a real model on a real device. **Read it as a runtime
result, not a model result.**

Headline: 6 of 33 passed, 3 grammar violations, median 25s per turn.

That pass rate says almost nothing about Gemma, because the grammar was not
constraining generation. `GrammarBuilder` emitted an unbracketed alternation in
the `find` tool rule, and GBNF binds `|` looser than concatenation, so the rule

    tool-find ::= "{...{" ( A ) | ( B ) | ( C ) "}}"

meant `( "{...{" A ) | ( B ) | ( C "}}" )`. The middle alternatives are bare
argument fragments, which is why `"type":"NOTE"` appears sitting directly in the
steps array in three traces. The grammar still *parsed*, so llama.cpp accepted
it and generation degraded quietly rather than failing.

Anything downstream of the grammar in this run is therefore unsafe to cite:
pass rate, per-category rates, and the reasoning failures alike. What the run
*does* establish, and what it was worth every minute of:

- The whole pipeline works end to end on hardware. The model downloads,
  checksums, loads, and produces plans the runtime executes against the board.
- **Latency is the real problem.** Median 25s per turn at ~5.5 tok/s decode,
  with ~600-token prompts. Roughly half of each turn is prefill of a context
  whose first ~500 tokens never change between turns. This is the measured
  baseline that KV prefix reuse was deliberately waiting for.
- The measurement harness had two defects of its own, both now fixed: a thrown
  case was dropped rather than recorded, which shrank the denominator and
  inflated the rate ("33 of 35"), and the positional assertions threw instead of
  failing when the model simply did not create the node.

Superseded as a model measurement. Kept because it is the run that found the
grammar defect, and because deleting an inconvenient result is how a project
starts lying to itself.
