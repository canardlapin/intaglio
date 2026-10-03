# Interactive plots in the browser

`intaglio-browser` (Scala.js) mounts an ordinary compiled plot as an interactive, accessible SVG
widget, with native Canvas available through `WidgetOptions(renderer = WidgetRenderer.Canvas)`.
See [Canvas widgets](canvas-widget.md) for renderer selection, application appearance and runtime
target styles. It reads the same interaction plan, state, picking and behaviour values as the JavaFX
host, so the two hosts agree on what a pointer or a key does.

## The smallest use

1. Compile the plot for interaction at the size it will be shown:
   `InteractionCompiler.compile(plot, space, revision, planId, planRevision,
   PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived()))(_.id)`.
2. Resolve it once for drawing and picking: `SvgWidgetView.compile(plan, context, "scores")`.
   The third argument namespaces every SVG id, so give each widget on a page its own.
3. Mount it: `SvgWidget.mount(container, view, behavior)`.

A complete, compiled example is the browser fixture,
[`modules/browser-fixture/.../Fixture.scala`](../modules/browser-fixture/src/main/scala/intaglio/browserfixture/Fixture.scala):
a keyed scatter with field tooltips, links and inverse emphasis beside a histogram whose bins
are selected as bins.

## Behaviour

An `InteractionBehavior[A]` describes what the widget shows, once, from the typed target:

| Setting | Meaning |
| --- | --- |
| `withTooltip(target => Option[TargetContent])` | plain text or labelled fields; always rendered as text, never markup |
| `withLink(target => Option[TargetLink])` | `http`, `https`, `mailto` or relative URLs only; followed only when a reader activates the mark |
| `withPlacement` | `Pointer(offset)`, `Anchored(offset)` or `Fixed(x, y)`; tooltips flip at an edge and stay inside the widget |
| `withTooltipDelay(ms)` | pointer hover only; keyboard focus shows content at once |
| `withHover` | `Direct`, or `Nearest(maxCssPx)` for sparse marks |
| `withInverseEmphasis(true)` | dims the plot and shows hovered and selected marks in their original paint |
| `withSelection` | `Disabled`, `Single` or `Multiple` |

`InteractionBehavior.describingEntities(describe)` is a ready default that names each target by
its entity key.

## Events and application control

`widget.subscribe` delivers typed `EventRecord`s (hover, focus, activation, selection) with their
cause; `widget.subscribeParts` reports legend entries, strips, axes, titles, colorbars and
annotations the reader points at or activates, as typed `PlotPart` values. No caller reads DOM
attributes.

`widget.setSelection` replaces the selection from the application. It is delivered as
`Projected`: state and overlay change, no event is emitted, and no link is followed, so linked
views cannot echo each other. `widget.update(view)` shows a new view of the same plot and
reconciles the selection by entity key, reporting dropped entities in a `Reconciled` event.

## Linked views

`WidgetLink.connect(space, Vector(a, b, c))` links widgets over the observation keys of one
`KeySpace` instance. The link owns one group selection of those keys:

- a reader's change in one plot (keys added or removed, among the keys that plot draws as marks)
  updates the group, and every other plot shows the group selection projected into its own keys,
  whatever its row order or arrangement. Keys a plot does not contain are reported through
  `onMissing`, never invented, and survive a reader's additive change in another plot;
- what the reader points at (a hovered or keyboard-focused mark, or a linked legend entry) is
  shown in the other plots as dotted linked rings and counts toward inverse emphasis;
- projection is `Projected` input and emphasis is display only, so links never echo or loop. A
  projection a plot refuses (several keys into a single-selection plot) is reported through
  `onError`. A widget belongs to at most one live link;
- a change that adds or removes no observation keys the plot draws, such as selecting, toggling
  or clearing histogram bins as bins, stays in its plot. A bin is never reported as its member
  observations (exact members are a later capability).

A plot keyed by another space, even one with the same namespace text, never feeds the group and
is reported missing for every key. `InteractionBehavior.withLegendLink(LegendLink(legend, space))`
makes a keyed legend's entries stand for link keys of `space`, bound by
`LayerBinding(...).withLinks(space)(row => category)`. `legend` is the legend's guide name: for a
derived legend, the scale name followed by `-legend` (`scaleColorDiscrete(..., name = "block")`
gives `block-legend`). Hovering an entry emphasizes its category's marks; clicking selects them as
a reader action, which the link then projects. Mounting refuses a legend link that names no
legend in the plot, or whose entry labels match no mark's link key.

`LinkedAxes.compatible` checks whether two plots' axes mean the same data position (the same
transform value and domain), and `LinkedAxes.converted` checks an explicit conversion, such as
seconds to milliseconds, at the endpoints and interior. These are checks only: pan and zoom
(below) are per plot, and no host shares a data window between plots yet. The linked fixture
([`LinkedFixture.scala`](../modules/browser-fixture/src/main/scala/intaglio/browserfixture/LinkedFixture.scala))
is checked by `tools/check-linked-browser.cjs`.

## Selecting regions and navigating

A toolbar above the plot chooses what a drag does: **Inspect** (point and click), **Select
area** (a rubber band), **Lasso**, **Pan** and **Zoom to area**, plus **Reset view**. A band or
lasso selects the marks whose bounds centre it covers (`AreaRule.CenterInside`); Shift adds to
the selection and Alt subtracts from it. Escape abandons a drag in progress without selecting.
The same sweep is available to other hosts as `HostInput.region(state, area, rule, operation)`.

