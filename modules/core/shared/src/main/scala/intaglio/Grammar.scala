package intaglio

import scala.util.control.NonFatal

/** The promise attached to a user-supplied row mapping.
  *
  *   - [[MappingContract.Total]] says the mapping is defined for every row. The compiler still
  *     catches a violated promise at its public `Either` boundary.
  *   - [[MappingContract.Checked]] lets the mapping reject a row with a typed [[MappingFailure]].
  *   - [[MappingContract.Throwing]] explicitly admits a partial function that may throw. Legacy
  *     `Row => A` mappings use this contract.
  */
enum MappingContract(val label: String):
  case Total extends MappingContract("total")
  case Checked extends MappingContract("checked")
  case Throwing extends MappingContract("throwing")

/** A mapping failure before scale-domain validation. Fatal JVM errors are never captured. */
enum MappingFailure:
  case Rejected(detail: String)
  case Threw(exceptionType: String, detail: String)

  def message: String =
    this match
      case Rejected(detail)             => detail
      case Threw(exceptionType, detail) => s"$exceptionType: $detail"

object MappingFailure:
  private[intaglio] def fromThrowable(error: Throwable): MappingFailure =
    error match
      case mapping: MappingException => mapping.failure
      case _                         =>
        val detail = Option(error.getMessage).filter(_.nonEmpty).getOrElse("no message")
        MappingFailure.Threw(error.getClass.getName, detail)

private[intaglio] final class MappingException(val failure: MappingFailure)
    extends RuntimeException(failure.message)

/** A `Row => A` carrying an explicit failure contract. Because it remains a `Function1`, it can be
  * passed anywhere Intaglio already accepts a row accessor without changing existing signatures.
  * Public compiler methods evaluate it through [[RowMapping.evaluate]]; calling `apply` directly is
  * the deliberate throwing convenience boundary.
  */
sealed trait RowMapping[-Row, +A] extends (Row => A):
  def contract: MappingContract
  def evaluate(row: Row): Either[MappingFailure, A]

  final override def apply(row: Row): A =
    evaluate(row) match
      case Right(value)  => value
      case Left(failure) => throw new MappingException(failure)

  /** Precompose a row projection. The declared contract is retained; any non-fatal exception from
    * the projection is still captured as a violated mapping contract.
    */
  final def contramap[Input](f: Input => Row): RowMapping[Input, A] =
    RowMapping.instance(contract, input => evaluate(f(input)))

  final def map[B](f: A => B): RowMapping[Row, B] =
    RowMapping.instance(contract, row => evaluate(row).map(f))

object RowMapping:
  private[intaglio] type Problem = (MappingContract, MappingFailure)

  private final case class Impl[-Row, +A](
      contract: MappingContract,
      run: Row => Either[MappingFailure, A]
  ) extends RowMapping[Row, A]:
    def evaluate(row: Row): Either[MappingFailure, A] =
      try run(row)
      catch case NonFatal(error) => Left(MappingFailure.fromThrowable(error))

  private def instance[Row, A](
      contract: MappingContract,
      run: Row => Either[MappingFailure, A]
  ): RowMapping[Row, A] =
    Impl(contract, run)

  /** Declare a mapping that is defined for every row. Exceptions violate that promise but are still
    * caught at compiler boundaries.
    */
  def total[Row, A](value: Row => A): RowMapping[Row, A] =
    instance(MappingContract.Total, row => Right(value(row)))

  /** Declare a mapping whose expected rejections are values rather than exceptions. */
  def checked[Row, A](
      value: Row => Either[MappingFailure, A]
  ): RowMapping[Row, A] =
    instance(MappingContract.Checked, value)

  /** String-valued convenience constructor for ordinary validation failures. */
  def checkedMessage[Row, A](value: Row => Either[String, A]): RowMapping[Row, A] =
    checked(row => value(row).left.map(MappingFailure.Rejected(_)))

  /** Declare a mapping that may throw. Non-fatal exceptions become typed diagnostics when compiled.
    */
  def throwing[Row, A](value: Row => A): RowMapping[Row, A] =
    instance(MappingContract.Throwing, row => Right(value(row)))

  private[intaglio] def fromFunction[Row, A](value: Row => A): RowMapping[Row, A] =
    value match
      case mapping: RowMapping[?, ?] =>
        mapping.asInstanceOf[RowMapping[Row, A]]
      case _ =>
        throwing(value)

  private[intaglio] def evaluateFunction[Row, A](
      value: Row => A,
      row: Row
  ): Either[Problem, A] =
    val mapping = fromFunction(value)
    mapping.evaluate(row).left.map(mapping.contract -> _)

  private[intaglio] def capture[A](
      contract: MappingContract
  )(value: => A): Either[Problem, A] =
    try Right(value)
    catch case NonFatal(error) => Left(contract -> MappingFailure.fromThrowable(error))

  private[intaglio] def zipWith[Row, A, B, C](
      left: Row => A,
      right: Row => B
  )(combine: (A, B) => C): RowMapping[Row, C] =
    val leftMapping = fromFunction(left)
    val rightMapping = fromFunction(right)
    val contract = combinedContract(leftMapping.contract, rightMapping.contract)
    instance(
      contract,
      row =>
        for
          a <- leftMapping.evaluate(row)
          b <- rightMapping.evaluate(row)
        yield combine(a, b)
    )

  private def combinedContract(
      left: MappingContract,
      right: MappingContract
  ): MappingContract =
    if left == MappingContract.Throwing || right == MappingContract.Throwing then
      MappingContract.Throwing
    else if left == MappingContract.Checked || right == MappingContract.Checked then
      MappingContract.Checked
    else MappingContract.Total

sealed trait AesValue[Row, A]:
  def map(row: Row): Option[A]
  private[intaglio] def registered(
      aesthetic: Aesthetic[A]
  ): Option[RegisteredScale[Row]] =
    None
  private[intaglio] def mappedBand(row: Row): Option[Band] =
    None
  private[intaglio] def isDiscreteMapped: Boolean =
    false
  def isScaled: Boolean =
    false

  def contramap[Input](f: Input => Row): AesValue[Input, A] =
    this match
      case AesValue.Direct(value)   => AesValue.direct(RowMapping.fromFunction(value).contramap(f))
      case AesValue.Constant(value) => AesValue.constant(value)
      case AesValue.Scaled(value, scale) =>
        AesValue.scaled(RowMapping.fromFunction(value).contramap(f), scale)

object AesValue:
  final case class Direct[Row, A](value: Row => A) extends AesValue[Row, A]:
    override def map(row: Row): Option[A] =
      Some(value(row))

  final case class Constant[Row, A](value: A) extends AesValue[Row, A]:
    override def map(row: Row): Option[A] =
      Some(value)

  final case class Scaled[Row, In, A](value: Row => In, scale: ScaleValue[In, A])
      extends AesValue[Row, A]:
    override def map(row: Row): Option[A] =
      scale.mapDeclaredValue(value(row))

    override def isScaled: Boolean =
      true

    private[intaglio] override def registered(
        aesthetic: Aesthetic[A]
    ): Option[RegisteredScale[Row]] =
      Some(RegisteredScale(aesthetic, this))

    private[intaglio] override def isDiscreteMapped: Boolean =
      scale.descriptor.kind == ScaleKind.Discrete

    private[intaglio] override def mappedBand(row: Row): Option[Band] =
      scale.mappedBand(value(row))

  def direct[Row, A](value: Row => A): AesValue[Row, A] =
    Direct(value)

  def total[Row, A](value: Row => A): AesValue[Row, A] =
    Direct(RowMapping.total(value))

  def checked[Row, A](
      value: Row => Either[MappingFailure, A]
  ): AesValue[Row, A] =
    Direct(RowMapping.checked(value))

  def throwing[Row, A](value: Row => A): AesValue[Row, A] =
    Direct(RowMapping.throwing(value))

  def constant[Row, A](value: A): AesValue[Row, A] =
    Constant(value)

  def scaled[Row, In, A](
      value: Row => In,
      scale: ScaleValue[In, A]
  ): AesValue[Row, A] =
    Scaled(value, scale)

  def scaledTotal[Row, In, A](
      value: Row => In,
      scale: ScaleValue[In, A]
  ): AesValue[Row, A] =
    Scaled(RowMapping.total(value), scale)

  def scaledChecked[Row, In, A](
      value: Row => Either[MappingFailure, In],
      scale: ScaleValue[In, A]
  ): AesValue[Row, A] =
    Scaled(RowMapping.checked(value), scale)

  def scaledThrowing[Row, In, A](
      value: Row => In,
      scale: ScaleValue[In, A]
  ): AesValue[Row, A] =
    Scaled(RowMapping.throwing(value), scale)

