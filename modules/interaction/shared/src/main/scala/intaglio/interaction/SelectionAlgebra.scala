package intaglio.interaction

import intaglio.*

/** The name of a saved selection: 1 to 64 characters of letters, digits, space, `_`, `-` or `.`,
  * not starting or ending with a space.
  */
final class SelectionName private (val value: String):
  override def equals(other: Any): Boolean = other match
    case that: SelectionName => value == that.value
    case _                   => false
  override def hashCode(): Int = value.hashCode
  override def toString: String = s"SelectionName($value)"

object SelectionName:
  private val Allowed = "^[A-Za-z0-9_.\\-](?:[A-Za-z0-9 _.\\-]{0,62}[A-Za-z0-9_.\\-])?$".r

  def apply(value: String): Either[InteractionError, SelectionName] =
    if value != null && Allowed.matches(value) then Right(new SelectionName(value))
    else Left(InteractionError.InvalidValue("selection name", String.valueOf(value)))

  def unsafe(value: String): SelectionName =
    apply(value).fold(error => throw IllegalArgumentException(error.message), identity)

/** How two selections combine. */
enum SetCombination:
  case Union, Intersection, Difference

/** Set operations on selections. Operands combine only when they mean the same things: every
  * observation key belongs to a key space instance `domain` uses (keys of two such spaces stay
  * distinct, so a selection may span the plots of a domain), and every target is a target of
  * `domain`. Observations and targets combine separately, as they are selected separately, so the
  * operations obey the set laws on each part.
  */
object SelectionAlgebra:
  def combine[A](
      left: Selection[A],
      right: Selection[A],
      how: SetCombination,
      domain: InteractionDomain[A]
  ): Either[StateError, Selection[A]] =
    compatible(left, right, domain).map { _ =>
      how match
        case SetCombination.Union =>
          Selection(left.entities ++ right.entities, left.targets ++ right.targets)
        case SetCombination.Intersection =>
          Selection(left.entities.intersect(right.entities), left.targets.intersect(right.targets))
        case SetCombination.Difference =>
          Selection(left.entities -- right.entities, left.targets -- right.targets)
    }

  def union[A](a: Selection[A], b: Selection[A], domain: InteractionDomain[A]) =
    combine(a, b, SetCombination.Union, domain)
  def intersect[A](a: Selection[A], b: Selection[A], domain: InteractionDomain[A]) =
    combine(a, b, SetCombination.Intersection, domain)
  def diff[A](a: Selection[A], b: Selection[A], domain: InteractionDomain[A]) =
    combine(a, b, SetCombination.Difference, domain)

  /** Both operands mean the same kind of thing in `domain`. */
  def compatible[A](
      left: Selection[A],
      right: Selection[A],
      domain: InteractionDomain[A]
  ): Either[StateError, Unit] =
    val keys = left.entities ++ right.entities
    if keys.exists(key => !domain.accepts(key)) then
      Left(
        StateError.IncompatibleSelections("an operand holds keys of a space this plot does not use")
      )
    else
      (left.targets ++ right.targets).find(id => domain.target(id).isLeft) match
        case Some(id) => Left(StateError.UnknownTarget(id))
        case None     => Right(())
