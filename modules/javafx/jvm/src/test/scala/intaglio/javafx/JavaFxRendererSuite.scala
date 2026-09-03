package intaglio.javafx

import scala.collection.mutable.{ArrayBuffer, HashSet}
import intaglio.*

/** Records every drawing call so the interpreter is exercised without starting the JavaFX toolkit.
  */
final class RecordingFxContext extends JavaFxGraphicsContext:
  val calls: ArrayBuffer[String] = ArrayBuffer.empty
  var lastDashes: Vector[Double] = Vector.empty
  var lastCap: Option[LineCap] = None
  var lastJoin: Option[LineJoin] = None
  var lastStroke: Option[JavaFxColor] = None
  var lastFill: Option[JavaFxColor] = None
  var lastPattern: Option[PatternPaint] = None
  val patternResources: HashSet[PatternPaint] = HashSet.empty
  var lastFont: (Option[String], Double) = (None, 0.0)
  var lastAlign: Option[HJust] = None
  var lastBaseline: Option[VJust] = None
  var globalAlpha: Double = 1.0
  val globalAlphaValues: ArrayBuffer[Double] = ArrayBuffer.empty
  var imageSmoothing: Boolean = true
  var drawnImage: Option[RasterImage] = None
  var drawnBounds: Vector[Double] = Vector.empty

  override def save(): Unit = calls += "save"
  override def restore(): Unit = calls += "restore"
  override def translate(x: Double, y: Double): Unit = calls += "translate"
  override def rotateDegrees(degrees: Double): Unit = calls += "rotate"
  override def beginPath(): Unit = calls += "beginPath"
  override def moveTo(x: Double, y: Double): Unit = calls += "moveTo"
  override def lineTo(x: Double, y: Double): Unit = calls += "lineTo"
  override def closePath(): Unit = calls += "closePath"
  override def rect(x: Double, y: Double, width: Double, height: Double): Unit = calls += "rect"
  override def clip(): Unit = calls += "clip"
  override def fillPath(): Unit = calls += "fillPath"
  override def strokePath(): Unit = calls += "strokePath"
  override def fillOval(x: Double, y: Double, width: Double, height: Double): Unit =
    calls += "fillOval"
  override def strokeOval(x: Double, y: Double, width: Double, height: Double): Unit =
    calls += "strokeOval"
  override def setFill(color: JavaFxColor): Unit =
    calls += "setFill"
    lastFill = Some(color)
  override def setPatternFill(pattern: PatternPaint): Boolean =
    calls += "setPatternFill"
    lastPattern = Some(pattern)
    val hit = patternResources.contains(pattern)
    patternResources += pattern
    hit
  override def setStroke(color: JavaFxColor): Unit =
    calls += "setStroke"
    lastStroke = Some(color)
  override def setLineWidth(width: Double): Unit = calls += "setLineWidth"
  override def setLineCap(cap: LineCap): Unit =
    calls += "setLineCap"
    lastCap = Some(cap)
  override def setLineJoin(join: LineJoin): Unit =
    calls += "setLineJoin"
    lastJoin = Some(join)
  override def setLineDashes(pattern: Vector[Double]): Unit =
    calls += "setLineDashes"
    lastDashes = pattern
  override def setFont(family: Option[String], sizePx: Double): Unit =
    calls += "setFont"
    lastFont = (family, sizePx)
  override def setTextAlign(horizontal: HJust): Unit =
    calls += "setTextAlign"
    lastAlign = Some(horizontal)
  override def setTextBaseline(vertical: VJust): Unit =
    calls += "setTextBaseline"
    lastBaseline = Some(vertical)
  override def fillText(label: String, x: Double, y: Double): Unit = calls += "fillText"
  override def setGlobalAlpha(alpha: Double): Unit =
    calls += "setGlobalAlpha"
    globalAlpha = alpha
    globalAlphaValues += alpha
  override def setImageSmoothing(enabled: Boolean): Unit =
    calls += "setImageSmoothing"
    imageSmoothing = enabled
  override def drawImage(
      image: RasterImage,
      x: Double,
      y: Double,
      width: Double,
      height: Double
  ): Unit =
    calls += "drawImage"
    drawnImage = Some(image)
    drawnBounds = Vector(x, y, width, height)

