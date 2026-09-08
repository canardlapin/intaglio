package intaglio.svg

import intaglio.*
import java.nio.file.{Files, Paths}

/** Reproducible legend visual checks; no browser or graphics toolkit required. */
object ScalarLegendGallery:
  def main(args: Array[String]): Unit =
    val output = Paths.get(args.headOption.getOrElse("target/scalar-legends"))
    Files.createDirectories(output)
    val blue = Rgba32.unsafe(30, 70, 170)
    val white = Rgba32.unsafe(245, 245, 245)
    val red = Rgba32.unsafe(180, 35, 35)
    val lower = ScalarRamp.linear(blue, white)
    val upper = ScalarRamp.linear(white, red)
    val window = DisplayWindow.unsafe(-4, 8)
    val sequential = ScalarMapping(ScalarScale.sequential(window, ScalarRamp.linear(blue, red)))
    val diverging = ScalarMapping(ScalarScale.diverging(window, 0, lower, upper).toOption.get)
    val split = ScalarMapping(ScalarScale.split(window, 0, -1, 2, lower, upper).toOption.get)
    val missing = diverging.copy(visibility = ScalarVisibility.Outside(
      ScalarInterval.make(-0.25, 0.25, ScalarEndpointInclusion.Neither).toOption.get), invalid = Rgba32.unsafe(190, 60, 175))
    for
      (name, mapping) <- Vector("sequential" -> sequential, "asymmetric-diverging" -> diverging, "split" -> split, "missing-threshold" -> missing)
      (format, style) <- Vector("manuscript" -> ScalarLegendStyle(), "poster" -> ScalarLegendStyle(widthPt = 440, fontPt = 18, barHeightPt = 26))
    do
      val title = LegendTitle.make("Estimated task-related response relative to the comparison condition", Some("percent signal change")).toOption.get
      val legend = ScalarLegend.make(mapping, title).toOption.get
      val drawing = ScalarLegendDrawing.draw(legend, style).toOption.get
      val options = SvgOptions.unsafe(math.ceil(drawing.widthPt * 4 / 3).toInt, math.ceil(drawing.heightPt * 4 / 3).toInt, Some(name))
      val svg = SvgRenderer.render(drawing.scene, options).toOption.get
      val path = output.resolve(s"$name-$format.svg")
      Files.writeString(path, svg.value)
      println(s"legend=$path width=${options.width} height=${options.height}")
