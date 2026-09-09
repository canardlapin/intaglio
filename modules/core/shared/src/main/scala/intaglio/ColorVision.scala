package intaglio

/** Dichromat colour vision, simulated by the two-half-plane construction of Brettel, Viénot and
  * Mollon (1997), "Computerized simulation of color appearance for dichromats", JOSA A
  * 14(10):2647-2655.
  *
  * A dichromat is missing one cone class, so the colours they can distinguish collapse onto a
  * surface in colour space. Brettel's construction is that surface: two half-planes that share the
  * neutral axis, each anchored on a monochromatic stimulus whose hue a dichromat and a trichromat
  * agree on (475 and 575 nm for protanopia and deuteranopia, 485 and 660 nm for tritanopia). A
  * colour is projected onto whichever half-plane its own side of the neutral axis selects, along
  * the axis of the missing cone.
  *
  * The single-plane simplification of Viénot, Brettel and Mollon (1999) is not used. It is a good
  * approximation for protanopia and deuteranopia and a poor one for tritanopia, where the two
  * half-planes are the whole point; the widely copied "tritanopia matrix" derived from it keeps the
  * red-green projection plane and leaves blue and yellow — the two colours a tritanope actually
  * confuses — completely unchanged. `ColorVisionSuite` pins that as a negative case.
  *
  * This simulates a *dichromat*, the severe form. Anomalous trichromacy — protanomaly,
  * deuteranomaly, tritanomaly, which together are far more common — retains partial discrimination
  * and is not modelled here. Reading these figures as "the worst case" is right; reading them as
  * "what most colour-deficient readers see" is not.
  *
  * A simulation is a model of a measurement, not a measurement. It says what a colour reduces to
  * under a standard observer, not whether a particular reader can use a particular figure.
  */
enum ColorVision:
  case Normal, Protanopia, Deuteranopia, Tritanopia

  def label: String =
    this match
      case Normal       => "normal vision"
      case Protanopia   => "protanopia"
      case Deuteranopia => "deuteranopia"
      case Tritanopia   => "tritanopia"

  /** How `color` reduces for this observer. Alpha is not a chromatic quantity and is carried
    * through unchanged. [[Normal]] is the identity.
    */
  def simulate(color: Rgba): Rgba =
    this match
      case Normal => color
      case _      =>
        val red = Srgb.toLinear(color.red)
        val green = Srgb.toLinear(color.green)
        val blue = Srgb.toLinear(color.blue)
        val plane = ColorVision.projection(this, red, green, blue)
        Rgba.unsafe(
          Srgb.toChannel(plane(0) * red + plane(1) * green + plane(2) * blue),
          Srgb.toChannel(plane(3) * red + plane(4) * green + plane(5) * blue),
          Srgb.toChannel(plane(6) * red + plane(7) * green + plane(8) * blue),
          color.alpha
        )

object ColorVision:
  /** The three dichromacies, without the identity. */
  val dichromacies: Vector[ColorVision] =
    Vector(Protanopia, Deuteranopia, Tritanopia)

  /** Brettel's half-planes, folded into linear sRGB.
    *
    * Everything between linear RGB and the projection is linear, so the LMS round trip collapses
    * into one 3x3 per half-plane and the plane test collapses into one dot product. The constants
    * are the precomputed tables published by libDaltonLens (`libDaltonLens.c`, public domain),
    * which pair Brettel's geometry with the Smith and Pokorny (1975) cone fundamentals over
    * Judd-Vos corrected XYZ, and take the neutral axis through RGB white rather than the
    * equal-energy white of the paper so that white maps to white exactly.
    *
    * Each row of each matrix sums to one, so every grey is a fixed point, and each separation
    * normal is orthogonal to `(1, 1, 1)`, so the neutral axis lies in the separating plane and both
    * half-planes agree there. `ColorVisionSuite` asserts both invariants and checks the published
    * simulated values for the sRGB primaries and secondaries.
    */
  private val ProtanFirst =
    Array(0.14980, 1.19548, -0.34528, 0.10764, 0.84864, 0.04372, 0.00384, -0.00540, 1.00156)
  private val ProtanSecond =
    Array(0.14570, 1.16172, -0.30742, 0.10816, 0.85291, 0.03892, 0.00386, -0.00524, 1.00139)
  private val ProtanSeparation = Array(0.00048, 0.00393, -0.00441)

  private val DeutanFirst =
    Array(0.36477, 0.86381, -0.22858, 0.26294, 0.64245, 0.09462, -0.02006, 0.02728, 0.99278)
  private val DeutanSecond =
    Array(0.37298, 0.88166, -0.25464, 0.25954, 0.63506, 0.10540, -0.01980, 0.02784, 0.99196)
  private val DeutanSeparation = Array(-0.00281, -0.00611, 0.00892)

  private val TritanFirst =
    Array(1.01277, 0.13548, -0.14826, -0.01243, 0.86812, 0.14431, 0.07589, 0.80500, 0.11911)
  private val TritanSecond =
    Array(0.93678, 0.18979, -0.12657, 0.06154, 0.81526, 0.12320, -0.37562, 1.12767, 0.24796)
  private val TritanSeparation = Array(0.03901, -0.02788, -0.01113)

  private def projection(
      vision: ColorVision,
      red: Double,
      green: Double,
      blue: Double
  ): Array[Double] =
    val (first, second, separation) =
      vision match
        case Protanopia   => (ProtanFirst, ProtanSecond, ProtanSeparation)
        case Deuteranopia => (DeutanFirst, DeutanSecond, DeutanSeparation)
        case Tritanopia   => (TritanFirst, TritanSecond, TritanSeparation)
        case Normal       => (ProtanFirst, ProtanFirst, ProtanSeparation)
    val side = separation(0) * red + separation(1) * green + separation(2) * blue
    if side >= 0.0 then first else second

  private[intaglio] def separationNormal(vision: ColorVision): Array[Double] =
    vision match
      case Protanopia   => ProtanSeparation
      case Deuteranopia => DeutanSeparation
      case Tritanopia   => TritanSeparation
      case Normal       => Array(0.0, 0.0, 0.0)

  private[intaglio] def halfPlanes(vision: ColorVision): Vector[Array[Double]] =
    vision match
      case Protanopia   => Vector(ProtanFirst, ProtanSecond)
      case Deuteranopia => Vector(DeutanFirst, DeutanSecond)
      case Tritanopia   => Vector(TritanFirst, TritanSecond)
      case Normal       => Vector.empty

