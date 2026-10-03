package intaglio.javafx.example

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.application.Platform
import _root_.javafx.event.Event
import _root_.javafx.scene.Node
import _root_.javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent, PickResult}
import _root_.javafx.scene.layout.Pane
import _root_.javafx.scene.robot.Robot
import _root_.javafx.stage.Stage
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.concurrent.{CountDownLatch, ExecutionException, FutureTask, TimeUnit}
import scala.jdk.CollectionConverters.*

/** The JavaFX toolkit for a runner thread: start once, run work on the FX application thread. */
object Fx:
  @volatile private var started = false

  def start(): Unit = synchronized {
    if !started then
      val latch = new CountDownLatch(1)
      try Platform.startup(() => { Platform.setImplicitExit(false); latch.countDown() })
      catch case _: IllegalStateException => latch.countDown()
      if !latch.await(30, TimeUnit.SECONDS) then throw new IllegalStateException("FX startup")
      started = true
  }

  def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then body
    else
      val task = new FutureTask[A](() => body)
      Platform.runLater(task)
      try task.get(60, TimeUnit.SECONDS)
      catch case e: ExecutionException => throw e.getCause

  /** Let queued FX work (input, run-later replies, layout) finish. */
  def settle(): Unit =
    fx(())
    Thread.sleep(15)
    fx(())

/** How the trace reaches the JavaFX scene. */
enum InputPath(val label: String):
  /** `javafx.scene.robot.Robot`: input enters through the Glass platform layer (window hit test,
    * enter/exit synthesis, click detection, focus-owner routing). Under Monocle this is the
    * headless Glass implementation; on a desktop it injects OS-level input, which moves the real
    * pointer and types into the focused window.
    */
  case GlassRobot extends InputPath("glass-robot")

  /** JavaFX `MouseEvent`/`KeyEvent` values fired into the live scene graph at the node under the
    * point and at the scene's focus owner: the toolkit's event dispatch chain without OS injection.
    */
  case SceneEvents extends InputPath("scene-events")

trait Driver:
  def moveTo(sceneX: Double, sceneY: Double): Unit
  def press(): Unit
  def release(): Unit
  def click(shift: Boolean): Unit
  def key(code: KeyCode, shift: Boolean): Unit

final class RobotDriver(stage: Stage) extends Driver:
  private val robot = Fx.fx(new Robot())
  private def screen(x: Double, y: Double) = Fx.fx(stage.getScene.getRoot.localToScreen(x, y))
  def moveTo(sceneX: Double, sceneY: Double): Unit =
    val p = screen(sceneX, sceneY)
    Fx.fx(robot.mouseMove(p))
    Fx.settle()
  def press(): Unit =
    Fx.fx(robot.mousePress(MouseButton.PRIMARY))
    Fx.settle()
  def release(): Unit =
    Fx.fx(robot.mouseRelease(MouseButton.PRIMARY))
    Fx.settle()
  def click(shift: Boolean): Unit =
    if shift then Fx.fx(robot.keyPress(KeyCode.SHIFT))
    Fx.fx(robot.mouseClick(MouseButton.PRIMARY))
    Fx.settle()
    if shift then Fx.fx(robot.keyRelease(KeyCode.SHIFT))
    Fx.settle()
  def key(code: KeyCode, shift: Boolean): Unit =
    Fx.fx {
      if shift then robot.keyPress(KeyCode.SHIFT)
      robot.keyPress(code)
      robot.keyRelease(code)
      if shift then robot.keyRelease(KeyCode.SHIFT)
    }
    Fx.settle()

/** Fires toolkit events at the node under the point (a press grabs later drags and the release),
  * synthesizing enter/exit and click as JavaFX does for a still press, and keys at the focus owner.
  *
  * `isolate` keeps a desktop's physical pointer, trackpad and keyboard out of a scripted run: a
  * scene filter consumes every input event the driver is not dispatching itself, so a cursor that
  * happens to rest over the window cannot add hover events to the trace.
  */
