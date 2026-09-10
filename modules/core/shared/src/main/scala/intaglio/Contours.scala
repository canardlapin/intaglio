package intaglio

opaque type ContourLevel = Double

object ContourLevel:
  def apply(value: Double): Either[GraphicsError, ContourLevel] =
    if value.isFinite then Right(value)
    else Left(GraphicsError.InvalidContourLevels("finite values", value.toString))

  def unsafe(value: Double): ContourLevel =
    apply(value).orThrow

  extension (level: ContourLevel) def toDouble: Double = level

final case class ContourLevels private (values: Vector[ContourLevel]):
  require(values.nonEmpty, "`values` must be non-empty")

object ContourLevels:
  def at(values: Vector[Double]): Either[GraphicsError, ContourLevels] =
    if values.isEmpty then Left(GraphicsError.InvalidContourLevels("at least one level", "empty"))
    else if values.exists(value => !value.isFinite) then
      Left(GraphicsError.InvalidContourLevels("finite levels", values.mkString(", ")))
    else if values.zip(values.drop(1)).exists { case (left, right) => left >= right } then
      Left(GraphicsError.InvalidContourLevels("strictly increasing levels", values.mkString(", ")))
    else Right(ContourLevels(values.map(ContourLevel.unsafe)))

  def atUnsafe(values: Vector[Double]): ContourLevels =
    at(values).orThrow

  /** Equally spaced interior levels. Field extrema are excluded so ordinary contours do not
    * collapse onto a grid boundary.
    */
  def between(lower: Double, upper: Double, count: Int): Either[GraphicsError, ContourLevels] =
    if !lower.isFinite || !upper.isFinite || lower >= upper then
      Left(GraphicsError.InvalidContourLevels("finite increasing bounds", s"[$lower, $upper]"))
    else if count < 1 then Left(GraphicsError.InvalidContourLevels("count >= 1", count.toString))
    else
      at(
        Vector.tabulate(count)(index =>
          lower + (index + 1).toDouble * (upper - lower) / (count + 1).toDouble
        )
      )

  def forField(field: ScalarField2D, count: Int = 10): Either[GraphicsError, ContourLevels] =
    val lower = field.samples.min
    val upper = field.samples.max
    if lower == upper then
      Left(GraphicsError.InvalidContourLevels("a non-constant field", lower.toString))
    else between(lower, upper, count)

enum SaddleTiePolicy:
  case ConnectAbove
  case ConnectBelow

final case class ContourConfig(
    levels: ContourLevels,
    saddleTie: SaddleTiePolicy = SaddleTiePolicy.ConnectAbove
)

final case class FieldPoint private (x: Double, y: Double):
  require(x.isFinite && y.isFinite, "field point coordinates must be finite")

object FieldPoint:
  def apply(x: Double, y: Double): Either[GraphicsError, FieldPoint] =
    if x.isFinite && y.isFinite then Right(new FieldPoint(x, y))
    else Left(GraphicsError.InvalidContourPoint(x, y))

  private[intaglio] def unsafe(x: Double, y: Double): FieldPoint =
    new FieldPoint(x, y)

final case class ContourPath private (points: Vector[FieldPoint]):
  require(points.length >= 2, "a contour path requires at least two points")

  def isClosed: Boolean =
    points.head == points.last

object ContourPath:
  def apply(points: Vector[FieldPoint]): Either[GraphicsError, ContourPath] =
    if points.length >= 2 then Right(new ContourPath(points))
    else Left(GraphicsError.InvalidGeometrySize("contour path", 2, points.length))

  private[intaglio] def unsafe(points: Vector[FieldPoint]): ContourPath =
    new ContourPath(points)

final case class ContourLine(level: ContourLevel, paths: Vector[ContourPath]):
  require(paths.nonEmpty, "a contour line requires at least one path")

final case class ContourVertex(
    x: Double,
    y: Double,
    level: Double,
    pathId: String,
    pointIndex: Int
)

final case class ContourSet private (lines: Vector[ContourLine]):
  def vertices: Vector[ContourVertex] =
    lines.zipWithIndex.flatMap { case (line, levelIndex) =>
      line.paths.zipWithIndex.flatMap { case (path, pathIndex) =>
        val id = s"contour-$levelIndex-$pathIndex"
        path.points.zipWithIndex.map { case (point, pointIndex) =>
          ContourVertex(point.x, point.y, line.level.toDouble, id, pointIndex)
        }
      }
    }

