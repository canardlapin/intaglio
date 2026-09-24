package intaglio

import java.awt.BasicStroke
import java.awt.geom.{Area, Ellipse2D, Line2D, Path2D, Rectangle2D}

/** Java2D strokes each legend key glyph as `Device.shapeMarks` draws it, at the radius the legend
  * placement fits; its complete outline must lie inside the key's square box.
  */
class LegendKeyInkOracleSuite extends munit.FunSuite:
  /** The key radius, in points, that legend placement fits for `entries` in a box `keyPt` wide. */
  private def fittedRadiusPt(entries: Vector[LegendEntry], keyPt: Double): Double =
    val placed = GuideSpec.place(
      GuideSpec.Legend(title = None, entries = entries),
      GuidePlacement.Legend(0.0, 0.0, keyPt, keyPt / 2.0, keyPt, keyPt / 2.0)
    )
    placed match
      case legend: GuideSpec.Legend =>
        legend.markerSize.expr match
          case LengthExpr.Const(length) if length.unit == LengthUnit.Point => length.value
          case other => fail(s"expected a point radius, got $other")
      case other => fail(s"expected a legend, got $other")
  private def outline(shape: PointShape, r: Double): java.awt.Shape =
    def polygon(points: Vector[(Double, Double)]) =
      val path = new Path2D.Double
      path.moveTo(points.head._1, points.head._2)
      points.tail.foreach((x, y) => path.lineTo(x, y))
      path.closePath()
      path
    shape match
      case PointShape.Circle   => new Ellipse2D.Double(-r, -r, 2 * r, 2 * r)
      case PointShape.Square   => new Rectangle2D.Double(-r, -r, 2 * r, 2 * r)
      case PointShape.Triangle => polygon(Vector((0.0, -r), (r, r), (-r, r)))
      case PointShape.Diamond  =>
        val d = PointShape.diamondHalfDiagonal(r)
        polygon(Vector((0.0, -d), (d, 0.0), (0.0, d), (-d, 0.0)))
      case PointShape.Cross =>
        val path = new Path2D.Double
        path.append(new Line2D.Double(-r, 0, r, 0), false)
        path.append(new Line2D.Double(0, -r, 0, r), false)
        path

  test("every key glyph's stroked outline fits its key box at the fitted radius") {
    for
      shape <- PointShape.values.toVector
      keyPt <- Vector(6.0, 10.0, 16.0)
      width <- Vector(1.0, 2.5)
      unit <- Vector(StrokeUnit.DevicePixel, StrokeUnit.Point)
      join <- LineJoin.values.toVector
      cap <- LineCap.values.toVector
      ppi <- Vector(96.0, 192.0)
    do
      val gp = GraphicParams
        .unsafe(stroke = Some(Rgba.Black), lineWidth = width, lineCap = cap, lineJoin = join)
        .withStrokeWidth(
          if unit == StrokeUnit.Point then StrokeWidth.pointsUnsafe(width)
          else StrokeWidth.devicePixelsUnsafe(width)
        )
      val entry = LegendEntry("level", gp, shape).fold(e => fail(e.message), identity)
      val pxPerPt = ppi / 72.0
      val radiusPx = fittedRadiusPt(Vector(entry), keyPt) * pxPerPt
      val strokePx = if unit == StrokeUnit.Point then width * pxPerPt else width
      val awtCap = cap match
        case LineCap.Butt   => BasicStroke.CAP_BUTT
        case LineCap.Round  => BasicStroke.CAP_ROUND
        case LineCap.Square => BasicStroke.CAP_SQUARE
      val awtJoin = join match
        case LineJoin.Miter => BasicStroke.JOIN_MITER
        case LineJoin.Round => BasicStroke.JOIN_ROUND
        case LineJoin.Bevel => BasicStroke.JOIN_BEVEL
      val glyph = outline(shape, radiusPx)
      val ink = new Area(glyph)
      ink.add(
        new Area(new BasicStroke(strokePx.toFloat, awtCap, awtJoin, 10f).createStrokedShape(glyph))
      )
      val bounds = ink.getBounds2D
      val half = keyPt / 2.0 * pxPerPt
      val where = clue((shape, keyPt, width, unit, join, cap, ppi, radiusPx, bounds))
      assert(radiusPx > 0.0, where)
      assert(bounds.getMinX >= -half - 1e-3 && bounds.getMaxX <= half + 1e-3, where)
      assert(bounds.getMinY >= -half - 1e-3 && bounds.getMaxY <= half + 1e-3, where)
  }

  test("one radius serves the whole legend, so a circle and a diamond keep equal areas") {
    val gp = GraphicParams.unsafe()
    val circle = LegendEntry("circle", gp, PointShape.Circle).fold(e => fail(e.message), identity)
    val diamond =
      LegendEntry("diamond", gp, PointShape.Diamond).fold(e => fail(e.message), identity)
    val mixed = fittedRadiusPt(Vector(circle, diamond), 10.0)
    assert(mixed < fittedRadiusPt(Vector(circle), 10.0))
    assertEqualsDouble(mixed, fittedRadiusPt(Vector(diamond), 10.0), 1e-12)
  }
