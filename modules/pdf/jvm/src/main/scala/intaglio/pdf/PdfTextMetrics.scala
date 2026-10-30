package intaglio.pdf

import java.awt.Font
import java.awt.font.FontRenderContext
import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDType0Font
import intaglio.*

/** Layout metrics from the exact bytes that [[PdfRenderer]] embeds. Zero tracking uses a scoped
  * PDFBox subset face, matching the native `showText` path; tracked text uses the same AWT shaping
  * plan as the positioned-glyph branch. Temporary PDF documents never escape a measurement call.
  */
final class PdfTextMetrics private[pdf] (catalog: PdfFontCatalog) extends TextMetrics:
  private val context = new FontRenderContext(null, true, true)
  private val awtFonts = new ConcurrentHashMap[PdfFont, Font]()

  override def widthPt(text: String, fontSizePt: Double): Double =
    widthPt(text, TextStyle(None, fontSizePt))

  override def widthPt(text: String, style: TextStyle): Double =
    val supplied = resolve(style)
    if style.letterSpacingPt == 0.0 then nativeWidth(supplied, text, style.fontSizePt)
    else
      TrackedGlyphs
        .layout(
          text,
          awt(supplied).deriveFont(style.fontSizePt.toFloat),
          context,
          style.letterSpacingPt
        )
        .advance

  override def heightPt(fontSizePt: Double): Double =
    heightPt(TextStyle(None, fontSizePt))

  override def heightPt(style: TextStyle): Double =
    val supplied = resolve(style)
    if style.letterSpacingPt == 0.0 then nativeHeight(supplied, style.fontSizePt)
    else
      val metrics = awt(supplied).deriveFont(style.fontSizePt.toFloat).getLineMetrics("Mg", context)
      metrics.getAscent + metrics.getDescent

  private def resolve(style: TextStyle): PdfFont =
    catalog.resolve(style.fontFamily, style.fontWeight).getOrElse {
      throw new IllegalArgumentException(
        s"No embedded PDF font for ${style.fontFamily.getOrElse("default")}"
      )
    }

  private def awt(supplied: PdfFont): Font =
    awtFonts.computeIfAbsent(
      supplied,
      face =>
        val input = face.inputStream
        try Font.createFont(Font.TRUETYPE_FONT, input)
        catch
          case NonFatal(error) =>
            throw new IllegalArgumentException(s"Could not load PDF font '${face.family}'", error)
        finally input.close()
    )

  private def nativeWidth(supplied: PdfFont, text: String, size: Double): Double =
    withPdfFont(supplied) { font =>
      font.getStringWidth(text).toDouble / 1000.0 * size
    }

  private def nativeHeight(supplied: PdfFont, size: Double): Double =
    withPdfFont(supplied) { font =>
      val descriptor = font.getFontDescriptor
      (descriptor.getAscent.toDouble - descriptor.getDescent.toDouble) / 1000.0 * size
    }

  private def withPdfFont[A](supplied: PdfFont)(body: PDType0Font => A): A =
    val document = new PDDocument()
    val input = supplied.inputStream
    try body(PDType0Font.load(document, input, true))
    finally
      try input.close()
      finally document.close()