/** Immutable heterogeneous aesthetic storage. The typed key and its `AesValue` remain packaged
  * together; lookup exposes the stored value only when the requested key has identical reference
  * identity.
  */
final class AestheticMap[Row] private (private val entries: Vector[AestheticMap.Entry[Row]]):
  def get[A](aesthetic: Aesthetic[A]): Option[AesValue[Row, A]] =
    entries.iterator.flatMap(_.get(aesthetic)).nextOption()

  def contains(aesthetic: Aesthetic[?]): Boolean =
    entries.exists(_.matches(aesthetic))

  /** Core keys retain their historical declaration order. Ecosystem keys follow in insertion order.
    */
  def keys: Vector[Aesthetic[?]] =
    entries.map(_.key)

  def updated[A](aesthetic: Aesthetic[A], value: AesValue[Row, A]): AestheticMap[Row] =
    entries.indexWhere(_.matches(aesthetic)) match
      case -1 =>
        val at = insertionIndex(aesthetic)
        new AestheticMap(entries.patch(at, Vector(AestheticMap.Entry(aesthetic, value)), 0))
      case index =>
        new AestheticMap(entries.updated(index, AestheticMap.Entry(aesthetic, value)))

  def removed(aesthetic: Aesthetic[?]): AestheticMap[Row] =
    entries.indexWhere(_.matches(aesthetic)) match
      case -1    => this
      case index => new AestheticMap(entries.patch(index, Vector.empty, 1))

  private[intaglio] def updatedOption[A](
      aesthetic: Aesthetic[A],
      value: Option[AesValue[Row, A]]
  ): AestheticMap[Row] =
    value.fold(removed(aesthetic))(updated(aesthetic, _))

  private[intaglio] def inherit(parent: AestheticMap[Row]): AestheticMap[Row] =
    val allKeys =
      (keys ++ parent.keys).foldLeft(Vector.empty[Aesthetic[?]]) { (result, aesthetic) =>
        if result.exists(_ eq aesthetic) then result else result :+ aesthetic
      }
    allKeys.foldLeft(AestheticMap.empty[Row]) { (result, aesthetic) =>
      inheritAt(result, parent, aesthetic)
    }

  private[intaglio] def contramap[Input](f: Input => Row): AestheticMap[Input] =
    new AestheticMap(entries.map(_.contramap(f)))

  private[intaglio] def scaledEntries: Vector[RegisteredScale[Row]] =
    keys.flatMap(scaledEntry)

  private[intaglio] def scaledEntry(
      aesthetic: Aesthetic[?]
  ): Option[RegisteredScale[Row]] =
    scaledEntryAt(aesthetic)

  override def equals(other: Any): Boolean =
    other match
      case that: AestheticMap[?] => entries == that.entries
      case _                     => false

  override def hashCode(): Int =
    entries.hashCode()

  override def toString: String =
    entries.mkString("AestheticMap(", ", ", ")")

  private def insertionIndex(aesthetic: Aesthetic[?]): Int =
    Aesthetic.builtInIndex(aesthetic) match
      case None       => entries.length
      case Some(rank) =>
        entries.indexWhere { entry =>
          Aesthetic.builtInIndex(entry.key).forall(_ > rank)
        } match
          case -1    => entries.length
          case index => index

  private def inheritAt[A](
      result: AestheticMap[Row],
      parent: AestheticMap[Row],
      aesthetic: Aesthetic[A]
  ): AestheticMap[Row] =
    val selected =
      get(aesthetic) match
        case local @ Some(value) if value.isScaled => local
        case local                                 =>
          parent.get(aesthetic) match
            case inherited @ Some(value) if value.isScaled => inherited
            case inherited                                 => local.orElse(inherited)
    selected.fold(result)(result.updated(aesthetic, _))

  private def scaledEntryAt[A](aesthetic: Aesthetic[A]): Option[RegisteredScale[Row]] =
    get(aesthetic).flatMap(_.registered(aesthetic))

object AestheticMap:
  private sealed trait Entry[Row]:
    type Value
    def key: Aesthetic[Value]
    def value: AesValue[Row, Value]

    final def matches(candidate: Aesthetic[?]): Boolean =
      key eq candidate

    /** Reference identity proves that `Value` and `A` are the same hidden type. This is the sole
      * existential recovery cast in aesthetic storage.
      */
    final def get[A](candidate: Aesthetic[A]): Option[AesValue[Row, A]] =
      Option.when(matches(candidate))(value.asInstanceOf[AesValue[Row, A]])

    final def contramap[Input](f: Input => Row): Entry[Input] =
      Entry(key, value.contramap(f))

  private final case class TypedEntry[Row, A](
      key: Aesthetic[A],
      value: AesValue[Row, A]
  ) extends Entry[Row]:
    type Value = A

  private object Entry:
    def apply[Row, A](aesthetic: Aesthetic[A], value: AesValue[Row, A]): Entry[Row] =
      TypedEntry(aesthetic, value)

  def empty[Row]: AestheticMap[Row] =
    new AestheticMap(Vector.empty)

/** How a compiled layer chooses its group identity. Inference deliberately names the contributing
  * aesthetics; row-level keys retain their raw pre-palette categories separately.
  */
enum GroupingDecision:
  case Ungrouped
  case Explicit
  case Inferred(aesthetics: Vector[Aesthetic[?]])

/** One raw categorical value contributing to an inferred group. Its stable identity is distinct
  * from its display label, so typed categories with equal labels do not collide.
  */
final case class DiscreteGroupValue private (aesthetic: Aesthetic[?], token: CategoryToken):
  def category: String =
    token.label

object DiscreteGroupValue:
  /** Source-compatible String category constructor. */
  def apply(aesthetic: Aesthetic[?], category: String): DiscreteGroupValue =
    new DiscreteGroupValue(aesthetic, CategoryToken(category, CategoryIdentity.strings))

  private[intaglio] def typed(
      aesthetic: Aesthetic[?],
      category: CategoryToken
  ): DiscreteGroupValue =
    new DiscreteGroupValue(aesthetic, category)

/** Collision-free row grouping identity. Renderers and position adjustments compare this structural
  * value rather than a palette output or concatenated display string.
  */
enum GroupKey:
  case Explicit(value: String)
  case Inferred(values: Vector[DiscreteGroupValue])

  /** Compatibility/debug label. Structural equality, not this rendering, defines identity. */
  def display: String =
    this match
      case Explicit(value)         => value
      case Inferred(Vector(value)) => value.category
      case Inferred(values)        =>
        values.map(value => s"${value.aesthetic.label}=${value.category}").mkString("|")

final case class Position2[Row](x: AesValue[Row, Double], y: AesValue[Row, Double]):
  def map(row: Row): Option[(Double, Double)] =
    for
      px <- x.map(row)
      py <- y.map(row)
    yield (px, py)

enum RequiredAesthetic(val aesthetic: Aesthetic[?]):
  case X extends RequiredAesthetic(Aesthetic.X)
  case Y extends RequiredAesthetic(Aesthetic.Y)
  case XEnd extends RequiredAesthetic(Aesthetic.XEnd)
  case YEnd extends RequiredAesthetic(Aesthetic.YEnd)
  case XMin extends RequiredAesthetic(Aesthetic.XMin)
  case XMax extends RequiredAesthetic(Aesthetic.XMax)
  case YMin extends RequiredAesthetic(Aesthetic.YMin)
  case YMax extends RequiredAesthetic(Aesthetic.YMax)
  case Label extends RequiredAesthetic(Aesthetic.Label)

  def label: String =
    aesthetic.label

  def isPresent[Row](mapping: AesSpec[Row]): Boolean =
    mapping.isBound(aesthetic)

