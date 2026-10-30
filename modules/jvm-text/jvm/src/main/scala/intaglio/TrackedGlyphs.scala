package intaglio

import java.awt.{Font, Shape}
import java.awt.font.{FontRenderContext, GlyphVector}
import java.awt.geom.{Path2D, Point2D, Rectangle2D}
import java.text.Bidi
import scala.collection.mutable

/** Positions already-shaped glyphs. Native TRACKING is deliberately not used: it can move a
  * combining mark independently of its base. This plan retains every within-unit glyph offset.
  */
private[intaglio] final case class TrackedGlyphs(
    runs: Vector[GlyphVector],
    advance: Double,
    ascent: Double,
    descent: Double,
    leading: Double
):
  def logicalBounds: Rectangle2D =
    new Rectangle2D.Double(0, -ascent, advance, ascent + descent + leading)

  lazy val outline: Shape =
    val path = new Path2D.Double()
    runs.foreach(run => path.append(run.getOutline, false))
    path

  def bounds: Rectangle2D =
    val ink = outline.getBounds2D
    if ink.isEmpty then logicalBounds else logicalBounds.createUnion(ink)

private[intaglio] object TrackedGlyphs:
  private final case class Directional(start: Int, limit: Int, level: Byte)
  private final case class Glyph(run: GlyphVector, index: Int, unit: Int, x: Double, y: Double)

  def layout(text: String, font: Font, context: FontRenderContext, spacing: Double): TrackedGlyphs =
    require(spacing.isFinite, "Letter spacing must be finite")
    val units = TextTracking.units(text)
    val line = font.getLineMetrics("Mg", context)
    if text.isEmpty then
      return TrackedGlyphs(Vector.empty, 0, line.getAscent, line.getDescent, line.getLeading)
    val parents = Array.tabulate(units.size)(identity)
    def root(value: Int): Int =
      var result = value
      while parents(result) != result do result = parents(result)
      result
    def merge(left: Int, right: Int): Unit =
      val a = root(left)
      val b = root(right)
      if a != b then parents(math.max(a, b)) = math.min(a, b)
    def unitAt(offset: Int): Int =
      // Labels are short; use a binary search to keep long runs bounded as well.
      var low = 0
      var high = units.size - 1
      while low < high do
        val middle = (low + high + 1) / 2
        if units(middle).start <= offset then low = middle else high = middle - 1
      low

    val bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
    val directions = Array.tabulate(bidi.getRunCount)(i =>
      Directional(bidi.getRunStart(i), bidi.getRunLimit(i), bidi.getRunLevel(i).toByte)
    )
    val visual: Array[Object] = directions.map(value => value: Object)
    Bidi.reorderVisually(directions.map(_.level), 0, visual, 0, directions.length)
    val chars = text.toCharArray
    val glyphs = Vector.newBuilder[Glyph]
    val runs = Vector.newBuilder[GlyphVector]
    var pen = 0.0
    visual.foreach { entry =>
      val direction = entry.asInstanceOf[Directional]
      val flags =
        if (direction.level & 1) == 0 then Font.LAYOUT_LEFT_TO_RIGHT else Font.LAYOUT_RIGHT_TO_LEFT
      val vector = font.layoutGlyphVector(context, chars, direction.start, direction.limit, flags)
      runs += vector
      val starts = (0 until vector.getNumGlyphs)
        .map(i => direction.start + vector.getGlyphCharIndex(i))
        .distinct
        .sorted
        .toVector
      starts.zipWithIndex.foreach { case (start, index) =>
        val end = if index + 1 < starts.size then starts(index + 1) else direction.limit
        val first = unitAt(start)
        val last = unitAt(math.max(start, end - 1))
        (first until last).foreach(i => merge(i, i + 1))
      }
      var index = 0
      while index < vector.getNumGlyphs do
        val position = vector.getGlyphPosition(index)
        glyphs += Glyph(
          vector,
          index,
          unitAt(direction.start + vector.getGlyphCharIndex(index)),
          pen + position.getX,
          position.getY
        )
        index += 1
      pen += vector.getGlyphPosition(vector.getNumGlyphs).getX
    }
    val ordered = glyphs.result()
    // Interleaved source mappings are one indivisible shaping unit, not independent letters.
    val ranges = mutable.Map.empty[Int, (Int, Int)]
    ordered.zipWithIndex.foreach { case (glyph, index) =>
      val group = root(glyph.unit)
      ranges.update(group, ranges.get(group).fold(index -> index)(r => r._1 -> index))
    }
    var active = -1
    var activeEnd = -1
    ranges.toVector.sortBy(_._2._1).foreach { case (group, (start, end)) =>
      if start <= activeEnd then
        merge(active, group)
        activeEnd = math.max(activeEnd, end)
      else
        active = group
        activeEnd = end
    }
    val visible = units.indices.filter(i => units(i).spaced).map(root).toSet
    val offsets = mutable.Map.empty[Int, Double]
    var visibleGroups = 0
    ordered.foreach { glyph =>
      val group = root(glyph.unit)
      val shift = offsets.getOrElseUpdate(
        group, {
          val delta = visibleGroups * spacing
          if visible(group) then visibleGroups += 1
          delta
        }
      )
      glyph.run.setGlyphPosition(glyph.index, new Point2D.Double(glyph.x + shift, glyph.y))
    }
    val advance = pen + math.max(0, visibleGroups - 1) * spacing
    require(advance.isFinite && advance >= 0, "Letter spacing produces an invalid text advance")
    // Glyph positions now use paragraph coordinates; retain a consistent terminal position.
    val completed = runs.result()
    completed.foreach(run => run.setGlyphPosition(run.getNumGlyphs, new Point2D.Double(advance, 0)))
    TrackedGlyphs(completed, advance, line.getAscent, line.getDescent, line.getLeading)
