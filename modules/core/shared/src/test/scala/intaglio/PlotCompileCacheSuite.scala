package intaglio

/** The compile cache contract: identical output to the uncached path, observable hits and misses,
  * identity keys for plots and options, value keys for contexts, LRU eviction at the declared
  * capacity, nothing retained on error, and `Disabled` as an exact zero-overhead non-cache.
  */
class PlotCompileCacheSuite extends munit.FunSuite:

  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  private final case class Observation(x: Double, y: Double, group: String)

  private val observations: Vector[Observation] =
    Vector.tabulate(200) { index =>
      Observation(
        (index % 20).toDouble,
        math.sin(index.toDouble / 9.0) * 3.0 + (index % 4).toDouble,
        s"grp-${index % 2}"
      )
    }

  private def freshPlot: Plot[Observation] =
    ok(
      Plot(observations)
        .addLayer(Layer.point[Observation](_.x, _.y))
        .flatMap(
          _.addLayer(
            Layer.line[Observation](
              _.x,
              _.y,
              mapping = AesSpec.empty[Observation].withGroup(_.group)
            )
          )
        )
    )

  private val basePlot = freshPlot
  private val options = PlotCompilerOptions(guides = GuidePolicy.Derived())
  private val small = RenderContext.unsafe(width = 640, height = 480)
  private val large = RenderContext.unsafe(width = 1280, height = 720)

  test("a cached resolve produces the scene an uncached resolve produces") {
    val cache = PlotCompileCache.bounded()
    val cached = ok(PlotCompiler.resolve(basePlot, small, options, cache))
    val trained = ok(PlotCompiler.train(basePlot, options))
    val uncached = ok(PlotCompiler.place(trained, small))
    assertEquals(cached.scene, uncached.scene)
  }

  test("an unchanged plot and context hit both cache levels and return the same value") {
    val cache = PlotCompileCache.bounded()
    val first = ok(PlotCompiler.resolve(basePlot, small, options, cache))
    val second = ok(PlotCompiler.resolve(basePlot, small, options, cache))
    assert(first eq second)
    val profile = cache.profile
    assertEquals(profile.trainedHits, 1L)
    assertEquals(profile.trainedMisses, 1L)
    assertEquals(profile.placedHits, 1L)
    assertEquals(profile.placedMisses, 1L)
    assertEquals(profile.evictions, 0L)
  }

  test("a new context reuses the training and pays only the placement") {
    val cache = PlotCompileCache.bounded()
    ok(PlotCompiler.resolve(basePlot, small, options, cache))
    ok(PlotCompiler.resolve(basePlot, large, options, cache))
    val profile = cache.profile
    assertEquals(profile.trainedHits, 1L)
    assertEquals(profile.trainedMisses, 1L)
    assertEquals(profile.placedMisses, 2L)
    // Returning to the first size hits through a freshly constructed, value-equal context.
    val again = ok(PlotCompiler.resolve(basePlot, RenderContext.unsafe(640, 480), options, cache))
    assertEquals(cache.profile.placedHits, 1L)
    assertEquals(again.scene, ok(PlotCompiler.resolve(basePlot, small, options)).scene)
  }

  test("a value-equal but distinct plot reference is deliberately a miss") {
    val cache = PlotCompileCache.bounded()
    ok(PlotCompiler.resolve(basePlot, small, options, cache))
    ok(PlotCompiler.resolve(freshPlot, small, options, cache))
    assertEquals(cache.profile.trainedHits, 0L)
    assertEquals(cache.profile.trainedMisses, 2L)
  }

  test("eviction honours the trained capacity and counts what it drops") {
    val cache = PlotCompileCache.bounded(trainedCapacity = 1, placedCapacity = 8)
    val other = freshPlot
    ok(PlotCompiler.resolve(basePlot, small, options, cache))
    ok(PlotCompiler.resolve(other, small, options, cache))
    // `other` evicted `plot`'s training and its dependent placement.
    val profile = cache.profile
    assertEquals(profile.trainedEntries, 1)
    assert(profile.evictions >= 2L, clues(profile))
    // The evicted plot still compiles correctly; it is simply a miss again.
    val recompiled = ok(PlotCompiler.resolve(basePlot, small, options, cache))
    assertEquals(recompiled.scene, ok(PlotCompiler.resolve(basePlot, small, options)).scene)
  }

  test("zero capacity retains nothing and never hits") {
    val cache = PlotCompileCache.bounded(trainedCapacity = 0, placedCapacity = 0)
    ok(PlotCompiler.resolve(basePlot, small, options, cache))
    ok(PlotCompiler.resolve(basePlot, small, options, cache))
    val profile = cache.profile
    assertEquals(profile.hits, 0L)
    assertEquals(profile.trainedEntries, 0)
    assertEquals(profile.placedEntries, 0)
    assertEquals(profile.evictions, 0L)
  }

  test("errors propagate unchanged and are never cached") {
    val facet = ok(FacetSpec.wrap[Observation](_.group))
    val faceted =
      ok(Plot(observations).withFacet(facet).addLayer(Layer.point[Observation](_.x, _.y)))
    // A faceted plot with an explicit layout cannot be placed by a solver: training fails.
    val badOptions = options.copy(layout =
      Some(
        PanelLayout(
          PanelFrame.npcUnsafe(0.1, 0.1, 0.8, 0.8),
          xScale = Interval.unsafe(0.0, 1.0),
          yScale = Interval.unsafe(0.0, 1.0),
          clip = Clip.On
        )
      )
    )
    val cache = PlotCompileCache.bounded()
    assert(PlotCompiler.resolve(faceted, small, badOptions, cache).isLeft)
    assert(PlotCompiler.resolve(faceted, small, badOptions, cache).isLeft)
    val profile = cache.profile
    assertEquals(profile.trainedMisses, 2L)
    assertEquals(profile.trainedEntries, 0)
  }

  test("Disabled is a first-class non-cache with an empty profile") {
    val direct = ok(PlotCompiler.resolve(basePlot, small, options))
    val throughDisabled =
      ok(PlotCompiler.resolve(basePlot, small, options, PlotCompileCache.Disabled))
    assertEquals(throughDisabled.scene, direct.scene)
    assertEquals(PlotCompileCache.Disabled.profile, PlotCacheProfile.empty)
  }

  test("the cache is reachable from the public DSL") {
    val cache = PlotCompileCache.bounded()
    val program = ok(
      plot(observations)
        .aes(_.x, _.y)
        .geomPoint()
        .build
    )
    val first = ok(program.resolve(small, cache))
    val second = ok(program.resolve(small, cache))
    assert(first eq second)
    assertEquals(cache.profile.hits, 2L)
  }
