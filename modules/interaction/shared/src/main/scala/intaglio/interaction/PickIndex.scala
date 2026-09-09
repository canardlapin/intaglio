package intaglio.interaction

import PickGeometry.*

/** Uniform spatial grid over target bounding boxes, built once when a [[PickingPlan]] is compiled.
  *
  * The grid answers one conservative question — which targets *might* satisfy a query over a given
  * device-pixel box — and the plan then applies the exact geometric predicate to that candidate set
  * only. Soundness rests on a property of the picking geometry: every point at finite clipped
  * distance from a target lies inside the target's unclipped source bounds (all boundary pieces and
  * interior points are drawn from the source and clip regions inside the source), so a box test
  * expanded by the query tolerance plus [[PickIndex.safety]] can never exclude a true hit.
  *
  * Targets without bounds have no boundary geometry, infinite distance, and no visible parts; they
  * can match no query and are excluded outright.
  */
private[interaction] final class PickIndex private (
    targetCount: Int,
    grid: Option[PickIndex.Grid]
):
  /** Every target index, for callers that must fall back to the exhaustive scan. */
  private val all: Array[Int] = Array.range(0, targetCount)

  /** Indices of every target whose bounds may intersect the query box. Conservative, deduplicated,
    * unordered; callers re-sort exactly as the exhaustive scan did, so results are identical.
    */
  def candidates(left: Double, top: Double, right: Double, bottom: Double): Array[Int] =
    grid match
      case None    => all
      case Some(g) =>
        val c0 = math.floor((left - g.left) / g.cellWidth).toInt
        val c1 = math.floor((right - g.left) / g.cellWidth).toInt
        val r0 = math.floor((top - g.top) / g.cellHeight).toInt
        val r1 = math.floor((bottom - g.top) / g.cellHeight).toInt
        if c1 < 0 || r1 < 0 || c0 >= g.columns || r0 >= g.rows then Array.empty[Int]
        else
          val colStart = math.max(0, c0)
          val colEnd = math.min(g.columns - 1, c1)
          val rowStart = math.max(0, r0)
          val rowEnd = math.min(g.rows - 1, r1)
          val seen = new Array[Boolean](targetCount)
          val out = Array.newBuilder[Int]
          var row = rowStart
          while row <= rowEnd do
            var col = colStart
            while col <= colEnd do
              val cell = g.cells(row * g.columns + col)
              var i = 0
              while i < cell.length do
                val target = cell(i)
                if !seen(target) then
                  seen(target) = true
                  out += target
                i += 1
              col += 1
            row += 1
          out.result()

private[interaction] object PickIndex:
  /** Query boxes are expanded by this margin so the epsilon slack inside the exact geometric
    * predicates (`PickGeometry.epsilon`) can never disagree with the box filter.
    */
  val safety: Double = 1e-6

  private val exhaustiveThreshold = 16
  private val maximumSide = 256

  private[interaction] final case class Grid(
      left: Double,
      top: Double,
      cellWidth: Double,
      cellHeight: Double,
      columns: Int,
      rows: Int,
      cells: Array[Array[Int]]
  )

  def build(bounds: Vector[Option[Box]]): PickIndex =
    val boxed = bounds.zipWithIndex.collect { case (Some(box), index) => (box, index) }
    if boxed.length <= exhaustiveThreshold then new PickIndex(bounds.length, None)
    else
      var left = Double.PositiveInfinity
      var top = Double.PositiveInfinity
      var right = Double.NegativeInfinity
      var bottom = Double.NegativeInfinity
      boxed.foreach { case (box, _) =>
        left = math.min(left, box.left)
        top = math.min(top, box.top)
        right = math.max(right, box.right)
        bottom = math.max(bottom, box.bottom)
      }
      val side =
        math.min(maximumSide, math.max(1, math.ceil(math.sqrt(boxed.length.toDouble)).toInt))
      val cellWidth = math.max((right - left) / side, safety)
      val cellHeight = math.max((bottom - top) / side, safety)
      val buckets = Array.fill(side * side)(Array.newBuilder[Int])
      boxed.foreach { case (box, index) =>
        val c0 = math.max(0, math.floor((box.left - left) / cellWidth).toInt)
        val c1 = math.min(side - 1, math.floor((box.right - left) / cellWidth).toInt)
        val r0 = math.max(0, math.floor((box.top - top) / cellHeight).toInt)
        val r1 = math.min(side - 1, math.floor((box.bottom - top) / cellHeight).toInt)
        var row = r0
        while row <= r1 do
          var col = c0
          while col <= c1 do
            buckets(row * side + col) += index
            col += 1
          row += 1
      }
      new PickIndex(
        bounds.length,
        Some(Grid(left, top, cellWidth, cellHeight, side, side, buckets.map(_.result())))
      )
