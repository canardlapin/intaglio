package intaglio

enum DisplayError extends IntaglioError:
  case InvalidWindow(lower: Double, upper: Double)
  case InvalidThresholdBand(lower: Double, upper: Double)
  case InvalidOpacity(value: Double)

  def message: String =
    this match
      case InvalidWindow(lower, upper) =>
        s"display window must have finite lower < upper; got [$lower, $upper]"
      case InvalidThresholdBand(lower, upper) =>
        s"display threshold band must have finite lower < upper; got [$lower, $upper]"
      case InvalidOpacity(value) =>
        s"display opacity must be finite and in [0, 1]; got $value"

object DisplayError:
  extension [A](either: Either[DisplayError, A])
    def orThrow: A =
      either match
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(error.message)

final case class DisplayWindow private (lower: Double, upper: Double):
  def width: Double =
    upper - lower

  def normalize(value: Double): Double =
    math.max(0.0, math.min(1.0, (value - lower) / width))

object DisplayWindow:
  def make(lower: Double, upper: Double): Either[DisplayError, DisplayWindow] =
    if lower.isFinite && upper.isFinite && lower < upper then Right(new DisplayWindow(lower, upper))
    else Left(DisplayError.InvalidWindow(lower, upper))

  def unsafe(lower: Double, upper: Double): DisplayWindow =
    make(lower, upper).orThrow

final case class ThresholdBand private (lower: Double, upper: Double):
  def contains(value: Double): Boolean =
    value > lower && value < upper

object ThresholdBand:
  def make(lower: Double, upper: Double): Either[DisplayError, ThresholdBand] =
    if lower.isFinite && upper.isFinite && lower < upper then Right(new ThresholdBand(lower, upper))
    else Left(DisplayError.InvalidThresholdBand(lower, upper))

  def unsafe(lower: Double, upper: Double): ThresholdBand =
    make(lower, upper).orThrow

enum DisplayThreshold:
  case Disabled
  case TransparentBand(band: ThresholdBand)

  def hides(value: Double): Boolean =
    this match
      case Disabled              => false
      case TransparentBand(band) => band.contains(value)

object DisplayThreshold:
  def transparentBand(lower: Double, upper: Double): Either[DisplayError, DisplayThreshold] =
    ThresholdBand.make(lower, upper).map(DisplayThreshold.TransparentBand.apply)

opaque type DisplayOpacity = Double

object DisplayOpacity:
  def make(value: Double): Either[DisplayError, DisplayOpacity] =
    if value.isFinite && value >= 0.0 && value <= 1.0 then Right(value)
    else Left(DisplayError.InvalidOpacity(value))

  def unsafe(value: Double): DisplayOpacity =
    make(value).orThrow

  val Transparent: DisplayOpacity =
    0.0

  val Opaque: DisplayOpacity =
    1.0

  def value(opacity: DisplayOpacity): Double =
    opacity

  extension (opacity: DisplayOpacity) def toDouble: Double = DisplayOpacity.value(opacity)

enum DisplayBlendMode:
  case Normal, Additive, Multiply, Screen

  /** Composite `over` onto `under` using source-over alpha and this mode's separable channel blend.
    * Computation is performed in the stored sRGB channel space because `Rgba32` deliberately
    * carries display bytes rather than a linear-light color profile.
    */
  def composite(
      under: Rgba32,
      over: Rgba32,
      opacity: DisplayOpacity = DisplayOpacity.Opaque
  ): Rgba32 =
    val underAlpha = under.alpha.toDouble / 255.0
    val overAlpha = over.alpha.toDouble / 255.0 * opacity.toDouble
    val outAlpha = overAlpha + underAlpha * (1.0 - overAlpha)

    if outAlpha == 0.0 then Rgba32.unsafe(0, 0, 0, 0)
    else
      def channel(underByte: Int, overByte: Int): Int =
        val backdrop = underByte.toDouble / 255.0
        val source = overByte.toDouble / 255.0
        val blended =
          this match
            case Normal   => source
            case Additive => math.min(1.0, backdrop + source)
            case Multiply => backdrop * source
            case Screen   => 1.0 - (1.0 - backdrop) * (1.0 - source)
        val premultiplied =
          (1.0 - overAlpha) * underAlpha * backdrop +
            (1.0 - underAlpha) * overAlpha * source +
            underAlpha * overAlpha * blended
        math.round(premultiplied / outAlpha * 255.0).toInt.max(0).min(255)

      Rgba32.packUnsafe(
        channel(under.red, over.red),
        channel(under.green, over.green),
        channel(under.blue, over.blue),
        math.round(outAlpha * 255.0).toInt.max(0).min(255)
      )

