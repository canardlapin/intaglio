# Interaction 09: exact aggregate-to-observation membership — phase plan

Mote `bd-01M1Z9B3YV0BAR2R6KJF1TWE13`. Copy to `docs/plans/2026-10-03-interaction-09-membership.md`
as the first execution step.

## Context

Selecting a histogram bin today selects the bin as a target. Its contributing observations can't be
reached, and a linked scatter shows nothing for it. Interaction 09 makes aggregate selection
explicit:
- a bin with 27 members can return exactly those 27 observation keys, while selecting the bin itself
  stays a separate, cheap action;
- counts, representatives, incomplete membership and stale revisions can never pass as a complete
  selection;
- a deferred resolver reports pending, unavailable, failed or complete, and late replies are rejected;
- linked views show selected/total member counts, and emphasis triggers on any, all, or a stated
  fraction of members.

You approved starting 09 before 08, which is formally its prerequisite. Work happens on branch
`interaction-09` and merges to main only after 08 closes. Slices 1–4 touch only core and
interaction files. Slice 5 needs the browser files, which codex-intaglio-help owns during 08.

**What already exists** (map at HEAD `12f0488`):
- `Membership` / `MembershipCapability` (Unavailable, CountOnly, Representative, Partial, Exact,
  Deferred), with `exactKeys(currentRevision)` checking the revision. File:
  `modules/core/shared/src/main/scala/intaglio/InteractionIdentity.scala:273-361`.
- `MembershipRetention`, with CountOnly as the default.
- `attach`, which maps `StatRow.members` through the binding key accessor rather than row equality,
  checks keys against the source `EntityIndex`, and calls `retain`. File:
  `modules/core/shared/src/main/scala/intaglio/InteractionCompiler.scala:203-338`.
- `StatInputPreservation` (OneToOne, AggregateMembers, WholeBatch, Custom) in each `StatContract`
  (`Stats.scala:275-343`).
- Custom-stat membership laws (`laws/ExtensionLaws.scala:141-169`).
- Partition laws (`laws/StatPositionLaws.scala:155-175`).
- Stamps and stale-input rejection (`InteractionState.scala:163, 427-439`).
- `replaceDomain` reconciliation.

Nothing yet uses any of this: there is no member-selection action, no resolver protocol, and no
coverage counts.

## Slices

### 1. Honest membership at compile time (core)
- `retain` derives the capability from the layer stat's `StatInputPreservation`, not from retention
  alone:
  - `OneToOne` and `AggregateMembers` may be Exact under `ExactKeys`.
  - `WholeBatch` (density) becomes CountOnly: a grid point is not composed of the batch.
  - `Custom` follows its declared contract, which `ExtensionLaws` already checks.
  - Today density under `ExactKeys` over-claims every observation as an exact member.
- `InteractionPlan.validateMemberSelection(group)` returns a typed error when a group's targets are
  not Exact, so a behaviour that asks for member selection on a CountOnly plan fails at mount, not
  at click time.
- Tests (`InteractionCompilerSuite`, `InteractionIdentitySuite`):
  - density under ExactKeys is not Exact;
  - histogram, summary, count and ecdf are Exact with keys equal to an oracle;
  - repeated, equal source rows keep distinct keys;
  - faceted bins keep per-panel membership.

### 2. Member selection in the shared reducer (interaction)
- New action `InteractionAction.SelectMembers(target, operation)`. It expands to
  `Membership.exactKeys(plan.sourceRevision)` and applies the result through the existing selection
  path, emitting `SelectionChanged` as usual. Non-Exact or stale membership returns a new
  `StateError.MembershipNotExact(capability)` or `StaleMembership`; it never returns partial keys.
- `InteractionDomain` gains a target→plan `DataRevision` lookup. It already holds the plans.
- `InteractionBehavior.withAggregateSelection(group → AggregateSelection.Target | Members)` declares
  per group what a click, key choose or region sweep does. `HostInput` emits `SelectMembers` for
  Members groups. The default stays Target, so current behaviour is unchanged.
- Tests (`InteractionStateSuite`, `HostInputSuite`):
  - every capability except Exact is refused;
  - a stale revision is refused;
  - Single mode with more than one member is refused;
  - add, subtract and toggle over members;
  - selecting the bin itself is still a target selection.

### 3. Deferred-membership resolver protocol (interaction, effect-free)
- New actions:
  - `RequestMembers(target, requestId, operation)` records a pending request and emits
    `MembershipRequested`, carrying the plan and data revisions.
  - `ResolveMembers(requestId, reply)`, where `reply` is `Pending | Unavailable(reason) |
    Failed(reason) | Complete(keys)`.
