# Facets and coordinates

Facets split one plot into a grid of panels. Coordinates decide how logical panel space becomes
physical panel space. They interact — a fixed aspect ratio and facets are mutually exclusive — so
they belong on one page.

```scala mdoc:silent
import intaglio.*

final case class Session(block: Double, score: Double, site: String, task: String)

val sessions: Vector[Session] =
  Vector(
    Session(1.0, 0.62, "north", "recall"),
    Session(2.0, 0.71, "north", "recall"),
    Session(3.0, 0.80, "north", "recall"),
    Session(1.0, 0.44, "north", "recognition"),
    Session(2.0, 0.55, "north", "recognition"),
    Session(3.0, 0.58, "north", "recognition"),
    Session(1.0, 0.51, "south", "recall"),
    Session(2.0, 0.66, "south", "recall"),
    Session(3.0, 0.74, "south", "recall"),
    Session(1.0, 0.39, "south", "recognition"),
    Session(2.0, 0.47, "south", "recognition"),
    Session(3.0, 0.52, "south", "recognition")
  )
```

## facetWrap

```scala mdoc:silent
val wrapped =
  plot(sessions)
    .aes(_.block, _.score)
    .facetWrap(_.task, columns = 2)
    .geomLine()
    .geomPoint()
    .axisTitles("Block", "Score")
```

```scala mdoc
wrapped.resolve.map(_.facetPanels.map(panel => panel.cell.panelName.value))
```

Panels are laid out row-major with row zero at the visual top. `levels` fixes panel order; omit it
and levels appear in first-occurrence order. `columns` must be at least 1 —
`GraphicsError.InvalidFacetColumns` otherwise — and a repeated level is
`GraphicsError.DuplicateLevel`.

## facetGrid

```scala mdoc:silent
val gridded =
  plot(sessions)
    .aes(_.block, _.score)
    .facetGrid(_.site, _.task)
    .geomPoint()
    .axisTitles("Block", "Score")
```

`facetGrid` takes two accessors and produces the full row-by-column cross product, so an empty
combination still gets a panel. `rowLevels` and `columnLevels` fix each axis's order independently.

Every cell is a typed `FacetCell(row, column, rowLabel, columnLabel)`. Wrap cells carry only a
column label; grid cells carry both, and `FacetCell.label` joins them as `"row | column"`. Names are
derived, not stringly assembled at the call site:

| Region | Name |
|---|---|
| panel group | `panel-<row>-<column>` |
| strip text | `strip-<row>-<column>` |
| per-panel axis | `<base>-<row>-<column>`, e.g. `x-axis-1-0` — the base is the guide's own name, and an unnamed explicit override falls back to the lowercased side |
| unfaceted panel | `plot-panel` |

## Shared and free scales

`FacetScales` controls **position** scales only. Colour and fill stay plot-global under every policy,
so a legend never means one thing in one panel and something else in another.

| Case | Effect |
|---|---|
| `Shared` (default) | one x and one y scale trained over every panel |
| `FreeX` | x retrained per panel; y shared |
| `FreeY` | y retrained per panel; x shared |
| `Free` | both retrained per panel |

```scala mdoc:silent
val freeY =
  plot(sessions)
    .aes(_.block, _.score)
    .facetWrap(_.task, columns = 1, scales = FacetScales.FreeY)
    .geomPoint()
```

A free dimension gets its own axis on **every** panel; only a dimension explicitly shared by
`FacetScales` suppresses inner axes. Under `Shared`, a side renders on the outer cells — bottom-most
of its column, left-most of its row — which for derived bottom-and-left axes means the bottom row and
the left column. The solver also widens the inter-panel gap when a free dimension causes inner axes
to be repeated, and it measures those independently trained tick labels with the active
`LayoutPolicy.metrics`, so a free panel's labels stay legible instead of colliding.

Order of operations matters for statistics: **facets partition each layer before statistics run**.
A faceted histogram bins within its panel; it does not bin globally and then split the bars.

## Facets need the layout solver

Faceted compilation requires a `LayoutPolicy` and refuses a pinned `PanelLayout` or `PanelFrame` —
there is no single panel rectangle to pin. The compiler supplies the theme's policy automatically
whenever a plot is faceted, so this only bites if you set `layout` or `frame` yourself:

```scala mdoc
plot(sessions)
  .aes(_.block, _.score)
  .facetWrap(_.task)
  .geomPoint()
  .compilerOptions(PlotCompilerOptions(layout = Some(PanelLayout.unit(
    Interval.unsafe(0.0, 4.0),
    Interval.unsafe(0.0, 1.0)
  ))))
  .resolve
  .left
  .map(_.message)
```

## coordFixed

`coordFixed(ratio)` constrains the panel's aspect so that one data unit on y is `ratio` data units on
x. It is checked at build time.

```scala mdoc:silent
val square =
  plot(sessions)
    .aes(_.block, _.score)
    .geomPoint()
    .coordFixed(1.0)
```

