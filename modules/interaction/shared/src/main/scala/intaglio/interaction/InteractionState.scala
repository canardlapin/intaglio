package intaglio.interaction

import intaglio.{IntaglioError, SemanticId}

enum SelectionMode:
  case Disabled, Single, Multiple

enum SelectionOperation:
  case Replace, Add, Subtract, Toggle, Clear

enum InputCause:
  case Pointer, Keyboard, Programmatic, Projected

enum GestureMode:
  case Inspect, Pan, Rectangle, Lasso

  /** Drag a rectangle to zoom the data window to it. */
  case ZoomRectangle

enum MissingEntityPolicy:
  case Drop, Preserve

/** Host-owned visual variants for one target. `S` is deliberately unconstrained so the shared
  * reducer can serve JavaFX, Canvas, and browser hosts without importing any host style type.
  */
final case class AppearanceStyles[S](
    base: S,
    focusOutline: S,
    external: Option[S] = None,
    selection: Option[S] = None,
    hover: Option[S] = None
)

/** The resolved primary style plus an independent focus outline. */
final case class ResolvedAppearance[S](style: S, focusOutline: Option[S])

object InteractionAppearance:
  /** Resolve base/external, selection, then hover precedence. Focus never replaces the primary
    * style: an active focus always supplies its independent outline.
    */
  def resolve[S](
      styles: AppearanceStyles[S],
      selected: Boolean,
      hovered: Boolean,
      focused: Boolean
  ): ResolvedAppearance[S] =
    val external = styles.external.getOrElse(styles.base)
    val selection = if selected then styles.selection.getOrElse(external) else external
    val primary = if hovered then styles.hover.getOrElse(selection) else selection
    ResolvedAppearance(primary, if focused then Some(styles.focusOutline) else None)

/** Targets select displayed results; entities select observations. Neither implies the other. */
final case class Selection[A](
    entities: Set[EntityKey[A]] = Set.empty[EntityKey[A]],
    targets: Set[VisualTargetId] = Set.empty
):
  def size: Int = entities.size + targets.size

/** Display-independent data window. Hosts preserve the axis kind and apply its inverse mapping. */
final class PanelViewport private (
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double
):
  override def equals(other: Any): Boolean = other match
    case that: PanelViewport =>
      xMin == that.xMin && xMax == that.xMax && yMin == that.yMin && yMax == that.yMax
    case _ => false
  override def hashCode(): Int = (xMin, xMax, yMin, yMax).hashCode

object PanelViewport:
  def apply(
      xMin: Double,
      xMax: Double,
      yMin: Double,
      yMax: Double
  ): Either[InteractionError, PanelViewport] =
    if Vector(xMin, xMax, yMin, yMax).forall(_.isFinite) && xMin < xMax && yMin < yMax then
      Right(new PanelViewport(xMin, xMax, yMin, yMax))
    else Left(InteractionError.InvalidValue("viewport", "bounds must be finite and increasing"))

/** A compiled domain retains compact target groups and the source keys, including undrawn rows. A
  * domain bound from a [[NamedInteraction]] has no compiled plot plans: `plans` is empty and its
  * targets and entities are the scene's names.
  */
final class InteractionDomain[A] private (
    val revision: PlanRevision,
    val plans: Vector[InteractionPlan[A]],
    val entities: Set[EntityKey[A]],
    private val groups: Map[(SemanticId, SemanticId), TargetGroup[A]],
    private val spaces: Vector[KeySpace[A]]
):
  def target(id: VisualTargetId): Either[StateError, TargetInfo[A]] =
    groups.get((id.plan, id.scope)) match
      case Some(group) if group.series.revision == id.revision =>
        group.at(id.ordinal - group.series.first).left.map(_ => StateError.UnknownTarget(id))
      case _ => Left(StateError.UnknownTarget(id))

  private[interaction] def accepts(key: EntityKey[A]): Boolean =
    spaces.exists(_ eq key.space)

  /** The source data revision of the compiled plan a target belongs to; `None` for named scenes. */
  private[interaction] def sourceRevision(id: VisualTargetId): Option[DataRevision] =
    plans.find(_.id == id.plan).map(_.sourceRevision)

