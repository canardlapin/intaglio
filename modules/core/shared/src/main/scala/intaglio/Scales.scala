package intaglio

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
    unsafe("all", DomainBound.Open(Double.NegativeInfinity), DomainBound.Open(Double.PositiveInfinity))

  def apply(name: String, lower: Double, upper: Double): Either[GraphicsError, TransformDomain] =
    closed(name, lower, upper)

  def closed(name: String, lower: Double, upper: Double): Either[GraphicsError, TransformDomain] =
    apply(name, DomainBound.Closed(lower), DomainBound.Closed(upper))

  def openClosed(name: String, lower: Double, upper: Double): Either[GraphicsError, TransformDomain] =
    apply(name, DomainBound.Open(lower), DomainBound.Closed(upper))

  def closedOpen(name: String, lower: Double, upper: Double): Either[GraphicsError, TransformDomain] =
    apply(name, DomainBound.Closed(lower), DomainBound.Open(upper))

  def open(name: String, lower: Double, upper: Double): Either[GraphicsError, TransformDomain] =
    apply(name, DomainBound.Open(lower), DomainBound.Open(upper))

  def apply(name: String, lower: DomainBound, upper: DomainBound): Either[GraphicsError, TransformDomain] =
    if !lower.value.isNaN && !upper.value.isNaN && lower.value < upper.value then Right(new TransformDomain(lower, upper))
    else Left(GraphicsError.InvalidTransformDomain(name, lower.value, upper.value))

  def unsafe(name: String, lower: Double, upper: Double): TransformDomain =
    closed(name, lower, upper).orThrow

  def unsafe(name: String, lower: DomainBound, upper: DomainBound): TransformDomain =
    apply(name, lower, upper).orThrow

trait Breaks:
  def apply(range: Interval): Vector[Double]

