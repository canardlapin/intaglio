package intaglio

import scala.util.control.NonFatal

final case class Interval private (lower: Double, upper: Double):
  require(lower.isFinite, "`lower` must be finite")
  require(upper.isFinite, "`upper` must be finite")
  require(lower <= upper, "`lower` must be <= `upper`")

  def width: Double =
    upper - lower

  def contains(value: Double): Boolean =
    value.isFinite && value >= lower && value <= upper

  def union(that: Interval): Interval =
    Interval.unsafe(math.min(lower, that.lower), math.max(upper, that.upper))

  def rescale(value: Double): Double =
    if width == 0.0 then 0.5
    else (value - lower) / width

object Interval:
  def apply(lower: Double, upper: Double): Either[GraphicsError, Interval] =
    if lower.isFinite && upper.isFinite && lower <= upper then Right(new Interval(lower, upper))
    else Left(GraphicsError.InvalidInterval(lower, upper))

  def unsafe(lower: Double, upper: Double): Interval =
    apply(lower, upper).orThrow

  def train(values: IterableOnce[Double]): Either[GraphicsError, Interval] =
    trainOption(values).toRight(GraphicsError.EmptyContinuousRange)

  def trainOption(values: IterableOnce[Double]): Option[Interval] =
    val finite = values.iterator.filter(_.isFinite)
    if !finite.hasNext then None
    else
      var lo = finite.next()
      var hi = lo
      while finite.hasNext do
        val x = finite.next()
        if x < lo then lo = x
        if x > hi then hi = x
      Some(unsafe(lo, hi))

final case class ContinuousRange private (interval: Option[Interval]):
  def isEmpty: Boolean =
    interval.isEmpty

  def train(values: IterableOnce[Double]): ContinuousRange =
    Interval.trainOption(values) match
      case None       => this
      case Some(next) => ContinuousRange(Some(interval.fold(next)(_.union(next))))

  def requireTrained: Either[GraphicsError, Interval] =
    interval.toRight(GraphicsError.EmptyContinuousRange)

object ContinuousRange:
  val empty: ContinuousRange =
    ContinuousRange(None)

  def from(values: IterableOnce[Double]): ContinuousRange =
    empty.train(values)

enum DomainBound(val value: Double):
  case Open(bound: Double) extends DomainBound(bound)
  case Closed(bound: Double) extends DomainBound(bound)

  def allowsLower(value: Double): Boolean =
    this match
      case Open(bound)   => value > bound
      case Closed(bound) => value >= bound

  def allowsUpper(value: Double): Boolean =
    this match
      case Open(bound)   => value < bound
      case Closed(bound) => value <= bound

final case class TransformDomain private (lower: DomainBound, upper: DomainBound):
  require(!lower.value.isNaN, "`lower` must not be NaN")
  require(!upper.value.isNaN, "`upper` must not be NaN")
  require(lower.value < upper.value, "`lower` must be < `upper`")

  def lowerValue: Double =
    lower.value

  def upperValue: Double =
    upper.value

  def contains(value: Double): Boolean =
    value.isFinite && lower.allowsLower(value) && upper.allowsUpper(value)

object TransformDomain:
  val all: TransformDomain =
    unsafe(
      "all",
      DomainBound.Open(Double.NegativeInfinity),
      DomainBound.Open(Double.PositiveInfinity)
    )

  def apply(name: String, lower: Double, upper: Double): Either[GraphicsError, TransformDomain] =
    closed(name, lower, upper)

  def closed(name: String, lower: Double, upper: Double): Either[GraphicsError, TransformDomain] =
    apply(name, DomainBound.Closed(lower), DomainBound.Closed(upper))

  def openClosed(
      name: String,
      lower: Double,
      upper: Double
  ): Either[GraphicsError, TransformDomain] =
    apply(name, DomainBound.Open(lower), DomainBound.Closed(upper))

  def closedOpen(
      name: String,
      lower: Double,
      upper: Double
  ): Either[GraphicsError, TransformDomain] =
    apply(name, DomainBound.Closed(lower), DomainBound.Open(upper))

  def open(name: String, lower: Double, upper: Double): Either[GraphicsError, TransformDomain] =
    apply(name, DomainBound.Open(lower), DomainBound.Open(upper))

  def apply(
      name: String,
      lower: DomainBound,
      upper: DomainBound
  ): Either[GraphicsError, TransformDomain] =
    if !lower.value.isNaN && !upper.value.isNaN && lower.value < upper.value then
      Right(new TransformDomain(lower, upper))
    else Left(GraphicsError.InvalidTransformDomain(name, lower.value, upper.value))

  def unsafe(name: String, lower: Double, upper: Double): TransformDomain =
    closed(name, lower, upper).orThrow

  def unsafe(name: String, lower: DomainBound, upper: DomainBound): TransformDomain =
    apply(name, lower, upper).orThrow

trait Breaks:
  def apply(range: Interval): Vector[Double]

  /** Checked generation for compiler and library code. The legacy `apply` method remains the
    * explicit throwing convenience boundary; built-in generators implement both methods from the
    * same bounded computation. Custom generators receive output validation by default.
    */
  def generate(range: Interval): Either[GraphicsError, Vector[Double]] =
    try Breaks.validateOutput("custom", apply(range))
    catch
      case NonFatal(error) =>
        val (exceptionType, detail) = GraphicsError.throwableDetails(error)
        Left(GraphicsError.BreakGenerationFailed("custom", exceptionType, detail))

