package intaglio.pdf

import java.nio.charset.StandardCharsets
import java.awt.Font
import java.awt.font.FontRenderContext
import scala.jdk.CollectionConverters.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.{COSDictionary, COSName, COSString}
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.rendering.{ImageType, PDFRenderer}
import org.apache.pdfbox.text.PDFTextStripper
import intaglio.*

class PdfTrackingSuite extends munit.FunSuite:
  private def fontBytes(): Array[Byte] =
    val path = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    val input = Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing $path"))
    try input.readAllBytes()
    finally input.close()

  private lazy val font =
    PdfFont.fromBytes("Liberation Sans", fontBytes()).fold(error => fail(error.message), identity)
  private lazy val catalog = PdfFontCatalog.single(font)

  private def arabicFontBytes(): Array[Byte] =
    val path = "/fonts/ibm-plex/IBMPlexSansArabic-Regular.ttf"
    val input = Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing $path"))
    try input.readAllBytes()
    finally input.close()

  private lazy val arabicFont =
    PdfFont
      .fromBytes("IBM Plex Sans Arabic", arabicFontBytes())
      .fold(error => fail(error.message), identity)
  private lazy val arabicCatalog = PdfFontCatalog.single(arabicFont)

  private def render(label: String): PdfDocument =
    val scene = Scene(
      Vector(
        Grob.textUnsafe(
          label,
          Point.npcUnsafe(0.5, 0.5),
          gp = GraphicParams
            .unsafe(fontFamily = Some(font.family), fontSize = Length.pointsUnsafe(14.0))
            .withLetterSpacing(LetterSpacing.devicePixelsUnsafe(3.0))
        )
      )
    )
    val context = RenderContext.unsafe(
      width = 240,
      height = 120,
      pixelsPerInch = 144.0,
      fontRegistry = catalog.fontRegistry
    )
    PdfRenderer
      .render(RenderPlan(scene, context), catalog)
      .fold(error => fail(error.message), identity)

  private def withDocument(output: PdfDocument)(body: PDDocument => Unit): Unit =
    val document = Loader.loadPDF(output.bytes)
    try body(document)
    finally document.close()

  private def actualText(document: PDDocument): String =
    val parser = new PDFStreamParser(document.getPage(0))
    val tokens = try parser.parse().asScala.toVector
    finally parser.close()
    tokens.sliding(3).collectFirst {
      case Vector(tag: COSName, properties: COSDictionary, operator: Operator)
          if tag == COSName.getPDFName("Span") && operator.getName == "BDC" =>
        properties.getDictionaryObject(COSName.ACTUAL_TEXT).asInstanceOf[COSString]
      case Vector(tag: COSName, propertyName: COSName, operator: Operator)
          if tag == COSName.getPDFName("Span") && operator.getName == "BDC" =>
        document
          .getPage(0)
          .getResources
          .getProperties(propertyName)
          .getCOSObject
          .getDictionaryObject(COSName.ACTUAL_TEXT)
          .asInstanceOf[COSString]
    } match
      case Some(value) =>
        val bytes = value.getBytes
        assertEquals(bytes.take(2).toVector, Vector(0xfe.toByte, 0xff.toByte))
        new String(bytes.drop(2), StandardCharsets.UTF_16BE)
      case None => fail("tracked text did not emit an ActualText marked-content span")

  test("tracked Unicode text remains embedded, subsetted, and carries the logical ActualText") {
    val label = "A Δe\u0301"
    val output = render(label)
    assertEquals(output.profile.textRuns, 1)
    assertEquals(output.profile.embeddedSubsetFonts, 1)
    withDocument(output) { document =>
      val resources = document.getPage(0).getResources
      val embedded =
        resources.getFont(resources.getFontNames.asScala.head).asInstanceOf[PDType0Font]
      assert(embedded.isEmbedded)
      assertEquals(actualText(document), label)
      assertEquals(new PDFTextStripper().getText(document).trim, label)
    }
  }

  test("mixed bidi tracking keeps logical ActualText and visible glyphs") {
    val label = "A Δאe\u0301"
    val output = render(label)
    withDocument(output) { document =>
      assertEquals(actualText(document), label)
      val image = new PDFRenderer(document).renderImageWithDPI(0, 144.0f, ImageType.RGB)
      assert(
        (0 until image.getWidth).exists(x =>
          (0 until image.getHeight).exists(y => (image.getRGB(x, y) & 0x00ffffff) != 0x00ffffff)
        ),
        "mixed-bidi tracked run did not render visible ink"
      )
    }
  }

  test("tracked text rejects a missing visible glyph before PDF emission") {
    val scene = Scene(
      Vector(
        Grob.textUnsafe(
          "🫈",
          Point.npcUnsafe(0.5, 0.5),
          gp = GraphicParams
            .unsafe(fontFamily = Some(font.family))
            .withLetterSpacing(LetterSpacing.pointsUnsafe(1.0))
        )
      )
    )
    val context = RenderContext.unsafe(fontRegistry = catalog.fontRegistry)
    assertEquals(
      PdfRenderer.render(RenderPlan(scene, context), catalog).left.toOption,
      Some(PdfRenderError.UnsupportedGlyph("Liberation Sans", 0x1fac8))
    )
  }

  test("tracked Arabic and mixed bidi text retain logical ActualText and paint visible glyphs") {
    Vector("السلام", "ABC السلام 123").foreach { label =>
      val scene = Scene(
        Vector(
          Grob.textUnsafe(
            label,
            Point.npcUnsafe(0.5, 0.5),
            gp = GraphicParams
              .unsafe(fontFamily = Some(arabicFont.family), fontSize = Length.pointsUnsafe(18.0))
              .withLetterSpacing(LetterSpacing.pointsUnsafe(2.0))
          )
        )
      )
      val context = RenderContext.unsafe(
        width = 300,
        height = 120,
        pixelsPerInch = 144.0,
        fontRegistry = arabicCatalog.fontRegistry
      )
      val output = PdfRenderer
        .render(RenderPlan(scene, context), arabicCatalog)
        .fold(error => fail(error.message), identity)
      withDocument(output) { document =>
        assertEquals(actualText(document), label)
        val image = new PDFRenderer(document).renderImageWithDPI(0, 144.0f, ImageType.RGB)
        val nonWhite = (0 until image.getWidth).exists(x =>
          (0 until image.getHeight).exists(y => (image.getRGB(x, y) & 0x00ffffff) != 0x00ffffff)
        )
        assert(nonWhite, s"tracked Arabic run '$label' did not render visible ink")
      }
    }
  }

  test("catalog metrics use native PDF widths at zero spacing and tracked advances otherwise") {
    val size = 18.0
    val label = "AV"
    val native = catalog.textMetrics.widthPt(label, TextStyle(Some(font.family), size))
    val document = new PDDocument()
    val pdfInput = new java.io.ByteArrayInputStream(fontBytes())
    val expectedNative =
      try
        val embedded = PDType0Font.load(document, pdfInput, true)
        embedded.getStringWidth(label).toDouble / 1000.0 * size
      finally
        pdfInput.close()
        document.close()
    assertEqualsDouble(native, expectedNative, 1e-8)
    val input = new java.io.ByteArrayInputStream(fontBytes())
    val awt = try Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(size.toFloat)
    finally input.close()
    val context = new FontRenderContext(null, true, true)
    val tracked = TrackedGlyphs.layout(label, awt, context, 1.25)
    assert(native > 0.0)
    assertEqualsDouble(
      catalog.textMetrics
        .widthPt(label, TextStyle(Some(font.family), size, letterSpacingPt = 1.25)),
      tracked.advance,
      1e-8
    )
  }

