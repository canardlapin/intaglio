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
      Layer.tile[Observation](_.x, _.y, _ => 0.8, _ => 0.6, mapping = AesSpec.empty.withFill(_.group match
        case "a" => Rgba.unsafe(40, 80, 120)
        case _   => Rgba.unsafe(180, 90, 50)
      ))
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
