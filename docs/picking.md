# Picking a hand-built scene

A host that draws its own grobs, rather than compiling a plot, identifies interactive marks by
`GraphicsName` and queries them through `NamedPicking` (the plot path is described in the
[interaction module README](../modules/interaction/README.md)). This page covers the entry points a
desktop or canvas host needs to draw and pick one scene without doing the work twice.

## Lower once, then draw and pick

`NamedPicking.compile(scene, context)` and `JavaFxRenderer.compile(...)` each resolve the scene
against the device before doing their own work. A host that draws, picks and overlays the same
scene resolves it once with `DeviceScene.fromScene` and hands the result to both consumers:

```scala mdoc:silent
import intaglio.*
import intaglio.interaction.*
import intaglio.javafx.*

val context = RenderContext.unsafe(width = 400, height = 300, pixelsPerInch = 192, deviceScale = 2)
val ink = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
val scene = Scene(
  Vector(
    Grob.circleUnsafe(Point.npcUnsafe(0.25, 0.5), ExtentExpr.pointsUnsafe(6), ink,
      name = Some(GraphicsName.unsafe("left"))),
    Grob.circleUnsafe(Point.npcUnsafe(0.75, 0.5), ExtentExpr.pointsUnsafe(6), ink,
      name = Some(GraphicsName.unsafe("right")))
  )
)

val drawAndPick = for
  resolved <- DeviceScene.fromScene(scene, context)
  program <- JavaFxProgram.fromResolved(resolved, context)
  picking <- NamedPicking.fromResolved(resolved, context)
yield (program, picking)
```

```scala mdoc
drawAndPick.map(_._2.nearest(DevicePoint(100, 150), 4).map(_.map(_.name.value)))
```

The program and the plan are identical to the ones the separate entry points build from the source
scene; only the resolution is shared. `context` must be the one the scene was resolved under, since
picking measures text with its metrics. `JavaFxProgram.fromResolved` checks the resolved scene as a
fresh lowering is checked, so a hand-assembled `DeviceScene` with a non-finite coordinate or an
oversized pattern tile is a typed `JavaFxRenderError`, not a drawing failure. For plots,
`Picking.fromResolved` plays the same role.

The JavaFX suite `JavaFxResolvedSceneSuite` records the cost for 11,520 named marks at device scale
2: two lowerings per draw-and-pick through the separate entry points, one through the resolved
scene. The timings it writes are measurements on one machine, not a guarantee.

## Hit the inside of hollow marks

By default only painted ink is a target, so a stroked, unfilled circle is hit on its outline and a
click at its centre misses. Point glyphs are the exception, described below. `PickPolicy.withHollow`
changes that without faking a fill:

```scala mdoc:silent
val outlined = GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = None, lineWidth = 2)
val hollowScene = Scene(
  Vector(
    Grob.circleUnsafe(Point.npcUnsafe(0.5, 0.5), ExtentExpr.pointsUnsafe(10), outlined,
      name = Some(GraphicsName.unsafe("ring")))
  )
)
val centre = DevicePoint(200, 150)
val outlineOnly = NamedPicking.compile(hollowScene, context)
val withInside = NamedPicking.compile(
  hollowScene,
  context,
  PickPolicy.default.withHollow(HollowPicking.Interior)
)
```

```scala mdoc
outlineOnly.map(_.hits(centre).map(_.map(_.name.value)))
withInside.map(_.hits(centre).map(_.map(_.name.value)))
```

`HollowPicking.Interior` applies to every closed hollow mark: circles, rectangles, closed paths and
polygons, and the circle, square, triangle and diamond point shapes. A cross or an open path has no
inside, and a mark with neither visible stroke nor visible fill is still not a target.
`HollowPicking.InteriorOf(names)` limits it to marks whose innermost enclosing `GraphicsName` is
listed. With the inside included, a hollow mark picks exactly as the same mark filled would:
overlapping hollow marks are ordered by distance and then by draw order, so a point inside two of
them reports the later-drawn one first. Area selection uses the same regions. Rendering is
unchanged. The policy applies to plot picking (`Picking.compile`) as well as named picking.

### Point glyphs are hit on their whole disc

A hollow point is small, and pointing at its centre is how a reader aims at it, so point glyphs
follow a second field, `PickPolicy.hollowPoints`, which defaults to `HollowPicking.Interior`. The
inside of a hollow circle, square, triangle or diamond point is hit by default on every host that
picks through the shared plan (the SVG widget, Canvas and JavaFX); a cross still has no inside.
`withHollowPoints(HollowPicking.Outline)` restores outline-only point picking, and `InteriorOf`
limits it to chosen names. A point's inside is hit when either `hollow` or `hollowPoints` includes
it, so `withHollow(HollowPicking.Interior)` still covers every closed mark.

