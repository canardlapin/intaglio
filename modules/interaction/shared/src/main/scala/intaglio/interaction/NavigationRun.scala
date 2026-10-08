package intaglio.interaction

/** A run of continuous navigation, recorded as one history entry: the contract both interactive
  * hosts share. A run opens at its first frame, remembering the state before it.
  *
  *   - A held run, a pan drag, is ended only by the host: on release, or when the drag is abandoned
  *     (Escape, a cancelled pointer, lost focus), however long the reader holds still.
  *   - Any other run (wheel, pinch or key zoom) also ends after `pauseMs` without a new frame.
  *   - A recorded change, undo or redo ends a run first, so the step reads the history after it.
  *
  * `schedule(ms, task)` runs `task` once after `ms` milliseconds on the host's own thread and
  * returns a function that cancels it. The host records what [[end]] returns.
  */
private[intaglio] final class NavigationRun[A](
    pauseMs: Double,
    schedule: (Double, () => Unit) => () => Unit
):
  private var opened = Option.empty[(InteractionState[A], InputCause)]
  private var cancelPause = Option.empty[() => Unit]

  /** Bumped whenever a pause is cancelled, so a pause that fires late ends nothing. */
  private var generation = 0L

  def isOpen: Boolean = opened.nonEmpty

  /** The open run has changed the durable state since it began: ending it would record an entry. */
  def changed(now: InteractionState[A]): Boolean =
    opened.exists((before, _) => DurableState.of(before) != DurableState.of(now))

  /** Account for one frame about to be shown, from `current` (the state before it). The first frame
    * opens the run. A frame that is not `held` ends the run with `onPause` once `pauseMs` pass
    * without another frame; a held frame waits for the host to call [[end]].
    */
  def frame(current: Option[InteractionState[A]], cause: InputCause, held: Boolean)(
      onPause: () => Unit
  ): Unit =
    if opened.isEmpty then opened = current.map(_ -> cause)
    stopPause()
    if !held then
      val mine = generation
      cancelPause = Some(schedule(pauseMs, () => if generation == mine then onPause()))

  /** End the run: the state it began from and its cause, to record against the state now. */
  def end(): Option[(InteractionState[A], InputCause)] =
    stopPause()
    val run = opened
    opened = None
    run

  private def stopPause(): Unit =
    generation += 1
    cancelPause.foreach(_())
    cancelPause = None
