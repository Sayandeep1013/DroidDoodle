# 50 — Reverification

Final pass over packages P0–P6, checking the implementation against
`40-IMPLEMENTATION-PLAN.md`, the specifications, and `00-INTENT.md`.

Verified at CI run 5 on the `jvm` job: all five modules compile, all tests pass,
zero warnings.

---

## 1. Acceptance criteria

Every plan acceptance criterion for P0–P6 has a corresponding test.

| Package | Criterion | Evidence |
|---|---|---|
| P0 | JVM job needs no Android SDK | `settings.gradle.kts` includes only Kotlin JVM modules; the job installs no SDK |
| P0 | `core-*` cannot compile against Android | modules apply `org.jetbrains.kotlin.jvm`, so the SDK is absent from the classpath — structural, not a lint rule |
| P1 | Settings registry matches the spec table | `SettingsRegistryTest` — table-driven against all 17 keys |
| P1 | `NodeId` rejects malformed ids | `IdsTest` |
| P1 | Field limits enforced, never truncated | `LimitsTest` |
| P2 | Placement exhaustively tested | `PlacementResolverTest` — exact hit, fallback ordering, `NEXT_TO` order, `Auto` centroid, ring walk, every failure mode |
| P2 | Occupancy and containment invariants hold | `InvariantPropertyTest` — 40 seeds × 120 random operations |
| P2 | `arrange` is atomic | `BoardOpsTest` |
| P2 | Vacate-first move | `BoardOpsTest` |
| P2 | Undo restores exactly | `HistoryTest` |
| P3 | Grammar admits respond-only, find-first, respond-last | `GrammarBuilderTest` |
| P3 | Empty board omits `existing` | `GrammarBuilderTest` |
| P3 | Argument rename changes the grammar | `GrammarBuilderTest` — the L5 drift detector |
| P3 | Protected setting keys absent from the grammar | `GrammarBuilderTest`, `PlanEnvelopeCheckerTest` |
| P4 | Script order, exhaustion throws, `OutputCheck` refuses | `MockEngineTest` |
| P5 | Byte-identical prompts from identical inputs | `PromptSuiteTest` |
| P5 | Digest omission rules exact | `PromptSuiteTest` |
| P5 | Static rejection leaves the board untouched | `fail-04` |
| P5 | Dynamic failure commits prior steps | `fail-01` |
| P5 | Both confirmation rules | `delete-02`, `delete-03` |
| P5 | One retrieval round, then refused | `find-01`, `find-02` |
| P5 | At most three inferences per message | `guard-ceiling` |
| P5 | Trace for every turn including rejected | `guard-trace` |
| P6 | All cases pass in RUNTIME mode | green |

## 2. Intent criteria

| Criterion | Status |
|---|---|
| P1 village + tavern + blacksmith in one turn | met — `multi-01` |
| P2 modify rather than recreate | met — `modify-01` |
| P3 castle north of village | met — `move-02` |
| P4 undo restores exactly | met — `anaph-04` |
| P5 model pass rate published | **not answerable yet** — needs P10 |
| L1 complete trace per turn | met in structure; JSON export lands in P9 |
| L2 strategy swappable | interface in place, two registered stubs — **unproven until a second strategy exists** |
| L3 model swappable | met — `:core-agent` depends only on `LlmEngine` |
| L4 headless JVM, no Android | met — the whole suite runs in CI without an emulator |
| L5 tool and grammar cannot drift | met — grammar emitted from `ToolSchema`, drift test in place |
| T1–T3 latency, memory, offline | **not answerable yet** — need a device |

## 3. Defects found and fixed during execution

1. **Grammar forbade `respond`-only plans** while two suite cases require them.
   Found in spec self-review.
2. **`MockEngine` grammar validation** would have forced a dependency-rule
   violation. Replaced with an injected checker.
3. **Placement candidate ordering.** The spec's worked example showed a strict
   row-by-row walk that the stated Manhattan rule does not produce. Both the
   example and the test encoded a sequence the resolver never emits — the code
   was right and the specification was wrong. Caught only by CI, having survived
   the written self-review.
4. **`removeNode` cascade** contradicted the confirmation gate. Resolved toward
   cascading into descendants, protected by confirmation.
5. **`fail-03` predicted the wrong layer.** Deleting a nonexistent node fails as
   `GRAMMAR_VIOLATION` at phase 4, never reaching the executor's `UNKNOWN_NODE`.
6. **`model.threads` default `auto`** on an INT key; now `0` as sentinel.

Defect 3 is the one worth remembering: a specification can be internally
consistent, pass a careful review, and still be wrong in a way only execution
reveals.

## 4. What green does not mean

- **Nothing has run on a device.** No APK exists.
- **No real model has produced a single plan.** Every plan in the suite was
  written by hand. RUNTIME mode proves the runtime executes correct plans
  correctly and says nothing about whether a 1B model can produce them — the
  central question in `00-INTENT.md` §3 is still open.
- **The GBNF grammar has never been fed to llama.cpp.** `PlanEnvelopeChecker` is
  schema-equivalent, not a grammar engine, so P3 proves the schema forbids a
  construct, not that the sampler does. P8 closes this.
- **No latency or memory number exists.**

## 5. Honest next step

P7 (`:app`, Compose canvas on `MockEngine`) is the right next package: it
produces the first APK and the first thing a person can look at, while still
needing no model. P8 then answers whether the grammar survives contact with a
real sampler.
