package intaglio

/** Superseded by [[RendererConformance]], which groups cases by behavior and adds a reusable
  * backend checker. Kept as a thin alias for downstream code still referring to the old name; new
  * backends should implement [[RendererHarness]] and call [[RendererConformance.check]].
  */
@deprecated("use RendererConformance", "0.1.0")
object SceneConformance:
  def cases: Either[GraphicsError, Vector[ConformanceCase]] =
    RendererConformance.cases
