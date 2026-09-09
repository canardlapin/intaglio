package intaglio

/** The sRGB transfer function of IEC 61966-2-1, in one place.
  *
  * [[Rgba]] and [[Rgba32]] carry display bytes and no colour profile, so every operation that needs
  * light rather than bytes — perceptual mixing, colour-vision simulation, CIELAB distance — has to
  * name the encoding it is undoing. Naming it once means those operations cannot drift apart.
  */
private[intaglio] object Srgb:
  /** Decode one stored channel byte to linear light in `[0, 1]`. */
  def toLinear(channel: Int): Double =
    val encoded = channel.toDouble / 255.0
    if encoded <= 0.04045 then encoded / 12.92
    else math.pow((encoded + 0.055) / 1.055, 2.4)

  /** Encode linear light back to a stored channel byte, clamping into gamut and rounding half up.
    */
  def toChannel(linear: Double): Int =
    val clamped = Oklab.clampUnit(linear)
    val encoded =
      if clamped <= 0.0031308 then clamped * 12.92
      else 1.055 * math.pow(clamped, 1.0 / 2.4) - 0.055
    math.round(encoded * 255.0).toInt.max(0).min(255)

/** A colour in the Oklab perceptual space: `lightness` runs `0` to `1` across the sRGB gamut, and
  * `a` (green to red) and `b` (blue to yellow) are opponent axes that stay inside about
  * `[-0.4, 0.4]` for displayable colours.
  *
  * Oklab is here for one reason. Interpolating a ramp channel by channel in sRGB walks through
  * whatever colour the encoded bytes happen to name between the endpoints, and for hue-distant
  * endpoints that path collapses to a near-neutral grey: `#0000FF` to `#FFFF00` passes through
  * `C* = 0`. A ramp whose whole job is to carry magnitude cannot have a dead zone in the middle of
  * it. Mixing in Oklab keeps chroma along the path and keeps the perceptual step size roughly even.
  *
  * Conversion reads [[Rgba]] channels as sRGB under IEC 61966-2-1 with a D65 white point. [[Rgba]]
  * and [[Rgba32]] deliberately carry display bytes and no colour profile of their own, so that
  * interpretation is stated here rather than assumed. There is no chromatic adaptation, no
  * alternative white point, and no ICC pipeline: this is a perceptual mixing space, not a colour
  * management system.
  */
final case class Oklab private (lightness: Double, a: Double, b: Double)

