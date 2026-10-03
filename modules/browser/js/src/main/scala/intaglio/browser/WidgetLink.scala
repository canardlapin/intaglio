package intaglio.browser

import intaglio.*
import intaglio.interaction.*

/** A group of linked widgets over one key space.
  *
  * The link owns one selection of observation keys for the whole group. When a reader changes the
  * observations selected in one member, only that change (keys added and removed) updates the
  * group's selection, and every other member is shown the group's selection projected into the keys
  * it contains. A key one member lacks therefore survives a reader's additive change in another,
  * and keys a member lacks are reported through `onMissing`, never invented. A change that adds or
  * removes no observation keys the plot draws as marks (selecting, toggling or clearing histogram
  * bins as bins) stays in its plot. What the reader points at is shown in the other members as
  * linked emphasis.
  *
  * Projection arrives as `Projected` input, which emits no event, and emphasis is display only, so
  * a link can never echo or loop. A projection a member refuses (for instance several keys into a
  * single-selection plot) is reported through `onError`. [[dispose]] unhooks the group, clears the
  * linked emphasis it set, and leaves each widget's selection as it is.
  */
final class WidgetLink[A] private (
    widgets: Vector[SvgWidget[A]],
    onMissing: (SvgWidget[A], Set[EntityKey[A]]) => Unit,
    onError: (SvgWidget[A], IntaglioError) => Unit
):
  private var shown: Map[Int, Set[EntityKey[A]]] = widgets.indices
    .map(i => i -> widgets(i).state.toOption.fold(Set.empty[EntityKey[A]])(_.selection.entities))
    .toMap
  // The group's selection starts as everything any member already has selected.
  private var shared: Set[EntityKey[A]] = shown.values.flatten.toSet
  private var unhook = Vector.empty[() => Unit]

  private def wire(): Either[IntaglioError, Unit] =
    widgets.zipWithIndex.foldLeft[Either[IntaglioError, Unit]](Right(())) {
      case (done, (widget, i)) =>
        done.flatMap { _ =>
          widget
            .subscribe { record =>
              record.event match
                case InteractionEvent.SelectionChanged(value)
                    if record.stamp.cause != InputCause.Projected =>
                  changed(i, value.entities)
                case _ => ()
            }
            .map { subscription =>
              unhook = unhook :+ (() => subscription.cancel())
              unhook = unhook :+ widget.subscribeHover(emphasis =>
                widgets.patch(i, Nil, 1).foreach(_.setLinkedEmphasis(emphasis))
              )
            }
        }
    }

  /** Member `i`'s reader changed its observation keys to `now`. */
  private def changed(i: Int, now: Set[EntityKey[A]]): Unit =
    val before = shown.getOrElse(i, Set.empty)
    // A reader can only add or remove observations this plot draws as marks; keys it holds only
    // because they were projected into it (a histogram holds them all, drawing none) are not its.
    val drawn = widgets(i).drawnEntities
    val added = (now -- before).intersect(drawn)
    val removed = (before -- now).intersect(drawn)
    shown += i -> now
    if added.nonEmpty || removed.nonEmpty then
      shared = (shared ++ added) -- removed
      widgets.indices.filter(_ != i).foreach { j =>
        val member = widgets(j)
        member.state match
          case Left(error)  => onError(member, error)
          case Right(state) =>
            val projected = SelectionProjection.into(Selection(shared), state.domain)
            // Keep the member's own plot-local targets (bins); replace only its observations.
            val next = projected.selection.copy(targets = state.selection.targets)
            member.setSelection(next) match
              case Left(error) => onError(member, error)
              case Right(_)    =>
                shown += j -> next.entities
                if projected.missing.nonEmpty then onMissing(member, projected.missing)
      }

  def dispose(): Unit =
    unhook.foreach(_())
    unhook = Vector.empty
    widgets.foreach(_.setLinkedEmphasis(LinkedEmphasis.none[A]))

object WidgetLink:
  def connect[A](
      widgets: Vector[SvgWidget[A]],
      onMissing: (SvgWidget[A], Set[EntityKey[A]]) => Unit =
        (_: SvgWidget[A], _: Set[EntityKey[A]]) => (),
      onError: (SvgWidget[A], IntaglioError) => Unit = (_: SvgWidget[A], _: IntaglioError) => ()
  ): Either[IntaglioError, WidgetLink[A]] =
    if widgets.distinct.length != widgets.length then
      Left(InteractionError.InvalidValue("widget link", "a widget appears twice"))
    else
      val link = new WidgetLink(widgets, onMissing, onError)
      link.wire() match
        case Right(_)    => Right(link)
        case Left(error) =>
          link.dispose()
          Left(error)