object Breaks:
  def count(n: Int): Either[GraphicsError, Breaks] =
    if n < 1 then Left(GraphicsError.InvalidBreakCount(n))
    else
      Right(new Breaks:
        override def apply(range: Interval): Vector[Double] =
          if n == 1 then Vector((range.lower + range.upper) / 2.0)
          else
            val step = range.width / (n - 1).toDouble
            Vector.tabulate(n)(i => range.lower + step * i)
      )

  def countUnsafe(n: Int): Breaks =
    count(n).orThrow

  /** Deterministic 1/2/5-style breaks with an approximate target count.
    *
    * The grid is anchored at zero and chosen without logarithms so the same
    * interval produces byte-identical labels on the JVM and Scala.js. Use
    * [[count]] when the number of breaks must be exact.
    */
  def pretty(targetCount: Int = 5): Either[GraphicsError, Breaks] =
    if targetCount < 1 then Left(GraphicsError.InvalidBreakCount(targetCount))
    else
      Right(new Breaks:
        override def apply(range: Interval): Vector[Double] =
          if range.lower == range.upper then Vector(range.lower)
          else if targetCount == 1 then Vector(midpoint(range))
          else
            val rawStep = targetStep(range, targetCount)
            if !rawStep.isFinite || rawStep <= 0.0 then boundaryFallback(range)
            else
              val step = niceStep(rawStep)
              if !step.isFinite || step <= 0.0 then boundaryFallback(range)
              else prettyGrid(range, step, targetCount)
      )

  def prettyUnsafe(targetCount: Int = 5): Breaks =
    pretty(targetCount).orThrow

  def width(width: Double, offset: Double = 0.0): Either[GraphicsError, Breaks] =
    if !width.isFinite || width <= 0.0 then Left(GraphicsError.InvalidBreakWidth(width))
    else
      Right(new Breaks:
        override def apply(range: Interval): Vector[Double] =
          val first = math.ceil((range.lower - offset) / width) * width + offset
          val buf = Vector.newBuilder[Double]
          var x = first
          while x <= range.upper + width * 1e-12 do
            buf += x
            x += width
          buf.result()
      )

  val log10: Breaks =
    new Breaks:
      override def apply(range: Interval): Vector[Double] =
        if range.upper <= 0.0 then Vector.empty
        else
          val lo = math.ceil(math.log10(math.max(range.lower, Double.MinPositiveValue))).toInt
          val hi = math.floor(math.log10(range.upper)).toInt
          if hi < lo then Vector.empty
          else Vector.tabulate(hi - lo + 1)(i => math.pow(10.0, lo + i))

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

  /** Nearest 1/2/5 power-of-ten step using D3-style geometric thresholds.
    * Repeated IEEE scaling avoids platform-specific `log10` edge behavior.
    */
  private def niceStep(rawStep: Double): Double =
    var fraction = rawStep
    var power = 1.0
    while fraction >= 10.0 && power.isFinite do
      fraction /= 10.0
      power *= 10.0
    while fraction < 1.0 && power > 0.0 do
      fraction *= 10.0
      power /= 10.0

    val factor =
      if fraction >= Sqrt50 then 10.0
      else if fraction >= Sqrt10 then 5.0
      else if fraction >= Sqrt2 then 2.0
      else 1.0
    val candidate = factor * power
    if candidate.isFinite && candidate > 0.0 then candidate
    else if power.isFinite && power > 0.0 then power
    else rawStep

  private def prettyGrid(range: Interval, step: Double, targetCount: Int): Vector[Double] =
    val firstIndex = math.ceil(range.lower / step)
    val first = firstIndex * step
    val out = Vector.newBuilder[Double]
    val maxTicks = targetCount.toLong * 4L + 16L
    var offset = 0L
    var previous = Double.NegativeInfinity
    var candidate = gridValue(firstIndex, first, offset, step)
    var continue = candidate.isFinite && candidate <= range.upper && offset < maxTicks
    while continue do
      if range.contains(candidate) && candidate > previous then
        out += candidate
        previous = candidate
      offset += 1L
      val next = gridValue(firstIndex, first, offset, step)
      continue = next.isFinite && next > candidate && next <= range.upper && offset < maxTicks
      candidate = next

    val result = out.result()
    if result.nonEmpty then result else Vector(midpoint(range))

  private def boundaryFallback(range: Interval): Vector[Double] =
    if range.lower == range.upper then Vector(range.lower)
    else Vector(range.lower, range.upper)

  private def normalizeZero(value: Double): Double =
    if value == 0.0 then 0.0 else value

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
  /** Deterministic, platform-independent number labels. `Double.toString`
    * switches to exponent notation at different magnitudes on the JVM and
    * Scala.js, so non-integral values are formatted manually: fixed notation
    * with up to six significant digits for ordinary magnitudes, an explicit
    * `<mantissa>e<exponent>` form for extreme ones.
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

  /** Largest e with 10^e <= magnitude, via repeated scaling (identical IEEE
    * arithmetic on JVM and JS, unlike `math.log10`).
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
    else
      val out = forward(value)
      if out.isFinite then Right(out)
      else Left(GraphicsError.TransformOutsideDomain(name.value, value))

  def inverse(value: Double): Either[GraphicsError, Double] =
    val out = backward(value)
    if out.isFinite then Right(out)
    else Left(GraphicsError.TransformOutsideDomain(name.value, value))

  def roundTrips(value: Double, tolerance: Double): Boolean =
    transform(value).flatMap(inverse).exists(restored => math.abs(restored - value) <= tolerance)

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
      TransformDomain.unsafe("log10", DomainBound.Open(0.0), DomainBound.Open(Double.PositiveInfinity)),
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
  case Discrete
  case Band
  case Generic

/** Whether a scale learns from every layer that uses it or keeps its declared
  * domain unchanged. Plot-wide training is the ordinary grammar-of-graphics
  * behavior; fixed domains are an explicit limits contract.
  */
enum ScaleTraining:
  case PlotWide
  case Fixed

enum ScaleDomain:
  case Continuous(raw: Interval, transformed: Interval)
  case Discrete(levels: Vector[String], ordered: Boolean)
  case Band(levels: Vector[String], ordered: Boolean, padding: BandPadding)
  case Unspecified

final case class ScaleDescriptor(
    name: GraphicsName,
    kind: ScaleKind,
    domain: ScaleDomain,
    training: ScaleTraining = ScaleTraining.PlotWide
)

/** Erased, closed observations let a heterogeneous plot-scale registry train
  * each binding without erasing the input type of `Scale[In, Out]` itself.
  */
private[intaglio] enum ScaleObservation:
  case Continuous(value: Double)
  case Discrete(value: String)

enum ScaleMapFailure:
  case TransformDomain(transform: String, value: Double)
  case OutOfDomain(scale: String, value: String)

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

trait DiscretePalette[+A]:
  def apply(index: Int, count: Int): A

