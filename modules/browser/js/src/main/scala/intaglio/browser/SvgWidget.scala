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
    onError: IntaglioError => Unit
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
    scheduleRedraw()
    result

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
        domain <- InteractionDomain(Vector(next.plan), next.plan.revision)
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
        view = next
        input = HostInput(view.picking, view.navigation, unitViewport, behavior)
        live.textContent = ""
        hideTooltip()
        renderPlot()
        if companion.open.asInstanceOf[Boolean] then fillCompanion()
        scheduleRedraw()

  def isDisposed: Boolean = disposed

  /** Listeners currently registered on the DOM; zero after [[dispose]]. */
  def domListenerCount: Int = listeners.size

  def dispose(): Unit =
    if !disposed then
      disposed = true
      listeners.clear()
      subscriptions.foreach(_.cancel())
      subscriptions = Vector.empty
      partListeners = Vector.empty
      hoverListeners = Vector.empty
      resizeObserver.foreach(_.disconnect())
      resizeObserver = None
      frame.foreach(handle => g.cancelAnimationFrame(handle))
      frame = None
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
    root.appendChild(plotHost)
    root.appendChild(tooltip)
    root.appendChild(live)
    root.appendChild(companion)
    container.appendChild(root)
    renderPlot()
    wire()

  /** Insert the plot markup beneath the overlay, which keeps the plot's viewBox. */
  private def renderPlot(): Unit =
    val previous = plotHost.querySelector(":scope > svg.intaglio-base")
    if previous != null then plotHost.removeChild(previous)
    val holder = element("div")
    holder.innerHTML = view.markup
    val svg = holder.firstElementChild
    svg.classList.add("intaglio-base")
    plotHost.insertBefore(svg, overlay)
    overlay.setAttribute("viewBox", s"0 0 ${view.width} ${view.height}")

  private def fillCompanion(): Unit =
    companionBody.textContent = ""
    view.companionRows(behavior).foreach { (kind, text) =>
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
    }
    listeners.on(plotHost, "pointercancel") { _ =>
      withInput(input.pointer(_, PointerInput.Cancel))
    }
    listeners.on(plotHost, "lostpointercapture") { _ =>
      withInput(input.pointer(_, PointerInput.Cancel))
    }
    listeners.on(plotHost, "pointerup") { event =>
      if event.button.asInstanceOf[Int] == 0 then withInput(input.pointer(_, PointerInput.Release))
    }
    listeners.on(plotHost, "click") { event =>
      val x = event.clientX.asInstanceOf[Double]
      val y = event.clientY.asInstanceOf[Double]
      val additive = modifier(event)
      val handled = withInput(input.pointer(_, PointerInput.Click(x, y, additive)))
      if !handled then
        hoveredPart.foreach { p =>
          emitPart(PartEvent.Activated(p.part, InputCause.Pointer))
          legendLink(p.part).foreach(chooseLegend(_, additive))
        }
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
      key.foreach { value =>
        event.preventDefault()
        withInput(input.key(_, value))
      }
    }
    listeners.on(plotHost, "focus") { _ => scheduleRedraw() }
    listeners.on(plotHost, "blur") { _ =>
      hideTooltip()
      scheduleRedraw()
    }
    listeners.on(companion, "toggle") { _ =>
      if companion.open.asInstanceOf[Boolean] then fillCompanion()
      else companionBody.textContent = ""
    }
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

  /** The CSS box the plot occupies now, as the picking viewport. */
  private def viewport: Either[IntaglioError, PickViewport] =
    val box = plotHost.querySelector(":scope > svg.intaglio-base").getBoundingClientRect()
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
      cause: InputCause
  ): Either[IntaglioError, Unit] =
    controller.state
      .flatMap(current => controller.dispatch(stamp(current, cause), action))
      .map(_ => ())

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
        val markHovered = controller.state.toOption.exists(_.hover.nonEmpty)
        setHoveredPart(if markHovered then None else part)
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
          showTooltip(content, pointer, anchorOf(target), immediate = false, TooltipSource.Hover)
        }
      case InteractionEvent.HoverChanged(None) =>
        if hoveredPart.isEmpty then emitHover(LinkedEmphasis.none[A])
        hideTooltip(TooltipSource.Hover)
      case InteractionEvent.FocusChanged(Some(target)) =>
        live.textContent = view.describe(target, behavior)
        if record.stamp.cause == InputCause.Keyboard then
          hideTooltip()
          behavior
            .tooltip(target)
            .foreach(showTooltip(_, None, anchorOf(target), immediate = true, TooltipSource.Focus))
      case InteractionEvent.FocusChanged(None) => ()
      case InteractionEvent.Activated(target)  =>
        if record.stamp.cause == InputCause.Pointer || record.stamp.cause == InputCause.Keyboard
        then
          behavior.link(target).foreach { link =>
            if link.newContext then g.window.open(link.url, "_blank", "noopener,noreferrer")
            else g.window.location.assign(link.url)
          }
      case _ => ()
    scheduleRedraw()

  private def anchorOf(target: TargetInfo[A]): (Double, Double) =
    input
      .geometry(target.id)
      .fold((0.0, 0.0))(geometry => toCss(geometry.anchor.x, geometry.anchor.y))

  /** Device point to CSS pixels relative to the widget's top-left corner. */
  private def toCss(x: Double, y: Double): (Double, Double) =
    val box = plotHost.querySelector(":scope > svg.intaglio-base").getBoundingClientRect()
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
    overlay.textContent = ""
    controller.state.foreach { current =>
      val selectedIds = view.navigation.targets.collect {
        case geometry
            if current.selection.targets.contains(geometry.target.id) ||
              geometry.target.entity.exists(current.selection.entities.contains) =>
          geometry.target.id
      }
      val linkedIds = view.navigation.targets.collect {
        case geometry
            if linkedEmphasis.matches(geometry.target) || legendEmphasis.matches(geometry.target) =>
          geometry.target.id
      }
      val emphasized = (selectedIds ++ current.hover.toVector ++ linkedIds).distinct
      val dim = behavior.inverseEmphasis && emphasized.nonEmpty
      plotHost.classList.toggle("intaglio-dimmed", dim)
      if dim then emphasize(emphasized)
      linkedIds.foreach(ring(_, "intaglio-ring-linked", 3.0))
      selectedIds.foreach(ring(_, "intaglio-ring-selected", 2.0))
      current.hover.foreach(ring(_, "intaglio-ring-hover", 3.0))
      hoveredPart.foreach { part =>
        view.parts.outline(part, 3.0).toOption.flatten.foreach(path(_, "intaglio-ring-hover"))
      }
      if document.activeElement == plotHost then
        current.focus.foreach { id =>
          ring(id, "intaglio-ring-focus-halo", 4.0)
          ring(id, "intaglio-ring-focus", 4.0)
        }
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
      onError: IntaglioError => Unit = error => g.console.error(error.message)
  ): Either[IntaglioError, SvgWidget[A]] =
    if js.isUndefined(container) || container == null then
      Left(InteractionError.InvalidValue("widget container", "no DOM element"))
    else if g.document.getElementById(s"${view.idPrefix}-live") != null then
      // Two widgets with one prefix would resolve each other's ids (live region, clips).
      Left(
        InteractionError.InvalidValue("widget id prefix", s"'${view.idPrefix}' is already mounted")
      )
    else
      for
        domain <- InteractionDomain(Vector(view.plan), view.plan.revision)
        state <- InteractionState.initial(domain, behavior.selection, selection)
        origin <- SemanticId(s"${view.idPrefix}-widget")
      yield new SvgWidget(
        container,
        view,
        behavior,
        new InteractionController(state),
        origin,
        label,
        onError
      )

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

  private def fmt(value: Double): String = Labeler.default(Vector(value)).head

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
      |.intaglio-plot>svg.intaglio-base{display:block;width:100%;height:auto;
      |  transition:opacity 160ms ease}
      |.intaglio-plot.intaglio-dimmed>svg.intaglio-base{opacity:var(--intaglio-dim)}
      |.intaglio-plot:focus-visible>svg.intaglio-base{outline:2px solid var(--intaglio-focus);
      |  outline-offset:2px}
      |.intaglio-overlay{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;
      |  overflow:visible}
      |.intaglio-overlay>path{fill:none;vector-effect:non-scaling-stroke}
      |.intaglio-ring-hover{stroke:var(--intaglio-hover);stroke-width:2}
      |.intaglio-ring-linked{stroke:var(--intaglio-linked);stroke-width:2;stroke-dasharray:2 2}
      |.intaglio-ring-selected{stroke:var(--intaglio-selected);stroke-width:2.5}
      |.intaglio-ring-focus-halo{stroke:var(--intaglio-focus-halo);stroke-width:5}
      |.intaglio-ring-focus{stroke:var(--intaglio-focus);stroke-width:2.5;stroke-dasharray:4 2}
      |.intaglio-tooltip{position:absolute;z-index:1;max-width:18rem;padding:4px 8px;
      |  background:#1f2937;color:#f9fafb;border-radius:4px;font:12px/1.4 system-ui,sans-serif;
      |  pointer-events:none;white-space:pre-wrap;line-height:1.4}
      |.intaglio-tooltip dl{margin:2px 0 0;display:grid;grid-template-columns:auto auto;gap:0 8px}
      |.intaglio-tooltip dt{font-weight:600}.intaglio-tooltip dd{margin:0}
      |.intaglio-tooltip-title{font-weight:600}
      |.intaglio-live{position:absolute;width:1px;height:1px;overflow:hidden;
      |  clip-path:inset(50%);white-space:nowrap}
      |.intaglio-companion{font:12px/1.4 system-ui,sans-serif;line-height:1.4}
      |@media (prefers-reduced-motion: reduce){
      |  .intaglio-plot>svg.intaglio-base{transition:none}}
      |""".stripMargin
