package intaglio.interaction

import intaglio.*

/** `NamedPickingPlan.cellAt`: the cell of a named image under a device point. Shared, so the JVM
  * and Scala.js runs pin the same answers; the pixel oracle against drawn output is the JavaFX
  * `JavaFxImageCellSuite`.
  */
class ImageCellPickingSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val context = RenderContext.unsafe(width = 200, height = 200)
  private def n(value: String): GraphicsName = GraphicsName.unsafe(value)
  private val fill = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))

  /** 4 columns by 3 rows, every pixel opaque except the top-left one. */
  private val grid = RasterImage.tabulate(RasterDimensions.unsafe(4, 3)) { (x, y) =>
    Rgba32.unsafe(40 * x, 40 * y, 200, if x == 0 && y == 0 then 0 else 255)
  }

  /** The grid drawn over device box (10, 20)-(90, 80): 20-pixel square cells. */
  private def image(
      name: Option[String] = Some("grid"),
      x: Double = 10,
      y: Double = 20,
      width: Double = 80,
      height: Double = 60,
      raster: RasterImage = grid,
      interpolation: RasterInterpolation = RasterInterpolation.Nearest
  ): DeviceElement =
    DeviceElement.Mark(
      DevicePrimitive.Image(raster, x, y, width, height, interpolation, 1.0, name.map(n))
    )

  private def compile(elements: DeviceElement*): NamedPickingPlan =
    ok(
      NamedPicking.fromDeviceScene(
        DeviceScene(200, 200, elements.toVector),
        context,
        PickPolicy.default
      )
    )

  private def cell(plan: NamedPickingPlan, x: Double, y: Double, name: String = "grid") =
    ok(plan.cellAt(n(name), DevicePoint(x, y)))

  private def hitAtZero(plan: NamedPickingPlan, x: Double, y: Double, name: String = "grid") =
    ok(plan.hits(DevicePoint(x, y))).exists(hit => hit.name == n(name))

  test("row 0 is the image's top row and column 0 its left column, as drawn"):
    val plan = compile(image())
    for row <- 0 until 3; column <- 0 until 4 do
      assertEquals(
        cell(plan, 10 + 20 * column + 10, 20 + 20 * row + 10),
        Some(ImageCell(row, column)),
        clues(row, column)
      )

  test("cells are half-open: an interior boundary belongs to the next cell; far edges to the last"):
    val plan = compile(image())
    assertEquals(cell(plan, 10, 20), Some(ImageCell(0, 0)), "top-left corner")
    assertEquals(cell(plan, 30, 40), Some(ImageCell(1, 1)), "interior corner goes right and down")
    assertEquals(cell(plan, 29.999999, 39.999999), Some(ImageCell(0, 0)))
    assertEquals(cell(plan, 50, 30), Some(ImageCell(0, 2)))
    assertEquals(cell(plan, 90, 80), Some(ImageCell(2, 3)), "right and bottom edges")
    assertEquals(cell(plan, 90, 50), Some(ImageCell(1, 3)))
    // Inside the 1e-8 inclusive tolerance every picking boundary has, the edge cell is reported.
    assertEquals(cell(plan, 90 + 5e-9, 80 + 5e-9), Some(ImageCell(2, 3)))
    assertEquals(cell(plan, 10 - 5e-9, 20 - 5e-9), Some(ImageCell(0, 0)))
    assertEquals(cell(plan, 90.001, 50), None)
    assertEquals(cell(plan, 9.999, 50), None)
    assertEquals(cell(plan, 50, 19.999), None)

  test("fractional cells use the same floor rule, so a boundary between pixels is not rounded"):
    // 3 columns over 10 pixels: boundaries at 13.333... and 16.666...
    val thirds = RasterImage.solid(RasterDimensions.unsafe(3, 7), Rgba32.unsafe(1, 2, 3))
    val plan = compile(image(x = 10, y = 0, width = 10, height = 7, raster = thirds))
    assertEquals(cell(plan, 13.3333, 0.5).map(_.column), Some(0))
    assertEquals(cell(plan, 13.3334, 0.5).map(_.column), Some(1))
    assertEquals(cell(plan, 16.6666, 0.5).map(_.column), Some(1))
    assertEquals(cell(plan, 16.6667, 0.5).map(_.column), Some(2))
    // A 7-row image over 7 pixels: one row per pixel, each pixel row [k, k + 1).
    (0 until 7).foreach { k =>
      assertEquals(cell(plan, 11, k.toDouble).map(_.row), Some(k))
      assertEquals(cell(plan, 11, k + 0.999).map(_.row), Some(k))
    }

  test("a cell is reported exactly where hits reports the image at distance zero"):
    val plan = compile(image())
    for x <- 0 to 100 by 3; y <- 10 to 90 by 3 do
      val px = x + 0.25
      val py = y + 0.5
      assertEquals(cell(plan, px, py).nonEmpty, hitAtZero(plan, px, py), clues(px, py))

  test("every pixel counts, as it does for hits, and interpolation does not change the grid"):
    // The top-left pixel is fully transparent; named picking still hits the whole image.
    val nearest = compile(image())
    val smooth = compile(image(interpolation = RasterInterpolation.Smooth))
    assertEquals(cell(nearest, 15, 25), Some(ImageCell(0, 0)))
    assert(hitAtZero(nearest, 15, 25))
    for x <- 11 to 89 by 7; y <- 21 to 79 by 7 do
      assertEquals(cell(smooth, x, y), cell(nearest, x, y), clues(x, y))

  test("a hit within tolerance but outside the image has no cell"):
    val plan = compile(image())
    assertEquals(ok(plan.nearest(DevicePoint(93, 50), 4)).map(_.name), Some(n("grid")))
    assertEquals(cell(plan, 93, 50), None)

  test("clipped-away parts of the image have no cell, as they have no hit"):
    val clip = DeviceClip(30, 30, 40, 30) // keeps x in [30, 70], y in [30, 60]
    val plan = compile(DeviceElement.Group(None, Some(clip), None, Vector(image())))
    assertEquals(cell(plan, 15, 25), None)
    assertEquals(cell(plan, 80, 50), None)
    assertEquals(cell(plan, 50, 70), None)
    assertEquals(cell(plan, 35, 35), Some(ImageCell(0, 1)))
    assertEquals(cell(plan, 69, 59), Some(ImageCell(1, 2)))
    assertEquals(cell(plan, 30, 30), Some(ImageCell(0, 1)), "the clip boundary is inclusive")
    for x <- 0 to 100 by 3; y <- 10 to 90 by 3 do
      val px = x + 0.25
      val py = y + 0.5
      assertEquals(cell(plan, px, py).nonEmpty, hitAtZero(plan, px, py), clues(px, py))

  test("a rotated group turns the cell grid with the image"):
    // 90 degrees about (50, 50): local (x, y) is drawn at (100 - y, x).
    val rotation = DeviceRotation(90, 50, 50)
    val plan = compile(DeviceElement.Group(None, None, Some(rotation), Vector(image())))
    // Local centre of cell (row, column) is (20 + 20 column, 30 + 20 row).
    for row <- 0 until 3; column <- 0 until 4 do
      val local = (20.0 + 20 * column, 30.0 + 20 * row)
      val drawn = (100 - local._2, local._1)
      assertEquals(cell(plan, drawn._1, drawn._2), Some(ImageCell(row, column)), clues(row, column))
    // The unrotated box is now empty.
    assertEquals(cell(plan, 15, 75), None)

  test("an arbitrary rotation agrees with the inverse rotation computed independently"):
    val degrees = 33.0
    val (pivotX, pivotY) = (60.0, 45.0)
    val plan = compile(
      DeviceElement.Group(
        None,
        None,
        Some(DeviceRotation(degrees, pivotX, pivotY)),
        Vector(image())
      )
    )
    // cos and sin of 33 degrees as literals: the fixture uses no platform trigonometry (Scala.js
    // has no StrictMath), and the 1e-6 boundary margin below absorbs the last-bit difference.
    val (c, s) = (0.8386705679454240, 0.5446390350150271)
    var inside = 0
    for x <- 0 to 120 by 2; y <- 0 to 120 by 2 do
      val px = x + 0.37
      val py = y + 0.61
      // Undo a rotation by `degrees` about the pivot (device y down: clockwise on screen).
      val dx = px - pivotX
      val dy = py - pivotY
      val lx = pivotX + c * dx + s * dy
      val ly = pivotY - s * dx + c * dy
      val u = (lx - 10) / 20
      val v = (ly - 20) / 20
      val nearBoundary = math.abs(u - math.rint(u)) < 1e-6 || math.abs(v - math.rint(v)) < 1e-6
      if !nearBoundary then
        val expected =
          Option.when(u >= 0 && u < 4 && v >= 0 && v < 3)(ImageCell(v.toInt, u.toInt))
        if expected.nonEmpty then inside += 1
        assertEquals(cell(plan, px, py), expected, clues(px, py))
    assert(inside > 200, s"the sample must cover the image ($inside)")

  test("only image parts report cells; a name's other grobs and other names do not"):
    val disc = DeviceElement.Mark(DevicePrimitive.Disc(150, 150, 10, fill, None))
    val namedDisc = DeviceElement.Mark(DevicePrimitive.Disc(150, 50, 10, fill, Some(n("dot"))))
    val plan = compile(
      DeviceElement.Group(Some(n("sheet")), None, None, Vector(image(None), disc)),
      namedDisc
    )
    assertEquals(cell(plan, 50, 50, "sheet"), Some(ImageCell(1, 2)))
    assert(hitAtZero(plan, 150, 150, "sheet"))
    assertEquals(cell(plan, 150, 150, "sheet"), None)
    assertEquals(cell(plan, 150, 50, "dot"), None)
    assertEquals(cell(plan, 50, 50, "absent"), None)

  test("where one name paints two images over the point, the later-drawn one reports"):
    val coarse = RasterImage.solid(RasterDimensions.unsafe(2, 2), Rgba32.unsafe(9, 9, 9))
    val plan = compile(
      image(),
      image(x = 50, y = 20, width = 40, height = 60, raster = coarse)
    )
    assertEquals(cell(plan, 55, 25), Some(ImageCell(0, 0)), "the 2 x 2 image drawn on top")
    assertEquals(cell(plan, 85, 75), Some(ImageCell(1, 1)))
    assertEquals(cell(plan, 15, 75), Some(ImageCell(2, 0)), "only the first image is here")

  test("non-finite query points are refused"):
    val plan = compile(image())
    assert(plan.cellAt(n("grid"), DevicePoint(Double.NaN, 1)).isLeft)
    assert(plan.cellAt(n("grid"), DevicePoint(1, Double.PositiveInfinity)).isLeft)

  test("a scene's viewports and device scale place the cells where the image box is resolved"):
    val scaled =
      RenderContext.unsafe(width = 400, height = 300, pixelsPerInch = 192, deviceScale = 2)
    val raster = RasterImage.solid(RasterDimensions.unsafe(5, 4), Rgba32.unsafe(1, 2, 3))
    val inner = Viewport.unsafe(
      origin = Point.npcUnsafe(0.2, 0.1),
      size = Size.npcUnsafe(0.6, 0.7),
      clip = Clip.Off
    )
    val outer = Viewport.unsafe(origin = Point.npcUnsafe(0.1, 0.1), size = Size.npcUnsafe(0.8, 0.8))
    val scene = Scene(
      Vector(
        Grob.group(
          Vector(
            Grob.group(
              Vector(
                Grob.imageUnsafe(
                  raster,
                  Point.npcUnsafe(0.5, 0.5),
                  Size.npcUnsafe(0.9, 0.8),
                  name = Some(n("grid"))
                )
              ),
              viewport = Some(inner)
            )
          ),
          viewport = Some(outer)
        )
      )
    )
    val plan = ok(NamedPicking.compile(scene, scaled))
    val box = ok(DeviceScene.fromScene(scene, scaled)).elements
      .flatMap(images)
      .head
    assert(box.width > 0 && box.height > 0)
    for row <- 0 until 4; column <- 0 until 5 do
      val x = box.x + (column + 0.5) * box.width / 5
      val y = box.y + (row + 0.5) * box.height / 4
      assertEquals(cell(plan, x, y), Some(ImageCell(row, column)), clues(row, column))

  private def images(element: DeviceElement): Vector[DevicePrimitive.Image] = element match
    case DeviceElement.Mark(image: DevicePrimitive.Image) => Vector(image)
    case DeviceElement.Mark(_)                            => Vector.empty
    case DeviceElement.Group(_, _, _, children)           => children.flatMap(images)
    case DeviceElement.Annotated(_, children)             => children.flatMap(images)
