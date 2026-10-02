# Interaction 04: accessible SVG widget — phase plan

Mote: `bd-01M1Z9B1VT19HE55FNS365YVRJ` (epic `bd-01M1Z987V50254M59P9MNGP9FH`). Design source:
[docs/design/interaction.md](../design/interaction.md). Prerequisites Interaction 01–03 are closed.

## Starting point (main `b020199`)

Shared state (`InteractionState.reduce`, `InteractionController`), picking (`PickingPlan`,
`NavigationPlan`, `TargetOutline`, `PickViewport`), named-scene interaction and a JavaFX host exist.
Missing for this child: a content model for tooltips and activation, typed plot-part targets, a
coverage matrix, per-widget SVG id isolation, any Scala.js browser code, and real-browser evidence.

## Decisions taken by default

- **No new library dependency.** The browser module uses hand-written `js.Dynamic` facades, as the
  Canvas backend already does, rather than adding scalajs-dom.
- **Portable plan, host effects outside.** Tooltip content and links are values in the shared
  module (escaped text or a small structured document; a URL value, never a script string). The
  browser host performs effects; the state transition function stays pure.
- **Real-browser evidence via the existing Playwright Chromium**, invoked like
  `tools/check-picking-browser.cjs` (headless, never the system Chrome profile, ownership audited
  before and after). No `package.json` is added to the repository.

## Slices, each verified before the next

1. **Content and payload model (shared).** `TargetContent` (plain or structured, escaped by
   construction), `TargetLink` (checked URL, target policy), per-binding content functions on
   `LayerBinding`, carried on `TargetInfo`/named targets. Gate: JVM+JS suites, laws where an
   invariant exists, additive compatibility records.
2. **Plot-part targets and coverage.** Typed targets for legend keys (scale value), colorbar
   (value at position), facet strips (facet value), axes, titles, labels and annotations in
   `InteractionPlan`; HLine/VLine lowering; a coverage matrix generated from the lowering table and
   pinned by a test that fails when a built-in geom/stat lacks an entry. Gate: core+interaction
   JVM/JS, docs.
3. **SVG id isolation.** A per-document id prefix for clip, pattern, title and description ids so
   two inline SVGs never collide; default output byte-identical. Gate: svg JVM/JS, pinned digests.
4. **Browser host (`interaction` JS).** Mount over an inline SVG: normalized pointer/keyboard
   input through `PickViewport`, an SVG overlay layer for hover/focus/selection drawn from
   `TargetOutline`, tooltips (pointer-relative, mark-anchored, fixed; delay; edge clamping; focus
   access), activation/link effects, inverse emphasis, `prefers-reduced-motion`, one tab stop with
   roving focus, a navigable text/table companion, and an owned controller with `update`,
   `subscribe`, `dispose` that removes listeners, observers, overlays, indexes and animation frames.
5. **Real-browser evidence and example.** A linked Scala.js fixture page and a Playwright script
   covering pointer, keyboard, tooltip placement at edges, two isolated widgets, repeated
   mount/update/dispose without retained listeners, and screenshots at realistic size; a runnable
   ordinary-plot example and guide.

Each slice lands as its own commit(s) with tests that fail without it. Full `testAll`, docs and
compatibility gates run at the end of the phase; nothing is pushed without a request.

## Out of scope here

Region gestures and data-window navigation (Interaction 05), linked views (06), standalone HTML
packaging and toolbar (07), Canvas parity (08).