```scala mdoc:silent
val hollowPoints = Scene(
  Vector(
    Grob.pointBatchUnsafe(
      Vector(Point.npcUnsafe(0.5, 0.5)),
      sizes = BatchColumn.Constant(ExtentExpr.pointsUnsafe(10)),
      graphicParams = BatchColumn.Constant(outlined),
      name = Some(GraphicsName.unsafe("glyph"))
    )
  )
)
val pointDefault = NamedPicking.compile(hollowPoints, context)
val pointOutline = NamedPicking.compile(
  hollowPoints,
  context,
  PickPolicy.default.withHollowPoints(HollowPicking.Outline)
)
```

```scala mdoc
pointDefault.map(_.hits(centre).map(_.map(_.name.value)))
pointOutline.map(_.hits(centre).map(_.map(_.name.value)))
```

A point glyph is recognised by its kind, not its shape: every mark of a point batch, and in plot
picking every primitive routed to a target whose grobs are point grobs, alongside at most lines,
segments, text or images, as for a summary's centre and interval. Rectangles, tiles, polygons,
ribbons and circle grobs keep the `hollow` rule. Because the route decides, a hand-built
`DeviceScene` given to `Picking.fromResolved` that routes a rectangle or polygon to a point layer's
group has that mark's inside picked as a point's. One gap remains in named picking: a point drawn
from an individual `Grob.points` is lowered to the same circle, square or closed path as any grob
of that shape, so a named scene cannot tell it apart and it follows `hollow`. Draw such points as a
`Grob.pointBatch` to give them point semantics.

The default has a cost where hollow glyphs overlap. A large hollow glyph drawn later, such as a
bubble, is at distance 0 over its whole disc and wins the draw-order tie, so a small point seen
through its ring cannot be hovered or clicked at its centre, and an `Intersecting` lasso or
rectangle inside the bubble selects the bubble. For bubble charts and other dense overlapping
hollow glyphs, pick with `PickPolicy.default.withHollowPoints(HollowPicking.Outline)`.

These entry points take the policy: `Picking.compile`, `Picking.composition` and
`Picking.fromResolved` for plots; `NamedPicking.compile` and `NamedPicking.fromResolved` for named
scenes; and on JavaFX, `JavaFxInteractionView.compile(plan, context, policy)`,
`JavaFxInteractionView.named` and `JavaFxInteractionView.namedResolved`. In the browser,
`SvgWidgetView.compile` and `compileComposition` take the same `policy`, which the widget keeps
across re-windowing and repainting.

## Report the cell of an image

A heatmap, design matrix or confusion matrix drawn as a scene is usually one image grob, so it
picks as one name. `cellAt(name, point)` reports which cell of that image is drawn under a device
point, for a hover readout or a selected column; looking up the value is the caller's, since the
caller built the image:

```scala mdoc:silent
val confusion = RasterImage.tabulate(RasterDimensions.unsafe(3, 2)) { (column, row) =>
  Rgba32.unsafe(80 * column, 120 * row, 160)
}
val matrixScene = Scene(
  Vector(
    Grob.imageUnsafe(confusion, Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(0.6, 0.4),
      name = Some(GraphicsName.unsafe("confusion")))
  )
)
val matrixPicking = NamedPicking.compile(matrixScene, context)
```

The image covers device pixels 80 to 320 across and 90 to 210 down, so each cell is 80 by 60:

```scala mdoc
matrixPicking.flatMap(_.cellAt(GraphicsName.unsafe("confusion"), DevicePoint(250, 100)))
matrixPicking.flatMap(_.cellAt(GraphicsName.unsafe("confusion"), DevicePoint(160, 150)))
matrixPicking.flatMap(_.cellAt(GraphicsName.unsafe("confusion"), DevicePoint(50, 150)))
```

`row` and `column` index the `RasterImage`: row 0 is the image's first row, which is its visual top
row, drawn along the top of the image's box, and column 0 is drawn along its left edge. Under a
rotated viewport the grid turns with the box. This is not the convention of the plot compiler's
`RasterCell`, whose row is the source field's y-up index; a hand-built image has no field, so its
own rows are the only order the scene knows. A caller who filled the image from a y-up matrix reads
that matrix's row as `height - 1 - row`.

