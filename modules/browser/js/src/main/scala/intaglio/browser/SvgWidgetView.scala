package intaglio.browser

import intaglio.*
import intaglio.interaction.*
import intaglio.svg.{SvgFonts, SvgRenderer}

/** Everything a widget draws and picks, resolved once from one interaction plan at one render
  * context: the plot's SVG markup, a second copy used to show emphasized marks in their original
  * paint, the picking and navigation plans, and the plot's typed parts. No DOM is touched here.
  *
  * Both SVG copies carry their own id prefix (`idPrefix` and `idPrefix-emphasis`), so a page may
  * hold any number of widgets whose prefixes differ.
  */
final class SvgWidgetView[A] private (
    val plans: Vector[InteractionPlan[A]],
    val scene: Scene,
    val revision: PlanRevision,
    val context: RenderContext,
    val idPrefix: String,
    val markup: String,
    val emphasisMarkup: String,
    val picking: PickingPlan[A],
    val navigation: NavigationPlan[A],
    val parts: PartPicking,
    val title: Option[String],
    val panelFrame: Option[DeviceFrame],
    val fonts: SvgFonts
):
  /** A single plot can navigate its data window; a composition has several independent plans. */
  def singlePlan: Option[InteractionPlan[A]] = Option.when(plans.size == 1)(plans.head)

  def width: Int = context.width
  def height: Int = context.height

  /** Authored annotations are also logical targets. Preserve their typed part when that target wins
    * a hit, rather than guessing from another shape under the pointer.
    */
  private lazy val annotations: Map[(SemanticId, String), PlotPart] =
    def routes(grob: Grob): Vector[String] = grob match
      case a: Grob.Annotated =>
        a.meta.data.collect {
          case (key, value) if key == InteractionCompiler.targetAttribute => value
        } ++ routes(a.child)
      case g: Grob.Group => g.children.flatMap(routes)
      case _             => Vector.empty
    plans.flatMap { plan =>
      (plan.trained.layers ++ plan.trained.facetPanels.flatMap(_.layers))
        .filter(_.annotation.nonEmpty)
        .flatMap { layer =>
          layer.grobs
            .flatMap(routes)
            .map(route => (plan.id, route) -> PlotPart.Annotation(layer.layerIndex))
        }
    }.toMap

  private[browser] def annotationPart(target: VisualTargetId): Option[PlotPart] =
    annotations.get((target.plan, target.scope.value))

  /** A short description of a target for the text companion and the live region. */
  def describe(target: TargetInfo[A], behavior: InteractionBehavior[A]): String =
    behavior
      .tooltip(target)
      .map(_.plainText)
      .getOrElse {
        val index = navigation.targets.indexWhere(_.target.id == target.id)
        s"mark ${index + 1} of ${navigation.targets.size}"
      }

  /** One row per target in reading order, then one per plot part: the text companion. */
  def companionRows(behavior: InteractionBehavior[A]): Vector[(String, String)] =
    navigation.targets.map(g => ("mark", describe(g.target, behavior))) ++
      parts.parts.map(part => ("part", part.part.describe))

object SvgWidgetView:
  /** Resolve `plan` at `context`. `plan` must have been compiled for the same context. */
  def compile[A](
      plan: InteractionPlan[A],
      context: RenderContext,
      idPrefix: String,
      title: Option[String] = None,
      fonts: SvgFonts = SvgFonts.empty
  ): Either[IntaglioError, SvgWidgetView[A]] =
    val renderPlan = RenderPlan(plan.scene, context)
    for
      device <- DeviceScene.fromScene(plan.scene, context)
      picking <- Picking.fromResolved(device, plan.groups, context)
      parts <- PartPicking.fromResolved(plan.trained, device, context)
      markup <- SvgRenderer.render(renderPlan, title, fonts, idPrefix)
      emphasis <- SvgRenderer.render(renderPlan, None, fonts, s"$idPrefix-emphasis")
    yield
      val panel = device.frame(PlotRegion.Panel).toOption.map(_.frame)
      new SvgWidgetView(
        Vector(plan),
        plan.scene,
        plan.revision,
        context,
        idPrefix,
        markup.value,
        emphasis.value,
        picking,
        picking.prepareNavigation(),
        parts,
        title,
        panel,
        fonts
      )

  /** Mount a composed figure without discarding any child identity or source revision. Figure-wide
    * pan/zoom is unavailable: children have independent trained scales.
    */
  def compileComposition[A](
      composed: ComposedInteraction[A],
      revision: PlanRevision,
      idPrefix: String,
      title: Option[String] = None,
      fonts: SvgFonts = SvgFonts.empty
  ): Either[IntaglioError, SvgWidgetView[A]] =
    val context = composed.composition.context
    val scene = composed.scene
    for
      device <- DeviceScene.fromScene(scene, context)
      picking <- Picking.fromResolved(device, composed.groups, context)
      parts <- PartPicking.fromParts(
        composed.plans.flatMap(p => PlotParts.of(p.trained)).distinct,
        device,
        context
      )
      markup <- SvgRenderer.render(composed.renderPlan, title, fonts, idPrefix)
      emphasis <- SvgRenderer.render(composed.renderPlan, None, fonts, s"$idPrefix-emphasis")
    yield new SvgWidgetView(
      composed.plans,
      scene,
      revision,
      context,
      idPrefix,
      markup.value,
      emphasis.value,
      picking,
      picking.prepareNavigation(),
      parts,
      title,
      None,
      fonts
    )
