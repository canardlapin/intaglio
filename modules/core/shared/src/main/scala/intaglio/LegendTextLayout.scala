package intaglio

/** Measured lines for legends and publication annotations, in physical points. */
final case class LegendTextLayout private (
  lines: Vector[String],
  lineHeightPt: Double,
  widthPt: Double
):
  def heightPt: Double = lines.length * lineHeightPt

object LegendTextLayout:
  def wrap(text: String, widthPt: Double, fontPt: Double, fontFamily: Option[String] = Some("sans-serif"),
    metrics: TextMetrics = TextMetrics.estimate): Either[LegendError, LegendTextLayout] =
    if !widthPt.isFinite || widthPt <= 0 || !fontPt.isFinite || fontPt <= 0 || fontFamily.exists(_.trim.isEmpty) then
      return Left(LegendError.InvalidLayout("text width and font must be positive and finite"))
    val style = TextStyle(fontFamily, fontPt)
    val height = metrics.heightPt(style)
    def width(value: String): Double = metrics.widthPt(value, style)
    if !height.isFinite || height <= 0 then return Left(LegendError.InvalidLayout("text height must be positive and finite"))
    val lines = Vector.newBuilder[String]
    var current = ""
    val words = text.trim.split("\\s+").filter(_.nonEmpty)
    var index = 0
    while index < words.length do
      var rest = words(index)
      val combined = if current.isEmpty then rest else current + " " + rest
      val measured = width(combined)
      if !measured.isFinite || measured < 0 then return Left(LegendError.InvalidLayout("text width must be finite and nonnegative"))
      if measured <= widthPt then
        current = combined
        rest = ""
      else if current.nonEmpty then
        lines += current
        current = ""
      while rest.nonEmpty && width(rest) > widthPt do
        var count = Character.charCount(rest.codePointAt(0))
        if width(rest.take(count)) > widthPt then return Left(LegendError.InvalidLayout("a text character exceeds the available width"))
        var next = count
        while next < rest.length && width(rest.take(next + Character.charCount(rest.codePointAt(next)))) <= widthPt do
          next += Character.charCount(rest.codePointAt(next))
        count = next
        lines += rest.take(count)
        rest = rest.drop(count)
      if rest.nonEmpty then current = rest
      index += 1
    if current.nonEmpty then lines += current
    Right(new LegendTextLayout(lines.result(), height, widthPt))
