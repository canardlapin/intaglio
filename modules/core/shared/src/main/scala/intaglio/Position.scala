package intaglio

/** Positive displacement span used when dodging overlapping groups. */
opaque type DodgeWidth = Double

object DodgeWidth:
  def apply(value: Double): Either[GraphicsError, DodgeWidth] =
    if value.isFinite && value > 0.0 then Right(value)
    else Left(GraphicsError.InvalidPositionParameter("dodge", "width", value, "finite and > 0"))

  def unsafe(value: Double): DodgeWidth =
    apply(value).orThrow

  extension (value: DodgeWidth) def toDouble: Double = value

/** Non-negative half-spread for deterministic jitter. */
opaque type JitterAmount = Double

object JitterAmount:
  def apply(value: Double): Either[GraphicsError, JitterAmount] =
    if value.isFinite && value >= 0.0 then Right(value)
    else Left(GraphicsError.InvalidPositionParameter("jitter", "amount", value, "finite and >= 0"))

  def unsafe(value: Double): JitterAmount =
    apply(value).orThrow

  extension (value: JitterAmount) def toDouble: Double = value

/** Seed is a domain value so reproducibility is visible in the plot model. */
opaque type JitterSeed = Long

object JitterSeed:
  def apply(value: Long): JitterSeed = value

  extension (value: JitterSeed) def toLong: Long = value

enum DodgePreserve:
  /** Divide the available span by the groups present at each position. */
  case Total

  /** Reserve a stable slot for every group observed anywhere in the layer. */
  case Single

enum StackOrder:
  case Encountered
  case Reverse

/** An absent width delegates to the resolved band width, then to 0.9 for unbanded positions. This
  * keeps scale geometry authoritative.
  */
final case class DodgeConfig(
    width: Option[DodgeWidth] = None,
    preserve: DodgePreserve = DodgePreserve.Total
)

object DodgeConfig:
  val default: DodgeConfig = DodgeConfig()

  def fixed(
      width: Double,
      preserve: DodgePreserve = DodgePreserve.Total
  ): Either[GraphicsError, DodgeConfig] =
    DodgeWidth(width).map(value => DodgeConfig(Some(value), preserve))

  def fixedUnsafe(width: Double, preserve: DodgePreserve = DodgePreserve.Total): DodgeConfig =
    fixed(width, preserve).orThrow

/** An absent amount follows ggplot2's useful resolution * 0.4 convention. The generator itself is
  * deliberately Intaglio-owned and platform-stable.
  */
final case class JitterConfig(
    width: Option[JitterAmount],
    height: Option[JitterAmount],
    seed: JitterSeed
)

object JitterConfig:
  def apply(
      seed: Long,
      width: Option[Double] = None,
      height: Option[Double] = None
  ): Either[GraphicsError, JitterConfig] =
    for
      x <- traverse(width)(JitterAmount(_))
      y <- traverse(height)(JitterAmount(_))
    yield JitterConfig(x, y, JitterSeed(seed))

  def unsafe(
      seed: Long,
      width: Option[Double] = None,
      height: Option[Double] = None
  ): JitterConfig =
    apply(seed, width, height).orThrow

  private def traverse[A, B](value: Option[A])(
      f: A => Either[GraphicsError, B]
  ): Either[GraphicsError, Option[B]] =
    value match
      case None        => Right(None)
      case Some(input) => f(input).map(Some(_))

/** Pure position transformations. They are values on a layer and are applied by one compiler phase
  * after statistics and before geom lowering.
  */
enum Position:
  case Identity
  case Dodge(config: DodgeConfig = DodgeConfig.default)
  case Stack(order: StackOrder = StackOrder.Reverse)
  case Jitter(config: JitterConfig)

object Position:
  def dodge(
      width: Double,
      preserve: DodgePreserve = DodgePreserve.Total
  ): Either[GraphicsError, Position] =
    DodgeConfig.fixed(width, preserve).map(Position.Dodge(_))

  def dodgeUnsafe(
      width: Double,
      preserve: DodgePreserve = DodgePreserve.Total
  ): Position =
    dodge(width, preserve).orThrow

  def jitter(
      seed: Long,
      width: Option[Double] = None,
      height: Option[Double] = None
  ): Either[GraphicsError, Position] =
    JitterConfig(seed, width, height).map(Position.Jitter(_))

  def jitterUnsafe(
      seed: Long,
      width: Option[Double] = None,
      height: Option[Double] = None
  ): Position =
    jitter(seed, width, height).orThrow
