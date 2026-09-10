package intaglio

/** Value stored in each rectangular 2D bin. */
enum Bin2DValue:
  case Count
  case Proportion

/** Validated rectangular-binning plan. Fixed domains are useful for parity, composition, and
  * repeated plots; absent domains train from finite input.
  */
final case class Bin2DConfig private (
    xBins: BinCount,
    yBins: BinCount,
    xDomain: Option[Interval],
    yDomain: Option[Interval],
    value: Bin2DValue
)

object Bin2DConfig:
  val default: Bin2DConfig =
    Bin2DConfig(BinCount.unsafe(30), BinCount.unsafe(30), None, None, Bin2DValue.Count)

  def apply(
      xBins: Int = 30,
      yBins: Int = 30,
      xDomain: Option[Interval] = None,
      yDomain: Option[Interval] = None,
      value: Bin2DValue = Bin2DValue.Count
  ): Either[GraphicsError, Bin2DConfig] =
    for
      x <- BinCount(xBins)
      y <- BinCount(yBins)
      _ <-
        if x.toInt.toLong * y.toInt.toLong <= Int.MaxValue.toLong then Right(())
        else
          Left(
            GraphicsError
              .InvalidStatParameter("bin2d", "representable cell count", s"${x.toInt}x${y.toInt}")
          )
    yield Bin2DConfig(x, y, xDomain, yDomain, value)

  def unsafe(
      xBins: Int = 30,
      yBins: Int = 30,
      xDomain: Option[Interval] = None,
      yDomain: Option[Interval] = None,
      value: Bin2DValue = Bin2DValue.Count
  ): Bin2DConfig =
    apply(xBins, yBins, xDomain, yDomain, value).orThrow

/** Gaussian 2D kernel-density plan. Fixed bandwidths are kernel standard deviations; automatic
  * bandwidths use the same `bw.nrd0` rule as the 1D density statistic, independently for x and y.
  */
final case class Kde2DConfig private (
    bandwidthX: Option[DensityBandwidth],
    bandwidthY: Option[DensityBandwidth],
    xPoints: DensityPoints,
    yPoints: DensityPoints,
    xDomain: Option[Interval],
    yDomain: Option[Interval],
    strategy: KdeStrategy
)

object Kde2DConfig:
  val default: Kde2DConfig =
    Kde2DConfig(
      None,
      None,
      DensityPoints.unsafe(100),
      DensityPoints.unsafe(100),
      None,
      None,
      KdeStrategy.Direct
    )

  def automatic(
      xPoints: Int = 100,
      yPoints: Int = 100,
      xDomain: Option[Interval] = None,
      yDomain: Option[Interval] = None,
      strategy: KdeStrategy = KdeStrategy.Direct
  ): Either[GraphicsError, Kde2DConfig] =
    for
      xGrid <- DensityPoints(xPoints)
      yGrid <- DensityPoints(yPoints)
    yield Kde2DConfig(None, None, xGrid, yGrid, xDomain, yDomain, strategy)

  def fixed(
      bandwidthX: Double,
      bandwidthY: Double,
      xPoints: Int = 100,
      yPoints: Int = 100,
      xDomain: Option[Interval] = None,
      yDomain: Option[Interval] = None,
      strategy: KdeStrategy = KdeStrategy.Direct
  ): Either[GraphicsError, Kde2DConfig] =
    for
      xBandwidth <- DensityBandwidth(bandwidthX)
      yBandwidth <- DensityBandwidth(bandwidthY)
      xGrid <- DensityPoints(xPoints)
      yGrid <- DensityPoints(yPoints)
    yield Kde2DConfig(
      Some(xBandwidth),
      Some(yBandwidth),
      xGrid,
      yGrid,
      xDomain,
      yDomain,
      strategy
    )

  def fixedUnsafe(
      bandwidthX: Double,
      bandwidthY: Double,
      xPoints: Int = 100,
      yPoints: Int = 100,
      xDomain: Option[Interval] = None,
      yDomain: Option[Interval] = None,
      strategy: KdeStrategy = KdeStrategy.Direct
  ): Kde2DConfig =
    fixed(bandwidthX, bandwidthY, xPoints, yPoints, xDomain, yDomain, strategy).orThrow

