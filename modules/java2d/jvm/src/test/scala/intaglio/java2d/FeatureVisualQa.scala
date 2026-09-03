package intaglio.java2d

import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import javax.imageio.ImageIO
import intaglio.*

private[java2d] final case class PixelBounds(
    minimumX: Int,
    minimumY: Int,
    maximumXExclusive: Int,
    maximumYExclusive: Int
)

private[java2d] final case class FeatureVisualCase(
    name: String,
    peer: String,
    reviewContract: String,
    renderPlan: RenderPlan,
    geometryBounds: PixelBounds,
    geometryThreshold: PerceptualThreshold = PerceptualThreshold(0.001, 0.03)
)

private[java2d] object FeatureVisualCase:
  /** The threshold for a case whose compared region is mostly text.
    *
    * AWT rasterizes the same pinned font differently on macOS and Linux --- hinting and subpixel
    * positioning are the host's, not the file's --- so a region dominated by titles and axis labels
    * carries a host-sized difference that is not a regression. The measured macOS-to-Linux delta
    * for the `composition` case is a changed fraction of 0.0060 and a mean channel error of 0.253;
    * this threshold clears both with roughly threefold and twofold headroom while still rejecting a
    * material change, which `FeatureVisualRegressionSuite` proves for every case that uses it.
    */
  val textDominatedThreshold: PerceptualThreshold =
    PerceptualThreshold(changedFraction = 0.02, meanChannelError = 0.5)

/** Deterministic fixtures for visual features added after the original plotting comparison gallery.
  * Each case compiles through the public grammar and ordinary render-plan boundary.
  */
