# intaglio-interaction

`intaglio-interaction` manages plot selection, focus, hover, input events, and
geometry queries for Scala applications on the JVM and Scala.js. Applications
can inspect an ordinary compiled plot and receive typed observation keys.

**Status:** Pre-release, source-only development. The portable state runtime
and spatially indexed picking are implemented; browser widgets and native input
adapters remain in development. APIs and module boundaries may change.

## Select an observation

From this repository, use `interactionJVM` or `interactionJS`. This example
compiles an ordinary point plot and selects observation 2 programmatically:

```scala
import intaglio.*
import intaglio.interaction.*

val result = for
  keys <- KeySpace("observations", KeyCodec.integer)
  plot <- Plot(Vector(1, 2, 3)).addLayer(Layer.point[Int](_.toDouble, _.toDouble))
  source <- DataRevision("data-1")
  revision <- PlanRevision("view-1")
  plan <- InteractionCompiler.compile(plot, keys, source,
    SemanticId.unsafe("example"), revision)(identity)
  domain <- InteractionDomain(Vector(plan), revision)
  initial <- InteractionState.initial(domain)
  entity <- keys.entity(2)
  changed <- InteractionState.reduce(initial,
    InputStamp(revision, SemanticId.unsafe("application"), 0, InputCause.Programmatic),
    InteractionAction.Select(Selection(Set(entity)), SelectionOperation.Replace))
yield changed.state.selection.entities.map(_.value)

assert(result == Right(Set(2)))
```

The returned transition contains the new state and application events.
`InteractionController` owns that state for a synchronous host event loop and
delivers `EventRecord[A]` values to subscribers. Call each subscription's
`cancel()` when finished, or `dispose()` to release the controller's state and
all listeners. Disposed controllers reject further updates. Subscriber failures
are reported in `DispatchResult.failures`; other subscribers still receive the
event. State changes during callback delivery are rejected: schedule the next
input after the current delivery.

## Query plot geometry

Compile a `PickingPlan` with `Picking.compile(plan, context)` using the same
render context as the displayed plot, or use `Picking.composition(composed)`.
`hits` returns every target within a device-pixel radius; `nearest` returns the
closest target within its cutoff. Results prefer smaller distances, then the
later-drawn target. Multiple painted parts of a mark produce one logical hit.

Use `PickArea.rectangle` or `PickArea.lasso` with `select` to query regions.
`AreaRule.CenterInside` tests the center of the uncut target bounds, provided
that its clips expose the center. `FullyContained` requires every visible part
inside the region; `Intersecting` requires at least one part to intersect it.
Boundaries are included. Lasso fill uses even-odd winding; compound plot
polygons use nonzero winding, matching the renderers.

Queries account for group rotations, nested clipping, point-batch indices,
fill, stroke, line caps and joins, and rounded corners. Transparent paint is
excluded unless `PickPolicy.includeTransparent` is enabled; absent paint
remains absent. Text uses measured, rotated bounds supplied by `TextMetrics`.
Images use their rectangle and overall opacity, without inspecting pixel alpha.

`DashPicking.Continuous`, the default, treats dashed strokes as continuous
interaction corridors. `Painted` respects dash gaps on linear outlines and
reports `PickingError.Unsupported` for curved dashed outlines. Painted linear
paths longer than 100,000 device pixels also report unsupported capability to
bound dash expansion. The default miter limit is 4; hosts must configure drawing
and picking with the same limit (Canvas and Java2D defaults can differ).

For a centered, aspect-preserving browser layout, `PickViewport.fit` takes the
device-scene dimensions and the CSS content rectangle. `toDevice` maps client
coordinates, returning no point in letterbox space; `tolerance` converts a CSS
pixel radius with the same scale. Scene dimensions already include device scale,
so hosts must not multiply by device pixel ratio again. Rebuild this mapping
after layout changes. Borders and padding are excluded from the supplied content
box; nonuniform CSS stretching, skew, and rotation require a different mapping.

Compiling a `PickingPlan` builds target geometry and a uniform spatial grid
over target bounds once; point and area queries evaluate exact geometry only on
the grid's conservative candidate set, and dense-plan tests assert the results
are identical to a full scan. Query cost therefore tracks local mark density
rather than total mark count (measured medians on one machine are in
[`performance/timings/v1.tsv`](../../performance/timings/v1.tsv)), but no
formal capacity or latency guarantee is established. Incremental index updates
remain separate work.

## State contracts

- Select displayed targets and observation entities independently. Selecting
  an aggregate target does not imply selecting all its source observations.
- Replace, add, subtract, toggle, and clear selections. Single mode rejects
  results with more than one selected item; disabled mode requires an empty
  selection. Mode changes never choose a surviving observation arbitrarily.
- Hover and focus remain independent. Viewport changes preserve selection.
  Source keys include rows omitted from visible geometry, such as non-finite
  points.
- Each input carries a domain revision and a monotonically increasing sequence
  number for its origin. Duplicate, out-of-order, and stale inputs fail without
  changing state. Projected input changes state without emitting application
  events, allowing hosts to coordinate linked views without echoing updates.
- Replacing the domain requires a new revision and an explicit missing-entity
  policy. Drop removes missing keys; Preserve retains them as unresolved.
  Reconciliation reports removed and unresolved entities and obsolete targets.
  Replacement clears hover, focus, active gestures, and viewport overrides.

Use the same `KeySpace` instance when linking observations. Matching namespace
labels alone do not authorize a join. Compilation retains source keys and
compact batch target tables, without retaining source rows under lean options.
Category links retain their own key types through activation subscriptions.

This module depends on core and has no browser or reactive-framework dependency.
Host-specific gesture geometry, viewport scale conversion, accessible UI,
appearance precedence, exact aggregate resolution, and history persistence
belong to the remaining [interaction epic](../../docs/design/interaction.md).
`PanelViewport` validates numeric bounds; a host still needs to validate the
addressed panel and its coordinate capabilities.

## Verify the source

```sh
sbt 'interactionJVM/test' 'interactionJS/test'
```

The shared tests exercise explicit selection traces, set-operation laws, typed
activation and category links, stale and duplicate input, cancellation,
reconciliation, callback failure, and disposal. Picking checks include analytic
and sampled distance oracles, clipped/rotated regions, winding, caps/joins,
dashes, batch identity, and display-coordinate scaling. A JVM suite compares
stroke containment against Java2D's independently constructed `BasicStroke`.

`tools/check-picking-browser.cjs` checks the dashed-seam regression against
Chromium SVG and Canvas and validates SVG screen-coordinate transforms at four
device pixel ratios. It requires Playwright and its installed Chromium; run it
after the repository's browser ownership audit. An explicit test executable can
be provided through `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`. The script closes its
own browser and contexts. These primitive fixtures do not establish widget
accessibility, lifecycle, or complete renderer parity.
