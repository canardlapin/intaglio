# Named selections, snapshots and history

Interactive plots keep their state in one `InteractionState`, changed only by the pure reducer
`InteractionState.reduce`. Three analytical additions build on it without changing how a single
selection works.

## Named selections and selection algebra

A selection can be saved by name and combined with others:

| Action | Effect |
| --- | --- |
| `SaveSelection(name)` | save the current selection under `name` |
| `RecallSelection(name, operation)` | apply a saved selection to the current one: replace, add, subtract, toggle, intersect or clear |
| `CombineSelections(left, right, how, into)` | save `left ∪ right`, `left ∩ right` or `left − right` under `into`; the current selection is unchanged |
| `DeleteSelection(name)` | forget a saved selection |

`SelectionAlgebra.union`, `intersect` and `diff` are the same operations as plain functions.
Observation keys and visual targets combine separately, as they are selected separately, and the
usual set laws hold (checked exhaustively in `SelectionAlgebraSuite`). Selections combine only when
they mean the same things: keys of key-space instances the plot uses (keys of two such spaces stay
distinct even when their values are equal) and targets the plot draws. Anything else is refused with
`StateError.IncompatibleSelections` or `UnknownTarget`.

A saved selection holds only observations the data has: keys the current selection keeps across a
data replacement (`MissingEntityPolicy.Preserve`, reported as unresolved) are not saved with it.

When the data is replaced, saved selections keep only the observations and targets the new data
still has (`MissingEntityPolicy.Preserve` applies to the current selection only), and a
`NamedSelectionsChanged` event reports the result.

## Snapshots

`InteractionSnapshot.capture(state)` records the durable state: the selection, saved selections,
panel viewports and selection mode, together with each plan's id, plan revision and data revision
and the key codecs of the saved keys. Hover, focus, gestures, pending membership requests and
callbacks are never saved.

`snapshot.toJson` and `InteractionSnapshot.fromJson` use a strict JSON format with no external
dependency; the JVM and Scala.js write the same text. A snapshot is restored in two steps:

1. `InteractionSnapshot.resolve(snapshot, domain)` checks it against the plot shown now and returns a
   typed `SnapshotError` when it cannot mean the same thing there: an unsupported schema, malformed
   text (including an unknown field, a repeated name or panel, or a target whose plan is not listed),
   an unknown plan, a different plan or data revision, a key space or key codec the plot does not
   use, a namespace that two of the plot's key spaces share (`AmbiguousKeySpace`), or an observation
   or target the plot does not have. Observations the saved selection held as unresolved are restored
   as unresolved.
2. Dispatching `InteractionAction.RestoreSnapshot(resolved)` applies it as one change. A restore never
   activates a target (so no link is followed) and never asks a membership resolver; it supersedes
   any pending member request, like any other selection change.

## Undo and redo

`InteractionHistory` keeps undo and redo stacks of durable state; `HistoryController` wraps an
`InteractionController` and records through it. The boundaries:

- An entry is the durable state just before a committed change made by this plot's reader
  (pointer or keyboard) or by an explicit application command (programmatic input).
- Projected input, from a linked view or `setSelection`, records nothing. Undo restores the state
  before this plot's own change; a linked group then carries that change to the other plots.
- A new recorded change clears redo. Replacing the data clears both stacks.
- Hover, focus, gestures and membership requests are never history.
- Undo and redo dispatch `RestoreSnapshot`, so they never follow a link or ask a resolver, and they
  are not recorded themselves. Each restores the whole durable state it recorded: a redo after a
  linked projection replaces the projected selection with the redone one, which the link then
  carries to the other plots.
- Unresolved observations (kept across a data replacement) are part of the recorded state, so
  undoing to a selection that held them restores them as unresolved.
- Undo restores this plot's whole recorded selection. Observations another linked plot added after
  that change are therefore removed from the group as well.
- Asking for a deferred aggregate's members records nothing; the reply, when it is applied, is the
  recorded change (an application-side event), and like any recorded change it clears redo.
- In the browser widget a continuous navigation (pan, pinch, wheel or key zoom) is one entry, not one
  per animation frame.

The history depth is bounded (100 by default).

## Inspecting and filtering

`InspectorModel.of(state, sample)` describes what a selection means: observations (with a sample),
selected marks and aggregates, each aggregate's `MemberCoverage` and its exact members when known,
uncountable aggregates, unresolved keys and saved selections. It is pure and shared; the browser
`InspectorPanel` renders it as text.

`FilterCommand(rows, space, key, compile).keep(current, keys, dataRevision, planRevision)` is an
explicit filter: it keeps the rows whose keys are given, recompiles the plot with new revisions and
returns a `FilterResult` with the input change (rows before and after, observations removed) and,
per target group, the target counts and member totals before and after. It selects and emphasizes
nothing; applying the new plan (for example with `SvgWidget.update`) reconciles the selection as any
data update does.

## In the browser

`SvgWidget` exposes all of this (see [browser-widget.md](browser-widget.md)), and
`tools/check-history-browser.cjs` exercises it on SVG and Canvas.

