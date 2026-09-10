# 0004. Resolve lengths once, against one target

Status: Accepted
Date: 2026-09-03

## Context

Five renderers have five different length vocabularies. SVG has user units, percentages, `pt`, and
`calc()`. Canvas has device pixels and a device-pixel ratio. Java2D has user space and a transform
stack. JavaFX has its own scene scaling. PDF has points and a page CTM. If each backend resolves
units itself, they disagree — not visibly at first, but in the third decimal of a tick position and
then in a golden image.

Layout has a second, sharper version of the problem. Deciding how wide an axis strip must be
requires measuring text, and measuring text requires knowing the font family and size that will
actually be drawn. If the layout solver measures one family and the renderer emits another, regions
are the wrong size regardless of how carefully the arithmetic is done.

R's `grid` supplies the shape of the answer: units are values (`npc`, `native`, `mm`, `lines`),
viewports nest and carry their own coordinate scales, and resolution happens against a device. What
`grid` does not supply is a type distinction between a location and an extent, and it resolves
lazily at draw time rather than producing an inspectable numeric intermediate.

## Decision

There is one resolution point and one target description.

`DeviceContext` is the minimum a backend needs: `width`, `height` in device pixels and
`pixelsPerInch`. `RenderContext` is the whole target: it wraps a `DeviceContext` and adds
`textMetrics`, an immutable `FontRegistry`, `lineHeightPt`, and `deviceScale` with the derived
`logicalWidth`/`logicalHeight`/`logicalPixelsPerInch`. Layout takes a `RenderContext`; lowering
takes a `RenderContext`; `RenderPlan(scene, context)` carries the *same* context from compilation
into the backend so the two cannot diverge. `RenderContext.layoutPolicy(base)` stamps the target's
metrics and the registry-resolved font families onto an otherwise renderer-neutral `LayoutPolicy`,
which is what makes the measured family and the emitted family the same family by construction.

`DeviceScene.fromScene` is the single resolution point. After it, nothing symbolic remains: a
`DeviceScene` is `Double`s in device pixels, y-down, origin at the top-left, with stroke widths
already converted to device pixels and font sizes already in pixels. Every backend consumes that.

Units come in three families, and `DeviceContext.pxPerUnit` is where they part. `Cm`, `Mm`, `Inch`,
and `Point` are physical and convert through `pixelsPerInch` alone. `Npc` and `Native` are
frame-relative and mean nothing without a `DeviceFrame`: `Npc` is a fraction of the frame's span,
`Native` is a coordinate in the frame's `xScale`/`yScale`. `Line` is context-bound, resolving to
`RenderContext.lineHeightPt` at the target's density. A font size may be physical or `Line`; `Npc`
and `Native` font sizes are `GraphicsError.UnresolvableLength`, because a fraction of a panel is not
a description of type.

`Length` is one checked value with a unit. `LengthExpr` is the location algebra over it —
`Const`, `Add`, `Sub`, `Mul`, and `Offset`. `ExtentExpr` is the provably non-negative subset:
`fromExpr` admits a non-negative `Const`, an `Add` of two provably non-negative expressions, and a
`Mul` by a non-negative factor, and refuses `Sub` and `Offset` outright rather than trying to prove
anything about them. Sizes, radii, and corner radii take `ExtentExpr`, so a negative extent cannot
enter a grob through a checked constructor.

The two are not interchangeable, and `LengthExpr.+(that: ExtentExpr)` exists precisely because they
are not. Adding two `LengthExpr` values in `Native` units adds two *coordinates* in the frame's
scale; adding an `ExtentExpr` to a location produces `Offset`, which resolves the extent as a delta
(`value / scale.width * span`) rather than as a second position (`scale.rescale(value) * span`).
Both operations are wanted; they are different, and the type says which one you asked for.

`LengthResolver` evaluates against a `DeviceFrame` with three modes. `x`/`y` resolve locations;
`width`/`height` resolve directional extents; `extent` resolves an axis-neutral magnitude as
`min(width, height)`, so a point radius or a circle radius does not become an ellipse on an
anisotropic frame. Every resolved coordinate is checked for finiteness and for magnitude at most
`1e13`, so a degenerate scale surfaces as `GraphicsError.UnresolvableLength` rather than as `NaN` in
a path.

