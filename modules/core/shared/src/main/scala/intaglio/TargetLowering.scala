package intaglio.interaction

import intaglio.*

/** Indices refer to the final resolved layer's grobs and rows, not source-row indices. Each outer
  * row vector is one logical target. Multiple targets in one assignment require a single point
  * batch, in batch order. Multiple grobs with one row vector form one multi-part logical mark.
  * Construction is unchecked; the compiler validates a complete layout before using any entry.
  */
final case class TargetAssignment(grobs: Vector[Int], rows: Vector[Vector[Int]])

/** Optional target lowering for an ordinary extension geom. It never changes geometry or paint. */
trait TargetLowering:
  def apply[Row](layer: ResolvedLayer[Row]): Either[InteractionError, Vector[TargetAssignment]]

/** The actual geometry object is the registration witness; equal labels do not alias adapters. */
final case class GeomTargetAdapter(geom: Geom, lowering: TargetLowering)

object TargetLowering:
  private[interaction] def resolve[Row](
      layer: ResolvedLayer[Row],
      adapters: Vector[GeomTargetAdapter]
  ): Either[InteractionError, Vector[TargetAssignment]] =
    val matching = adapters.filter(adapter => adapter.geom eq layer.geom)
    val layout = matching match
      case Vector()        => builtin(layer)
      case Vector(adapter) => Checked.callback("target lowering")(adapter.lowering(layer))
      case _               =>
        Left(InteractionError.InvalidValue("target lowering", "duplicate adapters for one geom"))
    layout.flatMap(assignments =>
      Checked.callback("target lowering validation") {
        validate(layer, assignments).map(_ => assignments)
      }
    )

  private def validate[Row](
      layer: ResolvedLayer[Row],
      layout: Vector[TargetAssignment]
  ): Either[InteractionError, Unit] =
    def invalid(reason: String) = Left(InteractionError.InvalidValue("target lowering", reason))
    val grobs = layout.flatMap(_.grobs)
    if grobs.exists(i => i < 0 || i >= layer.grobs.length) then
      invalid("grob index is outside the resolved layer")
    else if grobs.distinct.length != grobs.length then
      invalid("a grob belongs to more than one assignment")
    else if grobs.sorted != layer.grobs.indices.toVector then
      invalid("every grob must have an explicit target assignment")
    else
      var result: Either[InteractionError, Unit] = Right(())
      var i = 0
      while i < layout.length && result.isRight do
        val item = layout(i)
        if item.grobs.isEmpty || item.rows.isEmpty then
          result = invalid("assignments require grobs and targets")
        else if item.rows.exists(indices => indices.exists(j => j < 0 || j >= layer.rows.length))
        then result = invalid("row index is outside the resolved layer")
        else if item.rows.exists(indices => indices.distinct.length != indices.length) then
          result = invalid("a logical target repeats a resolved row")
        else if item.rows.length > 1 then
          item.grobs.map(layer.grobs(_)) match
            case Vector(batch: Grob.PointBatch) if batch.points.length == item.rows.length => ()
            case _                                                                         =>
              result = invalid("multiple targets require one point batch of matching cardinality")
        i += 1
      result

  private def builtin[Row](
      layer: ResolvedLayer[Row]
  ): Either[InteractionError, Vector[TargetAssignment]] =
    val rows = layer.rows
    val geom = layer.geom
    val positions = rows.iterator.zipWithIndex.map { case (row, i) => row.rowIndex -> i }.toMap
    def singles = rows.indices.map(i => Vector(i)).toVector
    def grouped(minimum: Int) = GeomBatch(rows, GeomContext(layer.layerIndex, Theme.default)).groups
      .filter(_.length >= minimum)
      .map(_.map(row => positions(row.rowIndex)))
    def ordinary(groups: Vector[Vector[Int]], width: Int = 1) =
      val expected = groups.length * width
      if layer.grobs.length != expected then
        Left(InteractionError.LoweringMismatch(geom.label, expected, layer.grobs.length))
      else
        Right(groups.zipWithIndex.map { case (group, index) =>
          TargetAssignment(Vector.tabulate(width)(offset => index * width + offset), Vector(group))
        })

    if positions.size != rows.length then
      Left(
        InteractionError.InvalidValue("target lowering", "resolved row identities are not unique")
      )
    else if layer.annotation.nonEmpty then ordinary(Vector.fill(layer.grobs.length)(Vector.empty))
    else if rows.isEmpty then ordinary(Vector.empty)
    else
      layer.stat.contract.lowering match
        case StatLowering.Summary => ordinary(singles, 2)
        case StatLowering.Density =>
          ordinary(if rows.length >= 2 then Vector(rows.indices.toVector) else Vector.empty)
        case StatLowering.Ecdf => ordinary(grouped(1))
        case StatLowering.Geom =>
          if geom eq Geom.Point then
            layer.grobs match
              case Vector(batch: Grob.PointBatch) =>
                Either.cond(
                  batch.points.length == rows.length,
                  Vector(TargetAssignment(Vector(0), singles)),
                  InteractionError.LoweringMismatch("point batch", rows.length, batch.points.length)
                )
              case _ => ordinary(singles)
          else if (geom eq Geom.Text) || (geom eq Geom.Rect) || (geom eq Geom.Tile) ||
            (geom eq Geom.Bar) || (geom eq Geom.Segment) || (geom eq Geom.ErrorBar)
          then ordinary(singles)
          else if (geom eq Geom.Line) || (geom eq Geom.Ribbon) || (geom eq Geom.Area) then
            ordinary(grouped(2))
          else if geom eq Geom.Polygon then ordinary(grouped(3))
          else Left(InteractionError.UnsupportedCapability(s"geom '${geom.label}' target lowering"))
