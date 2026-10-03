package intaglio.pdf

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.rendering.{ImageType, PDFRenderer}
import intaglio.*

/** An independent ink-versus-plate court for PDF text plates.
  *
  * Each case is exported twice: once with the text and no plate, once with the plate and fully
  * transparent text. PDFBox rasterises both, and every pixel the text inks must be painted by the
  * plate. The plate's own measurement plays no part in the oracle.
  */
class PdfTextPlateInkSuite extends munit.FunSuite:
  private val plateColor = Rgba.unsafe(0, 160, 0)

  private def bundledFontBytes(): Array[Byte] =
    val path = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    val input = Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing $path"))
    try input.readAllBytes()
    finally input.close()

  private def face(family: String, bytes: Array[Byte]): PdfFont =
    PdfFont.fromBytes(family, bytes).fold(error => fail(error.message), identity)

  /** A serif italic installed on the host, if any: the audit's face first, then DejaVu. */
  private lazy val systemItalic: Option[Path] =
    Vector(
      "/System/Library/Fonts/Supplemental/Georgia Italic.ttf",
      "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Italic.ttf",
      "/usr/share/fonts/dejavu/DejaVuSerif-Italic.ttf",
      "/usr/share/fonts/TTF/DejaVuSerif-Italic.ttf"
    ).map(Paths.get(_)).find(Files.isRegularFile(_))

  /** PDFBox fills an axis-aligned rectangle without antialiasing (pixel centres), but glyphs with
    * it, so a square plate edge can leave an antialiased fringe pixel of contained ink unpainted. A
    * corner radius of a thousandth of a pixel makes the plate a curved path that PDFBox fills
    * antialiased like the glyphs, at a cost of less than a millionth of a pixel at each corner.
    * That lets the court demand exact containment with no padding at all.
    */
  private val OracleCornerRadius = StrokeWidth.devicePixelsUnsafe(0.001)

  private final case class Court(inkPixels: Int, outside: Int)

  private final case class Case(
      label: String,
      anchor: Anchor,
      rotation: Double,
      family: Option[String],
      sizePoints: Double,
      paddingPixels: Double
  )

  private def raster(
      value: Case,
      catalog: PdfFontCatalog,
      pixelsPerInch: Double,
      plateOnly: Boolean
  ): BufferedImage =
    val parsed = Loader.loadPDF(pdf(value, catalog, pixelsPerInch, plateOnly, plate = plateOnly))
    try new PDFRenderer(parsed).renderImageWithDPI(0, pixelsPerInch.toFloat, ImageType.ARGB)
    finally parsed.close()

  private def pdf(
      value: Case,
      catalog: PdfFontCatalog,
      pixelsPerInch: Double,
      transparentText: Boolean,
      plate: Boolean
  ): Array[Byte] =
    val base = GraphicParams.unsafe(
      stroke = None,
      fill = Some(if transparentText then Rgba.unsafe(0, 0, 0, 0) else Rgba.Black),
      fontFamily = value.family,
      fontSize = Length.pointsUnsafe(value.sizePoints)
    )
    val gp =
      if plate then
        base.withTextPlate(
          TextPlate(
            plateColor,
            padding = StrokeWidth.devicePixelsUnsafe(value.paddingPixels),
            cornerRadius = OracleCornerRadius
          )
        )
      else base
    val text = Grob.textUnsafe(
      value.label,
      Point.npcUnsafe(0.5, 0.5),
      value.anchor,
      rotationDegrees = value.rotation,
      gp = gp
    )
    val context = RenderContext.unsafe(
      width = 480,
      height = 480,
      pixelsPerInch = pixelsPerInch,
      fontRegistry = catalog.fontRegistry
    )
    PdfRenderer
      .render(RenderPlan(Scene(Vector(text)), context), catalog)
      .fold(error => fail(error.message, clues(value)), _.bytes)

  private def court(value: Case, catalog: PdfFontCatalog, pixelsPerInch: Double): Court =
    val ink = raster(value, catalog, pixelsPerInch, plateOnly = false)
    val plate = raster(value, catalog, pixelsPerInch, plateOnly = true)
    var inked = 0
    var outside = 0
    var y = 0
    while y < ink.getHeight do
      var x = 0
      while x < ink.getWidth do
        if (ink.getRGB(x, y) >>> 24) > 0 then
          inked += 1
          if (plate.getRGB(x, y) >>> 24) == 0 then outside += 1
        x += 1
      y += 1
    Court(inked, outside)

  private val labels = Vector("j", "fj", "Á", "\u0301j", "Plate Wg")
  private val anchors = for h <- HJust.values.toVector; v <- VJust.values.toVector
  yield Anchor(h, v)
  private val rotations = Vector(0.0, 33.0, 90.0)

  private def sweep(catalog: PdfFontCatalog, family: Option[String], padding: Double) =
    for
      label <- labels
      anchor <- anchors
      rotation <- rotations
    yield
      val value = Case(label, anchor, rotation, family, 72.0, padding)
      value -> court(value, catalog, 72.0)

  test("the plate holds every inked pixel of the embedded face, overhangs and marks included") {
    // Liberation Sans j reaches left of the pen, and a combining acute has no advance but draws
    // left of it: a plate of the advance box alone leaves their ink outside.
    val catalog = PdfFontCatalog.single(face("Liberation Sans", bundledFontBytes()))
    // Both the named family and the catalog default resolve the face the PDF draws.
    for family <- Vector(Some("Liberation Sans"), None) do
      val results = sweep(catalog, family, padding = 0.0)
      val failures = results.filter(_._2.outside > 0)
      assert(results.forall(_._2.inkPixels > 0), results.filter(_._2.inkPixels == 0))
      assertEquals(
        failures.map((value, result) =>
          s"${value.label} ${value.anchor} ${value.rotation}: ${result.outside}"
        ),
        Vector.empty,
        clues(family, failures.map(_._2.outside).sum)
      )
  }

  test("a serif italic face installed on the host is contained, as in the audit") {
    val path = systemItalic.getOrElse {
      assume(false, "no Georgia Italic or DejaVu Serif Italic on this host")
      fail("unreachable")
    }
    val catalog = PdfFontCatalog.single(face("Host Italic", Files.readAllBytes(path)))
    // The audit's case: j at 96 pt with 4 px of padding, measured in 96 ppi device pixels.
    val audit = court(Case("j", Anchor.Center, 0.0, None, 96.0, 4.0), catalog, 96.0)
    val results = sweep(catalog, Some("Host Italic"), padding = 0.0)
    val failures = results.filter(_._2.outside > 0)
    assert(audit.inkPixels > 0)
    assertEquals(
      audit.outside,
      0,
      clues(path, audit, failures.size, failures.map(_._2.outside).sum)
    )
    assertEquals(
      failures.map((value, result) =>
        s"${value.label} ${value.anchor} ${value.rotation}: ${result.outside}"
      ),
      Vector.empty,
      clue(path)
    )
  }

  test("a plate does not move the glyphs: the text matrix is the one drawn without a plate") {
    val catalog = PdfFontCatalog.single(face("Liberation Sans", bundledFontBytes()))
    def textMatrix(value: Case, plate: Boolean): Vector[Double] =
      val parsed = Loader.loadPDF(pdf(value, catalog, 72.0, transparentText = false, plate))
      try
        val parser = new PDFStreamParser(parsed.getPage(0))
        val tokens = parser.parse().asScala.toVector
        val tm = tokens.indexWhere {
          case op: Operator => op.getName == "Tm"
          case _            => false
        }
        assert(tm >= 6)
        tokens.slice(tm - 6, tm).map {
          case number: COSNumber => number.floatValue.toDouble
          case other             => fail(s"Tm operand $other")
        }
      finally parsed.close()
    for
      label <- Vector("j", "Á")
      anchor <- anchors
      rotation <- Vector(0.0, 33.0)
    do
      val value = Case(label, anchor, rotation, None, 72.0, 4.0)
      assertEquals(textMatrix(value, plate = true), textMatrix(value, plate = false), clue(value))
  }

  test("a family the catalog does not hold fails closed rather than measuring another face") {
    val catalog = PdfFontCatalog.single(face("Liberation Sans", bundledFontBytes()))
    val gp = GraphicParams
      .unsafe(stroke = None, fill = Some(Rgba.Black), fontFamily = Some("Intaglio No Such Face"))
      .withTextPlate(TextPlate(plateColor))
    val context = RenderContext.unsafe(fontRegistry = catalog.fontRegistry)
    val scene = Scene(Vector(Grob.textUnsafe("j", Point.npcUnsafe(0.5, 0.5), gp = gp)))
    assertEquals(
      PdfRenderer.render(RenderPlan(scene, context), catalog).left.toOption,
      Some(PdfRenderError.MissingFont(Some("Intaglio No Such Face")))
    )
  }
