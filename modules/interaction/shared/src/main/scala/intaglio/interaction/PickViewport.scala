package intaglio.interaction

import intaglio.DevicePoint

/** A centered, aspect-preserving fit of a device scene into a CSS content rectangle.
  *
  * The host supplies client coordinates and the element's content box, excluding borders and
  * padding. Scene dimensions already include render device scale; do not multiply by DPR again.
  * This mapping does not represent nonuniform stretching or CSS rotations/skews.
  */
final class PickViewport private (
    val deviceWidth: Double,
    val deviceHeight: Double,
    val clientLeft: Double,
    val clientTop: Double,
    val cssPixelsPerDevicePixel: Double
):
  /** Letterbox space is outside the plot. Non-finite coordinates are invalid input. */
  def toDevice(clientX: Double, clientY: Double): Either[PickingError, Option[DevicePoint]] =
    if !clientX.isFinite || !clientY.isFinite then Left(PickingError.InvalidInput("client point"))
    else
      val x = (clientX - clientLeft) / cssPixelsPerDevicePixel
      val y = (clientY - clientTop) / cssPixelsPerDevicePixel
      Right(
        Option.when(x >= 0 && x <= deviceWidth && y >= 0 && y <= deviceHeight)(DevicePoint(x, y))
      )

  /** Convert a display-pixel hit radius using the same uniform scale as pointer positions. */
  def tolerance(cssPixels: Double): Either[PickingError, Double] =
    val result = cssPixels / cssPixelsPerDevicePixel
    if !cssPixels.isFinite || cssPixels < 0 || !result.isFinite then
      Left(PickingError.InvalidInput("CSS pixel tolerance"))
    else Right(result)

object PickViewport:
  def fit(
      deviceWidth: Double,
      deviceHeight: Double,
      contentLeft: Double,
      contentTop: Double,
      contentWidth: Double,
      contentHeight: Double
  ): Either[PickingError, PickViewport] =
    val dimensions = Vector(deviceWidth, deviceHeight, contentWidth, contentHeight)
    if dimensions.exists(value => !value.isFinite || value <= 0) ||
      !contentLeft.isFinite || !contentTop.isFinite
    then Left(PickingError.InvalidInput("viewport dimensions"))
    else
      val scale = math.min(contentWidth / deviceWidth, contentHeight / deviceHeight)
      val left = contentLeft + (contentWidth - deviceWidth * scale) / 2
      val top = contentTop + (contentHeight - deviceHeight * scale) / 2
      if !scale.isFinite || scale <= 0 || !left.isFinite || !top.isFinite ||
        !(left + deviceWidth * scale).isFinite || !(top + deviceHeight * scale).isFinite
      then Left(PickingError.InvalidInput("viewport scale"))
      else Right(new PickViewport(deviceWidth, deviceHeight, left, top, scale))
