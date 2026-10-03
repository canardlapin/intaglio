package intaglio.svg

import intaglio.*
import TestSfnt.*
import TestFace.*

class SvgEmbeddedPlateSuite extends munit.FunSuite:
  private val face = parsed()

  private def extent(label: String, fraction: Double, font: SfntFont = face) =
    SvgTextExtent.measure(font, SvgTextExtent.codePoints(label).get, fraction)

  private def ink(label: String, fraction: Double, font: SfntFont = face) =
    extent(label, fraction, font).toOption.get.ink.get

  test("one glyph: its own box, overhangs included, against the anchor's share of its advance") {
    assertEquals(ink("j", 0.0), (-150.0, -250.0, 420.0, 720.0))
    assertEquals(ink("j", 0.5), (-300.0, -250.0, 270.0, 720.0))
    assertEquals(ink("j", 1.0), (-450.0, -250.0, 120.0, 720.0))
    assertEquals(extent("j", 0.0).toOption.get.advance, Span(300, 300))
    assertEquals(extent(" ", 0.0), Right(RunExtent(None, Span(250, 250))), "a space has no ink")
    assertEquals(extent("", 0.5), Right(RunExtent(None, Span.zero)))
  }

  test("kerning on and off both lie inside, and an end anchor stays exact for the last glyph") {
    val av = extent("AV", 1.0).toOption.get
    assertEquals(av.advance, Span(1120, 1200))
    // Kerned the run starts at -1120, unkerned at -1200; V ends on the anchor either way.
    assertEquals(av.ink, Some((-1200.0, 0.0, 0.0, 700.0)))
    assertEquals(ink("AV", 0.0), (0.0, 0.0, 1200.0, 700.0))
  }

  test("a legacy kern table is a kerning alternative like GPOS") {
    val legacy = parsed(bytes(extra = Map("kern" -> legacyKern(Seq((A, V, -100))))))
    assertEquals(extent("AV", 0.0, legacy).toOption.get.advance, Span(1100, 1200))
  }

  test("a ligature and a context-only substitute both bound the run") {
    val fi = extent("fi", 0.0).toOption.get
    // f or the fi ligature at 0; i or i.alt after an advance of 300 (f) or 520 (fi).
    assertEquals(fi.ink, Some((0.0, 0.0, 720.0, 900.0)))
    assertEquals(fi.advance, Span(300, 770), "the consumed i may contribute no advance")
  }

  test("a base and mark compose to the face's composite glyph, as the viewer composes them") {
    // A + U+0301 draws as Aacute (950 units tall), not as the shorter unattached acute.
    assertEquals(ink("Á", 0.0), (0.0, 0.0, 600.0, 950.0))
  }

  test("without a composite, a mark is bounded unattached and at its mark-to-base anchor") {
    // Unattached the acute sits at the pen after E (x 300..500, y 750..850); anchored it moves
    // by (500, 200) from E's origin (x 200..400, y 950..1050).
    assertEquals(ink("É", 0.0), (0.0, 0.0, 600.0, 1050.0))
    assertEquals(extent("É", 0.0).toOption.get.advance, Span(600, 600))
  }

  test("a stacked mark is bounded at its mark-to-mark anchor on every placement of the first") {
    val stacked = parsed(
      bytes(extra =
        Map(
          "GDEF" -> gdef(Seq(Acute -> 3)),
          "GPOS" -> layout(
            Seq("mark" -> Seq(0), "mkmk" -> Seq(1)),
            Seq(
              Lookup(4, Seq(markAttachment(Acute, (-200, 700), E, (300, 900)))),
              Lookup(6, Seq(markAttachment(Acute, (-200, 700), Acute, (-200, 900))))
            )
          )
        )
      )
    )
    // First acute: origin x 500..600, y 0..200. Second: unattached at (600, 0), on E at
    // (500, 200), or 200 above the first: y up to 400, so its top reaches 1250.
    assertEquals(ink("É́", 0.0, stacked), (0.0, 0.0, 600.0, 1250.0))
  }

  test("runs outside the model are refused with a reason, never measured") {
    assertEquals(extent("Q", 0.0), Left("the face has no glyph for U+0051"))
    assertEquals(extent("א", 0.0), Left("U+05D0 needs shaping this model does not cover"))
    assertEquals(extent("j\tj", 0.0), Left("U+0009 needs shaping this model does not cover"))
    assertEquals(SvgTextExtent.codePoints("x\ud800"), None, "an unpaired surrogate")
    val multiple = parsed(
      bytes(extra =
        Map(
          "GSUB" -> layout(
            Seq("ccmp" -> Seq(0)),
            Seq(Lookup(2, Seq(multipleSubstitution(H, Seq(A, V)))))
          )
        )
      )
    )
    assertEquals(extent("H", 0.0, multiple), Left(s"glyph $H is multiply substituted"))
    val cursiveFace = parsed(
      bytes(extra = Map("GPOS" -> layout(Seq("curs" -> Seq(0)), Seq(Lookup(3, Seq(cursive(H)))))))
    )
    assertEquals(
      extent("H", 0.0, cursiveFace),
      Left(s"glyph $H takes cursive or mark-to-ligature attachment")
    )
    val noMarks = parsed(bytes(extra = Map.empty))
    assertEquals(
      extent("É", 0.0, noMarks),
      Left("U+0301 is a combining mark and the face has no mark positioning")
    )
    assert(extent("Á", 0.0, noMarks).isRight, "a composed mark needs no positioning")
    val variable = parsed(assemble(Map("fvar" -> new Array[Byte](16)) ++ tablesOf(bytes())))
    assertEquals(extent("H", 0.0, variable), Left("the face has a 'fvar' table"))
  }

  test("white space: as written and as SVG collapses it are both laid out") {
    assertEquals(
      SvgTextExtent.whiteSpaceVariants(Vector(0x20, 'j'.toInt, 0x20, 0x20, 'j'.toInt, 0x20)),
      Vector(
        Vector(0x20, 'j'.toInt, 0x20, 0x20, 'j'.toInt, 0x20),
        Vector('j'.toInt, 0x20, 'j'.toInt)
      )
    )
  }

  // ------------------------------------------------------------ the renderer

  private val family = "Test Face"
  private lazy val fonts = SvgFonts(SvgFontFace(family, bytes()).orThrow).orThrow
  private val options = SvgOptions.unsafe(width = 200, height = 200, pixelsPerInch = 72.0)
  private val plate = TextPlate(Rgba.unsafe(0, 160, 0), padding = StrokeWidth.devicePixelsUnsafe(2))

  private def scene(
      label: String,
      anchor: Anchor,
      rotation: Double = 0.0,
      family: String = family,
      weight: Option[FontWeight] = None
  ) =
    val gp = GraphicParams
      .unsafe(
        stroke = None,
        fill = Some(Rgba.Black),
        fontFamily = Some(family),
        fontSize = Length.pointsUnsafe(100),
        fontWeight = weight
      )
      .withTextPlate(plate)
    Scene(
      Vector(
        Grob.textUnsafe(
          label,
          Point.npcUnsafe(0.5, 0.5),
          anchor,
          rotationDegrees = rotation,
          gp = gp
        )
      )
    )

  private def rect(svg: String): String =
    svg.linesIterator.map(_.trim).find(_.startsWith("<rect")).getOrElse(fail(svg))

  private def render(scene: Scene, fonts: SvgFonts = fonts): String =
    SvgRenderer.render(scene, options, fonts).orThrow.value

  test("the plate is the logical box united with the face's ink, at each anchor's baseline") {
    // 100 px text: 0.1 px per unit. 'j' advances 30 px, ink -15..42 by -25..72 about its origin,
    // ascent 80 and descent 20, x-height 48. Ink gets a 1 px margin, the plate 2 px padding.
    // Bottom-left: baseline at y - 20 = 80; logical 100..130 x 0..100; ink 84..143 x 7..106.
    assertEquals(
      rect(render(scene("j", Anchor.BottomLeft))),
      """<rect fill="#00a000" stroke="none" x="82" y="-2" width="63" height="110" pointer-events="none" />"""
    )
    // Centre: middle puts the baseline x-height/2 below y, at 124; logical 85..115 x 44..144;
    // ink 69..128 x 51..150.
    assertEquals(
      rect(render(scene("j", Anchor.Center))),
      """<rect fill="#00a000" stroke="none" x="67" y="42" width="63" height="110" pointer-events="none" />"""
    )
    // Top-right: baseline at y + 80 = 180; logical 70..100 x 100..200; ink 54..113 x 107..206.
    assertEquals(
      rect(render(scene("j", Anchor(HJust.Right, VJust.Top)))),
      """<rect fill="#00a000" stroke="none" x="52" y="98" width="63" height="110" pointer-events="none" />"""
    )
  }

  test("a rotated plate carries the text's own rotation, so it bounds the rotated glyphs") {
    val svg = render(scene("j", Anchor.Center, rotation = 33))
    val lines = svg.linesIterator.map(_.trim).toVector
    val text = lines.find(_.startsWith("<text")).get
    val rotate = text.substring(text.indexOf("transform="), text.indexOf(")\"") + 2)
    assert(rect(svg).contains(rotate), svg)
    assertEquals(
      rect(svg).replace(s" $rotate", ""),
      rect(render(scene("j", Anchor.Center))),
      "rotation does not change the plate's frame-local geometry"
    )
  }

  test("the embedded face sizes the plate; the estimate sizes it when the face cannot") {
    val estimate = rect(render(scene("j", Anchor.Center), SvgFonts.empty))
    assertNotEquals(rect(render(scene("j", Anchor.Center))), estimate)
    // A code point the face lacks: the viewer falls back to another font, so no claim is made.
    assertEquals(
      rect(render(scene("jQ", Anchor.Center))),
      rect(render(scene("jQ", Anchor.Center), SvgFonts.empty))
    )
    // A weight the face does not have may be synthesised by the viewer.
    assertEquals(
      rect(render(scene("j", Anchor.Center, weight = Some(FontWeight.Bold)))),
      rect(render(scene("j", Anchor.Center, weight = Some(FontWeight.Bold)), SvgFonts.empty))
    )
    // Compressed WOFF tables are not read.
    val woff =
      SvgFonts(SvgFontFace(family, "wOFF".getBytes("US-ASCII") ++ bytes().drop(4)).orThrow).orThrow
    assertEquals(rect(render(scene("j", Anchor.Center), woff)), estimate)
    // A face that is not this family does not size this run.
    assertEquals(
      rect(render(scene("j", Anchor.Center, family = "Other Face"))),
      rect(render(scene("j", Anchor.Center, family = "Other Face"), SvgFonts.empty))
    )
  }

  test(
    "a face with malformed glyph data falls back to the estimate instead of failing the render"
  ) {
    val good = bytes()
    val count = ((good(4) & 0xff) << 8) | (good(5) & 0xff)
    val record = (0 until count)
      .map(i => 12 + i * 16)
      .find { r =>
        new String(good.slice(r, r + 4).map(_.toChar)) == "loca"
      }
      .get
    val loca =
      ((good(record + 8) & 0xff) << 24) | ((good(record + 9) & 0xff) << 16) |
        ((good(record + 10) & 0xff) << 8) | (good(record + 11) & 0xff)
    val broken = good.clone()
    broken(loca + (J + 1) * 2) = 0
    broken(loca + (J + 1) * 2 + 1) = 0
    val brokenFonts = SvgFonts(SvgFontFace(family, broken).orThrow).orThrow
    assertEquals(
      rect(render(scene("j", Anchor.Center), brokenFonts)),
      rect(render(scene("j", Anchor.Center), SvgFonts.empty))
    )
  }

  test("plate output is deterministic and the text element is unchanged by the face") {
    val first = render(scene("fi Á", Anchor.Center, rotation = 12))
    assertEquals(render(scene("fi Á", Anchor.Center, rotation = 12)), first)
    def textLine(svg: String) = svg.linesIterator.map(_.trim).find(_.startsWith("<text")).get
    assertEquals(
      textLine(first),
      textLine(render(scene("fi Á", Anchor.Center, rotation = 12), SvgFonts.empty))
        .replace("font-family=\"Test Face\"", "font-family=\"&quot;Test Face&quot;\"")
    )
  }

  test("every one of 500 plated labels in a document uses the face, independent of the others") {
    val labels = Vector("j", "fi", "AV", "É", "Á x", "Hj fi")
    def grob(i: Int) =
      val gp = GraphicParams
        .unsafe(
          stroke = None,
          fill = Some(Rgba.Black),
          fontFamily = Some(family),
          fontSize = Length.pointsUnsafe(10)
        )
        .withTextPlate(plate)
      Grob.textUnsafe(
        labels(i % labels.length),
        Point.npcUnsafe((i % 25) / 25.0, (i / 25) / 20.0),
        Anchor.Center,
        gp = gp
      )
    def rects(svg: String) = svg.linesIterator.map(_.trim).filter(_.startsWith("<rect")).toVector
    val together = rects(render(Scene(Vector.tabulate(500)(grob))))
    assertEquals(together.length, 500)
    (0 until 500).foreach { i =>
      val alone = rects(render(Scene(Vector(grob(i)))))
      assertEquals(together(i), alone.head, s"label $i")
      assertNotEquals(
        alone.head,
        rects(render(Scene(Vector(grob(i))), SvgFonts.empty)).head,
        s"label $i is sized from the face, not the estimate"
      )
    }
  }

  /** The tables of an assembled test font, for rebuilding it with one more. */
  private def tablesOf(file: Array[Byte]): Map[String, Array[Byte]] =
    val count = ((file(4) & 0xff) << 8) | (file(5) & 0xff)
    (0 until count).map { i =>
      val r = 12 + i * 16
      def u32(at: Int) =
        ((file(at) & 0xff) << 24) | ((file(at + 1) & 0xff) << 16) | ((file(at + 2) & 0xff) << 8) |
          (file(at + 3) & 0xff)
      new String(file.slice(r, r + 4).map(_.toChar)) -> file.slice(
        u32(r + 8),
        u32(r + 8) + u32(r + 12)
      )
    }.toMap
