package intaglio.interaction

import intaglio.*

/** Picks the typed [[PlotPart]] under a device point. Parts are found by name through the plot's
  * [[NamedPickingPlan]]: a hit on any of a part's names is a hit on the part; names that belong to
  * no part (panel backgrounds, grid lines) are ignored.
  */
final class PartPicking private (val parts: Vector[PartTarget], plan: NamedPickingPlan):
  private val byName: Map[GraphicsName, PartTarget] =
    parts.flatMap(part => part.names.map(_ -> part)).toMap

  /** The nearest part within `toleranceDevicePx`, preferring the later-drawn on a tie. */
  def at(
      point: DevicePoint,
      toleranceDevicePx: Double = 0
  ): Either[PickingError, Option[PartTarget]] =
    plan.hits(point, toleranceDevicePx).map(_.view.flatMap(hit => byName.get(hit.name)).headOption)

  /** Rings around a part's first painted name, for focus and hover overlays. */
  def outline(
      part: PartTarget,
      offsetDevicePx: Double
  ): Either[PickingError, Option[TargetOutline]] =
    part.names.view
      .map(plan.outline(_, offsetDevicePx))
      .collectFirst {
        case Left(error)          => Left(error)
        case Right(Some(outline)) => Right(Some(outline))
      }
      .getOrElse(Right(None))

object PartPicking:
  /** Parts of `trained`, picked over the scene it lowers to at `context`. */
  def compile(trained: TrainedPlot, context: RenderContext): Either[IntaglioError, PartPicking] =
    NamedPicking.compile(trained.scene, context).map(new PartPicking(PlotParts.of(trained), _))

  /** The same, over an already resolved device scene (lower once, draw and pick). */
  def fromResolved(
      trained: TrainedPlot,
      scene: DeviceScene,
      context: RenderContext
  ): Either[IntaglioError, PartPicking] =
    NamedPicking.fromResolved(scene, context).map(new PartPicking(PlotParts.of(trained), _))
