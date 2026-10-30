package intaglio.javafx

import javafx.geometry.{BoundingBox, Bounds, VPos}
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
  * Nonzero letter spacing uses the same native TextFlow paragraph layout as the renderer, including
  * its safe Unicode shaping units. Width includes the requested gaps; vertical metrics remain
  * properties of the face. Measuring does not create the renderer's paragraph snapshot.
  *
  * Headless tests draw runs with [[JavaFxRenderer]] and read the ink at sub-pixel precision: left-
  * against right-justified, the ink moves by `widthPt`, and top- against bottom-justified by
  * `heightPt`, each to within a small fraction of a pixel (well inside 1 px); [[inkBounds]] places
  * every ink edge to within 1 px. Where JavaFX synthesizes a bold face (its `System` family on
  * macOS reports regular advances and outlines for bold), the advance still holds but the drawn
  * strokes extend past [[inkBounds]], by up to 1.7 px at 24 px.
  *
  * Explicit weights first select a registered full face name with a conventional weight suffix,
  * including static Medium/SemiBold faces registered as separate families. Otherwise the selected
  * face follows JavaFX's platform lookup and can differ from the requested weight. `TextStyle` has
  * no posture, and the JavaFX renderer draws no italic, so neither does this provider. A family
  * JavaFX does not have resolves to JavaFX's default family here and in the renderer alike, through
  * the same resolver; [[resolvedFamily]] reports the unweighted family. [[resolvedFontName]] and
  * [[resolvedFontStyle]] expose the full resolved face, so callers can detect a platform
  * substitution within a requested family.
  *
  * AWT and JavaFX lay text out independently, and for the same family and size their advances
  * differ: about 2 pt over an 18-character Arial label at 24 pt on macOS, and far more for a
  * logical family such as `SansSerif`, which each stack maps to its own face. Use this provider for
  * layout that JavaFX draws, and AWT metrics (`Java2DTextMetrics` in `intaglio-java2d`) for Java2D.
  *
  * Threading and initialization: measurement uses an off-scene `javafx.scene.text.Text` node. It
  * does not start the JavaFX platform (`Platform.startup`) and does not need the FX application
  * thread, so the layout solver may call it from any thread. The first measurement does initialize
  * JavaFX's renderer (the Quantum toolkit, its renderer thread and a Prism pipeline), which needs
  * the OpenJFX classes and native libraries and a usable pipeline. If that initialization fails,
  * every measurement throws an `IllegalStateException` that says so, which the layout solver and
  * [[intaglio.TextExtent.measure]] report as `LayoutMeasurementFailed`, rather than JavaFX's raw
  * linkage error.
  *
  * Measurements through this provider are serialized on one lock. That lock does not serialize
  * JavaFX's own text work: JavaFX keeps shared font caches that its application and render threads
  * use, and does not document off-thread measurement as thread-safe. Concurrent measurement off the
  * FX thread has matched on-thread measurement in tests, but that is evidence, not a JavaFX
  * guarantee.
  */
