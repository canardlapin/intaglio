package intaglio.browser

import intaglio.*
import intaglio.interaction.*

/** A group of linked widgets over one key space, named when the group is connected.
  *
  * The link owns one selection of observation keys for the whole group. When a reader changes the
  * observations selected in one member, only that change (keys added and removed) updates the
  * group's selection, and every other member is shown the group's selection projected into the keys
  * it contains. A key one member lacks therefore survives a reader's additive change in another,
  * and keys a member lacks are reported through `onMissing`, never invented. A change that adds or
  * removes no observation keys the plot can select (selecting, toggling or clearing histogram bins
  * as bins) stays in its plot; in `AggregateSelection.Members` mode a bin's observations are the
  * reader's, so choosing or clearing there changes the group. What the reader points at is shown in
  * the other members as linked emphasis.
  *
  * Projection arrives as `Projected` input, which emits no event, and emphasis is display only, so
  * a link can never echo or loop. A projection a member refuses (for instance several keys into a
  * single-selection plot) is reported through `onError`. A widget belongs to at most one live link.
  * [[dispose]] unhooks the group, clears the linked emphasis it set, and leaves each widget's
  * selection as it is.
  */
final class WidgetLink[A] private (
    space: KeySpace[A],
    widgets: Vector[SvgWidget[A]],
    onMissing: (SvgWidget[A], Set[EntityKey[A]]) => Unit,
    onError: (SvgWidget[A], IntaglioError) => Unit
):
  private def inSpace(keys: Set[EntityKey[A]]): Set[EntityKey[A]] = keys.filter(_.space eq space)

  private var shown: Map[Int, Set[EntityKey[A]]] = widgets.indices
    .map(i =>
      i -> inSpace(widgets(i).state.toOption.fold(Set.empty[EntityKey[A]])(_.selection.entities))
    )
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
    // A reader can only add or remove observations this plot can select (SvgWidget.
    // selectableEntities): its marks, plus the members of aggregates chosen in Members mode. Keys it
    // holds only because they were projected into it (a histogram whose bins are bins holds them
    // all, drawing none) are not its.
    // Only keys of the link's space count; a member keyed by another space never feeds the group.
    val drawn = inSpace(widgets(i).selectableEntities)
    val mine = inSpace(now)
    val added = (mine -- before).intersect(drawn)
    val removed = (before -- mine).intersect(drawn)
    shown += i -> mine
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
                shown += j -> inSpace(next.entities)
                if projected.missing.nonEmpty then onMissing(member, projected.missing)
      }

  def dispose(): Unit =
    unhook.foreach(_())
    unhook = Vector.empty
    widgets.foreach { widget =>
      widget.setLinkedEmphasis(LinkedEmphasis.none[A])
      if widget.link.exists(_ eq this) then widget.link = None
    }

object WidgetLink:
  /** Link `widgets` over observation keys of `space`. A member keyed by another space (even one
    * with the same namespace text) never feeds the group's selection, and every key projected into
    * it is reported missing.
    */
  def connect[A](
      space: KeySpace[A],
      widgets: Vector[SvgWidget[A]],
      onMissing: (SvgWidget[A], Set[EntityKey[A]]) => Unit =
        (_: SvgWidget[A], _: Set[EntityKey[A]]) => (),
      onError: (SvgWidget[A], IntaglioError) => Unit = (_: SvgWidget[A], _: IntaglioError) => ()
  ): Either[IntaglioError, WidgetLink[A]] =
    if widgets.distinct.length != widgets.length then
      Left(InteractionError.InvalidValue("widget link", "a widget appears twice"))
    else if widgets.exists(_.link.nonEmpty) then
      // Projection is silent, so a widget relaying between two links would leave them disagreeing.
      Left(InteractionError.InvalidValue("widget link", "a widget already belongs to a live link"))
    else
      val link = new WidgetLink(space, widgets, onMissing, onError)
      widgets.foreach(_.link = Some(link))
      link.wire() match
        case Right(_)    => Right(link)
        case Left(error) =>
          link.dispose()
          Left(error)
