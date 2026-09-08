package intaglio

final case class ScalarLegendStyle(
  widthPt: Double = 280,
  fontPt: Double = 10,
  barHeightPt: Double = 16,
  marginPt: Double = 8,
  gapPt: Double = 5,
  resolution: Int = 512,
  fontFamily: Option[String] = Some("sans-serif"),
  metrics: TextMetrics = TextMetrics.estimate
):
  require(Vector(widthPt, fontPt, barHeightPt, marginPt, gapPt).forall(_.isFinite))
  require(fontPt > 0 && barHeightPt > 0 && marginPt >= 0 && gapPt >= 0)
  require(widthPt > marginPt * 2 + fontPt * 4, "legend width must leave room for labels")
  require(resolution >= 2 && resolution <= 8192)
  require(fontFamily.forall(_.trim.nonEmpty))

final case class ScalarLegendTickPlacement(
  value: Double,
  fraction: Double,
  label: String,
  leftPt: Double,
  topPt: Double,
  widthPt: Double,
  heightPt: Double
)

final case class ScalarLegendDrawing(
  scene: Scene,
  widthPt: Double,
  heightPt: Double,
  ticks: Vector[ScalarLegendTickPlacement],
  legendKey: String
)

/** A measured, renderer-neutral legend in physical points. Text wraps within
  * the card and colliding tick labels occupy separate rows. Bar cells are
  * sampled image strips with nearest-neighbor rendering. Mapping boundaries
  * split the strips, so interpolation cannot smear threshold or gap colors.
  */
