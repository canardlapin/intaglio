# 0008. Signed colour is a primitive, and mixing happens in Oklab

Status: Accepted
Date: 2026-09-08

## Context

Intaglio shipped `Palette.gradient(from, to)`, a two-point interpolation of the stored sRGB channel
bytes, and two named raster ramps, `ColorRamp.Grayscale` and `ColorRamp.Heat`. All three are
sequential: one end means "least", the other means "most". Signed data — a contrast, a difference,
a z-score, a log ratio, anything centred on a zero that means something — had no supported encoding
at all.

Consumers therefore built their own. A downstream neuroimaging application built one diverging ramp
for its brain overlay, out of two `ColorRamp`s around a hand-picked neutral selected on the sign of
the value, and a second one inline at a plot call site as
`t => if t <= 0.5 then gradient(negative, ivory)(t * 2) else gradient(ivory, positive)((t - 0.5) * 2)`.
The two disagreed about which end was positive. Rust meant `+1` in the overlay and negative in the
plot, and the two rusts were CIE76 10.2 apart — the same colour to a reader, opposite meanings, both
on screen at once.

That is a misreading hazard in a scientific tool, and the library caused it. The application made
two reasonable local choices; nothing existed for the two call sites to share, so nothing made them
agree.

## Decision

**The signed domain is the type.** `DivergingPalette` holds `negative`, `neutral`, and `positive`
and answers on `[-1, +1]`: `-1` is the negative endpoint, `0` is exactly the neutral, `+1` is the
positive endpoint. The sign of the argument names the arm. There is no normalized `[0, 1]` face on
which a caller could adopt the opposite convention by accident, and `unitPalette` — which exists
because `ContinuousScale` speaks in unit fractions — is documented as the wiring adaptor rather than
as the way to address the ramp.

**One interpolation feeds both output forms.** `pixel` is `color` packed through `Rgba32.fromRgba`.
A raster overlay and a scene mark showing the same value are the same colour by construction. The
original defect was two call sites in two representations; making them one function is the fix.

**The library owns the midpoint.** `DivergingColorizer` takes a magnitude rather than a window, so
zero is on the neutral by construction, and re-windowing it widens to the enclosing symmetric window
instead of moving the middle. `ContinuousScale.diverging(name, limit, palette)` does the same for
the scene path: a symmetric domain with `ScaleTraining.Fixed`, so a later plot-wide training pass
cannot widen one side and slide the neutral onto the data's midpoint.

**Mixing happens in Oklab.** `Oklab` is a checked perceptual colour type with `fromRgba`, `toRgba`,
and `mix`; `DivergingPalette` interpolates each arm outward from the neutral in it, and
`Palette.oklabGradient` is the sequential ramp on the same primitive. Interpolating the stored sRGB
bytes between hue-distant endpoints walks through whatever colour the encoding happens to name in
between, which is a near-neutral grey: `#0000FF` to `#FFFF00` passes through `#808080`, chroma
0.00001. A ramp whose whole job is to carry magnitude cannot have a dead zone in the middle.

**A named default is chosen against a measurement, and the measurement ships.** `ColorVision`
simulates dichromat vision with the two-half-plane construction of Brettel, Viénot and Mollon
(1997), and `ColorSeparation` reports CIE76 distance as a named observer sees it.
`DivergingPalette.BlueRust` and `DiscretePalette.okabeIto` are published with per-observer figures
that the evidence suites assert on every run, through that same public API rather than through a
private copy of it. A colour claim in this repository is a number a test checks, or it is not made.

**The compiler reports what a reader could not separate.** `AccessibilityDiagnostic` gained
`IndistinguishablePalette`, reported for a discrete colour or fill scale whose closest pair falls
below `ColorSeparation.SeriesFloor` for some observer, naming the observer, the two levels, and the
separation. Exact RGBA collision keeps its own diagnostic; this one catches the larger class where
the bytes differ and the reader cannot.

**The default is the measured palette.** `Theme.defaultPalettes.discrete` is the first six of
`DiscretePalette.okabeItoColors`. A default that a reader with common colour vision cannot follow is
a defect in the library, not a matter of taste, and leaving it to callers to opt out of would make
the safe path the one you have to know about.

**No rainbow, jet, or turbo ramp, ever.** Their lightness is not monotone, so a reader sees
boundaries the data does not have; their perceptual step size is uneven, so equal differences look
unequal; and nothing about them recovers a sign.

## Consequences

