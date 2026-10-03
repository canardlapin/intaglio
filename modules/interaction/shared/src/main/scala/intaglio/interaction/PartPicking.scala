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
    DeviceScene.fromScene(trained.scene, context).flatMap(fromResolved(trained, _, context))

  /** The same, over an already resolved device scene (lower once, draw and pick). */
  def fromResolved(
      trained: TrainedPlot,
      scene: DeviceScene,
      context: RenderContext
  ): Either[IntaglioError, PartPicking] =
    fromParts(PlotParts.of(trained), scene, context)

  /** Parts supplied by a composed figure, resolved over the transformed, clipped device scene. Only
    * names some part claims are picked, so a plot's data marks, however many, cost parts nothing: a
    * hit on any other name was ignored anyway, and kept names keep their draw order.
    */
  def fromParts(
      parts: Vector[PartTarget],
      scene: DeviceScene,
      context: RenderContext
  ): Either[IntaglioError, PartPicking] =
    val claimed = parts.iterator.flatMap(_.names).toSet
    NamedPicking
      .fromDeviceScene(scene, context, PickPolicy.default, claimed.contains)
      .map(new PartPicking(parts, _))
