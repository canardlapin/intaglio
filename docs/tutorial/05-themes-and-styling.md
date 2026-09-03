# Themes and styling

A theme is an ordinary immutable value. There is no ambient state, no selector cascade, and no
"current theme" a library elsewhere in your process can change under you. Compilation resolves one
`Theme` into complete leaf `GraphicParams`, and anything you state explicitly wins locally.

```scala mdoc:silent
import intaglio.*

final case class Cell(x: Double, y: Double, region: String, load: Double)

val cells: Vector[Cell] =
  Vector(
    Cell(1.0, 2.4, "hippocampus", 0.31),
    Cell(2.0, 3.1, "hippocampus", 0.55),
    Cell(3.0, 2.8, "hippocampus", 0.72),
    Cell(1.0, 1.6, "cortex", 0.28),
    Cell(2.0, 2.2, "cortex", 0.49),
    Cell(3.0, 2.9, "cortex", 0.81)
  )
```

## The two built-in themes

```scala mdoc
Vector(Theme.default, Theme.minimal).map(theme => (theme.pointSizePt, theme.panel.grid.nonEmpty))
```

`Theme.default` draws marks, axes, and text with no panel decoration. `Theme.minimal` is
`default.copy(...)` with 0.75-device-pixel axis lines and ticks, a white panel background, and a
tick-aligned grid in pale grey at 0.6 device pixels. Those are the only two named themes; everything
else is a `copy`.

```scala mdoc:silent
val themed = plot(cells).aes(_.x, _.y).geomPoint().theme(Theme.minimal)
```

The panel background and grid are ordinary renderer-neutral grobs drawn beneath the data, named
`plot-panel-background`, `plot-panel-grid-x`, and `plot-panel-grid-y`:

```scala mdoc
themed.resolve.map(_.panelGrobs.flatMap(_.name.map(_.value)))
```

## What a theme holds

`Theme` has eight fields and no defaults on any of them, so you build one by copying:

| Field | Type | Governs |
|---|---|---|
| `geom` | `GraphicParams` | default mark stroke, fill, line width |
| `pointSizePt` | `Double` | default point radius in typographic points |
| `axis` | `AxisTheme` | `line`, `tick`, `text`, `title` |
| `legend` | `LegendTheme` | `text`, `title` |
| `plotText` | `PlotTextTheme` | `title`, `subtitle` |
| `panel` | `PanelTheme` | optional `background`, optional `grid` |
| `palettes` | `ThemePalettes` | `discrete`, `continuousLow`, `continuousHigh` |
| `layout` | `LayoutPolicy` | margins, gaps, tick lengths, guide geometry |

```scala mdoc:silent
val editorial =
  Theme.minimal.copy(
    pointSizePt = 5.5,
    geom = GraphicParams.unsafe(
      stroke = Some(Rgba.unsafe(28, 40, 66)),
      fill = Some(Rgba.unsafe(120, 150, 200)),
      lineWidth = 1.4
    ),
    plotText = PlotTextTheme(
      title = GraphicParams.unsafe(
        stroke = None,
        fill = Some(Rgba.Black),
        fontSize = Length.pointsUnsafe(18.0)
      ),
      subtitle = GraphicParams.unsafe(
        stroke = None,
        fill = Some(Rgba.unsafe(90, 90, 90)),
        fontSize = Length.pointsUnsafe(11.0)
      )
    )
  )
```

Every text `GraphicParams` in a theme must carry a positive point-valued `fontSize`; the `Theme`
constructor rejects anything else. This is what makes the next section work.

## Theme typography is layout typography

`theme.layoutPolicy` folds the theme's font sizes and families into a `LayoutPolicy`, and the layout
solver measures with exactly those values before lowering emits the corresponding text grobs. A
title that is 18 pt in the theme is measured at 18 pt when the solver allocates the title band.

```scala mdoc
(editorial.layoutPolicy.plotTitleFontPt, editorial.layoutPolicy.axisFontPt)
```

`LayoutPolicy` itself is the geometry half: outer margin, tick length, tick-label gap, axis-title
gap, plot-label gap, legend key size and gaps, guide-stack gap, colorbar width and height, panel gap,
facet strip height, and per-role font sizes and families. Every spacing and font-size value is in
typographic points, so a layout is resolution-independent — the same policy gives the same physical
spacing at 96 and 300 DPI. The remaining fields are the `TextMetrics` provider, the reference
`DeviceContext`, and the six font-family options.