class JavaFxRendererSuite extends munit.FunSuite:

  test("program compilation is deterministic and preserves draw order") {
    val first = Grob.circleUnsafe(
      Point.npcUnsafe(0.25, 0.5),
      ExtentExpr.pointsUnsafe(4.0),
      name = Some(GraphicsName.unsafe("first"))
    )
    val second = Grob.rectUnsafe(
      Point.npcUnsafe(0.75, 0.5),
      Size.npcUnsafe(0.2, 0.3),
      name = Some(GraphicsName.unsafe("second"))
    )
    val raster = RasterImage.solid(RasterDimensions.unsafe(1, 1), Rgba32.unsafe(20, 40, 60))
    val image = Grob.imageUnsafe(
      raster,
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.2, 0.2),
      interpolation = RasterInterpolation.Smooth,
      name = Some(GraphicsName.unsafe("middle"))
    )
    val scene = Scene(Vector(first, image, second))
    val options = JavaFxOptions.unsafe(width = 200, height = 100)

    val left = JavaFxRenderer.compile(scene, options).fold(e => fail(e.message), identity)
    val right = JavaFxRenderer.compile(scene, options).fold(e => fail(e.message), identity)

    assertEquals(left, right)
    assert(left.commands(0).isInstanceOf[JavaFxCommand.Disc])
    left.commands(1) match
      case JavaFxCommand.Image(_, _, _, _, _, interpolation, _, name) =>
        assertEquals(interpolation, RasterInterpolation.Smooth)
        assertEquals(name.map(_.value), Some("middle"))
      case other => fail(s"unexpected middle command: $other")
    assert(left.commands(2).isInstanceOf[JavaFxCommand.Rectangle])
  }

  test("one heterogeneous point-batch command matches per-mark drawing calls") {
    val points = Vector(
      Point.npcUnsafe(0.2, 0.25),
      Point.npcUnsafe(0.4, 0.5),
      Point.npcUnsafe(0.6, 0.75),
      Point.npcUnsafe(0.8, 0.5),
      Point.npcUnsafe(0.5, 0.15)
    )
    val sizes = Vector(3.0, 4.0, 5.0, 6.0, 7.0).map(ExtentExpr.pointsUnsafe)
    val shapes = PointShape.values.toVector
    val params = Vector(
      GraphicParams.unsafe(
        stroke = Some(Rgba.unsafe(120, 20, 30)),
        fill = Some(Rgba.unsafe(240, 180, 80)),
        lineWidth = 1.25
      ),
      GraphicParams.unsafe(
        stroke = Some(Rgba.unsafe(20, 110, 50)),
        fill = Some(Rgba.unsafe(100, 220, 160)),
        lineWidth = 1.5
      ),
      GraphicParams.unsafe(
        stroke = Some(Rgba.unsafe(40, 70, 160)),
        fill = Some(Rgba.unsafe(130, 160, 240)),
        lineWidth = 1.75
      ),
      GraphicParams.unsafe(
        stroke = Some(Rgba.unsafe(90, 40, 130)),
        fill = None,
        lineWidth = 2.0,
        lineType = LineType.Dashed
      ),
      GraphicParams.unsafe(
        stroke = Some(Rgba.unsafe(30, 120, 120)),
        fill = Some(Rgba.unsafe(200, 240, 240)),
        lineWidth = 1.0
      )
    )
    val batch = Grob.pointBatchUnsafe(
      points,
      BatchColumn.Values(sizes),
      BatchColumn.Values(shapes),
      BatchColumn.Values(params)
    )
    val legacy = points.indices
      .map(index =>
        Grob
          .points(
            Vector(points(index)),
            sizes(index),
            shapes(index),
            params(index)
          )
          .fold(error => fail(error.message), identity)
      )
      .toVector
    val options = JavaFxOptions.unsafe(width = 160, height = 100)
    val batchProgram = JavaFxRenderer
      .compile(Scene(Vector(batch)), options)
      .fold(error => fail(error.message), identity)
    val legacyProgram = JavaFxRenderer
      .compile(Scene(legacy), options)
      .fold(error => fail(error.message), identity)

    assertEquals(batchProgram.commands.length, 1)
    batchProgram.commands.head match
      case JavaFxCommand.PointBatch(actualPoints, radii, actualShapes, paints, _) =>
        assertEquals(actualPoints.length, points.length)
        assertEquals(radii.valueCount, Some(points.length))
        assertEquals((0 until points.length).map(actualShapes.valueAt).toVector, shapes)
        assertEquals(
          (0 until points.length).map(index => paints.valueAt(index).lineWidth).toVector,
          params.map(_.lineWidth)
        )
      case other => fail(s"expected one JavaFX point batch, found $other")

    val batchContext = RecordingFxContext()
    val legacyContext = RecordingFxContext()
    JavaFxRenderer.draw(batchProgram, batchContext)
    JavaFxRenderer.draw(legacyProgram, legacyContext)

    assertEquals(batchContext.calls.toVector, legacyContext.calls.toVector)
    assertEquals(batchContext.lastStroke, legacyContext.lastStroke)
    assertEquals(batchContext.lastDashes, legacyContext.lastDashes)
    assertEquals(batchContext.lastCap, legacyContext.lastCap)
    assertEquals(batchContext.lastJoin, legacyContext.lastJoin)
  }

  test("draw interprets the deterministic program against the drawing contract") {
    val context = RecordingFxContext()
    val line = Grob
      .lines(
        Vector(Point.npcUnsafe(0.1, 0.2), Point.npcUnsafe(0.9, 0.8)),
        gp = GraphicParams.unsafe(
          stroke = Some(Rgba.unsafe(10, 20, 30)),
          lineType = LineType.Dashed,
          lineCap = LineCap.Round,
          lineJoin = LineJoin.Bevel
        )
      )
      .fold(e => fail(e.message), identity)
    val program = JavaFxRenderer
      .compile(Scene(Vector(line)), JavaFxOptions.unsafe(width = 100, height = 80))
      .fold(e => fail(e.message), identity)

    JavaFxRenderer.draw(program, context)

    assertEquals(
      context.calls.toVector,
      Vector(
        "save",
        "beginPath",
        "moveTo",
        "lineTo",
        "setStroke",
        "setLineWidth",
        "setLineCap",
        "setLineJoin",
        "setLineDashes",
        "strokePath",
        "restore"
      )
    )
    assertEquals(context.lastDashes, Vector(6.0, 4.0))
    assertEquals(context.lastCap, Some(LineCap.Round))
    assertEquals(context.lastJoin, Some(LineJoin.Bevel))
    assertEquals(context.lastStroke, Some(JavaFxColor(10, 20, 30, 1.0)))
  }

  test("pattern resources are reused across every fill-bearing primitive") {
    val recipe =
      PatternRecipe.crossHatch(30.0, 10.0, 1.5).fold(error => fail(error.message), identity)
    val pattern = PatternPaint(recipe, Rgba.Black, Some(Rgba.White))
    val params = GraphicParams.unsafe(stroke = None, alpha = 0.8).withPatternFill(pattern)
    val grobs = Vector(
      Grob.rectUnsafe(
        Point.npcUnsafe(0.15, 0.25),
        Size.npcUnsafe(0.2, 0.3),
        gp = params,
        name = Some(GraphicsName.unsafe("pattern-rect"))
      ),
      Grob.circleUnsafe(
        Point.npcUnsafe(0.4, 0.25),
        ExtentExpr.npcUnsafe(0.1),
        gp = params,
        name = Some(GraphicsName.unsafe("pattern-disc"))
      ),
      Grob.polygonUnsafe(
        Vector(Point.npcUnsafe(0.55, 0.1), Point.npcUnsafe(0.75, 0.1), Point.npcUnsafe(0.65, 0.4)),
        gp = params,
        name = Some(GraphicsName.unsafe("pattern-polygon"))
      ),
      Grob.compoundPolygonUnsafe(
        Vector(
          Vector(Point.npcUnsafe(0.1, 0.6), Point.npcUnsafe(0.9, 0.6), Point.npcUnsafe(0.5, 0.9))
        ),
        gp = params,
        name = Some(GraphicsName.unsafe("pattern-compound"))
      )
    )
    val program = JavaFxRenderer
      .compile(Scene(grobs), JavaFxOptions.unsafe(width = 100, height = 80))
      .fold(error => fail(error.message), identity)
    val context = RecordingFxContext()

    val cold = JavaFxRenderer.drawProfile(program, context)
    val warm = JavaFxRenderer.drawProfile(program, context)

    assertEquals(cold, JavaFxDrawProfile(4, 3, 1))
    assertEquals(warm, JavaFxDrawProfile(4, 4, 0))
    assertEquals(context.patternResources.toSet, Set(pattern))
    assertEquals(context.lastPattern, Some(pattern))
    assert(context.globalAlphaValues.contains(0.8))
    assertEquals(context.globalAlpha, 1.0)
    val paints = program.commands.collect {
      case JavaFxCommand.Disc(_, _, _, paint, _)         => paint
      case JavaFxCommand.Polyline(_, true, paint, _)     => paint
      case JavaFxCommand.CompoundPolygon(_, paint, _)    => paint
      case JavaFxCommand.Rectangle(_, _, _, _, paint, _) => paint
    }
    assertEquals(paints.length, 4)
    assert(paints.forall(_.fillPattern.contains(pattern)))
    assert(paints.forall(_.opacity == 0.8))
  }

  test("oversized raster patterns fail at the typed compile boundary") {
    val recipe = PatternRecipe
      .parallelRules(RuleOrientation.Vertical, PatternTile.MaxAxisPixels.toDouble + 1.0, 1.0)
      .fold(error => fail(error.message), identity)
    val rect = Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.5, 0.5),
      gp = GraphicParams.unsafe(stroke = None).withPatternFill(PatternPaint(recipe, Rgba.Black))
    )

    assert(JavaFxRenderer.compile(Scene(Vector(rect))).left.toOption.exists {
      case JavaFxRenderError
            .Graphics(GraphicsError.InvalidPatternParameter("raster", "spacing", _, _)) =>
        true
      case _ => false
    })
  }

  test("text runs carry anchor, font, and combined fill opacity") {
    val text = Grob
      .text(
        "label",
        Point.npcUnsafe(0.5, 0.5),
        anchor = Anchor(HJust.Right, VJust.Bottom),
        gp = GraphicParams.unsafe(
          fill = Some(Rgba.unsafe(0, 0, 0, 0.5)),
          alpha = 0.5,
          fontSize = Length.pointsUnsafe(9.0),
          fontFamily = Some("Menlo")
        )
      )
      .fold(e => fail(e.message), identity)
    val context = RecordingFxContext()
    val program = JavaFxRenderer
      .compile(Scene(Vector(text)), JavaFxOptions.unsafe(width = 100, height = 80))
      .fold(e => fail(e.message), identity)

    JavaFxRenderer.draw(program, context)

    assertEquals(context.lastAlign, Some(HJust.Right))
    assertEquals(context.lastBaseline, Some(VJust.Bottom))
    assertEquals(context.lastFont._1, Some("Menlo"))
    assertEqualsDouble(context.lastFont._2, 12.0, 1e-9)
    assertEqualsDouble(context.lastFill.map(_.alpha).getOrElse(Double.NaN), 0.25, 1e-9)
  }

  test("invalid canvas dimensions return typed errors") {
    assertEquals(
      JavaFxOptions(width = 0, height = 20).left.toOption,
      Some(JavaFxRenderError.InvalidCanvasSize(0, 20))
    )
  }

  test("image execution converts top-left ARGB pixels and applies placement policy") {
    val conformanceCase = RendererConformance.imageCase.fold(error => fail(error.message), identity)
    val context = RecordingFxContext()
    val program = JavaFxRenderer
      .compile(conformanceCase.scene, JavaFxOptions.unsafe(width = 100, height = 80))
      .fold(error => fail(error.message), identity)

    JavaFxRenderer.draw(program, context)

    val image = context.drawnImage.getOrElse(fail("no image drawn"))
    val argb = JavaFxRaster.argb(image)
    assertEquals(argb.take(2).toVector, Vector(0xffdc1e1e, 0xa01ec83c))
    assertEquals(context.drawnBounds, Vector(25.0, 20.0, 50.0, 40.0))
    assertEqualsDouble(context.globalAlpha, 0.8, 1e-9)
    assertEquals(context.imageSmoothing, false)
  }

  test("pre-pattern JavaFX paint apply and copy descriptors remain callable") {
    val legacyApply: (
        Option[JavaFxColor],
        Option[JavaFxColor],
        Double,
        JavaFxLineDash,
        LineCap,
        LineJoin,
        Double
    ) => JavaFxPaint = JavaFxPaint.apply
    val paint = legacyApply(
      None,
      Some(JavaFxColor.fromRgba(Rgba.Black)),
      2.0,
      JavaFxLineDash.Solid,
      LineCap.Round,
      LineJoin.Bevel,
      0.75
    )
    val legacyCopy: (
        Option[JavaFxColor],
        Option[JavaFxColor],
        Double,
        JavaFxLineDash,
        LineCap,
        LineJoin,
        Double
    ) => JavaFxPaint = paint.copy

    assertEquals(
      legacyCopy(None, paint.fill, 3.0, paint.dash, paint.lineCap, paint.lineJoin, 0.5).fillPattern,
      None
    )
  }
