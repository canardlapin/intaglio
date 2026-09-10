# Extending statistics

A statistic is a typed transformation from a layer's input rows to output rows, run between mapping
resolution and scale training. It is not a flag a geom interprets: the compiler calls your
implementation directly and has no registry, no match statement over built-in stats, and no fallback
cast.

## What you implement

`Stat[-Row]` has three members:

- `def label: String` — appears in `TrainedLayer.geom`/`stat` summaries and in error messages.
- `def contract: StatContract` — the public behavioural declaration, described below.
- `def compute[Input <: Row](batch: StatBatch[Input], context: StatContext): Either[StatError, StatResult[Input]]`

`compute` is polymorphic in the current input subtype so your exact output-row type stays attached to
your exact output mapping. Declare the narrower return type `StatResult.Aux[Input, YourRow[Input]]`
in the override; it is a subtype of the declared result and it is what keeps the mapping's accessors
total.

You also define an output row. `StatRow[+Row]` requires `source` (a stable representative), `members`
(every contributing input), `category`, and `kind`. Required statistic outputs are ordinary fields on
your subtype, not optional entries in a shared record — `row.computed` derives a generic
`ComputedValues` view from the typed row for inspection, and never the other way round.

`StatBatch` supplies `inputs` (each with its stable index), `rows`, `size`, the effective input
`mapping`, and `evaluate(aesthetic, accessor)`. Use `evaluate` rather than calling an accessor
directly: it catches a non-fatal failure and returns `StatError.MappingEvaluationFailed` carrying the
aesthetic name, the original row index, and the mapping contract that was violated.

`StatContext` gives `layerIndex`, the selected `geom`, and a `StatScope` — `Plot` or
`Facet(cell)` — so a statistic can tell plot-level execution from a per-panel batch.

`StatContract` is seven independent declarations, all inspectable before compilation:
`inputPreservation` (`OneToOne`, `AggregateMembers`, `WholeBatch`, `Custom`), `grouping`,
`summarization`, `rejection` (`FailBatch` or `Custom`), `mapping` (`Preserve` contramaps the input
mapping over one-to-one outputs, `Replace` requires an empty input mapping and owns the output
mapping, `Consume` inspects the input mapping first), `geometry` (`Any` or `Require(geom)`), and
`lowering`. An external statistic normally selects `StatLowering.Geom` and maps its result to an
ordinary geom; `Summary`, `Density`, and `Ecdf` select existing built-in lowering paths.

Failures are `StatError` values — `MappingEvaluationFailed`, `NonFiniteInput`, `InsufficientData`,
`InputOutsideBins`, `UnsupportedStrategy`, `Rejected`. The compiler adds layer provenance when it
translates them into `GraphicsError`.

## A worked statistic

```scala mdoc:compile-only
import intaglio.*

final case class Trial(time: Double, amplitude: Double)

/** One output per input: the amplitude expressed in batch standard deviations. */
final case class ZScored[+Row](
    source: Row,
    members: Vector[Row],
    position: Double,
    z: Double
) extends StatRow[Row]:
  val category: Option[String] = None
  val kind: String = "z-score"

final case class ZScore[Row](x: Row => Double, y: Row => Double) extends Stat[Row]:
  val label: String = "z-score"

  val contract: StatContract =
    StatContract(
      StatInputPreservation.OneToOne,
      StatGroupingPolicy.WholeBatch,
      StatSummarizationPolicy.Custom("centre and scale by the batch mean and sample SD"),
      StatRejectionPolicy.FailBatch,
      StatMappingPolicy.Replace,
      StatGeometryPolicy.Require(Geom.Point),
      StatLowering.Geom
    )

  def compute[Input <: Row](
      batch: StatBatch[Input],
      context: StatContext
  ): Either[StatError, StatResult.Aux[Input, ZScored[Input]]] =
    if batch.size < 2 then Left(StatError.InsufficientData(2, batch.size))
    else
      batch.evaluate("y", y) match
        case Left(error) => Left(error)
        case Right(ys)   =>
          val mean = ys.sum / ys.length.toDouble
          val variance =
            ys.map(value => (value - mean) * (value - mean)).sum / (ys.length - 1).toDouble
          val sd = math.sqrt(variance)
          if !sd.isFinite || sd <= 0.0 then Left(StatError.Rejected("y has no finite spread"))
          else
            batch.evaluate("x", x) match
              case Left(error) => Left(error)
              case Right(xs)   =>
                val rows = batch.rows.indices.toVector.map { index =>
                  ZScored(
                    source = batch.rows(index),
                    members = Vector(batch.rows(index)),
                    position = xs(index),
                    z = (ys(index) - mean) / sd
                  )
                }
                Right(
                  StatResult(
                    rows,
                    AesSpec[ZScored[Input]](
                      x = Some(AesValue.total(_.position)),
                      y = Some(AesValue.total(_.z))
                    )
                  )
                )
```

