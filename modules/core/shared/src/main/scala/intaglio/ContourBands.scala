package intaglio

final case class ContourBreaks private (values: Vector[ContourLevel]):
  require(values.length >= 2, "contour band breaks require at least two values")

object ContourBreaks:
  def at(values: Vector[Double]): Either[GraphicsError, ContourBreaks] =
    if values.length < 2 then
      Left(GraphicsError.InvalidContourLevels("at least two band breaks", values.mkString(", ")))
    else
      ContourLevels.at(values).map(levels => ContourBreaks(levels.values))

  def atUnsafe(values: Vector[Double]): ContourBreaks =
    at(values).orThrow

  def forField(field: ScalarField2D, bands: Int = 10): Either[GraphicsError, ContourBreaks] =
    val lower = field.samples.min
    val upper = field.samples.max
    if bands < 1 then Left(GraphicsError.InvalidContourLevels("band count >= 1", bands.toString))
    else if lower == upper then Left(GraphicsError.InvalidContourLevels("a non-constant field", lower.toString))
    else
      ContourLevels
        .at(Vector.tabulate(bands + 1)(index => lower + index.toDouble * (upper - lower) / bands.toDouble))
        .map(levels => ContourBreaks(levels.values))

enum RingWinding:
  case CounterClockwise
  case Clockwise

final case class ContourRing private (points: Vector[FieldPoint], signedArea: Double):
  require(points.length >= 4 && points.head == points.last, "a contour ring must be closed with three vertices")
  require(signedArea != 0.0 && signedArea.isFinite, "a contour ring must have finite non-zero area")

  def winding: RingWinding =
    if signedArea > 0.0 then RingWinding.CounterClockwise else RingWinding.Clockwise

object ContourRing:
  private[intaglio] def fromClosed(points: Vector[FieldPoint]): Either[GraphicsError, ContourRing] =
    if points.length < 4 || points.head != points.last then
      Left(GraphicsError.InvalidContourTopology("ring is not closed with at least three vertices"))
    else
      val area = polygonArea(points)
      if !area.isFinite || math.abs(area) <= 1e-15 then
        Left(GraphicsError.InvalidContourTopology("ring has zero or non-finite area"))
      else Right(new ContourRing(points, area))

  private[intaglio] def polygonArea(points: Vector[FieldPoint]): Double =
    var twiceArea = 0.0
    var index = 0
    while index < points.length - 1 do
      twiceArea += points(index).x * points(index + 1).y - points(index + 1).x * points(index).y
      index += 1
    twiceArea / 2.0

final case class ContourRegion(outer: ContourRing, holes: Vector[ContourRing]):
  require(outer.winding == RingWinding.CounterClockwise, "outer ring must be counter-clockwise")
  require(holes.forall(_.winding == RingWinding.Clockwise), "hole rings must be clockwise")

final case class BandFragment private[intaglio] (points: Vector[FieldPoint]):
  require(points.length >= 3, "a band fragment requires at least three points")

  def area: Double =
    ContourRing.polygonArea(points :+ points.head)

final case class ContourBand(
    lower: ContourLevel,
    upper: ContourLevel,
    regions: Vector[ContourRegion],
    fragments: Vector[BandFragment]
):
  require(lower.toDouble < upper.toDouble, "band bounds must be increasing")
  require(regions.nonEmpty && fragments.nonEmpty, "a contour band must contain geometry")

  def midpoint: Double =
    lower.toDouble + (upper.toDouble - lower.toDouble) / 2.0

final case class ContourBandVertex(
    x: Double,
    y: Double,
    levelLow: Double,
    levelHigh: Double,
    levelMid: Double,
    regionId: String,
    ringId: String,
    pointIndex: Int
)

final case class ContourBandSet private (bands: Vector[ContourBand]):
  def vertices: Vector[ContourBandVertex] =
    bands.zipWithIndex.flatMap { case (band, bandIndex) =>
      band.regions.zipWithIndex.flatMap { case (region, regionIndex) =>
        val regionId = s"band-$bandIndex-region-$regionIndex"
        (region.outer +: region.holes).zipWithIndex.flatMap { case (ring, ringIndex) =>
          val ringId = s"$regionId-ring-$ringIndex"
          ring.points.zipWithIndex.map { case (point, pointIndex) =>
            ContourBandVertex(
              point.x,
              point.y,
              band.lower.toDouble,
              band.upper.toDouble,
              band.midpoint,
              regionId,
              ringId,
              pointIndex
            )
          }
        }
      }
    }

