package intaglio.canvas

import scala.scalajs.js
import intaglio.{TextMetrics, TextStyle}

@js.native
trait CanvasTextMeasurement extends js.Object:
  val width: Double = js.native
  val actualBoundingBoxAscent: js.UndefOr[Double] = js.native
  val actualBoundingBoxDescent: js.UndefOr[Double] = js.native

/** Opt-in browser Canvas font measurement for [[intaglio.LayoutPolicy]]. `familyAvailable` should
  * query the caller's loaded-font environment (for example `document.fonts.check`). A missing
  * requested family resolves to the explicit CSS fallback family.
  */
final class CanvasTextMetrics(
    context: CanvasRenderingContext2D,
    val fallbackFamily: String = "sans-serif",
    familyAvailable: String => Boolean = _ => true
) extends TextMetrics:
  private val pixelsPerPoint = 96.0 / 72.0
  private val resolvedFallback =
    Option(fallbackFamily).map(_.trim).filter(_.nonEmpty).getOrElse("sans-serif")

  override def widthPt(text: String, fontSizePt: Double): Double =
    widthPt(text, TextStyle(None, fontSizePt))

  override def heightPt(fontSizePt: Double): Double =
    heightPt(TextStyle(None, fontSizePt))

  override def widthPt(text: String, style: TextStyle): Double =
    val measured = measure(text, style).width / pixelsPerPoint
    if measured >= 0.0 && measured.isFinite then measured
    else TextMetrics.estimate.widthPt(text, style)

  override def heightPt(style: TextStyle): Double =
    val measured = measure("Mg", style)
    val ascent = measured.actualBoundingBoxAscent.toOption
    val descent = measured.actualBoundingBoxDescent.toOption
    (ascent, descent) match
      case (Some(up), Some(down))
          if up >= 0.0 && down >= 0.0 && (up + down).isFinite && up + down > 0.0 =>
        (up + down) / pixelsPerPoint
      case _ =>
        TextMetrics.estimate.heightPt(style)

  def resolvedFamily(requested: Option[String]): String =
    requested
      .map(_.trim)
      .filter(value => value.nonEmpty && familyAvailable(value))
      .getOrElse(resolvedFallback)

  private def measure(text: String, style: TextStyle): CanvasTextMeasurement =
    context.save()
    try
      val sizePx = style.fontSizePt * pixelsPerPoint
      context.font = s"${sizePx}px ${cssFamily(resolvedFamily(style.fontFamily))}"
      context.measureText(text)
    finally context.restore()

  private def cssFamily(value: String): String =
    value.toLowerCase match
      case "serif" | "sans-serif" | "monospace" | "cursive" | "fantasy" | "system-ui" => value
      case _ => "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
