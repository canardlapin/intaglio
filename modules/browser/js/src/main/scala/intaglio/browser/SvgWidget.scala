package intaglio.browser

import intaglio.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.util.control.NonFatal

/** Every DOM listener a widget registers, so disposal removes exactly what mounting added. */
private[browser] final class Listeners:
  private var entries = Vector.empty[(js.Dynamic, String, js.Function1[js.Dynamic, Unit])]

  def on(target: js.Dynamic, kind: String)(handler: js.Dynamic => Unit): Unit =
    val fn: js.Function1[js.Dynamic, Unit] = handler
    target.addEventListener(kind, fn)
    entries = entries :+ ((target, kind, fn))

  /** A non-passive listener, for handlers that must be able to prevent the default (wheel zoom). */
  def active(target: js.Dynamic, kind: String)(handler: js.Dynamic => Unit): Unit =
    val fn: js.Function1[js.Dynamic, Unit] = handler
    target.addEventListener(kind, fn, js.Dynamic.literal(passive = false))
    entries = entries :+ ((target, kind, fn))

  def size: Int = entries.length

  def clear(): Unit =
    entries.foreach((target, kind, fn) => target.removeEventListener(kind, fn))
    entries = Vector.empty

/** A typed event about a plot part (legend entry, strip, axis, title, colorbar, annotation). Parts
  * are not selectable marks; the widget reports what the reader points at and activates.
  */
enum PartEvent:
  case Hovered(part: Option[PlotPart])
  case Activated(part: PlotPart, cause: InputCause)

/** An interactive SVG plot mounted into one DOM container.
  *
  * The widget owns one [[InteractionController]] and its DOM: the plot, an overlay for hover,
  * selection and focus rings (and, under inverse emphasis, a clipped copy of the plot in its
  * original paint), a tooltip, a polite live region, and a collapsible text companion that lists
  * every target and part without giving each its own tab stop. The plot is a single tab stop;
  * arrows, Home/End and PageUp/PageDown rove focus, Enter or Space chooses, Escape clears.
  *
  * Effects stay here: following a [[TargetLink]] happens on a reader's pointer or keyboard
  * activation, never on projected or programmatic state. [[dispose]] removes every listener,
  * observer, timer, animation frame and DOM node the widget added; it is idempotent.
  */
