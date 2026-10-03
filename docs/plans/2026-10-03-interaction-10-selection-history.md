# Interaction 10: selection algebra, inspectors, filters, history and replay — phase plan

Mote `bd-01M1Z9B4A5WWQVG4SYJX7MMD3B` (unblocked by 09). Copy this file to
`docs/plans/2026-10-03-interaction-10-selection-history.md` as the first execution step. Work on
branch `interaction-10` in `.claude/worktrees/interaction-10`, one commit per slice, merged to main
after each slice's gates; fresh-context review after slices 1–3 and after slice 6.

## Context

Interaction state today holds one selection, viewports and pending membership requests, all
changed through a pure reducer (`InteractionState.reduce`) and owned by an `InteractionController`.
Nothing names a selection, combines selections, records history, saves or replays state, or
inspects what a selection means (targets vs aggregates vs exact members), and there is no explicit
filter/recompute command. Interaction 10 adds these as analytical extensions without changing the
baseline: every new state change goes through the reducer, keeps key-space and source-revision
meaning, and never replays host effects (following links, asking a membership resolver).

Acceptance (mote): set laws within compatible key spaces, incompatible operands rejected;
inspectors distinguish targets, groups and exact members; filters report input/statistical changes
separately from emphasis; saved traces restore keys and viewport and reject incompatible
schema/codec/revision with typed errors; documented undo/redo boundaries with no host-effect replay;
workflows consistent on SVG and Canvas, including partial aggregate selection and data updates.

**Foundations already present** (map at `e891562`):
- `Selection(entities, targets)`, `SelectionOperation` (Replace/Add/Subtract/Toggle/Clear),
  `select` in `InteractionState.reduce` (`modules/interaction/.../InteractionState.scala:403-414`);
  `Projected` input updates state silently; `validateStamp` rejects stale/out-of-order input.
- `KeySpace.readEntity(token)` with typed `KeySpaceMismatch`/`CodecMismatch`/`NonCanonicalKey`;
  `EntityKey.token` public; `KeyCodec(name, version)`; `DataRevision`/`PlanRevision` values
  (core `InteractionIdentity.scala`).
- `PanelViewport`, `SetViewport`, `PanelWindow`, `DataWindowNavigator`, `SvgWidget.navigate`.
- `MemberCoverage`, `EmphasisRule`, `SelectionProjection`, `WidgetLink` (one shared key set).
- Host effects: links follow only on Pointer/Keyboard `Activated`; the resolver runs only on
  `MembershipRequested`; `RequestMembers` refuses Projected input.
- `InteractionStateSuite` set-law tests; `modules/laws` `Law`/`LawSuite` framework.
- No JSON library in the build; core and interaction are cross-built JVM/Scala.js.

## Slices

### 1. Selection algebra and named selections (shared interaction)
- `Selection.union/intersect/diff`, each returning `Either[StateError, Selection]`: operands must
  use compatible key spaces (keys of one space instance; targets of the current domain); otherwise
  `StateError.IncompatibleSelections`. `SelectionOperation.Intersect`.
- Named selections in `InteractionState` (`named: Map[SelectionName, Selection]`, validated names).
  Actions `SaveSelection(name)`, `RecallSelection(name, operation)`,
  `CombineSelections(left, right, op, into)` (union/intersect/diff), `DeleteSelection(name)`; event
  `NamedSelectionsChanged`; errors `UnknownSelection`. `replaceDomain` reconciles named selections
  by the same `MissingEntityPolicy` and reports what each lost.
