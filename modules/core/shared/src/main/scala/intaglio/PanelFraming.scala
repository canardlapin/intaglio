package intaglio

/** How a compiler-derived panel range is framed after [[RangeExpansion]].
  *
  * `Data` frames the trained data range alone: the expansion is a fraction of that range, so a
  * point's drawn extent is not a lower bound on the framing and a large mark at a data extreme can
  * cross the panel edge, where the panel clip cuts it. `MarkInk` additionally widens each derived
  * position range, after the layout solve fixes the panel's device size, until the whole ink of
  * every point mark --- shape, stroke, and miter corners --- lies at least `clearancePt` inside the
  * panel. It never narrows the expanded range, so a plot whose marks already fit is unchanged.
  */
sealed trait PanelFraming

object PanelFraming:
  case object Data extends PanelFraming

  /** Fit point ink inside the panel with `clearancePt` of space between ink and panel edge.
    *
    * Contract:
    *   - Applies to compiler-derived ranges on solver-placed panels. An explicit
    *     [[PlotCompilerOptions.layout]] or `frame` keeps its given ranges, and a zoomed axis keeps
    *     its zoom window.
    *   - Counts point marks (`Grob.Points`, `Grob.PointBatch`) drawn in the panel. Other marks, and
    *     point locations the framing cannot express as native position plus absolute offset, frame
    *     as `Data` does.
    *   - Widens only; each side widens independently, so observation positions and their order are
    *     unchanged. Transformed, flipped, and faceted axes frame in the space they draw in.
    *   - When the ink at opposite extremes cannot fit the panel at any range, that axis keeps its
    *     expanded range and the clip decides as under `Data`.
    *   - Device sizes come from the layout policy's reference device, which a [[RenderContext]]
    *     sets to the actual target.
    */
  final case class MarkInk private[intaglio] (clearancePt: Double) extends PanelFraming

  object MarkInk:
    def apply(clearancePt: Double): Either[GraphicsError, MarkInk] =
      if !clearancePt.isFinite || clearancePt < 0.0 then
        Left(GraphicsError.InvalidExtent(s"mark-ink clearance $clearancePt pt"))
      else Right(new MarkInk(clearancePt))

    def unsafe(clearancePt: Double): MarkInk =
      apply(clearancePt).orThrow

  /** Point ink fitted with a 2 pt clearance from the panel edge. */
  val markInk: PanelFraming =
    new MarkInk(2.0)

