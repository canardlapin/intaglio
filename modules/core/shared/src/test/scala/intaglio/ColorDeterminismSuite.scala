package intaglio

/** Byte-identity court for the colour path across the JVM and Scala.js.
  *
  * `Oklab` puts `math.cbrt` and `math.pow` between a caller's colour and the bytes a backend
  * receives, and both are implementation-approximated in JavaScript. The round-trip suites prove
  * each platform agrees with *itself*; this one proves the two platforms agree with *each other*,
  * by folding every emitted channel byte into one order-sensitive digest and pinning it. A single
  * byte moving anywhere in a ramp changes the digest.
  *
  * The digest covers bytes only. Whether the intermediate `Oklab` coordinates agree to the last bit
  * is not asserted and does not matter: the contract is the colour that reaches the device.
  *
  * Mixing is SplitMix64 over `Long`, which is the same integer arithmetic on both platforms — the
  * same reason `CompilerPhases` uses it for jitter.
  */
class ColorDeterminismSuite extends munit.FunSuite:

  private def mix(state: Long, value: Long): Long =
    var mixed = state + 0x9e3779b97f4a7c15L * (value + 1L)
    mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L
    mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL
    mixed ^ (mixed >>> 31)

  private def absorb(state: Long, color: Rgba): Long =
    val bytes =
      (color.red.toLong << 24) |
        (color.green.toLong << 16) |
        (color.blue.toLong << 8) |
        math.round(color.alpha * 255.0)
    mix(state, bytes)

  private val endpoints: Vector[(Rgba, Rgba)] =
    Vector(
      Rgba.unsafe(0, 0, 255) -> Rgba.unsafe(255, 255, 0),
      Rgba.unsafe(0, 160, 160) -> Rgba.unsafe(192, 0, 192),
      Rgba.unsafe(0, 63, 127) -> Rgba.unsafe(255, 127, 0),
      Rgba.unsafe(27, 120, 55) -> Rgba.unsafe(118, 42, 131),
      Rgba.unsafe(20, 30, 40, 0.2) -> Rgba.unsafe(200, 120, 60, 0.9)
    )

  private def divergingDigest: Long =
    val palette = DivergingPalette.BlueRust
    (-1000 to 1000).foldLeft(0L)((state, step) => absorb(state, palette.color(step / 1000.0)))

  private def gradientDigest: Long =
    endpoints.foldLeft(0L) { (outer, pair) =>
      val ramp = Palette.oklabGradient(pair._1, pair._2)
      (0 to 200).foldLeft(outer)((state, step) => absorb(state, ramp(step / 200.0)))
    }

  private def roundTripDigest: Long =
    val levels = 0 to 255 by 9
    val colors =
      for
        red <- levels
        green <- levels
        blue <- levels
      yield Rgba.unsafe(red, green, blue)
    colors.foldLeft(0L)((state, color) => absorb(state, Oklab.toRgba(Oklab.fromRgba(color))))

  test("the blue-rust ramp emits the same 8004 bytes on every platform"):
    assertEquals(divergingDigest, -9208156616735322647L)

  test("oklab gradients emit the same bytes on every platform"):
    assertEquals(gradientDigest, 4621379597808094067L)

  test("the oklab round trip emits the same bytes on every platform"):
    assertEquals(roundTripDigest, 2941652681240497395L)
