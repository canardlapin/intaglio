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
  private[intaglio] def evaluate(
      cell: FacetCell,
      row: Row
  ): Either[RowMapping.Problem, Boolean]

object LayerFacetPolicy:
  /** Repeat every row in every panel. Useful for reference annotations. */
  case object Repeat extends LayerFacetPolicy[Any]:
    private[intaglio] def includes(cell: FacetCell, row: Any): Boolean =
      true

    private[intaglio] def evaluate(
        cell: FacetCell,
        row: Any
    ): Either[RowMapping.Problem, Boolean] =
      Right(true)

  /** Keep the layer out of every facet panel. */
  case object Exclude extends LayerFacetPolicy[Any]:
    private[intaglio] def includes(cell: FacetCell, row: Any): Boolean =
      false

    private[intaglio] def evaluate(
        cell: FacetCell,
        row: Any
    ): Either[RowMapping.Problem, Boolean] =
      Right(false)

  /** Select rows with a function that sees the typed row and resolved cell. */
  final case class Select[Row](include: (FacetCell, Row) => Boolean) extends LayerFacetPolicy[Row]:
    private[intaglio] def includes(cell: FacetCell, row: Row): Boolean =
      include(cell, row)

    private[intaglio] def evaluate(
        cell: FacetCell,
        row: Row
    ): Either[RowMapping.Problem, Boolean] =
      RowMapping.capture(MappingContract.Throwing)(include(cell, row))

  private final case class MappedSelect[Row](
      include: RowMapping[(FacetCell, Row), Boolean]
  ) extends LayerFacetPolicy[Row]:
    private[intaglio] def includes(cell: FacetCell, row: Row): Boolean =
      include((cell, row))

    private[intaglio] def evaluate(
        cell: FacetCell,
        row: Row
    ): Either[RowMapping.Problem, Boolean] =
      RowMapping.evaluateFunction(include, (cell, row))

  def total[Row](include: (FacetCell, Row) => Boolean): LayerFacetPolicy[Row] =
    MappedSelect(RowMapping.total(include.tupled))

  def checked[Row](
      include: (FacetCell, Row) => Either[MappingFailure, Boolean]
  ): LayerFacetPolicy[Row] =
    MappedSelect(RowMapping.checked(include.tupled))

  def throwing[Row](include: (FacetCell, Row) => Boolean): LayerFacetPolicy[Row] =
    MappedSelect(RowMapping.throwing(include.tupled))

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
):
  private val indexByLabels: Map[(Option[String], Option[String]), Int] =
    cells.iterator.zipWithIndex.map { case (cell, index) =>
      (cell.rowLabel, cell.columnLabel) -> index
    }.toMap

  require(indexByLabels.size == cells.size, "facet cells must have distinct label coordinates")

  def indexOf(rowLabel: Option[String], columnLabel: Option[String]): Option[Int] =
    indexByLabels.get((rowLabel, columnLabel))

sealed trait FacetSpec[Row]:
  def scales: FacetScales

  private[intaglio] def layout(data: Vector[Row]): Either[GraphicsError, FacetLayout]
  private[intaglio] def partition(
      layout: FacetLayout,
      data: Vector[Row],
      layerIndex: Int
  ): Either[GraphicsError, Vector[Vector[Row]]]