/** A value-to-pixel mapping.
  *
  * `windowing` and `thresholding` are the single statement of each adjustment capability: `Some`
  * iff this colorizer can rebuild itself for a new window or threshold. The capability predicate
  * and the builder are both `final` and derived from them, so an implementation cannot claim
  * support it does not provide. Callers that only need to ask — before they hold a `DisplayWindow`
  * to try — use `supportsWindow`/`supportsThreshold`.
  */
trait Colorizer[A]:
  def color(value: A): Rgba32

  def windowing: Option[DisplayWindow => Colorizer[A]] =
    None

  def thresholding: Option[DisplayThreshold => Colorizer[A]] =
    None

  final def supportsWindow: Boolean =
    windowing.isDefined

  final def withWindow(window: DisplayWindow): Option[Colorizer[A]] =
    windowing.map(_(window))

  final def supportsThreshold: Boolean =
    thresholding.isDefined

  final def withThreshold(threshold: DisplayThreshold): Option[Colorizer[A]] =
    thresholding.map(_(threshold))

final case class ColorRamp(low: Rgba32, high: Rgba32):
  def colorAt(fraction: Double): Rgba32 =
    val t = math.max(0.0, math.min(1.0, fraction))
    def channel(from: Int, to: Int): Int =
      math.round(from + (to - from) * t).toInt
    Rgba32.packUnsafe(
      channel(low.red, high.red),
      channel(low.green, high.green),
      channel(low.blue, high.blue),
      channel(low.alpha, high.alpha)
    )

object ColorRamp:
  val Grayscale: ColorRamp =
    ColorRamp(Rgba32.unsafe(0, 0, 0), Rgba32.unsafe(255, 255, 255))

  val Heat: ColorRamp =
    ColorRamp(Rgba32.unsafe(0, 0, 0), Rgba32.unsafe(255, 96, 0))

final case class ScalarColorizer(
    window: DisplayWindow,
    ramp: ColorRamp = ColorRamp.Grayscale,
    invalid: Rgba32 = Rgba32.unsafe(0, 0, 0, 0),
    threshold: DisplayThreshold = DisplayThreshold.Disabled
) extends Colorizer[Double]:
  def color(value: Double): Rgba32 =
    if !value.isFinite then invalid
    else if threshold.hides(value) then ScalarColorizer.Transparent
    else ramp.colorAt(window.normalize(value))

  override def windowing: Option[DisplayWindow => Colorizer[Double]] =
    Some(value => copy(window = value))

  override def thresholding: Option[DisplayThreshold => Colorizer[Double]] =
    Some(value => copy(threshold = value))

object ScalarColorizer:
  private val Transparent: Rgba32 =
    Rgba32.unsafe(0, 0, 0, 0)

final case class LabelColorizer(
    colors: Map[Int, Rgba32],
    fallback: Rgba32 = Rgba32.unsafe(0, 0, 0, 0)
) extends Colorizer[Int]:
  def color(value: Int): Rgba32 =
    colors.getOrElse(value, fallback)

final case class MaskColorizer(
    foreground: Rgba32,
    background: Rgba32 = Rgba32.unsafe(0, 0, 0, 0)
) extends Colorizer[Boolean]:
  def color(value: Boolean): Rgba32 =
    if value then foreground else background

object Colorizer:
  def constant[A](pixel: Rgba32): Colorizer[A] =
    new Colorizer[A]:
      def color(value: A): Rgba32 = pixel
