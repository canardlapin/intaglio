package intaglio.svg

import intaglio.*

final case class SvgOptions private (width: Int, height: Int, title: Option[String])

object SvgOptions:
  val default: SvgOptions =
    unsafe()

  def apply(width: Int = 640, height: Int = 480, title: Option[String] = None): Either[SvgRenderError, SvgOptions] =
    if width <= 0 || height <= 0 then Left(SvgRenderError.InvalidDocumentSize(width, height))
    else Right(new SvgOptions(width, height, title))

  def unsafe(width: Int = 640, height: Int = 480, title: Option[String] = None): SvgOptions =
    apply(width, height, title).orThrow

final case class SvgDocument(value: String):
  override def toString: String =
    value

/** Serializes a resolved [[intaglio.DeviceScene]] to SVG text.
  * All unit and orientation semantics are handled by the shared device
  * lowering; this backend only formats numeric device primitives.
  */
object SvgRenderer:
  def render(scene: Scene, options: SvgOptions = SvgOptions.default): Either[SvgRenderError, SvgDocument] =
    for
      device <- DeviceContext(options.width.toDouble, options.height.toDouble)
        .left
        .map(SvgRenderError.Graphics(_))
      deviceScene <- DeviceScene.fromScene(scene, device).left.map(SvgRenderError.Graphics(_))
      serialized <- serialize(deviceScene, options)
    yield SvgDocument(serialized)

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

  private def serialize(scene: DeviceScene, options: SvgOptions): Either[SvgRenderError, String] =
    validateDocument(scene, options).map(_ => serializeValidated(scene, options))

  private def serializeValidated(scene: DeviceScene, options: SvgOptions): String =
    val out = new StringBuilder
    val clips = new ClipRegistry
    line(
      out,
      0,
      s"""<svg xmlns="http://www.w3.org/2000/svg" width="${options.width}" height="${options.height}" viewBox="0 0 ${options.width} ${options.height}">"""
    )
    options.title.foreach(title => line(out, 1, s"<title>${escapeText(title)}</title>"))
    scene.elements.foreach(writeElement(_, out, 1, clips))
    val defs = clips.defs
    if defs.nonEmpty then
      line(out, 1, "<defs>")
      defs.foreach { case (id, clip) =>
        line(out, 2, s"""<clipPath id="$id">""")
        line(
          out,
          3,
          s"""<rect x="${format(clip.x)}" y="${format(clip.y)}" width="${format(clip.width)}" height="${format(clip.height)}" />"""
        )
        line(out, 2, "</clipPath>")
      }
      line(out, 1, "</defs>")
    line(out, 0, "</svg>")
    out.result()

  private def validateDocument(scene: DeviceScene, options: SvgOptions): Either[SvgRenderError, Unit] =
    val title = options.title match
      case Some(value) => validateXml("document title", value)
      case None        => Right(())
    title.flatMap(_ => validateElements(scene.elements))

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

  private def writeElement(element: DeviceElement, out: StringBuilder, indent: Int, clips: ClipRegistry): Unit =
    element match
      case DeviceElement.Mark(primitive) =>
        writePrimitive(primitive, out, indent)
      case DeviceElement.Group(name, clip, rotation, children) =>
        val nameAttr = name.map(n => s""" data-name="${escapeAttr(n.value)}"""").getOrElse("")
        val clipAttr = clip.map(c => s""" clip-path="url(#${clips.register(c)})"""").getOrElse("")
        val rotateAttr = rotation
          .map(r => s""" transform="rotate(${format(r.degrees)} ${format(r.pivotX)} ${format(r.pivotY)})"""")
          .getOrElse("")
        line(out, indent, s"<g$nameAttr$clipAttr$rotateAttr>")
        children.foreach(writeElement(_, out, indent + 1, clips))
        line(out, indent, "</g>")

  private def writePrimitive(primitive: DevicePrimitive, out: StringBuilder, indent: Int): Unit =
    primitive match
      case DevicePrimitive.Disc(cx, cy, radius, gp, name) =>
        line(
          out,
          indent,
          s"""<circle${commonAttrs(name, gp)} cx="${format(cx)}" cy="${format(cy)}" r="${format(radius)}" />"""
        )
      case DevicePrimitive.Polyline(points, closed, gp, name) =>
        val coords = points.map(p => s"${format(p.x)},${format(p.y)}").mkString(" ")
        if closed then line(out, indent, s"""<polygon${commonAttrs(name, gp)} points="$coords" />""")
        else line(out, indent, s"""<polyline${lineAttrs(name, gp)} points="$coords" />""")
      case DevicePrimitive.CompoundPolygon(rings, gp, name) =>
        val path = rings.map { ring =>
          val start = ring.head
          val rest = ring.tail.map(point => s"L ${format(point.x)} ${format(point.y)}").mkString(" ")
          s"M ${format(start.x)} ${format(start.y)} $rest Z"
        }.mkString(" ")
        line(out, indent, s"""<path${commonAttrs(name, gp)} fill-rule="nonzero" d="$path" />""")
      case DevicePrimitive.RectShape(x, y, width, height, gp, name) =>
        line(
          out,
          indent,
          s"""<rect${commonAttrs(name, gp)} x="${format(x)}" y="${format(y)}" width="${format(width)}" height="${format(height)}" />"""
        )
      case DevicePrimitive.TextRun(label, x, y, horizontal, vertical, rotationDegrees, fontSizePx, fontFamily, gp, name) =>
        val rotation =
          if rotationDegrees == 0.0 then ""
          else s""" transform="rotate(${format(rotationDegrees)} ${format(x)} ${format(y)})""""
        line(
          out,
          indent,
          s"""<text${textAttrs(name, gp, fontSizePx, fontFamily)} x="${format(x)}" y="${format(y)}" text-anchor="${textAnchor(horizontal)}" dominant-baseline="${dominantBaseline(vertical)}"$rotation>${escapeText(label)}</text>"""
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
          s"""<image$nameAttr data-pixel-width="${image.width}" data-pixel-height="${image.height}" x="${format(x)}" y="${format(y)}" width="${format(width)}" height="${format(height)}" preserveAspectRatio="none" image-rendering="$rendering"$opacityAttr href="${PngEncoder.dataUri(image)}" />"""
        )

  private def commonAttrs(name: Option[GraphicsName], gp: GraphicParams): String =
    val attrs = new StringBuilder
    name.foreach(n => attrs.append(s""" data-name="${escapeAttr(n.value)}""""))
    appendPaint(attrs, "stroke", gp.stroke)
    appendPaint(attrs, "fill", gp.fill)
    attrs.append(s""" stroke-width="${format(gp.lineWidth)}"""")
    attrs.append(s""" stroke-linecap="${lineCap(gp.lineCap)}"""")
    attrs.append(s""" stroke-linejoin="${lineJoin(gp.lineJoin)}"""")
    lineTypeAttr(gp.lineType).foreach(attrs.append)
    if gp.alpha != 1.0 then attrs.append(s""" opacity="${format(gp.alpha)}"""")
    attrs.result()

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

  /** Fixed-point formatting (up to 4 decimals, no exponent) so output is
    * byte-identical across JVM and JS double-to-string behavior.
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