object Oklab:
  def apply(lightness: Double, a: Double, b: Double): Either[GraphicsError, Oklab] =
    if !lightness.isFinite then Left(GraphicsError.NonFiniteColorComponent("lightness", lightness))
    else if !a.isFinite then Left(GraphicsError.NonFiniteColorComponent("a", a))
    else if !b.isFinite then Left(GraphicsError.NonFiniteColorComponent("b", b))
    else Right(new Oklab(lightness, a, b))

  def unsafe(lightness: Double, a: Double, b: Double): Oklab =
    apply(lightness, a, b).orThrow

  def fromRgba(color: Rgba): Oklab =
    fromLinear(toLinear(color.red), toLinear(color.green), toLinear(color.blue))

  /** Encode back to opaque sRGB. Alpha is not a perceptual quantity and is not carried through the
    * mixing space; attach it afterwards with [[Rgba.withAlpha]].
    *
    * A coordinate outside the sRGB gamut has no exact encoding, so linear light is clamped into
    * `[0, 1]` per channel before the transfer function. Clamping preserves lightness ordering along
    * a ramp, which is what a magnitude ramp needs, at the cost of some chroma at the extremes. It
    * is total: every finite [[Oklab]] value yields a valid [[Rgba]].
    */
  def toRgba(color: Oklab): Rgba =
    val lPrime = color.lightness + 0.3963377774 * color.a + 0.2158037573 * color.b
    val mPrime = color.lightness - 0.1055613458 * color.a - 0.0638541728 * color.b
    val sPrime = color.lightness - 0.0894841775 * color.a - 1.2914855480 * color.b
    val l = lPrime * lPrime * lPrime
    val m = mPrime * mPrime * mPrime
    val s = sPrime * sPrime * sPrime
    Rgba.unsafe(
      toChannel(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
      toChannel(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
      toChannel(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    )

  /** Linear interpolation in Oklab. `fraction` is clamped to `[0, 1]`, so an infinite fraction
    * returns the endpoint of its sign; `NaN` has no position on the ramp and returns `from`. Both
    * endpoints are returned identically rather than recomputed, because `from + (to - from) * 1.0`
    * is not `to` in binary floating point and a ramp that misses its own endpoint by one byte is a
    * ramp that cannot be pinned.
    */
  def mix(from: Oklab, to: Oklab, fraction: Double): Oklab =
    val t = clampUnit(fraction)
    if t == 0.0 then from
    else if t == 1.0 then to
    else
      new Oklab(
        lerp(from.lightness, to.lightness, t),
        lerp(from.a, to.a, t),
        lerp(from.b, to.b, t)
      )

  /** Endpoint-exact linear interpolation for scalars carried alongside a mix, such as alpha. */
  private[intaglio] def lerp(from: Double, to: Double, fraction: Double): Double =
    if fraction == 0.0 then from
    else if fraction == 1.0 then to
    else from + (to - from) * fraction

  private def fromLinear(red: Double, green: Double, blue: Double): Oklab =
    val l = math.cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
    val m = math.cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
    val s = math.cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
    new Oklab(
      0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
      1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
      0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    )

  private def toLinear(channel: Int): Double =
    Srgb.toLinear(channel)

  private def toChannel(linear: Double): Int =
    Srgb.toChannel(linear)

  /** Clamp into `[0, 1]`, mapping a non-finite argument to `0`. `math.min`/`math.max` propagate
    * `NaN`, and a `NaN` channel would silently pack as byte zero, so the guard is explicit.
    */
  private[intaglio] def clampUnit(value: Double): Double =
    if value.isNaN then 0.0
    else if value < 0.0 then 0.0
    else if value > 1.0 then 1.0
    else value

/** A signed colour ramp with an explicit neutral at zero.
  *
  * The domain is signed and symmetric: `-1` is `negative`, `0` is exactly `neutral`, and `+1` is
  * `positive`. The sign of the argument names the arm, so two call sites in the same application
  * cannot quietly adopt opposite conventions — which is the failure this type exists to prevent. A
  * caller that has a magnitude rather than a signed fraction is asking the wrong question of a
  * diverging ramp.
  *
  * Both output forms come from one interpolation. [[pixel]] is [[color]] packed by
  * [[Rgba32.fromRgba]], so a scene mark and a raster overlay showing the same value are the same
  * colour by construction rather than by convention. Mixing runs through [[Oklab]], and each arm is
  * interpolated from `neutral` outward. The three named colours are returned as themselves at `-1`,
  * `0`, and `+1` rather than round-tripped through the mixing space, so an endpoint and the neutral
  * are exact on every platform rather than exact because the round trip happens to be.
  *
  * An infinite fraction is a saturated magnitude and clamps to the endpoint of its sign. `NaN` has
  * neither a sign nor a magnitude and returns `neutral` — including through [[unitPalette]], where
  * the naive reading of `NaN` would be the negative endpoint. Data that may be `NaN` should not
  * reach a palette at all: [[DivergingColorizer]] routes it to an explicit invalid pixel, and
  * `OobPolicy.Censor` drops it before a scale consults the palette.
  */
final case class DivergingPalette private (negative: Rgba, neutral: Rgba, positive: Rgba):
  /** Endpoint coordinates are converted once per palette rather than once per sample: a raster
    * overlay calls [[pixel]] for every pixel it paints.
    */
  private val neutralLab = Oklab.fromRgba(neutral)
  private val negativeLab = Oklab.fromRgba(negative)
  private val positiveLab = Oklab.fromRgba(positive)

  /** Scene colour at a signed fraction in `[-1, 1]`; values outside are clamped, and `NaN` returns
    * `neutral`.
    */
  def color(signed: Double): Rgba =
    val t = if signed.isNaN then 0.0 else math.max(-1.0, math.min(1.0, signed))
    if t < 0.0 then mix(negative, negativeLab, -t)
    else mix(positive, positiveLab, t)

  private def mix(endpoint: Rgba, endpointLab: Oklab, magnitude: Double): Rgba =
    if magnitude == 0.0 then neutral
    else if magnitude == 1.0 then endpoint
    else
      val blended = Oklab.toRgba(Oklab.mix(neutralLab, endpointLab, magnitude))
      val alpha = Oklab.clampUnit(Oklab.lerp(neutral.alpha, endpoint.alpha, magnitude))
      Rgba.unsafe(blended.red, blended.green, blended.blue, alpha)

  /** Raster pixel at a signed fraction in `[-1, 1]`. Identical to packing [[color]]. */
  def pixel(signed: Double): Rgba32 =
    Rgba32.fromRgba(color(signed))

  /** The same ramp addressed on the unit interval: `0` is `negative`, `0.5` is exactly `neutral`,
    * `1` is `positive`. `NaN` returns `neutral`, matching [[color]] rather than the negative
    * endpoint that clamping alone would give.
    *
    * A scale wired to this palette must have a domain symmetric about the neutral value, or the
    * middle of the ramp is not the middle of the data. `ContinuousScale.diverging` builds that
    * domain and fixes it; reach for this directly only when you are supplying the symmetry
    * yourself.
    */
  def unitPalette: Palette[Rgba] =
    value => if value.isNaN then neutral else color(Oklab.clampUnit(value) * 2.0 - 1.0)

  /** Swap the arms. The neutral is unchanged, so the reversed palette is still zero-centred.
    *
    * Reversing changes the published convention: in `BlueRust.reversed`, rust means negative. That
    * is the exact collision this type exists to prevent, so a reversed palette and its original
    * must not both appear in one application.
    */
  def reversed: DivergingPalette =
    new DivergingPalette(positive, neutral, negative)

object DivergingPalette:
  /** Reject a palette that could not be read back at all: exactly equal endpoints, or an arm whose
    * two colours are exactly equal. The check is exact `Rgba` equality, matching
    * `AccessibilityDiagnostic.AmbiguousPalette`; two endpoints a byte apart are indistinguishable
    * in practice and are not caught here.
    */
  def apply(
      negative: Rgba,
      neutral: Rgba,
      positive: Rgba
  ): Either[GraphicsError, DivergingPalette] =
    if negative == positive then Left(GraphicsError.DegenerateDivergingPalette("both endpoints"))
    else if negative == neutral then
      Left(GraphicsError.DegenerateDivergingPalette("the negative arm"))
    else if positive == neutral then
      Left(GraphicsError.DegenerateDivergingPalette("the positive arm"))
    else Right(new DivergingPalette(negative, neutral, positive))

  def unsafe(negative: Rgba, neutral: Rgba, positive: Rgba): DivergingPalette =
    apply(negative, neutral, positive).orThrow

  /** Deep blue for negative, warm ivory at zero, rust for positive.
    *
    * Chosen against measurement rather than taste. At matched magnitude `|t| >= 0.25` the two arms
    * stay at least CIE76 18.8 apart under every observer [[ColorVision]] models — 23.3 normal, 19.1
    * protanopia, 22.4 deuteranopia, 18.8 tritanopia — and lightness never reverses outward along
    * either arm, so magnitude survives greyscale reproduction.
    *
    * Rust rather than a saturated red is what buys the worst case: the same ramp ending at
    * `#B2182B` falls to 16.1 under protanopia. Red is the better of the two under tritanopia (23.5
    * against 18.8), and that trade is the reason to compare on the worst observer rather than on a
    * favourite one. `DivergingPaletteEvidenceSuite` asserts every figure here to within 0.05 and
    * `evidence/diverging-palette/README.md` records how they were obtained.
    *
    * Sign is not recoverable from a greyscale rendering of any diverging ramp, this one included;
    * both arms darken away from the neutral. A signed legend is the fix, not a different ramp.
    */
  val BlueRust: DivergingPalette =
    new DivergingPalette(
      negative = Rgba.unsafe(33, 102, 172),
      neutral = Rgba.unsafe(245, 240, 230),
      positive = Rgba.unsafe(180, 85, 45)
    )