Because `members` is `Vector(row)` and the contract declares `OneToOne`, the compiler's provenance
machinery can link every output back to its input under `ProvenancePolicy.SourceIndices` and
`Full` — see [ADR 0007](../adr/0007-provenance-is-a-compiler-policy.md). A statistic that generates
outputs with no input correspondence should declare that honestly rather than fabricating members;
`StatisticProvenance.hasCompleteSourceIndices` will then report `false`, which is the intended
signal.

## Laws to run

The kits live in `modules/laws/shared/src/main/scala/intaglio/laws/`. The consumer court in
`modules/laws/shared/src/test/scala/external/laws/` runs them through the public API only.

- **`StatLaws(stat, successfulBatch, context)(observe)`** (`ExtensionLaws.scala`) — four laws.
  *Stable public contract* requires a non-blank `label` and a `contract` that does not change between
  reads. *Successful fixture* requires your batch to be accepted. *Deterministic computation* runs
  `compute` twice and compares `observe(result)`. *Declared input preservation* checks the claim in
  `contract.inputPreservation` against the rows you actually returned: `OneToOne` requires one output
  per input with `members == Vector(source)` and matching input multiplicities; `AggregateMembers`
  requires non-empty members drawn from the batch and including the source; `WholeBatch` requires
  every output to retain the complete input batch. `Custom` is not checked — declaring it opts out.

  `observe` projects a `StatResult` to something comparable; `StatLaws.withEquality` takes an
  explicit comparison when that projection has no useful `==`.
- **`NativeStatLaws(seeds)`** (`StatPositionLaws.scala`) — the court for Intaglio's *own* statistics,
  not for yours. It checks count and bin mass conservation, right-closed bin intervals, summary
  bounds, density integration, contour topology, and declared-order invariance across
  `SeededLaw.defaultSeeds`. Run it when your statistic composes a built-in one, or as a regression
  court for the platform your output rows and lowering paths inherit. It takes only a seed vector;
  every counterexample carries `seed=<value>` in its `LawFailure.detail`, so a failure replays.

```scala mdoc:compile-only
import intaglio.*
import intaglio.laws.*

final case class Reading(t: Double, v: Double)

final case class Recentred[+Row](source: Row, members: Vector[Row], t: Double, centred: Double)
    extends StatRow[Row]:
  val category: Option[String] = None
  val kind: String = "recentred"

final case class Recentre[Row](t: Row => Double, v: Row => Double) extends Stat[Row]:
  val label: String = "recentre"

  val contract: StatContract =
    StatContract(
      StatInputPreservation.OneToOne,
      StatGroupingPolicy.WholeBatch,
      StatSummarizationPolicy.Custom("subtract the batch mean"),
      StatRejectionPolicy.FailBatch,
      StatMappingPolicy.Replace,
      StatGeometryPolicy.Require(Geom.Point),
      StatLowering.Geom
    )

  def compute[Input <: Row](
      batch: StatBatch[Input],
      context: StatContext
  ): Either[StatError, StatResult.Aux[Input, Recentred[Input]]] =
    if batch.isEmpty then Left(StatError.Rejected("recentring requires observations"))
    else
      batch.evaluate("y", v) match
        case Left(error) => Left(error)
        case Right(values) =>
          val mean = values.sum / values.length.toDouble
          batch.evaluate("x", t) match
            case Left(error)     => Left(error)
            case Right(position) =>
              val rows = batch.rows.indices.toVector.map { index =>
                Recentred(
                  source = batch.rows(index),
                  members = Vector(batch.rows(index)),
                  t = position(index),
                  centred = values(index) - mean
                )
              }
              Right(
                StatResult(
                  rows,
                  AesSpec[Recentred[Input]](
                    x = Some(AesValue.total(_.t)),
                    y = Some(AesValue.total(_.centred))
                  )
                )
              )

val readings: Vector[Reading] =
  Vector(Reading(0.0, 1.0), Reading(1.0, 3.0), Reading(2.0, 2.0))

val statSuite: LawSuite =
  StatLaws(
    Recentre[Reading](_.t, _.v),
    StatBatch(readings, AesSpec.empty[Reading]),
    StatContext(0, Geom.Point, StatScope.Plot)
  )(result => result.rows.map(row => (row.kind, row.members.length)))

val nativeSuite: LawSuite =
  NativeStatLaws()

val failures: Vector[LawFailure] =
  Vector(statSuite, nativeSuite).flatMap(_.failures)
```

`StatMappingPolicy.Replace` is why the fixture uses `AesSpec.empty[Reading]`: the statistic owns its
own output mapping and the layer must not also map `x` and `y`. A statistic that wants to inherit the
layer's mapping declares `Preserve` and lets the compiler contramap it over one-to-one outputs.