/** A renderer-neutral statistical transform whose result is a checked scalar field rather than an
  * R-style dynamically shaped row table.
  */
sealed trait FieldStat[-Row]:
  def label: String
  def compute(data: IterableOnce[Row]): Either[GraphicsError, ScalarField2D]

object FieldStat:
  final case class Bin2D[Row](
      x: Row => Double,
      y: Row => Double,
      config: Bin2DConfig = Bin2DConfig.default
  ) extends FieldStat[Row]:
    override val label: String = "bin2d"

    override def compute(data: IterableOnce[Row]): Either[GraphicsError, ScalarField2D] =
      val rows = data.iterator.toVector
      if rows.isEmpty then Left(GraphicsError.InsufficientStatData(label, 1, 0))
      else
        val xs = Array.ofDim[Double](rows.length)
        val ys = Array.ofDim[Double](rows.length)
        var index = 0
        while index < rows.length do
          xs(index) = x(rows(index))
          ys(index) = y(rows(index))
          index += 1
        for
          _ <- requireFinite(xs, Aesthetic.X.label)
          _ <- requireFinite(ys, Aesthetic.Y.label)
          xRange = config.xDomain.getOrElse(observedDomain(xs))
          yRange = config.yDomain.getOrElse(observedDomain(ys))
          _ <- requireInside(xs, Aesthetic.X.label, xRange)
          _ <- requireInside(ys, Aesthetic.Y.label, yRange)
          xAxis <- RegularGridAxis.cellCentered(xRange.lower, xRange.upper, config.xBins.toInt)
          yAxis <- RegularGridAxis.cellCentered(yRange.lower, yRange.upper, config.yBins.toInt)
          field <- bin(xs, ys, xAxis, yAxis, config.value)
        yield field

  final case class Kde2D[Row](
      x: Row => Double,
      y: Row => Double,
      config: Kde2DConfig = Kde2DConfig.default
  ) extends FieldStat[Row]:
    override val label: String = "kde2d"

    override def compute(data: IterableOnce[Row]): Either[GraphicsError, ScalarField2D] =
      val rows = data.iterator.toVector
      if rows.length < 2 then Left(GraphicsError.InsufficientStatData(label, 2, rows.length))
      else
        val xs = Array.ofDim[Double](rows.length)
        val ys = Array.ofDim[Double](rows.length)
        var index = 0
        while index < rows.length do
          xs(index) = x(rows(index))
          ys(index) = y(rows(index))
          index += 1
        for
          _ <- requireFinite(xs, Aesthetic.X.label, label)
          _ <- requireFinite(ys, Aesthetic.Y.label, label)
          _ <- requireKdeStrategy(config.strategy, label)
          xRange = config.xDomain.getOrElse(observedDomain(xs))
          yRange = config.yDomain.getOrElse(observedDomain(ys))
          xAxis <- RegularGridAxis.vertexCentered(xRange.lower, xRange.upper, config.xPoints.toInt)
          yAxis <- RegularGridAxis.vertexCentered(yRange.lower, yRange.upper, config.yPoints.toInt)
          field <- kde(
            xs,
            ys,
            xAxis,
            yAxis,
            config.bandwidthX.map(_.toDouble).getOrElse(DensityMath.nrd0(xs)),
            config.bandwidthY.map(_.toDouble).getOrElse(DensityMath.nrd0(ys))
          )
        yield field

  def bin2D[Row](
      x: Row => Double,
      y: Row => Double,
      config: Bin2DConfig = Bin2DConfig.default
  ): FieldStat[Row] =
    Bin2D(x, y, config)

  def kde2D[Row](
      x: Row => Double,
      y: Row => Double,
      config: Kde2DConfig = Kde2DConfig.default
  ): FieldStat[Row] =
    Kde2D(x, y, config)

  private def kde(
      xs: Array[Double],
      ys: Array[Double],
      xAxis: RegularGridAxis,
      yAxis: RegularGridAxis,
      bandwidthX: Double,
      bandwidthY: Double
  ): Either[GraphicsError, ScalarField2D] =
    val density = Array.ofDim[Double](xAxis.sampleCount * yAxis.sampleCount)
    val normalizer = xs.length.toDouble * bandwidthX * bandwidthY * 2.0 * math.Pi
    var yIndex = 0
    while yIndex < yAxis.sampleCount do
      val y = yAxis.coordinateUnsafe(yIndex)
      var xIndex = 0
      while xIndex < xAxis.sampleCount do
        val x = xAxis.coordinateUnsafe(xIndex)
        val sum = NumericalMath.CompensatedSum()
        var observation = 0
        while observation < xs.length do
          val zx = (x - xs(observation)) / bandwidthX
          val zy = (y - ys(observation)) / bandwidthY
          sum.add(math.exp(-0.5 * (zx * zx + zy * zy)))
          observation += 1
        density(yIndex * xAxis.sampleCount + xIndex) = sum.result / normalizer
        xIndex += 1
      yIndex += 1
    ScalarField2D(xAxis, yAxis, density)

  private def requireKdeStrategy(
      strategy: KdeStrategy,
      stat: String
  ): Either[GraphicsError, Unit] =
    strategy match
      case KdeStrategy.Direct => Right(())
      case KdeStrategy.Fft    => Left(GraphicsError.UnsupportedStatStrategy(stat, strategy.label))

  private def bin(
      xs: Array[Double],
      ys: Array[Double],
      xAxis: RegularGridAxis,
      yAxis: RegularGridAxis,
      value: Bin2DValue
  ): Either[GraphicsError, ScalarField2D] =
    val counts = Array.ofDim[Double](xAxis.sampleCount * yAxis.sampleCount)
    var index = 0
    while index < xs.length do
      val xBin = locateRightClosed(xs(index), xAxis.domain, xAxis.sampleCount)
      val yBin = locateRightClosed(ys(index), yAxis.domain, yAxis.sampleCount)
      counts(yBin * xAxis.sampleCount + xBin) += 1.0
      index += 1
    value match
      case Bin2DValue.Count =>
        ScalarField2D(xAxis, yAxis, counts)
      case Bin2DValue.Proportion =>
        val denominator = xs.length.toDouble
        index = 0
        while index < counts.length do
          counts(index) /= denominator
          index += 1
        ScalarField2D(xAxis, yAxis, counts)

  /** ggplot-compatible right closure: the lower domain boundary belongs to the first bin and an
    * internal break belongs to the bin on its left.
    */
  private def locateRightClosed(value: Double, domain: Interval, bins: Int): Int =
    if value <= domain.lower then 0
    else if value >= domain.upper then bins - 1
    else
      val raw = (value - domain.lower) / domain.width * bins.toDouble
      math.max(0, math.min(bins - 1, math.ceil(raw).toInt - 1))

  private def requireFinite(
      values: Array[Double],
      aesthetic: String,
      stat: String = "bin2d"
  ): Either[GraphicsError, Unit] =
    var index = 0
    var invalid: Option[Double] = None
    while index < values.length && invalid.isEmpty do
      if !values(index).isFinite then invalid = Some(values(index))
      index += 1
    invalid match
      case Some(value) => Left(GraphicsError.NonFiniteStatInput(stat, aesthetic, value))
      case None        => Right(())

  private def requireInside(
      values: Array[Double],
      aesthetic: String,
      domain: Interval
  ): Either[GraphicsError, Unit] =
    var index = 0
    var outside: Option[Double] = None
    while index < values.length && outside.isEmpty do
      if !domain.contains(values(index)) then outside = Some(values(index))
      index += 1
    outside match
      case Some(value) =>
        Left(
          GraphicsError.StatInputOutsideGrid("bin2d", aesthetic, value, domain.lower, domain.upper)
        )
      case None => Right(())

  private def observedDomain(values: Array[Double]): Interval =
    var lower = values(0)
    var upper = values(0)
    var index = 1
    while index < values.length do
      if values(index) < lower then lower = values(index)
      if values(index) > upper then upper = values(index)
      index += 1
    if lower == upper then Interval.unsafe(lower - 0.5, upper + 0.5)
    else Interval.unsafe(lower, upper)
