# 25 — Inference, Grammar, and Model Management

Modules: `:inference` (interface + mock), `:inference-llama` (JNI),
`:core-grammar` (GBNF emission), `:app` (download and storage).

---

## 1. Engine interface

```kotlin
interface LlmEngine {
    val modelId: String
    val contextTokens: Int
    fun tokenCount(text: String): Int
    suspend fun generate(
        prompt: String,
        grammar: String,
        params: SamplingParams,
    ): GenerationResult
    fun close()
}

data class SamplingParams(
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int,
    val seed: Long?,          // null = nondeterministic
)

data class GenerationResult(
    val text: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val prefillMillis: Long,
    val decodeMillis: Long,
    val stopReason: StopReason,   // COMPLETE | MAX_TOKENS | CANCELLED | ERROR
)
```

This interface is the whole of intent criterion L3: `:core-agent` knows nothing
else about models. `tokenCount` lives here because the context budget in
`22-context.md` §1 must be enforced with the *real* tokenizer, not an estimate.

Generation returns a whole result rather than a token stream. Streaming buys a
progress indicator, but under grammar-constrained decoding a partial plan is
never useful — it cannot be executed or even validated. If a token-level
progress display is wanted later, it belongs as a separate optional callback
rather than as the shape of this interface.

## 2. MockEngine

Lives in `:inference`, pure Kotlin, the primary development surface under
constraint C2.

```kotlin
class MockEngine(
    private val script: List<MockResponse>,
    private val tokenizer: (String) -> Int = { it.length / 4 },
    private val outputCheck: OutputCheck = OutputCheck.None,
) : LlmEngine

fun interface OutputCheck {
    fun verify(output: String, grammar: String): Result<Unit, String>
    companion object { val None: OutputCheck }
}
```

Each `MockResponse` supplies canned output text and synthetic timings. Responses
are consumed in order; exhausting the script is a test failure, never a silent
empty response.

`MockEngine` **verifies its own scripted output before returning it**, so a test
cannot assert on output the real grammar-constrained engine could never produce.
Without that check the suite drifts away from reality while staying green.

The check is **injected rather than built in**, because `:inference` must not
depend on `:core-grammar` (`10-architecture.md` §2). `:core-agent` tests supply
the real implementation.

That implementation is `PlanEnvelopeChecker` in `:core-grammar`. It is
deliberately **not** a GBNF interpreter: it parses the envelope and checks it
against the same `ToolSchema` data the grammar is emitted from — same source of
truth, same verdict for every plan we care about, at a small fraction of the
cost of writing a grammar engine. Cases that intentionally exercise the
executor's defence-in-depth against output the grammar could never produce pass
`OutputCheck.None` explicitly, and must say so.

## 3. GBNF generation

`:core-grammar` emits a grammar from the tool registry plus the live board. It
is rebuilt every turn, because the node-id alternatives change.

Shape:

```gbnf
root      ::= "{\"steps\":[" (respond | first ("," step)* ("," respond)?) "]}"
first     ::= findstep | step
step      ::= create | update | move | delete | connect | disconnect
            | arrange | setting
noderef   ::= "\"" (existing | stepref) "\""
existing  ::= "n1" | "n2" | "n7"          # regenerated per turn
stepref   ::= "$1" | "$2" | …             # $1 … $(max_steps - 1), emitted
                                          # dynamically from the live setting
nodetype  ::= "\"PLACE\"" | "\"CHARACTER\"" | "\"OBJECT\"" | "\"NOTE\"" | "\"GROUP\""
placement ::= relplace | cellplace | "{\"auto\":true}"
```

Three structural properties fall out of this and are worth stating plainly:

- **`respond` can only be last**, expressed directly in `root`. A `respond`-only
  plan is explicitly admitted by the leading alternative, since that is the
  correct output for a question or refusal.
- **`find` can only be first**, expressed by `first`.
- **Node ids cannot be hallucinated** — `existing` enumerates real ids.

And one thing the grammar deliberately does **not** do: **GBNF cannot count**,
so `agent.max_steps` is unenforceable here. Step-count and `$k` ordering are
static validation concerns (`23-agent-runtime.md` §6). Pretending otherwise
would be the kind of quiet spec lie that produces a confusing bug later.

### Empty board

