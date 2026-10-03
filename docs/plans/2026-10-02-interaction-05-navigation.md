# Interaction 05: region selection and data-aware pan/zoom — phase plan

Mote: `bd-01M1Z9B2AVT76SH1WGF905D5D3`. Builds on Interactions 04 and 06.

## Decisions

- **Navigation re-windows; it never recompiles.** A zoom or pan changes only the panel's ranges,
  axes and grid. `InteractionCompiler.rezoom` re-places retained training data with a new
  `Coord.Zoom` window and reuses the plan's annotated mark grobs, so statistics, scale training
  and target identities are untouched. Proof: a counting statistic and `PhaseClock`, plus scene
  equality with a fresh compile at the same window.
- **Windows keep their kind.** Navigation math runs in panel position space; positions convert
  back to `CoordinateWindow.Numeric`, `Date` or `DateTime` through the trained scale's inverse
  (transformed numeric axes through `Transform.inverse`). A window stays inside the trained
  extent (the bounds); reset returns to the compiled view.
- **Selection is separate from navigation.** Zooming never changes the selection. Region
  selection uses `PickArea` with an explicit `AreaRule`; Shift adds, Alt subtracts.
- **Magnification is not navigation.** Whole-scene magnification scales marks and labels together
  (a CSS size change); data-window navigation keeps text and marks at their size.
- **Scope limits stated, not hidden.** Faceted and flipped plots refuse data-window navigation
  with a typed error in this phase.

## Slices

1. Core: retained training on `InteractionPlan`; `InteractionCompiler.rezoom`; proofs.
2. Shared: `DataWindowNavigator` (zoom about a point, pan, rectangle, bounds, typed windows) and
   region-selection helpers, JVM+JS tests.
3. Browser: mode controls, rubber band and lasso, pan, wheel and pinch zoom with page-scroll
   arbitration, rectangle zoom, reset, Escape and cancellation, magnification.
4. Evidence: Playwright gesture traces on transformed and temporal plots; docs; review.
