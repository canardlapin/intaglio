# Date/time scales and coordinate zoom

Intaglio treats calendar dates and UTC instants as typed scale inputs. They are not preformatted
strings or caller-converted doubles: the compiler trains the temporal domain, maps values into panel
space, derives calendar-aligned breaks, and retains the original scale kind in inspection and
accessibility metadata.

`CalendarDate` is Intaglio's dependency-free proleptic-Gregorian date value. `UtcDateTime` is its
exact-millisecond UTC value. Both use the same integer calendar arithmetic on the JVM and Scala.js;
invalid dates and non-millisecond timestamp text fail through `GraphicsError` instead of being
rounded. Default labels are ISO dates or UTC timestamps with an explicit three-digit millisecond
field.

```scala
import intaglio.*

final case class Visit(day: CalendarDate, score: Double)

val yScale = ContinuousScaleSpec.numeric("score").orThrow

val program = plot(visits)
  .scaleXDate(
    _.day,
    name = "visit date",
    breaks = TemporalBreaks.everyUnsafe(1, TemporalUnit.Month)
  )
  .encode(Aesthetic.Y, _.score, yScale)
  .geomLine()
  .build
```

`scaleXDateTime` and `scaleYDateTime` provide the same path for `UtcDateTime`. The lower-level
`DateScaleSpec`, `DateTimeScaleSpec`, `DateScale`, and `DateTimeScale` APIs remain available for
ordinary `ScaleBinding` or generic `encode` use.

Automatic breaks choose a deterministic unit and integer step from milliseconds through years.
Explicit `TemporalBreaks.every` supports calendar-aware month and year stepping rather than treating
them as fixed-duration approximations. Date-only scales accept day, week, month, and year units.
Trained scales expose `breaksResult`, `labelsResult`, `mapValue`, and `inverse`; every accepted input
round-trips exactly at the documented precision.

## Limits are not zoom

Scale limits and coordinate zoom act at different contracts:

| Operation | When it acts | Changes trained scale | Can censor mapped rows | Changes statistical input |
| --- | --- | --- | --- | --- |
| `ContinuousScale.fixed`, `DateScale.fixed`, `DateTimeScale.fixed` | scale training and mapping | yes | yes, under `OobPolicy.Censor` | the statistic still runs first, but its resolved output must map through the fixed scale |
| `coordZoom` / `Coord.zoom` | after statistics and scale training | no | no | no |

A coordinate window replaces only the panel range. Computed rows, statistic membership, grobs,
trained domains, and provenance remain unchanged; the panel's explicit clipping policy determines
what is visible. A requested axis is exact and is not padded by ordinary range expansion. An axis
without a requested window keeps the configured expansion policy.

Numeric zoom bounds are raw data-space values. If the position is unscaled, they are already native
coordinates; if it has a trained continuous scale, Intaglio maps both bounds through that scale.

```scala
val zoomed = plot(observations)
  .aes(_.time, _.signal)
  .coordZoom(x = Some(Interval.unsafe(20.0, 40.0)))
  .geomPoint()
  .build
```

Typed temporal windows use the same post-stat path:

```scala
val window = CoordinateWindow.dateUnsafe(
  CalendarDate.parseUnsafe("2024-03-01"),
  CalendarDate.parseUnsafe("2024-06-30")
)

val zoomed = plot(visits)
  .scaleXDate(_.day, name = "visit date")
  .encode(Aesthetic.Y, _.score, yScale)
  .coordZoomWindows(x = Some(window), clip = Clip.On)
  .geomPoint()
  .build
```

`CoordinateWindow.dateTimeUnsafe` accepts `UtcDateTime` bounds. Window kinds must match the trained
position scale, and rejected mappings are typed errors. Bounds outside a trained domain follow that
scale's explicit `OobPolicy`: `Censor` rejects them, `Squish` clamps them, and `Keep` extrapolates.
`Clip.On` is the default, but clipping stays explicit and renderer-neutral for SVG, Canvas, Java2D,
and JavaFX.
