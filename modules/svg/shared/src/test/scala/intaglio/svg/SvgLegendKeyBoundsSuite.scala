package intaglio.svg

import intaglio.*

/** Derived legend keys read back from the SVG markup itself: circle centres, radii and stroke
  * widths, and label anchors, parsed from the emitted attributes rather than the scene.
  */
class SvgLegendKeyBoundsSuite extends munit.FunSuite:
  final case class Obs(x: Double, y: Double, condition: String)
  private val rows = Vector("baseline", "early", "late", "washout").zipWithIndex.map {
    (condition, i) => Obs(i.toDouble, i.toDouble * 0.5, condition)
  }

  private def attribute(element: String, name: String): Double =
    s"""\\s$name="([^"]+)"""".r
      .findFirstMatchIn(element)
      .map(_.group(1).toDouble)
      .getOrElse(fail(s"missing $name in $element"))

  private def elements(svg: String, tag: String, suffix: String): Vector[String] =
    s"""<$tag [^>]*data-name="[^"]*legend-entry-\\d+-$suffix"[^>]*>""".r
      .findAllIn(svg)
      .toVector

  test("legend key circles in the SVG never overlap each other or reach their labels") {
    for
      (width, height, ppi) <- Vector((640, 480, 96.0), (480, 300, 96.0), (1280, 960, 192.0))
      fontPt <- Vector(8.0, 10.0, 14.0)
    do
      val context = RenderContext.unsafe(width = width, height = height, pixelsPerInch = ppi)
      val plan = plot(rows)
        .aes(_.x, _.y)
        .scaleColorDiscrete(_.condition, name = "condition")
        .geomPoint()
        .compilerOptions(
          PlotCompilerOptions(
            guides = GuidePolicy.Derived(),
            policy = Some(LayoutPolicy(legendFontPt = fontPt))
          )
        )
        .renderPlan(context)
        .fold(error => fail(error.message), identity)
      val svg = SvgRenderer.render(plan).fold(error => fail(error.toString), _.value)
      val keys = elements(svg, "circle", "key").map { circle =>
        val half = attribute(circle, "r") + attribute(circle, "stroke-width") / 2.0
        (attribute(circle, "cx"), attribute(circle, "cy"), half)
      }
      val labels = elements(svg, "text", "label").map(text => attribute(text, "x"))
      val where = clue((width, height, ppi, fontPt, keys))
      assertEquals(keys.length, rows.length, where)
      assertEquals(labels.length, rows.length, where)
      keys.sliding(2).foreach { pair =>
        val ((_, upper, a), (_, lower, b)) = (pair(0), pair(1))
        assert(upper + a <= lower - b, where)
      }
      keys.zip(labels).foreach { case ((cx, _, half), labelX) =>
        assert(cx + half < labelX, where)
      }
  }
