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
  were added. Those follow-up checks remain pending.
- **#30:** cased segments sharing unambiguous endpoints are stitched into runs and
  loops. All run casings precede all ink. Branches remain separate runs; uncased
  segments preserve independent-stroke behavior. Shared regressions passed 6
  tests on each of JVM and Scala.js. Java2D and JavaFX each passed a native test
  comparing all pixels of 20 joined segments with one polyline, for opaque and
  half-transparent casing. SVG tests were added but the initial run was stopped
  before they finished because disk space fell below the authorized limit.
- **#32:** an explicit JavaFX font weight first selects a registered full face
  name with a matching conventional weight suffix, then falls back to JavaFX
  family lookup. Both drawing and measurement use this resolver. Tests load
  pinned IBM Plex Regular/Medium/SemiBold TTFs and compare selected faces and
  advances with direct native-font measurements. Verification is pending CI.

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