object Breaks:
  /** No built-in break generator can emit more values than this. */
  val MaximumOutputSize: Int = 10000

  private val MaximumScaleIterations = 1024

  private abstract class CheckedBreaks(val generator: String) extends Breaks:
    protected def runChecked(range: Interval): Either[GraphicsError, Vector[Double]]

    final override def apply(range: Interval): Vector[Double] =
      runChecked(range).orThrow

    final override def generate(range: Interval): Either[GraphicsError, Vector[Double]] =
      runChecked(range)

  private def checked(
      generator: String
  )(run: Interval => Either[GraphicsError, Vector[Double]]): Breaks =
    new CheckedBreaks(generator):
      override protected def runChecked(
          range: Interval
      ): Either[GraphicsError, Vector[Double]] =
        run(range)

  def count(n: Int): Either[GraphicsError, Breaks] =
    if n < 1 then Left(GraphicsError.InvalidBreakCount(n))
    else if n > MaximumOutputSize then
      Left(GraphicsError.BreakOutputLimitExceeded("count", n, MaximumOutputSize))
    else
      Right(
        checked("count") { range =>
          val values =
            if n == 1 then Vector(midpoint(range))
            else
              Vector.tabulate(n) { index =>
                interpolate(range, index.toDouble / (n - 1).toDouble)
              }
          validateOutput("count", values)
        }
      )

  def countUnsafe(n: Int): Breaks =
    count(n).orThrow

  /** Deterministic 1/2/5-style breaks with an approximate target count.
    *
    * The grid is anchored at zero and chosen without logarithms so the same interval produces
    * byte-identical labels on the JVM and Scala.js. Use [[count]] when the number of breaks must be
    * exact.
    */
  def pretty(targetCount: Int = 5): Either[GraphicsError, Breaks] =
    if targetCount < 1 then Left(GraphicsError.InvalidBreakCount(targetCount))
    else if targetCount > MaximumOutputSize then
      Left(GraphicsError.BreakOutputLimitExceeded("pretty", targetCount, MaximumOutputSize))
    else
      Right(
        checked("pretty") { range =>
          if range.lower == range.upper then Right(Vector(range.lower))
          else if targetCount == 1 then Right(Vector(midpoint(range)))
          else
            val rawStep = targetStep(range, targetCount)
            if !rawStep.isFinite || rawStep <= 0.0 then Right(boundaryFallback(range))
            else
              niceStep(rawStep).flatMap { step =>
                if !step.isFinite || step <= 0.0 then Right(boundaryFallback(range))
                else prettyGrid(range, step, targetCount)
              }
        }
      )

  def prettyUnsafe(targetCount: Int = 5): Breaks =
    pretty(targetCount).orThrow

  def width(width: Double, offset: Double = 0.0): Either[GraphicsError, Breaks] =
    if !width.isFinite || width <= 0.0 then Left(GraphicsError.InvalidBreakWidth(width))
    else
      Right(
        checked("width") { range =>
          val first = math.ceil((range.lower - offset) / width) * width + offset
          if !first.isFinite then Left(GraphicsError.NonFiniteBreak("width", first))
          else
            val tolerance = math.abs(width) * 1e-12
            val expandedUpper = range.upper + tolerance
            val upperLimit = if expandedUpper.isFinite then expandedUpper else range.upper
            val out = Vector.newBuilder[Double]
            var candidate = normalizeZero(first)
            var emitted = 0
            var iterations = 0
            var done = false
            var error: Option[GraphicsError] = None
            while !done && error.isEmpty do
              if iterations > MaximumOutputSize then
                error = Some(
                  GraphicsError.BreakIterationLimitExceeded(
                    "width",
                    MaximumOutputSize + 1
                  )
                )
              else if !candidate.isFinite then
                error = Some(GraphicsError.NonFiniteBreak("width", candidate))
              else if candidate > upperLimit then done = true
              else if emitted >= MaximumOutputSize then
                error = Some(
                  GraphicsError.BreakOutputLimitExceeded(
                    "width",
                    emitted + 1,
                    MaximumOutputSize
                  )
                )
              else
                if range.contains(candidate) then
                  out += candidate
                  emitted += 1
                if candidate >= range.upper then done = true
                else
                  val next = normalizeZero(candidate + width)
                  if !next.isFinite then error = Some(GraphicsError.NonFiniteBreak("width", next))
                  else if next <= candidate then
                    error = Some(
                      GraphicsError.BreakGenerationDidNotProgress("width", candidate, next)
                    )
                  else candidate = next
              iterations += 1
            error match
              case Some(value) => Left(value)
              case None        => Right(out.result())
        }
      )

  val log10: Breaks =
    checked("log10") { range =>
      val values =
        if range.upper <= 0.0 then Vector.empty
        else
          val lo = math.ceil(math.log10(math.max(range.lower, Double.MinPositiveValue))).toInt
          val hi = math.floor(math.log10(range.upper)).toInt
          if hi < lo then Vector.empty
          else Vector.tabulate(hi - lo + 1)(i => math.pow(10.0, lo + i))
      validateOutput("log10", values)
    }

  val default: Breaks =
    prettyUnsafe()

  private val Sqrt2 = 1.4142135623730951
  private val Sqrt10 = 3.1622776601683795
  private val Sqrt50 = 7.0710678118654755
  private val MaxExactInteger = 9007199254740992.0

  private def midpoint(range: Interval): Double =
    val width = range.width
    if width.isFinite then range.lower + width / 2.0
    else range.lower / 2.0 + range.upper / 2.0

  private def targetStep(range: Interval, targetCount: Int): Double =
    val width = range.width
    if width.isFinite then width / targetCount.toDouble
    else range.upper / targetCount.toDouble - range.lower / targetCount.toDouble

  /** Nearest 1/2/5 power-of-ten step using D3-style geometric thresholds. Repeated IEEE scaling
    * avoids platform-specific `log10` edge behavior.
    */
  private def niceStep(rawStep: Double): Either[GraphicsError, Double] =
    var fraction = rawStep
    var power = 1.0
    var iterations = 0
    while fraction >= 10.0 && power.isFinite && iterations < MaximumScaleIterations do
      val nextFraction = fraction / 10.0
      val nextPower = power * 10.0
      if nextFraction == fraction then
        return Left(
          GraphicsError.BreakGenerationDidNotProgress("pretty-step", fraction, nextFraction)
        )
      fraction = nextFraction
      power = nextPower
      iterations += 1
    if fraction >= 10.0 && power.isFinite then
      return Left(
        GraphicsError.BreakIterationLimitExceeded("pretty-step", MaximumScaleIterations)
      )

    while fraction < 1.0 && power > 0.0 && iterations < MaximumScaleIterations do
      val nextFraction = fraction * 10.0
      val nextPower = power / 10.0
      if nextFraction == fraction then
        return Left(
          GraphicsError.BreakGenerationDidNotProgress("pretty-step", fraction, nextFraction)
        )
      fraction = nextFraction
      power = nextPower
      iterations += 1
    if fraction < 1.0 && power > 0.0 then
      return Left(
        GraphicsError.BreakIterationLimitExceeded("pretty-step", MaximumScaleIterations)
      )

    val factor =
      if fraction >= Sqrt50 then 10.0
      else if fraction >= Sqrt10 then 5.0
      else if fraction >= Sqrt2 then 2.0
      else 1.0
    val candidate = factor * power
    if candidate.isFinite && candidate > 0.0 then Right(candidate)
    else if power.isFinite && power > 0.0 then Right(power)
    else Right(rawStep)

  private def prettyGrid(
      range: Interval,
      step: Double,
      targetCount: Int
  ): Either[GraphicsError, Vector[Double]] =
    val firstIndex = math.ceil(range.lower / step)
    val first = firstIndex * step
    if !firstIndex.isFinite then Left(GraphicsError.NonFiniteBreak("pretty", firstIndex))
    else if !first.isFinite then Left(GraphicsError.NonFiniteBreak("pretty", first))
    else
      val out = Vector.newBuilder[Double]
      val targetBound = targetCount.toLong * 4L + 16L
      val maxTicks = math.min(targetBound, MaximumOutputSize.toLong).toInt
      var offset = 0L
      var emitted = 0
      var iterations = 0
      var previous = Double.NegativeInfinity
      var candidate = gridValue(firstIndex, first, offset, step)
      var done = false
      var error: Option[GraphicsError] = None
      while !done && error.isEmpty do
        if iterations > maxTicks then
          error = Some(GraphicsError.BreakIterationLimitExceeded("pretty", maxTicks + 1))
        else if !candidate.isFinite then
          error = Some(GraphicsError.NonFiniteBreak("pretty", candidate))
        else if candidate > range.upper then done = true
        else if emitted >= maxTicks then
          error = Some(
            GraphicsError.BreakOutputLimitExceeded("pretty", emitted + 1, maxTicks)
          )
        else if candidate <= previous then
          error = Some(
            GraphicsError.BreakGenerationDidNotProgress("pretty", previous, candidate)
          )
        else
          if range.contains(candidate) then
            out += candidate
            emitted += 1
          previous = candidate
          if candidate >= range.upper then done = true
          else
            offset += 1L
            val next = gridValue(firstIndex, first, offset, step)
            if next <= candidate then
              error = Some(
                GraphicsError.BreakGenerationDidNotProgress("pretty", candidate, next)
              )
            else candidate = next
        iterations += 1

      error match
        case Some(value) => Left(value)
        case None        =>
          val result = out.result()
          if result.nonEmpty then Right(result) else Right(Vector(midpoint(range)))

  private def boundaryFallback(range: Interval): Vector[Double] =
    if range.lower == range.upper then Vector(range.lower)
    else Vector(range.lower, range.upper)

  private def normalizeZero(value: Double): Double =
    if value == 0.0 then 0.0 else value

  private def interpolate(range: Interval, fraction: Double): Double =
    val width = range.width
    if width.isFinite then range.lower + width * fraction
    else range.lower * (1.0 - fraction) + range.upper * fraction

  private[intaglio] def validateOutput(
      generator: String,
      values: Vector[Double]
  ): Either[GraphicsError, Vector[Double]] =
    if values.length > MaximumOutputSize then
      Left(
        GraphicsError.BreakOutputLimitExceeded(generator, values.length, MaximumOutputSize)
      )
    else
      var index = 0
      var previous = Double.NegativeInfinity
      var error: Option[GraphicsError] = None
      while index < values.length && error.isEmpty do
        val value = values(index)
        if !value.isFinite then error = Some(GraphicsError.NonFiniteBreak(generator, value))
        else if index > 0 && value <= previous then
          error = Some(GraphicsError.BreakGenerationDidNotProgress(generator, previous, value))
        else previous = value
        index += 1
      error match
        case Some(value) => Left(value)
        case None        => Right(values)

  private def gridValue(firstIndex: Double, first: Double, offset: Long, step: Double): Double =
    val offsetDouble = offset.toDouble
    val value =
      if math.abs(firstIndex) + offsetDouble <= MaxExactInteger then
        (firstIndex + offsetDouble) * step
      else first + offsetDouble * step
    normalizeZero(value)

