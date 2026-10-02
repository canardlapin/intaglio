package intaglio.interaction

import intaglio.*

/** Pointer input after a host has removed its toolkit: coordinates are client (CSS) pixels in the
  * same space as the [[PickViewport]] the host fitted. `additive` is Shift, Ctrl or Meta.
  */
enum PointerInput:
  case Move(clientX: Double, clientY: Double)
  case Leave
  case Press
  case Release

  /** The toolkit took the pointer away (cancelled touch, lost capture): end any gesture. */
  case Cancel
  case Click(clientX: Double, clientY: Double, additive: Boolean)

/** Keyboard input by meaning rather than key code, so every host maps its own keys once. */
enum KeyInput:
  case Arrow(direction: NavigationDirection)
  case First
  case Last
  case Previous
  case Next

  /** Enter or Space: select (toggle when `additive` and selection is multiple) and activate. */
  case Choose(additive: Boolean)

  /** Clear the selection and cancel any gesture. */
  case Escape

/** One action with the cause a host stamps it with. */
final case class HostAction[A](action: InteractionAction[A], cause: InputCause)

/** The host-neutral input contract shared by the browser and JavaFX hosts: normalized pointer and
  * key input become interaction actions, read against the current state, the picking plan drawn by
  * the host and its navigation order. It performs no effects and dispatches nothing itself.
  *
  * The hover rule decides the pointer's target: `Direct` takes the target under the pointer within
  * `directToleranceCssPx`; `Nearest(max)` takes the nearest target within `max` CSS pixels.
  */
final class HostInput[A](
    picking: PickingPlan[A],
    val navigation: NavigationPlan[A],
    viewport: PickViewport,
    behavior: InteractionBehavior[A],
    directToleranceCssPx: Double = 2.0
):
  private val geometries: Map[VisualTargetId, TargetGeometry[A]] =
    navigation.targets.iterator.map(g => g.target.id -> g).toMap

  /** The geometry of a target, for anchoring tooltips and overlays. */
  def geometry(id: VisualTargetId): Option[TargetGeometry[A]] = geometries.get(id)

  /** The target the pointer at a client point reveals under the behaviour's hover rule. */
  def targetAt(clientX: Double, clientY: Double): Either[IntaglioError, Option[TargetInfo[A]]] =
    viewport.toDevice(clientX, clientY).flatMap {
      case None        => Right(None)
      case Some(point) =>
        val reach = behavior.hover match
          case HoverRule.Direct       => directToleranceCssPx
          case HoverRule.Nearest(max) => max
        viewport.tolerance(reach).flatMap(picking.nearest(point, _)).map(_.map(_.target))
    }

  def pointer(
      state: InteractionState[A],
      input: PointerInput
  ): Either[IntaglioError, Vector[HostAction[A]]] =
    def act(action: InteractionAction[A]) = HostAction(action, InputCause.Pointer)
    input match
      case PointerInput.Move(x, y) =>
        targetAt(x, y).map { hit =>
          val id = hit.map(_.id)
          if state.hover == id then Vector.empty else Vector(act(InteractionAction.Hover(id)))
        }
      case PointerInput.Leave =>
        Right(
          if state.hover.isEmpty then Vector.empty else Vector(act(InteractionAction.Hover(None)))
        )
      case PointerInput.Press   => Right(Vector(act(InteractionAction.BeginGesture(0))))
      case PointerInput.Release =>
        Right(
          if state.gesture.isEmpty then Vector.empty
          else Vector(act(InteractionAction.EndGesture(false)))
        )
      case PointerInput.Cancel =>
        Right(
          if state.gesture.isEmpty then Vector.empty
          else Vector(act(InteractionAction.EndGesture(true)))
        )
      case PointerInput.Click(x, y, additive) =>
        targetAt(x, y).map {
          case None         => Vector.empty
          case Some(target) =>
            act(InteractionAction.Focus(Some(target.id))) +: choose(state, target, additive)
              .map(a => act(a))
        }

  def key(
      state: InteractionState[A],
      input: KeyInput
  ): Either[IntaglioError, Vector[HostAction[A]]] =
    def act(action: InteractionAction[A]) = HostAction(action, InputCause.Keyboard)
    def focus(next: Option[TargetGeometry[A]]) =
      next.toVector.map(g => act(InteractionAction.Focus(Some(g.target.id))))
    val ordered = navigation.targets
    val index = state.focus.fold(-1)(id => ordered.indexWhere(_.target.id == id))
    input match
      case KeyInput.Arrow(direction) =>
        state.focus match
          case None     => Right(focus(ordered.headOption))
          case Some(id) => navigation.nearest(id, direction).map(focus)
      case KeyInput.First    => Right(focus(ordered.headOption))
      case KeyInput.Last     => Right(focus(ordered.lastOption))
      case KeyInput.Previous => Right(focus(ordered.lift(math.max(0, index - 1))))
      case KeyInput.Next     => Right(focus(ordered.lift(math.min(ordered.size - 1, index + 1))))
      case KeyInput.Choose(additive) =>
        Right(
          state.focus.flatMap(geometries.get).toVector.flatMap { g =>
            choose(state, g.target, additive).map(act)
          }
        )
      case KeyInput.Escape =>
        Right(
          (if state.gesture.nonEmpty then Vector(act(InteractionAction.EndGesture(true)))
           else Vector.empty) :+
            act(InteractionAction.Select(Selection[A](), SelectionOperation.Clear))
        )

  /** Select (unless disabled) and activate, as a click or Enter does. */
  private def choose(
      state: InteractionState[A],
      target: TargetInfo[A],
      additive: Boolean
  ): Vector[InteractionAction[A]] =
    val selected =
      target.entity.fold(Selection[A](targets = Set(target.id)))(key => Selection(Set(key)))
    val operation =
      if additive && state.selectionMode == SelectionMode.Multiple then SelectionOperation.Toggle
      else SelectionOperation.Replace
    val select =
      if state.selectionMode == SelectionMode.Disabled then Vector.empty
      else Vector(InteractionAction.Select(selected, operation))
    select :+ InteractionAction.Activate(target.id)

