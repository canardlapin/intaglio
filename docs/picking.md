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
click at its centre misses. `PickPolicy.withHollow` changes that without faking a fill:

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
