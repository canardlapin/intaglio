package intaglio

import java.awt.BasicStroke
import java.awt.geom.{Area, Ellipse2D, Line2D, Path2D, Rectangle2D}

/** Java2D independently strokes each point shape as `Device.shapeMarks` draws it; the framing's
  * directional ink extents must cover that outline and, except for a round join's disc bound, equal
  * it.
  */
class PanelFramingInkOracleSuite extends munit.FunSuite:
  private val r = 7.0

  private def outline(shape: PointShape): java.awt.Shape =
    shape match
      case PointShape.Circle   => new Ellipse2D.Double(-r, -r, 2 * r, 2 * r)
      case PointShape.Square   => new Rectangle2D.Double(-r, -r, 2 * r, 2 * r)
      case PointShape.Triangle =>
        polygon(Vector((0.0, -r), (r, r), (-r, r)))
      case PointShape.Diamond =>
        val d = PointShape.diamondHalfDiagonal(r)
        polygon(Vector((0.0, -d), (d, 0.0), (0.0, d), (-d, 0.0)))
      case PointShape.Cross =>
        val path = new Path2D.Double
        path.append(new Line2D.Double(-r, 0, r, 0), false)
        path.append(new Line2D.Double(0, -r, 0, r), false)
        path

  private def polygon(points: Vector[(Double, Double)]): java.awt.Shape =
    val path = new Path2D.Double
    path.moveTo(points.head._1, points.head._2)
    points.tail.foreach((x, y) => path.lineTo(x, y))
    path.closePath()
    path

  test("point ink extents match the Java2D stroked outline for every shape, cap, and join") {
    for
      shape <- PointShape.values.toVector
      stroked <- Vector(false, true)
      width <- Vector(1.0, 3.0)
      cap <- LineCap.values.toVector
      join <- LineJoin.values.toVector
    do
      val gp = GraphicParams.unsafe(
        stroke = if stroked then Some(Rgba.Black) else None,
        fill = Some(Rgba.White),
        lineWidth = width,
        lineCap = cap,
        lineJoin = join
      )
      val base = outline(shape)
      val ink = new Area(if shape == PointShape.Cross then new Rectangle2D.Double() else base)
      if stroked then
        val awtCap = cap match
          case LineCap.Butt   => BasicStroke.CAP_BUTT
          case LineCap.Round  => BasicStroke.CAP_ROUND
          case LineCap.Square => BasicStroke.CAP_SQUARE
        val awtJoin = join match
          case LineJoin.Miter => BasicStroke.JOIN_MITER
          case LineJoin.Round => BasicStroke.JOIN_ROUND
          case LineJoin.Bevel => BasicStroke.JOIN_BEVEL
        ink.add(
          new Area(new BasicStroke(width.toFloat, awtCap, awtJoin, 10f).createStrokedShape(base))
        )
      val bounds = ink.getBounds2D
      val oracle = (-bounds.getMinX, bounds.getMaxX, -bounds.getMinY, bounds.getMaxY)
      val (left, right, up, down) = MarkInkFraming.pointInk(shape, r, gp)
      val slack = if stroked && join == LineJoin.Round then width / 2.0 else 1e-6
      val clues = clue((shape, stroked, width, cap, join, (left, right, up, down), oracle))
      Vector(left -> oracle._1, right -> oracle._2, up -> oracle._3, down -> oracle._4).foreach {
        (framed, drawn) =>
          // Java2D flattens round joins and caps into curves; allow its flattening tolerance.
          assert(framed >= drawn - 1e-3, clues)
          assert(framed <= drawn + slack, clues)
      }
  }
