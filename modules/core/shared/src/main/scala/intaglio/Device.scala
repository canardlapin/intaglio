package intaglio

/** Direction of increasing y within a viewport's coordinate space, relative to the device. Scene
  * coordinates (npc and native) are y-up by default, the grid convention: y = 0 is the bottom edge
  * and increasing y moves toward the top of the device. Rotation angles follow the same handedness:
  * in a y-up frame a positive angle turns counterclockwise (the mathematical convention), in a
  * y-down frame clockwise. Backends receive device coordinates (y-down, origin at the top-left,
  * clockwise-positive angles) only after resolution.
  */
enum YDirection:
  case Up
  case Down

/** Physical description of a render target: size in device pixels and pixel density for absolute
  * units. All backends resolve scene lengths through this context, so unit semantics live here
  * rather than per renderer.
  */
final case class DeviceContext private (width: Double, height: Double, pixelsPerInch: Double):
  private[intaglio] def pxPerUnit(unit: LengthUnit): Option[Double] =
    unit match
      case LengthUnit.Point                                     => Some(pixelsPerInch / 72.0)
      case LengthUnit.Inch                                      => Some(pixelsPerInch)
      case LengthUnit.Cm                                        => Some(pixelsPerInch / 2.54)
      case LengthUnit.Mm                                        => Some(pixelsPerInch / 25.4)
      case LengthUnit.Npc | LengthUnit.Native | LengthUnit.Line =>
        None

object DeviceContext:
  def apply(
      width: Double,
      height: Double,
      pixelsPerInch: Double = 96.0
  ): Either[GraphicsError, DeviceContext] =
    if !width.isFinite || width <= 0.0 || !height.isFinite || height <= 0.0 then
      Left(GraphicsError.InvalidDeviceSize(width, height))
    else if !pixelsPerInch.isFinite || pixelsPerInch <= 0.0 then
      Left(GraphicsError.InvalidDeviceResolution(pixelsPerInch))
    else Right(new DeviceContext(width, height, pixelsPerInch))

  def unsafe(width: Double, height: Double, pixelsPerInch: Double = 96.0): DeviceContext =
    apply(width, height, pixelsPerInch).orThrow

/** A viewport resolved to a device-pixel rectangle. `x`/`y` locate the top-left corner in device
  * coordinates (y-down); `xScale`/`yScale` give the native data ranges and `yDirection` the
  * orientation of scene y within the frame.
  */
final case class DeviceFrame(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    xScale: Interval,
    yScale: Interval,
    yDirection: YDirection
)

object DeviceFrame:
  def root(device: DeviceContext): DeviceFrame =
    DeviceFrame(
      0.0,
      0.0,
      device.width,
      device.height,
      Interval.unsafe(0.0, 1.0),
      Interval.unsafe(0.0, 1.0),
      YDirection.Up
    )

private object DeviceValue:
  val MaxMagnitude: Double = 1.0e13

  def checked(field: String, value: Double): Either[GraphicsError, Double] =
    if !value.isFinite || math.abs(value) > MaxMagnitude then
      Left(GraphicsError.InvalidDeviceValue(field, value))
    else Right(value)

/** Evaluates length expressions against a device frame. Locations resolve to device coordinates (y
  * flipped for y-up frames); extents resolve to non-directional pixel magnitudes.
  */
