# Interactive graphics: feature baseline and implementation proposal

Status: Implementation in progress; the full feature baseline remains a proposal.
Date: 2026-09-07
Source inspected: `38e8c62b5b3fb74d0a32664a168e95e9f708bd49`.

Intaglio should let a reader inspect, select, and navigate a plot, and let an
application use those interactions to coordinate other views. The minimum
product target is the interaction repertoire documented in the
[ggiraph book](https://www.ardata.fr/ggiraph-book/). The longer-term target adds
typed composition, explicit statistical membership, consistent SVG and Canvas
behavior, accessible controls, and reproducible interaction state.

This proposal defines that target and a dependency-ordered implementation plan.
It does not establish an accepted API, a release date, or feature availability.

## The reference baseline

The book documents tooltips, nearest-element hover, click actions, interactive
scales and facet strips, and shared hover across composed plots. Its extensions
also cover guides and theme elements. See
[Using ggiraph](https://www.ardata.fr/ggiraph-book/intro.html).

Customization includes hover emphasis and inverse emphasis, linked legend
hover, tooltip styling and placement, responsive sizing, pan/zoom, and a
configurable toolbar with rectangle zoom, reset, PNG download, and fullscreen.
See [Customizing girafe animations](https://www.ardata.fr/ggiraph-book/customize.html).

Selection includes click and lasso, deselection, initial and programmatic
selection, and reactive hover/selection values for panel, legend, and theme
elements. Linked legend selection and externally controlled CSS classes are
also documented. See
[Shiny and reactive values](https://www.ardata.fr/ggiraph-book/shiny.html).

The following are Intaglio requirements derived from that reference. Framework
integration means equivalent capabilities for Scala applications; an R/Shiny
adapter is not a prerequisite.

| Area | Required Intaglio behavior | Observable acceptance example |
| --- | --- | --- |
| Inspection | Plain and structured rich tooltips; direct and nearest hover; pointer-relative, mark-anchored, and fixed placement; configurable appearance and delay | Inspect a dense scatter plot, a line, a histogram bin, and a facet strip without ambiguous target changes |
| Emphasis | Hovered, selected, focused, and inactive appearances; inverse emphasis; configurable transitions; externally assigned styles | Hover a legend key and emphasize its marks; remove the emphasis and recover the original appearance |
| Actions | Typed activation events, declarative links, and host callbacks | Activate a mark with a pointer or keyboard and receive the same target and payload |
| Selection | Disabled, single, and multiple modes; toggle, replace, add, subtract, clear, and lasso; initial and externally supplied selection | Accumulate a selection, subtract a lasso region, then restore the previous selection from the application |
| Plot parts | Addressable marks, legend keys, colorbar components, facet strips, axes, titles, labels, and authored annotations | Select a facet strip or legend key and identify its typed facet value or scale value |
| Navigation | Pan, wheel/pinch zoom, rectangle zoom, bounds, reset, explicit gesture activation, and toolbar controls | Zoom a plot, pan to a neighbor, and reset without changing the statistic's input |
| Composition | Shared hover and selection across panels and plots, with explicit linking rules | Hover one observation in two differently arranged plots and emphasize both representations |
| Embedding | Standalone HTML, application mounting, responsive sizing, configurable toolbar, fullscreen, PNG export, and programmatic event/state access | Open a saved HTML artifact without a server; mount two independent widgets in an application |

Every built-in geom and statistic needs a coverage entry describing its logical
target and picking behavior. A point-only demonstration does not establish
baseline completion. Newly added extension geoms must declare their picking
capability or return an explicit unsupported-capability result.

## What exists today

Intaglio already provides several useful foundations:

- [Grob annotations](../accessibility.md) carry descriptions, CSS classes, and
  data attributes into SVG. Other backends draw the annotated child.
- [Coordinate zoom](../date-time-and-zoom.md) changes a viewport after
  statistics and scale training. It does not supply gesture handling.
- [Provenance policies](../adr/0007-provenance-is-a-compiler-policy.md) retain
  counts, representatives, source indices, or full values at explicit costs.
- [Canvas](../../modules/canvas/README.md) and
  [JavaFX](../../modules/javafx/README.md) render into live application surfaces.
  Canvas caches raster resources across draws.
- [Plot composition](../composition.md) and semantic metadata preserve
  information needed to identify panels and logical layers.

The working tree now includes checked entity/link identities, independent-layer
bindings, explicit target lowering, compact batch routing, and composition-aware
interaction plans. The [interaction module](../../modules/interaction/README.md)
adds a pure state reducer and a synchronous controller with typed subscriptions.
Its shared tests cover selection operations, revisions, duplicate deliveries,
reconciliation, and controller disposal on JVM and Scala.js.

Picking, browser gesture handling, widget lifecycle, visible interaction styling,
and native input integration remain outstanding. Notebook output is still an SVG
MIME bundle. Generated static datum IDs still use output position; persistent
interaction identity comes from explicit key bindings. Statistical membership
capabilities are checked, but aggregate-member selection is not implemented.

## Design decisions to settle before implementation

### Attach behaviors to ordinary plots

Keep one family of geoms, scales, guides, and facets. Add interaction bindings
and composable behaviors to those objects through an optional API. A caller
should describe its data keys and desired behavior once; it should not replace
every geom with an interactive counterpart.

Proposed compilation shape, using conceptual names rather than runnable Scala:

```text
ordinary plot + typed bindings + behavior specification
    -> checked interactive compilation
    -> ordinary Scene + InteractionPlan

InteractionPlan + current state + normalized input
    -> next state + typed application events + visual updates
```

The host supplies effects such as navigation or loading new data. The portable
plan contains values and action identifiers; executable JavaScript strings
are not part of its contract. Tooltip content is escaped text or a structured
document. Host-specific rich content belongs in an explicit adapter capability.

### Separate visual targets from data identity

Three identities have different jobs:

| Identity | Meaning | Lifetime |
| --- | --- | --- |
| Visual target | One logical mark or plot component, even if drawn with several primitives | A particular compiled plan and revision |
| Entity key | A caller-supplied observation identity in a named dataset/key space | Across updates when the caller preserves that identity |
| Link key | A shared category or grouping value, such as condition or subject | Across the explicitly linked views |

Repeated link keys are expected. Duplicate entity keys within a declared unique
key space are errors. Two views link through a shared key space or an explicit
mapping; equal display labels never create an implicit join. Generic key types
remain attached to bindings and event subscriptions. Serialization additionally
requires a named, versioned key codec so those distinctions survive transport.

Carry target references through statistical lowering, grob lowering, device
transforms, facets, and composition. Point batches need compact per-index
references, without allocating a wrapper or retained source row for every mark.
An already compiled static scene may lack this information: attaching behaviors
then requires explicit bindings, or recompilation from the plot program.

### Make aggregate selection explicit

A histogram bin, fitted curve, contour, or summary interval is not one source
observation. Each target declares whether an action selects the displayed
target, a group key, or its contributing observations.

Exact member selection requires complete membership and the matching source
revision. Compile-time capability checks where possible, followed by checked
plan validation, must reject requests that retain only a count, representative,
or incomplete member list. A representative must never be reported as the
complete selected set. Custom statistics need a contract for source identity;
row equality alone cannot certify that contract.

For example, selecting a bin with 27 members can select the bin itself with a
small metadata payload. Selecting its 27 observations requires their keys or
an explicit membership resolver. A resolver reports pending, unavailable, and
failed results and rejects replies for obsolete revisions. It must not appear
to have completed while returning a partial selection.

Linked aggregates report selected-member count and total count. A view chooses
whether any member, every member, or a stated fraction triggers emphasis.
Filtering and recomputing a statistic is a separate application action from
selecting or emphasizing its existing result.

### Give interaction state one owner

Use a pure, deterministic state transition function for hover, focus,
selections, active gestures, and viewport state. Normalize pointer, keyboard,
touch, and programmatic input before applying those transitions. Applications
can read state, replace selections, subscribe to typed changes, and link views
without inspecting DOM attributes.

Each update carries plan revision and origin. Linked views share selection
state or apply an explicit projection; receiving a projected state must not
emit it as a fresh user action. Ignore stale pointer and membership results.
On data replacement, reconcile by entity key and report removed or unresolved
keys. Persistence across missing data is an explicit policy.

Separate selection membership from currently visible membership: zooming or
hiding a layer does not silently clear the selection. Hover, focus, selection,
and externally supplied styles need documented precedence. Focus must remain
visible when a selected mark is also hovered.

### Pick the geometry that was drawn

Build picking geometry from the resolved device scene, with the same clipping,
transforms, panel boundaries, draw order, and batch indices as rendering. Map
browser coordinates through CSS sizing and device scale exactly once.

Specify fill versus stroke picking, nearest-distance limits in display pixels,
overlap tie-breaking, and point versus area selection. Text and images use
documented bounds by default; pixel-alpha picking is a separate capability.
Transparent marks are selectable only under an explicit picking policy.
Lasso and rectangle selection declare center-inside, fully-contained, or
intersecting geometry semantics, including boundary cases.

SVG DOM targeting can accelerate candidate discovery. A shared picking
contract remains authoritative so switching to Canvas preserves selected keys
and event meaning. Indexed queries must agree with an independent exhaustive
geometry oracle on small adversarial scenes.

### Distinguish magnification, viewport changes, and filtering

Offer whole-scene magnification for reference parity and data-window navigation
for analytical use. Magnification transforms labels and marks together.
Data-window navigation updates panel ranges and axes, preserves trained scales
and statistics, and can keep text and mark sizes fixed in display units.

The latter builds on `Coord.zoom` semantics, but requires an execution route
that reuses resolved statistical results; repeatedly invoking the whole
compiler is not an incremental navigation implementation. Numeric and temporal
windows retain their scale kinds. Inverse mappings for transformed coordinates
are explicit capabilities. Linked axes require compatible domains or a checked
conversion. Free facets remain independent unless the caller links them.

Gesture arbitration must distinguish panning, brushing, page scrolling, and
activation. Define pointer capture, cancellation, and Escape behavior. Provide
visible controls for changing modes and resetting the view.

### Include keyboard access and lifecycle in the first widget

Hover information must also be available through focus or an inspector.
Provide visible focus, keyboard activation, selection controls, and a navigable
text/table representation for dense plots. Avoid putting every one of 100,000
points into the document's tab order. Canvas needs an accessible DOM companion.
Respect reduced-motion preferences and offer touch equivalents.

Mounting returns an owned controller with update, subscribe, and dispose
operations. Disposal removes listeners, observers, subscriptions, overlays,
indexes, and pending animation frames. Repeated mount/unmount must not retain
plots or callbacks. Multiple widget instances must not share accidental global
state or duplicate document IDs.

## Module boundaries

Names here are provisional:

- **Core:** minimal portable target metadata and compiler hooks needed to
  preserve it. No browser, reactive-framework, or event-loop dependencies.
- **Interaction (JVM and Scala.js):** bindings, checked plans, keys, events,
  state transitions, selection operations, and picking contracts. Depends on
  core; core does not depend on it.
- **Browser interaction (Scala.js):** SVG/Canvas mounting, normalized input,
  tooltip and accessibility UI, toolbar, lifecycle, and standalone packaging.
- **Optional integrations:** framework adapters such as Laminar, notebook
  interactive display, and later JavaFX input handling. They consume the same
  state/event contract.

The static renderers remain usable independently. An interactive export names
whether it captures the original plot or current viewport/selection, and uses
the corresponding scene. Static PDF, SVG, and raster output must remain
available without initializing an interaction runtime.

## Delivery sequence and completion gates

| Slice | Depends on | Deliverable and gate |
| --- | --- | --- |
| 1. Identity and state | None | Typed keys, target references, retention validation, state transitions, and laws. Prove stable entity identity after reorder/filter, namespace isolation, explicit duplicate handling, and batch preservation on JVM and Scala.js |
| 2. Usable SVG widget | 1 | Pointer/focus tooltips, hover, activation, single/multiple selection, programmatic control, and disposal. Demonstrate ordinary plot authoring plus mark, legend, facet, and annotation targets in a real browser |
| 3. Navigation and linked views | 2 | Lasso/rectangle selection, pan/zoom/reset, linked plots/guides, touch, and event-origin handling. Prove transformed/clipped picking, preserved statistic results, and absence of feedback loops |
| 4. Baseline delivery and Canvas parity | 3 | Complete built-in target coverage, rich presentation, toolbar/fullscreen/export, standalone HTML, framework-neutral API, and Canvas accessibility/picking. Run matching interaction traces on SVG and Canvas and publish the remaining capability matrix |
| 5. Analytical extensions | 4, membership contract from 1 | Complete aggregate-to-source linking, selection algebra across named selections, coordinated filters, inspectors, undo/redo, and versioned replay. Reject incomplete membership and stale data; reproduce selected keys and viewport from a saved trace |
| 6. Larger workloads and other hosts | 4; 5 for analytical features | Indexed batch picking, incremental scene updates, measured redraw budgets, and JavaFX interaction. Verify behavior with large datasets and validate each host separately |

The first end-to-end acceptance example should have two scatter plots sharing
observation keys and one histogram. It must support hover across the scatters,
keyboard selection, additive/subtractive region selection, a linked category
legend, and viewport navigation. The histogram first supports explicit bin
selection; the analytical extension adds exact member selection and exposes
partial-selection counts. The same example should exercise SVG and Canvas.

Baseline completion requires passing examples for every baseline row above,
including standalone delivery and application-controlled state. Later slices
extend that baseline; they must not be used to defer a listed baseline feature.

## Evidence required before claiming parity or superiority

Use shared state laws plus independent picking and membership oracles. Cover
coincident points, line segments, polygon boundaries, clipped marks, transformed
axes, free facets, composition, repeated source values, and duplicate labels.
Compare selected keys and logical events across SVG and Canvas. Test keyboard,
touch, resizing, device scale changes, and mount/update/dispose in real browsers.

Keep static rendering conformance and existing compatibility gates passing.
Review the UI at realistic size, including tooltips near container edges and
overlapping selections. Do not infer browser behavior from serialized SVG or
passing JVM tests.

Use at least a 10,000-mark SVG fixture and a 100,000-point Canvas fixture for
performance characterization. Record target hardware, browser, retained memory,
index-build cost, pointer-to-highlight latency, and redraw latency. Set release
budgets from those measured fixtures before accepting the performance slice;
these dataset sizes are test workloads, not advertised capacity guarantees.

Retain source SHA, fixture inputs, event traces, expected keys, screenshots,
and timings with each result. A consumer application must use the exact tested
artifact. Feature parity, scientific membership correctness, accessibility,
performance, and host compatibility are separate claims requiring separate
evidence.