final class SvgWidget[A] private (
    container: js.Dynamic,
    private var view: SvgWidgetView[A],
    behavior: InteractionBehavior[A],
    controller: InteractionController[A],
    origin: SemanticId,
    label: String,
    onError: IntaglioError => Unit,
    options: WidgetOptions,
    resolver: Option[MembershipResolver[A]]
):
  private val document = g.document
  private val listeners = Listeners()
  private var subscriptions = Vector.empty[InteractionSubscription]
  private var partListeners = Vector.empty[(Long, PartEvent => Unit)]
  private var nextPartListener = 0L
  private var sequence = 0L
  private var disposed = false
  private var frame: Option[js.Dynamic] = None
  private var tooltipTimer: Option[js.Dynamic] = None
  private var resizeObserver: Option[js.Dynamic] = None
  private var pointer: Option[(Double, Double)] = None
  private var hoveredPart: Option[PartTarget] = None

  /** Emphasis a linked view asked for, and emphasis this plot's own linked legend asks for. */
  private var linkedEmphasis: LinkedEmphasis[A] = LinkedEmphasis.none[A]
  private var legendEmphasis: LinkedEmphasis[A] = LinkedEmphasis.none[A]
  private var hoverListeners = Vector.empty[(Long, LinkedEmphasis[A] => Unit)]

  // ---- Data-window navigation and region gestures ----
  /** The compiled, unwindowed plan every window is taken from. */
  private var basePlan: Option[InteractionPlan[A]] = view.singlePlan

  /** The compiled view itself, shown again on reset rather than re-placed. */
  private var baseView: SvgWidgetView[A] = view
  private var unstyledView: SvgWidgetView[A] = view
  private var targetStyles = Map.empty[VisualTargetId, WidgetTargetStyle]
  private var navigator: Option[DataWindowNavigator] = SvgWidget.navigatorOf(view)

  /** The unwindowed panel frame; a window's frame is this one with the window's position ranges. */
  private var basePanelFrame: Option[DeviceFrame] = view.panelFrame
  private var window: PanelWindow = PanelWindow.full

  /** The viewport the drawn window records; a restored state viewport that differs is drawn. */
  private var drawnViewport: Option[PanelViewport] = None

  /** Undo/redo of this plot's durable state, with the `InteractionHistory` boundaries. */
  private var history: InteractionHistory[A] = InteractionHistory.empty[A]().toOption.get
  private var stateListeners = Vector.empty[(Long, InteractionState[A] => Unit)]
  private var nextStateListener = 0L
  private var pendingCause: InputCause = InputCause.Pointer
  private var magnification = 1.0
  private var pendingWindow: Option[PanelWindow] = None
  private var windowFrame: Option[js.Dynamic] = None
  private enum Drag:
    case Band(startX: Double, startY: Double, x: Double, y: Double)
    case Trace(points: Vector[(Double, Double)])

    /** Pans are absolute from the gesture's start and its frame, so coalescing loses no motion. */
    case Panning(startX: Double, startY: Double, frame: DeviceFrame)
  private var drag: Option[Drag] = None
  private var moved = false
  private val touches = scala.collection.mutable.LinkedHashMap.empty[Double, (Double, Double)]

  /** A pinch's starting finger distance, midpoint and panel frame; zoom is absolute from them. */
  private var pinch: Option[(Double, DevicePoint, DeviceFrame)] = None
  private val exportViewportChoice = element("select")
  private val exportSelectionChoice = element("select")
  private val controlError = element("p")
  private val toolbar = element("div")
  private var buttons = Vector.empty[(Option[GestureMode], js.Dynamic, Boolean)]
  private val gestureLayer = svgElement("svg")

  /** The live link this widget belongs to, if any; a widget joins at most one. */
  private[browser] var link: Option[AnyRef] = None
  private var nextHoverListener = 0L

  /** What the visible (or pending) tooltip describes, so each source hides only its own tooltip. */
  private enum TooltipSource:
    case Hover, Focus, Part
  private var tooltipSource: Option[TooltipSource] = None
  private var input: HostInput[A] = HostInput(view.picking, view.navigation, unitViewport, behavior)

  private val root = element("div")
  private val plotHost = element("div")
  private val overlay = svgElement("svg")
  private val tooltip = element("div")
  private val live = element("div")
  private val companion = element("details")
  private val companionBody = element("tbody")

  private val canvasSurface = if options.renderer == WidgetRenderer.Canvas then
    Some(new CanvasSurface[A](controlFailure, () => scheduleRedraw()))
  else None

  build()

  /** The current interaction state. */
  def state: Either[ControllerError, InteractionState[A]] = controller.state

  /** Typed interaction events (hover, focus, activation, selection), as the controller emits them.
    */
  def subscribe(
      listener: EventRecord[A] => Unit
  ): Either[ControllerError, InteractionSubscription] =
    controller.subscribe(listener)

  /** What this plot's reader is pointing at, as keys a linked view can emphasize: the hovered
    * mark's entity, or the link key of a hovered linked legend entry; empty when nothing. The
    * returned function unsubscribes.
    */
  def subscribeHover(listener: LinkedEmphasis[A] => Unit): () => Unit =
    val id = nextHoverListener
    nextHoverListener += 1
    hoverListeners = hoverListeners :+ (id -> listener)
    () => hoverListeners = hoverListeners.filterNot(_._1 == id)

  /** Emphasize the marks a linked view points at. Host-level display only: state and events are
    * untouched, so emphasis can never echo back.
    */
  def setLinkedEmphasis(value: LinkedEmphasis[A]): Unit =
    if !disposed && value != linkedEmphasis then
      linkedEmphasis = value
      scheduleRedraw()

  /** Typed plot-part events; the returned function unsubscribes. */
  def subscribeParts(listener: PartEvent => Unit): () => Unit =
    val id = nextPartListener
    nextPartListener += 1
    partListeners = partListeners :+ (id -> listener)
    () => partListeners = partListeners.filterNot(_._1 == id)

  /** Replace the selection from the application. Delivered as `Projected`: the state and the
    * overlay change, but no event is emitted (the application already knows), so linked views and
    * subscribers cannot echo it back, and no link is followed.
    */
  def setSelection(value: Selection[A]): Either[IntaglioError, Unit] =
    val result =
      dispatch(InteractionAction.Select(value, SelectionOperation.Replace), InputCause.Projected)
    // Projected input emits no event, so coverage counts in an open companion refresh here.
    if companion.open.asInstanceOf[Boolean] then fillCompanion()
    scheduleRedraw()
    result

  // ---- Saved selections, snapshots and history (Interaction 10) ----

  /** Save the current selection as `name` (an application command, recorded in history). */
  def saveSelection(name: SelectionName): Either[IntaglioError, Unit] =
    dispatch(InteractionAction.SaveSelection(name), InputCause.Programmatic)

  /** Apply the selection saved as `name` under `operation`. */
  def recallSelection(
      name: SelectionName,
      operation: SelectionOperation
  ): Either[IntaglioError, Unit] =
    dispatch(InteractionAction.RecallSelection(name, operation), InputCause.Programmatic)

  /** Save `left` combined with `right` as `into`; the current selection is unchanged. */
  def combineSelections(
      left: SelectionName,
      right: SelectionName,
      how: SetCombination,
      into: SelectionName
  ): Either[IntaglioError, Unit] =
    dispatch(InteractionAction.CombineSelections(left, right, how, into), InputCause.Programmatic)

  def deleteSelection(name: SelectionName): Either[IntaglioError, Unit] =
    dispatch(InteractionAction.DeleteSelection(name), InputCause.Programmatic)

  /** The durable state (selection, saved selections, viewport, mode) as a versioned snapshot. */
  def snapshot: Either[IntaglioError, InteractionSnapshot] =
    controller.state.map(InteractionSnapshot.capture)

  /** Restore a snapshot taken of this plot (possibly in an earlier session): checked against what
    * is shown now, refused with a typed `SnapshotError` when it cannot mean the same thing, and
    * applied as one recorded change that follows no link and asks no resolver.
    */
  def restore(snapshot: InteractionSnapshot): Either[IntaglioError, Unit] =
    for
      current <- controller.state
      resolved <- InteractionSnapshot.resolve(snapshot, current.domain)
      _ <- dispatch(InteractionAction.RestoreSnapshot(resolved), InputCause.Programmatic)
    yield scheduleRedraw()

  def canUndo: Boolean = history.canUndo
  def canRedo: Boolean = history.canRedo

  /** Undo this plot's last recorded change; `false` when there is nothing to undo. */
  def undo(): Either[IntaglioError, Boolean] = undo(InputCause.Programmatic)
  def redo(): Either[IntaglioError, Boolean] = redo(InputCause.Programmatic)

  private def undo(cause: InputCause): Either[IntaglioError, Boolean] = move(cause, history.undo)
  private def redo(cause: InputCause): Either[IntaglioError, Boolean] = move(cause, history.redo)

  private def move(
      cause: InputCause,
      step: InteractionState[A] => Option[(InteractionAction[A], InteractionHistory[A])]
  ): Either[IntaglioError, Boolean] =
    controller.state.flatMap { current =>
      step(current) match
        case None                  => Right(false)
        case Some((action, after)) =>
          dispatch(action, cause, record = false).map { _ =>
            history = after
            scheduleRedraw()
            true
          }
    }

  /** Called with the state after every redraw, including changes projected in by linked views
    * (which emit no events). Returns an unsubscribe function.
    */
  def subscribeState(listener: InteractionState[A] => Unit): () => Unit =
    val id = nextStateListener
    nextStateListener += 1
    stateListeners = stateListeners :+ (id -> listener)
    scheduleRedraw()
    () => stateListeners = stateListeners.filterNot(_._1 == id)

  /** Replace application-supplied target paint, or clear it with an empty map. This changes no
    * selection, viewport, or events. Invalid targets/styles fail atomically. Explicit data/view
    * updates clear these view-bound styles; ordinary pan/zoom retains them.
    */
  def setTargetStyles(styles: Map[VisualTargetId, WidgetTargetStyle]): Either[IntaglioError, Unit] =
    if disposed then Left(ControllerError.Disposed)
    else
      unstyledView.withTargetStyles(styles).map { painted =>
        targetStyles = styles
        paintView(painted, keepTooltip = true)
      }

  /** Show a new view of the same plot (new data or a new render size). Selection is reconciled by
    * entity key; entities that no longer exist are dropped and reported in a `Reconciled` event. A
    * view whose plan has the current revision (a resize or restyle of the same data) keeps the
    * state as it is.
    */
  def update(next: SvgWidgetView[A]): Either[IntaglioError, Unit] =
    if disposed then Left(ControllerError.Disposed)
    else if next.idPrefix != view.idPrefix then
      Left(
        InteractionError.InvalidValue("widget update", "a view must keep the widget's id prefix")
      )
    else
      for
        // The new view must meet every requirement mounting checks, before any state changes.
        _ <- SvgWidget.validateLegendLinks(next, behavior)
        _ <- behavior.validateAggregates(next.plans)
        _ <- SvgWidget.validateResolver(next, behavior, resolver)
        domain <- InteractionDomain(next.plans, next.revision)
        current <- controller.state
        // The same plan revision is the same data at another size or look: target identities are
        // unchanged, so the state stands. A new revision is new data and is reconciled.
        _ <-
          if current.domain.revision == domain.revision then Right(())
          else
            controller
              .replaceDomain(
                stamp(current, InputCause.Programmatic),
                domain,
                MissingEntityPolicy.Drop
              )
              .map(_ => ())
      yield
        setHoveredPart(None)
        emitHover(LinkedEmphasis.none[A])
        legendEmphasis = LinkedEmphasis.none[A]
        // A new view from the application is the new compiled base; its window is full.
        cancelGesture()
        clearGestureLayer()
        // A pointer still held open its gesture in the state; end it, or the mode cannot change.
        if controller.state.exists(_.gesture.nonEmpty) then
          dispatch(InteractionAction.EndGesture(true), InputCause.Programmatic, record = false).left
            .foreach(report)
        basePlan = next.singlePlan
        baseView = next
        unstyledView = next
        targetStyles = Map.empty
        navigator = SvgWidget.navigatorOf(next)
        basePanelFrame = next.panelFrame
        window = PanelWindow.full
        drawnViewport = None
        // New data clears history, as InteractionHistory does on a domain replacement.
        history = InteractionHistory.empty[A]().toOption.get
        pendingWindow = None
        // The state's recorded viewport and a navigation-only mode follow the new full view.
        dispatch(
          InteractionAction.SetViewport(SvgWidget.panelId, None),
          InputCause.Programmatic,
          record = false
        ).left
          .foreach(report)
        if navigator.isEmpty && (mode == GestureMode.Pan || mode == GestureMode.ZoomRectangle) then
          dispatch(
            InteractionAction.SetGestureMode(GestureMode.Inspect),
            InputCause.Programmatic,
            record = false
          ).left
            .foreach(report)
        refreshToolbar()
        view = next
        applyMagnification()
        input = HostInput(view.picking, view.navigation, unitViewport, behavior)
        live.textContent = ""
        hideTooltip()
        renderPlot()
        if companion.open.asInstanceOf[Boolean] then fillCompanion()
        scheduleRedraw()

  /** The observation keys this plot draws as marks of their own; a histogram draws none. */
  def drawnEntities: Set[EntityKey[A]] =
    view.navigation.targets.flatMap(_.target.entity).toSet

  /** The observation keys a reader can select in this plot: those it draws as marks, and the
    * members of aggregates in [[AggregateSelection.Members]] mode. For exact membership those are
    * the aggregates' members; for deferred membership, which is unknown until asked, every source
    * observation of the plot (so it also includes rows no mark draws, such as non-finite values). A
    * linked group takes a reader's change in these keys only: keys merely projected into a plot
    * whose bins are selected as bins never flow back out of it, but in Members mode the bins'
    * observations are the reader's, so clearing such a plot clears them in the group.
    */
  def selectableEntities: Set[EntityKey[A]] =
    val members = view.navigation.targets.map(_.target).filter { info =>
      info.entity.isEmpty && behavior.aggregates(info) == AggregateSelection.Members
    }
    val exact = members.iterator
      .filter(_.membership.capability == MembershipCapability.Exact)
      .flatMap { info =>
        view.plans.find(_.id == info.id.plan).iterator.flatMap { plan =>
          info.membership.exactKeys(plan.sourceRevision).toOption.iterator.flatten
        }
      }
    val deferredPlans = members
      .filter(_.membership.capability == MembershipCapability.Deferred)
      .map(_.id.plan)
      .toSet
    val deferred = view.plans.iterator.filter(p => deferredPlans(p.id)).flatMap(_.sourceEntities)
    drawnEntities ++ exact ++ deferred

  /** "k of n selected" for an aggregate whose members are known exactly. */
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

  /** Show the data window `value` (in panel position units; [[PanelWindow.full]] resets). The
    * statistics are not recomputed; marks, targets and selection stay. Refused when the plot cannot
    * be navigated (faceted, flipped, or with no numeric or temporal axis).
    */
  def navigate(value: PanelWindow): Either[IntaglioError, Unit] =
    navigator match
      case None      => Left(InteractionError.UnsupportedCapability("navigating this plot"))
      case Some(nav) => nav.normalize(value).flatMap(showWindow(_, InputCause.Programmatic))

  /** The data window currently shown. */
  def currentWindow: PanelWindow = window

  /** Export an explicit viewport and selection choice. Original uses this data revision before
    * navigation; hover/focus decorations are never exported. No state or event is changed.
    */
  def exportPng(
      viewport: ExportViewport,
      selection: ExportSelection,
      scale: Double = 1.0
  ): js.Promise[Either[WidgetExportError, String]] =
    val snapshot = for
      current <- state
      source <- viewport match
        case ExportViewport.Current  => Right(view)
        case ExportViewport.Original =>
          baseView.withTargetStyles(targetStyles)
    yield (
      source,
      if selection == ExportSelection.Include then current.selection else Selection[A]()
    )
    snapshot match
      case Left(error) => js.Promise.resolve(Left(WidgetExportError.Failed(error.message)))
      case Right((source, chosen)) => SvgWidgetExport.png(source, chosen, scale)

  /** Fullscreen is a reader-activated browser capability; refusal remains an explicit result. */
  def toggleFullscreen(): js.Promise[Either[IntaglioError, Unit]] =
    def unavailable(reason: String): Either[IntaglioError, Unit] =
      Left(InteractionError.UnsupportedCapability(s"fullscreen: $reason"))
    if disposed then js.Promise.resolve(unavailable("widget is disposed"))
    else
      val target = if document.fullscreenElement == root then document else root
      val method =
        if document.fullscreenElement == root then "exitFullscreen" else "requestFullscreen"
      if js.typeOf(target.selectDynamic(method)) != "function" then
        js.Promise.resolve(unavailable("this browser does not provide the fullscreen API"))
      else
        try
          target
            .applyDynamic(method)()
            .asInstanceOf[js.Promise[Unit]]
            .`then`[Either[IntaglioError, Unit]](
              (_: Unit) => Right(()),
              (error: Any) => unavailable(s"request refused: $error")
            )
        catch case NonFatal(error) => js.Promise.resolve(unavailable(error.getMessage))

  /** Whole-scene magnification: draw the plot `factor` times its compiled size, marks and text
    * together, which data-window navigation never does. Picking follows the drawn size.
    */
  def magnify(factor: Double): Either[IntaglioError, Unit] =
    if !factor.isFinite || factor <= 0 then
      Left(InteractionError.InvalidValue("magnification", s"$factor"))
    else
      magnification = factor
      applyMagnification()
      hideTooltip()
      scheduleRedraw()
      Right(())

  /** 1 is the widget's default responsive width; any other factor is a fixed CSS width of `factor`
    * times the compiled width (it follows the compiled width across [[update]]).
    */
  private def applyMagnification(): Unit =
    plotHost.style.width = if magnification == 1.0 then "" else s"${view.width * magnification}px"

  def isDisposed: Boolean = disposed

  /** Listeners currently registered on the DOM; zero after [[dispose]]. */
  def domListenerCount: Int = listeners.size

  def dispose(): Unit =
    if !disposed then
      // Linked views stop showing what this plot pointed at.
      emitHover(LinkedEmphasis.none[A])
      disposed = true
      canvasSurface.foreach(_.dispose())
      listeners.clear()
      subscriptions.foreach(_.cancel())
      subscriptions = Vector.empty
      partListeners = Vector.empty
      hoverListeners = Vector.empty
      resizeObserver.foreach(_.disconnect())
      resizeObserver = None
      frame.foreach(handle => g.cancelAnimationFrame(handle))
      frame = None
      windowFrame.foreach(handle => g.cancelAnimationFrame(handle))
      windowFrame = None
      tooltipTimer.foreach(handle => g.clearTimeout(handle))
      tooltipTimer = None
      controller.dispose()
      if !js.isUndefined(root.parentNode) && root.parentNode != null then
        root.parentNode.removeChild(root)

  // ---------------------------------------------------------------------------------------------
  // DOM construction

  private def element(tag: String): js.Dynamic = document.createElement(tag)
  private def svgElement(tag: String): js.Dynamic =
    document.createElementNS("http://www.w3.org/2000/svg", tag)

  private def id(local: String): String = s"${view.idPrefix}-$local"

  private def build(): Unit =
    SvgWidget.installStyle(document)
    root.className = "intaglio-widget"
    if options.appearance != WidgetAppearance.default then
      options.appearance.properties.foreach((name, value) => root.style.setProperty(name, value))
    root.setAttribute("data-intaglio-widget", view.idPrefix)
    plotHost.className = "intaglio-plot"
    plotHost.tabIndex = 0
    plotHost.setAttribute("role", "application")
    plotHost.setAttribute("aria-roledescription", "interactive plot")
    plotHost.setAttribute("aria-label", label)
    plotHost.setAttribute("aria-describedby", id("live"))
    overlay.setAttribute("class", "intaglio-overlay")
    overlay.setAttribute("aria-hidden", "true")
    overlay.setAttribute("focusable", "false")
    tooltip.className = "intaglio-tooltip"
    tooltip.id = id("tooltip")
    tooltip.setAttribute("role", "tooltip")
    tooltip.hidden = true
    live.className = "intaglio-live"
    live.id = id("live")
    live.setAttribute("aria-live", "polite")
    companion.className = "intaglio-companion"
    val summary = element("summary")
    summary.textContent = "Data and plot parts as a table"
    val table = element("table")
    val head = element("thead")
    val headRow = element("tr")
    Vector("Kind", "Description").foreach { text =>
      val cell = element("th")
      cell.setAttribute("scope", "col")
      cell.textContent = text
      headRow.appendChild(cell)
    }
    head.appendChild(headRow)
    table.appendChild(head)
    table.appendChild(companionBody)
    companion.appendChild(summary)
    companion.appendChild(table)
    plotHost.appendChild(overlay)
    gestureLayer.setAttribute("class", "intaglio-gesture")
    gestureLayer.setAttribute("aria-hidden", "true")
    plotHost.appendChild(gestureLayer)
    buildToolbar()
    root.appendChild(toolbar)
    root.setAttribute("data-toolbar-position", options.toolbarPosition.toString)
    root.setAttribute("data-toolbar-visibility", options.toolbarVisibility.toString)
    options.sizing match
      case WidgetSizing.Responsive   => ()
      case WidgetSizing.Fixed(width) => root.style.width = s"${width}px"
    controlError.setAttribute("role", "alert")
    controlError.hidden = true
    root.appendChild(controlError)
    root.appendChild(plotHost)
    root.appendChild(tooltip)
    root.appendChild(live)
    root.appendChild(companion)
    if options.toolbarPosition == ToolbarPosition.Bottom then root.insertBefore(toolbar, companion)
    container.appendChild(root)
    renderPlot()
    wire()

  /** Insert the plot markup beneath the overlay, which keeps the plot's viewBox. */
  private def renderPlot(): Unit =
    val previous = plotHost.querySelector(":scope > .intaglio-base")
    if previous != null then plotHost.removeChild(previous)
    canvasSurface match
      case Some(surface) =>
        plotHost.insertBefore(surface.base, overlay)
        plotHost.insertBefore(surface.emphasis, overlay)
        surface.update(view)
      case None =>
        val holder = element("div")
        holder.innerHTML = view.markup
        val svg = holder.firstElementChild
        svg.classList.add("intaglio-base")
        plotHost.insertBefore(svg, overlay)
    overlay.setAttribute("viewBox", s"0 0 ${view.width} ${view.height}")
    gestureLayer.setAttribute("viewBox", s"0 0 ${view.width} ${view.height}")

  private def fillCompanion(): Unit =
    companionBody.textContent = ""
    val marks = view.navigation.targets.map { g =>
      val text = view.describe(g.target, behavior)
      ("mark", coverageNote(g.target).fold(text)(note => s"$text ($note)"))
    }
    (marks ++ view.parts.parts.map(part => ("part", part.part.describe))).foreach { (kind, text) =>
      val row = element("tr")
      val k = element("td")
      k.textContent = kind
      val d = element("td")
      d.textContent = text
      row.appendChild(k)
      row.appendChild(d)
      companionBody.appendChild(row)
    }

  // ---------------------------------------------------------------------------------------------
  // Input

  private def wire(): Unit =
    listeners.on(plotHost, "pointermove") { event =>
      pointer = Some((event.clientX.asInstanceOf[Double], event.clientY.asInstanceOf[Double]))
      if !gestureMove(event) then
        withInput(input.pointer(_, PointerInput.Move(pointer.get._1, pointer.get._2)))
        hoverPart()
    }
    listeners.on(plotHost, "pointerleave") { _ =>
      pointer = None
      withInput(input.pointer(_, PointerInput.Leave))
      setHoveredPart(None)
    }
    listeners.on(plotHost, "pointerdown") { event =>
      if event.button.asInstanceOf[Int] == 0 then
        plotHost.focus(js.Dynamic.literal(preventScroll = true))
        // Capture so the release reaches the plot even outside it; otherwise a gesture could
        // stay open and refuse every later press.
        try plotHost.setPointerCapture(event.pointerId)
        catch case NonFatal(_) => ()
        withInput(input.pointer(_, PointerInput.Press))
        gestureStart(event)
    }
    listeners.on(plotHost, "pointercancel") { _ =>
      cancelGesture()
      withInput(input.pointer(_, PointerInput.Cancel))
    }
    listeners.on(plotHost, "lostpointercapture") { event =>
      touches.remove(event.pointerId.asInstanceOf[Double])
      if drag.nonEmpty then cancelGesture()
      withInput(input.pointer(_, PointerInput.Cancel))
    }
    listeners.on(plotHost, "pointerup") { event =>
      if event.button.asInstanceOf[Int] == 0 then
        gestureEnd(event)
        withInput(input.pointer(_, PointerInput.Release))
    }
    listeners.active(plotHost, "wheel") { event => wheel(event) }
    listeners.on(plotHost, "click") { event =>
      // A drag ends in a click event; it is the end of the drag, not a click on a mark.
      if moved then
        moved = false
        event.stopImmediatePropagation()
      else click(event)
    }
    listeners.on(plotHost, "keydown") { event =>
      val additive = modifier(event)
      val key = event.key.asInstanceOf[String] match
        case "ArrowLeft"   => Some(KeyInput.Arrow(NavigationDirection.Left))
        case "ArrowRight"  => Some(KeyInput.Arrow(NavigationDirection.Right))
        case "ArrowUp"     => Some(KeyInput.Arrow(NavigationDirection.Up))
        case "ArrowDown"   => Some(KeyInput.Arrow(NavigationDirection.Down))
        case "Home"        => Some(KeyInput.First)
        case "End"         => Some(KeyInput.Last)
        case "PageUp"      => Some(KeyInput.Previous)
        case "PageDown"    => Some(KeyInput.Next)
        case "Enter" | " " => Some(KeyInput.Choose(additive))
        case "Escape"      => Some(KeyInput.Escape)
        case _             => None
      // Ctrl, Meta or Alt with these keys is the browser's own zoom; leave it to the browser.
      val modified = event.ctrlKey.asInstanceOf[Boolean] || event.metaKey.asInstanceOf[Boolean] ||
        event.altKey.asInstanceOf[Boolean]
      val navigation = event.key.asInstanceOf[String] match
        case _ if modified || navigator.isEmpty => None
        case "+" | "="                          => Some(() => zoomCentre(0.8))
        case "-" | "_"                          => Some(() => zoomCentre(1.25))
        case "0"                                =>
          Some(() => showWindow(PanelWindow.full, InputCause.Keyboard).left.foreach(report))
        case _ => None
      val keyName = event.key.asInstanceOf[String].toLowerCase
      val command = event.ctrlKey.asInstanceOf[Boolean] || event.metaKey.asInstanceOf[Boolean]
      val historyKey =
        if command && keyName == "z" && !event.shiftKey.asInstanceOf[Boolean] then Some(true)
        else if command && (keyName == "y" || (keyName == "z" && event.shiftKey
            .asInstanceOf[Boolean]))
        then Some(false)
        else None
      if historyKey.nonEmpty && drag.isEmpty then
        event.preventDefault()
        (if historyKey.contains(true) then undo(InputCause.Keyboard)
         else redo(InputCause.Keyboard)).left
          .foreach(report)
      else if drag.nonEmpty && event.key.asInstanceOf[String] == "Escape" then
        // Escape first abandons a drag in progress; a second Escape clears the selection.
        event.preventDefault()
        cancelGesture()
        withInput(
          input
            .key(_, KeyInput.Escape)
            .map(_.filterNot(_.action.isInstanceOf[InteractionAction.Select[?]]))
        )
      else if navigation.nonEmpty && navigator.nonEmpty then
        event.preventDefault()
        navigation.foreach(_())
      else
        key.foreach { value =>
          event.preventDefault()
          withInput(input.key(_, value))
        }
    }
    listeners.on(plotHost, "focus") { _ => scheduleRedraw() }
    listeners.on(plotHost, "blur") { _ =>
      if controller.state.toOption.forall(_.hover.isEmpty) && hoveredPart.isEmpty then
        emitHover(LinkedEmphasis.none[A])
      hideTooltip()
      scheduleRedraw()
    }
    listeners.on(companion, "toggle") { _ =>
      if companion.open.asInstanceOf[Boolean] then fillCompanion()
      else companionBody.textContent = ""
    }
    listeners.on(g.window, "resize") { _ => scheduleRedraw() }
    controller.subscribe(react).foreach(sub => subscriptions = subscriptions :+ sub)
    if !js.isUndefined(g.ResizeObserver) then
      val callback: js.Function1[js.Any, Unit] = _ =>
        hideTooltip()
        scheduleRedraw()
      val observer = js.Dynamic.newInstance(g.ResizeObserver)(callback)
      observer.observe(plotHost)
      resizeObserver = Some(observer)

  private def modifier(event: js.Dynamic): Boolean =
    event.shiftKey.asInstanceOf[Boolean] || event.ctrlKey.asInstanceOf[Boolean] ||
      event.metaKey.asInstanceOf[Boolean]

  private def click(event: js.Dynamic): Unit =
    val x = event.clientX.asInstanceOf[Double]
    val y = event.clientY.asInstanceOf[Double]
    val additive = modifier(event)
    val handled = withInput(input.pointer(_, PointerInput.Click(x, y, additive)))
    if !handled then
      hoveredPart.foreach { p =>
        emitPart(PartEvent.Activated(p.part, InputCause.Pointer))
        legendLink(p.part).foreach(chooseLegend(_, additive))
      }

  // ---------------------------------------------------------------------------------------------
  // Region gestures, panning, zooming

  private def mode: GestureMode =
    controller.state.toOption.fold(GestureMode.Inspect)(_.gestureMode)

  /** Client to device coordinates, unclamped, so a drag may leave the plot. */
  private def device(clientX: Double, clientY: Double): Option[(Double, Double)] =
    viewport.toOption.map { fitted =>
      (
        (clientX - fitted.clientLeft) / fitted.cssPixelsPerDevicePixel,
        (clientY - fitted.clientTop) / fitted.cssPixelsPerDevicePixel
      )
    }

  private def at(event: js.Dynamic): Option[(Double, Double)] =
    device(event.clientX.asInstanceOf[Double], event.clientY.asInstanceOf[Double])

  private def gestureStart(event: js.Dynamic): Unit =
    moved = false
    at(event).foreach { start =>
      if event.pointerType.asInstanceOf[String] == "touch" then
        touches(event.pointerId.asInstanceOf[Double]) = start
        if touches.size == 2 then
          drag = None
          clearGestureLayer()
          val (distance, (mx, my)) = pinchState
          pinch =
            if navigator.isEmpty then None
            else panelFrameNow.map(frame => (distance, DevicePoint(mx, my), frame))
      if touches.size < 2 then
        drag = mode match
          case GestureMode.Rectangle | GestureMode.ZoomRectangle =>
            Some(Drag.Band(start._1, start._2, start._1, start._2))
          case GestureMode.Lasso                     => Some(Drag.Trace(Vector(start)))
          case GestureMode.Pan if navigator.nonEmpty =>
            panelFrameNow.map(frame => Drag.Panning(start._1, start._2, frame))
          case _ => None
    }

  private def pinchState: (Double, (Double, Double)) =
    val Vector(a, b) = touches.values.toVector.take(2)
    (math.hypot(a._1 - b._1, a._2 - b._2), ((a._1 + b._1) / 2, (a._2 + b._2) / 2))

  /** Advance a drag or pinch; true when the move belonged to one. */
  private def gestureMove(event: js.Dynamic): Boolean =
    val here = at(event)
    if event.pointerType.asInstanceOf[String] == "touch" && touches.contains(
        event.pointerId.asInstanceOf[Double]
      )
    then here.foreach(p => touches(event.pointerId.asInstanceOf[Double]) = p)
    (pinch, here) match
      case (Some((distance, mid, frame)), _) if touches.size == 2 =>
        val (nextDistance, _) = pinchState
        moved = true
        navigator.foreach(nav =>
          requestWindow(nav.zoom(frame, mid, distance / math.max(nextDistance, 1e-6)))
        )
        true
      case (_, Some((x, y))) =>
        drag match
          case Some(Drag.Band(sx, sy, _, _)) =>
            moved = moved || math.hypot(x - sx, y - sy) > 3
            drag = Some(Drag.Band(sx, sy, x, y))
            drawBand(sx, sy, x, y)
            true
          case Some(Drag.Trace(points)) =>
            val (lx, ly) = points.last
            if math.hypot(x - lx, y - ly) > 2 then
              moved = true
              drag = Some(Drag.Trace(points :+ (x, y)))
              drawTrace(points :+ (x, y))
            true
          case Some(Drag.Panning(sx, sy, frame)) =>
            moved = moved || math.hypot(x - sx, y - sy) > 1
            navigator.foreach(nav => requestWindow(nav.pan(frame, x - sx, y - sy)))
            true
          case None => false
      case _ => drag.nonEmpty

  private def gestureEnd(event: js.Dynamic): Unit =
    touches.remove(event.pointerId.asInstanceOf[Double])
    if touches.size < 2 then pinch = None
    val finished = drag
    drag = None
    clearGestureLayer()
    finished.foreach {
      case Drag.Band(sx, sy, x, y) if moved =>
        if mode == GestureMode.ZoomRectangle then
          for
            nav <- navigator
            frame <- panelFrameNow
          do requestWindow(nav.rectangle(frame, DevicePoint(sx, sy), DevicePoint(x, y)))
        else
          PickArea
            .rectangle(math.min(sx, x), math.min(sy, y), math.max(sx, x), math.max(sy, y))
            .foreach(area => sweep(area, event))
      case Drag.Trace(points) if moved && points.size >= 3 =>
        PickArea.lasso(points.map((x, y) => DevicePoint(x, y))).foreach(area => sweep(area, event))
      case _ => ()
    }

  /** Select what a band or lasso covers: Shift adds, Alt subtracts, otherwise it replaces. */
  private def sweep(area: PickArea, event: js.Dynamic): Unit =
    val operation =
      if event.altKey.asInstanceOf[Boolean] then SelectionOperation.Subtract
      else if event.shiftKey.asInstanceOf[Boolean] then SelectionOperation.Add
      else SelectionOperation.Replace
    withInput(state => Right(input.region(state, area, AreaRule.CenterInside, operation)))

  private def cancelGesture(): Unit =
    drag = None
    pinch = None
    touches.clear()
    clearGestureLayer()

  private def wheel(event: js.Dynamic): Unit =
    // The page scrolls unless the reader is working in the plot: it has focus, or Ctrl is held
    // (a trackpad pinch arrives as a Ctrl-wheel).
    val engaged = event.ctrlKey.asInstanceOf[Boolean] || document.activeElement == plotHost
    // Lines and pages (deltaMode 1 and 2) are converted to pixels.
    val delta = event.deltaY.asInstanceOf[Double] * (event.deltaMode.asInstanceOf[Int] match
      case 1 => 16.0
      case 2 => view.height.toDouble
      case _ => 1.0)
    // Zooming out of the full view changes nothing; the page scrolls instead.
    val idle = delta == 0 || (delta > 0 && targetWindow.isFull)
    if navigator.nonEmpty && engaged && !idle then
      event.preventDefault()
      val factor = math.exp(math.max(-0.5, math.min(0.5, delta * 0.002)))
      for
        point <- at(event)
        nav <- navigator
        frame <- panelFrameNow
      do requestWindow(nav.zoom(frame, DevicePoint(point._1, point._2), factor))

  private def zoomCentre(factor: Double): Unit =
    for
      nav <- navigator
      frame <- panelFrameNow
    do
      val centre = DevicePoint(frame.x + frame.width / 2, frame.y + frame.height / 2)
      requestWindow(nav.zoom(frame, centre, factor), InputCause.Keyboard)

  /** The window the reader last asked for: one waiting for its frame, else the one shown. */
  private def targetWindow: PanelWindow = pendingWindow.getOrElse(window)

  /** The panel frame of [[targetWindow]], computed without drawing it, so increments (wheel
    * notches, keys, gesture starts) build on the window last asked for and are still coalesced. A
    * re-windowed panel keeps its device frame and spans exactly its window's positions.
    */
  private def panelFrameNow: Option[DeviceFrame] =
    val target = targetWindow
    def range(value: Option[(Double, Double)], full: Interval) =
      value.flatMap((lo, hi) => Interval(lo, hi).toOption).getOrElse(full)
    basePanelFrame.map(base =>
      base.copy(xScale = range(target.x, base.xScale), yScale = range(target.y, base.yScale))
    )

  /** Coalesce window changes to one re-windowing per animation frame. */
  private def requestWindow(value: PanelWindow, cause: InputCause = InputCause.Pointer): Unit =
    pendingWindow = Some(value)
    pendingCause = cause
    if windowFrame.isEmpty && !disposed then
      val callback: js.Function1[Double, Unit] = _ =>
        windowFrame = None
        pendingWindow.foreach { next =>
          showWindow(next, pendingCause).left.foreach(report)
        }
      windowFrame = Some(g.requestAnimationFrame(callback))

  /** Re-window the base plan and show it. The plan's identity and revision are unchanged, so the
    * interaction state (selection, focus) stands; the viewport is recorded in it.
    */
  private def showWindow(
      value: PanelWindow,
      cause: InputCause,
      record: Boolean = true
  ): Either[IntaglioError, Unit] =
    // A window shown now supersedes one still waiting for its animation frame.
    pendingWindow = None
    if disposed then Left(ControllerError.Disposed)
    else if value == window then Right(())
    else
      for
        nav <- navigator.toRight(InteractionError.UnsupportedCapability("navigating this plot"))
        next <-
          if value.isFull then Right(baseView)
          else
            for
              windows <- nav.windows(value)
              original <- basePlan.toRight(
                InteractionError.UnsupportedCapability("navigating a composed figure")
              )
              plan <- InteractionCompiler.rezoom(original, windows._1, windows._2)
              compiled <- SvgWidgetView.compile(
                plan,
                view.context,
                view.idPrefix,
                view.title,
                view.fonts,
                view.policy
              )
            yield compiled
        _ <- swap(next)
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
        _ = drawnViewport = recorded
        _ <- dispatch(
          InteractionAction.SetViewport(SvgWidget.panelId, recorded),
          cause,
          record
        )
      yield ()

  /** Show `next` (the same plan at another window) without touching state. Nothing changes unless
    * the current target styles also apply to it, so the window recorded by the caller is always the
    * one painted.
    */
  private def swap(next: SvgWidgetView[A]): Either[IntaglioError, Unit] =
    next.withTargetStyles(targetStyles).map { painted =>
      unstyledView = next
      paintView(painted)
    }

  /** A restyle keeps every target where it was, so a visible tooltip stays; a new window moves
    * them, so it goes.
    */
  private def paintView(next: SvgWidgetView[A], keepTooltip: Boolean = false): Unit =
    if !keepTooltip then hideTooltip()
    view = next
    input = HostInput(view.picking, view.navigation, unitViewport, behavior)
    renderPlot()
    if companion.open.asInstanceOf[Boolean] then fillCompanion()
    scheduleRedraw()

  private def clearGestureLayer(): Unit = gestureLayer.textContent = ""

  private def drawBand(x0: Double, y0: Double, x1: Double, y1: Double): Unit =
    clearGestureLayer()
    val rect = svgElement("rect")
    rect.setAttribute("x", SvgWidget.fmt(math.min(x0, x1)))
    rect.setAttribute("y", SvgWidget.fmt(math.min(y0, y1)))
    rect.setAttribute("width", SvgWidget.fmt(math.abs(x1 - x0)))
    rect.setAttribute("height", SvgWidget.fmt(math.abs(y1 - y0)))
    rect.setAttribute(
      "class",
      if mode == GestureMode.ZoomRectangle then "intaglio-band-zoom" else "intaglio-band"
    )
    gestureLayer.appendChild(rect)

  private def drawTrace(points: Vector[(Double, Double)]): Unit =
    clearGestureLayer()
    val line = svgElement("polygon")
    line.setAttribute(
      "points",
      points.map((x, y) => s"${SvgWidget.fmt(x)},${SvgWidget.fmt(y)}").mkString(" ")
    )
    line.setAttribute("class", "intaglio-band")
    gestureLayer.appendChild(line)

  // ---------------------------------------------------------------------------------------------
  // Toolbar

  private def modeButtons: Vector[(GestureMode, String, Boolean)] = Vector(
    (GestureMode.Inspect, "Inspect", false),
    (GestureMode.Rectangle, "Select area", false),
    (GestureMode.Lasso, "Lasso", false),
    (GestureMode.Pan, "Pan", true),
    (GestureMode.ZoomRectangle, "Zoom to area", true)
  )

  private def buildToolbar(): Unit =
    toolbar.className = "intaglio-toolbar"
    toolbar.setAttribute("role", "toolbar")
    toolbar.setAttribute("aria-label", s"$label controls")
    val modeButtonsBuilt = modeButtons
      .filter { (value, _, _) =>
        options.controls.contains(WidgetControl.valueOf(value.toString))
      }
      .map { (value, text, needsNavigation) =>
        val button = element("button")
        button.setAttribute("type", "button")
        button.textContent = text
        button.setAttribute("data-mode", value.toString)
        listeners.on(button, "click") { _ =>
          dispatch(InteractionAction.SetGestureMode(value), InputCause.Pointer).left.foreach(report)
          refreshToolbar()
        }
        toolbar.appendChild(button)
        (Some(value), button, needsNavigation)
      }
    val reset = element("button")
    reset.setAttribute("type", "button")
    reset.textContent = "Reset view"
    reset.setAttribute("data-action", "reset")
    listeners.on(reset, "click") { _ =>
      showWindow(PanelWindow.full, InputCause.Pointer).left.foreach(report)
    }
    val resetButtons = if options.controls.contains(WidgetControl.Reset) then
      toolbar.appendChild(reset)
      Vector((None, reset, true))
    else Vector.empty
    buttons = modeButtonsBuilt ++ resetButtons
    def extra(control: WidgetControl, text: String)(action: => Unit): Unit =
      if options.controls.contains(control) then
        val button = element("button")
        button.setAttribute("type", "button")
        button.setAttribute("data-action", control.toString.toLowerCase)
        button.textContent = text
        listeners.on(button, "click") { _ => action }
        toolbar.appendChild(button)
        buttons = buttons :+ ((None, button, false))
    extra(WidgetControl.Fullscreen, "Fullscreen") {
      toggleFullscreen().`then`[Unit]((result: Either[IntaglioError, Unit]) =>
        result.left.foreach(controlFailure)
      )
      ()
    }
    if options.controls.contains(WidgetControl.Download) then
      def choice(select: js.Dynamic, label: String, values: Vector[(String, String)]): Unit =
        select.setAttribute("aria-label", label)
        values.foreach { (value, text) =>
          val option = element("option")
          option.value = value
          option.textContent = text
          select.appendChild(option)
        }
        toolbar.appendChild(select)
      choice(
        exportViewportChoice,
        "PNG viewport",
        Vector("Current" -> "Current view", "Original" -> "Original view")
      )
      choice(
        exportSelectionChoice,
        "PNG selection",
        Vector("Include" -> "Include selection", "Omit" -> "Omit selection")
      )
    extra(WidgetControl.Download, "Download PNG") {
      controlError.hidden = true
      exportPng(
        ExportViewport.valueOf(exportViewportChoice.value.asInstanceOf[String]),
        ExportSelection.valueOf(exportSelectionChoice.value.asInstanceOf[String])
      ).`then`[Unit]((result: Either[WidgetExportError, String]) =>
        if !disposed then
          result match
            case Left(error) => controlFailure(error)
            case Right(data) =>
              val anchor = element("a")
              anchor.href = data
              anchor.download = "intaglio-plot.png"
              document.body.appendChild(anchor)
              anchor.click()
              anchor.remove()
      )
      ()
    }
    // One tab stop for the whole toolbar; arrows, Home and End move between its buttons.
    listeners.on(toolbar, "keydown") { event =>
      val visible = buttons.map(_._2).filterNot(_.hidden.asInstanceOf[Boolean])
      val here = visible.indexWhere(_ == document.activeElement)
      val next = event.key.asInstanceOf[String] match
        case "ArrowRight" | "ArrowDown" if here >= 0 => Some((here + 1) % visible.size)
        case "ArrowLeft" | "ArrowUp" if here >= 0    =>
          Some((here - 1 + visible.size) % visible.size)
        case "Home" if here >= 0 => Some(0)
        case "End" if here >= 0  => Some(visible.size - 1)
        case _                   => None
      next.foreach { i =>
        event.preventDefault()
        visible.foreach(_.setAttribute("tabindex", "-1"))
        visible(i).setAttribute("tabindex", "0")
        visible(i).focus()
      }
    }
    refreshToolbar()

  private def refreshToolbar(): Unit =
    val current = mode
    buttons.foreach { (value, button, needsNavigation) =>
      button.hidden = needsNavigation && navigator.isEmpty
      value.foreach(v => button.setAttribute("aria-pressed", (v == current).toString))
      button.setAttribute("tabindex", if value.contains(current) then "0" else "-1")
    }
    // The toolbar always keeps one tab stop, even when the active mode's button is hidden.
    val visible = buttons.map(_._2).filterNot(_.hidden.asInstanceOf[Boolean])
    if !visible.exists(_.getAttribute("tabindex").asInstanceOf[String] == "0") then
      visible.headOption.foreach(_.setAttribute("tabindex", "0"))
    plotHost.setAttribute("data-navigable", navigator.nonEmpty.toString)
    toolbar.hidden =
      options.toolbarVisibility == ToolbarVisibility.Hidden || options.controls.isEmpty
    plotHost.setAttribute("data-mode", current.toString)

  /** The CSS box the plot occupies now, as the picking viewport. */
  private def viewport: Either[IntaglioError, PickViewport] =
    val box = plotHost.querySelector(":scope > .intaglio-base").getBoundingClientRect()
    PickViewport.fit(
      view.width.toDouble,
      view.height.toDouble,
      box.left.asInstanceOf[Double],
      box.top.asInstanceOf[Double],
      box.width.asInstanceOf[Double],
      box.height.asInstanceOf[Double]
    )

  /** A placeholder replaced before every use by the live box; it keeps construction total. */
  private def unitViewport: PickViewport =
    PickViewport.fit(1, 1, 0, 0, 1, 1).toOption.get

  /** Map input against the live viewport and dispatch it; true when it produced any action. */
  private def withInput(
      produce: InteractionState[A] => Either[IntaglioError, Vector[HostAction[A]]]
  ): Boolean =
    val result = for
      fitted <- viewport
      _ = input = HostInput(view.picking, view.navigation, fitted, behavior)
      current <- controller.state
      actions <- produce(current)
      _ <- actions.foldLeft[Either[IntaglioError, Unit]](Right(())) { (done, action) =>
        done.flatMap(_ => dispatch(action.action, action.cause))
      }
    yield actions.nonEmpty
    result.fold(error => { report(error); false }, identity)

  private def stamp(state: InteractionState[A], cause: InputCause): InputStamp =
    sequence += 1
    InputStamp(state.domain.revision, origin, sequence, cause)

  private def dispatch(
      action: InteractionAction[A],
      cause: InputCause,
      record: Boolean = true
  ): Either[IntaglioError, Unit] =
    controller.state.flatMap { before =>
      controller.dispatch(stamp(before, cause), action).map { _ =>
        if record then
          controller.state.foreach(after => history = history.record(before, after, cause))
      }
    }

  private def controlFailure(error: IntaglioError): Unit =
    if !disposed then
      controlError.textContent = error.message
      controlError.hidden = false
      report(error)

  private def report(error: IntaglioError): Unit =
    try onError(error)
    catch case NonFatal(_) => ()

  // ---------------------------------------------------------------------------------------------
  // Parts

  private def hoverPart(): Unit =
    val hit = for
      fitted <- viewport
      at <- pointer.toRight(PickingError.InvalidInput("pointer"))
      point <- fitted.toDevice(at._1, at._2)
      tolerance <- fitted.tolerance(2.0)
      part <- point.fold[Either[IntaglioError, Option[PartTarget]]](Right(None))(
        view.parts.at(_, tolerance)
      )
    yield part
    hit match
      case Right(part) =>
        val hovered = controller.state.toOption.flatMap(_.hover)
        val annotation = hovered.flatMap(view.annotationPart)
        setHoveredPart(
          if hovered.nonEmpty then part.filter(p => annotation.contains(p.part)) else part
        )
      case Left(_) => setHoveredPart(None)

  private def setHoveredPart(part: Option[PartTarget]): Unit =
    if part != hoveredPart then
      hoveredPart = part
      emitPart(PartEvent.Hovered(part.map(_.part)))
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
      scheduleRedraw()

  private def partAnchor(part: PartTarget): (Double, Double) =
    view.parts
      .outline(part, 0)
      .toOption
      .flatten
      .flatMap(o => o.rings.flatten.headOption)
      .fold((0.0, 0.0))(p => toCss(p.x, p.y))

  /** The emphasis a linked legend entry stands for: its label as a link key of the legend's space.
    */
  private def legendLink(part: PlotPart): Option[LinkedEmphasis[A]] =
    part match
      case PlotPart.LegendEntry(legend, _, _, label) =>
        behavior.legendLinks
          .find(_.legend == legend)
          .flatMap(link => link.space.link(label).toOption)
          .map(key => LinkedEmphasis[A](links = Set(key)))
      case _ => None

  private def emitHover(value: LinkedEmphasis[A]): Unit =
    hoverListeners.foreach { (_, listener) =>
      try listener(value)
      catch case NonFatal(_) => ()
    }

  /** Choosing a linked legend entry selects its marks, as a reader's own action. */
  private def chooseLegend(emphasis: LinkedEmphasis[A], additive: Boolean): Unit =
    val entities = view.navigation.targets.map(_.target).filter(emphasis.matches).flatMap(_.entity)
    // A legend whose link keys match none of this plot's marks selects nothing and clears nothing.
    if entities.nonEmpty then
      val operation = if additive then SelectionOperation.Add else SelectionOperation.Replace
      dispatch(
        InteractionAction.Select(Selection(entities.toSet), operation),
        InputCause.Pointer
      ).left
        .foreach(report)

  private def emitPart(event: PartEvent): Unit =
    partListeners.foreach { (_, listener) =>
      try listener(event)
      catch case NonFatal(_) => ()
    }

  // ---------------------------------------------------------------------------------------------
  // Reactions: tooltip, live region, links, overlay

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
        val described = view.describe(target, behavior)
        live.textContent = coverageNote(target).fold(described)(note => s"$described ($note)")
        // Keyboard focus points at a mark as hover does, so linked views show it too.
        if record.stamp.cause == InputCause.Keyboard then
          emitHover(LinkedEmphasis[A](entities = target.entity.toSet))
        if record.stamp.cause == InputCause.Keyboard then
          hideTooltip()
          behavior
            .tooltip(target)
            .map(withCoverage(_, target))
            .foreach(showTooltip(_, None, anchorOf(target), immediate = true, TooltipSource.Focus))
      case InteractionEvent.FocusChanged(None)    => ()
      case InteractionEvent.GestureModeChanged(_) => refreshToolbar()
      case InteractionEvent.Activated(target)     =>
        view
          .annotationPart(target.id)
          .foreach(part => emitPart(PartEvent.Activated(part, record.stamp.cause)))
        if record.stamp.cause == InputCause.Pointer || record.stamp.cause == InputCause.Keyboard
        then
          behavior.link(target).foreach { link =>
            if link.newContext then g.window.open(link.url, "_blank", "noopener,noreferrer")
            else g.window.location.assign(link.url)
          }
      case InteractionEvent.MembershipRequested(request) =>
        live.textContent = "Retrieving members"
        resolver.foreach { service =>
          // A reply may come at once, during this delivery, or much later; it is always
          // dispatched on a later task, and the reducer rejects one that is no longer current.
          val deliver: MembershipReply[A] => Unit = reply =>
            val send: js.Function0[Unit] = () =>
              if !disposed then
                dispatch(
                  InteractionAction.ResolveMembers(request.target, request.id, reply),
                  InputCause.Programmatic
                ).left.foreach(report)
            g.setTimeout(send, 0)
          try service.resolve(request, deliver)
          catch case NonFatal(error) => deliver(MembershipReply.Failed(error.toString))
        }
      case InteractionEvent.MembershipResolved(_, _, outcome) =>
        outcome match
          case MembershipOutcome.Complete(count) =>
            live.textContent = s"The $count members of the bin are applied to the selection"
          case MembershipOutcome.Unavailable(reason) =>
            live.textContent = s"Members unavailable: $reason"
          case MembershipOutcome.Failed(reason) =>
            live.textContent = s"Members could not be retrieved: $reason"
          case MembershipOutcome.Rejected(reason) =>
            live.textContent = s"Members could not be applied: $reason"
          case MembershipOutcome.Pending | MembershipOutcome.Superseded => ()
        if companion.open.asInstanceOf[Boolean] then fillCompanion()
      case InteractionEvent.SelectionChanged(_) =>
        // Coverage counts in the companion follow the selection.
        if companion.open.asInstanceOf[Boolean] then fillCompanion()
      case _ => ()
    scheduleRedraw()

  private def anchorOf(target: TargetInfo[A]): (Double, Double) =
    input
      .geometry(target.id)
      .fold((0.0, 0.0))(geometry => toCss(geometry.anchor.x, geometry.anchor.y))

  /** Device point to CSS pixels relative to the widget's top-left corner. */
  private def toCss(x: Double, y: Double): (Double, Double) =
    val box = plotHost.querySelector(":scope > .intaglio-base").getBoundingClientRect()
    val rootBox = root.getBoundingClientRect()
    val scale = box.width.asInstanceOf[Double] / view.width
    (
      box.left.asInstanceOf[Double] - rootBox.left.asInstanceOf[Double] + x * scale,
      box.top.asInstanceOf[Double] - rootBox.top.asInstanceOf[Double] + y * scale
    )

  private def showTooltip(
      content: TargetContent,
      at: Option[(Double, Double)],
      anchor: (Double, Double),
      immediate: Boolean,
      source: TooltipSource
  ): Unit =
    tooltipTimer.foreach(handle => g.clearTimeout(handle))
    tooltipTimer = None
    tooltipSource = Some(source)
    def show(): Unit =
      tooltipTimer = None
      if !disposed then
        tooltip.textContent = ""
        content match
          case TargetContent.Text(value)         => tooltip.textContent = value
          case TargetContent.Fields(title, rows) =>
            title.foreach { text =>
              val heading = element("div")
              heading.className = "intaglio-tooltip-title"
              heading.textContent = text
              tooltip.appendChild(heading)
            }
            val list = element("dl")
            rows.foreach { row =>
              val term = element("dt")
              term.textContent = row.label
              val value = element("dd")
              value.textContent = row.value
              list.appendChild(term)
              list.appendChild(value)
            }
            tooltip.appendChild(list)
        // Measure at the origin: at its previous position the box could be squeezed by the edge.
        tooltip.style.left = "0px"
        tooltip.style.top = "0px"
        tooltip.hidden = false
        val rootBox = root.getBoundingClientRect()
        val relative = at.map((x, y) =>
          (x - rootBox.left.asInstanceOf[Double], y - rootBox.top.asInstanceOf[Double])
        )
        val box = TooltipLayout.place(
          behavior.placement,
          relative,
          anchor,
          tooltip.offsetWidth.asInstanceOf[Double],
          tooltip.offsetHeight.asInstanceOf[Double],
          root.clientWidth.asInstanceOf[Double],
          plotHost.clientHeight.asInstanceOf[Double]
        )
        tooltip.style.left = s"${box.left}px"
        tooltip.style.top = s"${box.top}px"
    if immediate || behavior.tooltipDelayMs == 0 then show()
    else
      val callback: js.Function0[Unit] = () => show()
      tooltipTimer = Some(g.setTimeout(callback, behavior.tooltipDelayMs))

  /** Hide the tooltip and cancel a pending one, whatever it shows. */
  private def hideTooltip(): Unit =
    tooltipTimer.foreach(handle => g.clearTimeout(handle))
    tooltipTimer = None
    tooltipSource = None
    tooltip.hidden = true

  /** Hide the tooltip only if `source` put it there. */
  private def hideTooltip(source: TooltipSource): Unit =
    if tooltipSource.contains(source) then hideTooltip()

  private def scheduleRedraw(): Unit =
    if !disposed && frame.isEmpty then
      val callback: js.Function1[Double, Unit] = _ =>
        frame = None
        if !disposed then redraw()
      frame = Some(g.requestAnimationFrame(callback))

  /** Redraw the overlay only: rings for hover, selection and focus, and inverse emphasis. */
  private def redraw(): Unit =
    canvasSurface.foreach { surface => surface.resize(); surface.clearEmphasis() }
    overlay.textContent = ""
    controller.state.foreach { current =>
      val selectedIds = view.navigation.targets.collect {
        case geometry
            if current.selection.targets.contains(geometry.target.id) ||
              geometry.target.entity.exists(current.selection.entities.contains) =>
          geometry.target.id
      }
      // Aggregates are pointed at by coverage: a hovered observation in a linked plot emphasizes the
      // bins that hold it, and selected observations cover bins by the behaviour's rule.
      val linkedIds = view.navigation.targets.collect {
        case geometry
            if linkedEmphasis.covers(geometry.target, EmphasisRule.AnyMember, current.domain) ||
              legendEmphasis.matches(geometry.target) =>
          geometry.target.id
      }
      // Coverage needs exact members, so only aggregates that keep them are measured, once each.
      val coveredIds = view.navigation.targets.collect {
        case geometry
            if geometry.target.entity.isEmpty &&
              geometry.target.membership.capability == MembershipCapability.Exact &&
              !current.selection.targets.contains(geometry.target.id) &&
              behavior.aggregateEmphasis.triggered(
                MemberCoverage.of(geometry.target, current.selection.entities, current.domain)
              ) =>
          geometry.target.id
      }
      val emphasized = (selectedIds ++ coveredIds ++ current.hover.toVector ++ linkedIds).distinct
      val dim = behavior.inverseEmphasis && emphasized.nonEmpty
      plotHost.classList.toggle("intaglio-dimmed", dim)
      if dim then emphasize(emphasized)
      linkedIds.foreach(ring(_, "intaglio-ring-linked", 3.0))
      selectedIds.foreach(ring(_, "intaglio-ring-selected", 2.0))
      coveredIds.foreach(ring(_, "intaglio-ring-covered", 2.0))
      current.hover.foreach(ring(_, "intaglio-ring-hover", 3.0))
      hoveredPart.foreach { part =>
        view.parts.outline(part, 3.0).toOption.flatten.foreach(path(_, "intaglio-ring-hover"))
      }
      if document.activeElement == plotHost then
        current.focus.foreach { id =>
          ring(id, "intaglio-ring-focus-halo", 4.0)
          ring(id, "intaglio-ring-focus", 4.0)
        }
      followViewport(current)
      stateListeners.foreach((_, listener) => listener(current))
    }

  /** Draw the window the state records when it differs from the one drawn: a restore, undo or redo
    * changed the viewport. Both recorded axes are normalized, so an axis at its full extent is the
    * compiled view; nothing new is recorded in history.
    */
  private def followViewport(current: InteractionState[A]): Unit =
    val wanted = current.viewports.get(SvgWidget.panelId)
    if wanted != drawnViewport then
      navigator.foreach { nav =>
        val target = wanted.fold(Right(PanelWindow.full))(v =>
          nav.normalize(PanelWindow(Some((v.xMin, v.xMax)), Some((v.yMin, v.yMax))))
        )
        target
          .flatMap(w =>
            if w == window then
              drawnViewport = wanted
              Right(())
            else showWindow(w, InputCause.Programmatic, record = false)
          )
          .left
          .foreach(report)
      }

  private def ring(target: VisualTargetId, className: String, offset: Double): Unit =
    view.picking.outline(target, offset).foreach(path(_, className))

  private def path(outline: TargetOutline, className: String): Unit =
    val d = SvgWidget.pathData(outline)
    if d.nonEmpty then
      val element = svgElement("path")
      element.setAttribute("d", d)
      element.setAttribute("class", className)
      overlay.appendChild(element)

  /** Show the emphasized targets in their original paint above the dimmed plot: a second copy of
    * the plot clipped to the targets' outlines.
    */
  private def emphasize(targets: Vector[VisualTargetId]): Unit =
    canvasSurface match
      case Some(surface) =>
        surface.emphasize(targets.flatMap(id => view.picking.outline(id, 1.5).toOption))
      case None => emphasizeSvg(targets)

  private def emphasizeSvg(targets: Vector[VisualTargetId]): Unit =
    val clipId = id("emphasis-clip")
    val defs = svgElement("defs")
    val clip = svgElement("clipPath")
    clip.setAttribute("id", clipId)
    targets.foreach { target =>
      view.picking.outline(target, 1.5).foreach { outline =>
        val d = SvgWidget.pathData(outline)
        if d.nonEmpty then
          val shape = svgElement("path")
          shape.setAttribute("d", d)
          clip.appendChild(shape)
      }
    }
    defs.appendChild(clip)
    overlay.appendChild(defs)
    val group = svgElement("g")
    group.setAttribute("clip-path", s"url(#$clipId)")
    group.setAttribute("class", "intaglio-emphasis")
    group.innerHTML = view.emphasisMarkup
    overlay.appendChild(group)

