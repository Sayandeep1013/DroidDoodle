# Handoff — current state

**Updated:** 2026-08-13, after the third device run.
**Read `CLAUDE.md` for the working rules; this file is the state.**

If you are resuming: everything that can be built without hardware is built and
green. The project is blocked on a device, not on code, for everything except
the one item below marked otherwise. Skip to [What is left](#what-is-left).

**Top priority right now:** a fourth device run. Run 3 (`results/README.md`)
held flat at 20% but its traces exposed the actual reasoning failure across
almost every category: the model reliably guesses the wrong JSON *shape*
(relative placement, fact maps) and the wrong enum *token* (node type, edge
type, arrange layout, setting key) whenever the prompt only describes them in
prose. That prompt has been rewritten with worked examples and closed
vocabulary (`docs/22-context.md` §2-3), and two assertions that were too weak
to catch real failures were tightened (`results/README.md`, run 3 entry). All
of this is reasoned from traces and covered by a new `ContextAssemblerTest`,
**not verified against a real model** — only a device run does that.

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

## 3. The three device runs

All in `results/`, with a README explaining each in detail. Summary:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| grammar violations | 3 | 0 | 0 |
| cases counted | 33 of 35 | 35 of 35 | 35 of 35 |
| passed | 6 (18%) | 7 (20%) | 7 (20%) |
| turn total (median) | 25,083 ms | 10,809 ms | 11,432 ms |

**Run 1 is void as a model measurement** — an unbracketed GBNF alternation
meant the grammar was not constraining generation. Kept because it is the run
that found the defect.

**Run 2 is the first real number**, and confirmed KV prefix reuse: 97% median
reuse, prefill down 96%, turn latency down 57% from run 1.

**Run 3 re-ran after three harness fixes and held flat at 20%,** but its
traces are the most informative artifact in the project so far: they show the
model choosing wrong among grammar-valid outputs, category by category, in a
way the flat pass rate completely hid. See `results/README.md` for the full
breakdown — relative placement, fact-map shape, enum vocabulary, multi-step
truncation, and the reference table all have a specific, evidenced failure
mode, and none of them are grammar defects.

**Decode remains the bottleneck** at ~4.7-4.9 tok/s across all three runs
with the grammar fixed, and no caching touches that — it is a function of
model and silicon, which makes it P10's question rather than an engineering
one.

### Fixed after run 3, unverified on device

- Worked examples and closed-vocabulary hints in the prompt
  (`docs/22-context.md` §2-3, `core-agent/.../ContextAssembler.kt`,
  `core-model/.../ToolCatalog.kt`) — the primary hypothesis for run 3's flat
  pass rate.
- Two Prompt Suite assertions (`modify-03`, `anaph-01`) that passed without
  checking the thing the case name promises. The true run-3 baseline is
  arguably below 20%, not at it.
- A casing bug in the old system-prompt prose ("north_of" vs the grammar's
  `NORTH_OF`).
- `docs/31-prompt-suite.md`'s case table, which had drifted from
  `PromptSuite.kt` (missing `delete-02b`, `find-02`, `fail-04`; described two
  `anaph` cases — `anaph-03`, `anaph-04`/undo — that were never implemented).

A re-run with no other change isolates whether the prompt rewrite moves the
pass rate.

---

## 4. What is left

### Blocked on a device — nothing else can proceed past these

1. **Re-run the Prompt Suite (run 4)** on the current build. Isolates the
   prompt rewrite in §3. Do **not** predict a number here — run 1→2's KV
   caching win and run 2→3's flat result both show this project's intuitions
   about what will move the pass rate have been wrong before. Read the
   traces the same way run 3's were read: group failures by root cause before
   trusting the headline percentage.
2. **Verify P7/P9 by looking at the app**: canvas renders, drag snaps and is
   refused when the cell is taken, trace detail reaches every `TraceRecord`
   field, export round-trips, the settings screen is registry-generated, and a
   `set_setting` from the model visibly moves a control.
3. **Answer T2** — open Resources during a suite run and record peak PSS.
4. **Answer T3** — run a full session with networking disabled.
5. **P10 proper** — download Qwen2.5 1.5B and Qwen2.5 0.5B, run the same suite,
   commit all three to `results/`. The manifest already carries them. Worth
   doing *after* run 4: if the prompt rewrite alone closes most of the gap,
   spending P10 on model comparison first would have been the wrong lever.

### Not blocked — can be done any time

6. **Close the `anaph-03`/`anaph-04` gap.** Two cases the suite spec once
   described — an anaphor resolving onto a node named earlier than the
   reference table's single slot, and `undo that` backing intent P4 — were
   never implemented in `PromptSuite.kt`. P4 is currently backed only by
   `HistoryTest` in `:core-world`, never exercised against a real model.
7. **Full per-index grammar unrolling.** Step 1 is fixed, but step 2 can still
   name `$5`. Needs `step1..stepN` rules with `noderef-K` offering `$1..$(K-1)`.
   Not observed on device, so low priority.
8. **`ReActStrategy`** is still a stub. Until a genuinely differently-shaped
   loop exists, L2 is met only in the weak sense.
9. **Decode speed.** ~4.7-4.9 tok/s is the whole latency budget now.
   Unexplored: thread count (currently `min(4, cores-2)`), big-core affinity,
   and whether `GGML_CPU_REPACK` is doing anything on this device.
10. **A latency target.** T1 has no number to be measured against. Write one
    into `docs/00-INTENT.md` before claiming it passes or fails.
11. **The "default to n1" fallback.** Several run-3 traces (`connect-01`,
    `connect-03`, `arrange-02`, `setting-01`, `anaph-01`, `anaph-02`) show the
    model editing the first-listed node with placeholder arguments when it
    doesn't know how to express a request, rather than the node the message
    actually named. The worked examples may or may not touch this; watch for
    it specifically in run 4's traces, since it wouldn't be fixed by better
    vocabulary alone.

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
| 9 | Model never shown the JSON shape for relative placement or fact maps | A grammar guarantees output *parses*; it says nothing about which parseable output the model reaches for |
| 10 | Enum vocabulary (`type`, `relation`, `layout`, setting keys) withheld from the prompt to save tokens | Structural validity and semantic correctness are different guarantees; only one was being bought |
| 11 | Two Prompt Suite assertions (`modify-03`, `anaph-01`) checked outcome and node count but not the field the case name promised | A weak assertion is a false green exactly like a dropped case or a case-sensitive match — it inflates the number the project trusts |
| 12 | `docs/31-prompt-suite.md`'s case table had drifted from `PromptSuite.kt` (missing cases, and two `anaph` cases described that were never built) | A spec nobody re-reads against the code it describes decays quietly |

Defects 5–8 were found by **running the thing on hardware**, and none of them
by review. Defects 9–12 were found by **reading every failing trace from a
hardware run against the code that produced it**, rather than stopping at the
summary's pass rate — the same lesson one level deeper: the number a suite
reports is only as trustworthy as the assertions and the analysis behind it.

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
