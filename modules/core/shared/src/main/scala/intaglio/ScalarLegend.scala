package intaglio

enum LegendError extends IntaglioError:
  case InvalidMetadata(reason: String)
  case InvalidTicks(reason: String)
  case InvalidLayout(reason: String)

  def message: String = this match
    case InvalidMetadata(reason) => s"invalid legend metadata: $reason"
    case InvalidTicks(reason) => s"invalid legend ticks: $reason"
    case InvalidLayout(reason) => s"invalid legend layout: $reason"

final class LegendTitle private (val quantity: String, val units: Option[String]):
  def text: String = units.fold(quantity)(unit => s"$quantity ($unit)")

object LegendTitle:
  def make(quantity: String, units: Option[String] = None): Either[LegendError, LegendTitle] =
    if quantity.trim.isEmpty || units.exists(_.trim.isEmpty) then
      Left(LegendError.InvalidMetadata("quantity and supplied units must be nonempty"))
    else Right(new LegendTitle(quantity.trim, units.map(_.trim)))

final case class ScalarLegendCell private[intaglio] (
  lowerFraction: Double,
  upperFraction: Double,
  sampleValue: Double,
  color: Rgba32
)

/** Mapping-owned calibration, independent of camera, compositing, and layout.
  * Tick positions use the full scalar window; diverging and split segments
  * therefore retain their actual asymmetric widths. Color evaluation uses each
  * segment's own normalization, exactly as on the mapped scientific object.
  */
final class ScalarLegend private (
  val mapping: ScalarMapping,
  val title: LegendTitle,
  val ticks: Vector[AxisTick],
  val showHidden: Boolean,
  val showInvalid: Boolean
):
  def position(value: Double): Double =
    require(value.isFinite && value >= mapping.scale.window.lower && value <= mapping.scale.window.upper,
      "legend position must be within the scalar window")
    mapping.scale.window.normalize(value)

  def valueAt(fraction: Double): Double =
    require(fraction.isFinite && fraction >= 0 && fraction <= 1, "legend fraction must be in [0,1]")
    (1 - fraction) * mapping.scale.window.lower + fraction * mapping.scale.window.upper

  def colorAt(fraction: Double): Rgba32 = mapping.color(valueAt(fraction))

  /** Piecewise-constant display cells. No cell crosses a mapping discontinuity;
    * each cell's color is the exact evaluator output at its recorded sample.
    * Pointwise endpoint inclusion remains available through mapping.color.
    */
  def cells(resolution: Int): Vector[ScalarLegendCell] =
    require(resolution >= 2 && resolution <= 8192, "legend resolution must be between 2 and 8192")
    val breaks = mapping.boundaries.filter(v => v >= mapping.scale.window.lower && v <= mapping.scale.window.upper).map(position)
    val edges = ((0 to resolution).map(_.toDouble / resolution) ++ breaks).distinct.sorted
    edges.sliding(2).map: pair =>
      val value = valueAt(pair(0) * 0.5 + pair(1) * 0.5)
      ScalarLegendCell(pair(0), pair(1), value, mapping.color(value))
    .toVector

  def notes: Vector[String] =
    def interval(value: ScalarInterval): String =
      val labels = ScalarLegend.labels(Vector(value.lower, value.upper))
      val left = if value.endpoints == ScalarEndpointInclusion.Lower || value.endpoints == ScalarEndpointInclusion.Both then "[" else "("
      val right = if value.endpoints == ScalarEndpointInclusion.Upper || value.endpoints == ScalarEndpointInclusion.Both then "]" else ")"
      s"$left${labels(0)}, ${labels(1)}$right"
    val visibility = mapping.visibility match
      case ScalarVisibility.All => Vector.empty
      case ScalarVisibility.Inside(value) => Vector(s"Visible interval: ${interval(value)}")
      case ScalarVisibility.Outside(value) => Vector(s"Hidden interval: ${interval(value)}")
    val gap = mapping.scale.omittedInterval.map(value => s"Omitted interval: ${interval(value)}").toVector
    val outside = mapping.outOfRange match
      case ScalarOutOfRange.Clamp => "Outside range: saturated; visibility still applies"
      case ScalarOutOfRange.Hide => "Outside range: hidden"
    visibility ++ gap ++ Vector(outside, "Unlit mapping colors")

  lazy val canonicalKey: String =
    def text(value: String): String = s"${value.length}:$value"
    def number(value: Double): String = java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(if value == 0 then 0 else value))
    val tickKey = ticks.map(t => s"${number(t.value)}:${text(t.label)}").mkString(";")
    s"scalar-legend-v1|${text(mapping.canonicalKey)}|${text(title.quantity)}|${title.units.fold("none")(text)}|$tickKey|$showHidden|$showInvalid"

object ScalarLegend:
  private[intaglio] def labels(values: Vector[Double]): Vector[String] =
    val formatted = Labeler.default(values)
    if formatted.distinct.length == values.distinct.length then formatted else
      values.map(value => BigDecimal.decimal(value).bigDecimal.stripTrailingZeros.toPlainString)

  def make(mapping: ScalarMapping, title: LegendTitle, ticks: Vector[AxisTick] = Vector.empty,
    showHidden: Boolean = true, showInvalid: Boolean = true): Either[LegendError, ScalarLegend] =
    val window = mapping.scale.window
    val values = (Vector(window.lower, window.upper) ++ mapping.scale.center ++
      mapping.scale.omittedInterval.toVector.flatMap(v => Vector(v.lower, v.upper)) ++ (mapping.visibility match
        case ScalarVisibility.All => Vector.empty
        case ScalarVisibility.Inside(v) => Vector(v.lower, v.upper)
        case ScalarVisibility.Outside(v) => Vector(v.lower, v.upper)))
      .filter(v => v >= window.lower && v <= window.upper).distinct.sorted
    val defaultLabels = labels(values)
    val selected = if ticks.isEmpty then values.zip(defaultLabels).map((v, label) => AxisTick.unsafe(v, label)) else ticks.sortBy(_.value)
    if selected.exists(t => !t.value.isFinite || t.value < window.lower || t.value > window.upper || t.label.trim.isEmpty) then
      Left(LegendError.InvalidTicks("ticks require finite in-range values and nonempty labels"))
    else if selected.map(_.value).distinct.length != selected.length then
      Left(LegendError.InvalidTicks("tick values must be unique"))
    else Right(new ScalarLegend(mapping, title, selected, showHidden, showInvalid))
