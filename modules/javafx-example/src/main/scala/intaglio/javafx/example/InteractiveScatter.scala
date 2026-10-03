package intaglio.javafx.example

import intaglio.*
import intaglio.interaction.*
import intaglio.javafx.*
import _root_.javafx.application.Application
import _root_.javafx.scene.input.{KeyCode, KeyEvent}
import _root_.javafx.scene.layout.{HBox, VBox}
import _root_.javafx.scene.text.{Font, Text}
import _root_.javafx.stage.Stage

/** The desktop example: a navigable scatter and a histogram whose bins select their members, linked
  * over one key space, with tooltips, keyboard roving, legend links and data-window navigation.
  * Everything interactive comes from the shared interaction plan, behaviour and state; the JavaFX
  * host maps toolkit input onto them.
  *
  * Run it with `sbt javafxExample/run`.
  */
object ExampleScene:
  final case class Trial(id: String, rt: Double, accuracy: Double, block: String)

  val trials: Vector[Trial] = Vector.tabulate(60) { i =>
    Trial(
      s"t${i + 1}",
      280.0 + (i * 47) % 420 + (i % 7) * 3.0,
      0.45 + ((i * 13) % 11) / 20.0,
      Vector("A", "B", "C")(i % 3)
    )
  }

  /** The two hosts, their link, and the status line the application writes to. */
  final case class Built(
      root: VBox,
      scatter: JavaFxInteractionHost[String],
      histogram: JavaFxInteractionHost[String],
      link: JavaFxLink[String],
      status: Text
  ):
    def dispose(): Unit =
      link.dispose()
      scatter.dispose()
      histogram.dispose()

  private def ok[A](value: Either[IntaglioError, A]): A =
    value.fold(error => throw new IllegalStateException(error.message), identity)

  /** Build the example on the FX application thread. */
  def build(): Built =
    val context = RenderContext.unsafe(560, 400)
    val options = PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    val trialSpace = ok(KeySpace("trial", KeyCodec.text))
    val blocks = ok(KeySpace("block", KeyCodec.text))
    val byId = trials.map(t => t.id -> t).toMap

    val program = ok(
      plot(trials)
        .aes(_.rt, _.accuracy)
        .scaleColorDiscrete(_.block, levels = Vector("A", "B", "C"), name = "block")
        .size(4)
        .geomPoint()
        .title("Response time and accuracy")
        .axisTitles("RT (ms)", "Accuracy")
        .build
    )
    val scatterPlan = ok(
      InteractionCompiler.compileBound(
        program.plot,
        Vector(
          LayerBinding(program.plot.layers.head, trialSpace)(row => row.asInstanceOf[Trial].id)
            .withLinks(blocks)(row => row.asInstanceOf[Trial].block)
        ),
        ok(DataRevision("trials-1")),
        SemanticId.unsafe("scatter"),
        ok(PlanRevision("trials-1")),
        options
      )
    )
    val histogramPlan = ok(
      InteractionCompiler.compile(
        ok(
          plot(trials)
            .aes(_.rt)
            .geomHistogram(bins = HistogramBins.countUnsafe(8))
            .title("RT distribution")
            .build
        ).plot,
        trialSpace,
        ok(DataRevision("trials-1")),
        SemanticId.unsafe("histogram"),
        ok(PlanRevision("trials-1")),
        options,
        MembershipRetention.ExactKeys
      )(_.id)
    )

    val status = new Text("Click, drag or use the keyboard in either plot.")
    status.setFont(Font.font(13))
    def say(text: String): Unit = status.setText(text)

    val scatterBehavior = ok(
      InteractionBehavior
        .default[String]
        .withTooltip { target =>
          target.entity.flatMap(key => byId.get(key.value)).map { t =>
            ok(
              TargetContent.fields(
                Some(s"Trial ${t.id}"),
                Vector(
                  ok(TargetField("RT", s"${t.rt.toInt} ms")),
                  ok(TargetField("accuracy", f"${t.accuracy}%.2f")),
                  ok(TargetField("block", t.block))
                )
              )
            )
          }
        }
        .withLink(target => target.entity.map(key => ok(TargetLink(s"#trial-${key.value}"))))
        .withLegendLink(LegendLink("block-legend", blocks))
        .withInverseEmphasis(true)
        .withHover(HoverRule.Nearest(16))
    )
    val histogramBehavior = InteractionBehavior
      .default[String]
      .withTooltip(info => info.membership.total.map(n => TargetContent.Text(s"$n trials")))
      .withAggregateSelection(_ => AggregateSelection.Members)
      .withAggregateEmphasis(ok(EmphasisRule.fraction(0.5)))

    val scatter = ok(
      JavaFxInteractionHost.mount(
        ok(JavaFxInteractionView.compile(scatterPlan, context)),
        scatterBehavior,
        onLink = Some(link => say(s"Activated: an application would open ${link.url}")),
        onError = error => say(s"Refused: ${error.message}")
      )
    )
    val histogram = ok(
      JavaFxInteractionHost.mount(
        ok(JavaFxInteractionView.compile(histogramPlan, context)),
        histogramBehavior,
        onError = error => say(s"Refused: ${error.message}")
      )
    )
    val link = ok(JavaFxLink.connect(trialSpace, Vector(scatter, histogram)))
    ok(scatter.subscribe { record =>
      record.event match
        case InteractionEvent.SelectionChanged(selection) =>
          say(s"${selection.entities.size} trials selected (${record.stamp.cause})")
        case InteractionEvent.ViewportChanged(_, value) =>
          say(value.fold("View reset")(v => f"Window x ${v.xMin}%.2f..${v.xMax}%.2f"))
        case _ => ()
    })

    val help = new Text(
      "Arrows / Page Up / Page Down move focus; Enter selects; Escape clears; + / - / 0 zoom.\n" +
        "Modes: I inspect, S select area, L lasso, P pan, Z zoom to area. Wheel zooms a focused " +
        "plot; pinch zooms."
    )
    help.setFont(Font.font(12))
    val plots = new HBox(16, scatter.node, histogram.node)
    val root = new VBox(10, plots, status, help)
    root.setStyle("-fx-padding: 16; -fx-background-color: white;")
    // Mode keys the host leaves alone reach the application; the host has no toolbar of its own.
    root.addEventHandler(
      KeyEvent.KEY_PRESSED,
      (event: KeyEvent) =>
        val mode = event.getCode match
          case KeyCode.I => Some(GestureMode.Inspect)
          case KeyCode.S => Some(GestureMode.Rectangle)
          case KeyCode.L => Some(GestureMode.Lasso)
          case KeyCode.P => Some(GestureMode.Pan)
          case KeyCode.Z => Some(GestureMode.ZoomRectangle)
          case _         => None
        mode.foreach { value =>
          val target = if histogram.node.isFocused then histogram else scatter
          say(
            target.setGestureMode(value).fold(e => s"Refused: ${e.message}", _ => s"Mode: $value")
          )
          event.consume()
        }
    )
    Built(root, scatter, histogram, link, status)

final class InteractiveScatterApp extends Application:
  private var built = Option.empty[ExampleScene.Built]

  override def start(stage: Stage): Unit =
    val example = ExampleScene.build()
    built = Some(example)
    stage.setTitle("Intaglio JavaFX interaction")
    stage.setScene(new _root_.javafx.scene.Scene(example.root))
    stage.show()
    example.scatter.node.requestFocus()

  override def stop(): Unit = built.foreach(_.dispose())

object InteractiveScatter:
  def main(args: Array[String]): Unit =
    Application.launch(classOf[InteractiveScatterApp], args*)
