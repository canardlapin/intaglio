# Canvas widgets

The browser host can paint a plot with native Canvas while keeping the same typed
picking, selection, events, tooltips, links, keyboard navigation and text companion
as its SVG renderer. Select the renderer when mounting:

```scala
SvgWidget.mount(
  container,
  view,
  behavior,
  options = WidgetOptions(renderer = WidgetRenderer.Canvas)
)
```

`SvgWidgetView` and `SvgWidget` retain their names for both renderers. Canvas draws
the compiled scene directly; it does not hide an SVG copy of the marks in the DOM.
SVG overlays draw focus, selection and gesture outlines. The text companion remains
available to keyboard and screen-reader users.

## Appearance

Both renderers accept application-supplied interaction colors and opacity:

```scala
WidgetOptions(
  renderer = WidgetRenderer.Canvas,
  appearance = WidgetAppearance(
    hover = Rgba.unsafe(0, 110, 100),
    selected = Rgba.unsafe(180, 80, 10),
    inactiveOpacity = 0.4,
    transitionMs = 200
  )
)
```

These options style interaction decorations, not the plot's data encodings. The
opacity must be finite and between zero and one; transition duration must be
between zero and 10,000 milliseconds. Invalid values return a typed mount error.
Reduced-motion preferences disable the emphasis transition.

The default appearance installs no inline overrides. Applications can instead
set the widget's CSS properties: `--intaglio-hover`, `--intaglio-selected`,
`--intaglio-focus`, `--intaglio-focus-halo`, `--intaglio-linked`,
`--intaglio-tooltip-background`, `--intaglio-tooltip-text`, `--intaglio-dim` and
`--intaglio-transition`. Canvas marks are pixels; CSS selectors cannot restyle
individual marks. Assign their paint from application state in the plot itself,
for example `.fill(row => applicationColors(row.id))`. Recompile and pass the new
view to `widget.update` when that state changes. The paired baseline checks verify
an externally supplied per-entity color survives hover and emphasis recovery.

## Runtime target styles

Use `widget.setTargetStyles(Map(target.id -> WidgetTargetStyle(fill = Some(color))))`
to replace paint on chosen logical targets. Target IDs come from the typed target
records in subscriptions or the view's navigation targets. Fill, stroke color and
opacity overrides apply to both renderers, including individual points in a batch.
This is a paint update: it preserves selection and the current navigation window
and emits no interaction events. Pass an empty map to recover the original paint.
Unknown target IDs, invalid opacity and raster-cell stroke requests fail before any
paint is changed. Raster cells support fill and opacity; they have no stroke.

Pan and zoom retain runtime styles, and PNG exports include them. An explicit
`widget.update(nextView)` clears runtime styles because visual target IDs belong to
a particular view; apply a new map from the new view's target records as needed.
These typed paint overrides work on Canvas without creating DOM nodes for marks.

## Compositions, resizing and export

`SvgWidgetView.compileComposition(composed, revision, idPrefix)` retains every
child plan's identity and picking geometry. Each child's titles, axes and
annotations stay its own parts: the widget draws the composed scene with those
part names scoped to the child's cell or inset (`composition-cell-1-plot-title`),
so hovering one child's title never reports or outlines a sibling's. A legend
collected to figure level is one shared part. Part events carry the part's value,
which two children can share (two `Annotation(1)` layers); use the target events
for child identity. `view.plans` lists those plans;
`view.singlePlan` returns an option for callers that require one plot. A composed
figure has independent scales, so figure-wide pan and zoom are unavailable.
Faceted plots likewise do not expose a single navigable panel.

Canvas backing dimensions follow the displayed width and device pixel ratio.
Where that would exceed the bounds PNG export also uses (16384 pixels per axis,
16777216 in total), as in fullscreen on a dense display, the bitmap is reduced
to the largest size within them and the browser scales it to the box. Embedded fonts load
before painting; failures reach the host error callback and visible error text.
Dispose a widget to release its listeners, font faces and backing buffers.

The [standalone HTML packager](standalone-browser.md), configurable toolbar,
fullscreen and explicit original/current viewport PNG export work with either
renderer. PNG export re-renders the selected scene, with an explicit choice to
include selection; transient hover and focus decorations are omitted.

The paired SVG/Canvas browser checks are `tools/check-canvas-*.cjs`, and
`INTAGLIO_TEST_RENDERER=canvas` runs the widget and linked-view checks on Canvas.
The [backend capability matrix](browser-capabilities.md) records which rows
each backend has qualified. Canvas performance measurements are tracked separately;
functional parity does not establish a throughput claim.