private[java2d] object FeatureVisualFixtures:
  val width = 640
  val height = 480
  val fullThreshold = PerceptualThreshold(changedFraction = 0.02, meanChannelError = 0.5)
  val resourceRoot = "/intaglio/java2d/golden/features"
  val repositoryRoot: Path =
    Paths.get("modules/java2d/jvm/src/test/resources/intaglio/java2d/golden/features")

  private val navy = Rgba.unsafe(35, 60, 90)
  private val blue = Rgba.unsafe(70, 125, 180)
  private val orange = Rgba.unsafe(220, 125, 55)
  private val green = Rgba.unsafe(55, 145, 105)

  val context: RenderContext = RenderContext.unsafe(
    width,
    height,
    pixelsPerInch = 96.0,
    fontRegistry = FontRegistry(_ => Some("Pinned Visual QA Sans"))
  )

  val cases: Vector[FeatureVisualCase] = Vector(
    FeatureVisualCase(
      "temporal-zoom",
      "ggplot2",
      "Monthly ISO ticks, the mid-January-through-mid-May coordinate window, clipping, and the visible line shape should agree; theme spacing and glyph rasterization may differ.",
      temporalZoomPlan,
      PixelBounds(85, 55, 615, 385)
    ),
    FeatureVisualCase(
      "style-aesthetics",
      "ggplot2",
      "Point shape, size, stroke/fill, grouped line type/width, and rotated text anchors should convey the same channels; exact symbol and dash rasterization may differ.",
      styleAestheticsPlan,
      PixelBounds(85, 55, 615, 405),
      FeatureVisualCase.textDominatedThreshold
    ),
    FeatureVisualCase(
      "ecdf",
      "ggplot2",
      "Ties, per-group cumulative mass, right-continuous steps, and the zero baseline should agree; group colors are intentionally renderer-authored.",
      ecdfPlan,
      PixelBounds(85, 55, 615, 390)
    ),
    FeatureVisualCase(
      "quantile-summary",
      "ggplot2",
      "Type-7 first quartile, median, and third quartile positions should agree for both groups; Intaglio intentionally renders a compact interval rather than a full boxplot.",
      quantilePlan,
      PixelBounds(85, 55, 615, 390)
    ),
    FeatureVisualCase(
      "composition",
      "ggplot2 + patchwork",
      "Two independently trained plots should occupy aligned side-by-side panels despite very different y-label widths; titles and physical gaps need only remain legible and non-overlapping.",
      compositionPlan,
      // Nearly the whole frame, and most of it is two titles and two sets of axis labels, so this
      // case reads glyph rasterization more than geometry --- exactly what its contract above says
      // it does not judge.
      PixelBounds(30, 45, 625, 405),
      FeatureVisualCase.textDominatedThreshold
    )
  )

  def render(example: FeatureVisualCase): BufferedImage =
    Java2DRenderer
      .renderImage(
        example.renderPlan,
        GoldenFixture.exportOptions,
        Java2DFontResolver.fixed(GoldenFixture.font)
      )
      .orThrow

  def expected(example: FeatureVisualCase): BufferedImage =
    val resource = s"$resourceRoot/${example.name}.png"
    val input = Option(getClass.getResourceAsStream(resource)).getOrElse(
      throw new IllegalStateException(
        s"missing golden $resource; run tools/update-feature-visual-goldens.sh and review every artifact"
      )
    )
    try Option(ImageIO.read(input)).getOrElse(throw new IllegalStateException(s"invalid $resource"))
    finally input.close()

  private final case class Visit(day: CalendarDate, score: Double)

  private def temporalZoomPlan: RenderPlan =
    val visits = Vector(
      Visit(CalendarDate.parseUnsafe("2024-01-01"), 1.0),
      Visit(CalendarDate.parseUnsafe("2024-02-01"), 1.7),
      Visit(CalendarDate.parseUnsafe("2024-03-01"), 1.4),
      Visit(CalendarDate.parseUnsafe("2024-04-01"), 2.5),
      Visit(CalendarDate.parseUnsafe("2024-05-01"), 2.1),
      Visit(CalendarDate.parseUnsafe("2024-06-01"), 3.0)
    )
    val yScale = ContinuousScaleSpec.numeric("score").orThrow
    val window = CoordinateWindow.dateUnsafe(
      CalendarDate.parseUnsafe("2024-01-15"),
      CalendarDate.parseUnsafe("2024-05-15")
    )
    plot(visits)
      .scaleXDate(
        _.day,
        name = "visit",
        breaks = TemporalBreaks.everyUnsafe(1, TemporalUnit.Month)
      )
      .encode(Aesthetic.Y, _.score, yScale)
      .coordZoomWindows(x = Some(window), clip = Clip.On)
      .geomLine(
        params = Some(
          GraphicParams
            .unsafe(stroke = Some(blue), lineWidth = 2.0, lineWidthUnit = StrokeUnit.Point)
        )
      )
      .geomPoint(
        params =
          Some(GraphicParams.unsafe(stroke = Some(navy), fill = Some(orange), lineWidth = 1.5))
      )
      .title("Temporal zoom")
      .axisTitles("visit", "score")
      .renderPlan(context)
      .orThrow

  private final case class StyledDatum(
      kind: String,
      x: Double,
      y: Double,
      group: String,
      label: String,
      shape: PointShape,
      size: Double,
      lineType: LineType,
      lineWidth: Double,
      angle: Double,
      hJust: HJust,
      vJust: VJust,
      stroke: Rgba,
      fill: Rgba
  )

  private def styleAestheticsPlan: RenderPlan =
    val pointRows = Vector(
      styled("point", 1.0, 3.0, "points", "circle", PointShape.Circle, 5.0, blue, orange),
      styled("point", 2.0, 3.0, "points", "square", PointShape.Square, 7.0, green, Rgba.White),
      styled("point", 3.0, 3.0, "points", "triangle", PointShape.Triangle, 9.0, orange, blue),
      styled("point", 4.0, 3.0, "points", "cross", PointShape.Cross, 11.0, navy, Rgba.White)
    )
    val lineRows = Vector(
      styledLine(1.0, 1.0, "solid", LineType.Solid, 1.5, blue),
      styledLine(2.0, 1.8, "solid", LineType.Solid, 1.5, blue),
      styledLine(3.0, 1.3, "solid", LineType.Solid, 1.5, blue),
      styledLine(4.0, 2.0, "solid", LineType.Solid, 1.5, blue),
      styledLine(1.0, 2.0, "dashed", LineType.Dashed, 2.5, orange),
      styledLine(2.0, 1.2, "dashed", LineType.Dashed, 2.5, orange),
      styledLine(3.0, 2.2, "dashed", LineType.Dashed, 2.5, orange),
      styledLine(4.0, 1.5, "dashed", LineType.Dashed, 2.5, orange)
    )
    val textRows = Vector(
      styledText(1.0, 4.0, "left", -30.0, HJust.Left, VJust.Bottom),
      styledText(2.5, 4.0, "center", 0.0, HJust.Center, VJust.Center),
      styledText(4.0, 4.0, "right", 30.0, HJust.Right, VJust.Top)
    )
    val allRows = pointRows ++ lineRows ++ textRows
    val pointMapping = AesSpec
      .empty[StyledDatum]
      .withPosition(_.x, _.y)
      .withShape(_.shape)
      .withSize(_.size)
      .withColor(_.stroke)
      .withFill(_.fill)
    val lineMapping = AesSpec
      .empty[StyledDatum]
      .withPosition(_.x, _.y)
      .withGroup(_.group)
      .withLineType(_.lineType)
      .withLineWidth(_.lineWidth)
      .withColor(_.stroke)
    val textMapping = AesSpec
      .empty[StyledDatum]
      .withPosition(_.x, _.y)
      .withLabel(_.label)
      .withAngle(_.angle)
      .withHJust(_.hJust)
      .withVJust(_.vJust)
      .withFill(_.fill)
    val points = Layer
      .fromMapping(Geom.Point, pointMapping, data = Some(pointRows), inheritMapping = false)
      .orThrow
    val lines = Layer
      .fromMapping(Geom.Line, lineMapping, data = Some(lineRows), inheritMapping = false)
      .orThrow
    val labels = Layer
      .fromMapping(Geom.Text, textMapping, data = Some(textRows), inheritMapping = false)
      .orThrow
    val plot = Plot(allRows)
      .addLayer(lines)
      .flatMap(_.addLayer(points))
      .flatMap(_.addLayer(labels))
      .map(
        _.withLabels(
          PlotLabels(title = Some("Typed style aesthetics"), x = Some("x"), y = Some("y"))
        )
      )
      .orThrow
    PlotCompiler
      .compile(plot, context, plottingOptions)
      .orThrow

  private final case class Distribution(value: Double, group: String)

  private def ecdfPlan: RenderPlan =
    val rows = Vector(
      Distribution(2.0, "A"),
      Distribution(1.0, "A"),
      Distribution(2.0, "A"),
      Distribution(4.0, "A"),
      Distribution(3.0, "B"),
      Distribution(3.0, "B"),
      Distribution(1.0, "B")
    )
    val first = Layer.ecdf[Distribution](
      _.value,
      data = Some(rows.filter(_.group == "A")),
      params = Some(
        GraphicParams
          .unsafe(stroke = Some(blue), lineWidth = 2.0, lineWidthUnit = StrokeUnit.Point)
      )
    )
    val second = Layer.ecdf[Distribution](
      _.value,
      data = Some(rows.filter(_.group == "B")),
      params = Some(
        GraphicParams
          .unsafe(stroke = Some(orange), lineWidth = 2.0, lineWidthUnit = StrokeUnit.Point)
      )
    )
    Plot(rows)
      .addLayer(first)
      .flatMap(_.addLayer(second))
      .map(
        _.withLabels(
          PlotLabels(
            title = Some("Grouped ECDF"),
            x = Some("value"),
            y = Some("cumulative proportion")
          )
        )
      )
      .flatMap(PlotCompiler.compile(_, context, plottingOptions))
      .orThrow

  private final case class Summary(position: Double, value: Double)

  private def quantilePlan: RenderPlan =
    val rows = Vector(
      Summary(1.0, 1.0),
      Summary(1.0, 2.0),
      Summary(1.0, 3.0),
      Summary(1.0, 4.0),
      Summary(1.0, 100.0),
      Summary(2.0, 0.0),
      Summary(2.0, 10.0),
      Summary(2.0, 20.0),
      Summary(2.0, 30.0)
    )
    plot(rows)
      .aes(_.position, _.value)
      .geomQuantileSummary(
        params = Some(
          GraphicParams.unsafe(
            stroke = Some(navy),
            fill = Some(orange),
            lineWidth = 2.0,
            lineWidthUnit = StrokeUnit.Point
          )
        )
      )
      .title("Type-7 quartile summary")
      .axisTitles("group", "value")
      .compilerOptions(plottingOptions)
      .renderPlan(context)
      .orThrow

  private final case class Observation(x: Double, y: Double)

  private def compositionPlan: RenderPlan =
    val first = trainedPoints(
      "Compact scale",
      Vector(Observation(1.0, 1.0), Observation(2.0, 2.0), Observation(3.0, 1.5)),
      blue
    )
    val second = trainedPoints(
      "Wide labels",
      Vector(
        Observation(1.0, 10000.0),
        Observation(2.0, 30000.0),
        Observation(3.0, 20000.0)
      ),
      orange
    )
    PlotComposition
      .row(
        Vector(first, second),
        context,
        CompositionOptions.unsafe(columnGapPt = Some(14.0), cellClip = Clip.On)
      )
      .map(_.renderPlan)
      .orThrow

  private def trainedPoints(
      title: String,
      rows: Vector[Observation],
      color: Rgba
  ): TrainedPlot =
    plot(rows)
      .aes(_.x, _.y)
      .geomLine(
        params = Some(
          GraphicParams
            .unsafe(stroke = Some(color), lineWidth = 1.5, lineWidthUnit = StrokeUnit.Point)
        )
      )
      .geomPoint(params = Some(GraphicParams.unsafe(stroke = Some(navy), fill = Some(color))))
      .title(title)
      .axisTitles("x", "response")
      .compilerOptions(plottingOptions)
      .resolve(context)
      .orThrow

  private def plottingOptions: PlotCompilerOptions =
    PlotCompilerOptions(
      policy = Some(LayoutPolicy()),
      guides = GuidePolicy.Derived()
    )

  private def styled(
      kind: String,
      x: Double,
      y: Double,
      group: String,
      label: String,
      shape: PointShape,
      size: Double,
      stroke: Rgba,
      fill: Rgba
  ): StyledDatum =
    StyledDatum(
      kind,
      x,
      y,
      group,
      label,
      shape,
      size,
      LineType.Solid,
      1.0,
      0.0,
      HJust.Center,
      VJust.Center,
      stroke,
      fill
    )

  private def styledLine(
      x: Double,
      y: Double,
      group: String,
      lineType: LineType,
      lineWidth: Double,
      color: Rgba
  ): StyledDatum =
    StyledDatum(
      "line",
      x,
      y,
      group,
      "",
      PointShape.Circle,
      1.0,
      lineType,
      lineWidth,
      0.0,
      HJust.Center,
      VJust.Center,
      color,
      color
    )

  private def styledText(
      x: Double,
      y: Double,
      label: String,
      angle: Double,
      hJust: HJust,
      vJust: VJust
  ): StyledDatum =
    StyledDatum(
      "text",
      x,
      y,
      "labels",
      label,
      PointShape.Circle,
      1.0,
      LineType.Solid,
      1.0,
      angle,
      hJust,
      vJust,
      navy,
      navy
    )

