package intaglio.interaction

import intaglio.*

/** Picks the typed [[PlotPart]] under a device point. Parts are found by name through the plot's
  * [[NamedPickingPlan]]: a hit on any of a part's names is a hit on the part; names that belong to
  * no part (panel backgrounds, grid lines) are ignored.
  */
final class PartPicking private (val parts: Vector[PartTarget], plan: NamedPickingPlan):
  // A name claimed by two parts resolves to the first, in reading order.
  private val byName: Map[GraphicsName, PartTarget] =
    parts.reverse.flatMap(part => part.names.map(_ -> part)).toMap

  /** The nearest part within `toleranceDevicePx`, preferring the later-drawn on a tie. */
  def at(
      point: DevicePoint,
      toleranceDevicePx: Double = 0
  ): Either[PickingError, Option[PartTarget]] =
    plan.hits(point, toleranceDevicePx).map(_.view.flatMap(hit => byName.get(hit.name)).headOption)

  /** Rings around every painted copy of a part (a facet-repeated axis has one per panel), for focus
    * and hover overlays; `None` when nothing of the part is painted.
    */
  def outline(
      part: PartTarget,
      offsetDevicePx: Double
  ): Either[PickingError, Option[TargetOutline]] =
    part.names
      .foldLeft[Either[PickingError, Vector[Vector[DevicePoint]]]](Right(Vector.empty)) {
        (rings, name) =>
          rings.flatMap(done =>
            plan.outline(name, offsetDevicePx).map(o => done ++ o.toVector.flatMap(_.rings))
          )
      }
      .map(rings => Option.when(rings.nonEmpty)(TargetOutline(rings)))

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
