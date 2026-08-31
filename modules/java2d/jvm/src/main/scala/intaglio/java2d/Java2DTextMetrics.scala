package intaglio.java2d

import java.awt.{Font, GraphicsEnvironment}
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.util.Locale
import intaglio.{FontRegistry, TextMetrics, TextStyle}

/** Opt-in Java2D font measurement for [[intaglio.LayoutPolicy]]. Missing requested families resolve
  * to `fallbackFamily`; if that family is unavailable, Java's logical `SansSerif` family is used.
  */
final class Java2DTextMetrics private (
    val fallbackFamily: String,
    availableFamilies: Map[String, String],
    renderContext: FontRenderContext
) extends TextMetrics:
  override def widthPt(text: String, fontSizePt: Double): Double =
    widthPt(text, TextStyle(None, fontSizePt))

  override def heightPt(fontSizePt: Double): Double =
    heightPt(TextStyle(None, fontSizePt))

  override def widthPt(text: String, style: TextStyle): Double =
    font(style).getStringBounds(text, renderContext).getWidth

  override def heightPt(style: TextStyle): Double =
    font(style).getLineMetrics("Mg", renderContext).getHeight.toDouble

  def resolvedFamily(requested: Option[String]): String =
    requested
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(value => availableFamilies.get(normalize(value)))
      .getOrElse(fallbackFamily)

  /** Resolve rendering families with the same fallback used by these layout metrics. */
  lazy val fontRegistry: FontRegistry =
    FontRegistry(requested => Some(resolvedFamily(requested)))

  private def font(style: TextStyle): Font =
    new Font(resolvedFamily(style.fontFamily), Font.PLAIN, 1).deriveFont(style.fontSizePt.toFloat)

  private def normalize(value: String): String =
    value.toLowerCase(Locale.ROOT)

object Java2DTextMetrics:
  def apply(fallbackFamily: String = Font.SANS_SERIF): Java2DTextMetrics =
    val families =
      GraphicsEnvironment.getLocalGraphicsEnvironment.getAvailableFontFamilyNames.iterator
        .map(value => value.toLowerCase(Locale.ROOT) -> value)
        .toMap
    val requestedFallback = Option(fallbackFamily).map(_.trim).filter(_.nonEmpty)
    val resolvedFallback = requestedFallback
      .flatMap(value => families.get(value.toLowerCase(Locale.ROOT)))
      .orElse(families.get(Font.SANS_SERIF.toLowerCase(Locale.ROOT)))
      .getOrElse(Font.SANS_SERIF)
    val image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    val context =
      try graphics.getFontRenderContext
      finally graphics.dispose()
    new Java2DTextMetrics(resolvedFallback, families, context)
