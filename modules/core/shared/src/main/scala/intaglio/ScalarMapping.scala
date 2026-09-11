package intaglio

/** Inspectable scalar display policy. Coordinates and ramps describe unlit sRGB bytes. */
enum ScalarMappingError extends IntaglioError:
  case InvalidRamp(reason: String)
  case InvalidInterval(lower: Double, upper: Double)
  case InvalidScale(reason: String)

  def message: String = this match
    case InvalidRamp(reason)           => s"invalid scalar ramp: $reason"
    case InvalidInterval(lower, upper) =>
      s"scalar interval requires finite lower < upper; got [$lower, $upper]"
    case InvalidScale(reason) => s"invalid scalar scale: $reason"

enum ScalarEndpointInclusion:
  case Neither, Lower, Upper, Both

final class ScalarInterval private (
    val lower: Double,
    val upper: Double,
    val endpoints: ScalarEndpointInclusion
):
  def contains(value: Double): Boolean =
    val includesLower =
      endpoints == ScalarEndpointInclusion.Lower || endpoints == ScalarEndpointInclusion.Both
    val includesUpper =
      endpoints == ScalarEndpointInclusion.Upper || endpoints == ScalarEndpointInclusion.Both
    (value > lower || (includesLower && value == lower)) &&
    (value < upper || (includesUpper && value == upper))

object ScalarInterval:
  def make(
      lower: Double,
      upper: Double,
      endpoints: ScalarEndpointInclusion
  ): Either[ScalarMappingError, ScalarInterval] =
    if lower.isFinite && upper.isFinite && lower < upper then
      Right(new ScalarInterval(lower, upper, endpoints))
    else Left(ScalarMappingError.InvalidInterval(lower, upper))

enum ScalarVisibility:
  case All
  case Inside(interval: ScalarInterval)
  case Outside(interval: ScalarInterval)

  def includes(value: Double): Boolean = this match
    case All               => true
    case Inside(interval)  => interval.contains(value)
    case Outside(interval) => !interval.contains(value)

object ScalarVisibility:
  /** The historical transparent band excludes only its open interior. */
  def fromThreshold(threshold: DisplayThreshold): ScalarVisibility = threshold match
    case DisplayThreshold.Disabled              => ScalarVisibility.All
    case DisplayThreshold.TransparentBand(band) =>
      ScalarVisibility.Outside(
        ScalarInterval.make(band.lower, band.upper, ScalarEndpointInclusion.Neither).toOption.get
      )

/** Immutable, ordered stops. No arbitrary callback is needed to reconstruct this ramp. */
final class ScalarRamp private (val stops: Vector[(Double, Rgba32)]):
  def colorAt(fraction: Double): Rgba32 =
    require(fraction.isFinite, "ramp fraction must be finite")
    if fraction <= 0.0 then stops.head._2
    else if fraction >= 1.0 then stops.last._2
    else
      var index = 1
      while stops(index)._1 < fraction do index += 1
      val (lo, lowColor) = stops(index - 1)
      val (hi, highColor) = stops(index)
      val t = (fraction - lo) / (hi - lo)
      def channel(a: Int, b: Int): Int = math.round(a + (b - a) * t).toInt
      Rgba32.packUnsafe(
        channel(lowColor.red, highColor.red),
        channel(lowColor.green, highColor.green),
        channel(lowColor.blue, highColor.blue),
        channel(lowColor.alpha, highColor.alpha)
      )

object ScalarRamp:
  def make(stops: Vector[(Double, Rgba32)]): Either[ScalarMappingError, ScalarRamp] =
    if stops.length < 2 then Left(ScalarMappingError.InvalidRamp("at least two stops are required"))
    else if stops.head._1 != 0.0 || stops.last._1 != 1.0 then
      Left(ScalarMappingError.InvalidRamp("first and last positions must be 0 and 1"))
    else if stops.exists(stop => !stop._1.isFinite || stop._1 < 0.0 || stop._1 > 1.0) ||
      stops.iterator.map(_._1).sliding(2).exists(pair => pair(0) >= pair(1))
    then Left(ScalarMappingError.InvalidRamp("positions must be finite and strictly increasing"))
    else Right(new ScalarRamp(stops))

  def linear(low: Rgba32, high: Rgba32): ScalarRamp = new ScalarRamp(
    Vector(0.0 -> low, 1.0 -> high)
  )
  def fromRamp(ramp: ColorRamp): ScalarRamp = linear(ramp.low, ramp.high)

enum ScalarScaleKind:
  case Sequential, Diverging, Split

final case class ScalarRampSegment(window: DisplayWindow, ramp: ScalarRamp)
final case class ScalarRampCoordinate(segment: Int, fraction: Double)

/** Validated scale geometry. Diverging segments meet at the explicit center; split segments leave
  * an open omitted interval containing that center.
  */