final class LengthResolver(
    device: DeviceContext,
    val frame: DeviceFrame,
    fontRegistry: FontRegistry = FontRegistry.passthrough,
    lineHeightPt: Double = 12.0
):
  private val lineHeightPx = lineHeightPt * device.pixelsPerInch / 72.0

  def x(expr: LengthExpr): Either[GraphicsError, Double] =
    eval(expr, horizontal = true, location = true).map(frame.x + _).flatMap(checked)

  def y(expr: LengthExpr): Either[GraphicsError, Double] =
    eval(expr, horizontal = false, location = true)
      .map { local =>
        frame.yDirection match
          case YDirection.Up   => frame.y + frame.height - local
          case YDirection.Down => frame.y + local
      }
      .flatMap(checked)

  def width(expr: LengthExpr): Either[GraphicsError, Double] =
    eval(expr, horizontal = true, location = false).flatMap(checked)

  def height(expr: LengthExpr): Either[GraphicsError, Double] =
    eval(expr, horizontal = false, location = false).flatMap(checked)

  def width(extent: ExtentExpr): Either[GraphicsError, Double] =
    width(extent.expr)

  def height(extent: ExtentExpr): Either[GraphicsError, Double] =
    height(extent.expr)

  /** Axis-neutral extent (point sizes, circle radii): the smaller of the horizontal and vertical
    * resolutions, so relative units cannot distort marks on anisotropic frames.
    */
  def extent(value: ExtentExpr): Either[GraphicsError, Double] =
    for
      w <- width(value)
      h <- height(value)
    yield math.min(w, h)

  /** Font sizes accept physical units and the context-bound line unit; frame-relative units do not
    * describe glyph size.
    */
  def fontSize(length: Length): Either[GraphicsError, Double] =
    length.unit match
      case LengthUnit.Line => DeviceValue.checked("font size", length.value * lineHeightPx)
      case unit            =>
        device.pxPerUnit(unit) match
          case Some(px) => DeviceValue.checked("font size", length.value * px)
          case None     =>
            Left(GraphicsError.UnresolvableLength(s"font size in unit '${length.unit}'"))

  def graphicParams(gp: GraphicParams): Either[GraphicsError, GraphicParams] =
    val pixels = gp.lineWidthUnit match
      case StrokeUnit.DevicePixel => gp.lineWidth
      case StrokeUnit.Point       => gp.lineWidth * device.pixelsPerInch / 72.0
    DeviceValue
      .checked("line width", pixels)
      .map(value => gp.withStrokeWidth(StrokeWidth.devicePixelsUnsafe(value)))

  def fontFamily(requested: Option[String]): Option[String] =
    fontRegistry.resolve(requested)

  /** Resolve a child viewport. The viewport origin is the lower-left corner of the child frame when
    * this frame is y-up, the upper-left when y-down.
    */
  def childFrame(viewport: Viewport): Either[GraphicsError, DeviceFrame] =
    for
      originX <- x(viewport.origin.x)
      originY <- y(viewport.origin.y)
      w <- width(viewport.size.width)
      h <- height(viewport.size.height)
    yield
      val top = frame.yDirection match
        case YDirection.Up   => originY - h
        case YDirection.Down => originY
      DeviceFrame(originX, top, w, h, viewport.xScale, viewport.yScale, viewport.yDirection)

  /** Resolved device coordinates must be finite and small enough to format exactly; anything else
    * is a degenerate scale or runaway expression.
    */
  private def checked(value: Double): Either[GraphicsError, Double] =
    if !value.isFinite then Left(GraphicsError.UnresolvableLength("non-finite device coordinate"))
    else if math.abs(value) > 1.0e13 then
      Left(GraphicsError.UnresolvableLength(s"device coordinate magnitude too large: $value"))
    else Right(value)

  private def eval(
      expr: LengthExpr,
      horizontal: Boolean,
      location: Boolean
  ): Either[GraphicsError, Double] =
    expr match
      case LengthExpr.Const(length) =>
        scalar(length, horizontal, location)
      case LengthExpr.Add(left, right) =>
        for
          l <- eval(left, horizontal, location)
          r <- eval(right, horizontal, location)
        yield l + r
      case LengthExpr.Sub(left, right) =>
        for
          l <- eval(left, horizontal, location)
          r <- eval(right, horizontal, location)
        yield l - r
      case LengthExpr.Offset(base, extent, direction) =>
        for
          locationValue <- eval(base, horizontal, location = true)
          extentValue <- eval(extent.expr, horizontal, location = false)
        yield locationValue + direction * extentValue
      case LengthExpr.Mul(factor, value) =>
        eval(value, horizontal, location).map(factor * _)

  private def scalar(
      length: Length,
      horizontal: Boolean,
      location: Boolean
  ): Either[GraphicsError, Double] =
    val span = if horizontal then frame.width else frame.height
    val scale = if horizontal then frame.xScale else frame.yScale
    length.unit match
      case LengthUnit.Npc =>
        Right(length.value * span)
      case LengthUnit.Native =>
        if location then Right(scale.rescale(length.value) * span)
        else if scale.width == 0.0 then Right(0.0)
        else Right(length.value / scale.width * span)
      case LengthUnit.Line =>
        Right(length.value * lineHeightPx)
      case other =>
        device.pxPerUnit(other) match
          case Some(px) => Right(length.value * px)
          case None     => Left(GraphicsError.UnresolvableLength(s"length unit '$other'"))

