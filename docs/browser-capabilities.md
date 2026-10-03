# Interactive baseline: capability matrix

This matrix certifies Intaglio's interactive widgets against the ggiraph-derived baseline in
[the interaction design](design/interaction.md#the-reference-baseline). Each required behaviour is
split into the parts a reader can observe, and each part has a status for each backend.

| Status | Meaning |
| --- | --- |
| **Pass** | A named real-browser check exercises and asserts the behaviour at the source commit below |
| **Unit** | Only JVM or Scala.js tests assert it; no browser check does yet |
| **Untested** | Implemented, but no test asserts it |
| **Gap** | Not implemented |
| **Pending** | Work exists outside the source commit; it counts only once committed and re-checked there |

Statuses are never promoted from another backend, from serialized SVG, or from a demo of a
different feature. A row passes only when every part of it passes.

**Source commit:** `829b03e`, in Playwright's Chromium 151.0.7922.34:
`tools/check-widget-browser.cjs` 16/16, `tools/check-linked-browser.cjs` 11/11,
`tools/check-navigation-browser.cjs` 15/15, `tools/check-standalone-browser.cjs` and
`tools/check-export-browser.cjs` passing, and `node --test tools/package-widget.test.cjs` 2/2. Check
names are quoted below as they appear in those scripts.

**Canvas:** the Scala.js Canvas renderer has no interaction at this commit. Canvas interaction is in
progress in a separate Interaction 08 lane; every Canvas cell is **Gap** here until that work is
committed and its receipts are re-run and independently verified (native pixels and normalized
trace equality against SVG).

## Summary

| Area | SVG | Canvas |
| --- | --- | --- |
| Inspection | partial: rich tooltips, direct hover and pointer placement pass; nearest hover, anchored and fixed placement, delay are unit-only; appearance has no API | Gap |
| Emphasis | partial: hover, selection, focus and inverse emphasis pass; configurable transitions and externally assigned styles are gaps | Gap |
| Actions | partial: pointer activation, links and callbacks pass; keyboard activation is untested | Gap |
| Selection | partial: add, replace, subtract, clear, lasso and external selection pass; disabled, single and toggle are unit-only; selection at mount is untested | Gap |
| Plot parts | partial: marks and legend keys pass; colorbars, strips, axes, titles and annotations are unit-only | Gap |
| Navigation | **pass** | Gap |
| Composition | partial: linked plots pass; links across panels of one composed figure are unit-only | Gap |
| Embedding | **pass**, including an outside application built against the exact published artifact | Gap |

The baseline is therefore **not yet complete** on either backend. The gaps are listed under
[What remains](#what-remains).

## Inspection

| Part | SVG | Evidence |
| --- | --- | --- |
| Plain-text tooltip | Unit | `InteractionBehaviorSuite` "content is text, never markup"; the fixture's histogram text tooltip is never hovered in a browser check |
| Structured tooltip (labelled fields) | Pass | "pointer hover shows a delayed, escaped tooltip and inverse emphasis"; "long tooltip fields wrap within narrow widgets without losing text" (text, escaping and wrapping; the field structure itself is not asserted) |
| Direct hover | Pass | "pointer hover shows a delayed, escaped tooltip and inverse emphasis"; "at device scale 2 the pointer still hits the drawn mark" |
| Nearest hover | Unit | `HostInputSuite` "the nearest rule reaches a mark within its CSS distance"; no fixture uses `HoverRule.Nearest` |
| Pointer-relative placement | Pass | "a tooltip at the right edge stays inside the widget" (containment; the offset from the pointer is unit-tested only) |
| Mark-anchored placement | Unit | `HostInputSuite` tooltip placement test |
| Fixed placement | Unit | `HostInputSuite` tooltip placement test |
| Configurable appearance | Untested | CSS custom properties and the `.intaglio-tooltip` rule only; no API |
| Configurable delay | Unit | `InteractionBehaviorSuite` "behaviour settings are checked"; the browser check waits out the delay but does not assert the tooltip is hidden before it |
| Acceptance: dense scatter, line, histogram bin and facet strip without ambiguous target changes | partial | scatter only; no browser fixture has a line or a facet strip, and the histogram bin is selected but not inspected |

## Emphasis

| Part | SVG | Evidence |
| --- | --- | --- |
| Hovered appearance | Pass | "pointer hover shows a delayed, escaped tooltip and inverse emphasis" |
| Selected appearance | Pass | "click selects and activates; a link follows only real input" |
| Focused appearance | Pass | "keyboard roves focus with a visible ring, announces it, chooses and clears" |
| Inactive appearance, inverse emphasis | Pass | "pointer hover shows a delayed, escaped tooltip and inverse emphasis" |
| Configurable transitions | Gap | a fixed transition; only the reduced-motion override exists ("reduced motion removes the emphasis transition") |
| Externally assigned styles | Gap | no API assigns classes or styles to targets |
| Acceptance: hover a legend key to emphasize its marks, then recover | partial | "a linked legend entry emphasizes and selects its category in both scatters" asserts the emphasis; recovery on pointer-out is not asserted |

## Actions

| Part | SVG | Evidence |
| --- | --- | --- |
| Typed activation by pointer | Pass | "click selects and activates; a link follows only real input" |
| Typed activation by keyboard | Untested | Enter activates through `HostInput`; neither the browser check nor `HostInputSuite` asserts the activation event |
| Declarative links | Pass | "click selects and activates; a link follows only real input"; `InteractionBehaviorSuite` "links accept web, mail and relative" |
| Host callbacks | Pass | event subscriptions read by every widget check; `InteractionControllerSuite` |
| Acceptance: the same target and payload by pointer and keyboard | partial | keyboard activation is not asserted |

## Selection

| Part | SVG | Evidence |
| --- | --- | --- |
| Disabled mode | Unit | `HostInputSuite` "disabled selection still activates"; `InteractionStateSuite` |
| Single mode | Unit | `InteractionStateSuite`; the histogram fixture is single-mode but no check exercises replacement or refusal |
| Multiple mode, add | Pass | "click selects and activates; a link follows only real input"; "a rectangle selects exactly the marks it covers; Shift adds and Alt subtracts" |
| Toggle | Unit | `HostInputSuite` additive-click toggle |
| Replace | Pass | "a lasso selects the marks inside its polygon" |
| Subtract | Pass | "a rectangle selects exactly the marks it covers; Shift adds and Alt subtracts" |
| Clear | Pass | "keyboard roves focus with a visible ring, announces it, chooses and clears"; "clearing a bin with Escape leaves the linked observation selection alone" |
| Lasso | Pass | "a lasso selects the marks inside its polygon" |
| Externally supplied selection | Pass | "click selects and activates; a link follows only real input" (no echo); "update reconciles the selection by entity key" |
| Initial selection at mount | Untested | `mount` takes a `selection` argument; no test asserts it |
| Acceptance: accumulate, subtract a lasso region, restore from the application | partial | subtraction is asserted for a rectangle, not a lasso; restoring through `setSelection` is asserted separately |

## Plot parts

| Part | SVG | Evidence |
| --- | --- | --- |
| Marks | Pass | most widget checks |
| Legend keys | Pass | "a legend entry is a typed part under the pointer"; the linked legend checks |
| Colorbar components | Unit | `PlotPartsSuite` "a colorbar is found through its title as well as its bar" |
| Facet strips | Unit | `PlotPartsSuite` titled faceted plot |
| Axes and titles | Unit | `PlotPartsSuite` |
| Labels | Gap | no part kind for data labels beyond axis titles |
| Authored annotations | Unit | `PlotPartsSuite` reference-line annotations; text annotations are not parts |
| Acceptance: select a facet strip or legend key and identify its typed value | partial | legend key only; part activation is not asserted in a browser |

## Navigation

| Part | SVG | Evidence |
| --- | --- | --- |
| Pan | Pass | "pan mode drags the window and stops at the data bounds; reset restores it" |
| Wheel zoom | Pass | "wheel zoom keeps the data under the pointer, keeps the selection, and runs no statistic"; "a burst of wheel events in one frame is applied in full and drawn once" |
| Pinch zoom | Pass | "a real two-finger pinch in Inspect mode zooms the plot, not the page" (real touch input; fails with the browser's own pinch-zoom left on) |
| Rectangle zoom | Pass | "zoom to area on a log axis shows the rectangle's data range" |
| Bounds | Pass | "an application window is validated and kept inside the data"; the pan check's clamp |
| Reset | Pass | the pan check's Reset button; "a date axis keeps date labels when zoomed by keyboard; 0 resets" |
| Explicit gesture activation | Pass | "without focus or Ctrl the wheel scrolls the page instead"; "browser zoom keys stay with the browser; zooming out of the full view scrolls the page" |
| Toolbar controls | Pass | "mode and reset controls are visible and report their state" |
| Acceptance: zoom, pan, reset without changing the statistic's input | Pass | the wheel and pan checks assert a counting statistic stays at one call; `RezoomSuite` |

Faceted and flipped plots refuse navigation by design; that is a stated limit, not a pass.

## Composition

| Part | SVG | Evidence |
| --- | --- | --- |
| Shared hover across plots | Pass | "hovering a mark emphasizes the same observation in the differently ordered scatter only" |
| Shared selection across plots | Pass | "a reader selection is projected silently; missing and foreign keys are reported"; "a key only one plot has survives an additive change in the other" |
| Explicit linking rules | Pass | "legend links that cannot link are refused at mount; links do not chain"; the foreign-key-space and application-controlled checks |
| Across panels of one composed figure | Unit | `InteractionCompositionSuite`; the browser links separate widgets only |
| Acceptance: hover one observation in two differently arranged plots | Pass | "hovering a mark emphasizes the same observation in the differently ordered scatter only" |

## Embedding

| Part | SVG | Evidence |
| --- | --- | --- |
| Standalone HTML | Pass | `tools/check-standalone-browser.cjs`: a one-file package opened from `file://` requests nothing but itself, keeps keyboard selection and application control, and refuses an unbundled resource with a visible diagnostic |
| Application mounting, two independent widgets | Pass | "two widgets, no duplicate ids, one tab stop per plot and per toolbar"; "repeated mount and dispose retain no listeners, observers or nodes" |
| Responsive sizing | Pass | `tools/check-standalone-browser.cjs` at 1200 px (device scale 1) and 390 px (device scale 2): the plots fit (480 and 342 px) without horizontal overflow |
| Configurable toolbar | Pass | `tools/check-export-browser.cjs` controls: a control subset, bottom placement, hidden and floating toolbars, keyboard reach |
| Fullscreen | Pass | `tools/check-export-browser.cjs`: fullscreen enters, and a browser without the API gets a typed refusal |
| PNG export | Pass | `tools/check-export-browser.cjs`: original and current views, with and without the selection, at scale 1 and 2, read back as pixels; an unavailable canvas and an encoder failure are reported |
| Programmatic event and state access | Pass | `setSelection`, `navigate`, event and part subscriptions across the widget, linked and navigation checks |
| External consumer of the published artifact | Pass | `bash tools/check-browser-consumer.sh` at `5a27028`: the clean checkout's Scala.js artifacts, published as `0.0.0-consumer-5a27028108f6`, are the only intaglio jars on an outside application's classpath; its two linked widgets mount, select by keyboard, pick by pointer, navigate and dispose cleanly (5/5) |

## Per-geom coverage

[Interaction coverage](interaction-coverage.md) lists the target granularity of 15 built-in
components and compiles one plot per entry. It counts compiled targets; it does not test picking,
and browser fixtures use only points and histograms. These geoms have no entry yet:
`geomContour`, `geomFilledContour`, `geomHeatmap`, `geomQuantileSummary` and `geomRasterByClass`.

## What remains

For SVG, before the baseline can be called complete:

1. Browser checks for nearest hover, anchored and fixed placement, the tooltip delay, plain-text
   tooltips, keyboard activation, toggle, single and disabled modes, selection at mount, lasso
   subtraction, legend-emphasis recovery, and part activation for strips, axes, titles, colorbars
   and annotations.
2. A line and a faceted fixture for the inspection acceptance example.
3. Configurable transitions, externally assigned target styles, a tooltip appearance API, and a
   data-label part kind.
4. Coverage entries for the five uncovered geoms, and picking tests per entry.
5. A browser check that a hollow point is hit at its centre. The shared picking plan now hits the
   inside of hollow point glyphs by default, but the widget passes no `PickPolicy`, so it cannot
   restore outline-only point picking.

For Canvas, every row.
