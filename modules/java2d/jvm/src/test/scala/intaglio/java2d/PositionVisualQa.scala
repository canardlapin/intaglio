package intaglio.java2d

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import javax.imageio.ImageIO
import intaglio.*

/** Renders canonical compiled plots beside ggplot2 reference images produced by
  * `tools/r-parity/render_graphics_position_reference.R`.
  */
object PositionVisualQa:
  private final case class Example(name: String, scalaCase: Either[GraphicsError, ConformanceCase])

  def main(args: Array[String]): Unit =
    val root = Paths.get(args.headOption.getOrElse("target/graphics-position-qa"))
    val scalaDir = root.resolve("intaglio")
    Files.createDirectories(scalaDir)
    val examples = Vector(
      Example("scatter", RendererConformance.scatterComparisonCase),
      Example("line", RendererConformance.groupedLineComparisonCase),
      Example("histogram", RendererConformance.histogramComparisonCase),
      Example("density", RendererConformance.densityComparisonCase),
      Example("summary", RendererConformance.summaryComparisonCase),
      Example("ribbon", RendererConformance.ribbonComparisonCase),
      Example("tiles", RendererConformance.tileComparisonCase),
      Example("heatmap", RendererConformance.heatmapComparisonCase),
      Example("bin2d", RendererConformance.bin2DComparisonCase),
      Example("kde2d", RendererConformance.kde2DComparisonCase),
      Example("contour", RendererConformance.contourComparisonCase),
      Example("filled-contour", RendererConformance.filledContourComparisonCase),
      Example("count", RendererConformance.countPlotCase),
      Example("facets", RendererConformance.facetedPlotCase),
      Example("dodge", RendererConformance.dodgedPositionCase),
      Example("stack", RendererConformance.stackedPositionCase),
      Example("jitter", RendererConformance.jitteredPositionCase)
    )
    val options = Java2DOptions.unsafe(width = 640, height = 480)

    examples.foreach { example =>
      val scene = example.scalaCase.fold(error => fail(error.message), _.scene)
      render(scene, options, scalaDir.resolve(s"${example.name}.png"))
    }
    Files.writeString(
      root.resolve("index.html"),
      comparisonHtml(examples.map(_.name)),
      StandardCharsets.UTF_8
    )
    Files.writeString(
      root.resolve("manifest.tsv"),
      examples
        .map(example =>
          s"${example.name}\tintaglio/${example.name}.png\tggplot2/${example.name}.png"
        )
        .mkString(
          "case\tintaglio\tggplot2\n",
          "\n",
          "\n"
        ),
      StandardCharsets.UTF_8
    )
    println(s"wrote plotting visual QA to $root")

  private def render(scene: Scene, options: Java2DOptions, path: Path): Unit =
    val image = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try
      graphics.setColor(Color.WHITE)
      graphics.fillRect(0, 0, options.width, options.height)
      Java2DRenderer.render(scene, graphics, options).fold(error => fail(error.message), identity)
    finally graphics.dispose()
    ImageIO.write(image, "png", path.toFile)

  private def comparisonHtml(names: Vector[String]): String =
    val rows = names
      .map { name =>
        s"""      <section>
         |        <h2>$name</h2>
         |        <figure><figcaption>Intaglio</figcaption><img src="intaglio/$name.png" alt="Intaglio $name"></figure>
         |        <figure><figcaption>ggplot2</figcaption><img src="ggplot2/$name.png" alt="ggplot2 $name"></figure>
         |      </section>""".stripMargin
      }
      .mkString("\n")
    s"""<!doctype html>
       |<html lang="en">
       |  <head>
       |    <meta charset="utf-8">
       |    <meta name="viewport" content="width=device-width, initial-scale=1">
       |    <title>Intaglio plotting visual QA</title>
       |    <style>
       |      body { margin: 0; padding: 2rem; color: #18212b; background: #f3f5f7; font: 15px/1.4 system-ui, sans-serif; }
       |      h1 { margin-top: 0; }
       |      section { display: grid; grid-template-columns: repeat(2, minmax(0, 640px)); gap: 1rem; margin: 0 0 1.5rem; padding: 1rem; background: white; border: 1px solid #d7dde3; border-radius: 8px; }
       |      h2 { grid-column: 1 / -1; margin: 0; }
       |      figure { margin: 0; }
       |      figcaption { margin-bottom: 0.5rem; font-weight: 650; }
       |      img { display: block; width: 100%; max-width: 640px; height: auto; border: 1px solid #edf0f2; }
       |      @media (max-width: 900px) { section { grid-template-columns: minmax(0, 640px); } h2 { grid-column: 1; } }
       |    </style>
       |  </head>
       |  <body>
       |    <h1>Intaglio / ggplot2 comparison</h1>
       |$rows
       |  </body>
       |</html>
       |""".stripMargin

  private def fail(message: String): Nothing =
    throw new IllegalStateException(message)
