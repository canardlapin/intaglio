# Accessible plots and semantic identity

Intaglio carries accessibility information beside renderer-neutral graphics instead of hiding it in
one backend. A compiled plot owns a typed plot ID, one stable ID per logical layer, a compact datum
ID series, authored descriptive text, a deterministic plot summary, and palette diagnostics. The
same `SceneSemantics` value travels from `Scene` to `DeviceScene`, including when lean compilation
stores point marks as one columnar batch.

## Add domain context

Semantic IDs use a portable SVG/HTML subset: an ASCII letter or underscore followed by letters,
digits, underscores, hyphens, or periods.

```scala
val plot = Plot(observations)
  .withSemanticId(SemanticId.unsafe("memory-activation"))
  .withTitle("Memory-related activation")
  .withDescription("Regional estimates from the preregistered contrast.")
  .withAltText("Activation is strongest in left hippocampus; uncertainty overlaps zero elsewhere.")
  .addLayer(
    Layer
      .point[Observation](_.estimate, _.regionIndex)
      .withSemanticId(SemanticId.unsafe("regional-estimates"))
  )
```

`withAltText` is for the domain claim a generic plotting library cannot infer. It takes precedence
over the generic description in accessible renderers. A trained plot can also be revised without
recompiling geometry:

```scala
val revised = trained.withAltText("A domain expert's final interpretation.")
```

## Inspect before rendering

`trained.textSummary` reports every logical layer, its geom/stat and row counts, and every trained
scale with its domain. `trained.accessibilityDiagnostics` reports exact palette collisions. For
example, a two-level discrete color scale that maps both levels to the same RGBA value emits
`AccessibilityDiagnostic.AmbiguousPalette`; it is never silently treated as distinguishable.

Datum IDs are represented by `DatumIdSeries`, not a `String` per mark. `valueAt(i)` deterministically
returns `<layer-id>-datum-<i>`, preserving inspectability without defeating batch memory budgets.

## Choosing a colour ramp

A ramp encodes one of two different things, and picking the wrong kind is a correctness problem
rather than a taste problem.

**Sequential** is for data with one meaningful end: a count, a distance, a duration, a magnitude
that is already unsigned. Lightness runs monotonically from one end to the other, so the ramp still
reads in greyscale and in a photocopy. `Palette.oklabGradient(from, to)` builds one.

**Diverging** is for data centred on a value that means something: a difference, a contrast, a
z-score, a log ratio, anything centred on zero. Lightness falls away from a light neutral in both
directions and hue carries the sign. `DivergingPalette` builds one, and its domain is signed
rather than normalized, so `-1` is the negative endpoint, `0` is exactly the neutral, and `+1` is
the positive endpoint:

```scala mdoc:compile-only
import intaglio.*

val palette = DivergingPalette.BlueRust

/** Scene colour for a signed fraction; the sign of the argument names the arm. */
val below: Rgba = palette.color(-0.6)
val above: Rgba = palette.color(0.6)

/** The raster form is the scene form packed, so an overlay and a plot of the same value agree. */
val pixel: Rgba32 = palette.pixel(0.6)

/** Signed data, symmetric window, zero on the neutral by construction. */
val overlay = DivergingColorizer.make(limit = 4.0, palette = palette)
```

Using a sequential ramp for signed data hides the sign; using a diverging ramp for unsigned data
invents a midpoint the data does not have. `DivergingColorizer` will not let the neutral drift off
zero: it takes a magnitude rather than a window, and re-windowing it widens to the enclosing
symmetric window instead of moving the middle.

### Colour vision deficiency

Intaglio simulates dichromat vision with the two-half-plane construction of Brettel, Viénot and
Mollon (1997), through `ColorVision`. `ColorSeparation.between` reports CIE76 distance as a named
observer sees it, and `ColorSeparation.closest` finds the closest pair in a set:

