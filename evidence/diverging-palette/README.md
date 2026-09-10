# Colour palette receipts

These receipts record how the colour claims made by `DivergingPalette`, `Oklab`, `ColorVision`, and
`DiscretePalette.okabeIto` were obtained. Every figure below is asserted to within 0.05 on each test
run by `DivergingPaletteEvidenceSuite` and `DiscretePaletteEvidenceSuite`, and the observer model
itself is pinned against published reference values by `ColorVisionSuite`. This file describes the
method; it is not the only place the figures live.

The one claim not re-checked per build is the exhaustive Oklab round trip, pinned below to the run
that produced it.

## Evidence boundary

| Layer | State | Receipt |
| --- | --- | --- |
| Dichromat simulation against published reference values | passed | `ColorVisionSuite`, re-run every build |
| Diverging arm separation under all four observers | passed | `DivergingPaletteEvidenceSuite`, re-run every build |
| Lightness monotonicity along each arm | passed | `DivergingPaletteEvidenceSuite`, re-run every build |
| Qualitative prefix separation table and its optimality | passed | `DiscretePaletteEvidenceSuite`, re-run every build |
| Default theme palette separation | passed (measured, not improved) | `DiscretePaletteEvidenceSuite`, re-run every build |
| JVM and Scala.js byte identity for the colour path | passed | `ColorDeterminismSuite`, re-run every build on both platforms |
| Exhaustive Oklab round trip over all 16 777 216 sRGB colours | passed once on each platform | `OklabRoundTripCourt`, run on demand |
| Anomalous trichromacy | not established | only dichromacy is modelled; see below |
| Observer study | not run | every figure here is a model prediction, not a measurement of readers |
| Print and projector reproduction | not run | figures are for an sRGB display |

## The observer model

