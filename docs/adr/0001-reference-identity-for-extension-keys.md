# 0001. Reference identity for extension keys

Status: Accepted
Date: 2026-09-03

## Context

A grammar of graphics has an open set of aesthetic channels. ggplot2 keeps them in a string-keyed
list, which makes extension free but gives up every type: the value behind `"colour"` is whatever
the layer put there, and a typo is a silently absent mapping.

Intaglio's mappings are heterogeneous by construction — `Aesthetic.X` carries `Double`,
`Aesthetic.Shape` carries `PointShape`, `Aesthetic.Color` carries `Rgba` — and they are stored
together in one `AestheticMap[Row]` behind `AesSpec[Row]`. Recovering `A` from that storage requires
a witness that the requested key is the key used at insertion. A closed `enum` would supply that
witness through exhaustive matching, but then no ecosystem package could add a channel without a
release of intaglio-core. A `String` key supplies no witness at all.

The same question arises for names, and there the answer is different. A `GraphicsName` labels a
grob or scale for a renderer to emit and for a test to find; two independently constructed names
with the same text must be the same name, or golden output and marker assertions become
order-dependent. An accessible plot node needs a third thing again: an identifier that survives into
an SVG `id` attribute unchanged.

## Decision

`Aesthetic[A]` is a `final class` with a private constructor and no `equals`/`hashCode` override, so
it uses reference identity. `Aesthetic[A]("label")` returns `Either[GraphicsError, Aesthetic[A]]`;
the ecosystem author retains the returned value and uses that same value for every insertion and
lookup. Two keys created independently under the same label are distinct keys and do not collide in
one map. `AestheticMap` compares keys with `eq`, and `Aesthetic.builtInIndex` is the only thing that
gives the twenty-one core keys a stable order ahead of extension keys, which follow in insertion
order.

`GraphicsName` is an `opaque type GraphicsName = String` with a trimming, blank-rejecting smart
constructor. It is a value: two names with the same text are the same name, and the underlying
`String` erasure costs nothing at a renderer boundary.

`SemanticId` is a `final class` with explicit structural `equals`/`hashCode` over its `value`, and a
constructor restricted to the portable XML/HTML identifier subset — an ASCII letter or underscore
followed by letters, digits, underscores, hyphens, or periods. Structural equality is required
because `PlotSemantics.build` rejects duplicate IDs within one plot, and the restricted alphabet is
required because SVG backends emit the value verbatim rather than rewriting it.

Both live in the public API for extension authors: an aesthetic key is defined beside the code that
uses it, not registered with the core.

## Consequences

An ecosystem package adds a typed channel with one `val` and no coordination with this repository.
The key's type parameter is enforced at every call site, and a mapping stored under one package's
key cannot be read by another package's key that happens to share a label — which is exactly the
isolation `AestheticLaws` checks with its `sameLabelKey` argument.

The cost is that the key must be shared as a value. A caller that writes
`Aesthetic.unsafe[Double]("confidence")` twice has two keys, and the second lookup returns `None`.
This is a real footgun and the reason `Aesthetic`'s scaladoc says to retain the returned key. It is
also why `AestheticLaws` exists and why the guide in `docs/extending/` shows the key as a top-level
`val`.

Reference identity also means an `Aesthetic` cannot be serialized and reconstructed, cannot be a map
key across a process boundary, and is not usable as a stable identifier in a file format. Anything
that must cross such a boundary uses the key's `label` (a `String`) — which is what
`ScaleDeclaration.aesthetic`, `TrainedScale.aesthetic`, and `ScaleSemantics.aesthetic` publish.

Because `Aesthetic` is a class rather than an enum, `Aesthetic.values` is a compatibility view over
`builtIns` rather than an exhaustive enumeration. Code that once matched exhaustively over an enum
of aesthetics must instead discover keys from the mappings that use them.

## Alternatives considered

**A closed `enum Aesthetic[A]`.** Gives exhaustive matching and free structural equality, and makes
the typed lookup a compiler-checked match. Rejected because it puts every ecosystem channel behind a
core release, which contradicts the same openness already granted to `Stat`, `Geom`, `Coord`, and
`PlotRecipe`.

**String keys with a runtime type tag.** Would allow reconstruction across process boundaries and
would make duplicate definitions harmless. Rejected because the cast at recovery is unchecked in
exactly the case that matters — two packages choosing the same label for different types — and
because it reintroduces the stringly-typed mapping the rest of the design removes.

**A mutable registry keyed by label.** Would deduplicate keys automatically. Rejected for the same
reason there is no stat or recipe registry: it makes the meaning of a plot depend on initialization
order, and it cannot be made deterministic across the JVM and Scala.js.

**Reference identity for `GraphicsName` and `SemanticId` too.** Rejected: both are consumed as text
by renderers and tests. A reference-identity name would make `containsMarker` in
`RendererHarness` untestable, and a reference-identity semantic ID could not be compared against an
`id` attribute read back out of an SVG document.
