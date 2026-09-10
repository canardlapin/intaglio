# Pattern-fill assurance receipts

These receipts bind the pattern-fill implementation and its compatibility courts to
Intaglio code candidate `b9ea59faacfc5507a7ce71d69ebf75521f11d7d7`.
The evidence files are committed after that code candidate and do not alter its
compiled sources.

## Evidence boundary

| Layer | State | Receipt |
| --- | --- | --- |
| Local Intaglio candidate | passed | `candidate.json` |
| Pinned source consumers | passed | `source-courts.json` |
| Pinned binary consumers | passed | `binary-courts.json` |
| StoryAtlas semantic integration | passed locally | `storyatlas-consumer-court.scala` |
| Formatter | passed | `scalafmtCheckAll` with the checked-in `.scalafmt.conf` |
| Hosted CI | not run | local evidence is not hosted evidence |
| Published artifact | not run | no artifact was published for these courts |

The exact candidate passed `compileAll testAll scalafmtCheckAll` with Oracle JDK 22,
sbt 1.12.9, sbt-scalafmt 2.6.2, and scalafmt 3.11.5. The macOS run set
`JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`; this avoids AppKit registration in the
Java2D tests and does not claim a GUI-display integration court.

The StoryAtlas court compiles the real War-of-the-Ghosts scene and calls the real
`AtlasLowering.lower`. StoryAtlas-owned post-lowering decoration maps four interaction
states to Intaglio's four typed recipes while preserving device polygons, semantic
names, navigation addresses, textual twins, and provenance receipts. No StoryAtlas
repository or hosted issue was modified.

The binary courts first compiled each consumer against Intaglio baseline
`596b398af380079e4b251535230d0bc03cd88c51`, then replaced only the test runtime
classpath with the candidate. Complete consumer-class hashes were identical before
and after each run. The ScalaFIM candidate-runtime run additionally set both Compile
and Test compile tasks to `skip := true`, preventing the runtime classpath change from
triggering consumer recompilation.
