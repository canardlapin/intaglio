package intaglio.svg

import intaglio.*

final case class SvgOptions private (
    width: Int,
    height: Int,
    title: Option[String],
    pixelsPerInch: Double,
    deviceScale: Double,
    description: Option[String]
):
  def logicalWidth: Double = width.toDouble / deviceScale
  def logicalHeight: Double = height.toDouble / deviceScale

object SvgOptions:
  val default: SvgOptions =
    unsafe()

  def apply(
      width: Int = 640,
      height: Int = 480,
      title: Option[String] = None,
      pixelsPerInch: Double = 96.0,
      deviceScale: Double = 1.0,
      description: Option[String] = None
  ): Either[SvgRenderError, SvgOptions] =
    if width <= 0 || height <= 0 then Left(SvgRenderError.InvalidDocumentSize(width, height))
    else
      RenderContext(width, height, pixelsPerInch, deviceScale = deviceScale).left
        .map(SvgRenderError.Graphics(_))
        .map(_ => new SvgOptions(width, height, title, pixelsPerInch, deviceScale, description))

  def unsafe(
      width: Int = 640,
      height: Int = 480,
      title: Option[String] = None,
      pixelsPerInch: Double = 96.0,
      deviceScale: Double = 1.0,
      description: Option[String] = None
  ): SvgOptions =
    apply(width, height, title, pixelsPerInch, deviceScale, description).orThrow

final case class SvgDocument(
    value: String,
    width: Int = 640,
    height: Int = 480,
    pixelsPerInch: Double = 96.0,
    deviceScale: Double = 1.0,
    logicalWidth: Double = 640.0,
    logicalHeight: Double = 480.0
):

  override def toString: String =
    value

/** Serializes a resolved [[intaglio.DeviceScene]] to SVG text. All unit and orientation semantics
  * are handled by the shared device lowering; this backend only formats numeric device primitives.
  */
