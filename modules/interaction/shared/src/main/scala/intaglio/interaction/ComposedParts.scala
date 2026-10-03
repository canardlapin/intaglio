package intaglio.interaction

import intaglio.*

/** The parts of a composed figure, with the scene they are named in.
  *
  * Every child plot draws its title, axes, strips and annotations under the same plot-level names
  * (`plot-title`, `axis-bottom`, ...), and composition places the children side by side without
  * renaming them, so in the composed scene one name would stand for every child's copy. Here each
  * child's part names are scoped to the cell or inset that holds it
  * (`composition-cell-1-plot-title`) so a hit, an outline or a focus ring belongs to exactly one
  * child. Guides that composition collected into one figure-level legend keep their names: they are
  * drawn once, outside every cell, and stand for every child that shares them.
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
    val scene = Scene(scoped.map(_._1)).withSemantics(composition.scene.semantics)
    // Keep only names the composed scene still draws: a child's collected legend is drawn once at
    // figure level, and identical collected parts from several children collapse to one.
    val present = PlotParts.names(scene.grobs)
    val parts = scoped
      .flatMap(_._2)
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