/** Writes current Intaglio images and a paired HTML review surface. The peer images are generated
  * independently by `tools/r-parity/render_feature_reference.R` before this runner starts.
  */
object FeatureVisualQa:
  def main(args: Array[String]): Unit =
    val root = Paths.get(args.headOption.getOrElse("target/feature-visual-qa"))
    val intaglioDir = root.resolve("intaglio")
    Files.createDirectories(intaglioDir)
    FeatureVisualFixtures.cases.foreach { example =>
      ImageIO.write(
        FeatureVisualFixtures.render(example),
        "png",
        intaglioDir.resolve(s"${example.name}.png").toFile
      )
    }
    Files.writeString(root.resolve("index.html"), comparisonHtml, StandardCharsets.UTF_8)
    Files.writeString(root.resolve("manifest.tsv"), manifest, StandardCharsets.UTF_8)
    println(s"wrote recent-feature visual QA to $root")

  private def manifest: String =
    FeatureVisualFixtures.cases
      .map(example =>
        Vector(
          example.name,
          s"intaglio/${example.name}.png",
          s"reference/${example.name}.png",
          example.peer,
          example.reviewContract
        ).mkString("\t")
      )
      .mkString("case\tintaglio\treference\tpeer\treview_contract\n", "\n", "\n")

  private def comparisonHtml: String =
    val rows = FeatureVisualFixtures.cases
      .map { example =>
        val contract = escapeHtml(example.reviewContract)
        val peer = escapeHtml(example.peer)
        s"""      <section>
           |        <h2>${escapeHtml(example.name)}</h2>
           |        <p class="contract"><strong>Review:</strong> $contract</p>
           |        <figure><figcaption>Intaglio / Java2D</figcaption><img src="intaglio/${example.name}.png" alt="Intaglio ${example.name}"></figure>
           |        <figure><figcaption>$peer reference</figcaption><img src="reference/${example.name}.png" alt="$peer ${example.name} reference"></figure>
           |      </section>""".stripMargin
      }
      .mkString("\n")
    s"""<!doctype html>
       |<html lang="en">
       |  <head>
       |    <meta charset="utf-8">
       |    <meta name="viewport" content="width=device-width, initial-scale=1">
       |    <title>Intaglio recent-feature visual QA</title>
       |    <style>
       |      body { margin: 0; padding: 2rem; color: #18212b; background: #f3f5f7; font: 15px/1.45 system-ui, sans-serif; }
       |      h1 { margin-top: 0; }
       |      .scope { max-width: 78rem; }
       |      section { display: grid; grid-template-columns: repeat(2, minmax(0, 640px)); gap: 1rem; margin: 0 0 1.5rem; padding: 1rem; background: white; border: 1px solid #d7dde3; border-radius: 8px; }
       |      h2, .contract { grid-column: 1 / -1; margin: 0; }
       |      figure { margin: 0; }
       |      figcaption { margin-bottom: 0.5rem; font-weight: 650; }
       |      img { display: block; width: 100%; max-width: 640px; height: auto; border: 1px solid #edf0f2; }
       |      @media (max-width: 900px) { section { grid-template-columns: minmax(0, 640px); } h2, .contract { grid-column: 1; } }
       |    </style>
       |  </head>
       |  <body>
       |    <h1>Recent-feature visual QA</h1>
       |    <p class="scope">Peer images are semantic references, not pixel or theme oracles. Review each pair at native size using its stated contract.</p>
       |$rows
       |  </body>
       |</html>
       |""".stripMargin

  private def escapeHtml(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")

/** Deliberately gated updater. Tests never rewrite their own feature oracles. */
object FeatureVisualGoldenUpdate:
  def main(args: Array[String]): Unit =
    if args.toVector != Vector("--accept") then
      throw new IllegalArgumentException(
        "feature golden updates require --accept after native-size review against peer references"
      )
    Files.createDirectories(FeatureVisualFixtures.repositoryRoot)
    FeatureVisualFixtures.cases.foreach { example =>
      val path = FeatureVisualFixtures.repositoryRoot.resolve(s"${example.name}.png")
      val written = ImageIO.write(FeatureVisualFixtures.render(example), "png", path.toFile)
      if !written then throw new IllegalStateException("no PNG ImageIO writer is available")
      println(s"updated $path sha256=${sha256(path)}")
    }

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
