package intaglio

import scala.annotation.implicitNotFound

/** Select the final compiler theme's palette when a plotting DSL scale omits an explicit palette.
  * The marker keeps omission distinct from an explicitly supplied empty palette.
  */
object ThemePalette:
  case object Default

/** Position mapping states carried by [[PlotBuilder]]. They make geom prerequisites visible to the
  * Scala compiler without exposing compiler phases or requiring a macro-based syntax layer.
  */
sealed trait PlotPosition[Row]

object PlotPosition:
  final case class Empty[Row]() extends PlotPosition[Row]

  sealed trait WithX[Row] extends PlotPosition[Row]:
    def x: Row => Double

  final case class X[Row](x: Row => Double) extends WithX[Row]

  final case class XY[Row](x: Row => Double, y: Row => Double) extends WithX[Row]

@implicitNotFound("This plotting operation requires x. Call .aes(x) or .aes(x, y) first.")
sealed trait HasX[Row, Position <: PlotPosition[Row]]:
  def apply(position: Position): PlotPosition.WithX[Row]

object HasX:
  given x[Row]: HasX[Row, PlotPosition.X[Row]] with
    def apply(position: PlotPosition.X[Row]): PlotPosition.WithX[Row] = position

  given xy[Row]: HasX[Row, PlotPosition.XY[Row]] with
    def apply(position: PlotPosition.XY[Row]): PlotPosition.WithX[Row] = position

@implicitNotFound("This plotting operation requires x and y. Call .aes(x, y) first.")
sealed trait HasXY[Row, Position <: PlotPosition[Row]]:
  def apply(position: Position): PlotPosition.XY[Row]

object HasXY:
  given xy[Row]: HasXY[Row, PlotPosition.XY[Row]] with
    def apply(position: PlotPosition.XY[Row]): PlotPosition.XY[Row] = position

/** An executable, inspectable plotting value.
  *
  * The public DSL stops here: callers can inspect or further compose the renderer-neutral [[Plot]],
  * resolve it to a [[TrainedPlot]], or compile it to a [[Scene]]. Concrete renderers remain
  * separate modules.
  */
final case class PlotProgram[Row] private[intaglio] (
    plot: Plot[Row],
    compilerOptions: PlotCompilerOptions
):
  def resolve: Either[GraphicsError, TrainedPlot] =
    PlotCompiler.resolve(plot, compilerOptions)

  def scene: Either[GraphicsError, Scene] =
    PlotCompiler.compile(plot, compilerOptions)

/** Immutable user-facing plotting builder.
  *
  * Every operation returns another builder. Errors from checked scales, coordinates, and layer
  * validation accumulate in `build` as `GraphicsError`; `resolve` and `scene` retain typed compiler
  * errors. No exception or backend value crosses the API boundary.
  */