object InteractionDomain:
  def apply[A](
      plans: Vector[InteractionPlan[A]],
      revision: PlanRevision
  ): Either[StateError, InteractionDomain[A]] =
    val groups = plans.flatMap(_.groups)
    val addresses = groups.map(group => (group.series.plan, group.series.scope))
    if plans.map(_.id).distinct.size != plans.size || addresses.distinct.size != addresses.size then
      Left(StateError.InvalidInput("compiled plans require distinct identities"))
    else
      Right(
        new InteractionDomain(
          revision,
          plans,
          plans.flatMap(_.sourceEntities).toSet,
          addresses.zip(groups).toMap,
          plans.flatMap(_.spaces)
        )
      )

  /** One group of named targets over one key space, with no compiled plot behind it. */
  private[interaction] def named[A](
      revision: PlanRevision,
      group: TargetGroup[A],
      space: KeySpace[A],
      entities: Set[EntityKey[A]]
  ): InteractionDomain[A] =
    new InteractionDomain(
      revision,
      Vector.empty,
      entities,
      Map((group.series.plan, group.series.scope) -> group),
      Vector(space)
    )

enum StateError extends IntaglioError:
  case InvalidInput(reason: String)
  case StaleInput(expected: PlanRevision, actual: PlanRevision)
  case DuplicateOrOutOfOrder(origin: SemanticId, sequence: Long, last: Long)
  case UnknownTarget(id: VisualTargetId)
  case UnknownEntity
  case SelectionDisabled
  case MultipleSelectionInSingleMode
  case GestureAlreadyActive
  case NoActiveGesture

  /** An aggregate's members were asked for, but its membership is not exact (a count, a
    * representative, a partial list, or nothing): no partial set is ever returned as complete.
    */
  case MembershipNotExact(capability: MembershipCapability)

  /** An aggregate's members belong to another source revision than the one now shown. */
  case StaleMembership(expected: String, actual: String)

  def message: String = this match
    case InvalidInput(reason)         => reason
    case StaleInput(expected, actual) =>
      s"Expected interaction revision ${expected.value}, received ${actual.value}"
    case DuplicateOrOutOfOrder(origin, sequence, last) =>
      s"Input $sequence from ${origin.value} does not follow $last"
    case UnknownTarget(_)               => "Target does not belong to the current compiled domain"
    case UnknownEntity                  => "Entity does not belong to the current source domain"
    case SelectionDisabled              => "Selection is disabled"
    case MultipleSelectionInSingleMode  => "Single selection permits at most one entity or target"
    case GestureAlreadyActive           => "Cancel or complete the active gesture first"
    case NoActiveGesture                => "There is no active gesture"
    case MembershipNotExact(capability) =>
      s"The target's members are not known exactly (${capability.toString.toLowerCase} membership)"
    case StaleMembership(expected, actual) =>
      s"The target's members describe source revision $expected, not $actual"

/** Sequence numbers increase per origin, including projected deliveries. */
final case class InputStamp(
    revision: PlanRevision,
    origin: SemanticId,
    sequence: Long,
    cause: InputCause
)

/** A pending request for a deferred aggregate's exact members, at the revisions it was made for. A
  * reply is accepted only for the latest request on its target, while the domain is unchanged.
  */
final case class MembershipRequest[A](
    id: Long,
    target: VisualTargetId,
    planRevision: PlanRevision,
    sourceRevision: DataRevision,
    total: Option[Int],
    resolver: Option[SemanticId],
    operation: SelectionOperation
)

/** A resolver's answer. `Complete` must carry exactly the target's member count, distinct and in
  * the domain's key space; anything less is rejected, never applied as a partial selection.
  */
