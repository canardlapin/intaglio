# Intaglio documentation

Every fenced Scala block marked `mdoc` in this directory is compiled against the
real modules by `tools/check-docs.sh`, and every gallery plate is rendered from
the code shown beside it. A guide here cannot claim an API that does not exist.

## Learn it

A task-shaped path. Read them in order the first time; each one stands alone
afterwards.

| | |
| --- | --- |
| [1. Data in, SVG out](tutorial/01-first-plot.md) | A case class, a plot program, a `Scene`, a file |
| [2. Scales and guides](tutorial/02-scales-and-guides.md) | Continuous and discrete scales, transforms, limits, legends, colorbars |
| [3. Statistics](tutorial/03-statistics.md) | Histograms, densities, summaries, quantiles, ECDFs |
| [4. Facets and coordinates](tutorial/04-facets-and-coords.md) | `facetWrap`, `facetGrid`, fixed aspect, zoom, flipped axes |
| [5. Themes and styling](tutorial/05-themes-and-styling.md) | Themes, palettes, style aesthetics, pattern fills |
| [6. Composition](tutorial/06-composition.md) | Several plots in one aligned figure, insets, collected guides |
| [7. Saving output](tutorial/07-saving-output.md) | SVG, PNG, PDF, Canvas, notebook — and the target each needs |

The [gallery](gallery.md) shows ten plates with the program that produced each
one directly above it.

## Use it

- [Backends](backends.md) — what each of the six can and cannot express.
- [Performance and limits](limits.md) — what the deterministic gates measure,
  and where the scaling limits are.
- [Notebooks and publication](notebooks.md) — Jupyter MIME bundles, print
  output, device scale, fonts.
- [Accessibility](accessibility.md) — semantic identity, per-grob titles and
  descriptions, `class` and `data-*` hooks, ARIA in SVG output.
- [Composition](composition.md) — the renderer-neutral mechanism behind
  tutorial 6.
- [Date/time scales and coordinate zoom](date-time-and-zoom.md).
- [Common statistical layers](common-statistics.md).
- [Style aesthetics](style-aesthetics.md) — which channels each geom accepts,
  and the point-shape table.

## Extend it

Guides for authoring an Intaglio extension, each ending in the law kit from
`intaglio-laws` that proves your implementation behaves.

- [Scales, transforms, and palettes](extending/scales.md)
- [Statistics](extending/stats.md)
- [Geoms](extending/geoms.md)
- [Coordinate systems](extending/coords.md)
- [Plot recipes](extending/recipes.md)
- [Backends](extending/backends.md)
- [Unsafe entry points and totality boundaries](extending/unsafe.md) — every
  place the API can throw, and the total alternative beside it.

## Why it is shaped this way

[Architecture decision records](adr/README.md): seven accepted records covering
extension identity, the typed error channel, the scale lifecycle, target
layout, the compatibility courts, the batch IR, and provenance.

## Contracts and process

- [Compatibility policy](compatibility.md) — the three courts, the exact-baseline
  gate, and what moving the baseline requires.
- [Numerical standards](numerical-standards.md) — finite input, histogram
  closure, KDE normalization, contour topology.
- [Visual regression](visual-regression.md) — pinned-font goldens, perceptual
  thresholds, deterministic fuzz replay.
- [Visual QA galleries](visual-qa/recent-features.md) — the paired
  Intaglio/ggplot2 human-review courts, and the
  [position-adjustment gallery](visual-qa/position-adjustments.md).
- [Releasing](releasing.md) — the rehearsal, the tag, the credentials.

Outside this directory: [CONTRIBUTING.md](../CONTRIBUTING.md),
[CHANGELOG.md](../CHANGELOG.md), [MIGRATION.md](../MIGRATION.md), and
[SECURITY.md](../SECURITY.md).
