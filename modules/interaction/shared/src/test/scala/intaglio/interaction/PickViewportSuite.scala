package intaglio.interaction

import intaglio.DevicePoint

class PickViewportSuite extends munit.FunSuite:
  private def ok[A](value: Either[PickingError, A]): A = value.fold(e => fail(e.message), identity)

  test("centered fit excludes letterboxing and includes the content boundary") {
    val view = ok(PickViewport.fit(800, 400, 10, 20, 400, 400))
    assertEquals(ok(view.toDevice(10, 120)), Some(DevicePoint(0, 0)))
    assertEquals(ok(view.toDevice(410, 320)), Some(DevicePoint(800, 400)))
    assertEquals(ok(view.toDevice(210, 220)), Some(DevicePoint(400, 200)))
    assertEquals(ok(view.toDevice(210, 100)), None)
    assertEquals(ok(view.toDevice(411, 220)), None)
    assertEquals(ok(view.tolerance(6)), 12.0)
    val tall = ok(PickViewport.fit(400, 800, 10, 20, 400, 400))
    assertEquals(ok(tall.toDevice(110, 20)), Some(DevicePoint(0, 0)))
    assertEquals(ok(tall.toDevice(100, 220)), None)
  }

  test("DPR changes device positions and tolerance together, preserving displayed hit distance") {
    for dpr <- Vector(1.0, 1.25, 2.0, 3.0); cssScale <- Vector(0.5, 1.0, 2.0) do
      val view = ok(PickViewport.fit(200 * dpr, 100 * dpr, 30, 40, 200 * cssScale, 100 * cssScale))
      val point = ok(view.toDevice(30 + 104 * cssScale, 40 + 50 * cssScale)).get
      val distanceToMark = math.hypot(point.x - 100 * dpr, point.y - 50 * dpr)
      assert(distanceToMark <= ok(view.tolerance(4.01 * cssScale)))
      assert(distanceToMark > ok(view.tolerance(3.99 * cssScale)))
      assertEqualsDouble(point.x, 104 * dpr, 1e-10)
  }

  test("invalid dimensions, coordinates and tolerance fail explicitly") {
    for invalid <- Vector(0.0, -1.0, Double.NaN, Double.PositiveInfinity) do
      assert(PickViewport.fit(invalid, 100, 0, 0, 100, 100).isLeft)
      assert(PickViewport.fit(100, 100, 0, 0, invalid, 100).isLeft)
    assert(PickViewport.fit(100, 100, Double.NaN, 0, 100, 100).isLeft)
    assert(PickViewport.fit(Double.MaxValue, 1, 0, 0, Double.MinPositiveValue, 100).isLeft)
    val view = ok(PickViewport.fit(100, 100, 0, 0, 100, 100))
    assert(view.toDevice(Double.NaN, 0).isLeft)
    assert(view.tolerance(-1).isLeft)
    assert(view.tolerance(Double.PositiveInfinity).isLeft)
  }
