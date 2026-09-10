package intaglio.svg

import intaglio.*
import java.nio.file.{Files, Path}

/** Ordinary public plot API; values and limits retain their exact binary doubles. */
object PrettyBoundaryVisualProbe:
  final case class Sample(x: Double, y: Double)
  def main(args: Array[String]): Unit =
    require(args.length == 1, "Pass a fresh output directory")
    val out = Path.of(args(0))
    require(!Files.exists(out), "Earlier specimens must not be overwritten")
    val _ = Files.createDirectories(out)
    val extent = 0.49999999999999983
    val values = Vector(Sample(-1.0, -extent), Sample(0.0, 0.0), Sample(1.0, extent))
    val breaks = Breaks.pretty(3).toOption.get
    val transform = Transform("identity", v => v, v => v, breaks = breaks).toOption.get
    Vector(360, 220).foreach { height =>
      val plan = plot(values)
        .aes(_.x, _.y)
        .scaleYContinuous(transform = transform)
        .geomLine()
        .theme(Theme.minimal)
        .title("Near-boundary axis · unchanged values")
        .axisTitles("Observation coordinate", "Estimate (a.u.)")
        .renderPlan(RenderContext.unsafe(width = 640, height = height))
        .toOption
        .get
      val svg = SvgRenderer.render(plan).toOption.get
      val _ = Files.writeString(out.resolve(s"axis-$height.svg"), svg.value)
    }
    val ticks = Axis.ticks(Interval.unsafe(-extent, extent), breaks).toOption.get
    val _ = Files.writeString(
      out.resolve("values.txt"),
      values.map(v => s"${v.x},${v.y}").mkString("\n") + "\nticks=" + ticks
        .map(_.value)
        .mkString(",") + "\n"
    )
