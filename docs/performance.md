# Browser performance: large workloads and release budgets

This page records how the browser widget behaves on two large fixtures, what is indexed or
incremental and what is not, and the budgets that `tools/check-performance-browser.cjs` enforces.
The fixtures are test workloads. Their sizes are not capacity guarantees, and the times are one
machine's measurements, not promises. For the deterministic JVM/Scala.js gates and the JVM timing
receipt, see [Performance and limits](limits.md).

## Fixtures and machine

The page is `PerformanceFixture` in `modules/browser-fixture`, mounted by
[`tools/browser/performance.html`](../tools/browser/performance.html).

| Workload | Data | Host |
| --- | --- | --- |
| `svg10k` | 10,000 keyed rows, SplitMix64 positions, two colour groups; every 25th row repeats its predecessor's position under a new key | SVG widget, 640 × 420, point size 3 |
| `canvas100k` | the same generator at 100,000 rows | Canvas widget, point size 1.5 |
| `membershipCountOnly100k`, `membershipExactKeys100k` | a 40-bin histogram over the 100,000 rows, with `MembershipRetention.CountOnly` or `ExactKeys` | Canvas widget |

Every timed value is the median of three fresh pages. The recorded machine is an Apple M3 Max (14
cores, 36 GiB, macOS 14.3 / Darwin 23.3.0, arm64), with Playwright's headless Chromium
151.0.7922.34 and Node 26.7.0. The bundle is `browserFixture/fastLinkJS`, the bundle every browser
suite uses. The production source measured is `40fe10129676915a664a78f7e53ecac92a911add`, which
was based on `e891562`. After the rebase onto Interaction 10 (`5e1b10f`), the same change is
`0f940ae56412936a32c812979923625558e8c31a`. The "before" column is
`e891562e07121d10c438f4c52dab0a22aea0c994`, linked with the same fixture. Other agents' builds
shared the machine during the runs.

### How each value is measured

- **Phase costs** (`planMs`, `lowerMs`, `pickingBuildMs`, `navigationBuildMs`, `viewCompileMs`) are
  timed inside the page around `InteractionCompiler.compile`, `DeviceScene.fromScene`,
  `Picking.fromResolved` (the target table and its grid index), `prepareNavigation` and
  `SvgWidgetView.compile`, which repeats all of them.
- **Retained heap** is `Runtime.getHeapUsage` after two forced collections through the DevTools
  protocol: the page before building, with the view held, with the widget mounted, after dispose,
  and after the page drops every reference.
- **Pointer-to-highlight latency** dispatches a `pointermove` at a mark's anchor and resolves in the
  next animation frame, after the widget's redraw callback. It is reported three ways: the event
  handler, the frame's callback time before the measuring callback (the redraw), and the total.
  The hover ring's presence is asserted for each sample. The latency excludes the browser's own
  style, layout and paint after the frame.
- **Redraw latency** uses the same frame measurement after a programmatic selection change, a
  re-window and a restyle.
- **History paths** (Interaction 10) change the selection and the window without pointer input,
  so the cached selection layer must follow them. With 1,000 marks selected, the check snapshots
  the state, selects 10 and requires exactly 10 selected rings. It restores the snapshot and
  requires 1,000 rings. It re-windows to the central half, where only the visible selected marks
  are ringed, then undoes the re-window and requires the full window and 1,000 rings again.
- **Disposal** checks the DOM listener count and widget root after `dispose`, then mounts and
  disposes five widgets over the same view. It holds them only through `WeakRef`, and after
  collection it requires that none survive.
- **Replaced views** must be released by the widget's redraw caches. The check dims the plot once,
  clears the selection, holds the current view only through `WeakRef`, and updates to a new view.
  After collection, the old view must be gone. With the cache release removed, this check fails.

## Measurements

