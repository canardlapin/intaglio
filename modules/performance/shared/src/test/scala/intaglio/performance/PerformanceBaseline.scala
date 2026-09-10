package intaglio.performance

private[performance] final case class PerformanceBaseline(
    workload: String,
    metric: String,
    recorded: Long,
    highSeverityLimit: Long,
    rationale: String
):
  def key: String =
    s"$workload.$metric"

private[performance] object PerformanceBaselines:
  val schemaVersion: Int = 1
  val sourceSha: String = "5cfaceecb60bbbacb96f18d900c76f7b5bc19428"

  val scatterMarks: Int = 20000
  val rasterWidth: Int = 256
  val rasterHeight: Int = 256
  val positionCategories: Int = 500
  val positionGroups: Int = 20
  val discreteLevels: Int = 8192
  val discreteMisses: Int = 1024
  val histogramSamples: Int = 50000
  val histogramBins: Int = 256
  val svgMarks: Int = 10000

  val entries: Vector[PerformanceBaseline] =
    Vector(
      PerformanceBaseline(
        "scatter",
        "retained_rows",
        0,
        0,
        "Lean compilation must not retain one resolved row per mark"
      ),
      PerformanceBaseline(
        "scatter",
        "grobs",
        1,
        1,
        "Large point layers must remain one columnar grob"
      ),
      PerformanceBaseline(
        "scatter",
        "device_primitives",
        1,
        1,
        "Device lowering must preserve one point batch"
      ),
      PerformanceBaseline(
        "scatter",
        "batch_coordinates",
        scatterMarks.toLong,
        scatterMarks.toLong,
        "The batch carries exactly one coordinate per source mark"
      ),
      PerformanceBaseline(
        "raster",
        "packed_bytes",
        rasterWidth.toLong * rasterHeight.toLong * 4L,
        rasterWidth.toLong * rasterHeight.toLong * 4L,
        "Packed rasters retain one four-byte pixel word"
      ),
      PerformanceBaseline(
        "raster",
        "device_primitives",
        1,
        1,
        "One raster grob lowers to one device image"
      ),
      PerformanceBaseline(
        "raster",
        "svg_bytes",
        350260,
        437825,
        "A 25 percent ceiling guards deterministic PNG and base64 growth"
      ),
      PerformanceBaseline(
        "dodge",
        "output_rows",
        positionCategories.toLong * positionGroups.toLong,
        positionCategories.toLong * positionGroups.toLong,
        "Dodge must not duplicate adjusted rows"
      ),
      PerformanceBaseline(
        "dodge",
        "grobs",
        positionCategories.toLong * positionGroups.toLong,
        positionCategories.toLong * positionGroups.toLong,
        "Dodge lowering remains one rectangle per input row"
      ),
      PerformanceBaseline(
        "stack",
        "output_rows",
        positionCategories.toLong * positionGroups.toLong,
        positionCategories.toLong * positionGroups.toLong,
        "Stack must not duplicate adjusted rows"
      ),
      PerformanceBaseline(
        "stack",
        "grobs",
        positionCategories.toLong * positionGroups.toLong,
        positionCategories.toLong * positionGroups.toLong,
        "Stack lowering remains one rectangle per input row"
      ),
      PerformanceBaseline(
        "discrete_lookup",
        "identity_calls",
        discreteLevels.toLong + discreteMisses.toLong,
        discreteLevels.toLong + discreteMisses.toLong,
        "Every indexed lookup derives its stable identity exactly once"
      ),
      PerformanceBaseline(
        "histogram",
        "compiled_bins",
        histogramBins.toLong,
        histogramBins.toLong,
        "Large generated histograms retain one output per requested bin"
      ),
      PerformanceBaseline(
        "histogram",
        "slow_path_penalty",
        0,
        0,
        "Generated bins use arithmetic lookup and explicit breaks use binary search"
      ),
      PerformanceBaseline(
        "svg",
        "mark_elements",
        svgMarks.toLong,
        svgMarks.toLong,
        "Point batches serialize exactly one SVG element per mark"
      ),
      PerformanceBaseline(
        "svg",
        "bytes",
        1336945,
        1671182,
        "A 25 percent ceiling guards large-scatter serialization growth"
      )
    )

  val byKey: Map[String, PerformanceBaseline] =
    entries.map(entry => entry.key -> entry).toMap
