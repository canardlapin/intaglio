package intaglio.java2d

import intaglio.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.{Files, Paths}
import javax.imageio.ImageIO

class CasingVisualSuite extends munit.FunSuite:
  private val blue = Rgba.unsafe(24, 94, 180)
  private val casedMark = GraphicParams
    .unsafe(stroke = Some(blue), lineWidth = 2.0)
    .withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(4.0)))

  private def raster(grobs: Grob*): BufferedImage =
    val program = Java2DRenderer
      .compile(Scene(grobs.toVector), Java2DOptions.unsafe(width = 80, height = 80))
      .fold(e => fail(e.message), identity)
    val image = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    // The renderer installs its own antialiasing hints, so masks below classify coverage.
    try Java2DRenderer.draw(program, graphics)
    finally graphics.dispose()
    image

  /** Pixels that read as the casing (`0xffffff`) or the stroke (`0x185eb4`) once antialiased
    * coverage is counted: mostly opaque and dominated by that paint.
    */
  private def mask(image: BufferedImage, rgb: Int): Set[(Int, Int)] =
    def reads(argb: Int): Boolean =
      val (a, r, g, b) = (argb >>> 24, (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255)
      a > 128 && (if rgb == 0xffffff then r > 200 && g > 200 && b > 200
                  else b > 140 && r < 120)
    (for
      y <- 0 until image.getHeight
      x <- 0 until image.getWidth
      if reads(image.getRGB(x, y))
    yield (x, y)).toSet

  test("a cased hollow circle point paints the same casing band as the equivalent cased path") {
    val point = Grob
      .points(Vector(Point.npcUnsafe(0.5, 0.5)), size = ExtentExpr.npcUnsafe(0.25), gp = casedMark)
      .orThrow
    val ring = (0 until 720).toVector.map { index =>
      val angle = index * 2.0 * math.Pi / 720.0
      Point.npcUnsafe(0.5 + 0.25 * math.cos(angle), 0.5 + 0.25 * math.sin(angle))
    }
    // Round joins: a mitred many-segment ring spikes outward at sub-pixel segments, which is a
    // property of the polygon oracle, not of casing.
    val path = Grob
      .polygon(
        ring,
        gp = GraphicParams
          .unsafe(stroke = Some(blue), lineWidth = 2.0, lineJoin = LineJoin.Round)
          .withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(4.0)))
      )
      .orThrow
    val evidence = Paths.get("target", "feature-evidence")
    Files.createDirectories(evidence)
    // Radius 20 px, stroke 2 px, casing 8 px: the casing band is 16..24 px from the centre and the
    // stroke band 19..21 px. Both images must realise that geometry to within a device pixel.
    for (label, grob) <- Vector("cased-point" -> point, "cased-point-path" -> path) do
      val image = raster(grob)
      ImageIO.write(image, "png", evidence.resolve(s"$label.png").toFile)
      val casing = mask(image, 0xffffff)
      val stroke = mask(image, 0x185eb4)
      def distance(p: (Int, Int)) = math.hypot(p._1 + 0.5 - 40.0, p._2 + 0.5 - 40.0)
      val pixels = for y <- 0 until 80; x <- 0 until 80 yield (x, y)
      val farCasing = casing.filter(p => distance(p) < 15.0 || distance(p) > 25.0)
      val farStroke = stroke.filter(p => math.abs(distance(p) - 20.0) > 2.0)
      val missingCasing = pixels.filter { p =>
        val d = distance(p)
        ((d >= 17.0 && d <= 18.0) || (d >= 22.0 && d <= 23.0)) && !casing.contains(p)
      }
      val missingStroke =
        pixels.filter(p => math.abs(distance(p) - 20.0) <= 0.3 && !stroke.contains(p))
      assert(casing.size > 400, s"$label casing band visible: ${casing.size} pixels")
      assertEquals(farCasing, Set.empty[(Int, Int)], clue(label))
      assertEquals(farStroke, Set.empty[(Int, Int)], clue(label))
      assertEquals(missingCasing.toVector, Vector.empty, clue(label))
      assertEquals(missingStroke.toVector, Vector.empty, clue(label))
      assertEquals(image.getRGB(40, 40) >>> 24, 0, s"$label stays hollow")
  }

  test("a cased cross keeps both bars whole where they cross, singly and in a batch") {
    val single = Grob
      .points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        size = ExtentExpr.npcUnsafe(0.25),
        shape = PointShape.Cross,
        gp = casedMark
      )
      .orThrow
    val batch = Grob
      .pointBatch(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        sizes = BatchColumn.Constant(ExtentExpr.npcUnsafe(0.25)),
        shapes = BatchColumn.Constant(PointShape.Cross),
        graphicParams = BatchColumn.Constant(casedMark)
      )
      .orThrow
    for grob <- Vector(single, batch) do
      val image = raster(grob)
      // Along the horizontal bar beside the centre, inside the vertical bar's casing band.
      for x <- 36 to 44 do
        assertEquals(image.getRGB(x, 40) & 0xffffff, 0x185eb4, s"bar pixel ($x, 40)")
      assertEquals(image.getRGB(46, 37) & 0xffffff, 0xffffff, "casing still frames the bars")
  }
  test("cased line stays visible over light and dark raster halves") {
    val program = Java2DRenderer
      .compile(
        RendererConformance.casedRasterScene,
        Java2DOptions.unsafe(width = 200, height = 100)
      )
      .fold(e => fail(e.message), identity)
    val image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
      Java2DRenderer.draw(program, graphics)
    finally graphics.dispose()
    val output = Paths.get("target", "feature-evidence", "stroke-casing.png")
    Files.createDirectories(output.getParent)
    ImageIO.write(image, "png", output.toFile)
    def rgb(x: Int, y: Int) = image.getRGB(x, y) & 0xffffff
    for xs <- Vector(40 until 80, 120 until 160) do
      assert(xs.exists(x => rgb(x, 50) == 0x185eb4), "blue foreground survives on each background")
      assert(
        xs.forall(x => rgb(x, 46) == 0xffffff),
        "solid white casing survives even at dash gaps"
      )
      assert(xs.exists(x => rgb(x, 50) == 0xffffff), "dash gaps reveal casing")
    assertEquals(rgb(60, 42), 0xf5f5f5)
    assertEquals(rgb(140, 42), 0x141e2d)
  }