final class ScalarScale private (
    val kind: ScalarScaleKind,
    val window: DisplayWindow,
    val center: Option[Double],
    val segments: Vector[ScalarRampSegment]
):
  def omittedInterval: Option[ScalarInterval] =
    if kind == ScalarScaleKind.Split then
      Some(
        ScalarInterval
          .make(
            segments.head.window.upper,
            segments.last.window.lower,
            ScalarEndpointInclusion.Neither
          )
          .toOption
          .get
      )
    else None

  def withWindow(next: DisplayWindow): Either[ScalarMappingError, ScalarScale] = kind match
    case ScalarScaleKind.Sequential => Right(ScalarScale.sequential(next, segments.head.ramp))
    case ScalarScaleKind.Diverging  =>
      ScalarScale.diverging(next, center.get, segments.head.ramp, segments.last.ramp)
    case ScalarScaleKind.Split =>
      ScalarScale.split(
        next,
        center.get,
        segments.head.window.upper,
        segments.last.window.lower,
        segments.head.ramp,
        segments.last.ramp
      )

  private[intaglio] def omits(value: Double): Boolean =
    kind == ScalarScaleKind.Split && value > segments.head.window.upper && value < segments.last.window.lower

  private[intaglio] def segmentAt(value: Double): Int =
    if segments.length == 1 || value <= segments.head.window.upper then 0 else 1

  private[intaglio] def fractionAt(value: Double, index: Int): Double =
    segments(index).window.normalize(value)

object ScalarScale:
  def sequential(window: DisplayWindow, ramp: ScalarRamp): ScalarScale =
    new ScalarScale(
      ScalarScaleKind.Sequential,
      window,
      None,
      Vector(ScalarRampSegment(window, ramp))
    )

  def diverging(
      window: DisplayWindow,
      center: Double,
      lower: ScalarRamp,
      upper: ScalarRamp
  ): Either[ScalarMappingError, ScalarScale] =
    if !center.isFinite || center <= window.lower || center >= window.upper then
      Left(ScalarMappingError.InvalidScale("center must lie strictly inside the window"))
    else if lower.stops.last._2 != upper.stops.head._2 then
      Left(
        ScalarMappingError.InvalidScale("continuous diverging ramps must share their center color")
      )
    else
      Right(
        new ScalarScale(
          ScalarScaleKind.Diverging,
          window,
          Some(center),
          Vector(
            ScalarRampSegment(DisplayWindow.unsafe(window.lower, center), lower),
            ScalarRampSegment(DisplayWindow.unsafe(center, window.upper), upper)
          )
        )
      )

  def split(
      window: DisplayWindow,
      center: Double,
      lowerEnd: Double,
      upperStart: Double,
      lower: ScalarRamp,
      upper: ScalarRamp
  ): Either[ScalarMappingError, ScalarScale] =
    if !center.isFinite || !lowerEnd.isFinite || !upperStart.isFinite ||
      !(window.lower < lowerEnd && lowerEnd <= center && center <= upperStart && upperStart < window.upper && lowerEnd < upperStart)
    then
      Left(
        ScalarMappingError.InvalidScale(
          "split scale requires lower < lowerEnd <= center <= upperStart < upper and a nonempty gap"
        )
      )
    else
      Right(
        new ScalarScale(
          ScalarScaleKind.Split,
          window,
          Some(center),
          Vector(
            ScalarRampSegment(DisplayWindow.unsafe(window.lower, lowerEnd), lower),
            ScalarRampSegment(DisplayWindow.unsafe(upperStart, window.upper), upper)
          )
        )
      )

enum ScalarOutOfRange:
  case Clamp, Hide

enum ScalarSampleState:
  case Visible, ClampedLow, ClampedHigh, HiddenVisibility, HiddenOutOfRange, HiddenScaleGap, Invalid

final case class ScalarMappedSample(
    state: ScalarSampleState,
    color: Rgba32,
    coordinate: Option[ScalarRampCoordinate]
)

/** Classification uses the original value: invalid, visibility, out-of-range, omitted scale
  * interval, then mapping. Clamping never changes visibility.
  */
