# DroidDoodle — working agreement

Read `docs/HANDOFF.md` first. It holds current state, what is done, what is
unverified, and what to do next. This file is the short version plus the rules
that are easy to get wrong.

## What this project is

An offline Android app where a small on-device LLM manipulates a grid canvas
through grammar-constrained tool calls. It is a **learning laboratory first** and
a toy second. Observability is a feature.

`docs/00-INTENT.md` is the authority. Where it and any other document disagree,
it wins.

## How to build — read this before trying

**There is no local toolchain.** No JDK, no Gradle, no Android SDK, no NDK
(constraint C2). Do not run `./gradlew`; it does not exist and no wrapper is
committed. **CI is the compiler.**

The loop is: edit → commit → push → read CI diagnostics over raw HTTPS.

```
# JVM job (fast, ~2 min). Replace N with the run number.
https://raw.githubusercontent.com/Sayandeep1013/DroidDoodle/ci-logs/status-N.txt
https://raw.githubusercontent.com/Sayandeep1013/DroidDoodle/ci-logs/diagnostics-N.txt
https://raw.githubusercontent.com/Sayandeep1013/DroidDoodle/ci-logs/cause-N.txt

# Android job (slow, ~5 min)
https://raw.githubusercontent.com/Sayandeep1013/DroidDoodle/android-logs/android-diagnostics-N.txt
https://raw.githubusercontent.com/Sayandeep1013/DroidDoodle/android-logs/apk-contents-N.txt
https://raw.githubusercontent.com/Sayandeep1013/DroidDoodle/android-logs/lint-N.txt
```

Find the current run number with:

```
curl -sS "https://api.github.com/repos/Sayandeep1013/DroidDoodle/contents/?ref=android-logs"
```

Empty diagnostics means the job passed. The GitHub Actions **log API needs auth
even for a public repo**, and `gh` does not work on this machine — that is why
the diagnostics branches exist. Do not try to use `gh run view`.

The user gets an email on every red run and none on recovery, so **a "build
failed" report is usually about a run that is already fixed**. Check the latest
run before acting on it.

## When the user drops files in the repo root

Untracked files appearing at the top level are almost always device output.
Do not ask what they are — triage them:

| Pattern | What it is | What to do |
|---|---|---|
| `suite-*.md` | A Prompt Suite MODEL-mode run | Follow the triage below, then `git mv` into `results/<model>-<date>.md` |
| `suite-*-traces.json` | Full traces for that run | Move alongside it, `-traces.json` suffix |
| logcat / stack traces | A device crash | Find the frame in our code, fix, note it in `docs/50-REVERIFICATION.md` |

### Triaging a suite run — in this order

1. **`grammar violations` must be 0.** Any other number is a defect in *our*
   grammar, not the model, because the sampler could not have produced that
   output otherwise. Stop and fix the grammar before reading any other number.
   Read the offending `rawOutput` in the traces; it tells you exactly what the
   grammar wrongly admitted.
2. **`cases: N of M` — if N < M a case threw.** Look for `A case threw:` at the
   bottom of the summary.
3. **Only then read the pass rate**, and before believing it, check whether the
   failures are ours. Past examples: a step reference the grammar guaranteed
   would be rejected, and case-sensitive label matching. A failure that the
   model could not have avoided is our bug.
4. **Compare latency against `results/README.md`** so a regression is visible.
5. Commit the run into `results/` **whether it is good or bad**, with a short
   note on what it establishes and what it does not.

## Rules that are easy to get wrong

- **GBNF binds `|` looser than concatenation.** Any rule body with an
  alternation must be bracketed. Getting this wrong shipped a grammar that
  silently stopped constraining anything and made a whole measurement void.
- **A grammar that fails to parse returns NULL** from
  `llama_sampler_init_grammar`, but one that parses and is *wrong* fails
  silently. Structural tests on the emitted grammar are the only defence;
  snapshot tests only prove stability, and `PlanEnvelopeChecker` is
  schema-equivalent, not a GBNF interpreter.
- **`llama_sampler_sample` accepts the token internally.** Calling
  `llama_sampler_accept` after it advances the grammar twice per token.
- **The llama.cpp submodule is pinned** at `153d324`. Verify every API against
  the pinned header, not memory — the pinned commit is itself the one that
  replaced `use_mmap` with a `load_mode` enum.
- **`:app` Kotlin cannot compile if resources fail**, because it needs the
  generated `R` class. `--continue` does not save you from a bad XML file.
- **XML comments cannot contain `--`.**
- **`material-icons-core` carries only a subset of icons.** Vendor anything
  uncertain via `tools/fetch-icons.py` rather than guessing.
- **Never commit model weights.** `*.gguf` is gitignored.

## Style

Match the surrounding code. Comments explain *why*, especially where a choice
looks arbitrary but is load-bearing. Where the implementation deviates from a
spec, **fix the spec** and say what changed — several specs have been wrong and
the code right.

Report honestly. "Builds" is not "works", "green CI" is not "verified on a
device", and a criterion that is unmet gets recorded as unmet.
