package intaglio.interaction

import intaglio.*

/** A selection projected into another plot: the keys that plot contains, and those it does not. */
final case class ProjectedSelection[A](selection: Selection[A], missing: Set[EntityKey[A]])

object SelectionProjection:
  /** Project `selection` into a plot whose domain is `target`. Entity keys link plots only when
    * they belong to the same key space instance and the receiving plot contains them; others are
    * reported as `missing`, never invented, because a plot cannot select an observation it does not
    * have. Visual targets are local to their plot and never cross, so a histogram bin selected as a
    * bin stays a bin.
    */
  def into[A](selection: Selection[A], target: InteractionDomain[A]): ProjectedSelection[A] =
    val (kept, missing) = selection.entities.partition(target.entities.contains)
    ProjectedSelection(Selection(kept), missing)

/** Marks to emphasize because a linked view or a legend points at them: by observation (entity)
  * key, or by a shared category (link) key. Keys only match keys of the same space.
  */
final case class LinkedEmphasis[A](
    entities: Set[EntityKey[A]] = Set.empty[EntityKey[A]],
    links: Set[LinkKey[?]] = Set.empty
):
  def isEmpty: Boolean = entities.isEmpty && links.isEmpty

  def matches(target: TargetInfo[A]): Boolean =
    target.entity.exists(entities.contains) || links.exists(target.links.contains)

object LinkedEmphasis:
  def none[A]: LinkedEmphasis[A] = LinkedEmphasis[A]()

/** A keyed legend whose entries stand for link keys of `space`: entry labels are values of that
  * space, so pointing at an entry emphasizes, and choosing it selects, the marks whose layer
  * binding projects that link key.
  */
final case class LegendLink(legend: String, space: KeySpace[String])

/** Checked compatibility of one position axis between two plots, for views that share a data
  * window. Two axes link directly only when they are continuous with the same transform and the
  * same trained domain; otherwise the caller must state an explicit conversion, which is checked.
  */
object LinkedAxes:
  private def continuous(
      plot: TrainedPlot,
      aesthetic: Aesthetic[Double]
  ): Either[InteractionError, ContinuousScale[?]] =
    plot.scaleRegistry.forAesthetic(aesthetic).map(_.scale) match
      case Some(scale: ContinuousScale[?]) => Right(scale)
      case Some(_)                         =>
        Left(
          InteractionError.UnsupportedCapability(
            s"linking a non-continuous ${aesthetic.label} axis"
          )
        )
      case None =>
        Left(InteractionError.InvalidValue("linked axis", s"no trained ${aesthetic.label} scale"))

  /** Right when `aesthetic` means the same data position in both plots. */
  def compatible(
      from: TrainedPlot,
      to: TrainedPlot,
      aesthetic: Aesthetic[Double]
  ): Either[InteractionError, Unit] =
    for
      a <- continuous(from, aesthetic)
      b <- continuous(to, aesthetic)
      _ <- Either.cond(
        a.transform.name == b.transform.name,
        (),
        InteractionError.InvalidValue(
          "linked axis",
          s"${aesthetic.label} transforms differ: ${a.transform.name.value} and ${b.transform.name.value}"
        )
      )
      _ <- Either.cond(
        a.domain == b.domain,
        (),
        InteractionError.InvalidValue(
          "linked axis",
          s"${aesthetic.label} domains differ; state an explicit conversion"
        )
      )
    yield ()

  /** Right when `conversion` maps the `from` axis's raw domain onto the `to` axis's raw domain
    * (both endpoints, within `tolerance` of the target's width) and both axes share a transform. A
    * caller linking seconds to milliseconds states the conversion; it is never inferred.
    */
  def converted(
      from: TrainedPlot,
      to: TrainedPlot,
      aesthetic: Aesthetic[Double],
      conversion: Transform,
      tolerance: Double = 1e-9
  ): Either[IntaglioError, Unit] =
    for
      a <- continuous(from, aesthetic)
      b <- continuous(to, aesthetic)
      _ <- Either.cond(
        a.transform.name == b.transform.name,
        (),
        InteractionError.InvalidValue("linked axis", s"${aesthetic.label} transforms differ")
      )
      lower <- conversion.transform(a.domain.lower)
      upper <- conversion.transform(a.domain.upper)
      span = math.max(b.domain.width, 1e-300)
      _ <- Either.cond(
        math.abs(lower - b.domain.lower) <= tolerance * span &&
          math.abs(upper - b.domain.upper) <= tolerance * span,
        (),
        InteractionError.InvalidValue(
          "linked axis",
          s"conversion ${conversion.name.value} does not map the ${aesthetic.label} domain onto the target"
        )
      )
    yield ()
