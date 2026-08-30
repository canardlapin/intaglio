package intaglio

/** Values created by a statistical transformation rather than read directly from an input row. The
  * type parameter keeps each computed field honest.
  */
enum ComputedAesthetic[A](val label: String):
  case Count extends ComputedAesthetic[Double]("count")
  case Proportion extends ComputedAesthetic[Double]("proportion")
  case Density extends ComputedAesthetic[Double]("density")
  case Position extends ComputedAesthetic[Double]("position")
  case Mean extends ComputedAesthetic[Double]("mean")
  case Lower extends ComputedAesthetic[Double]("lower")
  case Upper extends ComputedAesthetic[Double]("upper")
  case BinLower extends ComputedAesthetic[Double]("bin_lower")
  case BinUpper extends ComputedAesthetic[Double]("bin_upper")
  case BinWidth extends ComputedAesthetic[Double]("bin_width")
  case BinMidpoint extends ComputedAesthetic[Double]("bin_midpoint")

/** A read-only compatibility view of values produced by a statistical transformation. The
  * statistic-specific [[StatRow]] subtype owns every required field; this optional record is
  * derived from that row for generic inspection only.
  */
final case class ComputedValues private (
    count: Option[Double] = None,
    proportion: Option[Double] = None,
    density: Option[Double] = None,
    position: Option[Double] = None,
    mean: Option[Double] = None,
    lower: Option[Double] = None,
    upper: Option[Double] = None,
    binLower: Option[Double] = None,
    binUpper: Option[Double] = None,
    binWidth: Option[Double] = None,
    binMidpoint: Option[Double] = None
):
  def get[A](aesthetic: ComputedAesthetic[A]): Option[A] =
    aesthetic match
      case ComputedAesthetic.Count       => count
      case ComputedAesthetic.Proportion  => proportion
      case ComputedAesthetic.Density     => density
      case ComputedAesthetic.Position    => position
      case ComputedAesthetic.Mean        => mean
      case ComputedAesthetic.Lower       => lower
      case ComputedAesthetic.Upper       => upper
      case ComputedAesthetic.BinLower    => binLower
      case ComputedAesthetic.BinUpper    => binUpper
      case ComputedAesthetic.BinWidth    => binWidth
      case ComputedAesthetic.BinMidpoint => binMidpoint

  /** Computed keys present in this inspection view. */
  def aesthetics: Set[ComputedAesthetic[?]] =
    val out = Set.newBuilder[ComputedAesthetic[?]]
    if count.nonEmpty then out += ComputedAesthetic.Count
    if proportion.nonEmpty then out += ComputedAesthetic.Proportion
    if density.nonEmpty then out += ComputedAesthetic.Density
    if position.nonEmpty then out += ComputedAesthetic.Position
    if mean.nonEmpty then out += ComputedAesthetic.Mean
    if lower.nonEmpty then out += ComputedAesthetic.Lower
    if upper.nonEmpty then out += ComputedAesthetic.Upper
    if binLower.nonEmpty then out += ComputedAesthetic.BinLower
    if binUpper.nonEmpty then out += ComputedAesthetic.BinUpper
    if binWidth.nonEmpty then out += ComputedAesthetic.BinWidth
    if binMidpoint.nonEmpty then out += ComputedAesthetic.BinMidpoint
    out.result()

object ComputedValues:
  val empty: ComputedValues =
    ComputedValues()

  private[intaglio] def from[Row](row: StatRow[Row]): ComputedValues =
    row match
      case _: StatRow.Identity[?] =>
        empty
      case output: StatRow.Counted[?] =>
        ComputedValues(
          count = Some(output.count.toDouble),
          proportion = Some(output.proportion)
        )
      case output: StatRow.Binned[?] =>
        ComputedValues(
          count = Some(output.count.toDouble),
          proportion = Some(output.proportion),
          density = Some(output.density),
          binLower = Some(output.binLower),
          binUpper = Some(output.binUpper),
          binWidth = Some(output.binWidth),
          binMidpoint = Some(output.binMidpoint)
        )
      case output: StatRow.Summarized[?] =>
        ComputedValues(
          count = Some(output.count.toDouble),
          position = Some(output.position),
          mean = Some(output.mean),
          lower = Some(output.lower),
          upper = Some(output.upper)
        )
      case output: StatRow.Density[?] =>
        ComputedValues(
          count = Some(output.count),
          density = Some(output.density),
          position = Some(output.position)
        )

