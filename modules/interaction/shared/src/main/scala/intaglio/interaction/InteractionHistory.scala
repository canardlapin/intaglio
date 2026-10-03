package intaglio.interaction

import intaglio.*

/** The durable part of a state: what history and snapshots keep. */
private[interaction] final case class DurableState[A](
    domainRevision: PlanRevision,
    mode: SelectionMode,
    selection: Selection[A],
    named: Map[SelectionName, Selection[A]],
    viewports: Map[SemanticId, PanelViewport],
    unresolved: Set[EntityKey[A]]
):
  def restored: RestoredSnapshot[A] =
    RestoredSnapshot(domainRevision, mode, selection, named, viewports, unresolved)

private[interaction] object DurableState:
  def of[A](state: InteractionState[A]): DurableState[A] =
    DurableState(
      state.domain.revision,
      state.selectionMode,
      state.selection,
      state.named,
      state.viewports,
      state.unresolved
    )

/** Undo and redo over the durable state (selection, saved selections, panel viewports, selection
  * mode). The boundaries are fixed:
  *
  *   - An entry is the durable state just before a committed change made by this plot's reader
  *     (`Pointer`, `Keyboard`) or by an explicit application command (`Programmatic`).
  *   - `Projected` input (a linked view, `setSelection`) records no entry and keeps the redo stack;
  *     undoing restores the state before this plot's own change, which a link then carries on.
  *   - A new recorded change clears redo; a new domain (new data) clears both stacks.
  *   - Hover, focus, gestures and membership requests are never history, and undo and redo apply a
  *     `RestoreSnapshot`, which activates nothing and asks no resolver.
  */
final class InteractionHistory[A] private (
    val depth: Int,
    private val undoStack: List[DurableState[A]],
    private val redoStack: List[DurableState[A]]
):
  def canUndo: Boolean = undoStack.nonEmpty
  def canRedo: Boolean = redoStack.nonEmpty
  def undoSize: Int = undoStack.size
  def redoSize: Int = redoStack.size

  /** Account for one committed transition from `before` to `after` under `cause`. */
  def record(
      before: InteractionState[A],
      after: InteractionState[A],
      cause: InputCause
  ): InteractionHistory[A] =
    if after.domain.revision != before.domain.revision then new InteractionHistory(depth, Nil, Nil)
    else
      val was = DurableState.of(before)
      if was == DurableState.of(after) || cause == InputCause.Projected then this
      else new InteractionHistory(depth, (was :: undoStack).take(depth), Nil)

  /** The restore that undoes the last recorded change from `current`, and the history after it. */
  def undo(current: InteractionState[A]): Option[(InteractionAction[A], InteractionHistory[A])] =
    step(current, undoStack, redoStack).map((action, from, to) =>
      action -> new InteractionHistory(depth, from, to)
    )

  /** The restore that reapplies the last undone change from `current`, and the history after it. */
  def redo(current: InteractionState[A]): Option[(InteractionAction[A], InteractionHistory[A])] =
    step(current, redoStack, undoStack).map((action, from, to) =>
      action -> new InteractionHistory(depth, to, from)
    )

  private def step(
      current: InteractionState[A],
      from: List[DurableState[A]],
      to: List[DurableState[A]]
  ): Option[(InteractionAction[A], List[DurableState[A]], List[DurableState[A]])] =
    from match
      case target :: rest if target.domainRevision == current.domain.revision =>
        Some(
          (
            InteractionAction.RestoreSnapshot(target.restored),
            rest,
            (DurableState.of(current) :: to).take(depth)
          )
        )
      case _ => None

object InteractionHistory:
  def empty[A](depth: Int = 100): Either[InteractionError, InteractionHistory[A]] =
    if depth < 1 || depth > 100000 then
      Left(InteractionError.InvalidValue("history depth", s"$depth is not in 1..100000"))
    else Right(new InteractionHistory(depth, Nil, Nil))

/** An [[InteractionController]] with undo and redo: every dispatch and domain replacement through
  * it is recorded by the [[InteractionHistory]] rules; [[undo]] and [[redo]] dispatch restores,
  * which are not recorded themselves.
  */
final class HistoryController[A] private (
    val controller: InteractionController[A],
    private var current: InteractionHistory[A]
):
  def history: InteractionHistory[A] = current

  def dispatch(
      stamp: InputStamp,
      action: InteractionAction[A]
  ): Either[ControllerError, DispatchResult[A]] =
    tracked(stamp.cause)(controller.dispatch(stamp, action))

  def replaceDomain(
      stamp: InputStamp,
      domain: InteractionDomain[A],
      policy: MissingEntityPolicy
  ): Either[ControllerError, DispatchResult[A]] =
    tracked(stamp.cause)(controller.replaceDomain(stamp, domain, policy))

  /** Undo the last recorded change; `None` when there is nothing to undo. */
  def undo(stamp: InputStamp): Option[Either[ControllerError, DispatchResult[A]]] =
    move(stamp, current.undo)

  def redo(stamp: InputStamp): Option[Either[ControllerError, DispatchResult[A]]] =
    move(stamp, current.redo)

  private def move(
      stamp: InputStamp,
      step: InteractionState[A] => Option[(InteractionAction[A], InteractionHistory[A])]
  ): Option[Either[ControllerError, DispatchResult[A]]] =
    controller.state.toOption.flatMap(step).map { (action, next) =>
      val result = controller.dispatch(stamp, action)
      if result.isRight then current = next
      result
    }

  private def tracked(cause: InputCause)(
      run: => Either[ControllerError, DispatchResult[A]]
  ): Either[ControllerError, DispatchResult[A]] =
    val before = controller.state.toOption
    val result = run
    for
      was <- before
      _ <- result.toOption
      now <- controller.state.toOption
    do current = current.record(was, now, cause)
    result

object HistoryController:
  def apply[A](
      controller: InteractionController[A],
      depth: Int = 100
  ): Either[InteractionError, HistoryController[A]] =
    InteractionHistory.empty[A](depth).map(new HistoryController(controller, _))