/** Where a tooltip box goes, in CSS pixels relative to the widget's top-left corner. */
final case class TooltipBox(left: Double, top: Double)

object TooltipLayout:
  /** Place a `width` x `height` tooltip for `placement`, given the pointer (when the pointer
    * revealed the target) and the target's anchor, inside a `containerWidth` x `containerHeight`
    * widget.
    *
    * The box sits below-right of its reference point; it flips to the other side of an axis that
    * would overflow, and is finally clamped inside the widget so it never leaves it.
    */
  def place(
      placement: TooltipPlacement,
      pointer: Option[(Double, Double)],
      anchor: (Double, Double),
      width: Double,
      height: Double,
      containerWidth: Double,
      containerHeight: Double
  ): TooltipBox =
    def side(ref: Double, offset: Double, size: Double, limit: Double): Double =
      val after = ref + offset
      if after + size <= limit then after
      else
        val before = ref - offset - size
        if before >= 0 then before else after
    def beside(ref: (Double, Double), offset: Double): TooltipBox =
      TooltipBox(
        clamp(side(ref._1, offset, width, containerWidth), width, containerWidth),
        clamp(side(ref._2, offset, height, containerHeight), height, containerHeight)
      )
    placement match
      case TooltipPlacement.Pointer(offset)  => beside(pointer.getOrElse(anchor), offset)
      case TooltipPlacement.Anchored(offset) => beside(anchor, offset)
      case TooltipPlacement.Fixed(x, y)      =>
        TooltipBox(clamp(x, width, containerWidth), clamp(y, height, containerHeight))

  private def clamp(value: Double, size: Double, limit: Double): Double =
    math.max(0.0, math.min(value, limit - size))
