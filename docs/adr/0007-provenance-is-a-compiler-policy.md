# 0007. Provenance is a compiler policy

Status: Accepted
Date: 2026-09-03

## Context

Two questions get asked of a compiled plot, and they want opposite things.

"Which observations produced this bar, and why is that subject missing?" wants every source row
retained, every statistic output linked to its members, and every rejected row kept with the reason
it was rejected. That is an interactive, analytical question, and it is exactly what a statistical
graphics library should be able to answer — R's `ggplot_build` exists for it.

"Render this to SVG" wants none of that. It wants the grobs. Retaining the rows makes a plot cost a
multiple of its data for the lifetime of the value, which for a hundred thousand marks in a
long-lived server process is the difference between working and not.

A library can pick one and be wrong for the other half of its users, or it can guess — retain
provenance until memory pressure, cache weakly, drop silently — and be unpredictable. Neither is
acceptable when the same compiler runs on the JVM and in a browser.

## Decision

Retention is a declared compiler option with a published cost model, and the *rendered result does
not depend on it*.

`ProvenancePolicy` has five cases, and each carries a `ProvenanceRetentionCost` describing its
asymptotic retained memory in three fields — `statisticMembers`, `droppedRows`, and
`retainsSourceValues` — drawn from a `RetentionGrowth` scale of `None`, `Constant`, `PerOutput`,
`PerSourceIndex`, `FullSourceValues`. The cost is part of the enum, not prose: a caller can compare
policies programmatically, and `ProvenancePolicySuite` asserts the exact table.

| Policy | Statistic members | Dropped rows | Retains source values |
| --- | --- | --- | --- |
| `None` | `None` | `None` | no |
| `CountOnly` | `PerOutput` | `Constant` | no |
| `Representative` | `PerOutput` | `Constant` | yes |
| `SourceIndices` | `PerSourceIndex` | `PerSourceIndex` | no |
| `Full` | `FullSourceValues` | `FullSourceValues` | yes |

Each policy has a matching typed payload rather than a nullable superset. `StatisticProvenance` is
`CountOnly(index, count)`, `Representative(index, count, source)`, `SourceIndices(index, count,
indices)`, or `Full(index, members)`; `DroppedProvenance` is `None`, `CountOnly(value)`,
`Representative(total, sample)`, `SourceIndices(rows)`, or `Full(rows)`. A caller reads what the
policy promised and nothing else.

`SourceIndices` deliberately keeps `count` beside `indices`, and publishes
`hasCompleteSourceIndices` for the comparison. A statistic that generates outputs not traceable to
inputs — a kernel density estimate at grid points, say — produces fewer indices than members, and
that gap is visible rather than being reported as complete provenance. Index resolution itself is a
duplicate-safe multiset lookup: equal members resolve against source positions from the beginning,
so a whole-batch statistic may legitimately cite the same inputs from every output it generates.

Inspection is typed at each layer's own row. `TrainedLayer` is a sealed trait with an abstract
`type Row` and a `TrainedLayer.Aux[Row0]` refinement, wrapping a `ResolvedLayer[Row]` and forwarding
its fields. A plot as a whole has no single row type — an independent layer may carry its own — so
`TrainedPlot.layers` is a vector of existentially packed layers while each layer's `rows`,
`droppedRows`, `statFrame`, and `inspection` stay precise at its own `Row`. `TrainedDroppedRow` does
the same packing for `TrainedPlot.droppedRows`.

Rejection is a typed value, not a filter. `DroppedRow(layerIndex, rowIndex, source, reason)` carries
the source row and a `PlotDropReason` — twelve cases covering missing and non-finite aesthetics,
missing positions and labels, invalid bounds, transform-domain and scale-domain rejections, palette
overflow, unavailable grouping categories, and mapping-contract violations. A row never disappears
without a stated reason.

Release happens last. `PlotCompiler.resolve` runs the full pipeline and then calls
`retainRequestedInspection`, which — for any policy other than `Full` — replaces `statFrame.rows`,
`rows`, and `droppedRows` with empty vectors on every top-level and facet-panel layer while keeping
the `LayerInspection` payload the policy asked for. Because the semantic sidecar is built before
that, `TrainedPlot.semantics` retains `inputRows`, `resolvedRows`, and `droppedRows` counts per
layer at every policy: a lean plot still reports how many rows it dropped, just not which ones.

`DatumIdSeries` follows the same principle for identity. It stores a prefix `SemanticId` and a
count, and generates `<layer-id>-datum-<i>` from `valueAt(index)` on demand, so a lean columnar
batch keeps addressable per-mark identity without storing anything per mark.

The default is asymmetric on purpose. `PlotCompilerOptions.default` (and `.rich`) is `Full`, and
`PlotCompiler.resolve` uses it — resolving a plot is the inspection entry point. `PlotCompiler.compile`,
which returns a `Scene`, defaults to `PlotCompilerOptions.lean`, which is `None` — rendering does not
need provenance. `PlotCompilerOptions.lean` is also the name to reach for explicitly in a server.

## Consequences

The rendered scene is identical under every policy. `ProvenancePolicySuite` proves it by lowering
each policy's scene to a `DeviceScene`, expanding every `PointBatch` back into per-mark primitives,
and asserting equality against the `Full` result — including for a faceted plot, where the release
must reach `facetPanels.layers` as well as `layers`.

A caller chooses cost with one field and can read the consequence from the type. `ProvenancePolicy.
Representative` is the useful middle: constant dropped-row memory, one representative source per
statistic output, enough to render a tooltip or explain a bar without retaining the batch.

The five payload enums are more API than one optional record would be, and a caller that wants to
handle several policies uniformly writes a match. That is the trade: `StatisticProvenance.memberCount`
and `DroppedProvenance.count` exist as the common projections, and anything richer is
policy-specific by construction.

Provenance under `SourceIndices` and `Full` depends on `Row` equality. `LayerInspection.capture`
builds its index with a `HashMap[Row, ArrayBuffer[Int]]`, so a row type with reference equality gives
correct but position-sensitive results, and a row type with a broken `equals` gives wrong indices. In
practice rows are case classes and this holds; it is not enforced.

`Full` retains the entire input for the lifetime of the `TrainedPlot`, and it is the default for
`resolve`. A caller who resolves rather than compiles, and does not set `provenance`, holds their
data. That is the right default for an inspection API and the wrong one for a render loop, which is
why `compile` defaults the other way.

## Alternatives considered

**Always retain everything.** Rejected: it makes the library unusable for large layers in a
long-lived process, and there is no way for a caller to opt out.

**Never retain anything; recompute on demand.** Rejected: recomputation requires the plot *and* the
data, would re-run statistics, and cannot reconstruct which rows a stat dropped without repeating
the drop.

**One `LayerInspection` record with `Option` fields per policy.** Rejected: it makes every field
absent-able at every policy, so a caller cannot tell "the policy did not retain this" from "this
layer had none", and it puts the cost model in prose instead of in the type.

**Weak or soft references, or an LRU cache.** Rejected: the retained set would depend on GC timing,
which differs between the JVM and Scala.js and is not reproducible between two runs of the same
program.

**Releasing rows incrementally as each phase completes.** Rejected: the phases are not strictly
consuming — guide derivation, coordinate transforms, and the semantic sidecar all read resolved
rows — so an incremental release would have to be threaded through every phase instead of applied
once at the end.
