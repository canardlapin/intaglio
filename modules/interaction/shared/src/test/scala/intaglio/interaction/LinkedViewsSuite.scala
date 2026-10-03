package intaglio.interaction

import intaglio.*

/** Linking views by typed key: the same observation in two plots of different row order, categories
  * through link keys, namespaces that never join, projections that never echo, and axes that link
  * only when they mean the same data.
  */
class LinkedViewsSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Trial(id: String, rt: Double, accuracy: Double, block: String)

  private val trials = Vector(
    Trial("t1", 400, 0.9, "A"),
    Trial("t2", 520, 0.7, "B"),
    Trial("t3", 380, 0.8, "A"),
    Trial("t4", 610, 0.6, "B"),
    Trial("t5", 450, 0.95, "A")
  )
  private val trialsSpace = ok(KeySpace("trial", KeyCodec.text))
  private val blocks = ok(KeySpace("block", KeyCodec.text))

  private def plan(
      rows: Vector[Trial],
      id: String,
      space: KeySpace[String] = trialsSpace
  ): InteractionPlan[String] =
    val source = PlotLayer.inherited(Layer.point[Trial](_.rt, _.accuracy))
    val plot = ok(Plot(rows).addPackagedLayer(source))
    ok(
      InteractionCompiler.compileBound(
        plot,
        Vector(LayerBinding(source, space)(_.id).withLinks(blocks)(_.block)),
        ok(DataRevision(id)),
        SemanticId.unsafe(id),
        ok(PlanRevision(id))
      )
    )

  private def domain(p: InteractionPlan[String]) = ok(InteractionDomain(Vector(p), p.revision))
  private def keys(space: KeySpace[String], ids: String*) = ids.map(i => ok(space.entity(i))).toSet

  private val byRt = plan(trials.sortBy(_.rt), "by-rt")
  private val byAccuracy = plan(trials.sortBy(_.accuracy), "by-accuracy")

  test("two plots in different row orders link the same observation keys") {
    val selected = Selection(keys(trialsSpace, "t2", "t5"))
    val projected = SelectionProjection.into(selected, domain(byAccuracy))
    assertEquals(projected.selection.entities, selected.entities)
    assertEquals(projected.missing, Set.empty[EntityKey[String]])
  }

  test("keys the receiving plot does not contain are reported, never invented") {
    val subset = plan(trials.take(3), "subset")
    val projected =
      SelectionProjection.into(Selection(keys(trialsSpace, "t1", "t4", "t5")), domain(subset))
    assertEquals(projected.selection.entities, keys(trialsSpace, "t1"))
    assertEquals(projected.missing, keys(trialsSpace, "t4", "t5"))
  }

  test("equal labels in a separately declared namespace never join") {
    // Same namespace text, same codec, different key space instance.
    val impostor = ok(KeySpace("trial", KeyCodec.text))
    val projected = SelectionProjection.into(Selection(keys(impostor, "t2")), domain(byAccuracy))
    assert(projected.selection.entities.isEmpty)
    assertEquals(projected.missing.size, 1)
    val foreignBlocks = ok(KeySpace("block", KeyCodec.text))
    val target = ok(byRt.groups.head.at(0))
    val ownBlock = target.links.in(blocks).head
    assert(LinkedEmphasis[String](links = Set(ownBlock)).matches(target))
    assert(
      !LinkedEmphasis[String](links = Set(ok(foreignBlocks.link(ownBlock.value)))).matches(target)
    )
  }

  test("visual targets never cross plots, so a selected bin stays a bin") {
    val target = ok(byRt.groups.head.at(0)).id
    val projected =
      SelectionProjection.into(Selection[String](targets = Set(target)), domain(byAccuracy))
    assertEquals(projected.selection, Selection[String]())
  }

  test("a legend link emphasizes exactly the marks of its category") {
    val linkA = ok(blocks.link("A"))
    val group = byAccuracy.groups.head
    val matched = (0 until group.size)
      .map(i => ok(group.at(i)))
      .filter(LinkedEmphasis[String](links = Set(linkA)).matches)
    assertEquals(matched.flatMap(_.entity).map(_.value).toSet, Set("t1", "t3", "t5"))
  }

  test("a projection cycle through three plots never echoes or loops") {
    val plans = Vector(byRt, byAccuracy, plan(trials.reverse, "reversed"))
    val controllers =
      plans.map(p => new InteractionController(ok(InteractionState.initial(domain(p)))))
    var sequence = 0L
    var events = Vector.fill(3)(0)
    var deliveries = 0
    def stamp(i: Int, cause: InputCause) =
      sequence += 1
      InputStamp(plans(i).revision, SemanticId.unsafe(s"host-$i"), sequence, cause)
    controllers.zipWithIndex.foreach { (controller, i) =>
      controller.subscribe { record =>
        events = events.updated(i, events(i) + 1)
        record.event match
          case InteractionEvent.SelectionChanged(value)
              if record.stamp.cause != InputCause.Projected =>
            controllers.indices.filter(_ != i).foreach { j =>
              deliveries += 1
              val into = SelectionProjection.into(value, controllers(j).state.toOption.get.domain)
              ok(
                controllers(j).dispatch(
                  stamp(j, InputCause.Projected),
                  InteractionAction.Select(into.selection, SelectionOperation.Replace)
                )
              )
            }
          case _ => ()
      }
    }
    ok(
      controllers(0).dispatch(
        stamp(0, InputCause.Pointer),
        InteractionAction.Select(Selection(keys(trialsSpace, "t3")), SelectionOperation.Replace)
      )
    )
    assertEquals(events, Vector(1, 0, 0), "only the reader's plot emits")
    assertEquals(deliveries, 2)
    controllers.foreach(c =>
      assertEquals(c.state.toOption.get.selection.entities, keys(trialsSpace, "t3"))
    )
  }

  final case class Timing(seconds: Double, ms: Double)

  test("axes link directly only when they mean the same data, or through a checked conversion") {
    val rows = Vector(Timing(0.2, 200), Timing(0.5, 500), Timing(0.9, 900))
    def trained(builder: PlotBuilder[Timing, ?]) = ok(builder.resolve)
    val seconds = trained(plot(rows).aes(_.seconds, _.ms).scaleXContinuous().geomPoint())
    val ms = trained(plot(rows).aes(_.ms, _.seconds).scaleXContinuous().geomPoint())
    val logSeconds = trained(
      plot(rows).aes(_.seconds, _.ms).scaleXContinuous(transform = Transform.log10).geomPoint()
    )
    assert(LinkedAxes.compatible(seconds, seconds, Aesthetic.X).isRight)
    assert(LinkedAxes.compatible(seconds, ms, Aesthetic.X).isLeft, "different domains")
    assert(LinkedAxes.compatible(seconds, logSeconds, Aesthetic.X).isLeft, "different transforms")
    val toMs = ok(Transform("seconds-to-ms", _ * 1000.0, _ / 1000.0))
    assert(LinkedAxes.converted(seconds, ms, Aesthetic.X, toMs).isRight)
    val wrong = ok(Transform("seconds-to-cs", _ * 100.0, _ / 100.0))
    assert(LinkedAxes.converted(seconds, ms, Aesthetic.X, wrong).isLeft)
    // Two different transforms that share a name are not the same axis.
    val fakeLog = ok(Transform("log10", v => v, v => v))
    val fakeLogSeconds =
      trained(plot(rows).aes(_.seconds, _.ms).scaleXContinuous(transform = fakeLog).geomPoint())
    assert(LinkedAxes.compatible(logSeconds, fakeLogSeconds, Aesthetic.X).isLeft)
    // A proportional conversion keeps the interior under a log transform; an offset bends it.
    val logMs =
      trained(
        plot(rows).aes(_.ms, _.seconds).scaleXContinuous(transform = Transform.log10).geomPoint()
      )
    assert(LinkedAxes.converted(logSeconds, logMs, Aesthetic.X, toMs).isRight)
    val temps = Vector(Timing(1, 274.15), Timing(10, 283.15), Timing(100, 373.15))
    val logC =
      trained(
        plot(temps).aes(_.seconds, _.ms).scaleXContinuous(transform = Transform.log10).geomPoint()
      )
    val logK =
      trained(
        plot(temps).aes(_.ms, _.seconds).scaleXContinuous(transform = Transform.log10).geomPoint()
      )
    val toKelvin = ok(Transform("c-to-k", _ + 273.15, _ - 273.15))
    assert(LinkedAxes.converted(logC, logK, Aesthetic.X, toKelvin).isLeft)
    assert(
      LinkedAxes
        .compatible(seconds, ok(plot(rows).aes(_.seconds, _.ms).geomPoint().resolve), Aesthetic.X)
        .isLeft,
      "an unscaled axis has no trained scale to link"
    )
  }
