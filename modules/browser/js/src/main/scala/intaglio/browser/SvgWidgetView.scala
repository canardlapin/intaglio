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
    renderEmphasis: () => String,
    val picking: PickingPlan[A],
    val navigation: NavigationPlan[A],
    val parts: PartPicking,
    val title: Option[String],
    val panelFrame: Option[DeviceFrame],
    val fonts: SvgFonts,
    /** How marks are hit; re-windowing and repainting keep it. */
    val policy: PickPolicy
):
  /** The second copy of the marks, rendered on first use: a Canvas widget, or an SVG widget that
    * never dims, does not pay for it. It is the same document under `idPrefix-emphasis`, so it
    * cannot fail where `markup` succeeded.
    */
  lazy val emphasisMarkup: String = renderEmphasis()

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

  /** Repaint logical targets without changing their identity, data, layout, or navigation window.
    */
  private[browser] def withTargetStyles(
      styles: Map[VisualTargetId, WidgetTargetStyle]
  ): Either[IntaglioError, SvgWidgetView[A]] =
    if styles.isEmpty then Right(this)
    else
      for
        painted <- WidgetTargetStyle.paint(this, styles)
        device <- DeviceScene.fromScene(painted, context)
        picking <- Picking.fromResolved(device, plans.flatMap(_.groups), context, policy)
        parts <- PartPicking.fromParts(this.parts.parts, device, context)
        markup <- SvgRenderer.render(RenderPlan(painted, context), title, fonts, idPrefix)
      yield new SvgWidgetView(
        plans,
        painted,
        revision,
        context,
        idPrefix,
        markup.value,
        SvgWidgetView.emphasis(RenderPlan(painted, context), fonts, idPrefix),
        picking,
        picking.prepareNavigation(),
        parts,
        title,
        panelFrame,
        fonts,
        policy
      )

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
  /** The untitled emphasis copy, deferred. Its prefix is a valid prefix with a valid suffix and its
    * scene already rendered, so a failure here is a broken invariant, not an input error.
    */
  private def emphasis(plan: RenderPlan, fonts: SvgFonts, idPrefix: String): () => String =
    () =>
      SvgRenderer
        .render(plan, None, fonts, s"$idPrefix-emphasis")
        .fold(error => throw new IllegalStateException(error.message), _.value)

  /** Resolve `plan` at `context`. `plan` must have been compiled for the same context. `policy`
    * decides how marks are hit; `PickPolicy.default.withHollowPoints(HollowPicking.Outline)` hits a
    * hollow point only on its outline, as for bubble charts whose small points show through large
    * rings.
    */
  def compile[A](
      plan: InteractionPlan[A],
      context: RenderContext,
      idPrefix: String,
      title: Option[String] = None,
      fonts: SvgFonts = SvgFonts.empty,
      policy: PickPolicy = PickPolicy.default
  ): Either[IntaglioError, SvgWidgetView[A]] =
    val renderPlan = RenderPlan(plan.scene, context)
    for
      device <- DeviceScene.fromScene(plan.scene, context)
      picking <- Picking.fromResolved(device, plan.groups, context, policy)
      parts <- PartPicking.fromResolved(plan.trained, device, context)
      markup <- SvgRenderer.render(renderPlan, title, fonts, idPrefix)
    yield
      val panel = device.frame(PlotRegion.Panel).toOption.map(_.frame)
      new SvgWidgetView(
        Vector(plan),
        plan.scene,
        plan.revision,
        context,
        idPrefix,
        markup.value,
        emphasis(renderPlan, fonts, idPrefix),
        picking,
        picking.prepareNavigation(),
        parts,
        title,
        panel,
        fonts,
        policy
      )

  /** Mount a composed figure without discarding any child identity or source revision. Figure-wide
    * pan/zoom is unavailable: children have independent trained scales.
    */
  def compileComposition[A](
      composed: ComposedInteraction[A],
      revision: PlanRevision,
      idPrefix: String,
      title: Option[String] = None,
      fonts: SvgFonts = SvgFonts.empty,
      policy: PickPolicy = PickPolicy.default
  ): Either[IntaglioError, SvgWidgetView[A]] =
    val context = composed.composition.context
    // Children share plot-level part names; the scoped scene keeps each child's parts its own.
    val scoped = ComposedParts.of(composed)
    val scene = scoped.scene
    val plan = RenderPlan(scene, context)
    for
      device <- DeviceScene.fromScene(scene, context)
      picking <- Picking.fromResolved(device, composed.groups, context, policy)
      parts <- PartPicking.fromParts(scoped.parts, device, context)
      markup <- SvgRenderer.render(plan, title, fonts, idPrefix)
    yield new SvgWidgetView(
      composed.plans,
      scene,
      revision,
      context,
      idPrefix,
      markup.value,
      emphasis(plan, fonts, idPrefix),
      picking,
      picking.prepareNavigation(),
      parts,
      title,
      None,
      fonts,
      policy
    )
