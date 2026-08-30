package external.recipe

import intaglio.*

final case class Sample(time: Double, signal: Double)
final case class TimeSeries(samples: Vector[Sample])

given timeSeriesRecipe: PlotRecipe.Aux[TimeSeries, Sample] =
  PlotRecipe.checked { series =>
    if series.samples.isEmpty then Left(GraphicsError.EmptyGeometry("time-series recipe"))
    else
      plot(series.samples)
        .aes(_.time, _.signal)
        .geomLine()
        .geomPoint()
        .build
        .map(PlotSpec.fromProgram)
  }

final case class Estimate(groupIndex: Double, value: Double)
final case class EstimateTable(estimates: Vector[Estimate])

given estimateTableRecipe: PlotRecipe.Aux[EstimateTable, Estimate] =
  PlotRecipe.checked { table =>
    plot(table.estimates)
      .aes(_.groupIndex, _.value)
      .geomPoint()
      .build
      .map(PlotSpec.fromProgram)
  }

class PlotRecipeSuite extends munit.FunSuite:
  private val series =
    TimeSeries(Vector(Sample(0.0, 1.0), Sample(1.0, 3.0), Sample(2.0, 2.0)))

  private val estimates =
    EstimateTable(Vector(Estimate(0.0, 2.5), Estimate(1.0, 4.0)))

  test("external model types retain the exact row type without inheritance") {
    val seriesSpec: PlotSpec[Sample] = series.toPlotSpec.orThrow
    val estimateSpec: PlotSpec[Estimate] = PlotRecipe(estimates).orThrow

    assertEquals(seriesSpec.plot.data, series.samples)
    assertEquals(estimateSpec.plot.data, estimates.estimates)
    assertEquals(summon[PlotRecipe.Aux[TimeSeries, Sample]], timeSeriesRecipe)
    assertEquals(summon[PlotRecipe.Aux[EstimateTable, Estimate]], estimateTableRecipe)
  }

  test("independent external recipes compile on the shared JVM and Scala.js surface") {
    val seriesScene = series.toPlotSpec.flatMap(_.scene)
    val estimateScene = estimates.toPlotSpec.flatMap(_.scene)

    assert(seriesScene.isRight, seriesScene.left.map(_.message).toString)
    assert(estimateScene.isRight, estimateScene.left.map(_.message).toString)
  }

  test("recipe conversion and compilation are deterministic") {
    val first = series.toPlotSpec.flatMap(_.scene)
    val second = series.toPlotSpec.flatMap(_.scene)
    val retained = series.toPlotSpec.orThrow

    assertEquals(first, second)
    assertEquals(retained.scene, retained.program.scene)
    assertEquals(retained, PlotSpec.fromProgram(retained.program))
  }

  test("checked recipes return typed conversion failures") {
    assertEquals(
      TimeSeries(Vector.empty).toPlotSpec.left.toOption,
      Some(GraphicsError.EmptyGeometry("time-series recipe"))
    )
  }
