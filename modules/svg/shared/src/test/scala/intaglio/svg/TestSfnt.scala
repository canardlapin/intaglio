package intaglio.svg

import scala.collection.mutable.ArrayBuffer

/** Builds small, valid sfnt files table by table, so parser and plate tests state exactly the
  * metrics, cmap, outlines and OpenType layout they exercise. Outlines are header-only `glyf`
  * entries: a viewer could not draw them, but every box the model reads is real.
  */
object TestSfnt:
  final class Writer:
    private val buffer = ArrayBuffer.empty[Byte]
    def size: Int = buffer.length
    def u8(value: Int): Writer =
      buffer += value.toByte
      this
    def u16(value: Int): Writer = u8(value >>> 8).u8(value)
    def u32(value: Long): Writer = u16((value >>> 16).toInt & 0xffff).u16(value.toInt & 0xffff)
    def tag(value: String): Writer =
      value.foreach(c => u8(c.toInt))
      this
    def bytes(value: Array[Byte]): Writer =
      buffer ++= value
      this
    def patch16(at: Int, value: Int): Writer =
      buffer(at) = (value >>> 8).toByte
      buffer(at + 1) = value.toByte
      this
    def patch32(at: Int, value: Long): Writer =
      patch16(at, (value >>> 16).toInt & 0xffff).patch16(at + 2, value.toInt & 0xffff)
    def pad4(): Writer =
      while buffer.length % 4 != 0 do u8(0)
      this
    def result: Array[Byte] = buffer.toArray

  def writer: Writer = new Writer

  final case class Glyph(advance: Int, box: Option[(Int, Int, Int, Int)])

  final case class Os2(
      typo: (Int, Int),
      win: (Int, Int),
      useTypoMetrics: Boolean = false,
      xHeight: Option[Int] = None
  )

  // ------------------------------------------------------------ OpenType layout pieces

  def coverage(glyphs: Seq[Int]): Array[Byte] =
    val sorted = glyphs.distinct.sorted
    val w = writer.u16(1).u16(sorted.length)
    sorted.foreach(w.u16)
    w.result

  def rangeCoverage(start: Int, end: Int): Array[Byte] =
    writer.u16(2).u16(1).u16(start).u16(end).u16(0).result

  def classDef(classes: Seq[(Int, Int)]): Array[Byte] =
    val sorted = classes.sortBy(_._1)
    val w = writer.u16(2).u16(sorted.length)
    sorted.foreach { case (glyph, value) => w.u16(glyph).u16(glyph).u16(value) }
    w.result

  /** A subtable whose header refers to children by 16-bit offsets: `header` is written with zero
    * placeholders at `slots`, and each child is appended and its offset patched in.
    */
  def withChildren(header: Writer, slots: Seq[Int], children: Seq[Array[Byte]]): Array[Byte] =
    slots.zip(children).foreach { case (slot, child) =>
      header.patch16(slot, header.size)
      header.bytes(child)
    }
    header.result

  def singleSubstitution(mapping: Seq[(Int, Int)]): Array[Byte] =
    val sorted = mapping.sortBy(_._1)
    val w = writer.u16(2).u16(0).u16(sorted.length)
    sorted.foreach(pair => w.u16(pair._2))
    withChildren(w, Seq(2), Seq(coverage(sorted.map(_._1))))

  def deltaSubstitution(glyphs: Seq[Int], delta: Int): Array[Byte] =
    withChildren(writer.u16(1).u16(0).u16(delta & 0xffff), Seq(2), Seq(coverage(glyphs)))

  def multipleSubstitution(glyph: Int, sequence: Seq[Int]): Array[Byte] =
    val seq = writer.u16(sequence.length)
    sequence.foreach(seq.u16)
    withChildren(
      writer.u16(1).u16(0).u16(1).u16(0),
      Seq(2, 6),
      Seq(coverage(Seq(glyph)), seq.result)
    )

  def ligatureSubstitution(first: Int, ligatures: Seq[(Seq[Int], Int)]): Array[Byte] =
    val set = writer.u16(ligatures.length)
    ligatures.foreach(_ => set.u16(0))
    val tables = ligatures.map { case (rest, ligature) =>
      val l = writer.u16(ligature).u16(rest.length + 1)
      rest.foreach(l.u16)
      l.result
    }
    val setBytes = withChildren(set, ligatures.indices.map(i => 2 + i * 2), tables)
    withChildren(writer.u16(1).u16(0).u16(1).u16(0), Seq(2, 6), Seq(coverage(Seq(first)), setBytes))

  /** Chained context format 3 with one input glyph class and no backtrack or lookahead. */
  def chainedContext(input: Seq[Int], lookup: Int): Array[Byte] =
    val w = writer.u16(3).u16(0).u16(1).u16(0).u16(0).u16(1).u16(0).u16(lookup)
    withChildren(w, Seq(6), Seq(coverage(input)))

  /** Context format 1: one rule set for `first`, one rule of `glyphs` invoking `lookup`. */
  def context(first: Int, rest: Seq[Int], lookup: Int): Array[Byte] =
    val rule = writer.u16(rest.length + 1).u16(1)
    rest.foreach(rule.u16)
    rule.u16(0).u16(lookup)
    val set = withChildren(writer.u16(1).u16(0), Seq(2), Seq(rule.result))
    withChildren(writer.u16(1).u16(0).u16(1).u16(0), Seq(2, 6), Seq(coverage(Seq(first)), set))

  def extension(kind: Int, subtable: Array[Byte]): Array[Byte] =
    writer.u16(1).u16(kind).u32(8).bytes(subtable).result

  def singlePosition(glyph: Int, xPlacement: Int, yPlacement: Int, xAdvance: Int): Array[Byte] =
    val w = writer.u16(1).u16(0).u16(0x7).u16(xPlacement).u16(yPlacement).u16(xAdvance)
    withChildren(w, Seq(2), Seq(coverage(Seq(glyph))))

  def pairPosition(first: Int, second: Int, xAdvance: Int): Array[Byte] =
    val set = writer.u16(1).u16(second).u16(xAdvance).result
    withChildren(
      writer.u16(1).u16(0).u16(0x4).u16(0).u16(1).u16(0),
      Seq(2, 10),
      Seq(coverage(Seq(first)), set)
    )

  /** Class pair adjustment: class 1 holds `firsts`, class 1 of the second glyphs holds `seconds`;
    * the adjustment applies to the first glyph's advance and the second glyph's placement.
    */
  def classPairPosition(
      firsts: Seq[Int],
      seconds: Seq[Int],
      xAdvance: Int,
      xPlacement2: Int
  ): Array[Byte] =
    val w = writer.u16(2).u16(0).u16(0x4).u16(0x1).u16(0).u16(0).u16(2).u16(2)
    // class1 0: (0, 0), (0, 0); class1 1: (0, 0), (xAdvance, xPlacement2)
    w.u16(0).u16(0).u16(0).u16(0).u16(0).u16(0).u16(xAdvance).u16(xPlacement2)
    withChildren(
      w,
      Seq(2, 8, 10),
      Seq(coverage(firsts), classDef(firsts.map(_ -> 1)), classDef(seconds.map(_ -> 1)))
    )

  /** Mark-to-base (kind 4) or mark-to-mark (kind 6) with one mark class. */
  def markAttachment(
      mark: Int,
      markAnchor: (Int, Int),
      base: Int,
      baseAnchor: (Int, Int)
  ): Array[Byte] =
    def anchor(point: (Int, Int)) = writer.u16(1).u16(point._1).u16(point._2).result
    val marks = withChildren(writer.u16(1).u16(0).u16(0), Seq(4), Seq(anchor(markAnchor)))
    val bases = withChildren(writer.u16(1).u16(0), Seq(2), Seq(anchor(baseAnchor)))
    withChildren(
      writer.u16(1).u16(0).u16(0).u16(1).u16(0).u16(0),
      Seq(2, 4, 8, 10),
      Seq(coverage(Seq(mark)), coverage(Seq(base)), marks, bases)
    )

  def cursive(glyph: Int): Array[Byte] =
    withChildren(writer.u16(1).u16(0).u16(1).u16(0).u16(0), Seq(2), Seq(coverage(Seq(glyph))))

  final case class Lookup(kind: Int, subtables: Seq[Array[Byte]])

  /** A GSUB or GPOS table: features by tag over lookup indices, one DFLT script using them all. */
  def layout(features: Seq[(String, Seq[Int])], lookups: Seq[Lookup]): Array[Byte] =
    val langSys = writer.u16(0).u16(0xffff).u16(features.length)
    features.indices.foreach(langSys.u16)
    val script = withChildren(writer.u16(0).u16(0), Seq(0), Seq(langSys.result))
    val scriptList = withChildren(writer.u16(1).tag("DFLT").u16(0), Seq(6), Seq(script))
    val featureHeader = writer.u16(features.length)
    features.foreach { case (tag, _) => featureHeader.tag(tag).u16(0) }
    val featureTables = features.map { case (_, indices) =>
      val f = writer.u16(0).u16(indices.length)
      indices.foreach(f.u16)
      f.result
    }
    val featureList =
      withChildren(featureHeader, features.indices.map(i => 6 + i * 6), featureTables)
    val lookupHeader = writer.u16(lookups.length)
    lookups.foreach(_ => lookupHeader.u16(0))
    val lookupTables = lookups.map { lookup =>
      val l = writer.u16(lookup.kind).u16(0).u16(lookup.subtables.length)
      lookup.subtables.foreach(_ => l.u16(0))
      withChildren(l, lookup.subtables.indices.map(i => 6 + i * 2), lookup.subtables)
    }
    val lookupList = withChildren(lookupHeader, lookups.indices.map(i => 2 + i * 2), lookupTables)
    withChildren(
      writer.u16(1).u16(0).u16(0).u16(0).u16(0),
      Seq(4, 6, 8),
      Seq(scriptList, featureList, lookupList)
    )

  def gdef(classes: Seq[(Int, Int)]): Array[Byte] =
    withChildren(writer.u16(1).u16(0).u16(0).u16(0).u16(0).u16(0), Seq(4), Seq(classDef(classes)))

  def alternateSubstitution(glyph: Int, alternates: Seq[Int]): Array[Byte] =
    val set = writer.u16(alternates.length)
    alternates.foreach(set.u16)
    withChildren(
      writer.u16(1).u16(0).u16(1).u16(0),
      Seq(2, 6),
      Seq(coverage(Seq(glyph)), set.result)
    )

  def legacyKern(pairs: Seq[(Int, Int, Int)], coverage: Int = 0x0001): Array[Byte] =
    val sorted = pairs.sortBy(p => (p._1, p._2))
    val w = writer.u16(0).u16(1).u16(0).u16(14 + sorted.length * 6).u16(coverage).u16(sorted.length)
    w.u16(0).u16(0).u16(0)
    sorted.foreach { case (a, b, v) => w.u16(a).u16(b).u16(v & 0xffff) }
    w.result

  // ------------------------------------------------------------ the file

  /** A TrueType file. `cmapFormat` 4 (`rangeOffsets` selects the glyph-array path) or 12. */
  def font(
      glyphs: Vector[Glyph],
      cmap: Map[Int, Int],
      unitsPerEm: Int = 1000,
      hhea: (Int, Int) = (800, 200),
      os2: Option[Os2] = None,
      cmapFormat: Int = 4,
      rangeOffsets: Boolean = false,
      longLoca: Boolean = false,
      hMetrics: Option[Int] = None,
      extra: Map[String, Array[Byte]] = Map.empty,
      signature: Long = 0x00010000L,
      outlines: Boolean = true,
      cmapOverride: Option[Array[Byte]] = None
  ): Array[Byte] =
    val boxes = glyphs.flatMap(_.box)
    val fontBox =
      if boxes.isEmpty then (0, 0, 0, 0)
      else (boxes.map(_._1).min, boxes.map(_._2).min, boxes.map(_._3).max, boxes.map(_._4).max)
    val metrics = hMetrics.getOrElse(glyphs.length)
    val head = writer
      .u32(0x00010000L)
      .u32(0x00010000L)
      .u32(0)
      .u32(0x5f0f3cf5L)
      .u16(0)
      .u16(unitsPerEm)
      .u32(0)
      .u32(0)
      .u32(0)
      .u32(0)
      .u16(fontBox._1)
      .u16(fontBox._2)
      .u16(fontBox._3)
      .u16(fontBox._4)
      .u16(0)
      .u16(8)
      .u16(2)
      .u16(if longLoca then 1 else 0)
      .u16(0)
      .result
    val hheaTable = writer.u32(0x00010000L).u16(hhea._1).u16(-hhea._2 & 0xffff)
    (0 until 13).foreach(_ => hheaTable.u16(0))
    hheaTable.u16(metrics)
    val maxp = writer.u32(0x00005000L).u16(glyphs.length).result
    val hmtx = writer
    glyphs.take(metrics).foreach(g => hmtx.u16(g.advance).u16(0))
    glyphs.drop(metrics).foreach(_ => hmtx.u16(0))
    val glyf = writer
    val offsets = ArrayBuffer(0)
    glyphs.foreach { g =>
      g.box.foreach { case (x0, y0, x1, y1) =>
        glyf.u16(1).u16(x0 & 0xffff).u16(y0 & 0xffff).u16(x1 & 0xffff).u16(y1 & 0xffff).u16(0)
      }
      offsets += glyf.size
    }
    val loca = writer
    offsets.foreach(o => if longLoca then loca.u32(o) else loca.u16(o / 2))
    val cmapBytesValue = cmapOverride.getOrElse(cmapBytes(cmap, cmapFormat, rangeOffsets))
    val tables = Map(
      "head" -> head,
      "hhea" -> hheaTable.result,
      "maxp" -> maxp,
      "hmtx" -> hmtx.result,
      "cmap" -> cmapBytesValue
    ) ++ (if outlines then Map("glyf" -> glyf.result, "loca" -> loca.result) else Map.empty) ++
      os2.map(o => "OS/2" -> os2Bytes(o)) ++ extra
    assemble(tables, signature)

  def assemble(tables: Map[String, Array[Byte]], signature: Long = 0x00010000L): Array[Byte] =
    val tags = tables.keys.toVector.sorted
    val w = writer.u32(signature).u16(tags.length).u16(0).u16(0).u16(0)
    tags.foreach(_ => w.u32(0).u32(0).u32(0).u32(0))
    tags.zipWithIndex.foreach { case (tag, index) =>
      val record = 12 + index * 16
      val data = tables(tag)
      w.pad4()
      val offset = w.size
      w.bytes(data)
      val tagBytes = tag.padTo(4, ' ')
      w.patch16(record, (tagBytes(0) << 8) | tagBytes(1))
      w.patch16(record + 2, (tagBytes(2) << 8) | tagBytes(3))
      w.patch32(record + 8, offset)
      w.patch32(record + 12, data.length)
    }
    w.pad4().result

  private def os2Bytes(o: Os2): Array[Byte] =
    val version = if o.xHeight.nonEmpty then 4 else 1
    val w = writer.u16(version)
    (0 until 30).foreach(_ => w.u16(0)) // through achVendID, fsType 0 (installable)
    w.u16(if o.useTypoMetrics then 0x80 else 0).u16(0x20).u16(0xffff)
    w.u16(o.typo._1).u16(-o.typo._2 & 0xffff).u16(0).u16(o.win._1).u16(o.win._2)
    w.u32(0).u32(0)
    o.xHeight.foreach(x => w.u16(x).u16(0).u16(0).u16(0x20).u16(2))
    w.result

  private def cmapBytes(cmap: Map[Int, Int], format: Int, rangeOffsets: Boolean): Array[Byte] =
    cmapTable(Seq((3, if format == 12 then 10 else 1, cmapSubtable(cmap, format, rangeOffsets))))

  /** A cmap table of `(platform, encoding, subtable)` records, in the order given. */
  def cmapTable(records: Seq[(Int, Int, Array[Byte])]): Array[Byte] =
    val w = writer.u16(0).u16(records.length)
    var offset = 4 + records.length * 8
    records.foreach { case (platform, encoding, sub) =>
      w.u16(platform).u16(encoding).u32(offset)
      offset += sub.length
    }
    records.foreach(record => w.bytes(record._3))
    w.result

  def cmapSubtable(cmap: Map[Int, Int], format: Int, rangeOffsets: Boolean = false): Array[Byte] =
    val entries = cmap.toVector.sortBy(_._1)
    if format == 12 then
      val w = writer.u16(12).u16(0).u32(16 + entries.length * 12).u32(0).u32(entries.length)
      entries.foreach { case (cp, glyph) => w.u32(cp).u32(cp).u32(glyph) }
      w.result
    else
      val bmp = entries.filter(_._1 <= 0xfffe)
      val segments = bmp.length + 1
      val w = writer.u16(4).u16(0).u16(0).u16(segments * 2).u16(0).u16(0).u16(0)
      bmp.foreach(e => w.u16(e._1))
      w.u16(0xffff).u16(0)
      bmp.foreach(e => w.u16(e._1))
      w.u16(0xffff)
      bmp.foreach(e => w.u16(if rangeOffsets then 0 else (e._2 - e._1) & 0xffff))
      w.u16(1)
      // With range offsets, segment i's one glyph is entry i of the glyph array that follows the
      // idRangeOffset array, which is `segments` words past segment i's own slot.
      bmp.indices.foreach(_ => w.u16(if rangeOffsets then segments * 2 else 0))
      w.u16(0)
      if rangeOffsets then bmp.foreach(e => w.u16(e._2))
      w.patch16(2, w.size)
      w.result