final class SceneEventDriver(stage: Stage, targets: Vector[Node], isolate: Boolean = false)
    extends Driver:
  private var driving = false
  if isolate then
    Fx.fx(
      stage.getScene.addEventFilter(
        _root_.javafx.scene.input.InputEvent.ANY,
        (event: _root_.javafx.scene.input.InputEvent) => if !driving then event.consume()
      )
    )

  private def driven(body: => Unit): Unit =
    driving = true
    try body
    finally driving = false
  private var at = (0.0, 0.0)
  private var over = Option.empty[Node]
  private var pressed = Option.empty[Node]
  private var still = false
  private var shift = false

  private def hit(x: Double, y: Double): Option[Node] =
    targets.find(n => n.getLayoutBounds.contains(n.sceneToLocal(x, y)))

  private def fire(node: Node, kind: _root_.javafx.event.EventType[MouseEvent]): Unit =
    val screen = stage.getScene.getRoot.localToScreen(at._1, at._2)
    // The constructor takes scene coordinates; firing at `node` derives the local ones.
    driven(
      Event.fireEvent(
        node,
        new MouseEvent(
          kind,
          at._1,
          at._2,
          screen.getX,
          screen.getY,
          MouseButton.PRIMARY,
          1,
          shift,
          false,
          false,
          false,
          pressed.nonEmpty || kind == MouseEvent.MOUSE_PRESSED,
          false,
          false,
          false,
          false,
          still,
          new PickResult(node, at._1, at._2)
        )
      )
    )

  def moveTo(sceneX: Double, sceneY: Double): Unit =
    Fx.fx {
      at = (sceneX, sceneY)
      pressed match
        case Some(grab) =>
          still = false
          fire(grab, MouseEvent.MOUSE_DRAGGED)
        case None =>
          val target = hit(sceneX, sceneY)
          if over != target then
            over.foreach(fire(_, MouseEvent.MOUSE_EXITED))
            target.foreach(fire(_, MouseEvent.MOUSE_ENTERED))
            over = target
          target.foreach(fire(_, MouseEvent.MOUSE_MOVED))
    }
    Fx.settle()

  def press(): Unit =
    Fx.fx {
      over.foreach { node =>
        still = true
        pressed = Some(node)
        fire(node, MouseEvent.MOUSE_PRESSED)
      }
    }
    Fx.settle()

  def release(): Unit =
    Fx.fx {
      pressed.foreach { node =>
        fire(node, MouseEvent.MOUSE_RELEASED)
        pressed = None
        if still && hit(at._1, at._2).contains(node) then fire(node, MouseEvent.MOUSE_CLICKED)
      }
      still = false
      val target = hit(at._1, at._2)
      if over != target then
        over.foreach(fire(_, MouseEvent.MOUSE_EXITED))
        target.foreach(fire(_, MouseEvent.MOUSE_ENTERED))
        over = target
    }
    Fx.settle()

  def click(withShift: Boolean): Unit =
    shift = withShift
    press()
    release()
    shift = false

  /** The platform shortcut (Cmd on macOS, Ctrl elsewhere) with `code`, at the focus owner. */
  def shortcut(code: KeyCode, withShift: Boolean = false): Unit =
    val mac = sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")
    Fx.fx {
      val owner = Option(stage.getScene.getFocusOwner).getOrElse(stage.getScene.getRoot)
      Seq(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED).foreach { kind =>
        driven(
          Event.fireEvent(owner, new KeyEvent(kind, "", "", code, withShift, !mac, false, mac))
        )
      }
    }
    Fx.settle()

  def key(code: KeyCode, withShift: Boolean): Unit =
    Fx.fx {
      val owner = Option(stage.getScene.getFocusOwner).getOrElse(stage.getScene.getRoot)
      Seq(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED).foreach { kind =>
        driven(
          Event.fireEvent(owner, new KeyEvent(kind, "", "", code, withShift, false, false, false))
        )
      }
    }
    Fx.settle()

/** Replays a host-neutral trace script (a JSON file under tools/trace) against a [[TracePage]] in a
  * live JavaFX stage and records the browser runner's trace shape step by step.
  */
