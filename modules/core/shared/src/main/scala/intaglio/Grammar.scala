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

  final case class Scaled[Row, In, A](value: Row => In, scale: Scale[In, A])
      extends AesValue[Row, A]:
    override def map(row: Row): Option[A] =
      scale.mapValue(value(row))

    override def isScaled: Boolean =
      true

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

  def scaled[Row, In, A](value: Row => In, scale: Scale[In, A]): AesValue[Row, A] =
    Scaled(value, scale)

  def scaledTotal[Row, In, A](value: Row => In, scale: Scale[In, A]): AesValue[Row, A] =
    Scaled(RowMapping.total(value), scale)

  def scaledChecked[Row, In, A](
      value: Row => Either[MappingFailure, In],
      scale: Scale[In, A]
  ): AesValue[Row, A] =
    Scaled(RowMapping.checked(value), scale)

  def scaledThrowing[Row, In, A](value: Row => In, scale: Scale[In, A]): AesValue[Row, A] =
    Scaled(RowMapping.throwing(value), scale)

/** How a compiled layer chooses its group identity. Inference deliberately names the contributing
  * aesthetics; row-level keys retain their raw pre-palette categories separately.
  */
enum GroupingDecision:
  case Ungrouped
  case Explicit
  case Inferred(aesthetics: Vector[Aesthetic[?]])

/** One raw categorical value contributing to an inferred group. */
final case class DiscreteGroupValue(aesthetic: Aesthetic[?], category: String)

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

