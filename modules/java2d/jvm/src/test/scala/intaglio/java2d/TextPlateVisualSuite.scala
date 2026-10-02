package intaglio.java2d

import intaglio.*
import java.awt.image.BufferedImage
import java.nio.file.{Files, Paths}
import javax.imageio.ImageIO

/** A text plate measured by Java2D's own font, including a fallback face for a family that is not
  * installed, must bound the glyphs it draws with the requested padding.
  */
class TextPlateVisualSuite extends munit.FunSuite:
  private val plateColor = Rgba.unsafe(0, 160, 0)

  private def raster(label: String, family: Option[String], anchor: Anchor): BufferedImage =
    val gp = GraphicParams
      .unsafe(stroke = None, fill = Some(Rgba.Black), fontFamily = family)
      .withTextPlate(TextPlate(plateColor, padding = StrokeWidth.devicePixelsUnsafe(4.0)))
    val text = Grob.text(label, Point.npcUnsafe(0.5, 0.5), anchor = anchor, gp = gp).orThrow
    val program = Java2DRenderer
      .compile(Scene(Vector(text)), Java2DOptions.unsafe(width = 240, height = 80))
      .fold(e => fail(e.message), identity)
    val image = new BufferedImage(240, 80, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try Java2DRenderer.draw(program, graphics)
    finally graphics.dispose()
    image

  private def bounds(points: Iterable[(Int, Int)]): (Int, Int, Int, Int) =
    (points.map(_._1).min, points.map(_._2).min, points.map(_._1).max, points.map(_._2).max)

  test("the plate bounds the drawn glyphs, with padding, in an installed and a fallback face") {
    for
      family <- Vector(None, Some("Intaglio No Such Face"))
      anchor <- Vector(Anchor.Center, Anchor.BottomLeft)
    do
      val image = raster("Plate Wg", family, anchor)
      val evidence = Paths.get("target", "feature-evidence")
      Files.createDirectories(evidence)
      ImageIO.write(
        image,
        "png",
        evidence.resolve(s"text-plate-${family.isDefined}-${anchor.horizontal}.png").toFile
      )
      val pixels = for y <- 0 until 80; x <- 0 until 240 yield (x, y, image.getRGB(x, y))
      val plate = pixels.collect {
        case (x, y, argb) if (argb >>> 24) > 0 => (x, y)
      }
      val ink = pixels.collect {
        case (x, y, argb) if (argb >>> 24) == 0xff && ((argb >>> 8) & 0xff) < 100 => (x, y)
      }
      assert(ink.nonEmpty, clue(family))
      val (pl, pt, pr, pb) = bounds(plate)
      val (il, it, ir, ib) = bounds(ink)
      // Every glyph pixel lies inside the plate, at least the padding (less a pixel) from its edge.
      assert(il - pl >= 3 && pr - ir >= 3 && it - pt >= 3 && pb - ib >= 3, (family, plate, ink))
      // Tight: the plate is the logical text box plus padding, so its horizontal margin around
      // the ink is the padding plus side bearings, never a guessed width.
      assert(il - pl <= 8 && pr - ir <= 8, (family, (pl, pr), (il, ir)))
  }
