package intaglio.interaction

import intaglio.*

/** A panel's visible window in panel position units on each axis (the panel's native viewport
  * coordinates: for a scaled axis, the scale's mapped positions). `None` on an axis means the
  * compiled, unwindowed extent.
  */
final case class PanelWindow(x: Option[(Double, Double)], y: Option[(Double, Double)]):
  def isFull: Boolean = x.isEmpty && y.isEmpty

object PanelWindow:
  val full: PanelWindow = PanelWindow(None, None)

/** How one position axis turns panel positions back into a typed data window. */
private[interaction] enum NavigableAxis:
  /** An unscaled numeric axis: positions are data values; bounds are the compiled range. */
  case Raw(extent: (Double, Double))

  /** A trained continuous scale, inverted through its transform; bounds are its domain. */
  case Continuous(scale: ContinuousScale[?])
  case Date(scale: DateScale)
  case DateTime(scale: DateTimeScale)

  /** A categorical, band or other axis: not navigable; it always shows its compiled extent. */
  case Fixed

  def bounds: Option[(Double, Double)] =
    this match
      case Raw(extent)                           => Some(extent)
      case Continuous(_) | Date(_) | DateTime(_) => Some((0.0, 1.0))
      case Fixed                                 => None

  def window(lower: Double, upper: Double): Either[IntaglioError, CoordinateWindow] =
    this match
      case Raw(_)            => CoordinateWindow.numeric(lower, upper)
      case Continuous(scale) =>
        def raw(p: Double) =
          val t = scale.transformedDomain.lower + p * scale.transformedDomain.width
          scale.transform.inverse(t)
        for
          lo <- raw(lower)
          hi <- raw(upper)
          w <- CoordinateWindow.numeric(math.min(lo, hi), math.max(lo, hi))
        yield w
      case Date(scale) =>
        for
          lo <- scale.inverse(lower)
          hi <- scale.inverse(upper)
          w <- CoordinateWindow.date(lo, hi)
        yield w
      case DateTime(scale) =>
        for
          lo <- scale.inverse(lower)
          hi <- scale.inverse(upper)
          w <- CoordinateWindow.dateTime(lo, hi)
        yield w
      case Fixed => Left(InteractionError.UnsupportedCapability("navigating a non-numeric axis"))

private[interaction] object NavigableAxis:
  /** A window whose two ends name the same value (one day on a date axis zoomed below a day). */
  def degenerate(window: CoordinateWindow): Boolean =
    window match
      case CoordinateWindow.Numeric(range)  => !(range.upper > range.lower)
      case CoordinateWindow.Date(range)     => range.lower == range.upper
      case CoordinateWindow.DateTime(range) => range.lower == range.upper

/** Pure data-window navigation for one single-panel plan: zoom about a point, pan, rubber-band
  * rectangles, and the bounds that keep a window inside the trained extent. All arithmetic is in
  * panel position units, so it is linear on every axis kind; [[windows]] converts the result back
  * into typed `CoordinateWindow`s for [[InteractionCompiler.rezoom]]. A window that reaches the
  * whole extent of an axis becomes `None` there, so zooming out past the start is a reset.
  */