final class AesSpec[Row] private (val aesthetics: AestheticMap[Row]):
  def x: Option[AesValue[Row, Double]] = get(Aesthetic.X)
  def y: Option[AesValue[Row, Double]] = get(Aesthetic.Y)
  def xEnd: Option[AesValue[Row, Double]] = get(Aesthetic.XEnd)
  def yEnd: Option[AesValue[Row, Double]] = get(Aesthetic.YEnd)
  def xMin: Option[AesValue[Row, Double]] = get(Aesthetic.XMin)
  def xMax: Option[AesValue[Row, Double]] = get(Aesthetic.XMax)
  def yMin: Option[AesValue[Row, Double]] = get(Aesthetic.YMin)
  def yMax: Option[AesValue[Row, Double]] = get(Aesthetic.YMax)
  def color: Option[AesValue[Row, Rgba]] = get(Aesthetic.Color)
  def fill: Option[AesValue[Row, Rgba]] = get(Aesthetic.Fill)
  def alpha: Option[AesValue[Row, Double]] = get(Aesthetic.Alpha)
  def size: Option[AesValue[Row, Double]] = get(Aesthetic.Size)
  def label: Option[AesValue[Row, String]] = get(Aesthetic.Label)
  def group: Option[AesValue[Row, String]] = get(Aesthetic.Group)
  def subpath: Option[AesValue[Row, String]] = get(Aesthetic.Subpath)

  /** Typed lookup against the canonical heterogeneous aesthetic storage. */
  def get[A](aesthetic: Aesthetic[A]): Option[AesValue[Row, A]] =
    aesthetics.get(aesthetic)

  def isBound(aesthetic: Aesthetic[?]): Boolean =
    aesthetics.contains(aesthetic)

  /** Bound core aesthetics in their stable order, followed by ecosystem keys in insertion order. */
  def bound: Vector[Aesthetic[?]] =
    aesthetics.keys

  /** Explicit `group` is authoritative. Otherwise, discrete style bindings form an interaction in
    * stable aesthetic declaration order. Position, label, and subpath mappings do not implicitly
    * alter grouping.
    */
  private[intaglio] def groupingDecision: GroupingDecision =
    if group.nonEmpty then GroupingDecision.Explicit
    else
      val inferred: Vector[Aesthetic[?]] =
        Vector(
          Option.when(color.exists(_.isDiscreteMapped))(Aesthetic.Color),
          Option.when(fill.exists(_.isDiscreteMapped))(Aesthetic.Fill),
          Option.when(alpha.exists(_.isDiscreteMapped))(Aesthetic.Alpha),
          Option.when(size.exists(_.isDiscreteMapped))(Aesthetic.Size)
        ).flatten
      if inferred.isEmpty then GroupingDecision.Ungrouped
      else GroupingDecision.Inferred(inferred)

  def updated[A](aesthetic: Aesthetic[A], value: AesValue[Row, A]): AesSpec[Row] =
    new AesSpec(aesthetics.updated(aesthetic, value))

  def removed(aesthetic: Aesthetic[?]): AesSpec[Row] =
    new AesSpec(aesthetics.removed(aesthetic))

  /** Register a scaled binding; a second scaled binding on the same aesthetic remains a typed
    * error.
    */
  def bind[In, A](binding: ScaleBinding[Row, In, A]): Either[GraphicsError, AesSpec[Row]] =
    get(binding.aesthetic) match
      case Some(value) if value.isScaled =>
        Left(GraphicsError.DuplicateScale(binding.aesthetic.label))
      case _ =>
        Right(updated(binding.aesthetic, binding.toAesValue))

  /** Layer-over-plot inheritance: a scaled local binding wins, then a scaled parent binding, then
    * local, then parent.
    */
  def inherit(parent: AesSpec[Row]): AesSpec[Row] =
    new AesSpec(aesthetics.inherit(parent.aesthetics))

  /** Scaled bindings in declaration order, each registered exactly once. */
  def scaledEntries: Vector[RegisteredScale[Row]] =
    aesthetics.scaledEntries

  private[intaglio] def scaledEntry(aesthetic: Aesthetic[?]): Option[RegisteredScale[Row]] =
    aesthetics.scaledEntry(aesthetic)

  def contramap[Input](f: Input => Row): AesSpec[Input] =
    new AesSpec(aesthetics.contramap(f))

  def position: Option[Position2[Row]] =
    for
      px <- x
      py <- y
    yield Position2(px, py)

  def withPosition(x: Row => Double, y: Row => Double): AesSpec[Row] =
    updated(Aesthetic.X, AesValue.direct(x)).updated(Aesthetic.Y, AesValue.direct(y))

  def withSegment(
      x: Row => Double,
      y: Row => Double,
      xEnd: Row => Double,
      yEnd: Row => Double
  ): AesSpec[Row] =
    withPosition(x, y)
      .updated(Aesthetic.XEnd, AesValue.direct(xEnd))
      .updated(Aesthetic.YEnd, AesValue.direct(yEnd))

  def withBounds(
      x: Row => Double,
      y: Row => Double,
      xMin: Row => Double,
      xMax: Row => Double,
      yMin: Row => Double,
      yMax: Row => Double
  ): AesSpec[Row] =
    withPosition(x, y)
      .updated(Aesthetic.XMin, AesValue.direct(xMin))
      .updated(Aesthetic.XMax, AesValue.direct(xMax))
      .updated(Aesthetic.YMin, AesValue.direct(yMin))
      .updated(Aesthetic.YMax, AesValue.direct(yMax))

  def withYBounds(
      x: Row => Double,
      y: Row => Double,
      yMin: Row => Double,
      yMax: Row => Double
  ): AesSpec[Row] =
    withPosition(x, y)
      .updated(Aesthetic.YMin, AesValue.direct(yMin))
      .updated(Aesthetic.YMax, AesValue.direct(yMax))

  def withColor(f: Row => Rgba): AesSpec[Row] =
    updated(Aesthetic.Color, AesValue.direct(f))

  def withColor(value: Rgba): AesSpec[Row] =
    updated(Aesthetic.Color, AesValue.constant(value))

  def withFill(f: Row => Rgba): AesSpec[Row] =
    updated(Aesthetic.Fill, AesValue.direct(f))

  def withFill(value: Rgba): AesSpec[Row] =
    updated(Aesthetic.Fill, AesValue.constant(value))

  def withAlpha(f: Row => Double): AesSpec[Row] =
    updated(Aesthetic.Alpha, AesValue.direct(f))

  def withAlpha(value: Double): AesSpec[Row] =
    updated(Aesthetic.Alpha, AesValue.constant(value))

  def withSize(f: Row => Double): AesSpec[Row] =
    updated(Aesthetic.Size, AesValue.direct(f))

  def withSize(value: Double): AesSpec[Row] =
    updated(Aesthetic.Size, AesValue.constant(value))

  def withLabel(f: Row => String): AesSpec[Row] =
    updated(Aesthetic.Label, AesValue.direct(f))

  def withLabel(value: String): AesSpec[Row] =
    updated(Aesthetic.Label, AesValue.constant(value))

  def withGroup(f: Row => String): AesSpec[Row] =
    updated(Aesthetic.Group, AesValue.direct(f))

  def withGroup(value: String): AesSpec[Row] =
    updated(Aesthetic.Group, AesValue.constant(value))

  def withSubpath(f: Row => String): AesSpec[Row] =
    updated(Aesthetic.Subpath, AesValue.direct(f))

  def withSubpath(value: String): AesSpec[Row] =
    updated(Aesthetic.Subpath, AesValue.constant(value))

  /** Source-compatible built-in-field copy. Ecosystem mappings are retained unchanged. */
  def copy(
      x: Option[AesValue[Row, Double]] = this.x,
      y: Option[AesValue[Row, Double]] = this.y,
      xEnd: Option[AesValue[Row, Double]] = this.xEnd,
      yEnd: Option[AesValue[Row, Double]] = this.yEnd,
      xMin: Option[AesValue[Row, Double]] = this.xMin,
      xMax: Option[AesValue[Row, Double]] = this.xMax,
      yMin: Option[AesValue[Row, Double]] = this.yMin,
      yMax: Option[AesValue[Row, Double]] = this.yMax,
      color: Option[AesValue[Row, Rgba]] = this.color,
      fill: Option[AesValue[Row, Rgba]] = this.fill,
      alpha: Option[AesValue[Row, Double]] = this.alpha,
      size: Option[AesValue[Row, Double]] = this.size,
      label: Option[AesValue[Row, String]] = this.label,
      group: Option[AesValue[Row, String]] = this.group,
      subpath: Option[AesValue[Row, String]] = this.subpath
  ): AesSpec[Row] =
    val next = aesthetics
      .updatedOption(Aesthetic.X, x)
      .updatedOption(Aesthetic.Y, y)
      .updatedOption(Aesthetic.XEnd, xEnd)
      .updatedOption(Aesthetic.YEnd, yEnd)
      .updatedOption(Aesthetic.XMin, xMin)
      .updatedOption(Aesthetic.XMax, xMax)
      .updatedOption(Aesthetic.YMin, yMin)
      .updatedOption(Aesthetic.YMax, yMax)
      .updatedOption(Aesthetic.Color, color)
      .updatedOption(Aesthetic.Fill, fill)
      .updatedOption(Aesthetic.Alpha, alpha)
      .updatedOption(Aesthetic.Size, size)
      .updatedOption(Aesthetic.Label, label)
      .updatedOption(Aesthetic.Group, group)
      .updatedOption(Aesthetic.Subpath, subpath)
    new AesSpec(next)

  /** Compatibility view: `AesSpec` itself is the canonical environment. */
  def env: AesEnv[Row] =
    this

  def bindScale[In, A](binding: ScaleBinding[Row, In, A]): Either[GraphicsError, AesSpec[Row]] =
    bind(binding)

  override def equals(other: Any): Boolean =
    other match
      case that: AesSpec[?] => aesthetics == that.aesthetics
      case _                => false

  override def hashCode(): Int =
    aesthetics.hashCode()

  override def toString: String =
    s"AesSpec($aesthetics)"