final case class DevicePoint(x: Double, y: Double)

/** Fully resolved drawing primitives in device coordinates (pixels, y-down). No units, viewports,
  * or plot semantics remain: any backend can interpret these with local drawing calls only.
  */
enum DevicePrimitive:
  case Disc(
      centerX: Double,
      centerY: Double,
      radius: Double,
      gp: GraphicParams,
      name: Option[GraphicsName]
  )
  case PointBatch(
      points: Vector[DevicePoint],
      radii: BatchColumn[Double],
      shapes: BatchColumn[PointShape],
      graphicParams: BatchColumn[GraphicParams],
      name: Option[GraphicsName]
  )
  case Polyline(
      points: Vector[DevicePoint],
      closed: Boolean,
      gp: GraphicParams,
      name: Option[GraphicsName]
  )
  case CompoundPolygon(
      rings: Vector[Vector[DevicePoint]],
      gp: GraphicParams,
      name: Option[GraphicsName]
  )
  case RectShape(
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      gp: GraphicParams,
      name: Option[GraphicsName]
  )
  case TextRun(
      label: String,
      x: Double,
      y: Double,
      horizontal: HJust,
      vertical: VJust,
      rotationDegrees: Double,
      fontSizePx: Double,
      fontFamily: Option[String],
      gp: GraphicParams,
      name: Option[GraphicsName]
  )
  case Image(
      image: RasterImage,
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      interpolation: RasterInterpolation,
      alpha: Double,
      name: Option[GraphicsName]
  )

final case class DeviceClip(x: Double, y: Double, width: Double, height: Double)

final case class DeviceRotation(degrees: Double, pivotX: Double, pivotY: Double)

enum DeviceElement:
  case Mark(primitive: DevicePrimitive)
  case Group(
      name: Option[GraphicsName],
      clip: Option[DeviceClip],
      rotation: Option[DeviceRotation],
      children: Vector[DeviceElement]
  )

/** A scene flattened against a device context: the portable, numeric render contract shared by all
  * backends.
  */
final case class DeviceScene(width: Double, height: Double, elements: Vector[DeviceElement])