object SvgWidget:
  /** Mount `view` into `container` (a DOM element). The widget owns everything it adds. */
  def mount[A](
      container: js.Dynamic,
      view: SvgWidgetView[A],
      behavior: InteractionBehavior[A] = InteractionBehavior.default[A],
      selection: Selection[A] = Selection[A](),
      label: String = "Interactive plot",
      onError: IntaglioError => Unit = error => g.console.error(error.message),
      options: WidgetOptions = WidgetOptions(),
      resolver: Option[MembershipResolver[A]] = None
  ): Either[IntaglioError, SvgWidget[A]] =
    if js.isUndefined(container) || container == null then
      Left(InteractionError.InvalidValue("widget container", "no DOM element"))
    else if (
        options.sizing match
          case WidgetSizing.Fixed(width) => width <= 0
          case WidgetSizing.Responsive   => false
      )
    then Left(InteractionError.InvalidValue("widget width", "fixed width must be positive"))
    else if g.document.getElementById(s"${view.idPrefix}-live") != null then
      // Two widgets with one prefix would resolve each other's ids (live region, clips).
      Left(
        InteractionError.InvalidValue("widget id prefix", s"'${view.idPrefix}' is already mounted")
      )
    else
      for
        _ <- options.appearance.validate
        _ <-
          if options.renderer == WidgetRenderer.Canvas then CanvasSurface.available else Right(())
        _ <- validateLegendLinks(view, behavior)
        _ <- behavior.validateAggregates(view.plans)
        _ <- validateResolver(view, behavior, resolver)
        domain <- InteractionDomain(view.plans, view.revision)
        state <- InteractionState.initial(domain, behavior.selection, selection)
        origin <- SemanticId(s"${view.idPrefix}-widget")
      yield new SvgWidget(
        container,
        view,
        behavior,
        new InteractionController(state),
        origin,
        label,
        onError,
        options,
        resolver
      )

  /** Members-mode aggregates whose members are deferred need a resolver to ask. */
  private[browser] def validateResolver[A](
      view: SvgWidgetView[A],
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

  /** Every [[LegendLink]] must name a legend this plot draws (by its guide name,
    * `<scale name>-legend` for a derived legend), and every entry's label must be a link key some
    * mark binds in the link's space. A mismatch (a misspelled legend, a label that differs from the
    * bound category, a different key space) is refused here rather than linking nothing at run
    * time.
    */
  private[browser] def validateLegendLinks[A](
      view: SvgWidgetView[A],
      behavior: InteractionBehavior[A]
  ): Either[IntaglioError, Unit] =
    val targets = view.navigation.targets.map(_.target)
    behavior.legendLinks.foldLeft[Either[IntaglioError, Unit]](Right(())) { (done, link) =>
      done.flatMap { _ =>
        val entries = view.parts.parts.map(_.part).collect {
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

  /** An SVG path for every ring of an outline, in device pixels. */
  private[browser] def pathData(outline: TargetOutline): String =
    outline.rings
      .filter(_.nonEmpty)
      .map { ring =>
        ring.zipWithIndex
          .map((p, i) => s"${if i == 0 then "M" else "L"}${fmt(p.x)} ${fmt(p.y)}")
          .mkString("") + "Z"
      }
      .mkString(" ")

  private[browser] def fmt(value: Double): String = Labeler.default(Vector(value)).head

  /** The panel's identity in the interaction state's viewport map. */
  private[browser] val panelId: SemanticId = SemanticId.unsafe(PlotRegion.Panel.value)

  /** A navigator when the plot has a single panel with a numeric or temporal axis. */
  private[browser] def navigatorOf[A](view: SvgWidgetView[A]): Option[DataWindowNavigator] =
    view.singlePlan
      .filter(_ => view.panelFrame.nonEmpty)
      .filter(_.trained.facetPanels.isEmpty)
      .flatMap { plan =>
        plan.training
          .flatMap(_ => DataWindowNavigator.of(plan, view.context).toOption)
          .filter(nav => nav.navigable._1 || nav.navigable._2)
      }

  private val styleId = "intaglio-widget-style"

  /** One stylesheet per document, shared by every widget. It holds only static rules; per-widget
    * state lives in each widget's own elements.
    */
  private[browser] def installStyle(document: js.Dynamic): Unit =
    if document.getElementById(styleId) == null then
      val style = document.createElement("style")
      style.id = styleId
      style.textContent = css
      document.head.appendChild(style)

  private[browser] val css: String =
    """.intaglio-widget{position:relative;display:block;width:100%;
      |  --intaglio-focus:#1a56db;--intaglio-focus-halo:#ffffff;--intaglio-hover:#0b6e4f;
      |  --intaglio-selected:#b45309;--intaglio-linked:#7c3aed;--intaglio-dim:0.3}
      |.intaglio-plot{position:relative;outline:none;line-height:0;user-select:none;
      |  -webkit-user-select:none;touch-action:manipulation}
      |.intaglio-plot>.intaglio-base{display:block;width:100%;height:auto;
      |  transition:opacity var(--intaglio-transition,160ms) ease}
      |.intaglio-plot.intaglio-dimmed>.intaglio-base{opacity:var(--intaglio-dim)}
      |.intaglio-plot:focus-visible>.intaglio-base{outline:2px solid var(--intaglio-focus);
      |  outline-offset:2px}
      |.intaglio-canvas-emphasis{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}
      |.intaglio-overlay{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;
      |  overflow:visible}
      |.intaglio-overlay>path{fill:none;vector-effect:non-scaling-stroke}
      |.intaglio-ring-hover{stroke:var(--intaglio-hover);stroke-width:2}
      |.intaglio-ring-linked{stroke:var(--intaglio-linked);stroke-width:2;stroke-dasharray:2 2}
      |.intaglio-ring-selected{stroke:var(--intaglio-selected);stroke-width:2.5}
      |.intaglio-ring-covered{stroke:var(--intaglio-selected);stroke-width:2;stroke-dasharray:5 3}
      |.intaglio-ring-focus-halo{stroke:var(--intaglio-focus-halo);stroke-width:5}
      |.intaglio-ring-focus{stroke:var(--intaglio-focus);stroke-width:2.5;stroke-dasharray:4 2}
      |.intaglio-tooltip{position:absolute;z-index:1;max-width:min(18rem,100%);box-sizing:border-box;padding:4px 8px;
      |  background:var(--intaglio-tooltip-background,#1f2937);color:var(--intaglio-tooltip-text,#f9fafb);border-radius:4px;font:12px/1.4 system-ui,sans-serif;
      |  pointer-events:none;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.4}
      |.intaglio-tooltip dl{margin:2px 0 0;display:grid;grid-template-columns:minmax(0,auto) minmax(0,1fr);gap:0 8px}
      |.intaglio-tooltip dt{font-weight:600}.intaglio-tooltip dd{margin:0}
      |.intaglio-tooltip-title{font-weight:600}
      |.intaglio-live{position:absolute;width:1px;height:1px;overflow:hidden;
      |  clip-path:inset(50%);white-space:nowrap}
      |.intaglio-toolbar{display:flex;flex-wrap:wrap;gap:4px;margin:0 0 4px;
      |  font:12px/1.4 system-ui,sans-serif}
      |.intaglio-toolbar button{font:inherit;padding:2px 8px;border:1px solid #9ca3af;
      |  border-radius:4px;background:#fff;color:#111827;cursor:pointer}
      |.intaglio-toolbar button[aria-pressed=true]{background:#1f2937;color:#fff;border-color:#1f2937}
      |.intaglio-toolbar button:focus-visible{outline:2px solid var(--intaglio-focus);outline-offset:1px}
      |.intaglio-gesture{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}
      |.intaglio-band{fill:rgba(26,86,219,0.08);stroke:var(--intaglio-focus);stroke-width:1;
      |  stroke-dasharray:4 2;vector-effect:non-scaling-stroke}
      |.intaglio-band-zoom{fill:rgba(124,58,237,0.08);stroke:var(--intaglio-linked);stroke-width:1;
      |  vector-effect:non-scaling-stroke}
      |.intaglio-plot[data-navigable=true]{touch-action:pan-x pan-y}
      |.intaglio-plot[data-mode=Pan]{cursor:grab}
      |.intaglio-plot[data-mode=Rectangle],.intaglio-plot[data-mode=Lasso],
      |.intaglio-plot[data-mode=ZoomRectangle]{cursor:crosshair;touch-action:none}
      |.intaglio-plot[data-mode=Pan]{touch-action:none}
      |.intaglio-companion{font:12px/1.4 system-ui,sans-serif;line-height:1.4}
      |.intaglio-toolbar[hidden]{display:none}
      |.intaglio-widget[data-toolbar-visibility=OnFocus]>.intaglio-toolbar{opacity:0}
      |.intaglio-widget[data-toolbar-visibility=OnFocus]:hover>.intaglio-toolbar,
      |.intaglio-widget[data-toolbar-visibility=OnFocus]:focus-within>.intaglio-toolbar{opacity:1}
      |.intaglio-widget[data-toolbar-position=FloatingTop]>.intaglio-toolbar,
      |.intaglio-widget[data-toolbar-position=FloatingBottom]>.intaglio-toolbar{
      |  position:absolute;left:4px;right:4px;z-index:2;background:#fff;padding:4px}
      |.intaglio-widget[data-toolbar-position=FloatingTop]>.intaglio-toolbar{top:4px}
      |.intaglio-widget[data-toolbar-position=FloatingBottom]>.intaglio-toolbar{bottom:4px}
      |.intaglio-widget:fullscreen{background:#fff;padding:16px;box-sizing:border-box;
      |  width:100%!important;height:100%;overflow:auto}
      |@media (prefers-reduced-motion: reduce){
      |  .intaglio-plot>.intaglio-base{transition:none}}
      |""".stripMargin
