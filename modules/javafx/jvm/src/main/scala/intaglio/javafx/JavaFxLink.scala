package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.application.Platform

/** A group of linked JavaFX hosts over one key space, with the browser `WidgetLink`'s rules.
  *
  * The link owns one selection of observation keys for the whole group. When a reader changes the
  * observations selected in one member, only that change (keys added and removed, among the keys
  * that member can select) updates the group, and every other member is shown the group's selection
  * projected into the keys it contains. A key one member lacks therefore survives a reader's
  * additive change in another, and keys a member lacks are reported through `onMissing`, never
  * invented. A change that adds or removes no selectable observation (choosing histogram bins as
  * bins) stays in its plot. What the reader points at is shown in the other members as linked
  * emphasis.
  *
  * Projection arrives as `Projected` input, which emits no event, and emphasis is display only, so
  * a link can never echo or loop. A projection a member refuses (several keys into a
  * single-selection plot) is reported through `onError`. A host belongs to at most one live link.
  * Create, use and dispose a link on the FX application thread.
  */
final class JavaFxLink[A] private (
    space: KeySpace[A],
    hosts: Vector[JavaFxInteractionHost[A]],
    onMissing: (JavaFxInteractionHost[A], Set[EntityKey[A]]) => Unit,
    onError: (JavaFxInteractionHost[A], IntaglioError) => Unit
):
  private def inSpace(keys: Set[EntityKey[A]]): Set[EntityKey[A]] = keys.filter(_.space eq space)

  private var shown: Map[Int, Set[EntityKey[A]]] = hosts.indices
    .map(i =>
      i -> inSpace(hosts(i).state.toOption.fold(Set.empty[EntityKey[A]])(_.selection.entities))
    )
    .toMap
  // The group's selection starts as everything any member already has selected.
  private var shared: Set[EntityKey[A]] = shown.values.flatten.toSet
  private var unhook = Vector.empty[() => Unit]

  /** The group's current selection of observation keys. */
  def selection: Set[EntityKey[A]] = shared

  private def wire(): Either[IntaglioError, Unit] =
    hosts.zipWithIndex.foldLeft[Either[IntaglioError, Unit]](Right(())) { case (done, (host, i)) =>
      done.flatMap { _ =>
        for
          subscription <- host.subscribe { record =>
            record.event match
              case InteractionEvent.SelectionChanged(value)
                  if record.stamp.cause != InputCause.Projected =>
                changed(i, value.entities)
              case _ => ()
          }
          _ = unhook = unhook :+ (() => subscription.cancel())
          stop <- host.subscribeHover(emphasis =>
            hosts
              .patch(i, Nil, 1)
              .foreach(other => other.setLinkedEmphasis(emphasis).left.foreach(onError(other, _)))
          )
        yield unhook = unhook :+ stop
      }
    }

  /** Member `i`'s reader changed its observation keys to `now`. */
  private def changed(i: Int, now: Set[EntityKey[A]]): Unit =
    val before = shown.getOrElse(i, Set.empty)
    val selectable = inSpace(hosts(i).selectableEntities)
    val mine = inSpace(now)
    val added = (mine -- before).intersect(selectable)
    val removed = (before -- mine).intersect(selectable)
    shown += i -> mine
    if added.nonEmpty || removed.nonEmpty then
      shared = (shared ++ added) -- removed
      hosts.indices.filter(_ != i).foreach { j =>
        val member = hosts(j)
        member.state match
          case Left(error)  => onError(member, error)
          case Right(state) =>
            val projected = SelectionProjection.into(Selection(shared), state.domain)
            // Keep the member's own plot-local targets (bins); replace only its observations.
            val next = projected.selection.copy(targets = state.selection.targets)
            member.setSelection(next) match
              case Left(error) => onError(member, error)
              case Right(_)    =>
                shown += j -> inSpace(next.entities)
                if projected.missing.nonEmpty then onMissing(member, projected.missing)
      }

  /** Unhook the group, clear the linked emphasis it set, and leave each selection as it is. */
  def dispose(): Unit =
    unhook.foreach(_())
    unhook = Vector.empty
    hosts.foreach { host =>
      if !host.isDisposed then host.setLinkedEmphasis(LinkedEmphasis.none[A])
      if host.link.exists(_ eq this) then host.link = None
    }

object JavaFxLink:
  /** Link `hosts` over observation keys of `space`, on the FX application thread. A member keyed by
    * another space (even one with the same namespace text) never feeds the group's selection, and
    * every key projected into it is reported missing.
    */
  def connect[A](
      space: KeySpace[A],
      hosts: Vector[JavaFxInteractionHost[A]],
      onMissing: (JavaFxInteractionHost[A], Set[EntityKey[A]]) => Unit =
        (_: JavaFxInteractionHost[A], _: Set[EntityKey[A]]) => (),
      onError: (JavaFxInteractionHost[A], IntaglioError) => Unit =
        (_: JavaFxInteractionHost[A], _: IntaglioError) => ()
  ): Either[IntaglioError, JavaFxLink[A]] =
    if !Platform.isFxApplicationThread then Left(JavaFxHostError.WrongThread)
    else if hosts.distinct.length != hosts.length then
      Left(InteractionError.InvalidValue("host link", "a host appears twice"))
    else if hosts.exists(_.isDisposed) then Left(JavaFxHostError.Disposed)
    else if hosts.exists(_.link.nonEmpty) then
      // Projection is silent, so a host relaying between two links would leave them disagreeing.
      Left(InteractionError.InvalidValue("host link", "a host already belongs to a live link"))
    else
      val link = new JavaFxLink(space, hosts, onMissing, onError)
      hosts.foreach(_.link = Some(link))
      link.wire() match
        case Right(_)    => Right(link)
        case Left(error) =>
          link.dispose()
          Left(error)
