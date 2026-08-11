# 26 — Settings

Modules: registry in `:core-model`, storage in `:app`.

Settings are not incidental configuration. They are the world the `set_setting`
tool acts on, which makes the settings registry part of the agent's controllable
environment — the surviving good idea from design direction ③.

---

## 1. Registry

```kotlin
data class SettingDef<T>(
    val key: String,
    val type: SettingType,        // BOOL | INT | FLOAT | ENUM | STRING
    val default: T,
    val range: ClosedRange<Double>?,   // INT and FLOAT only
    val options: List<String>?,        // ENUM only
    val agentWritable: Boolean,
    val requiresReload: Boolean,
    val description: String,           // shown in UI and used in the tool enum
)
```

The registry is the single source of truth for the settings UI, persistence,
validation, and the `set_setting` grammar enum. As with tool schemas, nothing
downstream is hand-maintained.

## 2. Keys

| Key | Type | Default | Range / options | Agent-writable | Reload |
|---|---|---|---|---|---|
| `model.id` | ENUM | unset | from manifest | no | yes |
| `model.temperature` | FLOAT | 0.3 | 0.0 – 1.5 | **yes** | no |
| `model.top_p` | FLOAT | 0.9 | 0.1 – 1.0 | **yes** | no |
| `model.max_tokens` | INT | 384 | 64 – 1024 | no | no |
| `model.context_tokens` | INT | 4096 | 1024 – 8192 | no | yes |
| `model.threads` | INT | 0 | 0 – 8, where 0 means auto | no | yes |
| `agent.loop_strategy` | ENUM | `plan_then_execute` | registered strategies | no | no |
| `agent.max_steps` | INT | 8 | 1 – 12 | **yes** | no |
| `agent.auto_repair` | BOOL | false | — | **yes** | no |
| `agent.confirm_threshold` | INT | 3 | 0 – 20 | **yes** | no |
| `agent.digest_max_nodes` | INT | 25 | 5 – 50 | **yes** | no |
| `agent.history_turns` | INT | 2 | 0 – 6 | **yes** | no |
| `ui.theme` | ENUM | `system` | `system`, `light`, `dark` | **yes** | no |
| `ui.grid_visible` | BOOL | true | — | **yes** | no |
| `ui.cell_size` | ENUM | `medium` | `small`, `medium`, `large` | **yes** | no |
| `trace.enabled` | BOOL | true | — | no | no |
| `trace.retain_turns` | INT | 200 | 20 – 1000 | no | no |

`model.id` has no default. It is unset until the first-run picker completes, and
an unset value is what triggers that flow (`25-inference.md` §6). `model.threads`
uses 0 as a sentinel for the auto policy described in `25-inference.md` §5,
keeping the key a plain INT rather than a union type.

Defaults for `model.temperature` and `model.top_p` are low deliberately.
Structured planning is a task where sampling diversity mostly buys errors, and
grammar constraints already prevent the malformed output that higher
temperatures would otherwise cause — leaving only the semantic mistakes.

## 3. What the agent may and may not change

Agent-writable keys are chosen so that self-modification is genuinely
demonstrable but cannot break the runtime the agent is running inside.

**Writable** covers sampling behaviour, agent bounds, and appearance. "Make
yourself more creative" raising `model.temperature`, or "stop asking me before
deleting things" raising `agent.confirm_threshold`, are real, visible, useful
self-modifications.

**Not writable:**

- `model.id`, `model.context_tokens`, `model.threads` — require an engine
  reload, so an agent writing one mid-turn would be destroying the engine
  currently generating its own output.
- `agent.loop_strategy` — swapping the strategy from inside a running strategy
  is not a meaningful operation.
- `trace.*` — an agent able to disable its own observability defeats the
  project's primary purpose. This one is a hard line.

`set_setting` on a non-writable key is not merely rejected at validation: those
keys are absent from the grammar enum, so the call cannot be emitted at all.

## 4. Validation

Applied in order: key exists → key is agent-writable when the write came from
the model → value parses as the declared type → value is within range or among
options. Failures return `UNKNOWN_SETTING` or `SETTING_OUT_OF_RANGE` with a
message naming the valid range.

## 5. Storage and application

- Persisted with DataStore in `:app`. `:core-*` modules receive a settings
  snapshot as an immutable map, per architecture rule R4.
- A snapshot is taken once at the start of a turn and used for the whole turn,
  so a `set_setting` step never changes the rules mid-plan.
- Writes take effect from the **next** turn. Keys with `requiresReload` prompt
  the user rather than reloading silently, since discarding a loaded model
  without warning would strand an in-flight turn.
- Every turn's trace records the settings snapshot it ran under
  (`24-trace.md` §1), so a behavioural change between runs can be attributed to
  configuration rather than guessed at.
