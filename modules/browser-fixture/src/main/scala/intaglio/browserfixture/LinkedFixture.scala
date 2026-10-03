package intaglio.browserfixture

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*

/** The linked-views page behind tools/check-linked-browser.cjs: two scatters of the same trials in
  * different row orders and arrangements, a histogram whose bins are selected as bins, and a fourth
  * plot keyed by an impostor key space with the same namespace text. All four are linked.
  */
object LinkedFixture:
  final case class Trial(id: String, rt: Double, accuracy: Double, block: String)

  private val trials = Vector.tabulate(18) { i =>
    Trial(
      s"t${i + 1}",
      300.0 + (i * 53) % 380,
      0.5 + ((i * 7) % 10) / 20.0,
      if i % 3 == 0 then "B" else "A"
    )
  }

  private def orThrow[A](value: Either[IntaglioError, A]): A =
    value.fold(error => throw new IllegalStateException(error.message), identity)

  private val context = RenderContext.unsafe(420, 300)
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
  private val trialSpace = orThrow(KeySpace("trial", KeyCodec.text))
  private val impostor = orThrow(KeySpace("trial", KeyCodec.text))
  private val blocks = orThrow(KeySpace("block", KeyCodec.text))
  private val impostorBlocks = orThrow(KeySpace("block", KeyCodec.text))

  private def scatter(
      rows: Vector[Trial],
      prefix: String,
      revision: String,
      space: KeySpace[String],
      categories: KeySpace[String],
      x: Trial => Double,
      y: Trial => Double,
      title: String
  ): SvgWidgetView[String] =
    val program = orThrow(
      plot(rows)
        .aes(x, y)
        .scaleColorDiscrete(_.block, levels = Vector("A", "B"), name = "block")
        .size(5)
        .geomPoint()
        .title(title)
        .build
    )
    val source = program.plot.layers.head
    // The DSL's layer package hides its row type; its rows are the plot's trials.
    val plan = orThrow(
      InteractionCompiler.compileBound(
        program.plot,
        Vector(
          LayerBinding(source, space)(row => row.asInstanceOf[Trial].id)
            .withLinks(categories)(row => row.asInstanceOf[Trial].block)
        ),
        orThrow(DataRevision(revision)),
        SemanticId.unsafe(prefix),
        orThrow(PlanRevision(revision)),
        options
      )
    )
    orThrow(SvgWidgetView.compile(plan, context, prefix, Some(title)))

  private def histogram(prefix: String): SvgWidgetView[String] =
    val plan = orThrow(
      InteractionCompiler.compile(
        orThrow(plot(trials).aes(_.rt).geomHistogram().title("RT bins").build).plot,
        trialSpace,
        orThrow(DataRevision("h")),
        SemanticId.unsafe(prefix),
        orThrow(PlanRevision("h")),
        options
      )(_.id)
    )
    orThrow(SvgWidgetView.compile(plan, context, prefix, Some("RT bins")))

  def run(): Unit =
    val document = g.document
    val behavior = InteractionBehavior
      .describingEntities[String](id => s"trial $id")
      .withLegendLink(LegendLink("block-legend", blocks))
      .withInverseEmphasis(true)
    val slots = Vector("a", "b", "c", "d")
    var views = Map(
      "a" -> scatter(trials, "a", "a1", trialSpace, blocks, _.rt, _.accuracy, "RT by accuracy"),
      "b" -> scatter(
        trials.reverse,
        "b",
        "b1",
        trialSpace,
        blocks,
        _.accuracy,
        _.rt,
        "Accuracy by RT"
      ),
      "c" -> histogram("c"),
      "d" -> scatter(
        trials,
        "d",
        "d1",
        impostor,
        impostorBlocks,
        _.rt,
        _.accuracy,
        "Impostor key space"
      )
    )
    // Plot d's legend links its own (impostor) category space, as its marks do.
    val impostorBehavior = behavior.withLegendLink(LegendLink("block-legend", impostorBlocks))
    // The histogram draws no block legend, so it carries no legend link.
    val histogramBehavior = InteractionBehavior
      .describingEntities[String](id => s"trial $id")
      .withInverseEmphasis(true)
    def behaviorOf(slot: String) = slot match
      case "d" => impostorBehavior
      case "c" => histogramBehavior
      case _   => behavior
    val widgets = slots.map { slot =>
      slot -> orThrow(
        SvgWidget.mount(
          document.getElementById(s"linked-$slot"),
          views(slot),
          behaviorOf(slot),
          label = s"plot $slot",
          options = WidgetOptions(renderer =
            if g.window.location.search.asInstanceOf[String].contains("canvas") then
              WidgetRenderer.Canvas
            else WidgetRenderer.Svg
          )
        )
      )
    }.toMap
    val events = js.Dictionary(slots.map(s => s -> js.Array[String]())*)
    val missing = js.Array[String]()
    slots.foreach { slot =>
      widgets(slot).subscribe { record =>
        events(slot).push(s"${record.event.getClass.getSimpleName}:${record.stamp.cause}")
      }
    }
    val slotOf = widgets.map(_.swap)
    var link = orThrow(
      WidgetLink.connect(
        trialSpace,
        slots.map(widgets),
        (widget, keys) =>
          missing.push(s"${slotOf(widget)}:${keys.map(_.value).toVector.sorted.mkString(",")}")
      )
    )

    def markPoint(slot: String, index: Int): js.Array[Double] =
      val view = views(slot)
      val anchor = view.navigation.targets(index).anchor
      val box = document
        .querySelector(s"[data-intaglio-widget=$slot] .intaglio-base")
        .getBoundingClientRect()
      val scale = box.width.asInstanceOf[Double] / view.width
      js.Array(
        box.left.asInstanceOf[Double] + anchor.x * scale,
        box.top.asInstanceOf[Double] + anchor.y * scale
      )

    def legendPoint(slot: String): js.Array[Double] =
      val view = views(slot)
      val named = orThrow(NamedPicking.compile(view.scene, view.context))
      val ring = orThrow(
        named.outline(GraphicsName.unsafe("block-legend-entry-0-key"), 0)
      ).get.rings.head
      val x = (ring.map(_.x).min + ring.map(_.x).max) / 2
      val y = (ring.map(_.y).min + ring.map(_.y).max) / 2
      val box = document
        .querySelector(s"[data-intaglio-widget=$slot] .intaglio-base")
        .getBoundingClientRect()
      val scale = box.width.asInstanceOf[Double] / view.width
      js.Array(box.left.asInstanceOf[Double] + x * scale, box.top.asInstanceOf[Double] + y * scale)

    def markEntity(slot: String, index: Int): String =
      views(slot).navigation.targets(index).target.entity.fold("-")(_.value)

    def indexOf(slot: String, entity: String): Int =
      views(slot).navigation.targets.indexWhere(_.target.entity.exists(_.value == entity))

    def selected(slot: String): js.Array[String] =
      widgets(slot).state.toOption.fold(js.Array[String]())(state =>
        (state.selection.entities.map(_.value).toVector.sorted ++
          state.selection.targets.toVector.map(_ => "#target")).toJSArray
      )

    def rings(slot: String, kind: String): Int =
      document
        .querySelectorAll(s"[data-intaglio-widget=$slot] .intaglio-ring-$kind")
        .length
        .asInstanceOf[Int]

    def replaceB(): String =
      val next =
        scatter(
          trials.drop(5).reverse,
          "b",
          "b2",
          trialSpace,
          blocks,
          _.accuracy,
          _.rt,
          "Accuracy by RT"
        )
      views += "b" -> next
      widgets("b").update(next).fold(_.message, _ => "ok")

    def selectInA(ids: js.Array[String]): String =
      // A reader-equivalent selection: dispatched as the application, then projected by the link.
      widgets("a")
        .setSelection(Selection(ids.toVector.map(id => orThrow(trialSpace.entity(id))).toSet))
        .fold(_.message, _ => "ok")

    def unlink(): Unit = link.dispose()

    /** Legend links that cannot link anything are refused at mount, not discovered at run time. */
    def badLegendLinks(): js.Array[String] =
      val scratch = document.createElement("div")
      document.body.appendChild(scratch)
      def attempt(link: LegendLink, prefix: String): String =
        SvgWidget
          .mount(
            scratch,
            scatter(trials, prefix, "x1", impostor, impostorBlocks, _.rt, _.accuracy, "probe"),
            InteractionBehavior.default[String].withLegendLink(link)
          )
          .fold(_.message, widget => { widget.dispose(); "mounted" })
      val results = js.Array(
        attempt(LegendLink("block-legend", blocks), "probe1"),
        attempt(LegendLink("blocks-legend", impostorBlocks), "probe2")
      )
      document.body.removeChild(scratch)
      results

    /** A widget already in a live link cannot join another. */
    def relink(): String =
      WidgetLink
        .connect(trialSpace, Vector(widgets("a"), widgets("b")))
        .fold(_.message, _ => "linked")

    g.window.intaglioLinked = js.Dynamic.literal(
      events = events,
      missing = missing,
      markPoint = (slot: String, index: Int) => markPoint(slot, index),
      legendPoint = (slot: String) => legendPoint(slot),
      markEntity = (slot: String, index: Int) => markEntity(slot, index),
      indexOf = (slot: String, entity: String) => indexOf(slot, entity),
      markCount = (slot: String) => views(slot).navigation.targets.size,
      blockCount = (block: String) => trials.count(_.block == block),
      selected = (slot: String) => selected(slot),
      rings = (slot: String, kind: String) => rings(slot, kind),
      replaceB = () => replaceB(),
      selectInA = (ids: js.Array[String]) => selectInA(ids),
      unlink = () => unlink(),
      badLegendLinks = () => badLegendLinks(),
      relink = () => relink(),
      ready = true
    )