sealed trait MembershipReply[A]
object MembershipReply:
  final case class Pending[A]() extends MembershipReply[A]
  final case class Unavailable[A](reason: String) extends MembershipReply[A]
  final case class Failed[A](reason: String) extends MembershipReply[A]
  final case class Complete[A](keys: Vector[EntityKey[A]]) extends MembershipReply[A]

/** What became of a membership request or reply. */
enum MembershipOutcome:
  case Pending
  case Unavailable(reason: String)
  case Failed(reason: String)

  /** The members, `count` of them, were selected. */
  case Complete(count: Int)

  /** The reply was not applied: late, superseded, malformed, or refused by the selection mode. */
  case Rejected(reason: String)

/** A host-side service that answers membership requests, possibly asynchronously: the host
  * dispatches `RequestMembers`, hands the recorded request to `resolve`, and dispatches each reply
  * as `ResolveMembers`. The reducer never calls it.
  */
trait MembershipResolver[A]:
  def resolve(request: MembershipRequest[A], reply: MembershipReply[A] => Unit): Unit

sealed trait InteractionAction[A]
object InteractionAction:
  final case class Hover[A](target: Option[VisualTargetId]) extends InteractionAction[A]
  final case class Focus[A](target: Option[VisualTargetId]) extends InteractionAction[A]
  final case class Activate[A](target: VisualTargetId) extends InteractionAction[A]
  final case class Select[A](value: Selection[A], operation: SelectionOperation)
      extends InteractionAction[A]

  /** Select the exact contributing observations of aggregate `targets` (histogram bins, summary
    * intervals), together with `plus`, as one selection change under `operation`. Refused unless
    * every target's membership is exact and current; selecting the targets themselves is
    * [[Select]]. Operations apply per observation key, as for [[Select]]: toggling a partly
    * selected bin flips each of its members, so the selected and unselected halves swap.
    */
  final case class SelectMembers[A](
      targets: Set[VisualTargetId],
      operation: SelectionOperation,
      plus: Selection[A] = Selection[A]()
  ) extends InteractionAction[A]

  /** Ask for a deferred aggregate's members. `requestId` increases per target; a newer request
    * supersedes a pending one.
    */
  final case class RequestMembers[A](
      target: VisualTargetId,
      requestId: Long,
      operation: SelectionOperation
  ) extends InteractionAction[A]

  /** A resolver's reply to request `requestId` for `target`. */
  final case class ResolveMembers[A](
      target: VisualTargetId,
      requestId: Long,
      reply: MembershipReply[A]
  ) extends InteractionAction[A]
  final case class SetSelectionMode[A](value: SelectionMode) extends InteractionAction[A]
  final case class SetViewport[A](panel: SemanticId, value: Option[PanelViewport])
      extends InteractionAction[A]
  final case class SetGestureMode[A](value: GestureMode) extends InteractionAction[A]
  final case class BeginGesture[A](pointer: Long) extends InteractionAction[A]
  final case class EndGesture[A](cancelled: Boolean) extends InteractionAction[A]

sealed trait InteractionEvent[A]
object InteractionEvent:
  final case class HoverChanged[A](target: Option[TargetInfo[A]]) extends InteractionEvent[A]
  final case class FocusChanged[A](target: Option[TargetInfo[A]]) extends InteractionEvent[A]
  final case class Activated[A](target: TargetInfo[A]) extends InteractionEvent[A]
  final case class SelectionChanged[A](value: Selection[A]) extends InteractionEvent[A]
  final case class SelectionModeChanged[A](value: SelectionMode) extends InteractionEvent[A]
  final case class ViewportChanged[A](panel: SemanticId, value: Option[PanelViewport])
      extends InteractionEvent[A]
  final case class GestureModeChanged[A](value: GestureMode) extends InteractionEvent[A]
  final case class GestureStarted[A](pointer: Long, mode: GestureMode) extends InteractionEvent[A]
  final case class GestureEnded[A](pointer: Long, cancelled: Boolean) extends InteractionEvent[A]
  final case class MembershipRequested[A](request: MembershipRequest[A]) extends InteractionEvent[A]
  final case class MembershipResolved[A](
      target: VisualTargetId,
      requestId: Long,
      outcome: MembershipOutcome
  ) extends InteractionEvent[A]
  final case class Reconciled[A](
      removed: Set[EntityKey[A]],
      unresolved: Set[EntityKey[A]],
      removedTargets: Set[VisualTargetId]
  ) extends InteractionEvent[A]

