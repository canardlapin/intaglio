# Interaction coverage

Every built-in geom and statistic declares how it is divided into interactive targets. The table
below is rendered from `InteractionCoverage.entries`, and `InteractionCoverageSuite` compiles an
interactive plot for every row and checks that the declared granularity produces exactly the
expected number of targets, so the table cannot drift from the compiler.

```scala mdoc:passthrough
import intaglio.interaction.*

println("| Component | Targets | Membership | Picked by |")
println("| --- | --- | --- | --- |")
InteractionCoverage.entries.foreach { entry =>
  val targets = entry.granularity match
    case TargetGranularity.PerRow           => "one per row"
    case TargetGranularity.PerGroup(n)      => s"one per group of at least $n rows"
    case TargetGranularity.PerStatisticRow  => "one per statistical output"
    case TargetGranularity.PerCell          => "one per grid cell"
    case TargetGranularity.WholeLayer       => "one for the layer"
    case TargetGranularity.PerAnnotation    => "one per annotation"
  println(s"| `${entry.component}` | $targets | ${entry.membership} | ${entry.picking} |")
}
```

Aggregate targets (histogram bins, summaries) retain only a member count unless the compilation
asks for `MembershipRetention.ExactKeys` (members kept) or `MembershipRetention.Deferred` (the
application resolves them on request); a count is never reported as a member set. Membership follows
the statistic's declared contract: only one-to-one and aggregate-member statistics can offer exact
members, a density grid point's membership is unavailable, and a free-text `Custom` contract keeps
only its count. `InteractionAction.SelectMembers` selects exact members; `MemberCoverage` counts how
many a linked selection covers.

## Plot parts

Components that are not marks are typed targets too. `PlotParts.of(trained)` lists them with the
value each stands for, and `PartPicking` finds the part under a device point:

| Part | Typed value |
| --- | --- |
| `PlotTitle`, `PlotSubtitle` | the text |
| `LegendEntry` | legend name and title, entry index, the scale level's label |
| `LegendTitle` | legend name and title |
| `Colorbar` | colorbar name and title |
| `Axis` | side and title |
| `FacetStrip` | row and column index and labels |
| `Annotation` | the reference-line layer's index |

A part is listed only when its grobs are present in the scene; a guide that was turned off is not
guessed at. Faceted plots list a repeated axis or annotation once, under every name it is drawn as.