object ContourSet:
  def extract(field: ScalarField2D, config: ContourConfig): Either[GraphicsError, ContourSet] =
    if field.width < 2 || field.height < 2 then
      Left(GraphicsError.ContourGridTooSmall(field.width, field.height))
    else
      Right(
        ContourSet(
          config.levels.values.flatMap { level =>
            val paths = MarchingSquares.paths(field, level, config.saddleTie)
            Option.when(paths.nonEmpty)(ContourLine(level, paths))
          }
        )
      )

  def extract(field: ScalarField2D, levels: ContourLevels): Either[GraphicsError, ContourSet] =
    extract(field, ContourConfig(levels))

private[intaglio] object MarchingSquares:
  private final case class Segment(start: FieldPoint, end: FieldPoint)
  private final case class VertexKey(x: Long, y: Long)

  def paths(
      field: ScalarField2D,
      level: ContourLevel,
      tiePolicy: SaddleTiePolicy
  ): Vector[ContourPath] =
    val segments = Vector.newBuilder[Segment]
    val target = level.toDouble
    var yIndex = 0
    while yIndex < field.height - 1 do
      var xIndex = 0
      while xIndex < field.width - 1 do
        appendCellSegments(field, xIndex, yIndex, target, tiePolicy, segments)
        xIndex += 1
      yIndex += 1
    stitch(segments.result())

  private def appendCellSegments(
      field: ScalarField2D,
      xIndex: Int,
      yIndex: Int,
      level: Double,
      tiePolicy: SaddleTiePolicy,
      out: scala.collection.mutable.Builder[Segment, Vector[Segment]]
  ): Unit =
    val bottomLeft = field.valueUnsafe(xIndex, yIndex)
    val bottomRight = field.valueUnsafe(xIndex + 1, yIndex)
    val topRight = field.valueUnsafe(xIndex + 1, yIndex + 1)
    val topLeft = field.valueUnsafe(xIndex, yIndex + 1)
    var code = 0
    if bottomLeft >= level then code |= 1
    if bottomRight >= level then code |= 2
    if topRight >= level then code |= 4
    if topLeft >= level then code |= 8

    val pairs = code match
      case 0 | 15 => Vector.empty
      case 1      => Vector(3 -> 0)
      case 2      => Vector(0 -> 1)
      case 3      => Vector(3 -> 1)
      case 4      => Vector(1 -> 2)
      case 5      =>
        if connectAbove(bottomLeft, bottomRight, topRight, topLeft, level, tiePolicy) then
          Vector(0 -> 1, 2 -> 3)
        else Vector(3 -> 0, 1 -> 2)
      case 6  => Vector(0 -> 2)
      case 7  => Vector(3 -> 2)
      case 8  => Vector(2 -> 3)
      case 9  => Vector(0 -> 2)
      case 10 =>
        if connectAbove(bottomLeft, bottomRight, topRight, topLeft, level, tiePolicy) then
          Vector(3 -> 0, 1 -> 2)
        else Vector(0 -> 1, 2 -> 3)
      case 11 => Vector(1 -> 2)
      case 12 => Vector(3 -> 1)
      case 13 => Vector(0 -> 1)
      case 14 => Vector(3 -> 0)

    if pairs.nonEmpty then
      val x0 = field.xAxis.coordinateUnsafe(xIndex)
      val x1 = field.xAxis.coordinateUnsafe(xIndex + 1)
      val y0 = field.yAxis.coordinateUnsafe(yIndex)
      val y1 = field.yAxis.coordinateUnsafe(yIndex + 1)
      def edge(edgeIndex: Int): FieldPoint =
        edgeIndex match
          case 0 => interpolate(x0, y0, bottomLeft, x1, y0, bottomRight, level)
          case 1 => interpolate(x1, y0, bottomRight, x1, y1, topRight, level)
          case 2 => interpolate(x0, y1, topLeft, x1, y1, topRight, level)
          case 3 => interpolate(x0, y0, bottomLeft, x0, y1, topLeft, level)
      pairs.foreach { case (start, end) => out += Segment(edge(start), edge(end)) }

  /** Evaluate the bilinear interpolant at its saddle. Degenerate/equal cases use the explicit tie
    * policy, making topology stable on both platforms.
    */
  private def connectAbove(
      bottomLeft: Double,
      bottomRight: Double,
      topRight: Double,
      topLeft: Double,
      level: Double,
      tiePolicy: SaddleTiePolicy
  ): Boolean =
    val relativeBottomLeft = bottomLeft - level
    val relativeBottomRight = bottomRight - level
    val relativeTopRight = topRight - level
    val relativeTopLeft = topLeft - level
    val scale = math.max(
      math.max(math.abs(relativeBottomLeft), math.abs(relativeBottomRight)),
      math.max(math.abs(relativeTopRight), math.abs(relativeTopLeft))
    )
    val normalizedBottomLeft = relativeBottomLeft / scale
    val normalizedBottomRight = relativeBottomRight / scale
    val normalizedTopRight = relativeTopRight / scale
    val normalizedTopLeft = relativeTopLeft / scale
    val denominator =
      normalizedBottomLeft - normalizedBottomRight - normalizedTopLeft + normalizedTopRight
    val denominatorTolerance = 8.0 * math.ulp(1.0)
    val relativeSaddle =
      if math.abs(denominator) <= denominatorTolerance then
        val average = NumericalMath.CompensatedSum()
        average.add(normalizedBottomLeft)
        average.add(normalizedBottomRight)
        average.add(normalizedTopRight)
        average.add(normalizedTopLeft)
        average.result / 4.0
      else
        (normalizedBottomLeft * normalizedTopRight -
          normalizedBottomRight * normalizedTopLeft) / denominator
    if relativeSaddle > 0.0 then true
    else if relativeSaddle < 0.0 then false
    else tiePolicy == SaddleTiePolicy.ConnectAbove

  private def interpolate(
      x0: Double,
      y0: Double,
      value0: Double,
      x1: Double,
      y1: Double,
      value1: Double,
      level: Double
  ): FieldPoint =
    val relative0 = value0 - level
    val relative1 = value1 - level
    val scale = math.max(math.abs(relative0), math.abs(relative1))
    val normalized0 = relative0 / scale
    val normalized1 = relative1 / scale
    val raw = normalized0 / (normalized0 - normalized1)
    val t = math.max(0.0, math.min(1.0, raw))
    FieldPoint.unsafe(x0 + t * (x1 - x0), y0 + t * (y1 - y0))

  private def stitch(segments: Vector[Segment]): Vector[ContourPath] =
    val adjacency =
      scala.collection.mutable.HashMap.empty[VertexKey, scala.collection.mutable.ArrayBuffer[Int]]
    var index = 0
    while index < segments.length do
      adjacency.getOrElseUpdate(
        key(segments(index).start),
        scala.collection.mutable.ArrayBuffer.empty
      ) += index
      adjacency.getOrElseUpdate(
        key(segments(index).end),
        scala.collection.mutable.ArrayBuffer.empty
      ) += index
      index += 1
    val visited = Array.fill(segments.length)(false)
    val paths = Vector.newBuilder[ContourPath]
    index = 0
    while index < segments.length do
      if !visited(index) then
        visited(index) = true
        val center =
          scala.collection.mutable.ArrayBuffer(segments(index).start, segments(index).end)
        extend(center, atEnd = true, segments, adjacency, visited)
        extend(center, atEnd = false, segments, adjacency, visited)
        paths += ContourPath.unsafe(center.toVector)
      index += 1
    paths.result()

  private def extend(
      points: scala.collection.mutable.ArrayBuffer[FieldPoint],
      atEnd: Boolean,
      segments: Vector[Segment],
      adjacency: scala.collection.mutable.HashMap[VertexKey, scala.collection.mutable.ArrayBuffer[
        Int
      ]],
      visited: Array[Boolean]
  ): Unit =
    var continue = true
    while continue do
      val endpoint = if atEnd then points.last else points.head
      val candidates =
        adjacency.getOrElse(key(endpoint), scala.collection.mutable.ArrayBuffer.empty)
      var candidateIndex = 0
      var next = -1
      while candidateIndex < candidates.length && next < 0 do
        if !visited(candidates(candidateIndex)) then next = candidates(candidateIndex)
        candidateIndex += 1
      if next < 0 then continue = false
      else
        visited(next) = true
        val segment = segments(next)
        val other = if key(segment.start) == key(endpoint) then segment.end else segment.start
        if atEnd then points += other else points.prepend(other)

  private def key(point: FieldPoint): VertexKey =
    VertexKey(
      java.lang.Double.doubleToLongBits(point.x),
      java.lang.Double.doubleToLongBits(point.y)
    )
