package intaglio.canvas

import scala.collection.mutable.ArrayBuffer
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8ClampedArray
import intaglio.*

class CanvasRendererSuite extends munit.FunSuite:

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
    val options = CanvasOptions.unsafe(width = 200, height = 100)

    val left = CanvasRenderer.compile(scene, options).fold(e => fail(e.message), identity)
    val right = CanvasRenderer.compile(scene, options).fold(e => fail(e.message), identity)

    assertEquals(left, right)
    assert(left.commands(0).isInstanceOf[CanvasCommand.Disc])
    left.commands(1) match
      case CanvasCommand.Image(_, _, _, _, _, interpolation, _, name) =>
        assertEquals(interpolation, RasterInterpolation.Smooth)
        assertEquals(name.map(_.value), Some("middle"))
      case other => fail(s"unexpected middle command: $other")
    assert(left.commands(2).isInstanceOf[CanvasCommand.Rectangle])
  }

  test("draw interprets the deterministic program against a Canvas 2D context") {
    val calls = ArrayBuffer.empty[String]
    def noArgs(label: String): js.Function0[Unit] =
      () =>
        calls += label
        ()
    val context = js.Dynamic
      .literal(
        save = noArgs("save"),
        restore = noArgs("restore"),
        beginPath = noArgs("beginPath"),
        closePath = noArgs("closePath"),
        fill = noArgs("fill"),
        stroke = noArgs("stroke"),
        clip = noArgs("clip"),
        moveTo = ((_: Double, _: Double) => calls += "moveTo"): js.Function2[Double, Double, Unit],
        lineTo = ((_: Double, _: Double) => calls += "lineTo"): js.Function2[Double, Double, Unit],
        rect = ((_: Double, _: Double, _: Double, _: Double) => calls += "rect"): js.Function4[Double, Double, Double, Double, Unit],
        arc = ((_: Double, _: Double, _: Double, _: Double, _: Double, _: Boolean) => calls += "arc"): js.Function6[Double, Double, Double, Double, Double, Boolean, Unit],
        translate = ((_: Double, _: Double) => calls += "translate"): js.Function2[Double, Double, Unit],
        rotate = ((_: Double) => calls += "rotate"): js.Function1[Double, Unit],
        setLineDash = ((_: js.Array[Double]) => calls += "dash"): js.Function1[js.Array[Double], Unit],
        fillText = ((_: String, _: Double, _: Double) => calls += "fillText"): js.Function3[String, Double, Double, Unit],
        strokeStyle = "",
        fillStyle = "",
        globalAlpha = 1.0,
        lineWidth = 1.0,
        lineCap = "",
        lineJoin = "",
        font = "",
        textAlign = "start",
        textBaseline = "alphabetic"
      )
      .asInstanceOf[CanvasRenderingContext2D]
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
      .toOption
      .get
    val program = CanvasRenderer
      .compile(Scene(Vector(line)), CanvasOptions.unsafe(width = 100, height = 80))
      .fold(e => fail(e.message), identity)

    CanvasRenderer.draw(program, context)

    assertEquals(calls.toVector, Vector("save", "beginPath", "moveTo", "lineTo", "dash", "stroke", "restore"))
    assertEquals(context.lineCap, "round")
    assertEquals(context.lineJoin, "bevel")
  }

  test("pattern resources are reused across every fill-bearing primitive") {
    var tileCreates = 0
    given CanvasRasterFactory with
      def create(image: RasterImage, target: CanvasRenderingContext2D): CanvasImageSource =
        tileCreates += 1
        js.Dynamic.literal().asInstanceOf[CanvasImageSource]

    var patternCreates = 0
    var transforms = 0
    var fills = 0
    val patternResource = js.Dynamic
      .literal(
        setTransform = ((_: js.Any) => transforms += 1): js.Function1[js.Any, Unit]
      )
      .asInstanceOf[CanvasPattern]
    val context = js.Dynamic
      .literal(
        save = (() => ()): js.Function0[Unit],
        restore = (() => ()): js.Function0[Unit],
        beginPath = (() => ()): js.Function0[Unit],
        closePath = (() => ()): js.Function0[Unit],
        fill = (() => fills += 1): js.Function0[Unit],
        stroke = (() => ()): js.Function0[Unit],
        moveTo = ((_: Double, _: Double) => ()): js.Function2[Double, Double, Unit],
        lineTo = ((_: Double, _: Double) => ()): js.Function2[Double, Double, Unit],
        rect = ((_: Double, _: Double, _: Double, _: Double) => ()): js.Function4[Double, Double, Double, Double, Unit],
        arc = ((_: Double, _: Double, _: Double, _: Double, _: Double, _: Boolean) => ()): js.Function6[Double, Double, Double, Double, Double, Boolean, Unit],
        setLineDash = ((_: js.Array[Double]) => ()): js.Function1[js.Array[Double], Unit],
        createPattern = ((_: CanvasImageSource, repetition: String) =>
          patternCreates += 1
          assertEquals(repetition, "repeat")
          patternResource): js.Function2[CanvasImageSource, String, CanvasPattern],
        strokeStyle = "",
        fillStyle = "",
        globalAlpha = 1.0,
        lineWidth = 1.0,
        lineCap = "",
        lineJoin = ""
      )
      .asInstanceOf[CanvasRenderingContext2D]
    val recipe = PatternRecipe.crossHatch(30.0, 10.5, 1.5).fold(error => fail(error.message), identity)
    val pattern = PatternPaint(recipe, Rgba.Black, Some(Rgba.White))
    val params = GraphicParams.unsafe(stroke = None, alpha = 0.8).withPatternFill(pattern)
    val grobs = Vector(
      Grob.rectUnsafe(Point.npcUnsafe(0.15, 0.25), Size.npcUnsafe(0.2, 0.3), gp = params),
      Grob.circleUnsafe(Point.npcUnsafe(0.4, 0.25), ExtentExpr.npcUnsafe(0.1), gp = params),
      Grob.polygonUnsafe(
        Vector(Point.npcUnsafe(0.55, 0.1), Point.npcUnsafe(0.75, 0.1), Point.npcUnsafe(0.65, 0.4)),
        gp = params
      ),
      Grob.compoundPolygonUnsafe(
        Vector(Vector(Point.npcUnsafe(0.1, 0.6), Point.npcUnsafe(0.9, 0.6), Point.npcUnsafe(0.5, 0.9))),
        gp = params
      )
    )
    val program = CanvasRenderer
      .compile(Scene(grobs), CanvasOptions.unsafe(width = 100, height = 80))
      .fold(error => fail(error.message), identity)

    val profile = CanvasRenderer.drawChecked(program, context).fold(error => fail(error.message), identity)

    assertEquals(profile, CanvasDrawProfile(0, 0, 0, 0L, 4, 3, 1))
    assertEquals(tileCreates, 1)
    assertEquals(patternCreates, 1)
    assertEquals(transforms, 1)
    assertEquals(fills, 4)
    val paints = program.commands.collect {
      case CanvasCommand.Disc(_, _, _, paint, _)             => paint
      case CanvasCommand.Polyline(_, true, paint, _)         => paint
      case CanvasCommand.CompoundPolygon(_, paint, _)        => paint
      case CanvasCommand.Rectangle(_, _, _, _, paint, _)     => paint
    }
    assertEquals(paints.length, 4)
    assert(paints.forall(_.fillPattern.contains(pattern)))
    assert(paints.forall(_.opacity == 0.8))
  }

  test("native Canvas pattern failure remains typed") {
    given CanvasRasterFactory with
      def create(image: RasterImage, target: CanvasRenderingContext2D): CanvasImageSource =
        js.Dynamic.literal().asInstanceOf[CanvasImageSource]

    val context = js.Dynamic
      .literal(
        save = (() => ()): js.Function0[Unit],
        restore = (() => ()): js.Function0[Unit],
        beginPath = (() => ()): js.Function0[Unit],
        rect = ((_: Double, _: Double, _: Double, _: Double) => ()): js.Function4[Double, Double, Double, Double, Unit],
        createPattern = ((_: CanvasImageSource, _: String) => null.asInstanceOf[CanvasPattern]): js.Function2[CanvasImageSource, String, CanvasPattern],
        fillStyle = "",
        globalAlpha = 1.0
      )
      .asInstanceOf[CanvasRenderingContext2D]
    val recipe = PatternRecipe.stipple(8.0, 2.0).fold(error => fail(error.message), identity)
    val rect = Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.5, 0.5),
      gp = GraphicParams.unsafe(stroke = None).withPatternFill(PatternPaint(recipe, Rgba.Black))
    )
    val program = CanvasRenderer.compile(Scene(Vector(rect))).fold(error => fail(error.message), identity)

    assertEquals(
      CanvasRenderer.drawChecked(program, context).left.toOption,
      Some(CanvasRenderError.PatternResourceFailure("createPattern returned null"))
    )
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

    assert(CanvasRenderer.compile(Scene(Vector(rect))).left.toOption.exists {
      case CanvasRenderError.Graphics(GraphicsError.InvalidPatternParameter("raster", "spacing", _, _)) => true
      case _                                                                                               => false
    })
  }

  test("invalid canvas dimensions return typed errors") {
    assertEquals(
      CanvasOptions(width = 0, height = 20).left.toOption,
      Some(CanvasRenderError.InvalidCanvasSize(0, 20))
    )
  }

  test("image execution uploads top-left RGBA bytes and applies placement policy") {
    val bytes = new Uint8ClampedArray(16)
    val imageData = js.Dynamic.literal(data = bytes).asInstanceOf[CanvasImageData]
    var uploaded = false
    var drawn = Vector.empty[Double]
    val offscreenContext = js.Dynamic
      .literal(
        createImageData = ((_: Int, _: Int) => imageData): js.Function2[Int, Int, CanvasImageData],
        putImageData = ((_: CanvasImageData, _: Double, _: Double) => uploaded = true): js.Function3[CanvasImageData, Double, Double, Unit]
      )
      .asInstanceOf[CanvasRenderingContext2D]
    val offscreen = js.Dynamic
      .literal(
        width = 0,
        height = 0,
        getContext = ((_: String) => offscreenContext): js.Function1[String, CanvasRenderingContext2D]
      )
      .asInstanceOf[CanvasElement]
    val document = js.Dynamic
      .literal(
        createElement = ((_: String) => offscreen): js.Function1[String, CanvasElement]
      )
    val rootCanvas = js.Dynamic.literal(ownerDocument = document).asInstanceOf[CanvasElement]
    val context = js.Dynamic
      .literal(
        canvas = rootCanvas,
        save = (() => ()): js.Function0[Unit],
        restore = (() => ()): js.Function0[Unit],
        drawImage = ((_: CanvasImageSource, x: Double, y: Double, width: Double, height: Double) =>
          drawn = Vector(x, y, width, height)): js.Function5[CanvasImageSource, Double, Double, Double, Double, Unit],
        globalAlpha = 1.0,
        imageSmoothingEnabled = true
      )
      .asInstanceOf[CanvasRenderingContext2D]
    val scene = RendererConformance.imageCase.fold(error => fail(error.message), identity).scene
    val program = CanvasRenderer
      .compile(scene, CanvasOptions.unsafe(width = 100, height = 80))
      .fold(error => fail(error.message), identity)

    CanvasRenderer.draw(program, context)

    assert(uploaded)
    assertEquals(offscreen.width, 2)
    assertEquals(offscreen.height, 2)
    assertEquals((0 until 8).map(idx => bytes(idx).toInt).toVector, Vector(220, 30, 30, 255, 30, 200, 60, 160))
    assertEquals(drawn, Vector(25.0, 20.0, 50.0, 40.0))
    assertEquals(context.globalAlpha, 0.8)
    assertEquals(context.imageSmoothingEnabled, false)
  }

  test("packed Canvas upload is byte-exact with the portable fallback") {
    val dimensions = RasterDimensions.unsafe(3, 2)
    val image = RasterImage.unsafePacked(
      dimensions,
      Vector(
        Rgba32.unsafe(0, 1, 2, 3),
        Rgba32.unsafe(127, 128, 129, 130),
        Rgba32.unsafe(255, 254, 253, 252),
        Rgba32.unsafe(10, 20, 30, 40),
        Rgba32.unsafe(50, 60, 70, 80),
        Rgba32.unsafe(90, 100, 110, 120)
      )
    )
    val fallback = new Uint8ClampedArray(dimensions.pixelCount * 4)
    CanvasRasterFactory.writeRgbaBytes(image, fallback, usePackedLittleEndian = false)

    assertEquals(
      CanvasRasterFactory.rgbaToLittleEndianWord(0x01020304),
      0x04030201
    )
    if CanvasRasterFactory.nativeLittleEndian then
      val packed = new Uint8ClampedArray(dimensions.pixelCount * 4)
      CanvasRasterFactory.writeRgbaBytes(image, packed, usePackedLittleEndian = true)
      assertEquals(
        (0 until fallback.length).map(index => packed(index).toInt).toVector,
        (0 until fallback.length).map(index => fallback(index).toInt).toVector
      )
  }

  test("persistent raster cache avoids repeated browser uploads and reports work") {
    var creates = 0
    given CanvasRasterFactory with
      def create(image: RasterImage, target: CanvasRenderingContext2D): CanvasImageSource =
        creates += 1
        js.Dynamic.literal().asInstanceOf[CanvasImageSource]

    val context = js.Dynamic
      .literal(
        save = (() => ()): js.Function0[Unit],
        restore = (() => ()): js.Function0[Unit],
        drawImage = ((_: CanvasImageSource, _: Double, _: Double, _: Double, _: Double) => ()): js.Function5[CanvasImageSource, Double, Double, Double, Double, Unit],
        globalAlpha = 1.0,
        imageSmoothingEnabled = true
      )
      .asInstanceOf[CanvasRenderingContext2D]
    val raster = RasterImage.solid(RasterDimensions.unsafe(3, 2), Rgba32.unsafe(10, 20, 30))
    val scene = Scene(
      Vector(
        Grob.imageUnsafe(raster, Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(1.0, 1.0))
      )
    )
    val program = CanvasRenderer
      .compile(scene, CanvasOptions.unsafe(120, 80))
      .fold(error => fail(error.message), identity)
    val cache = CanvasRasterCache.empty(1)

    val cold = CanvasRenderer.drawCached(program, context, cache)
    val warm = CanvasRenderer.drawCached(program, context, cache)

    assertEquals(cold, CanvasDrawProfile(1, 0, 1, 24L))
    assertEquals(warm, CanvasDrawProfile(1, 1, 0, 0L))
    assertEqualsDouble(warm.hitRate, 1.0, 0.0)
    assertEquals(creates, 1)
    assertEquals(cache.size, 1)
    assert(CanvasRasterCache.make(-1).isLeft)
  }

  test("pre-pattern Canvas case-class apply and copy descriptors remain callable") {
    val legacyPaintApply: (
        Option[CanvasColor],
        Option[CanvasColor],
        Double,
        CanvasLineDash,
        LineCap,
        LineJoin,
        Double
    ) => CanvasPaint = CanvasPaint.apply
    val paint = legacyPaintApply(
      None,
      Some(CanvasColor.fromRgba(Rgba.Black)),
      2.0,
      CanvasLineDash.Solid,
      LineCap.Round,
      LineJoin.Bevel,
      0.75
    )
    val legacyPaintCopy: (
        Option[CanvasColor],
        Option[CanvasColor],
        Double,
        CanvasLineDash,
        LineCap,
        LineJoin,
        Double
    ) => CanvasPaint = paint.copy
    assertEquals(legacyPaintCopy(None, paint.fill, 3.0, paint.dash, paint.lineCap, paint.lineJoin, 0.5).fillPattern, None)

    val legacyProfileApply: (Int, Int, Int, Long) => CanvasDrawProfile =
      CanvasDrawProfile.apply
    val profile = legacyProfileApply(4, 3, 1, 96L)
    val legacyProfileCopy: (Int, Int, Int, Long) => CanvasDrawProfile = profile.copy
    assertEquals(legacyProfileCopy(5, 4, 1, 128L), CanvasDrawProfile(5, 4, 1, 128L, 0, 0, 0))
    assertEquals((profile.patternRequests, profile.patternCacheHits, profile.patternCacheMisses), (0, 0, 0))
  }
