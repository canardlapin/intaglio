package intaglio.svg

import intaglio.*

/** Two plots inlined in one HTML page must not share an id: a prefixed document namespaces every id
  * it defines and every reference to one, and an unprefixed document is unchanged.
  */
class SvgIdPrefixSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(x: Double, y: Double)

  /** A compiled plot with a title (accessible title id), a clipped panel and a hatched rectangle.
    */
  private val plan: RenderPlan =
    val context = RenderContext.unsafe(320, 240)
    val base = ok(
      plot(Vector(Obs(0, 0), Obs(1, 2), Obs(2, 1)))
        .aes(_.x, _.y)
        .geomPoint()
        .title("Scores")
        .renderPlan(context)
    )
    val hatch = PatternPaint(ok(PatternRecipe.angledHatch(45.0, 6.0, 1.0)), Rgba.Black)
    val hatched = ok(
      Grob.rect(
        Point.npcUnsafe(0.1, 0.1),
        Size.npcUnsafe(0.2, 0.2),
        gp = GraphicParams.unsafe(fill = None).withPatternFill(hatch)
      )
    )
    base.copy(scene = Scene(base.scene.grobs :+ hatched).withSemantics(base.scene.semantics))

  private def ids(svg: String): Vector[String] =
    """\sid="([^"]+)"""".r.findAllMatchIn(svg).map(_.group(1)).toVector

  private def references(svg: String): Vector[String] =
    ("""url\(#([^)]+)\)""".r.findAllMatchIn(svg).map(_.group(1)) ++
      """aria-(?:labelledby|describedby)="([^"]+)"""".r
        .findAllMatchIn(svg)
        .map(_.group(1))).toVector

  private def render(prefix: Option[String]): String =
    prefix
      .fold(SvgRenderer.render(plan, None, SvgFonts.empty))(
        SvgRenderer.render(plan, None, SvgFonts.empty, _)
      )
      .fold(error => fail(error.message), _.value)

  test("the fixture defines clip, pattern and accessible-title ids") {
    val plain = render(None)
    assert(ids(plain).exists(_.startsWith("clip-")), ids(plain))
    assert(ids(plain).exists(_.startsWith("pattern-")), ids(plain))
    assert(ids(plain).exists(_.endsWith("-title")), ids(plain))
  }

  test("every id and every reference in a prefixed document is namespaced and resolves") {
    val svg = render(Some("left"))
    assert(ids(svg).nonEmpty)
    assert(ids(svg).forall(_.startsWith("left-")), ids(svg))
    assertEquals(ids(svg).distinct.length, ids(svg).length)
    assert(references(svg).nonEmpty)
    assert(references(svg).forall(ids(svg).contains), (references(svg), ids(svg)))
  }

  test("two prefixed documents share no id") {
    val left = ids(render(Some("left")))
    val right = ids(render(Some("right")))
    assertEquals(left.intersect(right), Vector.empty)
  }

  test("a prefix only renames ids: stripping it restores the unprefixed document") {
    val plain = render(None)
    val prefixed = render(Some("w1"))
    val restored = ids(plain).foldLeft(prefixed)((text, id) => text.replace(s"w1-$id", id))
    assertEquals(restored, plain)
  }

  test("invalid prefixes are refused") {
    Vector("", "1abc", "a b", "a\"b", "é", "a:b").foreach { prefix =>
      assertEquals(
        SvgOptions.default.withIdPrefix(prefix).left.toOption,
        Some(SvgRenderError.InvalidIdPrefix(prefix)),
        prefix
      )
    }
    assert(SvgOptions.default.withIdPrefix("plot_2-a").isRight)
  }
