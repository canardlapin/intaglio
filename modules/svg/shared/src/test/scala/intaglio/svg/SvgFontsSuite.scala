package intaglio.svg

import intaglio.*

class SvgFontsSuite extends munit.FunSuite:
  /** A minimal sfnt: one OS/2 table whose `fsType` is `fsType`. Enough for format detection and the
    * embedding-permission check; no viewer would draw glyphs from it.
    */
  private def sfnt(fsType: Int, signature: Int = 0x00010000): Array[Byte] =
    val bytes = new Array[Byte](40)
    def put32(offset: Int, value: Int): Unit =
      bytes(offset) = (value >>> 24).toByte
      bytes(offset + 1) = (value >>> 16).toByte
      bytes(offset + 2) = (value >>> 8).toByte
      bytes(offset + 3) = value.toByte
    put32(0, signature)
    bytes(5) = 1 // one table
    put32(12, 0x4f532f32) // "OS/2"
    put32(20, 28) // table offset
    put32(24, 10) // table length
    bytes(36) = (fsType >>> 8).toByte
    bytes(37) = fsType.toByte
    bytes

  private val woff2 = "wOF2".getBytes("US-ASCII") ++ Array.tabulate[Byte](60)(i => (i * 7).toByte)

  private def decode(base64: String): Array[Byte] =
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val clean = base64.takeWhile(_ != '=')
    val bits = clean.map(c => alphabet.indexOf(c.toInt))
    val out = Array.newBuilder[Byte]
    bits.grouped(4).foreach { group =>
      val padded = group.padTo(4, 0)
      val n = (padded(0) << 18) | (padded(1) << 12) | (padded(2) << 6) | padded(3)
      val count = group.length - 1
      (0 until count).foreach(i => out += ((n >>> (16 - 8 * i)) & 0xff).toByte)
    }
    out.result()

  private def label(family: Option[String]) =
    Grob
      .text(
        "Embedded",
        Point.npcUnsafe(0.5, 0.5),
        gp = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black), fontFamily = family)
      )
      .orThrow

  private val options = SvgOptions.unsafe(width = 120, height = 60)

  test("a used face is embedded as an @font-face data URI ahead of the text it serves") {
    val face = SvgFontFace("Studio Sans", woff2, FontWeight.Bold).orThrow
    val fonts = SvgFonts(face).orThrow
    val scene = Scene(Vector(label(Some("studio sans"))))
    val svg = SvgRenderer.render(scene, options, fonts).orThrow.value
    val rule = svg.linesIterator.map(_.trim).find(_.startsWith("@font-face")).getOrElse(fail(svg))
    assert(rule.startsWith("""@font-face { font-family: "Studio Sans"; font-weight: 700; """), rule)
    assert(rule.endsWith("""format("woff2"); }"""), rule)
    val payload = rule.substring(rule.indexOf("base64,") + 7, rule.indexOf(")"))
    assertEquals(decode(payload).toVector, woff2.toVector, "byte-exact round trip")
    assert(svg.indexOf("<style>") < svg.indexOf("<text"), svg)
    assertEquals(SvgRenderer.render(scene, options, fonts).orThrow.value, svg, "deterministic")
  }

  test("faces nobody uses are not written, and no fonts leaves output unchanged") {
    val fonts = SvgFonts(SvgFontFace("Studio Sans", woff2).orThrow).orThrow
    for family <- Vector(None, Some("Other Face")) do
      val scene = Scene(Vector(label(family)))
      assertEquals(
        SvgRenderer.render(scene, options, fonts).orThrow.value,
        SvgRenderer.render(scene, options).orThrow.value
      )
  }

  test("formats are recognised from the file signature") {
    assertEquals(SvgFontFace("A", woff2).map(_.format), Right(SvgFontFormat.Woff2))
    val woff = "wOFF".getBytes("US-ASCII") ++ new Array[Byte](20)
    assertEquals(SvgFontFace("A", woff).map(_.format), Right(SvgFontFormat.Woff))
    assertEquals(SvgFontFace("A", sfnt(0)).map(_.format), Right(SvgFontFormat.TrueType))
    assertEquals(
      SvgFontFace("A", sfnt(0, 0x4f54544f)).map(_.format),
      Right(SvgFontFormat.OpenType)
    )
  }

  test("a face whose licence restricts embedding, or that is malformed, is a typed error") {
    assertEquals(SvgFontFace("A", sfnt(0x0002)), Left(SvgFontError.EmbeddingRestricted("A")))
    assert(SvgFontFace("A", sfnt(0x0004)).isRight, "preview & print embedding is allowed")
    assert(SvgFontFace("A", sfnt(0x0008)).isRight, "editable embedding is allowed")
    assertEquals(SvgFontFace("A", Array.emptyByteArray), Left(SvgFontError.Empty("A")))
    assertEquals(
      SvgFontFace("A", "<svg>not a font</svg>".getBytes("US-ASCII")),
      Left(SvgFontError.UnrecognisedFormat("A"))
    )
    for family <- Vector("", " ", "a\"b", "a;b", "a}b", "a<b", "a\u0001b") do
      assertEquals(SvgFontFace(family, woff2), Left(SvgFontError.InvalidFamily(family)))
    val oversized = new Array[Byte](SvgFontFace.MaximumBytes + 1)
    "wOF2".getBytes("US-ASCII").copyToArray(oversized)
    assertEquals(
      SvgFontFace("A", oversized),
      Left(SvgFontError.TooLarge("A", SvgFontFace.MaximumBytes + 1))
    )
  }

  test("one family may not repeat a weight, compared as CSS compares names") {
    val regular = SvgFontFace("Studio Sans", woff2).orThrow
    val again = SvgFontFace("STUDIO SANS", woff2).orThrow
    val bold = SvgFontFace("Studio Sans", woff2, FontWeight.Bold).orThrow
    assertEquals(SvgFonts(regular, again), Left(SvgFontError.DuplicateFace("STUDIO SANS", 400)))
    assertEquals(SvgFonts(regular, bold).map(_.faces), Right(Vector(regular, bold)))
  }

  test("a face keeps its own copy of the bytes") {
    val source = woff2.clone()
    val face = SvgFontFace("A", source).orThrow
    source(10) = 99
    face.bytes(11) = 99
    assertEquals(face.bytes.toVector, woff2.toVector)
  }
