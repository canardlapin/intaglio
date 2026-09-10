package intaglio.interaction

import intaglio.IntaglioError
import scala.util.control.NonFatal

enum ControllerError extends IntaglioError:
  case Disposed
  case DuringDelivery
  case InvalidState(error: StateError)

  def message: String = this match
    case Disposed            => "Interaction controller has been disposed"
    case DuringDelivery      => "Schedule state changes after the current event delivery"
    case InvalidState(error) => error.message

/** A failed subscriber does not prevent delivery to other subscribers or undo committed state. */
final case class DeliveryFailure(subscription: Long, eventIndex: Int)
final case class DispatchResult[A](
    transition: StateTransition[A],
    failures: Vector[DeliveryFailure]
)

/** Idempotent removal. The controller also owns and removes all subscriptions on disposal. */
final class InteractionSubscription private[interaction] (remove: () => Unit):
  private var pending: Option[() => Unit] = Some(remove)
  def cancel(): Unit =
    val action = pending
    pending = None
    action.foreach(_())

/** A synchronous owner for one host event loop. Marshal cross-thread work before dispatching.
  *
  * State is committed before listeners run. Reentrant dispatch is rejected explicitly; hosts may
  * schedule a new input after delivery. Listener removal and controller disposal are immediate.
  */
final class InteractionController[A](initial: InteractionState[A]):
  private var current: Option[InteractionState[A]] = Some(initial)
  private var listeners = Vector.empty[(Long, EventRecord[A] => Unit)]
  private var nextSubscription = 0L
  private var delivering = false

  def state: Either[ControllerError, InteractionState[A]] =
    current.toRight(ControllerError.Disposed)

  def subscribe(
      listener: EventRecord[A] => Unit
  ): Either[ControllerError, InteractionSubscription] =
    if current.isEmpty then Left(ControllerError.Disposed)
    else
      val id = nextSubscription
      nextSubscription += 1
      listeners = listeners :+ (id -> listener)
      Right(new InteractionSubscription(() => listeners = listeners.filterNot(_._1 == id)))

  def dispatch(
      stamp: InputStamp,
      action: InteractionAction[A]
  ): Either[ControllerError, DispatchResult[A]] =
    update(state => InteractionState.reduce(state, stamp, action))

  def replaceDomain(
      stamp: InputStamp,
      domain: InteractionDomain[A],
      policy: MissingEntityPolicy
  ): Either[ControllerError, DispatchResult[A]] =
    update(state => InteractionState.replaceDomain(state, stamp, domain, policy))

  def dispose(): Unit =
    current = None
    listeners = Vector.empty

  private def update(
      reduce: InteractionState[A] => Either[StateError, StateTransition[A]]
  ): Either[ControllerError, DispatchResult[A]] =
    if delivering then Left(ControllerError.DuringDelivery)
    else
      state.flatMap { before =>
        reduce(before).left.map(ControllerError.InvalidState(_)).map { transition =>
          current = Some(transition.state)
          val snapshot = listeners
          val failures = Vector.newBuilder[DeliveryFailure]
          delivering = true
          try
            transition.events.zipWithIndex.foreach { case (event, eventIndex) =>
              snapshot.foreach { case (id, listener) =>
                if listeners.exists(_._1 == id) then
                  try listener(event)
                  catch case NonFatal(_) => failures += DeliveryFailure(id, eventIndex)
              }
            }
          finally delivering = false
          DispatchResult(transition, failures.result())
        }
      }
