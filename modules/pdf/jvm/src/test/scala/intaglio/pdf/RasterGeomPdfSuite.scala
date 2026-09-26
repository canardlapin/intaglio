package intaglio.pdf

import intaglio.*
import scala.jdk.CollectionConverters.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject

class RasterGeomPdfSuite extends munit.FunSuite:
  test("scalar raster is one lossless PDF image XObject with source alpha") {
    val field = ScalarField2D.unsafe(
      RegularGridAxis.cellCenteredUnsafe(0, 3, 3),
      RegularGridAxis.cellCenteredUnsafe(0, 2, 2),
      Vector(0, 1, 2, 3, 4, 5).map(_.toDouble)
    )
    val trained = plot(field).geomRaster(alpha = 0.6).resolve.orThrow
    val source = trained.layers.head.grobs.head.asInstanceOf[Grob.Image].image
    val input =
      getClass.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
    val bytes = try input.readAllBytes()
    finally input.close()
    val font = PdfFont.fromBytes("Liberation Sans", bytes).fold(e => fail(e.message), identity)
    val fonts = PdfFontCatalog.single(font)
    val context = RenderContext.unsafe(fontRegistry = fonts.fontRegistry)
    val output = PdfRenderer
      .render(RenderPlan(trained.scene, context), fonts)
      .fold(e => fail(e.message), identity)
    val pdf = Loader.loadPDF(output.bytes)
    try
      val resources = pdf.getPage(0).getResources
      val images = resources.getXObjectNames.asScala.toVector.map(resources.getXObject).collect {
        case image: PDImageXObject => image
      }
      assertEquals(images.size, 1)
      val decoded = images.head.getImage
      assertEquals((decoded.getWidth, decoded.getHeight), (3, 2))
      for y <- 0 until 2; x <- 0 until 3 do
        val p = source.pixelUnsafe(x, y)
        assertEquals(
          decoded.getRGB(x, y),
          (p.alpha << 24) | (p.red << 16) | (p.green << 8) | p.blue
        )
    finally pdf.close()
  }
