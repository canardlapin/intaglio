package intaglio.java2d

import java.awt.Color
import java.awt.image.BufferedImage
import intaglio.*

class Java2DRendererSuite extends munit.FunSuite:

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
    val options = Java2DOptions.unsafe(width = 200, height = 100)

    val left = Java2DRenderer.compile(scene, options).fold(e => fail(e.message), identity)
    val right = Java2DRenderer.compile(scene, options).fold(e => fail(e.message), identity)

    assertEquals(left, right)
    assert(left.commands(0).isInstanceOf[Java2DCommand.Disc])
    left.commands(1) match
      case Java2DCommand.Image(_, _, _, _, _, interpolation, _, name) =>
        assertEquals(interpolation, RasterInterpolation.Smooth)
        assertEquals(name.map(_.value), Some("middle"))
      case other => fail(s"unexpected middle command: $other")
    assert(left.commands(2).isInstanceOf[Java2DCommand.Rectangle])
  }

  test("real BufferedImage rendering preserves fill color and combined alpha") {
    val rect = Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.5, 0.5),
      gp = GraphicParams.unsafe(
        stroke = None,
        fill = Some(Rgba.unsafe(200, 40, 20, 0.5)),
        alpha = 0.5
      )
    )
    val options = Java2DOptions.unsafe(width = 80, height = 60)
    val program = Java2DRenderer.compile(Scene(Vector(rect)), options).fold(e => fail(e.message), identity)
    val image = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try Java2DRenderer.draw(program, graphics)
    finally graphics.dispose()

    val pixel = new Color(image.getRGB(40, 30), true)
    assert(math.abs(pixel.getRed - 200) <= 1)
    assert(math.abs(pixel.getGreen - 40) <= 1)
    assert(math.abs(pixel.getBlue - 20) <= 1)
    assertEquals(pixel.getAlpha, 64)
    assertEquals(new Color(image.getRGB(0, 0), true).getAlpha, 0)
  }

  test("pattern resources are reused across every fill-bearing primitive") {
    val recipe = PatternRecipe.crossHatch(30.0, 10.0, 1.5).fold(error => fail(error.message), identity)
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
        Vector(Vector(Point.npcUnsafe(0.1, 0.6), Point.npcUnsafe(0.9, 0.6), Point.npcUnsafe(0.5, 0.9))),
        gp = params,
        name = Some(GraphicsName.unsafe("pattern-compound"))
      )
    )
    val options = Java2DOptions.unsafe(width = 100, height = 80)
    val program = Java2DRenderer.compile(Scene(grobs), options).fold(error => fail(error.message), identity)
    val image = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    val profile =
      try Java2DRenderer.drawProfile(program, graphics)
      finally graphics.dispose()

    assertEquals(profile, Java2DDrawProfile(4, 3, 1))
    val paints = program.commands.collect {
      case Java2DCommand.Disc(_, _, _, paint, _)             => paint
      case Java2DCommand.Polyline(_, true, paint, _)         => paint
      case Java2DCommand.CompoundPolygon(_, paint, _)        => paint
      case Java2DCommand.Rectangle(_, _, _, _, paint, _)     => paint
    }
    assertEquals(paints.length, 4)
    assert(paints.forall(_.fillPattern.contains(pattern)))
    assert(paints.forall(_.opacity == 0.8))
  }

  test("monochrome raster fixtures distinguish every pattern recipe from solid fill") {
    val recipes = Vector[PatternRecipe](
      PatternRecipe.angledHatch(30.0, 12.0, 1.5).fold(error => fail(error.message), identity),
      PatternRecipe.crossHatch(30.0, 12.0, 1.5).fold(error => fail(error.message), identity),
      PatternRecipe
        .parallelRules(RuleOrientation.Horizontal, 12.0, 2.0)
        .fold(error => fail(error.message), identity),
      PatternRecipe.stipple(12.0, 2.5).fold(error => fail(error.message), identity)
    )

    def pixels(params: GraphicParams): Vector[Int] =
      val rect = Grob.rectUnsafe(Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(0.75, 0.75), gp = params)
      val options = Java2DOptions.unsafe(width = 64, height = 64)
      val program = Java2DRenderer.compile(Scene(Vector(rect)), options).fold(error => fail(error.message), identity)
      val image = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_ARGB)
      val graphics = image.createGraphics()
      try Java2DRenderer.draw(program, graphics)
      finally graphics.dispose()
      (8 until 56).flatMap(y => (8 until 56).map(x => image.getRGB(x, y))).toVector

    val patterned = recipes.map { recipe =>
      pixels(GraphicParams.unsafe(stroke = None).withPatternFill(PatternPaint(recipe, Rgba.Black, Some(Rgba.White))))
    }
    val solid = pixels(GraphicParams.unsafe(stroke = None, fill = Some(Rgba.White)))

    patterned.foreach(result => assertNotEquals(result, solid))
    patterned.indices.foreach { left =>
      ((left + 1) until patterned.length).foreach(right => assertNotEquals(patterned(left), patterned(right)))
    }
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

    assert(Java2DRenderer.compile(Scene(Vector(rect))).left.toOption.exists {
      case Java2DRenderError.Graphics(GraphicsError.InvalidPatternParameter("raster", "spacing", _, _)) => true
      case _                                                                                               => false
    })
  }

  test("invalid image dimensions return typed errors") {
    assertEquals(
      Java2DOptions(width = 20, height = 0).left.toOption,
      Some(Java2DRenderError.InvalidImageSize(20, 0))
    )
  }

  test("raster rendering preserves top-left pixel order, source alpha, and grob alpha") {
    val scene = RendererConformance.imageCase.fold(error => fail(error.message), identity).scene
    val options = Java2DOptions.unsafe(width = 100, height = 80)
    val program = Java2DRenderer.compile(scene, options).fold(error => fail(error.message), identity)
    val target = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try Java2DRenderer.draw(program, graphics)
    finally graphics.dispose()

    def pixel(x: Int, y: Int): Color =
      new Color(target.getRGB(x, y), true)

    val topLeft = pixel(30, 25)
    assert(math.abs(topLeft.getRed - 220) <= 1)
    assert(math.abs(topLeft.getGreen - 30) <= 1)
    assert(math.abs(topLeft.getBlue - 30) <= 1)
    assertEquals(topLeft.getAlpha, 204)

    val topRight = pixel(60, 25)
    assert(math.abs(topRight.getRed - 30) <= 1)
    assert(math.abs(topRight.getGreen - 200) <= 1)
    assert(math.abs(topRight.getBlue - 60) <= 1)
    assertEquals(topRight.getAlpha, 128)

    val bottomLeft = pixel(30, 55)
    assert(math.abs(bottomLeft.getRed - 40) <= 1)
    assert(math.abs(bottomLeft.getGreen - 80) <= 1)
    assert(math.abs(bottomLeft.getBlue - 220) <= 1)
    assertEquals(bottomLeft.getAlpha, 204)

    assertEquals(pixel(60, 55).getAlpha, 0)
    assertEquals(pixel(5, 5).getAlpha, 0)
  }

  test("later vector marks paint over earlier raster images") {
    val raster = RasterImage.solid(RasterDimensions.unsafe(1, 1), Rgba32.unsafe(220, 30, 30))
    val image = Grob.imageUnsafe(raster, Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(0.8, 0.8))
    val overlay = Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.2, 0.2),
      gp = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.unsafe(20, 40, 220)))
    )
    val options = Java2DOptions.unsafe(width = 60, height = 60)
    val program = Java2DRenderer.compile(Scene(Vector(image, overlay)), options).fold(error => fail(error.message), identity)
    val target = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try Java2DRenderer.draw(program, graphics)
    finally graphics.dispose()

    assertEquals(new Color(target.getRGB(30, 30), true), new Color(20, 40, 220, 255))
    assertEquals(new Color(target.getRGB(10, 10), true), new Color(220, 30, 30, 255))
  }
