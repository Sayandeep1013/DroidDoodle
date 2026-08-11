# Offline AI Playground — Product Discovery & Agent Brainstorming Context

## 0. Purpose of This Document

This document captures the current thinking from an early product-discovery conversation.

It is **not a final PRD**, architecture, feature specification, or implementation plan.

The purpose is to give downstream AI agents a rich, coherent starting context so they can:

- brainstorm product directions,
- challenge assumptions,
- identify technical constraints,
- propose alternative concepts,
- investigate feasibility,
- reason about small on-device models,
- explore agentic/tool-calling architectures,
- and eventually converge on a compelling mobile product.

The product is intentionally still undefined.

The central idea is much clearer than the final app concept:

> Build a mobile application containing an on-device AI model that can understand natural-language or voice instructions and perform actions **inside the application itself** through tool calling.

The application should feel creative, playful, exploratory, and surprisingly capable rather than like a conventional productivity assistant.

The project also has a deliberate secondary purpose:

> Use the application as a practical learning laboratory for agentic AI: tool calling, intent routing, context, state, planning, execution loops, permissions, memory, local inference, and model limitations.

Agents should preserve this dual purpose when brainstorming.

---

# 1. The Core Idea

The initial idea was:

> A small mobile app where an AI model is shipped locally and has tool-calling capabilities that allow it to perform tasks offline.

However, the concept is **not** intended to become a generic Android assistant.

The AI should primarily operate **inside its own application world**.

Think:

**AI + application state + tools + creative sandbox**

rather than:

**Chatbot + Android automation**

The model should not normally leave the application and start controlling arbitrary phone applications.

Instead, the app itself exposes capabilities as tools.

For example:

User:

> "Change the name of this conversation to Game Ideas."

The model interprets the request and calls something conceptually like:

```text
rename_conversation(
    conversation_id,
    new_name="Game Ideas"
)
```

The application updates its own state.

The model did not need Android UI automation.

It simply manipulated application state through a controlled tool.

---

# 2. The User's Intended Experience

The desired feeling is approximately:

> "Whoa, I can tell this thing what to do and it actually manipulates the app."

and:

> "Whoa, I can create almost anything inside this little world."

The app should encourage users to experiment.

The user should be able to say unusual, ambiguous, creative, or multi-step things and discover that the AI can actually perform them.

The goal is not merely:

> "Ask AI a question and receive an answer."

The goal is:

> "Tell AI what you want to happen, and watch the application change."

The application itself becomes the playground.

---

# 3. Chat Is Not Necessarily the Only Interface

The application can have a chat-like interface, but it should not be thought of as "just a chat app."

Possible interaction modes include:

### Text

The user types a natural-language request.

### Voice

The user speaks a request.

A local speech-to-text model such as Whisper could transcribe it.

Example:

> "Change the VAD from point five to point seven."

The transcription becomes the model's input.

### Voice mode

The user can explicitly activate a voice interaction mode and speak naturally.

### Wake word

A future version could potentially support a wake word to activate the agent.

Wake-word support is interesting but should not become a core requirement for the first MVP.

The key idea is:

> Natural language should be the primary control surface.

---

# 4. Concrete Example: AI Controlling Its Own Settings

A particularly useful example emerged during discussion.

Imagine the app has settings such as:

```text
Model
- model selection
- temperature
- context length
- max tokens

Voice
- Whisper model
- VAD
- silence threshold
- language
- wake word

Agent
- tool calling
- max tool steps
- memory
- confirmation behavior
- debug mode
```

The user activates the local model and says:

> "Change the VAD from 0.5 to 0.7."

The system could work approximately like:

```text
Voice
  ↓
Whisper / local ASR
  ↓
"change the VAD from 0.5 to 0.7"
  ↓
Local LLM
  ↓
Intent: change_setting
  ↓
Tool:
set_setting(
    setting="vad",
    value=0.7
)
  ↓
Application state changes
  ↓
Settings UI reflects 0.7
```

The model does not need to navigate the settings screen visually.

It operates on the application's structured state.

This distinction is extremely important.

---

# 5. The General Architecture Emerging From the Idea

A conceptual architecture:

