# Recent-feature visual QA

The original Intaglio/ggplot2 gallery covers seventeen grammar, statistic, field, facet, and
position-adjustment cases. This court covers the user-visible features added after that gallery:

| Feature | Automated visual oracle | Independent review peer | What must agree |
| --- | --- | --- | --- |
| typed date scale and coordinate zoom | pinned Java2D PNG | ggplot2 | monthly ISO ticks, exact mid-month viewport, clipping, line shape |
| point, line, and text style aesthetics | pinned Java2D PNG | ggplot2 | shape/size/stroke/fill, line type/width, text angle/justification |
| grouped ECDF | pinned Java2D PNG | ggplot2 `stat_ecdf` | ties, group-wise mass, right-continuous steps, zero baseline |
| type-7 quantile summary | pinned Java2D PNG | ggplot2 `stat_summary` | first quartile, median, third quartile, group positions |
| aligned plot composition | pinned Java2D PNG | ggplot2 with patchwork | panel order/alignment and legible, collision-free margins |

ggplot2 is the closest independent peer for scales, grammar channels, and statistics. Patchwork is
the more relevant peer for multi-plot composition, so it is used only for that case. The comparison
does not expect pixel identity: themes, text metrics, symbol glyphs, and rasterization belong to the
respective engines. Each row in the generated manifest carries its narrower review contract.

## Run the paired gallery

The runner requires R packages `ggplot2` and `patchwork`, renders both engines at 640 x 480, writes
peer layer data as TSV, and produces a native-size HTML review page:

```sh
tools/render_feature_visual_qa.sh
```

Open `target/feature-visual-qa/index.html`, then review `manifest.tsv` and
`reference/reference-manifest.tsv` with the images. The layer TSV files are independent structural
receipts; they are preferable to judging a statistic or coordinate window by eye alone.

## Automated golden court

`FeatureVisualRegressionSuite` runs under ordinary `testAll`. It compiles every fixture through the
public grammar and render-plan boundary, uses the same pinned Liberation Sans bytes and explicit
rendering hints as the main Java2D golden, and compares both the full raster and a panel-focused
region. Failures write expected, actual, and difference images under
`target/golden-failures/java2d/features/<case>/`.

Golden updates are deliberately separate from tests. First render and review the paired gallery at
native size. If the Intaglio change is intended, run:

```sh
tools/update-feature-visual-goldens.sh
sbt -Djava.awt.headless=true \
  'java2dJVM / testOnly intaglio.java2d.FeatureVisualRegressionSuite'
```

The updater requires an internal `--accept` argument and prints every PNG SHA-256. Never update a
golden merely because a comparison failed.

## Why some recent additions are not image comparisons

Not every new contract is visual. Accessibility is checked through semantic IDs, authored alt text,
SVG roles, and non-color ambiguity diagnostics. Notebook support is a MIME/protocol contract. Batch
IR, provenance retention, compatibility, resource bounds, and performance receipts are structural
or computational. Raster orientation, patterns, PDF vector preservation, and backend parity already
have pixel, content-stream, conformance, and the original golden courts. Turning those into ggplot2
screenshots would test a less relevant contract and create false confidence.
