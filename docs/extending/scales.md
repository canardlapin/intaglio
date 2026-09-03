# Extending scales

A scale encodes one typed input into one typed output. Everything around it is separable: a
`Transform` is the numeric reparameterisation, a `Palette` or `DiscretePalette` is the output ramp,
and a `CategoryIdentity` is how a category type is looked up and labelled. You can supply any of the
four independently.

## What you implement

`Scale[-In, +Out]` has two abstract members you must write and one you should override:

- `def name: GraphicsName` — the scale's identity, used by guides, descriptors, and diagnostics.
- `def mapValue(value: In): Option[Out]` — the encoding. `None` means the value is outside the
  scale's domain, and the compiler turns it into `DroppedRow(ScaleOutOfDomain)`.
- `def descriptor: ScaleDescriptor` — override this. The default reports `ScaleKind.Generic`,
  `ScaleDomain.Unspecified`, and `ScaleTraining.PlotWide`, and the last of those is a claim your
  scale cannot honour.

`mapValueResult` has a default that lifts `mapValue` into `Either[ScaleMapFailure, Out]`; override it
when you can say *why* a value was rejected.

An ecosystem `Scale` cannot participate in plot-wide training. `Scale#trainPlotWide`,
`Scale#trainFacet`, and `Scale#observation` are `private[intaglio]`, so your implementation inherits
the defaults, which return `this` and `None`. Declare `ScaleTraining.Fixed` in your descriptor — that
is the truth about your scale, and `FixedScaleLaws` checks the claim. If you need a domain that grows
with the data, compose `ContinuousScale`, `DiscreteScale`, or `BandScale` instead of implementing
`Scale` yourself. See [ADR 0003](../adr/0003-separate-scale-training-from-encoding.md).

```scala mdoc:compile-only
import intaglio.*

/** Position on a fixed reference interval, in the units the instrument reports. */
final case class ReferencePosition(name: GraphicsName, reference: Interval)
    extends Scale[Double, Double]:

  override val descriptor: ScaleDescriptor =
    ScaleDescriptor(
      name,
      ScaleKind.Continuous,
      ScaleDomain.Continuous(reference, reference),
      ScaleTraining.Fixed
    )

  def mapValue(value: Double): Option[Double] =
    mapValueResult(value).toOption

  override def mapValueResult(value: Double): Either[ScaleMapFailure, Double] =
    if !value.isFinite || !reference.contains(value) then
      Left(ScaleMapFailure.OutOfDomain(name.value, value.toString))
    else Right(reference.rescale(value))

val celsius: ReferencePosition =
  ReferencePosition(GraphicsName.unsafe("celsius"), Interval.unsafe(-40.0, 60.0))
```

## Transforms

`Transform` is a case class, not a trait: you build one with the checked constructor rather than
subclassing. It pairs `forward` and `backward` with an explicit `TransformDomain` whose bounds are
`DomainBound.Open` or `DomainBound.Closed`, plus the `Breaks` generator and `Labeler` the axis should
use.

`Transform.transform` refuses a value outside the domain before it evaluates `forward`, and it wraps
both functions so a throw becomes `GraphicsError.TransformEvaluationFailed` rather than escaping.
Scale training silently skips values the transform rejects.

```scala mdoc:compile-only
import intaglio.*

/** Log odds. Defined on the open interval (0, 1), so proportions of exactly 0 or 1 are refused. */
val logit: Either[GraphicsError, Transform] =
  for
    domain <- TransformDomain.open("logit", 0.0, 1.0)
    breaks <- Breaks.count(7)
    transform <- Transform(
      "logit",
      p => math.log(p / (1.0 - p)),
      z => 1.0 / (1.0 + math.exp(-z)),
      domain,
      breaks
    )
  yield transform
```

`Breaks` and `Labeler` are the two places a custom function still runs during axis derivation.
`Breaks.generate` wraps your `apply` and validates its output for finiteness, strict increase, and a
maximum of `Breaks.MaximumOutputSize` values, so a misbehaving generator becomes a typed error.
`Labeler` has no checked twin: an exception thrown from a labeler escapes through
`ContinuousScale.labels`. Keep labelers total.

## Palettes

`Palette[+A]` maps a normalized `Double` in `[0, 1]`. `DiscretePalette[+A]` maps `(index, count)` and
additionally publishes `capacity` and `overflowPolicy`. A finite palette that declares
`PaletteOverflowPolicy.Reject` is checked once, at `DiscreteScale` construction, through
`validateDomain`; if you build one by hand, `apply` is only defined for indices within capacity.

```scala mdoc:compile-only
import intaglio.*

val ramp: Vector[Rgba] =
  Vector(
    Rgba.unsafe(31, 119, 180),
    Rgba.unsafe(255, 127, 14),
    Rgba.unsafe(44, 160, 44)
  )

/** A finite categorical palette that cycles rather than refusing a fourth level. */
val cycling: DiscretePalette[Rgba] =
  new DiscretePalette[Rgba]:
    override val capacity: Option[Int] = Some(ramp.length)
    override val overflowPolicy: PaletteOverflowPolicy = PaletteOverflowPolicy.Cycle

    def apply(index: Int, count: Int): Rgba =
      ramp(index % ramp.length)

/** Continuous ramps interpolate in the transformed domain, so the endpoints are the extremes. */
val sequential: Palette[Rgba] =
  Palette.gradient(Rgba.unsafe(247, 251, 255), Rgba.unsafe(8, 48, 107))
```

