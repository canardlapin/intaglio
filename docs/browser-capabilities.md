# Interactive baseline: capability matrix

SVG and Canvas pass the eight feature areas in the
[ggiraph-derived reference baseline](design/interaction.md#the-reference-baseline), within the
limits stated below. **Pass** means a named browser check exercises that backend. Shared unit
coverage is identified separately; a Canvas pass is never inferred from SVG output.

## Source and receipts

Browser and external-consumer source: **`13ed4a2b50097c7ec4995c8b1b0b080268f225b5`**, tested with
Playwright Chromium **151.0.7922.34**. The browser runner builds a temporary `git archive` of that
commit, excluding working-tree edits and untracked files, and retains its linked bundle and font
with SHA-256 hashes. The source-isolation regression also covers a concurrent commit.

The retained local evidence root is `.agent-work/interaction08-landing/`. Its
`browser-13ed4a2/report.json` records **12/12 passing jobs**, bundle/font hashes, and equal SVG/Canvas
widget and linked traces. Reports, normalized traces and screenshots live under each job directory.
The following receipt keys identify those directories and the checked-in scripts that reproduce them:

| Key | Report below `browser-13ed4a2/` | Check source |
| --- | --- | --- |
| B | `baseline/report.json`: 26 variants per backend, equal traces | [baseline](../tools/check-canvas-baseline-browser.cjs) |
| P | `parity/report.json`: both backends at DPR 1 and 2 | [parity](../tools/check-canvas-parity-browser.cjs) |
| W | `widget-svg/report.json`, `widget-canvas/report.json`: 16 checks each | [widget](../tools/check-widget-browser.cjs) |
| L | `linked-svg/report.json`, `linked-canvas/report.json`: 11 checks each | [linked](../tools/check-linked-browser.cjs) |
| N | `navigation-pair/report.json`: paired navigation | [paired navigation](../tools/check-canvas-navigation-browser.cjs) |
| NS | `navigation-svg/report.json`: 15 SVG checks | [SVG navigation](../tools/check-navigation-browser.cjs) |
| E | `export-svg/report.json`, `export-canvas/report.json` | [SVG export](../tools/check-export-browser.cjs), [Canvas export](../tools/check-canvas-export-browser.cjs) |
| H | `standalone-svg/report.json`, `standalone-canvas/report.json` | [SVG standalone](../tools/check-standalone-browser.cjs), [Canvas standalone](../tools/check-canvas-standalone-browser.cjs) |
| C | `consumer-13ed4a2/report.json` in the evidence root: 5 checks per backend, equal traces | [external consumer gate](../tools/check-browser-consumer.sh), [smoke checks](../tools/browser-consumer/smoke.cjs) |

C resolves five isolated published artifacts, including `intaglio-canvas`, at
`0.0.0-consumer-13ed4a2b5009`; its report records artifact hashes and the resolved classpath. It
compiles against public artifacts, mounts native Canvas as well as SVG, checks actual Canvas ink,
and compares events, linked selection and navigation. No sibling source projects are used.

The unchanged library, build and compatibility inputs were also checked at
`3216a413adb7e0957d60007618849e3613ab2946`: `scalafmtCheckAll` and `testAll` passed **2,131 tests in
16 reports**, the documentation gate passed, and the unchanged compatibility gate passed including
12 TASTy checks. Exact-SHA logs are retained under `.agent-work/interaction08/review-fix-3216a41/`.
`13ed4a2` changes only browser qualification tooling and the external consumer fixture; this matrix
is a subsequent documentation-only update. These are local receipts, not hosted CI results.

### JavaFX column (Interaction 12)

The JavaFX column records only what was verified for the JavaFX host; a dash means not verified,
never "fails". **Trace**: the shared trace scripts in [`tools/trace`](../tools/trace) produced the
same normalized events, selected keys, tooltips, announcements and followed links in JavaFX as the
browser recorded on SVG and Canvas (`JavaFxTraceParitySuite`, headless Monocle, and the native macOS
run in [evidence/interaction12](../evidence/interaction12/README.md)). **Host**: JavaFX-only checks
in the headless suites; "native" marks checks repeated on the macOS desktop toolkit with JavaFX
events fired into the live scene, not OS input. **Refused**: the host returns an actionable
`InteractionError.UnsupportedCapability` (see the [JavaFX capabilities](javafx-interaction.md#capabilities)).
JavaFX passes are never inferred from the browser columns, and the browser columns are unchanged.

## Inspection

| Required behaviour | SVG | Canvas | JavaFX | Named evidence |
| --- | --- | --- | --- | --- |
| Plain-text tooltip | Pass | Pass | Trace | B: `plain histogram membership tooltip` |
| Structured tooltip | Pass | Pass | Trace | W: `pointer hover shows a delayed, escaped tooltip and inverse emphasis`; long-field wrapping check |
| Direct hover | Pass | Pass | Trace | B: `direct hover ignores empty space`; W: DPR 2 pointer hit |
| Nearest hover | Pass | Pass | Host | B: `nearest hover reaches sparse observation` |
| Pointer-relative placement | Pass | Pass | Host | B: `pointer placement` asserts the pointer offset; W: right-edge containment |
| Mark-anchored placement | Pass | Pass | Host | B: `anchored placement`; shared `HostInputSuite` checks the placement coordinate contract |
| Fixed placement | Pass | Pass | Host | B: `fixed placement` asserts the configured coordinates |
| Configurable appearance | Pass | Pass | Host | B: `typed appearance and transition options honor reduced motion` checks tooltip background; invalid appearance is refused before DOM mutation |
| Configurable delay | Pass | Pass | Trace | B: `configured delay is observed before showing` |
| Scatter, line, histogram and facet-strip inspection | Pass | Pass | — | W: scatter hover; B: `line inspected and selected as a target`, histogram tooltip, and strip tooltip/activation |

## Emphasis

| Required behaviour | SVG | Canvas | JavaFX | Named evidence |
| --- | --- | --- | --- | --- |
| Hovered appearance | Pass | Pass | Host | W: delayed tooltip and inverse emphasis; B: `application appearance overrides` |
| Selected appearance | Pass | Pass | Host | W: `click selects and activates; a link follows only real input`; P: shared entity rings in both composed children |
| Focused appearance | Pass | Pass | Host | W: `keyboard roves focus with a visible ring, announces it, chooses and clears` |
| Inactive appearance and inverse emphasis | Pass | Pass | Host | W: inverse emphasis; B: configured dim opacity |
| Configurable transitions | Pass | Pass | Refused | B: `typed appearance and transition options honor reduced motion` asserts duration and reduced-motion override |
| Externally assigned styles | Pass | Pass | Refused | B: `application-assigned per-entity paint survives emphasis recovery`; runtime styles change exact marks atomically, clear without events, survive navigation/export, and clear on view replacement |
| Linked legend emphasis and recovery | Pass | Pass | Host; legend selection: Trace | L: `a linked legend entry emphasizes and selects its category in both scatters`, including pointer-out recovery |

Runtime styles use the typed `setTargetStyles` API: fill, stroke and opacity where the underlying
paint supports them. Raster fill/opacity are supported; raster stroke and image fill/stroke return
typed refusals. Arbitrary DOM classes are not the styling contract for Canvas.

## Actions

| Required behaviour | SVG | Canvas | JavaFX | Named evidence |
| --- | --- | --- | --- | --- |
| Pointer activation | Pass | Pass | Trace | W: `click selects and activates; a link follows only real input` |
| Keyboard activation | Pass | Pass | Trace | B: `pointer and keyboard activate the same key` |
| Declarative links | Pass | Pass | Trace | W: link follows real input, with no application-event echo |
| Host callbacks | Pass | Pass | Trace | W/L/B record public events; C compares external-consumer event traces |
| Same target and payload by pointer and keyboard | Pass | Pass | Trace | B: `pointer and keyboard activate the same key` asserts the same entity with distinct input origins |

## Selection

| Required behaviour | SVG | Canvas | JavaFX | Named evidence |
| --- | --- | --- | --- | --- |
| Disabled mode | Pass | Pass | Host | B: `disabled selection` |
| Single mode | Pass | Pass | Trace | B: `single selection` |
| Multiple mode and additive selection | Pass | Pass | Trace | W: click selection; B: `shift lasso adds exact middle row` |
| Toggle | Pass | Pass | Trace | B: `additive click toggles selected observation` |
| Replace | Pass | Pass | Host | B: `lasso replaces with exact middle row`; P: exact rectangle membership |
| Subtract | Pass | Pass | Host | B: `alt lasso subtracts exact middle row` |
| Clear | Pass | Pass | Trace | W: keyboard clear; L: `clearing a bin with Escape leaves the linked observation selection alone` |
| Lasso | Pass | Pass | Host | B: replace/add/subtract against independently specified keys `r3,r4,r5` |
| Externally supplied selection | Pass | Pass | Trace | W: application selection without echo and update reconciliation; L: programmatic selection does not propagate |
| Initial selection | Pass | Pass | Host | B: `initial selection` asserts state before the first click |
| Accumulate, subtract a lasso region, restore from application | Pass | Pass | Host | B: consecutive replace/add/subtract with application-supplied `r0`; W/L: silent application selection |

## Plot parts

| Required behaviour | SVG | Canvas | JavaFX | Named evidence |
| --- | --- | --- | --- | --- |
| Marks | Pass | Pass | Trace | W/P/B: points, line, histogram and keyed text labels |
| Legend keys | Pass | Pass | Trace | W: `a legend entry is a typed part under the pointer`; L: linked legend selection |
| Colorbar components | Pass | Pass | — | B: `title subtitle strip axis colorbar annotation activation` checks description and typed activation |
| Facet strips | Pass | Pass | — | Same B check: strip description and typed activation |
| Axes and titles | Pass | Pass | — | Same B check: axis, plot title and subtitle |
| Labels | Pass | Pass | — | B: `data labels are addressable keyed marks` |
| Authored annotations | Pass | Pass | — | Same B parts check: reference-line annotation |
| Identify a facet strip or legend key by typed value | Pass | Pass | Host | B: strip description/activation; W/L: typed legend entry |

Plot parts are inspected and activated; they are not added to the observation selection. Data
labels are keyed text marks. The authored-annotation check covers reference lines, not arbitrary
unkeyed text. Composed child parts have scoped identities; shared unit checks cover independent
child titles and clashing collected legends. Public part events carry the typed part value;
target events additionally carry child identity.

## Navigation

| Required behaviour | SVG | Canvas | JavaFX | Named evidence |
| --- | --- | --- | --- | --- |
| Pan | Pass | Pass | Host | N: pan preserves the grabbed datum and selection |
| Wheel zoom | Pass | Pass | Host | P: real wheel narrows the window, equal backend traces; NS additionally checks wheel bursts and page arbitration |
| Pinch zoom | Pass | Pass | Host | P: real two-finger touch input narrows the plot window while page scale stays 1 |
| Rectangle zoom | Pass | Pass | Host | N: logarithmic rectangle maps to the expected data edges |
| Bounds | Pass | Pass | Host | N: pan clamps at the lower bound; NS: application-window validation |
| Reset | Pass | Pass | Trace | N: reset preserves selection; temporal keyboard zoom/reset |
| Explicit gesture activation | Pass | Pass | — | P: focused Inspect mode receives wheel/pinch; N: explicit pan/zoom modes; NS additionally checks browser/page arbitration |
| Toolbar controls | Pass | Pass | Refused | W: plot/toolbar tab stops; N/P: modes and reset; E: configurable controls |
| Zoom, pan and reset without recomputing statistics | Pass | Pass | Host | N: counting statistic remains unchanged; NS: wheel/pan statistic checks |

Navigation supports a single panel with supported Cartesian scales. Faceted, flipped and composed
figure navigation is explicitly refused; P checks the composed refusal. It is not a claim of
independent navigation inside every facet or composed child.

## Composition

| Required behaviour | SVG | Canvas | JavaFX | Named evidence |
| --- | --- | --- | --- | --- |
| Shared hover across plots | Pass | Pass | Host | L: `hovering a mark emphasizes the same observation in the differently ordered scatter only` |
| Shared selection across plots | Pass | Pass | Trace | L: silent projection, missing/foreign keys, and additive preservation of a key present in only one plot |
| Explicit linking rules | Pass | Pass | Trace | L: `legend links that cannot link are refused at mount; links do not chain`; foreign-key-space and application-controlled checks |
| Across panels of one composed figure | Pass | Pass | — | P: transformed composed children select the same entity and display two selected rings; pointer/keyboard/touch traces match |
| Same observation in differently arranged plots | Pass | Pass | Trace | L: two differently ordered scatters plus histogram; C: two linked external-consumer widgets |

## Embedding

| Required behaviour | SVG | Canvas | JavaFX | Named evidence |
| --- | --- | --- | --- | --- |
| Standalone HTML | Pass | Pass | Refused | H: a `file://` package requests only itself, supports keyboard selection/application control, and visibly refuses an unbundled resource |
| Application mounting and independent widgets | Pass | Pass | Host | W: duplicate IDs/prefix validation and 25 mount/dispose cycles; C: two public-API widgets |
| Responsive sizing | Pass | Pass | Host | H: 1200 px/DPR 1 and 390 px/DPR 2, without horizontal overflow; W: resize preserves revision/selection |
| Configurable toolbar | Pass | Pass | Refused | E: control subset, bottom placement, hidden/floating controls and keyboard reach |
| Fullscreen | Pass | Pass | Refused | E: entry and typed refusal when unavailable |
| PNG export | Pass | Pass | Refused | E: original/current views, selection inclusion, scales 1/2, pixel readback, unavailable context and encoder failure |
| Programmatic event/state access | Pass | Pass | Trace | W/L/N/B/C: selection, navigation, subscriptions, target styles and view replacement |
| External consumer of exact artifacts | Pass | Pass | — | C: 10/10 checks, native Canvas ink, equal event/selection/window traces, clean disposal |

Canvas paints base marks directly and uses DOM/SVG companions for accessible focus, inspection,
selection and gestures; it does not create an SVG node for every base mark. E separately checks
embedded-font readiness and failure diagnostics. PNG export re-renders the selected scene through
an SVG snapshot for both hosts; its pixels are export evidence, not evidence of native Canvas
painting. Native Canvas painting is checked separately by B, P, N and C.

## Per-geom coverage and qualification limits

[Interaction coverage](interaction-coverage.md) is generated from 20 built-in component entries,
including contour, filled contour, heatmap, quantile summary and classed raster. The shared
`InteractionCoverageSuite` compiles every entry and checks exact target counts. These are shared
compiler checks, not 20 separate native-browser certifications. Browser specimens cover points,
line, histogram, text labels, facets, clipping, transforms, composition and typed plot parts.

Default hollow-point picking includes the unpainted interior. P verifies nearby ring ink centred
on the expected anchor independently of centre picking, at both DPRs; C verifies it in the
external application. Outline-only picking in the browser is a `policy` on `SvgWidgetView.compile`
and `compileComposition`, kept across re-windowing and repainting; `SvgWidgetViewSuite` checks that
it misses a hollow point's centre where the default hits it (unit evidence; no browser check).

These receipts qualify the tested Chromium version. Other browser engines, hosted browser CI,
large-workload capacity, analytical extensions and the later Interaction 09–12 qualification work
are not certified here. Large-workload performance is measured separately, below. See [Canvas widget contracts](canvas-widget.md) for
backing-store bounds, supported paints, lifecycle and error behaviour.

## Analytical extension: aggregate membership (Interaction 09)

Beyond the baseline, separately from it: an aggregate's exact members, linked coverage and deferred
resolution. Receipts are `tools/check-membership-browser.cjs` on SVG and Canvas, against bins
recomputed from the raw values, and the shared suites `MemberSelectionSuite`,
`MembershipResolverSuite` and `MemberCoverageSuite` (JVM and Scala.js).

| Part | SVG | Canvas | JavaFX | Evidence |
| --- | --- | --- | --- | --- |
| A bin selects exactly its members; the bin itself stays a separate, plot-local selection | Pass | Pass | Trace | "clicking each bin selects exactly its members, in the histogram and both scatters" |
| Counts, representatives, partial or stale membership never become a selection | Pass | Pass | Trace | `MemberSelectionSuite`; "superseded, short and failed replies change nothing" |
| Deferred members: pending, unavailable, failed and complete replies; late replies rejected | Pass | Pass | Trace: complete, short and failed replies only | `MembershipResolverSuite`; the two deferred-bin browser checks |
| Linked plots show partial-selection counts in the tooltip, the text companion and on keyboard focus | Pass | Pass | Trace | "an area selected in a scatter shows each bin's covered count and rings bins covered by half" (counts 3/7, 4/8, 4/8, 4/7) |
| Emphasis by a fraction of members, exactly at the threshold | Pass | Pass | — | the same check: 4/8 is ringed under the half rule, 3/7 is not |
| Emphasis by any or all members | Unit | Unit | — | `MemberCoverageSuite` thresholds; no browser check selects those rules |
| Pointing at an observation emphasizes the bins that hold it | Pass | Pass | — | the same check: hovering a scatter mark rings exactly one bin |
| Clearing a Members-mode plot clears the linked group | Pass | Pass | Trace | "clicking each bin selects exactly its members…" (Escape at the end) |
| Filtering or recomputing a statistic from a selection | Pass | Pass | — | an explicit application command, not part of membership; see Interaction 10 below |

## Analytical extension: named selections, history, snapshots and filters (Interaction 10)

Beyond the baseline: selections saved by name and combined, undo and redo, snapshots that survive a
reload, an inspector and an explicit filter. Receipts are `tools/check-history-browser.cjs` on SVG
and Canvas (paired traces must match), against set and bin oracles computed in the check, and the
shared suites `SelectionAlgebraSuite`, `InteractionSnapshotSuite`, `InteractionHistorySuite`,
`InspectorModelSuite` and `FilterCommandSuite` (JVM and Scala.js). See
[selection-history.md](selection-history.md).

| Part | SVG | Canvas | JavaFX | Evidence |
| --- | --- | --- | --- | --- |
| Save an area sweep and a bin's exact members by name | Pass | Pass | — | "a swept area and a bin's members are saved by name" |
| Union, intersection and difference follow the set laws; incompatible operands are refused | Pass | Pass | Host | the browser oracle check; `SelectionAlgebraSuite` (laws exhaustively, refusals) |
| Recall a saved selection into the current one, carried to linked plots | Pass | Pass | — | "intersection, union and difference of saved selections match set oracles" |
| Undo and redo by keyboard (both redo shortcuts), followed by the linked plots, which record nothing | Pass | Pass | Host; native: shortcut key events fired into the scene, not OS keystrokes | "keyboard undo and redo walk this plot's changes…": only `SelectionChanged` events, `canUndo` false in the linked plot |
| No link or resolver effect from undo, redo or restore | Unit | Unit | Host: no link followed | `InteractionHistorySuite`, `InteractionSnapshotSuite`; the history page has no links or deferred members |
| A pan is one history entry; undo restores each window in turn | Pass | Pass | Host | "a pan is one history entry…" (20 pointer frames, then zoom and pan undone and redone) |
| A restored window on a plot with one categorical axis | Gap | Gap | — | fixed by navigating only the numeric axis; no fixture builds a categorical axis |
| A snapshot restores keys, saved selections and the window after a reload | Pass | Pass | Host: same session, no reload | "a snapshot survives a reload…" |
| Tampered schema, codec, revision or text is refused with its reason, changing nothing | Pass | Pass | Host: another data revision only | "a tampered snapshot is refused…" (selection, saved selections and window unchanged); `InteractionSnapshotSuite` for each `SnapshotError` |
| Inspector: observations, aggregate coverage, saved selections, unresolved keys | Pass | Pass | Host: observations, coverage, saved selections; native: observations | "the inspector shows each plot's selection…" ("k of n selected" against the bin oracle) |
| Inspector: exact member lists, uncountable aggregates, stale coverage | Unit | Unit | — | `InspectorModelSuite` |
| Filter to a selection: input and statistical changes reported, selection unchanged, then reconciled | Pass | Pass | Host | "filtering the histogram to a selection…" (bin totals against the oracle; every plot keeps the kept observations) |
| Undo across a data replacement | Gap | Gap | — | history is cleared when the data is replaced, by design |

## Larger workloads (Interaction 11)

Measured separately from the baseline and from each other. The receipt is
[`tools/check-performance-browser.cjs`](../tools/check-performance-browser.cjs): three runs per
workload, `fastLinkJS` bundle, Chromium 151.0.7922.34 headless, on an Apple M3 Max. The production
source is `40fe10129676915a664a78f7e53ecac92a911add` (`0f940ae` after the rebase onto Interaction 10).
Budgets are in
[`performance/browser-budgets.json`](../performance/browser-budgets.json), and the method is in
[Browser performance](performance.md). Times are medians on that machine, not promises, and the
fixture sizes are not capacity guarantees.

| Measured | SVG, 10,000 marks | Canvas, 100,000 points | Evidence |
| --- | --- | --- | --- |
| Indexed picking agrees with the exhaustive oracle under overlap, clipping and rotation | Unit | Unit | `IndexedPickingScaleSuite` (JVM and Scala.js) |
| View compile, including picking index and navigation geometry (on SVG the emphasis copy is deferred to the first dimmed frame) | 375 ms | 3,455 ms | `viewCompileMs`, `pickingBuildMs`, `navigationBuildMs` |
| Retained heap, view and widget | 51 MiB | 577 MiB | `totalRetainedMiB` |
| Pointer to highlight (handler + redraw) | 4.6 ms | 6.7 ms | `pointerToHighlightMs`; ring asserted per sample |
| Hover redraw over 1,000 selected marks | 18.2 ms | 10.3 ms | `hoverWithSelectionRedrawMs` |
| Redraw after selecting 1,000 marks | 43 ms | 52 ms | `select1000RedrawMs` |
| Re-window to the central half (full rebuild; statistics not recomputed): action + first frame | 332 + 91 ms | 3,071 + 246 ms | `rezoomMs`, `rezoomRedrawMs` |
| Replaced views are released by the redraw caches | Pass | Pass | `WeakRef` to the old view is cleared after an update |
| Update to new data keeps surviving selected entities | Pass | Pass | 990 of 1,000 selected after every 100th row is removed |
| Restore and undo redraw the cached selection layer (1,000 selected; restore, re-window, undo) | Pass | Pass | exact selected-ring counts after each step |
| Dispose releases listeners, root and widget | Pass | Pass | 0 listeners, 0 roots, 0 of 5 `WeakRef` widgets survive collection |
| Exact-member coverage cost (histogram, 100,000 rows) | — | 345 vs 267 ms compile, 18.8 vs 15.9 MiB, 19.8 ms coverage redraw | `membershipExactKeys100k` vs `membershipCountOnly100k` |

Re-windowing, restyling and updates rebuild the whole view; only the overlay redraw is incremental.
The budgets are measured on the `fastLinkJS` bundle. An optimized (`fullLinkJS`) bundle passed
the same budgets, with compile phases 8–24% faster; see
[Optimized bundles](performance.md#optimized-fulllinkjs-bundles).
