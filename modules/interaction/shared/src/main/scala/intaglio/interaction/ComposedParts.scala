package intaglio.interaction

import intaglio.*

/** The parts of a composed figure, with the scene they are named in.
  *
  * Every child plot draws its title, axes, strips and annotations under the same plot-level names
  * (`plot-title`, `axis-bottom`, ...), and composition places the children side by side without
  * renaming them, so in the composed scene one name would stand for every child's copy. Here each
  * child's part names are scoped to the cell or inset that holds it
  * (`composition-cell-1-plot-title`) so a hit, an outline or a focus ring belongs to exactly one
  * child. Guides that composition collected to figure level are drawn once, outside every cell, and
  * stand for every child that shares them. They keep their names unless two collected guides draw
  * the same names (two scales named alike with different levels); then each is scoped by its
  * position (`composition-guide-0-...`), and a child's legend part goes to the guide whose title
  * and entry it describes.
  *
  * Draw and pick from `scene`, not from the composition's own scene: only the names differ.
  */
final case class ComposedParts(scene: Scene, parts: Vector[PartTarget])

object ComposedParts:
  def of[A](composed: ComposedInteraction[A]): ComposedParts =
    val composition = composed.composition
    val children = composed.plans.map(_.trained)
    val cellCount = composition.cells.size
    // Grid children are cells 0..n-1 in plan order; insets follow them in the order they were added.
    def owner(grob: Grob): Option[Int] =
      grob.name
        .map(_.value)
        .flatMap {
          case s"composition-cell-$i"  => i.toIntOption.filter(_ < cellCount)
          case s"composition-inset-$k" => k.toIntOption.map(cellCount + _)
          case _                       => None
        }
        .filter(children.indices.contains)

    val scoped = composition.scene.grobs.map { grob =>
      owner(grob).fold((grob, Vector.empty[PartTarget])) { child =>
        val scope = grob.name.get.value
        val parts = PlotParts.of(children(child))
        val present = PlotParts.names(Vector(grob))
        val local = parts.flatMap(_.names).filter(present).toSet
        def rename(name: GraphicsName): GraphicsName =
          if local(name) then GraphicsName.unsafe(s"$scope-${name.value}") else name
        (renamed(grob, rename), parts.map(part => part.copy(names = part.names.map(rename))))
      }
    }
    // Collected guides follow the cells in the composed scene, one top-level grob each.
    final case class Collected(position: Int, index: Int, spec: GuideSpec, names: Set[GraphicsName])
    val guides = composition.collectedGuides.zipWithIndex.flatMap { (guide, index) =>
      val position = composition.scene.grobs.indexWhere(_ eq guide.grob)
      Option.when(position >= 0)(
        Collected(position, index, guide.spec, PlotParts.names(Vector(guide.grob)))
      )
    }
    val clashing =
      guides.filter(g => guides.exists(o => o.index != g.index && g.names.exists(o.names)))
    def guideScope(index: Int, name: GraphicsName) =
      GraphicsName.unsafe(s"composition-guide-$index-${name.value}")
    val grobs = scoped.map(_._1).zipWithIndex.map { (grob, position) =>
      clashing.find(_.position == position).fold(grob) { guide =>
        renamed(grob, name => if guide.names(name) then guideScope(guide.index, name) else name)
      }
    }
    // A child's guide part names the clashing guides that draw what it describes.
    def describes(spec: GuideSpec, part: PlotPart): Boolean = (spec, part) match
      case (legend: GuideSpec.Legend, PlotPart.LegendEntry(name, title, index, label)) =>
        legend.name.exists(_.value == name) && legend.title == title &&
        legend.entries.lift(index).exists(_.label == label)
      case (legend: GuideSpec.Legend, PlotPart.LegendTitle(name, title)) =>
        legend.name.exists(_.value == name) && legend.title.contains(title)
      case (colorbar: GuideSpec.Colorbar, PlotPart.Colorbar(name, title)) =>
        colorbar.name.exists(_.value == name) && colorbar.title == title
      case _ => false
    def toGuides(part: PartTarget): PartTarget =
      val owners = clashing.filter(guide => describes(guide.spec, part.part))
      part.copy(names = part.names.flatMap { name =>
        if !clashing.exists(_.names(name)) then Vector(name)
        else owners.filter(_.names(name)).map(guide => guideScope(guide.index, name))
      })
    val scene = Scene(grobs).withSemantics(composition.scene.semantics)
    // Keep only names the composed scene still draws: a child's collected legend is drawn once at
    // figure level, and identical collected parts from several children collapse to one.
    val present = PlotParts.names(scene.grobs)
    val parts = scoped
      .flatMap(_._2)
      .map(toGuides)
      .flatMap { part =>
        val names = part.names.filter(present)
        Option.when(names.nonEmpty)(part.copy(names = names))
      }
      .distinct
    ComposedParts(scene, parts)

  private def renamed(grob: Grob, rename: GraphicsName => GraphicsName): Grob =
    val name = grob.name.map(rename)
    grob match
      case x: Grob.Points          => x.copy(name = name)
      case x: Grob.PointBatch      => x.copy(name = name)
      case x: Grob.Lines           => x.copy(name = name)
      case x: Grob.Polygon         => x.copy(name = name)
      case x: Grob.CompoundPolygon => x.copy(name = name)
      case x: Grob.Segments        => x.copy(name = name)
      case x: Grob.Rect            => x.copy(name = name)
      case x: Grob.Circle          => x.copy(name = name)
      case x: Grob.Text            => x.copy(name = name)
      case x: Grob.Image           => x.copy(name = name)
      case x: Grob.Group           =>
        x.copy(name = name, children = x.children.map(renamed(_, rename)))
      case x: Grob.Annotated => x.copy(child = renamed(x.child, rename))