Handedness is explicit and flips exactly once. Scene coordinates are y-up, the `grid` convention:
`y = 0` is the bottom of the frame. A `Viewport` carries a `YDirection` (default `Up`; `Down` for
raster-style spaces), and `LengthResolver.y` performs the only flip:
`frame.y + frame.height - local` for `Up`, `frame.y + local` for `Down`. Rotation follows the same
handedness — positive angles are counterclockwise in a y-up frame, clockwise in a y-down one — and
`DeviceScene` negates the angle exactly where it negates the axis. A backend receives device space
and never guesses.

`PlotLayout` is the bridge between the two worlds. `PlotLayoutSolver.solve` works in points against
`LayoutPolicy.referenceDevice` and returns `PlotFrames`: panel, axis strips, legend, title, subtitle,
and facet grid cells, all as npc `PanelFrame` rectangles of the whole plot area. A `PanelLayout`
pairs one such frame with the panel's `xScale`/`yScale` and produces the `Viewport` that gives
`Native` units their meaning inside it. Region names are fixed in `PlotRegion` (`plot-panel`,
`axis-bottom`, `legend-region`, …) so a backend test can find them.

## Consequences

Two targets that describe the same physical page produce the same physical plot. Doubling
`width`, `height`, and `pixelsPerInch` leaves every physical length and every measured text extent
unchanged, and `RenderContext.hidpi` builds exactly that from a logical size and a device-pixel
ratio. `TargetRecompilationLaws` is the executable statement of this.

Backends get simple. There is no percentage arithmetic, no `calc()`, no unit parsing, no y-flip, and
no DPI conversion in `intaglio-svg`, `intaglio-canvas`, `intaglio-java2d`, `intaglio-javafx`, or
`intaglio-pdf`. Adding a backend is writing `DeviceElement` and `DevicePrimitive` to an output
format.

The cost is that a scene must be recompiled when the target changes. A `Scene` compiled at 640×480
with `TextMetrics.estimate` is not reusable at 1280×960 with platform metrics, because the axis
strips were sized for the first. This is the honest consequence of measuring text during layout, and
the API makes it visible rather than silently reusing stale frames: `PlotSpec.renderPlan(context)`
takes the context, and `RenderPlan` retains it.

`FontRegistry` must be deterministic for the lifetime of a context. Nothing enforces that — it is a
`trait` with one method — so a registry that consults a mutable font cache will produce a layout
that does not match its own output. The contract is stated in the trait's scaladoc and is the
caller's to keep.

`ExtentExpr`'s proof is conservative rather than complete. `ExtentExpr.fromExpr` refuses
`a - b` even when `a >= b` is obvious to the author, because deciding that requires resolution and
resolution has not happened yet. The workaround is to build the extent directly rather than as a
difference.

## Alternatives considered

**Let each backend resolve units.** Rejected: it is exactly the divergence the conformance contract
exists to prevent, and five implementations of `pxPerUnit` would drift.

**Emit percentages or `calc()` to SVG and let the browser resolve.** Rejected: only one backend can
do it, so the shared scene would carry a construct four backends must approximate, and the resolved
geometry would no longer be inspectable before rendering.

**A y-down scene, matching every device.** Rejected: statistical graphics are authored in a y-up
frame, and pushing the flip up to the author means every geom, coord, and annotation performs it.
One flip, at the one place that knows the frame, is the smaller surface.

**One `Length` type with a runtime non-negativity check on sizes.** Rejected: it moves a
representable-invalid-state problem into a runtime error at the moment of drawing, which is the
latest possible point.

**Deferring resolution to draw time, as `grid` does.** Rejected: it removes the inspectable numeric
intermediate that `SceneDeviceLaws`, `PointShapeLaws`, `RectCornerLaws`, `LineInterpolationLaws`,
and `RendererConformance` all observe, and it makes "did the backend do this or did the core?"
unanswerable.