/** One typed output row from a statistic. `members` makes aggregation inspectable; `source` is the
  * stable representative used by source-oriented diagnostics. Required statistic outputs are
  * ordinary fields on the corresponding subtype, never absent entries in an optional record.
  */
sealed trait StatRow[+Row]:
  def source: Row
  def members: Vector[Row]
  def category: Option[String]
  def kind: String

  /** Generic inspection is deliberately derived from the typed output row. */
  final def computed: ComputedValues =
    ComputedValues.from(this)

object StatRow:
  /** Identity preserves one source row and has no computed fields. */
  final case class Identity[+Row](source: Row) extends StatRow[Row]:
    val members: Vector[Row] = Vector(source)
    val category: Option[String] = None
    val kind: String = "identity"

  /** Required output of `stat_count`. */
  final case class Counted[+Row](
      source: Row,
      members: Vector[Row],
      level: String,
      count: Int,
      proportion: Double
  ) extends StatRow[Row]:
    require(members.nonEmpty, "`members` must be non-empty")
    require(count == members.length, "`count` must equal `members.length`")
    require(proportion.isFinite && proportion >= 0.0 && proportion <= 1.0)

    val category: Option[String] = Some(level)
    val kind: String = "count"

  /** Required output of `stat_bin`. Width, midpoint, and density are total derived values. */
  final case class Binned[+Row](
      source: Row,
      members: Vector[Row],
      count: Int,
      proportion: Double,
      density: Double,
      binLower: Double,
      binUpper: Double
  ) extends StatRow[Row]:
    require(members.nonEmpty, "`members` must be non-empty")
    require(count == members.length, "`count` must equal `members.length`")
    require(proportion.isFinite && proportion >= 0.0 && proportion <= 1.0)
    require(density.isFinite && density >= 0.0)
    require(binLower.isFinite && binUpper.isFinite && binLower < binUpper)

    val category: Option[String] = None
    val kind: String = "bin"
    val binWidth: Double = binUpper - binLower
    val binMidpoint: Double = binLower + binWidth / 2.0

  /** Required output of `stat_summary`. */
  final case class Summarized[+Row](
      source: Row,
      members: Vector[Row],
      position: Double,
      mean: Double,
      lower: Double,
      upper: Double,
      count: Int
  ) extends StatRow[Row]:
    require(members.nonEmpty, "`members` must be non-empty")
    require(count == members.length, "`count` must equal `members.length`")
    require(Vector(position, mean, lower, upper).forall(_.isFinite))
    require(lower <= mean && mean <= upper, "summary bounds must contain the mean")

    val category: Option[String] = None
    val kind: String = "summary"

  /** Required output of `stat_density`. `count` is the ggplot-compatible scaled density. */
  final case class Density[+Row](
      source: Row,
      members: Vector[Row],
      position: Double,
      density: Double,
      sampleSize: Int
  ) extends StatRow[Row]:
    require(members.nonEmpty, "`members` must be non-empty")
    require(sampleSize == members.length, "`sampleSize` must equal `members.length`")
    require(position.isFinite)
    require(density.isFinite && density >= 0.0)

    val category: Option[String] = None
    val kind: String = "density"
    val count: Double = density * sampleSize.toDouble

/** Immutable, typed output of a statistical layer transformation. */
final case class StatFrame[Row] private[intaglio] (
    rows: Vector[StatRow[Row]],
    private val declaredAesthetics: Set[ComputedAesthetic[?]]
):
  /** The declaration remains available for an empty result; otherwise keys are derived from the
    * typed rows themselves.
    */
  def computedAesthetics: Set[ComputedAesthetic[?]] =
    if rows.isEmpty then declaredAesthetics
    else rows.iterator.flatMap(_.computed.aesthetics).toSet

enum CountOrder:
  /** Preserve the first occurrence of every category. */
  case Encountered

  /** Use platform-stable Unicode lexicographic order. */
  case Lexicographic

  /** Follow declared levels, then append undeclared observed levels in first occurrence order.
    */
  case Declared(domain: DiscreteDomain)

  private[intaglio] def arrange(observed: Vector[String]): Vector[String] =
    val distinct = observed.distinct
    this match
      case Encountered      => distinct
      case Lexicographic    => distinct.sorted
      case Declared(domain) =>
        val levels = domain.levels
        levels.filter(distinct.contains) ++ distinct.filterNot(levels.contains)

