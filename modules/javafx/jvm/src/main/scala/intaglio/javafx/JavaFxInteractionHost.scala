package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.application.Platform
import _root_.javafx.beans.value.ChangeListener
import _root_.javafx.event.EventHandler
import _root_.javafx.scene.AccessibleRole
import _root_.javafx.scene.canvas.{Canvas, GraphicsContext}
import _root_.javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent}
import _root_.javafx.scene.layout.Pane
import _root_.javafx.scene.paint.Color

/** Compiled once, independent of the FX toolkit; attach and draw on the FX application thread. */
final class JavaFxInteractionView[A] private[javafx] (
    val deviceScene: DeviceScene,
    val picking: PickingPlan[A],
    val navigation: NavigationPlan[A],
    val domain: InteractionDomain[A],
    val program: JavaFxProgram,
    val context: RenderContext
)

object JavaFxInteractionView:
  def compile[A](
      plan: InteractionPlan[A],
      context: RenderContext
  ): Either[IntaglioError, JavaFxInteractionView[A]] =
    for
      device <- DeviceScene.fromScene(plan.scene, context)
      _ <- PatternTile.validate(device)
      picking <- Picking.fromResolved(device, plan.groups, context)
      domain <- InteractionDomain(Vector(plan), plan.revision)
    yield new JavaFxInteractionView(
      device,
      picking,
      picking.prepareNavigation(),
      domain,
      JavaFxProgram.fromDevice(device, context),
      context
    )

enum JavaFxHostError extends IntaglioError:
  case WrongThread, Disposed
  case InvalidTolerance
  def message: String = this match
    case WrongThread      => "JavaFX interaction requires the FX application thread"
    case Disposed         => "JavaFX interaction host has been disposed"
    case InvalidTolerance => "Pointer tolerance must be finite and nonnegative"

final case class JavaFxHostProfile(baseDraws: Long, overlayDraws: Long)

/** Owns one focus stop, two canvases and their listeners. Input redraws only the overlay. The
  * shared PickViewport maps device pixels to JavaFX logical coordinates for both canvases and
  * pointer queries. JavaFX applies window output scaling; the host never applies it twice.
  */
