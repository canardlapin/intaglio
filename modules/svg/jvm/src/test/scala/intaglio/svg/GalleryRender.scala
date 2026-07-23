package intaglio.svg

import intaglio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Reproducible visual gallery runner. It lives in test sources so it cannot
  * become part of the shipped SVG API. Render with
  * `svgJVM / Test / runMain intaglio.svg.GalleryRender <out-dir>`.
  */
object GalleryRender:
  private final case class GalleryItem(
      name: String,
      group: String,
      scene: Scene,
      options: SvgOptions
  )

  private final case class LogObservation(x: Double, y: Double, condition: String)

  def main(args: Array[String]): Unit =
    val outDir = Paths.get(args.find(!_.startsWith("--")).getOrElse("target/gallery"))
    Files.createDirectories(outDir)

    val base = conformanceItems().fold(error => fail(error.message), identity)
    val logItem = logScatterItem().fold(error => fail(error.message), identity)
    val items = base :+ logItem

    val manifest = new StringBuilder
    val cards = new StringBuilder
    items.foreach { item =>
      SvgRenderer.render(item.scene, item.options) match
        case Left(error) =>
          fail(s"${item.name}: ${error.message}")
        case Right(document) =>
          val fileName = s"${item.name}.svg"
          Files.writeString(outDir.resolve(fileName), document.value, StandardCharsets.UTF_8)
          manifest.append(s"${item.name}\t${item.group}\t$fileName\t${document.value.length}\n")
          cards.append(
            s"""      <article><h2>${item.name}</h2><p>${item.group}</p><img src="$fileName" alt="${item.name} gallery case"></article>\n"""
          )
          println(s"[ok] ${item.name} (${item.group}) -> $fileName")
    }

    Files.writeString(outDir.resolve("manifest.tsv"), manifest.result(), StandardCharsets.UTF_8)
    Files.writeString(outDir.resolve("index.html"), galleryHtml(cards.result()), StandardCharsets.UTF_8)
    println(s"wrote ${items.length} SVGs, manifest.tsv, and index.html to $outDir")

  private def conformanceItems(): Either[GraphicsError, Vector[GalleryItem]] =
    val compiled = SvgOptions.unsafe(width = 640, height = 480)
    val primitive = SvgOptions.unsafe(width = 480, height = 360)
    RendererConformance.cases.map { cases =>
      cases.map { conformance =>
        val options =
          if conformance.group == ConformanceGroup.CompiledPlot then compiled
          else primitive
        GalleryItem(conformance.name.value, conformance.group.toString, conformance.scene, options)
      }
    }

  private def logScatterItem(): Either[GraphicsError, GalleryItem] =
    val observations = Vector(
      LogObservation(1.0, 1.0, "A"),
      LogObservation(3.0, 1.5, "B"),
      LogObservation(10.0, 2.0, "A"),
      LogObservation(30.0, 2.6, "B"),
      LogObservation(100.0, 3.0, "A")
    )
    for
      xScale <- ContinuousScale.train(
        "x-log10",
        observations.map(_.x),
        Palette.numeric,
        transform = Transform.log10
      )
      domain <- DiscreteDomain.ordered(Vector("A", "B"))
      colorScale <- DiscreteScale(
        "condition",
        domain,
        DiscretePalette.valuesUnsafe(Vector(Rgba.unsafe(40, 80, 120), Rgba.unsafe(210, 120, 40)))
      )
      plot <- Plot(observations)
        .withScale(ScaleBinding[LogObservation, Double, Double](Aesthetic.X, _.x, xScale))
        .flatMap(_.withScale(ScaleBinding[LogObservation, String, Rgba](Aesthetic.Color, _.condition, colorScale)))
        .flatMap(_.addLayer(Layer.point[LogObservation](_.x, _.y)))
      scene <- PlotCompiler.compile(
        plot,
        PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
      )
    yield GalleryItem(
      "derived-log10-scatter",
      "CompiledPlot",
      scene,
      SvgOptions.unsafe(width = 640, height = 480)
    )

  private def galleryHtml(cards: String): String =
    s"""<!doctype html>
       |<html lang="en">
       |  <head>
       |    <meta charset="utf-8">
       |    <meta name="viewport" content="width=device-width, initial-scale=1">
       |    <title>Intaglio gallery</title>
       |    <style>
       |      body { margin: 0; padding: 2rem; color: #18212b; background: #f3f5f7; font: 15px/1.4 system-ui, sans-serif; }
       |      h1 { margin-top: 0; }
       |      main { display: grid; grid-template-columns: repeat(auto-fit, minmax(420px, 1fr)); gap: 1rem; }
       |      article { padding: 1rem; overflow: auto; background: white; border: 1px solid #d7dde3; border-radius: 8px; }
       |      h2 { margin: 0; font-size: 1rem; }
       |      p { margin: 0.25rem 0 0.75rem; color: #5b6570; }
       |      img { display: block; max-width: 100%; height: auto; border: 1px solid #edf0f2; }
       |    </style>
       |  </head>
       |  <body>
       |    <h1>Intaglio gallery</h1>
       |    <main>
       |$cards    </main>
       |  </body>
       |</html>
       |""".stripMargin

  private def fail(message: String): Nothing =
    throw new IllegalStateException(message)
