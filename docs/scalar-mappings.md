# Inspectable scalar mappings

Use a `ScalarMapping` when a renderer needs the scale, visibility rules, and
colors as data. An arbitrary `Colorizer[Double]` remains useful for sample
coloring, but it does not provide enough information to construct a shader or
a trustworthy legend.

```scala
import intaglio.*

val blue = Rgba32.unsafe(0, 0, 200)
val white = Rgba32.unsafe(200, 200, 200)
val red = Rgba32.unsafe(200, 0, 0)
val scale = ScalarScale.split(
  DisplayWindow.unsafe(-4, 8), center = 0, lowerEnd = -1, upperStart = 2,
  lower = ScalarRamp.linear(blue, white),
  upper = ScalarRamp.linear(white, red)
).toOption.get
val mapping = ScalarMapping(scale)

mapping.evaluate(-2.5) // visible, halfway along the lower ramp
mapping.evaluate(0)    // HiddenScaleGap
mapping.evaluate(5)    // visible, halfway along the upper ramp
```

`ScalarScale.sequential` uses one ramp over the whole window. `diverging` gives
each side of an explicit center its own ramp; the ramps must share their center
color. `split` assigns the ramps to two nonempty tails and omits their open gap.
The center and cutoffs are absolute scalar values, so asymmetric limits are
supported without assuming symmetry about zero.

Ramps contain ordered stops from 0 to 1 and interpolate stored sRGB bytes,
including alpha. Visibility is a separate rule: the inside or outside of an
interval, with lower, upper, both, or neither endpoint included; everything at or
above a cutoff, or at or below one; or a band, which shows the inside of an outer
interval except the inside of an inner one. It never changes the scaling
intervals. A split scale always omits its gap, even when a separate visibility
threshold is disabled.

Classification tests the original sample in this order:

1. Nonfinite values use the invalid color.
2. Values excluded by visibility use the hidden color.
3. Values outside the color limits clamp or hide according to `outOfRange`.
4. Values in an omitted split interval use the hidden color.
5. Remaining values map through their ramp segment.

`evaluate` returns a state and an optional segment coordinate as well as a color.
Hidden, invalid, and saturated samples stay distinguishable even if their colors
are identical. `color` avoids allocating that richer result in sample loops.

Resolve presentation changes with `mapping.resolve(window, threshold)`. A window
change preserves the absolute center and split cutoffs and returns `Left` if the
new window cannot contain them. A threshold override accepts every
`DisplayThreshold` mode and shows exactly the finite values that threshold does
not hide. Use `effective.colorizer` only after this checked resolution; that
adapter deliberately does not advertise unchecked window/threshold callbacks.

`ScalarMapping.fromLegacy` describes a `ScalarColorizer`, including its invalid
color and its threshold, in any mode. `inspect` recognizes that colorizer and the new
mapping adapter; other callbacks return `None`. The window normalizer now handles
finite limits whose difference overflows, so a symmetric extreme window maps
zero to its midpoint in both APIs.

`canonicalKey` is a versioned, complete descriptor identity, using IEEE-754 bit
strings for numbers and normalized signed zero. It is identical on JVM and JS.
It can guard caches and scene restoration; it is not a cryptographic digest or
a replacement for a document codec. The exposed scale segments and ramp stops
provide the data for future fragment evaluation and legends. This change does
not itself implement shaders, lookup textures, or automatic legend rendering.
