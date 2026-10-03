package intaglio.svg

import scala.collection.mutable
import scala.util.control.NoStackTrace

/** A glyph's outline bounding box in font units, y up from the baseline. */
private[svg] final case class GlyphBox(xMin: Int, yMin: Int, xMax: Int, yMax: Int)

/** One horizontal metric set a viewer may lay a run out with: ascent above and descent below the
  * baseline, both non-negative distances in font units.
  */
private[svg] final case class VerticalMetrics(ascent: Int, descent: Int)

/** A GPOS value record reduced to the fields that move ink horizontally or vertically. */
private[svg] final case class GlyphAdjustment(
    xPlacement: Int,
    yPlacement: Int,
    xAdvance: Int
):
  def isZero: Boolean = xPlacement == 0 && yPlacement == 0 && xAdvance == 0

private[svg] object GlyphAdjustment:
  val zero: GlyphAdjustment = GlyphAdjustment(0, 0, 0)

/** Read-only view of an sfnt (TrueType or CFF-flavoured OpenType) file: the tables the SVG text
  * plate model needs to bound the glyphs a viewer draws from that face.
  *
  * Every read is bounds-checked against the file, and an untrusted unsigned offset is compared with
  * the file length before any addition, as [[SvgFontFace]]'s OS/2 check does. A file whose required
  * tables are missing or inconsistent is not a font this model reads: [[SfntFont.parse]] returns
  * `None`, and a malformed table met later surfaces as `None` from [[SfntFont.guard]], never as an
  * exception or an out-of-bounds read.
  */
