package intaglio.interaction

import intaglio.*

/** One selected visual target, as the inspector reports it. */
final case class InspectedTarget[A](
    id: VisualTargetId,
    /** A mark with its own observation key, or an aggregate (a bin, an interval) without one. */
    entity: Option[EntityKey[A]],
    capability: MembershipCapability,
    total: Option[Int]
)

/** An aggregate and how much of it the selected observations cover. */
final case class InspectedAggregate[A](
    id: VisualTargetId,
    coverage: MemberCoverage,
    /** The first members, when they are known exactly, in a stable order. */
    members: Vector[EntityKey[A]],
    /** All exact members, or `None` when membership is not exact. */
    memberCount: Option[Int]
)

/** What a selection means, separated as the reader needs it: the observations selected, the visual
  * targets selected (marks and aggregates), how far the selection reaches into each aggregate, the
  * exact members of selected aggregates, keys the data no longer has, and the saved selections.
  * Pure and host-neutral: a browser panel or a desktop view renders it.
  */
final case class InspectorModel[A](
    observations: Int,
    observationSample: Vector[EntityKey[A]],
    targets: Vector[InspectedTarget[A]],
    /** Aggregates the selection covers at all, or that are selected themselves. */
    aggregates: Vector[InspectedAggregate[A]],
    /** Aggregates whose coverage cannot be known (their membership is not exact). */
    uncountedAggregates: Int,
    unresolved: Vector[EntityKey[A]],
    named: Vector[(SelectionName, Int, Int)]
)

object InspectorModel:
  private def stable[A](keys: Iterable[EntityKey[A]]) = keys.toVector.sortBy(_.token.payload)

  /** The inspector for `state`, listing at most `sample` keys in any one list. */
  def of[A](state: InteractionState[A], sample: Int = 20): InspectorModel[A] =
    val domain = state.domain
    val entities = state.selection.entities
    val allTargets = domain.plans.iterator
      .flatMap(_.groups)
      .flatMap(group => Iterator.range(0, group.size).flatMap(i => group.at(i).toOption))
      .toVector
    val selectedTargets = state.selection.targets.toVector
      .flatMap(id => domain.target(id).toOption)
      .sortBy(t => (t.id.plan.value, t.id.scope.value, t.id.ordinal))
      .map(t => InspectedTarget(t.id, t.entity, t.membership.capability, t.membership.total))
    val aggregateTargets = allTargets.filter(_.entity.isEmpty)
    val inspected = aggregateTargets.flatMap { info =>
      val coverage = MemberCoverage.of(info, entities, domain)
      val selectedItself = state.selection.targets.contains(info.id)
      val reached = coverage match
        case MemberCoverage.Known(k, _) => k > 0
        case _                          => false
      Option.when(reached || selectedItself) {
        val exact = domain
          .sourceRevision(info.id)
          .flatMap(revision => info.membership.exactKeys(revision).toOption)
        InspectedAggregate(
          info.id,
          coverage,
          exact.fold(Vector.empty[EntityKey[A]])(keys => stable(keys).take(sample)),
          exact.map(_.size)
        )
      }
    }
    val uncounted = aggregateTargets.count(info =>
      MemberCoverage.of(info, entities, domain) match
        case MemberCoverage.Known(_, _) => false
        case _                          => true
    )
    InspectorModel(
      entities.size,
      stable(entities).take(sample),
      selectedTargets,
      inspected.sortBy(a => (a.id.plan.value, a.id.scope.value, a.id.ordinal)),
      uncounted,
      stable(state.unresolved).take(sample),
      state.named.toVector
        .map((name, s) => (name, s.entities.size, s.targets.size))
        .sortBy(_._1.value)
    )
