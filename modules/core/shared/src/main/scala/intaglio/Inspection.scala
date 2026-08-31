package intaglio

/** Asymptotic retained-memory growth for one inspection channel. */
enum RetentionGrowth:
  case None
  case Constant
  case PerOutput
  case PerSourceIndex
  case FullSourceValues

/** Public cost model for a provenance policy. The values describe data retained after compilation,
  * not temporary values needed while mappings, statistics, layout, and lowering execute.
  */
final case class ProvenanceRetentionCost(
    statisticMembers: RetentionGrowth,
    droppedRows: RetentionGrowth,
    retainsSourceValues: Boolean
)

/** How much source provenance and row diagnostics survive in a trained plot.
  *
  *   - [[ProvenancePolicy.None]] retains no per-output or dropped-row inspection payload.
  *   - [[ProvenancePolicy.CountOnly]] retains member counts per statistic output and one aggregate
  *     dropped-row count.
  *   - [[ProvenancePolicy.Representative]] retains one source value per statistic output and at
  *     most one representative dropped row.
  *   - [[ProvenancePolicy.SourceIndices]] retains source indices and typed drop reasons, but no
  *     source values.
  *   - [[ProvenancePolicy.Full]] retains resolved rows, typed statistic frames with complete member
  *     vectors, and every dropped source row.
  */
enum ProvenancePolicy(val retentionCost: ProvenanceRetentionCost):
  case None
      extends ProvenancePolicy(
        ProvenanceRetentionCost(RetentionGrowth.None, RetentionGrowth.None, false)
      )
  case CountOnly
      extends ProvenancePolicy(
        ProvenanceRetentionCost(RetentionGrowth.PerOutput, RetentionGrowth.Constant, false)
      )
  case Representative
      extends ProvenancePolicy(
        ProvenanceRetentionCost(RetentionGrowth.PerOutput, RetentionGrowth.Constant, true)
      )
  case SourceIndices
      extends ProvenancePolicy(
        ProvenanceRetentionCost(
          RetentionGrowth.PerSourceIndex,
          RetentionGrowth.PerSourceIndex,
          false
        )
      )
  case Full
      extends ProvenancePolicy(
        ProvenanceRetentionCost(
          RetentionGrowth.FullSourceValues,
          RetentionGrowth.FullSourceValues,
          true
        )
      )

/** Provenance retained for one statistic output row. `outputIndex` is stable within the statistic
  * frame. Source-index payloads retain `memberCount` separately, making incomplete provenance from
  * a non-conforming external statistic visible rather than silently claiming completeness.
  */
enum StatisticProvenance[+Row]:
  case CountOnly(index: Int, count: Int)
  case Representative(index: Int, count: Int, source: Row)
  case SourceIndices(index: Int, count: Int, indices: Vector[Int])
  case Full(index: Int, members: Vector[Row])

  def outputIndex: Int =
    this match
      case CountOnly(index, _)         => index
      case Representative(index, _, _) => index
      case SourceIndices(index, _, _)  => index
      case Full(index, _)              => index

  def memberCount: Int =
    this match
      case CountOnly(_, count)         => count
      case Representative(_, count, _) => count
      case SourceIndices(_, count, _)  => count
      case Full(_, members)            => members.length

  def hasCompleteSourceIndices: Boolean =
    this match
      case SourceIndices(_, count, indices) => count == indices.length
      case _                                => false

/** Source-index diagnostic for one rejected statistic output. `rowIndex` addresses the statistic
  * frame; `sourceIndices` address the original layer input.
  */
final case class DroppedSourceIndices(
    layerIndex: Int,
    rowIndex: Int,
    sourceIndices: Vector[Int],
    reason: PlotDropReason
)

/** Retained diagnostics for rejected rows. */
enum DroppedProvenance[+Row]:
  case None
  case CountOnly(value: Int)
  case Representative(total: Int, sample: Option[DroppedRow[Row]])
  case SourceIndices(rows: Vector[DroppedSourceIndices])
  case Full(rows: Vector[DroppedRow[Row]])

  def count: Int =
    this match
      case None                     => 0
      case CountOnly(value)         => value
      case Representative(value, _) => value
      case SourceIndices(rows)      => rows.length
      case Full(rows)               => rows.length

/** Policy-specific, typed inspection retained by one trained layer. */
final case class LayerInspection[+Row](
    policy: ProvenancePolicy,
    statistics: Vector[StatisticProvenance[Row]],
    dropped: DroppedProvenance[Row]
)

private[intaglio] object LayerInspection:
  def capture[Row](
      sourceRows: Vector[Row],
      frame: StatFrame[Row],
      droppedRows: Vector[DroppedRow[Row]],
      policy: ProvenancePolicy
  ): LayerInspection[Row] =
    policy match
      case ProvenancePolicy.None =>
        LayerInspection(policy, Vector.empty, DroppedProvenance.None)
      case ProvenancePolicy.CountOnly =>
        LayerInspection(
          policy,
          frame.rows.zipWithIndex.map { case (row, index) =>
            StatisticProvenance.CountOnly(index, row.members.length)
          },
          DroppedProvenance.CountOnly(droppedRows.length)
        )
      case ProvenancePolicy.Representative =>
        LayerInspection(
          policy,
          frame.rows.zipWithIndex.map { case (row, index) =>
            StatisticProvenance.Representative(index, row.members.length, row.source)
          },
          DroppedProvenance.Representative(droppedRows.length, droppedRows.headOption)
        )
      case ProvenancePolicy.SourceIndices =>
        val index = SourceIndex(sourceRows)
        LayerInspection(
          policy,
          frame.rows.zipWithIndex.map { case (row, outputIndex) =>
            StatisticProvenance.SourceIndices(
              outputIndex,
              row.members.length,
              index.resolve(row.members)
            )
          },
          DroppedProvenance.SourceIndices(
            droppedRows.map { dropped =>
              val sourceIndices = frame.rows
                .lift(dropped.rowIndex)
                .fold(Vector.empty[Int])(row => index.resolve(row.members))
              DroppedSourceIndices(
                dropped.layerIndex,
                dropped.rowIndex,
                sourceIndices,
                dropped.reason
              )
            }
          )
        )
      case ProvenancePolicy.Full =>
        LayerInspection(
          policy,
          frame.rows.zipWithIndex.map { case (row, index) =>
            StatisticProvenance.Full(index, row.members)
          },
          DroppedProvenance.Full(droppedRows)
        )

  /** Duplicate-safe multiset lookup. Each output resolves equal members against source positions
    * from the beginning, so whole-batch statistics may legitimately cite the same inputs from every
    * generated output.
    */
  private final class SourceIndex[Row](sourceRows: Vector[Row]):
    private val positions =
      val out = scala.collection.mutable.HashMap.empty[
        Row,
        scala.collection.mutable.ArrayBuffer[Int]
      ]
      sourceRows.zipWithIndex.foreach { case (row, index) =>
        out.getOrElseUpdate(row, scala.collection.mutable.ArrayBuffer.empty[Int]) += index
      }
      out

    def resolve(members: Vector[Row]): Vector[Int] =
      val offsets = scala.collection.mutable.HashMap.empty[Row, Int]
      val out = Vector.newBuilder[Int]
      members.foreach { member =>
        val offset = offsets.getOrElse(member, 0)
        positions.get(member).flatMap(_.lift(offset)).foreach(out += _)
        offsets.update(member, offset + 1)
      }
      out.result()

  private object SourceIndex:
    def apply[Row](sourceRows: Vector[Row]): SourceIndex[Row] =
      new SourceIndex(sourceRows)
