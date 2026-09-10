package intaglio.java2d

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import intaglio.*

class Java2DExportSuite extends munit.FunSuite:
  private val scene =
    Scene(
      Vector(
        Grob.rectUnsafe(
          Point.npcUnsafe(0.5, 0.5),
          Size.npcUnsafe(0.5, 0.5),
          gp = GraphicParams.unsafe(
            stroke = None,
            fill = Some(Rgba.unsafe(200, 40, 20, 0.5))
          )
        )
      )
    )

  test("renderImage uses one target for dimensions, density, metrics, and font fallback") {
    val metrics = Java2DTextMetrics("SansSerif")
    val context = RenderContext.unsafe(
      width = 120,
      height = 80,
      pixelsPerInch = 144.0,
      textMetrics = metrics,
      fontRegistry = metrics.fontRegistry,
      deviceScale = 2.0
    )
    val image = Java2DRenderer
      .renderImage(RenderPlan(scene, context))
      .fold(error => fail(error.message), identity)

    assertEquals(image.getWidth, 120)
    assertEquals(image.getHeight, 80)
    assertEquals((image.getRGB(5, 5) >>> 24) & 0xff, 0)
    val center = image.getRGB(60, 40)
    assertEquals((center >>> 24) & 0xff, 128)
    assert(math.abs(((center >>> 16) & 0xff) - 200) <= 1)
    assert(math.abs(((center >>> 8) & 0xff) - 40) <= 1)
    assert(math.abs((center & 0xff) - 20) <= 1)
    assertEquals(
      metrics.fontRegistry.resolve(Some("not-installed-family")),
      Some(metrics.fallbackFamily)
    )
  }

  test("renderPng preserves ARGB pixels and target dimensions") {
    val context = RenderContext.unsafe(width = 37, height = 23, pixelsPerInch = 120.0)
    val plan = RenderPlan(scene, context)
    val expected = Java2DRenderer
      .renderImage(plan)
      .fold(error => fail(error.message), identity)
    val bytes = Java2DRenderer.renderPng(plan).fold(error => fail(error.message), identity)
    val decoded = ImageIO.read(new ByteArrayInputStream(bytes))

    assert(bytes.length > 8)
    assertEquals(bytes.take(8).toVector, Vector(137, 80, 78, 71, 13, 10, 26, 10).map(_.toByte))
    assertEquals(decoded.getWidth, 37)
    assertEquals(decoded.getHeight, 23)
    assertEquals(decoded.getRGB(18, 11), expected.getRGB(18, 11))
    assertEquals(decoded.getRGB(1, 1), expected.getRGB(1, 1))
  }

  test("background and rendering-hint policies are explicit") {
    val background = Rgba.unsafe(5, 15, 25, 0.75)
    val hints = Java2DRenderingHints(
      geometry = Java2DAntialiasing.Disabled,
      text = Java2DAntialiasing.Disabled
    )
    val options = Java2DExportOptions(Java2DBackground.Solid(background), hints)
    val image = Java2DRenderer
      .renderImage(
        Scene(Vector.empty),
        Java2DOptions.unsafe(width = 9, height = 7),
        options
      )
      .fold(error => fail(error.message), identity)
    val pixel = image.getRGB(0, 0)

    assertEquals((pixel >>> 24) & 0xff, 191)
    assertEquals((pixel >>> 16) & 0xff, 5)
    assertEquals((pixel >>> 8) & 0xff, 15)
    assertEquals(pixel & 0xff, 25)

    val probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
    try
      hints.configure(probe)
      assertEquals(
        probe.getRenderingHint(RenderingHints.KEY_ANTIALIASING),
        RenderingHints.VALUE_ANTIALIAS_OFF
      )
      assertEquals(
        probe.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING),
        RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
      )
    finally probe.dispose()
  }
