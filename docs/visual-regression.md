# Golden, perceptual, and fuzz regression

Intaglio keeps visual and malformed-input assurance inside this repository. The ordinary
`testAll` gate runs every court; no consumer repository, browser profile, or installed desktop font
is required.

## Controlled Java2D golden

`GoldenRegressionSuite` renders one representative scene at 360 x 240 pixels and 96 ppi. The
fixture pins all variables that otherwise drift between machines:

- Liberation Sans bytes come from the test-scoped Apache PDFBox 3.0.8 artifact;
- `Java2DFontResolver.fixed` supplies that immutable font directly, without consulting installed
  family names;
- the background is opaque white;
- geometry and text antialiasing are disabled explicitly; and
- the scene, dimensions, density, colors, stroke widths, pattern recipe, and font size are fixed.

The committed PNG is the reviewable oracle. The full image permits at most a 2% changed-pixel
fraction and a mean absolute ARGB-channel error of 0.5. The non-text region, beginning at row 48,
uses tighter limits of 0.1% and 0.03. The split allows small JDK glyph-rasterizer differences while
making geometry, clipping, pattern, color, and mark regressions fail. A mutation test proves that a
20 x 20 non-text change exceeds the threshold.

On failure, inspect `target/golden-failures/java2d/expected.png`, `actual.png`, and
`difference.png`. Do not update the oracle solely to make the test green. First decide whether the
rendering change is intended, review the difference image at native size, and record any threshold
change in the same commit. After review, update deliberately:

```sh
tools/update-java2d-goldens.sh
sbt -Djava.awt.headless=true 'java2dJVM / testOnly intaglio.java2d.GoldenRegressionSuite'
```

The updater requires `--accept` internally and prints the new PNG SHA-256. Tests never rewrite the
oracle.

## Recent plotting features

`FeatureVisualRegressionSuite` adds five pinned 640 x 480 courts for temporal zoom, typed style
aesthetics, grouped ECDF, type-7 quantile summaries, and aligned plot composition. The same fixtures
feed a paired human-review gallery against ggplot2, using patchwork where composition—not one plot—is
the relevant peer. See the [recent-feature visual QA guide](visual-qa/recent-features.md) for the
feature inventory, review contracts, peer layer receipts, and deliberate update workflow.

## Deterministic fuzz court

`FuzzRegressionSuite` executes 256 fixed SplitMix64 seeds on both the JVM and Scala.js. A failure
names its category and replay seed. The bounded generators cover:

- checked scene values, batch cardinality, patterns, and device lowering;
- throwing and checked row mappings through plot compilation;
- built-in and user-defined transform callbacks;
- count, width, pretty, invalid-output, and throwing break generators; and
- varied layout requests plus user-defined text metrics.

Every built-in break run is checked against `Breaks.MaximumOutputSize`; generation loops already
have deterministic iteration caps. Non-fatal transform, custom-break, mapping, and metric callback
failures become typed errors. Unsafe convenience methods remain explicit throwing boundaries and
are not fuzz targets.
