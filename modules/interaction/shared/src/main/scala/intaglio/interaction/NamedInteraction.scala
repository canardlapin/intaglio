package intaglio.interaction

import intaglio.*

/** A [[NamedPickingPlan]] given typed interaction identity, so a hand-built scene can use the
  * shared state machine, keyboard navigation and hosts that plots use.
  *
  * Each name is one target. Targets are addressed in draw order (`ordinal` is the target's index in
  * `names.names`) under the caller's plan id and revision, and each carries its name as an entity
  * in `keys`, with exact membership of that one key. `picking` is the same geometry as `names` with
  * those identities: for every query its hits are `names`' hits, in the same order, with the same
  * distances and draw orders. Selecting a target's entity selects the name.
  */
final class NamedInteraction private (
    val names: NamedPickingPlan,
    val keys: KeySpace[GraphicsName],
    val picking: PickingPlan[GraphicsName],
    val domain: InteractionDomain[GraphicsName],
    byName: Map[GraphicsName, TargetInfo[GraphicsName]]
):

  /** The target that represents `name`, if the scene paints it. */
  def target(name: GraphicsName): Option[TargetInfo[GraphicsName]] = byName.get(name)

  /** Directional (`nearest`) and sequential (`next`, `previous`) keyboard navigation over the
    * names, in draw order.
    */
  def prepareNavigation(): NavigationPlan[GraphicsName] = picking.prepareNavigation()

object NamedInteraction:

  /** A key space whose values are graphics names, encoded as their text. Share one instance between
    * views that should link by name.
    */
  def keySpace(namespace: String): Either[InteractionError, KeySpace[GraphicsName]] =
    KeyCodec[GraphicsName]("graphics-name", 1)(
      name => Right(name.value),
      text => GraphicsName(text).left.map(_.message)
    ).flatMap(KeySpace(namespace, _))

  /** Bind `plan`'s names to typed identities under `planId` and `revision`. */
  def apply(
      plan: NamedPickingPlan,
      keys: KeySpace[GraphicsName],
      planId: SemanticId,
      revision: PlanRevision
  ): Either[IntaglioError, NamedInteraction] =
    val ordered = plan.targets.sortBy(_.drawOrder)
    for
      data <- DataRevision(revision.value)
      series <- TargetSeries(planId, revision, SemanticId.unsafe("names"), ordered.size)
      entities <- traverse(ordered)(target => keys.entity(target.name))
      memberships <- traverse(entities)(key => Membership.exact(keys, data, Vector(key)))
      group = new TargetGroup[GraphicsName](
        SemanticId.unsafe("names"),
        series,
        entities.map(Some(_)),
        memberships,
        Vector.fill(ordered.size)(LinkKeys.empty)
      )
      infos <- traverse(ordered.indices.toVector)(group.at)
    yield
      val targets = ordered.zip(infos).map { case (target, info) =>
        PickTarget(info, target.parts, target.orders)
      }
      new NamedInteraction(
        plan,
        keys,
        new PickingPlan(targets, Vector.empty[RasterPickTarget[GraphicsName]]),
        InteractionDomain.named(revision, group, keys, entities.toSet),
        ordered.map(_.name).zip(infos).toMap
      )

  private def traverse[A, B](values: Vector[A])(
      f: A => Either[InteractionError, B]
  ): Either[InteractionError, Vector[B]] =
    val out = Vector.newBuilder[B]
    var result: Either[InteractionError, Unit] = Right(())
    var index = 0
    while index < values.length && result.isRight do
      result = f(values(index)).map { value => out += value; () }
      index += 1
    result.map(_ => out.result())
