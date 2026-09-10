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

enum MissingEntityPolicy:
  case Drop, Preserve

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

/** A compiled domain retains compact target groups and the source keys, including undrawn rows. */
final class InteractionDomain[A] private (
    val revision: PlanRevision,
    val plans: Vector[InteractionPlan[A]],
    val entities: Set[EntityKey[A]],
    private val groups: Map[(SemanticId, SemanticId), TargetGroup[A]]
):
  def target(id: VisualTargetId): Either[StateError, TargetInfo[A]] =
    groups.get((id.plan, id.scope)) match
      case Some(group) if group.series.revision == id.revision =>
        group.at(id.ordinal - group.series.first).left.map(_ => StateError.UnknownTarget(id))
      case _ => Left(StateError.UnknownTarget(id))

  private[interaction] def accepts(key: EntityKey[A]): Boolean =
    plans.exists(_.spaces.exists(_ eq key.space))

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
          addresses.zip(groups).toMap
        )
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

  def message: String = this match
    case InvalidInput(reason)         => reason
    case StaleInput(expected, actual) =>
      s"Expected interaction revision ${expected.value}, received ${actual.value}"
    case DuplicateOrOutOfOrder(origin, sequence, last) =>
      s"Input $sequence from ${origin.value} does not follow $last"
    case UnknownTarget(_)              => "Target does not belong to the current compiled domain"
    case UnknownEntity                 => "Entity does not belong to the current source domain"
    case SelectionDisabled             => "Selection is disabled"
    case MultipleSelectionInSingleMode => "Single selection permits at most one entity or target"
    case GestureAlreadyActive          => "Cancel or complete the active gesture first"
    case NoActiveGesture               => "There is no active gesture"

/** Sequence numbers increase per origin, including projected deliveries. */
final case class InputStamp(
    revision: PlanRevision,
    origin: SemanticId,
    sequence: Long,
    cause: InputCause
)

sealed trait InteractionAction[A]
object InteractionAction:
  final case class Hover[A](target: Option[VisualTargetId]) extends InteractionAction[A]
  final case class Focus[A](target: Option[VisualTargetId]) extends InteractionAction[A]
  final case class Activate[A](target: VisualTargetId) extends InteractionAction[A]
  final case class Select[A](value: Selection[A], operation: SelectionOperation)
      extends InteractionAction[A]
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
    private[interaction] val delivered: Map[SemanticId, Long]
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
          viewports: Map[SemanticId, PanelViewport] = state.viewports
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
          state.delivered.updated(stamp.origin, stamp.sequence)
        )

      def optional(id: Option[VisualTargetId]): Either[StateError, Option[TargetInfo[A]]] =
        id match
          case Some(value) => state.domain.target(value).map(Some(_))
          case None        => Right(None)

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
        case InteractionAction.Select(value, operation) =>
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
                changed(selection = next),
                if next == old then Vector.empty
                else Vector(InteractionEvent.SelectionChanged(next))
              )
            }
          }
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
            state.delivered.updated(stamp.origin, stamp.sequence)
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