```text
                     USER
                  /        \
               TEXT        VOICE
                |            |
                |        Local ASR
                |        (Whisper etc.)
                |            |
                +----- INPUT +
                         |
                         v
                +------------------+
                |    LOCAL LLM     |
                |                  |
                | Intent           |
                | Context          |
                | Tool selection   |
                | Arguments        |
                +--------+---------+
                         |
                    Tool calls
                         |
                         v
                +------------------+
                |   TOOL RUNTIME   |
                |                  |
                | create()         |
                | modify()         |
                | move()           |
                | delete()         |
                | search()         |
                | set_setting()    |
                | etc.             |
                +--------+---------+
                         |
                         v
                +------------------+
                |   APP STATE      |
                |                  |
                | objects           |
                | settings         |
                | scenes           |
                | relationships    |
                | user data        |
                +--------+---------+
                         |
                         v
                +------------------+
                |       UI         |
                |                  |
                | visualizes state |
                +------------------+
```

Potentially:

```text
Tool result
    ↓
LLM observes result
    ↓
Decides whether another action is needed
    ↓
Next tool call
```

This creates an actual agent loop.

---

# 6. What This Is NOT

Agents should avoid drifting toward these interpretations unless there is a compelling reason.

## Not primarily an Android assistant

The app is not intended to be:

> "AI that controls my whole phone."

Examples like:

- setting alarms,
- opening arbitrary apps,
- sending arbitrary messages,
- changing phone settings,
- controlling the entire Android UI,

are technically interesting but are not the core product.

## Not primarily a productivity assistant

A todo list, reminder manager, document assistant, or generic note-taking app could use this architecture, but they do not capture the desired creative/playground feeling.

## Not simply a chatbot

The model should cause real state changes and perform actions.

## Not dependent on cloud inference

The core concept is offline/local inference.

Cloud features could theoretically exist later, but the fundamental experience should remain viable locally.

---

# 7. The Small-Model Constraint

One of the strongest constraints is:

> The model should not be unnecessarily heavy.

The goal is not to ship a huge model that solves every problem.

The user specifically wants to understand how far a relatively small local model can be pushed through good agent architecture.

The important insight discovered during discussion is:

> The model does not need to understand an unrestricted universe if the application gives it a well-defined world and a well-designed tool system.

For example, instead of asking a small model to directly construct an arbitrary world, give it structured tools such as:

```text
create_scene()
create_character()
create_location()
create_object()
move_entity()
modify_entity()
delete_entity()
connect_entities()
set_property()
search_entities()
```

The model's job becomes:

1. understand what the user means,
2. identify entities,
3. choose an appropriate tool,
4. produce structured arguments,
5. observe the result,
6. continue if necessary.

The application engine handles:

- state consistency,
- coordinates,
- rendering,
- collision,
- relationships,
- persistence,
- validation,
- undo/redo,
- etc.

This allows a small model to appear much more capable than its raw generative ability might suggest.

---

# 8. Important Principle: Make the World Structured

A major product/architecture principle emerged:

> **Make the model small by making the world well-defined, not by making the experience boring.**

The application can define:

- entities,
- properties,
- relationships,
- scenes,
- actions,
- transformations,
- rules,
- constraints.

The AI operates on those structures.

For example:

```json
{
  "entities": [
    {
      "id": "village",
      "type": "location",
      "name": "Village"
    },
    {
      "id": "castle",
      "type": "location",
      "name": "Castle"
    },
    {
      "id": "blacksmith",
      "type": "character",
      "name": "Borin"
    }
  ],
  "relationships": [
    {
      "type": "located_in",
      "subject": "blacksmith",
      "object": "village"
    }
  ]
}
```

The model can modify this representation using tools.

Natural language effectively becomes a high-level interface to structured application state.

---

# 9. Creative Sandbox Direction

A major direction that emerged is an AI-controlled creative sandbox.

Example interaction:

User:

> "Create a tiny village."

The application could create:

```text
Village
├── Blacksmith
├── Tavern
├── 8 villagers
├── Forest
└── Castle
```

Then:

> "Make the blacksmith secretly a vampire."

The model calls a tool that modifies the character's properties.

Then:

> "Move the castle north of the village and put a river between them."

The model identifies:

- castle,
- village,
- spatial relationship,
- river,

and calls appropriate tools.

The actual geometry/world simulation is handled by the application.

The model is primarily the interpreter/planner.

---

# 10. Example of Structured Tool Use

A request:

> "Make the blacksmith secretly a vampire."

could conceptually become:

```text
modify_entity(
    entity="blacksmith",
    property="secret_identity",
    value="vampire"
)
```

A request:

> "Move the castle north of the village."

could become:

```text
move_entity(
    entity="castle",
    relation="north_of",
    reference="village"
)
```

The application then determines the actual position.

This avoids requiring the model to calculate arbitrary game-world geometry.

---

# 11. A More Advanced Creative Playground

A more ambitious version could contain:

```text
Objects
Characters
Locations
Scenes
Relationships
Rules
Events
Properties
Groups
```

