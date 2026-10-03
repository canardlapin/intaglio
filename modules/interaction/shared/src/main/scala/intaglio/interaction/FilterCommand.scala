package intaglio.interaction

import intaglio.*

/** How a filter changed the input: rows before and after, and which observations it removed. */
final case class InputChange[A](rowsBefore: Int, rowsAfter: Int, removed: Set[EntityKey[A]])

/** How a filter changed one target group's statistical result (a layer's bins, intervals or marks):
  * how many targets it has, and each target's member total, before and after.
  */
final case class StatisticChange(
    group: Int,
    targetsBefore: Int,
    targetsAfter: Int,
    totalsBefore: Vector[Option[Int]],
    totalsAfter: Vector[Option[Int]]
):
  def changed: Boolean = targetsBefore != targetsAfter || totalsBefore != totalsAfter

/** The outcome of an explicit filter: the recompiled plan, the input change and the statistical
  * change, reported separately. A filter selects nothing and emphasizes nothing; applying the new
  * plan to a view (for example `SvgWidget.update`) reconciles the selection, as any data update
  * does, and that reconciliation is its own event.
  */
final case class FilterResult[A](
    plan: InteractionPlan[A],
    input: InputChange[A],
    statistics: Vector[StatisticChange]
)

/** An explicit filter-and-recompute command over an application's own rows: keep the rows whose
  * observation keys are given, recompile the plot from them, and report what changed. The
  * application supplies how a plot is compiled from rows (so layers with their own data, scales and
  * options are its choice) and the new revisions, which must differ from the current plan's: the
  * filtered plot is new data.
  */
final class FilterCommand[Row, A](
    rows: Vector[Row],
    space: KeySpace[A],
    key: Row => A,
    compile: (Vector[Row], DataRevision, PlanRevision) => Either[IntaglioError, InteractionPlan[A]]
):
  def keep(
      current: InteractionPlan[A],
      keys: Set[EntityKey[A]],
      dataRevision: DataRevision,
      planRevision: PlanRevision
  ): Either[IntaglioError, FilterResult[A]] =
    for
      _ <- Either.cond(
        dataRevision != current.sourceRevision && planRevision != current.revision,
        (),
        InteractionError.InvalidValue("filter revisions", "a filtered plot needs new revisions")
      )
      _ <- keys
        .find(k => !(k.space eq space))
        .fold(Right(()))(_ => Left(InteractionError.ForeignKey(0)))
      rowKeys <- rows.foldLeft[Either[IntaglioError, Vector[EntityKey[A]]]](Right(Vector.empty)) {
        (acc, row) => acc.flatMap(done => space.entity(key(row)).map(done :+ _))
      }
      present = rowKeys.toSet
      _ <- keys
        .find(k => !present.contains(k))
        .fold(Right(()))(k =>
          Left(InteractionError.InvalidValue("filter key", s"'${k.token.payload}' is not a row"))
        )
      kept = rows.zip(rowKeys).collect { case (row, k) if keys.contains(k) => row }
      plan <- compile(kept, dataRevision, planRevision)
    yield
      def totals(p: InteractionPlan[A]) =
        p.groups.map(g =>
          Vector.tabulate(g.size)(i => g.at(i).toOption.flatMap(_.membership.total))
        )
      val before = totals(current)
      val after = totals(plan)
      val statistics = (0 until math.max(before.size, after.size)).toVector.map { i =>
        val b = before.lift(i).getOrElse(Vector.empty)
        val a = after.lift(i).getOrElse(Vector.empty)
        StatisticChange(i, b.size, a.size, b, a)
      }
      FilterResult(
        plan,
        InputChange(rows.size, kept.size, present -- keys),
        statistics
      )