```scala mdoc:compile-only
import intaglio.*

val series: Vector[Rgba] = DiscretePalette.okabeItoColors.take(4)

/** The two colours a protanope would find hardest to tell apart, and how far apart they are. */
val worst: Option[ColorSeparation] =
  ColorSeparation.closest(series, ColorVision.Protanopia)
```

`ColorVision` models *dichromacy*, the severe form: protanopia, deuteranopia, tritanopia. Anomalous
trichromacy — protanomaly, deuteranomaly, tritanomaly, together far more common — retains partial
discrimination and is not modelled. Read these figures as the worst case, not as what most
colour-deficient readers see. A simulation is a model of a measurement, not a measurement: it says
what a colour reduces to for a standard observer, not whether a given reader can use a given figure.

`DivergingPalette.BlueRust` is the diverging palette to reach for when you have no reason to prefer
another. At matched magnitude `|t| >= 0.25` its two arms stay at least CIE76 18.8 apart under every
observer modelled — 23.3 normal, 19.1 protanopia, 22.4 deuteranopia, 18.8 tritanopia — and lightness
never reverses outward along either arm. A blue-to-red version of the same ramp falls to 16.1 under
protanopia; red is better under tritanopia, at 23.5 against 18.8, which is why the comparison is
made on the worst observer rather than a favourite one.

Red-to-green diverging pairs are the common default and the worst choice: red-green is exactly the
axis protanopia and deuteranopia collapse. Those two dichromacies affect roughly one man in fifty;
roughly one man in twelve has some red-green deficiency, and the milder anomalous forms lose margin
on the same axis rather than all of it. Blue-to-rust keeps its separation because it also moves
along the blue-yellow axis, which those two observers retain. Even so, no diverging ramp recovers
its sign in greyscale — both arms darken away from the neutral — so a signed legend and a stated
convention in the caption are part of the figure, not decoration.

For categorical series, `DiscretePalette.okabeIto` is the qualitative counterpart: the eight-colour
palette of Okabe and Ito, reordered so that the first *n* colours are the best *n*. The smallest
separation among the first *n*, taken as the worst of the four observers:

| n | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|
| `DiscretePalette.okabeIto` | 79.2 | 66.1 | 23.5 | 19.4 | 17.0 | 11.2 | 10.9 |
| the first six tab10 colours, replaced | 85.3 | 5.6 | 5.6 | 5.6 | 5.6 | — | — |

The default theme palette is the first six of these, so a plot that does not choose its own colours
gets the measured ones. It was the first six tab10 colours, which the second row measures: from
three series on, protanopia brought tab10's orange and its green to CIE76 5.6, and deuteranopia
brought its blue and its purple to 7.2 — both below the floor below. Existing plots change colour;
[the changelog](../CHANGELOG.md) says so and names the one-line way back.

Take all eight when a plot needs more than six series:

```scala mdoc:compile-only
import intaglio.*

val eightSeries =
  Theme.default.copy(
    palettes = Theme.default.palettes.copy(discrete = DiscretePalette.okabeItoColors)
  )
```

### The compiler tells you

`trained.accessibilityDiagnostics` reports both kinds of palette failure. Two levels mapping to the
same RGBA is `AccessibilityDiagnostic.AmbiguousPalette`; two levels that are distinct as bytes but
closer than `ColorSeparation.SeriesFloor` for some observer is
`AccessibilityDiagnostic.IndistinguishablePalette`, which names the observer, the two levels, and
the separation:

```
Diagnostic indistinguishable-palette: color scale 'condition-color' separates levels 1 and 2 by
only dE76 5.6 under protanopia.
```

The floor is 10, which is a judgement rather than a standard. CIE76 around 2.3 is a just-noticeable
difference for two large patches abutting under controlled light; marks in a plot are small,
separated in space, on different backgrounds, and often compared from memory across a legend, so the
working margin has to be much wider.