`LayoutPolicy` has 31 parameters and `legendTitleFontPt` sits after the font-family options, so
positional construction is a trap. Always use named arguments or `copy`:

```scala mdoc:silent
val roomy = editorial.copy(
  layout = editorial.layout.copy(outerMarginPt = 18.0, legendGapPt = 16.0, panelGapPt = 12.0)
)
```

An explicit `PlotCompilerOptions.policy` keeps its *geometry* — margins, gaps, tick lengths, guide
sizes — but the theme still stamps its own sizes and families over the twelve typography fields, so
typography always comes from the theme. An explicit `PanelLayout` or `PanelFrame` stays authoritative
over the solver.

## Declaration order does not matter

`.theme(...)` can appear before or after scale, label, and geom declarations without changing the
plot. Omitted palettes and layout policies are resolved from the *final* theme during scale training
and layout assembly, not at the moment the call is written.

```scala mdoc:silent
val themeFirst =
  plot(cells).aes(_.x, _.y).theme(editorial).scaleColorDiscrete(_.region).geomPoint().resolve

val themeLast =
  plot(cells).aes(_.x, _.y).scaleColorDiscrete(_.region).geomPoint().theme(editorial).resolve
```

```scala mdoc
themeFirst.map(_.scene) == themeLast.map(_.scene)
```

## Palettes

`ThemePalettes` holds a discrete vector and the two ends of the continuous ramp.

```scala mdoc
Theme.default.palettes.discrete.length
```

The default is the first six tab10 colours. `discretePalette` builds a `DiscretePalette[Rgba]` with
the default `PaletteOverflowPolicy.Reject`, which is why a theme-default discrete scale fails on a
seventh level rather than reusing a colour. `continuousPalette` is
`Palette.gradient(continuousLow, continuousHigh)`.

```scala mdoc:silent
val ownPalette =
  editorial.copy(
    palettes = ThemePalettes(
      discrete = Vector(
        Rgba.unsafe(17, 76, 128),
        Rgba.unsafe(196, 108, 26),
        Rgba.unsafe(40, 120, 82)
      ),
      continuousLow = Rgba.unsafe(245, 247, 250),
      continuousHigh = Rgba.unsafe(17, 76, 128)
    )
  )
```

`ThemePalettes` requires a non-empty discrete vector and throws on an empty one — it is a
constructor invariant, not a checked `Either`. An explicit palette passed to `scaleColorDiscrete` or
`scaleFillContinuous` overrides the theme's for that scale only.

## Style aesthetics

The builder binds six channels directly: `color`, `fill`, `alpha`, `size`, `group`, and `subpath`.
Each takes either a constant or a `Row => A`.

```scala mdoc:silent
val styled =
  plot(cells)
    .aes(_.x, _.y)
    .color(Rgba.unsafe(28, 40, 66))
    .fill(cell => if cell.load > 0.5 then Rgba.unsafe(196, 108, 26) else Rgba.White)
    .alpha(0.85)
    .size(6.0)
    .geomPoint()
```

The remaining typed channels — `shape`, `linetype`, `linewidth`, `angle`, `hjust`, `vjust`, `label` —
are bound on a layer's `AesSpec` rather than on the builder:

```scala mdoc:silent
val shaped =
  Plot(cells)
    .addLayer(
      Layer.point[Cell](
        _.x,
        _.y,
        mapping = AesSpec
          .empty[Cell]
          .withShape(cell => if cell.region == "cortex" then PointShape.Square else PointShape.Circle)
          .withSize(cell => 4.0 + cell.load * 6.0),
        inheritMapping = false
      )
    )
    .flatMap(shapes =>
      PlotCompiler.resolve(shapes, PlotCompilerOptions(guides = GuidePolicy.Derived()))
    )
```

```scala mdoc
shaped.map(_.layers.head.rows.map(_.shape))
```

Channels are geom-specific and the contract is enforced before rows are evaluated: binding `fill` on
a `Geom.Segment` layer is `GraphicsError.UnsupportedGeomAesthetic`, not a silently discarded value.
The per-geom table and the exact point-shape geometry — including why `Diamond`'s half-diagonal is
`r * sqrt(pi / 2)` — are in the [style aesthetics guide](../style-aesthetics.md).

One rule catches people out. A line, ribbon, area, or polygon grob represents one structural group,
so its colour, alpha, line type, and line width must be constant within that group; a varying value
is `GraphicsError.VaryingGroupAesthetic` rather than a first-row style silently applied to the whole
path. Map `group` explicitly when row functions select different line styles.

