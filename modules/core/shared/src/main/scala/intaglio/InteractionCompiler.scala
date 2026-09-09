package intaglio.interaction

import intaglio.*

enum MembershipRetention:
  case CountOnly, Representative, ExactKeys

/** One target's portable data. No source row is retained by this record. */
final case class TargetInfo[A](
    id: VisualTargetId,
    entity: Option[EntityKey[A]],
    membership: Membership[A],
    links: LinkKeys = LinkKeys.empty
)

/** Several grobs may share one logical target. A point batch uses one group with per-index targets.
  */
final class TargetGroup[A] private[interaction] (
    val name: SemanticId,
    val series: TargetSeries,
    private val entities: Vector[Option[EntityKey[A]]],
    private val memberships: Vector[Membership[A]],
    private val links: Vector[LinkKeys]
):
  def size: Int = series.size
  def at(index: Int): Either[InteractionError, TargetInfo[A]] =
    series.at(index).map(id => TargetInfo(id, entities(index), memberships(index), links(index)))

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
    val groups: Vector[TargetGroup[A]]
):
  def scene: Scene = trained.scene
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
        resolved <- PlotCompiler.resolveBeforeRetention(plot, options)
        result <- attach(
          plot,
          resolved,
          prepared,
          revision,
          planId,
          planRevision,
          retention,
          adapters,
          options.renderContext
        )
      yield result

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
                    (entity, membership, LinkKeys(projections.flatten))
                }
              yield
                groups += new TargetGroup(
                  name,
                  series,
                  evidence.map(_._1),
                  evidence.map(_._2),
                  evidence.map(_._3)
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