- The reducer accepts a reply only for the latest request on that target, at the current
  revisions:
  - `Complete` must have exactly `total` distinct keys, all in the source index and key space,
    and it then applies the selection;
  - anything else becomes `MembershipRejected(reason)`, with the selection untouched;
  - `replaceDomain` cancels pending requests.
- `InteractionController.requestMembers(target, op)(resolve: MembershipRequest => (MembershipReply
  => Unit) => Unit)` is a callback adapter, so hosts bring their own async layer on both
  platforms. Nothing in the reducer has a clock or performs effects.
- Tests:
  - stale-revision, superseded-request and late-reply traces;
  - a short, duplicate or foreign `Complete` is rejected;
  - `Pending`, then `Complete`;
  - `Failed` leaves the selection as it was;
  - a property test that a partial reply never yields a complete selection.

### 4. Partial-selection coverage (interaction)
- `MemberCoverage.of(target, entities)` returns `Known(selected, total)` for Exact membership and
  `Unknown(capability)` otherwise. It never reports 0 of n for CountOnly.
- `EmphasisRule.Any | All | Fraction(p)`, with p in (0, 1] checked.
- `LinkedEmphasis` gains `coverage(...)` and a rule-based `matches` for aggregate targets.
- Tests (`LinkedViewsSuite`):
  - bin coverage under a projected scatter selection matches an independent oracle;
  - rule thresholds at the boundaries;
  - Unknown for non-Exact membership.

### 5. Linked views and browser receipts (after the 08 handoff; browser files)
- `WidgetLink` feeds member selections of aggregate targets into the group selection instead of
  only drawn entities.
- A receiving histogram shows per-bin coverage. The SVG widget displays the counts in the tooltip,
  the live region and the text companion, and applies emphasis by `EmphasisRule`.
- `LinkedFixture` compiles its histogram with `ExactKeys`.
- Browser checks:
  - selecting a bin in Members mode selects exactly its oracle keys in both scatters;
  - a lasso in a scatter shows "k of n" on the matching bins;
  - the Any, All and Fraction rules;
  - target-mode bin selection is unchanged;
  - no echo.
- Canvas receipts follow once 08's Canvas host lands.
- These files belong to codex-intaglio-help during 08. Either I do this slice after they release
  them, or they take it in 08's wake; I'll coordinate by mote.

## Evidence and gates
- Independent membership oracles: bins recomputed from raw x with the documented boundary rule;
  summary groups recomputed from raw grouping keys; adversarial fixtures for repeated equal rows, a
  custom stat with `Custom` preservation, facets, and empty bins.
- JVM and Scala.js run every shared suite. `testAll` (SBT_OPTS=-Xmx6g), `tools/check-compatibility.sh`
  (exact additive records), `tools/check-links.sh`.
- New cases in the sealed `InteractionAction` and `InteractionEvent` traits (and `StateError`) go
  under CHANGELOG Breaking, with a MIGRATION.md note in the style of the `GestureMode.ZoomRectangle`
  entry.
- Docs:
  - `docs/design/interaction.md` status;
  - `docs/interaction-coverage.md` membership column;
  - `docs/browser-widget.md` (slice 5);
  - `modules/interaction/README.md`;
  - the capability matrix gains a row for the analytical extension, marked separately from the
    baseline.
- A fresh-context review subagent after slices 1–4 and again after slice 5. Exact-SHA receipts go
  on the mote. Pending checks stay marked pending.

## Critical files
- `modules/core/shared/src/main/scala/intaglio/InteractionCompiler.scala` (`retain`, validation)
- `modules/core/shared/src/main/scala/intaglio/InteractionIdentity.scala` (membership requests and
  replies)
- `modules/interaction/shared/src/main/scala/intaglio/interaction/InteractionState.scala` (actions,
  events, errors, pending state, domain lookup)
- `modules/interaction/shared/src/main/scala/intaglio/interaction/InteractionController.scala`
  (resolver adapter)
- `modules/interaction/shared/src/main/scala/intaglio/interaction/HostInput.scala`,
  `InteractionBehavior.scala`, `LinkedViews.scala`
- Slice 5: `modules/browser/js/.../WidgetLink.scala`, `SvgWidget.scala`,
  `modules/browser-fixture/.../LinkedFixture.scala`, `tools/check-linked-browser.cjs`

## Verification
1. `sbt coreJVM/test coreJS/test interactionJVM/test interactionJS/test lawsJVM/test` after each slice.
2. `SBT_OPTS=-Xmx6g sbt testAll`, `tools/check-compatibility.sh`, `tools/check-links.sh` before each
   commit on `interaction-09`.
3. Slice 5: `sbt browserFixture/fastLinkJS`, then `node tools/check-linked-browser.cjs …` (plus
   widget and navigation checks), with the browser-ownership audit before and after.
4. Merge to main after 08 closes; rerun all gates at the merge SHA; close the mote with receipts.
