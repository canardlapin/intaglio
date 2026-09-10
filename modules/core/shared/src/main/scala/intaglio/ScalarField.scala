package intaglio

/** Where values live on a regular axis. Cell-centered axes describe aggregate bins; vertex-centered
  * axes describe samples at both domain boundaries.
  */
enum GridSampling(val minimumSamples: Int):
  case CellCentered extends GridSampling(1)
  case VertexCentered extends GridSampling(2)

/** A checked, regular one-dimensional sampling axis. The domain denotes cell edges for
  * cell-centered sampling and endpoint coordinates for vertex-centered sampling.
  */
final case class RegularGridAxis private (
    domain: Interval,
    sampleCount: Int,
    sampling: GridSampling
):
  require(sampleCount >= sampling.minimumSamples, "`sampleCount` is valid for `sampling`")
  require(domain.width > 0.0, "`domain` must be non-degenerate")

  def step: Double =
    sampling match
      case GridSampling.CellCentered   => domain.width / sampleCount.toDouble
      case GridSampling.VertexCentered => domain.width / (sampleCount - 1).toDouble

  def coordinate(index: Int): Option[Double] =
    Option.when(index >= 0 && index < sampleCount)(coordinateUnsafe(index))

  /** Bounds used when a sampled value is shown as a tile. Vertex-centered samples use their Voronoi
    * cell, extending half a step beyond each end.
    */
  def tileBounds(index: Int): Option[Interval] =
    Option.when(index >= 0 && index < sampleCount)(tileBoundsUnsafe(index))

  private[intaglio] def coordinateUnsafe(index: Int): Double =
    sampling match
      case GridSampling.CellCentered   => domain.lower + (index.toDouble + 0.5) * step
      case GridSampling.VertexCentered => domain.lower + index.toDouble * step

  private[intaglio] def tileBoundsUnsafe(index: Int): Interval =
    sampling match
      case GridSampling.CellCentered =>
        val lower = domain.lower + index.toDouble * step
        Interval.unsafe(lower, lower + step)
      case GridSampling.VertexCentered =>
        val center = coordinateUnsafe(index)
        Interval.unsafe(center - step / 2.0, center + step / 2.0)

object RegularGridAxis:
  def cellCentered(
      lower: Double,
      upper: Double,
      cells: Int
  ): Either[GraphicsError, RegularGridAxis] =
    create(lower, upper, cells, GridSampling.CellCentered)

  def vertexCentered(
      lower: Double,
      upper: Double,
      points: Int
  ): Either[GraphicsError, RegularGridAxis] =
    create(lower, upper, points, GridSampling.VertexCentered)

  def cellCenteredUnsafe(lower: Double, upper: Double, cells: Int): RegularGridAxis =
    cellCentered(lower, upper, cells).orThrow

  def vertexCenteredUnsafe(lower: Double, upper: Double, points: Int): RegularGridAxis =
    vertexCentered(lower, upper, points).orThrow

  private def create(
      lower: Double,
      upper: Double,
      samples: Int,
      sampling: GridSampling
  ): Either[GraphicsError, RegularGridAxis] =
    if samples < sampling.minimumSamples then
      Left(GraphicsError.InvalidGridSize(sampling.toString, sampling.minimumSamples, samples))
    else if !lower.isFinite || !upper.isFinite || lower >= upper then
      Left(GraphicsError.InvalidGridDomain(lower, upper))
    else Right(new RegularGridAxis(Interval.unsafe(lower, upper), samples, sampling))

/** One display cell derived from a scalar field. It is intentionally an ordinary immutable row so
  * the generic grammar, scale trainer, facet compiler, and every renderer remain unaware of
  * scalar-field storage.
  */
final case class ScalarCell private[intaglio] (
    xIndex: Int,
    yIndex: Int,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    value: Double
)

/** Immutable regular 2D scalar field with x-fastest row-major storage. Construction copies and
  * validates the input once; numeric kernels may use the package-private zero-copy constructor
  * after proving the same contract.
  */
