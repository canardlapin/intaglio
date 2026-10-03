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

/** Checked compatibility of one position axis between two plots, for views that will share a data
  * window. Two axes link directly only when they are continuous with the same transform (the same
  * transform value, not merely the same name) and the same trained domain; otherwise the caller
  * must state an explicit conversion, which is checked. This is a check only: sharing a viewport is
  * the host's job.
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

  private def sameTransform(
      a: ContinuousScale[?],
      b: ContinuousScale[?],
      aesthetic: Aesthetic[Double]
  ): Either[InteractionError, Unit] =
    Either.cond(
      a.transform eq b.transform,
      (),
      InteractionError.InvalidValue(
        "linked axis",
        s"${aesthetic.label} axes use different transforms (${a.transform.name.value}, ${b.transform.name.value})"
      )
    )

  /** Right when `aesthetic` means the same data position in both plots. */
  def compatible(
      from: TrainedPlot,
      to: TrainedPlot,
      aesthetic: Aesthetic[Double]
  ): Either[InteractionError, Unit] =
    for
      a <- continuous(from, aesthetic)
      b <- continuous(to, aesthetic)
      _ <- sameTransform(a, b, aesthetic)
      _ <- Either.cond(
        a.domain == b.domain,
        (),
        InteractionError.InvalidValue(
          "linked axis",
          s"${aesthetic.label} domains differ; state an explicit conversion"
        )
      )
    yield ()

  /** Right when `conversion` carries the `from` axis onto the `to` axis: both share a transform,
    * and the converted positions of the domain's endpoints and of interior points land at the same
    * normalized panel position in the target, within `tolerance`. An offset conversion under a
    * nonlinear transform, which maps the endpoints but bends the interior, is refused.
    */
  def converted(
      from: TrainedPlot,
      to: TrainedPlot,
      aesthetic: Aesthetic[Double],
      conversion: Transform,
      tolerance: Double = 1e-9
  ): Either[IntaglioError, Unit] =
    def normalized(scale: ContinuousScale[?], raw: Double): Either[IntaglioError, Double] =
      scale.transform.transform(raw).map(t => scale.transformedDomain.rescale(t))
    for
      a <- continuous(from, aesthetic)
      b <- continuous(to, aesthetic)
      _ <- sameTransform(a, b, aesthetic)
      samples = (0 to 8).map(i => a.domain.lower + a.domain.width * i / 8.0)
      _ <- samples.foldLeft[Either[IntaglioError, Unit]](Right(())) { (done, x) =>
        done.flatMap { _ =>
          for
            source <- normalized(a, x)
            mapped <- conversion.transform(x)
            target <- normalized(b, mapped)
            _ <- Either.cond(
              math.abs(source - target) <= tolerance * math.max(1.0, math.abs(source)),
              (),
              InteractionError.InvalidValue(
                "linked axis",
                s"conversion ${conversion.name.value} does not carry the ${aesthetic.label} axis onto the target"
              )
            )
          yield ()
        }
      }
    yield ()
