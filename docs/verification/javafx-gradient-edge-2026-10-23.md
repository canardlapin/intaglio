# Cached thin-image gradient edge

Owner Mote: `intaglio-javafx-gradient-edge`. Consumer records:
`ephys4s-provider-intaglio-gradient-edge` and `ephys4s-f-ui-v2-colorbar-redraw`.
Branch `fix/javafx-gradient-edge` starts at the separately retained module-boundary
candidate `d6bb0fc5e557d784e5d3da63515cf801fe182ab2`. Canonical consumer input was
`aad4e0e25bc5c20af8158131d04a757b1e894a1a`.

Repeated actual-window redraws of the saved ephys4s ITC scene can paint yellow
pixels at the gradient bottom despite byte-correct opaque 1×32 source pixels.
Its blue minimum is 109; observed native paint reaches 0..13. Single-frame
replays are insufficient: the failure depends on image reuse, window pulses and
native allocation history. Pure JavaFX `WritableImage`/`drawImage` stress also
reproduces, with no Intaglio drawing calls. Cached CPU source pixels stay exact.
These observations isolate a runtime-sensitive drawing failure; they do not
identify a particular OpenJFX native implementation line or establish a general
failure rate.

`JavaFxCanvasContext` now repeats a constant one-pixel axis to length two in
native storage. All original RGBA cells, scene dimensions and destination
coordinates remain unchanged. Ordinary two-dimensional images are untouched.
The same private materialization path handles patterns. Raster cache accounting
now includes actual expanded native storage plus retained packed source bytes;
the resource budget, eviction and release policy remain in force.

This is a temporary backend workaround. Although the continuous constant axis
is unchanged, native filtering/edge coverage need not be pixel-identical to an
unexpanded JavaFX image. An initial fresh-image diagnostic observed differences
up to 15/14/11 RGB channel levels. No exact pixel-equivalence or scientific
qualification claim follows. Remove the duplication only when an explicitly
qualified newer runtime passes the unprotected reuse baseline repeatedly and
the protected source, drawing, cache and consumer conformance still passes
without it. A passing baseline on one execution is insufficient.

`GradientEdgeSuite` retains the exact source and fractional geometry, full
consumer DeviceScene, independent direct JavaFX comparison, actual window-pulse
reuse, nearest/smooth and explicit transform cases, and source/native pixel and
resource accounting assertions for 1×1, 1×32, 32×1 and ordinary 2D sources.
The direct vendor baseline is printed and archived separately, without
asserting it is fixed by an adapter it bypasses. Its source-byte assertions are
still enforced. Adapter and renderer paths must remain in bounds.

Local qualification: macOS 12.7.6 x86_64, JDK 25.0.4.1+1, software Prism,
Monocle. With both OpenJFX 27+30 and 21.0.5, `javafxJVM/test` passed all 129
tests at the default custom 2× test scale; an additional Headless 1× execution
of `javafxJVM/testOnly intaglio.javafx.GradientEdgeSuite` passed all six tests.
The OpenJFX 27 1× run reported 56/80 invalid unprotected vendor frames; the 21
runs reported none. Protected paths passed in both. Counts are diagnostics,
not controlled performance or platform-wide reliability estimates.

Task forms (set the version while loading the build, not after resolution):

```sh
JAVA_TOOL_OPTIONS=-Dintaglio.javafx.version=27 sbt 'javafxJVM/test'
JAVA_TOOL_OPTIONS=-Dintaglio.javafx.version=21.0.5 sbt 'javafxJVM/test'
```

For the 1× repeat, replace the configured `monocle.platform` JVM option with
`-Dmonocle.platform=Headless` before invoking the six-test suite. Optional
`INTAGLIO_GRADIENT_EVIDENCE` writes lossless PNGs and CSV observations into a
fresh directory. Exact local toolchain/cache invocations and original red
executions are retained in ephys4s
`docs/verification/native-gradient-2026-10-23/`; its runner explicitly supplies
the pinned local JDK and `-Dintaglio.javafx.version`.

Linux, Windows, hosted CI and other hardware/pipelines are unexecuted here.
Canonical publication/admission and affected consumer qualification are separate
gates; this record does not close them.
