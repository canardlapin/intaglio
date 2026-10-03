package intaglio.browserfixture

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*

/** The membership page behind tools/check-membership-browser.cjs: two linked scatters of the same
  * trials in different row orders, a histogram whose bins select their exact members (and show how
  * many of them a linked selection covers), and a histogram whose members are deferred to a
  * resolver the browser check controls. Bin members are checked against an independent bucketing of
  * the raw values, never against the plan.
  */
object MembershipFixture:
  final case class Trial(id: String, rt: Double, accuracy: Double)

  private val trials = Vector.tabulate(30) { i =>
    Trial(s"t${i + 1}", 300.0 + (i * 53) % 400 + 0.5, 0.5 + ((i * 7) % 10) / 20.0)
  }
  private val breaks = Vector(300.0, 400.0, 500.0, 600.0, 700.0)

  private def orThrow[A](value: Either[IntaglioError, A]): A =
    value.fold(error => throw new IllegalStateException(error.message), identity)

  private val context = RenderContext.unsafe(420, 300)
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
  private val space = orThrow(KeySpace("trial", KeyCodec.text))

  /** Independently: the trials in bin j, (b(j), b(j+1)], the first bin also holding b(0). */
  private def oracle(j: Int): Vector[String] =
    trials
      .filter(t => (t.rt > breaks(j) || (j == 0 && t.rt == breaks(0))) && t.rt <= breaks(j + 1))
      .map(_.id)
      .sorted
  private val nonEmptyBins = breaks.indices.dropRight(1).filter(j => oracle(j).nonEmpty).toVector

  private def scatter(rows: Vector[Trial], prefix: String, flip: Boolean): SvgWidgetView[String] =
    val built = orThrow(
      (if flip then plot(rows).aes(_.accuracy, _.rt) else plot(rows).aes(_.rt, _.accuracy))
        .size(5)
        .geomPoint()
        .title(s"Scatter $prefix")
        .build
    )
    val plan = orThrow(
      InteractionCompiler.compile(
        built.plot,
        space,
        orThrow(DataRevision("d")),
        SemanticId.unsafe(prefix),
        orThrow(PlanRevision("r")),
        options
      )(_.id)
    )
    orThrow(SvgWidgetView.compile(plan, context, prefix, Some(s"Scatter $prefix")))

  private def histogram(prefix: String, retention: MembershipRetention): SvgWidgetView[String] =
    val built = orThrow(
      plot(trials)
        .aes(_.rt)
        .geomHistogram(bins = HistogramBins.breaksUnsafe(breaks))
        .title(s"Histogram $prefix")
        .build
    )
    val plan = orThrow(
      InteractionCompiler.compile(
        built.plot,
        space,
        orThrow(DataRevision("d")),
        SemanticId.unsafe(prefix),
        orThrow(PlanRevision("r")),
        options,
        retention
      )(_.id)
    )
    orThrow(SvgWidgetView.compile(plan, context, prefix, Some(s"Histogram $prefix")))

  def run(): Unit =
    val document = g.document
    val canvas = g.window.location.search.asInstanceOf[String].contains("canvas")
    val widgetOptions =
      WidgetOptions(renderer = if canvas then WidgetRenderer.Canvas else WidgetRenderer.Svg)
    val scatterBehavior =
      InteractionBehavior.describingEntities[String](id => s"trial $id").withInverseEmphasis(true)
    val half = orThrow(EmphasisRule.fraction(0.5))
    val binBehavior = InteractionBehavior
      .default[String]
      .withTooltip(info => info.membership.total.map(n => TargetContent.Text(s"$n trials")))
      .withAggregateSelection(_ => AggregateSelection.Members)
      .withAggregateEmphasis(half)
      .withInverseEmphasis(true)

    // A resolver the check drives: it records each request and answers when told to.
    val requests = js.Array[js.Any]()
    var deliveries = Vector.empty[MembershipReply[String] => Unit]
    var recorded = Vector.empty[MembershipRequest[String]]
    val resolver = new MembershipResolver[String]:
      def resolve(
          request: MembershipRequest[String],
          reply: MembershipReply[String] => Unit
      ): Unit =
        recorded :+= request
        deliveries :+= reply
        requests.push(js.Array[js.Any](request.target.ordinal, request.id.toDouble))

    val views = Map(
      "a" -> scatter(trials, "a", flip = false),
      "b" -> scatter(trials.reverse, "b", flip = true),
      "h" -> histogram("h", MembershipRetention.ExactKeys),
      "d" -> histogram("d", MembershipRetention.Deferred)
    )
    val slots = Vector("a", "b", "h", "d")
    val widgets = slots.map { slot =>
      slot -> orThrow(
        SvgWidget.mount(
          document.getElementById(s"members-$slot"),
          views(slot),
          if slot == "a" || slot == "b" then scatterBehavior else binBehavior,
          label = s"plot $slot",
          options = widgetOptions,
          resolver = Option.when(slot == "d")(resolver)
        )
      )
    }.toMap
    val events = js.Dictionary(slots.map(s => s -> js.Array[String]())*)
    slots.foreach { slot =>
      widgets(slot).subscribe { record =>
        val detail = record.event match
          case InteractionEvent.MembershipResolved(_, _, outcome) => s"($outcome)"
          case _                                                  => ""
        events(slot).push(
          s"${record.event.getClass.getSimpleName}$detail:${record.stamp.cause}"
        )
      }
    }
    val missing = js.Array[String]()
    orThrow(
      WidgetLink.connect(space, slots.map(widgets), (_, keys) => missing.push(keys.size.toString))
    )

    def box(slot: String) =
      document.querySelector(s"[data-intaglio-widget=$slot] .intaglio-base").getBoundingClientRect()
    def client(slot: String, x: Double, y: Double): js.Array[Double] =
      val b = box(slot)
      val scale = b.width.asInstanceOf[Double] / views(slot).width
      js.Array(b.left.asInstanceOf[Double] + x * scale, b.top.asInstanceOf[Double] + y * scale)

    /** Scatter marks: [id, clientX, clientY]. */
    def marks(slot: String): js.Array[js.Array[js.Any]] =
      views(slot).navigation.targets.map { geometry =>
        val p = client(slot, geometry.anchor.x, geometry.anchor.y)
        js.Array[js.Any](geometry.target.entity.fold("-")(_.value), p(0), p(1))
      }.toJSArray

    /** Bars left to right: [clientX, clientY, oracle member ids], matched to the oracle's non-empty
      * bins in order.
      */
    def bins(slot: String): js.Array[js.Array[js.Any]] =
      views(slot).navigation.targets
        .sortBy(_.anchor.x)
        .zipWithIndex
        .map { (geometry, k) =>
          val p = client(slot, geometry.anchor.x, geometry.anchor.y)
          js.Array[js.Any](
            p(0),
            p(1),
            oracle(nonEmptyBins(k)).toJSArray,
            geometry.target.id.ordinal
          )
        }
        .toJSArray

    def selected(slot: String): js.Array[String] =
      widgets(slot).state.toOption
        .fold(js.Array[String]())(_.selection.entities.map(_.value).toVector.sorted.toJSArray)

    /** Answer recorded request `index`: "complete" with the oracle's members of its bin, "short"
      * with one fewer, or "failed".
      */
    def reply(index: Int, kind: String): String =
      val request = recorded(index)
      // Each bar is its own target group, so a bar is identified by its whole target id.
      val k =
        views("d").navigation.targets.sortBy(_.anchor.x).map(_.target.id).indexOf(request.target)
      val keys = oracle(nonEmptyBins(k)).map(id => orThrow(space.entity(id)))
      deliveries(index)(kind match
        case "complete" => MembershipReply.Complete(keys)
        case "short"    => MembershipReply.Complete(keys.drop(1))
        case _          => MembershipReply.Failed("the check said so"))
      s"bin $k"

    g.window.intaglioMembers = js.Dynamic.literal(
      events = events,
      missing = missing,
      requests = requests,
      marks = (slot: String) => marks(slot),
      bins = (slot: String) => bins(slot),
      selected = (slot: String) => selected(slot),
      targets = (slot: String) => widgets(slot).state.toOption.fold(0)(_.selection.targets.size),
      covered = (slot: String) =>
        document.querySelectorAll(s"[data-intaglio-widget=$slot] .intaglio-ring-covered").length,
      live = (slot: String) =>
        document.querySelector(s"[data-intaglio-widget=$slot] .intaglio-live").textContent,
      reply = (index: Int, kind: String) => reply(index, kind),
      setSelection = (slot: String, ids: js.Array[String]) =>
        widgets(slot)
          .setSelection(Selection(ids.toVector.map(id => orThrow(space.entity(id))).toSet))
          .fold(_.message, _ => "ok"),
      ready = true
    )
