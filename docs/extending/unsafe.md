# Throwing and partial entry points

Intaglio validates through `Either`. Every smart constructor returns `Either[E, A]`, every
case-class constructor with an invariant is private, and every compiler phase returns a typed error.
See [ADR 0002](../adr/0002-one-typed-error-channel.md).

Two families of exception nevertheless exist in the public API, and this page enumerates both.

**The `unsafe` family.** Each module's error companion defines one extension,
`extension [A](either: Either[E, A]) def orThrow: A`, which throws
`IllegalArgumentException(error.message)` on `Left`. Every `X.unsafe(...)` in the library is defined
as `X.apply(...).orThrow` — one implementation, so the checked and throwing paths cannot drift.
Reach for them only when the argument is a literal you have already reasoned about
(`Rgba.unsafe(31, 119, 180)`, `GraphicsName.unsafe("plot-panel")`) or when you are in a test or a
REPL. Anything derived from data goes through the checked constructor.

**Totality boundaries.** A handful of public methods are partial for reasons other than validation:
an index that may be out of range, an invariant established elsewhere, or a callback supplied by the
caller. They are listed below with what they throw and what to use instead.

The inventory was taken with
`grep -rn "def unsafe\|orThrow\|throw new" modules/*/*/src/main/scala`; `private[intaglio]` members
are omitted because they are not reachable from a consumer package.

