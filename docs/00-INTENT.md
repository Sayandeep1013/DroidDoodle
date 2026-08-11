# DroidDoodle — Intent

**Status:** Approved 2026-08-11
**Supersedes nothing. Constrains everything.**

This document states what we are building and why. Every specification, plan, and
line of code downstream is verified against this file. If an implementation
decision cannot be traced to something here, it is scope drift.

The upstream brainstorm that produced this is preserved verbatim at
`offline_ai_playground_brainstorm_context.md`. That file is history, not
requirements. Where the two disagree, **this file wins** — several of that
document's open directions were closed during design.

---

## 1. The product in one sentence

> An offline Android app in which a small on-device language model manipulates a
> structured visual canvas through tool calls, so that typing a sentence visibly
> rearranges a world.

The AI is not the world. The application is the world. The model is the
interpreter and planner. The tool runtime is the actuator. The state store is the
source of truth. The UI visualises what happened.

## 2. Dual purpose

DroidDoodle serves two goals at once, and they are ranked. When they conflict,
the higher one wins.

1. **A learning laboratory for agentic AI.** A rig for studying tool calling,
   planning, context assembly, entity resolution, validation, failure recovery,
   and the real limits of small local models. Observability is a feature, not a
   debug affordance.
2. **A creative toy.** Something that produces a genuine "I told it what to do
   and it actually did it" reaction.

Goal 2 is what makes goal 1 worth doing. Goal 1 is what we optimise for.

## 3. The central question

> How much apparent agency can be manufactured from a small local model, a
> well-defined world, structured state, and excellent tools?

Restated as the design principle that governs every trade-off:

> **Make the model small by making the world well-defined — not by making the
> experience boring.**

The corollary is a rule we apply repeatedly: *if the runtime can determine
something deterministically, the model must not be asked to infer it.* Spatial
coordinates, referent resolution, validation, and collision are all engine
responsibilities. The model contributes intent and structure, nothing else.

---

## 4. Locked decisions

Settled during the design conversation on 2026-08-11. These are inputs to the
specs, not open questions. Reopening any of them invalidates downstream specs.

| # | Decision | Rationale |
|---|---|---|
| D1 | **Semantic Canvas** — typed nodes and typed edges, positioned on a 2D canvas | The closed schema that makes a 1B model viable is also what makes the world transformable. A dungeon becomes a board game by relabelling, not rebuilding. |
| D2 | **Grid-snapped cells**, integer row/col | Spatial relations become exactly computable and therefore unit-testable. `north_of` is `row - 1`, collision is cell occupancy. |
| D3 | **Closed structure, open semantics** — `type` from a fixed enum; `kind`, `label`, `attributes` free text | The grammar constrains the skeleton; the flesh is unconstrained text that costs the model nothing. |
| D4 | **Plan-then-execute** as the default agent loop, strategy pluggable | One inference per turn keeps latency tolerable on a mid-range CPU while still exercising real multi-step planning. |
| D5 | **Native Kotlin + Jetpack Compose** | Direct JNI access to llama.cpp means direct control of GBNF grammars, sampler parameters, KV cache and quantization — most of what this lab exists to study. |
| D6 | **GBNF grammar-constrained decoding**, mandatory | Converts malformed output from a coin flip into a structural impossibility, so observed failures are reasoning failures worth studying. |
| D7 | **Model downloaded at first run**, never bundled | A Q4 1B GGUF is ~700MB–1GB, past both GitHub's file limit and sane APK delivery limits. |
| D8 | **Text-only MVP**; input abstracted behind an interface from day one | Voice teaches little about agentic AI and costs a large share of MVP budget. Deferring it costs nothing later. |
| D9 | **GitHub Actions + Gradle + NDK** for all builds | EAS Build is Expo/React Native infrastructure and cannot build a native Kotlin project. |

## 5. Hard constraints

These are properties of the environment, not preferences. Designs that violate
them are wrong regardless of merit.

- **C1 — Target device has ≤6GB RAM.** After the system takes its share, the app
  can expect roughly 600MB–1GB of headroom for model weights. This caps us at
  approximately a 1B-parameter model at 4-bit quantization.
- **C2 — No local build toolchain.** The development machine has git, `gh`, and
  `adb`, but **no JDK, no Gradle, and no Kotlin compiler** (verified
  2026-08-11). Nothing compiles or runs locally. All compilation and testing
  happens in CI.
- **C3 — Offline after first run.** Network is permitted only for the initial
  model download. No inference request may leave the device, ever.
- **C4 — Device iteration is expensive.** Each on-device check costs a CI round
  trip plus an `adb install`. Logic that can be tested without hardware must be
  placed where it can be.

