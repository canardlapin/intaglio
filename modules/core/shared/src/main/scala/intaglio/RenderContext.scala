package intaglio

/** Deterministic font-family resolution used by both layout and device lowering.
  *
  * A registry may substitute an installed family, select a fallback when no family was requested,
  * or leave the request unchanged. It must be immutable from the caller's point of view: the same
  * request in one render context must resolve to the same family during layout and rendering.
  */
trait FontRegistry:
  def resolve(requested: Option[String]): Option[String]

object FontRegistry:
  /** Preserve the requested family exactly. */
  val passthrough: FontRegistry =
    new FontRegistry:
      override def resolve(requested: Option[String]): Option[String] =
        requested

  /** Build a registry from a deterministic family-resolution function. */
  def apply(resolveFamily: Option[String] => Option[String]): FontRegistry =
    new FontRegistry:
      override def resolve(requested: Option[String]): Option[String] =
        resolveFamily(requested)

/** Complete target information needed before plot layout begins.
  *
  * Width and height are device pixels. `pixelsPerInch` controls physical-unit conversion,
  * `textMetrics` sizes layout regions, and `fontRegistry` resolves the exact families measured and
  * emitted. A single context therefore owns both compilation and backend lowering.
  */
final class RenderContext private (
    val width: Int,
    val height: Int,
    val pixelsPerInch: Double,
    val textMetrics: TextMetrics,
    val fontRegistry: FontRegistry,
    val deviceContext: DeviceContext
):
  /** Bind target metrics and resolved font families to an otherwise renderer-neutral policy. */
  def layoutPolicy(base: LayoutPolicy): LayoutPolicy =
    base.copy(
      metrics = textMetrics,
      referenceDevice = deviceContext,
      axisFontFamily = fontRegistry.resolve(base.axisFontFamily),
      axisTitleFontFamily = fontRegistry.resolve(base.axisTitleFontFamily),
      plotTitleFontFamily = fontRegistry.resolve(base.plotTitleFontFamily),
      plotSubtitleFontFamily = fontRegistry.resolve(base.plotSubtitleFontFamily),
      legendFontFamily = fontRegistry.resolve(base.legendFontFamily),
      legendTitleFontFamily = fontRegistry.resolve(base.legendTitleFontFamily)
    )

object RenderContext:
  val default: RenderContext =
    unsafe()

  def apply(
      width: Int = 640,
      height: Int = 480,
      pixelsPerInch: Double = 96.0,
      textMetrics: TextMetrics = TextMetrics.estimate,
      fontRegistry: FontRegistry = FontRegistry.passthrough
  ): Either[GraphicsError, RenderContext] =
    DeviceContext(width.toDouble, height.toDouble, pixelsPerInch).map { device =>
      new RenderContext(width, height, pixelsPerInch, textMetrics, fontRegistry, device)
    }

  def unsafe(
      width: Int = 640,
      height: Int = 480,
      pixelsPerInch: Double = 96.0,
      textMetrics: TextMetrics = TextMetrics.estimate,
      fontRegistry: FontRegistry = FontRegistry.passthrough
  ): RenderContext =
    apply(width, height, pixelsPerInch, textMetrics, fontRegistry).orThrow

/** A scene bound to the exact target context that was present during compilation. */
final case class RenderPlan(scene: Scene, context: RenderContext):
  def deviceScene: Either[GraphicsError, DeviceScene] =
    DeviceScene.fromScene(scene, context)
