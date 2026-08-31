package intaglio.performance

import intaglio.*
import intaglio.svg.*

private[performance] final case class PerformanceMetric(
    workload: String,
    metric: String,
    value: Long
):
  def key: String =
    s"$workload.$metric"

private[performance] object PerformanceProfiles:
  private final case class ScatterDatum(x: Double, y: Double)
  private final case class BarDatum(category: Int, value: Double, group: String)
  private final case class LookupCategory(id: Int)

  def capture(): Vector[PerformanceMetric] =
    scatterMetrics() ++ rasterMetrics() ++ positionMetrics() ++ discreteMetrics() ++
      histogramMetrics() ++ svgMetrics()

  private def scatterMetrics(): Vector[PerformanceMetric] =
    val count = PerformanceBaselines.scatterMarks
    val data = Vector.tabulate(count) { index =>
      ScatterDatum(
        x = (index % 400).toDouble,
        y = ((index * 37) % 997).toDouble
      )
    }
    val plot = Plot(data)
      .addLayer(Layer.point[ScatterDatum](_.x, _.y))
      .fold(error => throw new IllegalStateException(error.message), identity)
    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions.lean)
      .fold(error => throw new IllegalStateException(error.message), identity)
    val device = DeviceScene
      .fromScene(trained.scene, DeviceContext.unsafe(800.0, 600.0))
      .fold(error => throw new IllegalStateException(error.message), identity)
    val batchCoordinates = device.elements
      .collectFirst { case DeviceElement.Mark(DevicePrimitive.PointBatch(points, _, _, _, _)) =>
        points.length
      }
      .getOrElse(0)

    Vector(
      PerformanceMetric("scatter", "retained_rows", trained.layers.map(_.rows.length).sum),
      PerformanceMetric("scatter", "grobs", trained.layers.map(_.grobs.length).sum),
      PerformanceMetric("scatter", "device_primitives", primitiveCount(device.elements)),
      PerformanceMetric("scatter", "batch_coordinates", batchCoordinates)
    )

  private def rasterMetrics(): Vector[PerformanceMetric] =
    val dimensions =
      RasterDimensions.unsafe(PerformanceBaselines.rasterWidth, PerformanceBaselines.rasterHeight)
    val image = RasterImage.tabulate(dimensions) { (x, y) =>
      if ((x / 8) + (y / 8)) % 2 == 0 then Rgba32.unsafe(25, 70, 145)
      else Rgba32.unsafe(220, 145, 55, 192)
    }
    val grob = Grob.imageUnsafe(
      image,
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(1.0, 1.0),
      interpolation = RasterInterpolation.Nearest
    )
    val scene = Scene(Vector(grob))
    val device = DeviceScene
      .fromScene(scene, DeviceContext.unsafe(512.0, 512.0))
      .fold(error => throw new IllegalStateException(error.message), identity)
    val svg = SvgRenderer
      .render(scene, SvgOptions.unsafe(width = 512, height = 512))
      .fold(error => throw new IllegalStateException(error.message), _.value)

    Vector(
      PerformanceMetric("raster", "packed_bytes", dimensions.pixelCount.toLong * 4L),
      PerformanceMetric("raster", "device_primitives", primitiveCount(device.elements)),
      PerformanceMetric("raster", "svg_bytes", svg.length)
    )

  private def positionMetrics(): Vector[PerformanceMetric] =
    val rows = Vector.tabulate(
      PerformanceBaselines.positionCategories * PerformanceBaselines.positionGroups
    ) { index =>
      val category = index / PerformanceBaselines.positionGroups
      val group = index % PerformanceBaselines.positionGroups
      BarDatum(
        category,
        value = if group % 3 == 0 then -(group + 1).toDouble else (group + 1).toDouble,
        group = s"g$group"
      )
    }
    val dodge = resolveBars(rows, Position.Dodge())
    val stack = resolveBars(rows, Position.Stack())

    Vector(
      PerformanceMetric("dodge", "output_rows", dodge.layers.head.rows.length),
      PerformanceMetric("dodge", "grobs", dodge.layers.head.grobs.length),
      PerformanceMetric("stack", "output_rows", stack.layers.head.rows.length),
      PerformanceMetric("stack", "grobs", stack.layers.head.grobs.length)
    )

  private def resolveBars(rows: Vector[BarDatum], position: Position): TrainedPlot =
    val mapping = AesSpec
      .empty[BarDatum]
      .withPosition(_.category.toDouble, _.value)
      .withGroup(_.group)
    val layer = Layer
      .fromMapping(Geom.Bar, mapping, inheritMapping = false, position = position)
      .fold(error => throw new IllegalStateException(error.message), identity)
    Plot(rows)
      .addLayer(layer)
      .flatMap(PlotCompiler.resolve(_, PlotCompilerOptions.rich))
      .fold(error => throw new IllegalStateException(error.message), identity)

  private def discreteMetrics(): Vector[PerformanceMetric] =
    var identityCalls = 0L
    given CategoryIdentity[LookupCategory] = CategoryIdentity.by(
      category =>
        identityCalls += 1L
        category.id
      ,
      _.id.toString
    )
    val domain = DiscreteDomain
      .ordered(Vector.tabulate(PerformanceBaselines.discreteLevels)(LookupCategory.apply))
      .fold(error => throw new IllegalStateException(error.message), identity)
    identityCalls = 0L
    var index = 0
    while index < PerformanceBaselines.discreteLevels do
      domain.indexOf(LookupCategory(index))
      index += 1
    while index < PerformanceBaselines.discreteLevels + PerformanceBaselines.discreteMisses do
      domain.indexOf(LookupCategory(index))
      index += 1

    Vector(PerformanceMetric("discrete_lookup", "identity_calls", identityCalls))

  private def histogramMetrics(): Vector[PerformanceMetric] =
    val bins = HistogramBins.countUnsafe(PerformanceBaselines.histogramBins)
    val values = Vector.tabulate(PerformanceBaselines.histogramSamples) { index =>
      (index % 10000).toDouble / 10000.0 * PerformanceBaselines.histogramBins.toDouble
    }
    val plot = Plot(values)
      .addLayer(Layer.histogram[Double](identity, bins = bins))
      .fold(error => throw new IllegalStateException(error.message), identity)
    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions(provenance = ProvenancePolicy.CountOnly))
      .fold(error => throw new IllegalStateException(error.message), identity)
    val generatedBreaks = HistogramBins.partition(
      bins,
      values.head,
      PerformanceBaselines.histogramBins.toDouble
    )
    val generatedLookup = HistogramBins.lookup(bins, generatedBreaks)
    val explicitBreaks = Vector.tabulate(PerformanceBaselines.histogramBins + 1) { index =>
      val ratio = index.toDouble / PerformanceBaselines.histogramBins.toDouble
      ratio * ratio * PerformanceBaselines.histogramBins.toDouble
    }
    val explicitBins = HistogramBins.breaksUnsafe(explicitBreaks)
    val explicitLookup = HistogramBins.lookup(explicitBins, explicitBreaks)
    val slowPathPenalty =
      if generatedLookup.strategy == HistogramLookupStrategy.RegularArithmetic &&
        explicitLookup.strategy == HistogramLookupStrategy.ExplicitBinarySearch
      then 0L
      else 1L

    Vector(
      PerformanceMetric("histogram", "compiled_bins", trained.layers.head.grobs.length),
      PerformanceMetric("histogram", "slow_path_penalty", slowPathPenalty)
    )

  private def svgMetrics(): Vector[PerformanceMetric] =
    val columns = 200
    val rows = PerformanceBaselines.svgMarks / columns
    val points = Vector.tabulate(PerformanceBaselines.svgMarks) { index =>
      Point.npcUnsafe(
        ((index % columns).toDouble + 0.5) / columns.toDouble,
        ((index / columns).toDouble + 0.5) / rows.toDouble
      )
    }
    val batch = Grob.pointBatchUnsafe(
      points,
      sizes = BatchColumn.Constant(ExtentExpr.pointsUnsafe(1.5)),
      graphicParams = BatchColumn.Constant(
        GraphicParams.unsafe(
          stroke = Some(Rgba.unsafe(30, 75, 145)),
          fill = Some(Rgba.unsafe(130, 175, 225)),
          lineWidth = 0.5
        )
      )
    )
    val svg = SvgRenderer
      .render(Scene(Vector(batch)), SvgOptions.unsafe(width = 800, height = 600))
      .fold(error => throw new IllegalStateException(error.message), _.value)

    Vector(
      PerformanceMetric("svg", "mark_elements", occurrences(svg, "<circle")),
      PerformanceMetric("svg", "bytes", svg.length)
    )

  private def primitiveCount(elements: Vector[DeviceElement]): Long =
    elements.foldLeft(0L) {
      case (count, DeviceElement.Mark(_))                  => count + 1L
      case (count, DeviceElement.Group(_, _, _, children)) => count + primitiveCount(children)
    }

  private def occurrences(value: String, needle: String): Long =
    var count = 0L
    var from = 0
    var next = value.indexOf(needle, from)
    while next >= 0 do
      count += 1L
      from = next + needle.length
      next = value.indexOf(needle, from)
    count

class PerformanceRegressionSuite extends munit.FunSuite:
  private lazy val current = PerformanceProfiles.capture()

  test("cross-platform workloads expose every versioned metric and exact structural budget") {
    val metrics = current.map(metric => metric.key -> metric.value).toMap
    assertEquals(metrics.keySet, PerformanceBaselines.byKey.keySet)
    PerformanceBaselines.entries
      .filter(baseline => baseline.recorded == baseline.highSeverityLimit)
      .foreach { baseline =>
        assertEquals(metrics(baseline.key), baseline.recorded, clues(baseline.key))
      }
  }

  test("every workload stays within its agreed high-severity ceiling") {
    val metrics = current.map(metric => metric.key -> metric.value).toMap
    assertEquals(metrics.keySet, PerformanceBaselines.byKey.keySet)
    PerformanceBaselines.entries.foreach { baseline =>
      val actual = metrics(baseline.key)
      assert(
        actual <= baseline.highSeverityLimit,
        clues(
          baseline.key,
          actual,
          baseline.recorded,
          baseline.highSeverityLimit,
          baseline.rationale
        )
      )
    }
  }
