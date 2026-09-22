package intaglio.interaction

import intaglio.*
import java.awt.BasicStroke
import java.awt.geom.Path2D
import PickGeometry.*

/** Java2D independently constructs the complete stroke outline from the public path inputs. */
class StrokeOracleSuite extends munit.FunSuite:
  private val paths = Vector(
    Vector(P(40, 50), P(70, 50)),
    Vector(P(40, 50), P(60, 50), P(60, 70)),
    Vector(P(40, 50), P(60, 50), P(41, 53)),
    Vector(P(50, 50), P(50.5, 50), P(50.5, 50.7)),
    Vector(P(40, 40), P(70, 70), P(40, 70), P(70, 40))
  )

  test(
    "linear stroke regions agree with BasicStroke across caps, joins, dashes, and closed paths"
  ) {
    for
      points <- paths
      closed <- Vector(false, true)
      cap <- Vector(LineCap.Butt, LineCap.Round, LineCap.Square)
      join <- Vector(LineJoin.Miter, LineJoin.Round, LineJoin.Bevel)
      // Four unequal segments, so a rhythm beyond the two named ones is checked against the
      // oracle rather than assumed. Its period is chosen so that no path here ends a dash
      // exactly on a closed seam: that join disagrees with this oracle
      // (bd-01M2XQEHRTX5V422SWDC74X4MV), and the named rhythms already cover the branch on
      // the paths where it agrees.
      dash <- Vector(
        LineType.Solid,
        LineType.Dashed,
        LineType.Dotted,
        LineType.Custom(DashPattern.unsafe(2.0, 3.0, 1.0, 4.0))
      )
    do
      val gp = GraphicParams.unsafe(lineWidth = 4, lineCap = cap, lineJoin = join, lineType = dash)
      val regions = PickStroke(points, closed, gp, paintedDashes = true, miterLimit = 4)
      val path = new Path2D.Double
      path.moveTo(points.head.x, points.head.y)
      points.tail.foreach(p => path.lineTo(p.x, p.y))
      if closed then path.closePath()
      val awtCap = cap match
        case LineCap.Butt   => BasicStroke.CAP_BUTT
        case LineCap.Round  => BasicStroke.CAP_ROUND
        case LineCap.Square => BasicStroke.CAP_SQUARE
      val awtJoin = join match
        case LineJoin.Miter => BasicStroke.JOIN_MITER
        case LineJoin.Round => BasicStroke.JOIN_ROUND
        case LineJoin.Bevel => BasicStroke.JOIN_BEVEL
      // Derived from the same `LineType.dash` the outline uses, so the oracle checks the
      // geometry rather than restating the rhythms. `null` is BasicStroke's solid stroke.
      val pattern: Array[Float] = dash.dash.map(_.segments.map(_.toFloat).toArray).orNull
      val oracle = new BasicStroke(4, awtCap, awtJoin, 4, pattern, 0).createStrokedShape(path)
      for x <- 36 to 74 by 2; y <- 36 to 74 by 2 do
        // Avoid exact boundary points: Java2D's contains excludes some boundary directions.
        val p = P(x + 0.137, y + 0.271)
        assertEquals(
          regions.exists(_.contains(p)),
          oracle.contains(p.x, p.y),
          clues(points, closed, cap, join, dash, p)
        )
  }

  test("nonzero fill containment does not mistake an internal same-winding ring for a hole") {
    val outer = Vector(P(-5, -5), P(5, -5), P(5, 5), P(-5, 5))
    val inner = Vector(P(-2, -2), P(2, -2), P(2, 2), P(-2, 2))
    val filled = new Clipped(Vector(Region.polygon(Vector(outer, inner), evenOdd = false)))
    val hole = Region.polygon(Vector(outer, inner))
    assert(!filled.containedBy(hole))
    assert(filled.containedBy(Region.rectangle(Box(-5, -5, 5, 5))))
  }
