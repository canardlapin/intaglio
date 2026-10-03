package intaglio.interaction

import intaglio.*

/** Where each target identity sits in a plan's target vector. Identities of one target series share
  * their plan, revision and scope, and a plan lists a series' targets together, so building
  * compares those with the previous identity and hashes them only when the series changes; a lookup
  * hashes them once and binary-searches the ordinal. Nothing here outlives the plan that owns it.
  */
private[interaction] final class TargetPositions private (
    series: Map[(SemanticId, PlanRevision, SemanticId), TargetPositions.Series]
):
  def apply(id: VisualTargetId): Option[Int] =
    series.get((id.plan, id.revision, id.scope)).flatMap(_.find(id.ordinal))

private[interaction] object TargetPositions:
  final class Series(ordinals: Array[Int], positions: Array[Int]):
    def find(ordinal: Int): Option[Int] =
      val at = java.util.Arrays.binarySearch(ordinals, ordinal)
      Option.when(at >= 0)(positions(at))

  private final class Builder:
    val ordinals = scala.collection.mutable.ArrayBuffer.empty[Int]
    val positions = scala.collection.mutable.ArrayBuffer.empty[Int]
    def result(): Series =
      // Batches list their ordinals ascending; sort only a series that arrived out of order.
      val ascending = (1 until ordinals.length).forall(i => ordinals(i - 1) < ordinals(i))
      if ascending then new Series(ordinals.toArray, positions.toArray)
      else
        val order = ordinals.indices.sortBy(ordinals(_)).toArray
        new Series(order.map(ordinals(_)), order.map(positions(_)))

  def of(ids: Iterator[VisualTargetId]): TargetPositions =
    val builders =
      scala.collection.mutable.HashMap.empty[(SemanticId, PlanRevision, SemanticId), Builder]
    var last: VisualTargetId = null
    var current: Builder = null
    var position = 0
    ids.foreach { id =>
      val same = last != null &&
        ((id.plan eq last.plan) || id.plan == last.plan) &&
        ((id.revision eq last.revision) || id.revision == last.revision) &&
        ((id.scope eq last.scope) || id.scope == last.scope)
      if !same then
        current = builders.getOrElseUpdate((id.plan, id.revision, id.scope), new Builder)
      current.ordinals += id.ordinal
      current.positions += position
      last = id
      position += 1
    }
    new TargetPositions(builders.view.mapValues(_.result()).toMap)
