# JavaFX interaction host

`intaglio-javafx` hosts a compiled, typed `InteractionPlan` in a JavaFX node. Pointer and key input
is normalized into the shared `HostInput` contract and dispatched to the shared
`InteractionController`, so hover, focus, selection, activation, navigation and linked state mean
what they mean in the [browser widget](browser-widget.md): the same script of input produces the
same events and selected keys in both hosts (see [Evidence](#evidence)). The host reads the same
`InteractionBehavior` value as the browser, and keeps the browser widget's history and snapshot
boundaries. JavaFX remains outside core and shared interaction, and
static rendering (`JavaFxRenderer`) needs none of this.

A runnable desktop example — a navigable scatter linked to a histogram whose bins select their
members — is `sbt javafxExample/run`
([source](../modules/javafx-example/src/main/scala/intaglio/javafx/example/InteractiveScatter.scala)).

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

A named image (a heatmap, design matrix or confusion matrix) is one target, selected by its name;
the host also reports which of its cells the reader points at and chose.
`JavaFxInteractionView.names` is the view's `NamedPickingPlan`, and the host follows it:

```scala mdoc:silent
def followCells(
    host: JavaFxInteractionHost[GraphicsName],
    readout: String => Unit,
    chooseColumn: Option[Int] => Unit
): Either[IntaglioError, () => Unit] =
  host.subscribeCells {
    case JavaFxCellEvent.Hovered(cell) =>
      readout(cell.fold("")(c => s"${c.name.value}: row ${c.cell.row}, column ${c.cell.column}"))
    case JavaFxCellEvent.Selected(cell) => chooseColumn(cell.map(_.cell.column))
  }
```

`host.hoveredCell` is the hovered name's `cellAt` at the pointer, through the mapping that draws the
scene; it is `None` when the hovered target is not an image or only the pointer tolerance reaches
it, and keyboard focus has no cell. `host.selectedCell` is the cell of the most recent click while
its name stays selected: a click that selects an image records the cell under the pointer, and the
cell is dropped when the name leaves the selection (Escape, `setSelection`, undo) and when `update`
shows a new view. Selection, snapshots and history stay by name; the cell is host state. Keyboard
navigation between cells is not provided: arrow keys move between names, as for any hand-built
scene. Rows count from the image's top row; see [Report the cell of an
image](picking.md#report-the-cell-of-an-image) for the convention and the tie rule at boundaries. A
plot's view reports no cells (`names` is `None`); its rasters carry `TargetInfo.rasterCell`.

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
removes event/property listeners and subscriptions, stops a pending tooltip, detaches both
canvases, and clears native image and pattern caches. The host belongs to the FX application
thread: every method that returns an `Either` returns `JavaFxHostError.WrongThread` elsewhere (and
`Disposed` after disposal); the unsubscribe functions from `subscribeParts`, `subscribeHover` and
`subscribeState`, `JavaFxLink.dispose` and `JavaFxInspector`'s `showFilter`, `clearFilter` and
`dispose` throw `IllegalStateException` elsewhere. The plain getters (`isNavigable`,
`currentWindow`, `canUndo`, `canRedo`, `drawnEntities`, `selectableEntities`, `isDisposed`,
`profile`, `overlayStyle`, `lastError`) and the shared `InteractionSubscription.cancel` are not
checked: call them on the FX thread. `lastError` and the optional `onError` callback expose
rejected event inputs and, as `InteractionError.CallbackFailed`, application code that threw: an
event, state, hover or part listener, or the behaviour's tooltip, link or description. A listener
that throws never stops the others or undoes the committed change. `update` checks the new view as `mount` does (links need `onLink`, deferred
members a resolver, legend links a drawn legend) and refuses it without changing anything.

## Behaviour: tooltips, links, emphasis and members

`JavaFxInteractionHost.mount(view, behavior)` takes the browser widget's `InteractionBehavior`:

```scala mdoc:silent
val described = InteractionBehavior
  .describingEntities[Int](n => s"observation $n")
  .withInverseEmphasis(true)

def mountDescribed(): Either[IntaglioError, JavaFxInteractionHost[Int]] =
  JavaFxInteractionHost.mount(view, described)
```

- **Tooltips** are `TargetContent` drawn as JavaFX `Text` nodes, never parsed as markup, placed by
  the shared `TooltipLayout` (`Pointer`, `Anchored` or `Fixed`), flipped and clamped inside the
  node. Pointer hover waits for `tooltipDelayMs`; keyboard focus shows the tooltip at once.
  `host.tooltip` reads the content shown.
- **Announcements**: the focused mark's description (with partial-coverage counts for aggregates)
  is the node's accessible text and `host.announcement`, as the browser's live region says it.
  `host.companionRows` is the text companion: every mark, then every plot part.
- **Links** are followed only for pointer or keyboard activation and only through an
  `onLink: TargetLink => Unit` handler (`HostServices.showDocument`, for example). A behaviour whose
  targets carry links is refused at mount without one; a desktop node has no browser location.
- **Inverse emphasis** dims the base canvas and redraws hovered, selected, covered and linked marks
  in their own paint, clipped to their outlines.
- **Plot parts**: `host.subscribeParts` reports legend entries, strips, axes, titles, colorbars and
  annotations under the pointer or activated, as typed `PlotPart` values. `withLegendLink` legends
  emphasize and select their category's marks; a legend link that names no drawn legend, or whose
  entries match no mark, is refused at mount.
- **Aggregate members** (`withAggregateSelection(_ => AggregateSelection.Members)`): choosing or
  sweeping a bin selects its exact members; deferred members are asked of the `resolver` given to
  `mount` (refused at mount without one), and replies are dispatched on a later FX pulse.

## Navigation

A single-panel plot with a numeric, continuous or temporal axis pans and zooms its data window
through the shared `DataWindowNavigator`; each window is a re-windowed plan from
`InteractionCompiler.rezoom`, so statistics are not recomputed and selection and focus stand.
`+`/`=` and `-` zoom about the panel centre, `0` resets; the wheel zooms about the pointer while
the plot has focus (or with Ctrl); a trackpad pinch zooms about its centre. `setGestureMode` chooses
what a primary drag does — `Rectangle` and `Lasso` select (Shift adds, Alt subtracts), `Pan` drags
the window, `ZoomRectangle` zooms to the dragged box — and Escape first abandons a drag in
progress. `navigate(window)`, `zoomBy(factor)`, `resetWindow()` and `currentWindow` are the
programmatic equivalents; the host has no toolbar, so applications put these behind their own
controls. Named scenes, compositions and faceted plots are refused with
`InteractionError.UnsupportedCapability`.

`host.update(view)` shows a new view of the same plot: a new revision is reconciled by entity key
(a `Reconciled` event reports dropped keys), the same revision keeps the state, and the new view is
the unwindowed base. `JavaFxInteractionView.compileComposition` hosts a composed figure with scoped
child parts.

## Saved selections, history, snapshots and the inspector

The host exposes [Interaction 10](selection-history.md) through the shared state, with the browser
widget's boundaries:

```scala mdoc:silent
def keepAndUndo(host: JavaFxInteractionHost[Int]): Either[IntaglioError, Boolean] =
  for
    _ <- host.saveSelection(SelectionName.unsafe("first pass"))
    model <- host.inspector(sample = 10)
    _ = println(s"${model.observations} observations selected")
    undone <- host.undo()
  yield undone
```

- **Named selections**: `saveSelection`, `recallSelection(name, operation)`,
  `combineSelections(left, right, SetCombination.Union | Intersection | Difference, into)` and
  `deleteSelection` dispatch the shared actions; incompatible operands are refused.
- **Undo and redo**: `undo()`, `redo()`, `canUndo`, `canRedo`, and from the keyboard Ctrl/Cmd+Z,
  Shift+Ctrl/Cmd+Z and Ctrl+Y. An entry is the durable state before a change by this plot's reader
  or by an application command; projected input (a link, `setSelection`) records nothing; a pan
  drag is one entry from press to release (or until Escape or lost focus abandons it), however long
  the reader holds still; a run of wheel, pinch or key zoom (until a 400 ms pause) is one entry; a
  new recorded change clears redo; `update` with a new data revision clears both stacks. Undo and
  redo apply a `RestoreSnapshot`, so they follow no link and ask no resolver, and a restored
  viewport is drawn.
  Undo in one linked host is carried to the group like any reader change.
- **Snapshots**: `snapshot` captures the durable state; `restore(snapshot)` checks it with
  `InteractionSnapshot.resolve` and refuses another plan or data revision with a typed
  `SnapshotError`. A restored viewport is drawn, brought inside the axis bounds as `navigate` brings
  a window, and recorded as drawn; a viewport the view cannot draw (on a plot that cannot navigate,
  for a panel it does not have, or one showing a single value) is refused with
  `SnapshotError.Invalid` before anything changes.
- **Inspector**: `host.inspector(sample)` is `InspectorModel.of(state, sample)`;
  `host.subscribeState(listener)` fires with the current state and then only when the data revision
  or durable state changes (projected changes included; hover and focus are not news).
  `JavaFxInspector.mount(Vector("label" -> host, ...))` renders the model as text in the rows the
  browser `InspectorPanel` uses, and `showFilter` reports a `FilterCommand` result; the desktop
  example shows it beside the plots.

## Linked hosts

`JavaFxLink.connect(space, Vector(a, b, c))` links hosts with the browser `WidgetLink`'s rules: one
group selection of observation keys; a reader's change in one plot (among the keys it can select)
is projected into the others as silent `Projected` input; keys a plot lacks are reported through
`onMissing`, never invented; hover and keyboard focus are shown elsewhere as dashed linked rings;
bins chosen as bins stay local; a host joins at most one live link. A new link group starts from
the union of its members' selections, and that union is not projected into the members at
connect: each shows its own selection until a reader's change updates the group.

## Capabilities

`JavaFxCapabilities.matrix` lists every `HostCapability`, named after the rows of the
[browser matrix](browser-capabilities.md). `JavaFxCapabilities.require(c)` returns `Right(())` or an
`InteractionError.UnsupportedCapability` that says what to do instead; the host's own entry points
refuse with the same errors, never silently.

```scala mdoc
JavaFxCapabilities.require(HostCapability.PngExport).left.map(_.message)
```

| Area | Capability | JavaFX | Evidence |
| --- | --- | --- | --- |
| Inspection | Plain-text and structured tooltips, delay, pointer/anchored/fixed placement | Supported | T (text), H (all placements), N (shown) |
| Inspection | Direct and nearest hover | Supported | T (direct), H |
| Inspection | Tooltip appearance (`JavaFxHostOptions` colours, width) | Supported | H |
| Inspection | Text description of every mark and part | Supported | H |
| Emphasis | Hovered, selected, focused appearance | Supported | H, N |
| Emphasis | Inverse emphasis | Supported | H, N |
| Emphasis | Linked legend emphasis, recovery and selection | Supported | T (selection), H (emphasis and recovery) |
| Emphasis | Configurable transitions | Refused: drawn at once; animate the node from the application | H |
| Emphasis | Externally assigned target styles | Refused: map the style in the plot and `update` | H |
| Actions | Pointer and keyboard activation, links through `onLink`, host callbacks | Supported | T, H |
| Selection | Disabled, single, multiple; toggle; application and initial selection | Supported | T (single, multiple, toggle, application), H (disabled, initial) |
| Selection | Rectangle and lasso replace/add/subtract | Supported | H |
| Plot parts | Legend keys, strips, axes, titles, annotations | Supported | T, H |
| Navigation | Keyboard zoom and reset | Supported | T, H, N |
| Navigation | Pan, wheel, pinch and rectangle zoom; bounds | Supported | H, N (pan) |
| Navigation | Toolbar controls | Refused: call `setGestureMode`, `zoomBy`, `resetWindow` | H |
| Composition | Shared hover (emphasis and recovery) and selection across hosts | Supported | T (selection), H (hover and selection) |
| Composition | Composed figures (no figure-wide navigation) | Supported | H |
| Embedding | Responsive sizing, programmatic state and events | Supported | H |
| Embedding | Fullscreen | Refused: `Stage.setFullScreen` on the application's stage | H |
| Embedding | PNG export | Refused: `host.node.snapshot` and encode in the application | H |
| Embedding | Standalone HTML | Refused: not applicable to a desktop node | H |
| Analytical | Aggregate members, deferred resolution, coverage counts | Supported | T, H |
| Analytical | Inspector (`InspectorModel`, `JavaFxInspector`) | Supported | H, N |
| Analytical | Named selections and selection algebra | Supported | H |
| Analytical | Undo and redo (keyboard and API), navigation runs as one entry | Supported | H, N (shortcut `KeyEvent`s fired into the native scene, not OS keystrokes) |
| Analytical | Snapshots and restore | Supported | H, N |
| Analytical | Filter to a selection (`FilterCommand` + `update`) | Supported | H |

**T**: the shared trace comparison below. **H**: headless Monocle suites
(`JavaFxInteractionHostSuite`, `JavaFxHostCapabilitySuite`, `JavaFxHistorySuite`,
`JavaFxHostContractSuite`). **N**: the native desktop run, whose input is JavaFX events fired into
the live scene (see below), not OS input.

## Evidence

The host is qualified by three separate sources; none is inferred from another.

**Shared traces.** The scripts in [`tools/trace`](../tools/trace) name marks by reading order, bins
left to right and legend keys by name — never pixels. [`check-host-trace-browser.cjs`](../tools/check-host-trace-browser.cjs)
replays each in the browser fixture pages on SVG and Canvas and records normalized events, selected
keys, part events, missing-key reports, membership requests, tooltips, announcements and followed
links after every step (`tools/trace/browser/`). `JavaFxTraceParitySuite` replays the same scripts
against JavaFX twins of the fixture pages and requires equality step by step, through both the
Glass robot and scene-event dispatch, and checks that the comparison detects a single changed
event, key or announcement. Each recording carries `sourcesSha256`, a digest of the browser widget
and fixture sources, the fixture pages and the recorder; the suite recomputes it, so a recording
made from other browser sources fails until it is re-recorded with the commands below. The three scripts cover keyboard roving and choosing, pointer hover with
delayed tooltips, click and additive toggle, application selection without echo, a press released
outside, legend parts, keyboard zoom and reset, single-selection histograms, four linked plots
including an impostor key space, and aggregate members with complete, short and failed deferred
replies.

The comparison has limits. The widget page records events with their target keys, but the linked
and members fixtures record each event's class and cause only, so on those pages the targets are
compared through the selected keys, not through the events. Overlay drawing (rings, dimming) is not
part of any trace. After the keyboard zoom in the widget script, the pointer steps address marks by
their unzoomed positions and land on empty space, so they show that both hosts agree on a miss, not
that they hit the same zoomed mark. The negative control perturbs one side of a comparison (the
expected browser steps), not the JavaFX replay.

```sh
sbt browserFixture/fastLinkJS
node tools/check-host-trace-browser.cjs \
  modules/browser-fixture/target/scala-3.3.8/browserfixture-fastopt/main.js \
  tools/trace/widget.json tools/trace/browser/widget-svg.json svg
sbt javafxExample/test
```

**Headless toolkit.** The suites start real JavaFX with
[Monocle](https://github.com/TestFX/Monocle) and the software renderer: a simulated 2x screen for
the module suites, a 1x 1600x1200 screen for the trace parity suite. This is toolkit evidence, not
desktop evidence.

**Native desktop.** `NativeEvidence` runs the same scripts and the example on the platform's own
Glass and Prism, in real windows, and writes `evidence.json` naming the OS, JDK, OpenJFX, Glass and
Prism classes and source SHA, with scene snapshots of the real stages. Its default input path fires
JavaFX events into the live scene graph and filters the physical pointer and keyboard out of the
scripted stages; it injects no OS input. `--os-input` uses the Glass robot, which moves the real
pointer and needs accessibility permission, so it is opt-in.

```sh
sbt "javafxExample/runMain intaglio.javafx.example.NativeEvidence target/native-evidence"
```

The retained receipt is [evidence/interaction12](../evidence/interaction12/README.md). OS-injected
input, screen readers, multi-monitor scale changes and Windows/Linux desktops remain unqualified.

### Minimal host receipts

The minimal-host acceptance suite starts real JavaFX with [Monocle](https://github.com/TestFX/Monocle) and the
software renderer on a test-only simulated 2x screen. A mounted Stage must report output scale 2
before child-event routing and focus checks run. It also drives synthetic pointer and keyboard events at device scales 1 and 2,
checks rendered overlays and projected no-echo, exercises 1,000 attach/dispose cycles with retained
nodes and weak host references, and records 10,000-mark timing to
`modules/javafx/jvm/target/feature-evidence/javafx-interaction-latency.txt` (relative to the module's
forked-test working directory). Timings include synchronous Canvas snapshots and are measurements,
not a latency guarantee. This is headless toolkit evidence; native desktop accessibility and display
scaling across monitors remain separate qualification work.

```sh
sbt 'javafxJVM/testOnly intaglio.javafx.JavaFxInteractionHostSuite'
```

The [recorded qualification receipt](../performance/timings/javafx-host-12a.txt) includes the named
machine, measured latency distribution, evidence boundaries and a manifest of tested source hashes.