The user might say:

> "Create a dungeon."

Then:

> "Give it three rooms."

Then:

> "Put a dragon in the last room."

Then:

> "Make the dragon afraid of frogs."

Then:

> "Create five frogs."

Then:

> "Make one frog secretly a wizard."

Then:

> "Move the wizard frog into the dragon's room."

The interesting part is not necessarily the complexity of each operation.

It is the **composition of operations and context**.

The system has to understand references such as:

- "it"
- "the last room"
- "that character"
- "the other side"
- "everything except..."
- "make that one..."
- "undo that"

This makes context management and intent resolution meaningful.

---

# 12. Creative Canvas Direction

Another possible implementation is an AI-native 2D canvas.

The user could create and manipulate:

```text
cards
shapes
characters
objects
text
images
connections
groups
scenes
```

Example:

> "Make a board for my game idea."

AI creates a board.

> "Put the important mechanics on the left and the stupid ideas on the right."

AI rearranges objects.

> "Make this look like something you'd find in a cyberpunk laboratory."

AI modifies the visual presentation.

> "Scrap everything except the grappling hook idea."

AI identifies the relevant objects and deletes the rest.

> "Give the boss three phases and make phase two ridiculous."

AI modifies the appropriate structured content.

This direction is potentially easier to constrain than a fully simulated world.

---

# 13. Three Candidate Product Directions

At this stage, no final choice has been made.

## A — Creative Canvas

A visual playground where users create and manipulate structured objects.

Strengths:

- easier to constrain,
- easier to implement,
- strong visual feedback,
- natural fit for tool calling,
- relatively small model requirements,
- good MVP candidate.

## B — World Sandbox

A small simulated world with:

```text
locations
characters
objects
relationships
events
rules
```

Strengths:

- extremely playful,
- strong agentic behavior,
- context becomes important,
- naturally demonstrates multi-step tool use.

Weaknesses:

- substantially more engineering,
- larger state model,
- more rendering/simulation complexity.

## C — General / Weird AI Playground

A more flexible environment built around:

```text
objects
properties
relationships
scenes
transformations
```

The user might be able to create one kind of thing and then transform it into something completely different.

Example:

> "Make a dungeon."

then:

> "Turn it into a board game."

then:

> "Make all the enemies frogs."

then:

> "Turn the entire thing into a spaceship."

This is currently the most ambitious and potentially most interesting long-term direction.

A likely strategy is to prototype a constrained A/B version and evolve toward C.

---

# 14. The "AI Playground" Feeling

The product should encourage experimentation.

A successful session might look like:

```text
User:
"Create an island."

AI:
creates island

User:
"Put a village on the east side."

AI:
moves/creates village

User:
"Give it a tavern, blacksmith and six villagers."

AI:
creates entities

User:
"Make the blacksmith secretly a vampire."

AI:
modifies character

User:
"Put a castle north of the village."

AI:
moves castle

User:
"Actually destroy the village."

AI:
removes it

User:
"Undo that."

AI:
restores previous state
```

The user should continually think:

> "What else can I make it do?"

That is the "go brrr" quality.

---

# 15. Agent Debug Mode

Because the project is also intended as a learning/research project, a developer/debug mode would be highly valuable.

Instead of hiding the agent loop, the app could expose it.

For example:

```text
USER
"Put the castle north of the village."

        ↓

INTENT
spatial_modification

        ↓

ENTITIES
castle
village

        ↓

TOOL
move_entity

        ↓

ARGUMENTS
{
    entity: castle,
    relation: north_of,
    reference: village
}

        ↓

RESULT
success

        ↓

STATE CHANGE
castle.position = ...
```

This would make the application a practical environment for studying:

- intent classification,
- tool selection,
- argument extraction,
- context resolution,
- tool failures,
- retries,
- agent loops,
- planning,
- state changes,
- model errors.

A debug timeline could eventually show every model/tool/state transition.

---

# 16. The Agentic AI Learning Goals

The project should deliberately exercise:

### Tool calling

How does a local model select tools and generate valid arguments?

### Intent routing

Should every request go directly to the LLM?

Could simple requests use deterministic routing while ambiguous ones use the LLM?

### Context

How much conversation history is actually needed?

How should structured application state be provided to the model?

### Entity resolution

How does:

> "Move that to the other side."

resolve "that" and "other side"?

### Planning

How does:

> "Create a village with a tavern, blacksmith, river and castle."

become a sequence of tool calls?

### Execution

What happens when a tool fails?

### Validation

How do we prevent malformed or impossible state changes?