object DiscretePalette:
  def values[A](values: Vector[A]): Either[GraphicsError, DiscretePalette[A]] =
    if values.isEmpty then Left(GraphicsError.EmptyPalette)
    else
      Right(new DiscretePalette[A]:
        override def apply(index: Int, count: Int): A =
          values(index % values.length)
      )

  def valuesUnsafe[A](values: Vector[A]): DiscretePalette[A] =
    DiscretePalette.values(values).orThrow

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

  /** Sample the palette at equal-width transformed-domain bin centers.
    * Sampling is deliberately expressed only with integer indexing and IEEE
    * arithmetic so a guide receives the same colors on the JVM and Scala.js.
    */
  def paletteSamples(count: Int): Either[GraphicsError, Vector[A]] =
    if count < 1 then Left(GraphicsError.InvalidBreakCount(count))
    else
      Right(
        Vector.tabulate(count) { index =>
          palette((index.toDouble + 0.5) / count.toDouble)
        }
      )

  def breaks: Vector[Double] =
    transform.breaks(domain).filter(domain.contains)

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
      (domain, transformedDomain) <- domains
    yield ContinuousScale(scaleName, domain, transformedDomain, transform, palette, oob, training)

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

final case class DiscreteDomain private (levels: Vector[String], ordered: Boolean):
  require(levels.distinct.length == levels.length, "`levels` must be distinct")

  def contains(value: String): Boolean =
    levels.contains(value)

  def train(values: IterableOnce[String]): Either[GraphicsError, DiscreteDomain] =
    val additions = values.iterator.filterNot(levels.contains).toVector.distinct
    if ordered then DiscreteDomain.ordered(levels ++ additions)
    else DiscreteDomain.unordered(levels ++ additions)

object DiscreteDomain:
  val empty: DiscreteDomain =
    DiscreteDomain(Vector.empty, ordered = true)

  def ordered(levels: Vector[String]): Either[GraphicsError, DiscreteDomain] =
    firstDuplicate(levels) match
      case Some(level) => Left(GraphicsError.DuplicateLevel(level))
      case None        => Right(DiscreteDomain(levels, ordered = true))

  def unordered(levels: Vector[String]): Either[GraphicsError, DiscreteDomain] =
    firstDuplicate(levels) match
      case Some(level) => Left(GraphicsError.DuplicateLevel(level))
      case None        => Right(DiscreteDomain(levels.sorted, ordered = false))

  private def firstDuplicate(levels: Vector[String]): Option[String] =
    val seen = scala.collection.mutable.HashSet.empty[String]
    levels.find(level => !seen.add(level))

