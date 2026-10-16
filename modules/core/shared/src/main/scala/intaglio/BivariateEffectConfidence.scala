package intaglio

/** Construction failures for a rasterized effect-by-confidence key. */
enum BivariateEffectConfidenceError extends IntaglioError:
  case InvalidKeyResolution(width: Int, height: Int)
  case InvalidTextureSize(value: Int)
  case InvalidTick(axis: String, value: Double)
  case EmptyTicks(axis: String)

  def message: String = this match
    case InvalidKeyResolution(width, height) =>
      s"bivariate key dimensions must lie in [2, 1024]; got ${width}x$height"
    case InvalidTextureSize(value) =>
      s"reference texture size must lie in [2, 4096]; got $value"
    case InvalidTick(axis, value) =>
      s"$axis tick must be finite and inside its mapped domain; got $value"
    case EmptyTicks(axis) => s"$axis ticks must be nonempty"

/** Checked square texture size for the SurfViewJS-compatible policy. */
opaque type BivariateTextureSize = Int

object BivariateTextureSize:
  def make(value: Int): Either[BivariateEffectConfidenceError, BivariateTextureSize] =
    if value >= 2 && value <= 4096 then Right(value)
    else Left(BivariateEffectConfidenceError.InvalidTextureSize(value))

  def unsafe(value: Int): BivariateTextureSize =
    make(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  extension (size: BivariateTextureSize) def value: Int = size

/** How confidence participates in an effect-by-confidence mapping. */
enum BivariateConfidencePolicy:
  /** Match SurfViewJS `ColorMap2D.fromPreset("confidence", textureSize)`: normalize and clamp
    * effect to its X range and confidence to `[0, 1]`, select `floor(t * (N - 1))` texels, then
    * evaluate HSV with `hue = x * 0.8`, `saturation = y`, `value = 1`, and alpha one.
    */
  case SurfViewJsConfidence(textureSize: BivariateTextureSize)

  /** Preserve the earlier direct-alpha policy explicitly rather than confusing it with the
    * SurfViewJS confidence preset.
    */
  case ConfidenceAlpha

/** Immutable effect-by-confidence display policy.
  *
  * The default [[BivariateConfidencePolicy.SurfViewJsConfidence]] reproduces the current SurfViewJS
  * confidence preset's discrete two-dimensional texture lookup. The scalar mapping's window is its
  * X range; its ramp is deliberately not consulted by that reference preset. The named
  * [[BivariateConfidencePolicy.ConfidenceAlpha]] alternative uses the scalar mapping's color and
  * multiplies its stored alpha byte by confidence.
  *
  * In both policies non-finite input is transparent black. This is a defensive ScalaFIM boundary:
  * the JavaScript texture lookup has no finite-value guard.
  */
final case class BivariateEffectConfidenceMapping(
    effect: ScalarMapping,
    policy: BivariateConfidencePolicy = BivariateConfidencePolicy.SurfViewJsConfidence(
      BivariateTextureSize.unsafe(256)
    )
):
  private val transparent = Rgba32.unsafe(0, 0, 0, 0)

  /** Display pixel for one effect/confidence pair. */
  def color(effectValue: Double, confidence: Double): Rgba32 =
    if !effectValue.isFinite || !confidence.isFinite then transparent
    else
      policy match
        case BivariateConfidencePolicy.SurfViewJsConfidence(textureSize) =>
          val coordinate = referenceCoordinate(effectValue, confidence, textureSize.value)
          BivariateEffectConfidenceMapping.confidencePixel(
            coordinate.effectIndex.toDouble / (textureSize.value - 1).toDouble,
            coordinate.confidenceIndex.toDouble / (textureSize.value - 1).toDouble
          )
        case BivariateConfidencePolicy.ConfidenceAlpha =>
          if confidence < 0.0 || confidence > 1.0 then transparent
          else
            val base = effect.color(effectValue)
            Rgba32.packUnsafe(
              base.red,
              base.green,
              base.blue,
              math.round(base.alpha.toDouble * confidence).toInt
            )

  /** Discrete texture coordinate used by the reference-compatible policy. */
  def referenceCoordinate(
      effectValue: Double,
      confidence: Double
  ): Option[BivariateTextureCoordinate] =
    if !effectValue.isFinite || !confidence.isFinite then None
    else
      policy match
        case BivariateConfidencePolicy.SurfViewJsConfidence(textureSize) =>
          Some(referenceCoordinate(effectValue, confidence, textureSize.value))
        case BivariateConfidencePolicy.ConfidenceAlpha => None

  private def referenceCoordinate(
      effectValue: Double,
      confidence: Double,
      textureSize: Int
  ): BivariateTextureCoordinate =
    val effectFraction = effect.scale.window.normalize(effectValue)
    val confidenceFraction = BivariateEffectConfidenceMapping.normalizeUnit(confidence)
    BivariateTextureCoordinate(
      math.floor(effectFraction * (textureSize - 1)).toInt,
      math.floor(confidenceFraction * (textureSize - 1)).toInt
    )

  /** Complete, versioned identity for caches and serialized inspection. */
  lazy val canonicalKey: String =
    val encodedEffect = s"${effect.canonicalKey.length}:${effect.canonicalKey}"
    val policyKey = policy match
      case BivariateConfidencePolicy.SurfViewJsConfidence(textureSize) =>
        s"surfviewjs-confidence:${textureSize.value}"
      case BivariateConfidencePolicy.ConfidenceAlpha =>
        "confidence-alpha:round(effect-alpha*confidence)"
    s"bivariate-effect-confidence-v2|$encodedEffect|$policyKey"

final case class BivariateTextureCoordinate(effectIndex: Int, confidenceIndex: Int)

object BivariateEffectConfidenceMapping:
  /** The exact HSV branch calculation used by SurfViewJS `ColorMap2D.hsvToRgb`. Each component
    * rounds through `Float` before byte packing, matching the reference texture's `Float32Array`
    * storage before it is sampled.
    */
  private[intaglio] def confidencePixel(
      effectFraction: Double,
      confidenceFraction: Double
  ): Rgba32 =
    val hue = effectFraction * 0.8
    val (red, green, blue) = hsvToRgb(hue, confidenceFraction, 1.0)
    Rgba32.packUnsafe(
      math.round(red.toFloat.toDouble * 255.0).toInt,
      math.round(green.toFloat.toDouble * 255.0).toInt,
      math.round(blue.toFloat.toDouble * 255.0).toInt,
      255
    )

  private def normalizeUnit(value: Double): Double =
    math.max(0.0, math.min(1.0, value))

  private def hsvToRgb(hue: Double, saturation: Double, value: Double): (Double, Double, Double) =
    val sector = math.floor(hue * 6.0).toInt
    val fraction = hue * 6.0 - sector
    val p = value * (1.0 - saturation)
    val q = value * (1.0 - fraction * saturation)
    val t = value * (1.0 - (1.0 - fraction) * saturation)
    sector % 6 match
      case 0 => (value, t, p)
      case 1 => (q, value, p)
      case 2 => (p, value, t)
      case 3 => (p, q, value)
      case 4 => (t, p, value)
      case 5 => (value, p, q)
      case _ => (0.0, 0.0, 0.0)

/** Inspectable raster key for [[BivariateEffectConfidenceMapping]]. Rows run from high confidence
  * at the top to zero confidence at the bottom; columns run from the effect window's lower bound at
  * the left to its upper bound at the right. Its pixels are always evaluated by [[mapping]], so a
  * key cannot drift from the surface display policy it documents.
  */
final class BivariateEffectConfidenceKey private (
    val mapping: BivariateEffectConfidenceMapping,
    val effectTitle: LegendTitle,
    val confidenceTitle: LegendTitle,
    val effectTicks: Vector[AxisTick],
    val confidenceTicks: Vector[AxisTick],
    val image: RasterImage
):
  def width: Int = image.width
  def height: Int = image.height

  def effectAt(column: Int): Double =
    require(column >= 0 && column < width, "key column must be inside the image")
    val fraction = column.toDouble / (width - 1).toDouble
    val window = mapping.effect.scale.window
    (1.0 - fraction) * window.lower + fraction * window.upper

  def confidenceAt(row: Int): Double =
    require(row >= 0 && row < height, "key row must be inside the image")
    1.0 - row.toDouble / (height - 1).toDouble

  def colorAt(effectValue: Double, confidence: Double): Rgba32 =
    mapping.color(effectValue, confidence)

  def pixelAt(column: Int, row: Int): Rgba32 =
    image.pixelUnsafe(column, row)

  lazy val canonicalKey: String =
    def text(value: String): String = s"${value.length}:$value"
    def number(value: Double): String =
      java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(if value == 0.0 then 0.0
      else value))
    def ticks(values: Vector[AxisTick]): String =
      values.map(tick => s"${number(tick.value)}:${text(tick.label)}").mkString(";")
    s"bivariate-effect-confidence-key-v1|${text(mapping.canonicalKey)}|${text(effectTitle.text)}|${text(confidenceTitle.text)}|${width}x$height|${ticks(effectTicks)}|${ticks(confidenceTicks)}"

