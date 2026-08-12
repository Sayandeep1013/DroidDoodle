# 40 — Implementation Plan

Ordered work packages with explicit acceptance criteria and verification method.

**The governing constraint is C2:** there is no JDK, Gradle, or Kotlin compiler
on the development machine (verified 2026-08-11). Nothing is compiled locally.
Every package therefore declares *how* it gets verified, and "it looks right" is
never the answer.

Ordering is chosen so that the maximum amount of design risk is retired in
packages that need no device and no model.

---

## Verification vocabulary

| Tag | Meaning |
|---|---|
| **CI-JVM** | Verified by the `jvm` CI job. No device, no Android SDK, no model. |
| **CI-APK** | Verified by the `android` CI job assembling successfully. |
| **DEVICE** | Requires installing on hardware. Manual. |
| **MODEL** | Requires a downloaded model. Manual, produces measurements. |
| **REVIEW** | Verified by reading against a spec. Weakest form; used only where nothing stronger exists. |

---

## P0 — Repository scaffolding

**Deliverable.** Gradle multi-module build with Kotlin DSL, a version catalog,
seven module skeletons per `10-architecture.md` §1, the dependency wiring from
§2, `explicitApi()` on the core modules, and the two-job GitHub Actions
workflow.

**Acceptance.**
- `./gradlew :core-model:test … :inference:test` runs green with zero tests.
- The `jvm` job requires no Android SDK.
- A deliberate violation — adding an `androidx` import to a `core-*` module —
  fails compilation.

**Verification.** CI-JVM.

**Note.** The dependency-rule check of `10-architecture.md` §2 is enforced
structurally: `core-*` modules apply `org.jetbrains.kotlin.jvm`, so the Android
SDK is absent from their compile classpath and a violation cannot compile. No
separate lint rule is needed, and a rule that duplicated this would be dead
weight.

## P1 — `:core-model`

**Deliverable.** `Cell`, `Node`, `Edge`, `NodeId`, `EdgeId`, enums, `Style`,
`Placement`, `Relation`, `ArrangeLayout`, `WorldError`, `ToolError`,
`SettingDef`, `SettingType`, the settings registry from `26-settings.md` §2, and
the `Clock` / `IdGenerator` interfaces required by rule R4.

**Acceptance.**
- Field limits from `20-world-model.md` §2 enforced by a shared validator.
- Every setting key in the `26-settings.md` table is present with matching type,
  default, range and `agentWritable` flag, asserted by a table-driven test.
- `NodeId` rejects anything not matching `n[1-9][0-9]*`.

**Verification.** CI-JVM.

## P2 — `:core-world`

**Deliverable.** Immutable `Board`, the occupancy index, placement resolution,
the eight operations of `20-world-model.md` §8, containment invariants I1–I4, and
the undo/redo stacks.

**Acceptance.**
- Placement resolution is exhaustively tested: exact hit, axis-preserving
  fallback, `NEXT_TO` ordering, `Auto` centroid ring order, bounds, and every
  failure mode.
- Property test: after any sequence of operations, `occupancy` agrees with
  `nodes`, and no two nodes share a cell.
- Property test: the `CONTAINS` graph is acyclic and every node has at most one
  container.
- `arrange` is atomic — a blocked layout moves nothing.
- `moveNode` succeeds when moving a node relative to a neighbour of itself
  (the vacate-first rule).
- Undo restores a board equal to the pre-turn value.

**Verification.** CI-JVM.

**Risk.** This package holds the most intricate logic in the project and cannot
be exercised by hand. Property-based tests are load-bearing here, not a nicety.

## P3 — `:core-grammar`

**Deliverable.** GBNF emission from `ToolSchema` plus a live board, and
`PlanEnvelopeChecker`.

**Acceptance.**
- Snapshot tests over generated grammars for the four fixture boards.
- Empty-board case emits no `existing` alternative and remains a well-formed
  grammar.
- Grammar admits: `respond`-only, `find` first, `respond` last.
- Grammar rejects: `find` at position ≠ 1, `respond` not last, unknown node id,
  non-agent-writable setting key.
- Renaming a tool argument changes the snapshot — the drift detector for intent
  criterion L5.

**Verification.** CI-JVM.

**Note.** Grammar *rejection* is asserted through `PlanEnvelopeChecker`, not by
running a GBNF engine, consistent with `25-inference.md` §2. This is a real
limitation: it proves the schema forbids the construct, not that llama.cpp's
sampler does. P8 closes that gap on device.