Only discrete scales are checked. On a discrete scale two close colours mean two categories a reader
cannot separate, which is a defect; on a continuous ramp neighbouring samples are *supposed* to be
close, so the same measurement there would report the design rather than a fault.

The default palette clears the floor through all six of its colours, so the diagnostic is silent on
a plot that does not choose its own. It fires on one that chooses badly.

### Rainbow and jet are refused

Intaglio ships no rainbow, jet, or turbo ramp, and will not. Their lightness is not monotone: jet
peaks in the cyan and yellow bands and dips at green, so a reader sees boundaries where the data
has none and misses boundaries where it has some. The perceptual step size is wildly uneven, which
means equal data differences look unequal. And the sign of a value is unrecoverable once the ramp
has wrapped through the spectrum. A ramp whose job is to convey magnitude has to be monotone in
lightness to do that job; use `Palette.oklabGradient` for unsigned data and `DivergingPalette` for
signed data.

`Palette.gradient` interpolates the stored sRGB bytes directly and is kept for callers who want
exactly that. It is not the right default for a magnitude ramp between hue-distant endpoints:
interpolating `#0000FF` to `#FFFF00` in sRGB passes through `#808080`, a grey dead zone in the
middle of the scale, where `Palette.oklabGradient` holds chroma across the same path.

## Per-grob titles, descriptions, classes, and data attributes

Document-level text describes the whole plot. A single mark can carry its own words and hooks
through `Grob.annotated(child, meta)`, where `GrobMeta` holds an optional `title`, an optional
`description`, an optional checked `CssClass`, and an insertion-ordered vector of checked
`DataKey` / value pairs:

```scala
val meta = GrobMeta(
  title = Some("Recall unit 7: \"the lighthouse\""),
  description = Some("Decode-filled anchor, mass 0.42"),
  cssClass = Some(CssClass.unsafe("mark decode-filled")),
  data = Vector(DataKey.unsafe("kind") -> "anchor")
)
val plate = Scene(Vector(Grob.annotated(unitMark, meta)))
```

`CssClass` accepts one or more whitespace-separated ASCII identifier tokens
(`-?[A-Za-z_][A-Za-z0-9_-]*`) and normalises them to single-space separation. `DataKey` accepts
`[a-z][a-z0-9-]*` and refuses `name`, because `data-name` is reserved for a grob's `GraphicsName`
on every backend. Both return `Either[GraphicsError, ...]`; `Grob.annotated` itself is total.

The wrapper has no viewport and no name of its own, so name collection and depth-first traversal
are unchanged; its only child is the annotated grob. Device lowering delegates to the child and
records the metadata as `DeviceElement.Annotated`. The SVG backend emits it as a wrapping group:

```svg
<g class="mark decode-filled" data-kind="anchor">
  <title>Recall unit 7: "the lighthouse"</title>
  <desc>Decode-filled anchor, mass 0.42</desc>
  <circle data-name="unit-7" ... />
</g>
```

Every attribute value and text node is escaped; `<`, `&`, `"`, and `]]>` cannot break the
document. An absent title, description, class, or data vector emits nothing, and an empty
`GrobMeta` still wraps the child in a bare `<g>`. A scene without any annotated grob renders
byte-identically to the pre-annotation renderer, which `SvgAnnotationSuite` pins per conformance
case. XML-illegal characters in any annotation text and a repeated data key are typed
`SvgRenderError`s at the render boundary. Canvas, Java2D, JavaFX, and PDF draw the child exactly
as if the wrapper were absent; the renderer conformance contract includes an annotated case so
every backend proves that.

## SVG output

Rendering a compiled plot through `intaglio-svg` emits a root `id`, `role="img"`, ARIA
`labelledby`/`describedby` references, and escaped `<title>` and `<desc>` elements. Raw scenes can opt
in through `SvgOptions(title = ..., description = ...)`. XML-illegal title or description text fails
the checked SVG boundary instead of producing malformed markup.
