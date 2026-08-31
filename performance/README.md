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
