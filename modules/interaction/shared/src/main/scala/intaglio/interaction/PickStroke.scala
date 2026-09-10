package intaglio.interaction

import intaglio.{GraphicParams, LineCap, LineJoin, LineType}
import PickGeometry.*

/** Linear stroke outlines, including joins, endpoint caps, and dashes spanning vertices. */
private[interaction] object PickStroke:
  def apply(
      points: Vector[P],
      closed: Boolean,
      gp: GraphicParams,
      paintedDashes: Boolean,
      miterLimit: Double
  ): Vector[Region] =
    val clean = points.foldLeft(Vector.empty[P]) { (out, p) =>
      if out.lastOption.contains(p) then out else out :+ p
    }
    val normalized =
      if closed && clean.length > 1 && clean.head == clean.last then clean.dropRight(1) else clean
    if gp.lineWidth <= 0 || points.length < 2 then Vector.empty
    else if normalized.length == 1 then
      val p = normalized.head
      val h = gp.lineWidth / 2
      gp.lineCap match
        case LineCap.Butt   => Vector.empty
        case LineCap.Round  => Vector(Region.disc(p, h))
        case LineCap.Square => Vector(Region.rectangle(Box(p.x - h, p.y - h, p.x + h, p.y + h)))
    else if paintedDashes && gp.lineType != LineType.Solid then
      val pattern = gp.lineType match
        case LineType.Dashed => Vector(6.0, 4.0)
        case LineType.Dotted => Vector(1.0, 3.0)
        case LineType.Solid  => Vector.empty
      val path = if closed then normalized :+ normalized.head else normalized
      val runs = dashRuns(path, pattern)
      val joined =
        if closed && runs.length > 1 && runs.head.head == normalized.head && runs.last.last == normalized.head
        then Vector(runs.last ++ runs.head.tail) ++ runs.slice(1, runs.length - 1)
        else runs
      // A dash is an open run even when its endpoints coincide. Only separate first/last
      // runs are joined across a closed path's seam above; coincident endpoints alone do
      // not authorize a closing join (including when one dash covers the whole path).
      joined.flatMap(run => solid(run, closed = false, gp, miterLimit))
    else solid(normalized, closed, gp, miterLimit)

  private def solid(
      raw: Vector[P],
      closed: Boolean,
      gp: GraphicParams,
      miterLimit: Double
  ): Vector[Region] =
    val points = if closed && raw.head == raw.last then raw.dropRight(1) else raw
    if points.length < 2 then Vector.empty
    else
      val h = gp.lineWidth / 2
      val segments = (if closed then points :+ points.head else points)
        .sliding(2)
        .map { pair =>
          val a = pair(0)
          val b = pair(1)
          val u = (b - a) * (1 / a.distance(b))
          (a, b, u, P(-u.y, u.x) * h)
        }
        .toVector
      val rectangles = segments.map { case (a, b, _, n) =>
        Region.polygon(Vector(Vector(a + n, b + n, b - n, a - n)))
      }
      val joins = (if closed then segments.indices else 1 until segments.length).toVector.flatMap {
        index =>
          val previous = segments((index + segments.length - 1) % segments.length)
          val next = segments(index)
          val vertex = next._1
          val u = previous._3
          val v = next._3
          val cross = u.cross(v)
          val dot = u.dot(v)
          if math.abs(cross) < 1e-14 then
            if dot < 0 && gp.lineJoin == LineJoin.Round then
              Vector(Region.sector(vertex, h, math.atan2(u.y, u.x) - math.Pi / 2, math.Pi))
            else Vector.empty
          else
            val side = if cross > 0 then -1.0 else 1.0
            val a = vertex + previous._4 * side
            val b = vertex + next._4 * side
            gp.lineJoin match
              case LineJoin.Bevel => Vector(Region.polygon(Vector(Vector(vertex, a, b))))
              case LineJoin.Round =>
                val start = if cross > 0 then a - vertex else b - vertex
                Vector(
                  Region.sector(
                    vertex,
                    h,
                    math.atan2(start.y, start.x),
                    math.abs(math.atan2(cross, dot))
                  )
                )
              case LineJoin.Miter =>
                val tip = a + u * ((b - a).cross(v) / cross)
                val outline = if tip.distance(vertex) <= h * miterLimit then
                  Vector(vertex, a, tip, b)
                else Vector(vertex, a, b)
                Vector(Region.polygon(Vector(outline)))
      }
      val caps = if closed then Vector.empty
      else
        Vector((segments.head._1, segments.head._3 * -1), (segments.last._2, segments.last._3))
          .flatMap { case (at, direction) =>
            gp.lineCap match
              case LineCap.Butt  => Vector.empty
              case LineCap.Round =>
                Vector(
                  Region.sector(at, h, math.atan2(direction.y, direction.x) - math.Pi / 2, math.Pi)
                )
              case LineCap.Square =>
                val n = P(-direction.y, direction.x) * h
                Vector(
                  Region.polygon(
                    Vector(Vector(at + n, at + direction * h + n, at + direction * h - n, at - n))
                  )
                )
          }
      rectangles ++ joins ++ caps

  private def dashRuns(points: Vector[P], pattern: Vector[Double]): Vector[Vector[P]] =
    val runs = Vector.newBuilder[Vector[P]]
    var run = Vector.empty[P]
    var index = 0
    var remaining = pattern.head
    points.sliding(2).foreach { pair =>
      val a = pair(0)
      val b = pair(1)
      val length = a.distance(b)
      var walked = 0.0
      while walked < length do
        val step = math.min(remaining, length - walked)
        val start = a + (b - a) * (walked / length)
        val end = if step == length - walked then b else a + (b - a) * ((walked + step) / length)
        if index % 2 == 0 then
          if run.isEmpty then run = Vector(start)
          run = run :+ end
        walked += step
        remaining -= step
        if remaining <= epsilon then
          if run.nonEmpty then
            runs += run
            run = Vector.empty
          index = (index + 1) % pattern.length
          remaining = pattern(index)
    }
    if run.nonEmpty then runs += run
    runs.result()