object AesSpec:
  def apply[Row](
      x: Option[AesValue[Row, Double]] = None,
      y: Option[AesValue[Row, Double]] = None,
      xEnd: Option[AesValue[Row, Double]] = None,
      yEnd: Option[AesValue[Row, Double]] = None,
      xMin: Option[AesValue[Row, Double]] = None,
      xMax: Option[AesValue[Row, Double]] = None,
      yMin: Option[AesValue[Row, Double]] = None,
      yMax: Option[AesValue[Row, Double]] = None,
      color: Option[AesValue[Row, Rgba]] = None,
      fill: Option[AesValue[Row, Rgba]] = None,
      alpha: Option[AesValue[Row, Double]] = None,
      size: Option[AesValue[Row, Double]] = None,
      label: Option[AesValue[Row, String]] = None,
      group: Option[AesValue[Row, String]] = None,
      subpath: Option[AesValue[Row, String]] = None
  ): AesSpec[Row] =
    val values = AestheticMap
      .empty[Row]
      .updatedOption(Aesthetic.X, x)
      .updatedOption(Aesthetic.Y, y)
      .updatedOption(Aesthetic.XEnd, xEnd)
      .updatedOption(Aesthetic.YEnd, yEnd)
      .updatedOption(Aesthetic.XMin, xMin)
      .updatedOption(Aesthetic.XMax, xMax)
      .updatedOption(Aesthetic.YMin, yMin)
      .updatedOption(Aesthetic.YMax, yMax)
      .updatedOption(Aesthetic.Color, color)
      .updatedOption(Aesthetic.Fill, fill)
      .updatedOption(Aesthetic.Alpha, alpha)
      .updatedOption(Aesthetic.Size, size)
      .updatedOption(Aesthetic.Label, label)
      .updatedOption(Aesthetic.Group, group)
      .updatedOption(Aesthetic.Subpath, subpath)
    new AesSpec(values)

  def fromAesthetics[Row](aesthetics: AestheticMap[Row]): AesSpec[Row] =
    new AesSpec(aesthetics)

  def empty[Row]: AesSpec[Row] =
    new AesSpec(AestheticMap.empty)

  /** Compatibility identity for callers that previously normalized through the separate `AesEnv`
    * representation.
    */
  def fromEnv[Row](env: AesEnv[Row]): AesSpec[Row] =
    env

/** Complete mapping contract for one geom. Group-constant aesthetics may be mapped, but their
  * resolved values must remain constant within each structural [[GroupKey]].
  */
final case class GeomAestheticContract private (
    required: Vector[RequiredAesthetic],
    optional: Vector[Aesthetic[?]],
    groupConstant: Vector[Aesthetic[?]]
):
  require(required.map(_.aesthetic).distinct.length == required.length)
  require(optional.distinct.length == optional.length)
  require(required.forall(value => !optional.contains(value.aesthetic)))
  require(groupConstant.distinct.length == groupConstant.length)
  require(groupConstant.forall(optional.contains))
  require(
    groupConstant.forall(
      Vector(Aesthetic.Color, Aesthetic.Fill, Aesthetic.Alpha, Aesthetic.Size).contains
    )
  )

  val supported: Vector[Aesthetic[?]] =
    required.map(_.aesthetic) ++ optional

  def supports(aesthetic: Aesthetic[?]): Boolean =
    supported.contains(aesthetic)

object GeomAestheticContract:
  /** Checked constructor for ecosystem geoms. */
  def checked(
      required: Vector[RequiredAesthetic],
      optional: Vector[Aesthetic[?]],
      groupConstant: Vector[Aesthetic[?]] = Vector.empty
  ): Either[GraphicsError, GeomAestheticContract] =
    val requiredKeys = required.map(_.aesthetic)
    if requiredKeys.distinct.length != requiredKeys.length then
      Left(GraphicsError.InvalidGeomAestheticContract("required aesthetics must be distinct"))
    else if optional.distinct.length != optional.length then
      Left(GraphicsError.InvalidGeomAestheticContract("optional aesthetics must be distinct"))
    else if required.exists(value => optional.contains(value.aesthetic)) then
      Left(
        GraphicsError.InvalidGeomAestheticContract(
          "an aesthetic cannot be both required and optional"
        )
      )
    else if groupConstant.distinct.length != groupConstant.length then
      Left(
        GraphicsError.InvalidGeomAestheticContract("group-constant aesthetics must be distinct")
      )
    else if groupConstant.exists(value => !optional.contains(value)) then
      Left(
        GraphicsError.InvalidGeomAestheticContract(
          "group-constant aesthetics must also be optional"
        )
      )
    else if groupConstant.exists(value => !groupConstantAesthetics.contains(value)) then
      Left(
        GraphicsError.InvalidGeomAestheticContract(
          "only color, fill, alpha, and size may be group-constant"
        )
      )
    else Right(new GeomAestheticContract(required, optional, groupConstant))

  /** Throwing convenience for statically declared contracts. */
  def unsafe(
      required: Vector[RequiredAesthetic],
      optional: Vector[Aesthetic[?]],
      groupConstant: Vector[Aesthetic[?]] = Vector.empty
  ): GeomAestheticContract =
    checked(required, optional, groupConstant).orThrow

  private[intaglio] def create(
      required: Vector[RequiredAesthetic],
      optional: Vector[Aesthetic[?]],
      groupConstant: Vector[Aesthetic[?]] = Vector.empty
  ): GeomAestheticContract =
    unsafe(required, optional, groupConstant)

  private val groupConstantAesthetics: Vector[Aesthetic[?]] =
    Vector(Aesthetic.Color, Aesthetic.Fill, Aesthetic.Alpha, Aesthetic.Size)

/** Compiler context supplied to built-in and external geometry lowering. */
final case class GeomContext(layerIndex: Int, theme: Theme):
  require(layerIndex >= 0, "`layerIndex` must be non-negative")

