package intaglio

final case class SwatchLegendStyle(
  widthPt: Double = 280,
  fontPt: Double = 10,
  swatchPt: Double = 16,
  marginPt: Double = 8,
  gapPt: Double = 5,
  fontFamily: Option[String] = Some("sans-serif"),
  metrics: TextMetrics = TextMetrics.estimate
):
  require(Vector(widthPt, fontPt, swatchPt, marginPt, gapPt).forall(_.isFinite))
  require(fontPt > 0 && swatchPt > 0 && marginPt >= 0 && gapPt >= 0)
  require(widthPt > marginPt * 2 + swatchPt + gapPt)
  require(fontFamily.forall(_.trim.nonEmpty))

final case class SwatchLegendPlacement(label: String, color: Rgba32, topPt: Double, heightPt: Double)
final case class SwatchLegendDrawing(scene: Scene, widthPt: Double, heightPt: Double, entries: Vector[SwatchLegendPlacement])

/** Generic categorical or manual color key. Long labels wrap beside their
  * swatch; translucent colors are drawn over a checkerboard.
  */
object SwatchLegendDrawing:
  def draw(title: LegendTitle, entries: Vector[(String, Rgba32)], notes: Vector[String] = Vector.empty,
    style: SwatchLegendStyle = SwatchLegendStyle()): Either[LegendError, SwatchLegendDrawing] =
    if entries.isEmpty || entries.exists(_._1.trim.isEmpty) || notes.exists(_.trim.isEmpty) then
      return Left(LegendError.InvalidMetadata("swatch entries and supplied notes must be nonempty"))
    val available = style.widthPt - style.marginPt * 2
    def wrap(value: String, width: Double): Either[LegendError, LegendTextLayout] =
      LegendTextLayout.wrap(value, width, style.fontPt, style.fontFamily, style.metrics)
    for
      titleLines <- wrap(title.text, available)
      labels <- traverse(entries.map(_._1))(wrap(_, available - style.swatchPt - style.gapPt))
      noteLines <- traverse(notes)(wrap(_, available))
    yield
      val firstTop = style.marginPt + titleLines.heightPt + style.gapPt
      val tops = labels.scanLeft(firstTop)((top, label) => top + math.max(style.swatchPt, label.heightPt) + style.gapPt)
      val noteTops = noteLines.scanLeft(tops.last)((top, note) => top + note.heightPt + style.gapPt)
      val height = noteTops.last + style.marginPt
      val grobs = Vector.newBuilder[Grob]
      val gp = GraphicParams.unsafe(stroke = Some(Rgba.Black), fontSize = Length.pointsUnsafe(style.fontPt), fontFamily = style.fontFamily)
      def point(x: Double, top: Double): Point = Point(LengthExpr(Length.pointsUnsafe(x)), LengthExpr(Length.pointsUnsafe(height - top)))
      def text(layout: LegendTextLayout, x: Double, top: Double, name: String): Unit =
        layout.lines.zipWithIndex.foreach: (line, i) =>
          grobs += Grob.textUnsafe(line, point(x, top + i * layout.lineHeightPt), anchor = Anchor(HJust.Left, VJust.Top),
            gp = gp, name = Some(GraphicsName.unsafe(s"$name-$i")))
      def fill(x: Double, top: Double, w: Double, color: Rgba32, name: String): Unit =
        grobs += Grob.rectUnsafe(point(x, top), Size.fromExtents(ExtentExpr.pointsUnsafe(w), ExtentExpr.pointsUnsafe(w)),
          anchor = Anchor(HJust.Left, VJust.Top), gp = GraphicParams.unsafe(stroke = None,
            fill = Some(Rgba.unsafe(color.red, color.green, color.blue, color.alpha.toDouble / 255))),
          name = Some(GraphicsName.unsafe(name)))
      text(titleLines, style.marginPt, style.marginPt, "swatch-title")
      val placed = entries.zipWithIndex.map: (entry, i) =>
        val top = tops(i)
        for row <- 0 until 2; col <- 0 until 2 do
          val color = if (row + col) % 2 == 0 then Rgba32.unsafe(235, 235, 235) else Rgba32.unsafe(255, 255, 255)
          fill(style.marginPt + col * style.swatchPt / 2, top + row * style.swatchPt / 2, style.swatchPt / 2,
            color, s"swatch-checker-$i-$row-$col")
        fill(style.marginPt, top, style.swatchPt, entry._2, s"swatch-color-$i")
        text(labels(i), style.marginPt + style.swatchPt + style.gapPt, top, s"swatch-label-$i")
        SwatchLegendPlacement(entry._1.trim, entry._2, top, math.max(style.swatchPt, labels(i).heightPt))
      noteLines.zipWithIndex.foreach((note, i) => text(note, style.marginPt, noteTops(i), s"swatch-note-$i"))
      SwatchLegendDrawing(Scene(grobs.result()), style.widthPt, height, placed)

  private def traverse[A, B](values: Vector[A])(f: A => Either[LegendError, B]): Either[LegendError, Vector[B]] =
    values.foldLeft[Either[LegendError, Vector[B]]](Right(Vector.empty)): (result, value) =>
      for previous <- result; next <- f(value) yield previous :+ next