### Permissions

Which operations require user confirmation?

### Memory

What should persist between conversations?

### Local inference

How small can the model be before tool use becomes unreliable?

### Quantization

What are the RAM/storage/latency tradeoffs?

### Context window

How much structured world state can be safely supplied?

### Agent loops

How many tool calls should be allowed before stopping?

### Observability

How can we inspect what the model believed, selected, and executed?

---

# 17. External Device Access Is an Exception, Not the Core

The app may sometimes need access to resources outside itself.

Example:

> "Change my profile picture to another image from my gallery."

The model should recognize that this requires an external resource.

The flow could be:

```text
User request
    ↓
Model understands task
    ↓
Determine external resource required
    ↓
Request appropriate permission / invoke picker
    ↓
User selects image
    ↓
Application receives asset
    ↓
AI/application updates profile
```

If the user says:

> "Use the picture where I'm wearing the black shirt."

then vision becomes relevant.

That could require a more capable vision model or a separate image-analysis pipeline.

This is intentionally **not a first-MVP requirement**.

It is an example of how the boundary between the app's internal world and external device resources could work.

---

# 18. Vision Is a Possible Future Capability

A future version could potentially support:

- image understanding,
- visual search,
- image selection based on descriptions,
- visual manipulation,
- image generation,
- multimodal context.

But this introduces:

- larger models,
- more memory,
- more compute,
- more latency,
- more battery usage.

Therefore:

> Vision should not be assumed for the initial concept.

The initial product should be designed so that a small text/voice model can already produce a compelling experience.

---

# 19. Voice Architecture Direction

A possible local voice pipeline:

```text
Wake word / voice activation
        ↓
Voice capture
        ↓
VAD
        ↓
Local ASR
        ↓
Transcript
        ↓
Local LLM
        ↓
Tool calls
        ↓
Application state changes
        ↓
Optional local TTS response
```

Potential future components:

- wake-word detection,
- VAD,
- Whisper or another local ASR,
- local LLM,
- local TTS.

The app itself already has a conceptual relationship with model configuration, including settings such as:

```text
temperature
tone
VAD
model
context
etc.
```

Those settings can themselves become part of the agent's controllable environment.

---

# 20. A Critical Architectural Principle

The application should ideally separate:

```text
MODEL
```

from:

```text
AGENT RUNTIME
```

from:

```text
APPLICATION STATE
```

from:

```text
UI
```

Conceptually:

```text
             LOCAL MODEL
                  |
                  v
             AGENT CORE
        +---------+---------+
        |                   |
    context              tools
        |                   |
        +---------+---------+
                  |
                  v
             APP STATE
                  |
                  v
                 UI
```

This makes it possible to experiment with different models without rewriting the application.

It also makes it possible to test the agent with deterministic/mock models.

---

# 21. Tool Design Should Be a First-Class Product Concern

Do not design tools as an afterthought.

The tool vocabulary effectively defines what the AI can do.

Poor tool design:

```text
do_everything()
```

Better:

```text
create_entity()
modify_entity()
move_entity()
delete_entity()
connect_entities()
set_property()
search_entities()
```

But overly granular tools can also make the model inefficient.

Agents should investigate:

- ideal tool granularity,
- tool descriptions,
- JSON schema design,
- enums,
- required vs optional arguments,
- validation,
- tool result formatting,
- error messages,
- tool discoverability,
- tool grouping,
- dynamic tool availability.

---

# 22. Context Is More Than Chat History

The model potentially needs multiple kinds of context:

```text
Conversation context
+
Application state
+
Relevant entities
+
Current screen/scene
+
Available tools
+
Recent tool results
+
User preferences
+
Agent memory
```

The system should investigate whether the entire application state should ever be placed into the context window.

Likely alternatives include:

- current-screen state,
- relevant entity retrieval,
- structured state summaries,
- search tools,
- state IDs,
- context compression,
- deterministic references.

This is one of the core technical problems worth exploring.

---

# 23. The Small Model May Be More Capable Than Expected

The project should not assume:

> "Small model = dumb agent."

A small model can potentially become useful if:

- the tool set is constrained,
- tool schemas are clear,
- application state is structured,
- instructions are concise,
- tasks are decomposed,
- deterministic logic handles validation,
- context retrieval is intelligent,
- tool results are concise,
- the model isn't asked to solve problems outside its capability.

The application should therefore test the **system**, not merely benchmark the raw model.

---

# 24. What We Should NOT Prematurely Decide

Do not lock down:

