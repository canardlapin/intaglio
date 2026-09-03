# 0006. Columnar marks stay one grob

Status: Accepted
Date: 2026-09-03

## Context

A scatter plot of a hundred thousand observations is a hundred thousand marks that differ in
position and, usually, in nothing else. Represented as one `Grob` per mark, that is a hundred
thousand `Points` values, each holding a `Vector[Point]` of length one, an `ExtentExpr`, a
`PointShape`, and a complete `GraphicParams` — eleven fields including two `Option[Rgba]`, a
`Length`, and an `Option[PatternPaint]`. The style is identical across all of them, and the scene
retains a separate copy per mark. Lowering then multiplies the same shape by a hundred thousand
`DevicePrimitive.Disc` values, and the backend issues a hundred thousand independent draw calls.

The obvious fix — collapse the batch into one primitive at the last moment, inside each backend — is
the wrong shape. It would put a performance decision in five places, let them disagree about whether
a batch is eligible, and make the decision invisible to the shared conformance contract.

The second obvious fix — widen `Grob.Points` so its style fields become vectors — loses the
distinction that matters. A batch whose marks share one colour and a batch whose marks each have
their own colour have different costs and different backend strategies, and a plain `Vector` of
identical values cannot say which one it is.

## Decision

Cardinality is explicit in the type. `BatchColumn[+A]` has two cases: `Constant(value)`, one value
for the whole batch, and `Values(values)`, exactly one per mark. `valueAt(index)` reads either
uniformly; `isConstant` and `valueCount` expose the distinction to a backend that wants to hoist
state-setting out of its loop. `BatchColumn.compact` collapses a `Vector` whose elements are all
equal into `Constant`, so a producer does not have to decide.

`Grob.PointBatch(points, sizes, shapes, graphicParams, viewport, name)` carries one
`Vector[Point]` and three `BatchColumn`s. Every column is checked against the point count at
construction — `Grob.pointBatch` returns `GraphicsError.BatchColumnLengthMismatch(column, marks,
values)` naming the column — and the case class `require`s the same invariant so it cannot be
violated internally either.

Device lowering keeps it whole. `DeviceScene` resolves the points, maps `sizes` through
`LengthResolver.extent` and `graphicParams` through `LengthResolver.graphicParams` with
`BatchColumn.traverse` (which preserves `Constant`-ness), passes `shapes` through unchanged, and
emits exactly one `DevicePrimitive.PointBatch(points, radii, shapes, graphicParams, name)`. It does
not fan out. Backends therefore see the batch, and `DeviceScene`'s validation walks it per index,
checking each radius and each mark's graphic parameters, so a batch is validated as strictly as the
scalar form.

The scalar `Grob.Points` remains, and it does fan out — into one `Disc`, one `RectShape`, one closed
`Polyline`, or two open `Polyline`s per point depending on `PointShape`. The two forms are
deliberately not merged: `Points` is what an author writes by hand for a handful of marks, and
`PointBatch` is what the compiler emits for a layer.

Identity is per batch, not per mark. One `GraphicsName` names the whole `PointBatch`, and per-datum
semantic identity comes from `DatumIdSeries`, which stores a prefix `SemanticId` and a count and
generates `<layer-id>-datum-<i>` on demand from `valueAt(index)`. A lean batch keeps its columnar
memory behaviour and still has an addressable identity for every mark.

## Consequences

A layer's memory cost becomes the cost of its data plus a constant, not the cost of its data times
the size of a style record. The columns that are constant are stored once.

The representation is honest at every stage. A reader of a `Scene`, of a `DeviceScene`, or of a
backend's own recorded output can see that a hundred thousand marks share one `GraphicParams`,
because that fact is `BatchColumn.Constant` rather than a hundred thousand equal values. A backend
can set its stroke and fill once and then loop, and it can decide to do so from `isConstant` rather
than by comparing values.

Rendering is provably equivalent to the fanned-out form. `ProvenancePolicySuite` normalizes a device
scene by expanding every `PointBatch` back into the per-mark primitives `Grob.Points` would have
produced, and asserts equality against the same plot compiled with full provenance. That expansion
is the specification: `Circle` → `Disc`, `Square` → a zero-radius `RectShape` spanning `[-r, r]`,
`Triangle` and `Diamond` → closed `Polyline`s, `Cross` → two open `Polyline`s, with
`PointShape.diamondHalfDiagonal` supplying the diamond's vertices.

The cost lands on backend authors. `DevicePrimitive.PointBatch` is a case every renderer must
handle, and handling it correctly means honouring per-index `shapes` and `graphicParams` rather than
reading index zero and drawing everything the same way. The reference harness in
`modules/laws/shared/src/test/scala/external/laws/ExternalBackendLawsSuite.scala` shows both the
per-index walk in `firstNonFinite` and the index-zero read in `primitiveKind`; only the first is a
model for a real renderer.

Marker granularity is coarser. `RendererHarness.containsMarker` can assert that a batch is present
but not that mark 4,271 is present, because there is one name. Per-mark assertions go through
`DatumIdSeries` or through the semantic sidecar, not through grob names.

`BatchColumn.valueAt` is partial for `Values` — it indexes a `Vector` and throws
`IndexOutOfBoundsException` past the end. That is acceptable only because the length invariant is
established at construction and re-checked at lowering; it is listed as a totality boundary in
`docs/extending/unsafe.md`.

Only points are batched today. Lines, polygons, segments, rectangles, text, and images have no
columnar form. Adding one is a new `Grob` case, a new `DevicePrimitive` case, and a new conformance
case for every backend, and it has not been done because points are where the mark counts are.

## Alternatives considered

**Fan out in the scene and re-batch inside each backend.** Rejected: five implementations of the
same eligibility decision, invisible to `RendererConformance`, and no memory saving in the scene
itself.

**Widen `Grob.Points` so every style field becomes a `Vector`.** Rejected: it erases the
constant/varying distinction that makes batching worth doing, and it forces the common case — one
style for the whole layer — to allocate a vector of identical values.

**An opaque `PointBuffer` of primitive arrays.** Rejected: it would give the best memory profile and
the worst inspectability. `BatchColumn` keeps typed `ExtentExpr`, `PointShape`, and `GraphicParams`
values readable in a `Scene`, which is what `SceneDeviceLaws` and the golden suites depend on.

**Fan out during lowering and let backends batch only if they wish.** Rejected: `DeviceScene` is the
shared contract, so a decision made there is a decision made once. Fanning out would make the
columnar form a `Scene`-only optimization that never reaches the place where the draw calls are.

**Per-mark `GraphicsName`s inside a batch.** Rejected: a fourth `BatchColumn` of names would restore
most of the memory cost the batch exists to remove, and `DatumIdSeries` already supplies addressable
per-mark identity without storing anything per mark.
