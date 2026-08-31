# Numerical standards

Intaglio's statistical and contour kernels are portable Scala code. The same algorithms and test
fixtures run on the JVM and Scala.js; renderers do not reinterpret their results.

## Finite input and aggregation

Public constructors reject invalid counts, bandwidths, grids, breaks, and domains. Statistical
transforms reject non-finite observations through typed `GraphicsError` values before aggregation.

Grouped summaries use one-pass Welford moments. Their reported mean also uses Neumaier compensated
summation when the total is representable, and the second central moment is corrected to that mean.
Gaussian kernel sums use the same compensated accumulator. Polygon area is evaluated relative to
the first vertex and accumulated with compensation, avoiding the cancellation caused by a large
coordinate offset.

## Histogram closure and mass

Histogram bins are right-closed. For breaks `b0 < b1 < ... < bn`, the first bin is `[b0, b1]` and
each later bin is `(bi, b(i+1)]`. Therefore an internal break belongs to the bin on its left, both
domain endpoints are included, and an explicit-break histogram rejects observations outside the
closed outer domain. Count conservation is exact. Proportions and the sum of `density * binWidth`
are checked with an absolute tolerance of `1e-12` (the focused boundary fixture uses `1e-15`).

## Kernel density normalization and strategy

The direct one- and two-dimensional Gaussian kernels include the analytical whole-space
normalizer. Intaglio samples that density on the requested finite domain; it does not renormalize a
truncated domain to one. A normalization check therefore needs a domain wide enough to contain the
material Gaussian tails. Shared tests use the trapezoid rule and absolute tolerances of `1e-6` in
one dimension and `2e-6` in two dimensions.

`KdeStrategy.Direct` is the portable implementation and the default. `KdeStrategy.Fft` is an
explicit extension boundary, not a silent heuristic: selecting it currently returns the typed
`UnsupportedStatStrategy` error. No automatic direct-to-FFT threshold is claimed before an FFT
implementation, cross-platform parity fixtures, and benchmark evidence exist.

## Contour ambiguity and topology

Marching squares treats a corner equal to the contour level as above. Cases 5 and 10 use the
bilinear asymptotic decider after subtracting the contour level from all four samples. This makes
the decision invariant to a common value offset. A scale-relative denominator check handles a
degenerate saddle; an exact remaining tie follows `SaddleTiePolicy.ConnectAbove` or
`SaddleTiePolicy.ConnectBelow`.

Filled contours split every cell along the bottom-left to top-right diagonal, clip triangles with
inclusive lower and upper thresholds, and discard zero-area fragments. Boundary stitching retains
counter-clockwise outer rings and clockwise holes, assigning each hole to the smallest containing
outer ring. Independent edge-table fixtures cover every non-ambiguous marching-square case;
deterministic affine-invariance, area-conservation, ring-winding, and large-coordinate fixtures
exercise the topology policy on both supported Scala platforms.
