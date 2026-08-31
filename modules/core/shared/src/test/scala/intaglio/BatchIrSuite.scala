package intaglio

class BatchIrSuite extends munit.FunSuite:
  private final case class Mark(x: Double, y: Double, size: Double, fill: Rgba)

  private def pointLayer: Layer[Mark] =
    val mapping = AesSpec
      .empty[Mark]
      .withPosition(_.x, _.y)
      .withSize(_.size)
      .withFill(_.fill)
    Layer
      .fromMapping(Geom.Point, mapping, inheritMapping = false)
      .fold(error => fail(error.message), identity)

  test("large lean point plots retain one columnar grob and one device primitive") {
    val blue = Rgba.unsafe(40, 90, 170)
    val orange = Rgba.unsafe(220, 125, 45)
    val data = Vector.tabulate(10000) { index =>
      Mark(
        index.toDouble,
        (index % 101).toDouble,
        if index % 2 == 0 then 2.0 else 3.0,
        if index % 2 == 0 then blue else orange
      )
    }
    val plot = Plot(data).addLayer(pointLayer).fold(error => fail(error.message), identity)
    val lean = PlotCompiler
      .resolve(plot, PlotCompilerOptions.lean)
      .fold(error => fail(error.message), identity)
    val rich = PlotCompiler
      .resolve(plot, PlotCompilerOptions.rich)
      .fold(error => fail(error.message), identity)

    assertEquals(lean.layers.head.rows, Vector.empty)
    assertEquals(lean.layers.head.statFrame.rows, Vector.empty)
    assertEquals(lean.layers.head.grobs.length, 1)
    assertEquals(rich.layers.head.rows.length, data.length)
    assertEquals(rich.layers.head.grobs.length, data.length)

    val grob = lean.layers.head.grobs.head match
      case value: Grob.PointBatch => value
      case other                  => fail(s"expected one point batch, found $other")
    assertEquals(grob.points.length, data.length)
    assert(!grob.sizes.isConstant)
    assert(grob.shapes.isConstant)
    assert(!grob.graphicParams.isConstant)

    val device = DeviceContext.unsafe(800.0, 600.0)
    val leanDevice = DeviceScene.fromScene(lean.scene, device).fold(e => fail(e.message), identity)
    val richDevice = DeviceScene.fromScene(rich.scene, device).fold(e => fail(e.message), identity)
    val batch = leanDevice.elements.head match
      case DeviceElement.Mark(value: DevicePrimitive.PointBatch) => value
      case other => fail(s"expected one device point batch, found $other")
    val discs = richDevice.elements.collect {
      case DeviceElement.Mark(value: DevicePrimitive.Disc) => value
    }

    assertEquals(leanDevice.elements.length, 1)
    assertEquals(discs.length, data.length)
    assertEquals(batch.points, discs.map(disc => DevicePoint(disc.centerX, disc.centerY)))
    assertEquals(
      Vector.tabulate(data.length)(batch.radii.valueAt),
      discs.map(_.radius)
    )
    assertEquals(
      Vector.tabulate(data.length)(batch.graphicParams.valueAt),
      discs.map(_.gp)
    )
  }

  test("point batches preserve constant and value style columns") {
    val points = Vector(
      Point.npcUnsafe(0.2, 0.2),
      Point.npcUnsafe(0.4, 0.4),
      Point.npcUnsafe(0.6, 0.6),
      Point.npcUnsafe(0.8, 0.8)
    )
    val shapes = BatchColumn.Values(PointShape.values.toVector)
    val black = GraphicParams.unsafe(fill = Some(Rgba.Black))
    val white = GraphicParams.unsafe(fill = Some(Rgba.White), alpha = 0.5)
    val params = BatchColumn.Values(Vector(black, white, black, white))
    val grob = Grob
      .pointBatch(
        points,
        sizes = BatchColumn.Constant(ExtentExpr.pointsUnsafe(3.0)),
        shapes = shapes,
        graphicParams = params,
        name = Some(GraphicsName.unsafe("mixed-point-batch"))
      )
      .fold(error => fail(error.message), identity)
    val device = DeviceScene
      .fromScene(Scene(Vector(grob)), DeviceContext.unsafe(100.0, 100.0))
      .fold(error => fail(error.message), identity)
    val batch = device.elements.head match
      case DeviceElement.Mark(value: DevicePrimitive.PointBatch) => value
      case other => fail(s"expected a device point batch, found $other")

    assertEquals(batch.points.length, 4)
    assert(batch.radii.isConstant)
    assertEquals(Vector.tabulate(4)(batch.shapes.valueAt), PointShape.values.toVector)
    assertEquals(
      Vector.tabulate(4)(batch.graphicParams.valueAt),
      Vector(black, white, black, white)
    )
    assertEquals(batch.name.map(_.value), Some("mixed-point-batch"))
  }

  test("point batch constructors reject incoherent value-column lengths") {
    val points = Vector(Point.npcUnsafe(0.2, 0.2), Point.npcUnsafe(0.8, 0.8))
    assertEquals(
      Grob
        .pointBatch(
          points,
          shapes = BatchColumn.Values(Vector(PointShape.Circle))
        )
        .left
        .toOption,
      Some(GraphicsError.BatchColumnLengthMismatch("point shape", 2, 1))
    )
  }