final case class AesSpec[Row](
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
):
  /** Typed lookup against the canonical aesthetic storage. */
  def get[A](aesthetic: Aesthetic[A]): Option[AesValue[Row, A]] =
    aesthetic match
      case Aesthetic.X       => x
      case Aesthetic.Y       => y
      case Aesthetic.XEnd    => xEnd
      case Aesthetic.YEnd    => yEnd
      case Aesthetic.XMin    => xMin
      case Aesthetic.XMax    => xMax
      case Aesthetic.YMin    => yMin
      case Aesthetic.YMax    => yMax
      case Aesthetic.Color   => color
      case Aesthetic.Fill    => fill
      case Aesthetic.Alpha   => alpha
      case Aesthetic.Size    => size
      case Aesthetic.Label   => label
      case Aesthetic.Group   => group
      case Aesthetic.Subpath => subpath

  def isBound(aesthetic: Aesthetic[?]): Boolean =
    aesthetic match
      case Aesthetic.X       => x.nonEmpty
      case Aesthetic.Y       => y.nonEmpty
      case Aesthetic.XEnd    => xEnd.nonEmpty
      case Aesthetic.YEnd    => yEnd.nonEmpty
      case Aesthetic.XMin    => xMin.nonEmpty
      case Aesthetic.XMax    => xMax.nonEmpty
      case Aesthetic.YMin    => yMin.nonEmpty
      case Aesthetic.YMax    => yMax.nonEmpty
      case Aesthetic.Color   => color.nonEmpty
      case Aesthetic.Fill    => fill.nonEmpty
      case Aesthetic.Alpha   => alpha.nonEmpty
      case Aesthetic.Size    => size.nonEmpty
      case Aesthetic.Label   => label.nonEmpty
      case Aesthetic.Group   => group.nonEmpty
      case Aesthetic.Subpath => subpath.nonEmpty

  /** Bound aesthetics in declaration order. */
  def bound: Vector[Aesthetic[?]] =
    val out = Vector.newBuilder[Aesthetic[?]]
    Aesthetic.values.foreach { aesthetic =>
      if isBound(aesthetic) then out += aesthetic
    }
    out.result()

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
    aesthetic match
      case Aesthetic.X       => copy(x = Some(value))
      case Aesthetic.Y       => copy(y = Some(value))
      case Aesthetic.XEnd    => copy(xEnd = Some(value))
      case Aesthetic.YEnd    => copy(yEnd = Some(value))
      case Aesthetic.XMin    => copy(xMin = Some(value))
      case Aesthetic.XMax    => copy(xMax = Some(value))
      case Aesthetic.YMin    => copy(yMin = Some(value))
      case Aesthetic.YMax    => copy(yMax = Some(value))
      case Aesthetic.Color   => copy(color = Some(value))
      case Aesthetic.Fill    => copy(fill = Some(value))
      case Aesthetic.Alpha   => copy(alpha = Some(value))
      case Aesthetic.Size    => copy(size = Some(value))
      case Aesthetic.Label   => copy(label = Some(value))
      case Aesthetic.Group   => copy(group = Some(value))
      case Aesthetic.Subpath => copy(subpath = Some(value))

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
    AesSpec(
      x = inheritValue(x, parent.x),
      y = inheritValue(y, parent.y),
      xEnd = inheritValue(xEnd, parent.xEnd),
      yEnd = inheritValue(yEnd, parent.yEnd),
      xMin = inheritValue(xMin, parent.xMin),
      xMax = inheritValue(xMax, parent.xMax),
      yMin = inheritValue(yMin, parent.yMin),
      yMax = inheritValue(yMax, parent.yMax),
      color = inheritValue(color, parent.color),
      fill = inheritValue(fill, parent.fill),
      alpha = inheritValue(alpha, parent.alpha),
      size = inheritValue(size, parent.size),
      label = inheritValue(label, parent.label),
      group = inheritValue(group, parent.group),
      subpath = inheritValue(subpath, parent.subpath)
    )

  /** Scaled bindings in declaration order, each registered exactly once. */
  def scaledEntries: Vector[RegisteredScale[Row]] =
    Aesthetic.values.toVector.flatMap(scaledEntry)

  private[intaglio] def scaledEntry(aesthetic: Aesthetic[?]): Option[RegisteredScale[Row]] =
    aesthetic match
      case Aesthetic.X       => registered(Aesthetic.X, x)
      case Aesthetic.Y       => registered(Aesthetic.Y, y)
      case Aesthetic.XEnd    => registered(Aesthetic.XEnd, xEnd)
      case Aesthetic.YEnd    => registered(Aesthetic.YEnd, yEnd)
      case Aesthetic.XMin    => registered(Aesthetic.XMin, xMin)
      case Aesthetic.XMax    => registered(Aesthetic.XMax, xMax)
      case Aesthetic.YMin    => registered(Aesthetic.YMin, yMin)
      case Aesthetic.YMax    => registered(Aesthetic.YMax, yMax)
      case Aesthetic.Color   => registered(Aesthetic.Color, color)
      case Aesthetic.Fill    => registered(Aesthetic.Fill, fill)
      case Aesthetic.Alpha   => registered(Aesthetic.Alpha, alpha)
      case Aesthetic.Size    => registered(Aesthetic.Size, size)
      case Aesthetic.Label   => registered(Aesthetic.Label, label)
      case Aesthetic.Group   => registered(Aesthetic.Group, group)
      case Aesthetic.Subpath => registered(Aesthetic.Subpath, subpath)

  private def registered[A](
      aesthetic: Aesthetic[A],
      value: Option[AesValue[Row, A]]
  ): Option[RegisteredScale[Row]] =
    value match
      case Some(scaled: AesValue.Scaled[Row, ?, A]) =>
        Some(RegisteredScale.erased(aesthetic, scaled))
      case _ =>
        None

  private def inheritValue[A](
      local: Option[AesValue[Row, A]],
      parent: Option[AesValue[Row, A]]
  ): Option[AesValue[Row, A]] =
    local match
      case Some(value) if value.isScaled => local
      case _                             =>
        parent match
          case Some(value) if value.isScaled => parent
          case parentValue                   => local.orElse(parentValue)

  def contramap[Input](f: Input => Row): AesSpec[Input] =
    AesSpec(
      x = x.map(_.contramap(f)),
      y = y.map(_.contramap(f)),
      xEnd = xEnd.map(_.contramap(f)),
      yEnd = yEnd.map(_.contramap(f)),
      xMin = xMin.map(_.contramap(f)),
      xMax = xMax.map(_.contramap(f)),
      yMin = yMin.map(_.contramap(f)),
      yMax = yMax.map(_.contramap(f)),
      color = color.map(_.contramap(f)),
      fill = fill.map(_.contramap(f)),
      alpha = alpha.map(_.contramap(f)),
      size = size.map(_.contramap(f)),
      label = label.map(_.contramap(f)),
      group = group.map(_.contramap(f)),
      subpath = subpath.map(_.contramap(f))
    )

  def position: Option[Position2[Row]] =
    for
      px <- x
      py <- y
    yield Position2(px, py)

  def withPosition(x: Row => Double, y: Row => Double): AesSpec[Row] =
    copy(x = Some(AesValue.direct(x)), y = Some(AesValue.direct(y)))

  def withSegment(
      x: Row => Double,
      y: Row => Double,
      xEnd: Row => Double,
      yEnd: Row => Double
  ): AesSpec[Row] =
    copy(
      x = Some(AesValue.direct(x)),
      y = Some(AesValue.direct(y)),
      xEnd = Some(AesValue.direct(xEnd)),
      yEnd = Some(AesValue.direct(yEnd))
    )

  def withBounds(
      x: Row => Double,
      y: Row => Double,
      xMin: Row => Double,
      xMax: Row => Double,
      yMin: Row => Double,
      yMax: Row => Double
  ): AesSpec[Row] =
    copy(
      x = Some(AesValue.direct(x)),
      y = Some(AesValue.direct(y)),
      xMin = Some(AesValue.direct(xMin)),
      xMax = Some(AesValue.direct(xMax)),
      yMin = Some(AesValue.direct(yMin)),
      yMax = Some(AesValue.direct(yMax))
    )

  def withYBounds(
      x: Row => Double,
      y: Row => Double,
      yMin: Row => Double,
      yMax: Row => Double
  ): AesSpec[Row] =
    copy(
      x = Some(AesValue.direct(x)),
      y = Some(AesValue.direct(y)),
      yMin = Some(AesValue.direct(yMin)),
      yMax = Some(AesValue.direct(yMax))
    )

  def withColor(f: Row => Rgba): AesSpec[Row] =
    copy(color = Some(AesValue.direct(f)))

  def withColor(value: Rgba): AesSpec[Row] =
    copy(color = Some(AesValue.constant(value)))

  def withFill(f: Row => Rgba): AesSpec[Row] =
    copy(fill = Some(AesValue.direct(f)))

  def withFill(value: Rgba): AesSpec[Row] =
    copy(fill = Some(AesValue.constant(value)))

  def withAlpha(f: Row => Double): AesSpec[Row] =
    copy(alpha = Some(AesValue.direct(f)))

  def withAlpha(value: Double): AesSpec[Row] =
    copy(alpha = Some(AesValue.constant(value)))

  def withSize(f: Row => Double): AesSpec[Row] =
    copy(size = Some(AesValue.direct(f)))

  def withSize(value: Double): AesSpec[Row] =
    copy(size = Some(AesValue.constant(value)))

  def withLabel(f: Row => String): AesSpec[Row] =
    copy(label = Some(AesValue.direct(f)))

  def withLabel(value: String): AesSpec[Row] =
    copy(label = Some(AesValue.constant(value)))

  def withGroup(f: Row => String): AesSpec[Row] =
    copy(group = Some(AesValue.direct(f)))

  def withGroup(value: String): AesSpec[Row] =
    copy(group = Some(AesValue.constant(value)))

  def withSubpath(f: Row => String): AesSpec[Row] =
    copy(subpath = Some(AesValue.direct(f)))

  def withSubpath(value: String): AesSpec[Row] =
    copy(subpath = Some(AesValue.constant(value)))

  /** Compatibility view: `AesSpec` itself is the canonical environment. */
  def env: AesEnv[Row] =
    this

  def bindScale[In, A](binding: ScaleBinding[Row, In, A]): Either[GraphicsError, AesSpec[Row]] =
    bind(binding)

