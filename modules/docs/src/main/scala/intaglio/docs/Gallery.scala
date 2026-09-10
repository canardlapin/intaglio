package intaglio.docs

import java.nio.file.{Files, Path, Paths}
import intaglio.*
import intaglio.svg.{SvgOptions, SvgRenderer}

/** Writes a gallery plate next to the guide that builds it, so the documented source and the image
  * a reader sees are the same artifact.
  *
  * SVG is text, so a plate is diffable and its bytes are a regression court: `tools/check-docs.sh`
  * re-renders every plate and fails when a checked-in file differs from what the current library
  * produces. Nothing here is part of Intaglio's published API.
  */
object Gallery:
  /** Where plates live, relative to the repository root. */
  val directory: Path =
    Paths.get("docs", "gallery")

  val width: Int = 560
  val height: Int = 360

  /** Render `program` to `docs/gallery/<name>.svg` and return the markdown that shows it. */
  def plate(name: String, program: Either[IntaglioError, Scene]): String =
    val options = SvgOptions
      .unsafe(width = width, height = height)
    val svg =
      program
        .flatMap(scene => SvgRenderer.render(scene, options).left.map(identity[IntaglioError]))
        .fold(error => throw new IllegalStateException(s"$name: ${error.message}"), _.value)
    val target = directory.resolve(s"$name.svg")
    Files.createDirectories(directory)
    Files.write(target, svg.getBytes("UTF-8"))
    s"![$name](gallery/$name.svg)"

  /** The same, for a program that stops at a `PlotProgram`. */
  def plot[Row](name: String, program: Either[IntaglioError, PlotProgram[Row]]): String =
    plate(name, program.flatMap(_.scene.left.map(identity[IntaglioError])))