- exact model,
- exact parameter count,
- exact quantization,
- exact framework,
- exact UI,
- exact sandbox concept,
- exact tool list,
- Android-only vs cross-platform,
- wake-word implementation,
- vision architecture,
- memory architecture,
- cloud fallback.

Those should emerge from feasibility research and prototyping.

---

# 25. Questions Future Agents Should Investigate

Agents working from this document should explicitly explore:

### Product

1. What kind of playground would make people want to experiment?
2. What interaction produces the strongest "wow" moment?
3. What is the smallest compelling world we can build?
4. What makes the experience different from ChatGPT with buttons?
5. What could users create repeatedly rather than once?

### Model

6. What size local model is realistically sufficient?
7. Which current mobile-friendly models are strongest for structured tool calling?
8. What quantization levels are practical?
9. How much RAM do different models require?
10. What latency should we expect on modern Android devices?
11. Can a small model reliably perform multi-step tool calls?

### Agent architecture

12. Do we need an intent router separate from the LLM?
13. When should deterministic routing handle a request?
14. When should the LLM be invoked?
15. How should tool results be fed back?
16. How do we prevent runaway tool loops?
17. How do we validate tool calls?
18. How should failures be recovered?
19. How should undo/redo work?
20. How should permissions/confirmations work?

### Context

21. How should application state enter the context?
22. How much state should be retrieved dynamically?
23. How should references such as "that", "it", "the other one" be resolved?
24. How much conversation history is actually useful?
25. Should application state have its own retrieval/indexing system?

### Voice

26. What local ASR model is appropriate?
27. What is the latency of speech → text → LLM → tool?
28. Is wake-word detection practical?
29. Can voice mode feel conversational without requiring a huge model?
30. Should the app use local TTS?

### UX

31. How should the user know what the AI can do?
32. Should tool execution be visible?
33. Should the app have an "agent trace"?
34. When should actions require confirmation?
35. How should the user correct an action?
36. How should undo work?

### Sandbox

37. Canvas vs simulated world vs hybrid?
38. What is the smallest useful object model?
39. Which relationships matter?
40. What should users be able to create?
41. How much freedom should users have?
42. How can a small model interact with a rich environment?

---

# 26. Current Working Hypothesis

The strongest current hypothesis is:

> **Build a small, offline, AI-native mobile playground where natural language and voice are the primary controls, and a local model manipulates a structured visual world through tools.**

The AI is not the world.

The application is the world.

The model is the interpreter/planner.

The tool runtime is the actuator.

The state system is the source of truth.

The UI visualizes what happened.

This can simultaneously become:

1. a fun consumer-facing AI toy,
2. a serious experiment in local agentic AI,
3. a practical learning environment for tool calling and context,
4. and a testbed for understanding what small on-device models can actually accomplish.

---

# 27. Immediate Next Step

Do **not** start implementation yet.

First perform a structured brainstorming/research phase.

The next agents should produce:

### Phase 1 — Product ideation

Generate 10–20 possible versions of the AI playground.

For each:

- core interaction,
- what the user creates/manipulates,
- example interactions,
- why it is fun,
- why it demonstrates agentic AI,
- tool complexity,
- state complexity,
- model difficulty,
- MVP scope,
- long-term potential.

### Phase 2 — Compare concepts

Score concepts against:

```text
Fun
Novelty
Small-model feasibility
Tool-calling depth
Context complexity
Visual appeal
Voice compatibility
Implementation complexity
Offline feasibility
Potential to demonstrate agentic AI
Potential for future expansion
```

### Phase 3 — Select 2–3 finalists

Do not immediately pick one.

Develop each finalist enough to understand its architecture.

### Phase 4 — Model feasibility

For the shortlisted concepts:

- identify realistic local models,
- test tool-calling capability,
- estimate RAM,
- estimate storage,
- estimate latency,
- estimate token/context requirements,
- determine whether a separate intent router is useful.

### Phase 5 — Agent architecture

Design:

```text
Input
↓
Intent
↓
Context retrieval
↓
Tool selection
↓
Validation
↓
Execution
↓
State update
↓
Observation
↓
Next action / response
```

### Phase 6 — MVP

Only after the above should the project become a concrete implementation plan.

---

# 28. The Core Question

Everything should eventually answer this:

> **What is the smallest offline AI playground that makes a user say "holy shit, I can actually tell this thing what to do"?**

Not:

> "How many features can we cram into it?"

Not:

> "How powerful a model can we ship?"

Not:

> "How many Android APIs can the AI control?"

Instead:

> **How much apparent agency can we create from a small local model, a well-designed world, structured state, and excellent tools?**

That is the central experiment.
