package intaglio

/** Signed extra advance inserted between the tracking units of a text run.
  *
  * The unit remains explicit until device lowering, just as a [[StrokeWidth]] does. A negative
  * value tightens a run; zero preserves the historical text behaviour.
  */
final case class LetterSpacing private (value: Double, unit: StrokeUnit):
  require(value.isFinite, "`value` must be finite")

object LetterSpacing:
  def checked(value: Double, unit: StrokeUnit): Either[GraphicsError, LetterSpacing] =
    if !value.isFinite then Left(GraphicsError.InvalidLength(value))
    else Right(new LetterSpacing(value, unit))

  def unsafe(value: Double, unit: StrokeUnit): LetterSpacing =
    checked(value, unit).orThrow

  def devicePixels(value: Double): Either[GraphicsError, LetterSpacing] =
    checked(value, StrokeUnit.DevicePixel)

  def devicePixelsUnsafe(value: Double): LetterSpacing =
    devicePixels(value).orThrow

  def points(value: Double): Either[GraphicsError, LetterSpacing] =
    checked(value, StrokeUnit.Point)

  def pointsUnsafe(value: Double): LetterSpacing =
    points(value).orThrow