| Metric | `svg10k` before | `svg10k` now | `canvas100k` before | `canvas100k` now |
| --- | ---: | ---: | ---: | ---: |
| View compile (ms) | 1,790 | 375 | 9,496 | 3,455 |
| ↳ picking table and grid index (ms) | 284 | 153 | 1,324 | 1,293 |
| ↳ navigation geometry (ms) | 1,175 | 198 | 6,232 | 1,813 |
| Mount (ms) | 264 | 122 | 1,114 | 905 |
| Retained heap, view held (MiB) | 76.5 | 58.2 | 717 | 541 |
| Retained heap, view and widget (MiB) | 70.1 | 51.1 | 764 | 577 |
| One `nearest` query, 4 px (µs) | 100 | 59 | 369 | 319 |
| Pointer event handler (ms) | 20.1 | 0.5 | 169.7 | 1.1 |
| Hover redraw (ms) | 36.6 | 4.2 | 27.6 | 0.4 |
| Pointer to highlight (ms) | 56.9 | 4.6 | 197.6 | 6.7 |
| Selecting 1,000: redraw (ms) | 131.4 | 43.3 | 81.1 | 52.1 |
| Hover with 1,000 selected: redraw (ms) | — | 18.2 | 91.0 | 10.3 |
| Re-window to the central half: action (ms) | 1,814 | 332 | 9,382 | 3,071 |
| ↳ its first frame (ms) | 99 | 91 | 56 | 246 |
| ↳ action + first frame, sum of medians (ms) | 1,913 | 423 | 9,437 | 3,316 |
| Restyle 100 targets: action (ms) | 1,752 | 398 | 9,833 | 4,052 |
| ↳ action + first frame, loaded recording (ms) | — | 681 | — | 6,775 |
| Update, same revision: show (ms) | 122.5 | 51.8 | 675 | 569 |
| ↳ its first frame, loaded recording (ms) | — | 156 | — | 390 |
| Update, new revision: compile next view (ms) | 1,978 | 489 | 10,840 | 5,090 |
| Mount + dispose cycle (ms) | 162 | 71 | 765 | 683 |
| Heap left after release (MiB) | 5.0 | 5.0 | 5.3 | 5.4 |
| Surviving disposed widgets | 0 | 0 | 0 | 0 |

