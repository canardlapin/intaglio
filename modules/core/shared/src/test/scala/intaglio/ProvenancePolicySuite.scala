package intaglio

class ProvenancePolicySuite extends munit.FunSuite:
  private final case class CountDatum(category: String, value: Double)
  private final case class PointDatum(x: Double, y: Double)
  private final case class FacetDatum(x: Double, y: Double, panel: String)

  private val countData =
    Vector(
      CountDatum("A", 1.0),
      CountDatum("A", 1.0),
      CountDatum("B", 2.0)
    )

  private val countPlot =
    Plot(countData)
      .addLayer(Layer.count[CountDatum](_.category))
      .fold(error => fail(error.message), identity)

  private val badPoint = PointDatum(1.0, Double.NaN)
  private val pointData = Vector(PointDatum(0.0, 1.0), badPoint, PointDatum(2.0, 3.0))
  private val pointPlot =
    Plot(pointData)
      .addLayer(Layer.point[PointDatum](_.x, _.y))
      .fold(error => fail(error.message), identity)

  private def resolve[Row](plot: Plot[Row], policy: ProvenancePolicy): TrainedPlot =
    PlotCompiler
      .resolve(plot, PlotCompilerOptions(provenance = policy))
      .fold(error => fail(error.message), identity)

  private def assertRenderingEquivalent(actual: Scene, expected: Scene): Unit =
    val device = DeviceContext.unsafe(640.0, 480.0)
    val actualDevice =
      DeviceScene.fromScene(actual, device).fold(error => fail(error.message), identity)
    val expectedDevice =
      DeviceScene.fromScene(expected, device).fold(error => fail(error.message), identity)
    assertEquals(actualDevice.width, expectedDevice.width)
    assertEquals(actualDevice.height, expectedDevice.height)
    assertEquals(normalizeBatches(actualDevice.elements), normalizeBatches(expectedDevice.elements))

  private def normalizeBatches(elements: Vector[DeviceElement]): Vector[DeviceElement] =
    elements.flatMap {
      case DeviceElement.Mark(DevicePrimitive.PointBatch(points, radii, shapes, params, name)) =>
        points.indices.flatMap { index =>
          pointMarks(
            points(index),
            radii.valueAt(index),
            shapes.valueAt(index),
            params.valueAt(index),
            name
          ).map(DeviceElement.Mark(_))
        }.toVector
      case DeviceElement.Group(name, clip, rotation, children) =>
        Vector[DeviceElement](
          DeviceElement.Group(name, clip, rotation, normalizeBatches(children))
        )
      case DeviceElement.Annotated(meta, children) =>
        Vector[DeviceElement](DeviceElement.Annotated(meta, normalizeBatches(children)))
      case mark: DeviceElement.Mark => Vector(mark)
    }

  private def pointMarks(
      point: DevicePoint,
      radius: Double,
      shape: PointShape,
      params: GraphicParams,
      name: Option[GraphicsName]
  ): Vector[DevicePrimitive] =
    shape match
      case PointShape.Circle =>
        Vector(DevicePrimitive.Disc(point.x, point.y, radius, params, name))
      case PointShape.Square =>
        Vector(
          DevicePrimitive.RectShape(
            point.x - radius,
            point.y - radius,
            radius * 2.0,
            radius * 2.0,
            0.0,
            params,
            name
          )
        )
      case PointShape.Triangle =>
        Vector(
          DevicePrimitive.Polyline(
            Vector(
              DevicePoint(point.x, point.y - radius),
              DevicePoint(point.x + radius, point.y + radius),
              DevicePoint(point.x - radius, point.y + radius)
            ),
            closed = true,
            params,
            name
          )
        )
      case PointShape.Cross =>
        Vector(
          DevicePrimitive.Polyline(
            Vector(
              DevicePoint(point.x - radius, point.y),
              DevicePoint(point.x + radius, point.y)
            ),
            closed = false,
            params,
            name
          ),
          DevicePrimitive.Polyline(
            Vector(
              DevicePoint(point.x, point.y - radius),
              DevicePoint(point.x, point.y + radius)
            ),
            closed = false,
            params,
            name
          )
        )
      case PointShape.Diamond =>
        val half = PointShape.diamondHalfDiagonal(radius)
        Vector(
          DevicePrimitive.Polyline(
            Vector(
              DevicePoint(point.x, point.y - half),
              DevicePoint(point.x + half, point.y),
              DevicePoint(point.x, point.y + half),
              DevicePoint(point.x - half, point.y)
            ),
            closed = true,
            params,
            name
          )
        )

  test("provenance policies publish their retained-memory costs") {
    assertEquals(PlotCompilerOptions.default.provenance, ProvenancePolicy.Full)
    assertEquals(PlotCompilerOptions.rich.provenance, ProvenancePolicy.Full)
    assertEquals(PlotCompilerOptions.lean.provenance, ProvenancePolicy.None)
    assertEquals(
      ProvenancePolicy.values.toVector.map(_.retentionCost),
      Vector(
        ProvenanceRetentionCost(RetentionGrowth.None, RetentionGrowth.None, false),
        ProvenanceRetentionCost(RetentionGrowth.PerOutput, RetentionGrowth.Constant, false),
        ProvenanceRetentionCost(RetentionGrowth.PerOutput, RetentionGrowth.Constant, true),
        ProvenanceRetentionCost(
          RetentionGrowth.PerSourceIndex,
          RetentionGrowth.PerSourceIndex,
          false
        ),
        ProvenanceRetentionCost(
          RetentionGrowth.FullSourceValues,
          RetentionGrowth.FullSourceValues,
          true
        )
      )
    )
  }

  test("statistic provenance retains exactly the requested payload") {
    val full = resolve(countPlot, ProvenancePolicy.Full)
    val countOnly = resolve(countPlot, ProvenancePolicy.CountOnly)
    val representative = resolve(countPlot, ProvenancePolicy.Representative)
    val indices = resolve(countPlot, ProvenancePolicy.SourceIndices)
    val none = resolve(countPlot, ProvenancePolicy.None)

    Vector(countOnly, representative, indices, none).foreach { trained =>
      assertRenderingEquivalent(trained.scene, full.scene)
      assertEquals(trained.layers.head.rows, Vector.empty)
      assertEquals(trained.layers.head.statFrame.rows, Vector.empty)
    }
    assertEquals(full.layers.head.rows.length, 2)
    assertEquals(full.layers.head.statFrame.rows.map(_.members.length), Vector(2, 1))
    assertEquals(
      full.layers.head.inspection.statistics.map(_.memberCount),
      Vector(2, 1)
    )

    assertEquals(
      countOnly.layers.head.inspection.statistics,
      Vector(
        StatisticProvenance.CountOnly(0, 2),
        StatisticProvenance.CountOnly(1, 1)
      )
    )
    val representatives = representative.layers.head.inspection.statistics
    assertEquals(representatives.length, 2)
    representatives(0) match
      case StatisticProvenance.Representative(index, count, source) =>
        assertEquals((index, count), (0, 2))
        assert(source == countData(0))
      case other => fail(s"expected statistic representative, found $other")
    representatives(1) match
      case StatisticProvenance.Representative(index, count, source) =>
        assertEquals((index, count), (1, 1))
        assert(source == countData(2))
      case other => fail(s"expected statistic representative, found $other")
    assertEquals(
      indices.layers.head.inspection.statistics,
      Vector(
        StatisticProvenance.SourceIndices(0, 2, Vector(0, 1)),
        StatisticProvenance.SourceIndices(1, 1, Vector(2))
      )
    )
    assert(indices.layers.head.inspection.statistics.forall(_.hasCompleteSourceIndices))
    assertEquals(none.layers.head.inspection.statistics, Vector.empty)
  }

  test("dropped-row diagnostics retain counts, samples, indices, or full sources by policy") {
    val full = resolve(pointPlot, ProvenancePolicy.Full)
    val countOnly = resolve(pointPlot, ProvenancePolicy.CountOnly)
    val representative = resolve(pointPlot, ProvenancePolicy.Representative)
    val indices = resolve(pointPlot, ProvenancePolicy.SourceIndices)
    val none = resolve(pointPlot, ProvenancePolicy.None)

    Vector(countOnly, representative, indices, none).foreach { trained =>
      assertRenderingEquivalent(trained.scene, full.scene)
      assertEquals(trained.layers.head.rows, Vector.empty)
      assertEquals(trained.layers.head.statFrame.rows, Vector.empty)
      assertEquals(trained.layers.head.droppedRows, Vector.empty)
      assertEquals(trained.droppedRows, Vector.empty)
    }
    assert(full.layers.head.droppedRows.head.source == badPoint)

    assertEquals(countOnly.layers.head.inspection.dropped, DroppedProvenance.CountOnly(1))
    representative.layers.head.inspection.dropped match
      case DroppedProvenance.Representative(count, Some(sample)) =>
        assertEquals(count, 1)
        assert(sample.source == badPoint)
      case other =>
        fail(s"expected representative diagnostic, found $other")
    indices.layers.head.inspection.dropped match
      case DroppedProvenance.SourceIndices(Vector(row)) =>
        assertEquals(row.layerIndex, 0)
        assertEquals(row.rowIndex, 1)
        assertEquals(row.sourceIndices, Vector(1))
        assert(row.reason.isInstanceOf[PlotDropReason.NonFinitePosition])
      case other =>
        fail(s"expected source-index diagnostic, found $other")
    assertEquals(none.layers.head.inspection.dropped, DroppedProvenance.None)
  }

  test("non-full provenance releases rich rows from top-level and facet layer views") {
    val data = Vector(
      FacetDatum(0.0, 1.0, "A"),
      FacetDatum(1.0, 2.0, "A"),
      FacetDatum(2.0, 3.0, "B")
    )
    val facet = FacetSpec.wrap[FacetDatum](_.panel).fold(error => fail(error.message), identity)
    val plot = Plot(data)
      .withFacet(facet)
      .addLayer(Layer.point[FacetDatum](_.x, _.y))
      .fold(error => fail(error.message), identity)
    def resolved(policy: ProvenancePolicy): TrainedPlot =
      PlotCompiler
        .resolve(
          plot,
          PlotCompilerOptions(
            policy = Some(LayoutPolicy()),
            guides = GuidePolicy.Derived(),
            provenance = policy
          )
        )
        .fold(error => fail(error.message), identity)

    val full = resolved(ProvenancePolicy.Full)
    val lean = resolved(ProvenancePolicy.None)

    assertRenderingEquivalent(lean.scene, full.scene)
    assert(lean.layers.forall(_.rows.isEmpty))
    assert(lean.layers.forall(_.statFrame.rows.isEmpty))
    assert(lean.facetPanels.flatMap(_.layers).forall(_.rows.isEmpty))
    assert(lean.facetPanels.flatMap(_.layers).forall(_.statFrame.rows.isEmpty))
    assert(full.facetPanels.flatMap(_.layers).forall(_.rows.nonEmpty))
  }