object TraceRunner:
  final case class Outcome(trace: Json, mismatches: Vector[String])

  /** The repository root: the `intaglio.repo` property, or the nearest ancestor holding the
    * scripts.
    */
  def repo: Path =
    sys.props
      .get("intaglio.repo")
      .map(Paths.get(_))
      .getOrElse {
        Iterator
          .iterate(Paths.get("").toAbsolutePath)(_.getParent)
          .takeWhile(_ != null)
          .find(dir => Files.isRegularFile(dir.resolve("tools/trace/widget.json")))
          .getOrElse(throw new IllegalStateException("tools/trace not found above the cwd"))
      }

  def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  def script(page: String): (Json, String) =
    val bytes = Files.readAllBytes(repo.resolve(s"tools/trace/$page.json"))
    (Json.parse(new String(bytes, "UTF-8")), sha256(bytes))

  /** The browser-side sources a recorded trace depends on, by repository-relative path: the browser
    * widget and its trace fixture (main Scala sources), the fixture pages and the recorder.
    * tools/check-host-trace-browser.cjs records the same digest as `sourcesSha256`, so a changed
    * widget fails parity until its traces are re-recorded. Shared interaction code is not listed:
    * the JavaFX replay runs it live, so a change there shows as a step difference instead.
    */
  def browserSources: Vector[String] =
    def under(dir: String, keep: String => Boolean): Vector[String] =
      val root = repo.resolve(dir)
      if !Files.isDirectory(root) then Vector.empty
      else
        val stream = Files.walk(root)
        try
          stream.iterator.asScala
            .filter(Files.isRegularFile(_))
            .map(file => repo.relativize(file).toString.replace('\\', '/'))
            .filter(keep)
            .toVector
        finally stream.close()
    val scala = (path: String) =>
      path.contains("/src/main/") && !path.contains("/target/") && path.endsWith(".scala")
    (under("modules/browser", scala) ++ under("modules/browser-fixture", scala) ++
      under("tools/browser", _.endsWith(".html")) :+
      "tools/check-host-trace-browser.cjs").sorted

  /** One digest of [[browserSources]]: SHA-256 over `path NUL sha256(content) LF` per file. */
  def browserSourcesSha256: String =
    sha256(
      browserSources
        .map(path => path + "\u0000" + sha256(Files.readAllBytes(repo.resolve(path))) + "\n")
        .mkString
        .getBytes("UTF-8")
    )

  /** A recorded browser trace for `page` and `renderer`, if present. */
  def browser(page: String, renderer: String): Option[Json] =
    val file = repo.resolve(s"tools/trace/browser/$page-$renderer.json")
    Option.when(Files.isRegularFile(file))(Json.parse(Files.readString(file)))

  def keyCode(name: String): (KeyCode, Boolean) = name match
    case "ArrowLeft"  => (KeyCode.LEFT, false)
    case "ArrowRight" => (KeyCode.RIGHT, false)
    case "ArrowUp"    => (KeyCode.UP, false)
    case "ArrowDown"  => (KeyCode.DOWN, false)
    case "Home"       => (KeyCode.HOME, false)
    case "End"        => (KeyCode.END, false)
    case "PageUp"     => (KeyCode.PAGE_UP, false)
    case "PageDown"   => (KeyCode.PAGE_DOWN, false)
    case "Enter"      => (KeyCode.ENTER, false)
    case " "          => (KeyCode.SPACE, false)
    case "Escape"     => (KeyCode.ESCAPE, false)
    // A US layout types '+' as Shift with the '=' key, as the browser's '+' key event does.
    case "+"   => (KeyCode.EQUALS, true)
    case "-"   => (KeyCode.MINUS, false)
    case "0"   => (KeyCode.DIGIT0, false)
    case other => throw new IllegalArgumentException(s"no key mapping for '$other'")

  /** The browser tooltip's text content: the title and every label and value, unseparated. */
  def tooltipText(content: TargetContent): String = content match
    case TargetContent.Text(value)         => value
    case TargetContent.Fields(title, rows) =>
      title.getOrElse("") + rows.map(r => r.label + r.value).mkString

  /** The 1200 x 900 scene the browser runner's viewport matches, holding the page's hosts. */
  def stage(page: TracePage, title: String): (Stage, Pane) =
    Fx.fx {
      val root = new Pane()
      root.setStyle("-fx-background-color: white;")
      page.mount(root)
      val stage = new Stage()
      stage.setTitle(title)
      stage.setScene(new _root_.javafx.scene.Scene(root, 1200, 900))
      stage.show()
      stage.toFront()
      stage.requestFocus()
      (stage, root)
    }

  /** Run `script` against `page` (already mounted in `stage`) through `driver`. */
  def run(
      page: TracePage,
      stage: Stage,
      script: Json,
      driver: Driver,
      onStep: (Int, Json) => Unit = (_, _) => ()
  ): Vector[Json] =
    def scenePoint(step: Json): (Double, Double) =
      Fx.fx {
        val slot = step("slot").str
        val host = page.host(slot)
        val local = step.get("mark").map(_.int) match
          case Some(i) =>
            TracePages.ok(host.toLocal(page.mounted(slot).navigation.targets(i).anchor)).get
          case None if step.get("bin").nonEmpty =>
            TracePages.ok(host.toLocal(page.bin(slot, step("bin").int))).get
          case None if step("legend").bool => TracePages.ok(host.toLocal(page.legend(slot))).get
          case None if step("empty").bool  => (4.0, host.node.getHeight - 4)
          case None                        =>
            throw new IllegalArgumentException(s"step names no target: ${step.render}")
        val scene = host.node.localToScene(local._1, local._2)
        (scene.getX, scene.getY)
      }
    def snapshot(): (Map[String, Vector[String]], Vector[String], Vector[String], Int) =
      Fx.fx {
        (page.slots.map(s => s -> page.events(s)).toMap, page.parts, page.missing, page.requests)
      }
    var (events, parts, missing, _) = snapshot()
    val outside = (1150.0, 880.0)
    script("steps").items.zipWithIndex.map { (step, index) =>
      step("op").str match
        case "focus" => Fx.fx(page.host(step("slot").str).node.requestFocus()); Fx.settle()
        case "key"   =>
          val (code, shifted) = keyCode(step("key").str)
          driver.key(code, shifted || step("shift").bool)
        case "move" =>
          val (x, y) = scenePoint(step)
          driver.moveTo(x, y)
        case "click" =>
          val (x, y) = scenePoint(step)
          driver.moveTo(x, y)
          driver.click(step("shift").bool)
        case "press" =>
          val (x, y) = scenePoint(step)
          driver.moveTo(x, y)
          driver.press()
        case "dragOutside" | "leave" => driver.moveTo(outside._1, outside._2)
        case "release"               => driver.release()
        case "wait"                  =>
          Thread.sleep(step("ms").int.toLong)
          Fx.settle()
        case "select" =>
          Fx.fx(page.select(step.get("slot").map(_.str).getOrElse(""), step("keys").strings))
          Fx.settle()
        case "reply" =>
          Fx.fx(page.reply(step("index").int, step("kind").str))
          Fx.settle()
          Fx.settle()
        case other => throw new IllegalArgumentException(s"unknown op $other")
      val (nowEvents, nowParts, nowMissing, requests) = snapshot()
      val fields = Vector.newBuilder[(String, Json)]
      fields += "step" -> Json.Num(index)
      fields += "op" -> Json.Str(step("op").str)
      fields += "events" -> Json.Obj(
        page.slots.map(s => s -> Json.strs(nowEvents(s).drop(events(s).size)))
      )
      fields += "selected" -> Fx.fx(Json.Obj(page.slots.map(s => s -> Json.strs(page.selected(s)))))
      if page.name == "widget" then fields += "parts" -> Json.strs(nowParts.drop(parts.size))
      if page.name != "widget" then fields += "missing" -> Json.strs(nowMissing.drop(missing.size))
      if page.name == "members" then fields += "requests" -> Json.Num(requests)
      step.get("inspect").map(_.str).foreach { slot =>
        fields += "inspect" -> Fx.fx {
          val host = page.host(slot)
          Json.obj(
            "tooltip" -> TracePages.ok(host.tooltip).fold(Json.Null)(c => Json.Str(tooltipText(c))),
            "announce" -> Json.Str(TracePages.ok(host.announcement)),
            "link" -> Json.Str(page.lastLink)
          )
        }
      }
      events = nowEvents
      parts = nowParts
      missing = nowMissing
      onStep(index, step)
      Json.Obj(fields.result())
    }

  /** Step-by-step differences, ignoring field order. */
  def compare(javafx: Vector[Json], browser: Vector[Json]): Vector[String] =
    def canonical(value: Json): Json = value match
      case Json.Obj(fields) => Json.Obj(fields.map((k, v) => k -> canonical(v)).sortBy(_._1))
      case Json.Arr(values) => Json.Arr(values.map(canonical))
      case other            => other
    val sizes =
      if javafx.size == browser.size then Vector.empty
      else Vector(s"${javafx.size} JavaFX steps, ${browser.size} browser steps")
    sizes ++ javafx.zip(browser).collect {
      case (a, b) if canonical(a) != canonical(b) =>
        s"step ${a("step").render}:\n  javafx  ${canonical(a).render}\n  browser ${canonical(b).render}"
    }

  /** The runtime this trace ran on, read from the live toolkit. */
  def runtime(): Json =
    def prop(name: String) = Json.Str(sys.props.getOrElse(name, ""))
    def reflect(cls: String, method: String): String =
      try
        val owner = Class.forName(cls).getMethod(method).invoke(null)
        if owner == null then "" else owner.getClass.getName
      catch case _: Throwable => ""
    val glass = Fx.fx(reflect("com.sun.glass.ui.Application", "GetApplication"))
    val prism = Fx.fx(reflect("com.sun.prism.GraphicsPipeline", "getPipeline"))
    val screen = Fx.fx(_root_.javafx.stage.Screen.getPrimary)
    Json.obj(
      "os.name" -> prop("os.name"),
      "os.version" -> prop("os.version"),
      "os.arch" -> prop("os.arch"),
      "java.version" -> prop("java.version"),
      "java.vendor" -> prop("java.vendor"),
      "java.vm.name" -> prop("java.vm.name"),
      "javafx.runtime.version" -> prop("javafx.runtime.version"),
      "glass.application" -> Json.Str(glass),
      "prism.pipeline" -> Json.Str(prism),
      "screen.outputScale" -> Json.Num(Fx.fx(screen.getOutputScaleX)),
      "screen.bounds" -> Json.Str(Fx.fx(screen.getBounds.toString)),
      "headless" -> Json.Bool(glass.toLowerCase.contains("monocle"))
    )
