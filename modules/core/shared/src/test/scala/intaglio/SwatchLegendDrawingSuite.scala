package intaglio

class SwatchLegendDrawingSuite extends munit.FunSuite:
  private val title = LegendTitle
    .make("Functional regions with a long descriptive quantity", Some("atlas parcels"))
    .toOption
    .get
  private val entries = Vector(
    "Motor and premotor association cortex" -> Rgba32.unsafe(200, 0, 0),
    "Visual association cortex" -> Rgba32.unsafe(0, 0, 200, 120),
    "Unmapped" -> Rgba32.unsafe(0, 0, 0, 0)
  )

  test(
    "swatch rows wrap labels, preserve exact colors, and remain disjoint at manuscript and poster sizes"
  ):
    for style <- Vector(
        SwatchLegendStyle(widthPt = 150),
        SwatchLegendStyle(widthPt = 280, fontPt = 20, swatchPt = 28)
      )
    do
      val drawing =
        SwatchLegendDrawing.draw(title, entries, Vector("Unlit mapping colors"), style).toOption.get
      assertEquals(drawing.entries.map(_.color), entries.map(_._2))
      drawing.entries
        .sliding(2)
        .foreach(pair => assert(pair(0).topPt + pair(0).heightPt < pair(1).topPt))
      assert(drawing.entries.last.topPt + drawing.entries.last.heightPt < drawing.heightPt)
      assert(drawing.scene.grobs.collect {
        case text: Grob.Text if text.name.exists(_.value.startsWith("swatch-label-0")) => text
      }.length > 1)

  test("measured lines fit their width, including long tokens and unicode code points"):
    val layout = LegendTextLayout
      .wrap("A long title Supercalifragilisticexpialidocious 😀😀😀", 45, 10)
      .toOption
      .get
    assert(layout.lines.length > 4)
    layout.lines.foreach(line =>
      assert(TextMetrics.estimate.widthPt(line, TextStyle(Some("sans-serif"), 10)) <= 45)
    )
    assertEquals(
      layout.lines.mkString.replace(" ", ""),
      "AlongtitleSupercalifragilisticexpialidocious😀😀😀"
    )

  test("invalid metadata and insufficient text width fail before drawing"):
    assert(SwatchLegendDrawing.draw(title, Vector.empty).isLeft)
    assert(SwatchLegendDrawing.draw(title, Vector(" " -> entries.head._2)).isLeft)
    assert(LegendTextLayout.wrap("M", 0.01, 10).isLeft)
    assert(LegendTextLayout.wrap("text", Double.NaN, 10).isLeft)

  test("additional scalar notes reserve height without changing calibration identity"):
    val mapping = ScalarMapping(
      ScalarScale.sequential(
        DisplayWindow.unsafe(0, 1),
        ScalarRamp.linear(entries(0)._2, entries(1)._2)
      )
    )
    val legend = ScalarLegend.make(mapping, title).toOption.get
    val initial = ScalarLegendDrawing.draw(legend).toOption.get
    val annotated = ScalarLegendDrawing
      .draw(
        legend,
        extraNotes = Vector("Layer opacity and compositing can change displayed colors")
      )
      .toOption
      .get
    assertEquals(annotated.legendKey, initial.legendKey)
    assert(annotated.heightPt > initial.heightPt)
    assert(ScalarLegendDrawing.draw(legend, extraNotes = Vector(" ")).isLeft)
