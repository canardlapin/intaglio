package intaglio.interaction

import intaglio.*
import PickGeometry.*

/** Closed rings in device pixels that follow a target's mark at a distance, for focus and selection
  * outlines drawn by a host.
  *
  * Each ring traces one painted primitive of the target, offset outward from its ink: from a filled
  * shape's edge, or from the outer edge of its stroke (the centreline plus half the line width).
  * Circles stay circles, rectangles keep their corner radius plus the offset, and closed paths and
  * polygons — the triangle and diamond point shapes among them — are offset edge by edge with round
  * corners, so every vertex of a ring lies at the requested distance from the mark's outline. A
  * miter corner sharper than the offset can reach past the ring. Open paths, crosses, text, images
  * and raster cells have no closed silhouette and use their painted bounding rectangle, offset.
  * Curves are flattened to chords that stray at most `toleranceDevicePx` inside the true curve.
  * Clips do not cut an outline.
  */
final case class TargetOutline(rings: Vector[Vector[DevicePoint]])

object TargetOutline:
  /** The largest distance a flattened curve's chord lies inside the true curve. */
  val toleranceDevicePx: Double = 0.1

  private[interaction] def of(parts: Vector[PickPart], offset: Double): TargetOutline =
    val sources = Vector.newBuilder[MarkSource]
    val seen = scala.collection.mutable.HashSet.empty[MarkSource]
    parts.foreach(_.mark.foreach(mark => if seen.add(mark) then sources += mark))
    val loose = parts.filter(_.mark.isEmpty)
    val rings = sources.result().flatMap { mark =>
      ringsOf(mark, parts.filter(_.mark.contains(mark)), offset)
    } ++ (if loose.isEmpty then Vector.empty else bounds(loose, offset))
    TargetOutline(rings.map(_.map(p => DevicePoint(p.x, p.y))))

  private def ringsOf(
      mark: MarkSource,
      parts: Vector[PickPart],
      offset: Double
  ): Vector[Vector[P]] =
    def reach(gp: GraphicParams): Double =
      if gp.stroke.isDefined && gp.lineWidth > 0 then gp.lineWidth / 2 else 0.0
    def place(ring: Vector[P]): Vector[P] = ring.map(mark.transform(_))
    mark.primitive match
      case DevicePrimitive.Disc(x, y, radius, gp, _) =>
        Vector(place(circle(P(x, y), radius + reach(gp) + offset)))
      case DevicePrimitive.RectShape(x, y, width, height, corner, gp, _) =>
        Vector(place(roundedRectangle(x, y, width, height, corner, reach(gp) + offset)))
      case DevicePrimitive.Polyline(points, true, gp, _) if distinct(points).size >= 3 =>
        Vector(place(offsetPolygon(distinct(points), reach(gp) + offset)))
      case DevicePrimitive.CompoundPolygon(rings, gp, _) =>
        val closed = rings.map(distinct).filter(_.size >= 3)
        // A ring inside another is a hole; the silhouette is traced by the outer rings only.
        val outer = closed.filterNot(ring =>
          closed.exists(other =>
            !(other eq ring) && Region.polygon(Vector(other)).contains(ring.head)
          )
        )
        if outer.isEmpty then bounds(parts, offset)
        else outer.map(ring => place(offsetPolygon(ring, reach(gp) + offset)))
      case _ => bounds(parts, offset)

  private def distinct(points: Vector[DevicePoint]): Vector[P] =
    val values = points.map(p => P(p.x, p.y))
    val unique = values.zipWithIndex.collect {
      case (p, i) if i == 0 || p.distance(values(i - 1)) > epsilon => p
    }
    if unique.size > 1 && unique.last.distance(unique.head) <= epsilon then unique.init else unique

  private def bounds(parts: Vector[PickPart], offset: Double): Vector[Vector[P]] =
    parts.flatMap(_.source.bounds).reduceOption(_.union(_)).toVector.map { box =>
      Vector(
        P(box.left - offset, box.top - offset),
        P(box.right + offset, box.top - offset),
        P(box.right + offset, box.bottom + offset),
        P(box.left - offset, box.bottom + offset)
      )
    }

  /** Points from `start` through `sweep` radians on a circle, ends included. */
  private def arc(center: P, radius: Double, start: Double, sweep: Double): Vector[P] =
    if radius <= epsilon then Vector(center)
    else
      val step =
        if radius <= toleranceDevicePx then math.Pi / 2
        else 2 * math.acos(1 - toleranceDevicePx / radius)
      val steps = math.max(1, math.ceil(math.abs(sweep) / step).toInt)
      (0 to steps).toVector.map { i =>
        val angle = start + sweep * i / steps
        P(center.x + radius * math.cos(angle), center.y + radius * math.sin(angle))
      }

  private def circle(center: P, radius: Double): Vector[P] =
    val points = arc(center, radius, 0, 2 * math.Pi)
    val ring = if points.size > 1 then points.init else points
    if ring.size >= 8 || radius <= epsilon then ring
    else
      (0 until 8).toVector.map { i =>
        val angle = 2 * math.Pi * i / 8
        P(center.x + radius * math.cos(angle), center.y + radius * math.sin(angle))
      }

  private def roundedRectangle(
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      corner: Double,
      grow: Double
  ): Vector[P] =
    val r = math.max(0, math.min(corner, math.min(width, height) / 2))
    val radius = r + grow
    val half = math.Pi / 2
    arc(P(x + width - r, y + r), radius, -half, half) ++
      arc(P(x + width - r, y + height - r), radius, 0, half) ++
      arc(P(x + r, y + height - r), radius, half, half) ++
      arc(P(x + r, y + r), radius, math.Pi, half)

  /** Offset a simple closed ring outward by `distance`, with round convex corners and mitred
    * concave ones.
    */
  private def offsetPolygon(ring: Vector[P], distance: Double): Vector[P] =
    if distance <= epsilon then ring
    else
      val n = ring.size
      val area = ring.indices.map(i => ring(i).cross(ring((i + 1) % n))).sum
      val outwardSign = if area >= 0 then 1.0 else -1.0
      def direction(i: Int): P =
        val d = ring((i + 1) % n) - ring(i)
        d * (1 / d.norm)
      def normal(i: Int): P =
        val d = direction(i)
        P(d.y, -d.x) * outwardSign
      ring.indices.toVector.flatMap { i =>
        val previous = normal((i - 1 + n) % n)
        val current = normal(i)
        val vertex = ring(i)
        val turn = direction((i - 1 + n) % n).cross(direction(i)) * outwardSign
        if turn > epsilon then
          val start = math.atan2(previous.y, previous.x)
          val sweep = math.atan2(previous.cross(current), previous.dot(current))
          arc(vertex, distance, start, sweep)
        else
          val denominator = 1 + previous.dot(current)
          if denominator <= epsilon then
            Vector(vertex + previous * distance, vertex + current * distance)
          else Vector(vertex + (previous + current) * (distance / denominator))
      }
