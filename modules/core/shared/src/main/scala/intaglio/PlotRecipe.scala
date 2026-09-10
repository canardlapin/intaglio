package intaglio

/** Immutable renderer-neutral input to the Intaglio compiler.
  *
  * A specification retains its exact row type, plot algebra, and compiler options. It contains no
  * backend object or mutable registration state, so the same value can be resolved repeatedly with
  * the same result.
  */
final case class PlotSpec[Row](
    plot: Plot[Row],
    compilerOptions: PlotCompilerOptions = PlotCompilerOptions.default
):
  def resolve: Either[GraphicsError, TrainedPlot] =
    PlotCompiler.resolve(plot, compilerOptions)

  def scene: Either[GraphicsError, Scene] =
    PlotCompiler.compile(plot, compilerOptions)

  def resolve(context: RenderContext): Either[GraphicsError, TrainedPlot] =
    PlotCompiler.resolve(plot, context, compilerOptions)

  def renderPlan(context: RenderContext): Either[GraphicsError, RenderPlan] =
    PlotCompiler.compile(plot, context, compilerOptions)

  def program: PlotProgram[Row] =
    PlotProgram(plot, compilerOptions)

object PlotSpec:
  /** Retain the plot and compiler options selected by the plotting DSL. */
  def fromProgram[Row](program: PlotProgram[Row]): PlotSpec[Row] =
    PlotSpec(program.plot, program.compilerOptions)

/** Scala-native conversion from an external domain value to an Intaglio [[PlotSpec]].
  *
  * `Source` remains an ordinary application type: it need not inherit from an Intaglio type or
  * carry plotting concerns. The associated [[Row]] type keeps the recipe's extracted plotting rows
  * precise without erasing them to `Any`.
  *
  * Recipes participate only in normal Scala `given` resolution. There is no mutable registry or
  * runtime fallback; a missing or ambiguous recipe is a compile-time error.
  */
trait PlotRecipe[-Source]:
  type Row

  def apply(source: Source): Either[GraphicsError, PlotSpec[Row]]

object PlotRecipe:
  type Aux[-Source, Row0] = PlotRecipe[Source] { type Row = Row0 }

  /** Define a recipe whose conversion can reject an invalid source value with a typed error. */
  def checked[Source, Row0](
      convert: Source => Either[GraphicsError, PlotSpec[Row0]]
  ): PlotRecipe.Aux[Source, Row0] =
    new PlotRecipe[Source]:
      type Row = Row0

      def apply(source: Source): Either[GraphicsError, PlotSpec[Row]] =
        convert(source)

  /** Define a total recipe. */
  def total[Source, Row0](convert: Source => PlotSpec[Row0]): PlotRecipe.Aux[Source, Row0] =
    checked(source => Right(convert(source)))

  /** Convert with the recipe selected by normal Scala `given` resolution. */
  def apply[Source](source: Source)(using
      recipe: PlotRecipe[Source]
  ): Either[GraphicsError, PlotSpec[recipe.Row]] =
    recipe(source)

extension [Source](source: Source)
  /** Convert an external value with its uniquely resolved [[PlotRecipe]]. */
  def toPlotSpec(using
      recipe: PlotRecipe[Source]
  ): Either[GraphicsError, PlotSpec[recipe.Row]] =
    recipe(source)