final case class DiscreteScale[A] private (
    name: GraphicsName,
    domain: DiscreteDomain,
    palette: DiscretePalette[A],
    training: ScaleTraining
) extends Scale[String, A]:
  override def descriptor: ScaleDescriptor =
    ScaleDescriptor(
      name,
      ScaleKind.Discrete,
      ScaleDomain.Discrete(domain.levels, domain.ordered),
      training
    )

  private[intaglio] override def observation(value: String): Option[ScaleObservation] =
    Some(ScaleObservation.Discrete(value))

  private[intaglio] override def trainPlotWide(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[String, A]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        domain
          .train(observations.iterator.collect { case ScaleObservation.Discrete(value) => value })
          .map(DiscreteScale(name, _, palette, training))

  private[intaglio] override def trainFacet(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[String, A]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        val levels = observations.iterator.collect { case ScaleObservation.Discrete(value) => value }.toVector.distinct
        val trained =
          if domain.ordered then DiscreteDomain.ordered(levels)
          else DiscreteDomain.unordered(levels)
        trained.map(DiscreteScale(name, _, palette, training))

  override def mapValue(value: String): Option[A] =
    mapValueResult(value).toOption

  override def mapValueResult(value: String): Either[ScaleMapFailure, A] =
    val idx = domain.levels.indexOf(value)
    if idx < 0 then Left(ScaleMapFailure.OutOfDomain(name.value, value))
    else Right(palette(idx, domain.levels.length))

  def mapLevels(values: IterableOnce[String]): Vector[Option[A]] =
    values.iterator.map(mapValue).toVector

object DiscreteScale:
  def apply[A](
      name: String,
      domain: DiscreteDomain,
      palette: DiscretePalette[A],
      training: ScaleTraining = ScaleTraining.PlotWide
  ): Either[GraphicsError, DiscreteScale[A]] =
    GraphicsName(name, "discrete scale").map(DiscreteScale(_, domain, palette, training))

  def fixed[A](
      name: String,
      domain: DiscreteDomain,
      palette: DiscretePalette[A]
  ): Either[GraphicsError, DiscreteScale[A]] =
    apply(name, domain, palette, ScaleTraining.Fixed)

/** Scala-native categorical position scale. Levels retain their declared
  * order, centers are zero-based unit steps, and width is carried explicitly
  * as a [[Band]] rather than inferred later from a plotting convention.
  */
final case class BandScale private (
    name: GraphicsName,
    domain: DiscreteDomain,
    padding: BandPadding,
    training: ScaleTraining
) extends Scale[String, Double]:
  override def descriptor: ScaleDescriptor =
    ScaleDescriptor(
      name,
      ScaleKind.Band,
      ScaleDomain.Band(domain.levels, domain.ordered, padding),
      training
    )

  private[intaglio] override def observation(value: String): Option[ScaleObservation] =
    Some(ScaleObservation.Discrete(value))

  private[intaglio] override def trainPlotWide(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[String, Double]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        domain
          .train(observations.iterator.collect { case ScaleObservation.Discrete(value) => value })
          .map(BandScale(name, _, padding, training))

  private[intaglio] override def trainFacet(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[String, Double]] =
    training match
      case ScaleTraining.Fixed =>
        Right(this)
      case ScaleTraining.PlotWide =>
        val levels = observations.iterator.collect { case ScaleObservation.Discrete(value) => value }.toVector.distinct
        val trained =
          if domain.ordered then DiscreteDomain.ordered(levels)
          else DiscreteDomain.unordered(levels)
        trained.map(BandScale(name, _, padding, training))

  override def mapValue(value: String): Option[Double] =
    band(value).map(_.center)

  override def mapValueResult(value: String): Either[ScaleMapFailure, Double] =
    band(value).map(_.center).toRight(ScaleMapFailure.OutOfDomain(name.value, value))

  private[intaglio] override def mappedBand(value: String): Option[Band] =
    band(value)

  def band(value: String): Option[Band] =
    val index = domain.levels.indexOf(value)
    Option.when(index >= 0)(Band.unsafe(index.toDouble, 1.0 - padding.toDouble))

  def bands: Vector[(String, Band)] =
    domain.levels.zipWithIndex.map { case (level, index) =>
      level -> Band.unsafe(index.toDouble, 1.0 - padding.toDouble)
    }

  def mapLevels(values: IterableOnce[String]): Vector[Option[Double]] =
    values.iterator.map(mapValue).toVector

object BandScale:
  def apply(
      name: String,
      domain: DiscreteDomain,
      padding: BandPadding = BandPadding.default,
      training: ScaleTraining = ScaleTraining.PlotWide
  ): Either[GraphicsError, BandScale] =
    GraphicsName(name, "band scale").map(BandScale(_, domain, padding, training))

  def fixed(
      name: String,
      domain: DiscreteDomain,
      padding: BandPadding = BandPadding.default
  ): Either[GraphicsError, BandScale] =
    apply(name, domain, padding, ScaleTraining.Fixed)

trait Scale[-In, +Out]:
  def name: GraphicsName
  def mapValue(value: In): Option[Out]
  def descriptor: ScaleDescriptor =
    ScaleDescriptor(name, ScaleKind.Generic, ScaleDomain.Unspecified)

  def mapValueResult(value: In): Either[ScaleMapFailure, Out] =
    mapValue(value).toRight(ScaleMapFailure.OutOfDomain(name.value, value.toString))

  private[intaglio] def observation(value: In): Option[ScaleObservation] =
    None

  private[intaglio] def mappedBand(value: In): Option[Band] =
    None

  private[intaglio] def trainPlotWide(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[In, Out]] =
    Right(this)

  private[intaglio] def trainFacet(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[In, Out]] =
    trainPlotWide(observations)

final case class ScaleBinding[Row, In, Out](
    aesthetic: Aesthetic[Out],
    value: Row => In,
    scale: Scale[In, Out]
):
  def map(row: Row): Option[Out] =
    scale.mapValue(value(row))

  def toAesValue: AesValue[Row, Out] =
    AesValue.scaled(value, scale)