trait Labeler:
  def apply(values: Vector[Double]): Vector[String]

object Labeler:
  /** Deterministic, platform-independent number labels. `Double.toString` switches to exponent
    * notation at different magnitudes on the JVM and Scala.js, so non-integral values are formatted
    * manually: fixed notation with up to six significant digits for ordinary magnitudes, an
    * explicit `<mantissa>e<exponent>` form for extreme ones.
    */
  val default: Labeler =
    values => values.map(formatValue)

  private def formatValue(value: Double): String =
    if !value.isFinite then value.toString
    else
      val rounded = math.rint(value)
      if math.abs(value - rounded) < 1e-10 && math.abs(value) < 1e15 then rounded.toLong.toString
      else
        val sign = if value < 0.0 then "-" else ""
        val magnitude = math.abs(value)
        val exponent = decimalExponent(magnitude)
        if exponent >= -4 && exponent < 15 then
          sign + fixed(magnitude, decimals = math.min(6, math.max(0, 5 - exponent)))
        else
          val mantissa = magnitude / math.pow(10.0, exponent.toDouble)
          s"$sign${fixed(mantissa, decimals = 4)}e$exponent"

  /** Largest e with 10^e <= magnitude, via repeated scaling (identical IEEE arithmetic on JVM and
    * JS, unlike `math.log10`).
    */
  private def decimalExponent(magnitude: Double): Int =
    var exponent = 0
    var m = magnitude
    while m >= 10.0 do
      m /= 10.0
      exponent += 1
    while m < 1.0 do
      m *= 10.0
      exponent -= 1
    exponent

  /** Fixed-point rendering with trailing zeros stripped. */
  private def fixed(magnitude: Double, decimals: Int): String =
    val scale = math.pow(10.0, decimals.toDouble)
    val scaled = math.rint(magnitude * scale).toLong
    val whole = scaled / scale.toLong
    var frac = scaled % scale.toLong
    if decimals == 0 || frac == 0L then whole.toString
    else
      var digits = decimals
      while frac % 10L == 0L do
        frac /= 10L
        digits -= 1
      val text = frac.toString
      val padded = "0" * (digits - text.length) + text
      s"$whole.$padded"

final case class Transform private (
    name: GraphicsName,
    forward: Double => Double,
    backward: Double => Double,
    domain: TransformDomain,
    breaks: Breaks,
    labeler: Labeler
):
  def transform(value: Double): Either[GraphicsError, Double] =
    if !domain.contains(value) then Left(GraphicsError.TransformOutsideDomain(name.value, value))
    else evaluate("forward", value, forward)

  def inverse(value: Double): Either[GraphicsError, Double] =
    evaluate("inverse", value, backward)

  def roundTrips(value: Double, tolerance: Double): Boolean =
    transform(value).flatMap(inverse).exists(restored => math.abs(restored - value) <= tolerance)

  private def evaluate(
      operation: String,
      value: Double,
      callback: Double => Double
  ): Either[GraphicsError, Double] =
    try
      val out = callback(value)
      if out.isFinite then Right(out)
      else Left(GraphicsError.TransformOutsideDomain(name.value, value))
    catch
      case NonFatal(error) =>
        val (exceptionType, detail) = GraphicsError.throwableDetails(error)
        Left(
          GraphicsError.TransformEvaluationFailed(
            name.value,
            operation,
            exceptionType,
            detail
          )
        )

