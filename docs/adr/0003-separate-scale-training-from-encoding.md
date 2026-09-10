# 0003. Separate scale training from encoding

Status: Accepted
Date: 2026-09-03

## Context

A scale in a grammar of graphics does two unrelated jobs. It *learns* a domain from every layer that
binds it, and it *encodes* one value at a time into a position or a colour. Conflating them is the
classic source of plots that disagree with themselves: a colour legend built from one layer's levels
while a second layer's marks were painted from a different palette index, or an axis whose ticks
cover a range the marks exceed.

The conflation is easy to produce accidentally. If constructing a scale requires a domain, then
either the caller invents a provisional one (which is then silently wrong), or the constructor reads
rows (which makes scale construction depend on data that has not been through the statistic yet).
ggplot2 avoids this with a build step that trains scales after stats and before mapping; the
information is real, but it is carried in mutable `ggproto` objects rather than in types.

Intaglio needed the same ordering with the domain visible in the type of the value being compiled,
and it needed the answer to hold for facets, where some scales are shared across panels and some are
deliberately not.

## Decision

The declaration algebra and the trained artifact are different types, both under one supertype.

`ScaleValue[-In, +Out]` is what an aesthetic mapping carries. It has exactly two implementations.
`ScaleSpec[In, Out]` is an untrained declaration: it holds configuration only, its `descriptor` is
`ScaleDomain.Unspecified`, and `mapDeclaredValue` returns `None` — constructing one never inspects a
row and never invents a provisional domain. `Scale[-In, +Out]` is a concrete encoder with a real
domain that maps values now.

The compiler owns the transition. `ScaleValue.trainDeclaration(observations, theme, facetLocal)` is
its single entry point; it is `private[intaglio]`, so training is not something a caller performs
between phases. `PlotScaleRegistry` enforces one scale per aesthetic per plot — a second, different
declaration for the same aesthetic is `GraphicsError.ConflictingPlotScales`, naming both layers and
both scale names, rather than two independently normalized layers.

`ScaleTraining` records what a concrete scale does when the compiler offers it observations.
`PlotWide` is the ordinary grammar behavior: `ContinuousScale.trainPlotWide` retrains over the union
of the existing endpoints and the new observations, so a range can only widen; `DiscreteScale` and
`BandScale` append newly observed levels to the declared ones. `Fixed` returns the scale unchanged
from both training methods, which is the explicit limits contract behind `ContinuousScale.fixed`,
`DiscreteScale.fixed`, and `BandScale.fixed`.

Facets need a third behavior, and it is a different method rather than a third `ScaleTraining` case.
`trainFacet` *replaces* the domain from the panel's own observations rather than unioning with the
declaration, because a free panel scale must be able to shrink. `FacetScales` decides which
dimensions call it; colour and fill are never facet-local, so a legend cannot drift between panels.

A continuous scale retains two intervals, not one. `domain` is the raw data range and
`transformedDomain` is its image under the `Transform`. Encoding runs
`transform.transform(value)`, then `transformedDomain.rescale(...)`, then `OobPolicy`, then the
palette; breaks and labels are generated over the raw `domain` and filtered to it. That is why a log
axis has decade ticks in data units while its colours interpolate evenly in log space.

`Transform` carries an explicit `TransformDomain` built from `DomainBound.Open`/`Closed`, so
`log10`'s domain is `(0, ∞)` and `sqrt`'s is `[0, ∞]`. `Transform.transform` refuses a value outside
that domain before evaluating the function, and training silently skips values the transform rejects
rather than failing the plot.

`OobPolicy` acts on the normalized coordinate in `[0, 1]`, after the transform and the rescale, not
on the raw value. `Censor` returns `None` outside the unit interval, which becomes a
`DroppedRow(ScaleOutOfDomain)`; `Squish` clamps; `Keep` passes the value through and lets the
palette decide. Applying it in normalized space is what makes one policy meaningful for positions,
colours, sizes, and alphas alike.

## Consequences

The DSL can accept a scale before it knows anything about the data:
`.encode(Aesthetic.X, _.time, ContinuousScaleSpec.numeric("time").orThrow)` is a complete
declaration, and its descriptor honestly reports `Unspecified` until compilation replaces it. A
`TrainedPlot` therefore has no untrained scales anywhere in it — `RegisteredScale.trained` throws
`IllegalStateException` if a spec ever reaches that inspection boundary, which makes the invariant a
crash rather than a silent half-trained plot.

Training is idempotent in the direction that matters: a `PlotWide` continuous scale can only widen,
so re-training with an already-seen batch is a no-op. `ContinuousScaleTrainingLaws` checks that
incremental training equals one-shot training over the concatenation and is invariant to
permutation; `FixedScaleLaws` checks that a `Fixed` scale ignores later observations and keeps its
descriptor.

Because `Scale.trainPlotWide`, `Scale.trainFacet`, and `Scale.observation` are `private[intaglio]`,
an ecosystem `Scale` implementation cannot participate in plot-wide training. It inherits the
default `trainPlotWide`, which returns `this`. This is a real limitation, and the honest response is
for a custom scale to advertise `ScaleTraining.Fixed` in its descriptor — which is what the external
consumer court in `modules/laws/shared/src/test/scala/external/laws/` does. An ecosystem author who
needs a trainable domain composes a built-in scale family rather than implementing `Scale` directly.
It also means `FixedScaleLaws` cannot be applied to a custom scale: it needs observations, and only
built-in scales produce them.

Separating raw and transformed domains costs a second `Interval` per continuous scale and one more
thing to keep consistent. `ContinuousScale.train` computes both in one pass so they cannot disagree.

## Alternatives considered

**One `Scale` type with an optional domain.** Rejected: every encoding site would have to handle the
untrained case, and the "untrained" state would be representable in a `TrainedPlot`.

**Mutable scales trained in place, as ggplot2 does.** Rejected: it makes a `Plot` value's meaning
depend on whether it has been compiled, defeats structural sharing, and is not reproducible when the
same plot is compiled twice at two targets.

**A third `ScaleTraining` case for facet-local training.** Rejected: facet locality is a property of
the *plot's* `FacetScales`, not of the scale, and the same scale declaration must be usable in a
faceted and an unfaceted plot without editing.

**Applying `OobPolicy` to the raw value against the raw domain.** Rejected: it would need a separate
policy for each output kind, and it would give a different answer than the palette for a transformed
scale, because the raw domain is not evenly spaced in transformed coordinates.

**Opening `trainPlotWide` to ecosystem scales.** Rejected for now. It would require exposing
`ScaleObservation` and `CategoryObservation`, whose erased category recovery is guarded by
`CategoryIdentity` reference identity; publishing them would move that guard into the public API
before there is a consumer that needs it.