object CountOrder:
  def declared(levels: Vector[String]): Either[GraphicsError, CountOrder] =
    DiscreteDomain.ordered(levels).map(CountOrder.Declared(_))

  def declaredUnsafe(levels: Vector[String]): CountOrder =
    declared(levels).orThrow

opaque type BinCount = Int

object BinCount:
  def apply(value: Int): Either[GraphicsError, BinCount] =
    if value >= 1 then Right(value)
    else Left(GraphicsError.InvalidStatParameter("bin", "bin count >= 1", value.toString))

  def unsafe(value: Int): BinCount =
    apply(value).orThrow

  extension (value: BinCount) def toInt: Int = value

opaque type BinWidth = Double

object BinWidth:
  def apply(value: Double): Either[GraphicsError, BinWidth] =
    if value.isFinite && value > 0.0 then Right(value)
    else Left(GraphicsError.InvalidStatParameter("bin", "finite bin width > 0", value.toString))

  def unsafe(value: Double): BinWidth =
    apply(value).orThrow

  extension (value: BinWidth) def toDouble: Double = value

/** A histogram partition chosen by count, width, or explicit break points. Construction is
  * validated so the statistical kernel never sees an empty or incoherent partition.
  */
sealed trait HistogramBins

object HistogramBins:
  private final case class ByCount(value: BinCount) extends HistogramBins
  private final case class ByWidth(value: BinWidth) extends HistogramBins
  private final case class AtBreaks(values: Vector[Double]) extends HistogramBins

  val default: HistogramBins =
    ByCount(BinCount.unsafe(30))

  def count(value: Int): Either[GraphicsError, HistogramBins] =
    BinCount(value).map(ByCount(_))

  def countUnsafe(value: Int): HistogramBins =
    count(value).orThrow

  def width(value: Double): Either[GraphicsError, HistogramBins] =
    BinWidth(value).map(ByWidth(_))

  def widthUnsafe(value: Double): HistogramBins =
    width(value).orThrow

  def breaks(values: Vector[Double]): Either[GraphicsError, HistogramBins] =
    if values.length < 2 then
      Left(GraphicsError.InvalidStatParameter("bin", "at least two breaks", values.mkString(", ")))
    else if values.exists(value => !value.isFinite) then
      Left(GraphicsError.InvalidStatParameter("bin", "finite breaks", values.mkString(", ")))
    else if values.sliding(2).exists(pair => pair(0) >= pair(1)) then
      Left(
        GraphicsError
          .InvalidStatParameter("bin", "strictly increasing breaks", values.mkString(", "))
      )
    else Right(AtBreaks(values))

  def breaksUnsafe(values: Vector[Double]): HistogramBins =
    breaks(values).orThrow

  private[intaglio] def partition(
      spec: HistogramBins,
      minimum: Double,
      maximum: Double
  ): Vector[Double] =
    spec match
      case ByCount(value) =>
        val count = value.toInt
        if minimum == maximum then Vector(minimum - 0.5, maximum + 0.5)
        else
          Vector.tabulate(count + 1)(idx =>
            minimum + (maximum - minimum) * idx.toDouble / count.toDouble
          )
      case ByWidth(value) =>
        val width = value.toDouble
        val lower = math.floor(minimum / width) * width
        val upper = math.ceil(maximum / width) * width
        val bins = math.max(1, math.ceil((upper - lower) / width).toInt)
        Vector.tabulate(bins + 1)(idx => lower + idx.toDouble * width)
      case AtBreaks(values) => values

  private[intaglio] def isExplicit(spec: HistogramBins): Boolean =
    spec.isInstanceOf[AtBreaks]

enum SummaryInterval:
  /** Arithmetic mean plus or minus one sample standard error. */
  case StandardError

  /** Arithmetic mean with the observed minimum and maximum. */
  case Range

opaque type DensityBandwidth = Double

object DensityBandwidth:
  def apply(value: Double): Either[GraphicsError, DensityBandwidth] =
    if value.isFinite && value > 0.0 then Right(value)
    else Left(GraphicsError.InvalidStatParameter("density", "finite bandwidth > 0", value.toString))

  def unsafe(value: Double): DensityBandwidth =
    apply(value).orThrow

  extension (value: DensityBandwidth) def toDouble: Double = value

