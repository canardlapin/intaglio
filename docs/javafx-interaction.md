# Minimal JavaFX interaction host

`intaglio-javafx` includes a small host over the shared interaction controller and picking plan.
It accepts a compiled, typed `InteractionPlan`, so entity identity and projected updates follow the
same rules as other hosts. JavaFX remains outside core and shared interaction.

Compile the view before attaching it. Use the same `RenderContext` used for plot layout:

```scala mdoc:silent
import intaglio.*
import intaglio.interaction.*
import intaglio.javafx.*

val context = RenderContext.unsafe(width = 800, height = 600, pixelsPerInch = 192, deviceScale = 2)
val keys = KeySpace("observations", KeyCodec.integer).toOption.get
val plot = Plot(Vector(1, 2, 3)).addLayer(Layer.point[Int](_.toDouble, _.toDouble)).toOption.get
val plan = InteractionCompiler.compile(
  plot, keys, DataRevision("data-1").toOption.get,
  SemanticId.unsafe("example"), PlanRevision("view-1").toOption.get,
  PlotCompilerOptions.lean.copy(renderContext = Some(context))
)(identity).toOption.get
val view = JavaFxInteractionView.compile(plan, context).toOption.get

// Call on the JavaFX application thread; put host.node in your application's layout.
def attachPlot(): Either[IntaglioError, JavaFxInteractionHost[Int]] =
  JavaFxInteractionHost.attach(view)

def selectFromApplication(host: JavaFxInteractionHost[Int]): Either[IntaglioError, Unit] =
  keys.entity(2).flatMap(key => host.setSelection(Selection(Set(key))))
```

## Hand-built scenes

A host that draws its own grobs and names its interactive marks with `GraphicsName` uses the same
host. `JavaFxInteractionView.named` resolves the scene once, draws it, and binds each name to a
typed target through `NamedInteraction`; the entity of every target is its name, in a key space of
names:

```scala mdoc:silent
val names = NamedInteraction.keySpace("marks").toOption.get
val ink = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
val handBuilt = Scene(
  Vector("left" -> 0.25, "right" -> 0.75).map { case (name, x) =>
    Grob.circleUnsafe(Point.npcUnsafe(x, 0.5), ExtentExpr.pointsUnsafe(8), ink,
      name = Some(GraphicsName.unsafe(name)))
  }
)
val namedView = JavaFxInteractionView
  .named(handBuilt, context, names, SemanticId.unsafe("canvas"), PlanRevision("view-1").toOption.get)
  .toOption.get

def attachNamed(): Either[IntaglioError, JavaFxInteractionHost[GraphicsName]] =
  JavaFxInteractionHost.attach(namedView)
```

```scala mdoc
namedView.navigation.targets.flatMap(_.target.entity).map(_.value)
```

Pointer hits and keyboard focus reach the name `NamedPicking` reports at the same point, and
`setSelection` takes the same name keys (`names.entity(name)`). Targets are ordered by draw order,
which is the Page Up/Down order. `JavaFxInteractionView.namedResolved` accepts a scene already
resolved with `DeviceScene.fromScene`. Without JavaFX, `NamedInteraction(plan, keys, planId,
revision)` gives the same typed `PickingPlan`, `InteractionDomain` and `NavigationPlan` for a
`NamedPickingPlan`; see [Picking a hand-built scene](picking.md).

## Input and overlays

The node is one keyboard focus stop. Arrow keys move to the nearest visible mark in that direction;
coincident marks follow a stable forward/backward order before navigation leaves the stack. Home/End jump to the first/last mark; Page Up/Down traverse every visible mark in stable order,
including marks that directional nearest alone cannot reach. Enter or Space selects and activates the focused mark; Escape clears selection and cancels an active
gesture. Pointer hover, clicks and press/drag/release feed revision-stamped shared actions. Modifier
clicks toggle in multiple-selection mode. Subscribe through `host.subscribe` for typed application
events. The focused mark's accessible text comes from its entity namespace/value or target identity;
applications can supply a `describe` function for a richer label.

