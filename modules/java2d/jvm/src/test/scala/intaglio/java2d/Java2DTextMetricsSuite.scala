package intaglio.java2d

import java.awt.Font
import intaglio.*

class Java2DTextMetricsSuite extends munit.FunSuite:
  private val tol = 1e-6
  private val metrics = Java2DTextMetrics()

  test("family and point size propagate to Java2D font measurement") {
    val small = TextStyle(Some(Font.MONOSPACED), 10.0)
    val large = TextStyle(Some(Font.MONOSPACED), 20.0)

    assertEquals(metrics.resolvedFamily(small.fontFamily), Font.MONOSPACED)
    assertEqualsDouble(metrics.widthPt("intaglio", large) / metrics.widthPt("intaglio", small), 2.0, 0.02)
    assertEqualsDouble(metrics.heightPt(large) / metrics.heightPt(small), 2.0, 0.02)
  }

  test("missing families resolve explicitly to the configured fallback") {
    val missing = TextStyle(Some("__intaglio_missing_font__"), 12.0)
    val fallback = TextStyle(Some(metrics.fallbackFamily), 12.0)

    assertEquals(metrics.resolvedFamily(missing.fontFamily), metrics.fallbackFamily)
    assertEqualsDouble(metrics.widthPt("fallback", missing), metrics.widthPt("fallback", fallback), tol)
    assertEqualsDouble(metrics.heightPt(missing), metrics.heightPt(fallback), tol)
  }

  test("real Java2D metrics are opt-in and differ from the portable estimate") {
    val style = TextStyle(Some(Font.SERIF), 12.0)
    val actual = metrics.widthPt("WWWiii", style)
    val estimated = TextMetrics.estimate.widthPt("WWWiii", style)

    assert(actual > 0.0)
    assert(math.abs(actual - estimated) > 0.01)
  }

  test("Java2D metrics injected through LayoutPolicy participate in overflow") {
    val policy = LayoutPolicy(
      metrics = metrics,
      referenceDevice = DeviceContext.unsafe(160.0, 120.0),
      axisFontPt = 48.0,
      axisFontFamily = Some(Font.MONOSPACED)
    )
    val request = PlotLayoutRequest(
      axes = Map(AxisSide.Left -> AxisRequest(Vector("a deliberately wide label")))
    )

    assertEquals(
      PlotLayoutSolver.solve(policy, request).left.toOption,
      Some(GraphicsError.LayoutOverflow("panel width"))
    )
  }