| Kind | Entry point | Throws | Total alternative |
| --- | --- | --- | --- |
| Escape hatch | `intaglio.GraphicsError.orThrow` on `Either[GraphicsError, A]` | `IllegalArgumentException(error.message)` | Keep the `Either`; `fold`, `getOrElse`, or `for` |
| Escape hatch | `intaglio.DisplayError.orThrow` on `Either[DisplayError, A]` | `IllegalArgumentException` | Keep the `Either` |
| Escape hatch | `intaglio.svg.SvgRenderError.orThrow` | `IllegalArgumentException` | Keep the `Either` |
| Escape hatch | `intaglio.java2d.Java2DRenderError.orThrow` | `IllegalArgumentException` | Keep the `Either` |
| Escape hatch | `intaglio.javafx.JavaFxRenderError.orThrow` | `IllegalArgumentException` | Keep the `Either` |
| Escape hatch | `intaglio.pdf.PdfRenderError.orThrow` | `IllegalArgumentException` | Keep the `Either` |
| Escape hatch | `intaglio.canvas.CanvasRenderError.orThrow` (Scala.js) | `IllegalArgumentException` | Keep the `Either` |
| Escape hatch | `intaglio.notebook.NotebookRenderError.orThrow` | `IllegalArgumentException` | Keep the `Either` |
| Totality | `RowMapping#apply(row)` — and therefore any `Row => A` you invoke directly | An internal `RuntimeException` subclass carrying the `MappingFailure` message; the class is `private[intaglio]`, so you cannot name it in a `catch` | `RowMapping#evaluate(row): Either[MappingFailure, A]`. The compiler never calls `apply`. |
| Totality | `AesValue#map(row)`, `ScaleBinding#map(row)`, `Position2#map(row)` | Whatever the underlying mapping throws | `PlotCompiler.resolve`/`compile`, which evaluate through the checked boundary and report `DroppedRow` or `GraphicsError.MappingEvaluationFailed` |
| Totality | `Breaks#apply(range)` on every built-in generator | `IllegalArgumentException` — built-ins are `runChecked(range).orThrow` | `Breaks#generate(range): Either[GraphicsError, Vector[Double]]`, which also validates a custom generator's output |
| Totality | `Labeler#apply(values)` | Whatever your labeler throws; there is no checked twin, and an exception escapes `ContinuousScale#labels` | None. Keep labelers total. |
| Totality | `ContinuousScale#breaks`, `ContinuousScale#labels` | `IllegalArgumentException` from the break generator; `labels` additionally propagates a throwing `Labeler` | `ContinuousScale#breaksResult` |
| Totality | `DateScale#breaks`, `DateScale#labels`, `DateTimeScale#breaks`, `DateTimeScale#labels` | `IllegalArgumentException` | `breaksResult`, `labelsResult` |
| Totality | `DatumIdSeries#valueAt(index)` | `IndexOutOfBoundsException` outside `[0, count)` | `DatumIdSeries#values: Vector[SemanticId]` |
| Totality | `BatchColumn#valueAt(index)` for a `Values` column | `IndexOutOfBoundsException` from the underlying `Vector` | Guard with `valueCount`, or rely on the length invariant established by `Grob.pointBatch` and re-checked by `DeviceScene` |
| Totality | `RegisteredScale#trained` (reached through `AesSpec#scaledEntries`) | `IllegalStateException` if the entry still holds an untrained `ScaleSpec` | `TrainedPlot#trainedScales` / `TrainedLayer#trainedScales`, which exist only after compilation |
| Totality | `DiscretePalette#apply(index, count)` for a finite palette declaring `PaletteOverflowPolicy.Reject` | `IllegalArgumentException` for an index at or beyond capacity | `DiscretePalette#validateDomain(scale, levelCount)` first — `DiscreteScale` already calls it — or declare `PaletteOverflowPolicy.Cycle` |
| Totality | `RasterImage.unsafeFromOwnedPackedArray(dimensions, pixels)` | `IllegalArgumentException` when `pixels.length` does not match `dimensions.pixelCount`. It *also* takes exclusive ownership of the array without copying: reading or mutating `pixels` afterwards corrupts the image | `RasterImage.fromPacked` / `RasterImage.fromRgba`, which copy |
| Totality | Theme and palette constructors: `Theme`, `ThemePalettes` | `IllegalArgumentException` from `require` — a non-positive or non-finite point size, an empty discrete palette | None. These are the two `require`-carrying types a plot author touches routinely, so check the values you pass. `Theme.minimal` and the other named themes are already valid |
| Totality | Layout and guide constructors: `LayoutPolicy`, `TextStyle`, `PanelGridRequest`, `LegendRequest`, `GuidePlacement.Legend`, `GuidePlacement.Colorbar` | `IllegalArgumentException` from `require` | None. `LayoutPolicy` is the one a backend author constructs directly — `LayoutPolicy(metrics = Java2DTextMetrics())` — and it carries about twenty `require`s over its point-valued fields; every one demands a finite, non-negative value |
| Totality | Contour and facet output constructors: `ContourConfig`, `ContourLine`, `ContourRegion`, `ContourBand`, `FacetCell`, `PlotAccessibility` | `IllegalArgumentException` from `require` | None. These are compiler output types; you normally receive them from `Contours`, `ContourBands`, or the facet compiler rather than build them |
| Totality | Statistic fixture and output constructors: `GeomContext`, `StatContext`, `StatRow.Counted`, `StatRow.Binned`, `StatRow.Summarized`, `StatRow.Density`, `StatRow.QuantileSummary`, `StatRow.Ecdf` | `IllegalArgumentException` from `require` | None. A `StatRow` subtype's invariants are the statistic's contract with the compiler; validate before constructing |
| Names and identity | `GraphicsName.unsafe(value, kind)` | `IllegalArgumentException` | `GraphicsName.apply` |
| Names and identity | `Aesthetic.unsafe[A](label)` | `IllegalArgumentException` | `Aesthetic.apply[A]` |
| Names and identity | `SemanticId.unsafe(value)` | `IllegalArgumentException` | `SemanticId.apply` |
| Names and identity | `CssClass.unsafe(value)`, `DataKey.unsafe(value)` | `IllegalArgumentException` | `CssClass.apply`, `DataKey.apply` |
| Scene geometry | `Length.unsafe`, `Length.npcUnsafe`, `Length.nativeUnsafe`, `Length.pointsUnsafe`, `Length.linesUnsafe` | `IllegalArgumentException` | `Length.apply`, `.npc`, `.native`, `.points`, `.lines` |
| Scene geometry | `LengthExpr.npcUnsafe`, `LengthExpr.nativeUnsafe`, `LengthExpr.linesUnsafe` | `IllegalArgumentException` | `LengthExpr.npc`, `.native`, `.lines` |
| Scene geometry | `ExtentExpr.unsafe(expr)`, `ExtentExpr.unsafe(length)`, `ExtentExpr.npcUnsafe`, `.nativeUnsafe`, `.pointsUnsafe`, `.linesUnsafe` | `IllegalArgumentException` (`InvalidExtent` when non-negativity is not provable) | `ExtentExpr.fromExpr`, `ExtentExpr.apply`, `.npc`, `.native`, `.points`, `.lines` |
| Scene geometry | `Point.npcUnsafe`, `Point.nativeUnsafe`, `Size.npcUnsafe` | `IllegalArgumentException` | `Point.npc`, `Point.native`, `Size.npc` |
| Scene geometry | `Rgba.unsafe(red, green, blue, alpha)` | `IllegalArgumentException` | `Rgba.apply` |
| Scene geometry | `StrokeWidth.unsafe`, `StrokeWidth.devicePixelsUnsafe`, `StrokeWidth.pointsUnsafe` | `IllegalArgumentException` | `StrokeWidth.checked`, `.devicePixels`, `.points` |
| Scene geometry | `GraphicParams.unsafe(...)` | `IllegalArgumentException` | `GraphicParams.checked` |
| Scene geometry | `Viewport.unsafe(...)` | `IllegalArgumentException` | `Viewport.checked` |
| Scene geometry | `Grob.pointBatchUnsafe`, `Grob.linesUnsafe`, `Grob.polygonUnsafe`, `Grob.compoundPolygonUnsafe`, `Grob.rectUnsafe`, `Grob.circleUnsafe`, `Grob.textUnsafe`, `Grob.imageUnsafe` | `IllegalArgumentException` | `Grob.pointBatch`, `.lines`, `.polygon`, `.compoundPolygon`, `.rect`, `.circle`, `.text`, `.image`. `Grob.points`, `Grob.segments`, `Grob.group`, and `Grob.annotated` have no throwing twin |
| Scales | `Interval.unsafe(lower, upper)` | `IllegalArgumentException` | `Interval.apply`; `Interval.train` / `trainOption` for a fitted range |
| Scales | `TransformDomain.unsafe(name, lower, upper)` and `TransformDomain.unsafe(name, lowerBound, upperBound)` | `IllegalArgumentException` | `TransformDomain.closed`, `.open`, `.openClosed`, `.closedOpen`, `.apply` |
| Scales | `Breaks.countUnsafe(n)`, `Breaks.prettyUnsafe(targetCount)` | `IllegalArgumentException` | `Breaks.count`, `Breaks.pretty` |
| Scales | `BandPadding.unsafe(value)`, `Band.unsafe(center, width)` | `IllegalArgumentException` | `BandPadding.apply`, `Band.apply` |
| Scales | `DiscretePalette.valuesUnsafe(values, overflow)` | `IllegalArgumentException` (empty palette) | `DiscretePalette.values` |
| Compiler options | `RangeExpansion.unsafe(multiplicative, additive, zeroWidth)` | `IllegalArgumentException` | `RangeExpansion.apply`; `RangeExpansion.default` and `.none` are total |
| Geometry contracts | `GeomAestheticContract.unsafe(required, optional, groupConstant)` | `IllegalArgumentException` for a malformed contract | `GeomAestheticContract.checked` |
| Coordinates | `CoordinateRatio.unsafe(value)` | `IllegalArgumentException` | `CoordinateRatio.apply` |
| Coordinates | `CoordinateWindow.numericUnsafe`, `.dateUnsafe`, `.dateTimeUnsafe` | `IllegalArgumentException` | `CoordinateWindow.numeric`, `.date`, `.dateTime` |
| Coordinates | `Coord.fixedUnsafe`, `Coord.zoomUnsafe`, `Coord.zoomWindowsUnsafe` | `IllegalArgumentException` | `Coord.fixed`, `Coord.zoom`, `Coord.zoomWindows` |
| Statistics | `CountOrder.declaredUnsafe(levels)` | `IllegalArgumentException` (duplicate level) | `CountOrder.declared` |
| Statistics | `BinCount.unsafe(value)`, `BinWidth.unsafe(value)` | `IllegalArgumentException` | `BinCount.apply`, `BinWidth.apply` |
| Statistics | `HistogramBins.countUnsafe`, `.widthUnsafe`, `.breaksUnsafe` | `IllegalArgumentException` | `HistogramBins.count`, `.width`, `.breaks` |
| Statistics | `DensityBandwidth.unsafe`, `DensityPoints.unsafe`, `DensityConfig.fixedUnsafe` | `IllegalArgumentException` | `DensityBandwidth.apply`, `DensityPoints.apply`, `DensityConfig.fixed` |
| Positions | `DodgeWidth.unsafe`, `JitterAmount.unsafe`, `JitterConfig.unsafe`, `DodgeConfig.fixedUnsafe`, `Position.dodgeUnsafe`, `Position.jitterUnsafe` | `IllegalArgumentException` | `DodgeWidth.apply`, `JitterAmount.apply`, `JitterConfig.apply`, `DodgeConfig.fixed`, `Position.dodge`, `Position.jitter` |
| Layout | `PanelMargins.npcUnsafe`, `PanelFrame.npcUnsafe` | `IllegalArgumentException` | `PanelMargins.npc`, `PanelFrame.npc` |
| Layout | `LegendEntry.unsafe(label, gp, shape)`, `LegendEntry.colorUnsafe(label, value, shape)` | `IllegalArgumentException` | `LegendEntry.apply`, `LegendEntry.color` |
| Layout | `AxisTick.unsafe(value, label)` | `IllegalArgumentException` | `AxisTick.apply` |
| Composition | `CompositionOptions.unsafe(...)`, `PlotInset.npcUnsafe(...)` | `IllegalArgumentException` | `CompositionOptions.apply`, `PlotInset.npc` |
| Targets | `DeviceContext.unsafe(width, height, pixelsPerInch)` | `IllegalArgumentException` | `DeviceContext.apply` |
| Targets | `RenderContext.unsafe(...)`, `RenderContext.hidpiUnsafe(...)` | `IllegalArgumentException` | `RenderContext.apply`, `RenderContext.hidpi` |
| Rasters | `Rgba32.unsafe(red, green, blue, alpha)`, `RasterDimensions.unsafe(width, height)` | `IllegalArgumentException` | `Rgba32.apply`, `RasterDimensions.apply` |
| Rasters | `RasterImage.unsafePacked`, `RasterImage.unsafeRgba`, `RasterImage#pixelUnsafe(x, y)` | `IllegalArgumentException` | `RasterImage.fromPacked`, `RasterImage.fromRgba`, `RasterImage#pixel` |
| Fields and contours | `RegularGridAxis.cellCenteredUnsafe`, `.vertexCenteredUnsafe`, `ScalarField2D.unsafe` | `IllegalArgumentException` | `RegularGridAxis.cellCentered`, `.vertexCentered`, `ScalarField2D.apply` |
| Fields and contours | `Bin2DConfig.unsafe(...)`, `Kde2DConfig.fixedUnsafe(...)` | `IllegalArgumentException` | `Bin2DConfig.apply`, `Kde2DConfig.fixed` |
| Fields and contours | `ContourLevel.unsafe(value)`, `ContourLevels.atUnsafe(values)`, `ContourBreaks.atUnsafe(values)` | `IllegalArgumentException` | `ContourLevel.apply`, `ContourLevels.at`, `ContourBreaks.at` |
| Display | `DisplayWindow.unsafe`, `ThresholdBand.unsafe`, `DisplayOpacity.unsafe` | `IllegalArgumentException` | `DisplayWindow.make`, `ThresholdBand.make`, `DisplayOpacity.make` |
| Temporal | `CalendarDate.unsafe(year, month, day)`, `.parseUnsafe(value)`, `.fromEpochDayUnsafe(value)` | `IllegalArgumentException` | `CalendarDate.apply`, `.parse`, `.fromEpochDay` |
| Temporal | `CalendarDate#addDaysUnsafe`, `#addWeeksUnsafe`, `#addMonthsUnsafe`, `#addYearsUnsafe` | `IllegalArgumentException` outside the portable domain | `#addDays`, `#addWeeks`, `#addMonths`, `#addYears` |
| Temporal | `UtcDateTime.unsafe(epochMillis)`, `.ofUnsafe(...)`, `.parseUnsafe(value)`, `UtcDateTime#addMillisUnsafe` | `IllegalArgumentException` | `UtcDateTime.apply`, `.of`, `.parse`, `#addMillis` |
| Temporal | `DateDomain.unsafe(lower, upper)`, `DateTimeDomain.unsafe(lower, upper)` | `IllegalArgumentException` | `DateDomain.apply`, `DateTimeDomain.apply` |
| Temporal | `TemporalBreaks.automaticUnsafe(targetCount)`, `.everyUnsafe(step, unit)` | `IllegalArgumentException` | `TemporalBreaks.automatic`, `.every` |
| Backend options | `SvgOptions.unsafe(...)` | `IllegalArgumentException` | `SvgOptions.apply` |
| Backend options | `Java2DOptions.unsafe(...)` | `IllegalArgumentException` | `Java2DOptions.apply` |
| Backend options | `JavaFxOptions.unsafe(...)` | `IllegalArgumentException` | `JavaFxOptions.apply` |
| Backend options | `NotebookOptions.unsafe(...)` | `IllegalArgumentException` | `NotebookOptions.apply` |
| Backend options | `CanvasOptions.unsafe(...)`, `CanvasOptions.hidpiUnsafe(...)` (Scala.js) | `IllegalArgumentException` | `CanvasOptions.apply`, `CanvasOptions.hidpi` |

