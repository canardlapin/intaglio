package intaglio.javafx.example

import java.nio.file.Files

/** Shared-trace parity (Interaction 12, criterion 1): each tools/trace script, replayed through the
  * JavaFX host, yields the same normalized events, selected keys, part events, missing-key reports,
  * membership requests, tooltips, announcements and followed links, step by step, as the browser
  * widget recorded on both its SVG and Canvas renderers (tools/trace/browser/, written by
  * tools/check-host-trace-browser.cjs).
  *
  * This is headless toolkit evidence: Monocle's Glass robot and software Prism, not a desktop
  * window. NativeEvidence replays the same scripts on a real desktop toolkit.
  */
class JavaFxTraceParitySuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  override def beforeAll(): Unit = Fx.start()

  private val evidence = TraceRunner.repo.resolve("modules/javafx-example/target/trace-evidence")

  test("the comparison sees a single changed event, selection or announcement") {
    val steps = TraceRunner.browser("widget", "svg").get("steps").items
    def edit(index: Int, key: String, value: Json): Vector[Json] =
      steps.updated(
        index,
        steps(index) match
          case Json.Obj(fields) =>
            Json.Obj(fields.map((k, v) => if k == key then k -> value else k -> v))
          case other => other
      )
    assertEquals(TraceRunner.compare(steps, steps), Vector.empty)
    val event = edit(
      15,
      "events",
      Json.obj("left" -> Json.strs(Vector("hover:t5:Pointer")), "right" -> Json.strs(Nil))
    )
    assertEquals(TraceRunner.compare(event, steps).size, 1)
    val keys =
      edit(16, "selected", Json.obj("left" -> Json.strs(Vector("t4")), "right" -> Json.strs(Nil)))
    assertEquals(TraceRunner.compare(keys, steps).size, 1)
    val spoken = edit(
      1,
      "inspect",
      Json.obj("tooltip" -> Json.Null, "announce" -> Json.Str(""), "link" -> Json.Str(""))
    )
    assertEquals(TraceRunner.compare(spoken, steps).size, 1)
    assert(
      TraceRunner.compare(steps.dropRight(1), steps).nonEmpty,
      "a missing step is a difference"
    )
  }

  for
    page <- Vector("widget", "linked", "members")
    path <- InputPath.values.toVector
  do
    test(s"$page trace through ${path.label} matches the recorded SVG and Canvas browser traces") {
      val (script, sha) = TraceRunner.script(page)
      val recorded = Vector("svg", "canvas").map { renderer =>
        val trace = TraceRunner
          .browser(page, renderer)
          .getOrElse(fail(s"no recorded browser trace tools/trace/browser/$page-$renderer.json"))
        assertEquals(
          trace("scriptSha256").str,
          sha,
          s"$page-$renderer.json was recorded from another script; re-record it"
        )
        assertEquals(
          trace.get("sourcesSha256").flatMap(_.strOpt),
          Some(TraceRunner.browserSourcesSha256),
          s"$page-$renderer.json was recorded from other browser widget sources; re-record it " +
            "with tools/check-host-trace-browser.cjs"
        )
        renderer -> trace("steps").items
      }
      assertEquals(
        TraceRunner.compare(recorded(0)._2, recorded(1)._2),
        Vector.empty,
        "the two browser renderers disagree"
      )
      val tracePage = TracePages.byName(page)
      val (stage, _) = TraceRunner.stage(tracePage, s"trace $page")
      try
        val driver = path match
          case InputPath.GlassRobot  => new RobotDriver(stage)
          case InputPath.SceneEvents =>
            new SceneEventDriver(
              stage,
              Fx.fx(tracePage.slots.map(tracePage.host(_).node)),
              isolate = true
            )
        val steps = TraceRunner.run(tracePage, stage, script, driver)
        Files.createDirectories(evidence)
        Files.writeString(
          evidence.resolve(s"$page-javafx-${path.label}.json"),
          Json
            .obj(
              "page" -> Json.Str(page),
              "renderer" -> Json.Str("javafx"),
              "input" -> Json.Str(path.label),
              "scriptSha256" -> Json.Str(sha),
              "runtime" -> TraceRunner.runtime(),
              "steps" -> Json.Arr(steps)
            )
            .render
        )
        assertEquals(Fx.fx(tracePage.errors), Vector.empty)
        assertEquals(TraceRunner.compare(steps, recorded(0)._2), Vector.empty)
      finally
        Fx.fx {
          tracePage.dispose()
          stage.close()
        }
    }
