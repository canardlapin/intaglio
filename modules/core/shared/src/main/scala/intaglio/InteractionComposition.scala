package intaglio.interaction

import intaglio.*

/** A composed scene retains every child's typed routing table and source revision. */
final class ComposedInteraction[A] private[interaction] (
    val composition: ComposedPlot,
    val plans: Vector[InteractionPlan[A]]
):
  def scene: Scene = composition.scene
  def renderPlan: RenderPlan = composition.renderPlan
  val groups: Vector[TargetGroup[A]] = plans.flatMap(_.groups)
  private val byName = groups.iterator.map(group => group.name.value -> group).toMap
  def group(name: String): Option[TargetGroup[A]] = byName.get(name)

  def withInset(
      plan: InteractionPlan[A],
      inset: PlotInset
  ): Either[IntaglioError, ComposedInteraction[A]] =
    val combined = plans :+ plan
    InteractionComposition.validate(combined, composition.context).flatMap { _ =>
      InteractionComposition.checked(composition.withInset(plan.trained, inset), combined)
    }

/** Uses ordinary composition transforms and validates that none of the typed routes was lost. */
object InteractionComposition:
  def row[A](
      plans: Vector[InteractionPlan[A]],
      context: RenderContext,
      options: CompositionOptions = CompositionOptions.default
  ): Either[IntaglioError, ComposedInteraction[A]] =
    grid(plans, plans.length, context, options)

  def column[A](
      plans: Vector[InteractionPlan[A]],
      context: RenderContext,
      options: CompositionOptions = CompositionOptions.default
  ): Either[IntaglioError, ComposedInteraction[A]] =
    grid(plans, 1, context, options)

  def grid[A](
      plans: Vector[InteractionPlan[A]],
      columns: Int,
      context: RenderContext,
      options: CompositionOptions = CompositionOptions.default
  ): Either[IntaglioError, ComposedInteraction[A]] =
    for
      _ <- validate(plans, context)
      composed <- PlotComposition.grid(plans.map(_.trained), columns, context, options)
      result <- checked(composed, plans)
    yield result

  private[interaction] def validate[A](
      plans: Vector[InteractionPlan[A]],
      context: RenderContext
  ): Either[InteractionError, Unit] =
    if plans.map(_.id).distinct.length != plans.length then
      Left(
        InteractionError
          .InvalidValue("interaction composition", "each input requires a distinct plan id")
      )
    else if plans.exists(plan => !plan.context.exists(stored => stored eq context)) then
      Left(
        InteractionError.InvalidValue(
          "interaction composition",
          "compile every input with the supplied RenderContext instance"
        )
      )
    else
      val names = plans.flatMap(_.groups.map(_.name))
      if names.distinct.length != names.length then
        Left(
          InteractionError.InvalidValue("interaction composition", "target routing names collide")
        )
      else Right(())

  private[interaction] def checked[A](
      composition: ComposedPlot,
      plans: Vector[InteractionPlan[A]]
  ): Either[InteractionError, ComposedInteraction[A]] =
    def routes(grobs: Vector[Grob]): Vector[String] = grobs.flatMap {
      case Grob.Annotated(child, meta) =>
        meta.data.collect {
          case (key, value) if key == InteractionCompiler.targetAttribute => value
        } ++ routes(Vector(child))
      case group: Grob.Group => routes(group.children)
      case _                 => Vector.empty
    }
    val actual = routes(composition.scene.grobs).toSet
    val expected = plans.flatMap(_.groups.map(_.name.value)).toSet
    if actual != expected then
      Left(
        InteractionError.InvalidValue(
          "interaction composition",
          "composed scene and target routing table disagree"
        )
      )
    else Right(new ComposedInteraction(composition, plans))
