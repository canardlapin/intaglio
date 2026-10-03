package intaglio.browserfixture

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*

/** The page application behind tools/check-widget-browser.cjs. It mounts two independent widgets (a
  * keyed scatter with tooltips, links and inverse emphasis, and a histogram) and exposes their
  * event logs, mark positions and lifecycle hooks on `window.intaglioFixture`.
  */
object Fixture:
  final case class Trial(id: String, rt: Double, accuracy: Double, block: String)

  private val trials = Vector.tabulate(24) { i =>
    Trial(
      s"t${i + 1}",
      300.0 + (i * 37) % 400,
      0.5 + ((i * 7) % 10) / 20.0,
      if i % 3 == 0 then "B" else "A"
    )
  }

  private def orThrow[A](value: Either[IntaglioError, A]): A =
    value.fold(error => throw new IllegalStateException(error.message), identity)

  private val context = RenderContext.unsafe(480, 320)
  private val space = orThrow(KeySpace("trial", KeyCodec.text))
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())

  private def scatterView(
      prefix: String,
      revision: String,
      rows: Vector[Trial],
      at: RenderContext = context
  ): SvgWidgetView[String] =
    val options = PlotCompilerOptions(renderContext = Some(at), guides = GuidePolicy.Derived())
    val plan = orThrow(
      InteractionCompiler.compile(
        orThrow(
          plot(rows)
            .aes(_.rt, _.accuracy)
            .scaleColorDiscrete(_.block, levels = Vector("A", "B"), name = "block")
            .size(5)
            .geomPoint()
            .title("Response time and accuracy")
            .axisTitles("RT (ms)", "Accuracy")
            .build
        ).plot,
        space,
        orThrow(DataRevision(revision)),
        SemanticId.unsafe("scatter"),
        orThrow(PlanRevision(revision)),
        options
      )(_.id)
    )
    orThrow(SvgWidgetView.compile(plan, at, prefix, Some("Response time and accuracy")))

  private def histogramView(prefix: String): SvgWidgetView[String] =
    val plan = orThrow(
      InteractionCompiler.compile(
        orThrow(plot(trials).aes(_.rt).geomHistogram().title("RT distribution").build).plot,
        space,
        orThrow(DataRevision("h1")),
        SemanticId.unsafe("histogram"),
        orThrow(PlanRevision("h1")),
        options
      )(_.id)
    )
    orThrow(SvgWidgetView.compile(plan, context, prefix, Some("RT distribution")))

  private val byId = trials.map(t => t.id -> t).toMap

  private val scatterBehavior: InteractionBehavior[String] =
    InteractionBehavior
      .default[String]
      .withTooltip { target =>
        target.entity.flatMap(key => byId.get(key.value)).map { trial =>
          orThrow(
            TargetContent.fields(
              Some(s"Trial ${trial.id}"),
              Vector(
                orThrow(TargetField("RT", s"${trial.rt.toInt} ms")),
                orThrow(TargetField("accuracy", f"${trial.accuracy}%.2f")),
                orThrow(TargetField("note", "<b>not markup</b>"))
              )
            )
          )
        }
      }
      .withLink(target => target.entity.map(key => orThrow(TargetLink(s"#trial-${key.value}"))))
      .withInverseEmphasis(true)

  private val histogramBehavior: InteractionBehavior[String] =
    InteractionBehavior
      .default[String]
      .withTooltip(target =>
        Some(TargetContent.Text(s"bin of ${target.membership.total.getOrElse(0)} trials"))
      )
      .withSelection(SelectionMode.Single)

  private def describe(record: EventRecord[String]): String =
    val cause = record.stamp.cause.toString
    record.event match
      case InteractionEvent.HoverChanged(t) =>
        s"hover:${t.flatMap(_.entity).fold("-")(_.value)}:$cause"
      case InteractionEvent.FocusChanged(t) =>
        s"focus:${t.flatMap(_.entity).fold("-")(_.value)}:$cause"
      case InteractionEvent.Activated(t)        => s"activate:${t.entity.fold("-")(_.value)}:$cause"
      case InteractionEvent.SelectionChanged(s) =>
        s"select:${s.entities.map(_.value).toVector.sorted.mkString(",")}|${s.targets.size}:$cause"
      case other => s"${other.getClass.getSimpleName}:$cause"

  def main(args: Array[String]): Unit =
    val document = g.document
    val events = js.Dictionary[js.Array[String]]("left" -> js.Array(), "right" -> js.Array())
    val parts = js.Array[String]()
    var widgets = Map.empty[String, SvgWidget[String]]
    var views = Map.empty[String, SvgWidgetView[String]]

    def mount(slot: String): Unit =
      val view =
        if slot == "left" then scatterView("left", "s1", trials) else histogramView("right")
      val behavior = if slot == "left" then scatterBehavior else histogramBehavior
      val widget = orThrow(
        SvgWidget.mount(document.getElementById(slot), view, behavior, label = s"$slot plot")
      )
      widget.subscribe(record => events(slot).push(describe(record)))
      widget.subscribeParts {
        case PartEvent.Hovered(part)      => parts.push(s"hover:${part.fold("-")(_.describe)}")
        case PartEvent.Activated(part, _) => parts.push(s"activate:${part.describe}")
      }
      widgets += slot -> widget
      views += slot -> view

    mount("left")
    mount("right")

    /** Client coordinates of mark `index` of widget `slot`, in reading order. */
    def markPoint(slot: String, index: Int): js.Array[Double] =
      val view = views(slot)
      val anchor = view.navigation.targets(index).anchor
      val box = document
        .querySelector(s"[data-intaglio-widget=$slot] svg.intaglio-base")
        .getBoundingClientRect()
      val scale = box.width.asInstanceOf[Double] / view.width
      js.Array(
        box.left.asInstanceOf[Double] + anchor.x * scale,
        box.top.asInstanceOf[Double] + anchor.y * scale
      )

    def markCount(slot: String): Int = views(slot).navigation.targets.size

    def remount(times: Int): Unit =
      (0 until times).foreach { _ =>
        widgets("left").dispose()
        mount("left")
      }

    def update(): String =
      // The same plot with the first five trials removed: their selection must be dropped.
      val next = scatterView("left", "s2", trials.drop(5))
      views += "left" -> next
      widgets("left").update(next).fold(_.message, _ => "ok")

    /** The same data (same revision) at a larger size: the state must stand. */
    def resize(): String =
      val next = scatterView("left", "s1", trials, RenderContext.unsafe(560, 360))
      views += "left" -> next
      widgets("left").update(next).fold(_.message, _ => "ok")

    /** Mounting a second widget under an id prefix already on the page must be refused. */
    def mountDuplicate(): String =
      SvgWidget
        .mount(document.getElementById("right"), scatterView("left", "s1", trials), scatterBehavior)
        .fold(_.message, _ => "mounted")

    /** Updating to a view with another id prefix must be refused. */
    def updateOtherPrefix(): String =
      widgets("left").update(scatterView("other", "s9", trials)).fold(_.message, _ => "ok")

    def setSelection(ids: js.Array[String]): String =
      widgets("left")
        .setSelection(Selection(ids.toVector.map(id => orThrow(space.entity(id))).toSet))
        .fold(_.message, _ => "ok")

    def selected(slot: String): js.Array[String] =
      widgets(slot).state.toOption.fold(js.Array[String]())(state =>
        state.selection.entities.map(_.value).toVector.sorted.toJSArray
      )

    def dispose(slot: String): Int =
      widgets(slot).dispose()
      widgets(slot).domListenerCount

    g.window.intaglioFixture = js.Dynamic.literal(
      events = events,
      parts = parts,
      markPoint = (slot: String, index: Int) => markPoint(slot, index),
      markCount = (slot: String) => markCount(slot),
      remount = (times: Int) => remount(times),
      update = () => update(),
      resize = () => resize(),
      mountDuplicate = () => mountDuplicate(),
      updateOtherPrefix = () => updateOtherPrefix(),
      setSelection = (ids: js.Array[String]) => setSelection(ids),
      dispose = (slot: String) => dispose(slot),
      selected = (slot: String) => selected(slot),
      ready = true
    )
