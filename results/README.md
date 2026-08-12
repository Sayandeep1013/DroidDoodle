# Results

Measured runs, committed as they happen. A poor result is a finding and gets
published as one — see `docs/40-IMPLEMENTATION-PLAN.md`, P10.

## `gemma-3-1b-it-qat-q4_0-2026-08-12`

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