C2 and C4 together drive the single most important architectural consequence:
**the state store, tool runtime, planner, validator, grammar builder and mock
model must live in pure-Kotlin modules with zero Android dependencies**, so they
run as JVM unit tests in CI in seconds. The mock-model harness is the primary
development surface, not a convenience.

## 6. Non-goals

Explicitly out of scope. Proposals in these directions are rejected by default.

- **Not a phone assistant.** The model does not open apps, set alarms, send
  messages, or drive the Android UI. It acts only inside its own world.
- **Not a productivity tool.** No todo lists, no note-taking, no document
  assistance.
- **Not a chatbot.** A turn that produces only text and no state change is a
  degenerate case, not the main path.
- **Not cloud-dependent.** No cloud inference, no cloud fallback, no telemetry.
- **Not multimodal.** No vision, no image generation, no image understanding.
- **Not maximally capable.** Shipping a larger model to solve a problem is an
  admission of failure at the actual experiment.

### Deferred, not rejected

Designed-for but deliberately outside the MVP: voice input, wake word, TTS,
cross-session agent memory, alternate loop strategies beyond interface stubs,
multiple boards, sharing and export, vision.

---

## 7. Success criteria

Deliberately falsifiable. Each is either met or not, and the honest answer to
several of them may turn out to be "no" — that is a finding, not a failure.

### Product behaviour

Verified by the **Prompt Suite** (specified in `docs/31-prompt-suite.md`): a
fixed set of natural-language prompts, each paired with an expected board delta,
runnable headlessly against `MockEngine` in CI and on-device against the real
model.

- **P1** — "Create a village with a tavern and a blacksmith" produces three
  correctly typed, correctly related nodes in a single turn.
- **P2** — A follow-up "make the blacksmith secretly a vampire" modifies the
  existing node rather than creating a new one.
- **P3** — "Move the castle north of the village" results in
  `castle.row < village.row`, and exactly `village.row - 1` when that cell is
  free. The Prompt Suite case for this asserts the exact form from a starting
  board where the cell is guaranteed free; the general guarantee is directional
  (see `20-world-model.md` §7).
- **P4** — "Undo that" restores the board to a state byte-identical to the one
  before the previous turn.
- **P5** — The suite's pass rate against the real on-device model is **measured
  and published in the repository.** No specific pass rate is required. The
  number is the research result.

### Learning-laboratory capability

- **L1** — Every turn emits a complete trace: verbatim prompt, token counts, raw
  model output, parsed plan, per-step validation and execution results, state
  diffs, and timings. Traces persist and export as JSON.
- **L2** — The agent loop strategy can be swapped without modifying tool
  definitions or state code.
- **L3** — The model can be swapped without modifying any agent code.
- **L4** — The entire agent runtime executes headlessly on the JVM with zero
  Android dependencies, and its test suite runs in CI without an emulator.
- **L5** — Adding a tool to the registry automatically updates the GBNF grammar.
  A tool and its grammar cannot drift apart.

### Performance and viability

- **T1** — Median turn latency for a three-step plan on the target device is
  measured and recorded. Target ≤10s; the requirement is that it is *measured*.
- **T2** — A 20-turn session on a 6GB device completes without the app being
  killed for memory. Peak RSS is recorded.
- **T3** — The app performs no network I/O after the model file is present.
  Verified by inspection and by running with networking disabled.

## 8. What "done" means for the MVP

The MVP is complete when a user can open the app on a ≤6GB Android device,
download a model, type natural language, and watch a grid canvas change — with
undo, confirmation on destructive actions, agent-controllable settings, and a
full inspectable trace of every turn. And when the Prompt Suite pass rate for
that configuration is written down.

## 9. Document map

| Document | Contents |
|---|---|
| `00-INTENT.md` | This file. Why, constraints, success criteria. |
| `10-architecture.md` | Module boundaries and dependency rules. |
| `20-world-model.md` | Nodes, edges, grid, placement resolution. |
| `21-tools.md` | The tool vocabulary and argument schemas. |
| `22-context.md` | Prompt assembly, viewport digest, reference table. |
| `23-agent-runtime.md` | Planner, validation, execution, undo, confirmation. |
| `24-trace.md` | Trace record format and retention. |
| `25-inference.md` | Engine interface, GBNF generation, model management. |
| `26-settings.md` | Settings registry and agent-controllable keys. |
| `31-prompt-suite.md` | The behavioural benchmark. |
| `40-IMPLEMENTATION-PLAN.md` | Ordered work packages and acceptance criteria. |
