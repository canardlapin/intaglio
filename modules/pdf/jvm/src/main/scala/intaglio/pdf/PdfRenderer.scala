package intaglio.pdf

import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import scala.collection.mutable
import scala.util.control.NonFatal
import org.apache.pdfbox.pdmodel.{
  PDDocument,
  PDPage,
  PDPageContentStream,
  PDPatternContentStream,
  PDResources
}
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.cos.{COSArray, COSString}
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.color.{PDColor, PDPattern}
import org.apache.pdfbox.pdmodel.graphics.image.{LosslessFactory, PDImageXObject}
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.util.Matrix
import intaglio.*

/** Direct one-page PDF renderer.
  *
  * Device pixels are converted to PDF points exactly once with `72 / pixelsPerInch`. Geometry,
  * clipping, text, and fill patterns are emitted as PDF operators. Explicit raster grobs are the
  * only image XObjects, and every text font is supplied by [[PdfFontCatalog]], embedded, and subset
  * by PDFBox.
  */
object PdfRenderer:
  private val PointsPerInch = 72.0
  private val MaxPagePoints = 14400.0

  def render(
      plan: RenderPlan,
      fonts: PdfFontCatalog = PdfFontCatalog.empty,
      options: PdfOptions = PdfOptions.default
  ): Either[PdfRenderError, PdfDocument] =
    for
      scene <- plan.deviceScene.left.map(PdfRenderError.Graphics(_))
      page <- pageContract(plan.context)
      required <- requiredFonts(scene, fonts)
      document <- encode(scene, page, required, fonts, options)
    yield document

  private final case class PageContract(
      widthPoints: Double,
      heightPoints: Double,
      pointsPerPixel: Double
  )

  private def pageContract(context: RenderContext): Either[PdfRenderError, PageContract] =
    val pointsPerPixel = PointsPerInch / context.pixelsPerInch
    val width = context.width.toDouble * pointsPerPixel
    val height = context.height.toDouble * pointsPerPixel
    if !width.isFinite || !height.isFinite || width <= 0.0 || height <= 0.0 ||
      width > MaxPagePoints || height > MaxPagePoints
    then Left(PdfRenderError.InvalidPageSize(width, height))
    else Right(PageContract(width, height, pointsPerPixel))

  private def requiredFonts(
      scene: DeviceScene,
      catalog: PdfFontCatalog
  ): Either[PdfRenderError, Vector[PdfFont]] =
    val requested = Vector.newBuilder[Option[String]]

    def visit(elements: Vector[DeviceElement]): Unit =
      elements.foreach {
        case DeviceElement.Group(_, _, _, children) => visit(children)
        case DeviceElement.Annotated(_, children)   => visit(children)
        case DeviceElement.Mark(DevicePrimitive.TextRun(_, _, _, _, _, _, _, family, _, _)) =>
          requested += family
        case DeviceElement.Mark(_) => ()
      }

    visit(scene.elements)
    val values = requested.result()
    val out = Vector.newBuilder[PdfFont]
    var seen = Set.empty[String]
    var index = 0
    var failure: Option[PdfRenderError] = None
    while index < values.length && failure.isEmpty do
      val family = values(index)
      catalog.resolve(family) match
        case None       => failure = Some(PdfRenderError.MissingFont(family))
        case Some(font) =>
          val key = PdfFontCatalog.normalize(font.family)
          if !seen.contains(key) then
            seen += key
            out += font
      index += 1
    failure.fold[Either[PdfRenderError, Vector[PdfFont]]](Right(out.result()))(Left(_))

  private def encode(
      scene: DeviceScene,
      pageContract: PageContract,
      requiredFonts: Vector[PdfFont],
      catalog: PdfFontCatalog,
      options: PdfOptions
  ): Either[PdfRenderError, PdfDocument] =
    val document = new PDDocument()
    val output = new ByteArrayOutputStream()
    try
      val page = new PDPage(
        new PDRectangle(pageContract.widthPoints.toFloat, pageContract.heightPoints.toFloat)
      )
      page.setResources(new PDResources())
      document.addPage(page)
      configureMetadata(document, scene, options)
      val loaded = loadFonts(document, requiredFonts)
      val content = new PDPageContentStream(document, page)
      val encoder = new PageEncoder(
        document,
        page,
        content,
        scene.height,
        pageContract,
        catalog,
        loaded
      )
      try encoder.draw(scene.elements)
      finally content.close()
      setDocumentId(document, Array.fill(16)(0.toByte))
      document.save(output)
      val documentId = MessageDigest.getInstance("SHA-256").digest(output.toByteArray).take(16)
      output.reset()
      setDocumentId(document, documentId)
      document.save(output)
      Right(
        new PdfDocument(
          output.toByteArray,
          pageContract.widthPoints,
          pageContract.heightPoints,
          PdfRasterPolicy.ExplicitImagesOnly,
          encoder.profile(requiredFonts.size),
          encoder.trace
        )
      )
    catch
      case abort: RenderAbort => Left(abort.error)
      case NonFatal(error)    => Left(PdfRenderError.PdfEncodingFailed(PdfMessages.details(error)))
    finally
      try document.close()
      catch case NonFatal(_) => ()
      output.close()

  /** PDFBox otherwise creates a time-dependent trailer ID. A placeholder save materializes font
    * subsets and object streams; hashing those bytes gives the final file a content-derived ID and
    * keeps repeated exports byte-identical.
    */
  private def setDocumentId(document: PDDocument, id: Array[Byte]): Unit =
    val values = new COSArray()
    values.add(new COSString(id))
    values.add(new COSString(id))
    document.getDocument.setDocumentID(values)

  private def configureMetadata(
      document: PDDocument,
      scene: DeviceScene,
      options: PdfOptions
  ): Unit =
    val information = document.getDocumentInformation
    options.title.orElse(scene.semantics.accessibleTitle).foreach(information.setTitle)
    options.author.foreach(information.setAuthor)
    options.subject.orElse(scene.semantics.accessibleDescription).foreach(information.setSubject)
    information.setCreator("Intaglio")
    information.setProducer("Intaglio PDF renderer")

  private def loadFonts(
      document: PDDocument,
      fonts: Vector[PdfFont]
  ): Map[String, PDType0Font] =
    fonts.iterator.map { supplied =>
      val input = supplied.inputStream
      val loaded =
        try PDType0Font.load(document, input, true)
        catch
          case NonFatal(error) =>
            abort(PdfRenderError.FontLoadFailed(supplied.family, PdfMessages.details(error)))
        finally input.close()
      if !loaded.isEmbedded || !loaded.willBeSubset then
        abort(
          PdfRenderError.FontLoadFailed(
            supplied.family,
            "the font did not enter embedded subset mode"
          )
        )
      PdfFontCatalog.normalize(supplied.family) -> loaded
    }.toMap

  private final case class RenderAbort(error: PdfRenderError)
      extends RuntimeException(error.message, null, false, false)

  private def abort(error: PdfRenderError): Nothing =
    throw RenderAbort(error)

  private final class PageEncoder(
      document: PDDocument,
      page: PDPage,
      stream: PDPageContentStream,
      deviceHeight: Double,
      contract: PageContract,
      catalog: PdfFontCatalog,
      fonts: Map[String, PDType0Font]
  ):
    private val patterns = mutable.LinkedHashMap.empty[PatternPaint, PDColor]
    private val images =
      mutable.LinkedHashMap.empty[(RasterImage, RasterInterpolation), PDImageXObject]
    private val alphaStates = mutable.HashMap.empty[(Float, Float), PDExtendedGraphicsState]
    private val emittedMarkers = Vector.newBuilder[GraphicsName]
    private val emittedRequirements = Vector.newBuilder[RenderRequirement]
    private var vectorShapes = 0
    private var textRuns = 0
    private var rasterPlacements = 0

    private inline def px(value: Double): Float =
      (value * contract.pointsPerPixel).toFloat

    private inline def x(value: Double): Float =
      px(value)

    private inline def y(value: Double): Float =
      (contract.heightPoints - value * contract.pointsPerPixel).toFloat

    def draw(elements: Vector[DeviceElement]): Unit =
      elements.foreach(drawElement)

    def profile(fontCount: Int): PdfRenderProfile =
      PdfRenderProfile(
        vectorShapes = vectorShapes,
        textRuns = textRuns,
        rasterImagePlacements = rasterPlacements,
        rasterPayloads = images.size,
        vectorPatterns = patterns.size,
        embeddedSubsetFonts = fontCount
      )

    def trace: PdfRenderTrace =
      PdfRenderTrace(emittedMarkers.result(), emittedRequirements.result())

    private def drawElement(element: DeviceElement): Unit =
      element match
        case DeviceElement.Mark(primitive)                       => drawPrimitive(primitive)
        case DeviceElement.Annotated(_, children)                => children.foreach(drawElement)
        case DeviceElement.Group(name, clip, rotation, children) =>
          stream.saveGraphicsState()
          try
            rotation.foreach { value =>
              stream.transform(
                Matrix.getRotateInstance(
                  -math.toRadians(value.degrees),
                  x(value.pivotX),
                  y(value.pivotY)
                )
              )
            }
            clip.foreach { value =>
              stream.addRect(
                x(value.x),
                y(value.y + value.height),
                px(value.width),
                px(value.height)
              )
              stream.clip()
            }
            children.foreach(drawElement)
          finally stream.restoreGraphicsState()
          name.foreach { value =>
            emittedMarkers += value
            emittedRequirements += RenderRequirement.Group(
              value,
              clipped = clip.nonEmpty,
              rotated = rotation.nonEmpty
            )
          }

    private def drawPrimitive(primitive: DevicePrimitive): Unit =
      primitive match
        case DevicePrimitive.Disc(centerX, centerY, radius, gp, name) =>
          if hasPaint(gp, allowFill = true) then
            withGraphics {
              appendCircle(stream, x(centerX), y(centerY), px(radius))
              paint(gp, allowFill = true)
            }
            vectorShapes += 1
            recordStyledPrimitive(name, RenderPrimitiveKind.Disc, gp)
        case DevicePrimitive.PointBatch(points, radii, shapes, params, name) =>
          var index = 0
          while index < points.length do
            val shape = shapes.valueAt(index)
            val gp = params.valueAt(index)
            if drawPoint(
                points(index),
                radii.valueAt(index),
                shape,
                gp
              )
            then recordStyledPrimitive(name, pointKind(shape), gp)
            index += 1
        case DevicePrimitive.Polyline(points, closed, gp, name) =>
          if hasPaint(gp, allowFill = closed) then
            withGraphics {
              appendPolyline(points, closed)
              paint(gp, allowFill = closed)
            }
            vectorShapes += 1
            val kind = if closed then RenderPrimitiveKind.Polygon else RenderPrimitiveKind.Polyline
            recordStyledPrimitive(name, kind, gp)
        case DevicePrimitive.CompoundPolygon(rings, gp, name) =>
          if hasPaint(gp, allowFill = true) then
            withGraphics {
              rings.foreach(appendPolyline(_, closed = true))
              paint(gp, allowFill = true)
            }
            vectorShapes += 1
            recordStyledPrimitive(name, RenderPrimitiveKind.Polygon, gp)
        case DevicePrimitive.RectShape(rectX, rectY, width, height, gp, name) =>
          if hasPaint(gp, allowFill = true) then
            withGraphics {
              stream.addRect(x(rectX), y(rectY + height), px(width), px(height))
              paint(gp, allowFill = true)
            }
            vectorShapes += 1
            recordStyledPrimitive(name, RenderPrimitiveKind.Rectangle, gp)
        case DevicePrimitive.TextRun(
              label,
              textX,
              textY,
              horizontal,
              vertical,
              rotation,
              fontSize,
              family,
              gp,
              name
            ) =>
          drawText(label, textX, textY, horizontal, vertical, rotation, fontSize, family, gp)
          textRuns += 1
          name.foreach { value =>
            emittedMarkers += value
            emittedRequirements += RenderRequirement.Primitive(value, RenderPrimitiveKind.Text)
            emittedRequirements += RenderRequirement.Text(
              value,
              horizontal,
              vertical,
              rotated = rotation != 0.0
            )
            emittedRequirements += RenderRequirement.TextStyle(
              value,
              gp.fill.orElse(gp.stroke).getOrElse(Rgba.Black),
              fontSize,
              family,
              gp.alpha
            )
          }
        case DevicePrimitive.Image(
              image,
              imageX,
              imageY,
              width,
              height,
              interpolation,
              alpha,
              name
            ) =>
          drawImage(image, imageX, imageY, width, height, interpolation, alpha)
          rasterPlacements += 1
          name.foreach { value =>
            emittedMarkers += value
            emittedRequirements += RenderRequirement.Primitive(value, RenderPrimitiveKind.Image)
            emittedRequirements += RenderRequirement.Image(
              value,
              image.dimensions,
              interpolation,
              alpha
            )
          }

    private def drawPoint(
        point: DevicePoint,
        radius: Double,
        shape: PointShape,
        gp: GraphicParams
    ): Boolean =
      val allowFill = shape != PointShape.Cross
      if hasPaint(gp, allowFill) then
        withGraphics {
          shape match
            case PointShape.Circle =>
              appendCircle(stream, x(point.x), y(point.y), px(radius))
            case PointShape.Square =>
              stream.addRect(
                x(point.x - radius),
                y(point.y + radius),
                px(radius * 2.0),
                px(radius * 2.0)
              )
            case PointShape.Triangle =>
              stream.moveTo(x(point.x), y(point.y - radius))
              stream.lineTo(x(point.x + radius), y(point.y + radius))
              stream.lineTo(x(point.x - radius), y(point.y + radius))
              stream.closePath()
            case PointShape.Cross =>
              stream.moveTo(x(point.x - radius), y(point.y))
              stream.lineTo(x(point.x + radius), y(point.y))
              stream.moveTo(x(point.x), y(point.y - radius))
              stream.lineTo(x(point.x), y(point.y + radius))
            case PointShape.Diamond =>
              val half = PointShape.diamondHalfDiagonal(radius)
              stream.moveTo(x(point.x), y(point.y - half))
              stream.lineTo(x(point.x + half), y(point.y))
              stream.lineTo(x(point.x), y(point.y + half))
              stream.lineTo(x(point.x - half), y(point.y))
              stream.closePath()
          paint(gp, allowFill)
        }
        vectorShapes += 1
        true
      else false

    private def pointKind(shape: PointShape): RenderPrimitiveKind =
      shape match
        case PointShape.Circle   => RenderPrimitiveKind.Disc
        case PointShape.Square   => RenderPrimitiveKind.Rectangle
        case PointShape.Triangle => RenderPrimitiveKind.Polygon
        case PointShape.Cross    => RenderPrimitiveKind.Polyline
        case PointShape.Diamond  => RenderPrimitiveKind.Polygon

    private def recordStyledPrimitive(
        name: Option[GraphicsName],
        kind: RenderPrimitiveKind,
        gp: GraphicParams
    ): Unit =
      name.foreach { value =>
        emittedMarkers += value
        emittedRequirements += RenderRequirement.Primitive(value, kind)
        emittedRequirements += RenderRequirement.Style(
          value,
          gp.stroke,
          gp.fill,
          gp.lineWidth,
          gp.lineType,
          gp.lineCap,
          gp.lineJoin,
          gp.alpha
        )
        gp.fillPattern.foreach(pattern =>
          emittedRequirements += RenderRequirement.PatternFill(value, pattern, gp.alpha)
        )
      }

    private def appendPolyline(points: Vector[DevicePoint], closed: Boolean): Unit =
      val first = points.head
      stream.moveTo(x(first.x), y(first.y))
      points.tail.foreach(point => stream.lineTo(x(point.x), y(point.y)))
      if closed then stream.closePath()

    private def hasPaint(gp: GraphicParams, allowFill: Boolean): Boolean =
      gp.stroke.nonEmpty || (allowFill && (gp.fillPattern.nonEmpty || gp.fill.nonEmpty))

    private def paint(gp: GraphicParams, allowFill: Boolean): Unit =
      val fillPattern = Option.when(allowFill)(gp.fillPattern).flatten
      val solidFill = Option.when(allowFill && fillPattern.isEmpty)(gp.fill).flatten
      val hasFill = fillPattern.nonEmpty || solidFill.nonEmpty
      val hasStroke = gp.stroke.nonEmpty

      fillPattern match
        case Some(pattern) => stream.setNonStrokingColor(patternColor(pattern))
        case None          => solidFill.foreach(setNonStrokingColor)
      gp.stroke.foreach(setStrokingColor)
      configureStroke(gp)

      val fillAlpha = fillPattern.fold(solidFill.fold(1.0)(_.alpha))(_ => 1.0) * gp.alpha
      val strokeAlpha = gp.stroke.fold(1.0)(_.alpha) * gp.alpha
      setAlpha(strokeAlpha, fillAlpha)

      if hasFill && hasStroke then stream.fillAndStroke()
      else if hasFill then stream.fill()
      else stream.stroke()

    private def configureStroke(gp: GraphicParams): Unit =
      stream.setLineWidth(px(gp.lineWidth))
      stream.setLineCapStyle(
        gp.lineCap match
          case LineCap.Butt   => 0
          case LineCap.Round  => 1
          case LineCap.Square => 2
      )
      stream.setLineJoinStyle(
        gp.lineJoin match
          case LineJoin.Miter => 0
          case LineJoin.Round => 1
          case LineJoin.Bevel => 2
      )
      gp.lineType match
        case LineType.Solid  => stream.setLineDashPattern(Array.emptyFloatArray, 0.0f)
        case LineType.Dashed => stream.setLineDashPattern(Array(px(6.0), px(4.0)), 0.0f)
        case LineType.Dotted => stream.setLineDashPattern(Array(px(1.0), px(3.0)), 0.0f)

    private def drawText(
        label: String,
        textX: Double,
        textY: Double,
        horizontal: HJust,
        vertical: VJust,
        rotationDegrees: Double,
        fontSizePx: Double,
        family: Option[String],
        gp: GraphicParams
    ): Unit =
      val supplied = catalog.resolve(family).getOrElse(abort(PdfRenderError.MissingFont(family)))
      val font = fonts(PdfFontCatalog.normalize(supplied.family))
      validateGlyphs(label, supplied.family, font)
      val size = px(fontSizePx)
      val width = font.getStringWidth(label) / 1000.0f * size
      val descriptor = font.getFontDescriptor
      val ascent = descriptor.getAscent / 1000.0f * size
      val descent = descriptor.getDescent / 1000.0f * size
      val offsetX = horizontal match
        case HJust.Left   => 0.0f
        case HJust.Center => -width / 2.0f
        case HJust.Right  => -width
      val offsetY = vertical match
        case VJust.Top    => -ascent
        case VJust.Center => -(ascent + descent) / 2.0f
        case VJust.Bottom => -descent
      val radians = -math.toRadians(rotationDegrees)
      val cosine = math.cos(radians).toFloat
      val sine = math.sin(radians).toFloat
      val anchorX = x(textX)
      val anchorY = y(textY)
      val baselineX = anchorX + cosine * offsetX - sine * offsetY
      val baselineY = anchorY + sine * offsetX + cosine * offsetY
      val color = gp.fill.orElse(gp.stroke).getOrElse(Rgba.Black)

      withGraphics {
        setNonStrokingColor(color)
        setAlpha(1.0, color.alpha * gp.alpha)
        stream.beginText()
        stream.setFont(font, size)
        stream.setTextMatrix(new Matrix(cosine, sine, -sine, cosine, baselineX, baselineY))
        stream.showText(label)
        stream.endText()
      }

    private def validateGlyphs(label: String, family: String, font: PDType0Font): Unit =
      var offset = 0
      while offset < label.length do
        val codePoint = label.codePointAt(offset)
        if !font.hasGlyph(codePoint) then abort(PdfRenderError.UnsupportedGlyph(family, codePoint))
        offset += Character.charCount(codePoint)

    private def drawImage(
        image: RasterImage,
        imageX: Double,
        imageY: Double,
        width: Double,
        height: Double,
        interpolation: RasterInterpolation,
        alpha: Double
    ): Unit =
      val resource = images.getOrElseUpdate(
        image -> interpolation, {
          val value = LosslessFactory.createFromImage(document, buffered(image))
          value.setInterpolate(interpolation == RasterInterpolation.Smooth)
          value
        }
      )
      withGraphics {
        setAlpha(alpha, alpha)
        stream.drawImage(resource, x(imageX), y(imageY + height), px(width), px(height))
      }

    private def patternColor(paint: PatternPaint): PDColor =
      patterns.getOrElseUpdate(paint, createPattern(paint))

    private def createPattern(paint: PatternPaint): PDColor =
      val spacing = px(paint.recipe.spacing)
      val pattern = new PDTilingPattern()
      pattern.setPaintType(PDTilingPattern.PAINT_COLORED)
      pattern.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING)
      pattern.setBBox(new PDRectangle(0.0f, 0.0f, spacing, spacing))
      pattern.setXStep(spacing)
      pattern.setYStep(spacing)
      pattern.setResources(new PDResources())

      val transform = new AffineTransform()
      transform.translate(0.0, contract.heightPoints)
      transform.scale(1.0, -1.0)
      paint.recipe match
        case value: PatternRecipe.AngledHatch =>
          transform.rotate(math.toRadians(value.angleDegrees))
        case value: PatternRecipe.CrossHatch =>
          transform.rotate(math.toRadians(value.angleDegrees))
        case _: PatternRecipe.ParallelRules | _: PatternRecipe.Stipple => ()
      pattern.setMatrix(transform)

      val patternStream = new PDPatternContentStream(pattern)
      try
        paint.background.foreach { background =>
          setPatternColor(patternStream, background, stroking = false)
          setPatternAlpha(patternStream, background.alpha, background.alpha)
          patternStream.addRect(0.0f, 0.0f, spacing, spacing)
          patternStream.fill()
        }
        paint.recipe match
          case value: PatternRecipe.AngledHatch =>
            patternLine(patternStream, paint.ink, px(value.lineWidth), vertical = true, spacing)
          case value: PatternRecipe.CrossHatch =>
            patternLine(patternStream, paint.ink, px(value.lineWidth), vertical = true, spacing)
            patternLine(patternStream, paint.ink, px(value.lineWidth), vertical = false, spacing)
          case value: PatternRecipe.ParallelRules =>
            patternLine(
              patternStream,
              paint.ink,
              px(value.lineWidth),
              vertical = value.orientation == RuleOrientation.Vertical,
              spacing
            )
          case value: PatternRecipe.Stipple =>
            setPatternColor(patternStream, paint.ink, stroking = false)
            setPatternAlpha(patternStream, paint.ink.alpha, paint.ink.alpha)
            appendCircle(patternStream, spacing / 2.0f, spacing / 2.0f, px(value.radius))
            patternStream.fill()
      finally patternStream.close()

      val name = page.getResources.add(pattern)
      new PDColor(name, new PDPattern(page.getResources))

    private def patternLine(
        target: PDPatternContentStream,
        ink: Rgba,
        width: Float,
        vertical: Boolean,
        spacing: Float
    ): Unit =
      setPatternColor(target, ink, stroking = true)
      setPatternAlpha(target, ink.alpha, ink.alpha)
      target.setLineWidth(width)
      target.setLineCapStyle(0)
      target.moveTo(0.0f, 0.0f)
      if vertical then target.lineTo(0.0f, spacing)
      else target.lineTo(spacing, 0.0f)
      target.stroke()

    private def setPatternColor(
        target: PDPatternContentStream,
        color: Rgba,
        stroking: Boolean
    ): Unit =
      val red = color.red.toFloat / 255.0f
      val green = color.green.toFloat / 255.0f
      val blue = color.blue.toFloat / 255.0f
      if stroking then target.setStrokingColor(red, green, blue)
      else target.setNonStrokingColor(red, green, blue)

    private def setPatternAlpha(
        target: PDPatternContentStream,
        strokeAlpha: Double,
        fillAlpha: Double
    ): Unit =
      val state = new PDExtendedGraphicsState()
      state.setStrokingAlphaConstant(strokeAlpha.toFloat)
      state.setNonStrokingAlphaConstant(fillAlpha.toFloat)
      target.setGraphicsStateParameters(state)

    private def setStrokingColor(color: Rgba): Unit =
      stream.setStrokingColor(
        color.red.toFloat / 255.0f,
        color.green.toFloat / 255.0f,
        color.blue.toFloat / 255.0f
      )

    private def setNonStrokingColor(color: Rgba): Unit =
      stream.setNonStrokingColor(
        color.red.toFloat / 255.0f,
        color.green.toFloat / 255.0f,
        color.blue.toFloat / 255.0f
      )

    private def setAlpha(strokeAlpha: Double, fillAlpha: Double): Unit =
      val key = strokeAlpha.toFloat -> fillAlpha.toFloat
      val state = alphaStates.getOrElseUpdate(
        key, {
          val value = new PDExtendedGraphicsState()
          value.setStrokingAlphaConstant(key._1)
          value.setNonStrokingAlphaConstant(key._2)
          value
        }
      )
      stream.setGraphicsStateParameters(state)

    private def buffered(image: RasterImage): BufferedImage =
      val output = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
      val pixels = new Array[Int](image.dimensions.pixelCount)
      var index = 0
      while index < pixels.length do
        val pixel = image.packedAt(index)
        pixels(index) = (pixel.alpha << 24) | (pixel.red << 16) | (pixel.green << 8) | pixel.blue
        index += 1
      output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
      output

    private def withGraphics(body: => Unit): Unit =
      stream.saveGraphicsState()
      try body
      finally stream.restoreGraphicsState()

  private def appendCircle(
      stream: PDPageContentStream,
      centerX: Float,
      centerY: Float,
      radius: Float
  ): Unit =
    val control = radius * 0.55228475f
    stream.moveTo(centerX + radius, centerY)
    stream.curveTo(
      centerX + radius,
      centerY + control,
      centerX + control,
      centerY + radius,
      centerX,
      centerY + radius
    )
    stream.curveTo(
      centerX - control,
      centerY + radius,
      centerX - radius,
      centerY + control,
      centerX - radius,
      centerY
    )
    stream.curveTo(
      centerX - radius,
      centerY - control,
      centerX - control,
      centerY - radius,
      centerX,
      centerY - radius
    )
    stream.curveTo(
      centerX + control,
      centerY - radius,
      centerX + radius,
      centerY - control,
      centerX + radius,
      centerY
    )
    stream.closePath()

  private def appendCircle(
      stream: PDPatternContentStream,
      centerX: Float,
      centerY: Float,
      radius: Float
  ): Unit =
    val control = radius * 0.55228475f
    stream.moveTo(centerX + radius, centerY)
    stream.curveTo(
      centerX + radius,
      centerY + control,
      centerX + control,
      centerY + radius,
      centerX,
      centerY + radius
    )
    stream.curveTo(
      centerX - control,
      centerY + radius,
      centerX - radius,
      centerY + control,
      centerX - radius,
      centerY
    )
    stream.curveTo(
      centerX - radius,
      centerY - control,
      centerX - control,
      centerY - radius,
      centerX,
      centerY - radius
    )
    stream.curveTo(
      centerX + control,
      centerY - radius,
      centerX + radius,
      centerY - control,
      centerX + radius,
      centerY
    )
    stream.closePath()