## P4 — `:inference`

**Deliverable.** `LlmEngine`, `SamplingParams`, `GenerationResult`, `StopReason`,
`MockEngine`, `OutputCheck`.

**Acceptance.**
- `MockEngine` returns scripted responses in order.
- Exhausting the script throws rather than returning empty output.
- A response failing the injected `OutputCheck` throws.

**Verification.** CI-JVM.

## P5 — `:core-agent`

**Deliverable.** Tool registry and all ten tools, context assembly per
`22-context.md`, `PlanThenExecuteStrategy`, static validation, the confirmation
gate, execution with partial commit, trace construction, and the two unimplemented
strategy stubs.

**Acceptance.**
- Context assembly is a pure function; identical inputs give a byte-identical
  prompt (snapshot tests).
- Digest format matches `22-context.md` §4 exactly, including omission rules for
  blank kind, empty attributes, and `DEFAULT` colour.
- Budget degradation sheds blocks in the specified priority order and records
  what it shed.
- Reference table is computed by the runtime and is never model-writable.
- Static validation rejects the whole plan without mutating the board.
- Dynamic failure halts and commits prior steps as exactly one undo entry.
- A plan of only `respond`, or one where every step failed, creates no undo
  entry.
- Confirmation triggers on both the count rule and the container rule.
- `find` at step 1 triggers exactly one retrieval round; a second is
  `RETRIEVAL_EXHAUSTED`.
- Maximum three inferences per user message, asserted by counting `MockEngine`
  calls.
- A trace is produced for every turn including rejected ones.

**Verification.** CI-JVM.

## P6 — Prompt Suite, RUNTIME mode

**Deliverable.** The four board fixtures, the assertion DSL, all 33 cases from
`31-prompt-suite.md` §3 with scripted plans, and a JUnit runner.

**Acceptance.** All 33 cases pass in RUNTIME mode. This is a hard gate: a
failure is a runtime bug.

**Verification.** CI-JVM.

**Note.** RUNTIME mode proves the runtime executes correct plans correctly. It
proves nothing whatsoever about the model. Conflating the two would make every
later measurement meaningless.

---

*Packages P0–P6 need no Android SDK, no device, and no model. They contain the
entire agent design. This is the deliberate consequence of constraints C2 and
C4, and it is where the project's design risk actually lives.*

---

## P7 — `:app`, canvas on MockEngine

**Deliverable.** Compose grid canvas with pan and zoom, node rendering by type,
containment hulls, edge rendering, tap-to-select, drag-to-move with snapping,
the text input bar, and turn results rendered from `MockEngine`. Room
persistence for board and traces. DataStore settings.

**Acceptance.**
- Debug APK assembles.
- The canvas renders each fixture board correctly.
- Dragging snaps to the nearest free cell and is rejected when none is free.
- A scripted mock turn visibly changes the canvas.

**Verification.** CI-APK, then DEVICE.

## P8 — `:inference-llama`

**Deliverable.** llama.cpp as a pinned submodule, CMake and NDK wiring for
`arm64-v8a`, the five-function JNI surface, KV prefix reuse, and the model
download flow with the manifest, checksums and resumption.

**Acceptance.**
- APK assembles with the native library.
- A model downloads, checksums, and loads on device.
- A real turn produces a valid plan that mutates the board.
- **Zero grammar violations** across a full Prompt Suite run. Any violation is a
  grammar defect and blocks the package — this is what finally validates P3's
  schema-equivalent checker against the real sampler.
- KV prefix reuse length is reported in `Timings` and is non-zero on turn two.

**Verification.** CI-APK, then DEVICE and MODEL.

**Risk.** The highest-uncertainty package. The NDK build, the GGUF format, and
GBNF behaviour are all upstream-controlled, which is why the submodule is
pinned.

## P9 — Trace UI and settings UI

**Deliverable.** The turn list and turn detail screens of `24-trace.md` §4, JSON
export, the settings screen generated from the registry, and a device-side
Prompt Suite runner.

The first-run model picker with memory-suitability verdicts **landed early, in
P8**: the download flow is unusable without it, so deferring it would have meant
shipping P8 with no way to exercise it.

**Acceptance.**
- Every field of `TraceRecord` is reachable in the UI.
- Export round-trips: exported JSON deserialises to an equal record.
- The settings screen is generated from the registry — adding a key adds a row
  with no UI change.