object BivariateEffectConfidenceKey:
  def make(
      mapping: BivariateEffectConfidenceMapping,
      effectTitle: LegendTitle,
      confidenceTitle: LegendTitle,
      effectTicks: Vector[AxisTick],
      confidenceTicks: Vector[AxisTick],
      width: Int = 65,
      height: Int = 65
  ): Either[BivariateEffectConfidenceError, BivariateEffectConfidenceKey] =
    val effectWindow = mapping.effect.scale.window
    if width < 2 || width > 1024 || height < 2 || height > 1024 then
      Left(BivariateEffectConfidenceError.InvalidKeyResolution(width, height))
    else if effectTicks.isEmpty then Left(BivariateEffectConfidenceError.EmptyTicks("effect"))
    else if confidenceTicks.isEmpty then
      Left(BivariateEffectConfidenceError.EmptyTicks("confidence"))
    else if effectTicks.exists(tick =>
        tick.value < effectWindow.lower || tick.value > effectWindow.upper
      )
    then
      Left(
        BivariateEffectConfidenceError.InvalidTick(
          "effect",
          effectTicks
            .find(tick => tick.value < effectWindow.lower || tick.value > effectWindow.upper)
            .get
            .value
        )
      )
    else if confidenceTicks.exists(tick => tick.value < 0.0 || tick.value > 1.0) then
      Left(
        BivariateEffectConfidenceError.InvalidTick(
          "confidence",
          confidenceTicks.find(tick => tick.value < 0.0 || tick.value > 1.0).get.value
        )
      )
    else
      val dimensions = RasterDimensions.unsafe(width, height)
      val image = RasterImage.tabulate(dimensions): (column, row) =>
        val effectFraction = column.toDouble / (width - 1).toDouble
        val effectValue =
          (1.0 - effectFraction) * effectWindow.lower + effectFraction * effectWindow.upper
        val confidence = 1.0 - row.toDouble / (height - 1).toDouble
        mapping.color(effectValue, confidence)
      Right(
        new BivariateEffectConfidenceKey(
          mapping,
          effectTitle,
          confidenceTitle,
          effectTicks,
          confidenceTicks,
          image
        )
      )
