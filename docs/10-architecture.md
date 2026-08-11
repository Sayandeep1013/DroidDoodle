# 10 — Module Architecture

Derived from `00-INTENT.md` constraints C2 and C4: nothing compiles locally, and
device iteration costs a CI round trip. Therefore the maximum possible surface
area must be testable on a bare JVM.

---

## 1. Modules

```
:core-model        value types shared by everything          pure Kotlin
:core-world        board state, grid, placement, undo        pure Kotlin
:core-agent        tool registry, planner, executor, trace   pure Kotlin
:core-grammar      GBNF emission from tool schemas           pure Kotlin
:inference         LlmEngine interface + MockEngine          pure Kotlin
:inference-llama   JNI bridge to llama.cpp                   Android + NDK
:app               Compose UI, DI, persistence, downloads    Android
```

## 2. Dependency rules

```
:core-model   ← (nothing)
:core-world   ← :core-model
:core-grammar ← :core-model
:inference    ← :core-model
:core-agent   ← :core-model, :core-world, :core-grammar, :inference
:inference-llama ← :core-model, :inference
:app          ← everything
```

These rules are **enforced, not advisory**:

- **R1** — No module named `core-*` or `:inference` may declare a dependency on
  `com.android.*`, `androidx.*`, or the Android Gradle plugin. They are
  `org.jetbrains.kotlin.jvm` modules. The Android SDK is not on their compile
  classpath, so a violation is a compile error rather than a review finding.
- **R2** — No `core-*` module may depend on `:app` or `:inference-llama`.
- **R3** — `:core-agent` talks to models only through the `LlmEngine` interface
  from `:inference`. It never references `:inference-llama`.
- **R4** — No `core-*` module may perform I/O: no file access, no network, no
  clock reads, no random number generation. Time and randomness are injected as
  parameters. This is what makes the whole runtime deterministically testable.

R4 has teeth. `Clock` and `IdGenerator` are constructor parameters throughout,
never ambient singletons.

## 3. Why this split

`:core-model` through `:inference` — five of the seven modules — contain the
entire agent design: the world, the tools, the planner, the validator, the
grammar, the trace, and the mock model. They compile and test in seconds on a
JVM runner with no Android SDK and no emulator, satisfying intent criterion L4.

`:inference-llama` is the only module requiring the NDK, and it is deliberately
thin: it loads a model, accepts a prompt and a grammar, and streams tokens. It
holds no agent logic. This bounds the surface that can only be tested on
hardware.

`:app` holds Compose UI, Room persistence, the settings store, the model
downloader, and dependency wiring. It holds no agent logic either.

## 4. Testability contract

| Module | Test type | Runs in CI without a device |
|---|---|---|
| `:core-model` | JVM unit | yes |
| `:core-world` | JVM unit, property-based | yes |
| `:core-agent` | JVM unit, full turn simulation vs `MockEngine` | yes |
| `:core-grammar` | JVM unit, plus generated-grammar snapshot tests | yes |
| `:inference` | JVM unit (`MockEngine` only) | yes |
| `:inference-llama` | instrumented | no — device required |
| `:app` | Compose UI tests, Maestro smoke flows | no — device required |

The Prompt Suite (`31-prompt-suite.md`) runs entirely against `:core-agent` plus
`MockEngine`, so behavioural regressions surface in CI without hardware.

## 5. Package naming

Root package `dev.droiddoodle`, then the module's own segment:
`dev.droiddoodle.world`, `dev.droiddoodle.agent`, `dev.droiddoodle.grammar`,
`dev.droiddoodle.inference`, `dev.droiddoodle.inference.llama`,
`dev.droiddoodle.app`.

### Naming decisions taken during implementation

Spec snippets elsewhere use short illustrative names. Where a literal reading
would have caused a collision, the implementation uses these instead, and the
specs should be read accordingly:

| Spec snippet | Implementation | Why |
|---|---|---|
| `Result<V, E>` | `Res<V, E>` | `kotlin.Result` is auto-imported everywhere and carries no error type |
| `Color`, `Size` | `NodeColor`, `NodeSize` | `Color` collides with Compose in `:app` |
| `enum class WorldError` | `WorldErrorCode` + `WorldError(code, message)` | Errors must carry a model-readable message, which an enum alone cannot |
| `Placement.Relative(ref: NodeId)` | `ref: NodeRef` | Placement appears in tool arguments, where `$k` step references are legal; the executor substitutes them before the world layer sees them, and the world layer rejects any that survive |

Tool *schemas* live in `:core-model` (`ToolCatalog`) rather than `:core-agent`,
so `:core-grammar` can emit from them without depending on the agent. Execution
behaviour is still added in `:core-agent`.

## 6. Build and toolchain

- Gradle with Kotlin DSL and a version catalog at `gradle/libs.versions.toml`.
- **No committed Gradle wrapper.** Generating `gradle-wrapper.jar` requires a
  local Gradle install, which constraint C2 rules out. CI provisions a pinned
  Gradle via `gradle/actions/setup-gradle`, which is equivalent for our
  purposes. A wrapper can be added later from any machine that has Gradle.
- JVM toolchain 17 for all modules.
- Kotlin `explicitApiWarning()` on every `core-*` module and `:inference`.
  Strict `explicitApi()` is the intended end state, but under C2 a missing
  `public` modifier would fail the build with a CI-round-trip feedback loop,
  turning a style rule into a blocker. Flip to strict once CI is green.
- `kotlinx-serialization-json` for trace export and tool argument parsing. It is
  the only third-party dependency permitted in `core-*` modules.
- `:inference-llama` builds `arm64-v8a` only. Other ABIs are not produced;
  32-bit ARM and x86 devices are unsupported targets.
- No dependency in a `core-*` module may pull in `java.time` — `Clock` is our own
  minimal interface returning epoch millis, per R4.

## 7. CI

GitHub Actions, `ubuntu-latest`.

- **`jvm` job** — runs on every push and pull request. Executes
  `./gradlew :core-model:test :core-world:test :core-agent:test
  :core-grammar:test :inference:test` plus the dependency-rule check. Requires
  no Android SDK and no NDK. This is the fast feedback loop and the gate that
  matters day to day.
- **`android` job** — assembles the debug APK, requiring the Android SDK and NDK
  for the llama.cpp build. Slower, cached, and not a prerequisite for the `jvm`
  job.

Splitting the jobs means core logic failures report in a couple of minutes rather
than waiting on a native build.
