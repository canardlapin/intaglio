package intaglio.performance

/** Deterministic structure checks that keep the timing workloads honest and compiling in CI.
  * Elapsed time is deliberately not asserted here; the receipt comes from [[TimingHarness]].
  */
class TimingWorkloadSuite extends munit.FunSuite:

  test("the 30-panel trellis materializes every declared grid cell") {
    val trained =
      TimingWorkloads.resolveTrellis(TimingWorkloads.densePlot, TimingWorkloads.leanOptions)
    assertEquals(trained.facetPanels.length, 30)
    assertEquals(trained.facetPanels.count(_.layers.exists(_.dataSize > 0)), 30)
  }

  test("the sparse trellis pays for the full cross product while only half its cells hold data") {
    val trained =
      TimingWorkloads.resolveTrellis(TimingWorkloads.sparsePlot, TimingWorkloads.leanOptions)
    assertEquals(trained.facetPanels.length, 30)
    assertEquals(trained.facetPanels.count(_.layers.exists(_.dataSize > 0)), 15)
  }

  test("rich provenance retains one resolved row per datum per layer while lean retains none") {
    val lean =
      TimingWorkloads.resolveTrellis(TimingWorkloads.densePlot, TimingWorkloads.leanOptions)
    val rich =
      TimingWorkloads.resolveTrellis(TimingWorkloads.densePlot, TimingWorkloads.richOptions)
    val leanRows = lean.facetPanels.flatMap(_.layers).map(_.rows.length.toLong).sum
    val richRows = rich.facetPanels.flatMap(_.layers).map(_.rows.length.toLong).sum
    assertEquals(leanRows, 0L)
    assert(richRows >= TimingWorkloads.denseRows.length.toLong * 2L, clues(richRows))
  }

  test("the dense picking plan exposes one logical target per source mark") {
    val plan = TimingWorkloads.compilePicking(TimingWorkloads.pickInteractionPlan)
    assertEquals(plan.targetCount, TimingWorkloads.pickMarks)
    assert(TimingWorkloads.runHitQueries(plan) > 0L)
    assert(TimingWorkloads.runNearestQueries(plan) > 0L)
    assert(TimingWorkloads.runSelectQueries(plan) > 0L)
  }

  test("the resize sweep is deterministic across repeated runs") {
    val first = TimingWorkloads.resizeSweep(TimingWorkloads.densePlot)
    val second = TimingWorkloads.resizeSweep(TimingWorkloads.densePlot)
    assertEquals(first, second)
  }

  test("the place sweep reproduces the full-recompile sweep over the same contexts") {
    val trained = TimingWorkloads.trainTrellis(TimingWorkloads.densePlot)
    assertEquals(
      TimingWorkloads.placeSweep(trained),
      TimingWorkloads.resizeSweep(TimingWorkloads.densePlot)
    )
  }

  test("the cached sweep reproduces the uncached sweep and hits after the first pass") {
    val options = TimingWorkloads.leanOptions
    val cache = intaglio.PlotCompileCache.bounded()
    val cold = TimingWorkloads.cachedResizeSweep(TimingWorkloads.densePlot, options, cache)
    val warm = TimingWorkloads.cachedResizeSweep(TimingWorkloads.densePlot, options, cache)
    assertEquals(cold, warm)
    assertEquals(cold, TimingWorkloads.resizeSweep(TimingWorkloads.densePlot))
    val profile = cache.profile
    assertEquals(profile.trainedMisses, 1L)
    assertEquals(profile.placedMisses, 5L)
    assertEquals(profile.trainedHits, 9L)
    assertEquals(profile.placedHits, 5L)
  }
