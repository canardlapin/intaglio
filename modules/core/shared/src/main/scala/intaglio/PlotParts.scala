package intaglio.interaction

import intaglio.*

/** A non-mark part of a compiled plot that a reader can point at, with the typed value it stands
  * for. Marks are addressed through [[TargetInfo]]; parts complete the set of plot components an
  * interactive host exposes.
  */
enum PlotPart:
  /** One entry of a keyed legend: `label` is the scale level's display text, `index` its position.
    */
  case LegendEntry(legend: String, title: Option[String], index: Int, label: String)
  case LegendTitle(legend: String, title: String)

  /** A continuous colour scale's bar; `domain` is the data range the bar spans, when known. */
  case Colorbar(name: String, title: Option[String])
  case Axis(side: AxisSide, title: Option[String])
  case FacetStrip(row: Int, column: Int, rowLabel: Option[String], columnLabel: Option[String])
  case PlotTitle(text: String)
  case PlotSubtitle(text: String)

  /** A reference-line annotation layer (`hline`/`vline`), by the plot's layer index. */
  case Annotation(layerIndex: Int)

  /** A short plain-text description for accessible names and the text companion. */
  def describe: String =
    this match
      case LegendEntry(_, title, _, label) => title.fold(label)(t => s"$t: $label")
      case LegendTitle(_, title)           => s"legend $title"
      case Colorbar(name, title)           => s"colour scale ${title.getOrElse(name)}"
      case Axis(side, title) => s"${side.toString.toLowerCase} axis${title.fold("")(t => s" $t")}"
      case FacetStrip(_, _, rows, columns) => (rows.toVector ++ columns.toVector).mkString(", ")
      case PlotTitle(text)                 => text
      case PlotSubtitle(text)              => text
      case Annotation(index)               => s"annotation layer $index"

/** One part and the scene names its grobs carry. A host picks a part by any of its names. */
final case class PartTarget(names: Vector[GraphicsName], part: PlotPart)

object PlotParts:
  /** Every part of `plot` whose grobs are present in its scene, in a stable reading order: titles,
    * facet strips, axes, legends and colorbars, then annotations. A part whose expected grob is
    * absent (a guide turned off, an unnamed legend) is omitted rather than guessed.
    */
  def of(plot: TrainedPlot): Vector[PartTarget] =
    val present = names(plot.scene.grobs)
    def keep(part: PlotPart, candidates: Vector[GraphicsName]): Option[PartTarget] =
      val found = candidates.filter(present)
      Option.when(found.nonEmpty)(PartTarget(found, part))

    // Title and subtitle text is read from the lowered label grobs that carry the region names:
    // the first text run named for the region, or inside a group named for it.
    def text(region: GraphicsName): Option[String] =
      def find(grob: Grob, inside: Boolean): Option[String] =
        val here = inside || grob.name.contains(region)
        grob match
          case text: Grob.Text if here   => Some(text.label)
          case group: Grob.Group         => group.children.view.flatMap(find(_, here)).headOption
          case annotated: Grob.Annotated => find(annotated.child, here)
          case _                         => None
      plot.labelGrobs.view.flatMap(find(_, inside = false)).headOption
    val titles =
      text(PlotRegion.Title).toVector.flatMap(t =>
        keep(PlotPart.PlotTitle(t), Vector(PlotRegion.Title))
      ) ++
        text(PlotRegion.Subtitle).toVector.flatMap(t =>
          keep(PlotPart.PlotSubtitle(t), Vector(PlotRegion.Subtitle))
        )

    val strips = plot.facetPanels.flatMap { panel =>
      val cell = panel.cell
      keep(
        PlotPart.FacetStrip(cell.row, cell.column, cell.rowLabel, cell.columnLabel),
        panel.stripGrob.name.toVector
      )
    }

    val guides = plot.guides.map(_.spec).flatMap {
      case axis: GuideSpec.Axis =>
        axis.name.toVector.flatMap { name =>
          keep(
            PlotPart.Axis(axis.side, axis.title),
            Vector(name) ++ Vector("baseline", "ticks", "label", "title")
              .map(suffix => GraphicsName.unsafe(s"${name.value}-$suffix"))
          ).toVector
        }
      case legend: GuideSpec.Legend =>
        legend.name.toVector.flatMap { name =>
          val title = legend.title.toVector.flatMap { text =>
            keep(
              PlotPart.LegendTitle(name.value, text),
              Vector(GraphicsName.unsafe(s"${name.value}-title"))
            )
          }
          val entries = legend.entries.zipWithIndex.flatMap { (entry, index) =>
            val base = s"${name.value}-entry-$index"
            keep(
              PlotPart.LegendEntry(name.value, legend.title, index, entry.label),
              Vector(GraphicsName.unsafe(s"$base-key"), GraphicsName.unsafe(s"$base-label"))
            )
          }
          title ++ entries
        }
      case colorbar: GuideSpec.Colorbar =>
        colorbar.name.toVector.flatMap(name =>
          keep(
            PlotPart.Colorbar(name.value, colorbar.title),
            Vector(name, GraphicsName.unsafe(s"${name.value}-title"))
          ).toVector
        )
    }

    val annotations = plot.layers.filter(_.annotation.nonEmpty).flatMap { layer =>
      val grobNames = names(layer.grobs).toVector
      keep(PlotPart.Annotation(layer.layerIndex), grobNames)
    }

    // Facets repeat axes and annotations per panel; one part collects every name it is drawn under.
    val merged = (titles ++ strips ++ guides ++ annotations)
      .foldLeft(Vector.empty[PartTarget]) { (out, target) =>
        out.indexWhere(_.part == target.part) match
          case -1 => out :+ target
          case i  => out.updated(i, out(i).copy(names = (out(i).names ++ target.names).distinct))
      }
    merged

  /** Every name carried by `grobs` and their descendants. */
  private[interaction] def names(grobs: Vector[Grob]): Set[GraphicsName] =
    val out = Set.newBuilder[GraphicsName]
    def walk(grob: Grob): Unit =
      grob.name.foreach(out += _)
      grob match
        case group: Grob.Group         => group.children.foreach(walk)
        case annotated: Grob.Annotated => walk(annotated.child)
        case _                         => ()
    grobs.foreach(walk)
    out.result()

