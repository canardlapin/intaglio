package intaglio.interaction

import PickGeometry.*

class PickGeometrySuite extends munit.FunSuite:
  private def close(actual: Double, expected: Double, tolerance: Double = 1e-7): Unit =
    assertEqualsDouble(actual, expected, tolerance)
  private def point(actual: P, expected: P): Unit =
    close(actual.x, expected.x)
    close(actual.y, expected.y)
  private def region(value: Region, clips: Region*): Clipped = new Clipped(Vector(value) ++ clips)

  test("segment intersections include endpoints and overlapping collinear intervals") {
    val horizontal = Segment(P(-5, 0), P(5, 0))
    assertEquals(horizontal.intersections(Segment(P(0, -2), P(0, 2))), Vector(P(0, 0)))
    assertEquals(horizontal.intersections(Segment(P(5, 0), P(9, 3))), Vector(P(5, 0)))
    assertEquals(horizontal.intersections(Segment(P(2, 0), P(8, 0))).toSet, Set(P(2, 0), P(5, 0)))
    assertEquals(horizontal.intersections(Segment(P(-5, 1), P(5, 1))), Vector.empty)
    assertEquals(horizontal.intersections(Segment(P(2, 0), P(2, 0))), Vector(P(2, 0)))
  }

  test("arc intersections and closest points handle tangency and angle wrap without tessellation") {
    val circle = Arc(P(0, 0), 5, 0, math.Pi * 2)
    val chord = circle.intersections(Segment(P(-10, 4), P(10, 4))).sortBy(_.x)
    point(chord(0), P(-3, 4))
    point(chord(1), P(3, 4))
    val tangent = circle.intersections(Segment(P(-10, 5), P(10, 5)))
    assertEquals(tangent.size, 1)
    point(tangent.head, P(0, 5))
    val rightHalf = Arc(P(0, 0), 5, math.Pi * 1.5, math.Pi)
    point(rightHalf.nearest(P(10, 0)), P(5, 0))
    close(rightHalf.distance(P(-10, 0)), math.sqrt(125))
    val intersections = rightHalf.intersections(Segment(P(-10, 0), P(10, 0)))
    assertEquals(intersections.size, 1)
    point(intersections.head, P(5, 0))
  }

  test("unclipped disc distances agree with the closed-form radial oracle") {
    val disc = region(Region.disc(P(3, -7), 5))
    for x <- -12 to 15; y <- -20 to 8 do
      val expected = math.max(0, math.hypot(x - 3, y + 7) - 5)
      close(disc.distance(P(x, y)), expected)
  }

  test("clipped discs use visible arcs and clip edges when the uncut closest point is hidden") {
    val clipped = region(Region.disc(P(0, 0), 5), Region.rectangle(Box(3, -10, 10, 10)))
    close(clipped.distance(P(0, 0)), 3)
    close(clipped.distance(P(10, 0)), 5)
    close(clipped.distance(P(0, 10)), math.sqrt(45))
    close(clipped.distance(P(-7, 8)), math.sqrt(116))
    close(clipped.distance(P(7, 8)), math.sqrt(113) - 5)
    assert(clipped.edges.exists(_.isInstanceOf[Arc]))
    assert(clipped.edges.exists(_.isInstanceOf[Segment]))
    assert(clipped.contains(P(3, 4)))
    assert(!clipped.contains(P(3, 4.01)))
  }

  test("two clips produce corner distances and empty intersections explicitly") {
    val circle = Region.disc(P(0, 0), 5)
    val quadrant =
      region(circle, Region.rectangle(Box(0, -10, 10, 10)), Region.rectangle(Box(-10, 0, 10, 10)))
    close(quadrant.distance(P(-3, -4)), 5)
    close(quadrant.distance(P(0, 10)), 5)
    val empty = region(circle, Region.rectangle(Box(6, -1, 8, 1)))
    assert(!empty.nonEmpty)
    assertEquals(empty.distance(P(0, 0)), Double.PositiveInfinity)
    assertEquals(empty.bounds, None)
  }

  test("circle lenses and isolated tangencies preserve exact circular boundaries") {
    val lens = region(Region.disc(P(-3, 0), 5), Region.disc(P(3, 0), 5))
    close(lens.distance(P(0, 10)), 6)
    close(lens.distance(P(10, 0)), 8)
    close(lens.distance(P(0, 0)), 0)
    val tangent = region(Region.disc(P(-5, 0), 5), Region.disc(P(5, 0), 5))
    assert(tangent.nonEmpty)
    close(tangent.distance(P(3, 4)), 5)
    assert(!region(Region.disc(P(-5.001, 0), 5), Region.disc(P(5, 0), 5)).nonEmpty)
  }

  test("intersection of rectangles agrees with an independent clamped-distance oracle") {
    val clipped = region(Region.rectangle(Box(-4, -3, 10, 8)), Region.rectangle(Box(1, -9, 5, 4)))
    for x <- -10 to 12; y <- -12 to 12 do
      val dx = math.max(math.max(1.0 - x, 0), x - 5.0)
      val dy = math.max(math.max(-3.0 - y, 0), y - 4.0)
      close(clipped.distance(P(x, y)), math.hypot(dx, dy))
    assertEquals(clipped.bounds, Some(Box(1, -3, 5, 4)))
  }

  test("even-odd holes retain boundary hits but exclude their interior regardless of orientation") {
    val outer = Vector(P(-5, -5), P(5, -5), P(5, 5), P(-5, 5))
    val hole = Vector(P(-2, -2), P(2, -2), P(2, 2), P(-2, 2))
    for rings <- Vector(
        Vector(outer, hole),
        Vector(outer.reverse, hole),
        Vector(hole.reverse, outer)
      )
    do
      val shape = region(Region.polygon(rings))
      close(shape.distance(P(0, 0)), 2)
      assert(shape.contains(P(2, 0)))
      assert(!shape.contains(P(1, 0)))
      assert(!shape.intersects(Region.rectangle(Box(-1, -1, 1, 1))))
      assert(shape.intersects(Region.rectangle(Box(2, -1, 3, 1))))
    val ring = region(Region.annulus(P(0, 0), 2, 5))
    close(ring.distance(P(0, 0)), 2)
    close(ring.distance(P(6, 0)), 1)
    assert(!ring.intersects(Region.disc(P(0, 0), 1)))
    assert(ring.intersects(Region.disc(P(1, 0), 1)))
  }

  test("rounded rectangles distinguish the curved corner from its enclosing box") {
    val rounded = region(Region.roundedRectangle(Box(0, 0, 10, 6), 2))
    assert(!rounded.contains(P(0, 0)))
    close(rounded.distance(P(0, 0)), math.sqrt(8) - 2)
    close(rounded.distance(P(-1, 3)), 1)
    assert(rounded.contains(P(2, 0)))
    assert(rounded.containedBy(Region.rectangle(Box(0, 0, 10, 6))))
    assert(!rounded.intersects(Region.rectangle(Box(0, 0, 0.1, 0.1))))
  }

  test("containment checks the region interior and query holes as well as outer boundaries") {
    val disc = region(Region.disc(P(0, 0), 3))
    assert(disc.containedBy(Region.disc(P(0, 0), 3)))
    assert(disc.containedBy(Region.rectangle(Box(-3, -3, 3, 3))))
    assert(!disc.containedBy(Region.annulus(P(0, 0), 1, 5)))
    assert(!disc.containedBy(Region.rectangle(Box(-3, -3, 2.9, 3))))
    val u = Region.polygon(
      Vector(Vector(P(0, 0), P(10, 0), P(10, 10), P(7, 10), P(7, 3), P(3, 3), P(3, 10), P(0, 10)))
    )
    val bar = region(Region.rectangle(Box(1, 5, 9, 6)))
    assert(bar.intersects(u))
    assert(!bar.containedBy(u), "vertices in both arms do not imply containment across the gap")
    assert(region(Region.rectangle(Box(1, 5, 2, 6))).containedBy(u))
  }

  test("rigid transformations preserve clipped distance and area relations") {
    val source = Region.disc(P(2, 3), 5)
    val clip = Region.rectangle(Box(3, -2, 8, 8))
    val original = region(source, clip)
    for angle <- Vector(-170.0, -90, -17, 0, 45, 90, 179); x <- -5 to 10 by 3; y <- -5 to 10 by 3 do
      val transform = Rigid.rotation(angle, P(7, -3)).compose(Rigid(tx = 12, ty = -19))
      val moved = region(source.transform(transform), clip.transform(transform))
      val p = P(x, y)
      point(transform.inverse(transform(p)), p)
      close(moved.distance(transform(p)), original.distance(p), 1e-6)
      assert(moved.containedBy(Region.rectangle(Box(-20, -20, 20, 20)).transform(transform)))
  }

  test("sampled boundary oracle bounds analytic nearest-distance error independently") {
    val radius = 10.0
    val cut = 3.0
    val top = math.sqrt(radius * radius - cut * cut)
    val samples = 8192
    val circleSamples = (0 until samples)
      .map { i =>
        val angle = i * 2 * math.Pi / samples
        P(radius * math.cos(angle), radius * math.sin(angle))
      }
      .filter(_.x >= cut)
    val lineSamples = (0 to samples).map(i => P(cut, -top + 2 * top * i / samples))
    val cloud = circleSamples ++ lineSamples
    val shape = region(Region.disc(P(0, 0), radius), Region.rectangle(Box(cut, -20, 20, 20)))
    for i <- 0 until 80 do
      val p = P((i * 37 % 53) - 26, (i * 19 % 47) - 23)
      val exactInside = p.x >= cut && p.x * p.x + p.y * p.y <= radius * radius
      val sampled =
        if exactInside then 0.0 else cloud.iterator.map(q => math.hypot(q.x - p.x, q.y - p.y)).min
      val actual = shape.distance(p)
      assert(actual <= sampled + 1e-7, clues(p, actual, sampled))
      assert(sampled - actual <= 2 * math.Pi * radius / samples + 1e-7, clues(p, actual, sampled))
  }