object ScalarLegendDrawing:
  def draw(legend: ScalarLegend, style: ScalarLegendStyle = ScalarLegendStyle(),
    extraNotes: Vector[String] = Vector.empty): Either[LegendError, ScalarLegendDrawing] =
    if extraNotes.exists(_.trim.isEmpty) then return Left(LegendError.InvalidMetadata("supplied legend notes must be nonempty"))
    val available = style.widthPt - style.marginPt * 2
    val textStyle = TextStyle(style.fontFamily, style.fontPt)
    val lineHeight = style.metrics.heightPt(textStyle)
    def width(text: String): Double = style.metrics.widthPt(text, textStyle)
    if !lineHeight.isFinite || lineHeight <= 0 || !width("M").isFinite || width("M") > available then
      return Left(LegendError.InvalidLayout("text metrics do not fit the available width"))
    def wrap(text: String, limit: Double): Vector[String] =
      val lines = Vector.newBuilder[String]
      var current = ""
      text.split("\\s+").foreach: word =>
        var rest = word
        if current.nonEmpty && width(current + " " + rest) <= limit then
          current += " " + rest
          rest = ""
        else if current.nonEmpty then
          lines += current
          current = ""
        while rest.nonEmpty && width(rest) > limit do
          var count = 1
          while count < rest.length && width(rest.take(count + 1)) <= limit do count += 1
          lines += rest.take(count)
          rest = rest.drop(count)
        if rest.nonEmpty then current = rest
      if current.nonEmpty then lines += current
      lines.result()
    val titleLines = wrap(legend.title.text, available)
    val barTop = style.marginPt + titleLines.length * lineHeight + style.gapPt
    val barBottom = barTop + style.barHeightPt
    val labelTop = barBottom + style.gapPt * 2
    val laneEnds = scala.collection.mutable.ArrayBuffer.empty[Double]
    val laneHeights = scala.collection.mutable.ArrayBuffer.empty[Double]
    val labels = legend.ticks.map: tick =>
      val lines = wrap(tick.label, available)
      val labelWidth = lines.map(width).max
      val fraction = legend.position(tick.value)
      val center = style.marginPt + available * fraction
      val left = math.max(style.marginPt, math.min(center - labelWidth * 0.5, style.widthPt - style.marginPt - labelWidth))
      val found = laneEnds.indexWhere(end => left >= end + style.gapPt)
      val lane = if found >= 0 then found else
        laneEnds += Double.NegativeInfinity
        laneHeights += 0
        laneEnds.length - 1
      laneEnds(lane) = left + labelWidth
      laneHeights(lane) = math.max(laneHeights(lane), lines.length * lineHeight)
      (tick, lines, labelWidth, fraction, left, lane)
    val laneTops = laneHeights.scanLeft(labelTop)((top, height) => top + height + style.gapPt)
    val notes = (legend.notes ++ extraNotes).flatMap(text => wrap(text, available))
    val noteTop = laneTops.last + style.gapPt
    val keyTop = noteTop + notes.length * lineHeight + style.gapPt
    val keys = (if legend.showHidden then Vector("Hidden" -> legend.mapping.hidden) else Vector.empty) ++
      (if legend.showInvalid then Vector("Invalid / missing" -> legend.mapping.invalid) else Vector.empty)
    val keyWidth = available - style.barHeightPt - style.gapPt
    if keys.nonEmpty && keyWidth < width("M") then
      return Left(LegendError.InvalidLayout("key swatches leave no room for labels"))
    val keyLabels = keys.map(key => wrap(key._1, keyWidth))
    val keyTops = keyLabels.scanLeft(keyTop)((top, lines) => top + math.max(style.barHeightPt, lines.length * lineHeight) + style.gapPt)
    val height = keyTops.last + style.marginPt
    val grobs = Vector.newBuilder[Grob]
    def point(x: Double, top: Double): Point = Point(LengthExpr(Length.pointsUnsafe(x)), LengthExpr(Length.pointsUnsafe(height - top)))
    def size(w: Double, h: Double): Size = Size.fromExtents(ExtentExpr.pointsUnsafe(w), ExtentExpr.pointsUnsafe(h))
    def rgba(color: Rgba32): Rgba = Rgba.unsafe(color.red, color.green, color.blue, color.alpha.toDouble / 255)
    val textGp = GraphicParams.unsafe(stroke = Some(Rgba.Black), fontSize = Length.pointsUnsafe(style.fontPt), fontFamily = style.fontFamily)
    def textLine(text: String, x: Double, top: Double, name: String): Unit =
      grobs += Grob.textUnsafe(text, point(x, top), anchor = Anchor(HJust.Left, VJust.Top), gp = textGp,
        name = Some(GraphicsName.unsafe(name)))
    def fill(x: Double, top: Double, w: Double, h: Double, color: Rgba32, name: String): Unit =
      grobs += Grob.rectUnsafe(point(x, top), size(w, h), anchor = Anchor(HJust.Left, VJust.Top),
        gp = GraphicParams.unsafe(stroke = None, fill = Some(rgba(color))), name = Some(GraphicsName.unsafe(name)))
    def checker(x: Double, top: Double, w: Double, h: Double, name: String): Unit =
      val tile = style.barHeightPt * 0.5
      val columns = math.ceil(w / tile).toInt
      val rows = math.ceil(h / tile).toInt
      for row <- 0 until rows; col <- 0 until columns do
        val color = if (row + col) % 2 == 0 then Rgba32.unsafe(235, 235, 235) else Rgba32.unsafe(255, 255, 255)
        fill(x + col * tile, top + row * tile, math.min(tile, w - col * tile), math.min(tile, h - row * tile), color, s"$name-$row-$col")
    titleLines.zipWithIndex.foreach((line, i) => textLine(line, style.marginPt, style.marginPt + i * lineHeight, s"legend-title-$i"))
    checker(style.marginPt, barTop, available, style.barHeightPt, "legend-transparency")
    val boundaries = (Vector(0.0, 1.0) ++ legend.mapping.boundaries
      .filter(v => v >= legend.mapping.scale.window.lower && v <= legend.mapping.scale.window.upper).map(legend.position)).distinct.sorted
    boundaries.sliding(2).zipWithIndex.foreach: (pair, i) =>
      val lo = pair(0)
      val span = pair(1) - lo
      val count = math.max(1, math.ceil(style.resolution * span).toInt)
      val image = RasterImage.tabulate(RasterDimensions.unsafe(count, 1)): (x, _) =>
        legend.colorAt(lo + span * (x + 0.5) / count)
      grobs += Grob.imageUnsafe(image, point(style.marginPt + lo * available, barTop),
        size(span * available, style.barHeightPt), anchor = Anchor(HJust.Left, VJust.Top),
        interpolation = RasterInterpolation.Nearest, name = Some(GraphicsName.unsafe(s"legend-strip-$i")))
    grobs += Grob.rectUnsafe(point(style.marginPt, barTop), size(available, style.barHeightPt),
      anchor = Anchor(HJust.Left, VJust.Top), gp = GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = None),
      name = Some(GraphicsName.unsafe("legend-outline")))
    val placed = labels.zipWithIndex.map: (label, i) =>
      val (tick, lines, labelWidth, fraction, left, lane) = label
      val top = laneTops(lane)
      val x = style.marginPt + available * fraction
      grobs += Grob.segments(Vector(point(x, barBottom) -> point(x, barBottom + style.gapPt)),
        textGp, name = Some(GraphicsName.unsafe(s"legend-tick-$i"))).orThrow
      lines.zipWithIndex.foreach((line, n) => textLine(line, left, top + n * lineHeight, s"legend-label-$i-$n"))
      ScalarLegendTickPlacement(tick.value, fraction, tick.label, left, top, labelWidth, lines.length * lineHeight)
    notes.zipWithIndex.foreach((line, i) => textLine(line, style.marginPt, noteTop + i * lineHeight, s"legend-note-$i"))
    keys.zipWithIndex.foreach: (key, i) =>
      val (_, color) = key
      val top = keyTops(i)
      checker(style.marginPt, top, style.barHeightPt, style.barHeightPt, s"legend-key-background-$i")
      fill(style.marginPt, top, style.barHeightPt, style.barHeightPt, color, s"legend-key-color-$i")
      keyLabels(i).zipWithIndex.foreach((line, n) =>
        textLine(line, style.marginPt + style.barHeightPt + style.gapPt, top + n * lineHeight, s"legend-key-label-$i-$n"))
    Right(ScalarLegendDrawing(Scene(grobs.result()), style.widthPt, height, placed, legend.canonicalKey))
