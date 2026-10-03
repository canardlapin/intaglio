# Interactive plots in the browser

`intaglio-browser` (Scala.js) mounts an ordinary compiled plot as an interactive, accessible SVG
widget. It reads the same interaction plan, state, picking and behaviour values as the JavaFX
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
seconds to milliseconds, at the endpoints and interior. These are checks only: no host shares a
data window between plots yet (that arrives with pan and zoom). The linked fixture
([`LinkedFixture.scala`](../modules/browser-fixture/src/main/scala/intaglio/browserfixture/LinkedFixture.scala))
is checked by `tools/check-linked-browser.cjs`.

## Keyboard and accessibility

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
```

The script uses Playwright's own Chromium (set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` to a
Playwright-owned build if the installed Playwright expects another), never a system browser
profile. It covers pointer, keyboard, tooltip edges, two isolated widgets, application control,
update, reduced motion, device scale 2 and 25 mount/dispose cycles, and writes screenshots and a
JSON report.

Not yet covered: region selection and pan/zoom (Interaction 05), standalone HTML packaging and a
toolbar (07), and Canvas (08).
