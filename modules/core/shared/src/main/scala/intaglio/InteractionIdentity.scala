package intaglio.interaction

import intaglio.{IntaglioError, SemanticId}
import scala.util.control.NonFatal

/** Failures at the portable interaction boundary. */
enum InteractionError extends IntaglioError:
  case InvalidValue(field: String, reason: String)
  case KeySpaceMismatch(expected: String, actual: String)
  case CodecMismatch(expected: String, actual: String)
  case CodecFailure(operation: String, reason: String)
  case CallbackFailed(operation: String)
  case NonCanonicalKey
  case DuplicateEntity(firstIndex: Int, duplicateIndex: Int)
  case ForeignKey(index: Int)
  case StaleRevision(expected: String, actual: String)
  case MembershipUnavailable(capability: MembershipCapability)
  case IndexOutOfBounds(index: Int, size: Int)
  case UnsupportedCapability(component: String)
  case LoweringMismatch(component: String, expected: Int, actual: Int)
  case UnknownEntity(layerIndex: Int, outputIndex: Int)

  def message: String = this match
    case InvalidValue(field, reason)        => s"Invalid $field: $reason"
    case KeySpaceMismatch(expected, actual) => s"Expected key space '$expected', received '$actual'"
    case CodecMismatch(expected, actual)    => s"Expected key codec '$expected', received '$actual'"
    case CodecFailure(operation, reason)    => s"Key codec $operation failed: $reason"
    case CallbackFailed(operation)          => s"Interaction callback failed during $operation"
    case NonCanonicalKey                    => "Key payload is not canonical for its codec"
    case DuplicateEntity(first, duplicate)  =>
      s"Entity key at index $duplicate duplicates index $first"
    case ForeignKey(index)               => s"Key at index $index belongs to another key space"
    case StaleRevision(expected, actual) =>
      s"Expected data revision '$expected', received '$actual'"
    case MembershipUnavailable(capability) => s"Exact membership is unavailable under $capability"
    case IndexOutOfBounds(index, size)     => s"Interaction index $index is outside [0, $size)"
    case UnsupportedCapability(component)  =>
      s"Interaction capability is not declared for $component"
    case LoweringMismatch(component, expected, actual) =>
      s"Interaction lowering for $component expected $expected grobs, received $actual"
    case UnknownEntity(layerIndex, outputIndex) =>
      s"Layer $layerIndex output $outputIndex names an entity outside its source data"

private[interaction] object Checked:
  def callback[A](operation: String)(
      body: => Either[InteractionError, A]
  ): Either[InteractionError, A] =
    try body
    catch case NonFatal(_) => Left(InteractionError.CallbackFailed(operation))

  def name(field: String, value: String): Either[InteractionError, SemanticId] =
    if value == null then Left(InteractionError.InvalidValue(field, "must not be null"))
    else SemanticId(value).left.map(error => InteractionError.InvalidValue(field, error.message))

/** A caller-owned revision, distinct from an entity key. Changing it invalidates old membership. */
final class DataRevision private (val value: String):
  override def equals(other: Any): Boolean = other match
    case that: DataRevision => value == that.value
    case _                  => false
  override def hashCode(): Int = value.hashCode
  override def toString: String = s"DataRevision($value)"

object DataRevision:
  def apply(value: String): Either[InteractionError, DataRevision] =
    if value == null || value.trim.isEmpty then
      Left(InteractionError.InvalidValue("data revision", "must not be blank"))
    else Right(new DataRevision(value))

/** Changes whenever compiled targets or geometry change, even if the source dataset is unchanged.
  */
final class PlanRevision private (val value: String):
  override def equals(other: Any): Boolean = other match
    case that: PlanRevision => value == that.value
    case _                  => false
  override def hashCode(): Int = value.hashCode
  override def toString: String = s"PlanRevision($value)"

object PlanRevision:
  def apply(value: String): Either[InteractionError, PlanRevision] =
    if value == null || value.trim.isEmpty then
      Left(InteractionError.InvalidValue("plan revision", "must not be blank"))
    else Right(new PlanRevision(value))

/** Transport data is deliberately untrusted until a KeySpace decodes it. */
final case class KeyToken(namespace: String, codec: String, version: Int, payload: String)

/** A named codec declares a portable key schema. Encoded payloads must round-trip canonically. */
final class KeyCodec[A] private (
    val name: SemanticId,
    val version: Int,
    write: A => Either[String, String],
    read: String => Either[String, A]
):
  private[interaction] def encode(value: A): Either[InteractionError, String] =
    Checked.callback("encode") {
      write(value).left.map(InteractionError.CodecFailure("encode", _)).flatMap { result =>
        if result == null then
          Left(InteractionError.InvalidValue("key payload", "must not be null"))
        else Right(result)
      }
    }

  private[interaction] def decode(value: String): Either[InteractionError, A] =
    if value == null then Left(InteractionError.InvalidValue("key payload", "must not be null"))
    else
      Checked.callback("decode") {
        read(value).left.map(InteractionError.CodecFailure("decode", _))
      }

