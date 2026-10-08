package intaglio.javafx

import javafx.geometry.{Bounds, VPos}
import javafx.scene.text.{Font, FontWeight as FxFontWeight, Text, TextBoundsType}
import intaglio.{FontWeight, TextMetrics, TextStyle}

/** [[intaglio.TextMetrics]] measured by JavaFX's own text layout, for layout that must match what
  * [[JavaFxRenderer]] draws. Pass it as `LayoutPolicy(metrics = ...)` or as a render context's
  * `textMetrics`.
  *
  * What it measures, in the TextMetrics contract's units (a size of `n` points measures the face at
  * size `n`, so one unit is one point; at 72 pixels per inch one point is one device pixel):
  *
  *   - `widthPt` is the logical advance width of the run, the width JavaFX's canvas `fillText`
  *     justifies on. It is not the ink width: glyphs sit inside it with side bearings, and an
  *     italic or accented glyph can overhang it. [[inkBounds]] reports the ink.
  *   - `heightPt` is the logical line box, [[ascentPt]] plus [[descentPt]], which `fillText`
  *     positions on for top, centre and bottom baselines. It does not depend on the text.
  *
  * Headless tests draw runs with [[JavaFxRenderer]] and find both within 1 px of the drawn ink:
  * left- against right-justified, the ink moves by `widthPt`; top- against bottom-justified, by
  * `heightPt`. Where JavaFX synthesizes a bold face (its `System` family on macOS reports regular
  * advances and outlines for bold), the advance still holds but the drawn strokes extend past
  * [[inkBounds]], by up to 1.7 px at 24 px.
  *
  * Family, weight and size are honoured; weight resolves to the nearest face JavaFX has, as the
  * renderer resolves it. `TextStyle` has no posture, and the JavaFX renderer draws no italic, so
  * neither does this provider. A family JavaFX does not have resolves to JavaFX's default family
  * here and in the renderer alike, through the same `Font.font` call; [[resolvedFamily]] reports
  * which family that was.
  *
  * AWT and JavaFX lay text out independently, and for the same family and size their advances
  * differ: about 2 pt over an 18-character Arial label at 24 pt on macOS, and far more for a
  * logical family such as `SansSerif`, which each stack maps to its own face. Use this provider for
  * layout that JavaFX draws, and AWT metrics (`Java2DTextMetrics` in `intaglio-java2d`) for Java2D.
  *
  * Threading: measurement uses an off-scene `javafx.scene.text.Text` node. It needs neither the FX
  * application thread nor a started toolkit, so the layout solver may call it from any thread; only
  * the OpenJFX classes must be on the class path. Measurements through every instance are
  * serialized on one lock, because JavaFX does not document its text layout as thread-safe.
  */
final class JavaFxTextMetrics private () extends TextMetrics:
  override def widthPt(text: String, fontSizePt: Double): Double =
    widthPt(text, TextStyle(None, fontSizePt))

  override def heightPt(fontSizePt: Double): Double =
    heightPt(TextStyle(None, fontSizePt))

  override def widthPt(text: String, style: TextStyle): Double =
    logical(text, style).getWidth

  override def heightPt(style: TextStyle): Double =
    val line = logical(JavaFxTextMetrics.LineProbe, style)
    line.getMaxY - line.getMinY

  /** Distance from the top of the logical line box to the baseline. */
  def ascentPt(style: TextStyle): Double =
    -logical(JavaFxTextMetrics.LineProbe, style).getMinY

  /** Distance from the baseline to the bottom of the logical line box. */
  def descentPt(style: TextStyle): Double =
    logical(JavaFxTextMetrics.LineProbe, style).getMaxY

  /** The ink: the visual bounds of the glyphs JavaFX draws for `text`, relative to the left end of
    * its baseline (`top` is negative above the baseline). A run with no visible glyphs, such as an
    * empty or all-space label, has an empty box at the origin.
    */
  def inkBounds(text: String, style: TextStyle): JavaFxTextBox =
    val visual = bounds(text, style, TextBoundsType.VISUAL)
    if text.isEmpty || visual.isEmpty || visual.getWidth <= 0.0 || visual.getHeight <= 0.0 then
      JavaFxTextBox(0.0, 0.0, 0.0, 0.0)
    else JavaFxTextBox(visual.getMinX, visual.getMinY, visual.getWidth, visual.getHeight)

  /** The family JavaFX resolves `requested` to, a fallback family when it is not installed. */
  def resolvedFamily(requested: Option[String]): String =
    JavaFxTextMetrics.font(requested, 12.0, None).getFamily

  private def logical(text: String, style: TextStyle): Bounds =
    bounds(text, style, TextBoundsType.LOGICAL)

  private def bounds(text: String, style: TextStyle, kind: TextBoundsType): Bounds =
    JavaFxTextMetrics.lock.synchronized {
      val node = new Text(text)
      node.setFont(JavaFxTextMetrics.font(style.fontFamily, style.fontSizePt, style.fontWeight))
      node.setTextOrigin(VPos.BASELINE)
      node.setBoundsType(kind)
      node.getLayoutBounds
    }

object JavaFxTextMetrics:
  /** A JavaFX-backed provider. Construction touches no toolkit state. */
  def apply(): JavaFxTextMetrics = new JavaFxTextMetrics()

  private val LineProbe = "Mg"
  private val lock = new Object

  /** One font construction for drawing and measurement, so a family JavaFX does not have resolves
    * to the same fallback face in both.
    */
  private[javafx] def font(family: Option[String], size: Double, weight: Option[FontWeight]): Font =
    (family, weight.map(value => FxFontWeight.findByWeight(value.value))) match
      case (Some(name), Some(face)) => Font.font(name, face, size)
      case (Some(name), None)       => Font.font(name, size)
      case (None, Some(face))       => Font.font(null, face, size)
      case (None, None)             => Font.font(size)
