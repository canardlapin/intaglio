# Deterministic performance gates

This module runs representative Intaglio workloads on both the JVM and Scala.js. It deliberately
does not fail CI on elapsed time: shared runners, JIT warm-up, garbage collection, and hosted-runner
contention make wall-clock thresholds noisy. Instead, the gates measure stable work and output
cardinality that tracks the severe regressions this repository needs to stop:

- a 20,000-mark lean scatter must retain one batch grob and one device primitive;
- a 256 by 256 raster must retain four packed bytes per pixel and one image primitive;
- 10,000-row dodge and stack workloads must not duplicate rows or grobs;
- an 8,192-level discrete domain must derive one stable identity per lookup;
- generated histograms must use arithmetic lookup, explicit breaks must use binary search, and a
  256-bin workload must not grow extra outputs;
- raster and 10,000-mark SVG documents have explicit serialized-size ceilings.

The raw receipt is [baselines/v1.tsv](baselines/v1.tsv). `recorded` is the deterministic value
observed for the source SHA named in the receipt. `high_severity_limit` is the reviewed CI ceiling.
Cardinality and strategy limits are exact. Serialized-size limits allow 25 percent growth so small
formatting changes do not masquerade as severe performance failures. A JVM test checks that the TSV
and the shared JVM/Scala.js definitions remain identical.

The repository-wide `testAll` alias includes `performanceJVM/test` and `performanceJS/test`, so the
same receipt is reproduced on both platforms. Run only these gates with:

```text
sbt "performanceJVM/test" "performanceJS/test"
```

To refresh a baseline, first review why the deterministic metric changed. Then update the shared
baseline definition and TSV in the same commit, record the production source SHA, and rerun both
platforms plus `scalafmtCheckAll`. Use a profiler or a proper benchmark runner for exploratory
wall-clock work; do not convert timing observations into hosted-CI pass/fail assertions.

## The timing receipt (not a CI gate)

Alongside the deterministic gates, [timings/v1.tsv](timings/v1.tsv) records elapsed time and
allocation for interactive-rate workloads: a 30-panel faceted trellis (shared and free scales,
sparse and dense, lean and rich provenance), a resize sweep over unchanged data at five device
sizes taken three ways (full `resolve`, the `train`/`place` split, and `resolve` through a warm
`PlotCompileCache`), and dense hover/selection picking over 20,000 marks. Per-phase times (mapping,
stat, scale training, resolve, layout, lowering) come from a thread-local phase clock inside the
compiler that is inert unless the harness enables it.

Regenerate the receipt on named hardware with:

```text
sbt "performanceJVM/Test/runMain intaglio.performance.TimingHarness"
```

The harness runs single-threaded, discards warm-up runs, and reports run counts with min/median/max
so a single run is never presented as a benchmark. The receipt is JVM-only, machine-specific, and
deliberately **not** asserted in CI — everything in the paragraph above about wall-clock noise
still applies. A deterministic `TimingWorkloadSuite` keeps the workload definitions compiling and
structurally honest (panel counts, retained-row counts, picking target counts) without asserting
any elapsed time. The headline observations are summarized in
[docs/limits.md](../docs/limits.md#measured-time-the-non-gating-receipt).
