package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.animation.PauseTransition
import _root_.javafx.application.Platform
import _root_.javafx.beans.value.ChangeListener
import _root_.javafx.event.EventHandler
import _root_.javafx.geometry.Insets
import _root_.javafx.scene.AccessibleRole
import _root_.javafx.scene.canvas.{Canvas, GraphicsContext}
import _root_.javafx.scene.input.{
  KeyCode,
  KeyEvent,
  MouseButton,
  MouseEvent,
  ScrollEvent,
  ZoomEvent
}
import _root_.javafx.scene.layout.{Background, BackgroundFill, CornerRadii, Pane, VBox}
import _root_.javafx.scene.paint.Color
import _root_.javafx.scene.text.{Font, FontWeight, Text, TextFlow}
import _root_.javafx.util.Duration
import scala.util.control.NonFatal

/** Compiled once, independent of the FX toolkit; attach and draw on the FX application thread.
  *
  * `plans` are the compiled interaction plans the view draws (empty for a hand-built named scene),
  * `parts` picks the typed plot parts (legend keys, strips, axes, titles, annotations) when the
  * view was compiled from a plot, and `panelFrame` is the single panel's resolved frame, from which
  * data-window navigation starts.
  */
final class JavaFxInteractionView[A] private[javafx] (
    val deviceScene: DeviceScene,
    val picking: PickingPlan[A],
    val navigation: NavigationPlan[A],
    val domain: InteractionDomain[A],
    val program: JavaFxProgram,
    val context: RenderContext,
    val plans: Vector[InteractionPlan[A]],
    val parts: Option[PartPicking],
    val policy: PickPolicy,
    val panelFrame: Option[DeviceFrame]
):
  /** A single plot can navigate its data window; a composition has several independent plans. */
  def singlePlan: Option[InteractionPlan[A]] = Option.when(plans.size == 1)(plans.head)

  /** The text a target is described by: its tooltip as plain text, else its reading position. */
  def describe(target: TargetInfo[A], behavior: InteractionBehavior[A]): String =
    behavior
      .tooltip(target)
      .map(_.plainText)
      .getOrElse {
        val index = navigation.targets.indexWhere(_.target.id == target.id)
        s"mark ${index + 1} of ${navigation.targets.size}"
      }

  /** One row per target in reading order, then one per plot part: the text companion, as the
    * browser widget's table shows it.
    */
  def companionRows(behavior: InteractionBehavior[A]): Vector[(String, String)] =
    navigation.targets.map(g => ("mark", describe(g.target, behavior))) ++
      parts.toVector.flatMap(_.parts).map(part => ("part", part.part.describe))

object JavaFxInteractionView:
  /** Host a compiled plot under the default [[intaglio.interaction.PickPolicy]]. */
  def compile[A](
      plan: InteractionPlan[A],
      context: RenderContext
  ): Either[IntaglioError, JavaFxInteractionView[A]] =
    compile(plan, context, PickPolicy.default)

  /** Host a compiled plot whose pointer, hover and area queries pick under `policy`, for example
    * `PickPolicy.default.withHollowPoints(HollowPicking.Outline)` to hit hollow points only on
    * their outline.
    */
  def compile[A](
      plan: InteractionPlan[A],
      context: RenderContext,
      policy: PickPolicy
  ): Either[IntaglioError, JavaFxInteractionView[A]] =
    for
      device <- DeviceScene.fromScene(plan.scene, context)
      _ <- PatternTile.validate(device)
      picking <- Picking.fromResolved(device, plan.groups, context, policy)
      parts <- PartPicking.fromResolved(plan.trained, device, context)
      domain <- InteractionDomain(Vector(plan), plan.revision)
    yield new JavaFxInteractionView(
      device,
      picking,
      picking.prepareNavigation(),
      domain,
      JavaFxProgram.fromDevice(device, context),
      context,
      Vector(plan),
      Some(parts),
      policy,
      device.frame(PlotRegion.Panel).toOption.map(_.frame)
    )

  /** Host a composed figure without discarding any child identity or source revision. Each child's
    * parts are scoped to its cell (see `ComposedParts`). Figure-wide pan and zoom are refused:
    * children have independent trained scales.
    */
  def compileComposition[A](
      composed: ComposedInteraction[A],
      revision: PlanRevision,
      policy: PickPolicy = PickPolicy.default
  ): Either[IntaglioError, JavaFxInteractionView[A]] =
    val context = composed.composition.context
    val scoped = ComposedParts.of(composed)
    for
      device <- DeviceScene.fromScene(scoped.scene, context)
      _ <- PatternTile.validate(device)
      picking <- Picking.fromResolved(device, composed.groups, context, policy)
      parts <- PartPicking.fromParts(scoped.parts, device, context)
      domain <- InteractionDomain(composed.plans, revision)
    yield new JavaFxInteractionView(
      device,
      picking,
      picking.prepareNavigation(),
      domain,
      JavaFxProgram.fromDevice(device, context),
      context,
      composed.plans,
      Some(parts),
      policy,
      None
    )

  /** Host a scene drawn directly from grobs, whose interactive marks carry a `GraphicsName`. The
    * scene is resolved once for drawing and picking. Targets, hits and keyboard navigation are
    * those of `NamedInteraction(NamedPicking.fromResolved(...), keys, planId, revision)`: a pointer
    * or key reaches the same name `NamedPicking` reports, as the target's entity value.
    */
  def named(
      scene: Scene,
      context: RenderContext,
      keys: KeySpace[GraphicsName],
      planId: SemanticId,
      revision: PlanRevision,
      policy: PickPolicy = PickPolicy.default
  ): Either[IntaglioError, JavaFxInteractionView[GraphicsName]] =
    DeviceScene
      .fromScene(scene, context)
      .flatMap(namedResolved(_, context, keys, planId, revision, policy))

  /** As [[named]], for a scene the host has already resolved under `context`. */
  def namedResolved(
      scene: DeviceScene,
      context: RenderContext,
      keys: KeySpace[GraphicsName],
      planId: SemanticId,
      revision: PlanRevision,
      policy: PickPolicy = PickPolicy.default
  ): Either[IntaglioError, JavaFxInteractionView[GraphicsName]] =
    for
      program <- JavaFxProgram.fromResolved(scene, context)
      names <- NamedPicking.fromResolved(scene, context, policy)
      bound <- NamedInteraction(names, keys, planId, revision)
    yield new JavaFxInteractionView(
      scene,
      bound.picking,
      bound.prepareNavigation(),
      bound.domain,
      program,
      context,
      Vector.empty,
      None,
      policy,
      None
    )

enum JavaFxHostError extends IntaglioError:
  case WrongThread, Disposed
  case InvalidTolerance
  def message: String = this match
    case WrongThread      => "JavaFX interaction requires the FX application thread"
    case Disposed         => "JavaFX interaction host has been disposed"
    case InvalidTolerance => "Pointer tolerance must be finite and nonnegative"

final case class JavaFxHostProfile(baseDraws: Long, overlayDraws: Long)

/** What the reader points at or activates among a plot's typed parts. */
enum JavaFxPartEvent:
  case Hovered(part: Option[PlotPart])
  case Activated(part: PlotPart, cause: InputCause)

/** Host options that are not part of the shared behaviour. `toleranceLogicalPx` is the reach of
  * direct hover and clicks in JavaFX logical pixels (the browser widget's is 2 CSS pixels). Tooltip
  * colours are the box's background and text.
  */
final case class JavaFxHostOptions(
    toleranceLogicalPx: Double = 2.0,
    tooltipBackground: Rgba = Rgba.unsafe(0x1f, 0x29, 0x37),
    tooltipText: Rgba = Rgba.unsafe(0xf9, 0xfa, 0xfb),
    tooltipMaxWidthLogicalPx: Double = 288.0
)

/** Owns one focus stop, two canvases, a tooltip and their listeners. Input is normalized into the
  * shared [[intaglio.interaction.HostInput]] contract, so pointer and key input mean what they mean
  * in the browser widget; the shared [[intaglio.interaction.InteractionController]] owns the state.
  * Input redraws only the overlay. The shared PickViewport maps device pixels to JavaFX logical
  * coordinates for both canvases and pointer queries. JavaFX applies window output scaling; the
  * host never applies it twice.
  */
