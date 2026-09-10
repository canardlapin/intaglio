# Statistical layers

A statistical layer computes a new table before anything is drawn. In Intaglio that table is typed:
each built-in statistic emits its own `StatRow` subtype with its own required fields, and the
compiler maps those fields onto a geom. There is no enum flag a geom interprets, and no untyped
`..count..` string.

Five DSL methods cover the common cases. All of them take a `params: Option[GraphicParams]` for
styling and none of them take aesthetic mappings — see [Stat layers replace their
mapping](#stat-layers-replace-their-mapping) below for why.

```scala mdoc:silent
import intaglio.*

final case class Measurement(condition: Double, latency: Double, cohort: String)

val measurements: Vector[Measurement] =
  Vector(
    Measurement(1.0, 410.0, "young"),
    Measurement(1.0, 455.0, "young"),
    Measurement(1.0, 398.0, "older"),
    Measurement(2.0, 502.0, "young"),
    Measurement(2.0, 548.0, "young"),
    Measurement(2.0, 611.0, "older"),
    Measurement(3.0, 486.0, "young"),
    Measurement(3.0, 523.0, "older"),
    Measurement(3.0, 559.0, "older")
  )
```

## At a glance

| DSL method | Needs | Statistic | Output geom | Emits |
|---|---|---|---|---|
| `geomHistogram(bins)` | `x` | `Stat.Bin` | `Geom.Bar` | `StatRow.Binned` |
| `geomDensity(config)` | `x` | `Stat.Density` | `Geom.Line` | `StatRow.Density` |
| `geomSummary(interval)` | `x`, `y` | `Stat.Summary` | `Geom.Point` + interval | `StatRow.Summarized` |
| `geomQuantileSummary()` | `x`, `y` | quantile summary | `Geom.Point` + interval | `StatRow.QuantileSummary` |
| `geomEcdf(group)` | `x` | `Stat.Ecdf` | `Geom.Line` | `StatRow.Ecdf` |

"Needs `x`" means the builder's type must already carry an x mapping. `aes(x)` is enough for the
three that need only x; `aes(x, y)` is required for the two summaries. Calling `geomSummary` on an
x-only builder is a compile error reading *"This plotting operation requires x and y. Call .aes(x, y)
first."*

## Histogram

```scala mdoc:silent
val histogram =
  plot(measurements)
    .aes(_.latency)
    .geomHistogram(HistogramBins.countUnsafe(6))
    .axisTitles("Latency (ms)", "Count")
```

`HistogramBins` has four constructors and no public cases, so a bin specification cannot be
half-formed:

| Constructor | Meaning |
|---|---|
| `HistogramBins.default` | 30 bins by count — this is what `geomHistogram()` uses |
| `HistogramBins.count(n)` / `countUnsafe(n)` | `n` equal-width bins spanning the observed range |
| `HistogramBins.width(w)` / `widthUnsafe(w)` | fixed width, edges snapped outward to multiples of `w` |
| `HistogramBins.breaks(values)` / `breaksUnsafe(values)` | explicit strictly increasing edges |

**Bins are right-closed.** For edges `b0 < b1 < … < bn` the first bin is `[b0, b1]` and every later
bin is `(bi, b(i+1)]`. An internal edge therefore belongs to the bin on its left, and both outer
endpoints are included. With explicit breaks, an observation outside the closed outer domain is a
typed failure rather than a silently discarded row.

`StatRow.Binned` carries `count`, `proportion`, `density`, `binLower`, `binUpper`, and derives
`binWidth` and `binMidpoint`. `proportion` is `count / n`; `density` is `count / (n * binWidth)`.
Empty bins are dropped, and the bar's width is the bin width.

```scala mdoc
histogram.resolve.map(
  _.layers.head.statFrame.rows.collect { case row: StatRow.Binned[?] =>
    (row.binLower, row.binUpper, row.count)
  }
)
```

The x scale trains on the full bin edges and the y scale includes zero, so bars sit on a baseline
rather than floating.

## Density

```scala mdoc:silent
val density =
  plot(measurements)
    .aes(_.latency)
    .geomDensity()
    .axisTitles("Latency (ms)", "Density")
```

`DensityConfig.default` is: automatic bandwidth, 512 grid points, the observed data range as the
domain, and `KdeStrategy.Direct`. The kernel is Gaussian and the automatic bandwidth is R's
`bw.nrd0` — `min(sd, IQR/1.34)` with the same constant-data fallbacks, then Silverman's
`0.9 * scale * n^(-1/5)`.

Pin the bandwidth and the grid when you need reproducible smoothing across datasets:

```scala mdoc:silent
val pinned =
  DensityConfig.fixed(bandwidth = 40.0, points = 128, domain = Some(Interval.unsafe(350.0, 650.0)))

val fixedDensity =
  pinned.map(config => plot(measurements).aes(_.latency).geomDensity(config))
```

Two numerical commitments worth knowing before you interpret the curve. The kernel carries the
analytical whole-space Gaussian normalizer, and Intaglio *samples* that density on the requested
finite domain — it does not renormalize a truncated domain back to one, so a narrow `domain` gives
you a genuine slice rather than a rescaled distribution. And `KdeStrategy.Fft` is a declared but
unimplemented case: selecting it returns `GraphicsError.UnsupportedStatStrategy` instead of quietly
falling back to the direct sum.

Density needs at least two observations. `StatRow.Density` exposes `position`, `density`, and
`sampleSize`, with `count = density * sampleSize` for ggplot2-comparable scaled output.

## Summary and error bars

`geomSummary` groups rows by their exact numeric `x` and emits one row per group, in ascending `x`.

```scala mdoc:silent
val summary =
  plot(measurements)
    .aes(_.condition, _.latency)
    .geomSummary(SummaryInterval.StandardError)
    .axisTitles("Condition", "Latency (ms)")
```

`SummaryInterval` has exactly two cases:

| Case | Centre | Bounds |
|---|---|---|
| `StandardError` (default) | arithmetic mean | `mean ± sd / sqrt(n)`, with the *sample* standard deviation |
| `Range` | arithmetic mean | observed minimum and maximum |

Moments are computed with a compensated accumulation, and `sampleVariance` is
`secondCentralMoment / (n - 1)`, defined as `0.0` for a single observation.

`StatRow.Summarized` carries `position`, `mean`, `lower`, `upper`, and `count`, with the invariant
`lower <= mean <= upper` enforced at construction. Lowering emits, per group, one `Grob.Segments`
spanning the interval plus one `Grob.Points` at the mean, and trains the y range on the bounds — so
an error bar is never clipped by its own panel.

```scala mdoc
summary.resolve.map(
  _.layers.head.statFrame.rows.collect { case row: StatRow.Summarized[?] =>
    (row.position, row.mean, row.lower, row.upper, row.count)
  }
)
```

If you already have intervals in your data rather than raw observations, you want `geomErrorBar` or
`geomRibbon` instead — they are identity layers taking `yMin` and `yMax` accessors, not statistics.

## Quantile summary

```scala mdoc:silent
val quartiles =
  plot(measurements)
    .aes(_.condition, _.latency)
    .geomQuantileSummary()
    .axisTitles("Condition", "Latency (ms)")
```

Same grouping as `geomSummary`, different summary: the median with the interquartile range, using
**Hyndman-Fan type 7** — R's default, and the definition `stat_summary` reproduces. For
`c(1, 2, 3, 4, 100)` that gives 2.0, 3.0, 4.0; for `c(0, 10, 20, 30)` it gives 7.5, 15.0, 22.5.

`StatRow.QuantileSummary` carries `position`, `lowerQuartile`, `median`, `upperQuartile`, and
`count`, with `lowerQuartile <= median <= upperQuartile` enforced. The mark is the median; the
segment spans the quartiles.

The type-7 choice matters when you compare against another tool. Nine quantile definitions are in
common use and they disagree on small samples; naming the estimator is the only way a reader can
reproduce the number.

## ECDF

```scala mdoc:silent
val ecdf =
  plot(measurements)
    .aes(_.latency)
    .geomEcdf(group = Some(_.cohort))
    .axisTitles("Latency (ms)", "Cumulative proportion")
```

The empirical CDF is **right-continuous** and ties collapse into a single step. Grouping is an
argument of the statistic, not an aesthetic mapping: groups are computed independently in
first-encounter order, and each group's proportions are relative to its own count.

`StatRow.Ecdf` carries `position`, `cumulativeCount`, `totalCount`, and `groupLevel`, deriving
`proportion = cumulativeCount / totalCount`.

Lowering is explicit about the staircase. For a group holding `{1, 2, 2, 4}` the emitted path is
`(1, 0) (1, 0.25) (2, 0.25) (2, 0.75) (4, 0.75) (4, 1.0)`: it starts on the zero baseline at the
first observation, and each later step inserts the horizontal vertex before the vertical one. The y
range is trained to include zero so the baseline is inside the panel.

```scala mdoc
ecdf.resolve.map(
  _.layers.head.statFrame.rows.collect { case row: StatRow.Ecdf[?] =>
    (row.groupLevel, row.position, row.cumulativeCount, row.proportion)
  }
)
```

## Stat layers replace their mapping

Every built-in statistic declares `StatMappingPolicy.Replace`: it *owns* the output mapping. The DSL
therefore builds a stat layer with an empty mapping and no inheritance, and any aesthetic bound on
the layer is rejected before rows are evaluated — `GraphicsError.StatAestheticConflict` for a
position, `GraphicsError.UnsupportedStatAesthetic` for anything else.

The practical consequences:

- Style a stat layer with `params`, not with `.color(...)`:
  `geomHistogram(params = Some(GraphicParams.unsafe(fill = Some(Rgba.unsafe(70, 110, 170)))))`.
- Split a statistic by group through the statistic's own argument (`geomEcdf(group = ...)`), or
  through separate layers with their own `data`.
- Statistical layers take no `Position`. Dodge, stack, and jitter are set on `Layer.point`,
  `Layer.count`, and `Layer.fromMapping`; `Stack` applies only to `Geom.Bar` and `Jitter` only to
  `Geom.Point`, and any other pairing is `GraphicsError.InvalidPositionGeom`.

## Computed aesthetics

Every statistic publishes the derived quantities it produced, as a closed `ComputedAesthetic` set on
the frame. This is the inspection view — the compiler drives lowering from the typed subtype's
fields, not from this map.

| Statistic | `computedAesthetics` |
|---|---|
| identity | none |
| count | `Count`, `Proportion` |
| bin | `Count`, `Proportion`, `Density`, `BinLower`, `BinUpper`, `BinWidth`, `BinMidpoint` |
| summary | `Count`, `Position`, `Mean`, `Lower`, `Upper` |
| quantile summary | `Count`, `Position`, `Median`, `Lower`, `Upper` |
| ecdf | `Count`, `Proportion`, `Position` |
| density | `Count`, `Position`, `Density` |

```scala mdoc
histogram.resolve.map(_.layers.head.statFrame.computedAesthetics.map(_.label).toVector.sorted)
```

The declared key set survives an empty frame, so a statistic that produced no rows still reports what
it *would* have produced.

## Counting categories

`Stat.Count` has no DSL method, because its input is a `Row => String` rather than a position
mapping. Reach for the layer constructor:

```scala mdoc:silent
val counted =
  Plot(measurements)
    .addLayer(Layer.count[Measurement](_.cohort, order = CountOrder.Lexicographic))
    .flatMap(counts =>
      PlotCompiler.resolve(counts, PlotCompilerOptions(guides = GuidePolicy.Derived()))
    )
```

```scala mdoc
counted.map(_.layers.head.statFrame.rows.flatMap(_.category))
```

`CountOrder` is `Encountered` (first occurrence), `Lexicographic`, or `CountOrder.declared(levels)`,
which puts declared levels first and appends undeclared observed levels afterwards. The x positions
come from a `BandScale` trained on the resulting categories, so bar positions and axis labels are the
same object. `Layer.count` also takes `group`, `padding`, and `position` — its default position is
`Position.Stack()`.

## Beyond the built-ins

`Stat` is an open public contract, not a sealed enum. An extension implements one polymorphic
`compute(batch, context)` method, publishes a `StatContract` stating its input-preservation,
grouping, summarization, rejection, mapping, geometry, and lowering policies, and normally selects
`StatLowering.Geom` to map its result onto an ordinary geom. There is no registry and no match
statement over stat implementations in the compiler. [Extending statistics](../extending/stats.md)
walks through one.

The numerical commitments behind these statistics — finite-input rules, histogram closure and mass,
KDE normalization and strategy — are specified in the
[numerical standards guide](../numerical-standards.md). The quantile-summary and ECDF contracts have
their own page in [common statistical layers](../common-statistics.md).

## Next

- [Facets and coordinates](04-facets-and-coords.md) — the same statistics, computed per panel.
- [The gallery](../gallery.md) — each of these layers rendered, with its source beside it.
