package intaglio.interaction

import intaglio.*

/** In a composed figure every child draws its title, axes and annotations under the same plot-level
  * names. Each child's parts must still be its own: found under the pointer where that child draws
  * them, outlined only there, and never reported as a sibling's part.
  */
class ComposedPartsSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: String, x: Double, y: Double, condition: String)

  private val rows = Vector.tabulate(8)(i =>
    Obs(s"r$i", 1.0 + i, 1.0 + (i * 5 % 7), if i % 2 == 0 then "control" else "treated")
  )
  private val context = RenderContext.unsafe(900, 420)
  private val space = ok(KeySpace("composed-parts", KeyCodec.text))
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())

  private def compile(title: String, xTitle: String, id: String): InteractionPlan[String] =
    val spec = ok(
      plot(rows)
        .aes(_.x, _.y)
        .scaleColorDiscrete(_.condition, levels = Vector("control", "treated"), name = "condition")
        .geomPoint()
        .hline(3.0)
        .title(title)
        .axisTitles(xTitle, "Score")
        .build
    ).plot
    ok(
      InteractionCompiler.compile(
        spec,
        space,
        ok(DataRevision("rows-1")),
        SemanticId.unsafe(id),
        ok(PlanRevision("p1")),
        options
      )(_.id)
    )

  private val left = compile("Left study", "Session", "left-plan")
  private val right = compile("Right study", "Day", "right-plan")

  private def centre(ring: Vector[DevicePoint]): DevicePoint =
    DevicePoint(
      (ring.map(_.x).min + ring.map(_.x).max) / 2,
      (ring.map(_.y).min + ring.map(_.y).max) / 2
    )

  private def resolved(composed: ComposedInteraction[String]) =
    val scoped = ComposedParts.of(composed)
    val device = ok(DeviceScene.fromScene(scoped.scene, context))
    (scoped, ok(PartPicking.fromParts(scoped.parts, device, context)), device)

  test("each child keeps its own title, axes and annotation as separate parts") {
    val composed = ok(InteractionComposition.row(Vector(left, right), context))
    val (scoped, _, _) = resolved(composed)
    val kinds = scoped.parts.map(_.part)
    assert(kinds.contains(PlotPart.PlotTitle("Left study")), kinds)
    assert(kinds.contains(PlotPart.PlotTitle("Right study")), kinds)
    assert(kinds.contains(PlotPart.Axis(AxisSide.Bottom, Some("Session"))), kinds)
    assert(kinds.contains(PlotPart.Axis(AxisSide.Bottom, Some("Day"))), kinds)
    // Both children have an hline as layer 1: equal values, but two distinct parts.
    val annotations = scoped.parts.filter(_.part.isInstanceOf[PlotPart.Annotation])
    assertEquals(annotations.size, 2, annotations)
    assertEquals(annotations.map(_.part).distinct.size, 1)
    val names = scoped.parts.flatMap(_.names)
    assertEquals(names.distinct.size, names.size, "no name is claimed by two parts")
    // The y axes share a value ("Score") in both children but are still two parts.
    assertEquals(scoped.parts.count(_.part == PlotPart.Axis(AxisSide.Left, Some("Score"))), 2)
  }

  test("a part is found under the pointer and outlined only where its own child draws it") {
    val composed = ok(InteractionComposition.row(Vector(left, right), context))
    val (scoped, picking, _) = resolved(composed)
    def cell(part: PartTarget): Int =
      val cells = part.names.map(_.value).map {
        case n if n.startsWith("composition-cell-0-") => 0
        case n if n.startsWith("composition-cell-1-") => 1
        case n                                        => fail(s"$n is not scoped to a cell")
      }
      assertEquals(cells.distinct.size, 1, part)
      cells.head
    val probed = scoped.parts.flatMap { part =>
      ok(picking.outline(part, 0.0)).map { outline =>
        // A part's rings all lie inside its own child's half of the row.
        val xs = outline.rings.flatten.map(_.x)
        val middle = context.width / 2.0
        assert(
          xs.forall(x => if cell(part) == 0 then x <= middle else x >= middle),
          s"$part outlined outside its cell: ${xs.min}..${xs.max}"
        )
        part -> ok(picking.at(centre(outline.rings.head), 1.0))
      }
    }
    val titles = probed.filter(_._1.part.isInstanceOf[PlotPart.PlotTitle])
    assertEquals(titles.size, 2)
    titles.foreach((part, hit) => assertEquals(hit, Some(part)))
    val annotations = probed.filter(_._1.part.isInstanceOf[PlotPart.Annotation])
    assertEquals(annotations.size, 2)
    annotations.foreach((part, hit) => assertEquals(hit, Some(part)))
  }

  test("scoping renames only part names and keeps every typed target route") {
    val composed = ok(InteractionComposition.row(Vector(left, right), context))
    val (scoped, _, device) = resolved(composed)
    def routes(elements: Vector[DeviceElement]): Vector[String] = elements.flatMap {
      case DeviceElement.Annotated(meta, children) =>
        meta.data.collect {
          case (key, value) if key == InteractionCompiler.targetAttribute => value
        } ++ routes(children)
      case DeviceElement.Group(_, _, _, children) => routes(children)
      case _                                      => Vector.empty
    }
    assertEquals(routes(device.elements).toSet, composed.groups.map(_.name.value).toSet)
    val before = PlotParts.names(composed.scene.grobs)
    val after = PlotParts.names(scoped.scene.grobs)
    val renamed = scoped.parts.flatMap(_.names).toSet
    assertEquals(
      after -- renamed,
      before -- composed.plans.flatMap(p => PlotParts.of(p.trained)).flatMap(_.names).toSet
    )
    // Typed target picking still resolves every route over the scoped scene.
    ok(Picking.fromResolved(device, composed.groups, context))
  }

  test("a legend collected to figure level keeps its name and is one shared part") {
    val collect = CompositionOptions.unsafe(guides = CompositionGuidePolicy.CollectCompatible)
    val composed = ok(InteractionComposition.row(Vector(left, right), context, collect))
    val (scoped, picking, _) = resolved(composed)
    val entries = scoped.parts.filter(_.part.isInstanceOf[PlotPart.LegendEntry])
    assertEquals(entries.map(_.part.describe), Vector("condition: control", "condition: treated"))
    assert(entries.flatMap(_.names).forall(!_.value.startsWith("composition-")), entries)
    entries.foreach { entry =>
      val ring = ok(picking.outline(entry, 0.0)).getOrElse(fail(s"$entry not painted")).rings.head
      assertEquals(ok(picking.at(centre(ring), 1.0)), Some(entry))
    }
  }

  test("an inset child is scoped to its inset") {
    val inset = compile("Inset study", "Hour", "inset-plan")
    val composed = ok(
      ok(InteractionComposition.row(Vector(left, right), context))
        .withInset(inset, ok(PlotInset.npc(0.6, 0.6, 0.3, 0.3, Clip.On)))
    )
    val (scoped, picking, _) = resolved(composed)
    val title = scoped.parts
      .find(_.part == PlotPart.PlotTitle("Inset study"))
      .getOrElse(fail("no inset title"))
    assert(title.names.forall(_.value.startsWith("composition-inset-0-")), title)
    val ring = ok(picking.outline(title, 0.0)).getOrElse(fail("inset title not painted")).rings.head
    assertEquals(ok(picking.at(centre(ring), 1.0)), Some(title))
  }