- Laws (commutativity, associativity, distributivity, identity, absorption,
  difference/intersection identities, De Morgan within the domain's universe), checked
  exhaustively over every pair and triple of selections of a small universe, with incompatible
  operands always refused. Changed in execution: they live in the interaction module's
  `SelectionAlgebraSuite`, because `intaglio-laws` depends only on core and adding the
  interaction artifact to its published dependencies is not wanted.

### 2. Versioned snapshots and replay codec (shared interaction)
- `InteractionSnapshot`: schema version, per plan its id, `PlanRevision` and `DataRevision`, the key
  space namespace and codec name/version, the selection (entity tokens + target addresses), named
  selections, panel viewports, selection mode. No hover, focus, gestures, pending requests or
  callbacks.
- A hand-written, strict JSON writer/reader in the interaction module (no new dependency, identical
  output on JVM and Scala.js). Target addresses are `(plan, planRevision, scope, ordinal)`, resolved
  only through the domain (new `InteractionDomain.resolveTarget`).
- `SnapshotError` (typed): `UnsupportedSchema`, `Malformed(reason)`, `KeySpaceMismatch`,
  `CodecMismatch`, `StaleDataRevision(plan, saved, current)`, `UnknownPlan`, `UnknownTarget`,
  `UnknownEntity`.
- `RestoreSnapshot(snapshot)` action: applies selection, named selections, viewports and mode as
  one transition; it is never an `Activate` or `RequestMembers`, so no link or resolver can fire.

### 3. Undo/redo history (shared interaction)
- `InteractionHistory`: undo/redo stacks of snapshot values kept beside the controller, with a
  configurable depth.
- Boundaries (documented):
  - An entry is recorded before each committed change of selection, named selections, viewport or
    selection mode made by this plot's reader (Pointer/Keyboard) or by an explicit application
    command (Programmatic).
  - Projected input (linked views, `setSelection`) makes no entry. Undo restores the state captured
    before this plot's own change, as a Programmatic `RestoreSnapshot`, so a `WidgetLink` carries it
    to the group.
  - New input clears redo; a domain replacement (new data) clears both stacks.
  - Hover, focus, gestures and membership requests are never history.
- Tests: traces with interleaved projected input and stale input, depth limits, no effect events
  (`Activated`, `MembershipRequested`) ever produced by undo/redo/restore.

### 4. Inspector (shared model + browser panel)
- `InspectorModel.of(state)`: sections for selected visual targets (by plot and part), aggregates
  with `MemberCoverage` (Known k/n, Unknown capability, Stale), exact members (first N keys plus
  count), unresolved keys, and named selections with sizes. Pure and shared, so JavaFX can use it
  later.
- Browser `InspectorPanel.mount(container, widgets)`: a text panel (semantic HTML table, live
  updates via `subscribe`) for one widget or a `WidgetLink` group; accessible and keyboard
  readable; no canvas dependence.

### 5. Explicit filter and recompute (shared + browser)
- `FilterCommand`: an application-level object holding the plot, the key function and a revision
  scheme. `filter(keep: Set[EntityKey])` compiles the restricted plot with new revisions and returns
  a `FilterResult` with an input change (rows kept/removed), a statistical change (per layer, target
  counts and member totals before vs after), and the new plan. Selection reconciliation stays
  `Reconciled`. Emphasis is untouched.
- Browser: `widget.update(view)` applies it; the inspector shows the input/statistical report
  separately.

### 6. Browser workflows and evidence (SVG and Canvas)
- `HistoryFixture` + `tools/browser/history.html` + `tools/check-history-browser.cjs`:
  - name two selections from a lasso and a bin's members (partial aggregate);
  - intersect, union and diff them, checked against JS set oracles;
  - undo/redo across linked plots, with no echo and no link/resolver effects;
  - serialize, reload the page, restore keys and viewport;
  - tamper with a snapshot's schema, codec and revision, and see each typed refusal;
  - filter to the selection, check the input/statistical report against an oracle, then
    reconcile.
- Run per renderer in `tools/check-browser-suites.py` with paired-trace equality between SVG and
  Canvas; the external consumer gate exercises save/restore.

## Gates (every slice)
`SBT_OPTS=-Xmx6g sbt scalafmtCheckAll testAll`; `tools/check-compatibility.sh` (core changes
should be none or exact additive records; interaction/browser have no baseline);
`tools/check-links.sh`; browser suites (slices 4–6) with the Playwright `NODE_PATH` that matches the
installed Chromium; consumer gate after slice 6. CHANGELOG and docs (`docs/browser-widget.md`,
`modules/interaction/README.md`, a new `docs/selection-history.md`, capability matrix analytical
rows) updated per slice.

## Critical files
- Shared: `modules/interaction/shared/src/main/scala/intaglio/interaction/InteractionState.scala`,
  `InteractionController.scala`, `LinkedViews.scala`; new `SelectionAlgebra.scala`,
  `InteractionSnapshot.scala` (with the JSON codec), `InteractionHistory.scala`,
  `InspectorModel.scala`, `FilterCommand.scala`; laws in
  `modules/laws/shared/src/main/scala/intaglio/laws/`.
- Browser: `modules/browser/js/src/main/scala/intaglio/browser/SvgWidget.scala` (history, restore,
  snapshot API), `WidgetLink.scala`, new `InspectorPanel.scala`; fixture
  `modules/browser-fixture/.../HistoryFixture.scala`; `tools/check-history-browser.cjs`,
  `tools/check-browser-suites.py`.

## Verification
1. Unit and law suites on JVM and Scala.js after each slice (`interactionJVM/test interactionJS/test
   lawsJVM/test lawsJS/test`), then `testAll`.
2. Browser: `sbt browserFixture/fastLinkJS`, history check on both renderers, full suite runner
   (`NODE_PATH=/Users/bbuchsbaum/node_modules`), ownership audit before/after.
3. Close the mote with exact-SHA receipts; pending checks stay pending.