final class JavaFxTextMetrics private () extends TextMetrics:
  override def widthPt(text: String, fontSizePt: Double): Double =
    widthPt(text, TextStyle(None, fontSizePt))

  override def heightPt(fontSizePt: Double): Double =
    heightPt(TextStyle(None, fontSizePt))

  override def widthPt(text: String, style: TextStyle): Double =
    logical(text, style).getWidth

  override def heightPt(style: TextStyle): Double =
    if style.letterSpacingPt != 0 then trackedLine(style).height
    else
      val line = logical(JavaFxTextMetrics.LineProbe, style)
      line.getMaxY - line.getMinY

  /** Distance from the top of the logical line box to the baseline. */
  def ascentPt(style: TextStyle): Double =
    if style.letterSpacingPt != 0 then trackedLine(style).ascent
    else -logical(JavaFxTextMetrics.LineProbe, style).getMinY

  /** Distance from the baseline to the bottom of the logical line box. */
  def descentPt(style: TextStyle): Double =
    if style.letterSpacingPt != 0 then
      val line = trackedLine(style)
      line.height - line.ascent
    else logical(JavaFxTextMetrics.LineProbe, style).getMaxY

  /** The ink: the visual bounds of the glyphs JavaFX draws for `text`, relative to the left end of
    * its baseline (`top` is negative above the baseline). A run with no visible glyphs, such as an
    * empty or all-space label, has an empty box at the origin.
    */
  def inkBounds(text: String, style: TextStyle): JavaFxTextBox =
    if style.letterSpacingPt != 0 then
      val plan = tracked(text, style)
      return plan.ink.fold(JavaFxTextBox(0, 0, 0, 0))(box => box.copy(top = box.top - plan.ascent))
    val visual = bounds(text, style, TextBoundsType.VISUAL)
    if text.isEmpty || visual.isEmpty || visual.getWidth <= 0.0 || visual.getHeight <= 0.0 then
      JavaFxTextBox(0.0, 0.0, 0.0, 0.0)
    else JavaFxTextBox(visual.getMinX, visual.getMinY, visual.getWidth, visual.getHeight)

  /** The family JavaFX resolves `requested` to, a fallback family when it is not installed. */
  def resolvedFamily(requested: Option[String]): String =
    JavaFxTextMetrics.guarded(JavaFxTextMetrics.font(requested, 12.0, None).getFamily)

  /** Full face name JavaFX resolves for `requested` and `weight`, using the same construction as
    * drawing and measurement. For example, a platform may substitute a Light or synthesized face
    * while retaining the requested family name.
    */
  def resolvedFontName(requested: Option[String], weight: Option[FontWeight] = None): String =
    JavaFxTextMetrics.guarded(JavaFxTextMetrics.font(requested, 12.0, weight).getName)

  /** Style JavaFX resolves for `requested` and `weight`; pair with [[resolvedFontName]] when an
    * installed family has platform-specific face mapping.
    */
  def resolvedFontStyle(requested: Option[String], weight: Option[FontWeight] = None): String =
    JavaFxTextMetrics.guarded(JavaFxTextMetrics.font(requested, 12.0, weight).getStyle)

  private def logical(text: String, style: TextStyle): Bounds =
    bounds(text, style, TextBoundsType.LOGICAL)

  private def bounds(text: String, style: TextStyle, kind: TextBoundsType): Bounds =
    JavaFxTextMetrics.guarded {
      if style.letterSpacingPt != 0 then
        val plan = tracked(text, style)
        new BoundingBox(0, -plan.ascent, plan.advance, plan.height)
      else
        JavaFxTextMetrics.lock.synchronized {
          val node = new Text(text)
          node.setFont(JavaFxTextMetrics.font(style.fontFamily, style.fontSizePt, style.fontWeight))
          node.setTextOrigin(VPos.BASELINE)
          node.setBoundsType(kind)
          node.getLayoutBounds
        }
    }

  private def tracked(text: String, style: TextStyle): JavaFxTrackedText =
    JavaFxTrackedText.layout(
      text,
      style.fontFamily,
      style.fontSizePt,
      style.fontWeight,
      style.letterSpacingPt
    )

  private def trackedLine(style: TextStyle): JavaFxTrackedText =
    tracked(JavaFxTextMetrics.LineProbe, style.copy(letterSpacingPt = 0.0))

object JavaFxTextMetrics:
  /** A JavaFX-backed provider. Construction touches no JavaFX state; the first measurement does. */
  def apply(): JavaFxTextMetrics = new JavaFxTextMetrics()

  private val LineProbe = "Mg"
  private val lock = new Object

  /** JavaFX reports a failed renderer initialization as a `LinkageError` (an
    * `ExceptionInInitializerError` first, `NoClassDefFoundError` afterwards), which `NonFatal`
    * handlers, including the layout solver's, deliberately let escape. Measurement turns it into an
    * ordinary exception that names the cause.
    */
  private def guarded[A](body: => A): A =
    try body
    catch
      case error: LinkageError =>
        throw new IllegalStateException(
          "JavaFX text measurement is unavailable: JavaFX could not initialize its renderer " +
            s"($error). Check that the OpenJFX native libraries load and that a Prism pipeline " +
            "(the prism.order system property) is usable on this machine.",
          error
        )

  /** One font construction for drawing and measurement, so a family JavaFX does not have resolves
    * to the same fallback face in both. Some platforms resolve even the family-and-size overload to
    * a Light face; the resolved face accessors expose that substitution.
    */
  private[javafx] def font(family: Option[String], size: Double, weight: Option[FontWeight]): Font =
    (family, weight.map(value => FxFontWeight.findByWeight(value.value))) match
      case (Some(name), Some(face)) =>
        JavaFxFontFaces.resolve(name, face, size).getOrElse(Font.font(name, face, size))
      case (Some(name), None) => Font.font(name, size)
      case (None, Some(face)) => Font.font(null, face, size)
      case (None, None)       => Font.font(size)
