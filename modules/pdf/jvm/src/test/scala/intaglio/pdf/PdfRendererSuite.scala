package intaglio.pdf

import scala.jdk.CollectionConverters.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import intaglio.*

class PdfRendererSuite extends munit.FunSuite:
  private def bundledFontBytes(): Array[Byte] =
    val path = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    val input = Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing $path"))
    try input.readAllBytes()
    finally input.close()

  private lazy val liberationSans: PdfFont =
    PdfFont
      .fromBytes("Liberation Sans", bundledFontBytes())
      .fold(error => fail(error.message), identity)

  private lazy val fonts = PdfFontCatalog.single(liberationSans)

  private def load(document: PdfDocument)(body: PDDocument => Unit): Unit =
    val parsed = Loader.loadPDF(document.bytes)
    try body(parsed)
    finally parsed.close()

  private def render(
      scene: Scene,
      context: RenderContext,
      catalog: PdfFontCatalog = PdfFontCatalog.empty,
      options: PdfOptions = PdfOptions.default
  ): PdfDocument =
    PdfRenderer
      .render(RenderPlan(scene, context), catalog, options)
      .fold(error => fail(error.message), identity)

  test("page MediaBox is the exact physical size represented by the render context") {
    val context = RenderContext.hidpiUnsafe(
      logicalWidth = 600,
      logicalHeight = 400,
      devicePixelRatio = 2.0,
      logicalPixelsPerInch = 144.0
    )
    val output = render(Scene(Vector.empty), context)

    assertEquals(output.widthPoints, 300.0)
    assertEquals(output.heightPoints, 200.0)
    assertEquals(output.bytes.take(5).map(_.toChar).mkString, "%PDF-")
    assertEquals(output.rasterPolicy, PdfRasterPolicy.ExplicitImagesOnly)
    assertEquals(
      output.profile,
      PdfRenderProfile(0, 0, 0, 0, 0, 0)
    )
    load(output) { parsed =>
      assertEquals(parsed.getNumberOfPages, 1)
      val box = parsed.getPage(0).getMediaBox
      assertEqualsDouble(box.getWidth.toDouble, 300.0, 1.0e-5)
      assertEqualsDouble(box.getHeight.toDouble, 200.0, 1.0e-5)
    }
  }

  test("text uses a supplied embedded subset font and remains extractable") {
    val label = "Intaglio Δ"
    val scene = Scene(
      Vector(
        Grob.textUnsafe(
          label,
          Point.npcUnsafe(0.5, 0.5),
          anchor = Anchor(HJust.Center, VJust.Center),
          rotationDegrees = 17.0,
          gp = GraphicParams.unsafe(
            fill = Some(Rgba.unsafe(20, 40, 80, 0.8)),
            fontSize = Length.pointsUnsafe(13.0),
            fontFamily = Some("Liberation Sans")
          )
        )
      )
    )
    val context = RenderContext.unsafe(
      width = 240,
      height = 160,
      pixelsPerInch = 120.0,
      fontRegistry = fonts.fontRegistry
    )
    val output = render(scene, context, fonts, PdfOptions(title = Some("Font assurance")))

    assertEquals(output.profile.textRuns, 1)
    assertEquals(output.profile.embeddedSubsetFonts, 1)
    load(output) { parsed =>
      assertEquals(parsed.getDocumentInformation.getTitle, "Font assurance")
      val resources = parsed.getPage(0).getResources
      val names = resources.getFontNames.asScala.toVector
      assertEquals(names.length, 1)
      val embedded = resources.getFont(names.head).asInstanceOf[PDType0Font]
      assert(embedded.isEmbedded)
      assert(embedded.getName.matches("[A-Z]{6}\\+LiberationSans"), embedded.getName)
      assert(embedded.getFontDescriptor.getFontFile2 != null)
      assertEquals(new PDFTextStripper().getText(parsed).trim, label)
    }
  }

  test("repeated export is byte-identical") {
    val scene = Scene(
      Vector(
        Grob.textUnsafe(
          "deterministic",
          Point.npcUnsafe(0.5, 0.5),
          gp = GraphicParams.unsafe(fontFamily = Some(liberationSans.family))
        ),
        Grob.circleUnsafe(Point.npcUnsafe(0.25, 0.25), ExtentExpr.pointsUnsafe(5.0))
      )
    )
    val context = RenderContext.unsafe(
      width = 160,
      height = 100,
      fontRegistry = fonts.fontRegistry
    )
    val first = render(scene, context, fonts)
    val second = render(scene, context, fonts)
    val firstBytes = first.bytes
    val secondBytes = second.bytes
    val firstDifference = firstBytes.indices.find(index => firstBytes(index) != secondBytes(index))

    assertEquals(firstBytes.length, secondBytes.length)
    assertEquals(
      firstDifference,
      None,
      clues(
        firstBytes.length,
        firstDifference.map(index => firstBytes.slice(index - 8, index + 24).toVector),
        firstDifference.map(index => secondBytes.slice(index - 8, index + 24).toVector)
      )
    )
  }

  test("missing, malformed, and glyph-incomplete fonts fail closed") {
    val text = Scene(Vector(Grob.textUnsafe("text", Point.npcUnsafe(0.5, 0.5))))
    val missing = PdfRenderer.render(RenderPlan(text, RenderContext.default))
    assertEquals(missing.left.toOption, Some(PdfRenderError.MissingFont(None)))

    val malformed = PdfFont.fromBytes("Broken", Array[Byte](1, 2, 3)).toOption.get
    val malformedCatalog = PdfFontCatalog.single(malformed)
    val malformedContext = RenderContext.unsafe(fontRegistry = malformedCatalog.fontRegistry)
    assert(
      PdfRenderer
        .render(RenderPlan(text, malformedContext), malformedCatalog)
        .left
        .toOption
        .exists(_.isInstanceOf[PdfRenderError.FontLoadFailed])
    )

    val emoji = Scene(Vector(Grob.textUnsafe("🫈", Point.npcUnsafe(0.5, 0.5))))
    val emojiContext = RenderContext.unsafe(fontRegistry = fonts.fontRegistry)
    assertEquals(
      PdfRenderer.render(RenderPlan(emoji, emojiContext), fonts).left.toOption,
      Some(PdfRenderError.UnsupportedGlyph("Liberation Sans", 0x1fac8))
    )
  }

  test("vector marks and patterns stay vector; only explicit images create raster payloads") {
    val recipe = PatternRecipe
      .crossHatch(angleDegrees = 35.0, spacing = 12.0, lineWidth = 1.5)
      .fold(error => fail(error.message), identity)
    val patterned = GraphicParams
      .unsafe(stroke = Some(Rgba.unsafe(10, 20, 30)), fill = None, lineWidth = 2.0)
      .withPatternFill(
        PatternPaint(
          recipe,
          Rgba.unsafe(20, 80, 140, 0.7),
          Some(Rgba.unsafe(240, 245, 250))
        )
      )
    val raster = RasterImage.unsafePacked(
      RasterDimensions.unsafe(2, 1),
      Vector(Rgba32.unsafe(255, 0, 0, 128), Rgba32.unsafe(0, 0, 255))
    )
    val scene = Scene(
      Vector(
        Grob.rectUnsafe(
          Point.npcUnsafe(0.3, 0.6),
          Size.npcUnsafe(0.4, 0.5),
          gp = patterned
        ),
        Grob.circleUnsafe(
          Point.npcUnsafe(0.72, 0.68),
          ExtentExpr.pointsUnsafe(12.0),
          gp = GraphicParams.unsafe(
            stroke = Some(Rgba.unsafe(90, 30, 20)),
            fill = Some(Rgba.unsafe(240, 180, 80))
          )
        ),
        Grob.imageUnsafe(
          raster,
          Point.npcUnsafe(0.7, 0.25),
          Size.npcUnsafe(0.3, 0.25),
          interpolation = RasterInterpolation.Smooth,
          alpha = 0.75
        )
      )
    )
    val output = render(scene, RenderContext.unsafe(width = 240, height = 160))

    assertEquals(output.profile.vectorShapes, 2)
    assertEquals(output.profile.vectorPatterns, 1)
    assertEquals(output.profile.rasterImagePlacements, 1)
    assertEquals(output.profile.rasterPayloads, 1)
    load(output) { parsed =>
      val page = parsed.getPage(0)
      val resources = page.getResources
      val imageNames = resources.getXObjectNames.asScala.toVector
      assertEquals(imageNames.length, 1)
      val image = resources.getXObject(imageNames.head).asInstanceOf[PDImageXObject]
      assert(image.getInterpolate)

      val patternNames = resources.getPatternNames.asScala.toVector
      assertEquals(patternNames.length, 1)
      val pattern = resources.getPattern(patternNames.head).asInstanceOf[PDTilingPattern]
      assertEquals(pattern.getResources.getXObjectNames.asScala.toVector, Vector.empty)
      val patternOperators = operators(pattern)
      assert(patternOperators.contains("m"))
      assert(patternOperators.contains("l"))
      assert(!patternOperators.contains("Do"))

      val pageOperators = operators(page)
      assert(pageOperators.contains("re"), pageOperators)
      assert(pageOperators.contains("c"), pageOperators)
      assertEquals(pageOperators.count(_ == "Do"), 1)
    }
  }

  test("rendered PDF preserves raster top-row orientation and nearest-neighbor pixels") {
    val raster = RasterImage.unsafePacked(
      RasterDimensions.unsafe(2, 2),
      Vector(
        Rgba32.unsafe(220, 20, 30),
        Rgba32.unsafe(30, 180, 60),
        Rgba32.unsafe(40, 70, 210),
        Rgba32.unsafe(240, 190, 20)
      )
    )
    val scene = Scene(
      Vector(
        Grob.imageUnsafe(
          raster,
          Point.npcUnsafe(0.5, 0.5),
          Size.npcUnsafe(1.0, 1.0),
          interpolation = RasterInterpolation.Nearest
        )
      )
    )
    val output = render(
      scene,
      RenderContext.unsafe(width = 100, height = 100, pixelsPerInch = 72.0)
    )

    load(output) { parsed =>
      val image = new PDFRenderer(parsed).renderImageWithDPI(0, 72.0f)
      assertEquals(image.getWidth, 100)
      assertEquals(image.getHeight, 100)
      assertEquals(image.getRGB(25, 25) & 0xffffff, 0xdc141e)
      assertEquals(image.getRGB(75, 25) & 0xffffff, 0x1eb43c)
      assertEquals(image.getRGB(25, 75) & 0xffffff, 0x2846d2)
      assertEquals(image.getRGB(75, 75) & 0xffffff, 0xf0be14)
    }
  }

  test("vector tiling patterns paint visible repeated ink") {
    val recipe = PatternRecipe
      .parallelRules(RuleOrientation.Vertical, spacing = 10.0, lineWidth = 2.0)
      .fold(error => fail(error.message), identity)
    val gp = GraphicParams
      .unsafe(stroke = None, fill = None)
      .withPatternFill(PatternPaint(recipe, Rgba.Black, Some(Rgba.White)))
    val scene = Scene(
      Vector(
        Grob.rectUnsafe(
          Point.npcUnsafe(0.5, 0.5),
          Size.npcUnsafe(1.0, 1.0),
          gp = gp
        )
      )
    )
    val output = render(
      scene,
      RenderContext.unsafe(width = 100, height = 100, pixelsPerInch = 72.0)
    )

    load(output) { parsed =>
      val image = new PDFRenderer(parsed).renderImageWithDPI(0, 72.0f)
      var dark = 0
      var light = 0
      var row = 0
      while row < image.getHeight do
        var column = 0
        while column < image.getWidth do
          val rgb = image.getRGB(column, row) & 0xffffff
          if rgb < 0x404040 then dark += 1
          if rgb > 0xf0f0f0 then light += 1
          column += 1
        row += 1
      assert(dark > 500, clues(dark, light))
      assert(light > 5000, clues(dark, light))
    }
  }

  test("every renderer-conformance scene encodes as a parseable PDF") {
    val cases = RendererConformance.cases.fold(error => fail(error.message), identity)
    val context = RenderContext.unsafe(
      width = 240,
      height = 160,
      fontRegistry = FontRegistry(_ => Some(liberationSans.family))
    )
    cases.foreach { sceneCase =>
      val output = PdfRenderer
        .render(RenderPlan(sceneCase.scene, context), fonts)
        .fold(error => fail(s"${sceneCase.name}: ${error.message}"), identity)
      load(output)(parsed => assertEquals(parsed.getNumberOfPages, 1, sceneCase.name))
    }
  }

  test("font inputs are immutable and catalogs reject duplicate families") {
    val bytes = bundledFontBytes()
    val first = PdfFont.fromBytes("Example", bytes).toOption.get
    java.util.Arrays.fill(bytes, 0.toByte)
    val copiedCatalog = PdfFontCatalog.single(first)
    val text = Scene(Vector(Grob.textUnsafe("copied", Point.npcUnsafe(0.5, 0.5))))
    val context = RenderContext.unsafe(fontRegistry = copiedCatalog.fontRegistry)
    val copied = PdfRenderer.render(RenderPlan(text, context), copiedCatalog)
    assert(copied.isRight, copied.left.map(_.message))

    val second = PdfFont.fromBytes(" example ", Array[Byte](1)).toOption.get
    assertEquals(
      PdfFontCatalog.from(first, second).left.toOption,
      Some(PdfRenderError.DuplicateFontFamily("example"))
    )
    assertEquals(
      PdfFont.fromBytes("  ", Array[Byte](1)).left.toOption,
      Some(PdfRenderError.BlankFontFamily)
    )
    assertEquals(
      PdfFont.fromBytes("Empty", Array.emptyByteArray).left.toOption,
      Some(PdfRenderError.EmptyFontData("Empty"))
    )
  }

  private def operators(content: org.apache.pdfbox.contentstream.PDContentStream): Vector[String] =
    val parser = new PDFStreamParser(content)
    try
      parser.parse().asScala.collect { case operator: Operator => operator.getName }.toVector
    finally parser.close()