final class JavaFxInteractionHost[A] private (
    initialView: JavaFxInteractionView[A],
    initialState: InteractionState[A],
    val behavior: InteractionBehavior[A],
    options: JavaFxHostOptions,
    describeTarget: Option[TargetInfo[A] => String],
    resolver: Option[MembershipResolver[A]],
    onLink: Option[TargetLink => Unit],
    reportError: IntaglioError => Unit
):
  val node: Pane = new Pane()
  private val base = new Canvas()
  private val overlay = new Canvas()
  private val tooltipBox = new VBox()
  private val baseContext = new JavaFxCanvasContext(base.getGraphicsContext2D)
  private val emphasisContext = new JavaFxCanvasContext(overlay.getGraphicsContext2D)
  private val controller = new InteractionController(initialState)
  private var view = Option(initialView)
  private var viewport = Option.empty[PickViewport]
  private var input = Option.empty[HostInput[A]]
  private var sequence = 0L
  private val origin = SemanticId.unsafe("javafx-host")
  private var baseDraws = 0L
  private var overlayDraws = 0L
  private var failure = Option.empty[IntaglioError]
  private var style = JavaFxOverlayStyle.default
  private var spoken = ""
  private val outlines =
    scala.collection.mutable.HashMap.empty[(VisualTargetId, Double), Vector[Vector[DevicePoint]]]
  private var geometries = Map.empty[VisualTargetId, TargetGeometry[A]]
  private var entities = Map.empty[EntityKey[A], Vector[TargetGeometry[A]]]

  // ---- Pointer, parts, linked emphasis and tooltip state ----
  private var pointer = Option.empty[(Double, Double)]
  private var hoveredPart = Option.empty[PartTarget]
  private var linkedEmphasis = LinkedEmphasis.none[A]
  private var legendEmphasis = LinkedEmphasis.none[A]
  private var hoverListeners = Vector.empty[(Long, LinkedEmphasis[A] => Unit)]
  private var partListeners = Vector.empty[(Long, JavaFxPartEvent => Unit)]
  private var nextListener = 0L
  private var subscriptions = Vector.empty[InteractionSubscription]
  private enum TooltipSource:
    case Hover, Focus, Part
  private var tooltipSource = Option.empty[TooltipSource]
  private var tooltipShown = Option.empty[TargetContent]
  private var tooltipTimer = Option.empty[PauseTransition]

  /** The live link this host belongs to, if any; a host joins at most one. */
  private[javafx] var link: Option[AnyRef] = None

  // ---- Data-window navigation and region gestures ----
  private var basePlan = initialView.singlePlan
  private var baseView = initialView
  private var navigator = JavaFxInteractionHost.navigatorOf(initialView)
  private var basePanelFrame = initialView.panelFrame
  private var window = PanelWindow.full
  private enum Drag:
    case Band(startX: Double, startY: Double, x: Double, y: Double)
    case Trace(points: Vector[(Double, Double)])
    case Panning(startX: Double, startY: Double, frame: DeviceFrame)
  private var drag = Option.empty[Drag]
  private var band = Option.empty[(Double, Double, Double, Double, Boolean)]
  private var trace = Vector.empty[(Double, Double)]
  private var moved = false
  private var pinch = Option.empty[(DevicePoint, DeviceFrame)]

  node.setAccessibleRole(AccessibleRole.PARENT)
  node.setAccessibleRoleDescription("interactive plot")
  node.setAccessibleText(
    "Plot. Arrows move spatially; Page Up/Down traverse all marks; Enter selects; Escape clears."
  )
  node.setFocusTraversable(true)
  base.setManaged(false)
  overlay.setManaged(false)
  overlay.setMouseTransparent(true)
  tooltipBox.setManaged(false)
  tooltipBox.setMouseTransparent(true)
  tooltipBox.setVisible(false)
  tooltipBox.setPadding(new Insets(4, 8, 4, 8))
  tooltipBox.setBackground(
    new Background(new BackgroundFill(fx(options.tooltipBackground), new CornerRadii(4), null))
  )
  node.getChildren.addAll(base, overlay, tooltipBox)
  node.setPrefSize(initialView.context.logicalWidth, initialView.context.logicalHeight)
  node.resize(initialView.context.logicalWidth, initialView.context.logicalHeight)
  index(initialView)

  private val sizeListener: ChangeListener[Number] = (_, _, _) => resize()
  private val focusListener: ChangeListener[java.lang.Boolean] = (_, _, focused) =>
    if !focused.booleanValue then
      cancelGesture()
      if controller.state.toOption.forall(_.hover.isEmpty) && hoveredPart.isEmpty then
        emitHover(LinkedEmphasis.none[A])
      hideTooltip()
    redrawOverlay()
  private val mouseHandler: EventHandler[MouseEvent] = event => handleMouse(event)
  private val keyHandler: EventHandler[KeyEvent] = event => handleKey(event)
  private val scrollHandler: EventHandler[ScrollEvent] = event => handleScroll(event)
  private val zoomHandler: EventHandler[ZoomEvent] = event => handleZoom(event)
  node.widthProperty.addListener(sizeListener)
  node.heightProperty.addListener(sizeListener)
  node.focusedProperty.addListener(focusListener)
  node.addEventHandler(MouseEvent.ANY, mouseHandler)
  node.addEventHandler(KeyEvent.KEY_PRESSED, keyHandler)
  node.addEventHandler(ScrollEvent.SCROLL, scrollHandler)
  node.addEventHandler(ZoomEvent.ANY, zoomHandler)
  controller.subscribe(react).foreach(sub => subscriptions = subscriptions :+ sub)
  resize()

  def profile: JavaFxHostProfile = JavaFxHostProfile(baseDraws, overlayDraws)

  /** The style the overlay is drawn with; `JavaFxOverlayStyle.default` until replaced. */
  def overlayStyle: JavaFxOverlayStyle = style

  /** Replace the overlay style, for example on a theme change, and redraw the overlay only. */
  def setOverlayStyle(value: JavaFxOverlayStyle): Either[IntaglioError, Unit] =
    checked.map { _ =>
      style = value
      outlines.clear()
      redrawOverlay()
    }
  def lastError: Option[IntaglioError] = failure
  def isDisposed: Boolean = view.isEmpty

  def state: Either[IntaglioError, InteractionState[A]] = checked.flatMap(_ => controller.state)

  /** The view currently drawn: the attached view, or a re-windowed copy of it while navigated. */
  def currentView: Either[IntaglioError, JavaFxInteractionView[A]] =
    checked.map(_ => view.get)

  def subscribe(listener: EventRecord[A] => Unit): Either[IntaglioError, InteractionSubscription] =
    checked.flatMap(_ => controller.subscribe(listener))

  /** Typed plot-part events; the returned function unsubscribes. */
  def subscribeParts(listener: JavaFxPartEvent => Unit): Either[IntaglioError, () => Unit] =
    checked.map { _ =>
      val id = nextListener
      nextListener += 1
      partListeners = partListeners :+ (id -> listener)
      () => partListeners = partListeners.filterNot(_._1 == id)
    }

  /** What this plot's reader is pointing at, as keys a linked view can emphasize: the hovered or
    * keyboard-focused mark's entity, or a hovered linked legend entry's link key; empty when
    * nothing. The returned function unsubscribes.
    */
  def subscribeHover(listener: LinkedEmphasis[A] => Unit): Either[IntaglioError, () => Unit] =
    checked.map { _ =>
      val id = nextListener
      nextListener += 1
      hoverListeners = hoverListeners :+ (id -> listener)
      () => hoverListeners = hoverListeners.filterNot(_._1 == id)
    }

  /** Emphasize the marks a linked view points at. Display only: state and events are untouched, so
    * emphasis can never echo back.
    */
  def setLinkedEmphasis(value: LinkedEmphasis[A]): Either[IntaglioError, Unit] =
    checked.map { _ =>
      if value != linkedEmphasis then
        linkedEmphasis = value
        redrawOverlay()
    }

  /** Projected inputs change the overlay, but intentionally deliver no application event. */
  def setSelection(selected: Selection[A]): Either[IntaglioError, Unit] =
    dispatch(InteractionAction.Select(selected, SelectionOperation.Replace), InputCause.Projected)

  def setHover(target: Option[VisualTargetId]): Either[IntaglioError, Unit] =
    dispatch(InteractionAction.Hover(target), InputCause.Projected)

  /** Choose how a primary-button drag acts: inspect (no drag gesture), rectangle or lasso
    * selection, pan, or zoom to a rectangle. Pan and rectangle zoom are refused on a plot that
    * cannot be navigated.
    */
  def setGestureMode(mode: GestureMode): Either[IntaglioError, Unit] =
    checked.flatMap { _ =>
      if (mode == GestureMode.Pan || mode == GestureMode.ZoomRectangle) && navigator.isEmpty then
        Left(InteractionError.UnsupportedCapability(s"${mode.toString} on $unnavigable"))
      else dispatch(InteractionAction.SetGestureMode(mode), InputCause.Programmatic)
    }

  /** Whether this view can pan and zoom its data window. */
  def isNavigable: Boolean = navigator.nonEmpty

  /** The data window currently shown, in panel position units; [[PanelWindow.full]] when reset. */
  def currentWindow: PanelWindow = window

  /** Show the data window `value` (in panel position units; [[PanelWindow.full]] resets). The
    * statistics are not recomputed; marks, targets and selection stay. Refused when the plot cannot
    * be navigated (a named scene, a composition, a faceted or flipped plot, or one with no numeric
    * or temporal axis).
    */
  def navigate(value: PanelWindow): Either[IntaglioError, Unit] =
    checked.flatMap { _ =>
      navigator match
        case None      => Left(InteractionError.UnsupportedCapability(unnavigable))
        case Some(nav) => nav.normalize(value).flatMap(showWindow(_, InputCause.Programmatic))
    }

  /** Zoom about the panel centre by `factor` (below 1 zooms in), as the keyboard does. */
  def zoomBy(factor: Double): Either[IntaglioError, Unit] =
    checked.flatMap { _ =>
      if !factor.isFinite || factor <= 0 then
        Left(InteractionError.InvalidValue("zoom factor", factor.toString))
      else zoomCentre(factor, InputCause.Programmatic)
    }

  /** Return to the compiled, unwindowed view. */
  def resetWindow(): Either[IntaglioError, Unit] = navigate(PanelWindow.full)

  /** The observation keys this plot draws as marks of their own; a histogram draws none. */
  def drawnEntities: Set[EntityKey[A]] =
    view.fold(Set.empty[EntityKey[A]])(_.navigation.targets.flatMap(_.target.entity).toSet)

  /** The observation keys a reader can select in this plot: its marks' keys and, in
    * [[AggregateSelection.Members]] mode, the members of its aggregates (every source observation
    * for deferred membership). A link group takes a reader's change in these keys only.
    */
  def selectableEntities: Set[EntityKey[A]] =
    view.fold(Set.empty[EntityKey[A]]) { current =>
      val members = current.navigation.targets.map(_.target).filter { info =>
        info.entity.isEmpty && behavior.aggregates(info) == AggregateSelection.Members
      }
      val exact = members.iterator
        .filter(_.membership.capability == MembershipCapability.Exact)
        .flatMap { info =>
          current.plans.find(_.id == info.id.plan).iterator.flatMap { plan =>
            info.membership.exactKeys(plan.sourceRevision).toOption.iterator.flatten
          }
        }
      val deferredPlans = members
        .filter(_.membership.capability == MembershipCapability.Deferred)
        .map(_.id.plan)
        .toSet
      val deferred =
        current.plans.iterator.filter(p => deferredPlans(p.id)).flatMap(_.sourceEntities)
      drawnEntities ++ exact ++ deferred
    }

  /** The content of the tooltip currently shown, if any (a pending, delayed tooltip is not yet
    * shown). Content is plain values rendered as JavaFX text, never markup.
    */
  def tooltip: Either[IntaglioError, Option[TargetContent]] = checked.map(_ => tooltipShown)

  /** The tooltip's box in the node's coordinates while it is shown: left, top, width, height. */
  def tooltipBounds: Either[IntaglioError, Option[(Double, Double, Double, Double)]] =
    checked.map { _ =>
      Option.when(tooltipShown.nonEmpty) {
        (tooltipBox.getLayoutX, tooltipBox.getLayoutY, tooltipBox.getWidth, tooltipBox.getHeight)
      }
    }

  /** What the host last announced: the focused mark's description (with its coverage count) or a
    * membership outcome, as the browser widget's live region says it. It is also the node's
    * accessible text while a mark is focused.
    */
  def announcement: Either[IntaglioError, String] = checked.map(_ => spoken)

  /** The text companion: one row per mark in reading order (with partial-coverage counts), then one
    * per plot part.
    *
    * Interaction 10 hook: when the shared `InspectorModel` lands, this is where the JavaFX host
    * reads inspector rows; until then it returns the same text-companion rows the browser widget's
    * table shows, and [[HostCapability.Inspector]] stays refused.
    */
  def companionRows: Either[IntaglioError, Vector[(String, String)]] =
    checked.map { _ =>
      val current = view.get
      current.navigation.targets.map { g =>
        val text = current.describe(g.target, behavior)
        ("mark", coverageNote(g.target).fold(text)(note => s"$text ($note)"))
      } ++ current.parts.toVector.flatMap(_.parts).map(part => ("part", part.part.describe))
    }

  /** Show a new view of the same plot (new data or a new render size). Selection is reconciled by
    * entity key; entities that no longer exist are dropped and reported in a `Reconciled` event. A
    * view whose plan has the current revision (a resize or restyle of the same data) keeps the
    * state as it is. The new view is the new unwindowed base for navigation.
    */
  def update(next: JavaFxInteractionView[A]): Either[IntaglioError, Unit] =
    for
      _ <- checked
      current <- controller.state
      _ <-
        if current.domain.revision == next.domain.revision then Right(())
        else
          controller
            .replaceDomain(
              stamp(current, InputCause.Programmatic),
              next.domain,
              MissingEntityPolicy.Drop
            )
            .map(_ => ())
    yield
      setHoveredPart(None)
      emitHover(LinkedEmphasis.none[A])
      legendEmphasis = LinkedEmphasis.none[A]
      cancelGesture()
      basePlan = next.singlePlan
      baseView = next
      navigator = JavaFxInteractionHost.navigatorOf(next)
      basePanelFrame = next.panelFrame
      window = PanelWindow.full
      accept(
        dispatch(
          InteractionAction.SetViewport(JavaFxInteractionHost.panelId, None),
          InputCause.Programmatic
        )
      )
      if navigator.isEmpty && Set(GestureMode.Pan, GestureMode.ZoomRectangle).contains(mode) then
        accept(
          dispatch(InteractionAction.SetGestureMode(GestureMode.Inspect), InputCause.Programmatic)
        )
      swap(next)
      spoken = ""

  /** Local logical coordinates, including letterbox rejection, through the exact draw mapping. */
  def toDevice(x: Double, y: Double): Either[IntaglioError, Option[DevicePoint]] =
    checked.flatMap(_ =>
      viewport.fold[Either[IntaglioError, Option[DevicePoint]]](Right(None))(
        _.toDevice(x, y)
      )
    )

  /** Node-local logical coordinates of a device point, through the exact draw mapping. */
  def toLocal(point: DevicePoint): Either[IntaglioError, Option[(Double, Double)]] =
    checked.map(_ =>
      viewport.map(m =>
        (
          m.clientLeft + point.x * m.cssPixelsPerDevicePixel,
          m.clientTop + point.y * m.cssPixelsPerDevicePixel
        )
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

  /** Idempotent; removes listeners/subscriptions, stops the tooltip timer and releases raster and
    * pattern caches. Linked views stop showing what this plot pointed at.
    */
  def dispose(): Either[IntaglioError, Unit] =
    if !Platform.isFxApplicationThread then Left(JavaFxHostError.WrongThread)
    else
      if view.nonEmpty then
        emitHover(LinkedEmphasis.none[A])
        node.removeEventHandler(MouseEvent.ANY, mouseHandler)
        node.removeEventHandler(KeyEvent.KEY_PRESSED, keyHandler)
        node.removeEventHandler(ScrollEvent.SCROLL, scrollHandler)
        node.removeEventHandler(ZoomEvent.ANY, zoomHandler)
        node.widthProperty.removeListener(sizeListener)
        node.heightProperty.removeListener(sizeListener)
        node.focusedProperty.removeListener(focusListener)
        tooltipTimer.foreach(_.stop())
        tooltipTimer = None
        tooltipShown = None
        subscriptions.foreach(_.cancel())
        subscriptions = Vector.empty
        hoverListeners = Vector.empty
        partListeners = Vector.empty
        controller.dispose()
        baseContext.clearCaches()
        emphasisContext.clearCaches()
        node.getChildren.clear()
        tooltipBox.getChildren.clear()
        node.setFocusTraversable(false)
        node.setAccessibleText(null)
        base.setWidth(0)
        base.setHeight(0)
        overlay.setWidth(0)
        overlay.setHeight(0)
        geometries = Map.empty
        entities = Map.empty
        outlines.clear()
        drag = None
        pinch = None
        view = None
        viewport = None
        input = None
        basePlan = None
        navigator = None
      Right(())

  // ---------------------------------------------------------------------------------------------
  // Dispatch

  private def checked: Either[IntaglioError, Unit] =
    if !Platform.isFxApplicationThread then Left(JavaFxHostError.WrongThread)
    else if view.isEmpty then Left(JavaFxHostError.Disposed)
    else Right(())

  private def stamp(state: InteractionState[A], cause: InputCause): InputStamp =
    val value = InputStamp(state.domain.revision, origin, sequence, cause)
    sequence += 1
    value

  private def dispatch(
      action: InteractionAction[A],
      cause: InputCause
  ): Either[IntaglioError, Unit] =
    checked.flatMap { _ =>
      controller.state.flatMap { current =>
        controller.dispatch(stamp(current, cause), action).map { _ =>
          redrawOverlay()
          ()
        }
      }
    }

  /** Map input through the shared contract against the live viewport and dispatch it; true when it
    * produced any action.
    */
  private def withInput(
      produce: (HostInput[A], InteractionState[A]) => Either[IntaglioError, Vector[HostAction[A]]]
  ): Boolean =
    input match
      case None          => false
      case Some(mapping) =>
        val result = for
          current <- controller.state
          actions <- produce(mapping, current)
          _ <- actions.foldLeft[Either[IntaglioError, Unit]](Right(())) { (done, action) =>
            done.flatMap(_ => dispatch(action.action, action.cause))
          }
        yield actions.nonEmpty
        result.fold(error => { accept(Left(error)); false }, identity)

  private def accept(result: Either[IntaglioError, Unit]): Unit =
    result.left.foreach { error =>
      failure = Some(error)
      try reportError(error)
      catch case NonFatal(_) => ()
    }

  // ---------------------------------------------------------------------------------------------
  // Drawing

  private def index(current: JavaFxInteractionView[A]): Unit =
    geometries = current.navigation.targets.map(g => g.target.id -> g).toMap
    entities = current.navigation.targets
      .flatMap(g => g.target.entity.map(_ -> g))
      .groupMap(_._1)(_._2)
    outlines.clear()

  /** Show `next` (the same plan at another window, or a new view) without touching state. */
  private def swap(next: JavaFxInteractionView[A]): Unit =
    hideTooltip()
    baseContext.clearCaches()
    emphasisContext.clearCaches()
    view = Some(next)
    index(next)
    resize()

  private def resize(): Unit =
    view.foreach { current =>
      val width = node.getWidth
      val height = node.getHeight
      if width > 0 && height > 0 then
        viewport = PickViewport
          .fit(current.deviceScene.width, current.deviceScene.height, 0, 0, width, height)
          .toOption
        input = viewport.map(fitted =>
          HostInput(
            current.picking,
            current.navigation,
            fitted,
            behavior,
            options.toleranceLogicalPx
          )
        )
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
      else
        viewport = None
        input = None
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
        shown <- view
      do
        val targets = shown.navigation.targets
        val selected = current.selection.targets.flatMap(geometries.get) ++
          current.selection.entities.flatMap(key => entities.getOrElse(key, Vector.empty))
        val selectedIds = selected.map(_.target.id)
        // Aggregates are pointed at by coverage: a hovered observation in a linked plot emphasizes
        // the bins that hold it, and selected observations cover bins by the behaviour's rule.
        val linkedIds = targets.collect {
          case g
              if linkedEmphasis.covers(g.target, EmphasisRule.AnyMember, current.domain) ||
                legendEmphasis.matches(g.target) =>
            g.target.id
        }
        val coveredIds = targets.collect {
          case g
              if g.target.entity.isEmpty &&
                g.target.membership.capability == MembershipCapability.Exact &&
                !current.selection.targets.contains(g.target.id) &&
                behavior.aggregateEmphasis.triggered(
                  MemberCoverage.of(g.target, current.selection.entities, current.domain)
                ) =>
            g.target.id
        }
        val emphasized =
          (selectedIds.toVector ++ coveredIds ++ current.hover.toVector ++ linkedIds).distinct
        val dim = behavior.inverseEmphasis && emphasized.nonEmpty
        base.setOpacity(if dim then 0.3 else 1.0)
        transformed(gc, mapping) {
          if dim then emphasize(gc, shown, emphasized)
          linkedIds.foreach(id =>
            geometries.get(id).foreach { g =>
              outline(
                gc,
                mapping,
                g,
                style.linked,
                style.highlightOffsetLogicalPx,
                Vector(2.0, 2.0)
              )
            }
          )
          val active = selectedIds ++ current.hover ++ current.focus
          val styles = AppearanceStyles[Option[OverlayStroke]](
            None,
            None,
            selection = Some(Some(style.selection)),
            hover = Some(Some(style.hover))
          )
          active.flatMap(geometries.get).foreach { g =>
            InteractionAppearance
              .resolve(
                styles,
                selectedIds.contains(g.target.id),
                current.hover.contains(g.target.id),
                current.focus.contains(g.target.id)
              )
              .style
              .foreach(stroke => outline(gc, mapping, g, stroke, style.highlightOffsetLogicalPx))
          }
          coveredIds.foreach(id =>
            geometries.get(id).foreach { g =>
              outline(
                gc,
                mapping,
                g,
                style.covered,
                style.highlightOffsetLogicalPx,
                Vector(5.0, 3.0)
              )
            }
          )
          hoveredPart.foreach { part =>
            shown.parts.foreach { picking =>
              picking
                .outline(part, style.highlightOffsetLogicalPx * mapping.cssPixelsPerDevicePixel)
                .toOption
                .flatten
                .foreach(o => rings(gc, mapping, o.rings, style.hover))
            }
          }
          // Focus is the final overlay pass, even when other targets overlap it.
          current.focus.flatMap(geometries.get).foreach { g =>
            outline(gc, mapping, g, style.focus, style.focusOffsetLogicalPx)
          }
        }
        current.focus
          .flatMap(geometries.get)
          .foreach(g => node.setAccessibleText(describeWithCoverage(g.target)))
      overlayDraws += 1

  /** Redraw the emphasized targets in their original paint over the dimmed base, clipped to their
    * outlines, as the browser widget does.
    */
  private def emphasize(
      gc: GraphicsContext,
      shown: JavaFxInteractionView[A],
      targets: Vector[VisualTargetId]
  ): Unit =
    val clip = targets.flatMap(id => shown.picking.outline(id, 1.5).toOption).flatMap(_.rings)
    if clip.exists(_.size >= 3) then
      gc.save()
      try
        gc.beginPath()
        clip.filter(_.size >= 3).foreach { ring =>
          gc.moveTo(ring.head.x, ring.head.y)
          ring.tail.foreach(p => gc.lineTo(p.x, p.y))
          gc.closePath()
        }
        gc.clip()
        JavaFxRenderer.draw(shown.program, emphasisContext)
      finally gc.restore()

  private def outline(
      gc: GraphicsContext,
      mapping: PickViewport,
      geometry: TargetGeometry[A],
      stroke: OverlayStroke,
      offset: Double,
      dashes: Vector[Double] = Vector.empty
  ): Unit =
    for
      color <- stroke.casingColor
      width <- stroke.casingWidthLogicalPx
    do traceTarget(gc, mapping, geometry, color, width, offset, Vector.empty)
    traceTarget(gc, mapping, geometry, stroke.color, stroke.widthLogicalPx, offset, dashes)

  private def rings(
      gc: GraphicsContext,
      mapping: PickViewport,
      value: Vector[Vector[DevicePoint]],
      stroke: OverlayStroke
  ): Unit =
    val scale = mapping.cssPixelsPerDevicePixel
    gc.setGlobalAlpha(1)
    gc.setStroke(fx(stroke.color))
    gc.setLineWidth(stroke.widthLogicalPx / scale)
    gc.setLineDashes()
    value.filter(_.nonEmpty).foreach { ring =>
      gc.strokePolygon(ring.map(_.x).toArray, ring.map(_.y).toArray, ring.size)
    }

  private def traceTarget(
      gc: GraphicsContext,
      mapping: PickViewport,
      geometry: TargetGeometry[A],
      color: Rgba,
      width: Double,
      padding: Double,
      dashes: Vector[Double]
  ): Unit =
    val scale = mapping.cssPixelsPerDevicePixel
    val inset = padding / scale
    gc.setGlobalAlpha(1)
    gc.setStroke(fx(color))
    gc.setLineWidth(width / scale)
    gc.setLineDashes(dashes.map(_ / scale)*)
    style.outline match
      case OverlayOutline.Bounds   => bounds(gc, geometry, inset)
      case OverlayOutline.Geometry =>
        val traced = outlines.getOrElseUpdate(
          (geometry.target.id, inset),
          view
            .fold[Either[IntaglioError, TargetOutline]](Left(JavaFxHostError.Disposed))(
              _.picking.outline(geometry.target.id, inset)
            )
            .fold(_ => Vector.empty, _.rings)
        )
        if traced.isEmpty then bounds(gc, geometry, inset)
        else
          traced.foreach { ring =>
            gc.strokePolygon(ring.map(_.x).toArray, ring.map(_.y).toArray, ring.size)
          }
    gc.setLineDashes()

  private def bounds(gc: GraphicsContext, geometry: TargetGeometry[A], inset: Double): Unit =
    gc.strokeRect(
      geometry.left - inset,
      geometry.top - inset,
      geometry.right - geometry.left + 2 * inset,
      geometry.bottom - geometry.top + 2 * inset
    )

  private def fx(color: Rgba): Color = Color.rgb(color.red, color.green, color.blue, color.alpha)

  // ---------------------------------------------------------------------------------------------
  // Pointer input

  private def mode: GestureMode =
    controller.state.toOption.fold(GestureMode.Inspect)(_.gestureMode)

  /** Node-local to device coordinates, unclamped, so a drag may leave the plot. */
  private def device(x: Double, y: Double): Option[(Double, Double)] =
    viewport.map(m =>
      (
        (x - m.clientLeft) / m.cssPixelsPerDevicePixel,
        (y - m.clientTop) / m.cssPixelsPerDevicePixel
      )
    )

  private def additive(event: MouseEvent): Boolean =
    event.isShiftDown || event.isControlDown || event.isMetaDown

  private def handleMouse(event: MouseEvent): Unit =
    if view.nonEmpty then
      val kind = event.getEventType
      val x = event.getX
      val y = event.getY
      if kind == MouseEvent.MOUSE_MOVED || kind == MouseEvent.MOUSE_DRAGGED then
        pointer = Some((x, y))
        if !gestureMove(x, y) then
          withInput((in, s) => in.pointer(s, PointerInput.Move(x, y)))
          hoverPart()
      else if kind == MouseEvent.MOUSE_EXITED then
        pointer = None
        withInput((in, s) => in.pointer(s, PointerInput.Leave))
        setHoveredPart(None)
      else if kind == MouseEvent.MOUSE_PRESSED && event.getButton == MouseButton.PRIMARY then
        node.requestFocus()
        withInput((in, s) => in.pointer(s, PointerInput.Press))
        gestureStart(x, y)
      else if kind == MouseEvent.MOUSE_RELEASED && event.getButton == MouseButton.PRIMARY then
        gestureEnd(x, y, event)
        withInput((in, s) => in.pointer(s, PointerInput.Release))
      else if kind == MouseEvent.MOUSE_CLICKED && event.getButton == MouseButton.PRIMARY then
        // A drag ends in a click; it is the end of the drag, not a click on a mark.
        if moved then moved = false
        else
          val handled =
            withInput((in, s) => in.pointer(s, PointerInput.Click(x, y, additive(event))))
          if !handled then
            hoveredPart.foreach { part =>
              emitPart(JavaFxPartEvent.Activated(part.part, InputCause.Pointer))
              legendLink(part.part).foreach(chooseLegend(_, additive(event)))
            }

  private def gestureStart(x: Double, y: Double): Unit =
    moved = false
    device(x, y).foreach { start =>
      drag = mode match
        case GestureMode.Rectangle | GestureMode.ZoomRectangle =>
          Some(Drag.Band(start._1, start._2, start._1, start._2))
        case GestureMode.Lasso                     => Some(Drag.Trace(Vector(start)))
        case GestureMode.Pan if navigator.nonEmpty =>
          panelFrameNow.map(frame => Drag.Panning(start._1, start._2, frame))
        case _ => None
    }

  /** Advance a drag; true when the move belonged to one. */
  private def gestureMove(x: Double, y: Double): Boolean =
    device(x, y) match
      case Some((dx, dy)) =>
        drag match
          case Some(Drag.Band(sx, sy, _, _)) =>
            moved = moved || math.hypot(dx - sx, dy - sy) > 3
            drag = Some(Drag.Band(sx, sy, dx, dy))
            band = Some((sx, sy, dx, dy, mode == GestureMode.ZoomRectangle))
            redrawGesture()
            true
          case Some(Drag.Trace(points)) =>
            val (lx, ly) = points.last
            if math.hypot(dx - lx, dy - ly) > 2 then
              moved = true
              drag = Some(Drag.Trace(points :+ (dx, dy)))
              trace = points :+ (dx, dy)
              redrawGesture()
            true
          case Some(Drag.Panning(sx, sy, frame)) =>
            moved = moved || math.hypot(dx - sx, dy - sy) > 1
            navigator.foreach(nav =>
              accept(showWindow(nav.pan(frame, dx - sx, dy - sy), InputCause.Pointer))
            )
            true
          case None => false
      case None => drag.nonEmpty

  private def gestureEnd(x: Double, y: Double, event: MouseEvent): Unit =
    val finished = drag
    drag = None
    clearGesture()
    finished.foreach {
      case Drag.Band(sx, sy, ex, ey) if moved =>
        if mode == GestureMode.ZoomRectangle then
          for
            nav <- navigator
            frame <- panelFrameNow
          do
            accept(
              showWindow(
                nav.rectangle(frame, DevicePoint(sx, sy), DevicePoint(ex, ey)),
                InputCause.Pointer
              )
            )
        else
          PickArea
            .rectangle(math.min(sx, ex), math.min(sy, ey), math.max(sx, ex), math.max(sy, ey))
            .foreach(area => sweep(area, event))
      case Drag.Trace(points) if moved && points.size >= 3 =>
        PickArea.lasso(points.map((px, py) => DevicePoint(px, py))).foreach(sweep(_, event))
      case _ => ()
    }

  /** Select what a band or lasso covers: Shift adds, Alt subtracts, otherwise it replaces. */
  private def sweep(area: PickArea, event: MouseEvent): Unit =
    val operation =
      if event.isAltDown then SelectionOperation.Subtract
      else if event.isShiftDown then SelectionOperation.Add
      else SelectionOperation.Replace
    withInput((in, s) => Right(in.region(s, area, AreaRule.CenterInside, operation)))

  private def cancelGesture(): Unit =
    drag = None
    pinch = None
    clearGesture()
    if controller.state.toOption.exists(_.gesture.nonEmpty) then
      withInput((in, s) => in.pointer(s, PointerInput.Cancel))

  private def clearGesture(): Unit =
    if band.nonEmpty || trace.nonEmpty then
      band = None
      trace = Vector.empty
      redrawOverlay()

  /** The rubber band or lasso in progress, drawn over the overlay. */
  private def redrawGesture(): Unit =
    redrawOverlay()
    viewport.foreach { mapping =>
      val gc = overlay.getGraphicsContext2D
      transformed(gc, mapping) {
        val scale = mapping.cssPixelsPerDevicePixel
        gc.setLineWidth(1 / scale)
        gc.setLineDashes(4 / scale, 2 / scale)
        band.foreach { (x0, y0, x1, y1, zoom) =>
          val stroke = if zoom then style.linked.color else Rgba.unsafe(0x1a, 0x56, 0xdb)
          gc.setStroke(fx(stroke))
          gc.setFill(fx(stroke.withAlpha(0.08).getOrElse(stroke)))
          gc.fillRect(math.min(x0, x1), math.min(y0, y1), math.abs(x1 - x0), math.abs(y1 - y0))
          gc.strokeRect(math.min(x0, x1), math.min(y0, y1), math.abs(x1 - x0), math.abs(y1 - y0))
        }
        if trace.size >= 2 then
          gc.setStroke(fx(Rgba.unsafe(0x1a, 0x56, 0xdb)))
          gc.strokePolygon(trace.map(_._1).toArray, trace.map(_._2).toArray, trace.size)
        gc.setLineDashes()
      }
    }

  private def handleScroll(event: ScrollEvent): Unit =
    // The surrounding scroll pane scrolls unless the reader is working in the plot: it has focus,
    // or Ctrl is held. JavaFX's positive deltaY scrolls up (away from the reader), the opposite
    // sign of a DOM wheel event.
    val engaged = event.isControlDown || node.isFocused
    val delta = -event.getDeltaY
    val idle = delta == 0 || (delta > 0 && window.isFull)
    if navigator.nonEmpty && engaged && !idle && !event.isInertia then
      event.consume()
      val factor = math.exp(math.max(-0.5, math.min(0.5, delta * 0.002)))
      for
        point <- device(event.getX, event.getY)
        nav <- navigator
        frame <- panelFrameNow
      do
        accept(
          showWindow(nav.zoom(frame, DevicePoint(point._1, point._2), factor), InputCause.Pointer)
        )

  /** A trackpad or touch-screen pinch: zoom is absolute from the gesture's start. */
  private def handleZoom(event: ZoomEvent): Unit =
    if navigator.nonEmpty then
      val kind = event.getEventType
      if kind == ZoomEvent.ZOOM_STARTED then
        pinch = for
          (px, py) <- device(event.getX, event.getY)
          frame <- panelFrameNow
        yield (DevicePoint(px, py), frame)
        event.consume()
      else if kind == ZoomEvent.ZOOM then
        for
          (pivot, frame) <- pinch
          nav <- navigator
        do
          val total = event.getTotalZoomFactor
          if total.isFinite && total > 0 then
            accept(showWindow(nav.zoom(frame, pivot, 1.0 / total), InputCause.Pointer))
        event.consume()
      else if kind == ZoomEvent.ZOOM_FINISHED then
        pinch = None
        event.consume()

  // ---------------------------------------------------------------------------------------------
  // Keyboard input

  private def handleKey(event: KeyEvent): Unit =
    if view.nonEmpty then
      val code = event.getCode
      val chosen = event.isShiftDown || event.isControlDown || event.isMetaDown
      val key = code match
        case KeyCode.LEFT                  => Some(KeyInput.Arrow(NavigationDirection.Left))
        case KeyCode.RIGHT                 => Some(KeyInput.Arrow(NavigationDirection.Right))
        case KeyCode.UP                    => Some(KeyInput.Arrow(NavigationDirection.Up))
        case KeyCode.DOWN                  => Some(KeyInput.Arrow(NavigationDirection.Down))
        case KeyCode.HOME                  => Some(KeyInput.First)
        case KeyCode.END                   => Some(KeyInput.Last)
        case KeyCode.PAGE_UP               => Some(KeyInput.Previous)
        case KeyCode.PAGE_DOWN             => Some(KeyInput.Next)
        case KeyCode.ENTER | KeyCode.SPACE => Some(KeyInput.Choose(chosen))
        case KeyCode.ESCAPE                => Some(KeyInput.Escape)
        case _                             => None
      // Ctrl, Meta or Alt with these keys belongs to the application (its own zoom shortcuts).
      val modified = event.isControlDown || event.isMetaDown || event.isAltDown
      val zoom: Option[() => Unit] = code match
        case _ if modified || navigator.isEmpty          => None
        case KeyCode.PLUS | KeyCode.EQUALS | KeyCode.ADD =>
          Some(() => accept(zoomCentre(0.8, InputCause.Keyboard)))
        case KeyCode.MINUS | KeyCode.UNDERSCORE | KeyCode.SUBTRACT =>
          Some(() => accept(zoomCentre(1.25, InputCause.Keyboard)))
        case KeyCode.DIGIT0 | KeyCode.NUMPAD0 =>
          Some(() => accept(showWindow(PanelWindow.full, InputCause.Keyboard)))
        case _ => None
      if drag.nonEmpty && code == KeyCode.ESCAPE then
        // Escape first abandons a drag in progress; a second Escape clears the selection.
        event.consume()
        drag = None
        clearGesture()
        withInput((in, s) =>
          in.key(s, KeyInput.Escape)
            .map(_.filterNot(_.action.isInstanceOf[InteractionAction.Select[?]]))
        )
      else if zoom.nonEmpty then
        event.consume()
        zoom.foreach(_())
      else
        key.foreach { value =>
          event.consume()
          withInput((in, s) => in.key(s, value))
        }

  // ---------------------------------------------------------------------------------------------
  // Navigation

  private def unnavigable: String =
    "this JavaFX view (navigation needs one plot panel with a numeric or temporal axis)"

  private def zoomCentre(factor: Double, cause: InputCause): Either[IntaglioError, Unit] =
    (navigator, panelFrameNow) match
      case (Some(nav), Some(frame)) =>
        val centre = DevicePoint(frame.x + frame.width / 2, frame.y + frame.height / 2)
        showWindow(nav.zoom(frame, centre, factor), cause)
      case _ => Left(InteractionError.UnsupportedCapability(unnavigable))

  /** The panel frame of the window shown: a re-windowed panel keeps its device frame and spans
    * exactly its window's positions.
    */
  private def panelFrameNow: Option[DeviceFrame] =
    def range(value: Option[(Double, Double)], full: Interval) =
      value.flatMap((lo, hi) => Interval(lo, hi).toOption).getOrElse(full)
    basePanelFrame.map(b =>
      b.copy(xScale = range(window.x, b.xScale), yScale = range(window.y, b.yScale))
    )

  /** Re-window the base plan and show it. The plan's identity and revision are unchanged, so the
    * interaction state (selection, focus) stands; the viewport is recorded in it.
    */
  private def showWindow(value: PanelWindow, cause: InputCause): Either[IntaglioError, Unit] =
    if value == window then Right(())
    else
      for
        _ <- checked
        nav <- navigator.toRight(InteractionError.UnsupportedCapability(unnavigable))
        next <-
          if value.isFull then Right(baseView)
          else
            for
              windows <- nav.windows(value)
              original <- basePlan.toRight(InteractionError.UnsupportedCapability(unnavigable))
              plan <- InteractionCompiler.rezoom(original, windows._1, windows._2)
              compiled <- JavaFxInteractionView.compile(plan, baseView.context, baseView.policy)
            yield compiled
        _ = swap(next)
        // The window drawn, which a temporal axis snaps to whole days or milliseconds: it is what
        // the reader sees, what is recorded, and what the next increment builds on.
        shown = next.panelFrame.fold(value)(frame =>
          PanelWindow(
            value.x.map(_ => (frame.xScale.lower, frame.xScale.upper)),
            value.y.map(_ => (frame.yScale.lower, frame.yScale.upper))
          )
        )
        _ = window = shown
        recorded <- (shown.x, shown.y, next.panelFrame) match
          case (None, None, _) | (_, _, None) => Right(None)
          case (_, _, Some(frame))            =>
            PanelViewport(
              frame.xScale.lower,
              frame.xScale.upper,
              frame.yScale.lower,
              frame.yScale.upper
            ).map(Some(_))
        _ <- dispatch(InteractionAction.SetViewport(JavaFxInteractionHost.panelId, recorded), cause)
      yield ()

  // ---------------------------------------------------------------------------------------------
  // Plot parts and legend links

  private def hoverPart(): Unit =
    val hit = for
      shown <- view.toRight(JavaFxHostError.Disposed)
      parts <- shown.parts.toRight(JavaFxHostError.Disposed)
      fitted <- viewport.toRight(JavaFxHostError.Disposed)
      at <- pointer.toRight(JavaFxHostError.Disposed)
      point <- fitted.toDevice(at._1, at._2)
      reach <- fitted.tolerance(2.0)
      part <- point.fold[Either[IntaglioError, Option[PartTarget]]](Right(None))(parts.at(_, reach))
    yield part
    hit match
      case Right(part) =>
        val hovered = controller.state.toOption.flatMap(_.hover)
        val annotation = hovered.flatMap(annotationPart)
        setHoveredPart(
          if hovered.nonEmpty then part.filter(p => annotation.contains(p.part)) else part
        )
      case Left(_) => setHoveredPart(None)

  /** Authored annotations are also logical targets: a hit on one is a hit on its typed part. */
  private def annotationPart(target: VisualTargetId): Option[PlotPart] =
    view.flatMap { shown =>
      shown.plans.find(_.id == target.plan).flatMap { plan =>
        def routes(grob: Grob): Vector[String] = grob match
          case a: Grob.Annotated =>
            a.meta.data.collect {
              case (key, value) if key == InteractionCompiler.targetAttribute => value
            } ++ routes(a.child)
          case g: Grob.Group => g.children.flatMap(routes)
          case _             => Vector.empty
        (plan.trained.layers ++ plan.trained.facetPanels.flatMap(_.layers))
          .filter(_.annotation.nonEmpty)
          .find(layer => layer.grobs.flatMap(routes).contains(target.scope.value))
          .map(layer => PlotPart.Annotation(layer.layerIndex))
      }
    }

  private def setHoveredPart(part: Option[PartTarget]): Unit =
    if part != hoveredPart then
      hoveredPart = part
      emitPart(JavaFxPartEvent.Hovered(part.map(_.part)))
      legendEmphasis = part.flatMap(p => legendLink(p.part)).getOrElse(LinkedEmphasis.none[A])
      if part.nonEmpty || controller.state.toOption.forall(_.hover.isEmpty) then
        emitHover(legendEmphasis)
      part match
        case Some(p) =>
          showTooltip(
            TargetContent.Text(p.part.describe),
            pointer,
            partAnchor(p),
            immediate = true,
            TooltipSource.Part
          )
        case None => hideTooltip(TooltipSource.Part)
      redrawOverlay()

  private def partAnchor(part: PartTarget): (Double, Double) =
    view
      .flatMap(_.parts)
      .flatMap(_.outline(part, 0).toOption.flatten)
      .flatMap(_.rings.flatten.headOption)
      .fold((0.0, 0.0))(p => local(p.x, p.y))

  private def legendLink(part: PlotPart): Option[LinkedEmphasis[A]] =
    part match
      case PlotPart.LegendEntry(legend, _, _, label) =>
        behavior.legendLinks
          .find(_.legend == legend)
          .flatMap(link => link.space.link(label).toOption)
          .map(key => LinkedEmphasis[A](links = Set(key)))
      case _ => None

  /** Choosing a linked legend entry selects its marks, as a reader's own action. */
  private def chooseLegend(emphasis: LinkedEmphasis[A], additive: Boolean): Unit =
    val chosen = view.toVector
      .flatMap(_.navigation.targets.map(_.target))
      .filter(emphasis.matches)
      .flatMap(_.entity)
    // A legend whose link keys match none of this plot's marks selects nothing and clears nothing.
    if chosen.nonEmpty then
      val operation = if additive then SelectionOperation.Add else SelectionOperation.Replace
      accept(
        dispatch(InteractionAction.Select(Selection(chosen.toSet), operation), InputCause.Pointer)
      )

  private def emitPart(event: JavaFxPartEvent): Unit =
    partListeners.foreach { (_, listener) =>
      try listener(event)
      catch case NonFatal(_) => ()
    }

  private def emitHover(value: LinkedEmphasis[A]): Unit =
    hoverListeners.foreach { (_, listener) =>
      try listener(value)
      catch case NonFatal(_) => ()
    }

  // ---------------------------------------------------------------------------------------------
  // Reactions: tooltip, announcement, links, membership requests

  private def coverageNote(target: TargetInfo[A]): Option[String] =
    if target.entity.nonEmpty then None
    else
      controller.state.toOption.flatMap { current =>
        MemberCoverage.of(target, current.selection.entities, current.domain) match
          case MemberCoverage.Known(selected, total) => Some(s"$selected of $total selected")
          case _                                     => None
      }

  private def withCoverage(content: TargetContent, target: TargetInfo[A]): TargetContent =
    coverageNote(target).fold(content) { note =>
      content match
        case TargetContent.Text(value)         => TargetContent.Text(s"$value ($note)")
        case TargetContent.Fields(title, rows) =>
          TargetField("selected", note).fold(
            _ => content,
            row => TargetContent.Fields(title, rows :+ row)
          )
    }

  private def describeWithCoverage(target: TargetInfo[A]): String =
    val described = describeTarget match
      case Some(describe) => behavior.tooltip(target).map(_.plainText).getOrElse(describe(target))
      case None           => view.fold("")(_.describe(target, behavior))
    coverageNote(target).fold(described)(note => s"$described ($note)")

  private def react(record: EventRecord[A]): Unit =
    record.event match
      case InteractionEvent.HoverChanged(Some(target)) =>
        emitHover(LinkedEmphasis[A](entities = target.entity.toSet))
        // A new hover replaces any hover or part tooltip, including one still pending.
        hideTooltip(TooltipSource.Hover)
        hideTooltip(TooltipSource.Part)
        behavior.tooltip(target).foreach { content =>
          showTooltip(
            withCoverage(content, target),
            pointer,
            anchorOf(target),
            immediate = false,
            TooltipSource.Hover
          )
        }
      case InteractionEvent.HoverChanged(None) =>
        if hoveredPart.isEmpty then emitHover(LinkedEmphasis.none[A])
        hideTooltip(TooltipSource.Hover)
      case InteractionEvent.FocusChanged(Some(target)) =>
        spoken = describeWithCoverage(target)
        // Keyboard focus points at a mark as hover does, so linked views show it too.
        if record.stamp.cause == InputCause.Keyboard then
          emitHover(LinkedEmphasis[A](entities = target.entity.toSet))
          hideTooltip()
          behavior
            .tooltip(target)
            .map(withCoverage(_, target))
            .foreach(showTooltip(_, None, anchorOf(target), immediate = true, TooltipSource.Focus))
      case InteractionEvent.Activated(target) =>
        annotationPart(target.id).foreach(part =>
          emitPart(JavaFxPartEvent.Activated(part, record.stamp.cause))
        )
        if record.stamp.cause == InputCause.Pointer || record.stamp.cause == InputCause.Keyboard
        then
          for
            follow <- onLink
            target <- behavior.link(target)
          do
            try follow(target)
            catch case NonFatal(_) => accept(Left(InteractionError.CallbackFailed("link")))
      case InteractionEvent.MembershipRequested(request) =>
        spoken = "Retrieving members"
        resolver.foreach { service =>
          // A reply may come at once, during this delivery, or much later; it is always dispatched
          // on a later FX task, and the reducer rejects one that is no longer current.
          val deliver: MembershipReply[A] => Unit = reply =>
            Platform.runLater(() =>
              if view.nonEmpty then
                accept(
                  dispatch(
                    InteractionAction.ResolveMembers(request.target, request.id, reply),
                    InputCause.Programmatic
                  )
                )
            )
          try service.resolve(request, deliver)
          catch case NonFatal(error) => deliver(MembershipReply.Failed(error.toString))
        }
      case InteractionEvent.MembershipResolved(_, _, outcome) =>
        outcome match
          case MembershipOutcome.Complete(count) =>
            spoken = s"The $count members of the bin are applied to the selection"
          case MembershipOutcome.Unavailable(reason) => spoken = s"Members unavailable: $reason"
          case MembershipOutcome.Failed(reason)      =>
            spoken = s"Members could not be retrieved: $reason"
          case MembershipOutcome.Rejected(reason) =>
            spoken = s"Members could not be applied: $reason"
          case MembershipOutcome.Pending | MembershipOutcome.Superseded => ()
      case _ => ()

  private def anchorOf(target: TargetInfo[A]): (Double, Double) =
    geometries.get(target.id).fold((0.0, 0.0))(g => local(g.anchor.x, g.anchor.y))

  private def local(x: Double, y: Double): (Double, Double) =
    viewport.fold((x, y))(m =>
      (m.clientLeft + x * m.cssPixelsPerDevicePixel, m.clientTop + y * m.cssPixelsPerDevicePixel)
    )

  private def showTooltip(
      content: TargetContent,
      at: Option[(Double, Double)],
      anchor: (Double, Double),
      immediate: Boolean,
      source: TooltipSource
  ): Unit =
    tooltipTimer.foreach(_.stop())
    tooltipTimer = None
    tooltipSource = Some(source)
    def show(): Unit =
      tooltipTimer = None
      if view.nonEmpty then
        fill(content)
        tooltipShown = Some(content)
        val width = node.getWidth
        val limit = math.min(options.tooltipMaxWidthLogicalPx, math.max(width, 1.0))
        tooltipBox.setVisible(true)
        tooltipBox.applyCss()
        val w = math.min(limit, tooltipBox.prefWidth(-1))
        val h = tooltipBox.prefHeight(w)
        tooltipBox.resize(w, h)
        val box = TooltipLayout.place(behavior.placement, at, anchor, w, h, width, node.getHeight)
        tooltipBox.relocate(box.left, box.top)
    if immediate || behavior.tooltipDelayMs == 0 then show()
    else
      val timer = new PauseTransition(Duration.millis(behavior.tooltipDelayMs.toDouble))
      timer.setOnFinished(_ => if tooltipTimer.contains(timer) then show())
      tooltipTimer = Some(timer)
      timer.play()

  /** Content as JavaFX text nodes: never parsed, so no string can become markup. */
  private def fill(content: TargetContent): Unit =
    val text = fx(options.tooltipText)
    def line(parts: (String, Boolean)*): TextFlow =
      val flow = new TextFlow(parts.map { (value, bold) =>
        val t = new Text(value)
        t.setFill(text)
        t.setFont(Font.font("System", if bold then FontWeight.BOLD else FontWeight.NORMAL, 12))
        t
      }*)
      flow.setMaxWidth(options.tooltipMaxWidthLogicalPx - 16)
      flow
    tooltipBox.getChildren.clear()
    content match
      case TargetContent.Text(value)         => tooltipBox.getChildren.add(line(value -> false))
      case TargetContent.Fields(title, rows) =>
        title.foreach(t => tooltipBox.getChildren.add(line(t -> true)))
        rows.foreach(row =>
          tooltipBox.getChildren.add(line(row.label -> true, ("  " + row.value) -> false))
        )

  /** Hide the tooltip and cancel a pending one, whatever it shows. */
  private def hideTooltip(): Unit =
    tooltipTimer.foreach(_.stop())
    tooltipTimer = None
    tooltipSource = None
    tooltipShown = None
    tooltipBox.setVisible(false)

  /** Hide the tooltip only if `source` put it there. */
  private def hideTooltip(source: TooltipSource): Unit =
    if tooltipSource.contains(source) then hideTooltip()

object JavaFxInteractionHost:
  /** The panel's identity in the interaction state's viewport map, as the browser widget names it.
    */
  private[javafx] val panelId: SemanticId = SemanticId.unsafe(PlotRegion.Panel.value)

  /** Attach a view with the default behaviour (no tooltips or links) under selection `mode`. The
    * focused mark's accessible text is `describe(target)`.
    */
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
    build(
      view,
      InteractionBehavior.default[A].withSelection(mode),
      selection,
      JavaFxHostOptions(toleranceLogicalPx = toleranceLogicalPx),
      Some(describe),
      None,
      None,
      onError
    )

  /** Mount a view with an [[intaglio.interaction.InteractionBehavior]] — the same value the browser
    * widget reads: tooltips, links, placement, delay, hover rule, inverse emphasis, selection mode,
    * legend links and aggregate member selection.
    *
    * What the JavaFX host cannot do is refused here rather than ignored: a behaviour whose targets
    * carry links needs an `onLink` handler (a desktop node has no browser location to follow),
    * deferred aggregate members need a `resolver`, and legend links must name a legend this plot
    * draws whose entries match a mark's link key.
    */
  def mount[A](
      view: JavaFxInteractionView[A],
      behavior: InteractionBehavior[A],
      selection: Selection[A] = Selection[A](),
      options: JavaFxHostOptions = JavaFxHostOptions(),
      resolver: Option[MembershipResolver[A]] = None,
      onLink: Option[TargetLink => Unit] = None,
      onError: IntaglioError => Unit = _ => ()
  ): Either[IntaglioError, JavaFxInteractionHost[A]] =
    build(view, behavior, selection, options, None, resolver, onLink, onError)

  private def build[A](
      view: JavaFxInteractionView[A],
      behavior: InteractionBehavior[A],
      selection: Selection[A],
      options: JavaFxHostOptions,
      describe: Option[TargetInfo[A] => String],
      resolver: Option[MembershipResolver[A]],
      onLink: Option[TargetLink => Unit],
      onError: IntaglioError => Unit
  ): Either[IntaglioError, JavaFxInteractionHost[A]] =
    if !Platform.isFxApplicationThread then Left(JavaFxHostError.WrongThread)
    else if !options.toleranceLogicalPx.isFinite || options.toleranceLogicalPx < 0 then
      Left(JavaFxHostError.InvalidTolerance)
    else if !options.tooltipMaxWidthLogicalPx.isFinite || options.tooltipMaxWidthLogicalPx <= 16
    then
      Left(
        InteractionError.InvalidValue(
          "tooltip width",
          s"${options.tooltipMaxWidthLogicalPx} logical px"
        )
      )
    else
      for
        _ <- validateLinks(view, behavior, onLink)
        _ <- validateLegendLinks(view, behavior)
        _ <- behavior.validateAggregates(view.plans)
        _ <- validateResolver(view, behavior, resolver)
        initial <- InteractionState.initial(view.domain, behavior.selection, selection)
      yield new JavaFxInteractionHost(
        view,
        initial,
        behavior,
        options,
        describe,
        resolver,
        onLink,
        onError
      )

  /** A navigator when the view has a single panel with a numeric or temporal axis. */
  private[javafx] def navigatorOf[A](view: JavaFxInteractionView[A]): Option[DataWindowNavigator] =
    view.singlePlan
      .filter(_ => view.panelFrame.nonEmpty)
      .filter(_.trained.facetPanels.isEmpty)
      .flatMap { plan =>
        plan.training
          .flatMap(_ => DataWindowNavigator.of(plan, view.context).toOption)
          .filter(nav => nav.navigable._1 || nav.navigable._2)
      }

  private def validateLinks[A](
      view: JavaFxInteractionView[A],
      behavior: InteractionBehavior[A],
      onLink: Option[TargetLink => Unit]
  ): Either[IntaglioError, Unit] =
    val linked = onLink.isEmpty && view.navigation.targets.exists(g =>
      try behavior.link(g.target).nonEmpty
      catch case NonFatal(_) => false
    )
    if linked then
      Left(
        JavaFxCapabilities.refusal(
          HostCapability.TargetLinks,
          "a desktop node has no browser location to follow a target link",
          "mount with onLink = Some(link => hostServices.showDocument(link.url))"
        )
      )
    else Right(())

  private def validateResolver[A](
      view: JavaFxInteractionView[A],
      behavior: InteractionBehavior[A],
      resolver: Option[MembershipResolver[A]]
  ): Either[IntaglioError, Unit] =
    val deferred = view.plans.iterator
      .flatMap(_.groups)
      .flatMap(group => Iterator.range(0, group.size).flatMap(i => group.at(i).toOption))
      .exists(info =>
        info.entity.isEmpty && behavior.aggregates(info) == AggregateSelection.Members &&
          info.membership.capability == MembershipCapability.Deferred
      )
    if deferred && resolver.isEmpty then
      Left(
        InteractionError.InvalidValue(
          "membership resolver",
          "deferred aggregate members are selected on request; mount with a MembershipResolver"
        )
      )
    else Right(())

  /** Every legend link must name a legend this view draws, and every entry's label must be a link
    * key some mark binds in the link's space, as the browser widget requires.
    */
  private def validateLegendLinks[A](
      view: JavaFxInteractionView[A],
      behavior: InteractionBehavior[A]
  ): Either[IntaglioError, Unit] =
    val targets = view.navigation.targets.map(_.target)
    behavior.legendLinks.foldLeft[Either[IntaglioError, Unit]](Right(())) { (done, link) =>
      done.flatMap { _ =>
        val entries = view.parts.toVector.flatMap(_.parts).map(_.part).collect {
          case entry: PlotPart.LegendEntry if entry.legend == link.legend => entry
        }
        if entries.isEmpty then
          Left(
            InteractionError
              .InvalidValue("legend link", s"this plot draws no legend '${link.legend}'")
          )
        else
          entries.foldLeft[Either[IntaglioError, Unit]](Right(())) { (ok, entry) =>
            ok.flatMap { _ =>
              link.space.link(entry.label).flatMap { key =>
                Either.cond(
                  targets.exists(_.links.contains(key)),
                  (),
                  InteractionError.InvalidValue(
                    "legend link",
                    s"legend '${link.legend}' entry '${entry.label}' matches no mark's link key"
                  )
                )
              }
            }
          }
      }
    }
