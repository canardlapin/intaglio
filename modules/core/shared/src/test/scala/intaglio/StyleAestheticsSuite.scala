package intaglio

class StyleAestheticsSuite extends munit.FunSuite:
  private final case class PointDatum(
      x: Double,
      y: Double,
      size: Double,
      shape: PointShape,
      stroke: Rgba,
      fill: Rgba
  )

  private final case class LineDatum(
      x: Double,
      y: Double,
      group: String,
      lineType: LineType,
      lineWidth: Double
  )

  private final case class TextDatum(
      x: Double,
      y: Double,
      label: String,
      angle: Double,
      hJust: HJust,
      vJust: VJust
  )

  test("point channels survive rich rows and lean batch lowering") {
    val blue = Rgba.unsafe(35, 80, 180)
    val orange = Rgba.unsafe(230, 130, 45)
    val white = Rgba.White
    val black = Rgba.Black
    val data = Vector(
      PointDatum(0.0, 0.0, 3.0, PointShape.Square, blue, white),
      PointDatum(1.0, 1.0, 6.0, PointShape.Triangle, orange, black)
    )
    val mapping = AesSpec
      .empty[PointDatum]
      .withPosition(_.x, _.y)
      .withSize(_.size)
      .withShape(_.shape)
      .withColor(_.stroke)
      .withFill(_.fill)
    val layer = Layer
      .fromMapping(Geom.Point, mapping, inheritMapping = false)
      .fold(error => fail(error.message), identity)
    val plot = Plot(data).addLayer(layer).fold(error => fail(error.message), identity)
    val rich = PlotCompiler
      .resolve(plot, PlotCompilerOptions.rich)
      .fold(error => fail(error.message), identity)
    val lean = PlotCompiler
      .resolve(plot, PlotCompilerOptions.lean)
      .fold(error => fail(error.message), identity)

    assertEquals(rich.layers.head.rows.map(_.shape), data.map(_.shape))
    assertEquals(rich.layers.head.rows.map(_.size), data.map(d => ExtentExpr.pointsUnsafe(d.size)))
    assertEquals(rich.layers.head.rows.map(_.gp.stroke), data.map(d => Some(d.stroke)))
    assertEquals(rich.layers.head.rows.map(_.gp.fill), data.map(d => Some(d.fill)))

    val richPoints = rich.layers.head.grobs.map {
      case point: Grob.Points => point
      case other              => fail(s"expected rich point grob, found $other")
    }
    assertEquals(richPoints.map(_.shape), data.map(_.shape))

    val batch = lean.layers.head.grobs.headOption match
      case Some(point: Grob.PointBatch) => point
      case other                        => fail(s"expected lean point batch, found $other")
    assertEquals(Vector.tabulate(data.length)(batch.shapes.valueAt), data.map(_.shape))
    assertEquals(
      Vector.tabulate(data.length)(batch.sizes.valueAt),
      data.map(d => ExtentExpr.pointsUnsafe(d.size))
    )
    assertEquals(
      Vector.tabulate(data.length)(batch.graphicParams.valueAt).map(_.stroke),
      data.map(d => Some(d.stroke))
    )
    assertEquals(
      Vector.tabulate(data.length)(batch.graphicParams.valueAt).map(_.fill),
      data.map(d => Some(d.fill))
    )

    val deviceBatch = DeviceScene
      .fromScene(lean.scene, DeviceContext.unsafe(320.0, 240.0))
      .fold(error => fail(error.message), identity)
      .elements
      .headOption match
      case Some(DeviceElement.Mark(point: DevicePrimitive.PointBatch)) => point
      case other => fail(s"expected device point batch, found $other")
    assertEquals(Vector.tabulate(data.length)(deviceBatch.shapes.valueAt), data.map(_.shape))
  }

  test("line type and point-unit line width remain constant within each lowered line group") {
    val data = Vector(
      LineDatum(0.0, 0.0, "solid", LineType.Solid, 1.5),
      LineDatum(1.0, 1.0, "solid", LineType.Solid, 1.5),
      LineDatum(0.0, 1.0, "dashed", LineType.Dashed, 3.0),
      LineDatum(1.0, 0.0, "dashed", LineType.Dashed, 3.0)
    )
    val mapping = AesSpec
      .empty[LineDatum]
      .withPosition(_.x, _.y)
      .withGroup(_.group)
      .withLineType(_.lineType)
      .withLineWidth(_.lineWidth)
    val layer = Layer
      .fromMapping(Geom.Line, mapping, inheritMapping = false)
      .fold(error => fail(error.message), identity)
    val plot = Plot(data).addLayer(layer).fold(error => fail(error.message), identity)
    val trained = PlotCompiler.resolve(plot).fold(error => fail(error.message), identity)
    val lines = trained.layers.head.grobs.map {
      case line: Grob.Lines => line
      case other            => fail(s"expected line grob, found $other")
    }

    assertEquals(lines.map(_.gp.lineType), Vector(LineType.Solid, LineType.Dashed))
    assertEquals(lines.map(_.gp.lineWidth), Vector(1.5, 3.0))
    assertEquals(lines.map(_.gp.lineWidthUnit), Vector.fill(2)(StrokeUnit.Point))
  }

  test("discrete scaled line types participate in inferred structural grouping") {
    val data = Vector(
      LineDatum(0.0, 0.0, "solid", LineType.Solid, 1.0),
      LineDatum(1.0, 1.0, "solid", LineType.Solid, 1.0),
      LineDatum(0.0, 1.0, "dashed", LineType.Dashed, 1.0),
      LineDatum(1.0, 0.0, "dashed", LineType.Dashed, 1.0)
    )
    val scale = DiscreteScale(
      "series-linetype",
      DiscreteDomain
        .ordered(Vector("solid", "dashed"))
        .fold(error => fail(error.message), identity),
      DiscretePalette.valuesUnsafe(Vector(LineType.Solid, LineType.Dashed))
    ).fold(error => fail(error.message), identity)
    val mapping = AesSpec
      .empty[LineDatum]
      .withPosition(_.x, _.y)
      .bind(ScaleBinding(Aesthetic.LineType, _.group, scale))
      .fold(error => fail(error.message), identity)
    val layer = Layer
      .fromMapping(Geom.Line, mapping, inheritMapping = false)
      .fold(error => fail(error.message), identity)
    val plot = Plot(data).addLayer(layer).fold(error => fail(error.message), identity)
    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions.rich)
      .fold(error => fail(error.message), identity)

    assertEquals(
      trained.layers.head.grouping,
      GroupingDecision.Inferred(Vector(Aesthetic.LineType))
    )
    assertEquals(trained.layers.head.grobs.length, 2)
    assertEquals(
      trained.layers.head.grobs.collect { case line: Grob.Lines => line.gp.lineType },
      Vector(LineType.Solid, LineType.Dashed)
    )
  }

  test("varying line width fails the group-constant contract before lowering") {
    val data = Vector(
      LineDatum(0.0, 0.0, "all", LineType.Solid, 1.0),
      LineDatum(1.0, 1.0, "all", LineType.Solid, 1.0),
      LineDatum(2.0, 2.0, "all", LineType.Solid, 2.0)
    )
    val mapping = AesSpec
      .empty[LineDatum]
      .withPosition(_.x, _.y)
      .withGroup(_.group)
      .withLineWidth(_.lineWidth)
    val layer = Layer
      .fromMapping(Geom.Line, mapping, inheritMapping = false)
      .fold(error => fail(error.message), identity)

    assertEquals(
      Plot(data).addLayer(layer).flatMap(PlotCompiler.resolve(_)).left.toOption,
      Some(GraphicsError.VaryingGroupAesthetic("line", "linewidth", "all", 0, 2))
    )
  }

  test("text angle and both justification channels lower to text anchors") {
    val data = Vector(
      TextDatum(0.0, 0.0, "left", -30.0, HJust.Left, VJust.Bottom),
      TextDatum(1.0, 1.0, "right", 45.0, HJust.Right, VJust.Top)
    )
    val mapping = AesSpec
      .empty[TextDatum]
      .withPosition(_.x, _.y)
      .withLabel(_.label)
      .withAngle(_.angle)
      .withHJust(_.hJust)
      .withVJust(_.vJust)
    val layer = Layer
      .fromMapping(Geom.Text, mapping, inheritMapping = false)
      .fold(error => fail(error.message), identity)
    val plot = Plot(data).addLayer(layer).fold(error => fail(error.message), identity)
    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions.rich)
      .fold(error => fail(error.message), identity)
    val text = trained.layers.head.grobs.map {
      case value: Grob.Text => value
      case other            => fail(s"expected text grob, found $other")
    }

    assertEquals(text.map(_.rotationDegrees), data.map(_.angle))
    assertEquals(text.map(_.anchor), data.map(d => Anchor(d.hJust, d.vJust)))
  }

  test("geom contracts reject style channels that the geom cannot preserve") {
    val pointMapping = AesSpec
      .empty[PointDatum]
      .withPosition(_.x, _.y)
      .withLineType(LineType.Dotted)
    assertEquals(
      Layer.fromMapping(Geom.Point, pointMapping, inheritMapping = false).left.toOption,
      Some(GraphicsError.UnsupportedGeomAesthetic("point", "linetype"))
    )
  }

  test("non-finite text angles become typed row-drop diagnostics") {
    val data = Vector(TextDatum(0.0, 0.0, "bad", Double.NaN, HJust.Center, VJust.Center))
    val mapping = AesSpec
      .empty[TextDatum]
      .withPosition(_.x, _.y)
      .withLabel(_.label)
      .withAngle(_.angle)
    val layer = Layer
      .fromMapping(Geom.Text, mapping, inheritMapping = false)
      .fold(error => fail(error.message), identity)
    val plot = Plot(data).addLayer(layer).fold(error => fail(error.message), identity)
    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions.rich)
      .fold(error => fail(error.message), identity)

    assertEquals(trained.layers.head.rows, Vector.empty)
    assertEquals(
      trained.layers.head.droppedRows.map(_.reason),
      Vector(PlotDropReason.InvalidAesthetic("angle", "rotation must be finite"))
    )
  }
