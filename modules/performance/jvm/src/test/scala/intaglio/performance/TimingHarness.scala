package intaglio.performance

import intaglio.{PhaseClock, PlotCompileCache}

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Elapsed-time and allocation receipt for the interactive-rate workloads.
  *
  * Run manually on named hardware; never gated in CI (see `performance/README.md`):
  *
  * {{{
  * sbt "performanceJVM/Test/runMain intaglio.performance.TimingHarness"
  * }}}
  *
  * Writes `performance/timings/v1.tsv` (or the path given as the first argument) and echoes the
  * receipt to stdout. Reports run counts and min/median/max rather than presenting one run as a
  * benchmark.
  */
private[performance] object TimingHarness:

  private val warmupRuns = 4
  private val measuredRuns = 12

  /** Defeats dead-code elimination across runs. */
  @volatile private var blackhole: Long = 0L

  private final case class Sample(
      nanos: Long,
      allocBytes: Long,
      phases: Map[PhaseClock.Phase, PhaseClock.Reading]
  )

  private final case class WorkloadReport(
      name: String,
      samples: Vector[Sample],
      perOperationCount: Option[Int]
  )

  def main(args: Array[String]): Unit =
    val outPath = args.headOption.getOrElse("performance/timings/v1.tsv")

    // Force every lazy workload input before timing anything.
    val densePlot = TimingWorkloads.densePlot
    val sparsePlot = TimingWorkloads.sparsePlot
    val freePlot = TimingWorkloads.freeScalesPlot
    val interactionPlan = TimingWorkloads.pickInteractionPlan
    val pickingPlan = TimingWorkloads.compilePicking(interactionPlan)

    val compileWorkloads: Vector[(String, () => Long)] = Vector(
      "facet-grid-30" -> (() =>
        TimingWorkloads
          .resolveTrellis(densePlot, TimingWorkloads.leanOptions)
          .scene
          .grobs
          .length
          .toLong
      ),
      "facet-grid-30-rich" -> (() =>
        TimingWorkloads
          .resolveTrellis(densePlot, TimingWorkloads.richOptions)
          .scene
          .grobs
          .length
          .toLong
      ),
      "facet-grid-sparse" -> (() =>
        TimingWorkloads
          .resolveTrellis(sparsePlot, TimingWorkloads.leanOptions)
          .scene
          .grobs
          .length
          .toLong
      ),
      "facet-free-scales" -> (() =>
        TimingWorkloads
          .resolveTrellis(freePlot, TimingWorkloads.leanOptions)
          .scene
          .grobs
          .length
          .toLong
      ),
      "resize-only" -> (() => TimingWorkloads.resizeSweep(densePlot)),
      "resize-place" -> {
        val trained = TimingWorkloads.trainTrellis(densePlot)
        () => TimingWorkloads.placeSweep(trained)
      },
      "resize-cached" -> {
        val cacheOptions = TimingWorkloads.leanOptions
        val cache = PlotCompileCache.bounded()
        () => TimingWorkloads.cachedResizeSweep(densePlot, cacheOptions, cache)
      },
      "train-once" -> (() => TimingWorkloads.trainTrellis(densePlot).hashCode().toLong),
      "pick-compile" -> (() => TimingWorkloads.compilePicking(interactionPlan).targetCount.toLong)
    )

    val queryWorkloads: Vector[(String, () => Long, Int)] = Vector(
      ("pick-hits", () => TimingWorkloads.runHitQueries(pickingPlan), TimingWorkloads.pickQueries),
      (
        "pick-nearest",
        () => TimingWorkloads.runNearestQueries(pickingPlan),
        TimingWorkloads.pickQueries
      ),
      ("pick-select", () => TimingWorkloads.runSelectQueries(pickingPlan), 10)
    )

    val reports =
      compileWorkloads.map { case (name, body) => measure(name, body, None) } ++
        queryWorkloads.map { case (name, body, ops) => measure(name, body, Some(ops)) }

    val counts = structuralCounts(interactionPlan, pickingPlan)

    val receipt = render(reports, counts)
    val target = Paths.get(outPath)
    Option(target.getParent).foreach(Files.createDirectories(_))
    Files.write(target, receipt.getBytes(StandardCharsets.UTF_8))
    print(receipt)
    System.err.println(s"\nreceipt written to $target (blackhole ${blackhole})")

  private def measure(
      name: String,
      body: () => Long,
      perOperationCount: Option[Int]
  ): WorkloadReport =
    var index = 0
    while index < warmupRuns do
      blackhole ^= body()
      index += 1
    val samples = Vector.fill(measuredRuns) {
      val allocBefore = allocatedBytes()
      val start = System.nanoTime()
      val (checksum, phases) = PhaseClock.profile(body())
      val elapsed = System.nanoTime() - start
      val alloc = allocatedBytes() - allocBefore
      blackhole ^= checksum
      Sample(elapsed, alloc, phases)
    }
    WorkloadReport(name, samples, perOperationCount)

  private def allocatedBytes(): Long =
    ManagementFactory.getThreadMXBean match
      case bean: com.sun.management.ThreadMXBean => bean.getCurrentThreadAllocatedBytes
      case _                                     => -1L

  private def structuralCounts(
      interactionPlan: intaglio.interaction.InteractionPlan[Int],
      pickingPlan: intaglio.interaction.PickingPlan[Int]
  ): Vector[(String, String, Long)] =
    val denseTrained =
      TimingWorkloads.resolveTrellis(TimingWorkloads.densePlot, TimingWorkloads.leanOptions)
    val richTrained =
      TimingWorkloads.resolveTrellis(TimingWorkloads.densePlot, TimingWorkloads.richOptions)
    val sparseTrained =
      TimingWorkloads.resolveTrellis(TimingWorkloads.sparsePlot, TimingWorkloads.leanOptions)
    Vector(
      ("facet-grid-30", "panels", denseTrained.facetPanels.length.toLong),
      ("facet-grid-30", "source_rows", TimingWorkloads.denseRows.length.toLong),
      (
        "facet-grid-30",
        "retained_rows",
        denseTrained.facetPanels.flatMap(_.layers).map(_.rows.length.toLong).sum
      ),
      (
        "facet-grid-30-rich",
        "retained_rows",
        richTrained.facetPanels.flatMap(_.layers).map(_.rows.length.toLong).sum
      ),
      ("facet-grid-sparse", "panels", sparseTrained.facetPanels.length.toLong),
      (
        "facet-grid-sparse",
        "occupied_panels",
        sparseTrained.facetPanels.count(_.layers.exists(_.dataSize > 0)).toLong
      ),
      ("facet-grid-sparse", "source_rows", TimingWorkloads.sparseRows.length.toLong),
      ("pick-compile", "targets", pickingPlan.targetCount.toLong),
      ("pick-compile", "marks", TimingWorkloads.pickMarks.toLong),
      ("pick-hits", "queries", TimingWorkloads.pickQueries.toLong),
      ("pick-compile", "groups", interactionPlan.groups.length.toLong)
    )

  private def render(
      reports: Vector[WorkloadReport],
      counts: Vector[(String, String, Long)]
  ): String =
    val out = new StringBuilder
    out ++= "# Intaglio interactive-rate timing receipt\n"
    out ++= "# schema_version=1\n"
    out ++= s"# recorded_on=${java.time.LocalDate.now()}\n"
    out ++= s"# source_sha=${gitSha()}\n"
    out ++= s"# os=${sys.props.getOrElse("os.name", "?")} ${sys.props.getOrElse("os.version", "?")} ${sys.props.getOrElse("os.arch", "?")}\n"
    out ++= s"# cpu=${cpuBrand()} cores=${Runtime.getRuntime.availableProcessors()}\n"
    out ++= s"# jvm=${sys.props.getOrElse("java.vm.name", "?")} ${sys.props.getOrElse("java.version", "?")}\n"
    out ++= s"# protocol=warmup:$warmupRuns measured:$measuredRuns single-thread\n"
    out ++= "# Elapsed time is not CI-gated; see performance/README.md.\n"
    out ++= "workload\tmetric\tunit\truns\tmin\tmedian\tmax\n"

    reports.foreach { report =>
      val times = report.samples.map(_.nanos)
      row(out, report.name, "total", "ms", times.map(millis))
      report.perOperationCount.foreach { ops =>
        row(out, report.name, "per_operation", "us", times.map(nanos => micros(nanos / ops)))
      }
      val allocs = report.samples.map(_.allocBytes)
      if allocs.forall(_ >= 0) then row(out, report.name, "alloc", "mb", allocs.map(megabytes))
      PhaseClock.Phase.values.foreach { phase =>
        val perRun = report.samples.map(_.phases.get(phase).fold(0L)(_.nanos))
        if perRun.exists(_ > 0L) then
          row(out, report.name, s"phase.${phaseName(phase)}", "ms", perRun.map(millis))
      }
    }

    counts.foreach { case (workload, metric, value) =>
      out ++= s"$workload\t$metric\tcount\t1\t$value\t$value\t$value\n"
    }
    out.result()

  private def row(
      out: StringBuilder,
      workload: String,
      metric: String,
      unit: String,
      values: Vector[Double]
  ): Unit =
    val sorted = values.sorted
    val median = sorted(sorted.length / 2)
    out ++= f"$workload\t$metric\t$unit\t${values.length}\t${sorted.head}%.3f\t$median%.3f\t${sorted.last}%.3f\n"

  private def phaseName(phase: PhaseClock.Phase): String =
    phase match
      case PhaseClock.Phase.Mapping       => "mapping"
      case PhaseClock.Phase.Stat          => "stat"
      case PhaseClock.Phase.ScaleTraining => "scale_training"
      case PhaseClock.Phase.Resolve       => "resolve"
      case PhaseClock.Phase.Layout        => "layout"
      case PhaseClock.Phase.Lowering      => "lowering"

  private def millis(nanos: Long): Double = nanos.toDouble / 1e6
  private def micros(nanos: Long): Double = nanos.toDouble / 1e3
  private def megabytes(bytes: Long): Double = bytes.toDouble / (1024.0 * 1024.0)

  private def gitSha(): String =
    val sha = external(Vector("git", "rev-parse", "HEAD")).getOrElse("unknown")
    val dirty = external(Vector("git", "status", "--porcelain")).exists(status =>
      status.linesIterator.exists(line => !line.contains(".mote/"))
    )
    if dirty then s"$sha-dirty" else sha

  private def cpuBrand(): String =
    external(Vector("sysctl", "-n", "machdep.cpu.brand_string"))
      .orElse(external(Vector("uname", "-p")))
      .getOrElse("unknown")

  private def external(command: Vector[String]): Option[String] =
    try
      val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
      val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      if process.waitFor() == 0 && output.nonEmpty then Some(output) else None
    catch case _: Exception => None
