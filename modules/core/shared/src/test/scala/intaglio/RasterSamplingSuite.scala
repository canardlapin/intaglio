package intaglio

class RasterSamplingSuite extends munit.FunSuite:
  private def ok[A](value: Either[GraphicsError, A]): A = value.fold(e => fail(e.message), identity)
  private val source = RasterImage.tabulate(RasterDimensions.unsafe(301, 3)) { (x, y) =>
    Rgba32.unsafe(x % 256, x / 256, y * 80)
  }

  test("nearest samples are explicit at fractional placements and render scales one and two") {
    for
      scale <- Vector(1.0, 2.0)
      (x, width) <- Vector(
        (49.96, 286.25),
        (0.49, 301.0),
        (3.30, 150.20),
        (49.96, 79.01),
        (41.7, 287.3),
        (-3.3, 110.2),
        (10.0, 602.0)
      )
    do
      val dx = x * scale; val dw = width * scale
      val prepared = ok(
        RasterSampling.prepare(
          source,
          dx,
          2.3 * scale,
          dw,
          9.7 * scale,
          RasterInterpolation.Nearest
        )
      ).get
      val (image, left, top, w, h) = prepared
      assertEquals(left, math.ceil(dx - 0.5))
      assertEquals(w, math.ceil(dx + dw - 0.5) - left)
      for row <- 0 until image.height; column <- 0 until image.width do
        val expectedX = math.floor((left + column + 0.5 - dx) / dw * 301).toInt
        val expectedY = math.floor((top + row + 0.5 - 2.3 * scale) / (9.7 * scale) * 3).toInt
        assertEquals(image.pixelUnsafe(column, row), source.pixelUnsafe(expectedX, expectedY))
      val grid = ok(RasterSampling.nearestGrid(source.dimensions, dx, 2.3 * scale, dw, 9.7 * scale))
      assertEquals(grid.sourceColumn(grid.left - 1), None)
      assertEquals(grid.sourceColumn(grid.right), None)
      assertEquals(h, image.height.toDouble)
  }

  test("half-pixel ties, empty coverage and one-to-one identity have defined behavior") {
    val a = ok(RasterSampling.nearestGrid(source.dimensions, 0.5, 0.5, 1.0, 1.0))
    assertEquals((a.left, a.top, a.right, a.bottom), (0, 0, 1, 1))
    assertEquals(a.sourceColumn(0), Some(0))
    assertEquals(
      ok(RasterSampling.prepare(source, 0.6, 0, 0.1, 3, RasterInterpolation.Nearest)),
      None
    )
    val identity =
      ok(RasterSampling.prepare(source, 10, 2, 301, 3, RasterInterpolation.Nearest)).get
    assert(identity._1 eq source)
    assertEquals(ok(RasterSampling.prepare(source, 0, 0, 0, 3, RasterInterpolation.Nearest)), None)
    assert(RasterSampling.nearestGrid(source.dimensions, Double.NaN, 0, 3, 3).isLeft)
    assert(RasterSampling.nearestGrid(source.dimensions, 0, 0, -1, 3).isLeft)
    assert(RasterSampling.nearestGrid(source.dimensions, 1e20, 0, 1, 3).isLeft)
    assert(RasterSampling.prepare(source, 0, 0, 4097, 3, RasterInterpolation.Nearest).isLeft)
    assert(RasterSampling.prepare(source, 0, 0, 3, 4097, RasterInterpolation.Nearest).isLeft)
  }

  test("smooth images preserve fractional placement") {
    val prepared =
      ok(RasterSampling.prepare(source, 0.49, 2.3, 286.25, 9.7, RasterInterpolation.Smooth)).get
    assert(prepared._1 eq source)
    assertEquals(prepared, (source, 0.49, 2.3, 286.25, 9.7))
  }
