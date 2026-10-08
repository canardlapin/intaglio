package intaglio.fulllink

import intaglio.*
import intaglio.canvas.*
import scala.scalajs.js

/** Compiles one plot per representative layer family and paints each through the Canvas renderer
  * onto a recording stub context, printing a line per plot. `tools/check-fulllink.sh` links it with
  * `fullLinkJS` (the optimizer with unchecked semantics, then Closure, as a production bundle is
  * built) and runs it under a wall-clock timeout. Every other Scala.js suite links with fastLinkJS.
  *
  * Keep this program free of ECDF layers. The Scala.js 1.22.0 emitter folds an instance test
  * against a class with no instances in the linked program to `false` and drops the tested
  * expression, side effects included (see .agent-work/fulllink-hang). An ECDF layer would
  * instantiate `StatRow.Ecdf` and hide the hang of bd-01M41R5BTCMQ5R0NPGAZ0S504K, which is exactly
  * what an application without ECDF layers hit.
  */
object FullLinkSmoke:
  private final case class Observation(x: Double, y: Double, group: String)

  private val rows: Vector[Observation] =
    Vector.tabulate(24) { index =>
      val x = index.toDouble / 4.0
      Observation(
        x,
        math.sin(x) + (index % 3).toDouble,
        if index % 2 == 0 then "control" else "task"
      )
    }

  private val context = RenderContext.unsafe(480, 320)

  private def base = plot(rows).aes(_.x, _.y)

  private val programs: Vector[(String, () => Either[GraphicsError, RenderPlan])] =
    Vector(
      "point+line" -> (() => base.group(_.group).geomPoint().geomLine().renderPlan(context)),
      "histogram" -> (() => base.geomHistogram().renderPlan(context)),
      "density" -> (() => base.geomDensity().renderPlan(context)),
      "summary" -> (() => base.geomSummary().renderPlan(context)),
      "quantile-summary" -> (() => base.geomQuantileSummary().renderPlan(context)),
      "area" -> (() => base.geomArea().renderPlan(context))
    )

  /** A 2D context whose every method is a no-op that counts its calls; `measureText` reports a
    * fixed-width advance. Enough for `CanvasRenderer.render` without a DOM.
    */
  private final class StubContext:
    var calls = 0
    private val handler = js.Dynamic.literal(
      get = (target: js.Dynamic, property: js.Any) =>
        val existing = target.selectDynamic(property.toString)
        if !js.isUndefined(existing) then existing
        else
          (((text: js.Any) =>
            calls += 1
            js.Dynamic.literal(width = 6.0 * text.toString.length)
          ): js.Function1[js.Any, js.Any]
        )
    )
    val context: CanvasRenderingContext2D =
      js.Dynamic
        .newInstance(js.Dynamic.global.Proxy)(js.Dynamic.literal(), handler)
        .asInstanceOf[CanvasRenderingContext2D]

  def main(args: Array[String]): Unit =
    val failures = programs.flatMap { (name, program) =>
      println(s"compiling $name")
      val stub = StubContext()
      program().left
        .map(_.message)
        .flatMap(plan => CanvasRenderer.render(plan, stub.context).left.map(_.message)) match
        case Right(canvas) =>
          println(
            s"painted $name (${canvas.commands.length} commands, ${stub.calls} context calls)"
          )
          None
        case Left(message) =>
          Some(s"$name: $message")
    }
    if failures.nonEmpty then
      failures.foreach(failure => System.err.println(s"fulllink-smoke failure: $failure"))
      throw new IllegalStateException(s"${failures.length} plot(s) failed")
    println(s"fulllink-smoke: ok (${programs.length} plots)")
