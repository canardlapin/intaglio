# Extending plot recipes

A `PlotRecipe` converts a domain value into a `PlotSpec`. It is the seam that lets a scientific model
type stay independent of Intaglio: the model does not inherit from an Intaglio type, does not carry
plotting concerns, and does not know how it will be rendered. The recipe lives beside the model and
is selected by ordinary `given` resolution — there is no registry, no implicit conversion, and no
runtime fallback. A missing or ambiguous recipe is a compile error.

## What you implement

```
trait PlotRecipe[-Source]:
  type Row
  def apply(source: Source): Either[GraphicsError, PlotSpec[Row]]
```

The associated `Row` member is the row type the recipe extracts. Keeping it as a type member rather
than a second parameter means `Source` stays contravariant and callers never spell it, while
`PlotRecipe.Aux[Source, Row]` names it where a signature needs to.

Two constructors cover the cases. `PlotRecipe.checked(convert)` takes
`Source => Either[GraphicsError, PlotSpec[Row]]` for a conversion that can reject an invalid source
value. `PlotRecipe.total(convert)` takes `Source => PlotSpec[Row]` when it cannot.

`PlotSpec[Row](plot, compilerOptions)` is the immutable, renderer-neutral compiler input: a `Plot`
plus its `PlotCompilerOptions`, with no backend object and no mutable registration state.
`PlotSpec.fromProgram` lifts the plotting DSL's `PlotProgram` into one, which is the usual way to
write the body. `PlotSpec` then exposes `resolve`, `scene`, `resolve(context)`, and
`renderPlan(context)`.

Call sites use the `toPlotSpec` extension, or `PlotRecipe(source)` explicitly.

## A worked recipe

```scala mdoc:compile-only
import intaglio.*

/** An oceanographic cast: an ordinary domain type that knows nothing about plotting. */
final case class Sample(depth: Double, temperature: Double)
final case class Cast(station: String, samples: Vector[Sample])

given castRecipe: PlotRecipe.Aux[Cast, Sample] =
  PlotRecipe.checked { source =>
    plot(source.samples)
      .aes(_.temperature, _.depth)
      .geomLine()
      .title(s"Station ${source.station}")
      .axisTitles("Temperature (C)", "Depth (m)")
      .build
      .map(PlotSpec.fromProgram)
  }

val cast: Cast =
  Cast("A1", Vector(Sample(0.0, 18.2), Sample(10.0, 14.9), Sample(20.0, 9.4)))

val spec = cast.toPlotSpec
val scene = spec.flatMap(_.scene)
```

Conversion and compilation are pure over immutable inputs, so converting the same source twice gives
the same specification and compiling it twice gives the same scene. That property is not incidental —
it is what `PlotRecipeLaws` checks, and it is what makes a recipe safe to call from a display hook
that may run more than once.

Recipes compose with the rest of the grammar. A recipe may set `compilerOptions` — a lean
`ProvenancePolicy` for a rendering path, a `GuidePolicy.Derived()` for a self-describing plot — and
those choices travel with the specification rather than being reapplied at every call site.

## Laws to run

- **`PlotRecipeLaws(source, recipe)(observe)`** (`ExtensionLaws.scala`) — four laws. The *successful
  fixture* law requires the recipe to accept your source value. The *deterministic conversion* law
  converts twice and compares `observe(spec)` for both. The *program bridge* law requires
  `PlotSpec.fromProgram(spec.program) == spec`, so a round trip through `PlotProgram` retains exactly
  the compiler input. The *deterministic scene* law compiles the resulting specification twice and
  requires equal scenes, which also proves the specification is compilable at all.

  `observe` projects a `PlotSpec` to something comparable — the extracted rows are usually enough.
  `PlotRecipeLaws.withEquality` takes an explicit comparison when that projection has no useful `==`.

```scala mdoc:compile-only
import intaglio.*
import intaglio.laws.*

final case class Reading(t: Double, v: Double)
final case class Series(name: String, readings: Vector[Reading])

given seriesRecipe: PlotRecipe.Aux[Series, Reading] =
  PlotRecipe.checked { source =>
    plot(source.readings)
      .aes(_.t, _.v)
      .geomLine()
      .title(source.name)
      .build
      .map(PlotSpec.fromProgram)
  }

val series: Series =
  Series("channel-1", Vector(Reading(0.0, 1.0), Reading(1.0, 3.0), Reading(2.0, 2.0)))

val recipeSuite: LawSuite =
  PlotRecipeLaws(series, summon[PlotRecipe.Aux[Series, Reading]])(spec => spec.plot.data)

val failures: Vector[LawFailure] =
  recipeSuite.failures
```

`summon[PlotRecipe.Aux[Series, Reading]]` rather than the named `given` is deliberate in a test: it
proves that resolution finds a recipe with the expected `Row`, which is the part a caller depends on.

A recipe whose conversion rejects some sources should also have a test for the rejection. The law kit
only exercises the successful fixture; the `Left` branch is yours to assert, and `GraphicsError`
carries the operands, so assert on the case rather than on its message text.