object Transform:
  def apply(
      name: String,
      forward: Double => Double,
      backward: Double => Double,
      domain: TransformDomain = TransformDomain.all,
      breaks: Breaks = Breaks.default,
      labeler: Labeler = Labeler.default
  ): Either[GraphicsError, Transform] =
    GraphicsName(name, "transform").map(Transform(_, forward, backward, domain, breaks, labeler))

  val identity: Transform =
    apply("identity", value => value, value => value).orThrow

  val reverse: Transform =
    apply("reverse", value => -value, value => -value).orThrow

  val log10: Transform =
    apply(
      "log10",
      value => math.log10(value),
      value => math.pow(10.0, value),
      TransformDomain.unsafe(
        "log10",
        DomainBound.Open(0.0),
        DomainBound.Open(Double.PositiveInfinity)
      ),
      breaks = Breaks.log10
    ).orThrow

  val sqrt: Transform =
    apply(
      "sqrt",
      value => math.sqrt(value),
      value => value * value,
      TransformDomain.unsafe("sqrt", 0.0, Double.PositiveInfinity)
    ).orThrow

enum OobPolicy:
  case Censor
  case Squish
  case Keep

  def apply(value: Double): Option[Double] =
    this match
      case Censor =>
        if value >= 0.0 && value <= 1.0 then Some(value) else None
      case Squish =>
        Some(math.max(0.0, math.min(1.0, value)))
      case Keep =>
        Some(value)

/** Fraction of each unit categorical step reserved as inter-band space. */
opaque type BandPadding = Double

object BandPadding:
  def apply(value: Double): Either[GraphicsError, BandPadding] =
    if value.isFinite && value >= 0.0 && value < 1.0 then Right(value)
    else Left(GraphicsError.InvalidBandPadding(value))

  def unsafe(value: Double): BandPadding =
    apply(value).orThrow

  val default: BandPadding =
    unsafe(0.1)

  extension (value: BandPadding) def toDouble: Double = value

/** One categorical position interval in native plot coordinates. */
final case class Band private (center: Double, width: Double):
  def lower: Double = center - width / 2.0
  def upper: Double = center + width / 2.0

object Band:
  def apply(center: Double, width: Double): Either[GraphicsError, Band] =
    if center.isFinite && width.isFinite && width > 0.0 then Right(new Band(center, width))
    else Left(GraphicsError.InvalidBand(center, width))

  def unsafe(center: Double, width: Double): Band =
    apply(center, width).orThrow

enum ScaleKind:
  case Continuous
  case Temporal
  case Discrete
  case Band
  case Generic

/** Whether a scale learns from every layer that uses it or keeps its declared domain unchanged.
  * Plot-wide training is the ordinary grammar-of-graphics behavior; fixed domains are an explicit
  * limits contract.
  */
enum ScaleTraining:
  case PlotWide
  case Fixed

enum ScaleDomain:
  case Continuous(raw: Interval, transformed: Interval)
  case Temporal(
      kind: TemporalKind,
      encoded: Interval,
      lowerLabel: String,
      upperLabel: String
  )
  case Discrete(levels: Vector[String], ordered: Boolean)
  case Band(levels: Vector[String], ordered: Boolean, padding: BandPadding)
  case Unspecified

final case class ScaleDescriptor(
    name: GraphicsName,
    kind: ScaleKind,
    domain: ScaleDomain,
    training: ScaleTraining = ScaleTraining.PlotWide
)

/** Stable identity and display semantics for one category type. The category itself remains `A`;
  * callers explicitly choose the identity used for lookup and the label used by guides.
  */
trait CategoryIdentity[A]:
  type Identity

  def identity(value: A): Identity
  def label(value: A): String
  def ordering: Ordering[Identity]

  private[intaglio] final def erasedIdentity(value: A): Any =
    identity(value)

  private[intaglio] final def compare(left: A, right: A): Int =
    ordering.compare(identity(left), identity(right))

object CategoryIdentity:
  type Aux[A, Identity0] = CategoryIdentity[A] { type Identity = Identity0 }

  def by[A, Identity0](
      stableIdentity: A => Identity0,
      displayLabel: A => String
  )(using identityOrdering: Ordering[Identity0]): Aux[A, Identity0] =
    new CategoryIdentity[A]:
      type Identity = Identity0

      def identity(value: A): Identity =
        stableIdentity(value)

      def label(value: A): String =
        displayLabel(value)

      val ordering: Ordering[Identity] =
        identityOrdering

  val strings: Aux[String, String] =
    by(identity, identity)

  given CategoryIdentity[String] =
    strings

/** Erased category identity used by structural grouping without reducing the typed value to its
  * label. Equality requires the same [[CategoryIdentity]] instance and the same stable key.
  */
final class CategoryToken private[intaglio] (
    private val owner: AnyRef,
    private val key: Any,
    val label: String
):
  override def equals(other: Any): Boolean =
    other match
      case that: CategoryToken => (owner eq that.owner) && key == that.key
      case _                   => false

  override def hashCode(): Int =
    31 * owner.hashCode() + key.hashCode()

  override def toString: String =
    label

object CategoryToken:
  private[intaglio] def apply[A](value: A, categories: CategoryIdentity[A]): CategoryToken =
    new CategoryToken(
      categories.asInstanceOf[AnyRef],
      categories.erasedIdentity(value),
      categories.label(value)
    )

private[intaglio] sealed trait CategoryObservation:
  type Value
  def value: Value
  def categories: CategoryIdentity[Value]

  final def valueFor[A](expected: CategoryIdentity[A]): Option[A] =
    Option.when(categories.asInstanceOf[AnyRef] eq expected.asInstanceOf[AnyRef])(
      value.asInstanceOf[A]
    )

  final def token: CategoryToken =
    CategoryToken(value, categories)

private[intaglio] object CategoryObservation:
  def apply[A](category: A, identity: CategoryIdentity[A]): CategoryObservation =
    new CategoryObservation:
      type Value = A
      val value: Value = category
      val categories: CategoryIdentity[Value] = identity

/** Erased, closed observations let a heterogeneous plot-scale registry train each binding without
  * erasing the input type of `Scale[In, Out]` itself. Categorical recovery is localized to an
  * identity-guarded package.
  */
