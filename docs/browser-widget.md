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

Not yet covered: region selection and pan/zoom (Interaction 05), linked plots (06), standalone
HTML packaging and a toolbar (07), and Canvas (08).
