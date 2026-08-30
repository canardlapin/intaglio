# intaglio-laws

`intaglio-laws` is the portable, test-framework-neutral contract kit for
Intaglio extension authors. It cross-compiles to the JVM and Scala.js and has
one production dependency: `intaglio-core`.

Add it only to the consumer's test configuration:

```scala
libraryDependencies +=
  "io.github.canardlapin" %%% "intaglio-laws" % intaglioVersion % Test
```

Every kit accepts an extension plus an explicit successful fixture and returns
a `LawSuite`. Adapt the structured result to the project's test framework:

```scala
import intaglio.*
import intaglio.laws.*

val suite = ScaleLaws(myScale, Vector(-1.0, 0.0, 1.0))

test("my scale obeys the Intaglio scale laws") {
  assertEquals(suite.failures, Vector.empty)
}
```

The available entry points are:

- `AestheticLaws` for typed-key identity and heterogeneous storage;
- `ScaleLaws` for descriptor consistency and deterministic public mapping;
- `StatLaws` for public contracts, deterministic computation, and declared
  input preservation;
- `GeomLaws` for checked aesthetic contracts and deterministic lowering;
- `CoordLaws` for deterministic, layer-preserving transforms and layout
  declarations;
- `PlotRecipeLaws` for deterministic conversion, compiler bridging, and scene
  compilation;
- `BackendLaws` for the complete marker, semantic, validation, and determinism
  contract in `RendererConformance`.

Scale, statistic, recipe, and coordinate outputs without ordinary value
equality can use the corresponding `withEquality` variant and provide a
domain-specific comparison. Law execution catches non-fatal exceptions and
reports them as `LawFailure` values instead of leaking them through the test
runner.

The tests under `shared/src/test/scala/external/laws` are a consumer court, not
production helpers. They independently implement every seam and run the kits
unchanged on both JVM and Scala.js.