Cells are half-open: a point exactly on an interior boundary belongs to the cell to its right or
below (`DevicePoint(160, 150)`, on a corner, is row 1, column 1), and the image's right and bottom
edges belong to its last column and row. A cell is reported exactly where `hits(point)` reports the
name at distance zero: inside the box, within the 1e-8 device-pixel tolerance of every picking
boundary, and inside every clip around it. A hit that only a tolerance reaches, or a grob that is
not an image, has no cell. Every pixel counts whatever its alpha, as it does for `hits`, and smooth
interpolation does not change the grid. Where one name paints several images over the point, the
last drawn reports. The mapping goes through the same rotation picking uses, in plain double
arithmetic, so the JVM and Scala.js agree exactly without rotation and, under rotation, everywhere
except within rounding of a cell boundary or an image or clip edge.

`ImageCellPixelOracleSuite` checks this against pixels drawn by Java2D under nested viewports at
device scale 2, a clip that cuts the image, rotated viewports with and without their clip, and
nested rotations; on JavaFX, `JavaFxImageCellSuite` checks it against the host's own canvas.

## Keyboard navigation and interaction state

`NamedInteraction` binds a `NamedPickingPlan` to the identities the shared interaction state uses,
so focus, selection, keyboard navigation and hosts work over names:

```scala mdoc:silent
val marks = NamedInteraction.keySpace("marks").toOption.get
val bound = for
  plan <- NamedPicking.compile(scene, context)
  revision <- PlanRevision("view-1")
  interaction <- NamedInteraction(plan, marks, SemanticId.unsafe("canvas"), revision)
yield interaction
```

```scala mdoc
bound.map { interaction =>
  val navigation = interaction.prepareNavigation()
  val left = interaction.target(GraphicsName.unsafe("left")).get.id
  navigation.nearest(left, NavigationDirection.Right).map(_.flatMap(_.target.entity).map(_.value))
}
```

Each name is one target, addressed in draw order and carrying its name as an entity, so
`interaction.picking` answers every query with the same hits, distances and draw orders as the
named plan. `NavigationPlan.next` and `previous` step through targets in that stable order, reaching
marks that directional `nearest` cannot. `interaction.domain` feeds `InteractionState.initial`; its
`plans` is empty because no plot was compiled.

## Name the marks of a point batch

A point batch draws many marks as one primitive, so a name on the batch makes the whole batch one
target. `BatchMarks` gives each mark its own `GraphicsName`, and optionally accessible text, without
a side table from batch index to the host's key:

```scala mdoc:silent
val trials = Vector(0.2, 0.4, 0.6, 0.8)
val trialBatch = Grob.pointBatchUnsafe(
  trials.map(x => Point.npcUnsafe(x, 0.5)),
  sizes = BatchColumn.Constant(ExtentExpr.pointsUnsafe(5)),
  graphicParams = BatchColumn.Constant(ink),
  name = Some(GraphicsName.unsafe("trials"))
)
val trialMarks = for
  named <- BatchMarks(trials.indices.toVector.map(i => GraphicsName.unsafe(s"trial-${i + 1}")))
  titled <- named.withTitles(trials.indices.toVector.map(i => s"Trial ${i + 1}"))
  key <- DataKey("trial")
yield titled.withDataAttribute(key)

val trialPicking = for
  marks <- trialMarks
  plan <- NamedPicking.compile(Scene(Vector(Grob.annotated(trialBatch, GrobMeta.marks(marks)))), context)
yield plan
```

```scala mdoc
trialPicking.map(plan => plan.nearest(DevicePoint(240, 150), 4).map(_.map(_.name.value)))
trialPicking.map(_.accessibleText(GraphicsName.unsafe("trial-3")))
```

The names run, in draw order, over the marks of every point batch beneath the annotation, so one
`BatchMarks` can span a batch the host draws in several chunks; a nearer annotation with its own
marks names its batches instead. Lowering refuses a count mismatch as
`GraphicsError.BatchColumnLengthMismatch("mark names", ...)`. A host that filters or splits a batch
itself slices the names with the points (`BatchMarks.slice`); `BatchMarkLaws` in `intaglio-laws`
checks such a splitter. Picking treats each mark as a target named by its mark name, inside the
batch's own name, so clipping and hit order work exactly as for named grobs. Its marks are point
glyphs, so their hollow interiors follow `PickPolicy.hollowPoints` rather than `hollow`.

Identity never changes what is drawn: every backend draws the batch as before, and the renderer
conformance contract includes an identified batch. The SVG backend writes each mark's text as a
`<title>` and, when `withDataAttribute` is set, its name as that attribute
(`<g data-trial="trial-3"><title>Trial 3</title><circle data-name="trials" .../></g>`); the mark's
elements keep `data-name` for the batch. `BatchMarks.marksOf(deviceScene)` lists every identified
mark with its device position. A batch without marks is lowered, drawn and picked exactly as
before; `performance/timings/batch-marks-cost.txt` records the cost on one machine.