final class PlotBuilder[Row, Position <: PlotPosition[Row]] private[intaglio] (
    private val data: Vector[Row],
    private val position: Position,
    private val result: Either[GraphicsError, Plot[Row]],
    private val options: PlotCompilerOptions
):

  def aes(x: Row => Double): PlotBuilder[Row, PlotPosition.X[Row]] =
    remap(
      PlotPosition.X(x),
      mapping => mapping.copy(x = Some(AesValue.direct(x)), y = None)
    )

  def aes(x: Row => Double, y: Row => Double): PlotBuilder[Row, PlotPosition.XY[Row]] =
    remap(PlotPosition.XY(x, y), _.withPosition(x, y))

  def aes(
      x: Row => Double,
      y: Row => Double,
      color: Row => Rgba
  ): PlotBuilder[Row, PlotPosition.XY[Row]] =
    remap(PlotPosition.XY(x, y), _.withPosition(x, y).withColor(color))

  def color(value: Row => Rgba): PlotBuilder[Row, Position] =
    mapAesthetics(_.withColor(value))

  def color(value: Rgba): PlotBuilder[Row, Position] =
    mapAesthetics(_.withColor(value))

  def fill(value: Row => Rgba): PlotBuilder[Row, Position] =
    mapAesthetics(_.withFill(value))

  def fill(value: Rgba): PlotBuilder[Row, Position] =
    mapAesthetics(_.withFill(value))

  def alpha(value: Row => Double): PlotBuilder[Row, Position] =
    mapAesthetics(_.withAlpha(value))

  def alpha(value: Double): PlotBuilder[Row, Position] =
    mapAesthetics(_.withAlpha(value))

  def size(value: Row => Double): PlotBuilder[Row, Position] =
    mapAesthetics(_.withSize(value))

  def size(value: Double): PlotBuilder[Row, Position] =
    mapAesthetics(_.withSize(value))

  def group(value: Row => String): PlotBuilder[Row, Position] =
    mapAesthetics(_.withGroup(value))

  /** Identify independently closed subpaths within one polygon group. This retains holes as
    * geometry instead of flattening them into backend tricks.
    */
  def subpath(value: Row => String): PlotBuilder[Row, Position] =
    mapAesthetics(_.withSubpath(value))

  def scaleXContinuous(
      name: String = "x",
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor
  )(using ev: HasX[Row, Position]): PlotBuilder[Row, Position] =
    val x = ev(position).x
    bindContinuous(Aesthetic.X, x, name, Palette.numeric, transform, oob)

  def scaleYContinuous(
      name: String = "y",
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor
  )(using ev: HasXY[Row, Position]): PlotBuilder[Row, Position] =
    val y = ev(position).y
    bindContinuous(Aesthetic.Y, y, name, Palette.numeric, transform, oob)

  def scaleColorDiscrete(
      value: Row => String,
      levels: Vector[String] = Vector.empty,
      colors: Vector[Rgba] | ThemePalette.Default.type = ThemePalette.Default,
      name: String = "color",
      overflow: PaletteOverflowPolicy = PaletteOverflowPolicy.Reject
  ): PlotBuilder[Row, Position] =
    colors match
      case ThemePalette.Default =>
        bindThemeDiscrete(Aesthetic.Color, value, levels, name, overflow)
      case explicit: Vector[?] =>
        bindDiscrete(
          Aesthetic.Color,
          value,
          levels,
          explicit.asInstanceOf[Vector[Rgba]],
          name,
          overflow
        )

  def scaleFillDiscrete(
      value: Row => String,
      levels: Vector[String] = Vector.empty,
      colors: Vector[Rgba] | ThemePalette.Default.type = ThemePalette.Default,
      name: String = "fill",
      overflow: PaletteOverflowPolicy = PaletteOverflowPolicy.Reject
  ): PlotBuilder[Row, Position] =
    colors match
      case ThemePalette.Default =>
        bindThemeDiscrete(Aesthetic.Fill, value, levels, name, overflow)
      case explicit: Vector[?] =>
        bindDiscrete(
          Aesthetic.Fill,
          value,
          levels,
          explicit.asInstanceOf[Vector[Rgba]],
          name,
          overflow
        )

  def scaleFillContinuous(
      value: Row => Double,
      palette: Palette[Rgba] | ThemePalette.Default.type = ThemePalette.Default,
      name: String = "fill",
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor
  ): PlotBuilder[Row, Position] =
    palette match
      case ThemePalette.Default =>
        bindThemeContinuous(Aesthetic.Fill, value, name, transform, oob)
      case explicit: Palette[?] =>
        bindContinuous(
          Aesthetic.Fill,
          value,
          name,
          explicit.asInstanceOf[Palette[Rgba]],
          transform,
          oob
        )

  /** Bind a row-free [[ScaleSpec]] or an already prepared [[Scale]] — a log transform, a fixed
    * domain, a bespoke palette — without dropping to the low-level `Plot` API. The builder fixes
    * `Row`, so no `ScaleBinding` type parameters appear: `encode(Aesthetic.X, _.x, xScale)`.
    */
  def encode[In, Out](
      aesthetic: Aesthetic[Out],
      value: Row => In,
      scale: ScaleValue[In, Out]
  ): PlotBuilder[Row, Position] =
    updateResult(result.flatMap(_.encode(aesthetic, value, scale)))

  def geomPoint(
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using HasXY[Row, Position]): PlotBuilder[Row, Position] =
    addInheritedGeom(Geom.Point, data, params)

  def geomLine(
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using HasXY[Row, Position]): PlotBuilder[Row, Position] =
    addInheritedGeom(Geom.Line, data, params)

  def geomPolygon(
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using HasXY[Row, Position]): PlotBuilder[Row, Position] =
    addInheritedGeom(Geom.Polygon, data, params)

  def geomText(
      label: Row => String,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using HasXY[Row, Position]): PlotBuilder[Row, Position] =
    addLayer(
      Layer.fromMapping(
        Geom.Text,
        AesSpec.empty[Row].withLabel(label),
        data = data,
        inheritMapping = true,
        params = params
      )
    )

  def geomHistogram(
      bins: HistogramBins = HistogramBins.default,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using ev: HasX[Row, Position]): PlotBuilder[Row, Position] =
    addLayer(Right(Layer.histogram(ev(position).x, data, bins, params)))

  def geomDensity(
      config: DensityConfig = DensityConfig.default,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using ev: HasX[Row, Position]): PlotBuilder[Row, Position] =
    addLayer(Right(Layer.density(ev(position).x, data, config, params)))

  def geomSummary(
      interval: SummaryInterval = SummaryInterval.StandardError,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using ev: HasXY[Row, Position]): PlotBuilder[Row, Position] =
    val xy = ev(position)
    addLayer(Right(Layer.summary(xy.x, xy.y, data, interval, params)))

  def geomArea(
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using ev: HasXY[Row, Position]): PlotBuilder[Row, Position] =
    val xy = ev(position)
    addLayer(Right(Layer.area(xy.x, xy.y, data, resultMapping, params)))

  def geomRibbon(
      yMin: Row => Double,
      yMax: Row => Double,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using ev: HasX[Row, Position]): PlotBuilder[Row, Position] =
    addLayer(Right(Layer.ribbon(ev(position).x, yMin, yMax, data, resultMapping, params)))

  def geomErrorBar(
      yMin: Row => Double,
      yMax: Row => Double,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using ev: HasX[Row, Position]): PlotBuilder[Row, Position] =
    addLayer(Right(Layer.errorBar(ev(position).x, yMin, yMax, data, resultMapping, params)))

  def geomSegment(
      xEnd: Row => Double,
      yEnd: Row => Double,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using ev: HasXY[Row, Position]): PlotBuilder[Row, Position] =
    val xy = ev(position)
    addLayer(Right(Layer.segment(xy.x, xy.y, xEnd, yEnd, data, resultMapping, params)))

  def geomTile(
      width: Row => Double,
      height: Row => Double,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  )(using ev: HasXY[Row, Position]): PlotBuilder[Row, Position] =
    val xy = ev(position)
    addLayer(Right(Layer.tile(xy.x, xy.y, width, height, data, resultMapping, params)))

  /** Add a continuous-fill heatmap to a field-native plot. The equality witness makes this
    * operation unavailable to ordinary row plots without introducing a specialized mutable builder
    * hierarchy.
    */
  def geomHeatmap(
      palette: Palette[Rgba] | ThemePalette.Default.type = ThemePalette.Default,
      name: String = "value",
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor,
      params: GraphicParams = GraphicParams.unsafe(stroke = None)
  )(using fieldRows: Row =:= ScalarCell, ev: HasXY[Row, Position]): PlotBuilder[Row, Position] =
    scaleFillContinuous(row => fieldRows(row).value, palette, name, transform, oob)
      .geomTile(
        row => fieldRows(row).width,
        row => fieldRows(row).height,
        params = Some(params)
      )

  /** Add already-extracted contour paths. The capability witness prevents ordinary row plots from
    * accidentally claiming contour semantics.
    */
  def geomContour(
      params: Option[GraphicParams] = None
  )(using
      contourRows: Row =:= ContourVertex,
      ev: HasXY[Row, Position]
  ): PlotBuilder[Row, Position] =
    geomLine(params = params)

  /** Fill already-extracted contour bands. Each region is one compound polygon whose independently
    * closed subpaths retain explicit holes.
    */
  def geomFilledContour(
      palette: Palette[Rgba] | ThemePalette.Default.type = ThemePalette.Default,
      name: String = "level",
      params: GraphicParams = GraphicParams.unsafe(stroke = None)
  )(using
      bandRows: Row =:= ContourBandVertex,
      ev: HasXY[Row, Position]
  ): PlotBuilder[Row, Position] =
    scaleFillContinuous(row => bandRows(row).levelMid, palette, name)
      .geomPolygon(params = Some(params))

  def hline(
      y: Double,
      params: Option[GraphicParams] = None,
      scale: AnnotationScalePolicy = AnnotationScalePolicy.Train,
      facets: AnnotationFacetPolicy = AnnotationFacetPolicy.Repeat
  ): PlotBuilder[Row, Position] =
    addLayer(Right(Layer.hline(y, params = params, scale = scale, facets = facets)))

  def vline(
      x: Double,
      params: Option[GraphicParams] = None,
      scale: AnnotationScalePolicy = AnnotationScalePolicy.Train,
      facets: AnnotationFacetPolicy = AnnotationFacetPolicy.Repeat
  ): PlotBuilder[Row, Position] =
    addLayer(Right(Layer.vline(x, params = params, scale = scale, facets = facets)))

  def facetWrap(
      value: Row => String,
      columns: Int = 2,
      levels: Vector[String] = Vector.empty,
      scales: FacetScales = FacetScales.Shared
  ): PlotBuilder[Row, Position] =
    updateResult(
      for
        current <- result
        facet <- FacetSpec.wrap(value, columns, levels, scales)
      yield current.withFacet(facet)
    )

  def facetGrid(
      rows: Row => String,
      columns: Row => String,
      rowLevels: Vector[String] = Vector.empty,
      columnLevels: Vector[String] = Vector.empty,
      scales: FacetScales = FacetScales.Shared
  ): PlotBuilder[Row, Position] =
    updateResult(
      for
        current <- result
        facet <- FacetSpec.grid(rows, columns, rowLevels, columnLevels, scales)
      yield current.withFacet(facet)
    )

  def coord(coord: Coord): PlotBuilder[Row, Position] =
    updatePlot(_.withCoord(coord))

  def coordFixed(ratio: Double = 1.0, clip: Clip = Clip.On): PlotBuilder[Row, Position] =
    updateResult(Coord.fixed(ratio, clip).flatMap(coord => result.map(_.withCoord(coord))))

  def labels(value: PlotLabels): PlotBuilder[Row, Position] =
    updatePlot(_.withLabels(value))

  def title(value: String): PlotBuilder[Row, Position] =
    updatePlot(_.withTitle(value))

  def subtitle(value: String): PlotBuilder[Row, Position] =
    updatePlot(_.withSubtitle(value))

  def axisTitles(x: String, y: String): PlotBuilder[Row, Position] =
    updatePlot(_.withAxisTitles(x, y))

  def theme(value: Theme): PlotBuilder[Row, Position] =
    updateOptions(options.copy(theme = value))

  def guides(value: GuidePolicy): PlotBuilder[Row, Position] =
    updateOptions(options.copy(guides = value))

  def compilerOptions(value: PlotCompilerOptions): PlotBuilder[Row, Position] =
    updateOptions(value)

  /** Add a self-contained layer whose row type differs from the plot data. The required facet
    * policy keeps future faceting behavior explicit.
    */
  def independentLayer[LayerRow](
      data: Vector[LayerRow],
      layer: Layer[LayerRow],
      facetPolicy: LayerFacetPolicy[LayerRow]
  ): PlotBuilder[Row, Position] =
    updateResult(result.flatMap(_.addIndependentLayer(data, layer, facetPolicy)))

  def build: Either[GraphicsError, PlotProgram[Row]] =
    result.map(PlotProgram(_, options))

  def resolve: Either[GraphicsError, TrainedPlot] =
    build.flatMap(_.resolve)

  def scene: Either[GraphicsError, Scene] =
    build.flatMap(_.scene)

  private def resultMapping: AesSpec[Row] =
    result.toOption.map(_.mapping).getOrElse(AesSpec.empty)

  private def addInheritedGeom(
      geom: Geom,
      data: Option[Vector[Row]],
      params: Option[GraphicParams]
  ): PlotBuilder[Row, Position] =
    addLayer(Layer.fromMapping(geom, AesSpec.empty, data, inheritMapping = true, params = params))

  private def addLayer(layer: Either[GraphicsError, Layer[Row]]): PlotBuilder[Row, Position] =
    updateResult(for current <- result; next <- layer; plot <- current.addLayer(next) yield plot)

  private def bindContinuous[Out](
      aesthetic: Aesthetic[Out],
      value: Row => Double,
      name: String,
      palette: Palette[Out],
      transform: Transform,
      oob: OobPolicy
  ): PlotBuilder[Row, Position] =
    val next =
      for
        current <- result
        spec <- ContinuousScaleSpec(name, palette, transform, oob)
        plot <- current.withScale(ScaleBinding(aesthetic, value, spec))
      yield plot
    updateResult(next)

  private def bindThemeContinuous(
      aesthetic: Aesthetic[Rgba],
      value: Row => Double,
      name: String,
      transform: Transform,
      oob: OobPolicy
  ): PlotBuilder[Row, Position] =
    val next =
      for
        current <- result
        spec <- ContinuousScaleSpec.themeRgba(name, transform, oob)
        plot <- current.withScale(ScaleBinding(aesthetic, value, spec))
      yield plot
    updateResult(next)

  private def bindDiscrete(
      aesthetic: Aesthetic[Rgba],
      value: Row => String,
      levels: Vector[String],
      colors: Vector[Rgba],
      name: String,
      overflow: PaletteOverflowPolicy
  ): PlotBuilder[Row, Position] =
    val next =
      for
        current <- result
        palette <- DiscretePalette.values(colors, overflow)
        spec <- DiscreteScaleSpec(name, levels, palette)
        plot <- current.withScale(ScaleBinding(aesthetic, value, spec))
      yield plot
    updateResult(next)

  private def bindThemeDiscrete(
      aesthetic: Aesthetic[Rgba],
      value: Row => String,
      levels: Vector[String],
      name: String,
      overflow: PaletteOverflowPolicy
  ): PlotBuilder[Row, Position] =
    val next =
      for
        current <- result
        spec <- DiscreteScaleSpec.themeRgba(name, levels, overflow)
        plot <- current.withScale(ScaleBinding(aesthetic, value, spec))
      yield plot
    updateResult(next)

  private def mapAesthetics(f: AesSpec[Row] => AesSpec[Row]): PlotBuilder[Row, Position] =
    updateResult(result.flatMap(current => current.withMapping(f(current.mapping))))

  private def remap[Next <: PlotPosition[Row]](
      nextPosition: Next,
      f: AesSpec[Row] => AesSpec[Row]
  ): PlotBuilder[Row, Next] =
    new PlotBuilder(
      data,
      nextPosition,
      result.flatMap(current => current.withMapping(f(current.mapping))),
      options
    )

  private def updatePlot(f: Plot[Row] => Plot[Row]): PlotBuilder[Row, Position] =
    updateResult(result.map(f))

  private def updateResult(next: Either[GraphicsError, Plot[Row]]): PlotBuilder[Row, Position] =
    new PlotBuilder(data, position, next, options)

  private def updateOptions(next: PlotCompilerOptions): PlotBuilder[Row, Position] =
    new PlotBuilder(data, position, result, next)