final class JavaFxInteractionHost[A] private (
    initialView: JavaFxInteractionView[A],
    initialState: InteractionState[A],
    tolerance: Double,
    describe: TargetInfo[A] => String,
    reportError: IntaglioError => Unit
):
  val node: Pane = new Pane()
  private val base = new Canvas()
  private val overlay = new Canvas()
  private val baseContext = new JavaFxCanvasContext(base.getGraphicsContext2D)
  private val controller = new InteractionController(initialState)
  private var view = Option(initialView)
  private var viewport = Option.empty[PickViewport]
  private var sequence = 0L
  private val origin = SemanticId.unsafe("javafx-host")
  private var baseDraws = 0L
  private var overlayDraws = 0L
  private var failure = Option.empty[IntaglioError]
  private var geometries = initialView.navigation.targets.map(g => g.target.id -> g).toMap
  private var entities = initialView.navigation.targets
    .flatMap(g => g.target.entity.map(_ -> g))
    .groupMap(_._1)(_._2)

  node.setAccessibleRole(AccessibleRole.PARENT)
  node.setAccessibleRoleDescription("interactive plot")
  node.setAccessibleText(
    "Plot. Arrows move spatially; Page Up/Down traverse all marks; Enter selects; Escape clears."
  )
  node.setFocusTraversable(true)
  base.setManaged(false)
  overlay.setManaged(false)
  overlay.setMouseTransparent(true)
  node.getChildren.addAll(base, overlay)
  node.setPrefSize(initialView.context.logicalWidth, initialView.context.logicalHeight)
  node.resize(initialView.context.logicalWidth, initialView.context.logicalHeight)

  private val sizeListener: ChangeListener[Number] = (_, _, _) => resize()
  private val focusListener: ChangeListener[java.lang.Boolean] = (_, _, focused) =>
    if !focused.booleanValue then cancelGesture()
    redrawOverlay()
  private val mouseHandler: EventHandler[MouseEvent] = event => handleMouse(event)
  private val keyHandler: EventHandler[KeyEvent] = event => handleKey(event)
  node.widthProperty.addListener(sizeListener)
  node.heightProperty.addListener(sizeListener)
  node.focusedProperty.addListener(focusListener)
  node.addEventHandler(MouseEvent.ANY, mouseHandler)
  node.addEventHandler(KeyEvent.KEY_PRESSED, keyHandler)
  resize()

  def profile: JavaFxHostProfile = JavaFxHostProfile(baseDraws, overlayDraws)
  def lastError: Option[IntaglioError] = failure
  def isDisposed: Boolean = view.isEmpty

  def state: Either[IntaglioError, InteractionState[A]] = checked.flatMap(_ => controller.state)

  def subscribe(listener: EventRecord[A] => Unit): Either[IntaglioError, InteractionSubscription] =
    checked.flatMap(_ => controller.subscribe(listener))

  /** Projected inputs change the overlay, but intentionally deliver no application event. */
  def setSelection(selected: Selection[A]): Either[IntaglioError, Unit] =
    dispatch(InteractionAction.Select(selected, SelectionOperation.Replace), InputCause.Projected)

  def setHover(target: Option[VisualTargetId]): Either[IntaglioError, Unit] =
    dispatch(InteractionAction.Hover(target), InputCause.Projected)

  /** Local logical coordinates, including letterbox rejection, through the exact draw mapping. */
  def toDevice(x: Double, y: Double): Either[IntaglioError, Option[DevicePoint]] =
    checked.flatMap(_ =>
      viewport.fold[Either[IntaglioError, Option[DevicePoint]]](Right(None))(
        _.toDevice(x, y)
      )
    )

  /** Pick a raw data position using the compiler's resolved panel mapping. */
  def pickNative(
      panel: GraphicsName,
      point: DevicePoint,
      toleranceDevicePx: Double = 0
  ): Either[IntaglioError, Option[PickHit[A]]] =
    for
      _ <- checked
      frame <- view.get.deviceScene.frame(panel)
      device <- frame.nativeToDevice(point)
      hit <- view.get.picking.nearest(device, toleranceDevicePx)
    yield hit

  /** Idempotent; removes listeners/subscriptions and releases raster and pattern caches. */
  def dispose(): Either[IntaglioError, Unit] =
    if !Platform.isFxApplicationThread then Left(JavaFxHostError.WrongThread)
    else
      if view.nonEmpty then
        node.removeEventHandler(MouseEvent.ANY, mouseHandler)
        node.removeEventHandler(KeyEvent.KEY_PRESSED, keyHandler)
        node.widthProperty.removeListener(sizeListener)
        node.heightProperty.removeListener(sizeListener)
        node.focusedProperty.removeListener(focusListener)
        controller.dispose()
        baseContext.clearCaches()
        node.getChildren.clear()
        node.setFocusTraversable(false)
        node.setAccessibleText(null)
        base.setWidth(0)
        base.setHeight(0)
        overlay.setWidth(0)
        overlay.setHeight(0)
        geometries = Map.empty
        entities = Map.empty
        view = None
        viewport = None
      Right(())

  private def checked: Either[IntaglioError, Unit] =
    if !Platform.isFxApplicationThread then Left(JavaFxHostError.WrongThread)
    else if view.isEmpty then Left(JavaFxHostError.Disposed)
    else Right(())

  private def dispatch(
      action: InteractionAction[A],
      cause: InputCause
  ): Either[IntaglioError, Unit] =
    checked.flatMap { _ =>
      controller.state.flatMap { current =>
        val stamp = InputStamp(current.domain.revision, origin, sequence, cause)
        sequence += 1
        controller.dispatch(stamp, action).map { _ =>
          redrawOverlay()
          ()
        }
      }
    }

  private def accept(result: Either[IntaglioError, Unit]): Unit =
    result.left.foreach { error =>
      failure = Some(error)
      reportError(error)
    }

  private def resize(): Unit =
    view.foreach { current =>
      val width = node.getWidth
      val height = node.getHeight
      if width > 0 && height > 0 then
        viewport = PickViewport
          .fit(current.deviceScene.width, current.deviceScene.height, 0, 0, width, height)
          .toOption
        base.setWidth(width)
        base.setHeight(height)
        overlay.setWidth(width)
        overlay.setHeight(height)
        viewport.foreach { mapping =>
          val gc = base.getGraphicsContext2D
          gc.clearRect(0, 0, width, height)
          transformed(gc, mapping) {
            JavaFxRenderer.draw(current.program, baseContext)
          }
          baseDraws += 1
        }
        redrawOverlay()
      else viewport = None
    }

  private def transformed(gc: GraphicsContext, mapping: PickViewport)(draw: => Unit): Unit =
    gc.save()
    try
      gc.translate(mapping.clientLeft, mapping.clientTop)
      gc.scale(mapping.cssPixelsPerDevicePixel, mapping.cssPixelsPerDevicePixel)
      draw
    finally gc.restore()

  private def redrawOverlay(): Unit =
    if view.nonEmpty then
      val gc = overlay.getGraphicsContext2D
      gc.clearRect(0, 0, overlay.getWidth, overlay.getHeight)
      for
        mapping <- viewport
        current <- controller.state.toOption
      do
        transformed(gc, mapping) {
          val selected = current.selection.targets.flatMap(geometries.get) ++
            current.selection.entities.flatMap(key => entities.getOrElse(key, Vector.empty))
          val selectedIds = selected.map(_.target.id)
          val active = selectedIds ++ current.hover ++ current.focus
          val styles = AppearanceStyles(
            Color.TRANSPARENT,
            Color.BLACK,
            selection = Some(Color.web("#0072B2")),
            hover = Some(Color.web("#D55E00"))
          )
          active.flatMap(geometries.get).foreach { g =>
            val appearance = InteractionAppearance.resolve(
              styles,
              selectedIds.contains(g.target.id),
              current.hover.contains(g.target.id),
              current.focus.contains(g.target.id)
            )
            if appearance.style != Color.TRANSPARENT then
              outline(gc, mapping, g, appearance.style, 2, 3)
          }
          // Focus is the final overlay pass, even when other targets overlap it.
          current.focus.flatMap(geometries.get).foreach { g =>
            outline(gc, mapping, g, Color.WHITE, 5, 5)
            outline(gc, mapping, g, Color.BLACK, 2, 5)
          }
        }
        current.focus
          .flatMap(geometries.get)
          .foreach(g => node.setAccessibleText(describe(g.target)))
      overlayDraws += 1

  private def outline(
      gc: GraphicsContext,
      mapping: PickViewport,
      geometry: TargetGeometry[A],
      color: Color,
      width: Double,
      padding: Double
  ): Unit =
    val scale = mapping.cssPixelsPerDevicePixel
    val inset = padding / scale
    gc.setGlobalAlpha(1)
    gc.setStroke(color)
    gc.setLineWidth(width / scale)
    gc.setLineDashes()
    gc.strokeRect(
      geometry.left - inset,
      geometry.top - inset,
      geometry.right - geometry.left + 2 * inset,
      geometry.bottom - geometry.top + 2 * inset
    )

  private def underPointer(event: MouseEvent): Either[IntaglioError, Option[TargetInfo[A]]] =
    for
      point <- toDevice(event.getX, event.getY)
      hit <- (point, viewport, view) match
        case (Some(p), Some(mapping), Some(current)) =>
          mapping.tolerance(tolerance).flatMap(current.picking.nearest(p, _)).map(_.map(_.target))
        case _ => Right(None)
    yield hit

  private def handleMouse(event: MouseEvent): Unit =
    val kind = event.getEventType
    if kind == MouseEvent.MOUSE_MOVED || kind == MouseEvent.MOUSE_DRAGGED then
      accept(
        underPointer(event).flatMap(hit =>
          dispatch(InteractionAction.Hover(hit.map(_.id)), InputCause.Pointer)
        )
      )
    else if kind == MouseEvent.MOUSE_EXITED then
      accept(dispatch(InteractionAction.Hover(None), InputCause.Pointer))
    else if kind == MouseEvent.MOUSE_PRESSED && event.getButton == MouseButton.PRIMARY then
      node.requestFocus()
      accept(dispatch(InteractionAction.BeginGesture(0), InputCause.Pointer))
    else if kind == MouseEvent.MOUSE_RELEASED && event.getButton == MouseButton.PRIMARY then
      if controller.state.toOption.exists(_.gesture.nonEmpty) then
        accept(dispatch(InteractionAction.EndGesture(false), InputCause.Pointer))
    else if kind == MouseEvent.MOUSE_CLICKED && event.getButton == MouseButton.PRIMARY then
      accept(underPointer(event).flatMap {
        case Some(target) =>
          dispatch(InteractionAction.Focus(Some(target.id)), InputCause.Pointer).flatMap(_ =>
            selectTarget(
              target,
              InputCause.Pointer,
              event.isShiftDown || event.isControlDown || event.isMetaDown
            )
          )
        case None => Right(())
      })

  private def cancelGesture(): Unit =
    if controller.state.toOption.exists(_.gesture.nonEmpty) then
      accept(dispatch(InteractionAction.EndGesture(true), InputCause.Keyboard))

  private def handleKey(event: KeyEvent): Unit =
    val direction = event.getCode match
      case KeyCode.LEFT  => Some(NavigationDirection.Left)
      case KeyCode.RIGHT => Some(NavigationDirection.Right)
      case KeyCode.UP    => Some(NavigationDirection.Up)
      case KeyCode.DOWN  => Some(NavigationDirection.Down)
      case _             => None
    direction match
      case Some(value) =>
        accept(for
          current <- state
          next <- view.get.navigation.targets.headOption match
            case None        => Right(None)
            case Some(first) =>
              current.focus match
                case None     => Right(Some(first))
                case Some(id) => view.get.navigation.nearest(id, value)
          _ <- next.fold[Either[IntaglioError, Unit]](Right(()))(g =>
            dispatch(InteractionAction.Focus(Some(g.target.id)), InputCause.Keyboard)
          )
        yield ())
        event.consume()
      case None
          if Set(KeyCode.HOME, KeyCode.END, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN).contains(
            event.getCode
          ) =>
        accept(state.flatMap { current =>
          val ordered = view.get.navigation.targets
          val index = current.focus.fold(-1)(id => ordered.indexWhere(_.target.id == id))
          val next = event.getCode match
            case KeyCode.HOME    => 0
            case KeyCode.END     => ordered.size - 1
            case KeyCode.PAGE_UP => math.max(0, index - 1)
            case _               => math.min(ordered.size - 1, index + 1)
          ordered
            .lift(next)
            .fold[Either[IntaglioError, Unit]](Right(()))(g =>
              dispatch(InteractionAction.Focus(Some(g.target.id)), InputCause.Keyboard)
            )
        })
        event.consume()
      case None if event.getCode == KeyCode.ENTER || event.getCode == KeyCode.SPACE =>
        accept(state.flatMap { current =>
          current.focus
            .flatMap(geometries.get)
            .fold[Either[IntaglioError, Unit]](Right(()))(g =>
              selectTarget(
                g.target,
                InputCause.Keyboard,
                event.isShiftDown || event.isControlDown || event.isMetaDown
              )
            )
        })
        event.consume()
      case None if event.getCode == KeyCode.ESCAPE =>
        cancelGesture()
        accept(
          dispatch(
            InteractionAction.Select(Selection[A](), SelectionOperation.Clear),
            InputCause.Keyboard
          )
        )
        event.consume()
      case _ => ()

  private def selectTarget(
      target: TargetInfo[A],
      cause: InputCause,
      toggle: Boolean
  ): Either[IntaglioError, Unit] =
    state.flatMap { current =>
      val selected =
        target.entity.fold(Selection[A](targets = Set(target.id)))(key => Selection(Set(key)))
      val operation = if toggle && current.selectionMode == SelectionMode.Multiple then
        SelectionOperation.Toggle
      else SelectionOperation.Replace
      val change = if current.selectionMode == SelectionMode.Disabled then Right(())
      else dispatch(InteractionAction.Select(selected, operation), cause)
      change.flatMap(_ => dispatch(InteractionAction.Activate(target.id), cause))
    }

object JavaFxInteractionHost:
  def attach[A](
      view: JavaFxInteractionView[A],
      mode: SelectionMode = SelectionMode.Multiple,
      selection: Selection[A] = Selection[A](),
      toleranceLogicalPx: Double = 4,
      describe: TargetInfo[A] => String = (target: TargetInfo[A]) =>
        target.entity
          .map(key => s"${key.space.namespace.value}: ${key.value}")
          .getOrElse(s"${target.id.plan.value} ${target.id.scope.value} ${target.id.ordinal}"),
      onError: IntaglioError => Unit = _ => ()
  ): Either[IntaglioError, JavaFxInteractionHost[A]] =
    if !Platform.isFxApplicationThread then Left(JavaFxHostError.WrongThread)
    else if !toleranceLogicalPx.isFinite || toleranceLogicalPx < 0 then
      Left(JavaFxHostError.InvalidTolerance)
    else
      InteractionState
        .initial(view.domain, mode, selection)
        .map(initial =>
          new JavaFxInteractionHost(view, initial, toleranceLogicalPx, describe, onError)
        )
