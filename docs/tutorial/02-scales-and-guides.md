# Scales and guides

A scale is the relation between a column of your data and a visual channel. A guide — an axis, a
legend, a colorbar — is that same relation drawn so a reader can invert it. In Intaglio they are one
object read twice: guides are derived from the trained scale registry, so a mark and its axis cannot
disagree.

Everything on this page works on one fixture.

```scala mdoc:silent
import intaglio.*

final case class Trial(dose: Double, response: Double, arm: String, weight: Double)

val trials: Vector[Trial] =
  Vector(
    Trial(1.0, 0.42, "control", 0.8),
    Trial(10.0, 0.61, "control", 1.4),
    Trial(100.0, 0.88, "control", 2.1),
    Trial(1.0, 0.55, "treated", 1.1),
    Trial(10.0, 0.94, "treated", 1.9),
    Trial(100.0, 1.42, "treated", 2.8)
  )
```

## Positions are trained without a scale object

`aes(x, y)` stores positions as direct mappings. The panel range is then trained straight from the
raw values across every layer, and no scale is installed in the registry — a plain `aes` plot has an
empty `trainedScales`. That is enough for an axis, and it is why the shortest useful plot needs no
scale call at all.

Name a position scale when you want a transform, an out-of-bounds policy, or an explicit name. Doing
so also puts a real `ContinuousScale` into the registry.

```scala mdoc:silent
val named =
  plot(trials)
    .aes(_.dose, _.response)
    .scaleXContinuous(name = "Dose (mg)")
    .scaleYContinuous(name = "Response")
    .geomPoint()
```

A derived axis takes its title from the scale name, so the two lines above are also the axis titles.
`axisTitles` overrides them without touching the scale:

```scala mdoc
named
  .axisTitles("Dose", "Normalised response")
  .resolve
  .map(_.trainedScales.map(scale => scale.aesthetic -> scale.descriptor.name.value))
```

The scale names survive the override, because the titles are `PlotLabels` and the names belong to the
scales.

Training is plot-global. Every layer bound to an aesthetic contributes observations to one scale
before any row is mapped, so two layers over the same data share one coordinate language. Reusing a
declaration across layers is the mechanism; conflicting declarations are a typed error rather than
silent per-layer normalization.

## Transforms

`scaleXContinuous` and `scaleYContinuous` take a `Transform`. There are exactly four built-ins:
`Transform.identity`, `Transform.reverse`, `Transform.log10`, and `Transform.sqrt`.

```scala mdoc:silent
val logDose =
  plot(trials)
    .aes(_.dose, _.response)
    .scaleXContinuous(name = "Dose (mg)", transform = Transform.log10)
    .geomPoint()
    .geomLine()
```

A transform carries its own domain, breaks, and labeler. `Transform.log10`'s domain is the open
interval `(0, ∞)` and its breaks are `Breaks.log10`; `Transform.sqrt`'s domain is `[0, ∞)`. A value
outside the domain is a typed failure, never a silently substituted `NaN`.

The distinction that matters when you read a plot: a continuous scale keeps *both* domains. Palette
mapping and panel geometry use transformed coordinates; breaks and labels stay in the raw data
domain. A log axis is therefore labelled 1, 10, 100 — not 0, 1, 2.

```scala mdoc
logDose.resolve.map(_.trainedScales.map(scale => scale.aesthetic -> scale.descriptor.domain))
```

Writing your own transform is `Transform(name, forward, backward, domain, breaks, labeler)`, which
returns an `Either`. Round-tripping through the inverse within tolerance is a law
(`ScaleTransformLaws` in `intaglio-laws`), not a convention.

## Out-of-bounds

`OobPolicy` decides what happens to a value that falls outside the trained domain *after* rescaling
to the unit interval. It has three cases and they are exhaustive:

| Policy | Effect on a rescaled value `v` |
|---|---|
| `Censor` (the default) | keeps `v` when `0 ≤ v ≤ 1`, otherwise the value is unmapped and the row is dropped |
| `Squish` | clamps to `max(0, min(1, v))` |
| `Keep` | passes `v` through unchanged, so the palette is asked for an out-of-range position |

`Censor` is what you want with fixed limits; `Squish` is what you want when out-of-limit marks should
pile up at the edge rather than vanish.

## Limits

Limits are a *fixed domain*, and Intaglio makes that an explicit contract rather than an argument
that silently disables training. Build the scale with `ContinuousScale.fixed` and `encode` it:

