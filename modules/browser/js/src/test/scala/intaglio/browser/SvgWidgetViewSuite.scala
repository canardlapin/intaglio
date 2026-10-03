package intaglio.browser

import intaglio.*
import intaglio.interaction.*
import scala.scalajs.js

/** The DOM-free part of the widget, run under Node: what a view draws, describes and lists. */
class SvgWidgetViewSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Trial(id: String, rt: Double, accuracy: Double, block: String)

  private val trials = Vector(
    Trial("t1", 410, 0.9, "A"),
    Trial("t2", 520, 0.7, "A"),
    Trial("t3", 380, 0.95, "B")
  )
  private val context = RenderContext.unsafe(400, 300)
  private val space = ok(KeySpace("trial", KeyCodec.text))
  private val plan = ok(
    InteractionCompiler.compile(
      ok(
        plot(trials)
          .aes(_.rt, _.accuracy)
          .scaleColorDiscrete(_.block, name = "block")
          .geomPoint()
          .title("Trials")
          .build
      ).plot,
      space,
      ok(DataRevision("d")),
      SemanticId.unsafe("trials"),
      ok(PlanRevision("p")),
      PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    )(_.id)
  )
  private val view = ok(SvgWidgetView.compile(plan, context, "w1", Some("Trials")))

  private def ids(markup: String): Vector[String] =
    """\sid="([^"]+)"""".r.findAllMatchIn(markup).map(_.group(1)).toVector

  test("both SVG copies carry their own id namespace") {
    assert(ids(view.markup).nonEmpty)
    assert(ids(view.markup).forall(_.startsWith("w1-")), ids(view.markup))
    assert(ids(view.emphasisMarkup).forall(_.startsWith("w1-emphasis-")), ids(view.emphasisMarkup))
    assertEquals(ids(view.markup).intersect(ids(view.emphasisMarkup)), Vector.empty)
  }

  test("the text companion lists every mark, then every plot part") {
    val behavior = InteractionBehavior.describingEntities[String](id => s"trial $id")
    val rows = view.companionRows(behavior)
    assertEquals(rows.count(_._1 == "mark"), trials.length)
    assertEquals(
      rows.filter(_._1 == "mark").map(_._2).toSet,
      Set("trial t1", "trial t2", "trial t3")
    )
    assert(rows.exists(row => row._1 == "part" && row._2 == "Trials"), rows)
    assert(rows.exists(row => row._1 == "part" && row._2 == "block: A"), rows)
  }

  test("marks without tooltip content are described by their position in reading order") {
    val rows = view.companionRows(InteractionBehavior.default[String])
    assertEquals(rows.filter(_._1 == "mark").map(_._2).head, "mark 1 of 3")
  }

  test("outline rings become closed SVG path data") {
    val outline = TargetOutline(
      Vector(Vector(DevicePoint(0, 0), DevicePoint(10, 0), DevicePoint(10, 5.5)))
    )
    assertEquals(SvgWidget.pathData(outline), "M0 0L10 0L10 5.5Z")
    assertEquals(SvgWidget.pathData(TargetOutline(Vector.empty)), "")
  }

  test("mounting without a DOM container is a typed error") {
    assert(SvgWidget.mount(js.undefined.asInstanceOf[js.Dynamic], view).isLeft)
  }

  test("a view's picking policy decides whether a hollow point's centre hits it") {
    val points = ok(
      InteractionCompiler.compile(
        ok(plot(trials).aes(_.rt, _.accuracy).size(8).geomPoint().build).plot,
        space,
        ok(DataRevision("d")),
        SemanticId.unsafe("hollow"),
        ok(PlanRevision("p")),
        PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
      )(_.id)
    )
    val byDefault = ok(SvgWidgetView.compile(points, context, "hollow-a"))
    val outline = ok(
      SvgWidgetView.compile(
        points,
        context,
        "hollow-b",
        policy = PickPolicy.default.withHollowPoints(HollowPicking.Outline)
      )
    )
    assertEquals(outline.policy.hollowPoints, HollowPicking.Outline)
    byDefault.navigation.targets.foreach { geometry =>
      val centre = DevicePoint(
        (geometry.left + geometry.right) / 2,
        (geometry.top + geometry.bottom) / 2
      )
      assertEquals(
        ok(byDefault.picking.hits(centre)).map(_.target.id).headOption,
        Some(geometry.target.id),
        "the default hits a hollow point anywhere on its disc"
      )
      assertEquals(ok(outline.picking.hits(centre)), Vector.empty, "outline-only misses its centre")
    }
  }