`DiscretePalette.values(values, overflow)` covers the ordinary case and returns
`Either[GraphicsError, DiscretePalette[A]]`, refusing an empty vector. `DiscretePalette.indices` is
the zero-based ordinal palette used by discrete position output.

## Category identity

`DiscreteDomain[A]` and `BandScale[A]` keep your category type. They need a `CategoryIdentity[A]`
declaring the stable lookup key, the display label, and an `Ordering` on the key. `String` has a
built-in given; anything else supplies one once, beside the type.

```scala mdoc:compile-only
import intaglio.*

enum Arm(val code: Int):
  case Control extends Arm(10)
  case Treatment extends Arm(20)

given armIdentity: CategoryIdentity[Arm] =
  CategoryIdentity.by[Arm, Int](
    _.code,
    {
      case Arm.Control   => "control"
      case Arm.Treatment => "treatment"
    }
  )

val arms: Either[GraphicsError, DiscreteDomain[Arm]] =
  DiscreteDomain.ordered(Vector(Arm.Control, Arm.Treatment))

val positions: Either[GraphicsError, BandScale[Arm]] =
  arms.flatMap(domain => BandScale("arm", domain))
```

Lookup and grouping use the identity, guides use the label. Two categories that share a label stay
distinct.

## Laws to run

The kits live in `modules/laws/shared/src/main/scala/intaglio/laws/`. Each returns a `LawSuite`;
assert that `suite.failures` is empty in whatever test framework you use. The consumer court in
`modules/laws/shared/src/test/scala/external/laws/ScaleTransformLawsSuite.scala` runs all of these
through the public API only.

- **`ScaleLaws(scale, samples)`** (`ExtensionLaws.scala`) — non-empty fixture, a stable descriptor
  whose name matches the scale, deterministic mapping, and agreement between `mapValue` and
  `mapValueResult`. Use `ScaleLaws.withEquality` when `Out` has no useful `==`.
- **`TransformLaws(transform, validSamples, monotonicity, tolerance)`**
  (`ScaleTransformLaws.scala`) — round trip through the inverse, the declared
  `TransformMonotonicity` over the sorted fixture, and open/closed behaviour at the transform's own
  domain endpoints. The direction is a claim you make and the kit checks.
- **`OobPolicyLaws()`** — the normalized-coordinate contract shared by every continuous scale:
  endpoints are in bounds, `Censor` rejects outside `[0, 1]`, `Squish` clamps, `Keep` passes through.
  It takes no arguments and no fixture.
- **`ContinuousScaleTrainingLaws(batches, transform, tolerance)`** — incremental plot-wide training
  equals one-shot training over the concatenation, is invariant to permutation, and reaches the
  palette endpoints at the transformed-domain endpoints. Pass your `Transform` to exercise it under
  training.
- **`FixedScaleLaws(scale, laterValues)`** — the descriptor advertises `ScaleTraining.Fixed`, and
  later observations change neither the descriptor nor any mapping. **This kit needs training
  observations, which only built-in scales produce**, so it applies to `ContinuousScale.fixed`,
  `DiscreteScale.fixed`, `BandScale.fixed`, `DateScale.fixed`, and `DateTimeScale.fixed` — not to a
  custom `Scale`. For a custom scale, `ScaleLaws` plus a `Fixed` descriptor is the available court.
- **`DiscreteDomainLaws.ordered(declared, batches)`** / **`.unordered(declared, batches)`** —
  concatenation, plus encounter-order preservation for ordered domains or permutation invariance for
  unordered ones. Applicability is explicit: an ordered suite does not contain a permutation law, and
  the law names are inspectable through `suite.laws`.

```scala mdoc:compile-only
import intaglio.*
import intaglio.laws.*

final case class Reciprocal(name: GraphicsName) extends Scale[Double, Double]:
  override val descriptor: ScaleDescriptor =
    ScaleDescriptor(name, ScaleKind.Generic, ScaleDomain.Unspecified, ScaleTraining.Fixed)

  def mapValue(value: Double): Option[Double] =
    Option.when(value.isFinite && value != 0.0)(1.0 / value)

val scaleSuite: LawSuite =
  ScaleLaws(Reciprocal(GraphicsName.unsafe("reciprocal")), Vector(-2.0, 0.5, 0.0, Double.NaN))

val transformSuite: LawSuite =
  TransformLaws(Transform.sqrt, Vector(0.0, 1.0, 4.0, 100.0), TransformMonotonicity.Increasing)

val oobSuite: LawSuite =
  OobPolicyLaws()

val trainingSuite: LawSuite =
  ContinuousScaleTrainingLaws(
    Vector(Vector(2.0, Double.NaN, 8.0), Vector(-1.0), Vector(4.0, 16.0))
  )

val domainSuite: LawSuite =
  DiscreteDomainLaws.ordered(
    declared = Vector("control"),
    batches = Vector(Vector("treatment", "control"), Vector("washout"))
  )

val fixedSuite: Either[GraphicsError, LawSuite] =
  ContinuousScale
    .fixed("reference", Vector(0.0, 10.0), Palette.numeric)
    .map(scale => FixedScaleLaws(scale, Vector(-5.0, 5.0, 20.0)))

val failures: Vector[LawFailure] =
  Vector(scaleSuite, transformSuite, oobSuite, trainingSuite, domainSuite).flatMap(_.failures)
```

Law execution catches non-fatal exceptions from your code and reports them as `LawFailure` values
carrying the exception type and message, so a throwing scale fails a law instead of failing the test
runner.
