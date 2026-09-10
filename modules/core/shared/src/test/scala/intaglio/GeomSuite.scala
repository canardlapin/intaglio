package intaglio

class GeomSuite extends munit.FunSuite:
  private final case class Observation(
      x: Double,
      y: Double,
      xEnd: Double,
      yEnd: Double,
      lower: Double,
      upper: Double,
      group: String
  )

  private val data =
    Vector(
      Observation(0.0, 1.0, 0.5, 1.5, 0.5, 1.5, "a"),
      Observation(1.0, 2.0, 1.5, 2.5, 1.0, 3.0, "a"),
      Observation(2.0, 1.5, 2.5, 2.0, 1.0, 2.0, "b"),
      Observation(3.0, 2.5, 3.5, 3.0, 2.0, 3.0, "b")
    )

  private def resolve(layer: Layer[Observation]): TrainedPlot =
    Plot(data)
      .addLayer(layer)
      .flatMap(
        PlotCompiler.resolve(
          _,
          PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
        )
      )
      .fold(error => fail(error.message), identity)

  test("rectangles and tiles retain exact bounds and train their full extents") {
    val rectangles = resolve(Layer.rect[Observation](_.x, _.xEnd, _.lower, _.upper))
    val rectLayer = rectangles.layers.head
    assertEquals(rectangles.layout.map(_.xScale), Some(Interval.unsafe(0.0, 3.5)))
    assertEquals(rectangles.layout.map(_.yScale), Some(Interval.unsafe(0.5, 3.0)))
    assertEquals(rectLayer.grobs.length, 4)
    assert(rectLayer.grobs.forall(_.isInstanceOf[Grob.Rect]))

    val tiles = resolve(
      Layer.tile[Observation](
        _.x,
        _.y,
        _ => 0.8,
        _ => 0.6,
        mapping = AesSpec.empty.withFill(_.group match
          case "a" => Rgba.unsafe(40, 80, 120)
          case _   => Rgba.unsafe(180, 90, 50))
      )
    )
    assertEquals(tiles.layout.map(_.xScale), Some(Interval.unsafe(-0.4, 3.4)))
    assertEquals(tiles.layout.map(_.yScale), Some(Interval.unsafe(0.7, 2.8)))
    assertEquals(tiles.layers.head.grobs.length, 4)
  }

  test("segments and error bars lower to explicit shared segments") {
    val segments = resolve(Layer.segment[Observation](_.x, _.y, _.xEnd, _.yEnd))
    assertEquals(segments.layout.map(_.xScale), Some(Interval.unsafe(0.0, 3.5)))
    assertEquals(segments.layout.map(_.yScale), Some(Interval.unsafe(1.0, 3.0)))
    assertEquals(segments.layers.head.grobs.length, 4)

    val errorBars = resolve(Layer.errorBar[Observation](_.x, _.lower, _.upper))
    val first = errorBars.layers.head.grobs.head.asInstanceOf[Grob.Segments]
    assertEquals(first.segments.length, 3)
    assertEquals(errorBars.layout.map(_.yScale), Some(Interval.unsafe(0.5, 3.0)))
  }

  test("ribbons and areas lower one polygon per sufficiently large group") {
    val mapping = AesSpec.empty[Observation].withGroup(_.group)
    val ribbons = resolve(Layer.ribbon[Observation](_.x, _.lower, _.upper, mapping = mapping))
    assertEquals(ribbons.layers.head.grobs.length, 2)
    assert(ribbons.layers.head.grobs.forall(_.isInstanceOf[Grob.Polygon]))
    assertEquals(
      ribbons.layers.head.grobs.map(_.asInstanceOf[Grob.Polygon].points.length),
      Vector(4, 4)
    )

    val areas = resolve(Layer.area[Observation](_.x, _.y, mapping = mapping))
    assertEquals(areas.layers.head.grobs.length, 2)
    assertEquals(areas.layout.map(_.yScale), Some(Interval.unsafe(0.0, 2.5)))

    val signed = data.take(2).updated(0, data.head.copy(y = -1.0))
    val signedArea =
      Plot(signed)
        .addLayer(Layer.area[Observation](_.x, _.y))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
          )
        )
        .fold(error => fail(error.message), identity)
    assertEquals(signedArea.layout.map(_.yScale), Some(Interval.unsafe(-1.0, 2.0)))
  }

  test("polygons infer groups from raw discrete fill categories") {
    final case class Vertex(x: Double, y: Double, region: String)
    val vertices = Vector(
      Vertex(0.0, 0.0, "left"),
      Vertex(1.0, 0.0, "left"),
      Vertex(0.0, 1.0, "left"),
      Vertex(2.0, 0.0, "right"),
      Vertex(3.0, 0.0, "right"),
      Vertex(2.0, 1.0, "right")
    )
    val identicalFill = Rgba.unsafe(80, 100, 120)
    val fillScale = DiscreteScale(
      "region-fill",
      DiscreteDomain.empty,
      DiscretePalette.valuesUnsafe(Vector(identicalFill, identicalFill))
    ).fold(error => fail(error.message), identity)
    val trained = Plot(vertices)
      .withScale(ScaleBinding[Vertex, String, Rgba](Aesthetic.Fill, _.region, fillScale))
      .flatMap(_.addLayer(Layer.polygon[Vertex](_.x, _.y)))
      .flatMap(PlotCompiler.resolve(_))
      .fold(error => fail(error.message), identity)
    val layer = trained.layers.head

    assertEquals(layer.grouping, GroupingDecision.Inferred(Vector(Aesthetic.Fill)))
    assertEquals(
      layer.rows.map(_.groupKey).distinct,
      Vector("left", "right").map(category =>
        Some(GroupKey.Inferred(Vector(DiscreteGroupValue(Aesthetic.Fill, category))))
      )
    )
    assertEquals(layer.rows.map(_.gp.fill).distinct, Vector(Some(identicalFill)))
    assertEquals(layer.grobs.length, 2)
    assert(layer.grobs.forall(_.isInstanceOf[Grob.Polygon]))
  }

  test("bounded geoms compose with flipped coordinates in the shared compiler") {
    val flipped =
      Plot(data.take(1))
        .addLayer(Layer.rect[Observation](_.x, _.xEnd, _.lower, _.upper))
        .map(_.withCoord(Coord.Flipped()))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
          )
        )
        .fold(error => fail(error.message), identity)
    val row = flipped.layers.head.rows.head

    assertEquals(row.xMin -> row.xMax, Some(0.5) -> Some(1.5))
    assertEquals(row.yMin -> row.yMax, Some(0.0) -> Some(0.5))
    assertEquals(flipped.layout.map(_.xScale), Some(Interval.unsafe(0.5, 1.5)))
    assertEquals(flipped.layout.map(_.yScale), Some(Interval.unsafe(0.0, 0.5)))
  }

  test("reference lines lower once and contribute only to their own axis range") {
    val plot =
      Plot(data)
        .addLayer(Layer.point[Observation](_.x, _.y))
        .flatMap(_.addLayer(Layer.hline[Observation](2.75)))
        .flatMap(_.addLayer(Layer.vline[Observation](3.25)))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
          )
        )
        .fold(error => fail(error.message), identity)

    assertEquals(plot.layout.map(_.xScale), Some(Interval.unsafe(0.0, 3.25)))
    assertEquals(plot.layout.map(_.yScale), Some(Interval.unsafe(1.0, 2.75)))
    assertEquals(plot.layers(1).grobs.length, 1)
    assertEquals(plot.layers(2).grobs.length, 1)
  }

  test("reference annotations render over empty data without retaining or resolving rows") {
    val horizontal = Layer.hline[Observation](2.75, data = Some(data))
    val vertical = Layer.vline[Observation](3.25, data = Some(data))

    assertEquals(horizontal.data, None)
    assertEquals(vertical.data, None)
    assertEquals(horizontal.effectiveData(data), Vector.empty)
    assertEquals(vertical.effectiveData(data), Vector.empty)

    val specified =
      Plot(Vector.empty[Observation])
        .addLayer(horizontal)
        .flatMap(_.addLayer(vertical))
        .fold(error => fail(error.message), identity)
    assert(specified.layers.forall(layer => !layer.inheritsPlotData))

    val trained = PlotCompiler
      .resolve(
        specified,
        PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
      )
      .fold(error => fail(error.message), identity)

    assertEquals(trained.layers.map(_.dataSize), Vector(0, 0))
    assert(trained.layers.forall(_.rows.isEmpty))
    assert(trained.layers.forall(_.statFrame.rows.isEmpty))
    assert(trained.layers.forall(_.mapping.bound.isEmpty))
    assert(trained.layers.forall(_.annotation.nonEmpty))
    assertEquals(trained.layers.map(_.grobs.length), Vector(1, 1))
    assertEquals(trained.layout.map(_.xScale), Some(Interval.unsafe(3.25, 3.25)))
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(2.75, 2.75)))
  }

  test("reference annotation scale training and overlay policies are distinct") {
    val yScale =
      ContinuousScale
        .train("y", data.map(_.y), Palette.numeric)
        .fold(error => fail(error.message), identity)

    def compile(scalePolicy: AnnotationScalePolicy, coordinate: Double): TrainedPlot =
      Plot(data)
        .withScale(ScaleBinding[Observation, Double, Double](Aesthetic.Y, _.y, yScale))
        .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
        .flatMap(_.addLayer(Layer.hline[Observation](coordinate, scale = scalePolicy)))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
          )
        )
        .fold(error => fail(error.message), identity)

    val trained = compile(AnnotationScalePolicy.Train, 10.0)
    val trainedAnnotation = trained.layers(1).annotation.getOrElse(fail("missing annotation"))
    assert(trainedAnnotation.isMapped)
    assertEquals(trainedAnnotation.coordinate, 1.0)
    assertEquals(trained.layers(1).trainedScales.map(_.aesthetic), Vector("y"))
    assertEquals(
      trained.scaleRegistry.forAesthetic(Aesthetic.Y).map(_.descriptor.domain),
      Some(ScaleDomain.Continuous(Interval.unsafe(1.0, 10.0), Interval.unsafe(1.0, 10.0)))
    )
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(0.0, 1.0)))

    val overlay = compile(AnnotationScalePolicy.Overlay, 0.25)
    val overlayAnnotation = overlay.layers(1).annotation.getOrElse(fail("missing annotation"))
    assert(!overlayAnnotation.isMapped)
    assertEquals(overlayAnnotation.coordinate, 0.25)
    assertEquals(
      overlay.scaleRegistry.forAesthetic(Aesthetic.Y).map(_.descriptor.domain),
      Some(ScaleDomain.Continuous(Interval.unsafe(1.0, 2.5), Interval.unsafe(1.0, 2.5)))
    )
  }

  test("invalid reference coordinates fail at the typed layer boundary") {
    assert(Plot(data).addLayer(Layer.hline[Observation](Double.NaN)) match
      case Left(GraphicsError.InvalidAnnotationCoordinate("horizontal", value)) => value.isNaN
      case _                                                                    => false)

    assertEquals(
      Layer
        .fromMapping(
          Geom.HLine,
          AesSpec.empty[Observation].withPosition(_ => 0.0, _ => 1.0),
          inheritMapping = false
        )
        .left
        .toOption,
      Some(GraphicsError.ReferenceLineRequiresAnnotation("hline"))
    )
  }

  test("training a numeric reference against a categorical position scale fails explicitly") {
    val scale =
      BandScale("group-y", DiscreteDomain.empty)
        .fold(error => fail(error.message), identity)
    val plot =
      Plot(data)
        .withScale(ScaleBinding[Observation, String, Double](Aesthetic.Y, _.group, scale))
        .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
        .flatMap(_.addLayer(Layer.hline[Observation](0.5)))
        .fold(error => fail(error.message), identity)

    assertEquals(
      PlotCompiler.resolve(plot).left.toOption,
      Some(GraphicsError.AnnotationRequiresContinuousScale("horizontal", "y", "group-y"))
    )
  }

  test("required extent aesthetics and row bounds remain typed failures") {
    val missing = Layer.fromMapping(
      Geom.Segment,
      AesSpec.empty[Observation].withPosition(_.x, _.y),
      inheritMapping = false
    )
    assertEquals(missing.left.toOption, Some(GraphicsError.MissingAesthetic("segment", "xend")))

    val inverted =
      Plot(data.take(1))
        .addLayer(Layer.rect[Observation](_.xEnd, _.x, _.lower, _.upper))
        .flatMap(PlotCompiler.resolve(_))
        .fold(error => fail(error.message), identity)
    assertEquals(
      inverted.droppedRows.map(_.reason),
      Vector(PlotDropReason.InvalidBounds("x", 0.5, 0.0))
    )
    assertEquals(inverted.layers.head.grobs, Vector.empty)
  }
