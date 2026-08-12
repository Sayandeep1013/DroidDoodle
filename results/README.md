# Results

Measured runs, committed as they happen. A poor result is a finding and gets
published as one — see `docs/40-IMPLEMENTATION-PLAN.md`, P10.

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
