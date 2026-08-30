package intaglio

import scala.compiletime.testing.typeCheckErrors

class PlotDslSuite extends munit.FunSuite:
  private final case class Observation(x: Double, y: Double, group: String)
  private final case class Overlay(x: Double, y: Double)

  private val rows =
    Vector(
      Observation(0.0, 1.0, "control"),
      Observation(1.0, 2.0, "control"),
      Observation(0.0, 1.5, "task"),
      Observation(1.0, 2.5, "task")
    )

  test("point and line programs compose mappings, scales, labels, and theme concisely") {
    val program =
      plot(rows)
        .aes(_.x, _.y)
        .group(_.group)
        .scaleColorDiscrete(_.group, levels = Vector("control", "task"), name = "condition")
        .geomPoint()
        .geomLine()
        .title("Response")
        .axisTitles("Time", "Signal")
        .theme(Theme.minimal)
        .build
        .fold(error => fail(error.message), identity)

    val trained = program.resolve.fold(error => fail(error.message), identity)
    assertEquals(program.plot.layers.map(_.geom), Vector(Geom.Point, Geom.Line))
    assertEquals(program.plot.labels.title, Some("Response"))
    assertEquals(program.compilerOptions.theme, Theme.minimal)
    assertEquals(trained.droppedRows, Vector.empty)
    assertEquals(trained.trainedScales.map(_.aesthetic), Vector("color"))
    assert(trained.guides.nonEmpty)
  }

  test("the DSL adds independent typed layers only with an explicit facet policy") {
    val overlays = Vector(Overlay(0.5, 4.0))
    val program =
      plot(rows)
        .aes(_.x, _.y)
        .geomPoint()
        .independentLayer(
          overlays,
          Layer.point[Overlay](_.x, _.y, inheritMapping = false),
          LayerFacetPolicy.Repeat
        )
        .build
        .fold(error => fail(error.message), identity)
    val trained = program.resolve.fold(error => fail(error.message), identity)

    assertEquals(program.plot.layers.map(_.inheritsPlotData), Vector(true, false))
    assertEquals(program.plot.layers.map(_.inheritsPlotMapping), Vector(true, false))
    assertEquals(trained.layers.map(_.dataSize), Vector(rows.length, overlays.length))
  }

  test("canonical histogram, summary, and density programs expose trained plots") {
    val histogram =
      plot(rows)
        .aes(_.x)
        .geomHistogram(HistogramBins.countUnsafe(2))
        .resolve
        .fold(error => fail(error.message), identity)
    assertEquals(histogram.layers.map(_.stat.label), Vector("bin"))
    assertEquals(histogram.layers.map(_.geom), Vector(Geom.Bar))

    val summary =
      plot(rows)
        .aes(_.x, _.y)
        .geomSummary(SummaryInterval.Range)
        .resolve
        .fold(error => fail(error.message), identity)
    assertEquals(summary.layers.map(_.stat.label), Vector("summary"))
    assertEquals(summary.droppedRows, Vector.empty)

    val density =
      plot(rows)
        .aes(_.y)
        .geomDensity()
        .resolve
        .fold(error => fail(error.message), identity)
    assertEquals(density.layers.map(_.stat.label), Vector("density"))
  }

  test("the DSL and core grammar compile canonical points to the same scene") {
    val x: Observation => Double = _.x
    val y: Observation => Double = _.y
    val options = PlotCompilerOptions(
      policy = Some(LayoutPolicy()),
      expansion = RangeExpansion.none
    )
    val manual =
      Plot(rows)
        .addLayer(Layer.point(x, y))
        .flatMap(PlotCompiler.compile(_, options))
        .fold(error => fail(error.message), identity)
    val concise =
      plot(rows)
        .aes(x, y)
        .geomPoint()
        .compilerOptions(options)
        .scene
        .fold(error => fail(error.message), identity)

    assertEquals(concise, manual)
  }

  test("checked builder operations retain typed errors until build") {
    val emptyPalette =
      plot(rows)
        .aes(_.x, _.y)
        .scaleColorDiscrete(_.group, colors = Vector.empty)
        .geomPoint()
        .build
    assertEquals(emptyPalette.left.toOption, Some(GraphicsError.EmptyPalette))

    val invalidCoord =
      plot(rows)
        .aes(_.x, _.y)
        .geomPoint()
        .coordFixed(0.0)
        .build
    assertEquals(invalidCoord.left.toOption, Some(GraphicsError.InvalidCoordinateRatio(0.0)))
  }

  test("eager DSL scale declaration retains mapping exceptions as typed errors") {
    val x = RowMapping.throwing[Observation, Double] { row =>
      if row.group == "task" then throw new IllegalStateException("task x unavailable")
      else row.x
    }

    val built =
      plot(rows)
        .aes(x, _.y)
        .scaleXContinuous()
        .geomPoint()
        .build

    assert(built match
      case Left(
            GraphicsError.MappingEvaluationFailed(
              "scale declaration",
              None,
              "x",
              2,
              MappingContract.Throwing,
              MappingFailure.Threw(_, "task x unavailable")
            )
          ) =>
        true
      case _ => false)
  }

  test("publication palettes reject overflow unless cycling is explicit") {
    val manyGroups = Vector.tabulate(7) { index =>
      Observation(index.toDouble, index.toDouble, s"group-$index")
    }

    val publicationDefault =
      plot(manyGroups)
        .aes(_.x, _.y)
        .scaleColorDiscrete(_.group)
        .geomPoint()
        .build
    assertEquals(
      publicationDefault.left.toOption,
      Some(GraphicsError.DiscretePaletteOverflow("color", 7, 6))
    )

    val cycled =
      plot(manyGroups)
        .aes(_.x, _.y)
        .scaleColorDiscrete(
          _.group,
          colors = Vector(Rgba.Black, Rgba.White),
          overflow = PaletteOverflowPolicy.Cycle
        )
        .geomPoint()
        .resolve
        .fold(error => fail(error.message), identity)

    assertEquals(
      cycled.layers.head.rows.map(_.gp.stroke),
      Vector(
        Some(Rgba.Black),
        Some(Rgba.White),
        Some(Rgba.Black),
        Some(Rgba.White),
        Some(Rgba.Black),
        Some(Rgba.White),
        Some(Rgba.Black)
      )
    )
  }

  test("facet builders retain an inspectable typed specification") {
    val program =
      plot(rows)
        .aes(_.x, _.y)
        .facetWrap(_.group, columns = 1, scales = FacetScales.FreeY)
        .geomPoint()
        .build
        .fold(error => fail(error.message), identity)

    assert(program.plot.facet.nonEmpty)
    assertEquals(program.resolve.fold(error => fail(error.message), identity).facetPanels.length, 2)
  }

  test("field-native heatmaps bind continuous fill and derive a colorbar") {
    val axis = RegularGridAxis.cellCenteredUnsafe(0.0, 2.0, 2)
    val field = ScalarField2D.unsafe(axis, axis, Vector(0.0, 1.0, 2.0, 3.0))
    val program =
      plot(field)
        .geomHeatmap(name = "activation")
        .theme(Theme.minimal)
        .build
        .fold(error => fail(error.message), identity)
    val trained = program.resolve.fold(error => fail(error.message), identity)

    assertEquals(program.plot.data.map(_.value), field.samples)
    assertEquals(program.plot.layers.map(_.geom), Vector(Geom.Tile))
    assertEquals(
      trained.trainedScales.map(scale => scale.aesthetic -> scale.descriptor.name.value),
      Vector("fill" -> "activation")
    )
    assertEquals(trained.layers.head.grobs.length, 4)
    assert(trained.guides.exists(_.grob.name.exists(_.value == "activation-colorbar")))
  }

  test("geom prerequisites are compile-time constraints") {
    val pointErrors = typeCheckErrors("""
      import intaglio.*
      final case class Row(x: Double, y: Double)
      plot(Vector(Row(1.0, 2.0))).geomPoint()
    """)
    val summaryErrors = typeCheckErrors("""
      import intaglio.*
      final case class Row(x: Double, y: Double)
      plot(Vector(Row(1.0, 2.0))).aes(_.x).geomSummary()
    """)
    val heatmapErrors = typeCheckErrors("""
      import intaglio.*
      final case class Row(x: Double, y: Double)
      plot(Vector(Row(1.0, 2.0))).aes(_.x, _.y).geomHeatmap()
    """)
    val contourErrors = typeCheckErrors("""
      import intaglio.*
      final case class Row(x: Double, y: Double)
      plot(Vector(Row(1.0, 2.0))).aes(_.x, _.y).geomContour()
    """)
    val filledContourErrors = typeCheckErrors("""
      import intaglio.*
      final case class Row(x: Double, y: Double)
      plot(Vector(Row(1.0, 2.0))).aes(_.x, _.y).geomFilledContour()
    """)

    assert(pointErrors.nonEmpty)
    assert(summaryErrors.nonEmpty)
    assert(heatmapErrors.nonEmpty)
    assert(contourErrors.nonEmpty)
    assert(filledContourErrors.nonEmpty)
    assert(pointErrors.exists(_.message.contains("requires x and y")))
    assert(summaryErrors.exists(_.message.contains("requires x and y")))
  }