final case class EventRecord[A](stamp: InputStamp, event: InteractionEvent[A])
final case class ActiveGesture(pointer: Long, mode: GestureMode)

/** Constructed only by checked transitions. Viewport changes never edit selection. */
final class InteractionState[A] private[interaction] (
    val domain: InteractionDomain[A],
    val selectionMode: SelectionMode,
    val selection: Selection[A],
    val unresolved: Set[EntityKey[A]],
    val hover: Option[VisualTargetId],
    val focus: Option[VisualTargetId],
    val gestureMode: GestureMode,
    val gesture: Option[ActiveGesture],
    val viewports: Map[SemanticId, PanelViewport],
    private[interaction] val delivered: Map[SemanticId, Long],
    /** Membership requests awaiting a reply, at most one (the latest) per target. */
    val pendingMembers: Map[VisualTargetId, MembershipRequest[A]] =
      Map.empty[VisualTargetId, MembershipRequest[A]],
    /** The highest request id ever issued per target. It outlives replies and domain replacement,
      * so an id is never reused and a duplicate of an old reply can never answer a newer request.
      */
    private[interaction] val requestMarks: Map[VisualTargetId, Long] =
      Map.empty[VisualTargetId, Long]
)

final case class StateTransition[A](state: InteractionState[A], events: Vector[EventRecord[A]])

