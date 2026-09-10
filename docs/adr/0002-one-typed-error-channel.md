# 0002. One typed error channel

Status: Accepted
Date: 2026-09-03

## Context

A plot fails for many unrelated reasons: a blank scale name, a value outside a transform's domain, a
palette with fewer entries than levels, a layout with no room for its axis strip, an SVG title
containing a code point XML cannot represent, a PDF font with no glyph for a character. Some are
authoring mistakes caught before any row is read; some depend on the data; some depend on the
render target; some belong to one backend and cannot be expressed in the shared core at all.

A single library-wide exception type discards that structure. A single library-wide error enum in
`intaglio-core` cannot hold backend cases without core depending on every backend. Separate,
unrelated error types per module force a caller who compiles a plot and then renders it to unify two
error types by hand at the seam.

Compilation itself has a second, subtler problem: a user-supplied `Row => A` accessor can throw, a
user-supplied `Breaks` generator can loop or emit non-finite values, and a user-supplied
`TextMetrics` can throw during layout. Those are third-party failures inside a pure pipeline.

## Decision

`IntaglioError` is a bare `trait` with one member, `def message: String`. It is the shared supertype
and nothing more: it declares no cases, so it does not constrain what a backend can report.

Each module publishes its own closed enum extending it. `GraphicsError` is the core's, and it is
large and specific — one case per validated condition, each carrying the values that failed rather
than a pre-rendered string. `StatError` and `DisplayError` are core enums for narrower surfaces;
`StatError.toGraphicsError` re-frames a statistic's failure with layer provenance when the compiler
crosses that boundary. `SvgRenderError`, `CanvasRenderError`, `Java2DRenderError`,
`JavaFxRenderError`, `PdfRenderError`, and `NotebookRenderError` each add their own cases plus a
`Graphics(error: GraphicsError)` wrapper for failures inherited from lowering.

Every smart constructor returns `Either[E, A]`. Case-class constructors are private wherever
validation exists, so an invalid `Interval`, `Rgba`, `Length`, `Viewport`, or `Grob` is not
constructible through the public API at all.

Alongside each enum, its companion defines one extension:

```
extension [A](either: Either[E, A]) def orThrow: A
```

which throws `IllegalArgumentException(error.message)` on `Left`. Every `X.unsafe(...)` in the
library is defined as `X.apply(...).orThrow` — one implementation, one exception type, one message,
no separate validation path that could drift.

Third-party code inside the pipeline is caught rather than propagated. `RowMapping` declares its
contract (`total`, `checked`, `throwing`) and the compiler evaluates it through `RowMapping.evaluate`,
turning a non-fatal exception into `MappingFailure.Threw` and then into either a `DroppedRow` or
`GraphicsError.MappingEvaluationFailed`. `Breaks.generate` wraps a custom generator's `apply` and
validates its output for finiteness, strict increase, and size. `PlotLayoutSolver.solve` wraps the
supplied `TextMetrics` and converts a throw into `GraphicsError.LayoutMeasurementFailed`.
`Transform.transform` and `Transform.inverse` wrap the supplied functions into
`GraphicsError.TransformEvaluationFailed`.

## Consequences

A pipeline that compiles and then renders has one error type. `Either[IntaglioError, String]` is the
type of the SVG example in the root README, and it needs no adapter at the seam.

Because the enums are closed within their module, a `match` over `GraphicsError` is exhaustive and a
new case is a compile error for exhaustive consumers — which is the intended pressure. Because
`IntaglioError` itself is open, adding a backend never touches core.

`GraphicsError` is long — over a hundred cases — and grows with the library. That is accepted: each
case carries its own operands, so the message can be precise (`"aesthetic 'x' uses different plot
scales in layers 0 ('time') and 2 ('index')"`) rather than a generic string built at the throw site.
A caller that only wants text uses `.message` and never matches.

`orThrow` and the `unsafe` constructors are a deliberate escape hatch, not an oversight. They exist
for two situations: a literal known good at authorship (`Rgba.unsafe(31, 119, 180)`,
`GraphicsName.unsafe("panel")`) and a test or REPL where an `Either` would be noise. They are
enumerated with their total alternatives in `docs/extending/unsafe.md`, and library code uses them
only where the argument is a compile-time constant.

The exception type is `IllegalArgumentException` in every case, so a caller cannot distinguish a
blank name from a degenerate interval by catching. That is intentional: `orThrow` is for values the
caller has already reasoned about, and anything that needs to be distinguished should not have gone
through `orThrow`.

## Alternatives considered

**A sealed hierarchy rooted in core covering every backend.** Rejected: it inverts the dependency,
and it would force core to know about PDF fonts and Canvas pattern resources.

**`cats.data.Validated` or an effect type.** Rejected: `intaglio-core` has no dependencies and
cross-compiles to Scala.js; a typeclass-heavy error channel would either add a dependency or
duplicate one. `Either` is in the standard library, is understood without documentation, and
composes in `for`.

**Accumulating all errors instead of failing at the first.** Rejected for now. The compiler phases
are sequential and later phases are meaningless once an earlier one fails — a scale that could not
be named cannot train, and rows that could not be mapped cannot be positioned. Row-level diagnostics
that *are* accumulable are already accumulated, as `DroppedRow` vectors, rather than being errors.

**Exceptions with a typed payload.** Rejected: the failure would not appear in any signature, and
the Scala.js and JVM courts could disagree about which exceptions are catchable.
