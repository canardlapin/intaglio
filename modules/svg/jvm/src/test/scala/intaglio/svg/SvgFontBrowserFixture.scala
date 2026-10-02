package intaglio.svg

import intaglio.*
import java.nio.file.{Files, Path}

/** Generate actual exporter output for the real-font browser court; no font is bundled. */
object SvgFontBrowserFixture:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "Pass a licensed font file and a fresh output directory")
    val bytes = Files.readAllBytes(Path.of(args(0)))
    val out = Path.of(args(1))
    require(!Files.exists(out), "Earlier specimens must not be overwritten")
    val family = "Intaglio Browser Fixture 739162"
    val face = SvgFontFace(family, bytes).toOption.get
    val fonts = SvgFonts(face).toOption.get
    val text = Grob
      .text(
        "iiii MMMM 0123456789",
        Point.npcUnsafe(0.5, 0.5),
        gp = GraphicParams.unsafe(
          stroke = None,
          fill = Some(Rgba.Black),
          fontFamily = Some(family),
          fontSize = Length.pointsUnsafe(36)
        )
      )
      .orThrow
    val scene = Scene(Vector(text))
    val options = SvgOptions.unsafe(width = 800, height = 180)
    val _ = Files.createDirectories(out)
    val _ = Files.writeString(
      out.resolve("embedded.svg"),
      SvgRenderer.render(scene, options, fonts).orThrow.value
    )
    val _ = Files.writeString(
      out.resolve("fallback.svg"),
      SvgRenderer.render(scene, options).orThrow.value
    )
    val _ = Files.write(out.resolve("reference-font.bin"), bytes)