## What is *not* on this list

These look like they could throw and do not.

`PlotCompiler.resolve` and `PlotCompiler.compile` do not leak mapping exceptions. A throwing row
accessor becomes a `DroppedRow` diagnostic or `GraphicsError.MappingEvaluationFailed`, depending on
whether the value was needed for scale training, statistics, or facet partitioning.

`PlotLayoutSolver.solve` wraps the supplied `TextMetrics` and converts a throw into
`GraphicsError.LayoutMeasurementFailed(exceptionType, detail)`. A platform metrics provider that
fails is a typed error, not a crash.

`Transform#transform` and `Transform#inverse` wrap your `forward` and `backward` functions and return
`GraphicsError.TransformEvaluationFailed`. `Breaks#generate` wraps a custom generator's `apply` and
validates its output for finiteness, strict increase, and size.

`LawSuite#failures` catches non-fatal exceptions thrown by the extension under test and reports them
as `LawFailure` values carrying the exception type and message, so a throwing extension fails a law
rather than the test runner.

`Grob.points`, `Grob.segments`, `Grob.group`, and `Grob.annotated` are total: they either cannot fail
or return `Either` with no throwing twin. `Grob.annotated` accepts an empty `GrobMeta` deliberately —
the SVG backend then emits a bare wrapping `<g>`.
