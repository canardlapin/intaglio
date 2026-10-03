package intaglio.interaction

import intaglio.*

enum MembershipRetention:
  case CountOnly, Representative, ExactKeys

/** Scalar grid coordinates use the field's y-up row index, not the image's top-row index. */
final case class RasterCell(row: Int, column: Int, value: Double)

/** One target's portable data. No source row is retained by this record. */
final case class TargetInfo[A](
    id: VisualTargetId,
    entity: Option[EntityKey[A]],
    membership: Membership[A],
    links: LinkKeys = LinkKeys.empty,
    rasterCell: Option[RasterCell] = None
):
  /** Preserve the pre-raster constructor and copy descriptors. */
  def this(
      id: VisualTargetId,
      entity: Option[EntityKey[A]],
      membership: Membership[A],
      links: LinkKeys
  ) =
    this(id, entity, membership, links, None)

  def copy[B](
      id: VisualTargetId,
      entity: Option[EntityKey[B]],
      membership: Membership[B],
      links: LinkKeys
  ): TargetInfo[B] =
    new TargetInfo(id, entity, membership, links, rasterCell)

object TargetInfo:
  // Preserve the compiler-generated companion member from the baseline.
  override def toString: String = "TargetInfo"

  def apply[A](
      id: VisualTargetId,
      entity: Option[EntityKey[A]],
      membership: Membership[A],
      links: LinkKeys
  ): TargetInfo[A] =
    new TargetInfo(id, entity, membership, links, None)

/** Several grobs may share one logical target. A point batch uses one group with per-index targets.
  */
final class TargetGroup[A] private[interaction] (
    val name: SemanticId,
    val series: TargetSeries,
    private val entities: Vector[Option[EntityKey[A]]],
    private val memberships: Vector[Membership[A]],
    private val links: Vector[LinkKeys],
    private val rasterCells: Vector[Option[RasterCell]],
    private[interaction] val raster: Boolean
):
  private[interaction] def this(
      name: SemanticId,
      series: TargetSeries,
      entities: Vector[Option[EntityKey[A]]],
      memberships: Vector[Membership[A]],
      links: Vector[LinkKeys]
  ) = this(name, series, entities, memberships, links, Vector.empty, false)

  def size: Int = series.size
  def at(index: Int): Either[InteractionError, TargetInfo[A]] =
    series
      .at(index)
      .map(id =>
        TargetInfo(
          id,
          entities(index),
          memberships(index),
          links(index),
          rasterCells.lift(index).flatten
        )
      )

/** A scene and its typed interaction metadata are compiled together. Arbitrary scene edits require
  * recompilation; a static scene alone cannot recover source identity.
  */
final class InteractionPlan[A] private[interaction] (
    val id: SemanticId,
    val revision: PlanRevision,
    val sourceRevision: DataRevision,
    val spaces: Vector[KeySpace[A]],
    val sourceEntities: Vector[EntityKey[A]],
    val context: Option[RenderContext],
    val trained: TrainedPlot,
    val groups: Vector[TargetGroup[A]],
    private[intaglio] val training: Option[TrainedPlotData]
):
  /** Bridge for the constructor descriptor from before retained training. */
  private[interaction] def this(
      id: SemanticId,
      revision: PlanRevision,
      sourceRevision: DataRevision,
      spaces: Vector[KeySpace[A]],
      sourceEntities: Vector[EntityKey[A]],
      context: Option[RenderContext],
      trained: TrainedPlot,
      groups: Vector[TargetGroup[A]]
  ) = this(id, revision, sourceRevision, spaces, sourceEntities, context, trained, groups, None)

  def scene: Scene = trained.scene

  private[interaction] def retaining(value: TrainedPlotData): InteractionPlan[A] =
    new InteractionPlan(
      id,
      revision,
      sourceRevision,
      spaces,
      sourceEntities,
      context,
      trained,
      groups,
      Some(value)
    )
  private val byName = groups.iterator.map(group => group.name.value -> group).toMap
  def group(name: String): Option[TargetGroup[A]] = byName.get(name)

