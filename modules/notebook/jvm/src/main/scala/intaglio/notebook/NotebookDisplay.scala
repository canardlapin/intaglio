package intaglio.notebook

import intaglio.*
import intaglio.svg.*

enum NotebookErrorDisplay:
  case PlainText
  case AccessibleHtml

final case class NotebookOptions private (
    width: Int,
    height: Int,
    pixelsPerInch: Double,
    deviceScale: Double,
    title: Option[String],
    errorDisplay: NotebookErrorDisplay
)

object NotebookOptions:
  val default: NotebookOptions = unsafe()

  def apply(
      width: Int = 640,
      height: Int = 480,
      pixelsPerInch: Double = 96.0,
      deviceScale: Double = 1.0,
      title: Option[String] = None,
      errorDisplay: NotebookErrorDisplay = NotebookErrorDisplay.PlainText
  ): Either[NotebookRenderError, NotebookOptions] =
    SvgOptions(width, height, title, pixelsPerInch, deviceScale).left
      .map(NotebookRenderError.Svg(_))
      .map(_ => new NotebookOptions(width, height, pixelsPerInch, deviceScale, title, errorDisplay))

  def unsafe(
      width: Int = 640,
      height: Int = 480,
      pixelsPerInch: Double = 96.0,
      deviceScale: Double = 1.0,
      title: Option[String] = None,
      errorDisplay: NotebookErrorDisplay = NotebookErrorDisplay.PlainText
  ): NotebookOptions =
    apply(width, height, pixelsPerInch, deviceScale, title, errorDisplay).orThrow

enum NotebookRenderError extends IntaglioError:
  case Compiler(error: GraphicsError)
  case Svg(error: SvgRenderError)

  def message: String =
    this match
      case Compiler(error) => error.message
      case Svg(error)      => error.message

object NotebookRenderError:
  extension [A](either: Either[NotebookRenderError, A])
    def orThrow: A =
      either match
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(error.message)

/** Dependency-free representation of Jupyter's MIME-bundle protocol. Notebook integrations can pass
  * `data` and `metadata` to their kernel's display API without making Intaglio core depend on
  * Almond, Jupyter, or a particular frontend.
  */
final case class NotebookMimeBundle(
    data: Map[String, String],
    metadata: Map[String, Map[String, String]] = Map.empty
):
  def svg: Option[String] = data.get(NotebookMimeBundle.SvgMime)
  def plainText: Option[String] = data.get(NotebookMimeBundle.PlainTextMime)
  def html: Option[String] = data.get(NotebookMimeBundle.HtmlMime)

object NotebookMimeBundle:
  val SvgMime: String = "image/svg+xml"
  val PlainTextMime: String = "text/plain"
  val HtmlMime: String = "text/html"

object NotebookRenderer:
  /** Return a checked Jupyter MIME bundle and preserve SVG renderer failures as typed values. */
  def render(
      scene: Scene,
      options: NotebookOptions = NotebookOptions.default
  ): Either[NotebookRenderError, NotebookMimeBundle] =
    SvgRenderer
      .render(
        scene,
        SvgOptions.unsafe(
          options.width,
          options.height,
          options.title,
          options.pixelsPerInch,
          options.deviceScale
        )
      )
      .left
      .map(NotebookRenderError.Svg(_))
      .map(successBundle)

  /** Always return something displayable, converting a checked rendering failure according to the
    * configured notebook error policy.
    */
  def display(
      scene: Scene,
      options: NotebookOptions = NotebookOptions.default
  ): NotebookMimeBundle =
    render(scene, options).fold(error => errorBundle(error, options.errorDisplay), identity)

  /** Compile a plot against the exact notebook target before producing its MIME bundle. */
  def renderPlot[Row](
      plot: Plot[Row],
      options: NotebookOptions = NotebookOptions.default,
      compilerOptions: PlotCompilerOptions = PlotCompilerOptions.lean
  ): Either[NotebookRenderError, NotebookMimeBundle] =
    for
      context <- RenderContext(
        options.width,
        options.height,
        options.pixelsPerInch,
        deviceScale = options.deviceScale
      ).left.map(NotebookRenderError.Compiler(_))
      plan <- PlotCompiler
        .compile(plot, context, compilerOptions)
        .left
        .map(NotebookRenderError.Compiler(_))
      bundle <- renderPlan(plan, options.title)
    yield bundle

  def displayPlot[Row](
      plot: Plot[Row],
      options: NotebookOptions = NotebookOptions.default,
      compilerOptions: PlotCompilerOptions = PlotCompilerOptions.lean
  ): NotebookMimeBundle =
    renderPlot(plot, options, compilerOptions)
      .fold(error => errorBundle(error, options.errorDisplay), identity)

  /** Display a plan already bound to its exact RenderContext. */
  def displayPlan(
      plan: RenderPlan,
      title: Option[String] = None,
      errorDisplay: NotebookErrorDisplay = NotebookErrorDisplay.PlainText
  ): NotebookMimeBundle =
    renderPlan(plan, title)
      .fold(error => errorBundle(error, errorDisplay), identity)

  private def renderPlan(
      plan: RenderPlan,
      title: Option[String]
  ): Either[NotebookRenderError, NotebookMimeBundle] =
    SvgRenderer
      .render(plan, title)
      .left
      .map(NotebookRenderError.Svg(_))
      .map(successBundle)

  private def successBundle(document: SvgDocument): NotebookMimeBundle =
    NotebookMimeBundle(
      data = Map(
        NotebookMimeBundle.SvgMime -> document.value,
        NotebookMimeBundle.PlainTextMime ->
          s"Intaglio SVG plot (${document.logicalWidth} x ${document.logicalHeight} logical px)"
      ),
      metadata = Map(
        NotebookMimeBundle.SvgMime -> Map(
          "width" -> document.logicalWidth.toString,
          "height" -> document.logicalHeight.toString,
          "pixelsPerInch" -> document.pixelsPerInch.toString,
          "deviceScale" -> document.deviceScale.toString
        )
      )
    )

  private def errorBundle(
      error: NotebookRenderError,
      display: NotebookErrorDisplay
  ): NotebookMimeBundle =
    val message = s"Intaglio notebook render failed: ${error.message}"
    display match
      case NotebookErrorDisplay.PlainText =>
        NotebookMimeBundle(Map(NotebookMimeBundle.PlainTextMime -> message))
      case NotebookErrorDisplay.AccessibleHtml =>
        NotebookMimeBundle(
          Map(
            NotebookMimeBundle.PlainTextMime -> message,
            NotebookMimeBundle.HtmlMime ->
              s"<pre role=\"alert\">${escapeHtml(message)}</pre>"
          )
        )

  private def escapeHtml(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")
