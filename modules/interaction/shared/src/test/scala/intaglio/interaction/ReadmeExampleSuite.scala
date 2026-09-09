package intaglio.interaction

class ReadmeExampleSuite extends munit.FunSuite:
  test("module README selects observation 2 using the public API") {
    import intaglio.*
    import intaglio.interaction.*

    val result = for
      keys <- KeySpace("observations", KeyCodec.integer)
      plot <- Plot(Vector(1, 2, 3)).addLayer(Layer.point[Int](_.toDouble, _.toDouble))
      source <- DataRevision("data-1")
      revision <- PlanRevision("view-1")
      plan <- InteractionCompiler.compile(
        plot,
        keys,
        source,
        SemanticId.unsafe("example"),
        revision
      )(identity)
      domain <- InteractionDomain(Vector(plan), revision)
      initial <- InteractionState.initial(domain)
      entity <- keys.entity(2)
      changed <- InteractionState.reduce(
        initial,
        InputStamp(revision, SemanticId.unsafe("application"), 0, InputCause.Programmatic),
        InteractionAction.Select(Selection(Set(entity)), SelectionOperation.Replace)
      )
    yield changed.state.selection.entities.map(_.value)

    assert(result == Right(Set(2)))
  }