On SVG, part of the view-compile saving is deferred rather than removed. The emphasis copy is no
longer rendered when a view is compiled, so the first frame that dims a new view renders it and
parses it. That is why the re-window and restyle rows show the first frame as well: a new view's
cost is the action plus that frame. On Canvas the copy is never rendered, so the saving is real.
Rows marked "loaded recording" come from the second recording (see
[Budgets and the gate](#budgets-and-the-gate)), on a machine 1.3–1.5× slower than the first.

"Before" for the SVG fixture comes from the first characterization run. The gate's emphasis-reuse
assertion stops the SVG workload at `e891562` before its hover-with-selection measurement, so that
cell has no value. The 5 MiB left after release does not grow with the workload (5.0 MiB at 10,000
marks, 5.4 MiB at 100,000), so it is page residue rather than retained plot state.

### Aggregate membership, recorded separately

| `canvas` histogram over 100,000 rows | `CountOnly` | `ExactKeys` |
| --- | ---: | ---: |
| Plan compile (ms) | 267 | 345 |
| Retained heap with widget (MiB) | 15.9 | 18.8 |
| Redraw after selecting 50,000 observations (ms) | 0.3 | 19.8 |
| Bins ringed by coverage (half rule) | 0 | 24 |

Exact members cost about 78 ms of compile time and 2.8 MiB for 100,000 keys. A selection redraw
under `ExactKeys` measures each bin's coverage once per selection change, which takes about 20 ms
at this size. Hover frames reuse that coverage; see below.

## What is indexed, what is incremental, and what is not

**Indexed.** Shared picking (`PickingPlan`, `NamedPickingPlan`) uses a uniform grid over target
bounds, built once per plan. The exact predicates run only on the grid's candidates. Target
identities resolve through `TargetPositions`, a per-series ordinal table built lazily once per plan,
so `geometry`, `outline` and host lookups no longer scan every target. Clipped geometry
short-circuits two exact cases. A clip rectangle that encloses a mark with a 10⁻⁶ px margin does not
cut it. A clip rectangle apart from the mark by that margin leaves the mark with no visible part.
Plot-part picking builds targets only for the names a part claims. `IndexedPickingScaleSuite`
compares every one of these with an independent oracle (see [Correctness evidence](#correctness-evidence)).

**Incremental, where honest.** The widget's overlay redraw rebuilds only what changed:

- the selection's selected and covered targets, ring paths and emphasis outlines are a layer
  rebuilt when the view, the selection or the domain changes, and hover, focus and linked-emphasis
  frames reuse it;
- the SVG emphasis copy is parsed once per view and re-attached on each redraw;
- `emphasisMarkup` is rendered on first use, so a Canvas widget never renders it;
- the browser host no longer rebuilds an identity map of every target on each pointer event.

**Not incremental.** Each of these does the full work, and the documentation does not claim
otherwise:

- re-windowing (pan, zoom, reset) re-lowers the plan, rebuilds picking and navigation, and
  re-renders the SVG markup or the Canvas program. It does not recompute statistics;
  `InteractionCompiler.rezoom` reuses the trained statistic output;
- `setTargetStyles` repaints by re-lowering, re-indexing and re-rendering the whole view, since
  opacity can change what is pickable;
- `update` with a new view replaces the view whole. The application compiles that view, including
  any statistics. Selection is kept for a same-revision view and reconciled by entity key for a new
  revision, which the gate checks: 990 of 1,000 selected entities remain after every 100th row is
  removed;
- a selection change recomputes its layer over every target.

Each data-bearing view is lowered more than once: once for picking, once for the SVG markup, and,
on Canvas, once more for the Canvas program. The SVG markup is rendered for Canvas widgets too,
because rendering validates the document. Retained heap is dominated by per-mark picking geometry
(regions, clip lists and their lazily computed boundaries), navigation geometry and the scene.
At 100,000 marks that is about 5.5 KiB per mark.

## Budgets and the gate

[`performance/browser-budgets.json`](../performance/browser-budgets.json) records each budgeted
metric's measured median (`recorded`) and its `budget`, with the bundle, browser and machine. Two
recordings feed it, and each metric names its own:

- the first, at `40fe101`, on a moderately busy machine, for the original metrics;
- the second, at `3e2598a`, at a load average rising from 8.9 to 19.8, for the first-frame metrics
  (`rezoomTotalMs`, `restyleRedrawMs`, `restyleTotalMs`, `updateSameRevisionRedrawMs`) added after
  review.

The second recording's shared metrics were 1.3–1.5× slower than the first's, so the budgets for
the newer metrics are correspondingly looser. The headroom is:

- time: the larger of 2 × recorded and recorded + 2 ms (+20 µs for per-query microseconds);
- retained heap: 1.15 × recorded + 2 MiB;
- heap left after release: max(recorded, 0) + 3 MiB, a leak tripwire.

Within one run the timings were steady; the worst three-run spread of any timed metric was under
13%. Machine load moves them together, though. A gate run at a load average of about 15 on the 14
cores, from other builds on the same machine, slowed every timed metric by 1.3–1.75×. Under a 1.5×
headroom, two metrics then failed: one `nearest` query at 100,000 points (489 µs) and the
`ExactKeys` coverage redraw (34.5 ms). The time headroom is therefore 2×. For metrics recorded
above 2 ms, that catches a doubling, and the regressions removed here were 2× to 150×. Below 2 ms
the absolute floor dominates. The hover handler (0.5 ms on SVG, 1.1 ms on Canvas) and the Canvas
hover redraw (0.4 ms) are caught only when a regression adds more than about 2 ms, that is 3–6×
their recorded value. Heap figures did not move with load.
The report records the load average at the start and end of each run. Read a failure together with
it, and rerun on a quiet machine before moving a budget. The gate also fails, independently of
time, on any of these:

- a missing hover ring;
- a lost selection on navigation or update;
- re-parsing the emphasis copy within one view;
- a replaced view that stays reachable;
- wrong selected rings after restore, re-window or undo;
- a listener or root left after dispose;
- a surviving disposed widget;
- coverage rings without exact members;
- a console error.

```text
sbt browserFixture/fastLinkJS
NODE_PATH=<dir with playwright> node tools/check-performance-browser.cjs \
  modules/browser-fixture/target/scala-3.3.8/browserfixture-fastopt/main.js <fresh output dir>
```

It writes `report.json`, which holds every run, the medians, the budget comparisons and the machine.
It exits nonzero on any failure. `--record` does not fail on a budget. Instead it writes
`proposed-budgets.json`, applying the headroom above to every committed metric, and names its source
from `INTAGLIO_PERF_SOURCE_SHA`. It still exits nonzero, and writes nothing, if any assertion fails or
any workload did not complete every run. Review the proposal before changing the committed budgets,
and move a budget only with the reason in the commit. `INTAGLIO_PERF_RUNS` (default 3) and `INTAGLIO_PERF_ONLY` (comma-separated workload
names; partial runs skip the completeness check) are for exploration. The gate runs serially
outside `tools/check-browser-suites.py` because parallel jobs would distort its timings. Audit
browser ownership before and after it, as for every browser check.

Before the rebase, at `29b5fa2811e37352a459351fb96be984c0d2824f`, which carries these budgets, the
gate passed all 44 budget comparisons and every assertion over three runs. The load average was 9.1
at the start and 7.7 at the end. The verification after the review fixes is recorded below. Run
against `e891562` with the same fixture, the gate fails. It stops the SVG
workload at the emphasis-reuse assertion and exceeds 11 Canvas budgets, among them view compile
(12.3 s), pointer to highlight (248 ms), hover redraw, re-window, restyle and retained heap
(764 MiB).

## Correctness evidence

`IndexedPickingScaleSuite` runs on the JVM and on Scala.js. Its 10,000-target scene has 6,000 free
glyphs of five shapes with hollow, filled and coincident keyed marks, 2,000 glyphs in a clip that
cuts many of them, 1,980 glyphs under a 27° rotation and a rotated clip, and 20 scene-spanning
strokes and hollow polygons. The suite checks that:

- 300 point queries at three tolerances and 12 area queries (rectangles and a lasso, under each
  area rule) equal `hitsExhaustive` and `selectExhaustive`;
- coincident marks with distinct keys are all reported, latest drawn first;
- for all 10,000 identities, `geometry` equals the materialized navigation geometry, and
  identities from another revision are refused as `UnknownTarget`;
- directional navigation equals a full sort of the candidates;
- a plan rebuilt with moved marks, or for a new revision, indexes its own geometry, and the old
  plan's identities are foreign to it;
- 3,000 region/clip configurations give boundaries, bounds and intersection tests identical to
  `Clipped.boundariesOf` over every region; the configurations are enclosing, apart, touching and
  cutting, at gaps on both sides of the margin, translated and rotated;
- part picking equals the unfiltered named plan on a faceted 2,000-point plot, outlines included;
- a 4 px query examines on average fewer than 2% of the 10,000 targets. Oracle agreement alone would
  also hold for an index that returned every target, so this separate check keeps the grid
  selective.

## Known gaps

- **Scala.js full optimization hangs this fixture.** With `fullLinkJS` (sbt-scalajs 1.22.0), both
  `e891562` and this commit loop forever in `LayoutPhase.positionRange`. The optimizer folds
  `layer.rows.exists(_.statRow.isInstanceOf[StatRow.Ecdf[?]])` to a loop that never advances its
  iterator (`while (!res && it.hasNext()) { res = false }`), presumably because no `StatRow.Ecdf`
  is instantiated in the program. Any optimized application that compiles a plot without an ECDF
  layer is affected. The budgets are therefore for the `fastLinkJS` bundle, as every other browser
  suite is. The defect is recorded on the Interaction 11 tracker item and is not fixed here.
- Only Chromium 151 on one machine is measured. Other engines, slower hardware and hosted CI are
  not characterized.
- Re-window, restyle and update cost hundreds of milliseconds at 10,000 marks and seconds at
  100,000 (see [What is not incremental](#what-is-indexed-what-is-incremental-and-what-is-not)).
- The pointer latency excludes browser paint. Selecting many marks on the SVG host also pays
  native style and clip-path work, which the frame measurement does not include.
