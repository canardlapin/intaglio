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