## GraphicParams

`GraphicParams` is the complete leaf style value. Primitive grobs own one outright — there is no
inheritance from a parent group and no backend-dependent default.

| Field | Default |
|---|---|
| `stroke` | `Some(Rgba.Black)` |
| `fill` | `None` |
| `lineWidth` | `1.0` |
| `lineType` | `LineType.Solid` |
| `lineCap` | `LineCap.Butt` |
| `lineJoin` | `LineJoin.Miter` |
| `alpha` | `1.0` |
| `fontFamily` | `None` |
| `fontSize` | `Length.pointsUnsafe(12.0)` |
| `fillPattern` | `None` — see [pattern fills](#pattern-fills) |
| `lineWidthUnit` | `StrokeUnit.DevicePixel` |

`GraphicParams.checked(...)` returns an `Either`; `GraphicParams.unsafe(...)` throws. There is no
public `apply`. Both constructors deliberately omit `fillPattern`: the only way to set it is
`withPatternFill`, and the only way to clear it is `withSolidFill`.

Line width has two meanings and the type says which. A bare `lineWidth = 1.0` is one literal device
pixel — a hairline that gets thinner as you raise DPI. A physical stroke uses `StrokeWidth`:

```scala mdoc:silent
val hairline = GraphicParams.unsafe(lineWidth = 1.0)
val onePoint = GraphicParams.unsafe().withStrokeWidth(StrokeWidth.pointsUnsafe(1.0))
```

Device lowering converts point strokes once, as `lineWidth * pixelsPerInch / 72`, and normalizes
everything to `StrokeUnit.DevicePixel` — so every backend receives the same number. Only
`pixelsPerInch` enters that conversion; `RenderContext.hidpi` folds the device-pixel ratio into
`pixelsPerInch`, which is why a HiDPI target thickens point strokes correctly and a hand-built
context with a bare `deviceScale = 2.0` does not.

## Pattern fills

A pattern is a checked recipe plus explicit colours. It stores no SVG, no CSS, no callback, and no
backend object, which is why it survives to every backend including PDF as vector output.

```scala mdoc:silent
val hatched =
  for
    recipe <- PatternRecipe.angledHatch(angleDegrees = 45.0, spacing = 8.0, lineWidth = 1.25)
  yield GraphicParams
    .unsafe(stroke = Some(Rgba.Black), alpha = 0.8)
    .withPatternFill(
      PatternPaint(recipe, Rgba.unsafe(30, 80, 120), background = Some(Rgba.White))
    )
```

Four recipes exist and no more: `PatternRecipe.angledHatch(angleDegrees, spacing, lineWidth)`,
`crossHatch(angleDegrees, spacing, lineWidth)`,
`parallelRules(orientation, spacing, lineWidth)` with `RuleOrientation.Horizontal | Vertical`, and
`stipple(spacing, radius)` where `radius <= spacing / 2`.

Facts that determine what a pattern looks like:

- Spacing, line width, and stipple radius are **device pixels**, not points.
- Hatch angles are clockwise degrees from a vertical rule, in the y-down device coordinate system.
- The tile starts at device `(0, 0)`, does not restart at each mark's bounding box, and follows
  enclosing viewport transforms — so adjacent marks share one continuous field of hatching.
- Ink and background keep their own RGBA; the mark's `alpha` is applied once, to the composited
  result.
- Patterns affect only primitives with a fill channel: discs, closed polygons, compound polygons,
  and rectangles. Text, images, and open lines are unchanged.

`withPatternFill` replaces the solid fill channel; `withSolidFill` switches back. The two cannot both
be set.

One limit is worth knowing before you pick a spacing: the raster backends (Java2D, Canvas, JavaFX)
materialize the pattern as a tile and reject a spacing above 1024 device pixels with
`GraphicsError.InvalidPatternParameter`. SVG and PDF emit vector patterns and do not apply that cap.

## Where theme styling stops

Themes resolve into `GraphicParams`. They do not carry layout algorithms, glyph choices, or
conditional rules — a theme cannot say "use squares when there are more than five groups". That is a
mapping, and it belongs in an `AesSpec`.

## Next

- [Composition](06-composition.md) — sharing one theme and one layout policy across several plots.
- [Saving output](07-saving-output.md) — how DPI and device scale interact with everything on this
  page.