final class ScalarField2D private (
    val xAxis: RegularGridAxis,
    val yAxis: RegularGridAxis,
    private val values: Array[Double]
):
  val width: Int = xAxis.sampleCount
  val height: Int = yAxis.sampleCount
  val sampleCount: Int = width * height

  def value(xIndex: Int, yIndex: Int): Either[GraphicsError, Double] =
    if xIndex < 0 || xIndex >= width || yIndex < 0 || yIndex >= height then
      Left(GraphicsError.ScalarFieldIndexOutsideBounds(xIndex, yIndex, width, height))
    else Right(valueUnsafe(xIndex, yIndex))

  def samples: Vector[Double] =
    values.toVector

  def cells: Vector[ScalarCell] =
    Vector.tabulate(sampleCount) { index =>
      val xIndex = index % width
      val yIndex = index / width
      val xBounds = xAxis.tileBoundsUnsafe(xIndex)
      val yBounds = yAxis.tileBoundsUnsafe(yIndex)
      ScalarCell(
        xIndex,
        yIndex,
        xAxis.coordinateUnsafe(xIndex),
        yAxis.coordinateUnsafe(yIndex),
        xBounds.width,
        yBounds.width,
        values(index)
      )
    }

  def mapValues(f: Double => Double): Either[GraphicsError, ScalarField2D] =
    val mapped = Array.ofDim[Double](values.length)
    var index = 0
    while index < values.length do
      mapped(index) = f(values(index))
      index += 1
    ScalarField2D.fromArray(xAxis, yAxis, mapped, copy = false)

  private[intaglio] def valueUnsafe(xIndex: Int, yIndex: Int): Double =
    values(yIndex * width + xIndex)

  override def equals(other: Any): Boolean =
    other match
      case that: ScalarField2D =>
        xAxis == that.xAxis && yAxis == that.yAxis && values.sameElements(that.values)
      case _ => false

  override def hashCode(): Int =
    var result = 31 * xAxis.hashCode() + yAxis.hashCode()
    var index = 0
    while index < values.length do
      result = 31 * result + java.lang.Double.hashCode(values(index))
      index += 1
    result

  override def toString: String =
    s"ScalarField2D(${width}x$height, ${xAxis.sampling}, ${yAxis.sampling})"

object ScalarField2D:
  def apply(
      xAxis: RegularGridAxis,
      yAxis: RegularGridAxis,
      values: IterableOnce[Double]
  ): Either[GraphicsError, ScalarField2D] =
    fromArray(xAxis, yAxis, values.iterator.toArray, copy = false)

  def tabulate(
      xAxis: RegularGridAxis,
      yAxis: RegularGridAxis
  )(sample: (Double, Double) => Double): Either[GraphicsError, ScalarField2D] =
    val values = Array.ofDim[Double](xAxis.sampleCount * yAxis.sampleCount)
    var yIndex = 0
    while yIndex < yAxis.sampleCount do
      val y = yAxis.coordinateUnsafe(yIndex)
      var xIndex = 0
      while xIndex < xAxis.sampleCount do
        values(yIndex * xAxis.sampleCount + xIndex) = sample(xAxis.coordinateUnsafe(xIndex), y)
        xIndex += 1
      yIndex += 1
    fromArray(xAxis, yAxis, values, copy = false)

  def unsafe(
      xAxis: RegularGridAxis,
      yAxis: RegularGridAxis,
      values: IterableOnce[Double]
  ): ScalarField2D =
    apply(xAxis, yAxis, values).orThrow

  private[intaglio] def unsafeArray(
      xAxis: RegularGridAxis,
      yAxis: RegularGridAxis,
      values: Array[Double]
  ): ScalarField2D =
    new ScalarField2D(xAxis, yAxis, values)

  private def fromArray(
      xAxis: RegularGridAxis,
      yAxis: RegularGridAxis,
      values: Array[Double],
      copy: Boolean
  ): Either[GraphicsError, ScalarField2D] =
    val expected = xAxis.sampleCount * yAxis.sampleCount
    if values.length != expected then
      Left(GraphicsError.ScalarFieldValueCountMismatch(expected, values.length))
    else
      var index = 0
      var invalid: Option[(Int, Double)] = None
      while index < values.length && invalid.isEmpty do
        if !values(index).isFinite then invalid = Some(index -> values(index))
        index += 1
      invalid match
        case Some((invalidIndex, value)) =>
          Left(GraphicsError.NonFiniteScalarFieldValue(invalidIndex, value))
        case None =>
          Right(new ScalarField2D(xAxis, yAxis, if copy then values.clone() else values))