When the board has no nodes, `existing` has no alternatives and is omitted;
`noderef` reduces to `stepref` alone. Tools taking a `NodeRef` remain in the
grammar, and a step-1 `update_node($1)` is caught by static validation as
`UNRESOLVED_STEP_REF`. Conditioning tool availability on step position is not
expressible in GBNF, and adding a second mechanism to approximate it would buy
less than it costs.

### Escaping

Free-text fields use the standard GBNF JSON string rule with proper escaping.
Field length limits from `20-world-model.md` §2 are **not** encoded in the
grammar — character-counting rules would balloon it. They are validation
concerns.

### Snapshot testing

`:core-grammar` has snapshot tests over generated grammars for a set of fixture
boards. Adding or renaming a tool argument changes the snapshot, so grammar
drift shows up as a reviewable diff. This is the mechanism behind intent
criterion L5.

## 4. KV cache reuse

Context blocks 1 and 2 — system rules and tool descriptions — are static across
turns and placed first for exactly this reason (`22-context.md`).

`:inference-llama` retains the KV cache for the longest common token prefix
between consecutive prompts and re-prefills only the divergent suffix. On a
mid-range CPU, prefill of ~500 static tokens is a substantial share of turn
latency, so this is the single largest available latency lever.

The cache is invalidated when the model changes, the tool registry changes, or
`model.context_tokens` changes. Prefix-reuse hit length is reported into
`Timings` so the optimisation is measurable rather than assumed.

## 5. llama.cpp binding

- Built for `arm64-v8a` only, via CMake and the NDK, as a Gradle external native
  build.
- Pinned to a specific llama.cpp commit, vendored as a git submodule. Pinning
  matters because GBNF behaviour and the GGUF format both change over time, and
  an unpinned upstream turns a reproducible experiment into a moving target.
- The JNI surface is deliberately tiny: `loadModel`, `freeModel`,
  `generateWithGrammar`, `tokenCount`, `cancel`.
- Threads default to `min(4, availableProcessors - 2)`, leaving headroom so the
  UI thread is not starved during decode.
- No GPU or NNAPI backend in the MVP. On the target class of device the
  available backends are inconsistent, and CPU-only keeps the latency numbers
  interpretable.

## 6. Model management

### Storage

Models live in app-internal storage at `filesDir/models/<modelId>.gguf`. Not
external storage — a model file removed by a cleaner app mid-session would look
like a crash.

### First-run flow

1. No model present → the model picker is shown. The app is unusable without
   one; this is not a dismissible prompt.
2. The picker lists curated candidates with download size, approximate resident
   memory, and a suitability verdict computed from `ActivityManager.MemoryInfo`.
3. A candidate whose estimated resident size exceeds available memory is shown
   with an explicit warning and requires a second confirmation. It is not
   silently hidden — on a ≤6GB device, discovering the boundary is part of the
   point.
4. Download is resumable, checksummed with SHA-256, and written to a `.part`
   file promoted on success. A failed checksum deletes the file rather than
   leaving a corrupt model that produces baffling output.
5. After the first successful download the app performs **no further network
   I/O** (intent criterion C3/T3).

### Candidate models

Curated candidates are defined in a JSON manifest bundled with the app, not
hard-coded, so the list can change without a code change.

**The specific model list is deliberately not fixed in this spec.** Selection is
a phase-3 task with its own acceptance criteria: candidates must be verified for
current availability, GGUF Q4 file size, measured resident memory on the target
device, and Prompt Suite pass rate. Small instruct-tuned models in the 0.5B–1.5B
range, including function-calling-tuned variants, are the search space. Writing
names into a spec that were not measured on the target hardware would be
exactly the kind of unverified claim this project is meant to avoid.

The manifest schema is fixed now, so implementation is unblocked:

```json
{
  "id": "string",
  "displayName": "string",
  "url": "https://…",
  "sha256": "hex",
  "fileBytes": 0,
  "estimatedResidentBytes": 0,
  "contextTokens": 4096,
  "promptTemplate": "chatml | llama3 | gemma | plain"
}
```

### Prompt templates

Instruct models expect specific turn delimiters. `promptTemplate` selects a
formatter applied around the assembled context. A mismatched template degrades
output quality in ways easily mistaken for the model being weak, so the template
in use is recorded in every trace.
