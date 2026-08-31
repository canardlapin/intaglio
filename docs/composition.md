# Aligned plot composition

Intaglio composes trained plots as ordinary renderer-neutral `Scene` groups. The composition owns no
SVG, Canvas, Java2D, or JavaFX object: it applies checked `Viewport` transforms, then the usual
`Scene` to `DeviceScene` lowering serves every backend.

Compile every input against the same `RenderContext` passed to the composition. The returned
`ComposedPlot` retains that context in its `renderPlan`, keeping text measurement, physical gaps,
font resolution, and final device lowering bound to one target.

```scala
val context = RenderContext.unsafe(width = 1200, height = 700)

val first  = firstProgram.resolve(context).orThrow
val second = secondProgram.resolve(context).orThrow

val composed = PlotComposition
  .row(Vector(first, second), context)
  .orThrow

val renderPlan = composed.renderPlan
```

`row`, `column`, and `grid` use row-major order with row zero at the visual top. Their affine cell
transforms map every source panel envelope to the same cell-relative panel rectangle. Axis labels,
titles, or legends may therefore give the source plots different margins without leaving their
composed panels misaligned. `composed.cells` exposes both the whole-plot transform and the resulting
panel frame for inspection.

Composition gaps are physical points. `None` uses `LayoutPolicy.panelGapPt`; explicit gaps are
checked before layout.

```scala
val options = CompositionOptions.unsafe(
  columnGapPt = Some(12.0),
  rowGapPt = Some(16.0),
  cellClip = Clip.On
)

val grid = PlotComposition.grid(plots, columns = 2, context, options).orThrow
```

## Collect guides

`CompositionGuidePolicy.CollectCompatible` leaves position axes with their own panels and moves
legends and colorbars into one solver-owned guide column. Guides with the same semantic content,
authored styles, and stable name share one entry; incompatible guides remain separate and retain
stable first-use order. Placement fields are deliberately excluded from compatibility because the
composition's guide-stack solver owns their new location.

Pass the theme used to train the plots when collected guides rely on theme defaults:

```scala
val options = CompositionOptions.unsafe(
  guides = CompositionGuidePolicy.CollectCompatible,
  layoutPolicy = publicationPolicy,
  theme = publicationTheme
)
```

## Add an inset

Inset bounds are explicit whole-composition NPC fractions in the usual y-up coordinate system.
Clipping is a required argument rather than an implicit backend default.

```scala
val inset = PlotInset.npcUnsafe(
  x = 0.62,
  y = 0.58,
  width = 0.32,
  height = 0.34,
  clip = Clip.On
)

val withInset = composed.withInset(detailPlot, inset)
```

The inset plot's semantic sidecar is appended to the composition, and its viewport and clipping
policy survive unchanged into `DeviceScene`.
