# Accessible plots and semantic identity

Intaglio carries accessibility information beside renderer-neutral graphics instead of hiding it in
one backend. A compiled plot owns a typed plot ID, one stable ID per logical layer, a compact datum
ID series, authored descriptive text, a deterministic plot summary, and palette diagnostics. The
same `SceneSemantics` value travels from `Scene` to `DeviceScene`, including when lean compilation
stores point marks as one columnar batch.

## Add domain context

Semantic IDs use a portable SVG/HTML subset: an ASCII letter or underscore followed by letters,
digits, underscores, hyphens, or periods.

```scala
val plot = Plot(observations)
  .withSemanticId(SemanticId.unsafe("memory-activation"))
  .withTitle("Memory-related activation")
  .withDescription("Regional estimates from the preregistered contrast.")
  .withAltText("Activation is strongest in left hippocampus; uncertainty overlaps zero elsewhere.")
  .addLayer(
    Layer
      .point[Observation](_.estimate, _.regionIndex)
      .withSemanticId(SemanticId.unsafe("regional-estimates"))
  )
```

`withAltText` is for the domain claim a generic plotting library cannot infer. It takes precedence
over the generic description in accessible renderers. A trained plot can also be revised without
recompiling geometry:

```scala
val revised = trained.withAltText("A domain expert's final interpretation.")
```

## Inspect before rendering

`trained.textSummary` reports every logical layer, its geom/stat and row counts, and every trained
scale with its domain. `trained.accessibilityDiagnostics` reports exact palette collisions. For
example, a two-level discrete color scale that maps both levels to the same RGBA value emits
`AccessibilityDiagnostic.AmbiguousPalette`; it is never silently treated as distinguishable.

Datum IDs are represented by `DatumIdSeries`, not a `String` per mark. `valueAt(i)` deterministically
returns `<layer-id>-datum-<i>`, preserving inspectability without defeating batch memory budgets.

## SVG output

Rendering a compiled plot through `intaglio-svg` emits a root `id`, `role="img"`, ARIA
`labelledby`/`describedby` references, and escaped `<title>` and `<desc>` elements. Raw scenes can opt
in through `SvgOptions(title = ..., description = ...)`. XML-illegal title or description text fails
the checked SVG boundary instead of producing malformed markup.
