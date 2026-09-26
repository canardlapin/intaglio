package intaglio.svg

import intaglio.*
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

class RasterGeomSvgSuite extends munit.FunSuite:
  test("scalar raster is one lossless PNG in SVG") {
    val field = ScalarField2D.unsafe(
      RegularGridAxis.cellCenteredUnsafe(0, 3, 3),
      RegularGridAxis.cellCenteredUnsafe(0, 2, 2),
      Vector(0, 1, 2, 3, 4, 5).map(_.toDouble)
    )
    val trained = plot(field).geomRaster(alpha = 0.6).resolve.orThrow
    val source = trained.layers.head.grobs.head.asInstanceOf[Grob.Image].image
    val svg = SvgRenderer.render(trained.scene).fold(e => fail(e.message), _.value)
    assertEquals("<image\\b".r.findAllIn(svg).size, 1)
    val png = "data:image/png;base64,([^\"]+)".r.findFirstMatchIn(svg).get.group(1)
    val decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder.decode(png)))
    assertEquals((decoded.getWidth, decoded.getHeight), (3, 2))
    for y <- 0 until 2; x <- 0 until 3 do
      val p = source.pixelUnsafe(x, y)
      assertEquals(decoded.getRGB(x, y), (p.alpha << 24) | (p.red << 16) | (p.green << 8) | p.blue)
  }
