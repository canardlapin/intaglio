package intaglio.javafx.example

import intaglio.*
import intaglio.interaction.*
import intaglio.javafx.*
import _root_.javafx.scene.layout.Pane

/** The JavaFX twins of the browser fixture pages behind tools/check-host-trace-browser.cjs. Each
  * page compiles the same plots from the same rows with the same options, mounts them with the same
  * behaviours, and reports events, selections and announcements in the browser fixture's own
  * normalized strings, so one script yields directly comparable traces on both hosts.
  *
  * Mount, drive and read a page on the FX application thread.
  */
trait TracePage:
  def name: String
  def slots: Vector[String]

  /** Each slot's host node position in the 1200 x 900 scene. */
  def layout: Map[String, (Double, Double)]
  def mount(root: Pane): Unit
  def host(slot: String): JavaFxInteractionHost[String]

  /** The view the page mounted, which addresses marks as the browser fixture does: by the mounted
    * view's reading order even after navigation re-windows the host.
    */
  def mounted(slot: String): JavaFxInteractionView[String]
  def events(slot: String): Vector[String]
  def selected(slot: String): Vector[String]
  def parts: Vector[String] = Vector.empty
  def missing: Vector[String] = Vector.empty
  def requests: Int = 0
  def lastLink: String = ""
  def errors: Vector[String]
  def select(slot: String, keys: Vector[String]): Unit
  def reply(index: Int, kind: String): Unit =
    throw new IllegalArgumentException(s"page $name answers no membership requests")
  def dispose(): Unit

  /** Device point of bin `k` counted left to right. */
  def bin(slot: String, k: Int): DevicePoint =
    mounted(slot).navigation.targets.sortBy(_.anchor.x).apply(k).anchor

  def legend(slot: String): DevicePoint =
    val view = mounted(slot)
    val named = TracePages.ok(NamedPicking.fromResolved(view.deviceScene, view.context))
    val ring =
      TracePages
        .ok(named.outline(GraphicsName.unsafe("block-legend-entry-0-key"), 0))
        .get
        .rings
        .head
    DevicePoint(
      (ring.map(_.x).min + ring.map(_.x).max) / 2,
      (ring.map(_.y).min + ring.map(_.y).max) / 2
    )

