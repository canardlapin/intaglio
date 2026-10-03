package intaglio.browser

import intaglio.*
import intaglio.interaction.InteractionError

/** Application-supplied presentation for both paint backends. It changes interaction decorations,
  * not data encodings in the compiled plot. CSS overrides remain available on the stable widget
  * classes; the default value installs no inline overrides.
  */
final case class WidgetAppearance(
    hover: Rgba = Rgba.unsafe(11, 110, 79),
    selected: Rgba = Rgba.unsafe(180, 83, 9),
    focus: Rgba = Rgba.unsafe(26, 86, 219),
    focusHalo: Rgba = Rgba.White,
    linked: Rgba = Rgba.unsafe(124, 58, 237),
    tooltipBackground: Rgba = Rgba.unsafe(31, 41, 55),
    tooltipText: Rgba = Rgba.unsafe(249, 250, 251),
    inactiveOpacity: Double = 0.3,
    transitionMs: Int = 160
):
  private[browser] def validate: Either[InteractionError, Unit] =
    if !inactiveOpacity.isFinite || inactiveOpacity < 0 || inactiveOpacity > 1 then
      Left(InteractionError.InvalidValue("inactive opacity", "must be finite and between 0 and 1"))
    else if transitionMs < 0 || transitionMs > 10000 then
      Left(InteractionError.InvalidValue("emphasis transition", "must be between 0 and 10000 ms"))
    else Right(())

  private[browser] def properties: Vector[(String, String)] =
    def css(c: Rgba): String = s"rgba(${c.red},${c.green},${c.blue},${c.alpha})"
    Vector(
      "--intaglio-hover" -> css(hover),
      "--intaglio-selected" -> css(selected),
      "--intaglio-focus" -> css(focus),
      "--intaglio-focus-halo" -> css(focusHalo),
      "--intaglio-linked" -> css(linked),
      "--intaglio-tooltip-background" -> css(tooltipBackground),
      "--intaglio-tooltip-text" -> css(tooltipText),
      "--intaglio-dim" -> inactiveOpacity.toString,
      "--intaglio-transition" -> s"${transitionMs}ms"
    )

object WidgetAppearance:
  val default: WidgetAppearance = WidgetAppearance()
