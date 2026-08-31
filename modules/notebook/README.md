# intaglio-notebook

`intaglio-notebook` is an optional JVM adapter for displaying Intaglio plots in Jupyter-style
notebooks. It depends on `intaglio-core` and `intaglio-svg`, but neither core nor the SVG backend
depends on a notebook kernel.

`NotebookRenderer.display` returns a dependency-free `NotebookMimeBundle`. Its `data` map follows
the standard Jupyter MIME-bundle shape: successful plots contain `image/svg+xml` and `text/plain`.
Pass `bundle.data` and `bundle.metadata` to the display API supplied by Almond or another Scala
kernel. `displayPlot` accepts an uncompiled `Plot` and compiles layout against the configured
notebook target; `displayPlan` preserves an existing `RenderContext` exactly.

```scala
val options = NotebookOptions.unsafe(
  width = 960,
  height = 540,
  pixelsPerInch = 144.0,
  title = Some("Response by condition")
)
val bundle = NotebookRenderer.displayPlot(trainedPlot, options)
```

Target width, height, DPI, and device scale are validated. `NotebookRenderer.render` keeps SVG
failures in `Either`; `display` instead guarantees a displayable error bundle. Error presentation is
configurable as plain text or accessible HTML with a text fallback. No global notebook state is
read or mutated.
