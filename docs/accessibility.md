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

## Per-grob titles, descriptions, classes, and data attributes

Document-level text describes the whole plot. A single mark can carry its own words and hooks
through `Grob.annotated(child, meta)`, where `GrobMeta` holds an optional `title`, an optional
`description`, an optional checked `CssClass`, and an insertion-ordered vector of checked
`DataKey` / value pairs:

```scala
val meta = GrobMeta(
  title = Some("Recall unit 7: \"the lighthouse\""),
  description = Some("Decode-filled anchor, mass 0.42"),
  cssClass = Some(CssClass.unsafe("mark decode-filled")),
  data = Vector(DataKey.unsafe("kind") -> "anchor")
)
val plate = Scene(Vector(Grob.annotated(unitMark, meta)))
```

`CssClass` accepts one or more whitespace-separated ASCII identifier tokens
(`-?[A-Za-z_][A-Za-z0-9_-]*`) and normalises them to single-space separation. `DataKey` accepts
`[a-z][a-z0-9-]*` and refuses `name`, because `data-name` is reserved for a grob's `GraphicsName`
on every backend. Both return `Either[GraphicsError, ...]`; `Grob.annotated` itself is total.

The wrapper has no viewport and no name of its own, so name collection and depth-first traversal
are unchanged; its only child is the annotated grob. Device lowering delegates to the child and
records the metadata as `DeviceElement.Annotated`. The SVG backend emits it as a wrapping group:

```svg
<g class="mark decode-filled" data-kind="anchor">
  <title>Recall unit 7: "the lighthouse"</title>
  <desc>Decode-filled anchor, mass 0.42</desc>
  <circle data-name="unit-7" ... />
</g>
```

Every attribute value and text node is escaped; `<`, `&`, `"`, and `]]>` cannot break the
document. An absent title, description, class, or data vector emits nothing, and an empty
`GrobMeta` still wraps the child in a bare `<g>`. A scene without any annotated grob renders
byte-identically to the pre-annotation renderer, which `SvgAnnotationSuite` pins per conformance
case. XML-illegal characters in any annotation text and a repeated data key are typed
`SvgRenderError`s at the render boundary. Canvas, Java2D, JavaFX, and PDF draw the child exactly
as if the wrapper were absent; the renderer conformance contract includes an annotated case so
every backend proves that.

## SVG output

Rendering a compiled plot through `intaglio-svg` emits a root `id`, `role="img"`, ARIA
`labelledby`/`describedby` references, and escaped `<title>` and `<desc>` elements. Raw scenes can opt
in through `SvgOptions(title = ..., description = ...)`. XML-illegal title or description text fails
the checked SVG boundary instead of producing malformed markup.
