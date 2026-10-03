package intaglio.svg

import TestSfnt.*

/** Glyphs of the synthetic test face, at 1000 units per em. */
object TestFace:
  val Notdef = 0
  val H = 1
  val J = 2
  val Space = 3
  val A = 4
  val V = 5
  val Acute = 6
  val Aacute = 7
  val F = 8
  val I = 9
  val FiLigature = 10
  val X = 11
  val IAlternate = 12
  val E = 13
  val MathA = 14

  val glyphs: Vector[Glyph] = Vector(
    Glyph(500, Some((0, 0, 500, 700))),
    Glyph(600, Some((50, 0, 550, 700))),
    Glyph(300, Some((-150, -250, 420, 720))), // italic j: overhangs both sides
    Glyph(250, None),
    Glyph(600, Some((0, 0, 600, 700))),
    Glyph(600, Some((0, 0, 600, 700))),
    Glyph(0, Some((-300, 750, -100, 850))), // combining acute, drawn left of its pen
    Glyph(600, Some((0, 0, 600, 950))),
    Glyph(300, Some((0, 0, 400, 750))),
    Glyph(250, Some((50, 0, 200, 700))),
    Glyph(520, Some((0, 0, 600, 760))),
    Glyph(500, Some((0, 0, 500, 480))),
    Glyph(250, Some((50, 0, 200, 900))),
    Glyph(600, Some((0, 0, 600, 700))),
    Glyph(600, Some((0, 0, 600, 700)))
  )

  val cmap: Map[Int, Int] = Map(
    'H'.toInt -> H,
    'j'.toInt -> J,
    ' '.toInt -> Space,
    'A'.toInt -> A,
    'V'.toInt -> V,
    0x301 -> Acute,
    0xc1 -> Aacute,
    'f'.toInt -> F,
    'i'.toInt -> I,
    'x'.toInt -> X,
    'E'.toInt -> E,
    0x1d400 -> MathA
  )

  /** liga: f i -> fi; ccmp: i -> i.alt, reachable only through a chained context; salt (not a
    * default feature): H -> .notdef.
    */
  val gsub: Array[Byte] = layout(
    Seq("liga" -> Seq(0), "ccmp" -> Seq(1), "salt" -> Seq(3)),
    Seq(
      Lookup(4, Seq(ligatureSubstitution(F, Seq(Seq(I) -> FiLigature)))),
      Lookup(6, Seq(chainedContext(Seq(I), 2))),
      Lookup(1, Seq(singleSubstitution(Seq(I -> IAlternate)))),
      Lookup(1, Seq(singleSubstitution(Seq(H -> Notdef))))
    )
  )

  /** kern: A V -80 (through an extension lookup); mark: acute onto E at (500, 200). */
  val gpos: Array[Byte] = layout(
    Seq("kern" -> Seq(0), "mark" -> Seq(1)),
    Seq(
      Lookup(9, Seq(extension(2, pairPosition(A, V, -80)))),
      Lookup(4, Seq(markAttachment(Acute, (-200, 700), E, (300, 900))))
    )
  )

  val os2: Os2 = Os2(typo = (800, 200), win = (800, 200), xHeight = Some(480))

  def bytes(
      cmapFormat: Int = 4,
      os2: Option[Os2] = Some(os2),
      extra: Map[String, Array[Byte]] = Map(
        "GSUB" -> gsub,
        "GPOS" -> gpos,
        "GDEF" -> gdef(Seq(Acute -> 3))
      ),
      longLoca: Boolean = false,
      rangeOffsets: Boolean = false,
      hMetrics: Option[Int] = None
  ): Array[Byte] =
    font(
      glyphs,
      cmap,
      os2 = os2,
      cmapFormat = cmapFormat,
      extra = extra,
      longLoca = longLoca,
      rangeOffsets = rangeOffsets,
      hMetrics = hMetrics
    )

  def parsed(bytes: Array[Byte] = TestFace.bytes()): SfntFont =
    SfntFont.parse(bytes).getOrElse(throw new AssertionError("test face did not parse"))