object TracePages:
  def ok[A](value: Either[IntaglioError, A]): A =
    value.fold(error => throw new IllegalStateException(error.message), identity)

  def byName(name: String): TracePage = name match
    case "widget"  => new WidgetPage
    case "linked"  => new LinkedPage
    case "members" => new MembersPage
    case other     => throw new IllegalArgumentException(s"unknown trace page $other")

  /** Shared plumbing: hosts, their event logs and errors. */
  abstract class Base extends TracePage:
    protected var hosts = Map.empty[String, JavaFxInteractionHost[String]]
    protected var views = Map.empty[String, JavaFxInteractionView[String]]
    protected val log = scala.collection.mutable.Map.empty[String, Vector[String]]
    protected var failures = Vector.empty[String]
    protected var followed = ""

    def host(slot: String): JavaFxInteractionHost[String] = hosts(slot)
    def mounted(slot: String): JavaFxInteractionView[String] = views(slot)
    def events(slot: String): Vector[String] = log.getOrElse(slot, Vector.empty)
    def errors: Vector[String] = failures
    override def lastLink: String = followed

    protected def place(
        root: Pane,
        slot: String,
        view: JavaFxInteractionView[String],
        behavior: InteractionBehavior[String],
        describe: EventRecord[String] => String,
        resolver: Option[MembershipResolver[String]] = None
    ): Unit =
      val host = ok(
        JavaFxInteractionHost.mount(
          view,
          behavior,
          resolver = resolver,
          onLink = Some(link => followed = link.url),
          onError = error => failures :+= s"$slot: ${error.message}"
        )
      )
      ok(host.subscribe(record => log(slot) = events(slot) :+ describe(record)))
      val (x, y) = layout(slot)
      host.node.relocate(x, y)
      root.getChildren.add(host.node)
      hosts += slot -> host
      views += slot -> view

    def dispose(): Unit = hosts.values.foreach(host => ok(host.dispose()))

  def simpleName(record: EventRecord[String]): String =
    s"${record.event.getClass.getSimpleName}:${record.stamp.cause}"

  // -----------------------------------------------------------------------------------------------
  // The widget page: modules/browser-fixture/.../Fixture.scala

  final class WidgetPage extends Base:
    final case class Trial(id: String, rt: Double, accuracy: Double, block: String)

    private val trials = Vector.tabulate(24) { i =>
      Trial(
        s"t${i + 1}",
        300.0 + (i * 37) % 400,
        0.5 + ((i * 7) % 10) / 20.0,
        if i % 3 == 0 then "B" else "A"
      )
    }
    private val context = RenderContext.unsafe(480, 320)
    private val space = ok(KeySpace("trial", KeyCodec.text))
    private val options =
      PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    private val byId = trials.map(t => t.id -> t).toMap
    private var partSeen = Vector.empty[String]

    val name = "widget"
    val slots = Vector("left", "right")
    val layout = Map("left" -> (24.0, 60.0), "right" -> (528.0, 60.0))

    private def scatter: JavaFxInteractionView[String] =
      val plan = ok(
        InteractionCompiler.compile(
          ok(
            plot(trials)
              .aes(_.rt, _.accuracy)
              .scaleColorDiscrete(_.block, levels = Vector("A", "B"), name = "block")
              .size(5)
              .geomPoint()
              .title("Response time and accuracy")
              .axisTitles("RT (ms)", "Accuracy")
              .build
          ).plot,
          space,
          ok(DataRevision("s1")),
          SemanticId.unsafe("scatter"),
          ok(PlanRevision("s1")),
          options
        )(_.id)
      )
      ok(JavaFxInteractionView.compile(plan, context))

    private def histogram: JavaFxInteractionView[String] =
      val plan = ok(
        InteractionCompiler.compile(
          ok(plot(trials).aes(_.rt).geomHistogram().title("RT distribution").build).plot,
          space,
          ok(DataRevision("h1")),
          SemanticId.unsafe("histogram"),
          ok(PlanRevision("h1")),
          options
        )(_.id)
      )
      ok(JavaFxInteractionView.compile(plan, context))

    private val scatterBehavior: InteractionBehavior[String] =
      InteractionBehavior
        .default[String]
        .withTooltip { target =>
          target.entity.flatMap(key => byId.get(key.value)).map { trial =>
            ok(
              TargetContent.fields(
                Some(s"Trial ${trial.id}"),
                Vector(
                  ok(TargetField("RT", s"${trial.rt.toInt} ms")),
                  ok(TargetField("accuracy", f"${trial.accuracy}%.2f")),
                  ok(TargetField("note", "<b>not markup</b>"))
                )
              )
            )
          }
        }
        .withLink(target => target.entity.map(key => ok(TargetLink(s"#trial-${key.value}"))))
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
        case InteractionEvent.Activated(t) => s"activate:${t.entity.fold("-")(_.value)}:$cause"
        case InteractionEvent.SelectionChanged(s) =>
          s"select:${s.entities.map(_.value).toVector.sorted.mkString(",")}|${s.targets.size}:$cause"
        case other => s"${other.getClass.getSimpleName}:$cause"

    def mount(root: Pane): Unit =
      place(root, "left", scatter, scatterBehavior, describe)
      place(root, "right", histogram, histogramBehavior, describe)
      slots.foreach { slot =>
        ok(host(slot).subscribeParts {
          case JavaFxPartEvent.Hovered(part) =>
            partSeen :+= s"hover:${part.fold("-")(_.describe)}"
          case JavaFxPartEvent.Activated(part, _) => partSeen :+= s"activate:${part.describe}"
        })
      }

    override def parts: Vector[String] = partSeen

    def selected(slot: String): Vector[String] =
      host(slot).state.toOption.fold(Vector.empty[String])(
        _.selection.entities.map(_.value).toVector.sorted
      )

    def select(slot: String, keys: Vector[String]): Unit =
      ok(host("left").setSelection(Selection(keys.map(id => ok(space.entity(id))).toSet)))

  // -----------------------------------------------------------------------------------------------
  // The linked page: modules/browser-fixture/.../LinkedFixture.scala

  final class LinkedPage extends Base:
    final case class Trial(id: String, rt: Double, accuracy: Double, block: String)

    private val trials = Vector.tabulate(18) { i =>
      Trial(
        s"t${i + 1}",
        300.0 + (i * 53) % 380,
        0.5 + ((i * 7) % 10) / 20.0,
        if i % 3 == 0 then "B" else "A"
      )
    }
    private val context = RenderContext.unsafe(420, 300)
    private val options =
      PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    private val trialSpace = ok(KeySpace("trial", KeyCodec.text))
    private val impostor = ok(KeySpace("trial", KeyCodec.text))
    private val blocks = ok(KeySpace("block", KeyCodec.text))
    private val impostorBlocks = ok(KeySpace("block", KeyCodec.text))
    private var missed = Vector.empty[String]
    private var link = Option.empty[JavaFxLink[String]]

    val name = "linked"
    val slots = Vector("a", "b", "c", "d")
    val layout =
      Map("a" -> (24.0, 24.0), "b" -> (468.0, 24.0), "c" -> (24.0, 348.0), "d" -> (468.0, 348.0))

    private def scatter(
        rows: Vector[Trial],
        prefix: String,
        revision: String,
        space: KeySpace[String],
        categories: KeySpace[String],
        x: Trial => Double,
        y: Trial => Double,
        title: String
    ): JavaFxInteractionView[String] =
      val program = ok(
        plot(rows)
          .aes(x, y)
          .scaleColorDiscrete(_.block, levels = Vector("A", "B"), name = "block")
          .size(5)
          .geomPoint()
          .title(title)
          .build
      )
      val source = program.plot.layers.head
      val plan = ok(
        InteractionCompiler.compileBound(
          program.plot,
          Vector(
            LayerBinding(source, space)(row => row.asInstanceOf[Trial].id)
              .withLinks(categories)(row => row.asInstanceOf[Trial].block)
          ),
          ok(DataRevision(revision)),
          SemanticId.unsafe(prefix),
          ok(PlanRevision(revision)),
          options
        )
      )
      ok(JavaFxInteractionView.compile(plan, context))

    private def histogram(prefix: String): JavaFxInteractionView[String] =
      val plan = ok(
        InteractionCompiler.compile(
          ok(plot(trials).aes(_.rt).geomHistogram().title("RT bins").build).plot,
          trialSpace,
          ok(DataRevision("h")),
          SemanticId.unsafe(prefix),
          ok(PlanRevision("h")),
          options
        )(_.id)
      )
      ok(JavaFxInteractionView.compile(plan, context))

    def mount(root: Pane): Unit =
      val behavior = InteractionBehavior
        .describingEntities[String](id => s"trial $id")
        .withLegendLink(LegendLink("block-legend", blocks))
        .withInverseEmphasis(true)
      val impostorBehavior = behavior.withLegendLink(LegendLink("block-legend", impostorBlocks))
      val histogramBehavior = InteractionBehavior
        .describingEntities[String](id => s"trial $id")
        .withInverseEmphasis(true)
      place(
        root,
        "a",
        scatter(trials, "a", "a1", trialSpace, blocks, _.rt, _.accuracy, "RT by accuracy"),
        behavior,
        simpleName
      )
      place(
        root,
        "b",
        scatter(
          trials.reverse,
          "b",
          "b1",
          trialSpace,
          blocks,
          _.accuracy,
          _.rt,
          "Accuracy by RT"
        ),
        behavior,
        simpleName
      )
      place(root, "c", histogram("c"), histogramBehavior, simpleName)
      place(
        root,
        "d",
        scatter(
          trials,
          "d",
          "d1",
          impostor,
          impostorBlocks,
          _.rt,
          _.accuracy,
          "Impostor key space"
        ),
        impostorBehavior,
        simpleName
      )
      val slotOf = hosts.map(_.swap)
      link = Some(
        ok(
          JavaFxLink.connect(
            trialSpace,
            slots.map(hosts),
            (member, keys) =>
              missed :+= s"${slotOf(member)}:${keys.map(_.value).toVector.sorted.mkString(",")}",
            (member, error) => failures :+= s"${slotOf(member)}: ${error.message}"
          )
        )
      )

    override def missing: Vector[String] = missed

    def selected(slot: String): Vector[String] =
      host(slot).state.toOption.fold(Vector.empty[String])(state =>
        state.selection.entities.map(_.value).toVector.sorted ++
          state.selection.targets.toVector.map(_ => "#target")
      )

    def select(slot: String, keys: Vector[String]): Unit =
      ok(host("a").setSelection(Selection(keys.map(id => ok(trialSpace.entity(id))).toSet)))

    override def dispose(): Unit =
      link.foreach(_.dispose())
      super.dispose()

  // -----------------------------------------------------------------------------------------------
  // The membership page: modules/browser-fixture/.../MembershipFixture.scala

  final class MembersPage extends Base:
    final case class Trial(id: String, rt: Double, accuracy: Double)

    private val trials = Vector.tabulate(30) { i =>
      Trial(s"t${i + 1}", 300.0 + (i * 53) % 400 + 0.5, 0.5 + ((i * 7) % 10) / 20.0)
    }
    private val breaks = Vector(300.0, 400.0, 500.0, 600.0, 700.0)
    private val context = RenderContext.unsafe(420, 300)
    private val options =
      PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    private val space = ok(KeySpace("trial", KeyCodec.text))
    private var missed = Vector.empty[String]
    private var recorded = Vector.empty[MembershipRequest[String]]
    private var deliveries = Vector.empty[MembershipReply[String] => Unit]
    private var link = Option.empty[JavaFxLink[String]]

    val name = "members"
    val slots = Vector("a", "b", "h", "d")
    val layout =
      Map("a" -> (16.0, 60.0), "b" -> (472.0, 60.0), "h" -> (16.0, 400.0), "d" -> (472.0, 400.0))

    /** Independently: the trials in bin j, (b(j), b(j+1)], the first bin also holding b(0). */
    private def oracle(j: Int): Vector[String] =
      trials
        .filter(t => (t.rt > breaks(j) || (j == 0 && t.rt == breaks(0))) && t.rt <= breaks(j + 1))
        .map(_.id)
        .sorted
    private val nonEmptyBins = breaks.indices.dropRight(1).filter(j => oracle(j).nonEmpty).toVector

    private def scatter(rows: Vector[Trial], prefix: String, flip: Boolean) =
      val built = ok(
        (if flip then plot(rows).aes(_.accuracy, _.rt) else plot(rows).aes(_.rt, _.accuracy))
          .size(5)
          .geomPoint()
          .title(s"Scatter $prefix")
          .build
      )
      val plan = ok(
        InteractionCompiler.compile(
          built.plot,
          space,
          ok(DataRevision("d")),
          SemanticId.unsafe(prefix),
          ok(PlanRevision("r")),
          options
        )(_.id)
      )
      ok(JavaFxInteractionView.compile(plan, context))

    private def histogram(prefix: String, retention: MembershipRetention) =
      val built = ok(
        plot(trials)
          .aes(_.rt)
          .geomHistogram(bins = HistogramBins.breaksUnsafe(breaks))
          .title(s"Histogram $prefix")
          .build
      )
      val plan = ok(
        InteractionCompiler.compile(
          built.plot,
          space,
          ok(DataRevision("d")),
          SemanticId.unsafe(prefix),
          ok(PlanRevision("r")),
          options,
          retention
        )(_.id)
      )
      ok(JavaFxInteractionView.compile(plan, context))

    private def describe(record: EventRecord[String]): String =
      val detail = record.event match
        case InteractionEvent.MembershipResolved(_, _, outcome) => s"($outcome)"
        case _                                                  => ""
      s"${record.event.getClass.getSimpleName}$detail:${record.stamp.cause}"

    def mount(root: Pane): Unit =
      val scatterBehavior =
        InteractionBehavior.describingEntities[String](id => s"trial $id").withInverseEmphasis(true)
      val half = ok(EmphasisRule.fraction(0.5))
      val binBehavior = InteractionBehavior
        .default[String]
        .withTooltip(info => info.membership.total.map(n => TargetContent.Text(s"$n trials")))
        .withAggregateSelection(_ => AggregateSelection.Members)
        .withAggregateEmphasis(half)
        .withInverseEmphasis(true)
      val resolver = new MembershipResolver[String]:
        def resolve(
            request: MembershipRequest[String],
            reply: MembershipReply[String] => Unit
        ): Unit =
          recorded :+= request
          deliveries :+= reply
      place(root, "a", scatter(trials, "a", flip = false), scatterBehavior, describe)
      place(root, "b", scatter(trials.reverse, "b", flip = true), scatterBehavior, describe)
      place(root, "h", histogram("h", MembershipRetention.ExactKeys), binBehavior, describe)
      place(
        root,
        "d",
        histogram("d", MembershipRetention.Deferred),
        binBehavior,
        describe,
        Some(resolver)
      )
      link = Some(
        ok(
          JavaFxLink.connect(
            space,
            slots.map(hosts),
            (_, keys) => missed :+= keys.size.toString
          )
        )
      )

    override def missing: Vector[String] = missed
    override def requests: Int = recorded.size

    def selected(slot: String): Vector[String] =
      host(slot).state.toOption.fold(Vector.empty[String])(
        _.selection.entities.map(_.value).toVector.sorted
      )

    def select(slot: String, keys: Vector[String]): Unit =
      ok(host(slot).setSelection(Selection(keys.map(id => ok(space.entity(id))).toSet)))

    override def reply(index: Int, kind: String): Unit =
      val request = recorded(index)
      val k =
        mounted("d").navigation.targets.sortBy(_.anchor.x).map(_.target.id).indexOf(request.target)
      val keys = oracle(nonEmptyBins(k)).map(id => ok(space.entity(id)))
      deliveries(index)(kind match
        case "complete" => MembershipReply.Complete(keys)
        case "short"    => MembershipReply.Complete(keys.drop(1))
        case _          => MembershipReply.Failed("the check said so"))

    override def dispose(): Unit =
      link.foreach(_.dispose())
      super.dispose()
