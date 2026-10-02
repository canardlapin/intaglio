package intaglio.java2d

import intaglio.*
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.{Files, Paths}
import javax.imageio.ImageIO

/** A text plate measured by Java2D's own font, including a fallback face for a family that is not
  * installed, must bound the glyphs it draws with the requested padding.
  */
class TextPlateVisualSuite extends munit.FunSuite:
  private val plateColor = Rgba.unsafe(0, 160, 0)

  private def raster(
      label: String,
      family: Option[String],
      anchor: Anchor,
      plateOnly: Boolean = false
  ): BufferedImage =
    val gp = GraphicParams
      .unsafe(
        stroke = None,
        fill = Some(if plateOnly then Rgba.unsafe(0, 0, 0, 0) else Rgba.Black),
        fontFamily = family
      )
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
      val plateImage = raster("Plate Wg", family, anchor, plateOnly = true)
      val evidence = Paths.get("target", "feature-evidence")
      Files.createDirectories(evidence)
      ImageIO.write(
        image,
        "png",
        evidence.resolve(s"text-plate-${family.isDefined}-${anchor.horizontal}.png").toFile
      )
      val pixels = for y <- 0 until 80; x <- 0 until 240 yield (x, y, image.getRGB(x, y))
      // Measure the plate separately: glyph pixels must not enlarge the supposed plate box.
      val plate = for
        y <- 0 until plateImage.getHeight
        x <- 0 until plateImage.getWidth
        if (plateImage.getRGB(x, y) >>> 24) > 0
      yield (x, y)
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

  test("plates include native glyph overhangs without moving anchored text") {
    val options = Java2DOptions.unsafe(width = 400, height = 240, pixelsPerInch = 72)
    val italic = Java2DFontResolver.fixed(new Font(Font.SERIF, Font.ITALIC, 1))
    val evidence = Paths.get("target", "feature-evidence", "text-plate-overhang")
    Files.createDirectories(evidence)

    def render(
        label: String,
        anchor: Anchor,
        gp: GraphicParams,
        resolver: Java2DFontResolver
    ): BufferedImage =
      val text = Grob.text(label, Point.npcUnsafe(0.5, 0.5), anchor = anchor, gp = gp).orThrow
      val program =
        Java2DRenderer.compile(Scene(Vector(text)), options).fold(e => fail(e.message), identity)
      val image = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_ARGB)
      val graphics = image.createGraphics()
      try Java2DRenderer.draw(program, graphics, Java2DRenderingHints.default, resolver)
      finally graphics.dispose()
      image

    for
      (family, resolver) <- Vector(
        (None, italic),
        (Some("Intaglio No Such Face"), Java2DFontResolver.system)
      )
      label <- Vector("j", "f", "fj", "Wj", "A\u0301")
      horizontal <- HJust.values
      vertical <- VJust.values
    do
      val anchor = Anchor(horizontal, vertical)
      val gp = GraphicParams.unsafe(
        stroke = None,
        fill = Some(Rgba.Black),
        fontFamily = family,
        fontSize = Length.pointsUnsafe(72)
      )
      val plateParams = gp
        .withSolidFill(Some(Rgba.unsafe(0, 0, 0, 0)))
        .withTextPlate(TextPlate(plateColor, padding = StrokeWidth.devicePixelsUnsafe(4)))
      val ink = render(label, anchor, gp, resolver)
      val plate = render(label, anchor, plateParams, resolver)
      val inkPixels = for
        y <- 0 until ink.getHeight
        x <- 0 until ink.getWidth
        if (ink.getRGB(x, y) >>> 24) >= 128
      yield (x, y)
      val platePixels = for
        y <- 0 until plate.getHeight
        x <- 0 until plate.getWidth
        if (plate.getRGB(x, y) >>> 24) > 0
      yield (x, y)
      if family.isEmpty && label == "j" && anchor == Anchor.Center then
        ImageIO.write(ink, "png", evidence.resolve("italic-j-ink.png").toFile)
        ImageIO.write(plate, "png", evidence.resolve("italic-j-plate.png").toFile)
      assert(inkPixels.nonEmpty, (family, label, anchor))
      val (pl, pt, pr, pb) = bounds(platePixels)
      val (il, it, ir, ib) = bounds(inkPixels)
      // Allow one pixel for rasterization at fractional rectangle and glyph boundaries.
      assert(
        il - pl >= 3 && pr - ir >= 3 && it - pt >= 3 && pb - ib >= 3,
        (family, label, anchor, (pl, pt, pr, pb), (il, it, ir, ib))
      )
      assert(inkPixels.forall { case (x, y) => (plate.getRGB(x, y) >>> 24) > 0 })
  }
