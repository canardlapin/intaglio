# Source consumer boundary checks

At aad4e0e25bc5c20af8158131d04a757b1e894a1a, launching `coreJVM/test` through an
ephys4s ProjectRef ran non-forked tests in the consumer JVM. ModuleBoundarySuite
searched from user.dir and consequently inspected ephys4s build.sbt and sources.
Five boundary tests failed; 724 other core checks passed. No guard was skipped.

The suite now searches from its own test class code location. That location
belongs to the owning source checkout for both standalone and ProjectRef use.
An additional regression verifies that the discovered tree owns the suite and
Intaglio core build declaration. The change does not set global user.dir or add
a production dependency or runtime override.

Qualification on macOS x86_64, JDK 25.0.4.1+1, OpenJFX 27+30:

| Invocation | Result |
| --- | --- |
| Provider root: coreJVM/test | 730 passed, zero failed |
| Provider root: canvasJS/test | 23 passed, zero failed |
| Provider root: javafxJVM/test, intaglio.javafx.version=27 | 123 passed, zero failed |
| coreJVM/Test/scalafmt; coreJVM/Test/scalafmtCheck | one changed source formatted; check passed |
| ephys4s root, explicit source override, coreJVM/test | 730 passed, zero failed |

The consumer reproduction uses ephys4s tools/sbt.py with
`-Dephys4s.intaglio.build=$EPHYS4S_ROOT/.providers/intaglio`, followed by
`{file:/Users/bbuchsbaum/code/ephys4s/.providers/intaglio/}coreJVM/test`.
The URI is an explicit development override, not an admitted dependency.
The original failure and complete commands/outcomes are preserved in ephys4s
docs/verification/native-tranche-2026-10-21/providers/native-2.log,
intaglio-owner-1.log, intaglio-format-1.log and intaglio-consumer-fix-1.log.
The untouched canonical revision also passed all 729/23/123 checks when run from
its own root (intaglio-canonical-1.log), separating the harness defect from
runtime rendering correctness. Other host platforms were not executed here.

Tracking: provider Mote `intaglio-test-source-root`; consumer Mote
`ephys4s-provider-intaglio-boundary-root`. Canonical integration and the consumer
pin update remain required after publication authorization.
