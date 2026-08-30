# Pattern-fill assurance receipts

These receipts bind the pattern-fill implementation and its compatibility courts to
Intaglio code candidate `b4cc376379b12dc199f49cc61f3c6c77a2eaa0c9`.
The evidence files are committed after that code candidate and do not alter its
compiled sources.

## Evidence boundary

| Layer | State | Receipt |
| --- | --- | --- |
| Local Intaglio candidate | passed | `candidate.json` |
| Pinned source consumers | passed | `source-courts.json` |
| Pinned binary consumers | passed | `binary-courts.json` |
| StoryAtlas semantic integration | passed locally | `storyatlas-consumer-court.scala` |
| Formatter | not configured | no sbt-scalafmt plugin and no `.scalafmt.conf` |
| Hosted CI | not run | local evidence is not hosted evidence |
| Published artifact | not run | no artifact was published for these courts |

The formatter state is deliberately not reported as a pass. Invoking
`scalafmtCheckAll` at the candidate produced `Not a valid command: scalafmtCheckAll`.
Adding a no-op alias or unrelated formatter infrastructure would not prove that the
candidate was formatted.

The StoryAtlas court compiles the real War-of-the-Ghosts scene and calls the real
`AtlasLowering.lower`. StoryAtlas-owned post-lowering decoration maps four interaction
states to Intaglio's four typed recipes while preserving device polygons, semantic
names, navigation addresses, textual twins, and provenance receipts. No StoryAtlas
repository or hosted issue was modified.

The binary courts first compiled each consumer against Intaglio baseline
`596b398af380079e4b251535230d0bc03cd88c51`, then replaced only the test runtime
classpath with the candidate. Complete consumer-class hashes were identical before
and after each run.