object FacetSpec:
  private final case class Wrap[Row](
      value: Row => String,
      columns: Int,
      levels: Vector[String],
      scales: FacetScales
  ) extends FacetSpec[Row]:
    private[intaglio] def layout(data: Vector[Row]): Either[GraphicsError, FacetLayout] =
      evaluateRows(value, data, "facet layout", None, "facet").flatMap { observed =>
        val resolved = orderedLevels(levels, observed)
        if resolved.isEmpty then Left(GraphicsError.EmptyFacet)
        else
          val cells = resolved.zipWithIndex.map { case (label, index) =>
            FacetCell(index / columns, index % columns, None, Some(label))
          }
          Right(FacetLayout((cells.length + columns - 1) / columns, columns, cells))
      }

    private[intaglio] def partition(
        layout: FacetLayout,
        data: Vector[Row],
        layerIndex: Int
    ): Either[GraphicsError, Vector[Vector[Row]]] =
      partitionRows(layout, data) { (row, rowIndex) =>
        evaluateRow(
          value,
          row,
          "facet membership",
          Some(layerIndex),
          "facet",
          rowIndex
        ).map(label => (None, Some(label)))
      }

  private final case class Grid[Row](
      rowValue: Row => String,
      columnValue: Row => String,
      rowLevels: Vector[String],
      columnLevels: Vector[String],
      scales: FacetScales
  ) extends FacetSpec[Row]:
    private[intaglio] def layout(data: Vector[Row]): Either[GraphicsError, FacetLayout] =
      for
        observedRows <- evaluateRows(rowValue, data, "facet layout", None, "facet-row")
        observedColumns <- evaluateRows(
          columnValue,
          data,
          "facet layout",
          None,
          "facet-column"
        )
        layout <-
          val rows = orderedLevels(rowLevels, observedRows)
          val columns = orderedLevels(columnLevels, observedColumns)
          if rows.isEmpty || columns.isEmpty then Left(GraphicsError.EmptyFacet)
          else
            val cells =
              rows.zipWithIndex.flatMap { case (rowLabel, rowIndex) =>
                columns.zipWithIndex.map { case (columnLabel, columnIndex) =>
                  FacetCell(rowIndex, columnIndex, Some(rowLabel), Some(columnLabel))
                }
              }
            Right(FacetLayout(rows.length, columns.length, cells))
      yield layout

    private[intaglio] def partition(
        layout: FacetLayout,
        data: Vector[Row],
        layerIndex: Int
    ): Either[GraphicsError, Vector[Vector[Row]]] =
      partitionRows(layout, data) { (row, rowIndex) =>
        for
          rowLabel <- evaluateRow(
            rowValue,
            row,
            "facet membership",
            Some(layerIndex),
            "facet-row",
            rowIndex
          )
          columnLabel <- evaluateRow(
            columnValue,
            row,
            "facet membership",
            Some(layerIndex),
            "facet-column",
            rowIndex
          )
        yield (Some(rowLabel), Some(columnLabel))
      }

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
    val seen = scala.collection.mutable.HashSet.from(declared)
    val out = Vector.newBuilder[String]
    out ++= declared
    observed.foreach { level =>
      if seen.add(level) then out += level
    }
    out.result()

  private def partitionRows[Row](
      layout: FacetLayout,
      data: Vector[Row]
  )(
      labels: (Row, Int) => Either[GraphicsError, (Option[String], Option[String])]
  ): Either[GraphicsError, Vector[Vector[Row]]] =
    val buckets = Vector.fill(layout.cells.length)(scala.collection.mutable.ArrayBuffer.empty[Row])
    var rowIndex = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while rowIndex < data.length && result.isRight do
      result = labels(data(rowIndex), rowIndex).flatMap { case (rowLabel, columnLabel) =>
        layout
          .indexOf(rowLabel, columnLabel)
          .toRight(GraphicsError.FacetCellNotIndexed(rowLabel, columnLabel))
          .map { cellIndex =>
            buckets(cellIndex) += data(rowIndex)
            ()
          }
      }
      rowIndex += 1
    result.map(_ => buckets.map(_.toVector))

  private def evaluateRows[Row, A](
      mapping: Row => A,
      data: Vector[Row],
      stage: String,
      layerIndex: Option[Int],
      aesthetic: String
  ): Either[GraphicsError, Vector[A]] =
    val out = Vector.newBuilder[A]
    var rowIndex = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while rowIndex < data.length && result.isRight do
      result = evaluateRow(mapping, data(rowIndex), stage, layerIndex, aesthetic, rowIndex).map {
        value =>
          out += value
          ()
      }
      rowIndex += 1
    result.map(_ => out.result())

  private def evaluateRow[Row, A](
      mapping: Row => A,
      row: Row,
      stage: String,
      layerIndex: Option[Int],
      aesthetic: String,
      rowIndex: Int
  ): Either[GraphicsError, A] =
    RowMapping.evaluateFunction(mapping, row).left.map { case (contract, failure) =>
      GraphicsError.MappingEvaluationFailed(
        stage,
        layerIndex,
        aesthetic,
        rowIndex,
        contract,
        failure
      )
    }

  private def firstDuplicate(values: Vector[String]): Option[String] =
    val seen = scala.collection.mutable.HashSet.empty[String]
    values.find(value => !seen.add(value))
