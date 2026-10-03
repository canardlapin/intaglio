# Interaction 06: linked plots, guides and external selection — phase plan

Mote: `bd-01M1Z9B2R9HSG6QJRA0JTR875X`. Builds on Interaction 04 (browser widget, shared input).

## Decisions

- **Linking is by typed key, never by label.** Selections link through entity keys of one
  `KeySpace` instance; category emphasis through link keys. Keys from another space never match,
  even with equal text.
- **Projection is silent.** A linked plot receives a projected selection as `Projected` input,
  which changes state and overlay but emits no event, so links cannot echo or loop.
- **Missing keys are dropped and reported**, never invented: a plot cannot hold a key its data
  does not contain (the reducer refuses it), so a projection keeps the keys the receiver knows and
  reports the rest to the caller.
- **Aggregates stay aggregate.** A histogram bin's selection is target-level; it is not projected
  as member keys (exact membership is Interaction 09).

## Slices

1. Shared: `LinkKeys.contains`, `SelectionProjection` (namespace-safe, drop-and-report), legend
   links on `InteractionBehavior`, `LinkedAxes` compatibility check with an explicit checked
   conversion. JVM+JS tests including namespace and cycle adversaries.
2. Browser: widget hover signals and linked emphasis, legend entry hover/click linked to the
   plot's own marks, `WidgetLink.connect` for a group of widgets with disposal.
3. Evidence: two differently ordered scatters plus a histogram in the fixture; Playwright traces
   for linked hover, selection, legend, programmatic updates, data replacement and no-echo; docs.
