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
- `TransformLaws`, `ContinuousScaleTrainingLaws`, `FixedScaleLaws`, and
  `OobPolicyLaws` for numeric transform and built-in scale algebra;
- `DiscreteDomainLaws.ordered` for declared/encounter order, or
  `DiscreteDomainLaws.unordered` for canonical, permutation-invariant domains;
- `NativeStatLaws` for count/bin mass, right-closed intervals, summary bounds,
  density integration, contour topology, and declared-order invariance;
- `NativePositionLaws` for identity, deterministic jitter, non-overlapping
  dodge, signed stack, and explicit stack-order preservation;
- `SceneDeviceLaws` for scene identity/associativity, ordered depth-first
  traversal, and deterministic numeric lowering;
- `PointShapeLaws` for point marks centred on their point and the `Diamond`
  area-parity rule (a diamond covers the circle's area at the same size);
- `CoordinateInvolutionLaws` for the native transpose involution across rows,
  grobs, annotations, and panel ranges;
- `PlotLayoutLaws` for deterministic finite frames, root bounds, and measured
  legend/colorbar stack fit;
- `TargetRecompilationLaws` for equal-physical-target applicability,
  renderer-neutral scene equality, and caller-selected target-bound facts;
- `StatLaws` for public contracts, deterministic computation, and declared
  input preservation;
- `GeomLaws` for checked aesthetic contracts and deterministic lowering;
- `CoordLaws` for deterministic, layer-preserving transforms and layout
  declarations;
- `PlotRecipeLaws` for deterministic conversion, compiler bridging, and scene
  compilation;
- `BackendLaws` for the complete marker, primitive-shape, paint, pattern, text
  style, image, group-effect, validation, and determinism contract in
  `RendererConformance`.

Renderer harnesses must render the canonical scenes at
`RendererConformance.targetContext()` (240 by 160 pixels at 96 ppi), or construct
backend options from the published target fields. This keeps point-valued stroke
widths and font sizes comparable across every backend.

Scale, statistic, recipe, and coordinate outputs without ordinary value
equality can use the corresponding `withEquality` variant and provide a
domain-specific comparison. Law execution catches non-fatal exceptions and
reports them as `LawFailure` values instead of leaking them through the test
runner.

Ordering applicability is explicit. An ordered discrete domain treats encounter
order as data, so its suite executes concatenation and encounter-order laws but
does not claim permutation invariance. An unordered domain uses the ordering in
its `CategoryIdentity`, so its suite executes concatenation and permutation laws
but does not claim encounter-order preservation. `TransformLaws` similarly
requires callers to declare increasing or decreasing monotonicity and checks
that declaration alongside round trips and the transform's open/closed
endpoints.

The native statistic and position suites use `SeededLaw.defaultSeeds`, a small
portable seed court that executes unchanged on the JVM and Scala.js. Pass an
explicit `Vector[Long]` to replay or expand a court. Every returned
counterexample—and every non-fatal exception caught during one seeded
case—includes `seed=<value>` in its `LawFailure.detail`.

`TargetRecompilationLaws` takes two `RenderContext` values that describe the
same physical page at different pixel densities. Its observer receives each
compiled `RenderPlan`; normalize the device facts that matter to physical units
and use `withEquality` when those observations need a tolerance-aware
comparison.

The tests under `shared/src/test/scala/external/laws` are a consumer court, not
production helpers. They independently implement every seam and run the kits
unchanged on both JVM and Scala.js.