/** Immutable geometry input after statistics, scales, row evaluation, and position adjustment. */
final case class GeomBatch[Row](rows: Vector[ResolvedRow[Row]], context: GeomContext):
  /** Partition rows by structural group while preserving first-encounter and row order. */
  def groups: Vector[Vector[ResolvedRow[Row]]] =
    if rows.forall(_.groupKey.isEmpty) then if rows.isEmpty then Vector.empty else Vector(rows)
    else
      val order = Vector.newBuilder[Option[GroupKey]]
      val buckets = scala.collection.mutable.HashMap
        .empty[Option[GroupKey], scala.collection.mutable.ArrayBuffer[ResolvedRow[Row]]]
      rows.foreach { row =>
        val bucket = buckets.getOrElseUpdate(
          row.groupKey, {
            order += row.groupKey
            scala.collection.mutable.ArrayBuffer.empty[ResolvedRow[Row]]
          }
        )
        bucket += row
      }
      order.result().map(key => buckets(key).toVector)

/** Open renderer-neutral geometry contract. Implementations declare their accepted aesthetics and
  * lower already-resolved rows to portable grobs; the compiler has no geometry registry.
  */
trait Geom:
  def label: String
  def contract: GeomAestheticContract

  def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]]

  /** Source-compatible required-aesthetic view. */
  final def requiredAesthetics: Vector[RequiredAesthetic] =
    contract.required

object Geom:
  case object Point extends Geom:
    val label: String = "point"
    val contract: GeomAestheticContract =
      GeomAestheticContract.create(
        required = Vector(RequiredAesthetic.X, RequiredAesthetic.Y),
        optional = Vector(
          Aesthetic.Color,
          Aesthetic.Fill,
          Aesthetic.Alpha,
          Aesthetic.Size,
          Aesthetic.Group
        )
      )
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.pointGrobs(batch.rows)

  case object Line extends Geom:
    val label: String = "line"
    val contract: GeomAestheticContract =
      GeomAestheticContract.create(
        required = Vector(RequiredAesthetic.X, RequiredAesthetic.Y),
        optional = Vector(Aesthetic.Color, Aesthetic.Alpha, Aesthetic.Group),
        groupConstant = Vector(Aesthetic.Color, Aesthetic.Alpha)
      )
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.lineGrobs(batch.rows)

  case object Text extends Geom:
    val label: String = "text"
    val contract: GeomAestheticContract =
      GeomAestheticContract.create(
        required = Vector(
          RequiredAesthetic.X,
          RequiredAesthetic.Y,
          RequiredAesthetic.Label
        ),
        optional = Vector(
          Aesthetic.Color,
          Aesthetic.Fill,
          Aesthetic.Alpha,
          Aesthetic.Group
        )
      )
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.textGrobs(batch.rows)

  case object Rect extends Geom:
    val label: String = "rect"
    val contract: GeomAestheticContract = boundedContract
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.boundedRectGrobs(batch.rows, label)

  case object Bar extends Geom:
    val label: String = "bar"
    val contract: GeomAestheticContract =
      GeomAestheticContract.create(
        required = Vector(RequiredAesthetic.X, RequiredAesthetic.Y),
        optional = Vector(
          Aesthetic.Color,
          Aesthetic.Fill,
          Aesthetic.Alpha,
          Aesthetic.Group
        )
      )
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.barGrobs(batch.rows)

  case object Segment extends Geom:
    val label: String = "segment"
    val contract: GeomAestheticContract =
      GeomAestheticContract.create(
        required = Vector(
          RequiredAesthetic.X,
          RequiredAesthetic.Y,
          RequiredAesthetic.XEnd,
          RequiredAesthetic.YEnd
        ),
        optional = Vector(Aesthetic.Color, Aesthetic.Alpha, Aesthetic.Group)
      )
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.segmentGrobs(batch.rows)

  case object ErrorBar extends Geom:
    val label: String = "errorbar"
    val contract: GeomAestheticContract =
      GeomAestheticContract.create(
        required = Vector(
          RequiredAesthetic.X,
          RequiredAesthetic.Y,
          RequiredAesthetic.YMin,
          RequiredAesthetic.YMax
        ),
        optional = Vector(Aesthetic.Color, Aesthetic.Alpha, Aesthetic.Group)
      )
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.errorBarGrobs(batch.rows)

  case object Ribbon extends Geom:
    val label: String = "ribbon"
    val contract: GeomAestheticContract = ribbonContract
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.ribbonGrobs(batch.rows, label)

  case object Area extends Geom:
    val label: String = "area"
    val contract: GeomAestheticContract = ribbonContract
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.ribbonGrobs(batch.rows, label)

  case object HLine extends Geom:
    val label: String = "hline"
    val contract: GeomAestheticContract = emptyContract
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      Left(GraphicsError.ReferenceLineRequiresAnnotation(label))

  case object VLine extends Geom:
    val label: String = "vline"
    val contract: GeomAestheticContract = emptyContract
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      Left(GraphicsError.ReferenceLineRequiresAnnotation(label))

  case object Tile extends Geom:
    val label: String = "tile"
    val contract: GeomAestheticContract = boundedContract
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.boundedRectGrobs(batch.rows, label)

  case object Polygon extends Geom:
    val label: String = "polygon"
    val contract: GeomAestheticContract =
      GeomAestheticContract.create(
        required = Vector(RequiredAesthetic.X, RequiredAesthetic.Y),
        optional = markStyles :+ Aesthetic.Subpath,
        groupConstant = Vector(Aesthetic.Color, Aesthetic.Fill, Aesthetic.Alpha)
      )
    def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
      GeomPhase.polygonGrobs(batch.rows)

  /** Stable source-compatible enumeration of the built-ins. */
  def values: Array[Geom] =
    builtIns.toArray

  private val markStyles: Vector[Aesthetic[?]] =
    Vector(Aesthetic.Color, Aesthetic.Fill, Aesthetic.Alpha, Aesthetic.Group)

  private val emptyContract: GeomAestheticContract =
    GeomAestheticContract.create(Vector.empty, Vector.empty)

  private val ribbonContract: GeomAestheticContract =
    GeomAestheticContract.create(
      required = Vector(
        RequiredAesthetic.X,
        RequiredAesthetic.Y,
        RequiredAesthetic.YMin,
        RequiredAesthetic.YMax
      ),
      optional = markStyles,
      groupConstant = Vector(Aesthetic.Color, Aesthetic.Fill, Aesthetic.Alpha)
    )

  private val boundedContract: GeomAestheticContract =
    GeomAestheticContract.create(
      required = Vector(
        RequiredAesthetic.X,
        RequiredAesthetic.Y,
        RequiredAesthetic.XMin,
        RequiredAesthetic.XMax,
        RequiredAesthetic.YMin,
        RequiredAesthetic.YMax
      ),
      optional = markStyles
    )

  private val builtIns: Vector[Geom] =
    Vector(
      Point,
      Line,
      Text,
      Rect,
      Bar,
      Segment,
      ErrorBar,
      Ribbon,
      Area,
      HLine,
      VLine,
      Tile,
      Polygon
    )

opaque type CoordinateRatio = Double

object CoordinateRatio:
  def apply(value: Double): Either[GraphicsError, CoordinateRatio] =
    if value.isFinite && value > 0.0 then Right(value)
    else Left(GraphicsError.InvalidCoordinateRatio(value))

  def unsafe(value: Double): CoordinateRatio =
    apply(value).orThrow

  extension (ratio: CoordinateRatio) def toDouble: Double = ratio

/** Logical compiler output supplied to an open coordinate implementation. */
final case class CoordInput(
    layers: Vector[TrainedLayer],
    ranges: Option[(Interval, Interval)]
)

/** Physical compiler output returned by an open coordinate implementation. */
final case class CoordResult(
    layers: Vector[TrainedLayer],
    ranges: Option[(Interval, Interval)]
)

/** Placement of logical x and y guides in physical panel space. */
final case class CoordGuideLayout(
    xSide: AxisSide,
    xRange: Interval,
    ySide: AxisSide,
    yRange: Interval
)

