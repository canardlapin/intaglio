package intaglio.javafx

import intaglio.*

class JavaFxOverlayStyleSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)
  private val light = Rgba.White
  private val dark = Rgba.unsafe(18, 18, 18)

  test("the default style is the host's original overlay") {
    val style = JavaFxOverlayStyle.default
    assertEquals(style.selection.color, Rgba.unsafe(0x00, 0x72, 0xb2))
    assertEquals(style.hover.color, Rgba.unsafe(0xd5, 0x5e, 0x00))
    assertEquals(Vector(style.selection, style.hover).map(_.widthLogicalPx), Vector(2.0, 2.0))
    assertEquals(Vector(style.selection, style.hover).map(_.casingColor), Vector(None, None))
    assertEquals(style.focus.color, Rgba.Black)
    assertEquals(style.focus.widthLogicalPx, 2.0)
    assertEquals(style.focus.casingColor, Some(Rgba.White))
    assertEquals(style.focus.casingWidthLogicalPx, Some(5.0))
    assertEquals((style.highlightOffsetLogicalPx, style.focusOffsetLogicalPx), (3.0, 5.0))
    assertEquals(style.outline, OverlayOutline.Bounds)
  }

  test("widths, casings and offsets are checked at the typed boundary") {
    assertEquals(
      OverlayStroke(Rgba.Black, 0).left.toOption,
      Some(JavaFxOverlayError.InvalidWidth("stroke", 0))
    )
    assert(OverlayStroke(Rgba.Black, Double.NaN).isLeft)
    assertEquals(
      OverlayStroke.cased(Rgba.Black, 3, Rgba.White, 3).left.toOption,
      Some(JavaFxOverlayError.CasingNotWider("stroke"))
    )
    assert(OverlayStroke.cased(Rgba.Black, 2, Rgba.White, Double.PositiveInfinity).isLeft)
    val stroke = ok(OverlayStroke(Rgba.Black, 2))
    assertEquals(
      JavaFxOverlayStyle(stroke, stroke, stroke, highlightOffsetLogicalPx = -1).left.toOption,
      Some(JavaFxOverlayError.InvalidOffset("highlight", -1))
    )
    assert(JavaFxOverlayStyle(stroke, stroke, stroke, focusOffsetLogicalPx = Double.NaN).isLeft)
    val geometry = ok(JavaFxOverlayStyle(stroke, stroke, stroke, outline = OverlayOutline.Geometry))
    assertEquals(geometry.outline, OverlayOutline.Geometry)
    assertEquals(geometry.withOutline(OverlayOutline.Bounds).outline, OverlayOutline.Bounds)
  }

  test("WCAG contrast ratios match the published endpoints") {
    assertEqualsDouble(JavaFxOverlayStyle.contrastRatio(Rgba.Black, Rgba.White), 21.0, 1e-9)
    assertEqualsDouble(JavaFxOverlayStyle.contrastRatio(Rgba.White, Rgba.White), 1.0, 1e-12)
    // #767676 on white is the classic smallest grey meeting 4.5:1.
    assertEqualsDouble(
      JavaFxOverlayStyle.contrastRatio(Rgba.unsafe(0x76, 0x76, 0x76), Rgba.White),
      4.54,
      0.01
    )
  }

  test("a cased focus ring reaches 3:1 against both light and dark backgrounds") {
    assert(JavaFxOverlayStyle.default.focusContrast(light) >= 3)
    assert(JavaFxOverlayStyle.default.focusContrast(dark) >= 3)
    val yellow = Rgba.unsafe(255, 230, 0)
    val accent = ok(OverlayStroke(Rgba.unsafe(0x00, 0x72, 0xb2), 2))
    val bare = ok(JavaFxOverlayStyle(accent, accent, ok(OverlayStroke(yellow, 2))))
    assert(bare.focusContrast(light) < 3, "a light accent alone vanishes on a light background")
    val cased =
      ok(JavaFxOverlayStyle(accent, accent, ok(OverlayStroke.cased(yellow, 2, Rgba.Black, 5))))
    assert(cased.focusContrast(light) >= 3)
    assert(cased.focusContrast(dark) >= 3)
  }
