package intaglio

class AccessibilitySuite extends munit.FunSuite:
  private final case class Observation(x: Double, y: Double, condition: String)

  private val data = Vector(
    Observation(0.0, 1.0, "control"),
    Observation(1.0, 2.0, "treatment")
  )

  test("semantic IDs accept the portable SVG subset and reject ambiguous strings") {
    assertEquals(SemanticId("activation_plot-1.0").map(_.value), Right("activation_plot-1.0"))
    assertEquals(
      SemanticId("1 activation").left.toOption,
      Some(GraphicsError.InvalidSemanticId("1 activation"))
    )
    assertEquals(
      SemanticId("activation:plot").left.toOption,
      Some(GraphicsError.InvalidSemanticId("activation:plot"))
    )
    assertEquals(
      SemanticId("activation-\u0661").left.toOption,
      Some(GraphicsError.InvalidSemanticId("activation-\u0661"))
    )
  }

  test("plot, layer, and compact datum IDs survive rich, lean, and device batches") {
    val plotId = SemanticId.unsafe("activation-plot")
    val layerId = SemanticId.unsafe("subject-points")
    val layer = Layer.point[Observation](_.x, _.y).withSemanticId(layerId)
    val plot = Plot(data)
      .withSemanticId(plotId)
      .addLayer(layer)
      .fold(error => fail(error.message), identity)
    val rich = PlotCompiler
      .resolve(plot, PlotCompilerOptions.rich)
      .fold(error => fail(error.message), identity)
    val lean = PlotCompiler
      .resolve(plot, PlotCompilerOptions.lean)
      .fold(error => fail(error.message), identity)

    assertEquals(lean.semantics, rich.semantics)
    assertEquals(lean.semantics.id, plotId)
    assertEquals(lean.semantics.layers.map(_.id), Vector(layerId))
    assertEquals(lean.semantics.layers.head.datumIds.count, data.length)
    assertEquals(
      lean.semantics.layers.head.datumIds.values.map(_.value),
      Vector("subject-points-datum-0", "subject-points-datum-1")
    )
    assertEquals(lean.layers.head.rows, Vector.empty)
    assert(lean.layers.head.grobs.head.isInstanceOf[Grob.PointBatch])
    assertEquals(lean.scene.semantics, SceneSemantics.single(lean.semantics))

    val device = DeviceScene
      .fromScene(lean.scene, DeviceContext.unsafe(320.0, 240.0))
      .fold(error => fail(error.message), identity)
    assertEquals(device.semantics, lean.scene.semantics)
    assert(device.elements.exists {
      case DeviceElement.Mark(_: DevicePrimitive.PointBatch) => true
      case _                                                 => false
    })
  }

  test("trained plots expose deterministic layer and scale summaries plus authored alt text") {
    val domain = DiscreteDomain
      .ordered(Vector("control", "treatment"))
      .fold(error => fail(error.message), identity)
    val scale = DiscreteScale(
      "condition-color",
      domain,
      DiscretePalette.valuesUnsafe(Vector(Rgba.Black, Rgba.White))
    ).fold(error => fail(error.message), identity)
    val base = Plot(data)
      .withSemanticId(SemanticId.unsafe("condition-plot"))
      .withTitle("Condition response")
      .withDescription("Two experimental conditions measured over time.")
      .withAltText("Treatment rises above control at the second observation.")
    val plot = base
      .withScale(ScaleBinding(Aesthetic.Color, _.condition, scale))
      .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
      .fold(error => fail(error.message), identity)
    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions.lean)
      .fold(error => fail(error.message), identity)

    assertEquals(
      trained.altText,
      Some("Treatment rises above control at the second observation.")
    )
    assert(trained.textSummary.contains("Plot condition-plot: 1 layer, 2 resolved marks, 1 scale."))
    assert(
      trained.textSummary.contains(
        "Layer 0 (condition-plot-layer-0): geom=point, stat=identity, input=2, resolved=2, dropped=0."
      )
    )
    assert(
      trained.textSummary.contains(
        "Scale color (condition-color): kind=discrete, domain=ordered [control, treatment]."
      )
    )

    val revised = trained.withAltText("A domain expert's revised interpretation.")
    assertEquals(revised.altText, Some("A domain expert's revised interpretation."))
    assertEquals(
      revised.scene.semantics.plots.head.accessibleDescription,
      "A domain expert's revised interpretation."
    )
  }

  test("duplicate palette colors emit an inspectable ambiguity diagnostic") {
    val domain = DiscreteDomain
      .ordered(Vector("control", "treatment"))
      .fold(error => fail(error.message), identity)
    val scale = DiscreteScale(
      "ambiguous-condition",
      domain,
      DiscretePalette.valuesUnsafe(Vector(Rgba.Black, Rgba.Black))
    ).fold(error => fail(error.message), identity)
    val plot = Plot(data)
      .withScale(ScaleBinding(Aesthetic.Color, _.condition, scale))
      .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
      .fold(error => fail(error.message), identity)
    val trained = PlotCompiler.resolve(plot).fold(error => fail(error.message), identity)

    assertEquals(
      trained.accessibilityDiagnostics,
      Vector(AccessibilityDiagnostic.AmbiguousPalette("color", "ambiguous-condition", 2, 1))
    )
    assert(trained.textSummary.contains("Diagnostic ambiguous-palette:"))
  }

  test("duplicate explicit layer IDs fail before a scene can expose invalid DOM identity") {
    val duplicate = SemanticId.unsafe("repeated-layer")
    val first = Layer.point[Observation](_.x, _.y).withSemanticId(duplicate)
    val second = Layer.line[Observation](_.x, _.y).withSemanticId(duplicate)
    val plot = Plot(data)
      .addLayer(first)
      .flatMap(_.addLayer(second))
      .fold(error => fail(error.message), identity)

    assertEquals(
      PlotCompiler.resolve(plot).left.toOption,
      Some(GraphicsError.DuplicateSemanticId("repeated-layer"))
    )
  }
