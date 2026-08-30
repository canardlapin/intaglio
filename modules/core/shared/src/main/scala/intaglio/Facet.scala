package intaglio

/** Position-scale sharing across facet panels. Non-position scales remain plot-global under every
  * policy, so color and fill legends stay coherent.
  */
enum FacetScales:
  case Shared
  case FreeX
  case FreeY
  case Free

  def xIsFree: Boolean =
    this == FreeX || this == Free

  def yIsFree: Boolean =
    this == FreeY || this == Free

/** How an independent layer whose row type differs from the plot's row type participates in facets.
  * Same-row layers continue to use the plot's typed `FacetSpec` directly.
  */
sealed trait LayerFacetPolicy[-Row]:
  private[intaglio] def includes(cell: FacetCell, row: Row): Boolean

object LayerFacetPolicy:
  /** Repeat every row in every panel. Useful for reference annotations. */
  case object Repeat extends LayerFacetPolicy[Any]:
    private[intaglio] def includes(cell: FacetCell, row: Any): Boolean =
      true

  /** Keep the layer out of every facet panel. */
  case object Exclude extends LayerFacetPolicy[Any]:
    private[intaglio] def includes(cell: FacetCell, row: Any): Boolean =
      false

  /** Select rows with a function that sees the typed row and resolved cell. */
  final case class Select[Row](include: (FacetCell, Row) => Boolean) extends LayerFacetPolicy[Row]:
    private[intaglio] def includes(cell: FacetCell, row: Row): Boolean =
      include(cell, row)

final case class FacetCell(
    row: Int,
    column: Int,
    rowLabel: Option[String],
    columnLabel: Option[String]
):
  require(row >= 0, "`row` must be non-negative")
  require(column >= 0, "`column` must be non-negative")

  def label: String =
    (rowLabel, columnLabel) match
      case (Some(r), Some(c)) => s"$r | $c"
      case (Some(r), None)    => r
      case (None, Some(c))    => c
      case (None, None)       => ""

  def panelName: GraphicsName =
    GraphicsName.unsafe(s"panel-$row-$column")

  def stripName: GraphicsName =
    GraphicsName.unsafe(s"strip-$row-$column")

private[intaglio] final case class FacetLayout(
    rows: Int,
    columns: Int,
    cells: Vector[FacetCell]
)

sealed trait FacetSpec[Row]:
  def scales: FacetScales

  private[intaglio] def layout(data: Vector[Row]): Either[GraphicsError, FacetLayout]
  private[intaglio] def contains(cell: FacetCell, row: Row): Boolean

object FacetSpec:
  private final case class Wrap[Row](
      value: Row => String,
      columns: Int,
      levels: Vector[String],
      scales: FacetScales
  ) extends FacetSpec[Row]:
    private[intaglio] def layout(data: Vector[Row]): Either[GraphicsError, FacetLayout] =
      val resolved = orderedLevels(levels, data.map(value))
      if resolved.isEmpty then Left(GraphicsError.EmptyFacet)
      else
        val cells = resolved.zipWithIndex.map { case (label, index) =>
          FacetCell(index / columns, index % columns, None, Some(label))
        }
        Right(FacetLayout((cells.length + columns - 1) / columns, columns, cells))

    private[intaglio] def contains(cell: FacetCell, row: Row): Boolean =
      cell.columnLabel.contains(value(row))

  private final case class Grid[Row](
      rowValue: Row => String,
      columnValue: Row => String,
      rowLevels: Vector[String],
      columnLevels: Vector[String],
      scales: FacetScales
  ) extends FacetSpec[Row]:
    private[intaglio] def layout(data: Vector[Row]): Either[GraphicsError, FacetLayout] =
      val rows = orderedLevels(rowLevels, data.map(rowValue))
      val columns = orderedLevels(columnLevels, data.map(columnValue))
      if rows.isEmpty || columns.isEmpty then Left(GraphicsError.EmptyFacet)
      else
        val cells =
          rows.zipWithIndex.flatMap { case (rowLabel, rowIndex) =>
            columns.zipWithIndex.map { case (columnLabel, columnIndex) =>
              FacetCell(rowIndex, columnIndex, Some(rowLabel), Some(columnLabel))
            }
          }
        Right(FacetLayout(rows.length, columns.length, cells))

    private[intaglio] def contains(cell: FacetCell, row: Row): Boolean =
      cell.rowLabel.contains(rowValue(row)) && cell.columnLabel.contains(columnValue(row))

  def wrap[Row](
      value: Row => String,
      columns: Int = 2,
      levels: Vector[String] = Vector.empty,
      scales: FacetScales = FacetScales.Shared
  ): Either[GraphicsError, FacetSpec[Row]] =
    if columns < 1 then Left(GraphicsError.InvalidFacetColumns(columns))
    else
      firstDuplicate(levels) match
        case Some(level) => Left(GraphicsError.DuplicateLevel(level))
        case None        => Right(Wrap(value, columns, levels, scales))

  def grid[Row](
      rows: Row => String,
      columns: Row => String,
      rowLevels: Vector[String] = Vector.empty,
      columnLevels: Vector[String] = Vector.empty,
      scales: FacetScales = FacetScales.Shared
  ): Either[GraphicsError, FacetSpec[Row]] =
    firstDuplicate(rowLevels).orElse(firstDuplicate(columnLevels)) match
      case Some(level) => Left(GraphicsError.DuplicateLevel(level))
      case None        => Right(Grid(rows, columns, rowLevels, columnLevels, scales))

  private def orderedLevels(declared: Vector[String], observed: Vector[String]): Vector[String] =
    declared ++ observed.filterNot(declared.contains).distinct

  private def firstDuplicate(values: Vector[String]): Option[String] =
    val seen = scala.collection.mutable.HashSet.empty[String]
    values.find(value => !seen.add(value))