object SvgRenderer:
  def render(plan: RenderPlan): Either[SvgRenderError, SvgDocument] =
    render(plan, None)

  def render(plan: RenderPlan, title: Option[String]): Either[SvgRenderError, SvgDocument] =
    for
      options <- SvgOptions(
        plan.context.width,
        plan.context.height,
        title,
        plan.context.pixelsPerInch,
        plan.context.deviceScale
      )
      deviceScene <- plan.deviceScene.left.map(SvgRenderError.Graphics(_))
      serialized <- serialize(deviceScene, options)
    yield SvgDocument(
      serialized,
      plan.context.width,
      plan.context.height,
      plan.context.pixelsPerInch,
      plan.context.deviceScale,
      plan.context.logicalWidth,
      plan.context.logicalHeight
    )

  def render(
      scene: Scene,
      options: SvgOptions = SvgOptions.default
  ): Either[SvgRenderError, SvgDocument] =
    for
      context <- RenderContext(
        options.width,
        options.height,
        options.pixelsPerInch,
        deviceScale = options.deviceScale
      ).left
        .map(SvgRenderError.Graphics(_))
      deviceScene <- DeviceScene.fromScene(scene, context).left.map(SvgRenderError.Graphics(_))
      serialized <- serialize(deviceScene, options)
    yield SvgDocument(
      serialized,
      options.width,
      options.height,
      options.pixelsPerInch,
      options.deviceScale,
      options.logicalWidth,
      options.logicalHeight
    )

  private final class ClipRegistry:
    private val builder = Vector.newBuilder[DeviceClip]
    private var count = 0

    def register(clip: DeviceClip): String =
      val id = s"clip-$count"
      builder += clip
      count += 1
      id

    def defs: Vector[(String, DeviceClip)] =
      builder.result().zipWithIndex.map { case (clip, idx) => (s"clip-$idx", clip) }

  private final class PatternRegistry:
    private var paints = Vector.empty[PatternPaint]

    def register(paint: PatternPaint): String =
      val existing = paints.indexOf(paint)
      if existing >= 0 then s"pattern-$existing"
      else
        val id = s"pattern-${paints.length}"
        paints = paints :+ paint
        id

    def defs: Vector[(String, PatternPaint)] =
      paints.zipWithIndex.map { case (paint, idx) => (s"pattern-$idx", paint) }

  private final case class DocumentAccessibility(
      id: String,
      title: Option[String],
      description: Option[String]
  )

  private def serialize(scene: DeviceScene, options: SvgOptions): Either[SvgRenderError, String] =
    validateDocument(scene, options).map(_ => serializeValidated(scene, options))

  private def serializeValidated(scene: DeviceScene, options: SvgOptions): String =
    val out = new StringBuilder
    val clips = new ClipRegistry
    val patterns = new PatternRegistry
    val accessibility = documentAccessibility(scene, options)
    val accessibilityAttrs = accessibility.fold("") { metadata =>
      val labelledBy = metadata.title.fold("")(_ => s" aria-labelledby=\"${metadata.id}-title\"")
      val describedBy =
        metadata.description.fold("")(_ => s" aria-describedby=\"${metadata.id}-description\"")
      s" id=\"${metadata.id}\" role=\"img\"$labelledBy$describedBy"
    }
    line(
      out,
      0,
      s"""<svg xmlns="http://www.w3.org/2000/svg" width="${options.width}" height="${options.height}" viewBox="0 0 ${options.width} ${options.height}"$accessibilityAttrs>"""
    )
    accessibility match
      case Some(metadata) =>
        metadata.title.foreach(title =>
          line(out, 1, s"<title id=\"${metadata.id}-title\">${escapeText(title)}</title>")
        )
        metadata.description.foreach(description =>
          line(
            out,
            1,
            s"<desc id=\"${metadata.id}-description\">${escapeText(description)}</desc>"
          )
        )
      case None =>
        options.title.foreach(title => line(out, 1, s"<title>${escapeText(title)}</title>"))
    scene.elements.foreach(writeElement(_, out, 1, clips, patterns))
    val clipDefs = clips.defs
    val patternDefs = patterns.defs
    if clipDefs.nonEmpty || patternDefs.nonEmpty then
      line(out, 1, "<defs>")
      clipDefs.foreach { case (id, clip) =>
        line(out, 2, s"""<clipPath id="$id">""")
        line(
          out,
          3,
          s"""<rect x="${format(clip.x)}" y="${format(clip.y)}" width="${format(
              clip.width
            )}" height="${format(clip.height)}" />"""
        )
        line(out, 2, "</clipPath>")
      }
      patternDefs.foreach { case (id, paint) =>
        writePatternDefinition(id, paint, out, 2)
      }
      line(out, 1, "</defs>")
    line(out, 0, "</svg>")
    out.result()

  private def validateDocument(
      scene: DeviceScene,
      options: SvgOptions
  ): Either[SvgRenderError, Unit] =
    val accessibility = documentAccessibility(scene, options)
    val title = accessibility.flatMap(_.title).orElse(options.title) match
      case Some(value) => validateXml("document title", value)
      case None        => Right(())
    val description = accessibility.flatMap(_.description) match
      case Some(value) => validateXml("document description", value)
      case None        => Right(())
    title.flatMap(_ => description).flatMap(_ => validateElements(scene.elements))

  private def documentAccessibility(
      scene: DeviceScene,
      options: SvgOptions
  ): Option[DocumentAccessibility] =
    Option.when(!scene.semantics.isEmpty || options.description.nonEmpty) {
      DocumentAccessibility(
        scene.semantics.documentId.map(_.value).getOrElse("intaglio-svg"),
        options.title.orElse(scene.semantics.accessibleTitle),
        options.description.orElse(scene.semantics.accessibleDescription)
      )
    }

  private def validateElements(elements: Vector[DeviceElement]): Either[SvgRenderError, Unit] =
    var idx = 0
    var result: Either[SvgRenderError, Unit] = Right(())
    while idx < elements.length && result.isRight do
      result = validateElement(elements(idx))
      idx += 1
    result

  private def validateElement(element: DeviceElement): Either[SvgRenderError, Unit] =
    element match
      case DeviceElement.Mark(primitive) =>
        validatePrimitive(primitive)
      case DeviceElement.Group(name, _, _, children) =>
        validateName(name).flatMap(_ => validateElements(children))

  private def validatePrimitive(primitive: DevicePrimitive): Either[SvgRenderError, Unit] =
    primitive match
      case DevicePrimitive.Disc(_, _, _, _, name) =>
        validateName(name)
      case DevicePrimitive.PointBatch(_, _, _, _, name) =>
        validateName(name)
      case DevicePrimitive.Polyline(_, _, _, name) =>
        validateName(name)
      case DevicePrimitive.CompoundPolygon(_, _, name) =>
        validateName(name)
      case DevicePrimitive.RectShape(_, _, _, _, _, name) =>
        validateName(name)
      case DevicePrimitive.TextRun(label, _, _, _, _, _, _, fontFamily, _, name) =>
        val family = fontFamily match
          case Some(value) => validateXml("font family", value)
          case None        => Right(())
        validateName(name)
          .flatMap(_ => family)
          .flatMap(_ => validateXml("text label", label))
      case DevicePrimitive.Image(_, _, _, _, _, _, _, name) =>
        validateName(name)

  private def validateName(name: Option[GraphicsName]): Either[SvgRenderError, Unit] =
    name match
      case Some(value) => validateXml("data name", value.value)
      case None        => Right(())

  private def validateXml(field: String, value: String): Either[SvgRenderError, Unit] =
    firstInvalidXmlCodePoint(value) match
      case Some(codePoint) => Left(SvgRenderError.InvalidXmlCharacter(field, codePoint))
      case None            => Right(())

  private def firstInvalidXmlCodePoint(value: String): Option[Int] =
    var index = 0
    var invalid: Option[Int] = None
    while index < value.length && invalid.isEmpty do
      val first = value.charAt(index)
      val paired =
        java.lang.Character.isHighSurrogate(first) &&
          index + 1 < value.length &&
          java.lang.Character.isLowSurrogate(value.charAt(index + 1))
      val codePoint =
        if paired then java.lang.Character.toCodePoint(first, value.charAt(index + 1))
        else first.toInt
      if !isXmlCodePoint(codePoint) then invalid = Some(codePoint)
      index += (if paired then 2 else 1)
    invalid

  private def isXmlCodePoint(value: Int): Boolean =
    value == 0x9 || value == 0xa || value == 0xd ||
      (value >= 0x20 && value <= 0xd7ff) ||
      (value >= 0xe000 && value <= 0xfffd) ||
      (value >= 0x10000 && value <= 0x10ffff)

  private def writeElement(
      element: DeviceElement,
      out: StringBuilder,
      indent: Int,
      clips: ClipRegistry,
      patterns: PatternRegistry
  ): Unit =
    element match
      case DeviceElement.Mark(primitive) =>
        writePrimitive(primitive, out, indent, patterns)
      case DeviceElement.Group(name, clip, rotation, children) =>
        val nameAttr = name.map(n => s""" data-name="${escapeAttr(n.value)}"""").getOrElse("")
        val clipAttr = clip.map(c => s""" clip-path="url(#${clips.register(c)})"""").getOrElse("")
        val rotateAttr = rotation
          .map(r =>
            s""" transform="rotate(${format(r.degrees)} ${format(r.pivotX)} ${format(r.pivotY)})""""
          )
          .getOrElse("")
        line(out, indent, s"<g$nameAttr$clipAttr$rotateAttr>")
        children.foreach(writeElement(_, out, indent + 1, clips, patterns))
        line(out, indent, "</g>")

  private def writePrimitive(
      primitive: DevicePrimitive,
      out: StringBuilder,
      indent: Int,
      patterns: PatternRegistry
  ): Unit =
    primitive match
      case DevicePrimitive.Disc(cx, cy, radius, gp, name) =>
        line(
          out,
          indent,
          s"""<circle${commonAttrs(name, gp, patterns)} cx="${format(cx)}" cy="${format(
              cy
            )}" r="${format(radius)}" />"""
        )
      case DevicePrimitive.PointBatch(points, radii, shapes, params, name) =>
        var index = 0
        while index < points.length do
          writePointMark(
            points(index),
            radii.valueAt(index),
            shapes.valueAt(index),
            params.valueAt(index),
            name,
            out,
            indent,
            patterns
          )
          index += 1
      case DevicePrimitive.Polyline(points, closed, gp, name) =>
        val coords = points.map(p => s"${format(p.x)},${format(p.y)}").mkString(" ")
        if closed then
          line(out, indent, s"""<polygon${commonAttrs(name, gp, patterns)} points="$coords" />""")
        else line(out, indent, s"""<polyline${lineAttrs(name, gp)} points="$coords" />""")
      case DevicePrimitive.CompoundPolygon(rings, gp, name) =>
        val path = rings
          .map { ring =>
            val start = ring.head
            val rest =
              ring.tail.map(point => s"L ${format(point.x)} ${format(point.y)}").mkString(" ")
            s"M ${format(start.x)} ${format(start.y)} $rest Z"
          }
          .mkString(" ")
        line(
          out,
          indent,
          s"""<path${commonAttrs(name, gp, patterns)} fill-rule="nonzero" d="$path" />"""
        )
      case DevicePrimitive.RectShape(x, y, width, height, gp, name) =>
        line(
          out,
          indent,
          s"""<rect${commonAttrs(name, gp, patterns)} x="${format(x)}" y="${format(
              y
            )}" width="${format(width)}" height="${format(height)}" />"""
        )
      case DevicePrimitive.TextRun(
            label,
            x,
            y,
            horizontal,
            vertical,
            rotationDegrees,
            fontSizePx,
            fontFamily,
            gp,
            name
          ) =>
        val rotation =
          if rotationDegrees == 0.0 then ""
          else s""" transform="rotate(${format(rotationDegrees)} ${format(x)} ${format(y)})""""
        line(
          out,
          indent,
          s"""<text${textAttrs(name, gp, fontSizePx, fontFamily)} x="${format(x)}" y="${format(
              y
            )}" text-anchor="${textAnchor(horizontal)}" dominant-baseline="${dominantBaseline(
              vertical
            )}"$rotation>${escapeText(label)}</text>"""
        )
      case DevicePrimitive.Image(image, x, y, width, height, interpolation, alpha, name) =>
        val nameAttr = name.map(n => s""" data-name="${escapeAttr(n.value)}"""").getOrElse("")
        val opacityAttr = if alpha == 1.0 then "" else s""" opacity="${format(alpha)}""""
        val rendering = interpolation match
          case RasterInterpolation.Nearest => "pixelated"
          case RasterInterpolation.Smooth  => "auto"
        line(
          out,
          indent,
          s"""<image$nameAttr data-pixel-width="${image.width}" data-pixel-height="${image.height}" x="${format(
              x
            )}" y="${format(y)}" width="${format(width)}" height="${format(
              height
            )}" preserveAspectRatio="none" image-rendering="$rendering"$opacityAttr href="${PngEncoder
              .dataUri(image)}" />"""
        )

  private def writePointMark(
      point: DevicePoint,
      radius: Double,
      shape: PointShape,
      gp: GraphicParams,
      name: Option[GraphicsName],
      out: StringBuilder,
      indent: Int,
      patterns: PatternRegistry
  ): Unit =
    shape match
      case PointShape.Circle =>
        line(
          out,
          indent,
          s"""<circle${commonAttrs(name, gp, patterns)} cx="${format(point.x)}" cy="${format(
              point.y
            )}" r="${format(radius)}" />"""
        )
      case PointShape.Square =>
        line(
          out,
          indent,
          s"""<rect${commonAttrs(name, gp, patterns)} x="${format(point.x - radius)}" y="${format(
              point.y - radius
            )}" width="${format(radius * 2.0)}" height="${format(radius * 2.0)}" />"""
        )
      case PointShape.Triangle =>
        val coords =
          s"${format(point.x)},${format(point.y - radius)} ${format(point.x + radius)},${format(
              point.y + radius
            )} ${format(point.x - radius)},${format(point.y + radius)}"
        line(out, indent, s"""<polygon${commonAttrs(name, gp, patterns)} points="$coords" />""")
      case PointShape.Cross =>
        line(
          out,
          indent,
          s"""<polyline${lineAttrs(name, gp)} points="${format(point.x - radius)},${format(
              point.y
            )} ${format(point.x + radius)},${format(point.y)}" />"""
        )
        line(
          out,
          indent,
          s"""<polyline${lineAttrs(name, gp)} points="${format(point.x)},${format(
              point.y - radius
            )} ${format(point.x)},${format(point.y + radius)}" />"""
        )

  private def commonAttrs(
      name: Option[GraphicsName],
      gp: GraphicParams,
      patterns: PatternRegistry
  ): String =
    val attrs = new StringBuilder
    name.foreach(n => attrs.append(s""" data-name="${escapeAttr(n.value)}""""))
    appendPaint(attrs, "stroke", gp.stroke)
    gp.fillPattern match
      case Some(pattern) => attrs.append(s""" fill="url(#${patterns.register(pattern)})"""")
      case None          => appendPaint(attrs, "fill", gp.fill)
    attrs.append(s""" stroke-width="${format(gp.lineWidth)}"""")
    attrs.append(s""" stroke-linecap="${lineCap(gp.lineCap)}"""")
    attrs.append(s""" stroke-linejoin="${lineJoin(gp.lineJoin)}"""")
    lineTypeAttr(gp.lineType).foreach(attrs.append)
    if gp.alpha != 1.0 then attrs.append(s""" opacity="${format(gp.alpha)}"""")
    attrs.result()

  private def writePatternDefinition(
      id: String,
      paint: PatternPaint,
      out: StringBuilder,
      indent: Int
  ): Unit =
    val transform = paint.recipe match
      case recipe: PatternRecipe.AngledHatch =>
        s""" patternTransform="rotate(${format(recipe.angleDegrees)})""""
      case recipe: PatternRecipe.CrossHatch =>
        s""" patternTransform="rotate(${format(recipe.angleDegrees)})""""
      case _: PatternRecipe.ParallelRules => ""
      case _: PatternRecipe.Stipple       => ""
    val spacing = paint.recipe.spacing
    line(
      out,
      indent,
      s"""<pattern id="$id" x="0" y="0" width="${format(spacing)}" height="${format(
          spacing
        )}" patternUnits="userSpaceOnUse"$transform>"""
    )
    paint.background.foreach { color =>
      val attrs = new StringBuilder
      appendPaint(attrs, "fill", Some(color))
      line(
        out,
        indent + 1,
        s"""<rect x="0" y="0" width="${format(spacing)}" height="${format(spacing)}"${attrs
            .result()} />"""
      )
    }
    paint.recipe match
      case recipe: PatternRecipe.AngledHatch =>
        writePatternLine(paint.ink, recipe.lineWidth, true, spacing, out, indent + 1)
      case recipe: PatternRecipe.CrossHatch =>
        writePatternLine(paint.ink, recipe.lineWidth, true, spacing, out, indent + 1)
        writePatternLine(paint.ink, recipe.lineWidth, false, spacing, out, indent + 1)
      case recipe: PatternRecipe.ParallelRules =>
        writePatternLine(
          paint.ink,
          recipe.lineWidth,
          recipe.orientation == RuleOrientation.Vertical,
          spacing,
          out,
          indent + 1
        )
      case recipe: PatternRecipe.Stipple =>
        val attrs = new StringBuilder
        appendPaint(attrs, "fill", Some(paint.ink))
        line(
          out,
          indent + 1,
          s"""<circle cx="${format(spacing / 2.0)}" cy="${format(spacing / 2.0)}" r="${format(
              recipe.radius
            )}"${attrs.result()} />"""
        )
    line(out, indent, "</pattern>")

  private def writePatternLine(
      ink: Rgba,
      width: Double,
      vertical: Boolean,
      spacing: Double,
      out: StringBuilder,
      indent: Int
  ): Unit =
    val attrs = new StringBuilder
    appendPaint(attrs, "stroke", Some(ink))
    val coordinates =
      if vertical then s"""x1="0" y1="0" x2="0" y2="${format(spacing)}""""
      else s"""x1="0" y1="0" x2="${format(spacing)}" y2="0""""
    line(out, indent, s"""<line $coordinates${attrs.result()} stroke-width="${format(width)}" />""")

  private def lineAttrs(name: Option[GraphicsName], gp: GraphicParams): String =
    val attrs = new StringBuilder
    name.foreach(n => attrs.append(s""" data-name="${escapeAttr(n.value)}""""))
    appendPaint(attrs, "stroke", gp.stroke)
    attrs.append(""" fill="none"""")
    attrs.append(s""" stroke-width="${format(gp.lineWidth)}"""")
    attrs.append(s""" stroke-linecap="${lineCap(gp.lineCap)}"""")
    attrs.append(s""" stroke-linejoin="${lineJoin(gp.lineJoin)}"""")
    lineTypeAttr(gp.lineType).foreach(attrs.append)
    if gp.alpha != 1.0 then attrs.append(s""" opacity="${format(gp.alpha)}"""")
    attrs.result()

  private def textAttrs(
      name: Option[GraphicsName],
      gp: GraphicParams,
      fontSizePx: Double,
      fontFamily: Option[String]
  ): String =
    val attrs = new StringBuilder
    name.foreach(n => attrs.append(s""" data-name="${escapeAttr(n.value)}""""))
    appendPaint(attrs, "fill", gp.fill.orElse(gp.stroke).orElse(Some(Rgba.Black)))
    attrs.append(""" stroke="none"""")
    fontFamily.foreach(family => attrs.append(s""" font-family="${escapeAttr(family)}""""))
    attrs.append(s""" font-size="${format(fontSizePx)}"""")
    if gp.alpha != 1.0 then attrs.append(s""" opacity="${format(gp.alpha)}"""")
    attrs.result()

  private def appendPaint(out: StringBuilder, attr: String, color: Option[Rgba]): Unit =
    color match
      case Some(rgba) =>
        out.append(s""" $attr="${hex(rgba)}"""")
        if rgba.alpha != 1.0 then out.append(s""" $attr-opacity="${format(rgba.alpha)}"""")
      case None =>
        out.append(s""" $attr="none"""")

  private def lineTypeAttr(lineType: LineType): Option[String] =
    lineType match
      case LineType.Solid  => None
      case LineType.Dashed => Some(""" stroke-dasharray="6 4"""")
      case LineType.Dotted => Some(""" stroke-dasharray="1 3"""")

  private def lineCap(value: LineCap): String =
    value match
      case LineCap.Butt   => "butt"
      case LineCap.Round  => "round"
      case LineCap.Square => "square"

  private def lineJoin(value: LineJoin): String =
    value match
      case LineJoin.Miter => "miter"
      case LineJoin.Round => "round"
      case LineJoin.Bevel => "bevel"

  private def textAnchor(just: HJust): String =
    just match
      case HJust.Left   => "start"
      case HJust.Center => "middle"
      case HJust.Right  => "end"

  private def dominantBaseline(just: VJust): String =
    just match
      case VJust.Bottom => "text-after-edge"
      case VJust.Center => "middle"
      case VJust.Top    => "text-before-edge"

  private def hex(color: Rgba): String =
    def channel(value: Int): String =
      val s = value.toHexString
      if s.length == 1 then "0" + s else s
    "#" + channel(color.red) + channel(color.green) + channel(color.blue)

  private def line(out: StringBuilder, indent: Int, value: String): Unit =
    out.append("  " * indent).append(value).append("\n")

  /** Fixed-point formatting (up to 4 decimals, no exponent) so output is byte-identical across JVM
    * and JS double-to-string behavior.
    */
  private def format(value: Double): String =
    val scaled = math.rint(math.abs(value) * 10000.0).toLong
    val sign = if value < 0.0 && scaled != 0L then "-" else ""
    val whole = scaled / 10000L
    var frac = (scaled % 10000L).toInt
    if frac == 0 then s"$sign$whole"
    else
      var digits = 4
      while frac % 10 == 0 do
        frac /= 10
        digits -= 1
      val text = frac.toString
      val padded = "0" * (digits - text.length) + text
      s"$sign$whole.$padded"

  private def escapeText(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")

  private def escapeAttr(value: String): String =
    escapeText(value).replace("\"", "&quot;")