```scala mdoc:silent
val fixedResponse =
  for
    yScale <- ContinuousScale.fixed("response", Vector(0.0, 1.0), Palette.numeric)
    trained <- plot(trials)
      .aes(_.dose, _.response)
      .encode(Aesthetic.Y, _.response, yScale)
      .geomPoint()
      .resolve
  yield trained
```

```scala mdoc
fixedResponse.map(_.droppedRows.map(_.reason))
```

`ContinuousScale.fixed` records `ScaleTraining.Fixed`; a later training pass cannot widen it. The
`encode` overloads for `Aesthetic.X` and `Aesthetic.Y` establish the same compile-time position
prerequisite as `aes`, so `geomPoint` stays callable and a plot can be built entirely from encoded
scales with no `aes` call at all.

`ContinuousScale.train` is the counterpart when you want a concrete trained scale over values you
already have, and `ContinuousScaleSpec.numeric(name, transform, oob)` is the row-free *declaration*
whose domain the compiler fills in. A spec never evaluates a row: its domain is
`ScaleDomain.Unspecified` until compilation.

## Breaks and labels

`Breaks` produces tick positions from an interval.

| Constructor | Behaviour |
|---|---|
| `Breaks.pretty(targetCount)` | zero-anchored 1/2/5 grid, *approximately* `targetCount` ticks. The default is `pretty(5)` |
| `Breaks.count(n)` | exactly `n` equally spaced ticks |
| `Breaks.width(width, offset)` | a fixed step |
| `Breaks.log10` | decade ticks; the default for `Transform.log10` |

`Breaks.pretty` is deliberate about determinism: it avoids `log10` and `Double.toString` so JVM and
Scala.js emit the same ticks and the same label strings. `Labeler.default` is the matching
deterministic number format. Reach for `Breaks.count` when an exact tick count is part of your
contract, because `pretty` will not honour one.

Attach them through a guide override rather than through the scale:

```scala mdoc:silent
val threeTicks =
  plot(trials)
    .aes(_.dose, _.response)
    .geomPoint()
    .guides(
      GuidePolicy.Derived(
        overrides = Vector(GuideSpec.Axis(AxisSide.Bottom, breaks = Breaks.countUnsafe(3)))
      )
    )
```

```scala mdoc
threeTicks.resolve.map(
  _.guides.collect { case ResolvedGuide(axis: GuideSpec.Axis, _) => axis.side }
)
```

## Discrete scales and legends

`scaleColorDiscrete` and `scaleFillDiscrete` map a `Row => String` onto a palette and cause a legend
to be derived.

```scala mdoc:silent
val byArm =
  plot(trials)
    .aes(_.dose, _.response)
    .group(_.arm)
    .scaleColorDiscrete(_.arm, levels = Vector("control", "treated"), name = "arm")
    .geomPoint()
    .geomLine()
```

`levels` fixes the order — and therefore the palette assignment and the legend order. Omit it and
levels are taken in first-appearance order.

`colors` defaults to `ThemePalette.Default`, meaning "resolve from the theme at compile time". The
default theme's discrete palette has **six** colours, and the default `PaletteOverflowPolicy` is
`Reject`, so a seventh level is a compile-time-of-the-plot error rather than a quietly reused colour:

```scala mdoc:silent
val sevenGroups = Vector.tabulate(7)(i => Trial(i.toDouble, i.toDouble, s"g$i", 1.0))
```

```scala mdoc
plot(sevenGroups)
  .aes(_.dose, _.response)
  .scaleColorDiscrete(_.arm)
  .geomPoint()
  .resolve
  .left
  .map(_.message)
```

Two ways out, both explicit: pass a longer `colors` vector, or opt into `PaletteOverflowPolicy.Cycle`.

```scala mdoc:silent
val cycled =
  plot(sevenGroups)
    .aes(_.dose, _.response)
    .scaleColorDiscrete(
      _.arm,
      colors = Vector(Rgba.unsafe(31, 119, 180), Rgba.unsafe(255, 127, 14)),
      overflow = PaletteOverflowPolicy.Cycle
    )
    .geomPoint()
```

An explicit palette that maps two levels to the same RGBA is not silently accepted as
distinguishable: `trained.accessibilityDiagnostics` reports it as
`AccessibilityDiagnostic.AmbiguousPalette`. Grouping is unaffected, because it is computed from raw
pre-palette categories, not from the resolved colours.

