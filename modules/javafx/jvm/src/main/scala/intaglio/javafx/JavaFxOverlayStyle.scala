package intaglio.javafx

import intaglio.*

enum JavaFxOverlayError extends IntaglioError:
  case InvalidWidth(field: String, value: Double)
  case InvalidOffset(field: String, value: Double)
  case CasingNotWider(field: String)

  def message: String = this match
    case InvalidWidth(field, value)  => s"Overlay $field width must be finite and positive: $value"
    case InvalidOffset(field, value) =>
      s"Overlay $field offset must be finite and nonnegative: $value"
    case CasingNotWider(field) => s"Overlay $field casing must be wider than its stroke"

/** Where an overlay outline runs around a target. */
enum OverlayOutline:
  /** An axis-aligned rectangle around the target's visible bounds: the original host outline. */
  case Bounds

  /** Rings that follow each mark's own geometry: circles, rounded rectangles, and polygons offset
    * with round corners (see `intaglio.interaction.TargetOutline`).
    */
  case Geometry

/** One overlay stroke, in JavaFX logical pixels. A casing, when present, is drawn first beneath it,
  * wider and centred on the same outline, so the stroke reads against any background the casing
  * contrasts with.
  */
final class OverlayStroke private[javafx] (
    val color: Rgba,
    val widthLogicalPx: Double,
    casing: Option[(Rgba, Double)]
):
  /** The casing colour and width, if any. */
  def casingColor: Option[Rgba] = casing.map(_._1)
  def casingWidthLogicalPx: Option[Double] = casing.map(_._2)

object OverlayStroke:
  def apply(color: Rgba, widthLogicalPx: Double): Either[JavaFxOverlayError, OverlayStroke] =
    width("stroke", widthLogicalPx).map(w => new OverlayStroke(color, w, None))

  /** A stroke over a wider casing. */
  def cased(
      color: Rgba,
      widthLogicalPx: Double,
      casingColor: Rgba,
      casingWidthLogicalPx: Double
  ): Either[JavaFxOverlayError, OverlayStroke] =
    for
      w <- width("stroke", widthLogicalPx)
      c <- width("casing", casingWidthLogicalPx)
      _ <- Either.cond(c > w, (), JavaFxOverlayError.CasingNotWider("stroke"))
    yield new OverlayStroke(color, w, Some(casingColor -> c))

  private def width(field: String, value: Double): Either[JavaFxOverlayError, Double] =
    if value.isFinite && value > 0 then Right(value)
    else Left(JavaFxOverlayError.InvalidWidth(field, value))

/** How the JavaFX interaction host draws selection, hover and keyboard focus on its overlay.
  *
  * Selection and hover outlines run `highlightOffsetLogicalPx` outside a target, focus outlines
  * `focusOffsetLogicalPx` outside it; widths and offsets are JavaFX logical pixels, so they keep
  * their on-screen size at every output scale. Hover takes precedence over selection on one target
  * (see `InteractionAppearance`), and focus is always drawn last. The default reproduces the host's
  * original overlay: blue selection and orange hover 2 px wide at 3 px, a black 2 px focus ring on
  * a white 5 px casing at 5 px, around the target's bounds.
  */
final class JavaFxOverlayStyle private (
    val selection: OverlayStroke,
    val hover: OverlayStroke,
    val focus: OverlayStroke,
    val highlightOffsetLogicalPx: Double,
    val focusOffsetLogicalPx: Double,
    val outline: OverlayOutline
):
  /** The same style with outlines placed by `value`. */
  def withOutline(value: OverlayOutline): JavaFxOverlayStyle =
    new JavaFxOverlayStyle(
      selection,
      hover,
      focus,
      highlightOffsetLogicalPx,
      focusOffsetLogicalPx,
      value
    )

  /** The better WCAG 2 contrast ratio that the focus ring or its casing has against `background`.
    * WCAG 2.2's focus-appearance criteria ask for at least 3:1; a dark ring on a light casing (the
    * default) meets it against both light and dark backgrounds.
    */
  def focusContrast(background: Rgba): Double =
    (focus.color +: focus.casingColor.toVector)
      .map(JavaFxOverlayStyle.contrastRatio(_, background))
      .max

object JavaFxOverlayStyle:
  val default: JavaFxOverlayStyle =
    new JavaFxOverlayStyle(
      new OverlayStroke(Rgba.unsafe(0x00, 0x72, 0xb2), 2, None),
      new OverlayStroke(Rgba.unsafe(0xd5, 0x5e, 0x00), 2, None),
      new OverlayStroke(Rgba.Black, 2, Some(Rgba.White -> 5.0)),
      3,
      5,
      OverlayOutline.Bounds
    )

  def apply(
      selection: OverlayStroke,
      hover: OverlayStroke,
      focus: OverlayStroke,
      highlightOffsetLogicalPx: Double = 3,
      focusOffsetLogicalPx: Double = 5,
      outline: OverlayOutline = OverlayOutline.Bounds
  ): Either[JavaFxOverlayError, JavaFxOverlayStyle] =
    def offset(field: String, value: Double) =
      Either.cond(
        value.isFinite && value >= 0,
        value,
        JavaFxOverlayError.InvalidOffset(field, value)
      )
    for
      highlight <- offset("highlight", highlightOffsetLogicalPx)
      focused <- offset("focus", focusOffsetLogicalPx)
    yield new JavaFxOverlayStyle(selection, hover, focus, highlight, focused, outline)

  /** WCAG 2 contrast ratio between two opaque colours' relative luminances, from 1 to 21. Alpha is
    * ignored: a translucent colour's contrast depends on what lies under it.
    */
  def contrastRatio(a: Rgba, b: Rgba): Double =
    def channel(value: Int): Double =
      val c = value / 255.0
      if c <= 0.04045 then c / 12.92 else math.pow((c + 0.055) / 1.055, 2.4)
    def luminance(color: Rgba): Double =
      0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    val (high, low) =
      val (x, y) = (luminance(a), luminance(b))
      if x >= y then (x, y) else (y, x)
    (high + 0.05) / (low + 0.05)
