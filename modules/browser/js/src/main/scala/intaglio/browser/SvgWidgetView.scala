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
    val plan: InteractionPlan[A],
    val context: RenderContext,
    val idPrefix: String,
    val markup: String,
    val emphasisMarkup: String,
    val picking: PickingPlan[A],
    val navigation: NavigationPlan[A],
    val parts: PartPicking
):
  def width: Int = context.width
  def height: Int = context.height

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
      title: Option[String] = None
  ): Either[IntaglioError, SvgWidgetView[A]] =
    val renderPlan = RenderPlan(plan.scene, context)
    for
      device <- DeviceScene.fromScene(plan.scene, context)
      picking <- Picking.fromResolved(device, plan.groups, context)
      parts <- PartPicking.fromResolved(plan.trained, device, context)
      markup <- SvgRenderer.render(renderPlan, title, SvgFonts.empty, idPrefix)
      emphasis <- SvgRenderer.render(renderPlan, None, SvgFonts.empty, s"$idPrefix-emphasis")
    yield new SvgWidgetView(
      plan,
      context,
      idPrefix,
      markup.value,
      emphasis.value,
      picking,
      picking.prepareNavigation(),
      parts
    )
