# Security policy

## Supported versions

Intaglio has not yet been published to Maven Central. Once it is, security
fixes will be issued for the most recent minor line only, built with the
Scala 3 LTS. This file will name the supported versions explicitly at that
point.

## Reporting a vulnerability

Report privately, not in a public issue: open a
[GitHub security advisory](https://github.com/canardlapin/intaglio/security/advisories/new)
on the repository. Include the smallest scene, plot program, or input document
that demonstrates the problem, the module and backend involved, and the
platform (JVM version, or Scala.js and its runtime).

Expect an acknowledgement within a week. A fix, or an explanation of why the
report is not a vulnerability, follows on a timeline proportional to severity.
Please give a reasonable interval before public disclosure.

## What is in scope

Intaglio turns data into drawing instructions. Its security-relevant surface is
narrow but real:

- **Output injection.** The SVG backend serializes caller-supplied text: axis
  and legend labels, `Grob.Text` labels, document and per-grob titles and
  descriptions, `class` and `data-*` values, font family names, and
  `GraphicsName` values that become `data-name`. Every one of these is escaped
  on output and rejected at the render boundary when it contains an XML-illegal
  code point. A construction that escapes an SVG document — closing an element,
  introducing an attribute, or smuggling a `<script>` — through any of those
  channels is a vulnerability. Report it.

- **Untrusted input to the compiler.** `PlotCompiler` and the statistical
  layers consume caller data. A non-finite value, an empty domain, a degenerate
  interval, or a pathological grid must produce a typed error, not an
  exception, an unbounded allocation, or a non-terminating loop. A public entry
  point that hangs or exhausts memory on an input a caller could plausibly hold
  is in scope; `modules/core/shared/src/test/scala/intaglio/FuzzRegressionSuite.scala`
  is the existing court for this class.

- **Resource exhaustion in a renderer.** The raster backends allocate images
  from caller-supplied dimensions and the PDF backend embeds fonts and rasters.
  An input that turns a modest scene into an unbounded allocation is in scope.

- **Dependency vulnerabilities.** Intaglio's only non-test runtime dependency
  outside the Scala and Scala.js standard libraries is Apache PDFBox, used by
  `intaglio-pdf`. `intaglio-javafx` takes OpenJFX as `Provided`. A vulnerable
  version pinned by this build is in scope.

## What is out of scope

- The `unsafe` and `orThrow` entry points throwing on invalid input. That is
  their documented contract; the total `Either`-returning constructor beside
  each one is the safe alternative. `docs/extending/unsafe.md` lists them.
- Rendering an SVG document produced by Intaglio in a host page that does not
  sanitise it. Intaglio escapes what it emits; embedding a document from an
  untrusted source is the host's decision.
- The documentation and tooling scripts under `tools/`, which are maintainer
  tools run on a trusted checkout.