/** Writes the same mixed-bidi PDF used by the extraction regression so external readers can be
  * checked without reimplementing a renderer fixture. Invoke with one output path.
  */
object PdfTrackingFixture:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: PdfTrackingFixture <output.pdf>")
    val resource = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    val input = Option(getClass.getResourceAsStream(resource)).getOrElse(
      throw new IllegalStateException(s"missing $resource")
    )
    val bytes = try input.readAllBytes()
    finally input.close()
    val font = PdfFont
      .fromBytes("Liberation Sans", bytes)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val catalog = PdfFontCatalog.single(font)
    val label = "A Δאe\u0301"
    val scene = Scene(
      Vector(
        Grob.textUnsafe(
          label,
          Point.npcUnsafe(0.5, 0.5),
          gp = GraphicParams
            .unsafe(fontFamily = Some(font.family), fontSize = Length.pointsUnsafe(14.0))
            .withLetterSpacing(LetterSpacing.devicePixelsUnsafe(3.0))
        )
      )
    )
    val context = RenderContext.unsafe(
      width = 240,
      height = 120,
      pixelsPerInch = 144.0,
      fontRegistry = catalog.fontRegistry
    )
    val output = PdfRenderer
      .render(RenderPlan(scene, context), catalog)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    java.nio.file.Files.write(java.nio.file.Path.of(args(0)), output.bytes)
