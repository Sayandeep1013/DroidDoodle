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

### Operator precedence

**GBNF binds `|` looser than concatenation.** Any rule body containing an
alternation must be bracketed, or the alternation splits the entire rule rather
than the part it was meant to cover. `GrammarBuilder` therefore parenthesises
every tool body unconditionally.

This is not a style rule. Getting it wrong produced

    tool-find ::= "{...{" ( A ) | ( B ) | ( C ) "}}"

which means `( "{...{" A ) | ( B ) | ( C "}}" )` — so a bare `"type":"OBJECT"`
fragment, with no braces at all, was a complete legal match. It reached
production and made the first on-device run's pass rate meaningless. See
`results/README.md`.

### Snapshot testing

`:core-grammar` has snapshot tests over generated grammars for a set of fixture
boards. Adding or renaming a tool argument changes the snapshot, so grammar
drift shows up as a reviewable diff. This is the mechanism behind intent
criterion L5.

Snapshots alone are not enough, and the precedence defect proved it: they assert
the grammar is *stable*, not *correct*, and `PlanEnvelopeChecker` is
schema-equivalent rather than a GBNF interpreter, so a grammar that admits **too
much** falls exactly between the two. `GrammarAlternationTest` covers that gap by
asserting the emitted shape — every tool rule must be a single top-level
alternative opening with its own tool literal.

The remaining honest gap: nothing on the JVM interprets GBNF, so "the sampler
will accept only what we intend" is still checked structurally rather than
executed. A device run is the only place that claim is tested.

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

> **Status: implemented, unmeasured.** The bridge keeps the exact token
> sequence held in the KV cache, finds the longest common prefix with the next
> prompt, drops everything after it, and decodes only the divergent suffix.
> `cachedPrefixTokens` reports the reuse length per round.
>
> It deliberately never reuses the *entire* prompt: sampling needs logits, and
> logits come only from a token decoded this turn, so the last prompt token is
> always re-decoded.
>
> The measurement that justified it, from the first on-device run: prefill was
> **56% of turn latency** — 14.1s median for 589 tokens at 42 tok/s, against
> 10.5s of decode. Assembly, grammar emission and execution together came to
> 7ms, which is to say the Kotlin is free and the entire cost is the model.
>
> **Whether it helped is not yet known.** The next device run answers that, and
> until it does this is an optimisation with a rationale rather than a result.

## 5. llama.cpp binding

- Built for `arm64-v8a` only, via CMake and the NDK, as a Gradle external native
  build.
- Pinned to a specific llama.cpp commit, vendored as a git submodule. Pinning
  matters because GBNF behaviour and the GGUF format both change over time, and
  an unpinned upstream turns a reproducible experiment into a moving target.
- The JNI surface is deliberately tiny. As built it is `backendInit`,
  `loadModel`, `freeModel`, `tokenCount`, `generate` — still five functions, but
  not the five this spec first listed. `generateWithGrammar` is just `generate`,
  since every call is grammar-constrained and the qualifier says nothing.
  `cancel` is gone: generation runs inside a coroutine and the decode loop
  checks for cancellation between tokens, so cancelling the caller is enough and
  a second cancellation channel would be a way for the two to disagree.
  `backendInit` appeared because `llama_backend_init` is a real one-time step
  that has to happen somewhere.
- The bridge is pinned to a revision, and that pin has already earned itself:
  the pinned commit is the one that replaced `llama_model_params.use_mmap` with
  a `load_mode` enum. An unpinned build would have broken on an unrelated day
  for an unrelated reason.
- Loading uses `LLAMA_LOAD_MODE_MMAP`, not `AUTO` and not `MLOCK`. Mapping lets
  the kernel evict model pages under memory pressure rather than the app being
  killed; locking ~700MB resident on a ≤6GB device is a way to guarantee the
  kill.
- The sampler chain is grammar → top_p → temperature → dist, in that order, so
  probability shaping only ever chooses among continuations the grammar already
  permits. `llama_sampler_sample` accepts the sampled token internally; calling
  `llama_sampler_accept` after it would advance the grammar state twice per
  token and silently corrupt it.
- llama and ggml are linked statically with `c++_static`, so the APK carries a
  single `libdroiddoodle_llama.so`.
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
  "promptTemplate": "chatml | llama3 | gemma | plain",
  "note": "string, optional -- shown in the picker"
}
```

The file wraps this in `{ "schemaVersion": 1, "models": [ … ] }`. A manifest
declaring a schema this build does not read is rejected whole; an individual
entry that fails validation is dropped rather than defaulted, because a
defaulted template or a malformed checksum costs a several-hundred-megabyte
download before it fails.

### What implementation found

- **Google's own Gemma GGUF repositories are gated.** They require an accepted
  licence and a bearer token, which an offline-first app with no account cannot
  supply. The ungated `ggml-org` mirrors are used instead. Any future candidate
  has to be checked for this before it goes in the manifest.
- Every `sha256` and `fileBytes` in the shipped manifest was read from the
  file's HuggingFace LFS pointer, so they are facts. `estimatedResidentBytes` is
  a guess — file size plus a coarse KV allowance — and is labelled as such in
  the manifest itself. Replacing those with device measurements is P10.
- Resumption was verified against the CDN rather than assumed: a ranged request
  answers `206 Partial Content` with a `Content-Range`.
- Redirects are followed by hand. HttpURLConnection's automatic handling is not
  specified to carry request headers across hops, and dropping `Range` silently
  restarts a resumed download from zero. Doing it explicitly also enforces the
  HTTPS-only rule at every hop rather than only the first.
- The SHA-256 is computed by reading the finished file back, not accumulated
  during transfer. A download resumed in a later process has no digest state to
  continue from, and a re-read costs seconds against a transfer that costs
  minutes.

### Prompt templates

Instruct models expect specific turn delimiters. `promptTemplate` selects a
formatter applied around the assembled context. A mismatched template degrades
output quality in ways easily mistaken for the model being weak, so the template
in use is recorded in every trace.

The formatter lives in `:inference` as `PromptTemplate`, and the **engine**
applies it — not `:core-agent`, which under criterion L3 knows nothing about
models beyond `LlmEngine`. Two consequences worth stating:

- The whole assembled context goes in a single user turn. No template gains
  anything from a separate system turn here, and Gemma has no system role at all.
- No template writes a BOS token into the text. Tokenisation uses
  `add_special = true`, which adds the correct one, or none for ChatML
  tokenisers that set `add_bos_token=false`. A literal BOS would double it.
- `LlamaEngine.contextTokens` reports the model window **minus** the tokenised
  template envelope, measured once at load with the real tokeniser. Reporting
  the raw window would let the context budget overspend by exactly the amount
  the agent cannot see.
