package intaglio.browser

import intaglio.*
import intaglio.interaction.*

/** A group of linked widgets over one key space. A reader's selection in any member is projected
  * into every other member by entity key (keys a member does not contain are reported through
  * `onMissing`, never invented), and what the reader points at is shown as linked emphasis. A
  * selection of plot-local targets only, such as histogram bins selected as bins, stays local.
  *
  * Projection arrives as `Projected` input, which emits no event, and emphasis is display only, so
  * a link can never echo or loop, however many members it has. [[dispose]] unhooks the group and
  * leaves each widget with its current state.
  */
final class WidgetLink[A] private (private var unhook: Vector[() => Unit]):
  def dispose(): Unit =
    unhook.foreach(_())
    unhook = Vector.empty

object WidgetLink:
  def connect[A](
      widgets: Vector[SvgWidget[A]],
      onMissing: (SvgWidget[A], Set[EntityKey[A]]) => Unit =
        (_: SvgWidget[A], _: Set[EntityKey[A]]) => ()
  ): Either[IntaglioError, WidgetLink[A]] =
    if widgets.distinct.length != widgets.length then
      Left(InteractionError.InvalidValue("widget link", "a widget appears twice"))
    else
      val hooks = Vector.newBuilder[() => Unit]
      var failure: Option[IntaglioError] = None
      widgets.zipWithIndex.foreach { (widget, i) =>
        val others = widgets.patch(i, Nil, 1)
        widget.subscribe { record =>
          record.event match
            // A selection of plot-local targets only (a histogram bin) stays in its plot: it holds
            // no observation keys, and projecting it would wrongly clear the linked selections.
            case InteractionEvent.SelectionChanged(value)
                if record.stamp.cause != InputCause.Projected &&
                  !(value.entities.isEmpty && value.targets.nonEmpty) =>
              others.foreach { other =>
                other.state.foreach { state =>
                  val projected = SelectionProjection.into(value, state.domain)
                  other.setSelection(projected.selection)
                  if projected.missing.nonEmpty then onMissing(other, projected.missing)
                }
              }
            case _ => ()
        } match
          case Right(subscription) => hooks += (() => subscription.cancel())
          case Left(error)         => failure = failure.orElse(Some(error))
        hooks += widget.subscribeHover(emphasis => others.foreach(_.setLinkedEmphasis(emphasis)))
      }
      failure match
        case Some(error) =>
          hooks.result().foreach(_())
          Left(error)
        case None => Right(new WidgetLink(hooks.result()))