final class DataWindowNavigator private (x: NavigableAxis, y: NavigableAxis):
  /** Whether this axis can be navigated (numeric, continuous or temporal). */
  def navigable: (Boolean, Boolean) = (x.bounds.nonEmpty, y.bounds.nonEmpty)

  /** The panel position under a device point in `frame` (the current panel's resolved frame). */
  def position(frame: DeviceFrame, point: DevicePoint): (Double, Double) =
    val fx = (point.x - frame.x) / frame.width
    val fy = (point.y - frame.y) / frame.height
    val py = frame.yDirection match
      case YDirection.Up   => frame.yScale.lower + (1.0 - fy) * frame.yScale.width
      case YDirection.Down => frame.yScale.lower + fy * frame.yScale.width
    (frame.xScale.lower + fx * frame.xScale.width, py)

  private def current(frame: DeviceFrame): ((Double, Double), (Double, Double)) =
    ((frame.xScale.lower, frame.xScale.upper), (frame.yScale.lower, frame.yScale.upper))

  /** Clamp one axis's interval into its bounds, keeping its width when it fits. */
  private def clamp(axis: NavigableAxis, lo: Double, hi: Double): Option[(Double, Double)] =
    axis.bounds.flatMap { (b0, b1) =>
      val width = hi - lo
      if width >= (b1 - b0) - 1e-12 || !width.isFinite || width <= 0 then None
      else
        val shifted =
          if lo < b0 then (b0, b0 + width) else if hi > b1 then (b1 - width, b1) else (lo, hi)
        Some(shifted)
    }

  /** A narrower window, unless the axis cannot show it (narrower than its smallest data step, such
    * as one day on a date axis, or than float resolution): then zooming stops at `current`.
    */
  private def narrowed(
      axis: NavigableAxis,
      current: (Double, Double),
      lo: Double,
      hi: Double
  ): Option[(Double, Double)] =
    val next = clamp(axis, lo, hi)
    val showable = next.forall { (a, b) =>
      val full = axis.bounds.fold(1.0)((b0, b1) => b1 - b0)
      (b - a) > full * 1e-9 && axis.window(a, b).exists(w => !NavigableAxis.degenerate(w))
    }
    if showable then next else clamp(axis, current._1, current._2)

  /** `window` checked and brought inside the bounds: each interval must be finite and increasing;
    * it is shifted inside the extent, and an interval covering the extent becomes `None`. Refused
    * on an axis that cannot be navigated.
    */
  def normalize(window: PanelWindow): Either[IntaglioError, PanelWindow] =
    def axisOf(axis: NavigableAxis, name: String, value: Option[(Double, Double)]) =
      value match
        case None           => Right(None)
        case Some((lo, hi)) =>
          if !lo.isFinite || !hi.isFinite || lo >= hi then
            Left(InteractionError.InvalidValue(s"$name window", s"($lo, $hi)"))
          else if axis.bounds.isEmpty then
            Left(InteractionError.UnsupportedCapability(s"navigating the $name axis"))
          else
            val clamped = clamp(axis, lo, hi)
            clamped.fold(Right(None)) { (a, b) =>
              axis.window(a, b).flatMap { w =>
                if NavigableAxis.degenerate(w) then
                  Left(
                    InteractionError.InvalidValue(s"$name window", s"($lo, $hi) shows one value")
                  )
                else Right(clamped)
              }
            }
    for
      nx <- axisOf(x, "x", window.x)
      ny <- axisOf(y, "y", window.y)
    yield PanelWindow(nx, ny)

  /** Zoom by `factor` (below 1 zooms in) about a device point, keeping that point's data fixed. */
  def zoom(frame: DeviceFrame, pivot: DevicePoint, factor: Double): PanelWindow =
    val (px, py) = position(frame, pivot)
    val ((x0, x1), (y0, y1)) = current(frame)
    def scaled(p: Double, a: Double, b: Double) = (p + (a - p) * factor, p + (b - p) * factor)
    val (nx0, nx1) = scaled(px, x0, x1)
    val (ny0, ny1) = scaled(py, y0, y1)
    PanelWindow(narrowed(x, (x0, x1), nx0, nx1), narrowed(y, (y0, y1), ny0, ny1))

  /** Pan by a device-pixel drag: the data under the pointer follows the pointer. */
  def pan(frame: DeviceFrame, dxDevice: Double, dyDevice: Double): PanelWindow =
    val ((x0, x1), (y0, y1)) = current(frame)
    val dx = -dxDevice / frame.width * frame.xScale.width
    val dyPositions = dyDevice / frame.height * frame.yScale.width
    val dy = frame.yDirection match
      case YDirection.Up   => dyPositions
      case YDirection.Down => -dyPositions
    PanelWindow(
      if x0 == x1 then None else clampPan(x, x0 + dx, x1 + dx),
      if y0 == y1 then None else clampPan(y, y0 + dy, y1 + dy)
    )

  /** A pan never changes the width, so a full axis stays full instead of shifting. */
  private def clampPan(axis: NavigableAxis, lo: Double, hi: Double): Option[(Double, Double)] =
    clamp(axis, lo, hi)

  /** The window a device rectangle (two corners) covers. */
  def rectangle(frame: DeviceFrame, a: DevicePoint, b: DevicePoint): PanelWindow =
    val (ax, ay) = position(frame, a)
    val (bx, by) = position(frame, b)
    val ((x0, x1), (y0, y1)) = current(frame)
    PanelWindow(
      narrowed(x, (x0, x1), math.min(ax, bx), math.max(ax, bx)),
      narrowed(y, (y0, y1), math.min(ay, by), math.max(ay, by))
    )

  /** The window that draws a saved `viewport`: its interval on each navigable axis, checked and
    * brought inside the bounds as [[normalize]] does; a categorical axis keeps its compiled extent.
    */
  private[intaglio] def windowOf(viewport: PanelViewport): Either[IntaglioError, PanelWindow] =
    val (nx, ny) = navigable
    normalize(
      PanelWindow(
        Option.when(nx)((viewport.xMin, viewport.xMax)),
        Option.when(ny)((viewport.yMin, viewport.yMax))
      )
    )

  /** Typed data windows for [[InteractionCompiler.rezoom]]; `None` where the axis is full. */
  def windows(
      window: PanelWindow
  ): Either[IntaglioError, (Option[CoordinateWindow], Option[CoordinateWindow])] =
    for
      wx <- window.x.fold[Either[IntaglioError, Option[CoordinateWindow]]](Right(None))((lo, hi) =>
        x.window(lo, hi).map(Some(_))
      )
      wy <- window.y.fold[Either[IntaglioError, Option[CoordinateWindow]]](Right(None))((lo, hi) =>
        y.window(lo, hi).map(Some(_))
      )
    yield (wx, wy)

