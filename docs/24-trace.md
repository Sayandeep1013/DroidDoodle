# 24 — Trace

Module: `:core-agent` produces it; `:app` persists and renders it.

The trace is the reason this project exists (intent §2, goal 1). It is a
first-class product surface, not a debug flag — DroidDoodle's second audience is
someone trying to understand why a small model did what it did.

Satisfies intent criterion L1.

---

## 1. Record

```kotlin
data class TraceRecord(
    val turnId: String,
    val startedAtMillis: Long,
    val strategyId: String,
    val modelId: String,
    val settingsSnapshot: Map<String, String>,

    val userMessage: String,
    val rounds: List<InferenceRound>,     // 1–3, see 23-agent-runtime.md §4
    val plan: List<PlanStep>?,            // final plan, post-retrieval
    val validation: ValidationOutcome,
    val confirmation: ConfirmationOutcome?,
    val steps: List<StepOutcome>,
    val diff: List<CellDelta>,
    val outcome: Outcome,
    val timings: Timings,
)

data class InferenceRound(
    val role: RoundRole,                  // INITIAL | RETRIEVAL_REPLAN | REPAIR
    val prompt: String,                   // verbatim, in full
    val promptTokens: Int,
    val blockTokens: Map<String, Int>,    // per context block
    val shedBlocks: List<String>,         // budget degradation, 22-context.md §1
    val grammarHash: String,
    val rawOutput: String,                // verbatim, pre-parse
    val outputTokens: Int,
    val prefillMillis: Long,
    val decodeMillis: Long,
    val tokensPerSecond: Double,
)

data class StepOutcome(
    val index: Int,
    val tool: String,
    val args: JsonObject,
    val resolvedRefs: Map<String, String>,   // "$2" → "n9"
    val result: StepResult,                  // OK | FAILED | SKIPPED
    val error: String?,
    val durationMillis: Long,
)
```

### Why prompts are stored verbatim and in full

Storing a hash or a summary would make the single most common question —
"what exactly was the model looking at when it got this wrong?" — unanswerable
after the fact. Prompts are ~1200 tokens; storage is not the binding constraint.

`blockTokens` and `shedBlocks` matter specifically because a turn that failed
due to context budget degradation must be distinguishable from a turn that
failed due to reasoning. Without them the two look identical.

`resolvedRefs` records what `$2` actually resolved to, which is the difference
between diagnosing a planning error and a reference error.

## 2. Timings

```kotlin
data class Timings(
    val totalMillis: Long,
    val assembleMillis: Long,
    val grammarMillis: Long,
    val inferenceMillis: Long,      // summed across rounds
    val executeMillis: Long,
)
```

These feed intent criterion T1. `grammarMillis` is tracked because the grammar is
rebuilt every turn with the live id set (`21-tools.md` §2), and if that ever
becomes significant on a large board we need the evidence rather than a
suspicion.

## 3. Retention and export

- Persisted in Room, in `:app`. Serialisation uses
  `kotlinx-serialization-json`.
- Ring buffer of the most recent **200 turns**, with older records evicted.
- Export writes a JSON array of full records to a user-chosen file.

Export is what makes cross-configuration comparison possible: run the Prompt
Suite under model A, export, switch to model B, export, compare. That workflow
is the main way intent criterion P5 gets answered.

## 4. Trace UI

A dedicated screen, reachable from the main canvas — not buried behind a
developer setting.

**Turn list.** One row per turn: user message, outcome badge, step count, total
duration, tokens per second.

**Turn detail**, rendered as the phase pipeline from `23-agent-runtime.md` §2 so
the on-screen structure matches the specified lifecycle:

```
USER      "put a castle north of the village"

CONTEXT   1180 tok   system 150 · tools 350 · board 310 · refs 40 · history 310
          no blocks shed

MODEL     qwen-1.5b-q4 · grammar #a3f1
          prefill 780ms · decode 3210ms · 14.2 tok/s

PLAN      1  create_node  type=PLACE label="Castle" at={rel:NORTH_OF ref:n1}

EXEC      1  ✓ created n7 @-1,0                              41ms

DIFF      + n7 place "Castle" @-1,0

OUTCOME   OK · 4031ms total
```

Every section is expandable to the verbatim prompt and raw output.

**Comparison view** is explicitly out of MVP scope. Export plus external tooling
covers it, and a comparison UI is a research convenience rather than a
prerequisite for producing the research.