private[svg] final class SfntFont private (
    reader: SfntFont.Reader,
    tables: Map[String, (Int, Int)],
    val unitsPerEm: Int,
    val fontBox: GlyphBox,
    numGlyphs: Int,
    numberOfHMetrics: Int,
    hheaMetrics: VerticalMetrics,
    os2: Option[SfntFont.Os2],
    longLoca: Boolean,
    cmapSubtable: Option[(Int, Int)]
):
  import SfntFont.*

  private def table(tag: String): Option[(Int, Int)] = tables.get(tag)

  /** True for TrueType outlines, whose per-glyph boxes are read from `glyf`. A CFF-flavoured face
    * has no `glyf`; every glyph is then bounded by the font-wide `head` box.
    */
  val hasGlyphOutlines: Boolean = table("glyf").nonEmpty && table("loca").nonEmpty

  /** Tables whose presence means the viewer may draw or move glyphs in ways this model does not
    * read: variation axes (optical size follows the font size), AAT shaping and tracking, colour
    * and bitmap glyphs, and GSUB/GPOS feature variations.
    */
  lazy val unmodelledTables: Vector[String] =
    Vector("fvar", "morx", "mort", "kerx", "trak", "COLR", "sbix", "CBDT", "EBDT", "SVG ")
      .filter(tables.contains) ++
      Vector("GSUB", "GPOS").filter(tag => layout(tag).exists(_.featureVariations))

  /** The candidate metric sets a viewer may use: `hhea`, `OS/2` typographic (when the face asks for
    * it, or `hhea` is empty) and `OS/2` Windows metrics.
    */
  lazy val verticalMetrics: Vector[VerticalMetrics] =
    val hhea = Option.when(hheaMetrics.ascent != 0 || hheaMetrics.descent != 0)(hheaMetrics)
    val typo = os2.flatMap { o =>
      Option.when(o.useTypoMetrics || hhea.isEmpty)(o.typo)
    }
    val win = os2.map(_.win).filter(m => m.ascent != 0 || m.descent != 0)
    val all = (hhea.toVector ++ typo ++ win).distinct
    if all.isEmpty then Vector(VerticalMetrics(fontBox.yMax max 0, (-fontBox.yMin) max 0))
    else all

  /** Candidate x-heights: `OS/2.sxHeight` when the table records one, and the top of the glyph for
    * `x`, which is what font backends measure when it does not.
    */
  lazy val xHeights: Vector[Int] =
    val recorded = os2.flatMap(_.xHeight).filter(_ > 0).toVector
    val measured = SfntFont
      .guard(glyphFor('x'.toInt).flatMap(glyphBox))
      .flatten
      .map(_.yMax)
      .filter(_ > 0)
      .toVector
    (recorded ++ measured).distinct

  /** The glyph a viewer draws for `codePoint` from this face, or `None` when the face has none and
    * the viewer falls back to another font.
    */
  def glyphFor(codePoint: Int): Option[Int] =
    cmapSubtable.flatMap { case (format, offset) =>
      val glyph =
        if format == 12 then cmap12(offset, codePoint)
        else if codePoint > 0xffff then 0
        else cmap4(offset, codePoint)
      Option.when(glyph > 0 && glyph < numGlyphs)(glyph)
    }

  def advance(glyph: Int): Int =
    val (hmtx, length) = table("hmtx").get
    val index = if glyph < numberOfHMetrics then glyph else numberOfHMetrics - 1
    if index * 4 + 2 > length then throw Malformed
    reader.u16(hmtx + index * 4)

  /** The glyph's outline box, `None` for a glyph without contours (a space). A CFF face answers the
    * font-wide `head` box for every glyph.
    */
  def glyphBox(glyph: Int): Option[GlyphBox] =
    if glyph < 0 || glyph >= numGlyphs then throw Malformed
    if !hasGlyphOutlines then Some(fontBox)
    else
      val (loca, locaLength) = table("loca").get
      val (glyf, glyfLength) = table("glyf").get
      def locaAt(index: Int): Int =
        if longLoca then
          if index * 4 + 4 > locaLength then throw Malformed
          reader.offset32(loca + index * 4)
        else
          if index * 2 + 2 > locaLength then throw Malformed
          reader.u16(loca + index * 2) * 2
      val start = locaAt(glyph)
      val end = locaAt(glyph + 1)
      if end < start || end > glyfLength then throw Malformed
      if end == start then None
      else
        if end - start < 10 then throw Malformed
        val at = glyf + start
        val box =
          GlyphBox(reader.s16(at + 2), reader.s16(at + 4), reader.s16(at + 6), reader.s16(at + 8))
        if box.xMin > box.xMax || box.yMin > box.yMax then throw Malformed
        Some(box)

  /** GDEF glyph class: 1 base, 2 ligature, 3 mark, 4 component; 0 when unrecorded. */
  def glyphClass(glyph: Int): Int =
    gdefClassDef.fold(0)(offset => classOf(offset, glyph))

  private lazy val gdefClassDef: Option[Int] =
    table("GDEF").flatMap { case (gdef, length) =>
      if length < 6 then throw Malformed
      val relative = reader.u16(gdef + 4)
      Option.when(relative != 0)(within(gdef, length, relative))
    }

  // ---------------------------------------------------------------- cmap

  private def cmap4(offset: Int, codePoint: Int): Int =
    val segX2 = reader.u16(offset + 6)
    if segX2 % 2 != 0 then throw Malformed
    val segments = segX2 / 2
    val ends = offset + 14
    val starts = ends + segX2 + 2
    val deltas = starts + segX2
    val ranges = deltas + segX2
    reader.require(ranges, segX2)
    var low = 0
    var high = segments - 1
    var found = -1
    while low <= high do
      reader.step()
      val mid = (low + high) >>> 1
      if reader.u16(ends + mid * 2) < codePoint then low = mid + 1
      else
        found = mid
        high = mid - 1
    if found < 0 then 0
    else
      val start = reader.u16(starts + found * 2)
      if codePoint < start then 0
      else
        val delta = reader.u16(deltas + found * 2)
        val rangeAt = ranges + found * 2
        val rangeOffset = reader.u16(rangeAt)
        if rangeOffset == 0 then (codePoint + delta) & 0xffff
        else
          val glyph = reader.u16(rangeAt + rangeOffset + 2 * (codePoint - start))
          if glyph == 0 then 0 else (glyph + delta) & 0xffff

  private def cmap12(offset: Int, codePoint: Int): Int =
    val groups = reader.count32(offset + 12, 12)
    val first = offset + 16
    reader.require(first, groups * 12)
    var low = 0
    var high = groups - 1
    var glyph = 0
    while low <= high do
      reader.step()
      val mid = (low + high) >>> 1
      val at = first + mid * 12
      val start = reader.u32(at)
      val end = reader.u32(at + 4)
      if end < codePoint then low = mid + 1
      else if start > codePoint then high = mid - 1
      else
        val value = reader.u32(at + 8) + (codePoint - start)
        glyph = if value > Int.MaxValue then 0 else value.toInt
        low = high + 1
    glyph

  // ---------------------------------------------------------------- OpenType layout

  private def coverageIndex(offset: Int, glyph: Int): Int =
    reader.u16(offset) match
      case 1 =>
        val count = reader.u16(offset + 2)
        reader.require(offset + 4, count * 2)
        var low = 0
        var high = count - 1
        var index = -1
        while low <= high do
          reader.step()
          val mid = (low + high) >>> 1
          val value = reader.u16(offset + 4 + mid * 2)
          if value < glyph then low = mid + 1
          else if value > glyph then high = mid - 1
          else
            index = mid
            low = high + 1
        index
      case 2 =>
        val count = reader.u16(offset + 2)
        reader.require(offset + 4, count * 6)
        var low = 0
        var high = count - 1
        var index = -1
        while low <= high do
          reader.step()
          val mid = (low + high) >>> 1
          val at = offset + 4 + mid * 6
          val start = reader.u16(at)
          val end = reader.u16(at + 2)
          if end < glyph then low = mid + 1
          else if start > glyph then high = mid - 1
          else
            index = reader.u16(at + 4) + glyph - start
            low = high + 1
        index
      case _ => throw Malformed

  private def classOf(offset: Int, glyph: Int): Int =
    reader.u16(offset) match
      case 1 =>
        val start = reader.u16(offset + 2)
        val count = reader.u16(offset + 4)
        if glyph < start || glyph >= start + count then 0
        else reader.u16(offset + 6 + (glyph - start) * 2)
      case 2 =>
        val count = reader.u16(offset + 2)
        reader.require(offset + 4, count * 6)
        var low = 0
        var high = count - 1
        var value = 0
        while low <= high do
          reader.step()
          val mid = (low + high) >>> 1
          val at = offset + 4 + mid * 6
          if reader.u16(at + 2) < glyph then low = mid + 1
          else if reader.u16(at) > glyph then high = mid - 1
          else
            value = reader.u16(at + 4)
            low = high + 1
        value
      case _ => throw Malformed

  /** A resolved lookup subtable: its effective type (extensions followed) and absolute offset. */
  private final case class Subtable(kind: Int, offset: Int)

  private final class Layout(
      val lookups: Vector[Vector[Subtable]],
      val reachable: Vector[Int],
      val hasKernFeature: Boolean,
      val featureVariations: Boolean
  )

  private lazy val layouts: Map[String, Option[Layout]] =
    Vector("GSUB", "GPOS").map(tag => tag -> table(tag).map(readLayout(tag, _))).toMap

  private def layout(tag: String): Option[Layout] = layouts(tag)

  private def readLayout(tag: String, location: (Int, Int)): Layout =
    val (base, length) = location
    if length < 10 then throw Malformed
    val minor = reader.u16(base + 2)
    val features = within(base, length, reader.u16(base + 6))
    val lookupList = within(base, length, reader.u16(base + 8))
    val variations = minor >= 1 && length >= 14 && reader.u32(base + 10) != 0L
    val extension = if tag == "GSUB" then 7 else 9
    val lookupCount = reader.u16(lookupList)
    reader.require(lookupList + 2, lookupCount * 2)
    val lookups = Vector.tabulate(lookupCount) { index =>
      val lookup = lookupList + reader.u16(lookupList + 2 + index * 2)
      val kind = reader.u16(lookup)
      val count = reader.u16(lookup + 4)
      reader.require(lookup + 6, count * 2)
      Vector.tabulate(count) { sub =>
        reader.step()
        val offset = lookup + reader.u16(lookup + 6 + sub * 2)
        if kind == extension then
          if reader.u16(offset) != 1 then throw Malformed
          Subtable(reader.u16(offset + 2), offset + reader.offset32(offset + 4))
        else Subtable(kind, offset)
      }
    }
    val defaults = if tag == "GSUB" then DefaultSubstitutionFeatures else DefaultPositionFeatures
    val featureCount = reader.u16(features)
    reader.require(features + 2, featureCount * 6)
    val seeds = mutable.LinkedHashSet.empty[Int]
    var kern = false
    (0 until featureCount).foreach { index =>
      val record = features + 2 + index * 6
      val featureTag = reader.tag(record)
      if defaults.contains(featureTag) then
        if featureTag == "kern" then kern = true
        val feature = features + reader.u16(record + 4)
        val indices = reader.u16(feature + 2)
        reader.require(feature + 4, indices * 2)
        (0 until indices).foreach(i => seeds += reader.u16(feature + 4 + i * 2))
    }
    val context = if tag == "GSUB" then (5, 6) else (7, 8)
    val reached = mutable.LinkedHashSet.empty[Int]
    val queue = mutable.Queue.from(seeds)
    while queue.nonEmpty do
      reader.step()
      val index = queue.dequeue()
      if index >= lookups.length then throw Malformed
      if reached.add(index) then
        lookups(index).foreach { subtable =>
          if subtable.kind == context._1 then nestedContext(subtable.offset).foreach(queue.enqueue)
          else if subtable.kind == context._2 then
            nestedChain(subtable.offset).foreach(queue.enqueue)
        }
    Layout(lookups, reached.toVector, kern, variations)

  /** Lookup indices a (non-chained) contextual subtable invokes, in any of its three formats. */
  private def nestedContext(offset: Int): Vector[Int] =
    def records(at: Int, count: Int): Vector[Int] =
      reader.require(at, count * 4)
      Vector.tabulate(count)(i => reader.u16(at + i * 4 + 2))
    def rule(at: Int): Vector[Int] =
      val glyphs = reader.u16(at)
      val count = reader.u16(at + 2)
      if glyphs == 0 then throw Malformed
      records(at + 4 + (glyphs - 1) * 2, count)
    def sets(first: Int): Vector[Int] =
      val setCount = reader.u16(offset + first)
      reader.require(offset + first + 2, setCount * 2)
      (0 until setCount).iterator.flatMap { i =>
        val relative = reader.u16(offset + first + 2 + i * 2)
        if relative == 0 then Iterator.empty
        else
          val set = offset + relative
          val ruleCount = reader.u16(set)
          reader.require(set + 2, ruleCount * 2)
          (0 until ruleCount).iterator.flatMap { r =>
            reader.step()
            rule(set + reader.u16(set + 2 + r * 2))
          }
      }.toVector
    reader.u16(offset) match
      case 1 => sets(4)
      case 2 => sets(6)
      case 3 =>
        val glyphs = reader.u16(offset + 2)
        val count = reader.u16(offset + 4)
        records(offset + 6 + glyphs * 2, count)
      case _ => throw Malformed

  /** Lookup indices a chained contextual subtable invokes, in any of its three formats. */
  private def nestedChain(offset: Int): Vector[Int] =
    def records(at: Int, count: Int): Vector[Int] =
      reader.require(at, count * 4)
      Vector.tabulate(count)(i => reader.u16(at + i * 4 + 2))
    def rule(at: Int): Vector[Int] =
      val backtrack = reader.u16(at)
      val inputAt = at + 2 + backtrack * 2
      val input = reader.u16(inputAt)
      if input == 0 then throw Malformed
      val lookaheadAt = inputAt + 2 + (input - 1) * 2
      val lookahead = reader.u16(lookaheadAt)
      val countAt = lookaheadAt + 2 + lookahead * 2
      records(countAt + 2, reader.u16(countAt))
    def sets(first: Int): Vector[Int] =
      val setCount = reader.u16(offset + first)
      reader.require(offset + first + 2, setCount * 2)
      (0 until setCount).iterator.flatMap { i =>
        val relative = reader.u16(offset + first + 2 + i * 2)
        if relative == 0 then Iterator.empty
        else
          val set = offset + relative
          val ruleCount = reader.u16(set)
          reader.require(set + 2, ruleCount * 2)
          (0 until ruleCount).iterator.flatMap { r =>
            reader.step()
            rule(set + reader.u16(set + 2 + r * 2))
          }
      }.toVector
    reader.u16(offset) match
      case 1 => sets(4)
      case 2 => sets(10)
      case 3 =>
        val backtrack = reader.u16(offset + 2)
        val inputAt = offset + 4 + backtrack * 2
        val input = reader.u16(inputAt)
        val lookaheadAt = inputAt + 2 + input * 2
        val lookahead = reader.u16(lookaheadAt)
        val countAt = lookaheadAt + 2 + lookahead * 2
        records(countAt + 2, reader.u16(countAt))
      case _ => throw Malformed

  private def reachableSubtables(tag: String): Iterator[(Int, Subtable)] =
    layout(tag).iterator.flatMap(l => l.reachable.iterator.flatMap(i => l.lookups(i).map(i -> _)))

  // ---------------------------------------------------------------- GSUB queries

  /** Glyphs a default-feature single, alternate or reverse-chaining substitution can turn `glyph`
    * into, regardless of context.
    */
  def substitutes(glyph: Int): Vector[Int] =
    reachableSubtables("GSUB")
      .flatMap { case (_, Subtable(kind, offset)) =>
        kind match
          case 1 =>
            val index = coverageIndex(offset + reader.u16(offset + 2), glyph)
            if index < 0 then Iterator.empty
            else
              reader.u16(offset) match
                case 1 => Iterator((glyph + reader.u16(offset + 4)) & 0xffff)
                case 2 =>
                  if index >= reader.u16(offset + 4) then throw Malformed
                  Iterator(reader.u16(offset + 6 + index * 2))
                case _ => throw Malformed
          case 3 =>
            val index = coverageIndex(offset + reader.u16(offset + 2), glyph)
            if index < 0 then Iterator.empty
            else
              if index >= reader.u16(offset + 4) then throw Malformed
              val set = offset + reader.u16(offset + 6 + index * 2)
              val count = reader.u16(set)
              reader.require(set + 2, count * 2)
              Iterator.tabulate(count)(i => reader.u16(set + 2 + i * 2))
          case 8 =>
            val index = coverageIndex(offset + reader.u16(offset + 2), glyph)
            if index < 0 then Iterator.empty
            else
              val backtrack = reader.u16(offset + 4)
              val lookaheadAt = offset + 6 + backtrack * 2
              val lookahead = reader.u16(lookaheadAt)
              val countAt = lookaheadAt + 2 + lookahead * 2
              if index >= reader.u16(countAt) then throw Malformed
              Iterator(reader.u16(countAt + 2 + index * 2))
          case _ => Iterator.empty
      }
      .filter(_ < numGlyphs)
      .toVector
      .distinct

  /** Ligatures a default-feature ligature substitution forms with `glyph` first: the remaining
    * components and the ligature glyph.
    */
  def ligatures(glyph: Int): Vector[(Vector[Int], Int)] =
    reachableSubtables("GSUB")
      .flatMap { case (_, Subtable(kind, offset)) =>
        if kind != 4 then Iterator.empty
        else
          val index = coverageIndex(offset + reader.u16(offset + 2), glyph)
          if index < 0 then Iterator.empty
          else
            if index >= reader.u16(offset + 4) then throw Malformed
            val set = offset + reader.u16(offset + 6 + index * 2)
            val count = reader.u16(set)
            reader.require(set + 2, count * 2)
            Iterator.tabulate(count) { i =>
              reader.step()
              val ligature = set + reader.u16(set + 2 + i * 2)
              val components = reader.u16(ligature + 2)
              if components == 0 then throw Malformed
              reader.require(ligature + 4, (components - 1) * 2)
              (
                Vector.tabulate(components - 1)(c => reader.u16(ligature + 4 + c * 2)),
                reader.u16(ligature)
              )
            }
      }
      .filter(_._2 < numGlyphs)
      .toVector

  /** True when a default-feature multiple substitution covers `glyph`: one glyph becomes several,
    * which this model does not lay out.
    */
  def multipleSubstitution(glyph: Int): Boolean =
    reachableSubtables("GSUB").exists { case (_, Subtable(kind, offset)) =>
      kind == 2 && coverageIndex(offset + reader.u16(offset + 2), glyph) >= 0
    }

  // ---------------------------------------------------------------- GPOS queries

  private def valueSize(format: Int): Int = Integer.bitCount(format & 0xff) * 2

  private def valueRecord(at: Int, format: Int): GlyphAdjustment =
    var cursor = at
    def field(bit: Int): Int =
      if (format & bit) == 0 then 0
      else
        val value = reader.s16(cursor)
        cursor += 2
        value
    val xPlacement = field(0x1)
    val yPlacement = field(0x2)
    val xAdvance = field(0x4)
    GlyphAdjustment(xPlacement, yPlacement, xAdvance)

  /** Single adjustments default features may apply to `glyph`. */
  def singleAdjustments(glyph: Int): Vector[GlyphAdjustment] =
    reachableSubtables("GPOS")
      .flatMap { case (_, Subtable(kind, offset)) =>
        if kind != 1 then Iterator.empty
        else
          val index = coverageIndex(offset + reader.u16(offset + 2), glyph)
          if index < 0 then Iterator.empty
          else
            val format = reader.u16(offset + 4)
            reader.u16(offset) match
              case 1 => Iterator(valueRecord(offset + 6, format))
              case 2 =>
                if index >= reader.u16(offset + 6) then throw Malformed
                Iterator(valueRecord(offset + 8 + index * valueSize(format), format))
              case _ => throw Malformed
      }
      .filterNot(_.isZero)
      .toVector

  /** Pair adjustments default-feature lookups may apply to `first` followed by `second`: per
    * lookup, the first of its subtables that applies. A lookup reached only through a contextual
    * rule may or may not apply, so callers bound every subset of these.
    */
  def pairAdjustments(first: Int, second: Int): Vector[(GlyphAdjustment, GlyphAdjustment)] =
    layout("GPOS").toVector.flatMap { l =>
      l.reachable.flatMap { lookup =>
        l.lookups(lookup)
          .iterator
          .filter(_.kind == 2)
          .map(subtable => pairIn(subtable.offset, first, second))
          .collectFirst { case Some(pair) => pair }
      }
    }

  private def pairIn(
      offset: Int,
      first: Int,
      second: Int
  ): Option[(GlyphAdjustment, GlyphAdjustment)] =
    val index = coverageIndex(offset + reader.u16(offset + 2), first)
    if index < 0 then None
    else
      val format1 = reader.u16(offset + 4)
      val format2 = reader.u16(offset + 6)
      val size1 = valueSize(format1)
      val size2 = valueSize(format2)
      reader.u16(offset) match
        case 1 =>
          if index >= reader.u16(offset + 8) then throw Malformed
          val set = offset + reader.u16(offset + 10 + index * 2)
          val count = reader.u16(set)
          val recordSize = 2 + size1 + size2
          reader.require(set + 2, count * recordSize)
          var low = 0
          var high = count - 1
          var found: Option[(GlyphAdjustment, GlyphAdjustment)] = None
          while low <= high do
            reader.step()
            val mid = (low + high) >>> 1
            val at = set + 2 + mid * recordSize
            val glyph = reader.u16(at)
            if glyph < second then low = mid + 1
            else if glyph > second then high = mid - 1
            else
              found = Some((valueRecord(at + 2, format1), valueRecord(at + 2 + size1, format2)))
              low = high + 1
          found
        case 2 =>
          val class1 = classOf(offset + reader.u16(offset + 8), first)
          val class2 = classOf(offset + reader.u16(offset + 10), second)
          val class1Count = reader.u16(offset + 12)
          val class2Count = reader.u16(offset + 14)
          if class1 >= class1Count || class2 >= class2Count then throw Malformed
          val at = offset + 16 + (class1 * class2Count + class2) * (size1 + size2)
          Some((valueRecord(at, format1), valueRecord(at + size1, format2)))
        case _ => throw Malformed

  private def anchor(offset: Int): (Int, Int) =
    reader.u16(offset) match
      case 1 | 2 | 3 => (reader.s16(offset + 2), reader.s16(offset + 4))
      case _         => throw Malformed

  /** Offsets (attachment anchor minus mark anchor) at which a default-feature mark-to-base (`kind`
    * 4) or mark-to-mark (`kind` 6) lookup can attach `mark` to `base`.
    */
  def markAttachments(kind: Int, base: Int, mark: Int): Vector[(Int, Int)] =
    reachableSubtables("GPOS")
      .flatMap { case (_, Subtable(k, offset)) =>
        if k != kind then Iterator.empty
        else
          if reader.u16(offset) != 1 then throw Malformed
          val markIndex = coverageIndex(offset + reader.u16(offset + 2), mark)
          val baseIndex = coverageIndex(offset + reader.u16(offset + 4), base)
          if markIndex < 0 || baseIndex < 0 then Iterator.empty
          else
            val classCount = reader.u16(offset + 6)
            val marks = offset + reader.u16(offset + 8)
            val bases = offset + reader.u16(offset + 10)
            if markIndex >= reader.u16(marks) || baseIndex >= reader.u16(bases) then throw Malformed
            val record = marks + 2 + markIndex * 4
            val markClass = reader.u16(record)
            if markClass >= classCount then throw Malformed
            val (mx, my) = anchor(marks + reader.u16(record + 2))
            val relative = reader.u16(bases + 2 + (baseIndex * classCount + markClass) * 2)
            if relative == 0 then Iterator.empty
            else
              val (bx, by) = anchor(bases + relative)
              Iterator((bx - mx, by - my))
      }
      .toVector
      .distinct

  /** True when a default-feature lookup this model does not lay out could move `glyph`: cursive
    * attachment, or mark-to-ligature attachment of a mark onto one of `ligatures`.
    */
  def unmodelledPositioning(glyph: Int, ligatures: Set[Int]): Boolean =
    reachableSubtables("GPOS").exists { case (_, Subtable(kind, offset)) =>
      kind match
        case 3 => coverageIndex(offset + reader.u16(offset + 2), glyph) >= 0
        case 5 =>
          coverageIndex(offset + reader.u16(offset + 2), glyph) >= 0 &&
          ligatures.exists(l => coverageIndex(offset + reader.u16(offset + 4), l) >= 0)
        case _ => false
    }

  /** True when the face has GPOS mark attachment lookups at all; without them a viewer positions
    * combining marks by its own fallback, which this model does not lay out.
    */
  lazy val hasMarkPositioning: Boolean =
    reachableSubtables("GPOS").exists(_._2.kind == 4)

  // ---------------------------------------------------------------- legacy kern

  /** Pair values from a Windows `kern` table's horizontal format 0 subtables. */
  def legacyKern(first: Int, second: Int): Int =
    legacyKernTables.iterator.map { case (pairs, count) =>
      var low = 0
      var high = count - 1
      var value = 0
      val key = (first.toLong << 16) | second.toLong
      while low <= high do
        reader.step()
        val mid = (low + high) >>> 1
        val at = pairs + mid * 6
        val candidate = (reader.u16(at).toLong << 16) | reader.u16(at + 2).toLong
        if candidate < key then low = mid + 1
        else if candidate > key then high = mid - 1
        else
          value = reader.s16(at + 4)
          low = high + 1
      value
    }.sum

  /** True when a `kern` table exists that this model cannot read (an Apple-format table, or a
    * subtable format other than 0) and GPOS has no kern feature, so a viewer would use it.
    */
  lazy val unreadableKern: Boolean =
    legacyKernState._2 && !layout("GPOS").exists(_.hasKernFeature)

  private lazy val legacyKernTables: Vector[(Int, Int)] = legacyKernState._1

  private lazy val legacyKernState: (Vector[(Int, Int)], Boolean) =
    table("kern") match
      case None                 => (Vector.empty, false)
      case Some((kern, length)) =>
        if length < 4 then throw Malformed
        if reader.u16(kern) != 0 then (Vector.empty, true)
        else
          val count = reader.u16(kern + 2)
          var at = kern + 4
          var unreadable = false
          val found = Vector.newBuilder[(Int, Int)]
          (0 until count).foreach { _ =>
            reader.step()
            val subLength = reader.u16(at + 2)
            val coverage = reader.u16(at + 4)
            val format = coverage >>> 8
            val horizontal = (coverage & 0x1) != 0
            val minimum = (coverage & 0x2) != 0
            val crossStream = (coverage & 0x4) != 0
            if horizontal && !minimum && !crossStream then
              if format == 0 then
                val pairs = reader.u16(at + 6)
                reader.require(at + 14, pairs * 6)
                found += ((at + 14, pairs))
              else unreadable = true
            // A subtable length is 16 bits and may wrap for large format 0 tables; step by the
            // pair count instead when the header length cannot hold it.
            val advanceBy =
              if format == 0 then math.max(subLength, 14 + reader.u16(at + 6) * 6) else subLength
            if advanceBy < 6 then throw Malformed
            at += advanceBy
          }
          (found.result(), unreadable)