opaque type DensityPoints = Int

object DensityPoints:
  def apply(value: Int): Either[GraphicsError, DensityPoints] =
    if value >= 2 then Right(value)
    else Left(GraphicsError.InvalidStatParameter("density", "grid points >= 2", value.toString))

  def unsafe(value: Int): DensityPoints =
    apply(value).orThrow

  extension (value: DensityPoints) def toInt: Int = value

/** Validated Gaussian kernel-density configuration. When bandwidth is absent, the transform uses
  * R's `bw.nrd0` rule. An absent domain spans the observed data exactly.
  */
final case class DensityConfig private (
    bandwidth: Option[DensityBandwidth],
    points: DensityPoints,
    domain: Option[Interval]
)

object DensityConfig:
  val default: DensityConfig =
    DensityConfig(None, DensityPoints.unsafe(512), None)

  def automatic(
      points: Int = 512,
      domain: Option[Interval] = None
  ): Either[GraphicsError, DensityConfig] =
    DensityPoints(points).map(DensityConfig(None, _, domain))

  def fixed(
      bandwidth: Double,
      points: Int = 512,
      domain: Option[Interval] = None
  ): Either[GraphicsError, DensityConfig] =
    for
      resolvedBandwidth <- DensityBandwidth(bandwidth)
      resolvedPoints <- DensityPoints(points)
    yield DensityConfig(Some(resolvedBandwidth), resolvedPoints, domain)

  def fixedUnsafe(
      bandwidth: Double,
      points: Int = 512,
      domain: Option[Interval] = None
  ): DensityConfig =
    fixed(bandwidth, points, domain).orThrow

private[intaglio] object DensityMath:
  /** R's `bw.nrd0`: the standard deviation or robust IQR scale, with the same constant-data
    * fallbacks, followed by Silverman's 0.9 rule.
    */
  def nrd0(values: Array[Double]): Double =
    val sorted = values.clone()
    scala.util.Sorting.quickSort(sorted)
    var sum = 0.0
    var sumIndex = 0
    while sumIndex < values.length do
      sum += values(sumIndex)
      sumIndex += 1
    val mean = sum / values.length.toDouble
    var sumSquares = 0.0
    var index = 0
    while index < values.length do
      val centered = values(index) - mean
      sumSquares += centered * centered
      index += 1
    val standardDeviation = math.sqrt(sumSquares / (values.length - 1).toDouble)
    val robust = (quantile(sorted, 0.75) - quantile(sorted, 0.25)) / 1.34
    var scale = math.min(standardDeviation, robust)
    if !(scale > 0.0) then scale = standardDeviation
    if !(scale > 0.0) then scale = math.abs(values.head)
    if !(scale > 0.0) then scale = 1.0
    0.9 * scale * math.pow(values.length.toDouble, -0.2)

  private def quantile(sorted: Array[Double], probability: Double): Double =
    val position = (sorted.length - 1).toDouble * probability
    val lower = math.floor(position).toInt
    val upper = math.ceil(position).toInt
    val fraction = position - lower.toDouble
    sorted(lower) + fraction * (sorted(upper) - sorted(lower))

sealed trait Stat[-Row]:
  def label: String

object Stat:
  case object Identity extends Stat[Any]:
    override val label: String = "identity"

  final case class Count[Row](
      x: Row => String,
      order: CountOrder = CountOrder.Encountered,
      scaleName: GraphicsName = GraphicsName.unsafe("x"),
      padding: BandPadding = BandPadding.default,
      group: Option[Row => String] = None
  ) extends Stat[Row]:
    override val label: String = "count"

  final case class Bin[Row](x: Row => Double, bins: HistogramBins = HistogramBins.default)
      extends Stat[Row]:
    override val label: String = "bin"

  final case class Summary[Row](
      x: Row => Double,
      y: Row => Double,
      interval: SummaryInterval = SummaryInterval.StandardError
  ) extends Stat[Row]:
    override val label: String = "summary"

  final case class Density[Row](x: Row => Double, config: DensityConfig = DensityConfig.default)
      extends Stat[Row]:
    override val label: String = "density"
