package intaglio.svg

import intaglio.*

private[svg] final case class PatternFillStructuralMetrics(
    markCount: Int,
    semanticNameCount: Int,
    uniqueSemanticNameCount: Int,
    svgElementCount: Int,
    patternDefinitionCount: Int,
    serializedBytes: Int,
    sha256: String
)

private[svg] object PatternFillAssuranceFixture:
  val MarkCount = 2300
  val MaxElements = 10000
  val MaxSerializedBytes = 2 * 1024 * 1024

  private val Columns = 50
  private val Rows = MarkCount / Columns
  private val Options = SvgOptions.unsafe(width = 1200, height = 800)
  private val Recipe =
    PatternRecipe.crossHatch(angleDegrees = 37.5, spacing = 11.0, lineWidth = 1.25).orThrow
  private val Paint =
    PatternPaint(Recipe, Rgba.unsafe(32, 48, 64, 0.75), Some(Rgba.unsafe(240, 244, 248, 0.6)))

  val expectedNames: Vector[String] =
    Vector.tabulate(MarkCount)(index => s"pattern-budget-${padded(index)}")

  val scene: Scene =
    val width = 0.8 / Columns.toDouble
    val height = 0.8 / Rows.toDouble
    val marks = Vector.tabulate(MarkCount) { index =>
      val column = index % Columns
      val row = index / Columns
      Grob.rectUnsafe(
        Point.npcUnsafe(0.1 + (column.toDouble + 0.5) * width, 0.1 + (row.toDouble + 0.5) * height),
        Size.npcUnsafe(width * 0.72, height * 0.72),
        gp = GraphicParams.unsafe(stroke = None, alpha = 0.9).withPatternFill(Paint),
        name = Some(GraphicsName.unsafe(expectedNames(index)))
      )
    }
    Scene(marks)

  def render(): String =
    SvgRenderer.render(scene, Options).orThrow.value

  def measure(svg: String): PatternFillStructuralMetrics =
    require(svg.forall(_ <= 0x7f), "the assurance fixture must remain ASCII for portable byte accounting")
    val names = semanticNames(svg)
    PatternFillStructuralMetrics(
      markCount = MarkCount,
      semanticNameCount = names.length,
      uniqueSemanticNameCount = names.distinct.length,
      svgElementCount = svg.linesIterator.count { raw =>
        val line = raw.trim
        line.startsWith("<") && !line.startsWith("</") && !line.startsWith("<?") && !line.startsWith("<!")
      },
      patternDefinitionCount = occurrences(svg, "<pattern id=\""),
      serializedBytes = svg.length,
      sha256 = PortableSha256.hexAscii(svg)
    )

  def semanticNames(svg: String): Vector[String] =
    attributeValues(svg, "data-name=\"")

  private def padded(value: Int): String =
    val raw = value.toString
    "0000".substring(raw.length) + raw

  private def occurrences(value: String, needle: String): Int =
    var count = 0
    var offset = value.indexOf(needle)
    while offset >= 0 do
      count += 1
      offset = value.indexOf(needle, offset + needle.length)
    count

  private def attributeValues(value: String, prefix: String): Vector[String] =
    val out = Vector.newBuilder[String]
    var start = value.indexOf(prefix)
    while start >= 0 do
      val valueStart = start + prefix.length
      val end = value.indexOf('"', valueStart)
      if end >= 0 then out += value.substring(valueStart, end)
      start = value.indexOf(prefix, valueStart)
    out.result()

private[svg] object PortableSha256:
  private val Initial = Array(
    0x6a09e667,
    0xbb67ae85,
    0x3c6ef372,
    0xa54ff53a,
    0x510e527f,
    0x9b05688c,
    0x1f83d9ab,
    0x5be0cd19
  )

  private val Constants = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  )

  def hexAscii(value: String): String =
    require(value.forall(_ <= 0x7f), "portable SHA-256 input must be ASCII")
    val input = value.iterator.map(_.toInt).toArray
    val padding = (64 + 56 - ((input.length + 1) % 64)) % 64
    val message = new Array[Int](input.length + 1 + padding + 8)
    Array.copy(input, 0, message, 0, input.length)
    message(input.length) = 0x80
    val bitLength = input.length.toLong * 8L
    var lengthByte = 0
    while lengthByte < 8 do
      message(message.length - 1 - lengthByte) = ((bitLength >>> (lengthByte * 8)) & 0xffL).toInt
      lengthByte += 1

    val state = Initial.clone()
    val words = new Array[Int](64)
    var offset = 0
    while offset < message.length do
      var index = 0
      while index < 16 do
        val base = offset + index * 4
        words(index) =
          (message(base) << 24) |
            (message(base + 1) << 16) |
            (message(base + 2) << 8) |
            message(base + 3)
        index += 1
      while index < 64 do
        val s0 = rotateRight(words(index - 15), 7) ^ rotateRight(words(index - 15), 18) ^ (words(index - 15) >>> 3)
        val s1 = rotateRight(words(index - 2), 17) ^ rotateRight(words(index - 2), 19) ^ (words(index - 2) >>> 10)
        words(index) = words(index - 16) + s0 + words(index - 7) + s1
        index += 1

      var a = state(0)
      var b = state(1)
      var c = state(2)
      var d = state(3)
      var e = state(4)
      var f = state(5)
      var g = state(6)
      var h = state(7)
      index = 0
      while index < 64 do
        val sum1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25)
        val choose = (e & f) ^ (~e & g)
        val temporary1 = h + sum1 + choose + Constants(index) + words(index)
        val sum0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22)
        val majority = (a & b) ^ (a & c) ^ (b & c)
        val temporary2 = sum0 + majority
        h = g
        g = f
        f = e
        e = d + temporary1
        d = c
        c = b
        b = a
        a = temporary1 + temporary2
        index += 1

      state(0) += a
      state(1) += b
      state(2) += c
      state(3) += d
      state(4) += e
      state(5) += f
      state(6) += g
      state(7) += h
      offset += 64

    state.iterator.map { value =>
      val raw = Integer.toHexString(value)
      "00000000".substring(raw.length) + raw
    }.mkString

  private def rotateRight(value: Int, distance: Int): Int =
    (value >>> distance) | (value << (32 - distance))

class PatternFillAssuranceSuite extends munit.FunSuite:
  test("portable SHA-256 matches the standard ASCII oracle") {
    assertEquals(
      PortableSha256.hexAscii("abc"),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
  }

  test("2,300 named marks share one bounded deterministic SVG pattern resource") {
    val first = PatternFillAssuranceFixture.render()
    val second = PatternFillAssuranceFixture.render()
    val metrics = PatternFillAssuranceFixture.measure(first)

    assertEquals(second, first)
    assertEquals(metrics.markCount, PatternFillAssuranceFixture.MarkCount)
    assertEquals(metrics.semanticNameCount, PatternFillAssuranceFixture.MarkCount)
    assertEquals(metrics.uniqueSemanticNameCount, PatternFillAssuranceFixture.MarkCount)
    assertEquals(PatternFillAssuranceFixture.semanticNames(first), PatternFillAssuranceFixture.expectedNames)
    assertEquals(metrics.patternDefinitionCount, 1)
    assert(metrics.svgElementCount < PatternFillAssuranceFixture.MaxElements)
    assert(metrics.serializedBytes < PatternFillAssuranceFixture.MaxSerializedBytes)
    assertEquals((metrics.svgElementCount, metrics.serializedBytes), (2306, 492331))
    assertEquals(metrics.sha256, "30390d0a6ada0e693ae58d264356ae8d4bdb848f5eff498b5763fd40be6f5031")
  }
