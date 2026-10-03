package intaglio.browser

import intaglio.*
import intaglio.interaction.*
import scala.scalajs.js.JSConverters.*
import scala.concurrent.ExecutionContext.Implicits.global

class SvgWidgetExportSuite extends munit.FunSuite:
  private def ok[A](value: Either[IntaglioError, A]): A = value.fold(e => fail(e.message), identity)
  private val context = RenderContext.unsafe(400, 300)
  private val keys = ok(KeySpace("export", KeyCodec.text))
  private val plan = ok(
    InteractionCompiler.compile(
      ok(plot(Vector(("one", 1.0, 2.0), ("two", 2.0, 4.0))).aes(_._2, _._3).geomPoint().build).plot,
      keys,
      ok(DataRevision("data")),
      SemanticId.unsafe("export"),
      ok(PlanRevision("plan")),
      PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    )(_._1)
  )
  private val view = ok(SvgWidgetView.compile(plan, context, "export"))

  test("empty selection preserves exact SVG; explicit entity selection adds only its ring") {
    assertEquals(SvgWidgetExport.svg(view), view.markup)
    val selected = Selection(Set(ok(keys.entity("two"))))
    val snapshot = SvgWidgetExport.svg(view, selected)
    assertEquals("data-export-selection".r.findAllIn(snapshot).length, 1)
    assert(snapshot.contains("stroke=\"#b45309\""))
    assertEquals(SvgWidgetExport.svg(view), view.markup)
  }

  test("PNG size checks bound allocation before browser work") {
    assertEquals(SvgWidgetExport.dimensions(400, 300, 2), Right((800, 600)))
    for scale <- Vector(0.0, -1.0, Double.NaN, Double.PositiveInfinity, 100.0) do
      assert(SvgWidgetExport.dimensions(400, 300, scale).isLeft)
    SvgWidgetExport.png(view, scale = 0).toFuture.map(value => assert(value.isLeft))
  }

  test("nonbrowser PNG export produces a typed capability failure") {
    SvgWidgetExport
      .png(view)
      .toFuture
      .map(value =>
        assert(value.left.toOption.exists(_.isInstanceOf[WidgetExportError.Unavailable]))
      )
  }