private[intaglio] enum ScaleObservation:
  case Continuous(value: Double)
  case Discrete(value: CategoryObservation)

private[intaglio] object ScaleObservation:
  def discrete[A](value: A, categories: CategoryIdentity[A]): ScaleObservation =
    ScaleObservation.Discrete(CategoryObservation(value, categories))

  def discreteValues[A](
      observations: IterableOnce[ScaleObservation],
      categories: CategoryIdentity[A]
  ): Vector[A] =
    observations.iterator
      .collect { case ScaleObservation.Discrete(value) => value }
      .flatMap(
        _.valueFor(categories)
      )
      .toVector

enum ScaleMapFailure:
  case TransformDomain(transform: String, value: Double)
  case OutOfDomain(scale: String, value: String)
  case PaletteOverflow(scale: String, levels: Int, capacity: Int)

trait Palette[+A]:
  def apply(value: Double): A

object Palette:
  def constant[A](value: A): Palette[A] =
    _ => value

  val numeric: Palette[Double] =
    value => value

  def gradient(from: Rgba, to: Rgba): Palette[Rgba] =
    value =>
      val t = math.max(0.0, math.min(1.0, value))
      def channel(a: Int, b: Int): Int =
        math.rint(a + (b - a) * t).toInt
      Rgba.unsafe(
        channel(from.red, to.red),
        channel(from.green, to.green),
        channel(from.blue, to.blue),
        from.alpha + (to.alpha - from.alpha) * t
      )

enum PaletteOverflowPolicy:
  case Reject
  case Cycle

trait DiscretePalette[+A]:
  def apply(index: Int, count: Int): A

  /** Finite palettes publish their capacity; procedural palettes remain unbounded. */
  def capacity: Option[Int] =
    None

  def overflowPolicy: PaletteOverflowPolicy =
    PaletteOverflowPolicy.Reject

  final def validateDomain(
      scale: String,
      levelCount: Int
  ): Either[GraphicsError, Unit] =
    (capacity, overflowPolicy) match
      case (Some(maximum), PaletteOverflowPolicy.Reject) if levelCount > maximum =>
        Left(GraphicsError.DiscretePaletteOverflow(scale, levelCount, maximum))
      case _ => Right(())

  private[intaglio] final def mapValue(
      scale: String,
      index: Int,
      levelCount: Int
  ): Either[ScaleMapFailure, A] =
    (capacity, overflowPolicy) match
      case (Some(maximum), PaletteOverflowPolicy.Reject) if levelCount > maximum =>
        Left(ScaleMapFailure.PaletteOverflow(scale, levelCount, maximum))
      case _ => Right(apply(index, levelCount))

object DiscretePalette:
  def values[A](
      values: Vector[A],
      overflow: PaletteOverflowPolicy = PaletteOverflowPolicy.Reject
  ): Either[GraphicsError, DiscretePalette[A]] =
    if values.isEmpty then Left(GraphicsError.EmptyPalette)
    else
      Right(new DiscretePalette[A]:
        override val capacity: Option[Int] =
          Some(values.length)

        override val overflowPolicy: PaletteOverflowPolicy =
          overflow

        override def apply(index: Int, count: Int): A =
          overflow match
            case PaletteOverflowPolicy.Reject =>
              if index >= 0 && index < values.length then values(index)
              else
                Left[GraphicsError, A](
                  GraphicsError.DiscretePaletteOverflow("unvalidated", count, values.length)
                ).orThrow
            case PaletteOverflowPolicy.Cycle =>
              values(index % values.length))

  def valuesUnsafe[A](
      values: Vector[A],
      overflow: PaletteOverflowPolicy = PaletteOverflowPolicy.Reject
  ): DiscretePalette[A] =
    DiscretePalette.values(values, overflow).orThrow

  /** Stable zero-based positions for discrete axes and other ordinal output. */
  val indices: DiscretePalette[Double] =
    (index, _) => index.toDouble