Navigation changes the data window, not the picture's size. `InteractionCompiler.rezoom`
re-places the plan's retained training data at the new window: statistics, scale training and
target identities are not recomputed, the panel stays where it is, and axes and grid are
re-broken for the window as a fresh compile at that window would break them. Windows keep the
axis kind: a log axis zooms in its transformed positions and a date axis keeps date ticks. A
window never leaves the trained extent, and a window covering it is the compiled view again.
Selection, focus and hover survive every window change.

| Input | Effect |
| --- | --- |
| wheel with the plot focused, or Ctrl-wheel (a trackpad pinch) | zoom about the pointer, keeping the data under it fixed |
| wheel otherwise, or zooming out of the full view | scrolls the page, as it would without the widget |
| two-finger pinch, in any mode | zoom about the fingers' midpoint; one finger still scrolls the page in Inspect mode |
| drag in Pan mode | the data under the pointer follows it |
| drag in Zoom to area mode | show exactly the rectangle's data range |
| `+` / `-`, `0` with the plot focused | zoom in or out about the centre; reset (with Ctrl, Meta or Alt these stay the browser's own zoom keys) |

`widget.navigate(window)` and `widget.currentWindow` give an application the same control in
panel position units (`PanelWindow`). `navigate` refuses a reversed or non-finite interval, shifts
one that leaves the data inside it, and treats one covering the data as the compiled view
(`DataWindowNavigator.normalize`). `DataWindowNavigator` holds the pure arithmetic and turns a
window into typed `CoordinateWindow`s. Each shown window is recorded in the interaction state as
the panel's viewport, with its cause: `Pointer` for gestures and the Reset button, `Keyboard` for
keys, `Programmatic` for `navigate` and for the reset an `update` performs. Pointer moves and wheel
notches are coalesced to one re-windowing per animation frame, and each builds on the window last
asked for, so a burst of input is applied in full. Zooming in stops at the narrowest window an
axis can show (on a date axis, a window of a single day is never produced).

`widget.magnify(factor)` is whole-scene magnification: marks and text grow together, and picking
follows the drawn size. Factor 1 is the default responsive width; any other factor fixes the CSS
width at that multiple of the compiled width and follows it across `update`. Navigation never
changes text or mark size.

Faceted and flipped plots, and plots with no numeric or temporal position axis, refuse
navigation with a typed error and hide the Pan, Zoom and Reset controls; region selection still
works. An `update` returns to the full view, cancels a drag in progress, and leaves Pan or Zoom to
area for Inspect when the new view cannot be navigated.

Known limits: a navigated window stays within the scale's trained domain, so the expansion margin
around the data in the compiled view is reachable only by Reset, and marks at the very edge of the
data sit on the panel edge when the window touches it. A plot compiled with
`PanelFraming.MarkInk` keeps its ink framing only in the full view: a re-windowed view draws an
unwindowed axis with its expanded range. An application that recompiles and calls `update` on
resize resets the reader's window. Axes are broken for the window shown, so a narrow window
keeps its own ticks (on a log axis, multiples within the decade).

## Keyboard and accessibility

The toolbar is one tab stop, at the active mode; arrow keys, Home and End move between its
buttons, and each mode button reports `aria-pressed`.

The plot is one tab stop. Arrow keys move focus to the nearest mark in that direction; Home and
End go to the first and last; Page Up and Page Down step through reading order; Enter or Space
selects (Shift, Ctrl or Meta toggles) and activates; Escape clears the selection. The focused
mark has a dashed ring with a light halo, and a polite live region announces it. A collapsible
table lists every mark and plot part as text without adding a tab stop per datum. Under
`prefers-reduced-motion: reduce` the emphasis transition is off.

## Lifecycle

`widget.dispose()` removes every listener, observer, timer, animation frame and DOM node the
widget added, and disposes its controller. It is idempotent. Several widgets on one page share
nothing but a static stylesheet.

## Verification

The widget is checked in a real browser:

```text
sbt browserFixture/fastLinkJS
node tools/check-widget-browser.cjs \
  modules/browser-fixture/target/scala-3.3.8/browserfixture-fastopt/main.js <output-dir>
node tools/check-navigation-browser.cjs \
  modules/browser-fixture/target/scala-3.3.8/browserfixture-fastopt/main.js <output-dir>
```

The navigation check drives linear, log10 and date scatters
([`NavigationFixture.scala`](../modules/browser-fixture/src/main/scala/intaglio/browserfixture/NavigationFixture.scala)):
band, Shift and Alt sweeps and a lasso against the marks' own centres, wheel zoom that holds the
pointer's data and leaves a counting statistic at one call, page-scroll arbitration, pan that
holds the grabbed data and stops at the bounds, zoom to area on the log axis, date ticks under
keyboard zoom, a two-finger pinch, and magnification against navigation.

The script uses Playwright's own Chromium (set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` to a
Playwright-owned build if the installed Playwright expects another), never a system browser
profile. It covers pointer, keyboard, tooltip edges, two isolated widgets, application control,
update, reduced motion, device scale 2 and 25 mount/dispose cycles, and writes screenshots and a
JSON report.

Canvas interaction parity retains its separate Interaction 08 gate. Browser receipts qualify
the cases they exercise, not every operating system or browser engine.

## Standalone files, controls, and PNG export

See [standalone browser applications](standalone-browser.md) for a one-file offline package,
configurable toolbar placement and controls, explicit original/current PNG snapshots, fullscreen,
and embedded fonts. Canvas parity retains its separate qualification gate.