object KeyCodec:
  def apply[A](name: String, version: Int)(
      encode: A => Either[String, String],
      decode: String => Either[String, A]
  ): Either[InteractionError, KeyCodec[A]] =
    for
      id <- Checked.name("codec name", name)
      _ <- Either.cond(
        version > 0,
        (),
        InteractionError.InvalidValue("codec version", "must be positive")
      )
    yield new KeyCodec(id, version, encode, decode)

  val text: KeyCodec[String] =
    new KeyCodec(SemanticId.unsafe("text"), 1, Right(_), Right(_))

  val integer: KeyCodec[Int] =
    new KeyCodec(
      SemanticId.unsafe("integer"),
      1,
      value => Right(value.toString),
      value => value.toIntOption.toRight("expected a signed 32-bit integer")
    )

/** Share this instance to link typed views. Matching labels alone do not join key spaces. A
  * transport token can be explicitly rebound through readEntity/readLink after schema checks.
  */
final class KeySpace[A] private (val namespace: SemanticId, val codec: KeyCodec[A]):
  def entity(value: A): Either[InteractionError, EntityKey[A]] =
    codec.encode(value).flatMap(payload => readEntity(token(payload)))

  def link(value: A): Either[InteractionError, LinkKey[A]] =
    codec.encode(value).flatMap(payload => readLink(token(payload)))

  def readEntity(value: KeyToken): Either[InteractionError, EntityKey[A]] =
    decode(value).map(decoded => new EntityKey(this, decoded, value.payload))

  def readLink(value: KeyToken): Either[InteractionError, LinkKey[A]] =
    decode(value).map(decoded => new LinkKey(this, decoded, value.payload))

  private[interaction] def token(payload: String): KeyToken =
    KeyToken(namespace.value, codec.name.value, codec.version, payload)

  private def decode(value: KeyToken): Either[InteractionError, A] =
    if value.namespace != namespace.value then
      Left(InteractionError.KeySpaceMismatch(namespace.value, value.namespace))
    else if value.codec != codec.name.value || value.version != codec.version then
      Left(
        InteractionError.CodecMismatch(
          s"${codec.name.value}/${codec.version}",
          s"${value.codec}/${value.version}"
        )
      )
    else
      for
        decoded <- codec.decode(value.payload)
        encoded <- codec.encode(decoded)
        _ <- Either.cond(encoded == value.payload, (), InteractionError.NonCanonicalKey)
      yield decoded

object KeySpace:
  def apply[A](namespace: String, codec: KeyCodec[A]): Either[InteractionError, KeySpace[A]] =
    Checked.name("key space", namespace).map(new KeySpace(_, codec))

/** Unique observation identity. Equality uses the actual key-space witness and canonical payload.
  */
final class EntityKey[A] private[interaction] (
    val space: KeySpace[A],
    val value: A,
    private val payload: String
):
  def token: KeyToken = space.token(payload)
  override def equals(other: Any): Boolean = other match
    case that: EntityKey[?] => (space eq that.space) && payload == that.payload
    case _                  => false
  override def hashCode(): Int = 31 * space.hashCode() + payload.hashCode

/** Grouping identity; many observations may intentionally share this key. */
final class LinkKey[A] private[interaction] (
    val space: KeySpace[A],
    val value: A,
    private val payload: String
):
  def token: KeyToken = space.token(payload)
  override def equals(other: Any): Boolean = other match
    case that: LinkKey[?] => (space eq that.space) && payload == that.payload
    case _                => false
  override def hashCode(): Int = 31 * space.hashCode() + payload.hashCode

/** Validated keys without retained source rows or row-equality-based reverse lookup. */
final class EntityIndex[A] private (
    val space: KeySpace[A],
    val keys: Vector[EntityKey[A]],
    positions: Map[EntityKey[A], Int]
):
  def indexOf(key: EntityKey[A]): Option[Int] = positions.get(key)

object EntityIndex:
  def build[Row, A](space: KeySpace[A], rows: Vector[Row])(
      key: Row => A
  ): Either[InteractionError, EntityIndex[A]] =
    val keys = Vector.newBuilder[EntityKey[A]]
    var positions = Map.empty[EntityKey[A], Int]
    var result: Either[InteractionError, Unit] = Right(())
    var index = 0
    while index < rows.length && result.isRight do
      val current = index
      result = Checked
        .callback("entity key accessor") {
          space.entity(key(rows(current)))
        }
        .flatMap { entity =>
          positions.get(entity) match
            case Some(first) => Left(InteractionError.DuplicateEntity(first, current))
            case None        =>
              positions = positions.updated(entity, current)
              keys += entity
              Right(())
        }
      index += 1
    result.map(_ => new EntityIndex(space, keys.result(), positions))

