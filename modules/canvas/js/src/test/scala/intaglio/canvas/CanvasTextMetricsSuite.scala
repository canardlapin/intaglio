package intaglio.canvas

import scala.collection.mutable.ArrayBuffer
import scala.scalajs.js
import intaglio.*

class CanvasTextMetricsSuite extends munit.FunSuite:
  private final case class Fixture(context: CanvasRenderingContext2D, fonts: ArrayBuffer[String])

  private def fixture(widthFactor: Double = 0.5): Fixture =
    val fonts = ArrayBuffer.empty[String]
    var dynamic: js.Dynamic = null
    dynamic = js.Dynamic.literal(
      font = "",
      save = (() => ()): js.Function0[Unit],
      restore = (() => ()): js.Function0[Unit],
      measureText = ((text: String) =>
        val font = dynamic.font.asInstanceOf[String]
        fonts += font
        val sizePx = font.takeWhile(_ != 'p').toDouble
        js.Dynamic
          .literal(
            width = text.length.toDouble * sizePx * widthFactor,
            actualBoundingBoxAscent = sizePx * 0.8,
            actualBoundingBoxDescent = sizePx * 0.2
          )
          .asInstanceOf[CanvasTextMeasurement]
      ): js.Function1[String, CanvasTextMeasurement]
    )
    Fixture(dynamic.asInstanceOf[CanvasRenderingContext2D], fonts)

  test("family and point size propagate to Canvas measureText") {
    val probe = fixture()
    val metrics = new CanvasTextMetrics(probe.context, familyAvailable = _ == "Loaded Sans")

    assertEqualsDouble(metrics.widthPt("abcd", TextStyle(Some("Loaded Sans"), 18.0)), 36.0, 1e-9)
    assertEqualsDouble(metrics.heightPt(TextStyle(Some("Loaded Sans"), 18.0)), 18.0, 1e-9)
    assert(probe.fonts.forall(_.endsWith("px \"Loaded Sans\"")))
    probe.fonts.foreach { font =>
      assertEqualsDouble(font.takeWhile(_ != 'p').toDouble, 24.0, 1e-9)
    }
  }

  test("missing Canvas families resolve to the explicit fallback") {
    val probe = fixture()
    val metrics = new CanvasTextMetrics(
      probe.context,
      fallbackFamily = "Fallback Sans",
      familyAvailable = _ == "Loaded Sans"
    )

    assertEquals(metrics.resolvedFamily(Some("Missing Sans")), "Fallback Sans")
    metrics.widthPt("fallback", TextStyle(Some("Missing Sans"), 12.0))
    assert(probe.fonts.last.endsWith("\"Fallback Sans\""))
  }

  test("Canvas measurement remains opt-in beside the portable estimate") {
    val probe = fixture(widthFactor = 0.5)
    val metrics = new CanvasTextMetrics(probe.context)
    val style = TextStyle(None, 12.0)

    assertEqualsDouble(metrics.widthPt("abcdef", style), 36.0, 1e-9)
    assertEqualsDouble(TextMetrics.estimate.widthPt("abcdef", style), 44.64, 1e-9)
  }

  test("Canvas metrics injected through LayoutPolicy participate in overflow") {
    val probe = fixture(widthFactor = 1.0)
    val metrics = new CanvasTextMetrics(probe.context)
    val policy = LayoutPolicy(
      metrics = metrics,
      referenceDevice = DeviceContext.unsafe(160.0, 120.0),
      axisFontPt = 48.0,
      axisFontFamily = Some("Loaded Sans")
    )
    val request = PlotLayoutRequest(
      axes = Map(AxisSide.Left -> AxisRequest(Vector("a deliberately wide label")))
    )

    assertEquals(
      PlotLayoutSolver.solve(policy, request).left.toOption,
      Some(GraphicsError.LayoutOverflow("panel width"))
    )
  }