`ColorVision` implements Brettel, Viénot and Mollon (1997), *Computerized simulation of color
appearance for dichromats*, JOSA A 14(10):2647-2655. A dichromat is missing one cone class, so the
colours they can distinguish collapse onto a surface: two half-planes sharing the neutral axis, each
anchored on a monochromatic stimulus whose hue a dichromat and a trichromat agree on — 475 and
575 nm for protanopia and deuteranopia, 485 and 660 nm for tritanopia. A colour is projected onto
whichever half-plane its own side of the neutral axis selects, along the axis of the missing cone
(the paper's Eqs. 6-11, p. 2650).

Everything between linear RGB and the projection is linear, so the constants shipped are the
precomputed per-half-plane matrices and separation normals published by
[libDaltonLens](https://github.com/DaltonLens/libDaltonLens) (`libDaltonLens.c`, public domain),
which pair Brettel's geometry with the Smith and Pokorny (1975) cone fundamentals over Judd-Vos
corrected XYZ, and take the neutral axis through RGB white rather than the equal-energy white of the
paper so that white maps to white exactly.

`ColorVisionSuite` checks the implementation three ways: the simulated sRGB primaries and
secondaries reproduce libDaltonLens's published golden values exactly under all three deficiencies;
every row of every half-plane matrix sums to one and every separation normal is orthogonal to
`(1,1,1)`, so greys are fixed points and both half-planes agree on the neutral axis; and every grey
is checked to be a fixed point directly.

### What was rejected

The single-plane simplification of Viénot, Brettel and Mollon (1999) is not used. It is a reasonable
approximation for protanopia and deuteranopia — though not interchangeable with Brettel even there —
and a poor one for tritanopia, where the two half-planes are the entire point.

The widely copied "tritanopia matrix" `[[1,0,0],[0,1,0],[-0.395913, 0.801109, 0]]` is worse than an
approximation: it keeps the *red-green* projection plane and merely swaps the projection axis, so
folded into RGB its first two rows are identical, which is the algebraic signature of a red-green
simulation. It leaves blue and yellow — the pair a tritanope actually confuses — completely
unchanged and desaturates red instead. `ColorVisionSuite` pins the opposite behaviour: under this
implementation blue moves CIE76 119.8 and yellow 97.9. Any implementation that leaves them fixed is
simulating the wrong deficiency.

### What is not modelled

Only dichromacy, the severe form. Anomalous trichromacy — protanomaly, deuteranomaly, tritanomaly,
together far more common than the dichromacies — retains partial discrimination and has no model
here. These figures are the worst case, not the typical one. Achromatopsia is likewise not modelled;
the lightness-monotonicity assertions are what speak to greyscale reproduction.

## Metric

Separation is CIE76 — Euclidean distance in CIELAB under a D65 white point — computed from the sRGB
bytes the palette actually emits, so eight-bit quantization is inside the measurement rather than
outside it. CIE76 rather than CIEDE2000 because every claim here is a floor, "these are at least
this far apart", and CIE76 is the conservative closed-form choice for a floor.

## Diverging: `DivergingPalette.BlueRust`

`#2166AC` negative, `#F5F0E6` neutral, `#B4552D` positive; each arm interpolated from the neutral
outward in Oklab.

| Observer | Smallest arm-vs-arm CIE76 over matched magnitudes 0.25 to 1 |
| --- | --- |
| Normal vision | 23.3 |
| Protanopia | 19.1 |
| Deuteranopia | 22.4 |
| Tritanopia | 18.8 |

The floor sits at magnitude 0.25 in every model and grows steadily outward from there, but not
strictly: eight-bit quantization produces occasional dips of a hundredth of a unit along the way.
The figures above are therefore minima over the whole range, which is what the suite computes, not
the values at 0.25. Below 0.25 the two arms converge on the neutral by design, because a value near
zero is supposed to look like a value near zero.

The same ramp ending at `#B2182B`, a saturated red, falls to 16.1 under protanopia. Red is the
better of the two under tritanopia — 23.5 against 18.8 — so the choice is made on the worst observer
rather than a favourite one: rust's worst case is 18.8, red's is 16.1.
`DivergingPaletteEvidenceSuite` asserts both sides of that trade so the justification cannot quietly
stop being true.

Rendered CIE lightness: 94.9 at the neutral, 42.5 at the negative endpoint, 47.5 at the positive
endpoint. Sampled at 201 steps per arm, lightness never increases outward; eight-bit quantization
produces occasional ties, and the assertion permits ties and refuses reversals. Adjacent steps of a
33-sample ramp stay at least CIE76 3.3 apart under all four observers, so magnitude remains legible
rather than banding into plateaus.

## Interpolation space

Interpolating `#0000FF` to `#FFFF00` through the stored sRGB bytes yields `#808080` at the midpoint:
chroma 0.00001, a grey dead zone in the middle of the ramp. The same interpolation through Oklab
yields chroma 24.2. This is the concrete reason `Palette.oklabGradient` exists beside
`Palette.gradient`.

For the shipped diverging endpoints the two spaces differ by at most CIE76 2.34, sampled at 1000
steps per arm, because those endpoints are already moderate in chroma. The Oklab path is not chosen
for what it does to this palette; it is chosen so that a consumer who names saturated endpoints does
not silently get a ramp with a hole in it.

## Cross-platform byte identity

`ColorDeterminismSuite` folds every channel byte the colour path emits into one order-sensitive
SplitMix64 digest and pins it. Because the suite is a shared source, the same digest is recomputed
on the JVM and on Scala.js on every run, and a single byte moving anywhere changes it. It covers
2001 samples of the shipped diverging ramp, 1005 samples across five `Palette.oklabGradient` pairs
including a translucent one, and the Oklab round trip over a 29x29x29 grid — 24 389 colours.

This matters because `math.cbrt` and `math.pow` are implementation-approximated in JavaScript, and
`Oklab` puts both between a caller's colour and the bytes a backend receives. The digest asserts
identity of the *bytes*, not of the intermediate coordinates; the contract is the colour that
reaches the device.

Not established: byte identity between platforms for arbitrary colours outside that sample. The
round trip is now exhaustive on both platforms, which is a stronger statement about `fromRgba` and
`toRgba` than the digest makes — but a colour part way along a ramp is a `mix` result rather than a
round trip, so neither claim substitutes for the other.

## Oklab round trip

`Oklab.toRgba(Oklab.fromRgba(color))` returns `color` unchanged for every one of the 16 777 216 sRGB
colours. Run:

```
sbt "coreJVM/Test/runMain intaglio.oklabRoundTripCourt"
```

Scala.js needs the linker pointed at the main, which the build deliberately does not do for the
test configuration, so the setting is applied for the one run rather than committed:

```
sbt 'set coreJS/Test/scalaJSUseTestModuleInitializer := false' \
    'set coreJS/Test/scalaJSUseMainModuleInitializer := true' \
    'set coreJS/Test/mainClass := Some("intaglio.oklabRoundTripCourt")' \
    'coreJS/Test/run'
```

Run on both platforms on 2026-09-10, with Homebrew Java 25.0.1, sbt 1.12.9, Scala 3.3.8, Node
through the build's `NodeJSEnv`, on macOS arm64. Identical output from each:

```
sampled colors:        16777216
round trips that moved: 0
worst channel error:    0
```

The court lives in the shared test sources so both runs are possible at all and neither can drift
from the API. The sweep depends only on `Oklab.fromRgba` and `Oklab.toRgba`, so it needs re-running
when either conversion or the sRGB transfer function changes, not on every commit. `ColorSuite`
walks a strided grid of the same sweep on every build, on both platforms.

## Qualitative: `DiscretePalette.okabeIto`

The eight colours of Okabe and Ito (2008), reordered so that every prefix is the best set of its
size. `DiscretePalette` assigns by index, so a four-series plot takes the first four colours; an
ordering that is good only at full length is not good where it is used.

The order was chosen by exhaustive search over all 40 320 permutations, maximising the prefix floors
lexicographically from `n = 2` upward, subject to two constraints: black leads, because a first
series is conventionally black; and every colour before the sixth holds `L* <= 80`, so a thin series
stroke keeps lightness contrast against a light panel. Yellow is sixth for that reason and no other.
`DiscretePaletteEvidenceSuite` re-runs that search and asserts the shipped order is a maximum.

| Prefix | Smallest pairwise CIE76, worst of the four observers |
| --- | --- |
| 2 | 79.2 |
| 3 | 66.1 |
| 4 | 23.5 |
| 5 | 19.4 |
| 6 | 17.0 |
| 7 | 11.2 |
| 8 | 10.9 |

Every prefix clears the floor of 10 that `ColorSeparation.SeriesFloor` names, but seven and eight
clear it by about a unit rather than by a margin. The palette is published under the name of its
source rather than as a promise: strong through six series, and past that the honest move is
faceting or direct labelling.

## The default theme palette

The default is now the first six of `DiscretePalette.okabeItoColors`. It was the first six tab10
colours. Both are measured and both are pinned, so the reason for the change stays a number rather
than a memory.

| Prefix | current default (okabeIto) | replaced default (tab10) |
| --- | --- | --- |
| 2 | 79.2 | 85.3 |
| 3 | 66.1 | 5.6 |
| 4 | 23.5 | 5.6 |
| 5 | 19.4 | 5.6 |
| 6 | 17.0 | 5.6 |

tab10's limiting pairs were its orange against its green under protanopia, at 5.6, and its blue
against its purple under deuteranopia, at 7.2 — both below `ColorSeparation.SeriesFloor`, so a
three-or-more-series discrete colour scale on the old default would now emit
`AccessibilityDiagnostic.IndistinguishablePalette`. The current default clears the floor through all
six.

The change moved three of the ten committed gallery plates —
`docs/gallery/scatter-by-condition.svg`, `line-series.svg`, and `composed-figure.svg`, the three
that use a theme-default discrete colour scale. Each was re-rendered and reviewed at native size.
The java2d golden images did not move: those fixtures name their colours explicitly.

Not established: whether the new default is preferable *aesthetically*. It is preferable
*measurably*, which is the only claim made.
