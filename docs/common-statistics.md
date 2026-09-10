# Common statistical layers

Intaglio provides two compact summaries for common distribution plots in addition to histograms,
density estimates, counts, and mean intervals.

`Layer.quantileSummary(x, y)` groups observations by numeric `x`, computes the median plus first and
third quartiles using Hyndman-Fan type 7, and lowers each group as one interval and one median point.
This is the ordinary renderer-neutral composition to use when a full box-and-whisker glyph is not
needed. The typed `StatRow.QuantileSummary` output retains every contributing row and exposes
`lowerQuartile`, `median`, `upperQuartile`, and `count` directly.

```scala
val layer = Layer.quantileSummary[Observation](_.time, _.response)
val plot = Plot(observations).addLayer(layer)
```

`Layer.ecdf(x, group = ...)` produces a right-continuous empirical cumulative distribution. Equal
values collapse into one step, each output row retains the tied observations, and cumulative mass
is computed independently within each explicit group. Renderer-neutral lowering inserts the
horizontal and vertical vertices of the step path and includes the zero baseline in the panel
range. Positions are sorted within a group; groups retain first-encounter order.

```scala
val layer = Layer.ecdf[Observation](_.response, group = Some(_.condition))
val plot = Plot(observations).addLayer(layer)
```

The plot DSL exposes the same operations as `geomQuantileSummary()` and `geomEcdf(group = ...)`.
Both statistics reject non-finite mapped values through `GraphicsError`, run identically on the JVM
and Scala.js, and keep the core free of platform or dataframe dependencies.
