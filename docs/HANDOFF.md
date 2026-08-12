# Handoff — current state

**Updated:** 2026-08-12, at CI run 34.
**Read `CLAUDE.md` for the working rules; this file is the state.**

If you are resuming: everything that can be built without hardware is built and
green. The project is blocked on a device, not on code. Skip to
[What is left](#what-is-left).

---

## 1. Where the project stands

| Package | State |
|---|---|
| P0 Scaffolding and CI | verified — CI green |
| P1 `:core-model` | verified — CI green |
| P2 `:core-world` | verified — CI green |
| P3 `:core-grammar` | verified — CI green |
| P4 `:inference` | verified — CI green |
| P5 `:core-agent` | verified — CI green |
| P6 Prompt Suite (RUNTIME mode) | verified — 35/35 pass |
| P7 `:app` canvas | builds; **DEVICE criteria unverified** |
| P8 `:inference-llama` | builds; **grammar criterion now met on device** |
| P9 Trace, settings, suite runner | builds; **DEVICE criteria unverified** |
| P10 Model selection and measurement | **one model measured, two to go** |

Eight modules, not the seven `docs/10-architecture.md` §1 lists: `:prompt-suite`
was added in P9 so the app and the JVM test drive the *same* case list.

### What "verified" means

The `jvm` CI job runs `gradle build` — every module compiles, every test runs.
The `android` job assembles debug and release, unzips the APK to confirm
`libdroiddoodle_llama.so` and `assets/models.json` are packaged, and runs lint
(currently **0 errors**, 30 warnings, all pinned-version notices plus two
arm64-only notes).

It does **not** mean anything has been looked at on a screen.

---

## 2. Intent criteria — honest status

From `docs/00-INTENT.md`. This table is the real scoreboard.

| Criterion | Status |
|---|---|
| P1 village + tavern + blacksmith in one turn | **fails on device** — Gemma produced 1 node of 3 |
| P2 modify rather than recreate | met — `modify-01/02/03`, 2 of 3 pass on device |
| P3 castle north of village | **fails on device** — placement ignored |
| P4 undo restores exactly | met in RUNTIME mode; not exercised on device |
| P5 model pass rate published | **partially** — one model measured, `results/` |
| L1 complete trace per turn | met — `TraceJson`, with a reflection drift detector |
| L2 strategy swappable | met with a stated limit — `single_shot` shares the pipeline; `ReActStrategy` is still a stub |
| L3 model swappable | met — `:core-agent` depends only on `LlmEngine` |
| L4 headless JVM, no Android | met |
| L5 tool and grammar cannot drift | met |
| T1 latency | **measured, not met** — 10.8s median turn. No target was ever written down; write one |
| T2 memory | **unanswered** — the Resources page exists but no one has read it during a run |
| T3 offline after first run | **structurally true, unverified** — `ModelStore` is the only class that opens a socket; nobody has run a session with networking off |

---

## 3. The two device runs

Both in `results/`, with a README explaining them.

| | run 1 | run 2 (current) |
|---|---|---|
| grammar violations | 3 | **0** |
| cases counted | 33 of 35 | 35 of 35 |
| passed | 6 (18%) | 7 (20%) |
| prefill | 14,110 ms | **512 ms** |
| decode | 10,464 ms | 8,940 ms |
| turn total | 25,083 ms | **10,809 ms** |

**Run 1 is void as a model measurement** — the grammar was not constraining
generation, so it measured an unconstrained model. It is kept because it is the
run that found the defect.

**Run 2 is the first real number.** Zero grammar violations is P8's acceptance
criterion and the first evidence that `PlanEnvelopeChecker` and the real
llama.cpp sampler agree.

KV prefix reuse landed between them: 97% median reuse, prefill down 96%, turn
latency down 57%. **Decode is the bottleneck now** at ~4.9 tok/s, and no caching
touches that — it is a function of model and silicon, which makes it P10's
question rather than an engineering one.

### Not yet reflected in any run

Three fixes landed *after* run 2 and are unmeasured:

- the step-1 reference trap (below) — expect roughly +3 cases
- case-insensitive label matching in the suite harness
- a thrown case is now recorded rather than dropped

A re-run with no other change isolates their effect.

---

## 4. What is left

### Blocked on a device — nothing else can proceed past these

1. **Re-run the Prompt Suite** on the current build. Isolates the three fixes
   above. Expect ~20% → ~28% and still zero grammar violations.
2. **Verify P7/P9 by looking at the app**: canvas renders, drag snaps and is
   refused when the cell is taken, trace detail reaches every `TraceRecord`
   field, export round-trips, the settings screen is registry-generated, and a
   `set_setting` from the model visibly moves a control.
3. **Answer T2** — open Resources during a suite run and record peak PSS.
4. **Answer T3** — run a full session with networking disabled.
5. **P10 proper** — download Qwen2.5 1.5B and Qwen2.5 0.5B, run the same suite,
   commit all three to `results/`. The manifest already carries them.

### Not blocked — can be done any time

6. **A worked example in the prompt.** The context has tool descriptions but no
   example plan. 1B models lean heavily on format demonstration; this is the
   cheapest remaining lever on the pass rate and it is untested.
7. **Full per-index grammar unrolling.** Step 1 is fixed, but step 2 can still
   name `$5`. Needs `step1..stepN` rules with `noderef-K` offering `$1..$(K-1)`.
   Not observed on device, so low priority.
8. **`ReActStrategy`** is still a stub. Until a genuinely differently-shaped
   loop exists, L2 is met only in the weak sense.
9. **Decode speed.** 4.9 tok/s is the whole latency budget now. Unexplored:
   thread count (currently `min(4, cores-2)`), big-core affinity, and whether
   `GGML_CPU_REPACK` is doing anything on this device.
10. **A latency target.** T1 has no number to be measured against. Write one
    into `docs/00-INTENT.md` before claiming it passes or fails.

### Known-good things not to redo

- The signing key is stable now (orphan `signing` branch). Builds update in
  place and no longer wipe the downloaded model.
- Exports are pruned to the last three; abandoned `.part` files are visible and
  clearable in the picker.
- The release lives at the `latest` tag, not a prerelease:
  `https://github.com/Sayandeep1013/DroidDoodle/releases/latest/download/DroidDoodle.apk`

---

## 5. Defects found so far, and what each one taught

Kept because the pattern matters more than the list.

| # | Defect | Lesson |
|---|---|---|
| 1 | Grammar forbade `respond`-only plans | Spec review caught it; not everything needs execution |
| 2 | Placement candidate ordering — spec and test agreed with each other and disagreed with correct code | A spec can be internally consistent, pass review, and still be wrong |
| 3 | Missing `google()` repo, missing `useAndroidX`, bad Compose import | The running cost of having no local compiler |
| 4 | `use_mmap` removed by the pinned commit itself | Verify APIs against the pinned source, never memory |
| 5 | **Unbracketed GBNF alternation** | Precedence bugs fail *silently*; a grammar that parses can still constrain nothing |
| 6 | Thrown case dropped from the denominator | A shrinking denominator inflates a pass rate |
| 7 | Case-sensitive label matching | The harness was docking marks the model had earned |
| 8 | Step-1 `$k` offered but never valid | Catching a failure is not the same as preventing it |

Defects 5–8 were all found by **running the thing on hardware**, and none of
them by review. That is the argument for prioritising the device work above.

---

## 6. Orientation for a new session

- `docs/00-INTENT.md` — authority. Locked decisions D1–D9, constraints C1–C4.
- `docs/10-architecture.md` — modules and dependency rules.
- `docs/25-inference.md` — grammar, KV reuse, model management. Most of the
  hard-won knowledge is here.
- `docs/31-prompt-suite.md` — the suite spec; `:prompt-suite` is the code.
- `docs/40-IMPLEMENTATION-PLAN.md` — packages and acceptance criteria.
- `docs/50-REVERIFICATION.md` — what green does and does not establish.
- `results/README.md` — every measured run and how to read it.

The task list in the harness mirrors §4. If it is empty, rebuild it from there.