/** The closest pair of colours in a set, as one observer sees them. */
final case class ColorSeparation(vision: ColorVision, first: Int, second: Int, deltaE76: Double)

object ColorSeparation:
  /** Below this CIE76 separation, two colours carrying different meanings in one plot should not be
    * relied on to be told apart.
    *
    * This is a judgement, not a standard. CIE76 around 2.3 is a just-noticeable difference for two
    * large patches abutting under controlled light; marks in a plot are small, separated in space,
    * on different backgrounds, and often compared from memory across a legend, so the working
    * margin has to be far wider. Ten is the value this library's diagnostics use, chosen so that it
    * clears the separations the shipped palettes actually achieve while still catching the
    * collapses that prompted it.
    */
  val SeriesFloor: Double = 10.0

  /** CIE76 distance in CIELAB under a D65 white point, between two colours as `vision` sees them.
    *
    * CIE76 rather than CIEDE2000 because every claim made from it here is a floor — "these are at
    * least this far apart" — and CIE76 is the conservative closed-form choice for a floor. Alpha is
    * ignored: two marks are compared as painted, not as composited.
    */
  def between(vision: ColorVision, left: Rgba, right: Rgba): Double =
    val (leftL, leftA, leftB) = cielab(vision.simulate(left))
    val (rightL, rightA, rightB) = cielab(vision.simulate(right))
    val deltaL = leftL - rightL
    val deltaA = leftA - rightA
    val deltaB = leftB - rightB
    math.sqrt(deltaL * deltaL + deltaA * deltaA + deltaB * deltaB)

  /** The two closest colours in `colors` as `vision` sees them, by index, or `None` for fewer than
    * two colours. Ties resolve to the earliest pair in index order.
    */
  def closest(colors: Vector[Rgba], vision: ColorVision): Option[ColorSeparation] =
    var best: Option[ColorSeparation] = None
    var left = 0
    while left < colors.length do
      var right = left + 1
      while right < colors.length do
        val distance = between(vision, colors(left), colors(right))
        if best.forall(distance < _.deltaE76) then
          best = Some(ColorSeparation(vision, left, right, distance))
        right += 1
      left += 1
    best

  /** CIE L*, for reporting lightness contrast rather than hue separation. A ramp that is monotone
    * in this still carries magnitude in greyscale.
    */
  def lightness(color: Rgba): Double =
    cielab(color)._1

  /** CIE C*, the distance from the neutral axis. A ramp whose chroma collapses in the middle has a
    * dead zone there however far apart its endpoints are.
    */
  def chroma(color: Rgba): Double =
    val (_, a, b) = cielab(color)
    math.hypot(a, b)

  private def cielab(color: Rgba): (Double, Double, Double) =
    val red = Srgb.toLinear(color.red)
    val green = Srgb.toLinear(color.green)
    val blue = Srgb.toLinear(color.blue)
    val x = (0.4124564 * red + 0.3575761 * green + 0.1804375 * blue) / 0.95047
    val y = 0.2126729 * red + 0.7151522 * green + 0.0721750 * blue
    val z = (0.0193339 * red + 0.1191920 * green + 0.9503041 * blue) / 1.08883
    (116.0 * pivot(y) - 16.0, 500.0 * (pivot(x) - pivot(y)), 200.0 * (pivot(y) - pivot(z)))

  private def pivot(value: Double): Double =
    if value > 0.008856451679035631 then math.cbrt(value)
    else value / 0.12841854934601665 + 4.0 / 29.0
