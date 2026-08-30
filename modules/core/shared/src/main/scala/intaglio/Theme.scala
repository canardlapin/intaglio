package intaglio

/** Complete leaf styles for axes. `None` in a [[GuideSpec]] means use these values; an explicit
  * guide style remains authoritative.
  */
final case class AxisTheme(
    line: GraphicParams,
    tick: GraphicParams,
    text: GraphicParams,
    title: GraphicParams
)

final case class LegendTheme(
    text: GraphicParams,
    title: GraphicParams
)

final case class PlotTextTheme(
    title: GraphicParams,
    subtitle: GraphicParams
)

/** Optional panel decoration. Absence is meaningful: the default theme adds no marks that were not
  * requested by the plot.
  */
final case class PanelTheme(
    background: Option[GraphicParams] = None,
    grid: Option[GraphicParams] = None
)

/** Default palettes available to scale constructors without introducing a mutable registry or
  * implicit ambient state.
  */
final case class ThemePalettes(
    discrete: Vector[Rgba],
    continuousLow: Rgba,
    continuousHigh: Rgba
):
  require(discrete.nonEmpty, "`discrete` must be non-empty")

  def discretePalette: DiscretePalette[Rgba] =
    DiscretePalette.valuesUnsafe(discrete)

  def continuousPalette: Palette[Rgba] =
    Palette.gradient(continuousLow, continuousHigh)

/** Immutable plot defaults resolved once by [[PlotCompiler]]. A theme is a finite product of
  * complete values: there is no cascade, selector language, mutable global, or backend-specific
  * styling hook.
  */
final case class Theme(
    geom: GraphicParams,
    pointSizePt: Double,
    axis: AxisTheme,
    legend: LegendTheme,
    plotText: PlotTextTheme,
    panel: PanelTheme,
    palettes: ThemePalettes,
    layout: LayoutPolicy
):
  require(pointSizePt > 0.0 && pointSizePt.isFinite, "`pointSizePt` must be finite and > 0")
  requirePointFont("axis.text", axis.text)
  requirePointFont("axis.title", axis.title)
  requirePointFont("legend.text", legend.text)
  requirePointFont("legend.title", legend.title)
  requirePointFont("plot.title", plotText.title)
  requirePointFont("plot.subtitle", plotText.subtitle)

  /** Layout measures the exact typography later emitted into guide and label grobs. Non-typographic
    * spacing and the text-metrics capability remain configurable through `layout`.
    */
  def layoutPolicy: LayoutPolicy =
    layoutPolicy(layout)

  def layoutPolicy(base: LayoutPolicy): LayoutPolicy =
    base.copy(
      axisFontPt = pointFont(axis.text),
      axisFontFamily = axis.text.fontFamily,
      axisTitleFontPt = pointFont(axis.title),
      axisTitleFontFamily = axis.title.fontFamily,
      plotTitleFontPt = pointFont(plotText.title),
      plotTitleFontFamily = plotText.title.fontFamily,
      plotSubtitleFontPt = pointFont(plotText.subtitle),
      plotSubtitleFontFamily = plotText.subtitle.fontFamily,
      legendFontPt = pointFont(legend.text),
      legendFontFamily = legend.text.fontFamily,
      legendTitleFontPt = pointFont(legend.title),
      legendTitleFontFamily = legend.title.fontFamily
    )

  private def requirePointFont(label: String, gp: GraphicParams): Unit =
    require(
      gp.fontSize.unit == LengthUnit.Point && gp.fontSize.value > 0.0,
      s"`$label` font size must be positive points"
    )

  private def pointFont(gp: GraphicParams): Double =
    gp.fontSize.value

object Theme:
  private def text(sizePt: Double): GraphicParams =
    GraphicParams.unsafe(
      stroke = None,
      fill = Some(Rgba.Black),
      fontSize = Length.pointsUnsafe(sizePt)
    )

  val defaultPalettes: ThemePalettes =
    ThemePalettes(
      discrete = Vector(
        Rgba.unsafe(31, 119, 180),
        Rgba.unsafe(255, 127, 14),
        Rgba.unsafe(44, 160, 44),
        Rgba.unsafe(214, 39, 40),
        Rgba.unsafe(148, 103, 189),
        Rgba.unsafe(140, 86, 75)
      ),
      continuousLow = Rgba.unsafe(239, 243, 255),
      continuousHigh = Rgba.unsafe(8, 81, 156)
    )

  val default: Theme =
    Theme(
      geom = GraphicParams.unsafe(),
      pointSizePt = 4.0,
      axis = AxisTheme(
        line = GraphicParams.unsafe(),
        tick = GraphicParams.unsafe(),
        text = text(10.0),
        title = text(11.0)
      ),
      legend = LegendTheme(text = text(10.0), title = text(10.0)),
      plotText = PlotTextTheme(title = text(16.0), subtitle = text(12.0)),
      panel = PanelTheme(),
      palettes = defaultPalettes,
      layout = LayoutPolicy()
    )

  /** A quiet publication-oriented base: white panel, pale grid, crisp axes. Callers customize it
    * with ordinary case-class `copy` operations.
    */
  val minimal: Theme =
    default.copy(
      axis = default.axis.copy(
        line = GraphicParams.unsafe(lineWidth = 0.75),
        tick = GraphicParams.unsafe(lineWidth = 0.75)
      ),
      panel = PanelTheme(
        background = Some(GraphicParams.unsafe(stroke = None, fill = Some(Rgba.White))),
        grid = Some(
          GraphicParams.unsafe(
            stroke = Some(Rgba.unsafe(224, 228, 234)),
            fill = None,
            lineWidth = 0.6
          )
        )
      )
    )