`Rgba` and `Rgba32` now have a stated colour space. They carry display bytes and no profile, and
`DisplayBlendMode.composite` deliberately works in the stored channel space for that reason. Oklab
conversion cannot be space-agnostic, so it declares sRGB under IEC 61966-2-1 with a D65 white point.
That declaration is a new commitment, narrow on purpose: a perceptual mixing space, not colour
management. There is no chromatic adaptation, no alternative white point, and no ICC pipeline.

`Palette.gradient` is unchanged, and no existing plot changes colour. The new ramps are opt-in and
the default theme palette is untouched, so the committed gallery plates and the visual regression
baselines are unaffected. The cost is two ways to interpolate two colours; the doc comment on
`gradient` names which one is right for a magnitude ramp and why.

The colour path now runs `math.cbrt` and `math.pow` on the way to a pixel, where the previous ramps
were integer arithmetic. Endpoint colours are converted once per palette rather than once per
sample, and the endpoints and the neutral are returned as themselves rather than round-tripped, so
the transcendental calls fall where the interpolation actually is. A diverging raster overlay is
still more expensive per pixel than a grayscale one.

Cross-platform determinism rests on a narrower proof than the rest of the library. `math.cbrt` and
`math.pow` are implementation-approximated in JavaScript, so the shared suites pin exact bytes for
the shipped ramp and walk a strided grid of the round trip on both platforms rather than asserting
byte-identity for arbitrary colours. The exhaustive sweep — all 16 777 216 sRGB colours round trip
unchanged — is a JVM court run on demand, recorded in `evidence/diverging-palette/README.md`.

Tritanopia is claimed, because Brettel's two-half-plane construction is what is implemented. It is
the observer under which blue-to-rust is weakest — 18.8 against 19.1 for protanopia and 22.4 for
deuteranopia — which is exactly why it had to be modelled rather than assumed.

Anomalous trichromacy is not claimed. Protanomaly, deuteranomaly and tritanomaly are together far
more common than the dichromacies, retain partial discrimination, and have no model here. The
figures are the worst case, not the typical one, and the documentation says so wherever it states
one.

Two new `GraphicsError` cases and one new `DisplayError` case exist for colour, and the additive API
review in `compatibility/interaction-additions.txt` grew a third supported problem kind:
a Scala 3 `enum` case compiles to a class, a companion, and a static field, and the review could not
express the field before.

## Alternatives considered

**A `diverging` constructor returning a plain `Palette[Rgba]` on `[0, 1]`.** Rejected: it is the
shape the consumers already wrote by hand. The midpoint convention lives in the caller's head, and
two callers can hold different ones — which is the bug.

**Generalize `ColorRamp` into a trait so `ScalarColorizer` could take a diverging ramp.** Rejected:
`ColorRamp` is a two-point `final case class` in the published API, and widening it is a breaking
change bought to avoid one small new colorizer. `DivergingColorizer` also needs a different
invariant — a symmetric window — which a shared colorizer would have had to weaken.

**Interpolate in CIELAB.** Rejected: CIELAB's blue hue non-linearity is exactly the region a
blue-neutral ramp spends half its length in, and Oklab was built to fix it. CIELAB stays in the test
suites, where CIE76 distance is the conservative closed-form choice for asserting a floor.

**Keep colour-vision simulation in the test suites.** Rejected. It was the first shape of this
change, and it made the library assert things a consumer could not check. A consumer with brand
colours has the same question the evidence suites have, and answering it needs `ColorVision` and
`ColorSeparation` in the published API — at which point the diagnostic is nearly free, and the
evidence suites exercise shipped code instead of a private copy that could drift from it.

**Use the Viénot, Brettel and Mollon (1999) single-plane matrices.** Rejected. They are a reasonable
approximation for protanopia and deuteranopia and a poor one for tritanopia, where the two
half-planes are the whole point. The widely copied "tritanopia matrix" derived from that
simplification keeps the red-green projection plane, so it leaves blue and yellow untouched and
desaturates red — it simulates the wrong deficiency under the right name. An early draft of this
change used it, and the resulting figures were wrong; `ColorVisionSuite` now pins the correct
behaviour so the mistake cannot come back.

**Keep the previous default theme palette and let the diagnostic report it.** Rejected. It was the
first plan, on the grounds that changing the default moves committed plates. The measurement is what
settled it: the first six tab10 colours fall to CIE76 5.6 under protanopia from three series on,
which is below the floor the diagnostic reports at, so keeping them would have meant shipping a
diagnostic whose first finding is the library's own default. Three of the ten gallery plates moved
and were reviewed; the golden images did not move at all, because those fixtures name their colours.
The previous six are named in the changelog for anyone who needs the old look back.