final case class ContinuousScale[A] private (
    name: GraphicsName,
    domain: Interval,
    transformedDomain: Interval,
    transform: Transform,
    palette: Palette[A],
    oob: OobPolicy,
    training: ScaleTraining
) extends Scale[Double, A]:
  override def descriptor: ScaleDescriptor =
    ScaleDescriptor(
      name,
      ScaleKind.Continuous,
      ScaleDomain.Continuous(domain, transformedDomain),
      training
    )

  private[intaglio] override def observation(value: Double): Option[ScaleObservation] =
    Some(ScaleObservation.Continuous(value))

  private[intaglio] override def trainPlotWide(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[Double, A]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        val values =
          Iterator(domain.lower, domain.upper) ++ observations.iterator.collect {
            case ScaleObservation.Continuous(value) => value
          }
        ContinuousScale.train(name.value, values, palette, transform, oob, training)

  private[intaglio] override def trainFacet(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[Double, A]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        ContinuousScale.train(
          name.value,
          observations.iterator.collect { case ScaleObservation.Continuous(value) => value },
          palette,
          transform,
          oob,
          training
        )

  override def mapValue(value: Double): Option[A] =
    mapValueResult(value).toOption

  override def mapValueResult(value: Double): Either[ScaleMapFailure, A] =
    transform.transform(value) match
      case Left(_) =>
        Left(ScaleMapFailure.TransformDomain(transform.name.value, value))
      case Right(transformed) =>
        oob(transformedDomain.rescale(transformed)) match
          case Some(rescaled) => Right(palette(rescaled))
          case None           => Left(ScaleMapFailure.OutOfDomain(name.value, value.toString))

  def mapValues(values: IterableOnce[Double]): Vector[Option[A]] =
    values.iterator.map(mapValue).toVector

  /** Sample the palette at equal-width transformed-domain bin centers. Sampling is deliberately
    * expressed only with integer indexing and IEEE arithmetic so a guide receives the same colors
    * on the JVM and Scala.js.
    */
  def paletteSamples(count: Int): Either[GraphicsError, Vector[A]] =
    if count < 1 then Left(GraphicsError.InvalidBreakCount(count))
    else
      Right(
        Vector.tabulate(count) { index =>
          palette((index.toDouble + 0.5) / count.toDouble)
        }
      )

  def breaksResult: Either[GraphicsError, Vector[Double]] =
    transform.breaks.generate(domain).map(_.filter(domain.contains))

  /** Explicit throwing convenience for callers that have already validated the break policy. */
  def breaks: Vector[Double] =
    breaksResult.orThrow

  def labels: Vector[String] =
    transform.labeler(breaks)

object ContinuousScale:
  def train[A](
      name: String,
      values: IterableOnce[Double],
      palette: Palette[A],
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor,
      training: ScaleTraining = ScaleTraining.PlotWide
  ): Either[GraphicsError, ContinuousScale[A]] =
    val domains = trainDomains(values, transform)
    for
      scaleName <- GraphicsName(name, "continuous scale")
      trained <- domains
    yield ContinuousScale(
      scaleName,
      trained._1,
      trained._2,
      transform,
      palette,
      oob,
      training
    )

  def fixed[A](
      name: String,
      limits: IterableOnce[Double],
      palette: Palette[A],
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor
  ): Either[GraphicsError, ContinuousScale[A]] =
    train(name, limits, palette, transform, oob, ScaleTraining.Fixed)

  private def trainDomains(
      values: IterableOnce[Double],
      transform: Transform
  ): Either[GraphicsError, (Interval, Interval)] =
    var seen = false
    var rawLo = 0.0
    var rawHi = 0.0
    var transformedLo = 0.0
    var transformedHi = 0.0
    val it = values.iterator
    while it.hasNext do
      val value = it.next()
      transform.transform(value).toOption.foreach { transformed =>
        if !seen then
          rawLo = value
          rawHi = value
          transformedLo = transformed
          transformedHi = transformed
          seen = true
        else
          if value < rawLo then rawLo = value
          if value > rawHi then rawHi = value
          if transformed < transformedLo then transformedLo = transformed
          if transformed > transformedHi then transformedHi = transformed
      }
    if seen then
      Right((Interval.unsafe(rawLo, rawHi), Interval.unsafe(transformedLo, transformedHi)))
    else Left(GraphicsError.EmptyContinuousRange)

final case class DiscreteDomain[A] private (
    levels: Vector[A],
    ordered: Boolean,
    categories: CategoryIdentity[A]
):
  private val indexByIdentity: Map[Any, Int] =
    levels.iterator.zipWithIndex.map { case (level, index) =>
      categories.erasedIdentity(level) -> index
    }.toMap

  require(indexByIdentity.size == levels.size, "category identities must be distinct")

  def labels: Vector[String] =
    levels.map(categories.label)

  def label(value: A): String =
    categories.label(value)

  def indexOf(value: A): Option[Int] =
    indexByIdentity.get(categories.erasedIdentity(value))

  def contains(value: A): Boolean =
    indexOf(value).nonEmpty

  def train(values: IterableOnce[A]): Either[GraphicsError, DiscreteDomain[A]] =
    val seen = scala.collection.mutable.HashSet.from(indexByIdentity.keys)
    val additions = values.iterator.filter { value =>
      seen.add(categories.erasedIdentity(value))
    }.toVector
    DiscreteDomain.build(levels ++ additions, ordered, categories)

  private[intaglio] def replace(values: Vector[A]): Either[GraphicsError, DiscreteDomain[A]] =
    val seen = scala.collection.mutable.HashSet.empty[Any]
    val distinct = values.filter(value => seen.add(categories.erasedIdentity(value)))
    DiscreteDomain.build(distinct, ordered, categories)

object DiscreteDomain:
  val empty: DiscreteDomain[String] =
    new DiscreteDomain(Vector.empty, ordered = true, CategoryIdentity.strings)

  def emptyFor[A](using categories: CategoryIdentity[A]): DiscreteDomain[A] =
    new DiscreteDomain(Vector.empty, ordered = true, categories)

  def ordered[A](levels: Vector[A])(using
      categories: CategoryIdentity[A]
  ): Either[GraphicsError, DiscreteDomain[A]] =
    build(levels, ordered = true, categories)

  def unordered[A](levels: Vector[A])(using
      categories: CategoryIdentity[A]
  ): Either[GraphicsError, DiscreteDomain[A]] =
    build(levels, ordered = false, categories)

  private def build[A](
      levels: Vector[A],
      ordered: Boolean,
      categories: CategoryIdentity[A]
  ): Either[GraphicsError, DiscreteDomain[A]] =
    firstDuplicate(levels, categories) match
      case Some(level) => Left(GraphicsError.DuplicateLevel(categories.label(level)))
      case None        =>
        val resolved = if ordered then levels else levels.sortWith(categories.compare(_, _) < 0)
        Right(new DiscreteDomain(resolved, ordered, categories))

  private def firstDuplicate[A](
      levels: Vector[A],
      categories: CategoryIdentity[A]
  ): Option[A] =
    val seen = scala.collection.mutable.HashSet.empty[Any]
    levels.find(level => !seen.add(categories.erasedIdentity(level)))

final case class DiscreteScale[Category, A] private (
    name: GraphicsName,
    domain: DiscreteDomain[Category],
    palette: DiscretePalette[A],
    training: ScaleTraining
) extends Scale[Category, A]:
  override def descriptor: ScaleDescriptor =
    ScaleDescriptor(
      name,
      ScaleKind.Discrete,
      ScaleDomain.Discrete(domain.labels, domain.ordered),
      training
    )

  private[intaglio] override def observation(value: Category): Option[ScaleObservation] =
    Some(ScaleObservation.discrete(value, domain.categories))

  private[intaglio] override def trainPlotWide(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[Category, A]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        domain
          .train(ScaleObservation.discreteValues(observations, domain.categories))
          .flatMap(DiscreteScale.validated(name, _, palette, training))

  private[intaglio] override def trainFacet(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[Category, A]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        domain
          .replace(ScaleObservation.discreteValues(observations, domain.categories))
          .flatMap(DiscreteScale.validated(name, _, palette, training))

  override def mapValue(value: Category): Option[A] =
    mapValueResult(value).toOption

  override def mapValueResult(value: Category): Either[ScaleMapFailure, A] =
    domain.indexOf(value) match
      case None        => Left(ScaleMapFailure.OutOfDomain(name.value, domain.label(value)))
      case Some(index) => palette.mapValue(name.value, index, domain.levels.length)

  def mapLevels(values: IterableOnce[Category]): Vector[Option[A]] =
    values.iterator.map(mapValue).toVector

object DiscreteScale:
  def apply[Category, A](
      name: String,
      domain: DiscreteDomain[Category],
      palette: DiscretePalette[A],
      training: ScaleTraining = ScaleTraining.PlotWide
  ): Either[GraphicsError, DiscreteScale[Category, A]] =
    GraphicsName(name, "discrete scale").flatMap(validated(_, domain, palette, training))

  def fixed[Category, A](
      name: String,
      domain: DiscreteDomain[Category],
      palette: DiscretePalette[A]
  ): Either[GraphicsError, DiscreteScale[Category, A]] =
    apply(name, domain, palette, ScaleTraining.Fixed)

  private def validated[Category, A](
      name: GraphicsName,
      domain: DiscreteDomain[Category],
      palette: DiscretePalette[A],
      training: ScaleTraining
  ): Either[GraphicsError, DiscreteScale[Category, A]] =
    palette
      .validateDomain(name.value, domain.levels.length)
      .map(_ => new DiscreteScale(name, domain, palette, training))

/** Scala-native categorical position scale. Levels retain their declared order, centers are
  * zero-based unit steps, and width is carried explicitly as a [[Band]] rather than inferred later
  * from a plotting convention.
  */
final case class BandScale[A] private (
    name: GraphicsName,
    domain: DiscreteDomain[A],
    padding: BandPadding,
    training: ScaleTraining
) extends Scale[A, Double]:
  override def descriptor: ScaleDescriptor =
    ScaleDescriptor(
      name,
      ScaleKind.Band,
      ScaleDomain.Band(domain.labels, domain.ordered, padding),
      training
    )

  private[intaglio] override def observation(value: A): Option[ScaleObservation] =
    Some(ScaleObservation.discrete(value, domain.categories))

  private[intaglio] override def trainPlotWide(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[A, Double]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        domain
          .train(ScaleObservation.discreteValues(observations, domain.categories))
          .map(BandScale(name, _, padding, training))

  private[intaglio] override def trainFacet(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[A, Double]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        domain
          .replace(ScaleObservation.discreteValues(observations, domain.categories))
          .map(BandScale(name, _, padding, training))

  override def mapValue(value: A): Option[Double] =
    band(value).map(_.center)

  override def mapValueResult(value: A): Either[ScaleMapFailure, Double] =
    band(value).map(_.center).toRight(ScaleMapFailure.OutOfDomain(name.value, domain.label(value)))

  private[intaglio] override def mappedBand(value: A): Option[Band] =
    band(value)

  def band(value: A): Option[Band] =
    domain.indexOf(value).map(index => Band.unsafe(index.toDouble, 1.0 - padding.toDouble))

  def bands: Vector[(A, Band)] =
    domain.levels.zipWithIndex.map { case (level, index) =>
      level -> Band.unsafe(index.toDouble, 1.0 - padding.toDouble)
    }

  def mapLevels(values: IterableOnce[A]): Vector[Option[Double]] =
    values.iterator.map(mapValue).toVector

object BandScale:
  def apply[A](
      name: String,
      domain: DiscreteDomain[A],
      padding: BandPadding = BandPadding.default,
      training: ScaleTraining = ScaleTraining.PlotWide
  ): Either[GraphicsError, BandScale[A]] =
    GraphicsName(name, "band scale").map(BandScale(_, domain, padding, training))

  def fixed[A](
      name: String,
      domain: DiscreteDomain[A],
      padding: BandPadding = BandPadding.default
  ): Either[GraphicsError, BandScale[A]] =
    apply(name, domain, padding, ScaleTraining.Fixed)

/** A typed scale declaration carried by an aesthetic mapping. A declaration is either an untrained
  * [[ScaleSpec]] or an already prepared [[Scale]]. The compiler trains declarations and installs
  * concrete scales before row mapping.
  */
trait ScaleValue[-In, +Out]:
  def name: GraphicsName
  def descriptor: ScaleDescriptor

  private[intaglio] def mapDeclaredValue(value: In): Option[Out]

  private[intaglio] def mapDeclaredValueResult(value: In): Either[ScaleMapFailure, Out]

  private[intaglio] def observation(value: In): Option[ScaleObservation] =
    None

  private[intaglio] def mappedBand(value: In): Option[Band] =
    None

  private[intaglio] def trainDeclaration(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[In, Out]]

trait Scale[-In, +Out] extends ScaleValue[In, Out]:
  def name: GraphicsName
  def mapValue(value: In): Option[Out]
  def descriptor: ScaleDescriptor =
    ScaleDescriptor(name, ScaleKind.Generic, ScaleDomain.Unspecified)

  def mapValueResult(value: In): Either[ScaleMapFailure, Out] =
    mapValue(value).toRight(ScaleMapFailure.OutOfDomain(name.value, value.toString))

  private[intaglio] override def observation(value: In): Option[ScaleObservation] =
    None

  private[intaglio] override def mappedBand(value: In): Option[Band] =
    None

  private[intaglio] def trainPlotWide(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[In, Out]] =
    Right(this)

  private[intaglio] def trainFacet(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[In, Out]] =
    trainPlotWide(observations)

  private[intaglio] final override def mapDeclaredValue(value: In): Option[Out] =
    mapValue(value)

  private[intaglio] final override def mapDeclaredValueResult(
      value: In
  ): Either[ScaleMapFailure, Out] =
    mapValueResult(value)

  private[intaglio] def resolveTheme(theme: Theme): Either[GraphicsError, Scale[In, Out]] =
    Right(this)

  private[intaglio] final override def trainDeclaration(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[In, Out]] =
    resolveTheme(theme).flatMap { resolved =>
      if facetLocal then resolved.trainFacet(observations)
      else resolved.trainPlotWide(observations)
    }

/** An untrained scale declaration. Specs contain configuration only: constructing one never
  * inspects rows or invents a provisional domain. Its descriptor therefore has an unspecified
  * domain until the compiler replaces it with a concrete [[Scale]].
  */
trait ScaleSpec[In, Out] extends ScaleValue[In, Out]:
  def kind: ScaleKind

  final override def descriptor: ScaleDescriptor =
    ScaleDescriptor(name, kind, ScaleDomain.Unspecified, ScaleTraining.PlotWide)

  private[intaglio] final override def mapDeclaredValue(value: In): Option[Out] =
    None

  private[intaglio] final override def mapDeclaredValueResult(
      value: In
  ): Either[ScaleMapFailure, Out] =
    Left(ScaleMapFailure.OutOfDomain(name.value, "untrained scale declaration"))

  private[intaglio] def trainSpec(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[In, Out]]

  private[intaglio] final override def trainDeclaration(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[In, Out]] =
    trainSpec(observations, theme, facetLocal)

enum ScalePaletteSource:
  case Explicit
  case ThemeDefault

final class ContinuousScaleSpec[A] private (
    val name: GraphicsName,
    val transform: Transform,
    val oob: OobPolicy,
    val paletteSource: ScalePaletteSource,
    palette: Theme => Palette[A]
) extends ScaleSpec[Double, A]:
  override val kind: ScaleKind =
    ScaleKind.Continuous

  private[intaglio] override def observation(value: Double): Option[ScaleObservation] =
    Some(ScaleObservation.Continuous(value))

  private[intaglio] override def trainSpec(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[Double, A]] =
    ContinuousScale.train(
      name.value,
      observations.collect { case ScaleObservation.Continuous(value) => value },
      palette(theme),
      transform,
      oob,
      ScaleTraining.PlotWide
    )

object ContinuousScaleSpec:
  def apply[A](
      name: String,
      palette: Palette[A],
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor
  ): Either[GraphicsError, ContinuousScaleSpec[A]] =
    GraphicsName(name, "continuous scale spec").map { scaleName =>
      new ContinuousScaleSpec(
        scaleName,
        transform,
        oob,
        ScalePaletteSource.Explicit,
        _ => palette
      )
    }

  def numeric(
      name: String,
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor
  ): Either[GraphicsError, ContinuousScaleSpec[Double]] =
    apply(name, Palette.numeric, transform, oob)

  def themeRgba(
      name: String,
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor
  ): Either[GraphicsError, ContinuousScaleSpec[Rgba]] =
    GraphicsName(name, "continuous scale spec").map { scaleName =>
      new ContinuousScaleSpec(
        scaleName,
        transform,
        oob,
        ScalePaletteSource.ThemeDefault,
        _.palettes.continuousPalette
      )
    }

final class DiscreteScaleSpec[Category, A] private (
    val name: GraphicsName,
    val declaredDomain: DiscreteDomain[Category],
    val paletteSource: ScalePaletteSource,
    palette: Theme => Either[GraphicsError, DiscretePalette[A]]
) extends ScaleSpec[Category, A]:
  override val kind: ScaleKind =
    ScaleKind.Discrete

  def declaredLevels: Vector[Category] =
    declaredDomain.levels

  private[intaglio] override def observation(value: Category): Option[ScaleObservation] =
    Some(ScaleObservation.discrete(value, declaredDomain.categories))

  private[intaglio] override def trainSpec(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[Category, A]] =
    for
      domain <- declaredDomain.train(
        ScaleObservation.discreteValues(observations, declaredDomain.categories)
      )
      resolvedPalette <- palette(theme)
      scale <- DiscreteScale(name.value, domain, resolvedPalette, ScaleTraining.PlotWide)
    yield scale

object DiscreteScaleSpec:
  def apply[Category, A](
      name: String,
      declaredLevels: Vector[Category],
      palette: DiscretePalette[A]
  )(using CategoryIdentity[Category]): Either[GraphicsError, DiscreteScaleSpec[Category, A]] =
    for
      scaleName <- GraphicsName(name, "discrete scale spec")
      domain <- DiscreteDomain.ordered(declaredLevels)
    yield new DiscreteScaleSpec(
      scaleName,
      domain,
      ScalePaletteSource.Explicit,
      _ => Right(palette)
    )

  def themeRgba(
      name: String,
      declaredLevels: Vector[String] = Vector.empty,
      overflow: PaletteOverflowPolicy = PaletteOverflowPolicy.Reject
  ): Either[GraphicsError, DiscreteScaleSpec[String, Rgba]] =
    for
      scaleName <- GraphicsName(name, "discrete scale spec")
      domain <- DiscreteDomain.ordered(declaredLevels)
    yield new DiscreteScaleSpec(
      scaleName,
      domain,
      ScalePaletteSource.ThemeDefault,
      theme => DiscretePalette.values(theme.palettes.discrete, overflow)
    )

final class BandScaleSpec[A] private (
    val name: GraphicsName,
    val declaredDomain: DiscreteDomain[A],
    val padding: BandPadding
) extends ScaleSpec[A, Double]:
  override val kind: ScaleKind =
    ScaleKind.Band

  def declaredLevels: Vector[A] =
    declaredDomain.levels

  private[intaglio] override def observation(value: A): Option[ScaleObservation] =
    Some(ScaleObservation.discrete(value, declaredDomain.categories))

  private[intaglio] override def mappedBand(value: A): Option[Band] =
    None

  private[intaglio] override def trainSpec(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[A, Double]] =
    declaredDomain
      .train(ScaleObservation.discreteValues(observations, declaredDomain.categories))
      .flatMap(BandScale(name.value, _, padding, ScaleTraining.PlotWide))

object BandScaleSpec:
  def apply[A](
      name: String,
      declaredLevels: Vector[A],
      padding: BandPadding
  )(using CategoryIdentity[A]): Either[GraphicsError, BandScaleSpec[A]] =
    for
      scaleName <- GraphicsName(name, "band scale spec")
      domain <- DiscreteDomain.ordered(declaredLevels)
    yield new BandScaleSpec(scaleName, domain, padding)

  def apply[A](
      name: String,
      declaredLevels: Vector[A]
  )(using CategoryIdentity[A]): Either[GraphicsError, BandScaleSpec[A]] =
    apply(name, declaredLevels, BandPadding.default)

  def apply(
      name: String,
      padding: BandPadding = BandPadding.default
  ): Either[GraphicsError, BandScaleSpec[String]] =
    apply(name, Vector.empty[String], padding)

final case class ScaleBinding[Row, In, Out](
    aesthetic: Aesthetic[Out],
    value: Row => In,
    scale: ScaleValue[In, Out]
):
  /** Convenience evaluation outside compilation. Like `RowMapping.apply`, this method may throw;
    * `PlotCompiler` uses the checked mapping boundary instead.
    */
  def map(row: Row): Option[Out] =
    scale.mapDeclaredValue(value(row))

  def toAesValue: AesValue[Row, Out] =
    AesValue.scaled(value, scale)

object ScaleBinding:
  def total[Row, In, Out](
      aesthetic: Aesthetic[Out],
      value: Row => In,
      scale: ScaleValue[In, Out]
  ): ScaleBinding[Row, In, Out] =
    ScaleBinding(aesthetic, RowMapping.total(value), scale)

  def checked[Row, In, Out](
      aesthetic: Aesthetic[Out],
      value: Row => Either[MappingFailure, In],
      scale: ScaleValue[In, Out]
  ): ScaleBinding[Row, In, Out] =
    ScaleBinding(aesthetic, RowMapping.checked(value), scale)

  def throwing[Row, In, Out](
      aesthetic: Aesthetic[Out],
      value: Row => In,
      scale: ScaleValue[In, Out]
  ): ScaleBinding[Row, In, Out] =
    ScaleBinding(aesthetic, RowMapping.throwing(value), scale)