object InteractionCompiler:
  val targetAttribute: DataKey = DataKey.unsafe("intaglio-targets")

  /** Convenience binding for ordinary layers sharing the plot's row type. Use compileBound for
    * independent row types, explicit link projections, or different entity namespaces.
    */
  def compile[Row, A](
      plot: Plot[Row],
      space: KeySpace[A],
      revision: DataRevision,
      planId: SemanticId,
      planRevision: PlanRevision,
      options: PlotCompilerOptions = PlotCompilerOptions.lean,
      retention: MembershipRetention = MembershipRetention.CountOnly,
      adapters: Vector[GeomTargetAdapter] = Vector.empty
  )(key: Row => A): Either[IntaglioError, InteractionPlan[A]] =
    val inherited = plot.layers.map(PlotLayer.inheritedPackage(_))
    if inherited.exists(_.isEmpty) then
      Left(InteractionError.UnsupportedCapability("independent-layer source bindings"))
    else
      val bindings = inherited.flatten.map(source => LayerBinding[Row, A](source, space)(key))
      compileBound(plot, bindings, revision, planId, planRevision, options, retention, adapters)

  /** Bind every layer explicitly by package identity. Each accessor's input type is retained by its
    * binding; labels, vector positions, row equality, and stat identity are not type witnesses.
    */
  def compileBound[PlotRow, A](
      plot: Plot[PlotRow],
      bindings: Vector[LayerBinding[PlotRow, A]],
      revision: DataRevision,
      planId: SemanticId,
      planRevision: PlanRevision,
      options: PlotCompilerOptions = PlotCompilerOptions.lean,
      retention: MembershipRetention = MembershipRetention.CountOnly,
      adapters: Vector[GeomTargetAdapter] = Vector.empty
  ): Either[IntaglioError, InteractionPlan[A]] =
    if bindings.exists(binding => !plot.layers.exists(_ eq binding.source)) then
      Left(InteractionError.InvalidValue("layer binding", "source package is not in this plot"))
    else
      for
        prepared <- traverse(plot.layers) { source =>
          bindings.filter(binding => binding.source eq source) match
            case Vector(binding) => binding.prepare(plot.data)
            case _               =>
              Left(
                InteractionError.InvalidValue(
                  "layer binding",
                  "exactly one binding is required for each layer package"
                )
              )
        }
        resolved <- PlotCompiler.resolveRetainingTraining(plot, options)
        result <- attach(
          plot,
          resolved._2,
          prepared,
          revision,
          planId,
          planRevision,
          retention,
          adapters,
          options.renderContext
        )
      yield result.retaining(resolved._1)

  private def attach[PlotRow, A](
      plot: Plot[PlotRow],
      trained: TrainedPlot,
      bindings: Vector[PreparedLayerBinding[PlotRow, A]],
      revision: DataRevision,
      planId: SemanticId,
      planRevision: PlanRevision,
      retention: MembershipRetention,
      adapters: Vector[GeomTargetAdapter],
      context: Option[RenderContext]
  ): Either[InteractionError, InteractionPlan[A]] =
    val groups = Vector.newBuilder[TargetGroup[A]]
    var groupOrdinal = 0

    def attachLayer(packed: TrainedLayer): Either[InteractionError, TrainedLayer] =
      if packed.layerIndex < 0 || packed.layerIndex >= bindings.length then
        Left(
          InteractionError.InvalidValue("compiled layer", "layer index is outside the source plot")
        )
      else
        val input = bindings(packed.layerIndex)
        input.recover(packed, plot.layers(packed.layerIndex)).flatMap { layer =>
          TargetLowering.resolve(layer, adapters).flatMap { assignments =>
            val replacements = layer.grobs.toArray
            traverse(assignments) { assignment =>
              val name = SemanticId.unsafe(s"${planId.value}-targets-$groupOrdinal")
              groupOrdinal += 1
              for
                series <- TargetSeries(planId, planRevision, name, assignment.rows.length)
                evidence <- traverse(assignment.rows) { indices =>
                  val rows = indices.map(layer.rows(_))
                  val members = rows.flatMap(_.statRow.members)
                  for
                    keys <- traverse(members)(row =>
                      Checked.callback("statistic member key")(input.space.entity(input.key(row)))
                    )
                    _ <- Either.cond(
                      keys.forall(input.index.indexOf(_).nonEmpty),
                      (),
                      InteractionError
                        .UnknownEntity(layer.layerIndex, rows.headOption.fold(0)(_.rowIndex))
                    )
                    unique = keys.distinct
                    membership <- retain(input.space, revision, unique, retention)
                    projections <- traverse(members)(row =>
                      traverse(input.links)(projection => projection(row))
                    )
                  yield
                    val entity = rows match
                      case Vector(row) if row.statRow.isInstanceOf[StatRow.Identity[?]] =>
                        unique.headOption
                      case _ => None
                    val cell = Option.when(layer.geom.isInstanceOf[Geom.Raster])(rows).flatMap {
                      case Vector(row) =>
                        row.source match
                          case cell: ScalarCell =>
                            Some(RasterCell(cell.yIndex, cell.xIndex, cell.value))
                          case _ => None
                      case _ => None
                    }
                    (entity, membership, LinkKeys(projections.flatten), cell)
                }
              yield
                groups += new TargetGroup(
                  name,
                  series,
                  evidence.map(_._1),
                  evidence.map(_._2),
                  evidence.map(_._3),
                  evidence.map(_._4),
                  layer.geom.isInstanceOf[Geom.Raster]
                )
                assignment.grobs.foreach { index =>
                  replacements(index) = Grob.annotated(
                    replacements(index),
                    GrobMeta(data = Vector(targetAttribute -> name.value))
                  )
                }
            }.map(_ => TrainedLayer(layer.copy(grobs = replacements.toVector)))
          }
        }

    val updated =
      if trained.facetPanels.nonEmpty then
        traverse(trained.facetPanels) { panel =>
          traverse(panel.layers)(attachLayer).map(layers => panel.copy(layers = layers))
        }.map(panels => trained.copy(layers = panels.flatMap(_.layers), facetPanels = panels))
      else traverse(trained.layers)(attachLayer).map(layers => trained.copy(layers = layers))
    updated.map(value =>
      new InteractionPlan(
        planId,
        planRevision,
        revision,
        bindings.map(_.space).distinct,
        bindings.flatMap(_.index.keys).distinct,
        context,
        value.retainRequestedInspection,
        groups.result()
      )
    )

  /** Show `plan` through a data window: `x`/`y` are typed windows (numeric, date or date-time, as
    * the axis's scale is), and both empty restores the compiled view. Only the panel ranges, axes,
    * grid and layout are recomputed from the plan's retained training; statistics, scale training
    * and row resolution do not run, and the marks, their targets and the plan's identity and
    * revision are the same, so selection and focus carry over. The plan must have been compiled
    * with a render context; faceted and flipped plots are refused.
    */
  def rezoom[A](
      plan: InteractionPlan[A],
      x: Option[CoordinateWindow],
      y: Option[CoordinateWindow]
  ): Either[IntaglioError, InteractionPlan[A]] =
    for
      training <- plan.training.toRight(
        InteractionError.UnsupportedCapability("re-windowing a plan without retained training")
      )
      _ <- plan.context.toRight(
        InteractionError.InvalidValue("rezoom", "the plan was compiled without a render context")
      )
      zoomed <- PlotCompiler.rezoomPlaced(training, x, y)
    yield
      val (data, placed) = zoomed
      // The marks are unchanged by a window: keep the plan's annotated, retention-applied layers.
      new InteractionPlan(
        plan.id,
        plan.revision,
        plan.sourceRevision,
        plan.spaces,
        plan.sourceEntities,
        plan.context,
        placed.copy(layers = plan.trained.layers, facetPanels = plan.trained.facetPanels),
        plan.groups,
        Some(data)
      )

  private def retain[A](
      space: KeySpace[A],
      revision: DataRevision,
      keys: Vector[EntityKey[A]],
      retention: MembershipRetention
  ): Either[InteractionError, Membership[A]] =
    retention match
      case MembershipRetention.CountOnly      => Membership.countOnly(space, revision, keys.length)
      case MembershipRetention.Representative =>
        keys.headOption match
          case Some(first) => Membership.representative(space, revision, keys.length, first)
          case None        => Membership.countOnly(space, revision, 0)
      case MembershipRetention.ExactKeys => Membership.exact(space, revision, keys)

  private def traverse[A, B, E](values: Vector[A])(f: A => Either[E, B]): Either[E, Vector[B]] =
    val out = Vector.newBuilder[B]
    var result: Either[E, Unit] = Right(())
    var index = 0
    while index < values.length && result.isRight do
      result = f(values(index)).map { value => out += value; () }
      index += 1
    result.map(_ => out.result())
