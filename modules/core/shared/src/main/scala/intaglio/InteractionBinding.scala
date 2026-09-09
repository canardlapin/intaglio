package intaglio.interaction

import intaglio.*

/** Typed link projections from one target. Query with the same key-space witness used to bind it.
  */
final class LinkKeys private[interaction] (private val values: Vector[PackedLinkKey]):
  def size: Int = values.length
  def in[A](space: KeySpace[A]): Vector[LinkKey[A]] = values.flatMap(_.in(space))

object LinkKeys:
  val empty: LinkKeys = new LinkKeys(Vector.empty)
  private[interaction] def apply(values: Vector[PackedLinkKey]): LinkKeys =
    new LinkKeys(values.distinctBy(_.key))

private[interaction] sealed trait PackedLinkKey:
  type Value
  val key: LinkKey[Value]
  final def in[A](space: KeySpace[A]): Option[LinkKey[A]] =
    // KeySpace is invariant. Sharing this exact instance witnesses Value = A; matching labels
    // or encoded payloads alone never permit erasure recovery.
    if key.space eq space then Some(key.asInstanceOf[LinkKey[A]]) else None

private[interaction] object PackedLinkKey:
  def apply[A](value: LinkKey[A]): PackedLinkKey = new PackedLinkKey:
    type Value = A
    val key = value

/** A source accessor is kept with the exact PlotLayer package that owns its row type. Independent
  * rows need no cast to the plot's root row type, and each layer may use its own entity namespace.
  */
sealed abstract class LayerBinding[PlotRow, A]:
  type Row
  val source: PlotLayer.Aux[PlotRow, Row]
  val space: KeySpace[A]
  private[interaction] val key: Row => A
  private[interaction] val links: Vector[Row => Either[InteractionError, PackedLinkKey]]

  def withLinks[B](space: KeySpace[B])(value: Row => B): LayerBinding.Aux[PlotRow, Row, A] =
    val projection: Row => Either[InteractionError, PackedLinkKey] = row =>
      Checked.callback("link key accessor")(space.link(value(row)).map(PackedLinkKey(_)))
    LayerBinding.create(source, this.space, key, links :+ projection)

  private[interaction] final def prepare(
      plotData: Vector[PlotRow]
  ): Either[InteractionError, PreparedLayerBinding[PlotRow, A]] =
    EntityIndex.build(space, source.effectiveData(plotData))(key).map { index =>
      PreparedLayerBinding.create(source, space, key, links, index)
    }

object LayerBinding:
  type Aux[PlotRow, Row0, A] = LayerBinding[PlotRow, A] { type Row = Row0 }

  def apply[PlotRow, A](source: PlotLayer[PlotRow], space: KeySpace[A])(
      key: source.Row => A
  ): Aux[PlotRow, source.Row, A] = create(source, space, key, Vector.empty)

  private[interaction] def create[PlotRow, Row0, A](
      packageValue: PlotLayer.Aux[PlotRow, Row0],
      keySpace: KeySpace[A],
      accessor: Row0 => A,
      projections: Vector[Row0 => Either[InteractionError, PackedLinkKey]]
  ): Aux[PlotRow, Row0, A] = new LayerBinding[PlotRow, A]:
    type Row = Row0
    val source = packageValue
    val space = keySpace
    private[interaction] val key = accessor
    private[interaction] val links = projections

private[interaction] sealed abstract class PreparedLayerBinding[PlotRow, A]:
  type Row
  val source: PlotLayer.Aux[PlotRow, Row]
  val space: KeySpace[A]
  val key: Row => A
  val links: Vector[Row => Either[InteractionError, PackedLinkKey]]
  val index: EntityIndex[A]

  final def recover(
      compiled: TrainedLayer,
      actualSource: PlotLayer[PlotRow]
  ): Either[InteractionError, ResolvedLayer[Row]] =
    // Only called for a layer freshly produced by this exact plot compilation. Its layerIndex
    // selects actualSource. Reference identity witnesses the package's hidden row type.
    if source eq actualSource then Right(compiled.value.asInstanceOf[ResolvedLayer[Row]])
    else
      Left(
        InteractionError.InvalidValue("layer binding", "source package does not match compilation")
      )

private[interaction] object PreparedLayerBinding:
  def create[PlotRow, Row0, A](
      packageValue: PlotLayer.Aux[PlotRow, Row0],
      keySpace: KeySpace[A],
      accessor: Row0 => A,
      projections: Vector[Row0 => Either[InteractionError, PackedLinkKey]],
      sourceIndex: EntityIndex[A]
  ): PreparedLayerBinding[PlotRow, A] = new PreparedLayerBinding[PlotRow, A]:
    type Row = Row0
    val source = packageValue
    val space = keySpace
    val key = accessor
    val links = projections
    val index = sourceIndex
