package intaglio.browserfixture

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*

/** The analytical-workflow page behind tools/check-history-browser.cjs: two linked scatters of the
  * same trials and a histogram whose bins select their exact members, with an inspector panel. The
  * check names selections (a lasso and a bin's members), combines them, undoes and redoes across
  * the linked plots, saves a snapshot, reloads the page and restores it, tampers with it, and
  * filters the histogram to a selection. Set oracles are computed in the check itself. A faceted
  * scatter, which cannot navigate, is mounted on demand below them for restore refusals.
  */
object HistoryFixture:
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

  private def scatterPlan(prefix: String, flip: Boolean): InteractionPlan[String] =
    val built = orThrow(
      (if flip then plot(trials.reverse).aes(_.accuracy, _.rt)
       else plot(trials).aes(_.rt, _.accuracy))
        .size(5)
        .geomPoint()
        .title(s"Scatter $prefix")
        .build
    )
    orThrow(
      InteractionCompiler.compile(
        built.plot,
        space,
        orThrow(DataRevision("d1")),
        SemanticId.unsafe(prefix),
        orThrow(PlanRevision("r1")),
        options
      )(_.id)
    )

  private def histogramPlan(
      rows: Vector[Trial],
      d: DataRevision,
      p: PlanRevision
  ): Either[IntaglioError, InteractionPlan[String]] =
    plot(rows)
      .aes(_.rt)
      .geomHistogram(bins = HistogramBins.breaksUnsafe(breaks))
      .title("Histogram h")
      .build
      .flatMap(built =>
        InteractionCompiler.compile(
          built.plot,
          space,
          d,
          SemanticId.unsafe("h"),
          p,
          options,
          MembershipRetention.ExactKeys
        )(_.id)
      )

  def run(): Unit =
    val document = g.document
    val canvas = g.window.location.search.asInstanceOf[String].contains("canvas")
    val widgetOptions =
      WidgetOptions(renderer = if canvas then WidgetRenderer.Canvas else WidgetRenderer.Svg)
    val scatterBehavior =
      InteractionBehavior.describingEntities[String](id => s"trial $id").withInverseEmphasis(true)
    val binBehavior = InteractionBehavior
      .default[String]
      .withTooltip(info => info.membership.total.map(n => TargetContent.Text(s"$n trials")))
      .withAggregateSelection(_ => AggregateSelection.Members)
      .withInverseEmphasis(true)
    val firstHistogram = orThrow(
      histogramPlan(trials, orThrow(DataRevision("d1")), orThrow(PlanRevision("r1")))
    )
    val views = Map(
      "a" -> orThrow(
        SvgWidgetView.compile(scatterPlan("a", flip = false), context, "a", Some("Scatter a"))
      ),
      "b" -> orThrow(
        SvgWidgetView.compile(scatterPlan("b", flip = true), context, "b", Some("Scatter b"))
      ),
      "h" -> orThrow(SvgWidgetView.compile(firstHistogram, context, "h", Some("Histogram h")))
    )
    val slots = Vector("a", "b", "h")
    val widgets = slots.map { slot =>
      slot -> orThrow(
        SvgWidget.mount(
          document.getElementById(s"history-$slot"),
          views(slot),
          if slot == "h" then binBehavior else scatterBehavior,
          label = s"plot $slot",
          options = widgetOptions
        )
      )
    }.toMap
    val events = js.Dictionary(slots.map(s => s -> js.Array[String]())*)
    slots.foreach { slot =>
      widgets(slot).subscribe { record =>
        events(slot).push(s"${record.event.getClass.getSimpleName}:${record.stamp.cause}")
      }
    }
    orThrow(WidgetLink.connect(space, slots.map(widgets)))
    val inspector = orThrow(
      InspectorPanel.mount(
        document.getElementById("history-inspector"),
        slots.map(s => s -> widgets(s))
      )
    )
    var histogram = firstHistogram
    var revision = 1
    val filter = FilterCommand[Trial, String](trials, space, _.id, histogramPlan)

    def box(slot: String) =
      document.querySelector(s"[data-intaglio-widget=$slot] .intaglio-base").getBoundingClientRect()
    def client(slot: String, x: Double, y: Double): js.Array[Double] =
      val b = box(slot)
      val scale = b.width.asInstanceOf[Double] / views(slot).width
      js.Array(b.left.asInstanceOf[Double] + x * scale, b.top.asInstanceOf[Double] + y * scale)
    // A faceted plot cannot navigate: a snapshot carrying a viewport must be refused there.
    lazy val fixed =
      val container = document.createElement("div")
      container.id = "history-fixed"
      document.body.appendChild(container)
      val built = orThrow(
        plot(trials)
          .aes(_.rt, _.accuracy)
          .geomPoint()
          .facetWrap(t => if t.rt < 500 then "fast" else "slow")
          .title("Facets f")
          .build
      )
      val planned = orThrow(
        InteractionCompiler.compile(
          built.plot,
          space,
          orThrow(DataRevision("d1")),
          SemanticId.unsafe("f"),
          orThrow(PlanRevision("r1")),
          options
        )(_.id)
      )
      orThrow(
        SvgWidget.mount(
          container,
          orThrow(SvgWidgetView.compile(planned, context, "f", Some("Facets f"))),
          scatterBehavior,
          label = "plot f",
          options = widgetOptions
        )
      )
    def widget(slot: String) = if slot == "f" then fixed else widgets(slot)
    def name(n: String) = SelectionName.unsafe(n)
    def message(result: Either[IntaglioError, ?]): String = result.fold(_.message, _ => "ok")
    def selected(slot: String): js.Array[String] =
      widgets(slot).state.toOption
        .fold(js.Array[String]())(_.selection.entities.map(_.value).toVector.sorted.toJSArray)

    g.window.intaglioHistory = js.Dynamic.literal(
      events = events,
      ids = trials.map(_.id).toJSArray,
      rts = trials.map(_.rt).toJSArray,
      breaks = breaks.toJSArray,
      marks = (slot: String) =>
        views(slot).navigation.targets.map { geometry =>
          val p = client(slot, geometry.anchor.x, geometry.anchor.y)
          js.Array[js.Any](geometry.target.entity.fold("-")(_.value), p(0), p(1))
        }.toJSArray,
      bins = () =>
        widgets("h").state.toOption.fold(js.Array[js.Array[js.Any]]()) { _ =>
          views("h").navigation.targets
            .sortBy(_.anchor.x)
            .map { geometry =>
              val p = client("h", geometry.anchor.x, geometry.anchor.y)
              js.Array[js.Any](p(0), p(1))
            }
            .toJSArray
        },
      selected = (slot: String) => selected(slot),
      named = (slot: String) =>
        widgets(slot).state.toOption.fold(js.Dictionary[js.Array[String]]()) { state =>
          js.Dictionary(
            state.named.toVector.map((n, s) =>
              n.value -> s.entities.map(_.value).toVector.sorted.toJSArray
            )*
          )
        },
      save = (slot: String, n: String) => message(widgets(slot).saveSelection(name(n))),
      recall = (slot: String, n: String, op: String) =>
        message(widgets(slot).recallSelection(name(n), SelectionOperation.valueOf(op))),
      combine = (slot: String, l: String, r: String, how: String, into: String) =>
        message(
          widgets(slot).combineSelections(name(l), name(r), SetCombination.valueOf(how), name(into))
        ),
      canUndo = (slot: String) => widgets(slot).canUndo,
      canRedo = (slot: String) => widgets(slot).canRedo,
      undo = (slot: String) => widgets(slot).undo().fold(_.message, _.toString),
      redo = (slot: String) => widgets(slot).redo().fold(_.message, _.toString),
      zoom = (slot: String, lo: Double, hi: Double) =>
        message(widgets(slot).navigate(PanelWindow(Some((lo, hi)), None))),
      window = (slot: String) =>
        widgets(slot).currentWindow.x.fold[js.Any](null)((lo, hi) => js.Array(lo, hi)),
      snapshot = (slot: String) => widgets(slot).snapshot.fold(_.message, _.toJson),
      restore = (slot: String, json: String) =>
        message(InteractionSnapshot.fromJson(json).flatMap(widgets(slot).restore)),
      // Restore this plot's own snapshot with one saved viewport replaced (positions on the panel).
      restoreViewport = (
          slot: String,
          panel: String,
          x0: Double,
          x1: Double,
          y0: Double,
          y1: Double
      ) =>
        message(
          widget(slot).snapshot.flatMap(saved =>
            widget(slot).restore(
              saved.copy(viewports = Vector(SnapshotViewport(panel, x0, x1, y0, y1)))
            )
          )
        ),
      // The window drawn on both axes and the viewport the state records, or null where full.
      drawn = (slot: String) =>
        val shown = widget(slot).currentWindow
        def pair(value: Option[(Double, Double)]): js.Any =
          value.fold[js.Any](null)((lo, hi) => js.Array(lo, hi))
        js.Dynamic.literal(
          x = pair(shown.x),
          y = pair(shown.y),
          recorded = widget(slot).state.toOption
            .flatMap(_.viewports.values.headOption)
            .fold[js.Any](null)(v => js.Array(v.xMin, v.xMax, v.yMin, v.yMax)),
          canUndo = widget(slot).canUndo,
          canRedo = widget(slot).canRedo
        ),
      filterToSelection = (slot: String) =>
        val keep =
          widgets(slot).state.toOption.fold(Set.empty[EntityKey[String]])(_.selection.entities)
        revision += 1
        val result = filter.keep(
          histogram,
          keep,
          orThrow(DataRevision(s"d$revision")),
          orThrow(PlanRevision(s"r$revision"))
        )
        result
          .flatMap { r =>
            SvgWidgetView.compile(r.plan, context, "h", Some("Histogram h")).flatMap { view =>
              widgets("h").update(view).map { _ =>
                histogram = r.plan
                inspector.showFilter("plot h", r)
                js.Array[js.Any](
                  r.input.rowsBefore,
                  r.input.rowsAfter,
                  r.statistics.flatMap(_.totalsAfter.flatten).toJSArray
                )
              }
            }
          }
          .fold[js.Any](_.message, identity)
      ,
      panel = PlotRegion.Panel.value,
      inspector = () => document.getElementById("history-inspector").textContent,
      ready = true
    )
