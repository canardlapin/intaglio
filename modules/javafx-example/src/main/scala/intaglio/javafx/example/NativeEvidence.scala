package intaglio.javafx.example

import intaglio.*
import intaglio.interaction.*
import intaglio.javafx.*
import _root_.javafx.application.Platform
import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.image.WritableImage
import _root_.javafx.scene.transform.Transform
import _root_.javafx.scene.input.KeyCode
import _root_.javafx.stage.Stage
import java.awt.image.BufferedImage
import java.lang.ref.WeakReference
import java.nio.file.{Files, Path, Paths}
import javax.imageio.ImageIO
import scala.sys.process.*

/** Desktop evidence for Interaction 12 on the toolkit this JVM starts (no Monocle properties: the
  * platform's own Glass and Prism). It shows real windows, replays every tools/trace script against
  * the recorded browser traces, checks application-thread ownership, keyboard focus, tooltips,
  * navigation and disposal on the live stage, saves scene snapshots of the real stages as PNG, and
  * writes `evidence.json` naming the OS, JDK, OpenJFX, Glass/Prism implementation and source.
  *
  * {{{
  * sbt "javafxExample/runMain intaglio.javafx.example.NativeEvidence <out dir> [--os-input]"
  * }}}
  *
  * By default input is fired into the live scene graph (`scene-events`): the toolkit's dispatch,
  * focus and rendering are native, but no OS input is injected, and the physical pointer and
  * keyboard are filtered out of the scripted stages so a resting cursor cannot alter a trace.
  * `--os-input` drives the Glass robot instead, which moves the real pointer and types into the
  * focused window; it needs the platform's accessibility permission and an otherwise idle desktop,
  * so it is opt-in.
  */
