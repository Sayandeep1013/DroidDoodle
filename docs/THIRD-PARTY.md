# Third-party assets and code

Everything vendored into this repository, and why it is vendored rather than
fetched.

---

## Material Symbols (icons)

- **Source:** https://github.com/google/material-design-icons
- **Licence:** Apache License 2.0
- **Where:** `app/src/main/res/drawable/ic_*.xml`

Thirteen outlined symbols — `castle`, `person`, `inventory_2`, `sticky_note_2`,
`workspaces`, `memory`, `science`, `receipt_long`, `download`, `undo`, `redo`,
`add`, `remove` — converted from the upstream SVGs to Android `VectorDrawable`.

Undo, redo and the zoom glyphs are vendored rather than taken from
`Icons.Filled`: `material-icons-core` carries only a subset, and discovering a
missing symbol costs a CI round trip.

Three notes on the conversion:

- Material Symbols ship with the viewBox `0 -960 960 960`, whose negative Y
  origin `VectorDrawable` cannot express. Each path is wrapped in a group
  translated by `+960`, which maps it back into `0..960`.
- They carry **no `android:tint`**. `?attr/colorControlNormal` is an AppCompat
  attribute and this is a pure-Compose app with no AppCompat theme, so aapt
  cannot resolve it. Compose's `Icon` and the canvas apply their own tint.
- They are **committed, not downloaded at runtime**. Constraint C3 says the app
  performs no network I/O after the model download, and fetching an icon at
  first launch would break that guarantee for the sake of two kilobytes.

Regenerate with `tools/fetch-icons.py` rather than hand-editing; the files
carry a header saying so.

## llama.cpp

- **Source:** https://github.com/ggml-org/llama.cpp
- **Licence:** MIT
- **Where:** `third_party/llama.cpp`, a git submodule pinned at `153d324`

Pinned rather than tracked, because GBNF behaviour and the GGUF format both
change upstream and an unpinned dependency turns a reproducible experiment into
a moving target. The pin has already earned itself: the pinned commit is the one
that replaced `llama_model_params.use_mmap` with a `load_mode` enum.

## Model weights

Not vendored. Models are downloaded on first run from the URLs in
`app/src/main/assets/models.json`, each verified against a SHA-256 read from the
file's own HuggingFace LFS pointer.

| Model | Repository | Licence |
|---|---|---|
| Gemma 3 1B Instruct QAT | `ggml-org/gemma-3-1b-it-qat-GGUF` | Gemma Terms of Use |
| Qwen2.5 1.5B Instruct | `Qwen/Qwen2.5-1.5B-Instruct-GGUF` | Apache 2.0 |
| Qwen2.5 0.5B Instruct | `Qwen/Qwen2.5-0.5B-Instruct-GGUF` | Apache 2.0 |

Google's own Gemma GGUF repositories are gated behind an accepted licence and a
bearer token, which an app with no account cannot satisfy, so the ungated
`ggml-org` mirror is used. Anyone redistributing a build with weights included
is subject to the model licence, not this repository's.

## The launcher icon

Not third-party. `ic_launcher_foreground.xml` is drawn for this project — a grid
with one filled cell and an edge leaving it — so the repository contains no
binary image assets at all.