object DataWindowNavigator:
  /** Whether a host can draw the viewports of a snapshot it is asked to restore, checked before
    * anything changes. An interactive host draws at most one navigable panel, `panel`, through
    * `navigator` (`None` when the view cannot navigate; `unnavigable` names it in the reason). A
    * viewport for another panel, any viewport on a view that cannot navigate, and one that would
    * show a single value are refused with `SnapshotError.Invalid`; a viewport outside the bounds is
    * accepted, since it is drawn brought inside them.
    */
  private[intaglio] def drawable(
      viewports: Map[SemanticId, PanelViewport],
      panel: SemanticId,
      navigator: Option[DataWindowNavigator],
      unnavigable: String
  ): Either[SnapshotError, Unit] =
    val others = (viewports.keySet - panel).map(_.value).toVector.sorted
    if others.nonEmpty then
      Left(SnapshotError.Invalid(s"this view has no panel ${others.mkString(", ")} to draw"))
    else
      viewports.get(panel).fold(Right(())) { saved =>
        navigator match
          case None =>
            Left(
              SnapshotError.Invalid(s"its viewport cannot be drawn: $unnavigable cannot navigate")
            )
          case Some(nav) =>
            nav
              .windowOf(saved)
              .left
              .map(e => SnapshotError.Invalid(s"its viewport cannot be drawn: ${e.message}"))
              .map(_ => ())
      }

  /** A navigator for `plan` as compiled (its unwindowed panel gives the raw bounds). */
  def of[A](
      plan: InteractionPlan[A],
      context: RenderContext
  ): Either[IntaglioError, DataWindowNavigator] =
    for
      device <- DeviceScene.fromScene(plan.scene, context)
      panel <- device
        .frame(PlotRegion.Panel)
        .left
        .map(error =>
          InteractionError
            .UnsupportedCapability(s"navigation without one plot panel (${error.message})")
        )
    yield
      def axis(aesthetic: Aesthetic[Double], compiled: Interval): NavigableAxis =
        plan.trained.scaleRegistry.forAesthetic(aesthetic).map(_.scale) match
          case None => NavigableAxis.Raw((compiled.lower, compiled.upper))
          case Some(scale: ContinuousScale[?]) => NavigableAxis.Continuous(scale)
          case Some(scale: DateScale)          => NavigableAxis.Date(scale)
          case Some(scale: DateTimeScale)      => NavigableAxis.DateTime(scale)
          case Some(_)                         => NavigableAxis.Fixed
      new DataWindowNavigator(
        axis(Aesthetic.X, panel.frame.xScale),
        axis(Aesthetic.Y, panel.frame.yScale)
      )
