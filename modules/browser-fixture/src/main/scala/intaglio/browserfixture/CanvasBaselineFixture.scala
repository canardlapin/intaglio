package intaglio.browserfixture

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*

/** Remountable specimens for each remaining baseline behavior, with input rows retained by the
  * application only to provide explicit expectations for the browser test.
  */
object CanvasBaselineFixture:
  final case class Row(id: String, x: Double, y: Double, group: String)
  private def checked[A](result: Either[IntaglioError, A]): A =
    result.fold(e => throw new IllegalStateException(e.message), identity)

  def run(): Unit =
    val rows =
      Vector.tabulate(9)(i => Row(s"r$i", 1 + i % 3, 1 + i / 3, if i % 2 == 0 then "A" else "B"))
    val context = RenderContext.unsafe(600, 400)
    val space = checked(KeySpace("baseline", KeyCodec.text))
    var widget: Option[SvgWidget[String]] = None
    var view: Option[SvgWidgetView[String]] = None
    val events = js.Array[String]()
    val parts = js.Array[String]()
    val errors = js.Array[String]()
    def mount(kind: String, renderer: String): Unit =
      widget.foreach(_.dispose())
      events.length = 0; parts.length = 0; errors.length = 0
      val base = plot(rows).aes(_.x, _.y)
      val spec = kind match
        case "authored-styles" =>
          val applicationColors = Map("r4" -> Rgba.unsafe(15, 150, 120))
          checked(
            base
              .fill(r => applicationColors.getOrElse(r.id, Rgba.unsafe(60, 90, 180)))
              .size(7)
              .geomPoint()
              .build
          ).plot
        case "labels"    => checked(base.geomText(_.id).title("Observation labels").build).plot
        case "histogram" =>
          checked(
            plot(rows)
              .aes(_.x)
              .geomHistogram(bins = checked(HistogramBins.count(3)))
              .title("Three bins")
              .build
          ).plot
        case "line" =>
          checked(base.group(_ => "series").geomLine().title("Connected observations").build).plot
        case "parts" =>
          checked(
            base
              .scaleFillContinuous(_.x, name = "value")
              .size(5)
              .geomPoint()
              .facetWrap(_.group)
              .hline(1.5)
              .title("Observed grid")
              .subtitle("Nine keyed observations")
              .axisTitles("Column", "Row")
              .build
          ).plot
        case _ => checked(base.size(5).geomPoint().title("Observed grid").build).plot
      val plan = checked(
        InteractionCompiler.compile(
          spec,
          space,
          checked(DataRevision("rows1")),
          SemanticId.unsafe("baseline-plan"),
          checked(PlanRevision("p1")),
          PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
        )(_.id)
      )
      val next = checked(SvgWidgetView.compile(plan, context, "baseline"))
      view = Some(next)
      val plain = InteractionBehavior
        .default[String]
        .withTooltip(t =>
          Some(TargetContent.Text(if kind == "histogram" then
            s"bin rows: ${t.membership.total.getOrElse(0)}"
          else t.entity.fold("connected series")(_.value)))
        )
      val behavior = checked(plain.withTooltipDelay(if kind == "delayed" then 350 else 0))
        .withPlacement(kind match
          case "fixed"    => TooltipPlacement.Fixed(28, 42)
          case "anchored" => TooltipPlacement.Anchored(10)
          case _          => TooltipPlacement.Pointer(10))
        .withSelection(kind match
          case "disabled" => SelectionMode.Disabled
          case "single"   => SelectionMode.Single
          case _          => SelectionMode.Multiple)
        .withInverseEmphasis(true)
      val configured =
        if kind == "nearest" then checked(behavior.withHover(HoverRule.Nearest(35))) else behavior
      val initial = if kind == "initial" then Selection(Set(checked(space.entity("r4"))))
      else Selection[String]()
      val mounted = checked(
        SvgWidget.mount(
          g.document.getElementById("baseline"),
          next,
          configured,
          selection = initial,
          onError = e => { errors.push(e.message); () },
          options = WidgetOptions(
            appearance = if kind == "appearance" then
              WidgetAppearance(
                hover = Rgba.unsafe(192, 18, 52),
                tooltipBackground = Rgba.unsafe(20, 40, 60),
                inactiveOpacity = 0.45,
                transitionMs = 250
              )
            else WidgetAppearance.default,
            renderer = if renderer == "canvas" then WidgetRenderer.Canvas
            else WidgetRenderer.Svg
          )
        )
      )
      widget = Some(mounted)
      mounted.subscribe { record =>
        val value = record.event match
          case InteractionEvent.HoverChanged(t) =>
            s"hover:${t.flatMap(_.entity).fold("target")(_.value)}"
          case InteractionEvent.Activated(t) => s"activate:${t.entity.fold("target")(_.value)}"
          case InteractionEvent.SelectionChanged(s) =>
            s"select:${s.entities.map(_.value).toVector.sorted.mkString(",")}:${s.targets.size}"
          case other => other.getClass.getSimpleName
        events.push(s"$value:${record.stamp.cause}")
      }
      mounted.subscribeParts {
        case PartEvent.Hovered(p) => parts.push(s"hover:${p.fold("none")(_.productPrefix)}"); ()
        case PartEvent.Activated(p, cause) => parts.push(s"activate:${p.productPrefix}:$cause"); ()
      }
    def client(x: Double, y: Double): js.Array[Double] =
      val b = g.document.querySelector(".intaglio-base").getBoundingClientRect()
      val scale = b.width.asInstanceOf[Double] / context.width
      js.Array(b.left.asInstanceOf[Double] + x * scale, b.top.asInstanceOf[Double] + y * scale)
    def marks() = view.get.navigation.targets.map { mark =>
      val p = client(mark.anchor.x, mark.anchor.y)
      js.Dynamic.literal(id = mark.target.entity.fold("target")(_.value), x = p(0), y = p(1))
    }.toJSArray
    def partTargets() = view.get.parts.parts.zipWithIndex.flatMap { (part, index) =>
      checked(view.get.parts.outline(part, 0)).toVector.flatMap(_.rings.headOption).map { ring =>
        val p = client(
          (ring.map(_.x).min + ring.map(_.x).max) / 2,
          (ring.map(_.y).min + ring.map(_.y).max) / 2
        )
        js.Dynamic.literal(
          index = index,
          kind = part.part.productPrefix,
          description = part.part.describe,
          x = p(0),
          y = p(1)
        )
      }
    }.toJSArray
    g.window.intaglioBaseline = js.Dynamic.literal(
      ready = true,
      mount = (kind: String, renderer: String) => mount(kind, renderer),
      events = events,
      parts = parts,
      errors = errors,
      marks = () => marks(),
      partTargets = () => partTargets(),
      selected =
        () => checked(widget.get.state).selection.entities.map(_.value).toVector.sorted.toJSArray,
      selectedTargets = () => checked(widget.get.state).selection.targets.size,
      select = (ids: js.Array[String]) =>
        widget.get
          .setSelection(Selection(ids.toVector.map(id => checked(space.entity(id))).toSet))
          .fold(_.message, _ => "ok"),
      invalidAppearance = (renderer: String) =>
        widget.foreach(_.dispose())
        Vector(
          WidgetAppearance(inactiveOpacity = Double.NaN),
          WidgetAppearance(inactiveOpacity = -0.1),
          WidgetAppearance(transitionMs = -1),
          WidgetAppearance(transitionMs = 10001)
        ).map { appearance =>
          val container = g.document.createElement("div")
          val result = SvgWidget.mount(
            container,
            view.get,
            options = WidgetOptions(
              appearance = appearance,
              renderer = if renderer == "canvas" then WidgetRenderer.Canvas else WidgetRenderer.Svg
            )
          )
          val rejectedWithoutDom =
            result.isLeft && container.childElementCount.asInstanceOf[Int] == 0
          result.foreach(_.dispose())
          rejectedWithoutDom
        }.toJSArray
      ,
      dispose = () => widget.foreach(_.dispose())
    )
