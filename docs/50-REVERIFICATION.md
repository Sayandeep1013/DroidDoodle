# 50 — Reverification

Running record of what the implementation actually establishes, checked against
`40-IMPLEMENTATION-PLAN.md`, the specifications, and `00-INTENT.md`.

**Last updated at CI run 23** (P0–P9 built). The `jvm` job now runs
`gradle build`, so every included module compiles and every test runs — an
enumerated task list had already nearly missed `:prompt-suite`. The `android`
job assembles debug *and* release, unzips the APK to confirm the native library
and model manifest are packaged, and runs Android Lint: **0 errors, 34
warnings**, all of them "a newer version exists" against deliberately pinned
versions, plus two `ChromeOsAbiSupport` notes about the intentional arm64-only
build.

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
| L1 complete trace per turn | met — JSON export in `TraceJson`, with a reflection-based drift detector that fails when a field is added to a traced type but not to the exporter, and a separate assertion that the prompt survives export verbatim |
| L2 strategy swappable | **met, with a stated limit** — `single_shot` is implemented and `StrategySwapTest` shows the same request yielding one inference instead of two, a refused `find`-first plan, and a different `strategyId`. It delegates to the same pipeline with both extra rounds disabled, so it proves the seam works and that behaviour differs observably through it. It does **not** prove the interface would accommodate a loop shaped unlike plan-then-execute; `ReActStrategy` is still a stub and that question stays open. |
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

- **Nothing has run on a device.** An APK exists and is published, and CI proves
  it contains `libdroiddoodle_llama.so` and `assets/models.json` — but nobody
  has installed it. Every DEVICE-tagged criterion across P7, P8 and P9 is
  unverified.
- **No real model has produced a single plan.** Every plan in the suite was
  written by hand. RUNTIME mode proves the runtime executes correct plans
  correctly and says nothing about whether a 1B model can produce them — the
  central question in `00-INTENT.md` §3 is still open.
- **The GBNF grammar has never been fed to llama.cpp.** `PlanEnvelopeChecker` is
  schema-equivalent, not a grammar engine, so P3 proves the schema forbids a
  construct, not that the sampler does. P8 closes this.
- **No latency or memory number exists.**

- **KV prefix reuse is not implemented.** A stated P8 acceptance criterion.
  `cachedPrefixTokens` is always 0, deliberately, until P10 provides a baseline
  to measure an optimisation against (`25-inference.md` §4).

## 5. Defects found after P6

7. **`use_mmap` no longer existed.** The pinned llama.cpp revision is the very
   commit that replaced it with a `load_mode` enum. Prompted a check of all 26
   `llama.h` symbols the bridge calls against the pinned header rather than
   against memory; the rest were correct.
8. **`--` inside an XML comment**, which XML forbids, failed resource parsing.
9. **A bogus `LazyListScope.item` import** — `item` is a member, not an
   importable extension. Same class of mistake as the Compose `weight` import in
   P7, and the second time it has cost a CI round trip.
10. **Colliding turn ids in MODEL mode.** Every case used a bare sequential
    generator, so every case's turn was `turn-1` and an exported multi-case
    document carried duplicate ids — unusable as a record. Now prefixed with the
    case id.
11. **`File.usableSpace` under-reports** the space a download could claim, since
    Android will evict other apps' cached data to satisfy an allocation. On a
    nearly full phone the gap is easily a gigabyte, which for a 700MB model is
    the difference between refusing a download and completing one. Found by
    lint, not by review. Now `StorageManager.getAllocatableBytes`.

Defects 8 and 9 are both "the compiler would have caught this in a second". They
are the running cost of constraint C2, and the argument for a local toolchain
rather than for more care.

## 6. Honest next step

**Everything that can be built without hardware is built.** P0–P9 compile, all
tests pass, the APK packages correctly, and lint is clean of errors.

The next step is not a package: it is installing the APK. Every remaining
question — does the canvas render, does a model load in the memory available,
does the grammar survive contact with a real sampler, what is the latency — is
DEVICE- or MODEL-tagged. P10 cannot begin until the suite runner has been
pointed at a real model, and the suite runner is written and waiting.