/** Open logical-to-physical coordinate contract. */
trait Coord:
  def clipping: Clip

  def transform(input: CoordInput): Either[GraphicsError, CoordResult] =
    CoordinateTransform.identity(input)

  def guideLayout(xRange: Interval, yRange: Interval): CoordGuideLayout =
    CoordGuideLayout(AxisSide.Bottom, xRange, AxisSide.Left, yRange)

  def panelAspect(
      xRange: Interval,
      yRange: Interval
  ): Either[GraphicsError, Option[CoordinateRatio]] =
    Right(None)

  def validateFacet: Either[GraphicsError, Unit] =
    Right(())

object Coord:
  final case class Cartesian(clip: Clip = Clip.On) extends Coord:
    val clipping: Clip = clip

  final case class Flipped(clip: Clip = Clip.On) extends Coord:
    val clipping: Clip = clip

    override def transform(input: CoordInput): Either[GraphicsError, CoordResult] =
      CoordinateTransform.transpose(input)

    override def guideLayout(xRange: Interval, yRange: Interval): CoordGuideLayout =
      CoordGuideLayout(AxisSide.Left, xRange, AxisSide.Bottom, yRange)

  final case class Fixed(ratio: CoordinateRatio, clip: Clip = Clip.On) extends Coord:
    val clipping: Clip = clip

    override def panelAspect(
        xRange: Interval,
        yRange: Interval
    ): Either[GraphicsError, Option[CoordinateRatio]] =
      if xRange.width <= 0.0 || yRange.width <= 0.0 then
        Left(GraphicsError.DegenerateFixedAspect(xRange.width, yRange.width))
      else CoordinateRatio(yRange.width / xRange.width * ratio.toDouble).map(Some(_))

    override def validateFacet: Either[GraphicsError, Unit] =
      Left(GraphicsError.FacetFixedCoordinates)

  def fixed(ratio: Double = 1.0, clip: Clip = Clip.On): Either[GraphicsError, Coord] =
    CoordinateRatio(ratio).map(Coord.Fixed(_, clip))

  def fixedUnsafe(ratio: Double = 1.0, clip: Clip = Clip.On): Coord =
    fixed(ratio, clip).orThrow

enum ReferenceLineOrientation(val label: String):
  case Horizontal extends ReferenceLineOrientation("horizontal")
  case Vertical extends ReferenceLineOrientation("vertical")

/** Whether a reference coordinate participates in its position scale.
  *
  * `Train` uses data-space coordinates: it expands an unscaled panel range or contributes an
  * observation to an existing continuous position scale. `Overlay` leaves training unchanged and
  * interprets the coordinate directly in the compiled panel's native space.
  */
enum AnnotationScalePolicy:
  case Train
  case Overlay

/** Facet participation for row-independent annotations. */
enum AnnotationFacetPolicy:
  case Repeat
  case Exclude

/** O(1) reference-line state. It contains no source rows or row accessors. */
final case class ReferenceLine private (
    orientation: ReferenceLineOrientation,
    coordinate: Double,
    scalePolicy: AnnotationScalePolicy,
    facetPolicy: AnnotationFacetPolicy
):
  def aesthetic: Aesthetic[Double] =
    orientation match
      case ReferenceLineOrientation.Horizontal => Aesthetic.Y
      case ReferenceLineOrientation.Vertical   => Aesthetic.X

  private[intaglio] def flipped: ReferenceLine =
    val next = orientation match
      case ReferenceLineOrientation.Horizontal => ReferenceLineOrientation.Vertical
      case ReferenceLineOrientation.Vertical   => ReferenceLineOrientation.Horizontal
    copy(orientation = next)

object ReferenceLine:
  def horizontal(
      y: Double,
      scale: AnnotationScalePolicy = AnnotationScalePolicy.Train,
      facets: AnnotationFacetPolicy = AnnotationFacetPolicy.Repeat
  ): ReferenceLine =
    ReferenceLine(ReferenceLineOrientation.Horizontal, y, scale, facets)

  def vertical(
      x: Double,
      scale: AnnotationScalePolicy = AnnotationScalePolicy.Train,
      facets: AnnotationFacetPolicy = AnnotationFacetPolicy.Repeat
  ): ReferenceLine =
    ReferenceLine(ReferenceLineOrientation.Vertical, x, scale, facets)

final case class Layer[Row] private (
    geom: Geom,
    stat: Stat[Row],
    data: Option[Vector[Row]],
    mapping: AesSpec[Row],
    inheritMapping: Boolean,
    params: Option[GraphicParams],
    position: Position = Position.Identity,
    annotation: Option[ReferenceLine] = None
):
  def effectiveMapping(plotMapping: AesSpec[Row]): AesSpec[Row] =
    if inheritMapping then mapping.inherit(plotMapping) else mapping

  def effectiveData(plotData: Vector[Row]): Vector[Row] =
    if annotation.nonEmpty then Vector.empty else data.getOrElse(plotData)

  /** Detach a layer from plot-level mapping inheritance. The rows of an independent layer are held
    * by [[PlotLayer.Independent]] itself, so `data` is cleared here rather than carrying a second
    * copy that could disagree with it.
    */
  private[intaglio] def selfContained: Layer[Row] =
    copy(data = None, inheritMapping = false)