/** How a built-in layer is divided into interactive targets. */
enum TargetGranularity:
  /** One target per source row (a point batch keeps one primitive with per-index targets). */
  case PerRow

  /** One target per group of rows drawn as one shape, with at least `minimumRows` rows. */
  case PerGroup(minimumRows: Int)

  /** One target per statistical output row (a bin, a summarized x position). */
  case PerStatisticRow

  /** One target per grid cell of one image. */
  case PerCell

  /** One target for the whole layer's single shape. */
  case WholeLayer

  /** One target per annotation grob; it carries no source rows. */
  case PerAnnotation

/** One row of the built-in interaction coverage matrix. */
final case class CoverageEntry(
    component: String,
    granularity: TargetGranularity,
    membership: String,
    picking: String
)

/** The interaction coverage of every built-in geom and statistic, as data. The documentation
  * renders this table and a test compiles an interactive plot for every row, so a built-in that
  * loses its targets, or an entry that overstates them, fails the build.
  */
object InteractionCoverage:
  val entries: Vector[CoverageEntry] = Vector(
    CoverageEntry(
      "geomPoint",
      TargetGranularity.PerRow,
      "the row's entity",
      "mark shape, stroke and fill"
    ),
    CoverageEntry("geomText", TargetGranularity.PerRow, "the row's entity", "text box"),
    CoverageEntry("geomSegment", TargetGranularity.PerRow, "the row's entity", "stroked segment"),
    CoverageEntry("geomTile", TargetGranularity.PerRow, "the row's entity", "filled rectangle"),
    CoverageEntry(
      "geomErrorBar",
      TargetGranularity.PerRow,
      "the row's entity",
      "stroked bar and caps"
    ),
    CoverageEntry(
      "geomLine",
      TargetGranularity.PerGroup(2),
      "every row of the group",
      "stroked path"
    ),
    CoverageEntry(
      "geomArea",
      TargetGranularity.PerGroup(2),
      "every row of the group",
      "filled band"
    ),
    CoverageEntry(
      "geomRibbon",
      TargetGranularity.PerGroup(2),
      "every row of the group",
      "filled band"
    ),
    CoverageEntry(
      "geomPolygon",
      TargetGranularity.PerGroup(3),
      "every row of the group",
      "filled polygon"
    ),
    CoverageEntry(
      "geomHistogram",
      TargetGranularity.PerStatisticRow,
      "the bin's members (count by default)",
      "filled bar"
    ),
    CoverageEntry(
      "geomSummary",
      TargetGranularity.PerStatisticRow,
      "the summarized rows (count by default)",
      "point and interval"
    ),
    CoverageEntry(
      "geomDensity",
      TargetGranularity.WholeLayer,
      "every input row (count by default)",
      "stroked curve"
    ),
    CoverageEntry(
      "geomEcdf",
      TargetGranularity.PerGroup(1),
      "every row of the group",
      "stroked steps"
    ),
    CoverageEntry(
      "geomContour",
      TargetGranularity.PerGroup(2),
      "the extracted path vertices",
      "stroked path"
    ),
    CoverageEntry(
      "geomFilledContour",
      TargetGranularity.PerGroup(3),
      "the extracted region vertices",
      "filled compound polygon, excluding holes"
    ),
    CoverageEntry(
      "geomQuantileSummary",
      TargetGranularity.PerStatisticRow,
      "the summarized rows (count by default)",
      "point and quantile interval"
    ),
    CoverageEntry(
      "geomHeatmap",
      TargetGranularity.PerCell,
      "the cell's entity",
      "filled cell rectangle"
    ),
    CoverageEntry(
      "geomRasterByClass",
      TargetGranularity.PerCell,
      "the cell's entity",
      "image cell"
    ),
    CoverageEntry("geomRaster", TargetGranularity.PerCell, "the cell's entity", "image cell"),
    CoverageEntry("hline / vline", TargetGranularity.PerAnnotation, "none", "stroked line")
  )