## Continuous fill and colorbars

`scaleFillContinuous` derives a colorbar instead of a legend. Its ticks follow the scale's transform,
and both are lowered to ordinary portable grobs — there is no backend colorbar feature.

```scala mdoc:silent
val weighted =
  plot(trials)
    .aes(_.dose, _.response)
    .scaleFillContinuous(_.weight, name = "weight")
    .geomPoint()
```

```scala mdoc
weighted.resolve.map(_.guides.flatMap(_.grob.name.map(_.value)))
```

`palette` also defaults to `ThemePalette.Default`, which resolves to
`Palette.gradient(theme.palettes.continuousLow, theme.palettes.continuousHigh)`. Supply
`Palette.gradient(from, to)` for your own two-point ramp, or any `Palette[Rgba]` for something else.

## Guide policy

`GuidePolicy` has three cases.

| Case | Meaning |
|---|---|
| `GuidePolicy.NoGuides` | no axes, no legends. A layout is then optional |
| `GuidePolicy.Explicit(specs)` | exactly these specs and nothing else |
| `GuidePolicy.Derived(overrides, deriveLegends)` | routine axes from trained position scales, legends from discrete colour/fill scales, colorbars from continuous fill |

The plotting DSL starts at `GuidePolicy.Derived()`. The bare core compiler starts at
`GuidePolicy.NoGuides` — which is why `PlotCompiler.compile(plot)` on a hand-built `Plot` draws marks
and nothing else.

Under `Derived`, an override axis on a side suppresses the derived axis on that side, and any
explicit legend suppresses derived legends. Overrides are always included.
`deriveLegends = false` keeps derived axes and drops derived legends.

`GuideSpec.Axis` carries `side`, `breaks`, `labeler`, optional explicit `ticks`, tick length, label
offset, per-part `GraphicParams`, a title, and a name. `GuideSpec.Legend` carries a title and a
vector of `LegendEntry` values; `GuideSpec.Colorbar` carries a title, a colour vector, and
`AxisTick` values. Building one by hand:

```scala mdoc:silent
val handBuilt =
  plot(trials)
    .aes(_.dose, _.response)
    .geomPoint()
    .guides(
      GuidePolicy.Explicit(
        Vector(
          GuideSpec.Axis(AxisSide.Bottom, title = Some("Dose (mg)")),
          GuideSpec.Legend(
            title = Some("arm"),
            entries = Vector(
              LegendEntry.colorUnsafe("control", Rgba.unsafe(31, 119, 180)),
              LegendEntry.colorUnsafe("treated", Rgba.unsafe(255, 127, 14))
            )
          )
        )
      )
    )
```

An empty legend or colorbar fails at lowering with `GraphicsError.EmptyGeometry` rather than
producing an invisible guide.

## Labels

Plot text is structural data, not decoration. `PlotLabels` holds title, subtitle, and the two axis
titles; the layout solver sizes dedicated regions for them and lowering emits ordinary text grobs.

```scala mdoc:silent
val labelled =
  plot(trials)
    .aes(_.dose, _.response)
    .geomPoint()
    .labels(
      PlotLabels(
        title = Some("Dose-response"),
        subtitle = Some("Two arms, log dose"),
        x = Some("Dose (mg)"),
        y = Some("Response")
      )
    )
```

`title`, `subtitle`, and `axisTitles` are shorthands over the same value.

## Inspecting the result

A trained plot exposes its scale registry before anything is drawn.

```scala mdoc
byArm.resolve.map(_.scaleRegistry.forAesthetic(Aesthetic.Color).map(_.descriptor.domain))
```

`trainedScales` lists every *scaled* aesthetic's descriptor and domain — positions bound only by
`aes` are absent, because they were never given a scale; `scaleRegistry.forAesthetic(key)` looks
one up; `droppedRows` reports rows a renderer would have to skip, each carrying a typed
`PlotDropReason` rather than being discarded silently. `textSummary` prints all of it — layers, geoms,
row counts, and trained domains — as one human-readable block, and `accessibilityDiagnostics` reports
palette collisions.

## Next

- [Statistics](03-statistics.md) — layers that compute before they draw.
- [Themes and styling](05-themes-and-styling.md) — where the default palette comes from.
- [Date, time, and zoom](../date-time-and-zoom.md) — typed temporal scales and post-statistical
  coordinate windows.