object Layer:
  def point[Row](
      x: Row => Double,
      y: Row => Double,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      inheritMapping: Boolean = true,
      params: Option[GraphicParams] = None,
      position: Position = Position.Identity
  ): Layer[Row] =
    Layer(
      Geom.Point,
      Stat.Identity,
      data,
      mapping.withPosition(x, y),
      inheritMapping,
      params,
      position
    )

  def line[Row](
      x: Row => Double,
      y: Row => Double,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      inheritMapping: Boolean = true,
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(Geom.Line, Stat.Identity, data, mapping.withPosition(x, y), inheritMapping, params)

  def polygon[Row](
      x: Row => Double,
      y: Row => Double,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      inheritMapping: Boolean = true,
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(Geom.Polygon, Stat.Identity, data, mapping.withPosition(x, y), inheritMapping, params)

  def text[Row](
      x: Row => Double,
      y: Row => Double,
      label: Row => String,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      inheritMapping: Boolean = true,
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Text,
      Stat.Identity,
      data,
      mapping.withPosition(x, y).withLabel(label),
      inheritMapping,
      params
    )

  def rect[Row](
      xMin: Row => Double,
      xMax: Row => Double,
      yMin: Row => Double,
      yMax: Row => Double,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Rect,
      Stat.Identity,
      data,
      mapping.withBounds(midpoint(xMin, xMax), midpoint(yMin, yMax), xMin, xMax, yMin, yMax),
      inheritMapping = false,
      params
    )

  def segment[Row](
      x: Row => Double,
      y: Row => Double,
      xEnd: Row => Double,
      yEnd: Row => Double,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Segment,
      Stat.Identity,
      data,
      mapping.withSegment(x, y, xEnd, yEnd),
      inheritMapping = false,
      params
    )

  def errorBar[Row](
      x: Row => Double,
      yMin: Row => Double,
      yMax: Row => Double,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.ErrorBar,
      Stat.Identity,
      data,
      mapping.withYBounds(x, midpoint(yMin, yMax), yMin, yMax),
      inheritMapping = false,
      params
    )

  def ribbon[Row](
      x: Row => Double,
      yMin: Row => Double,
      yMax: Row => Double,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Ribbon,
      Stat.Identity,
      data,
      mapping.withYBounds(x, midpoint(yMin, yMax), yMin, yMax),
      inheritMapping = false,
      params
    )

  def area[Row](
      x: Row => Double,
      y: Row => Double,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Area,
      Stat.Identity,
      data,
      mapping.withYBounds(
        x,
        RowMapping.fromFunction(y).map(_ / 2.0),
        RowMapping.fromFunction(y).map(math.min(0.0, _)),
        RowMapping.fromFunction(y).map(math.max(0.0, _))
      ),
      inheritMapping = false,
      params
    )

  /** A row-independent horizontal annotation. `data` is retained for source compatibility but is
    * deliberately not stored or traversed.
    */
  def hline[Row](
      y: Double,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None,
      scale: AnnotationScalePolicy = AnnotationScalePolicy.Train,
      facets: AnnotationFacetPolicy = AnnotationFacetPolicy.Repeat
  ): Layer[Row] =
    Layer(
      geom = Geom.HLine,
      stat = Stat.Identity,
      data = None,
      mapping = AesSpec.empty[Row],
      inheritMapping = false,
      params = params,
      annotation = Some(ReferenceLine.horizontal(y, scale, facets))
    )

  /** A row-independent vertical annotation. `data` is retained for source compatibility but is
    * deliberately not stored or traversed.
    */
  def vline[Row](
      x: Double,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None,
      scale: AnnotationScalePolicy = AnnotationScalePolicy.Train,
      facets: AnnotationFacetPolicy = AnnotationFacetPolicy.Repeat
  ): Layer[Row] =
    Layer(
      geom = Geom.VLine,
      stat = Stat.Identity,
      data = None,
      mapping = AesSpec.empty[Row],
      inheritMapping = false,
      params = params,
      annotation = Some(ReferenceLine.vertical(x, scale, facets))
    )

  def tile[Row](
      x: Row => Double,
      y: Row => Double,
      width: Row => Double,
      height: Row => Double,
      data: Option[Vector[Row]] = None,
      mapping: AesSpec[Row] = AesSpec.empty[Row],
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Tile,
      Stat.Identity,
      data,
      mapping.withBounds(
        x,
        y,
        RowMapping.zipWith(x, width)(_ - _ / 2.0),
        RowMapping.zipWith(x, width)(_ + _ / 2.0),
        RowMapping.zipWith(y, height)(_ - _ / 2.0),
        RowMapping.zipWith(y, height)(_ + _ / 2.0)
      ),
      inheritMapping = false,
      params
    )

  /** Count observations by a discrete key and lower the computed result as bars. Position
    * aesthetics belong to the statistic, so this constructor deliberately does not accept raw `x`
    * or `y` mappings.
    */
  def count[Row](
      x: Row => String,
      data: Option[Vector[Row]] = None,
      order: CountOrder = CountOrder.Encountered,
      scaleName: GraphicsName = GraphicsName.unsafe("x"),
      padding: BandPadding = BandPadding.default,
      params: Option[GraphicParams] = None,
      group: Option[Row => String] = None,
      position: Position = Position.Stack()
  ): Layer[Row] =
    Layer(
      Geom.Bar,
      Stat.Count(x, order, scaleName, padding, group),
      data,
      AesSpec.empty[Row],
      inheritMapping = false,
      params,
      position
    )

  def histogram[Row](
      x: Row => Double,
      data: Option[Vector[Row]] = None,
      bins: HistogramBins = HistogramBins.default,
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(Geom.Bar, Stat.Bin(x, bins), data, AesSpec.empty[Row], inheritMapping = false, params)

  def summary[Row](
      x: Row => Double,
      y: Row => Double,
      data: Option[Vector[Row]] = None,
      interval: SummaryInterval = SummaryInterval.StandardError,
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Point,
      Stat.Summary(x, y, interval),
      data,
      AesSpec.empty[Row],
      inheritMapping = false,
      params
    )

  /** Median plus interquartile range at each x position, lowered as a point and interval. */
  def quantileSummary[Row](
      x: Row => Double,
      y: Row => Double,
      data: Option[Vector[Row]] = None,
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Point,
      Stat.QuantileSummary(x, y),
      data,
      AesSpec.empty[Row],
      inheritMapping = false,
      params
    )

  /** Right-continuous empirical CDF with ties collapsed and optional independent groups. */
  def ecdf[Row](
      x: Row => Double,
      data: Option[Vector[Row]] = None,
      group: Option[Row => String] = None,
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Line,
      Stat.Ecdf(x, group),
      data,
      AesSpec.empty[Row],
      inheritMapping = false,
      params
    )

  def density[Row](
      x: Row => Double,
      data: Option[Vector[Row]] = None,
      config: DensityConfig = DensityConfig.default,
      params: Option[GraphicParams] = None
  ): Layer[Row] =
    Layer(
      Geom.Line,
      Stat.Density(x, config),
      data,
      AesSpec.empty[Row],
      inheritMapping = false,
      params
    )

  def fromMapping[Row](
      geom: Geom,
      mapping: AesSpec[Row],
      data: Option[Vector[Row]] = None,
      inheritMapping: Boolean = true,
      stat: Stat[Row] = Stat.Identity,
      params: Option[GraphicParams] = None,
      position: Position = Position.Identity
  ): Either[GraphicsError, Layer[Row]] =
    val layer = Layer(geom, stat, data, mapping, inheritMapping, params, position)
    if inheritMapping then Right(layer)
    else validate(layer, mapping).map(_ => layer)

  private[intaglio] def validate[Row](
      layer: Layer[Row],
      mapping: AesSpec[Row]
  ): Either[GraphicsError, Unit] =
    layer.annotation match
      case Some(annotation) if !annotation.coordinate.isFinite =>
        Left(
          GraphicsError.InvalidAnnotationCoordinate(
            annotation.orientation.label,
            annotation.coordinate
          )
        )
      case Some(annotation)
          if (annotation.orientation == ReferenceLineOrientation.Horizontal && layer.geom != Geom.HLine) ||
            (annotation.orientation == ReferenceLineOrientation.Vertical && layer.geom != Geom.VLine) =>
        Left(GraphicsError.InvalidAnnotationGeom(annotation.orientation.label, layer.geom.label))
      case Some(_) =>
        validate(layer.geom, mapping)
      case None if layer.geom == Geom.HLine || layer.geom == Geom.VLine =>
        Left(GraphicsError.ReferenceLineRequiresAnnotation(layer.geom.label))
      case None =>
        for
          _ <- validateStatGeometry(layer.stat, layer.geom)
          _ <- layer.stat.contract.mapping match
            case StatMappingPolicy.Preserve => validate(layer.geom, mapping)
            case StatMappingPolicy.Replace  => validateReplacedMapping(layer.stat, mapping)
            case StatMappingPolicy.Consume  => Right(())
        yield ()

  private def validateStatGeometry(
      stat: Stat[?],
      geom: Geom
  ): Either[GraphicsError, Unit] =
    stat.contract.geometry match
      case StatGeometryPolicy.Any =>
        Right(())
      case StatGeometryPolicy.Require(expected) if geom == expected =>
        Right(())
      case StatGeometryPolicy.Require(_) =>
        Left(GraphicsError.InvalidStatGeom(stat.label, geom.label))

  private def validateReplacedMapping[Row](
      stat: Stat[Row],
      mapping: AesSpec[Row]
  ): Either[GraphicsError, Unit] =
    if mapping.x.nonEmpty then
      Left(GraphicsError.StatAestheticConflict(stat.label, Aesthetic.X.label))
    else if mapping.y.nonEmpty then
      Left(GraphicsError.StatAestheticConflict(stat.label, Aesthetic.Y.label))
    else
      mapping.bound.headOption match
        case Some(aesthetic) =>
          Left(GraphicsError.UnsupportedStatAesthetic(stat.label, aesthetic.label))
        case None => Right(())

  private[intaglio] def validate[Row](
      geom: Geom,
      mapping: AesSpec[Row]
  ): Either[GraphicsError, Unit] =
    geom.contract.required.find(required => !required.isPresent(mapping)) match
      case Some(aesthetic) => Left(GraphicsError.MissingAesthetic(geom.label, aesthetic.label))
      case None            =>
        mapping.bound.find(aesthetic => !geom.contract.supports(aesthetic)) match
          case Some(aesthetic) =>
            Left(GraphicsError.UnsupportedGeomAesthetic(geom.label, aesthetic.label))
          case None =>
            Right(())

  private def midpoint[Row](lower: Row => Double, upper: Row => Double): Row => Double =
    RowMapping.zipWith(lower, upper)((lo, hi) => lo + (hi - lo) / 2.0)

/** One plot layer with its row type kept together with its data, mapping, and statistic. `PlotRow`
  * is the plot-level row type; `Row` may differ for an explicitly independent layer.
  */
sealed trait PlotLayer[PlotRow]:
  type Row

  def layer: Layer[Row]
  def inheritsPlotData: Boolean
  def inheritsPlotMapping: Boolean
  def facetPolicy: Option[LayerFacetPolicy[Row]]

  final def geom: Geom = layer.geom
  final def stat: Stat[Row] = layer.stat
  final def position: Position = layer.position
  final def params: Option[GraphicParams] = layer.params

  private[intaglio] def effectiveData(plotData: Vector[PlotRow]): Vector[Row]
  private[intaglio] def effectiveMapping(plotMapping: AesSpec[PlotRow]): AesSpec[Row]
  private[intaglio] def facetSeedData(plotData: Vector[PlotRow]): Vector[PlotRow]
  private[intaglio] def panelDataByCell(
      plotData: Vector[PlotRow],
      facet: FacetSpec[PlotRow],
      layout: FacetLayout,
      layerIndex: Int
  ): Either[GraphicsError, Vector[Vector[Row]]]

object PlotLayer:
  type Aux[PlotRow, Row0] = PlotLayer[PlotRow] { type Row = Row0 }

  private final case class Inherited[PlotRow](layer: Layer[PlotRow]) extends PlotLayer[PlotRow]:
    type Row = PlotRow

    val inheritsPlotData: Boolean = layer.data.isEmpty && layer.annotation.isEmpty
    val inheritsPlotMapping: Boolean = layer.inheritMapping
    val facetPolicy: Option[LayerFacetPolicy[Row]] = None

    private[intaglio] def effectiveData(plotData: Vector[PlotRow]): Vector[Row] =
      layer.effectiveData(plotData)

    private[intaglio] def effectiveMapping(plotMapping: AesSpec[PlotRow]): AesSpec[Row] =
      layer.effectiveMapping(plotMapping)

    private[intaglio] def facetSeedData(plotData: Vector[PlotRow]): Vector[PlotRow] =
      layer.data.getOrElse(Vector.empty)

    private[intaglio] def panelDataByCell(
        plotData: Vector[PlotRow],
        facet: FacetSpec[PlotRow],
        layout: FacetLayout,
        layerIndex: Int
    ): Either[GraphicsError, Vector[Vector[Row]]] =
      facet.partition(layout, effectiveData(plotData), layerIndex)

  private final case class Independent[PlotRow, Row0](
      layer: Layer[Row0],
      data: Vector[Row0],
      policy: LayerFacetPolicy[Row0]
  ) extends PlotLayer[PlotRow]:
    type Row = Row0

    val inheritsPlotData: Boolean = false
    val inheritsPlotMapping: Boolean = false
    val facetPolicy: Option[LayerFacetPolicy[Row]] = Some(policy)

    private[intaglio] def effectiveData(plotData: Vector[PlotRow]): Vector[Row] =
      data

    private[intaglio] def effectiveMapping(plotMapping: AesSpec[PlotRow]): AesSpec[Row] =
      layer.mapping

    private[intaglio] def facetSeedData(plotData: Vector[PlotRow]): Vector[PlotRow] =
      Vector.empty

    private[intaglio] def panelDataByCell(
        plotData: Vector[PlotRow],
        facet: FacetSpec[PlotRow],
        layout: FacetLayout,
        layerIndex: Int
    ): Either[GraphicsError, Vector[Vector[Row]]] =
      val rows = effectiveData(plotData)
      val cells = layout.cells
      policy match
        case LayerFacetPolicy.Repeat =>
          Right(Vector.fill(cells.length)(rows))
        case LayerFacetPolicy.Exclude =>
          Right(Vector.fill(cells.length)(Vector.empty))
        case _ =>
          val out = Vector.fill(cells.length)(scala.collection.mutable.ArrayBuffer.empty[Row])
          var rowIndex = 0
          var result: Either[GraphicsError, Unit] = Right(())
          while rowIndex < rows.length && result.isRight do
            var cellIndex = 0
            while cellIndex < cells.length && result.isRight do
              policy.evaluate(cells(cellIndex), rows(rowIndex)) match
                case Right(true) =>
                  out(cellIndex) += rows(rowIndex)
                case Right(false) =>
                  ()
                case Left((contract, failure)) =>
                  result = Left(
                    GraphicsError.MappingEvaluationFailed(
                      "facet membership",
                      Some(layerIndex),
                      "facet-policy",
                      rowIndex,
                      contract,
                      failure
                    )
                  )
              cellIndex += 1
            rowIndex += 1
          result.map(_ => out.map(_.toVector))

  def inherited[Row](layer: Layer[Row]): PlotLayer.Aux[Row, Row] =
    Inherited(layer)

  def independent[PlotRow, Row](
      data: Vector[Row],
      layer: Layer[Row],
      facetPolicy: LayerFacetPolicy[Row]
  ): PlotLayer.Aux[PlotRow, Row] =
    Independent(layer.selfContained, data, facetPolicy)

final case class PlotLabels(
    title: Option[String] = None,
    subtitle: Option[String] = None,
    x: Option[String] = None,
    y: Option[String] = None
):
  def isEmpty: Boolean =
    title.isEmpty && subtitle.isEmpty && x.isEmpty && y.isEmpty

final case class Plot[Row] private (
    data: Vector[Row],
    mapping: AesSpec[Row],
    layers: Vector[PlotLayer[Row]],
    coord: Coord,
    labels: PlotLabels,
    facet: Option[FacetSpec[Row]]
):
  def addLayer(layer: Layer[Row]): Either[GraphicsError, Plot[Row]] =
    Layer
      .validate(layer, layer.effectiveMapping(mapping))
      .map(_ => copy(layers = layers :+ PlotLayer.inherited(layer)))

  /** Add a layer with a row type independent of the plot-level data. Its data and mapping are
    * self-contained, and its facet behavior is mandatory.
    */
  def addIndependentLayer[LayerRow](
      data: Vector[LayerRow],
      layer: Layer[LayerRow],
      facetPolicy: LayerFacetPolicy[LayerRow]
  ): Either[GraphicsError, Plot[Row]] =
    Layer
      .validate(layer, layer.mapping)
      .map(_ => copy(layers = layers :+ PlotLayer.independent(data, layer, facetPolicy)))

  def withMapping(mapping: AesSpec[Row]): Either[GraphicsError, Plot[Row]] =
    validateLayers(mapping).map(_ => copy(mapping = mapping))

  def withScale[In, A](binding: ScaleBinding[Row, In, A]): Either[GraphicsError, Plot[Row]] =
    mapping.bindScale(binding).flatMap(withMapping)

  /** Bind a row-free [[ScaleSpec]] or an already prepared [[Scale]] through a row accessor. The
    * plot already fixes `Row`, so callers never spell the `ScaleBinding` type parameters:
    * `encode(Aesthetic.X, _.x, xScale)`.
    */
  def encode[In, Out](
      aesthetic: Aesthetic[Out],
      value: Row => In,
      scale: ScaleValue[In, Out]
  ): Either[GraphicsError, Plot[Row]] =
    withScale(ScaleBinding(aesthetic, value, scale))

  def withCoord(coord: Coord): Plot[Row] =
    copy(coord = coord)

  def withLabels(labels: PlotLabels): Plot[Row] =
    copy(labels = labels)

  def withTitle(title: String): Plot[Row] =
    copy(labels = labels.copy(title = Some(title)))

  def withSubtitle(subtitle: String): Plot[Row] =
    copy(labels = labels.copy(subtitle = Some(subtitle)))

  def withAxisTitles(x: String, y: String): Plot[Row] =
    copy(labels = labels.copy(x = Some(x), y = Some(y)))

  def withFacet(spec: FacetSpec[Row]): Plot[Row] =
    copy(facet = Some(spec))

  def withoutFacet: Plot[Row] =
    copy(facet = None)

  def layerData(layer: Layer[Row]): Vector[Row] =
    layer.effectiveData(data)

  def layerMapping(layer: Layer[Row]): AesSpec[Row] =
    layer.effectiveMapping(mapping)

  private def validateLayers(plotMapping: AesSpec[Row]): Either[GraphicsError, Unit] =
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < layers.length && result.isRight do
      val packed = layers(idx)
      result = Layer.validate(packed.layer, packed.effectiveMapping(plotMapping))
      idx += 1
    result

object Plot:
  def apply[Row](data: Vector[Row]): Plot[Row] =
    Plot(data, AesSpec.empty, Vector.empty, Coord.Cartesian(), PlotLabels(), None)