object AesSpec:
  def empty[Row]: AesSpec[Row] =
    AesSpec()

  /** Compatibility identity for callers that previously normalized through the separate `AesEnv`
    * representation.
    */
  def fromEnv[Row](env: AesEnv[Row]): AesSpec[Row] =
    env

enum Geom(val label: String):
  case Point extends Geom("point")
  case Line extends Geom("line")
  case Text extends Geom("text")
  case Rect extends Geom("rect")
  case Bar extends Geom("bar")
  case Segment extends Geom("segment")
  case ErrorBar extends Geom("errorbar")
  case Ribbon extends Geom("ribbon")
  case Area extends Geom("area")
  case HLine extends Geom("hline")
  case VLine extends Geom("vline")
  case Tile extends Geom("tile")
  case Polygon extends Geom("polygon")

  def requiredAesthetics: Vector[RequiredAesthetic] =
    this match
      case Point   => Vector(RequiredAesthetic.X, RequiredAesthetic.Y)
      case Line    => Vector(RequiredAesthetic.X, RequiredAesthetic.Y)
      case Polygon => Vector(RequiredAesthetic.X, RequiredAesthetic.Y)
      case Text    => Vector(RequiredAesthetic.X, RequiredAesthetic.Y, RequiredAesthetic.Label)
      case Bar     => Vector(RequiredAesthetic.X, RequiredAesthetic.Y)
      case HLine | VLine => Vector.empty
      case Segment       =>
        Vector(
          RequiredAesthetic.X,
          RequiredAesthetic.Y,
          RequiredAesthetic.XEnd,
          RequiredAesthetic.YEnd
        )
      case ErrorBar | Ribbon | Area =>
        Vector(
          RequiredAesthetic.X,
          RequiredAesthetic.Y,
          RequiredAesthetic.YMin,
          RequiredAesthetic.YMax
        )
      case Rect | Tile =>
        Vector(
          RequiredAesthetic.X,
          RequiredAesthetic.Y,
          RequiredAesthetic.XMin,
          RequiredAesthetic.XMax,
          RequiredAesthetic.YMin,
          RequiredAesthetic.YMax
        )

