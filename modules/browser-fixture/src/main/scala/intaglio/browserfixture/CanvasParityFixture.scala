package intaglio.browserfixture

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*

/** Fixed specimens exercise the same event host over two genuinely different paint backends. */
object CanvasParityFixture:
  final case class Row(id: String, x: Double, y: Double, group: String)
  private def checked[A](value: Either[IntaglioError, A]): A =
    value.fold(e => throw new IllegalStateException(e.message), identity)

  def run(): Unit =
    val rows = Vector.tabulate(24)(i =>
      Row(s"r$i", 1 + i.toDouble, 2 + (i * 7 % 19), if i % 2 == 0 then "A" else "B")
    )
    val space = checked(KeySpace("parity", KeyCodec.text))
    val context = RenderContext.unsafe(540, 360)
    val options = PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    def compile(spec: Plot[Row], id: String): InteractionPlan[String] =
      checked(
        InteractionCompiler.compile(
          spec,
          space,
          checked(DataRevision("rows-1")),
          SemanticId.unsafe(id),
          checked(PlanRevision("p1")),
          options
        )(_.id)
      )
    val base = plot(rows).aes(_.x, _.y).size(5).geomPoint()
    val linear = compile(checked(base.title("Observations").build).plot, "linear-plan")
    val clipped = compile(
      checked(
        base.coordZoom(x = Some(Interval.unsafe(5, 20))).title("Clipped observations").build
      ).plot,
      "clipped-plan"
    )
    val log = compile(
      checked(
        base.scaleXContinuous(transform = Transform.log10).title("Log transformed").build
      ).plot,
      "log-plan"
    )
    val facets =
      compile(checked(base.facetWrap(_.group).title("Two facets").build).plot, "facets-plan")
    val composed = checked(InteractionComposition.row(Vector(linear, log), context))
    val views = Map(
      "linear" -> checked(SvgWidgetView.compile(linear, context, "linear")),
      "clipped" -> checked(SvgWidgetView.compile(clipped, context, "clipped")),
      "log" -> checked(SvgWidgetView.compile(log, context, "log")),
      "facets" -> checked(SvgWidgetView.compile(facets, context, "facets")),
      "composed" -> checked(
        SvgWidgetView.compileComposition(composed, checked(PlanRevision("figure1")), "composed")
      )
    )
    val renderer = if g.window.location.search.asInstanceOf[String].contains("canvas") then
      WidgetRenderer.Canvas
    else WidgetRenderer.Svg
    val events = js.Dictionary[js.Array[String]]()
    val errors = js.Array[String]()
    val widgets = views.toVector
      .sortBy(_._1)
      .map { (slot, view) =>
        val container = g.document.createElement("div")
        container.id = s"parity-$slot"
        g.document.getElementById("parity").appendChild(container)
        val widget = checked(
          SvgWidget.mount(
            container,
            view,
            InteractionBehavior.describingEntities[String](identity).withInverseEmphasis(true),
            onError = e => { errors.push(e.message); () },
            options = WidgetOptions(renderer = renderer)
          )
        )
        events(slot) = js.Array[String]()
        widget.subscribe { record =>
          val value = record.event match
            case InteractionEvent.HoverChanged(t) =>
              s"hover:${t.flatMap(_.entity).fold("-")(_.value)}"
            case InteractionEvent.FocusChanged(t) =>
              s"focus:${t.flatMap(_.entity).fold("-")(_.value)}"
            case InteractionEvent.Activated(t)        => s"activate:${t.entity.fold("-")(_.value)}"
            case InteractionEvent.SelectionChanged(s) =>
              s"select:${s.entities.map(_.value).toVector.sorted.mkString(",")}:${s.targets.size}"
            case other => other.getClass.getSimpleName
          events(slot).push(s"$value:${record.stamp.cause}")
        }
        slot -> widget
      }
      .toMap
    def selected(slot: String) =
      checked(widgets(slot).state).selection.entities.map(_.value).toVector.sorted.toJSArray
    def marks(slot: String): js.Array[js.Object] =
      val view = views(slot)
      val box = g.document
        .querySelector(s"[data-intaglio-widget=$slot] .intaglio-base")
        .getBoundingClientRect()
      val scale = box.width.asInstanceOf[Double] / context.width
      view.navigation.targets.map { mark =>
        js.Dynamic.literal(
          id = mark.target.entity.fold("-")(_.value),
          x = box.left.asInstanceOf[Double] + mark.anchor.x * scale,
          y = box.top.asInstanceOf[Double] + mark.anchor.y * scale,
          localX = mark.anchor.x,
          localY = mark.anchor.y
        )
      }.toJSArray
    g.window.intaglioParity = js.Dynamic.literal(
      ready = true,
      events = events,
      errors = errors,
      marks = (slot: String) => marks(slot),
      selected = (slot: String) => selected(slot),
      clear = (slot: String) =>
        widgets(slot).setSelection(Selection[String]()).fold(_.message, _ => "ok"),
      navigate = (slot: String) =>
        widgets(slot).navigate(PanelWindow(Some((0.2, 0.8)), None)).fold(_.message, _ => "ok"),
      window = (slot: String) => {
        val w = widgets(slot).currentWindow
        js.Array(
          w.x.fold(views(slot).panelFrame.fold(0.0)(_.xScale.lower))(_._1),
          w.x.fold(views(slot).panelFrame.fold(1.0)(_.xScale.upper))(_._2),
          w.y.fold(views(slot).panelFrame.fold(0.0)(_.yScale.lower))(_._1),
          w.y.fold(views(slot).panelFrame.fold(1.0)(_.yScale.upper))(_._2)
        )
      },
      reset = (slot: String) => widgets(slot).navigate(PanelWindow.full).fold(_.message, _ => "ok"),
      dispose = () => widgets.values.foreach(_.dispose())
    )
