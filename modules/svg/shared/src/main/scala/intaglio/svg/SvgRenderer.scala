package intaglio.svg

import intaglio.*

final case class SvgOptions private (
    width: Int,
    height: Int,
    title: Option[String],
    pixelsPerInch: Double,
    deviceScale: Double,
    description: Option[String],
    idPrefix: Option[String]
):
  def logicalWidth: Double = width.toDouble / deviceScale
  def logicalHeight: Double = height.toDouble / deviceScale

  /** Binary bridge for the constructor from before id prefixes. Package-private, not private, so
    * the compiler keeps its descriptor in the class file for code compiled against 0.5.0.
    */
  private[svg] def this(
      width: Int,
      height: Int,
      title: Option[String],
      pixelsPerInch: Double,
      deviceScale: Double,
      description: Option[String]
  ) = this(width, height, title, pixelsPerInch, deviceScale, description, None)

  /** Prefix every id the document defines or references (clip paths, patterns, the accessible title
    * and description) with `prefix-`, so several SVG documents inlined in one HTML page never share
    * an id. Without a prefix the output is unchanged.
    */
  def withIdPrefix(prefix: String): Either[SvgRenderError, SvgOptions] =
    if SvgOptions.validIdPrefix(prefix) then
      Right(
        new SvgOptions(width, height, title, pixelsPerInch, deviceScale, description, Some(prefix))
      )
    else Left(SvgRenderError.InvalidIdPrefix(prefix))

  /** The document-local id `local`, namespaced by the prefix when one is set. */
  private[svg] def scopedId(local: String): String =
    idPrefix.fold(local)(prefix => s"$prefix-$local")

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
        .map(_ =>
          new SvgOptions(width, height, title, pixelsPerInch, deviceScale, description, None)
        )

  def unsafe(
      width: Int = 640,
      height: Int = 480,
      title: Option[String] = None,
      pixelsPerInch: Double = 96.0,
      deviceScale: Double = 1.0,
      description: Option[String] = None
  ): SvgOptions =
    apply(width, height, title, pixelsPerInch, deviceScale, description).orThrow

  private def validIdPrefix(value: String): Boolean =
    value.nonEmpty && value.head.isLetter && value.head < 128 &&
      value.forall(c => c < 128 && (c.isLetterOrDigit || c == '-' || c == '_'))

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
    render(plan, title, SvgFonts.empty)

  /** Render with caller-supplied faces embedded as `@font-face` data URIs, so the document draws
    * those faces on a viewer that does not have them installed. A face is embedded only when a text
    * run names its family; see [[SvgFonts]].
    */
  def render(
      plan: RenderPlan,
      title: Option[String],
      fonts: SvgFonts
  ): Either[SvgRenderError, SvgDocument] =
    renderPlan(plan, title, fonts, None)

  /** [[render]] with every document id namespaced by `idPrefix` (see [[SvgOptions.withIdPrefix]]),
    * for several plots inlined in one HTML page.
    */
  def render(
      plan: RenderPlan,
      title: Option[String],
      fonts: SvgFonts,
      idPrefix: String
  ): Either[SvgRenderError, SvgDocument] =
    renderPlan(plan, title, fonts, Some(idPrefix))

  private def renderPlan(
      plan: RenderPlan,
      title: Option[String],
      fonts: SvgFonts,
      idPrefix: Option[String]
  ): Either[SvgRenderError, SvgDocument] =
    for
      unscoped <- SvgOptions(
        plan.context.width,
        plan.context.height,
        title,
        plan.context.pixelsPerInch,
        plan.context.deviceScale
      )
      options <- idPrefix.fold(Right(unscoped))(unscoped.withIdPrefix)
      deviceScene <- plan.deviceScene.left.map(SvgRenderError.Graphics(_))
      serialized <- serialize(deviceScene, options, plan.context.textMetrics, fonts)
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
    render(scene, options, SvgFonts.empty)

  /** [[render]] with caller-supplied faces embedded; see the plan overload. */
  def render(
      scene: Scene,
      options: SvgOptions,
      fonts: SvgFonts
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
      serialized <- serialize(deviceScene, options, context.textMetrics, fonts)
    yield SvgDocument(
      serialized,
      options.width,
      options.height,
      options.pixelsPerInch,
      options.deviceScale,
      options.logicalWidth,
      options.logicalHeight
    )

  private final class ClipRegistry(options: SvgOptions):
    /** Bridge for the constructor descriptor from before id prefixes. */
    def this() = this(SvgOptions.default)

    private val builder = Vector.newBuilder[DeviceClip]
    private var count = 0

    def register(clip: DeviceClip): String =
      val id = options.scopedId(s"clip-$count")
      builder += clip
      count += 1
      id

    def defs: Vector[(String, DeviceClip)] =
      builder.result().zipWithIndex.map { case (clip, idx) =>
        (options.scopedId(s"clip-$idx"), clip)
      }

  private final class PatternRegistry(options: SvgOptions):
    /** Bridge for the constructor descriptor from before id prefixes. */
    def this() = this(SvgOptions.default)

    private var paints = Vector.empty[PatternPaint]

    def register(paint: PatternPaint): String =
      val existing = paints.indexOf(paint)
      if existing >= 0 then options.scopedId(s"pattern-$existing")
      else
        val id = options.scopedId(s"pattern-${paints.length}")
        paints = paints :+ paint
        id

    def defs: Vector[(String, PatternPaint)] =
      paints.zipWithIndex.map { case (paint, idx) => (options.scopedId(s"pattern-$idx"), paint) }

  private final case class DocumentAccessibility(
      id: String,
      title: Option[String],
      description: Option[String]
  )

  private def serialize(
      scene: DeviceScene,
      options: SvgOptions,
      textMetrics: TextMetrics,
      fonts: SvgFonts
  ): Either[SvgRenderError, String] =
    validateDocument(scene, options).map(_ =>
      serializeValidated(
        scene,
        options,
        new PlateMetrics(
          textMetrics,
          options.pixelsPerInch / 72.0,
          fonts.faces.map(face => SvgFonts.familyKey(face.family)).toSet,
          fonts
        ),
        fonts
      )
    )

  /** Families named by any text run, lower-cased as CSS compares them. */
  private def usedFamilies(elements: Vector[DeviceElement]): Set[String] =
    elements.iterator.flatMap {
      case DeviceElement.Mark(run: DevicePrimitive.TextRun) =>
        run.fontFamily.map(SvgFonts.familyKey).iterator
      case DeviceElement.Mark(_)                  => Iterator.empty
      case DeviceElement.Group(_, _, _, children) => usedFamilies(children).iterator
      case DeviceElement.Annotated(_, children)   => usedFamilies(children).iterator
    }.toSet

  private def writeFontFaces(
      scene: DeviceScene,
      fonts: SvgFonts,
      out: StringBuilder
  ): Unit =
    if fonts.faces.nonEmpty then
      val used = usedFamilies(scene.elements)
      val faces =
        fonts.faces.filter(face => used.contains(SvgFonts.familyKey(face.family)))
      if faces.nonEmpty then
        line(out, 1, "<style>")
        faces.foreach { face =>
          line(
            out,
            2,
            s"""@font-face { font-family: "${face.family}"; font-weight: ${face.weight.value}; src: url(${face.dataUri}) format("${face.format.cssFormat}"); }"""
          )
        }
        line(out, 1, "</style>")

  /** How a plate is measured. An SVG renderer cannot see the viewer's font, so without an embedded
    * face its measure is the render context's `TextMetrics`: the same measure layout and picking
    * use for this target. When the run's family and weight name an embedded TrueType or OpenType
    * face, the plate is bounded from that face's own tables instead (see [[SvgTextExtent]]).
    */
  private final class PlateMetrics(
      val metrics: TextMetrics,
      val pixelsPerPoint: Double,
      val embeddedFamilies: Set[String],
      fonts: SvgFonts
  ):
    private val parsed = scala.collection.mutable.HashMap.empty[Int, Option[SfntFont]]

    /** The embedded face a viewer draws `family` at `weight` with, parsed once per document. CSS
      * matches an exact weight first; any other weight could be synthesised, so it is not used.
      */
    def face(family: Option[String], weight: Option[FontWeight]): Option[SfntFont] =
      family.flatMap { name =>
        val key = SvgFonts.familyKey(name)
        val wanted = weight.getOrElse(FontWeight.Regular).value
        val index = fonts.faces.indexWhere(face =>
          SvgFonts.familyKey(face.family) == key && face.weight.value == wanted
        )
        if index < 0 then None
        else
          parsed.getOrElseUpdate(
            index, {
              val face = fonts.faces(index)
              val sfnt =
                face.format == SvgFontFormat.TrueType || face.format == SvgFontFormat.OpenType
              if sfnt then SfntFont.parse(face.bytes) else None
            }
          )
      }

  /** Extra room around bounded ink for anti-aliased edges and the viewer's rounding of font ascent
    * and glyph origins: one SVG user unit. The document's viewBox is its size in device pixels, so
    * this is one device pixel when the SVG is shown at its own size (`width` by `height` device
    * pixels); a host that scales the SVG scales the allowance with it.
    */
  private val InkMargin = 1.0

  /** The plate's content box (left, top, right, bottom) from an embedded face: the union of the
    * run's logical box (its widest possible advance by the face's ascent and descent) and the
    * bounded ink of every layout the viewer may produce, for every baseline the viewer may place.
    * `None` when the run is outside the model, so the caller uses the estimate.
    */
  private def embeddedPlateBox(
      font: SfntFont,
      label: String,
      x: Double,
      y: Double,
      horizontal: HJust,
      vertical: VJust,
      fontSizePx: Double
  ): Option[(Double, Double, Double, Double)] =
    val fraction = horizontal match
      case HJust.Left   => 0.0
      case HJust.Center => 0.5
      case HJust.Right  => 1.0
    // Every table read is guarded: a face that proves malformed here falls back to the estimate.
    SfntFont
      .guard(SvgTextExtent.codePoints(label).flatMap { cps =>
        val extents =
          SvgTextExtent.whiteSpaceVariants(cps).map(SvgTextExtent.measure(font, _, fraction))
        if extents.exists(_.isLeft) then None
        else
          val scale = fontSizePx / font.unitsPerEm.toDouble
          val sets = font.verticalMetrics
          val baselines: Vector[(Double, VerticalMetrics)] = vertical match
            case VJust.Top    => sets.map(m => (m.ascent * scale, m))
            case VJust.Bottom => sets.map(m => (-m.descent * scale, m))
            case VJust.Center =>
              val heights =
                if font.xHeights.nonEmpty then font.xHeights.map(_.toDouble)
                else sets.flatMap(m => Vector(0.0, m.ascent.toDouble))
              for height <- heights; m <- sets yield (height / 2.0 * scale, m)
          val boxes = for
            extent <- extents.collect { case Right(extent) => extent }
            (offset, set) <- baselines
            box <- {
              val baseline = y + offset
              val width = extent.advance.hi * scale
              val logical = (
                x - fraction * width,
                baseline - set.ascent * scale,
                x + (1.0 - fraction) * width,
                baseline + set.descent * scale
              )
              logical +: extent.ink.toVector.map { case (x0, y0, x1, y1) =>
                (
                  x + x0 * scale - InkMargin,
                  baseline - y1 * scale - InkMargin,
                  x + x1 * scale + InkMargin,
                  baseline - y0 * scale + InkMargin
                )
              }
            }
          yield box
          boxes.reduceOption { (a, b) =>
            (math.min(a._1, b._1), math.min(a._2, b._2), math.max(a._3, b._3), math.max(a._4, b._4))
          }
      })
      .flatten

  private def serializeValidated(
      scene: DeviceScene,
      options: SvgOptions,
      plates: PlateMetrics,
      fonts: SvgFonts
  ): String =
    val out = new StringBuilder
    val clips = new ClipRegistry(options)
    val patterns = new PatternRegistry(options)
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
    writeFontFaces(scene, fonts, out)
    scene.elements.foreach(writeElement(_, out, 1, clips, patterns, plates))
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
        options.scopedId(scene.semantics.documentId.map(_.value).getOrElse("intaglio-svg")),
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
      case DeviceElement.Annotated(meta, children) =>
        validateMeta(meta).flatMap(_ => validateElements(children))

  private def validateMeta(meta: GrobMeta): Either[SvgRenderError, Unit] =
    val title = meta.title match
      case Some(value) => validateXml("annotation title", value)
      case None        => Right(())
    val description = meta.description match
      case Some(value) => validateXml("annotation description", value)
      case None        => Right(())
    val duplicate = meta.duplicateDataKey match
      case Some(key) => Left(SvgRenderError.DuplicateDataKey(key.value))
      case None      => Right(())
    val values = meta.data.foldLeft[Either[SvgRenderError, Unit]](Right(())) {
      case (result, (key, value)) =>
        result.flatMap(_ => validateXml(s"annotation ${key.attributeName}", value))
    }
    val markTitles = meta.marks
      .flatMap(_.titles)
      .getOrElse(Vector.empty)
      .foldLeft[Either[SvgRenderError, Unit]](Right(())) { (result, text) =>
        result.flatMap(_ => validateXml("mark title", text))
      }
    val markNames = meta.marks
      .filter(_.dataAttribute.nonEmpty)
      .fold(Vector.empty[GraphicsName])(_.names)
      .foldLeft[Either[SvgRenderError, Unit]](Right(())) { (result, name) =>
        result.flatMap(_ => validateXml("mark name", name.value))
      }
    title
      .flatMap(_ => description)
      .flatMap(_ => duplicate)
      .flatMap(_ => values)
      .flatMap(_ => markTitles)
      .flatMap(_ => markNames)

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
      case DevicePrimitive.RectShape(_, _, _, _, _, _, name) =>
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
      patterns: PatternRegistry,
      plates: PlateMetrics,
      marks: Option[BatchMarks.Cursor] = None
  ): Unit =
    element match
      case DeviceElement.Mark(batch: DevicePrimitive.PointBatch) if marks.nonEmpty =>
        val cursor = marks.get
        var index = 0
        while index < batch.points.length do
          val identity = cursor.at(index)
          val attribute = for
            key <- cursor.marks.dataAttribute
            (name, _) <- identity
          yield s""" ${key.attributeName}="${escapeAttr(name.value)}""""
          val title = identity.flatMap(_._2)
          val wrapped = attribute.nonEmpty || title.nonEmpty
          // A mark is wrapped so its identity and title cover every element it is drawn with.
          if wrapped then
            line(out, indent, s"<g${attribute.getOrElse("")}>")
            title.foreach(text => line(out, indent + 1, s"<title>${escapeText(text)}</title>"))
          writePointMark(
            batch.points(index),
            batch.radii.valueAt(index),
            batch.shapes.valueAt(index),
            batch.graphicParams.valueAt(index),
            batch.name,
            out,
            if wrapped then indent + 1 else indent,
            patterns
          )
          if wrapped then line(out, indent, "</g>")
          index += 1
        cursor.advance(batch.points.length)
      case DeviceElement.Mark(primitive) =>
        writePrimitive(primitive, out, indent, patterns, plates)
      case DeviceElement.Group(name, clip, rotation, children) =>
        val nameAttr = name.map(n => s""" data-name="${escapeAttr(n.value)}"""").getOrElse("")
        val clipAttr = clip.map(c => s""" clip-path="url(#${clips.register(c)})"""").getOrElse("")
        val rotateAttr = rotation
          .map(r =>
            s""" transform="rotate(${format(r.degrees)} ${format(r.pivotX)} ${format(r.pivotY)})""""
          )
          .getOrElse("")
        line(out, indent, s"<g$nameAttr$clipAttr$rotateAttr>")
        children.foreach(writeElement(_, out, indent + 1, clips, patterns, plates, marks))
        line(out, indent, "</g>")
      case DeviceElement.Annotated(meta, children) =>
        val classAttr =
          meta.cssClass.map(value => s""" class="${escapeAttr(value.value)}"""").getOrElse("")
        val dataAttrs = meta.data.map { case (key, value) =>
          s""" ${key.attributeName}="${escapeAttr(value)}""""
        }.mkString
        line(out, indent, s"<g$classAttr$dataAttrs>")
        meta.title.foreach(title => line(out, indent + 1, s"<title>${escapeText(title)}</title>"))
        meta.description.foreach(description =>
          line(out, indent + 1, s"<desc>${escapeText(description)}</desc>")
        )
        val nested = meta.marks.map(new BatchMarks.Cursor(_)).orElse(marks)
        children.foreach(writeElement(_, out, indent + 1, clips, patterns, plates, nested))
        line(out, indent, "</g>")

  private def writePrimitive(
      primitive: DevicePrimitive,
      out: StringBuilder,
      indent: Int,
      patterns: PatternRegistry,
      plates: PlateMetrics
  ): Unit =
    primitive match
      case DevicePrimitive.Disc(cx, cy, radius, gp, name) =>
        writeClosedShape(
          "circle",
          s"""cx="${format(cx)}" cy="${format(cy)}" r="${format(radius)}"""",
          name,
          gp,
          out,
          indent,
          patterns
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
        visibleCasing(gp).foreach { casing =>
          val attrs = casingLineAttrs(gp, casing)
          if closed then line(out, indent, s"""<polygon$attrs points="$coords" />""")
          else line(out, indent, s"""<polyline$attrs points="$coords" />""")
        }
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
      case DevicePrimitive.RectShape(x, y, width, height, cornerRadius, gp, name) =>
        val corners =
          if cornerRadius == 0.0 then ""
          else s""" rx="${format(cornerRadius)}" ry="${format(cornerRadius)}""""
        writeClosedShape(
          "rect",
          s"""x="${format(x)}" y="${format(y)}" width="${format(width)}" height="${format(
              height
            )}"$corners""",
          name,
          gp,
          out,
          indent,
          patterns
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
        // A supplied face names one CSS string, not an unquoted identifier list or generic family.
        val cssFamily = fontFamily.map { family =>
          if plates.embeddedFamilies.contains(SvgFonts.familyKey(family)) then s"\"$family\""
          else family
        }
        val rotation =
          if rotationDegrees == 0.0 then ""
          else s""" transform="rotate(${format(rotationDegrees)} ${format(x)} ${format(y)})""""
        gp.textPlate.foreach { plate =>
          val measured = plates
            .face(fontFamily, gp.fontWeight)
            .flatMap(embeddedPlateBox(_, label, x, y, horizontal, vertical, fontSizePx))
          val box = measured match
            case Some((left, top, right, bottom)) =>
              plate.around(left, top, right - left, bottom - top)
            case None =>
              val style = TextStyle(fontFamily, fontSizePx / plates.pixelsPerPoint, gp.fontWeight)
              val width = plates.metrics.widthPt(label, style) * plates.pixelsPerPoint
              val height = plates.metrics.heightPt(style) * plates.pixelsPerPoint
              val left = horizontal match
                case HJust.Left   => x
                case HJust.Center => x - width / 2.0
                case HJust.Right  => x - width
              val top = vertical match
                case VJust.Top    => y
                case VJust.Center => y - height / 2.0
                case VJust.Bottom => y - height
              plate.around(left, top, width, height)
          val attrs = new StringBuilder
          appendPaint(attrs, "fill", Some(plate.fill))
          attrs.append(""" stroke="none"""")
          if gp.alpha != 1.0 then attrs.append(s""" opacity="${format(gp.alpha)}"""")
          val corners =
            if box.cornerRadius == 0.0 then ""
            else s""" rx="${format(box.cornerRadius)}" ry="${format(box.cornerRadius)}""""
          val events = if plate.pickable then "" else """ pointer-events="none""""
          line(
            out,
            indent,
            s"""<rect${attrs.result()} x="${format(box.x)}" y="${format(box.y)}" width="${format(
                box.width
              )}" height="${format(box.height)}"$corners$rotation$events />"""
          )
        }
        line(
          out,
          indent,
          s"""<text${textAttrs(name, gp, fontSizePx, cssFamily)} x="${format(x)}" y="${format(
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
        writeClosedShape(
          "circle",
          s"""cx="${format(point.x)}" cy="${format(point.y)}" r="${format(radius)}"""",
          name,
          gp,
          out,
          indent,
          patterns
        )
      case PointShape.Square =>
        writeClosedShape(
          "rect",
          s"""x="${format(point.x - radius)}" y="${format(point.y - radius)}" width="${format(
              radius * 2.0
            )}" height="${format(radius * 2.0)}"""",
          name,
          gp,
          out,
          indent,
          patterns
        )
      case PointShape.Triangle =>
        val coords =
          s"${format(point.x)},${format(point.y - radius)} ${format(point.x + radius)},${format(
              point.y + radius
            )} ${format(point.x - radius)},${format(point.y + radius)}"
        writeClosedShape("polygon", s"""points="$coords"""", name, gp, out, indent, patterns)
      case PointShape.Cross =>
        val bars = Vector(
          s"""points="${format(point.x - radius)},${format(point.y)} ${format(
              point.x + radius
            )},${format(point.y)}"""",
          s"""points="${format(point.x)},${format(point.y - radius)} ${format(point.x)},${format(
              point.y + radius
            )}""""
        )
        // Both underlays precede both bars: an underlay painted between them would cut the first
        // bar where the second crosses it.
        visibleCasing(gp).foreach { casing =>
          val attrs = casingLineAttrs(gp, casing)
          bars.foreach(bar => line(out, indent, s"""<polyline$attrs $bar />"""))
        }
        bars.foreach(bar => line(out, indent, s"""<polyline${lineAttrs(name, gp)} $bar />"""))
      case PointShape.Diamond =>
        val half = PointShape.diamondHalfDiagonal(radius)
        val coords =
          s"${format(point.x)},${format(point.y - half)} ${format(point.x + half)},${format(
              point.y
            )} ${format(point.x)},${format(point.y + half)} ${format(point.x - half)},${format(
              point.y
            )}"
        writeClosedShape("polygon", s"""points="$coords"""", name, gp, out, indent, patterns)

  /** One filled-and-stroked element, preceded by its unnamed casing underlay when the style has a
    * stroke to case. The underlay repeats the element's own geometry, so a cased point, disc or
    * rectangle follows the same rule as a cased path.
    */
  private def writeClosedShape(
      tag: String,
      geometry: String,
      name: Option[GraphicsName],
      gp: GraphicParams,
      out: StringBuilder,
      indent: Int,
      patterns: PatternRegistry
  ): Unit =
    visibleCasing(gp).foreach { casing =>
      line(out, indent, s"""<$tag${casingLineAttrs(gp, casing)} $geometry />""")
    }
    line(out, indent, s"""<$tag${commonAttrs(name, gp, patterns)} $geometry />""")

  private def visibleCasing(gp: GraphicParams): Option[StrokeCasing] =
    gp.casing.filter(_ => gp.stroke.nonEmpty)

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

  /** The casing deliberately omits `data-name`: it is paint for its primary primitive, not a second
    * scene target.
    */
  private def casingLineAttrs(gp: GraphicParams, casing: StrokeCasing): String =
    val width = casing.width match
      case CasingWidth.Absolute(value) => value.value
      case CasingWidth.Relative(_)     =>
        throw new IllegalStateException("relative casing width was not resolved")
    val attrs = new StringBuilder
    appendPaint(attrs, "stroke", Some(casing.color))
    attrs.append(""" fill="none"""")
    attrs.append(s""" stroke-width="${format(width)}"""")
    attrs.append(s""" stroke-linecap="${lineCap(gp.lineCap)}"""")
    attrs.append(s""" stroke-linejoin="${lineJoin(gp.lineJoin)}"""")
    lineTypeAttr(casing.lineType).foreach(attrs.append)
    val opacity = gp.alpha * casing.alpha
    if opacity != 1.0 then attrs.append(s""" opacity="${format(opacity)}"""")
    attrs.append(""" pointer-events="none"""")
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
    gp.fontWeight.foreach(weight => attrs.append(s""" font-weight="${weight.value}""""))
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
    lineType.dash.map { pattern =>
      s""" stroke-dasharray="${pattern.segments.map(format).mkString(" ")}""""
    }

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