/** No host callbacks, clock reads, DOM access, or mutation occur in this reducer. */
object InteractionState:
  def initial[A](
      domain: InteractionDomain[A],
      mode: SelectionMode = SelectionMode.Multiple,
      selected: Selection[A] = Selection[A]()
  ): Either[StateError, InteractionState[A]] =
    validateSelection(domain, selected, Set.empty).flatMap(_ => checkMode(mode, selected)).map {
      _ =>
        new InteractionState(
          domain,
          mode,
          selected,
          Set.empty,
          None,
          None,
          GestureMode.Inspect,
          None,
          Map.empty,
          Map.empty
        )
    }

  def reduce[A](
      state: InteractionState[A],
      stamp: InputStamp,
      action: InteractionAction[A]
  ): Either[StateError, StateTransition[A]] =
    validateStamp(state, stamp).flatMap { _ =>
      def finish(next: InteractionState[A], events: Vector[InteractionEvent[A]]) =
        StateTransition(
          next,
          if stamp.cause == InputCause.Projected then Vector.empty
          else events.map(EventRecord(stamp, _))
        )

      def changed(
          selectionMode: SelectionMode = state.selectionMode,
          selection: Selection[A] = state.selection,
          hover: Option[VisualTargetId] = state.hover,
          focus: Option[VisualTargetId] = state.focus,
          gestureMode: GestureMode = state.gestureMode,
          gesture: Option[ActiveGesture] = state.gesture,
          viewports: Map[SemanticId, PanelViewport] = state.viewports,
          pending: Map[VisualTargetId, MembershipRequest[A]] = state.pendingMembers,
          marks: Map[VisualTargetId, Long] = state.requestMarks
      ) =
        new InteractionState(
          state.domain,
          selectionMode,
          selection,
          state.unresolved.intersect(selection.entities),
          hover,
          focus,
          gestureMode,
          gesture,
          viewports,
          state.delivered.updated(stamp.origin, stamp.sequence),
          pending,
          marks
        )

      def optional(id: Option[VisualTargetId]): Either[StateError, Option[TargetInfo[A]]] =
        id match
          case Some(value) => state.domain.target(value).map(Some(_))
          case None        => Right(None)

      def select(
          value: Selection[A],
          operation: SelectionOperation,
          pending: Map[VisualTargetId, MembershipRequest[A]] = state.pendingMembers
      ): Either[StateError, StateTransition[A]] =
        // Even subtraction validates its input: stale routes must not be silently accepted.
        validateSelection(state.domain, value, state.unresolved).flatMap { _ =>
          val old = state.selection
          val next = operation match
            case SelectionOperation.Replace => value
            case SelectionOperation.Add     =>
              Selection(old.entities ++ value.entities, old.targets ++ value.targets)
            case SelectionOperation.Subtract =>
              Selection(old.entities -- value.entities, old.targets -- value.targets)
            case SelectionOperation.Toggle =>
              Selection(
                (old.entities -- value.entities) ++ (value.entities -- old.entities),
                (old.targets -- value.targets) ++ (value.targets -- old.targets)
              )
            case SelectionOperation.Clear => Selection[A]()
          checkMode(state.selectionMode, next).map { _ =>
            finish(
              changed(selection = next, pending = pending),
              if next == old then Vector.empty
              else Vector(InteractionEvent.SelectionChanged(next))
            )
          }
        }

      /** A complete reply's keys, if they are exactly the request's members: distinct, in this
        * domain's sources, and as many as the target counted.
        */
      def completeReply(
          request: MembershipRequest[A],
          keys: Vector[EntityKey[A]]
      ): Either[String, Set[EntityKey[A]]] =
        val set = keys.toSet
        val space = state.domain.target(request.target).toOption.map(_.membership.space)
        val sources = state.domain.plans
          .find(_.id == request.target.plan)
          .fold(Set.empty[EntityKey[A]])(_.sourceEntities.toSet)
        if set.size != keys.size then Left("the reply repeats a key")
        else if keys.exists(key => !space.exists(_ eq key.space)) then
          Left("the reply has a key from another key space than the target's")
        else if !set.subsetOf(sources) then
          Left("the reply has a key that is not an observation of the target's plot")
        else if request.total.exists(_ != set.size) then
          Left(s"the reply has ${set.size} members, not ${request.total.getOrElse(0)}")
        else Right(set)

      /** Every target's exact members at the source revision its plan was compiled from. */
      def members(targets: Set[VisualTargetId]): Either[StateError, Set[EntityKey[A]]] =
        // In a fixed order, so the first failing target reported does not depend on Set order.
        val ordered = targets.toVector.sortBy(t => (t.plan.value, t.scope.value, t.ordinal))
        ordered.foldLeft[Either[StateError, Set[EntityKey[A]]]](Right(Set.empty)) { (acc, id) =>
          for
            keys <- acc
            info <- state.domain.target(id)
            current <- state.domain
              .sourceRevision(id)
              .toRight(StateError.MembershipNotExact(info.membership.capability))
            exact <- info.membership.exactKeys(current).left.map {
              case InteractionError.StaleRevision(expected, actual) =>
                StateError.StaleMembership(expected, actual)
              case InteractionError.MembershipUnavailable(capability) =>
                StateError.MembershipNotExact(capability)
              case other => StateError.InvalidInput(other.message)
            }
          yield keys ++ exact
        }

      action match
        case InteractionAction.Hover(id) =>
          optional(id).map { info =>
            finish(
              changed(hover = id),
              if id == state.hover then Vector.empty
              else Vector(InteractionEvent.HoverChanged(info))
            )
          }
        case InteractionAction.Focus(id) =>
          optional(id).map { info =>
            finish(
              changed(focus = id),
              if id == state.focus then Vector.empty
              else Vector(InteractionEvent.FocusChanged(info))
            )
          }
        case InteractionAction.Activate(id) =>
          state.domain.target(id).map { info =>
            finish(changed(), Vector(InteractionEvent.Activated(info)))
          }
        case InteractionAction.Select(value, operation)                => select(value, operation)
        case InteractionAction.SelectMembers(targets, operation, plus) =>
          members(targets).flatMap(keys =>
            select(Selection(plus.entities ++ keys, plus.targets), operation)
          )
        case InteractionAction.RequestMembers(id, requestId, operation) =>
          for
            info <- state.domain.target(id)
            _ <- info.membership.capability match
              case MembershipCapability.Deferred => Right(())
              case MembershipCapability.Exact    =>
                Left(StateError.InvalidInput("exact members need no resolver: use SelectMembers"))
              case other => Left(StateError.MembershipNotExact(other))
            _ <- Either.cond(
              state.selectionMode != SelectionMode.Disabled,
              (),
              StateError.SelectionDisabled
            )
            _ <- Either.cond(
              stamp.cause != InputCause.Projected,
              (),
              StateError.InvalidInput("a projected request would never reach a resolver")
            )
            _ <- state.requestMarks.get(id) match
              case Some(previous) if requestId <= previous =>
                Left(StateError.InvalidInput(s"request $requestId does not follow $previous"))
              case _ => Right(())
            source <- state.domain
              .sourceRevision(id)
              .toRight(StateError.MembershipNotExact(info.membership.capability))
          yield
            val request = MembershipRequest[A](
              requestId,
              id,
              state.domain.revision,
              source,
              info.membership.total,
              info.membership.resolver,
              operation
            )
            finish(
              changed(
                pending = state.pendingMembers.updated(id, request),
                marks = state.requestMarks.updated(id, requestId)
              ),
              Vector(InteractionEvent.MembershipRequested(request))
            )
        case InteractionAction.ResolveMembers(id, requestId, reply) =>
          def outcome(
              pending: Map[VisualTargetId, MembershipRequest[A]],
              value: MembershipOutcome
          ) =
            finish(
              changed(pending = pending),
              Vector(InteractionEvent.MembershipResolved(id, requestId, value))
            )
          state.pendingMembers.get(id) match
            case Some(request) if request.id == requestId =>
              val cleared = state.pendingMembers - id
              reply match
                case MembershipReply.Pending() =>
                  Right(outcome(state.pendingMembers, MembershipOutcome.Pending))
                case MembershipReply.Unavailable(reason) =>
                  Right(outcome(cleared, MembershipOutcome.Unavailable(reason)))
                case MembershipReply.Failed(reason) =>
                  Right(outcome(cleared, MembershipOutcome.Failed(reason)))
                case MembershipReply.Complete(keys) =>
                  completeReply(request, keys) match
                    case Left(reason) => Right(outcome(cleared, MembershipOutcome.Rejected(reason)))
                    case Right(set)   =>
                      select(Selection(set), request.operation, cleared) match
                        case Left(error) =>
                          Right(outcome(cleared, MembershipOutcome.Rejected(error.message)))
                        case Right(applied) =>
                          val resolved = finish(
                            applied.state,
                            Vector(
                              InteractionEvent.MembershipResolved(
                                id,
                                requestId,
                                MembershipOutcome.Complete(set.size)
                              )
                            )
                          )
                          Right(applied.copy(events = applied.events ++ resolved.events))
            case _ =>
              Right(
                outcome(
                  state.pendingMembers,
                  MembershipOutcome.Rejected(s"request $requestId for this target is not pending")
                )
              )
        case InteractionAction.SetSelectionMode(mode) =>
          checkMode(mode, state.selection).map { _ =>
            finish(
              changed(selectionMode = mode),
              if mode == state.selectionMode then Vector.empty
              else Vector(InteractionEvent.SelectionModeChanged(mode))
            )
          }
        case InteractionAction.SetViewport(panel, value) =>
          val next =
            value.fold(state.viewports - panel)(window => state.viewports.updated(panel, window))
          Right(
            finish(
              changed(viewports = next),
              if next == state.viewports then Vector.empty
              else Vector(InteractionEvent.ViewportChanged(panel, value))
            )
          )
        case InteractionAction.SetGestureMode(mode) =>
          if state.gesture.nonEmpty then Left(StateError.GestureAlreadyActive)
          else
            Right(
              finish(
                changed(gestureMode = mode),
                if mode == state.gestureMode then Vector.empty
                else Vector(InteractionEvent.GestureModeChanged(mode))
              )
            )
        case InteractionAction.BeginGesture(pointer) =>
          if pointer < 0 then Left(StateError.InvalidInput("Pointer identity must be nonnegative"))
          else if state.gesture.nonEmpty then Left(StateError.GestureAlreadyActive)
          else
            Right(
              finish(
                changed(gesture = Some(ActiveGesture(pointer, state.gestureMode))),
                Vector(InteractionEvent.GestureStarted(pointer, state.gestureMode))
              )
            )
        case InteractionAction.EndGesture(cancelled) =>
          state.gesture match
            case None         => Left(StateError.NoActiveGesture)
            case Some(active) =>
              Right(
                finish(
                  changed(gesture = None),
                  Vector(InteractionEvent.GestureEnded(active.pointer, cancelled))
                )
              )
    }

  /** Replacement cancels transient input and resets viewports; persistent keys follow an explicit
    * policy.
    */
  def replaceDomain[A](
      state: InteractionState[A],
      stamp: InputStamp,
      domain: InteractionDomain[A],
      policy: MissingEntityPolicy
  ): Either[StateError, StateTransition[A]] =
    validateStamp(state, stamp).flatMap { _ =>
      if domain.revision == state.domain.revision then
        Left(StateError.InvalidInput("Replacement requires a new domain revision"))
      else
        val missing = state.selection.entities -- domain.entities
        val retained = policy match
          case MissingEntityPolicy.Drop     => state.selection.entities -- missing
          case MissingEntityPolicy.Preserve => state.selection.entities
        if retained.exists(key => !domain.accepts(key)) then Left(StateError.UnknownEntity)
        else
          val targets = state.selection.targets.filter(domain.target(_).isRight)
          val unresolved = retained -- domain.entities
          val selection = Selection(retained, targets)
          val next = new InteractionState(
            domain,
            state.selectionMode,
            selection,
            unresolved,
            None,
            None,
            state.gestureMode,
            None,
            Map.empty,
            state.delivered.updated(stamp.origin, stamp.sequence),
            // A new domain cancels every pending request; request ids stay spent.
            Map.empty,
            state.requestMarks
          )
          val event = InteractionEvent.Reconciled(
            state.selection.entities -- retained,
            unresolved,
            state.selection.targets -- targets
          )
          Right(
            StateTransition(
              next,
              if stamp.cause == InputCause.Projected then Vector.empty
              else Vector(EventRecord(stamp, event))
            )
          )
    }

  private def validateStamp[A](
      state: InteractionState[A],
      stamp: InputStamp
  ): Either[StateError, Unit] =
    if stamp.revision != state.domain.revision then
      Left(StateError.StaleInput(state.domain.revision, stamp.revision))
    else if stamp.sequence < 0 then
      Left(StateError.InvalidInput("Input sequence must be nonnegative"))
    else
      state.delivered.get(stamp.origin) match
        case Some(last) if stamp.sequence <= last =>
          Left(StateError.DuplicateOrOutOfOrder(stamp.origin, stamp.sequence, last))
        case _ => Right(())

  private def checkMode[A](mode: SelectionMode, selection: Selection[A]): Either[StateError, Unit] =
    if mode == SelectionMode.Disabled && selection.size > 0 then Left(StateError.SelectionDisabled)
    else if mode == SelectionMode.Single && selection.size > 1 then
      Left(StateError.MultipleSelectionInSingleMode)
    else Right(())

  private def validateSelection[A](
      domain: InteractionDomain[A],
      selection: Selection[A],
      unresolved: Set[EntityKey[A]]
  ): Either[StateError, Unit] =
    if selection.entities.exists(key => !domain.entities.contains(key) && !unresolved.contains(key))
    then Left(StateError.UnknownEntity)
    else
      selection.targets.iterator.map(domain.target).collectFirst { case Left(error) => error } match
        case Some(error) => Left(error)
        case None        => Right(())