private[svg] object SfntFont:
  /** A malformed table, unwound to [[guard]] or [[parse]]. */
  private[svg] object Malformed extends RuntimeException("malformed sfnt table") with NoStackTrace

  /** Runs `body` against fonts it reads, answering `None` when a table proves malformed. */
  def guard[A](body: => A): Option[A] =
    try Some(body)
    catch case Malformed => None

  /** HarfBuzz's always-on horizontal substitution features for scripts without a complex shaper. */
  val DefaultSubstitutionFeatures: Set[String] =
    Set("ccmp", "locl", "rlig", "rclt", "calt", "clig", "liga", "rvrn", "ltra", "ltrm")

  /** HarfBuzz's always-on horizontal positioning features. */
  val DefaultPositionFeatures: Set[String] =
    Set("kern", "mark", "mkmk", "dist", "abvm", "blwm", "curs")

  private[svg] final case class Os2(
      typo: VerticalMetrics,
      win: VerticalMetrics,
      useTypoMetrics: Boolean,
      xHeight: Option[Int]
  )

  /** Read the table directory and the required tables of a TrueType or OpenType file. */
  def parse(bytes: Array[Byte]): Option[SfntFont] =
    guard(read(new Reader(bytes))).flatten

  private def read(reader: Reader): Option[SfntFont] =
    val signature = reader.u32(0)
    if signature != 0x00010000L && signature != 0x74727565L && signature != 0x4f54544fL then None
    else
      val count = reader.u16(4)
      reader.require(12, count * 16)
      val tables = (0 until count).iterator.map { index =>
        val record = 12 + index * 16
        val offset = reader.offset32(record + 8)
        val length = reader.u32(record + 12)
        if length > reader.length - offset then throw Malformed
        reader.tag(record) -> (offset, length.toInt)
      }.toMap
      val required = Vector("head", "hhea", "hmtx", "maxp", "cmap")
      if !required.forall(tables.contains) then None
      else
        val (head, headLength) = tables("head")
        val (hhea, hheaLength) = tables("hhea")
        val (maxp, maxpLength) = tables("maxp")
        if headLength < 54 || hheaLength < 36 || maxpLength < 6 then throw Malformed
        val unitsPerEm = reader.u16(head + 18)
        if unitsPerEm < 16 || unitsPerEm > 16384 then throw Malformed
        val box = GlyphBox(
          reader.s16(head + 36),
          reader.s16(head + 38),
          reader.s16(head + 40),
          reader.s16(head + 42)
        )
        if box.xMin > box.xMax || box.yMin > box.yMax then throw Malformed
        val longLoca = reader.s16(head + 50) match
          case 0 => false
          case 1 => true
          case _ => throw Malformed
        val numGlyphs = reader.u16(maxp + 4)
        val hMetrics = reader.u16(hhea + 34)
        if numGlyphs == 0 || hMetrics == 0 || hMetrics > numGlyphs then throw Malformed
        val (_, hmtxLength) = tables("hmtx")
        if hmtxLength < hMetrics * 4 then throw Malformed
        val hheaMetrics = VerticalMetrics(reader.s16(hhea + 4) max 0, (-reader.s16(hhea + 6)) max 0)
        val os2 = tables.get("OS/2").flatMap { case (offset, length) =>
          Option.when(length >= 78) {
            val version = reader.u16(offset)
            Os2(
              VerticalMetrics(reader.s16(offset + 68) max 0, (-reader.s16(offset + 70)) max 0),
              VerticalMetrics(reader.u16(offset + 74), reader.u16(offset + 76)),
              (reader.u16(offset + 62) & 0x80) != 0,
              Option.when(version >= 2 && length >= 88)(reader.s16(offset + 86))
            )
          }
        }
        if tables.contains("glyf") != tables.contains("loca") then throw Malformed
        tables.get("loca").foreach { case (_, length) =>
          if length < (numGlyphs + 1) * (if longLoca then 4 else 2) then throw Malformed
        }
        Some(
          new SfntFont(
            reader,
            tables,
            unitsPerEm,
            box,
            numGlyphs,
            hMetrics,
            hheaMetrics,
            os2,
            longLoca,
            chooseCmap(reader, tables("cmap"))
          )
        )

  /** The Unicode subtable a viewer shapes with: a full-repertoire format 12 when present, else a
    * BMP format 4.
    */
  private def chooseCmap(reader: Reader, location: (Int, Int)): Option[(Int, Int)] =
    val (cmap, length) = location
    if length < 4 then throw Malformed
    val count = reader.u16(cmap + 2)
    reader.require(cmap + 4, count * 8)
    val subtables = (0 until count).map { index =>
      val record = cmap + 4 + index * 8
      val platform = reader.u16(record)
      val encoding = reader.u16(record + 2)
      val relative = reader.offset32(record + 4)
      if relative > length - 4 then throw Malformed
      (platform, encoding, reader.u16(cmap + relative), cmap + relative)
    }
    def unicode(platform: Int, encoding: Int): Boolean =
      platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
    subtables
      .find(s => unicode(s._1, s._2) && s._3 == 12)
      .orElse(subtables.find(s => unicode(s._1, s._2) && s._3 == 4))
      .map(s => (s._3, s._4))

  /** Big-endian reads that refuse to leave the file, plus a work budget so a hostile table of
    * self-referencing offsets cannot make the model loop for long.
    */
  private[svg] final class Reader(bytes: Array[Byte]):
    val length: Int = bytes.length
    private var budget = 4000000

    def step(): Unit =
      budget -= 1
      if budget < 0 then throw Malformed

    def require(offset: Int, size: Int): Unit =
      if offset < 0 || size < 0 || offset > length || size > length - offset then throw Malformed

    def u16(offset: Int): Int =
      require(offset, 2)
      ((bytes(offset) & 0xff) << 8) | (bytes(offset + 1) & 0xff)

    def s16(offset: Int): Int = u16(offset).toShort.toInt

    def u32(offset: Int): Long =
      require(offset, 4)
      ((bytes(offset) & 0xffL) << 24) | ((bytes(offset + 1) & 0xffL) << 16) |
        ((bytes(offset + 2) & 0xffL) << 8) | (bytes(offset + 3) & 0xffL)

    /** An unsigned 32-bit offset, compared with the file length before it is used. */
    def offset32(offset: Int): Int =
      val value = u32(offset)
      if value > length then throw Malformed
      value.toInt

    /** An unsigned 32-bit count of records of `size` bytes that must fit in the file. */
    def count32(offset: Int, size: Int): Int =
      val value = u32(offset)
      if value * size > length then throw Malformed
      value.toInt

    def tag(offset: Int): String =
      require(offset, 4)
      new String(Array.tabulate(4)(i => (bytes(offset + i) & 0xff).toChar))

  /** `base + relative`, refused when it leaves the table. */
  private def within(base: Int, length: Int, relative: Int): Int =
    if relative < 0 || relative >= length then throw Malformed
    base + relative
