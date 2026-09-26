package intaglio.java2d

import intaglio.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.{Files, Paths}
import javax.imageio.ImageIO

class CasingVisualSuite extends munit.FunSuite:
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