final case class ScalarMapping(
    scale: ScalarScale,
    visibility: ScalarVisibility = ScalarVisibility.All,
    outOfRange: ScalarOutOfRange = ScalarOutOfRange.Clamp,
    hidden: Rgba32 = Rgba32.unsafe(0, 0, 0, 0),
    invalid: Rgba32 = Rgba32.unsafe(0, 0, 0, 0)
):
  def classify(value: Double): ScalarSampleState =
    if !value.isFinite then ScalarSampleState.Invalid
    else if !visibility.includes(value) then ScalarSampleState.HiddenVisibility
    else if value < scale.window.lower then
      if outOfRange == ScalarOutOfRange.Hide then ScalarSampleState.HiddenOutOfRange
      else ScalarSampleState.ClampedLow
    else if value > scale.window.upper then
      if outOfRange == ScalarOutOfRange.Hide then ScalarSampleState.HiddenOutOfRange
      else ScalarSampleState.ClampedHigh
    else if scale.omits(value) then ScalarSampleState.HiddenScaleGap
    else ScalarSampleState.Visible

  /** Allocation-free classification and color lookup for sample loops. */
  def color(value: Double): Rgba32 = classify(value) match
    case ScalarSampleState.Invalid => invalid
    case ScalarSampleState.HiddenVisibility | ScalarSampleState.HiddenOutOfRange |
        ScalarSampleState.HiddenScaleGap =>
      hidden
    case _ =>
      val index = scale.segmentAt(value)
      scale.segments(index).ramp.colorAt(scale.fractionAt(value, index))

  def evaluate(value: Double): ScalarMappedSample =
    val state = classify(value)
    val coordinate = state match
      case ScalarSampleState.Visible | ScalarSampleState.ClampedLow |
          ScalarSampleState.ClampedHigh =>
        val index = scale.segmentAt(value)
        Some(ScalarRampCoordinate(index, scale.fractionAt(value, index)))
      case _ => None
    ScalarMappedSample(state, color(value), coordinate)

  /** Window changes preserve the absolute center and split boundaries; invalid changes fail. */
  def resolve(
      window: Option[DisplayWindow] = None,
      threshold: Option[DisplayThreshold] = None
  ): Either[ScalarMappingError, ScalarMapping] =
    val resolvedScale =
      window.fold[Either[ScalarMappingError, ScalarScale]](Right(scale))(scale.withWindow)
    resolvedScale.map(next =>
      copy(scale = next, visibility = threshold.fold(visibility)(ScalarVisibility.fromThreshold))
    )

  /** Adapt only color evaluation. Resolve checked overrides first, then create this adapter. */
  def colorizer: Colorizer[Double] = new ScalarMappingColorizer(this)

  /** Physical ramp knots and visibility endpoints, in ascending scalar order. */
  lazy val boundaries: Vector[Double] =
    val knots = scale.segments.flatMap: segment =>
      segment.ramp.stops.map: (t, _) =>
        (1.0 - t) * segment.window.lower + t * segment.window.upper
    val selected = visibility match
      case ScalarVisibility.All               => Vector.empty
      case ScalarVisibility.Inside(interval)  => Vector(interval.lower, interval.upper)
      case ScalarVisibility.Outside(interval) => Vector(interval.lower, interval.upper)
    (knots ++ selected).distinct.sorted

  /** Complete, versioned descriptor identity, identical on JVM and JS; not a lossy hash. Signed
    * zero is canonicalized because it has no distinct display semantics here.
    */
  lazy val canonicalKey: String =
    def number(value: Double): String =
      java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(if value == 0.0 then 0.0
      else value))
    def pixel(value: Rgba32): String = java.lang.Integer.toHexString(value.toPackedInt)
    def interval(value: ScalarInterval): String =
      s"${number(value.lower)},${number(value.upper)},${value.endpoints}"
    val selection = visibility match
      case ScalarVisibility.All            => "all"
      case ScalarVisibility.Inside(value)  => s"inside:${interval(value)}"
      case ScalarVisibility.Outside(value) => s"outside:${interval(value)}"
    val segments = scale.segments.map: segment =>
      val stops = segment.ramp.stops
        .map((position, color) => s"${number(position)}=${pixel(color)}")
        .mkString(",")
      s"${number(segment.window.lower)}:${number(segment.window.upper)}:$stops"
    s"scalar-mapping-v1|${scale.kind}|${scale.center.fold("none")(number)}|${segments.mkString(";")}|$selection|$outOfRange|${pixel(hidden)}|${pixel(invalid)}"

final class ScalarMappingColorizer private[intaglio] (val mapping: ScalarMapping)
    extends Colorizer[Double]:
  def color(value: Double): Rgba32 = mapping.color(value)

object ScalarMapping:
  def fromLegacy(colorizer: ScalarColorizer): ScalarMapping = ScalarMapping(
    ScalarScale.sequential(colorizer.window, ScalarRamp.fromRamp(colorizer.ramp)),
    ScalarVisibility.fromThreshold(colorizer.threshold),
    invalid = colorizer.invalid
  )

  /** Opaque callbacks remain opaque: absence is not permission to invent mapping metadata. */
  def inspect(colorizer: Colorizer[Double]): Option[ScalarMapping] = colorizer match
    case value: ScalarMappingColorizer => Some(value.mapping)
    case value: ScalarColorizer        => Some(fromLegacy(value))
    case _                             => None
