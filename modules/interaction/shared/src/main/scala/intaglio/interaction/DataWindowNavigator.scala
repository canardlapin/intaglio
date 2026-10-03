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

  /** Zoom by `factor` (below 1 zooms in) about a device point, keeping that point's data fixed. */
  def zoom(frame: DeviceFrame, pivot: DevicePoint, factor: Double): PanelWindow =
    val (px, py) = position(frame, pivot)
    val ((x0, x1), (y0, y1)) = current(frame)
    def scaled(p: Double, a: Double, b: Double) = (p + (a - p) * factor, p + (b - p) * factor)
    val (nx0, nx1) = scaled(px, x0, x1)
    val (ny0, ny1) = scaled(py, y0, y1)
    PanelWindow(clamp(x, nx0, nx1), clamp(y, ny0, ny1))

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
    PanelWindow(
      clamp(x, math.min(ax, bx), math.max(ax, bx)),
      clamp(y, math.min(ay, by), math.max(ay, by))
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