object NativeEvidence:
  private def ok[A](value: Either[IntaglioError, A]): A =
    value.fold(error => throw new IllegalStateException(error.message), identity)

  def main(args: Array[String]): Unit =
    val out = Paths.get(args.headOption.getOrElse("target/native-evidence")).toAbsolutePath
    val osInput = args.contains("--os-input")
    Files.createDirectories(out)
    if sys.props.get("glass.platform").exists(_.equalsIgnoreCase("monocle")) then
      Console.err.println("NativeEvidence must run on a desktop toolkit, not Monocle")
      sys.exit(2)
    Fx.start()
    val runtime = TraceRunner.runtime()
    val shots = Vector.newBuilder[String]
    def snapshot(stage: Stage, name: String): Unit =
      // The stage's own scene graph at its output scale (2 on a Retina display), not a screen grab.
      val image = Fx.fx {
        val parameters = new SnapshotParameters()
        val scale = stage.getOutputScaleX
        parameters.setTransform(Transform.scale(scale, scale))
        stage.getScene.getRoot.snapshot(parameters, null)
      }
      val file = out.resolve(s"$name.png")
      ImageIO.write(Fx.fx(toBuffered(image)), "png", file.toFile)
      shots += out.relativize(file).toString

    val pages = Vector("widget", "linked", "members").map { name =>
      val (script, sha) = TraceRunner.script(name)
      val page = TracePages.byName(name)
      val (stage, _) = TraceRunner.stage(page, s"Intaglio trace: $name")
      Thread.sleep(500)
      Fx.settle()
      val scale = Fx.fx(stage.getOutputScaleX)
      val focusedWindow = Fx.fx(stage.isFocused)
      val driver =
        if osInput then new RobotDriver(stage)
        else new SceneEventDriver(stage, Fx.fx(page.slots.map(page.host(_).node)), isolate = true)
      val steps = TraceRunner.run(
        page,
        stage,
        script,
        driver,
        (index, step) => if step.get("inspect").nonEmpty then snapshot(stage, s"$name-step-$index")
      )
      val browser = TraceRunner.browser(name, "svg").map(_("steps").items).getOrElse(Vector.empty)
      val mismatches = TraceRunner.compare(steps, browser)
      val errors = Fx.fx(page.errors)
      Files.writeString(
        out.resolve(s"$name-javafx.json"),
        Json
          .obj(
            "page" -> Json.Str(name),
            "renderer" -> Json.Str("javafx"),
            "input" -> Json.Str(if osInput then InputPath.GlassRobot.label
            else InputPath.SceneEvents.label),
            "scriptSha256" -> Json.Str(sha),
            "runtime" -> runtime,
            "steps" -> Json.Arr(steps)
          )
          .render
      )
      Fx.fx {
        page.dispose()
        stage.close()
      }
      Json.obj(
        "page" -> Json.Str(name),
        "steps" -> Json.Num(steps.size),
        "browserSteps" -> Json.Num(browser.size),
        "equalToBrowser" -> Json.Bool(browser.nonEmpty && mismatches.isEmpty),
        "mismatches" -> Json.strs(mismatches),
        "hostErrors" -> Json.strs(errors),
        "stageOutputScale" -> Json.Num(scale),
        "windowFocused" -> Json.Bool(focusedWindow)
      )
    }

    val lifecycle = exampleChecks(snapshot)
    val evidence = Json.obj(
      "runtime" -> runtime,
      "source" -> source(),
      "input" -> Json.Str(if osInput then InputPath.GlassRobot.label
      else InputPath.SceneEvents.label),
      "osInputInjected" -> Json.Bool(osInput),
      "traces" -> Json.Arr(pages),
      "lifecycle" -> lifecycle,
      "screenshots" -> Json.strs(shots.result())
    )
    Files.writeString(out.resolve("evidence.json"), evidence.render + "\n")
    println(evidence.render)
    val lifecycleOk = lifecycle match
      case Json.Obj(fields) => fields.forall((_, v) => v("ok").bool)
      case _                => false
    val passed = pages.forall(_("equalToBrowser").bool) && lifecycleOk
    Platform.exit()
    sys.exit(if passed then 0 else 1)

  /** The example application on the live stage: thread ownership, focus, tooltip, navigation and
    * disposal, each with its own verdict, plus screenshots of the example itself.
    */
  private def exampleChecks(snapshot: (Stage, String) => Unit): Json =
    def check(ok: Boolean, detail: (String, Json)*): Json =
      Json.Obj(("ok" -> Json.Bool(ok)) +: detail.toVector)
    val (stage, built) = Fx.fx {
      val built = ExampleScene.build()
      val stage = new Stage()
      stage.setTitle("Intaglio JavaFX interaction")
      stage.setScene(new _root_.javafx.scene.Scene(built.root))
      stage.show()
      stage.toFront()
      stage.requestFocus()
      built.scatter.node.requestFocus()
      (stage, built)
    }
    Thread.sleep(500)
    Fx.settle()
    val scatter = built.scatter
    val driver =
      new SceneEventDriver(stage, Fx.fx(Vector(scatter.node, built.histogram.node)), isolate = true)
    snapshot(stage, "example-initial")

    // Application-thread ownership: the same calls succeed on the FX thread, refuse elsewhere.
    val thread = check(
      scatter.state.left.toOption.contains(JavaFxHostError.WrongThread) &&
        scatter.navigate(PanelWindow.full).left.toOption.contains(JavaFxHostError.WrongThread) &&
        Fx.fx(scatter.state.isRight),
      "offThread" -> Json.Str(scatter.state.left.toOption.fold("")(_.message))
    )

    // Keyboard focus and the immediate keyboard tooltip.
    val focusOwner = Fx.fx(stage.getScene.getFocusOwner eq scatter.node)
    driver.key(KeyCode.HOME, false)
    driver.key(KeyCode.RIGHT, false)
    val tip = Fx.fx(ok(scatter.tooltip)).map(TraceRunner.tooltipText)
    val spoken = Fx.fx(ok(scatter.announcement))
    snapshot(stage, "example-keyboard-focus")
    driver.key(KeyCode.ENTER, false)
    val chosen = Fx.fx(ok(scatter.state).selection.entities.size)
    val linkedBins = Fx.fx(ok(built.histogram.state).selection.entities.size)
    val focus = check(
      focusOwner && tip.nonEmpty && spoken.startsWith("Trial") && chosen == 1 && linkedBins == 1,
      "focusOwner" -> Json.Bool(focusOwner),
      "windowFocused" -> Json.Bool(Fx.fx(stage.isFocused)),
      "tooltip" -> tip.fold(Json.Null)(Json.Str(_)),
      "announcement" -> Json.Str(spoken),
      "selectedAfterEnter" -> Json.Num(chosen),
      "projectedIntoHistogram" -> Json.Num(linkedBins)
    )

    // Pointer hover with the delayed tooltip, through the native scene.
    val (hx, hy) = Fx.fx {
      val g = ok(scatter.currentView).navigation.targets(10)
      val (x, y) = ok(scatter.toLocal(g.anchor)).get
      val p = scatter.node.localToScene(x, y)
      (p.getX, p.getY)
    }
    driver.moveTo(hx, hy)
    Thread.sleep(500)
    Fx.settle()
    val hoverTip = Fx.fx(ok(scatter.tooltip)).map(TraceRunner.tooltipText)
    snapshot(stage, "example-hover-tooltip")
    val hover = check(
      hoverTip.exists(_.startsWith("Trial")),
      "tooltip" -> hoverTip.fold(Json.Null)(Json.Str(_))
    )

    // Navigation: keyboard zoom through the shared navigator, a pan drag, and reset.
    driver.key(KeyCode.EQUALS, true)
    driver.key(KeyCode.EQUALS, true)
    val zoomed = Fx.fx(scatter.currentWindow)
    snapshot(stage, "example-zoomed")
    Fx.fx(ok(scatter.setGestureMode(GestureMode.Pan)))
    val (px, py) = Fx.fx {
      val p = scatter.node.localToScene(280, 200)
      (p.getX, p.getY)
    }
    driver.moveTo(px, py)
    driver.press()
    driver.moveTo(px + 60, py)
    driver.release()
    val panned = Fx.fx(scatter.currentWindow)
    snapshot(stage, "example-panned")
    driver.key(KeyCode.DIGIT0, false)
    val reset = Fx.fx(scatter.currentWindow)
    val navigation = check(
      !zoomed.isFull && panned != zoomed && reset.isFull,
      "zoomed" -> Json.Str(zoomed.toString),
      "panned" -> Json.Str(panned.toString),
      "reset" -> Json.Str(reset.toString)
    )

    // Disposal on the live stage, then repeated mount/dispose cycles that must leave nothing.
    Fx.fx {
      built.dispose()
      stage.close()
    }
    val disposed = Fx.fx(scatter.isDisposed && scatter.node.getChildren.isEmpty)
    val refs = Fx.fx {
      (0 until 200).map { _ =>
        val again = ExampleScene.build()
        again.dispose()
        new WeakReference(again.scatter)
      }
    }
    var attempts = 0
    while refs.exists(_.get() != null) && attempts < 40 do
      System.gc()
      Thread.sleep(25)
      Fx.fx(())
      attempts += 1
    val retained = refs.count(_.get() != null)
    val disposal = check(
      disposed && retained <= 1,
      "disposed" -> Json.Bool(disposed),
      "cycles" -> Json.Num(200),
      "retainedAfterGc" -> Json.Num(retained)
    )
    Json.obj(
      "threadOwnership" -> thread,
      "keyboardFocus" -> focus,
      "hoverTooltip" -> hover,
      "navigation" -> navigation,
      "disposal" -> disposal
    )

  private def source(): Json =
    val repo = TraceRunner.repo.toFile
    def git(args: String*): String =
      try Process(Seq("git") ++ args, repo).!!.trim
      catch case _: Throwable => ""
    Json.obj(
      "sha" -> Json.Str(git("rev-parse", "HEAD")),
      "dirtyPaths" -> Json.Num(git("status", "--porcelain").linesIterator.count(_.nonEmpty))
    )

  private def toBuffered(image: WritableImage): BufferedImage =
    val width = image.getWidth.toInt
    val height = image.getHeight.toInt
    val buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val reader = image.getPixelReader
    for y <- 0 until height; x <- 0 until width do buffered.setRGB(x, y, reader.getArgb(x, y))
    buffered
