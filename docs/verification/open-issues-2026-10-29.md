# Open issue review, 29 October 2026

Reviewed all 15 open GitHub issues against remote main
`dda85300120c32607c50c407db784b755971d8de`. Issues #14–#23 already had their
repairs and regressions on main and were closed. Its [CI run](https://github.com/canardlapin/intaglio/actions/runs/40765199376)
passed all ten jobs, including both OpenJFX runtimes, six Scala/JDK combinations,
formatting/documentation, and compatibility. The documented `geomRaster` route
resolves #14; independently antialiased `geomTile` rectangles retain that limitation.

## New repairs

- **#31:** reference equality returns before inspecting raster pixels. Cached hashes
  reject unequal values, while colliding hashes still require pixel comparison.
  `RasterSuite` passed 7 tests on JVM and 7 on Scala.js, including cache lookups and
  a deliberately constructed hash collision.
- **#26:** Canvas caches source uploads across changed image geometry. Two passes
  of integer strip blits realize the public pixel-centre rule. The browser probe
  passed 50 checks under installed Chromium 151.0.7922.34, including DPR 1/2,
  positive host transforms, fractional origins, asymmetric and one-dimensional
  sources, empty coverage, transparency, and a source taller than 4096 pixels.
  Eight cases compared every output byte against `RasterSampling.prepare`.
  `CanvasRendererSuite` passed 14 tests.
  A subsequent independent review identified custom OffscreenCanvas factories and
  sources beyond native canvas dimension limits as missing cases. The follow-up
  preserves CPU sampling for factories without scratch-surface support and for
  source axes above 4096; three additional browser cases and a unit regression
  were added. On 30 October the combined browser probe passed all 173 cases:
  53 raster/font regressions and 120 Unicode tracking comparisons.
- **#30:** cased segments sharing unambiguous endpoints are stitched into runs and
  loops. All run casings precede all ink. Branches remain separate runs; uncased
  segments preserve independent-stroke behavior. Shared regressions passed 6
  tests on each of JVM and Scala.js. Java2D and JavaFX each passed a native test
  comparing all pixels of 20 joined segments with one polyline, for opaque and
  half-transparent casing. The later SVG regression passed on JVM and Scala.js.
- **#32:** an explicit JavaFX font weight first selects a registered full face
  name with a matching conventional weight suffix, then falls back to JavaFX
  family lookup. Both drawing and measurement use this resolver. Tests load
  pinned IBM Plex Regular/Medium/SemiBold TTFs and compare selected faces and
  advances with direct native-font measurements. Both native face tests passed
  locally after a follow-up repaired JavaFX's stale global font inventory by
  also probing conventional full names after registration.

## Unicode follow-up, 30 October 2026

PR #33 was merged upstream as `28e764cb8d24c2a95dc6de17b2412d0c92d5e15d`
while the Unicode work was in progress. Its CI failed on cased-segment picking,
font registration, and additive compatibility. This follow-up repairs those
three failures. The follow-up starts from that merge commit; no unrelated
upstream source change needed integration.

For **#3**, signed `LetterSpacing` now reaches text styles, theme layout,
device lowering, measurement, picking, and all five renderers. Zero retains
the original route. The Unicode 17 boundary implementation passes all 766
official grapheme cases on JVM and Scala.js. Combining marks, variation
selectors, and emoji ZWJ sequences remain together; contextual-script words
remain intact under the conservative JVM shaping policy.

Focused local evidence includes four independent AWT glyph-positioning tests,
seven native JavaFX tracking tests, eight shared style/validation tests, SVG
tracking and conformance tests, and 18 Canvas renderer/conformance tests. The
120 browser Unicode cases compare all output pixels and native measurements
across ten labels, two spacing values, three anchors, and DPR 1/2.

PDF tests distinguish logical `/ActualText`, embedded glyphs, visible rendering,
and reader-specific extraction. PDFBox 3.0.8 reordered a mixed Hebrew fixture;
that adverse result is retained in the documented reader limitation. Poppler
recovered every original character in order, adding directional controls around
the Hebrew run. `tools/check-pdf-tracking.py` reproduces the exact observed output.
See [letter spacing](../letter-spacing.md) for the shaping policy, native end
bearings, JavaFX snapshot ceiling, and reader limitations.

The browser audit found one pre-existing automation browser before and after;
the regression probe closed its own browser and left that process untouched.

The completed local module suites passed 2,518 tests: core JVM/JS (748/737),
shared JVM shaping (4), interaction JVM/JS (246/238), laws JVM/JS (46/46),
SVG JVM/JS (113/107), Java2D (45), PDF (24), JavaFX (139), and Canvas (25).
Documentation compilation covered 80 files with zero errors; the generated
gallery remained unchanged and all relative links resolved. These runs exposed
and repaired decorative casing picking, SVG pointer handling, stale conformance
fixture registration, and older assertions about the segment command structure.

The exact-baseline compatibility gate passed against
`3f3db55c92a49cf06489fd72c0e94e7383738314` (`0.5.0`): backward JVM binary,
reviewed additive forward findings with negative calibration, and TASTy checks
for every existing JVM/Scala.js artifact. No new backward or TASTy exclusions
were added. `scalafmtCheckAll` also passed. Hosted matrix results remain a
separate qualification gate for the follow-up PR.

## Canvas resize measurement

One local browser run, 30 alternating resize frames after four warmup frames,
using a 256×256 source. The baseline executes the previous CPU preparation and
upload path; the candidate uses cached source uploads and strip blits. This
measures JavaScript submission time, not GPU completion or display latency.

| Approximate destination | Previous ms/frame | Candidate ms/frame | Previous uploaded bytes/frame | Candidate uploaded bytes/frame |
| --- | ---: | ---: | ---: | ---: |
| 500×500 | 3.14 | 1.52 | 1,002,002 | 0 |
| 2000×2000 | 49.87 | 7.72 | 16,008,002 | 0 |

Reproduce with `ConsumerRasterBrowserProbe` and
`tools/check-consumer-raster-browser.cjs`. The script owns and closes its
Playwright Chromium browser; process audits before and after found no remaining
automated top-level browser processes.

Local checks ran on Scala 3.3.8 and JDK 25.0.1; JavaFX used OpenJFX 21.0.5.
These local results do not establish other platform/runtime results for the new
changes. Hosted checks are recorded separately when complete.
