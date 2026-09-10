package intaglio.java2d

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths}
import javax.imageio.ImageIO
import intaglio.*

/** Reproducible visual-review gallery for the JVM raster backend. */
object GalleryRender:
  def main(args: Array[String]): Unit =
    val output = Paths.get(args.headOption.getOrElse("target/graphics-java2d-gallery"))
    Files.createDirectories(output)
    val options = Java2DOptions.unsafe(width = 640, height = 480)
    val cases = RendererConformance.cases.fold(
      error => throw new IllegalArgumentException(error.message),
      identity
    )

    val manifest = cases.zipWithIndex.map { case (conformanceCase, index) =>
      val slug = f"${index + 1}%02d-${conformanceCase.name.value}"
      val path = output.resolve(s"$slug.png")
      render(conformanceCase.scene, options, path)
      s"$slug\t${conformanceCase.group}"
    }
    Files.writeString(
      output.resolve("manifest.tsv"),
      manifest.mkString("name\tgroup\n", "\n", "\n")
    )

  private def render(scene: Scene, options: Java2DOptions, path: Path): Unit =
    val program = Java2DRenderer
      .compile(scene, options)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val image = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try Java2DRenderer.draw(program, graphics)
    finally graphics.dispose()
    ImageIO.write(image, "png", path.toFile)