class SfntFontSuite extends munit.FunSuite:
  import TestFace.*

  test("head, hhea, OS/2 and the x glyph give the em, font box and vertical metric candidates") {
    val font = parsed()
    assertEquals(font.unitsPerEm, 1000)
    assertEquals(font.fontBox, GlyphBox(-300, -250, 600, 950))
    assertEquals(font.verticalMetrics, Vector(VerticalMetrics(800, 200)))
    assertEquals(font.xHeights, Vector(480))
    assert(font.hasGlyphOutlines)
    assertEquals(font.unmodelledTables, Vector.empty)
  }

  test(
    "distinct hhea, typographic and Windows metrics are all candidates when a viewer may use them"
  ) {
    val split = Os2(typo = (700, 300), win = (900, 250))
    assertEquals(
      parsed(bytes(os2 = Some(split))).verticalMetrics,
      Vector(VerticalMetrics(800, 200), VerticalMetrics(900, 250)),
      "typographic metrics only count when USE_TYPO_METRICS is set"
    )
    assertEquals(
      parsed(bytes(os2 = Some(split.copy(useTypoMetrics = true)))).verticalMetrics,
      Vector(VerticalMetrics(800, 200), VerticalMetrics(700, 300), VerticalMetrics(900, 250))
    )
    val v1 = parsed(bytes(os2 = Some(split)))
    assertEquals(v1.xHeights, Vector(480), "without sxHeight the x glyph's top is measured")
    assertEquals(parsed(bytes(os2 = None)).verticalMetrics, Vector(VerticalMetrics(800, 200)))
  }

  test("cmap format 4 maps by delta and by glyph array, format 12 reaches astral code points") {
    for rangeOffsets <- Vector(false, true) do
      val font = parsed(bytes(rangeOffsets = rangeOffsets))
      assertEquals(font.glyphFor('H'.toInt), Some(H), s"rangeOffsets=$rangeOffsets")
      assertEquals(font.glyphFor(0xc1), Some(Aacute))
      assertEquals(font.glyphFor('Q'.toInt), None)
      assertEquals(font.glyphFor(0x1d400), None, "format 4 is BMP only")
    val twelve = parsed(bytes(cmapFormat = 12))
    assertEquals(twelve.glyphFor(0x1d400), Some(MathA))
    assertEquals(twelve.glyphFor('j'.toInt), Some(J))
    assertEquals(twelve.glyphFor(0x1d401), None)
  }

  test("advances repeat the last long metric, and boxes come from short or long loca") {
    for longLoca <- Vector(false, true) do
      val font = parsed(bytes(longLoca = longLoca, hMetrics = Some(11)))
      assertEquals(font.advance(H), 600)
      assertEquals(font.advance(FiLigature), 520, "glyph 10 is the last long metric")
      assertEquals(font.advance(IAlternate), 520, "glyphs after it repeat its advance")
      assertEquals(font.glyphBox(J), Some(GlyphBox(-150, -250, 420, 720)), s"longLoca=$longLoca")
      assertEquals(font.glyphBox(Space), None, "a glyph without contours has no ink")
  }

  test("a CFF-flavoured face without glyf bounds every glyph by the font-wide head box") {
    val cff = font(glyphs, cmap, signature = 0x4f54544fL, outlines = false)
    val parsedCff = parsed(cff)
    assert(!parsedCff.hasGlyphOutlines)
    assertEquals(parsedCff.glyphBox(H), Some(parsedCff.fontBox))
    assertEquals(parsedCff.glyphBox(Space), Some(parsedCff.fontBox))
  }

  test("GSUB: default features and their nested lookups, never a non-default feature") {
    val font = parsed()
    assertEquals(font.substitutes(I), Vector(IAlternate), "reached only through ccmp's context")
    assertEquals(font.substitutes(H), Vector.empty, "salt is not applied by default")
    assertEquals(font.ligatures(F), Vector((Vector(I), FiLigature)))
    assertEquals(font.ligatures(I), Vector.empty)
    assert(!font.multipleSubstitution(H))
  }

  test("GPOS: pair adjustments through an extension, class pairs, single adjustments, anchors") {
    val font = parsed()
    assertEquals(
      font.pairAdjustments(A, V),
      Vector((GlyphAdjustment(0, 0, -80), GlyphAdjustment.zero))
    )
    assertEquals(font.pairAdjustments(V, A), Vector.empty)
    assertEquals(font.markAttachments(4, E, Acute), Vector((500, 200)))
    assertEquals(font.markAttachments(4, A, Acute), Vector.empty)
    assert(font.hasMarkPositioning)
    val classes = parsed(
      bytes(extra =
        Map(
          "GPOS" -> layout(
            Seq("kern" -> Seq(0, 1), "dist" -> Seq(2)),
            Seq(
              Lookup(2, Seq(classPairPosition(Seq(A, E), Seq(V), -50, 30))),
              Lookup(2, Seq(pairPosition(A, V, -10))),
              Lookup(1, Seq(singlePosition(H, 5, 7, 11)))
            )
          )
        )
      )
    )
    assertEquals(
      classes.pairAdjustments(E, V),
      Vector((GlyphAdjustment(0, 0, -50), GlyphAdjustment(30, 0, 0)))
    )
    assertEquals(
      classes.pairAdjustments(E, A),
      Vector((GlyphAdjustment.zero, GlyphAdjustment.zero))
    )
    assertEquals(
      classes.pairAdjustments(A, V),
      Vector(
        (GlyphAdjustment(0, 0, -50), GlyphAdjustment(30, 0, 0)),
        (GlyphAdjustment(0, 0, -10), GlyphAdjustment.zero)
      ),
      "two kern lookups each apply"
    )
    assertEquals(classes.singleAdjustments(H), Vector(GlyphAdjustment(5, 7, 11)))
    assert(!classes.hasMarkPositioning)
  }

  test("a legacy kern table is read when present") {
    val font = parsed(bytes(extra = Map("kern" -> legacyKern(Seq((A, V, -100), (V, A, -40))))))
    assertEquals(font.legacyKern(A, V), -100)
    assertEquals(font.legacyKern(V, A), -40)
    assertEquals(font.legacyKern(A, A), 0)
    assert(!font.unreadableKern)
  }

  test("a lookup that invokes itself through its context terminates") {
    val looped = layout(
      Seq("ccmp" -> Seq(0)),
      Seq(Lookup(5, Seq(context(I, Seq(I), 0))))
    )
    val font = parsed(bytes(extra = Map("GSUB" -> looped)))
    assertEquals(font.substitutes(I), Vector.empty)
  }

  test("unsupported signatures, missing tables and impossible headers are not fonts") {
    assertEquals(SfntFont.parse(Array[Byte](0, 1, 0, 0)), None)
    assertEquals(SfntFont.parse("wOFF".getBytes("US-ASCII") ++ new Array[Byte](40)), None)
    val noCmap = assemble(Map("head" -> new Array[Byte](54)))
    assertEquals(SfntFont.parse(noCmap), None)
    val good = bytes()
    // A table record whose offset is near 2^32: compared before any addition, so it cannot
    // overflow into a small positive offset.
    val huge = good.clone()
    huge(12 + 8) = 0xff.toByte
    huge(12 + 9) = 0xff.toByte
    huge(12 + 10) = 0xff.toByte
    huge(12 + 11) = 0xf0.toByte
    assertEquals(SfntFont.parse(huge), None)
  }

  test(
    "every truncation and deterministic corruption of the face parses or refuses, never throws"
  ) {
    val good = bytes()
    val labels = Vector("Hj fi", "AV", "Á", "Éx", "́")
    def exercise(data: Array[Byte]): Unit =
      SfntFont.parse(data).foreach { font =>
        labels.foreach { label =>
          SvgTextExtent.codePoints(label).foreach { cps =>
            Vector(0.0, 0.5, 1.0).foreach(f => SvgTextExtent.measure(font, cps, f))
          }
        }
        SfntFont.guard((font.verticalMetrics, font.xHeights, font.unmodelledTables))
      }
    (0 until good.length by 7).foreach(length => exercise(good.take(length)))
    var seed = 0x9e3779b97f4a7c15L
    def next(): Long =
      seed += 0x9e3779b97f4a7c15L
      var z = seed
      z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
      z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
      z ^ (z >>> 31)
    (0 until 300).foreach { _ =>
      val corrupt = good.clone()
      (0 until 4).foreach { _ =>
        val at = java.lang.Long.remainderUnsigned(next(), corrupt.length.toLong).toInt
        corrupt(at) = next().toByte
      }
      exercise(corrupt)
    }
  }

  test("an inconsistent loca refuses the measurement instead of reading past glyf") {
    val good = bytes()
    // Make glyph J's end offset smaller than its start: locate loca through the directory.
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
    val face = parsed(broken)
    assertEquals(
      SvgTextExtent.measure(face, Vector('j'.toInt), 0.0),
      Left("the face has a malformed table")
    )
  }