opaque type CoordinateRatio = Double

object CoordinateRatio:
  def apply(value: Double): Either[GraphicsError, CoordinateRatio] =
    if value.isFinite && value > 0.0 then Right(value)
    else Left(GraphicsError.InvalidCoordinateRatio(value))

  def unsafe(value: Double): CoordinateRatio =
    apply(value).orThrow

  extension (ratio: CoordinateRatio) def toDouble: Double = ratio

enum Coord:
  case Cartesian(clip: Clip = Clip.On)
  case Flipped(clip: Clip = Clip.On)
  case Fixed(ratio: CoordinateRatio, clip: Clip = Clip.On)

  def clipping: Clip =
    this match
      case Cartesian(value) => value
      case Flipped(value)   => value
      case Fixed(_, value)  => value

object Coord:
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
        Right(())
      case None if layer.geom == Geom.HLine || layer.geom == Geom.VLine =>
        Left(GraphicsError.ReferenceLineRequiresAnnotation(layer.geom.label))
      case None =>
        layer.stat match
          case Stat.Identity =>
            validate(layer.geom, mapping)
          case _: Stat.Count[?] =>
            validateComputedStat(layer, mapping, Geom.Bar)
          case _: Stat.Bin[?] =>
            validateComputedStat(layer, mapping, Geom.Bar)
          case _: Stat.Summary[?] =>
            validateComputedStat(layer, mapping, Geom.Point)
          case _: Stat.Density[?] =>
            validateComputedStat(layer, mapping, Geom.Line)

  private def validateComputedStat[Row](
      layer: Layer[Row],
      mapping: AesSpec[Row],
      expectedGeom: Geom
  ): Either[GraphicsError, Unit] =
    if layer.geom != expectedGeom then
      Left(GraphicsError.InvalidStatGeom(layer.stat.label, layer.geom.label))
    else if mapping.x.nonEmpty then
      Left(GraphicsError.StatAestheticConflict(layer.stat.label, Aesthetic.X.label))
    else if mapping.y.nonEmpty then
      Left(GraphicsError.StatAestheticConflict(layer.stat.label, Aesthetic.Y.label))
    else
      mapping.bound.headOption match
        case Some(aesthetic) =>
          Left(GraphicsError.UnsupportedStatAesthetic(layer.stat.label, aesthetic.label))
        case None => Right(())

  private[intaglio] def validate[Row](
      geom: Geom,
      mapping: AesSpec[Row]
  ): Either[GraphicsError, Unit] =
    geom.requiredAesthetics.find(required => !required.isPresent(mapping)) match
      case Some(aesthetic) => Left(GraphicsError.MissingAesthetic(geom.label, aesthetic.label))
      case None            => Right(())

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
  private[intaglio] def panelData(
      plotData: Vector[PlotRow],
      facet: FacetSpec[PlotRow],
      cell: FacetCell,
      layerIndex: Int
  ): Either[GraphicsError, Vector[Row]]

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
      effectiveData(plotData)

    private[intaglio] def panelData(
        plotData: Vector[PlotRow],
        facet: FacetSpec[PlotRow],
        cell: FacetCell,
        layerIndex: Int
    ): Either[GraphicsError, Vector[Row]] =
      facet.panelData(cell, effectiveData(plotData), layerIndex)

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

    private[intaglio] def panelData(
        plotData: Vector[PlotRow],
        facet: FacetSpec[PlotRow],
        cell: FacetCell,
        layerIndex: Int
    ): Either[GraphicsError, Vector[Row]] =
      val rows = effectiveData(plotData)
      val out = Vector.newBuilder[Row]
      var rowIndex = 0
      var result: Either[GraphicsError, Unit] = Right(())
      while rowIndex < rows.length && result.isRight do
        policy.evaluate(cell, rows(rowIndex)) match
          case Right(true) =>
            out += rows(rowIndex)
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
        rowIndex += 1
      result.map(_ => out.result())

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

  /** Bind a prepared scale to an aesthetic through a row accessor. The plot already fixes `Row`, so
    * callers never spell the `ScaleBinding` type parameters: `encode(Aesthetic.X, _.x, xScale)`.
    */
  def encode[In, Out](
      aesthetic: Aesthetic[Out],
      value: Row => In,
      scale: Scale[In, Out]
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