- Agent-writable keys changed by `set_setting` visibly update the screen.
- The suite runner executes all 33 cases of `31-prompt-suite.md` against the
  loaded engine and reports, per case, pass or fail with the failure layer,
  latency, and token counts — and reports grammar violations separately from
  reasoning failures, since the two mean entirely different things.

**Verification.** DEVICE.

## P10 — Model selection and measurement

**Deliverable.** Candidate models verified for current availability and size,
Prompt Suite MODEL runs for each, and committed results.

**Acceptance.**
- At least two candidates measured end to end.
- For each: pass rate overall and per category, median and p90 latency, mean
  token counts, peak RSS.
- Results committed to `results/<model-id>-<date>.md` with traces.
- Intent criteria P5, T1, T2 answered with numbers.
- T3 verified by running a full session with networking disabled.

**Verification.** MODEL.

**Note.** No pass-rate threshold is required. A poor result is a finding and gets
published as one. Selecting a model *after* measuring is the point; naming one in
a spec beforehand would have been the unverified claim this project exists to
avoid.

---

## Dependency order

```
P0 → P1 → P2 ─┐
      ├→ P3 ──┼→ P5 → P6 → P7 → P8 → P9 → P10
      └→ P4 ──┘
```

P2, P3 and P4 are independent of each other once P1 lands.

## Status

| Package | State |
|---|---|
| P0 Scaffolding and CI | **verified — CI green** |
| P1 `:core-model` | **verified — CI green** |
| P2 `:core-world` | **verified — CI green** |
| P3 `:core-grammar` | **verified — CI green** |
| P4 `:inference` | **verified — CI green** |
| P5 `:core-agent` | **verified — CI green** |
| P6 Prompt Suite | **verified — CI green** |
| P7 `:app` canvas | **builds — CI green**; DEVICE criteria unverified |
| P8 `:inference-llama` | **builds — CI green**; every DEVICE and MODEL criterion unverified, and one criterion deliberately unmet |
| P9 Trace, settings, suite runner | **builds — CI green**; DEVICE criteria unverified |
| P10 | blocked on a device |

An eighth module appeared during P9: **`:prompt-suite`**. The 33-odd cases had
been living in `:core-agent`'s test source set, where the app could not reach
them, and P10 needs to run the *same* cases against a real model. A second copy
inside `:app` would have drifted — the failure mode the generated grammar exists
to avoid — so the cases moved into a pure-Kotlin module that both the RUNTIME
test and the device runner consume. `docs/10-architecture.md` §1 lists seven
modules and should be read as eight.

### What P8 has and has not established

Established by CI: llama.cpp cross-compiles for `arm64-v8a` against the pinned
revision; the JNI bridge compiles against the real headers; the APK contains
`lib/arm64-v8a/libdroiddoodle_llama.so` and `assets/models.json`, checked by
unzipping the artifact rather than inferred from a green build.

Not established, because none of it can be without hardware: that a model
downloads and checksums on a phone, that it loads, that a real turn produces a
valid plan, and — the criterion that actually matters — that a full Prompt Suite
run yields **zero grammar violations**. Until that last one runs against the
real sampler, `PlanEnvelopeChecker` is validated only against itself.

Deliberately unmet: **KV prefix reuse is not implemented.** The cache is cleared
every turn and `cachedPrefixTokens` is always 0. See `25-inference.md` §4 for
why this is sequenced after P10 rather than done now. P8 is therefore not
signed off, and saying otherwise would be the kind of claim this project exists
to avoid.

One further gap the plan did not name: P10 requires Prompt Suite runs against a
**real** engine, and the suite currently exists only as a JVM test driving
`MockEngine`. A device-side MODEL-mode runner is undone work that P10 depends
on, and it belongs in P9 alongside the other on-device screens.

P7's remaining acceptance criteria — canvas renders each fixture correctly,
drag snaps to the nearest free cell and is refused when none is free, a scripted
turn visibly changes the canvas — are DEVICE-tagged. An assembling APK does not
establish any of them.

Four CI round trips were spent reaching a green Android build: missing
`google()` repository, missing `android.useAndroidX`, and one bad Compose
import. Three of the four were environment setup that a local SDK resolves in
seconds. This is the evidence behind the toolchain recommendation for P8, where
the failure modes are native and the cycle cost is higher.

"Verified" here means precisely one thing: the `jvm` job compiles every module
and every test passes. See `50-REVERIFICATION.md` for what that does and does
not establish.

P7–P10 need an Android SDK build, a device, and a downloaded model, none of
which exist on the development machine.
