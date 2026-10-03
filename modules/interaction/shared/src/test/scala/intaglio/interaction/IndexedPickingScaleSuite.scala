package intaglio.interaction

import intaglio.*
import PickGeometry.*

/** The indexed paths of a picking plan against independent oracles at the 10,000-target scale the
  * performance fixtures use: the grid against the exhaustive scan, the clip fast path against the
  * boundary computed over every region, identity lookups against the materialized navigation
  * geometry, and directional navigation against a full sort. Overlap, clipping and rotation are all
  * present, and every plan is rebuilt rather than mutated, so an index cannot outlive its geometry.
  */
class IndexedPickingScaleSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val keys = ok(KeySpace("rows", KeyCodec.integer))
  private val context = RenderContext.unsafe(width = 400, height = 400)
  private val fill = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
  private val hollow = GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = None, lineWidth = 1.5)
  private val stroked = GraphicParams.unsafe(lineWidth = 2)

  private def group(size: Int, name: String, revision: String = "one"): TargetGroup[Int] =
    val plot = ok(
      Plot(Vector.tabulate(size)(identity)).addLayer(Layer.point[Int](_.toDouble, _.toDouble))
    )
    ok(
      InteractionCompiler.compile(
        plot,
        keys,
        ok(DataRevision(revision)),
        SemanticId.unsafe(name),
        ok(PlanRevision(revision))
      )(identity)
    ).groups.head

  private def route(group: TargetGroup[Int], marks: DevicePrimitive*): DeviceElement =
    DeviceElement.Annotated(
      GrobMeta(data = Vector(InteractionCompiler.targetAttribute -> group.name.value)),
      marks.toVector.map(DeviceElement.Mark(_))
    )

  /** SplitMix64, so the scene is the same on the JVM and Scala.js. */
  private final class Mix(seed: Long):
    private var state = seed
    def next(): Double =
      state += 0x9e3779b97f4a7c15L
      var z = state
      z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
      z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
      z = z ^ (z >>> 31)
      (z >>> 11).toDouble / (1L << 53).toDouble

  private def batch(points: Vector[DevicePoint], mix: Mix): DevicePrimitive.PointBatch =
    val shapes = PointShape.values.toVector
    DevicePrimitive.PointBatch(
      points,
      BatchColumn.Values(points.indices.toVector.map(_ => 1.0 + 4.0 * mix.next())),
      BatchColumn.Values(points.indices.toVector.map(i => shapes(i % shapes.size))),
      // A cross has no inside, so it is always stroked; a quarter of the other glyphs are hollow.
      BatchColumn.Values(
        points.indices.toVector.map(i =>
          if i % 4 == 0 || shapes(i % shapes.size) == PointShape.Cross then hollow else fill
        )
      ),
      None
    )

  /** 10,000 targets: 6,000 free points of which every 25th repeats its predecessor's position,
    * 2,000 points in a clip that cuts through many of them, 1,980 rotated points in a rotated clip,
    * and twenty scene-spanning strokes and hollow polygons. `shift` moves every free point.
    */
  private def scene(shift: Double = 0, revision: String = "one") =
    val mix = Mix(11)
    val free = group(6000, "free", revision)
    val clipped = group(2000, "clipped", revision)
    val rotated = group(1980, "rotated", revision)
    val strokes = Vector.tabulate(20)(i => group(1, s"stroke-$i", revision))
    var previous = DevicePoint(0, 0)
    val freePoints = Vector.tabulate(6000) { i =>
      val p =
        if i % 25 == 0 && i > 0 then previous
        else DevicePoint(400 * mix.next() + shift, 400 * mix.next())
      previous = p
      p
    }
    val clippedPoints =
      Vector.tabulate(2000)(_ => DevicePoint(100 + 200 * mix.next(), 100 + 200 * mix.next()))
    val rotatedPoints =
      Vector.tabulate(1980)(_ => DevicePoint(150 + 140 * mix.next(), 40 + 140 * mix.next()))
    val strokeMarks = strokes.zipWithIndex.map { (g, i) =>
      val y0 = 400 * mix.next()
      val y1 = 400 * mix.next()
      if i % 2 == 0 then
        route(
          g,
          DevicePrimitive
            .Polyline(Vector(DevicePoint(-10, y0), DevicePoint(410, y1)), false, stroked, None)
        )
      else
        val x = 30 + 340 * mix.next()
        route(
          g,
          DevicePrimitive.Polyline(
            Vector(DevicePoint(x, y0), DevicePoint(x + 40, y0 + 10), DevicePoint(x + 15, y0 + 50)),
            true,
            hollow,
            None
          )
        )
    }
    val elements = Vector(
      route(free, batch(freePoints, mix)),
      DeviceElement.Group(
        None,
        Some(DeviceClip(130, 120, 150, 130)),
        None,
        Vector(route(clipped, batch(clippedPoints, mix)))
      ),
      DeviceElement.Group(
        None,
        Some(DeviceClip(160, 50, 110, 110)),
        Some(DeviceRotation(27, 220, 110)),
        Vector(route(rotated, batch(rotatedPoints, mix)))
      )
    ) ++ strokeMarks
    val groups = Vector(free, clipped, rotated) ++ strokes
    (
      ok(
        Picking
          .fromDeviceScene(DeviceScene(400, 400, elements), groups, context, PickPolicy.default)
      ),
      groups
    )

  private lazy val (plan, groups) = scene()

  test("a 10,000-target plan answers point queries exactly as the exhaustive scan") {
    assertEquals(plan.targetCount, 10000)
    val mix = Mix(5)
    val points =
      Vector.tabulate(90)(_ => DevicePoint(-20 + 440 * mix.next(), -20 + 440 * mix.next()))
    // Coincident marks and the clip and rotation boundaries are deliberately among the probes.
    val anchors = plan.prepareNavigation().targets
    val probes = points ++ Vector(0, 25, 50, 6001, 7999, 8500, 9999).map(i =>
      anchors(i % anchors.size).anchor
    ) ++
      Vector(DevicePoint(130, 120), DevicePoint(280, 250), DevicePoint(220, 110))
    var nonEmpty = 0
    for
      point <- probes
      tolerance <- Vector(0.0, 3.0, 12.0)
    do
      val indexed = ok(plan.hits(point, tolerance))
      assertEquals(indexed, ok(plan.hitsExhaustive(point, tolerance)), clues(point, tolerance))
      if indexed.nonEmpty then nonEmpty += 1
    assert(nonEmpty > 100, s"the probes must hit marks, not empty space: $nonEmpty")
  }

  test("coincident marks with distinct keys are all reported, latest drawn first") {
    val anchors = plan.prepareNavigation().targets
    // Free point 25 repeats point 24's position and is drawn after it.
    val at = anchors.find(_.target.id.ordinal == 25).get.anchor
    val hits = ok(plan.hits(at, 0)).map(_.target.id)
    val pair = hits.filter(id => id.plan.value == "free" && (id.ordinal == 24 || id.ordinal == 25))
    assertEquals(pair.map(_.ordinal), Vector(25, 24))
  }

  test("a 10,000-target plan answers area queries exactly as the exhaustive scan") {
    val areas = Vector(
      ok(PickArea.rectangle(10, 10, 60, 60)),
      ok(PickArea.rectangle(120, 110, 290, 260)),
      ok(PickArea.rectangle(150, 40, 300, 190)),
      ok(PickArea.lasso(Vector(DevicePoint(200, 90), DevicePoint(330, 300), DevicePoint(90, 330))))
    )
    for
      area <- areas
      rule <- Vector(AreaRule.CenterInside, AreaRule.FullyContained, AreaRule.Intersecting)
    do
      val indexed = plan.select(area, rule).map(_.id)
      assertEquals(indexed, plan.selectExhaustive(area, rule).map(_.id), clues(rule.toString))
  }

  test("identity lookups return the materialized geometry of every target") {
    val navigation = plan.prepareNavigation()
    // Marks wholly outside their clip have no visible geometry and are not navigable.
    assert(
      navigation.targets.size > 7000 && navigation.targets.size < 10000,
      navigation.targets.size
    )
    navigation.targets.foreach { g =>
      assertEquals(ok(plan.geometry(g.target.id)), Some(g))
      assertEquals(navigation.lookup(g.target.id), Some(g))
    }
    val ids = groups.flatMap(g => (0 until g.size).map(i => ok(g.at(i)).id))
    assertEquals(ids.size, 10000)
    ids.foreach(id => assertEquals(ok(plan.geometry(id)), navigation.lookup(id), id))
    // An identity from another revision is refused, never resolved by ordinal.
    val stale = ok(group(6000, "free", "two").at(3)).id
    assertEquals(plan.geometry(stale), Left(PickingError.UnknownTarget(stale)))
    assertEquals(plan.outline(stale, 2), Left(PickingError.UnknownTarget(stale)))
    assertEquals(navigation.lookup(stale), None)
  }

  test("directional navigation picks what a full sort of the candidates would") {
    val navigation = plan.prepareNavigation()
    val targets = navigation.targets
    def sorted(from: TargetGeometry[Int], direction: NavigationDirection): Option[VisualTargetId] =
      def delta(c: TargetGeometry[Int]) = (c.anchor.x - from.anchor.x, c.anchor.y - from.anchor.y)
      val coincident = targets.filter { c =>
        val (dx, dy) = delta(c)
        math.abs(dx) <= epsilon && math.abs(dy) <= epsilon
      }
      if coincident.size > 1 then None
      else
        targets
          .filter { c =>
            val (dx, dy) = delta(c)
            direction match
              case NavigationDirection.Left  => dx < -epsilon
              case NavigationDirection.Right => dx > epsilon
              case NavigationDirection.Up    => dy < -epsilon
              case NavigationDirection.Down  => dy > epsilon
          }
          .sortBy { c =>
            val (dx, dy) = delta(c)
            val id = c.target.id
            (dx * dx + dy * dy, id.plan.value, id.revision.value, id.scope.value, id.ordinal)
          }
          .headOption
          .map(_.target.id)
    var compared = 0
    for
      i <- Vector(1, 7, 333, 4999, 6123, 8100, 9990)
      direction <- NavigationDirection.values
    do
      val from = targets(i % targets.size)
      sorted(from, direction).foreach { expected =>
        compared += 1
        assertEquals(
          ok(navigation.nearest(from.target.id, direction)).map(_.target.id),
          Some(expected)
        )
      }
    assert(compared >= 20, compared)
  }

  test("a rebuilt plan indexes its own geometry: moved marks are found where they now are") {
    val moved = scene(shift = 7.5)._1
    val before = plan.prepareNavigation().targets.find(_.target.id.ordinal == 3).get
    val after = moved
      .prepareNavigation()
      .targets
      .find(t => t.target.id.ordinal == 3 && t.target.id.plan == before.target.id.plan)
      .get
    assertEqualsDouble(after.anchor.x, before.anchor.x + 7.5, 1e-9)
    val mix = Mix(9)
    for _ <- 0 until 60 do
      val point = DevicePoint(440 * mix.next() - 20, 400 * mix.next())
      assertEquals(ok(moved.hits(point, 4)), ok(moved.hitsExhaustive(point, 4)), point)
    // A new data revision yields new identities: the old plan's ids are foreign to the new plan.
    val revised = scene(revision = "two")._1
    val old = plan.prepareNavigation().targets.head.target.id
    assertEquals(revised.geometry(old), Left(PickingError.UnknownTarget(old)))
  }

  /** Regions of every kind against clip rectangles that enclose them with and without the margin,
    * touch them, cut them or miss them, translated and rotated.
    */
  test("the clip fast path yields exactly the boundary computed over every region") {
    val mix = Mix(3)
    def around(c: P, r: Double, kind: Int): Region = kind % 6 match
      case 0 => Region.disc(c, r)
      case 1 => Region.rectangle(Box(c.x - r, c.y - r * 0.6, c.x + r, c.y + r * 0.6))
      case 2 => Region.roundedRectangle(Box(c.x - r, c.y - r, c.x + r, c.y + r), r / 3)
      case 3 =>
        Region.polygon(Vector(Vector(P(c.x - r, c.y + r), P(c.x, c.y - r), P(c.x + r, c.y + r))))
      case 4 => Region.annulus(c, r / 2, r)
      case _ => Region.sector(c, r, 0.3, 4.0)
    var fast = 0
    var apart = 0
    for i <- 0 until 3000 do
      val c = P(50 + 100 * mix.next(), 50 + 100 * mix.next())
      val r = 0.5 + 10 * mix.next()
      val source = around(c, r, i)
      val box = source.bounds.get
      // Gaps straddle the margin: well inside, just inside or outside it, touching, and cutting;
      // every seventh clip instead lies beside the source at a distance that straddles it too.
      val gap =
        Vector(5.0, 2e-6, 5e-7, 0.0, -0.3 * r, -3 * r)(i % 6) * (if i % 12 < 6 then 1 else 0.5)
      val clip =
        if i % 7 == 6 then
          val apart = Vector(4.0, 2e-6, 5e-7, 0.0, -1e-3)((i / 7) % 5)
          val width = box.right - box.left
          Region.rectangle(
            Box(box.right + apart, box.top - 1, box.right + apart + width, box.bottom + 1)
          )
        else Region.rectangle(Box(box.left - gap, box.top - gap, box.right + gap, box.bottom + gap))
      val moved = Rigid(1, 0, 1e-3 * (i % 3), -2e-3 * (i % 2))
      val turned = Rigid.rotation(1.0 + i % 40, c)
      val second = i % 3 match
        case 0 => clip.transform(moved)
        case 1 => clip.transform(turned)
        case _ => Region.rectangle(Box(0, 0, 200, 200))
      val transformedSource =
        if i % 5 == 0 then source.transform(Rigid.rotation(13, P(100, 100))) else source
      val regions = Vector(transformedSource, clip, second)
      val clipped = new Clipped(regions)
      val (edges, points) = Clipped.boundariesOf(regions, p => regions.forall(_.contains(p)))
      assertEquals(clipped.edges, edges, clues(i, gap))
      assertEquals(clipped.points, points, clues(i, gap))
      assertEquals(clipped.bounds, Box.enclosing(edges.flatMap(_.extrema) ++ points), clues(i))
      val area =
        Region.rectangle(Box(c.x - 2 * r * mix.next(), c.y - r, c.x + r, c.y + 3 * r * mix.next()))
      val withArea = regions :+ area
      assertEquals(
        clipped.intersects(area),
        Clipped.boundariesOf(withArea, p => withArea.forall(_.contains(p))) match
          case (e, p) => e.nonEmpty || p.nonEmpty
        ,
        clues(i)
      )
      if regions.tail.exists(_.encloses(transformedSource.bounds.get, Clipped.enclosureMargin)) then
        fast += 1
      if regions.tail.exists(_.excludes(transformedSource.bounds.get, Clipped.enclosureMargin)) then
        apart += 1
    assert(fast > 1000, s"the enclosing fast path must be exercised: $fast")
    assert(apart > 100, s"the disjoint fast path must be exercised: $apart")
  }

  test("only an axis-aligned rectangle claims to enclose a box") {
    val box = Box(10, 10, 20, 20)
    val clip = Region.rectangle(Box(0, 0, 30, 30))
    assert(clip.encloses(box, 1e-6))
    assert(!clip.encloses(Box(10, 10, 30, 20), 1e-6), "touching is not enclosing")
    assert(clip.transform(Rigid(1, 0, 2, 3)).encloses(box, 1e-6))
    assert(!clip.transform(Rigid(1, 0, 25, 0)).encloses(box, 1e-6))
    assert(!clip.transform(Rigid.rotation(1, P(15, 15))).encloses(box, 1e-6))
    assert(!Region.disc(P(15, 15), 100).encloses(box, 1e-6))
    assert(
      !Region.polygon(Vector(Vector(P(0, 0), P(30, 0), P(30, 30), P(0, 30)))).encloses(box, 1e-6)
    )
    assert(Region.rectangle(Box(21, 0, 30, 30)).excludes(box, 1e-6))
    assert(!Region.rectangle(Box(20, 0, 30, 30)).excludes(box, 1e-6), "touching is not apart")
    assert(!clip.transform(Rigid.rotation(1, P(100, 100))).excludes(box, 1e-6))
    assert(!Region.disc(P(100, 100), 1).excludes(box, 1e-6))
  }

  final case class Obs(x: Double, y: Double, condition: String, site: String)

  test("part picking that skips data marks finds exactly the parts the full named plan finds") {
    val mix = Mix(21)
    val rows = Vector.tabulate(2000) { i =>
      Obs(
        10 * mix.next(),
        10 * mix.next(),
        if i % 2 == 0 then "control" else "treated",
        if i % 3 == 0 then "north" else "south"
      )
    }
    val partContext = RenderContext.unsafe(640, 480)
    val trained = ok(
      plot(rows)
        .aes(_.x, _.y)
        .scaleColorDiscrete(_.condition, levels = Vector("control", "treated"), name = "condition")
        .geomPoint()
        .hline(5.0)
        .facetWrap(_.site)
        .title("Scores by site")
        .axisTitles("Session", "Score")
        .resolve(partContext)
    )
    val device = ok(DeviceScene.fromScene(trained.scene, partContext))
    val parts = PlotParts.of(trained)
    val picking = ok(PartPicking.fromResolved(trained, device, partContext))
    // The oracle: every named target, then the first hit some part claims.
    val full = ok(NamedPicking.fromResolved(device, partContext))
    val byName = parts.reverse.flatMap(part => part.names.map(_ -> part)).toMap
    assert(full.targetCount > parts.flatMap(_.names).distinct.size, "data marks are named targets")
    var found = 0
    for
      x <- 0 to 640 by 6
      y <- 0 to 480 by 6
      tolerance <- Vector(0.0, 2.0)
    do
      val point = DevicePoint(x + 0.3, y + 0.7)
      val expected =
        ok(full.hits(point, tolerance)).view.flatMap(hit => byName.get(hit.name)).headOption
      assertEquals(ok(picking.at(point, tolerance)), expected, clues(point, tolerance))
      if expected.nonEmpty then found += 1
    assert(found > 50, found)
    parts.foreach { part =>
      val rings = part.names.flatMap(name => ok(full.outline(name, 3)).toVector.flatMap(_.rings))
      assertEquals(
        ok(picking.outline(part, 3)),
        Option.when(rings.nonEmpty)(TargetOutline(rings)),
        part
      )
    }
  }

  test("positions resolve interleaved, reversed and repeated series, and refuse foreign ids") {
    val a = group(50, "a")
    val b = group(30, "b")
    val c = group(30, "b", "two")
    val ids =
      (0 until 30).flatMap(i => Vector(ok(a.at(49 - i)).id, ok(b.at(i)).id)) ++
        (0 until 20).map(i => ok(a.at(i)).id) ++ (0 until 30).map(i => ok(c.at(29 - i)).id)
    val positions = TargetPositions.of(ids.iterator)
    ids.zipWithIndex.foreach((id, i) => assertEquals(positions(id), Some(i), id))
    assertEquals(positions(ok(group(60, "a").at(55)).id), None, "an ordinal the series lacks")
    assertEquals(positions(ok(group(1, "z").at(0)).id), None, "a foreign series")
  }

  test("the grid narrows a pointer query to a small fraction of 10,000 targets") {
    // Agreement with the oracle holds even for an index that returns every target, so the index's
    // selectivity is checked on its own: a 4-pixel query must not examine more than 2% of them.
    val mix = Mix(17)
    val counts = Vector.tabulate(400) { _ =>
      val x = 400 * mix.next()
      val y = 400 * mix.next()
      plan.index.candidates(x - 4, y - 4, x + 4, y + 4).length
    }
    val mean = counts.sum.toDouble / counts.size
    assert(mean < 200, s"mean candidates per query: $mean")
    assert(counts.max < 400, s"most candidates for one query: ${counts.max}")
  }