`setSelection` and `setHover` are projected inputs: they redraw the overlay and emit no user event.
The base plot stays cached during input. Selection is blue, hover takes precedence in orange, and
keyboard focus has an independent opaque black/white outline. External plot styles remain on the
base canvas and cannot suppress the focus outline. The shared `InteractionAppearance` resolver
specifies the precedence.

### Overlay style

The overlay is drawn from a `JavaFxOverlayStyle` value. `JavaFxOverlayStyle.default` is the outline
described above; a host with its own design tokens builds another and installs it with
`host.setOverlayStyle`, which redraws only the overlay, so a theme switch costs no base redraw.
Widths and offsets are JavaFX logical pixels. `OverlayOutline.Geometry` makes outlines follow each
mark — circles stay circles, diamonds and closed paths are offset edge by edge with round corners —
instead of the bounding rectangle:

```scala mdoc:silent
val accent = Rgba.unsafe(0x7c, 0x3a, 0xed)
val themed = for
  selection <- OverlayStroke(accent, 2)
  hover <- OverlayStroke(Rgba.unsafe(0xd9, 0x77, 0x06), 2)
  focus <- OverlayStroke.cased(Rgba.unsafe(255, 230, 0), 2, Rgba.Black, 5)
  style <- JavaFxOverlayStyle(selection, hover, focus, outline = OverlayOutline.Geometry)
yield style

def applyTheme(host: JavaFxInteractionHost[Int]): Either[IntaglioError, Unit] =
  themed.flatMap(host.setOverlayStyle)
```

A focus ring on a contrasting casing stays visible on any background. `focusContrast` reports the
better WCAG 2 contrast ratio of the ring or its casing against a background, so a host can check the
3:1 that focus-appearance guidance asks for against both its light and dark surfaces:

```scala mdoc
themed.map(style => (style.focusContrast(Rgba.White), style.focusContrast(Rgba.unsafe(18, 18, 18))))
```

The geometry comes from `PickingPlan.outline(id, offsetDevicePx)` (and `NamedPickingPlan.outline`),
which any host can use: closed rings in device pixels at the given distance outside the mark's ink.
Open paths, text and images have no closed silhouette and keep a bounding rectangle.

Both canvases and pointer queries use the same aspect-preserving `PickViewport` mapping. Canvas
coordinates are JavaFX logical pixels; compiled device dimensions already include device scale.
JavaFX applies the window output scale. Do not multiply input coordinates by that scale again.
Resizing fits the cached scene and updates picking identically, including letterbox rejection;
it does not retrain scales or rerun statistics. Recompile/attach a new view when data or layout
must change. `view.deviceScene.frames` exposes resolved panel mappings for data-position overlays.

Call `host.dispose()` on the FX application thread when removing the view. Disposal is idempotent,
removes event/property listeners and subscriptions, detaches both canvases, and clears native image
and pattern caches. `lastError` and the optional `onError` callback expose rejected event inputs.

The acceptance suite starts real JavaFX with [Monocle](https://github.com/TestFX/Monocle) and the
software renderer on a test-only simulated 2x screen. A mounted Stage must report output scale 2
before child-event routing and focus checks run. It also drives synthetic pointer and keyboard events at device scales 1 and 2,
checks rendered overlays and projected no-echo, exercises 1,000 attach/dispose cycles with retained
nodes and weak host references, and records 10,000-mark timing to
`modules/javafx/jvm/target/feature-evidence/javafx-interaction-latency.txt` (relative to the module's
forked-test working directory). Timings include synchronous Canvas snapshots and are measurements,
not a latency guarantee. This is headless toolkit evidence; native desktop accessibility, display
scaling across monitors, and full Interaction 12 remain separate qualification work.

```sh
sbt 'javafxJVM/testOnly intaglio.javafx.JavaFxInteractionHostSuite'
```

The [recorded qualification receipt](../performance/timings/javafx-host-12a.txt) includes the named
machine, measured latency distribution, evidence boundaries and a manifest of tested source hashes.