object ContourBandSet:
  def extract(field: ScalarField2D, breaks: ContourBreaks): Either[GraphicsError, ContourBandSet] =
    if field.width < 2 || field.height < 2 then
      Left(GraphicsError.ContourGridTooSmall(field.width, field.height))
    else
      val out = Vector.newBuilder[ContourBand]
      var bandIndex = 0
      var result: Either[GraphicsError, Unit] = Right(())
      while bandIndex < breaks.values.length - 1 && result.isRight do
        val lower = breaks.values(bandIndex)
        val upper = breaks.values(bandIndex + 1)
        val fragments = Isobands.fragments(field, lower.toDouble, upper.toDouble)
        if fragments.nonEmpty then
          result = Isobands.regions(fragments).map { regions =>
            out += ContourBand(lower, upper, regions, fragments)
            ()
          }
        bandIndex += 1
      result.map(_ => ContourBandSet(out.result()))

private[intaglio] object Isobands:
  private final case class Sample(x: Double, y: Double, value: Double)
  private final case class VertexKey(x: Long, y: Long)
  private final case class UndirectedEdge(first: VertexKey, second: VertexKey)
  private final case class DirectedEdge(start: FieldPoint, end: FieldPoint)

  def fragments(field: ScalarField2D, lower: Double, upper: Double): Vector[BandFragment] =
    val out = Vector.newBuilder[BandFragment]
    var yIndex = 0
    while yIndex < field.height - 1 do
      val y0 = field.yAxis.coordinateUnsafe(yIndex)
      val y1 = field.yAxis.coordinateUnsafe(yIndex + 1)
      var xIndex = 0
      while xIndex < field.width - 1 do
        val x0 = field.xAxis.coordinateUnsafe(xIndex)
        val x1 = field.xAxis.coordinateUnsafe(xIndex + 1)
        val bottomLeft = Sample(x0, y0, field.valueUnsafe(xIndex, yIndex))
        val bottomRight = Sample(x1, y0, field.valueUnsafe(xIndex + 1, yIndex))
        val topRight = Sample(x1, y1, field.valueUnsafe(xIndex + 1, yIndex + 1))
        val topLeft = Sample(x0, y1, field.valueUnsafe(xIndex, yIndex + 1))
        appendFragment(Vector(bottomLeft, bottomRight, topRight), lower, upper, out)
        appendFragment(Vector(bottomLeft, topRight, topLeft), lower, upper, out)
        xIndex += 1
      yIndex += 1
    out.result()

  def regions(fragments: Vector[BandFragment]): Either[GraphicsError, Vector[ContourRegion]] =
    for
      rings <- boundaryRings(fragments)
      regions <- assignHoles(rings)
    yield regions

  private def appendFragment(
      triangle: Vector[Sample],
      lower: Double,
      upper: Double,
      out: scala.collection.mutable.Builder[BandFragment, Vector[BandFragment]]
  ): Unit =
    val clippedLower = clip(triangle, lower, keepAbove = true)
    val clipped = clip(clippedLower, upper, keepAbove = false)
    val normalized = normalize(clipped.map(sample => FieldPoint.unsafe(sample.x, sample.y)))
    if normalized.length >= 3 then
      val area = ContourRing.polygonArea(normalized :+ normalized.head)
      if area > 1e-15 then out += BandFragment(normalized)

  private def clip(polygon: Vector[Sample], threshold: Double, keepAbove: Boolean): Vector[Sample] =
    if polygon.isEmpty then Vector.empty
    else
      val out = Vector.newBuilder[Sample]
      var previous = polygon.last
      var previousInside = inside(previous.value, threshold, keepAbove)
      var index = 0
      while index < polygon.length do
        val current = polygon(index)
        val currentInside = inside(current.value, threshold, keepAbove)
        if currentInside then
          if !previousInside then out += intersection(previous, current, threshold)
          out += current
        else if previousInside then
          out += intersection(previous, current, threshold)
        previous = current
        previousInside = currentInside
        index += 1
      out.result()

  private def inside(value: Double, threshold: Double, keepAbove: Boolean): Boolean =
    if keepAbove then value >= threshold else value <= threshold

  /** Canonical endpoint order makes shared-edge intersections bit-identical
    * even when adjacent triangles traverse the edge in opposite directions.
    */
  private def intersection(left: Sample, right: Sample, threshold: Double): Sample =
    val (first, second) =
      if left.x < right.x || (left.x == right.x && left.y <= right.y) then (left, right)
      else (right, left)
    val t = (threshold - first.value) / (second.value - first.value)
    Sample(
      first.x + t * (second.x - first.x),
      first.y + t * (second.y - first.y),
      threshold
    )

  private def normalize(points: Vector[FieldPoint]): Vector[FieldPoint] =
    val out = Vector.newBuilder[FieldPoint]
    var previous: Option[FieldPoint] = None
    points.foreach { point =>
      if previous.forall(_ != point) then
        out += point
        previous = Some(point)
    }
    val result = out.result()
    if result.length > 1 && result.head == result.last then result.dropRight(1) else result

  private def boundaryRings(fragments: Vector[BandFragment]): Either[GraphicsError, Vector[ContourRing]] =
    val boundary = scala.collection.mutable.LinkedHashMap.empty[UndirectedEdge, DirectedEdge]
    fragments.foreach { fragment =>
      var index = 0
      while index < fragment.points.length do
        val start = fragment.points(index)
        val end = fragment.points((index + 1) % fragment.points.length)
        val edgeKey = undirected(start, end)
        if boundary.contains(edgeKey) then boundary.remove(edgeKey)
        else boundary.update(edgeKey, DirectedEdge(start, end))
        index += 1
    }
    stitch(boundary.values.toVector)

  private def stitch(edges: Vector[DirectedEdge]): Either[GraphicsError, Vector[ContourRing]] =
    val outgoing = scala.collection.mutable.HashMap.empty[VertexKey, scala.collection.mutable.ArrayBuffer[Int]]
    var index = 0
    while index < edges.length do
      outgoing.getOrElseUpdate(key(edges(index).start), scala.collection.mutable.ArrayBuffer.empty) += index
      index += 1
    val visited = Array.fill(edges.length)(false)
    val rings = Vector.newBuilder[ContourRing]
    var result: Either[GraphicsError, Unit] = Right(())
    index = 0
    while index < edges.length && result.isRight do
      if !visited(index) then
        val points = scala.collection.mutable.ArrayBuffer(edges(index).start, edges(index).end)
        visited(index) = true
        var closed = key(points.last) == key(points.head)
        var searching = true
        while !closed && searching do
          val candidates = outgoing.getOrElse(key(points.last), scala.collection.mutable.ArrayBuffer.empty)
          var candidateIndex = 0
          var next = -1
          while candidateIndex < candidates.length && next < 0 do
            if !visited(candidates(candidateIndex)) then next = candidates(candidateIndex)
            candidateIndex += 1
          if next < 0 then searching = false
          else
            visited(next) = true
            points += edges(next).end
            closed = key(points.last) == key(points.head)
        if !closed then
          result = Left(GraphicsError.InvalidContourTopology("band boundary contains an open ring"))
        else
          result = ContourRing.fromClosed(points.toVector).map { ring =>
            rings += ring
            ()
          }
      index += 1
    result.map(_ => rings.result())

  private def assignHoles(rings: Vector[ContourRing]): Either[GraphicsError, Vector[ContourRegion]] =
    val outers = rings.filter(_.winding == RingWinding.CounterClockwise)
    val holes = rings.filter(_.winding == RingWinding.Clockwise)
    if outers.isEmpty && rings.nonEmpty then
      Left(GraphicsError.InvalidContourTopology("band has holes but no outer ring"))
    else
      val assigned = Array.fill(outers.length)(Vector.empty[ContourRing])
      var holeIndex = 0
      var result: Either[GraphicsError, Unit] = Right(())
      while holeIndex < holes.length && result.isRight do
        val hole = holes(holeIndex)
        val containers = outers.indices.filter(index => contains(outers(index), hole.points.head)).toVector
        if containers.isEmpty then
          result = Left(GraphicsError.InvalidContourTopology("hole is not contained by an outer ring"))
        else
          val owner = containers.minBy(index => math.abs(outers(index).signedArea))
          assigned(owner) = assigned(owner) :+ hole
        holeIndex += 1
      result.map(_ => outers.indices.map(index => ContourRegion(outers(index), assigned(index))).toVector)

  private def contains(ring: ContourRing, point: FieldPoint): Boolean =
    var inside = false
    var previous = ring.points.length - 2
    var index = 0
    while index < ring.points.length - 1 do
      val current = ring.points(index)
      val prior = ring.points(previous)
      val crosses = (current.y > point.y) != (prior.y > point.y)
      if crosses then
        val boundaryX = (prior.x - current.x) * (point.y - current.y) / (prior.y - current.y) + current.x
        if point.x < boundaryX then inside = !inside
      previous = index
      index += 1
    inside

  private def undirected(start: FieldPoint, end: FieldPoint): UndirectedEdge =
    val startKey = key(start)
    val endKey = key(end)
    if less(startKey, endKey) then UndirectedEdge(startKey, endKey)
    else UndirectedEdge(endKey, startKey)

  private def less(left: VertexKey, right: VertexKey): Boolean =
    left.x < right.x || (left.x == right.x && left.y < right.y)

  private def key(point: FieldPoint): VertexKey =
    VertexKey(java.lang.Double.doubleToLongBits(point.x), java.lang.Double.doubleToLongBits(point.y))
