package intaglio.interaction

/** Analytic closed regions for picking. Boundaries are inclusive within 1e-8 device pixels. Curves
  * remain circular arcs through clipping; they are never replaced by sampled polygons.
  */
private[interaction] object PickGeometry:
  val epsilon = 1e-8
  private val twoPi = 2.0 * math.Pi

  final case class P(x: Double, y: Double):
    def +(other: P): P = P(x + other.x, y + other.y)
    def -(other: P): P = P(x - other.x, y - other.y)
    def *(scale: Double): P = P(x * scale, y * scale)
    def dot(other: P): Double = x * other.x + y * other.y
    def cross(other: P): Double = x * other.y - y * other.x
    def norm: Double = math.hypot(x, y)
    def distance(other: P): Double = (this - other).norm

  final case class Box(left: Double, top: Double, right: Double, bottom: Double):
    def center: P = P(left + (right - left) / 2, top + (bottom - top) / 2)
    def contains(p: P): Boolean =
      p.x >= left - epsilon && p.x <= right + epsilon && p.y >= top - epsilon && p.y <= bottom + epsilon
    def union(other: Box): Box = Box(
      math.min(left, other.left),
      math.min(top, other.top),
      math.max(right, other.right),
      math.max(bottom, other.bottom)
    )

  object Box:
    def enclosing(points: Vector[P]): Option[Box] =
      points.headOption.map { first =>
        points.tail.foldLeft(Box(first.x, first.y, first.x, first.y)) { (box, p) =>
          box.union(Box(p.x, p.y, p.x, p.y))
        }
      }

  /** Device groups rotate in their parent's coordinate system. Compose outer after inner. */
  final case class Rigid(cos: Double = 1, sin: Double = 0, tx: Double = 0, ty: Double = 0):
    def apply(p: P): P = P(cos * p.x - sin * p.y + tx, sin * p.x + cos * p.y + ty)
    def compose(inner: Rigid): Rigid =
      val translation = apply(P(inner.tx, inner.ty))
      Rigid(
        cos * inner.cos - sin * inner.sin,
        sin * inner.cos + cos * inner.sin,
        translation.x,
        translation.y
      )
    def inverse(p: P): P =
      val local = p - P(tx, ty)
      P(cos * local.x + sin * local.y, -sin * local.x + cos * local.y)

  object Rigid:
    def rotation(degrees: Double, pivot: P): Rigid =
      val angle = math.toRadians(degrees)
      val c = math.cos(angle)
      val s = math.sin(angle)
      Rigid(c, s, pivot.x - c * pivot.x + s * pivot.y, pivot.y - s * pivot.x - c * pivot.y)

  sealed trait Edge:
    def at(t: Double): P
    def nearest(p: P): P
    def parameter(p: P): Double
    def portion(from: Double, until: Double): Edge
    def transform(value: Rigid): Edge
    def extrema: Vector[P]
    final def distance(p: P): Double = nearest(p).distance(p)
    final def contains(p: P): Boolean = distance(p) <= epsilon
    final def intersections(other: Edge): Vector[P] = intersect(this, other)
    final def split(points: Vector[P]): Vector[Edge] =
      val cuts =
        (Vector(0.0, 1.0) ++ points.map(parameter).filter(t => t > 0 && t < 1)).sorted.distinct
      cuts.sliding(2).map(pair => portion(pair(0), pair(1))).toVector

  final case class Segment(a: P, b: P) extends Edge:
    private val delta = b - a
    private val length = delta.norm
    def at(t: Double): P = a + delta * t
    def parameter(p: P): Double =
      if length == 0 then 0 else (p - a).dot(delta * (1 / length)) / length
    def nearest(p: P): P = at(clamp(parameter(p)))
    def portion(from: Double, until: Double): Edge = Segment(at(from), at(until))
    def transform(value: Rigid): Edge = Segment(value(a), value(b))
    def extrema: Vector[P] = Vector(a, b)

  /** Counterclockwise in numeric x/y coordinates, with sweep in (0, 2 pi]. */
  final case class Arc(center: P, radius: Double, start: Double, sweep: Double) extends Edge:
    def at(t: Double): P =
      val angle = start + sweep * t
      center + P(math.cos(angle), math.sin(angle)) * radius
    private def delta(p: P): Double = wrap(math.atan2(p.y - center.y, p.x - center.x) - start)
    def parameter(p: P): Double =
      val d = delta(p)
      if d <= sweep then d / sweep
      else if p.distance(at(0)) <= p.distance(at(1)) then 0
      else 1
    def nearest(p: P): P =
      if radius == 0 then center
      else if p.distance(center) == 0 then at(0)
      else at(parameter(p))
    def portion(from: Double, until: Double): Edge =
      Arc(center, radius, start + sweep * from, sweep * (until - from))
    def transform(value: Rigid): Edge =
      Arc(value(center), radius, start + math.atan2(value.sin, value.cos), sweep)
    def extrema: Vector[P] =
      Vector(at(0), at(1)) ++ Vector(0.0, math.Pi / 2, math.Pi, 3 * math.Pi / 2).flatMap { angle =>
        val d = wrap(angle - start)
        if d <= sweep then Some(center + P(math.cos(angle), math.sin(angle)) * radius) else None
      }

  final class Region private (val edges: Vector[Edge], inside: P => Boolean):
    def contains(p: P): Boolean = inside(p)
    def strictlyContains(p: P): Boolean =
      val probe = math.max(epsilon * 8, math.max(math.abs(p.x), math.abs(p.y)) * 2e-15)
      contains(p) && edges.filter(_.contains(p)).forall { edge =>
        val direction = edge match
          case Segment(a, b) =>
            val delta = b - a
            if delta.norm == 0 then P(1, 0) else P(-delta.y, delta.x) * (1 / delta.norm)
          case Arc(center, radius, _, _) =>
            if radius == 0 then P(1, 0) else (edge.nearest(p) - center) * (1 / radius)
        contains(p + direction * probe) && contains(p - direction * probe)
      }
    lazy val bounds: Option[Box] = Box.enclosing(edges.flatMap(_.extrema))
    def transform(value: Rigid): Region =
      new Region(edges.map(_.transform(value)), p => contains(value.inverse(p)))

  object Region:
    val empty: Region = new Region(Vector.empty, _ => false)

    def difference(outer: Region, inner: Region): Region =
      new Region(
        outer.edges ++ inner.edges,
        p => outer.contains(p) && (!inner.contains(p) || inner.edges.exists(_.contains(p)))
      )

    def sector(center: P, radius: Double, start: Double, sweep: Double): Region =
      val arc = Arc(center, radius, start, sweep)
      val edges = Vector[Edge](Segment(center, arc.at(0)), arc, Segment(arc.at(1), center))
      new Region(
        edges,
        p =>
          edges.exists(_.contains(p)) || (p.distance(center) <= radius + epsilon &&
            wrap(math.atan2(p.y - center.y, p.x - center.x) - start) <= sweep)
      )

    def disc(center: P, radius: Double): Region =
      new Region(Vector(Arc(center, radius, 0, twoPi)), p => p.distance(center) <= radius + epsilon)

    def annulus(center: P, inner: Double, outer: Double): Region =
      val edges = Vector(Arc(center, outer, 0, twoPi)) ++
        (if inner > 0 then Vector(Arc(center, inner, 0, twoPi)) else Vector.empty)
      new Region(
        edges,
        p =>
          val distance = p.distance(center)
          distance >= inner - epsilon && distance <= outer + epsilon
      )

    /** Even-odd fill with inclusive ring boundaries, independent of ring orientation. */
    def polygon(rings: Vector[Vector[P]], evenOdd: Boolean = true): Region =
      val valid = rings.filter(_.nonEmpty)
      val edges =
        valid.flatMap(ring => ring.indices.map(i => Segment(ring(i), ring((i + 1) % ring.length))))
      new Region(
        edges,
        p =>
          if edges.exists(_.contains(p)) then true
          else
            var winding = 0
            valid.foreach { ring =>
              ring.indices.foreach { i =>
                val a = ring(i)
                val b = ring((i + 1) % ring.length)
                if (a.y > p.y) != (b.y > p.y) &&
                  p.x < a.x + (b.x - a.x) * ((p.y - a.y) / (b.y - a.y))
                then winding += (if b.y > a.y then 1 else -1)
              }
            }
            if evenOdd then winding % 2 != 0 else winding != 0
      )

    def rectangle(box: Box): Region = polygon(
      Vector(
        Vector(
          P(box.left, box.top),
          P(box.right, box.top),
          P(box.right, box.bottom),
          P(box.left, box.bottom)
        )
      )
    )

    def roundedRectangle(box: Box, radius: Double): Region =
      val r =
        math.max(0, math.min(radius, math.min(box.right - box.left, box.bottom - box.top) / 2))
      if r == 0 then rectangle(box)
      else
        val l = box.left
        val t = box.top
        val right = box.right
        val b = box.bottom
        val edges = Vector[Edge](
          Segment(P(l + r, t), P(right - r, t)),
          Arc(P(right - r, t + r), r, -math.Pi / 2, math.Pi / 2),
          Segment(P(right, t + r), P(right, b - r)),
          Arc(P(right - r, b - r), r, 0, math.Pi / 2),
          Segment(P(right - r, b), P(l + r, b)),
          Arc(P(l + r, b - r), r, math.Pi / 2, math.Pi / 2),
          Segment(P(l, b - r), P(l, t + r)),
          Arc(P(l + r, t + r), r, math.Pi, math.Pi / 2)
        )
        new Region(
          edges,
          p =>
            val nearest =
              P(math.max(l + r, math.min(right - r, p.x)), math.max(t + r, math.min(b - r, p.y)))
            p.distance(nearest) <= r + epsilon
        )

  /** Intersection of a filled region and zero or more clip regions. Empty intersections have
    * infinite distance. Clipped boundary pieces are computed once and reused by queries.
    */
  final class Clipped(val regions: Vector[Region]):
    require(regions.nonEmpty, "a clipped region requires its source geometry")
    def contains(p: P): Boolean = regions.forall(_.contains(p))
    private lazy val boundaries: (Vector[Edge], Vector[P]) =
      val pieces = Vector.newBuilder[Edge]
      val points = Vector.newBuilder[P]
      regions.indices.foreach { index =>
        val others = regions.indices.filter(_ != index).flatMap(i => regions(i).edges).toVector
        regions(index).edges.foreach { edge =>
          val crossings = others.flatMap(edge.intersections)
          (Vector(edge.at(0), edge.at(1)) ++ crossings).filter(contains).foreach(points += _)
          edge.split(crossings).filter(part => contains(part.at(0.5))).foreach(pieces += _)
        }
      }
      (pieces.result(), points.result().distinct)
    def edges: Vector[Edge] = boundaries._1
    def points: Vector[P] = boundaries._2
    def nonEmpty: Boolean = edges.nonEmpty || points.nonEmpty
    lazy val bounds: Option[Box] = Box.enclosing(edges.flatMap(_.extrema) ++ points)

    def distance(p: P): Double =
      if contains(p) then 0
      else
        (edges.map(_.distance(p)) ++ points.map(_.distance(p))).minOption
          .getOrElse(Double.PositiveInfinity)

    def intersects(area: Region): Boolean = new Clipped(regions :+ area).nonEmpty

    /** Boundary tests also inspect area boundaries inside this region, so a query hole cannot be
      * mistaken for containment merely because it lies away from the mark's outer boundary.
      */
    def containedBy(area: Region): Boolean =
      if !nonEmpty then false
      else
        val boundaryInside = points.forall(area.contains) && edges.forall { edge =>
          val crossings = area.edges.flatMap(edge.intersections)
          edge
            .split(crossings)
            .forall(part =>
              area.contains(part.at(0)) && area.contains(part.at(0.5)) && area.contains(part.at(1))
            )
        }
        def strictlyInside(p: P): Boolean = regions.forall(_.strictlyContains(p))
        boundaryInside && !area.edges.exists { edge =>
          edge.split(edges.flatMap(edge.intersections)).exists(part => strictlyInside(part.at(0.5)))
        }

  private def clamp(t: Double): Double = math.max(0, math.min(1, t))
  private def wrap(angle: Double): Double =
    val value = angle % twoPi
    if value < 0 then value + twoPi else value

  private def intersect(a: Edge, b: Edge): Vector[P] = (a, b) match
    case (left: Segment, right: Segment) => segmentSegment(left, right)
    case (line: Segment, arc: Arc)       => segmentArc(line, arc)
    case (arc: Arc, line: Segment)       => segmentArc(line, arc)
    case (left: Arc, right: Arc)         => arcArc(left, right)

  private def segmentSegment(a: Segment, b: Segment): Vector[P] =
    val da = a.b - a.a
    val db = b.b - b.a
    val la = da.norm
    val lb = db.norm
    if la == 0 then if b.contains(a.a) then Vector(a.a) else Vector.empty
    else if lb == 0 then if a.contains(b.a) then Vector(b.a) else Vector.empty
    else
      val ua = da * (1 / la)
      val ub = db * (1 / lb)
      val cross = ua.cross(ub)
      if math.abs(cross) <= 1e-14 then
        Vector(a.a, a.b, b.a, b.b).filter(p => a.contains(p) && b.contains(p)).distinct
      else
        val offset = b.a - a.a
        val t = offset.cross(ub) / cross
        val u = offset.cross(ua) / cross
        if t >= -epsilon && t <= la + epsilon && u >= -epsilon && u <= lb + epsilon then
          Vector(a.a + ua * math.max(0, math.min(la, t)))
        else Vector.empty

  private def segmentArc(line: Segment, arc: Arc): Vector[P] =
    val direction = line.b - line.a
    val length = direction.norm
    if length == 0 then if arc.contains(line.a) then Vector(line.a) else Vector.empty
    else
      val unit = direction * (1 / length)
      val along = (arc.center - line.a).dot(unit)
      val foot = line.a + unit * along
      val distance = foot.distance(arc.center)
      if distance > arc.radius + epsilon then Vector.empty
      else
        val offset = math.sqrt(math.max(0, (arc.radius - distance) * (arc.radius + distance)))
        Vector(along - offset, along + offset)
          .filter(t => t >= -epsilon && t <= length + epsilon)
          .map(t => line.a + unit * math.max(0, math.min(length, t)))
          .filter(arc.contains)
          .distinct

  private def arcArc(a: Arc, b: Arc): Vector[P] =
    val delta = b.center - a.center
    val distance = delta.norm
    if distance <= epsilon && math.abs(a.radius - b.radius) <= epsilon then
      Vector(a.at(0), a.at(1), b.at(0), b.at(1))
        .filter(p => a.contains(p) && b.contains(p))
        .distinct
    else if distance == 0 || distance > a.radius + b.radius + epsilon ||
      distance < math.abs(a.radius - b.radius) - epsilon
    then Vector.empty
    else
      val along = (distance + (a.radius - b.radius) * (a.radius + b.radius) / distance) / 2
      val height = math.sqrt(math.max(0, (a.radius - along) * (a.radius + along)))
      val unit = delta * (1 / distance)
      val foot = a.center + unit * along
      val perpendicular = P(-unit.y, unit.x) * height
      Vector(foot + perpendicular, foot - perpendicular)
        .filter(p => a.contains(p) && b.contains(p))
        .distinct
