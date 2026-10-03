package intaglio.interaction

import intaglio.*

/** Plot components besides marks are typed, pickable targets: titles, legend entries, axes, facet
  * strips and annotations, each found under the pointer at the place it is drawn.
  */
class PlotPartsSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(x: Double, y: Double, condition: String, site: String)

  private val rows = Vector(
    Obs(1, 1, "control", "north"),
    Obs(2, 3, "treated", "north"),
    Obs(1, 2, "control", "south"),
    Obs(3, 1, "treated", "south")
  )
  private val context = RenderContext.unsafe(640, 480)

  private val trained = ok(
    plot(rows)
      .aes(_.x, _.y)
      .scaleColorDiscrete(_.condition, levels = Vector("control", "treated"), name = "condition")
      .geomPoint()
      .hline(2.0)
      .facetWrap(_.site)
      .title("Scores by site")
      .subtitle("Pilot")
      .axisTitles("Session", "Score")
      .resolve(context)
  )

  private val parts = PlotParts.of(trained)

  test("a titled, faceted plot with a legend exposes every part with its typed value") {
    val kinds = parts.map(_.part)
    assertEquals(kinds.distinct.length, kinds.length, "each part appears once")
    assert(kinds.contains(PlotPart.PlotTitle("Scores by site")), kinds)
    assert(kinds.contains(PlotPart.PlotSubtitle("Pilot")), kinds)
    val entries = kinds.collect { case e: PlotPart.LegendEntry => e.label }
    assertEquals(entries, Vector("control", "treated"))
    assert(
      kinds.exists { case PlotPart.LegendTitle(_, "condition") => true; case _ => false },
      kinds
    )
    val strips = kinds.collect { case s: PlotPart.FacetStrip =>
      s.rowLabel.toVector ++ s.columnLabel
    }
    assertEquals(strips.flatten.toSet, Set("north", "south"))
    assert(kinds.exists(_.isInstanceOf[PlotPart.Axis]), kinds)
    assert(kinds.exists(_.isInstanceOf[PlotPart.Annotation]), kinds)
  }

  test("every part is found under the pointer at the centre of its own outline") {
    val picking = ok(PartPicking.compile(trained, context))
    val probed = parts.filterNot(_.part.isInstanceOf[PlotPart.Axis]).flatMap { part =>
      ok(picking.outline(part, 0.0)).map { outline =>
        // One ring: a name drawn in several panels has one ring per panel.
        val points = outline.rings.head
        val centre = DevicePoint(
          (points.map(_.x).min + points.map(_.x).max) / 2,
          (points.map(_.y).min + points.map(_.y).max) / 2
        )
        part -> ok(picking.at(centre, 1.0))
      }
    }
    assert(probed.nonEmpty)
    probed.foreach { (part, hit) =>
      assertEquals(hit.map(_.part), Some(part.part), part)
    }
  }

  test("a plot without titles, legends or facets has only its axes as parts") {
    val plain = ok(plot(rows).aes(_.x, _.y).geomPoint().resolve(context))
    assert(PlotParts.of(plain).forall(_.part.isInstanceOf[PlotPart.Axis]), PlotParts.of(plain))
  }

  test("parts describe themselves for accessible names") {
    assert(parts.exists(_.part.describe == "condition: control"), parts.map(_.part.describe))
  }

  test("a colorbar is found through its title as well as its bar") {
    val shaded = ok(
      plot(rows)
        .aes(_.x, _.y)
        .scaleFillContinuous(_.y, name = "score")
        .geomTile(_ => 0.5, _ => 0.5)
        .resolve(context)
    )
    val colorbar = PlotParts.of(shaded).find(_.part.isInstanceOf[PlotPart.Colorbar])
    assert(colorbar.exists(_.names.exists(_.value.endsWith("-title"))), PlotParts.of(shaded))
    val picking = ok(PartPicking.compile(shaded, context))
    val title = colorbar.get.names.find(_.value.endsWith("-title")).get
    val ring = ok(NamedPicking.compile(shaded.scene, context))
      .outline(title, 0)
      .fold(e => fail(e.message), identity)
      .getOrElse(fail("title not painted"))
      .rings
      .head
    val centre = DevicePoint(
      (ring.map(_.x).min + ring.map(_.x).max) / 2,
      (ring.map(_.y).min + ring.map(_.y).max) / 2
    )
    assertEquals(ok(picking.at(centre, 1.0)).map(_.part), colorbar.map(_.part))
  }
