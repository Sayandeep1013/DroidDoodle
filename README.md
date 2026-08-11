# DroidDoodle

An offline Android app in which a small on-device language model manipulates a
structured visual canvas through tool calls, so that typing a sentence visibly
rearranges a world.

It is two things at once, in this order of priority:

1. **A learning laboratory for agentic AI** — tool calling, planning, context
   assembly, entity resolution, validation, failure recovery, and the real
   limits of small local models. Observability is a feature, not a debug flag.
2. **A creative toy** — something that produces a genuine "I told it what to do
   and it actually did it" reaction.

The governing question: *how much apparent agency can be manufactured from a
small local model, a well-defined world, structured state, and excellent tools?*

## Read this first

| Document | Contents |
|---|---|
| [`docs/00-INTENT.md`](docs/00-INTENT.md) | Why, constraints, locked decisions, success criteria. **Start here.** |
| [`docs/10-architecture.md`](docs/10-architecture.md) | Module boundaries and dependency rules |
| [`docs/20-world-model.md`](docs/20-world-model.md) | Nodes, edges, grid, placement resolution |
| [`docs/21-tools.md`](docs/21-tools.md) | The ten tools and their schemas |
| [`docs/22-context.md`](docs/22-context.md) | Prompt assembly and the viewport digest |
| [`docs/23-agent-runtime.md`](docs/23-agent-runtime.md) | Turn lifecycle, validation, undo |
| [`docs/24-trace.md`](docs/24-trace.md) | Trace record format |
| [`docs/25-inference.md`](docs/25-inference.md) | Engine interface, GBNF, model management |
| [`docs/26-settings.md`](docs/26-settings.md) | Settings registry |
| [`docs/31-prompt-suite.md`](docs/31-prompt-suite.md) | The behavioural benchmark |
| [`docs/40-IMPLEMENTATION-PLAN.md`](docs/40-IMPLEMENTATION-PLAN.md) | Work packages and acceptance criteria |

`offline_ai_playground_brainstorm_context.md` is the upstream brainstorm. It is
history, not requirements — where it disagrees with `docs/00-INTENT.md`, the
intent document wins.

## Current state

Packages **P0–P6** are complete and green in CI: the pure-Kotlin agent core.
World model, tools, grammar, context assembly, planner, executor and trace, plus
the Prompt Suite — all running as JVM unit tests with no Android SDK, no
emulator and no model file.

**P7–P10** — Compose UI, the llama.cpp JNI bridge, trace UI, and on-device
measurement — are not started.

What green does **not** mean: nothing has run on a device, no real model has
produced a single plan, and the GBNF grammar has never been fed to llama.cpp.
Every plan the suite executes was written by hand, so it proves the runtime
executes correct plans correctly and says nothing yet about whether a 1B model
can produce them. See [`docs/50-REVERIFICATION.md`](docs/50-REVERIFICATION.md).

## Building

There is no committed Gradle wrapper. Generating `gradle-wrapper.jar` requires a
local Gradle install, which the development environment does not have
(constraint C2 in the intent document); CI provisions a pinned Gradle instead.

With Gradle 8.14+ and JDK 17 available:

```
gradle :core-model:test :core-world:test :core-grammar:test :inference:test :core-agent:test
```

CI runs exactly this on every push.

## Architecture in one picture

```
        user text
            │
            ▼
    ┌───────────────┐   assembles system + tools + board digest
    │   :core-agent │   + reference table + history + message
    └───────┬───────┘
            │  prompt + GBNF grammar (rebuilt each turn)
            ▼
    ┌───────────────┐   LlmEngine: MockEngine (CI) or llama.cpp (device)
    │   :inference  │
    └───────┬───────┘
            │  one constrained generation → a plan of tool calls
            ▼
    ┌───────────────┐   validate → confirm → execute, halting on failure
    │   :core-agent │
    └───────┬───────┘
            │
            ▼
    ┌───────────────┐   immutable Board, grid-snapped, undo by reference
    │   :core-world │
    └───────┬───────┘
            │
            ▼
         the canvas
```

The model contributes intent and structure. Coordinates, referent resolution,
validation and collision belong to the runtime — if it can be determined
deterministically, the model is not asked to infer it.
