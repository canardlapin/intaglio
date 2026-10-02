package intaglio.interaction

import intaglio.*

class InteractionBehaviorSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  test("links accept web, mail and relative references and refuse script-capable schemes") {
    Vector(
      "https://example.org/a?b=1#c",
      "http://example.org",
      "mailto:someone@example.org",
      "details/42",
      "/abs/path",
      "?query",
      "#fragment"
    ).foreach(url => assert(TargetLink(url).isRight, url))
    Vector(
      "javascript:alert(1)",
      "JavaScript:alert(1)",
      " javascript:alert(1)",
      "data:text/html,<b>x</b>",
      "vbscript:x",
      "file:///etc/passwd",
      "",
      "https://exa mple.org",
      "https://example.org/\nx",
      "//evil.example/x",
      "\\\\evil.example",
      "/\\evil.example",
      "details\\x"
    ).foreach(url => assert(TargetLink(url).isLeft, url))
  }

  test("content is text, never markup, and refuses characters a document cannot carry") {
    val markup = ok(TargetContent.text("<b>bold</b> & \"quoted\""))
    assertEquals(markup.plainText, "<b>bold</b> & \"quoted\"")
    assert(TargetContent.text("bell\u0007").isLeft)
    assert(TargetField("ok", "nul\u0000").isLeft)
    assert(TargetContent.text("lone \ud800 surrogate").isLeft)
    assert(TargetContent.text("astral \ud83d\ude00 ok").isRight)
    val fields = ok(
      TargetContent.fields(
        Some("Trial 3"),
        Vector(ok(TargetField("RT", "412 ms")), ok(TargetField("accuracy", "correct")))
      )
    )
    assertEquals(fields.plainText, "Trial 3; RT: 412 ms; accuracy: correct")
  }

  test("behaviour settings are checked") {
    val base = InteractionBehavior.default[String]
    assert(base.withTooltipDelay(-1).isLeft)
    assert(base.withTooltipDelay(20000).isLeft)
    assert(base.withHover(HoverRule.Nearest(0)).isLeft)
    assert(base.withHover(HoverRule.Nearest(Double.NaN)).isLeft)
    assertEquals(ok(base.withHover(HoverRule.Nearest(24))).hover, HoverRule.Nearest(24))
    assertEquals(base.selection, SelectionMode.Multiple)
  }

  test("entity description reads the typed key, and raster cells their value") {
    val space = ok(KeySpace("trial", KeyCodec.text))
    val behavior = InteractionBehavior.describingEntities[String](id => s"trial $id")
    val revision = ok(DataRevision("d"))
    val series = ok(
      TargetSeries(ok(SemanticId("plan")), ok(PlanRevision("p")), ok(SemanticId("layer")), 1)
    )
    val target = TargetInfo(
      ok(series.at(0)),
      Some(ok(space.entity("t7"))),
      ok(Membership.countOnly(space, revision, 1))
    )
    assertEquals(behavior.tooltip(target).map(_.plainText), Some("trial t7"))
  }