/** A plan-local visual identity; this must not be used as a persistent observation key. */
final class VisualTargetId private[interaction] (
    val plan: SemanticId,
    val revision: PlanRevision,
    val scope: SemanticId,
    val ordinal: Int
):
  override def equals(other: Any): Boolean = other match
    case that: VisualTargetId =>
      plan == that.plan && revision == that.revision && scope == that.scope && ordinal == that.ordinal
    case _ => false
  override def hashCode(): Int = (plan, revision, scope, ordinal).hashCode

/** Constant-size addressing for a contiguous batch of logical marks. */
final class TargetSeries private (
    val plan: SemanticId,
    val revision: PlanRevision,
    val scope: SemanticId,
    val first: Int,
    val size: Int
):
  def at(index: Int): Either[InteractionError, VisualTargetId] =
    if index < 0 || index >= size then Left(InteractionError.IndexOutOfBounds(index, size))
    else Right(new VisualTargetId(plan, revision, scope, first + index))

object TargetSeries:
  def apply(
      plan: SemanticId,
      revision: PlanRevision,
      scope: SemanticId,
      size: Int,
      first: Int = 0
  ): Either[InteractionError, TargetSeries] =
    if size < 0 || first < 0 || first.toLong + size.toLong > Int.MaxValue.toLong + 1L then
      Left(
        InteractionError
          .InvalidValue("target series", "requires nonnegative, representable ordinals")
      )
    else Right(new TargetSeries(plan, revision, scope, first, size))

enum MembershipCapability:
  case Unavailable, CountOnly, Representative, Partial, Exact, Deferred

/** Retained evidence for one statistical target. Only Exact can yield an immediate member set. */
final class Membership[A] private (
    val space: KeySpace[A],
    val revision: DataRevision,
    val capability: MembershipCapability,
    val total: Option[Int],
    val retainedKeys: Vector[EntityKey[A]],
    val resolver: Option[SemanticId]
):
  def exactKeys(currentRevision: DataRevision): Either[InteractionError, Vector[EntityKey[A]]] =
    if currentRevision != revision then
      Left(InteractionError.StaleRevision(revision.value, currentRevision.value))
    else if capability != MembershipCapability.Exact then
      Left(InteractionError.MembershipUnavailable(capability))
    else Right(retainedKeys)

object Membership:
  def unavailable[A](space: KeySpace[A], revision: DataRevision): Membership[A] =
    new Membership(space, revision, MembershipCapability.Unavailable, None, Vector.empty, None)

  def countOnly[A](
      space: KeySpace[A],
      revision: DataRevision,
      total: Int
  ): Either[InteractionError, Membership[A]] =
    make(space, revision, MembershipCapability.CountOnly, total, Vector.empty)

  def representative[A](
      space: KeySpace[A],
      revision: DataRevision,
      total: Int,
      key: EntityKey[A]
  ): Either[InteractionError, Membership[A]] =
    make(space, revision, MembershipCapability.Representative, total, Vector(key))

  def partial[A](
      space: KeySpace[A],
      revision: DataRevision,
      total: Int,
      keys: Vector[EntityKey[A]]
  ): Either[InteractionError, Membership[A]] =
    if keys.length >= total then
      Left(InteractionError.InvalidValue("partial membership", "must omit at least one member"))
    else make(space, revision, MembershipCapability.Partial, total, keys)

  def exact[A](
      space: KeySpace[A],
      revision: DataRevision,
      keys: Vector[EntityKey[A]]
  ): Either[InteractionError, Membership[A]] =
    make(space, revision, MembershipCapability.Exact, keys.length, keys)

  def deferred[A](
      space: KeySpace[A],
      revision: DataRevision,
      total: Int,
      resolver: SemanticId
  ): Either[InteractionError, Membership[A]] =
    make(space, revision, MembershipCapability.Deferred, total, Vector.empty, Some(resolver))

  private def make[A](
      space: KeySpace[A],
      revision: DataRevision,
      capability: MembershipCapability,
      total: Int,
      keys: Vector[EntityKey[A]],
      resolver: Option[SemanticId] = None
  ): Either[InteractionError, Membership[A]] =
    if total < 0 || keys.length > total then
      Left(
        InteractionError
          .InvalidValue("membership count", "must be nonnegative and include all retained keys")
      )
    else
      var seen = Map.empty[EntityKey[A], Int]
      var result: Either[InteractionError, Unit] = Right(())
      var index = 0
      while index < keys.length && result.isRight do
        val key = keys(index)
        if !(key.space eq space) then result = Left(InteractionError.ForeignKey(index))
        else
          seen.get(key) match
            case Some(first) => result = Left(InteractionError.DuplicateEntity(first, index))
            case None        => seen = seen.updated(key, index)
        index += 1
      result.map(_ => new Membership(space, revision, capability, Some(total), keys, resolver))