/** Solves [[PanelFraming.MarkInk]] for one placed panel. */
private[intaglio] object MarkInkFraming:
  /** One mark's constraint on one axis of a range `[lo, lo + span]`: its ink must satisfy
    * `lo <= native + lowSlope * span` and `lo + span >= native + highSlope * span`.
    *
    * For a panel `length` pixels long, a mark at `native` shifted by `offset` absolute pixels whose
    * ink reaches `low` pixels toward the range's low end and `high` toward its high end has
    * `lowSlope = (offset - low) / length` and `highSlope = (offset + high) / length`. Slopes are
    * unitless, so marks from panels of different sizes that share one range pool directly.
    */
  final case class AxisMark(native: Double, lowSlope: Double, highSlope: Double)

  object AxisMark:
    def apply(native: Double, offset: Double, low: Double, high: Double, length: Double): AxisMark =
      AxisMark(native, (offset - low) / length, (offset + high) / length)

  /** Directional ink extents, in device pixels, of a point mark of radius `r` drawn with `gp`
    * already resolved to device pixels: (left, right, up, down).
    */
  def pointInk(shape: PointShape, r: Double, gp: GraphicParams): (Double, Double, Double, Double) =
    val half = if gp.stroke.isEmpty || gp.lineWidth <= 0.0 then 0.0 else gp.lineWidth / 2.0
    shape match
      case PointShape.Circle =>
        val e = r + half
        (e, e, e, e)
      case PointShape.Square =>
        // Axis-aligned edges offset by the half width bound every join along both axes.
        val e = r + half
        (e, e, e, e)
      case PointShape.Cross if half == 0.0 =>
        // Two open strokes: without a stroke the mark has no ink at all.
        (0.0, 0.0, 0.0, 0.0)
      case PointShape.Cross =>
        val cap = gp.lineCap match
          case LineCap.Butt                   => 0.0
          case LineCap.Round | LineCap.Square => half
        val e = math.max(r + cap, half)
        (e, e, e, e)
      case PointShape.Triangle =>
        polygonInk(Vector((0.0, -r), (r, r), (-r, r)), half, gp.lineJoin)
      case PointShape.Diamond =>
        val d = PointShape.diamondHalfDiagonal(r)
        polygonInk(Vector((0.0, -d), (d, 0.0), (0.0, d), (-d, 0.0)), half, gp.lineJoin)

  /** Largest miter ratio any supported renderer draws before beveling (Canvas and Java2D use 10,
    * SVG 4), so a miter this framing counts is never smaller than the one drawn.
    */
  private val MiterLimit = 10.0

  /** Directional extents (left, right, up, down) of a closed polygon stroked with half width `h`,
    * in device coordinates (y down) relative to the mark centre.
    */
  private def polygonInk(
      vertices: Vector[(Double, Double)],
      h: Double,
      join: LineJoin
  ): (Double, Double, Double, Double) =
    val n = vertices.length
    val candidates = Vector.newBuilder[(Double, Double)]
    vertices.foreach(candidates += _)
    if h > 0.0 then
      for i <- 0 until n do
        val (px, py) = vertices((i + n - 1) % n)
        val (vx, vy) = vertices(i)
        val (nx, ny) = vertices((i + 1) % n)
        val (ax, ay) = unit(vx - px, vy - py)
        val (bx, by) = unit(nx - vx, ny - vy)
        // Both offset corners of each adjacent edge at this vertex are stroke ink.
        candidates += ((vx - ay * h, vy + ax * h))
        candidates += ((vx + ay * h, vy - ax * h))
        candidates += ((vx - by * h, vy + bx * h))
        candidates += ((vx + by * h, vy - bx * h))
        val cosTurn = ax * bx + ay * by
        // Interior angle theta between the two edges: sin(theta / 2) = sqrt((1 + cosTurn) / 2).
        val sinHalf = math.sqrt(math.max(0.0, (1.0 + cosTurn) / 2.0))
        join match
          case LineJoin.Miter if sinHalf > 0.0 && 1.0 / sinHalf <= MiterLimit =>
            val (ox, oy) = unit(ax - bx, ay - by)
            candidates += ((vx + ox * h / sinHalf, vy + oy * h / sinHalf))
          case LineJoin.Round =>
            // A round join is an arc of the disc of radius h about the vertex. The disc's axis
            // extremes bound it, overstating by at most h (1 - cos) where the arc misses them.
            candidates += ((vx - h, vy))
            candidates += ((vx + h, vy))
            candidates += ((vx, vy - h))
            candidates += ((vx, vy + h))
          case _ => ()
    val points = candidates.result()
    (
      -points.map(_._1).min,
      points.map(_._1).max,
      -points.map(_._2).min,
      points.map(_._2).max
    )

  /** Frame both axes of one placed panel. `panel` is the panel frame resolved at the base ranges;
    * `frameX`/`frameY` false keeps that axis as given (a zoom window).
    */
  def frame(
      grobs: Vector[Grob],
      device: DeviceContext,
      panel: DeviceFrame,
      clearancePx: Double,
      frameX: Boolean,
      frameY: Boolean
  ): Either[GraphicsError, (Interval, Interval)] =
    collect(grobs, device, panel, clearancePx).map { case (xs, ys) =>
      (
        if frameX then frameAxis(panel.xScale, xs) else panel.xScale,
        if frameY then frameAxis(panel.yScale, ys) else panel.yScale
      )
    }

  /** Every point mark's constraints on the panel's x and y axes. Empty on an axis of zero length.
    */
  def collect(
      grobs: Vector[Grob],
      device: DeviceContext,
      panel: DeviceFrame,
      clearancePx: Double
  ): Either[GraphicsError, (Vector[AxisMark], Vector[AxisMark])] =
    val resolver = new LengthResolver(device, panel)
    val xs = Vector.newBuilder[AxisMark]
    val ys = Vector.newBuilder[AxisMark]
    // In a y-up panel the range's low end is the bottom, so a mark's downward ink faces it.
    val yUp = panel.yDirection == YDirection.Up
    def add(point: Point, shape: PointShape, radius: Double, gp: GraphicParams): Unit =
      for
        x <- decompose(point.x, horizontal = true, device, panel)
        y <- decompose(point.y, horizontal = false, device, panel)
      do
        val (left, right, up, down) = pointInk(shape, radius, gp)
        val c = clearancePx
        if panel.width > 0.0 then xs += AxisMark(x._1, x._2, left + c, right + c, panel.width)
        if panel.height > 0.0 then
          ys += (
            if yUp then AxisMark(y._1, y._2, down + c, up + c, panel.height)
            else AxisMark(y._1, y._2, up + c, down + c, panel.height)
          )
    def visit(grob: Grob): Either[GraphicsError, Unit] =
      grob match
        case points: Grob.Points if points.viewport.isEmpty =>
          for
            radius <- resolver.extent(points.size)
            gp <- resolver.graphicParams(points.gp)
          yield points.points.foreach(add(_, points.shape, radius, gp))
        case batch: Grob.PointBatch if batch.viewport.isEmpty =>
          var result: Either[GraphicsError, Unit] = Right(())
          var i = 0
          while i < batch.points.length && result.isRight do
            result = for
              radius <- resolver.extent(batch.sizes.valueAt(i))
              gp <- resolver.graphicParams(batch.graphicParams.valueAt(i))
            yield add(batch.points(i), batch.shapes.valueAt(i), radius, gp)
            i += 1
          result
        case group: Grob.Group if group.viewport.isEmpty =>
          visitAll(group.children)
        case annotated: Grob.Annotated =>
          visit(annotated.child)
        case _ =>
          Right(())
    def visitAll(children: Vector[Grob]): Either[GraphicsError, Unit] =
      children.foldLeft[Either[GraphicsError, Unit]](Right(()))((acc, g) =>
        acc.flatMap(_ => visit(g))
      )
    visitAll(grobs).map(_ => (xs.result(), ys.result()))

  /** A location as `native` (in the panel's scale) plus `offset` (absolute local pixels), when its
    * native terms sum to exactly one location; `None` for anything the framing cannot follow.
    */
  private def decompose(
      expr: LengthExpr,
      horizontal: Boolean,
      device: DeviceContext,
      panel: DeviceFrame
  ): Option[(Double, Double)] =
    val span = if horizontal then panel.width else panel.height
    val scale = if horizontal then panel.xScale else panel.yScale
    // (native value, count of native locations, absolute pixels)
    def eval(e: LengthExpr, location: Boolean): Option[(Double, Double, Double)] =
      e match
        case LengthExpr.Const(length) =>
          length.unit match
            case LengthUnit.Native =>
              if location then Some((length.value, 1.0, 0.0))
              else Some((length.value, 0.0, 0.0))
            case LengthUnit.Npc  => Some((0.0, 0.0, length.value * span))
            case LengthUnit.Line => None
            case unit            => device.pxPerUnit(unit).map(px => (0.0, 0.0, length.value * px))
        case LengthExpr.Add(l, r) =>
          for a <- eval(l, location); b <- eval(r, location)
          yield (a._1 + b._1, a._2 + b._2, a._3 + b._3)
        case LengthExpr.Sub(l, r) =>
          for a <- eval(l, location); b <- eval(r, location)
          yield (a._1 - b._1, a._2 - b._2, a._3 - b._3)
        case LengthExpr.Offset(base, extent, direction) =>
          for a <- eval(base, location = true); b <- eval(extent.expr, location = false)
          yield (a._1 + direction * b._1, a._2 + direction * b._2, a._3 + direction * b._3)
        case LengthExpr.Mul(factor, value) =>
          eval(value, location).map((n, c, p) => (factor * n, factor * c, factor * p))
    eval(expr, location = true).collect {
      case (native, count, offset)
          if math.abs(
            count - 1.0
          ) < 1.0e-12 && native.isFinite && offset.isFinite && scale.width > 0 =>
        (native, offset)
    }

  private def unit(x: Double, y: Double): (Double, Double) =
    val length = math.hypot(x, y)
    (x / length, y / length)

  /** The narrowest interval containing `base` that satisfies every mark's constraint, or `base`
    * itself when no widening is needed or none can succeed.
    *
    * With span `R`, the tightest bounds are `lo(R) = min(base.lower, native_i + lowSlope_i R)` and
    * `hi(R) = max(base.upper, native_i + highSlope_i R)`. The shortfall `f(R) = hi(R) - lo(R) - R`
    * is convex and piecewise linear, so Newton steps taken from the base span land on each active
    * piece's root and stop at the smallest feasible span; a nonnegative slope on an unsatisfied
    * piece means no span is feasible.
    */
  def frameAxis(base: Interval, marks: Vector[AxisMark]): Interval =
    if marks.isEmpty || !(base.width > 0.0) then base
    else
      def bounds(span: Double): (Double, Double, Double, Double) =
        var top = base.upper
        var topSlope = 0.0
        var bottom = base.lower
        var bottomSlope = 0.0
        var i = 0
        while i < marks.length do
          val mark = marks(i)
          val upper = mark.native + mark.highSlope * span
          val lower = mark.native + mark.lowSlope * span
          if upper > top then
            top = upper
            topSlope = mark.highSlope
          if lower < bottom then
            bottom = lower
            bottomSlope = mark.lowSlope
          i += 1
        (bottom, bottomSlope, top, topSlope)
      var span = base.width
      var result: Option[Interval] = None
      var failed = false
      var steps = 0
      while result.isEmpty && !failed && steps < 64 do
        val (bottom, bottomSlope, top, topSlope) = bounds(span)
        if top - bottom - span <= 1.0e-12 * span then
          result =
            if steps == 0 then Some(base)
            else Interval(bottom, top).toOption
        else
          val slope = topSlope - bottomSlope - 1.0
          if slope >= 0.0 then failed = true
          else
            // Root of the active piece: (top0 + topSlope R) - (bottom0 + bottomSlope R) = R.
            val top0 = top - topSlope * span
            val bottom0 = bottom - bottomSlope * span
            span = (top0 - bottom0) / -slope
        steps += 1
      result.getOrElse(base)