object DeviceScene:
  def fromScene(scene: Scene, device: DeviceContext): Either[GraphicsError, DeviceScene] =
    fromScene(scene, device, FontRegistry.passthrough, lineHeightPt = 12.0)

  def fromScene(scene: Scene, context: RenderContext): Either[GraphicsError, DeviceScene] =
    fromScene(scene, context.deviceContext, context.fontRegistry, context.lineHeightPt)

  private def fromScene(
      scene: Scene,
      device: DeviceContext,
      fontRegistry: FontRegistry,
      lineHeightPt: Double
  ): Either[GraphicsError, DeviceScene] =
    for
      elements <- lowerAll(
        scene.grobs,
        device,
        DeviceFrame.root(device),
        fontRegistry,
        lineHeightPt
      )
      resolved <- validate(DeviceScene(device.width, device.height, elements))
    yield resolved

  private def validate(scene: DeviceScene): Either[GraphicsError, DeviceScene] =
    for
      _ <- DeviceValue.checked("width", scene.width)
      _ <- DeviceValue.checked("height", scene.height)
      _ <- validateElements(scene.elements)
    yield scene

  private def validateElements(elements: Vector[DeviceElement]): Either[GraphicsError, Unit] =
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < elements.length && result.isRight do
      result = validateElement(elements(idx))
      idx += 1
    result

  private def validateElement(element: DeviceElement): Either[GraphicsError, Unit] =
    element match
      case DeviceElement.Mark(primitive) =>
        validatePrimitive(primitive)
      case DeviceElement.Group(_, clip, rotation, children) =>
        val clipResult = clip match
          case Some(value) =>
            validateNumbers(
              Vector(
                "clip x" -> value.x,
                "clip y" -> value.y,
                "clip width" -> value.width,
                "clip height" -> value.height
              )
            )
          case None => Right(())
        val rotationResult = rotation match
          case Some(value) =>
            validateNumbers(
              Vector(
                "rotation" -> value.degrees,
                "rotation pivot x" -> value.pivotX,
                "rotation pivot y" -> value.pivotY
              )
            )
          case None => Right(())
        clipResult.flatMap(_ => rotationResult).flatMap(_ => validateElements(children))

  private def validatePrimitive(primitive: DevicePrimitive): Either[GraphicsError, Unit] =
    primitive match
      case DevicePrimitive.Disc(centerX, centerY, radius, gp, _) =>
        validateNumbers(
          Vector(
            "disc center x" -> centerX,
            "disc center y" -> centerY,
            "disc radius" -> radius
          )
        ).flatMap(_ => validateFillGraphicParams(gp))
      case DevicePrimitive.PointBatch(points, radii, shapes, params, _) =>
        for
          _ <- validateBatchColumn("device point radius", radii, points.length)
          _ <- validateBatchColumn("device point shape", shapes, points.length)
          _ <- validateBatchColumn("device point graphic parameters", params, points.length)
          _ <- validatePoints(points)
          _ <- validatePointBatchStyles(points.length, radii, params)
        yield ()
      case DevicePrimitive.Polyline(points, closed, gp, _) =>
        validatePoints(points).flatMap { _ =>
          if closed then validateFillGraphicParams(gp)
          else DeviceValue.checked("line width", gp.lineWidth).map(_ => ())
        }
      case DevicePrimitive.CompoundPolygon(rings, gp, _) =>
        validatePointGroups(rings).flatMap(_ => validateFillGraphicParams(gp))
      case DevicePrimitive.RectShape(x, y, width, height, gp, _) =>
        validateNumbers(
          Vector(
            "rectangle x" -> x,
            "rectangle y" -> y,
            "rectangle width" -> width,
            "rectangle height" -> height
          )
        ).flatMap(_ => validateFillGraphicParams(gp))
      case DevicePrimitive.TextRun(_, x, y, _, _, rotationDegrees, fontSizePx, _, _, _) =>
        validateNumbers(
          Vector(
            "text x" -> x,
            "text y" -> y,
            "rotation" -> rotationDegrees,
            "font size" -> fontSizePx
          )
        )
      case DevicePrimitive.Image(_, x, y, width, height, _, alpha, _) =>
        validateNumbers(
          Vector(
            "image x" -> x,
            "image y" -> y,
            "image width" -> width,
            "image height" -> height,
            "image alpha" -> alpha
          )
        )

  private def validateFillGraphicParams(gp: GraphicParams): Either[GraphicsError, Unit] =
    DeviceValue.checked("line width", gp.lineWidth).flatMap { _ =>
      gp.fillPattern match
        case None          => Right(())
        case Some(pattern) =>
          val values = pattern.recipe match
            case recipe: PatternRecipe.AngledHatch =>
              Vector(
                "pattern angle" -> recipe.angleDegrees,
                "pattern spacing" -> recipe.spacing,
                "pattern line width" -> recipe.lineWidth
              )
            case recipe: PatternRecipe.CrossHatch =>
              Vector(
                "pattern angle" -> recipe.angleDegrees,
                "pattern spacing" -> recipe.spacing,
                "pattern line width" -> recipe.lineWidth
              )
            case recipe: PatternRecipe.ParallelRules =>
              Vector(
                "pattern spacing" -> recipe.spacing,
                "pattern line width" -> recipe.lineWidth
              )
            case recipe: PatternRecipe.Stipple =>
              Vector(
                "pattern spacing" -> recipe.spacing,
                "pattern radius" -> recipe.radius
              )
          validateNumbers(values)
    }

  private def validateBatchColumn[A](
      name: String,
      column: BatchColumn[A],
      markCount: Int
  ): Either[GraphicsError, Unit] =
    column.valueCount match
      case Some(values) if values != markCount =>
        Left(GraphicsError.BatchColumnLengthMismatch(name, markCount, values))
      case _ => Right(())

  private def validatePointBatchStyles(
      markCount: Int,
      radii: BatchColumn[Double],
      params: BatchColumn[GraphicParams]
  ): Either[GraphicsError, Unit] =
    var index = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while index < markCount && result.isRight do
      result = DeviceValue
        .checked("point batch radius", radii.valueAt(index))
        .flatMap(_ => validateFillGraphicParams(params.valueAt(index)))
      index += 1
    result

  private def validatePoints(points: Vector[DevicePoint]): Either[GraphicsError, Unit] =
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < points.length && result.isRight do
      val point = points(idx)
      result = validateNumbers(Vector("point x" -> point.x, "point y" -> point.y))
      idx += 1
    result

  private def validatePointGroups(
      groups: Vector[Vector[DevicePoint]]
  ): Either[GraphicsError, Unit] =
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < groups.length && result.isRight do
      result = validatePoints(groups(idx))
      idx += 1
    result

  private def validateNumbers(values: Vector[(String, Double)]): Either[GraphicsError, Unit] =
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < values.length && result.isRight do
      val (field, value) = values(idx)
      result = DeviceValue.checked(field, value).map(_ => ())
      idx += 1
    result

  private def lowerAll(
      grobs: Vector[Grob],
      device: DeviceContext,
      frame: DeviceFrame,
      fontRegistry: FontRegistry,
      lineHeightPt: Double
  ): Either[GraphicsError, Vector[DeviceElement]] =
    val out = Vector.newBuilder[DeviceElement]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < grobs.length && result.isRight do
      result = lower(grobs(idx), device, frame, fontRegistry, lineHeightPt).map { elements =>
        out ++= elements
        ()
      }
      idx += 1
    result.map(_ => out.result())

  /** Scene angles are counterclockwise-positive in y-up frames; device space is y-down where
    * positive angles turn clockwise, so the handedness flips with the frame orientation.
    */
  private def deviceDegrees(degrees: Double, frame: DeviceFrame): Double =
    frame.yDirection match
      case YDirection.Up   => -degrees
      case YDirection.Down => degrees

  private def lower(
      grob: Grob,
      device: DeviceContext,
      frame: DeviceFrame,
      fontRegistry: FontRegistry,
      lineHeightPt: Double
  ): Either[GraphicsError, Vector[DeviceElement]] =
    grob.viewport match
      case Some(viewport) =>
        LengthResolver(device, frame, fontRegistry, lineHeightPt).childFrame(viewport).flatMap {
          child =>
            contents(grob, device, child, fontRegistry, lineHeightPt).map { children =>
              val clip = viewport.clip match
                case Clip.On  => Some(DeviceClip(child.x, child.y, child.width, child.height))
                case Clip.Off => None
              val rotation =
                if viewport.angleDegrees == 0.0 then None
                else
                  val pivotY = frame.yDirection match
                    case YDirection.Up   => child.y + child.height
                    case YDirection.Down => child.y
                  Some(DeviceRotation(deviceDegrees(viewport.angleDegrees, frame), child.x, pivotY))
              Vector(DeviceElement.Group(grob.name, clip, rotation, children))
            }
        }
      case None =>
        grob match
          case group: Grob.Group =>
            lowerAll(group.children, device, frame, fontRegistry, lineHeightPt).map { children =>
              Vector(DeviceElement.Group(group.name, None, None, children))
            }
          case other =>
            contents(other, device, frame, fontRegistry, lineHeightPt)

  private def contents(
      grob: Grob,
      device: DeviceContext,
      frame: DeviceFrame,
      fontRegistry: FontRegistry,
      lineHeightPt: Double
  ): Either[GraphicsError, Vector[DeviceElement]] =
    grob match
      case group: Grob.Group =>
        lowerAll(group.children, device, frame, fontRegistry, lineHeightPt)
      case other =>
        marks(other, LengthResolver(device, frame, fontRegistry, lineHeightPt))
          .map(_.map(DeviceElement.Mark(_)))

  private def marks(
      grob: Grob,
      resolver: LengthResolver
  ): Either[GraphicsError, Vector[DevicePrimitive]] =
    grob match
      case points: Grob.Points =>
        pointMarks(points, resolver)
      case points: Grob.PointBatch =>
        pointBatchMark(points, resolver)
      case lines: Grob.Lines =>
        for
          resolved <- resolvePoints(lines.points, resolver)
          gp <- resolver.graphicParams(lines.gp)
        yield Vector(DevicePrimitive.Polyline(resolved, closed = false, gp, lines.name))
      case polygon: Grob.Polygon =>
        for
          resolved <- resolvePoints(polygon.points, resolver)
          gp <- resolver.graphicParams(polygon.gp)
        yield Vector(DevicePrimitive.Polyline(resolved, closed = true, gp, polygon.name))
      case polygon: Grob.CompoundPolygon =>
        for
          resolved <- resolvePointGroups(polygon.rings, resolver)
          gp <- resolver.graphicParams(polygon.gp)
        yield Vector(DevicePrimitive.CompoundPolygon(resolved, gp, polygon.name))
      case segments: Grob.Segments =>
        segmentMarks(segments, resolver)
      case rect: Grob.Rect =>
        rectMark(rect, resolver)
      case circle: Grob.Circle =>
        for
          cx <- resolver.x(circle.center.x)
          cy <- resolver.y(circle.center.y)
          radius <- resolver.extent(circle.radius)
          gp <- resolver.graphicParams(circle.gp)
        yield Vector(DevicePrimitive.Disc(cx, cy, radius, gp, circle.name))
      case text: Grob.Text =>
        for
          x <- resolver.x(text.at.x)
          y <- resolver.y(text.at.y)
          fontPx <- resolver.fontSize(text.gp.fontSize)
          gp <- resolver.graphicParams(text.gp)
        yield
          val fontFamily = resolver.fontFamily(text.gp.fontFamily)
          Vector(
            DevicePrimitive.TextRun(
              text.label,
              x,
              y,
              text.anchor.horizontal,
              text.anchor.vertical,
              deviceDegrees(text.rotationDegrees, resolver.frame),
              fontPx,
              fontFamily,
              gp,
              text.name
            )
          )
      case image: Grob.Image =>
        imageMark(image, resolver)
      case group: Grob.Group =>
        Left(GraphicsError.UnresolvableLength("group grobs have no marks"))

  private def pointMarks(
      points: Grob.Points,
      resolver: LengthResolver
  ): Either[GraphicsError, Vector[DevicePrimitive]] =
    for
      radius <- resolver.extent(points.size)
      resolved <- resolvePoints(points.points, resolver)
      gp <- resolver.graphicParams(points.gp)
    yield resolved.flatMap(point => shapeMarks(points, point, radius, gp))

  private def pointBatchMark(
      points: Grob.PointBatch,
      resolver: LengthResolver
  ): Either[GraphicsError, Vector[DevicePrimitive]] =
    for
      resolved <- resolvePoints(points.points, resolver)
      radii <- points.sizes.traverse(resolver.extent)
      params <- points.graphicParams.traverse(resolver.graphicParams)
    yield Vector(
      DevicePrimitive.PointBatch(resolved, radii, points.shapes, params, points.name)
    )

  private def shapeMarks(
      points: Grob.Points,
      at: DevicePoint,
      radius: Double,
      gp: GraphicParams
  ): Vector[DevicePrimitive] =
    points.shape match
      case PointShape.Circle =>
        Vector(DevicePrimitive.Disc(at.x, at.y, radius, gp, points.name))
      case PointShape.Square =>
        Vector(
          DevicePrimitive.RectShape(
            at.x - radius,
            at.y - radius,
            radius * 2.0,
            radius * 2.0,
            gp,
            points.name
          )
        )
      case PointShape.Triangle =>
        Vector(
          DevicePrimitive.Polyline(
            Vector(
              DevicePoint(at.x, at.y - radius),
              DevicePoint(at.x + radius, at.y + radius),
              DevicePoint(at.x - radius, at.y + radius)
            ),
            closed = true,
            gp,
            points.name
          )
        )
      case PointShape.Cross =>
        Vector(
          DevicePrimitive.Polyline(
            Vector(DevicePoint(at.x - radius, at.y), DevicePoint(at.x + radius, at.y)),
            closed = false,
            gp,
            points.name
          ),
          DevicePrimitive.Polyline(
            Vector(DevicePoint(at.x, at.y - radius), DevicePoint(at.x, at.y + radius)),
            closed = false,
            gp,
            points.name
          )
        )

  private def segmentMarks(
      segments: Grob.Segments,
      resolver: LengthResolver
  ): Either[GraphicsError, Vector[DevicePrimitive]] =
    resolver.graphicParams(segments.gp).flatMap { gp =>
      val out = Vector.newBuilder[DevicePrimitive]
      var idx = 0
      var result: Either[GraphicsError, Unit] = Right(())
      while idx < segments.segments.length && result.isRight do
        val (from, to) = segments.segments(idx)
        result =
          for
            x0 <- resolver.x(from.x)
            y0 <- resolver.y(from.y)
            x1 <- resolver.x(to.x)
            y1 <- resolver.y(to.y)
          yield
            out += DevicePrimitive.Polyline(
              Vector(DevicePoint(x0, y0), DevicePoint(x1, y1)),
              closed = false,
              gp,
              segments.name
            )
            ()
        idx += 1
      result.map(_ => out.result())
    }

  private def rectMark(
      rect: Grob.Rect,
      resolver: LengthResolver
  ): Either[GraphicsError, Vector[DevicePrimitive]] =
    for
      bounds <- anchoredBounds(rect.center, rect.size, rect.anchor, resolver)
      gp <- resolver.graphicParams(rect.gp)
    yield
      val (x, y, width, height) = bounds
      Vector(DevicePrimitive.RectShape(x, y, width, height, gp, rect.name))

  private def imageMark(
      image: Grob.Image,
      resolver: LengthResolver
  ): Either[GraphicsError, Vector[DevicePrimitive]] =
    anchoredBounds(image.at, image.size, image.anchor, resolver).map { case (x, y, width, height) =>
      Vector(
        DevicePrimitive.Image(
          image.image,
          x,
          y,
          width,
          height,
          image.interpolation,
          image.alpha,
          image.name
        )
      )
    }

  private def anchoredBounds(
      at: Point,
      size: Size,
      anchor: Anchor,
      resolver: LengthResolver
  ): Either[GraphicsError, (Double, Double, Double, Double)] =
    for
      cx <- resolver.x(at.x)
      cy <- resolver.y(at.y)
      w <- resolver.width(size.width)
      h <- resolver.height(size.height)
    yield
      val x0 = anchor.horizontal match
        case HJust.Left   => cx
        case HJust.Center => cx - w / 2.0
        case HJust.Right  => cx - w
      val y0 = anchor.vertical match
        case VJust.Top    => cy
        case VJust.Center => cy - h / 2.0
        case VJust.Bottom => cy - h
      (x0, y0, w, h)

  private def resolvePoints(
      points: Vector[Point],
      resolver: LengthResolver
  ): Either[GraphicsError, Vector[DevicePoint]] =
    val out = Vector.newBuilder[DevicePoint]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < points.length && result.isRight do
      val point = points(idx)
      result =
        for
          x <- resolver.x(point.x)
          y <- resolver.y(point.y)
        yield
          out += DevicePoint(x, y)
          ()
      idx += 1
    result.map(_ => out.result())

  private def resolvePointGroups(
      groups: Vector[Vector[Point]],
      resolver: LengthResolver
  ): Either[GraphicsError, Vector[Vector[DevicePoint]]] =
    val out = Vector.newBuilder[Vector[DevicePoint]]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < groups.length && result.isRight do
      result = resolvePoints(groups(idx), resolver).map { points =>
        out += points
        ()
      }
      idx += 1
    result.map(_ => out.result())
