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
      dash <- Vector(LineType.Solid, LineType.Dashed, LineType.Dotted)
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
      val pattern: Array[Float] = dash match
        case LineType.Solid  => null
        case LineType.Dashed => Array(6f, 4f)
        case LineType.Dotted => Array(1f, 3f)
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