/** Start a renderer-neutral plot program. The default DSL policy derives axes and legends and uses
  * the active theme's layout policy.
  */
def plot[Row](data: IterableOnce[Row]): PlotBuilder[Row, PlotPosition.Empty[Row]] =
  val rows = data.iterator.toVector
  val theme = Theme.default
  new PlotBuilder(
    rows,
    PlotPosition.Empty(),
    Right(Plot(rows)),
    PlotCompilerOptions(
      guides = GuidePolicy.Derived(),
      theme = theme
    )
  )

/** Begin a field-native plot. Coordinates and cell extents derive from the checked field instead of
  * being repeated as loosely related columns.
  */
def plot(field: ScalarField2D): PlotBuilder[ScalarCell, PlotPosition.XY[ScalarCell]] =
  plot(field.cells).aes(_.x, _.y)

/** Begin a plot from deterministic, already-extracted contour geometry. */
def plot(contours: ContourSet): PlotBuilder[ContourVertex, PlotPosition.XY[ContourVertex]] =
  plot(contours.vertices)
    .aes(_.x, _.y)
    .group(_.pathId)

/** Begin a plot from filled-band regions while retaining each outer/hole ring as an independently
  * closed polygon subpath.
  */
def plot(
    bands: ContourBandSet
): PlotBuilder[ContourBandVertex, PlotPosition.XY[ContourBandVertex]] =
  plot(bands.vertices)
    .aes(_.x, _.y)
    .group(_.regionId)
    .subpath(_.ringId)