```scala mdoc
plot(sessions).aes(_.block, _.score).geomPoint().coordFixed(0.0).build.left.map(_.message)
```

A degenerate range — zero width on either axis — is `GraphicsError.DegenerateFixedAspect`, and a
fixed coordinate on a faceted plot is `GraphicsError.FacetFixedCoordinates`:

```scala mdoc
plot(sessions)
  .aes(_.block, _.score)
  .facetWrap(_.task)
  .geomPoint()
  .coordFixed()
  .resolve
  .left
  .map(_.message)
```

## coordZoom

Zoom is a *post-statistical* window on the panel, not a filter on the data. Rows are retained and
statistics have already run over the full dataset; the visual zoom is ordinary panel clipping.

```scala mdoc:silent
val zoomed =
  plot(sessions)
    .aes(_.block, _.score)
    .geomLine()
    .coordZoom(x = Some(Interval.unsafe(1.5, 2.5)))
```

```scala mdoc
zoomed.resolve.map(trained => (trained.layers.head.rows.length, trained.droppedRows.length))
```

Two behaviours follow from that and are worth stating outright.

**No rows are dropped.** A histogram zoomed to its central bins still shows the bins computed from
every observation. If you want the statistic recomputed on a subrange, filter the data or set scale
limits — see the [limits versus zoom](../date-time-and-zoom.md#limits-are-not-zoom) discussion.

**A zoomed axis skips range expansion.** The compiler's default 5% `RangeExpansion` pads derived
panel ranges so marks at the extremes stay inside the panel. An axis you have windowed explicitly
gets exactly the window you asked for; an axis you left alone keeps the expansion.

`coordZoom` takes raw numeric intervals. For a typed date or date-time position scale, use
`coordZoomWindows` with a `CoordinateWindow`:

```scala mdoc:silent
final case class Daily(day: CalendarDate, value: Double)

val start = CalendarDate.parseUnsafe("2026-03-01")

val daily = Vector.tabulate(12)(i => Daily(start.addDaysUnsafe(i.toLong), i.toDouble * 1.5))

val windowed =
  for
    yScale <- ContinuousScaleSpec.numeric("value")
    trained <- plot(daily)
      .scaleXDate(_.day, name = "day")
      .encode(Aesthetic.Y, _.value, yScale)
      .coordZoomWindows(
        x = Some(CoordinateWindow.dateUnsafe(start.addDaysUnsafe(2), start.addDaysUnsafe(9)))
      )
      .geomLine()
      .resolve
  yield trained
```

```scala mdoc
windowed.map(trained => (trained.layers.head.rows.length, trained.droppedRows.length))
```

`scaleXDate` establishes the x position prerequisite the same way `aes` does, which is why
`geomLine` remains callable without an `aes` call — the y side is supplied by `encode`.

A window whose kind does not match the trained scale is `GraphicsError.CoordinateZoomScaleMismatch`
rather than a silently mis-scaled axis, and `coordZoom`/`coordZoomWindows` with both bounds absent is
`GraphicsError.EmptyCoordinateZoom`. Typed temporal scales are covered in the
[date/time and zoom guide](../date-time-and-zoom.md).

## Flipped coordinates

There is no `coordFlip` method. The coordinate is a value, and you pass it:

```scala mdoc:silent
val horizontal =
  plot(sessions)
    .aes(_.block, _.score)
    .geomPoint()
    .coord(Coord.Flipped())
```

`Coord.Flipped` is a whole-plot transpose. It swaps x and y on every resolved row — including bands,
category identities, segment ends, and min/max bounds — flips every annotation and grob, and swaps
the panel range pair. Its `guideLayout` correspondingly moves the logical x guide to `AxisSide.Left`
and the logical y guide to `AxisSide.Bottom`, so the x axis title ends up on the left where a reader
expects it. Unlike `Coord.Fixed`, it is facet-safe.

`Coord.Cartesian(clip)` is the default; both take a `Clip`, which is `Clip.On` or `Clip.Off` and
nothing else.

## Coordinates are an open contract

`Coord` is a public trait with one abstract member, `clipping`, and four overridable methods:
`transform(CoordInput): Either[GraphicsError, CoordResult]`, `guideLayout`, `panelAspect`, and
`validateFacet`. Built-in Cartesian, flipped, fixed, and zoom coordinates use exactly those methods —
the compiler has no built-in-coordinate registry and no fallback cast.

`CoordinateTransform.identity`, `.transpose`, and the checked `.translate(input, x, y)` are reusable
logical-output transforms for anyone writing one. Writing your own is covered in
[extending coordinates](../extending/coords.md), and `CoordLaws` in
[`intaglio-laws`](../../modules/laws/README.md) is the conformance kit it should pass.

## Next

- [Themes and styling](05-themes-and-styling.md) — panel decoration, strip text, and the layout
  policy that sizes all of this.
- [Composition](06-composition.md) — several *plots* side by side, as opposed to several panels.
