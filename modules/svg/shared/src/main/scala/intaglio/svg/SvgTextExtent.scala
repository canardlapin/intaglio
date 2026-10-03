package intaglio.svg

import scala.collection.mutable

/** A closed interval of font units. */
private[svg] final case class Span(lo: Double, hi: Double):
  def +(other: Span): Span = Span(lo + other.lo, hi + other.hi)
  def hull(other: Span): Span = Span(math.min(lo, other.lo), math.max(hi, other.hi))
  def including(value: Double): Span = Span(math.min(lo, value), math.max(hi, value))

private[svg] object Span:
  def at(value: Double): Span = Span(value, value)
  val zero: Span = at(0.0)

  /** Every subset sum of `values`: from the sum of the negative ones to the sum of the positive. */
  def subsetSums(values: Iterable[Int]): Span =
    Span(values.iterator.filter(_ < 0).sum.toDouble, values.iterator.filter(_ > 0).sum.toDouble)

/** Bounds of a laid-out run in font units, relative to the run's anchor point on the baseline (x to
  * the right, y up). `ink` is the union of every glyph box a modelled layout can draw; `advance`
  * spans the run's possible total advance.
  */
private[svg] final case class RunExtent(
    ink: Option[(Double, Double, Double, Double)],
    advance: Span
)

/** Bounds the glyphs an SVG viewer draws for one text run in an embedded sfnt face.
  *
  * The viewer shapes the run itself (HarfBuzz in Chromium and Firefox), so this is not a shaper: it
  * over-approximates the alternatives a shaper may take with the face's own tables and unions their
  * ink. For each code point it collects the candidate glyphs (the cmap glyph; canonical composites
  * the face has, as HarfBuzz composes a base and its marks; default-feature single, alternate and
  * reverse substitutions; default-feature ligatures), and for each glyph an interval of advance
  * (every candidate's advance; zero for a combining mark or a consumed ligature component) and of
  * placement (every subset of default-feature single and pair adjustments, plus a legacy `kern`
  * value; kerning off is always included). Marks are bounded both unattached and at every
  * mark-to-base and mark-to-mark anchor offset the face records for any candidate pair. Pen
  * positions, including the `middle`/`end` anchor's share of the whole advance, are interval sums
  * over those advances, so every combination of these alternatives lies inside the result.
  *
  * Kerning and glyph placement are taken as the face's design units scaled linearly: a viewer that
  * hints outlines or rounds each advance to whole pixels may differ by a fraction of a pixel per
  * glyph, which the renderer's ink margin absorbs only for short runs.
  *
  * Runs the model does not cover answer `Left` with the reason, and the caller falls back to the
  * estimate: a code point the face does not map (the viewer would use another font), control,
  * bidirectional, variation-selector or complex-script code points, multiple substitution, cursive
  * or mark-to-ligature attachment, combining marks in a face without mark positioning, and faces
  * with variation, AAT, colour or bitmap tables.
  */
private[svg] object SvgTextExtent:
  private val CandidateLimit = 96

  /** Code points outside the default (non-complex) left-to-right shaping this model covers. */
  def unmodelledCodePoint(cp: Int): Boolean =
    cp < 0x20 || (cp >= 0x7f && cp <= 0x9f) ||
      (cp >= 0x0590 && cp <= 0x08ff) || // Hebrew, Arabic, Syriac, Thaana, NKo, Samaritan, Mandaic
      (cp >= 0x0900 && cp <= 0x0dff) || // Indic
      (cp >= 0x0e00 && cp <= 0x0fff) || // Thai, Lao, Tibetan
      (cp >= 0x1000 && cp <= 0x109f) || // Myanmar
      (cp >= 0x1100 && cp <= 0x11ff) || // Hangul jamo
      (cp >= 0x1700 && cp <= 0x18af) || // Philippine scripts, Khmer, Mongolian
      (cp >= 0x1900 && cp <= 0x1c7f) || // Limbu .. Lepcha, Ol Chiki
      (cp >= 0x1cd0 && cp <= 0x1cff) || // Vedic extensions
      (cp >= 0x200b && cp <= 0x200f) || // zero-width and directional marks
      (cp >= 0x2028 && cp <= 0x202e) || // separators and bidi embeddings
      cp == 0x2044 || // fraction slash: HarfBuzz applies frac, numr and dnom around it
      (cp >= 0x2060 && cp <= 0x206f) || // invisible operators, bidi isolates
      (cp >= 0x2600 && cp <= 0x27bf) || // symbols with emoji presentation
      (cp >= 0xa800 && cp <= 0xabff) || // Indic and South-East Asian, Hangul jamo extended
      (cp >= 0xd7b0 && cp <= 0xd7ff) || // Hangul jamo extended
      (cp >= 0xd800 && cp <= 0xdfff) || // unpaired surrogates
      (cp >= 0xfb1d && cp <= 0xfdff) || // Hebrew and Arabic presentation forms
      (cp >= 0xfe00 && cp <= 0xfe0f) || // variation selectors
      (cp >= 0xfe70 && cp <= 0xfeff) || // Arabic presentation forms, byte order mark
      (cp >= 0xfff0 && cp <= 0xffff) ||
      (cp >= 0x10800 && cp <= 0x10fff) || // historic right-to-left scripts
      (cp >= 0x11000 && cp <= 0x11fff) || // Brahmic historic scripts
      (cp >= 0x1e800 && cp <= 0x1efff) || // Adlam and other right-to-left
      (cp >= 0x1f000 && cp <= 0x1faff) || // emoji
      cp >= 0xe0000 // tags, variation selectors supplement, private use planes

  /** Combining-mark blocks of the scripts the model covers. */
  private def markBlock(cp: Int): Boolean =
    (cp >= 0x0300 && cp <= 0x036f) || (cp >= 0x0483 && cp <= 0x0489) ||
      (cp >= 0x1ab0 && cp <= 0x1aff) || (cp >= 0x1dc0 && cp <= 0x1dff) ||
      (cp >= 0x20d0 && cp <= 0x20ff) || (cp >= 0xfe20 && cp <= 0xfe2f)

  /** The code points of `label`, or `None` when it holds an unpaired surrogate. */
  def codePoints(label: String): Option[Vector[Int]] =
    val out = Vector.newBuilder[Int]
    var index = 0
    var valid = true
    while index < label.length && valid do
      val cp = label.codePointAt(index)
      if cp >= 0xd800 && cp <= 0xdfff then valid = false
      out += cp
      index += Character.charCount(cp)
    Option.when(valid)(out.result())

  /** The layouts a viewer may give the label's white space: as written, and with runs of spaces
    * collapsed and leading and trailing spaces removed, as SVG and CSS white-space processing does.
    */
  def whiteSpaceVariants(cps: Vector[Int]): Vector[Vector[Int]] =
    val collapsed = Vector.newBuilder[Int]
    var previousSpace = true
    cps.foreach { cp =>
      if cp == 0x20 then
        if !previousSpace then collapsed += cp
        previousSpace = true
      else
        collapsed += cp
        previousSpace = false
    }
    val stripped = collapsed.result().reverse.dropWhile(_ == 0x20).reverse
    Vector(cps, stripped).distinct

  /** Bound the run `cps` drawn from `font` with `anchorFraction` of its advance left of the anchor
    * (0 start, 0.5 middle, 1 end).
    */
  def measure(font: SfntFont, cps: Vector[Int], anchorFraction: Double): Either[String, RunExtent] =
    font.startMeasurement()
    SfntFont.guard(layout(font, cps, anchorFraction)) match
      case Some(result) => result
      case None         => Left("the face has a malformed table")

  private def hex(cp: Int): String = f"U+$cp%04X"

  private def layout(
      font: SfntFont,
      cps: Vector[Int],
      anchorFraction: Double
  ): Either[String, RunExtent] =
    val n = cps.length
    val glyphs = new Array[Int](n)
    var problem: Option[String] =
      font.unmodelledTables.headOption.map(t => s"the face has a '$t' table")
    if problem.isEmpty && font.unreadableKern then
      problem = Some("the face's 'kern' table is unreadable")
    var index = 0
    while index < n && problem.isEmpty do
      val cp = cps(index)
      if unmodelledCodePoint(cp) then
        problem = Some(s"${hex(cp)} needs shaping this model does not cover")
      else
        font.glyphFor(cp) match
          case Some(glyph) => glyphs(index) = glyph
          case None        => problem = Some(s"the face has no glyph for ${hex(cp)}")
      index += 1
    problem match
      case Some(reason)   => Left(reason)
      case None if n == 0 => Right(RunExtent(None, Span.zero))
      case None           => layoutGlyphs(font, cps, glyphs.toVector, anchorFraction)

  private def layoutGlyphs(
      font: SfntFont,
      cps: Vector[Int],
      glyphs: Vector[Int],
      anchorFraction: Double
  ): Either[String, RunExtent] =
    val n = cps.length
    val combining = Vector.tabulate(n) { i =>
      UnicodeComposition.combining(cps(i)) || markBlock(cps(i)) || font.glyphClass(glyphs(i)) == 3
    }
    val candidates = Array.tabulate(n)(i => mutable.LinkedHashSet(glyphs(i)))
    val zeroable = Array.tabulate(n)(combining)
    val composed = Array.fill(n)(false)
    val ligatureGlyphs = mutable.Set.empty[Int]
    var problem: Option[String] = None

    // Canonical composition: HarfBuzz draws a base and its marks as the composite when the face
    // has one, so every composite reachable from the cluster is a candidate for the base.
    var base = 0
    while base < n do
      if !combining(base) then
        var end = base + 1
        while end < n && combining(end) do end += 1
        if end > base + 1 then
          val marks = mutable.LinkedHashSet.from(cps.slice(base + 1, end))
          val starts = mutable.LinkedHashSet(cps(base))
          var root = cps(base)
          while UnicodeComposition.decompose.contains(root) do
            val (first, second) = UnicodeComposition.decompose(root)
            starts += first
            marks += second
            root = first
          val reached = mutable.LinkedHashSet.from(starts)
          var grew = true
          while grew && reached.size < CandidateLimit do
            grew = false
            for s <- reached.toVector; m <- marks do
              UnicodeComposition.compose.get((s, m)).foreach { composite =>
                if reached.add(composite) then grew = true
              }
          reached.iterator.filter(_ != cps(base)).foreach { cp =>
            font.glyphFor(cp).foreach { glyph =>
              candidates(base) += glyph
              (base + 1 until end).foreach(i => composed(i) = true)
            }
          }
        base = end
      else base += 1

    def close(slot: Int): Unit =
      val queue = mutable.Queue.from(candidates(slot))
      while queue.nonEmpty && problem.isEmpty do
        font.substitutes(queue.dequeue()).foreach { glyph =>
          if candidates(slot).add(glyph) then queue.enqueue(glyph)
        }
        if candidates(slot).size > CandidateLimit then
          problem = Some("substitutions reach too many glyphs")

    (0 until n).foreach(close)

    // Ligatures, matched against the following glyphs both adjacent and skipping marks.
    def following(slot: Int, skipMarks: Boolean): Vector[Int] =
      ((slot + 1) until n).filter(i => !skipMarks || !combining(i)).toVector
    var round = 0
    var changed = true
    while changed && round < 4 && problem.isEmpty do
      changed = false
      round += 1
      for
        slot <- 0 until n; first <- candidates(slot).toVector;
        (rest, ligature) <- font.ligatures(first)
      do
        for skip <- Vector(false, true) do
          val next = following(slot, skip)
          if rest.length <= next.length && rest.indices.forall(k =>
              candidates(next(k)).contains(rest(k))
            )
          then
            ligatureGlyphs += ligature
            rest.indices.foreach(k => zeroable(next(k)) = true)
            if candidates(slot).add(ligature) then
              changed = true
              close(slot)
    if changed && problem.isEmpty then problem = Some("ligatures nest too deeply")

    val allCandidates = candidates.iterator.flatMap(_.iterator).toSet
    val ligatureLike = ligatureGlyphs.toSet ++ allCandidates.filter(font.glyphClass(_) == 2)
    if problem.isEmpty then
      allCandidates.find(font.multipleSubstitution).foreach { glyph =>
        problem = Some(s"glyph $glyph is multiply substituted")
      }
    if problem.isEmpty then
      allCandidates.find(font.unmodelledPositioning(_, ligatureLike)).foreach { glyph =>
        problem = Some(s"glyph $glyph takes cursive or mark-to-ligature attachment")
      }
    if problem.isEmpty && !font.hasMarkPositioning then
      (0 until n).find(i => combining(i) && !composed(i)).foreach { i =>
        problem = Some(s"${hex(cps(i))} is a combining mark and the face has no mark positioning")
      }
    problem match
      case Some(reason) => Left(reason)
      case None         =>
        Right(
          place(
            font,
            candidates.map(_.toVector).toVector,
            combining,
            zeroable.toVector,
            anchorFraction
          )
        )

  private def place(
      font: SfntFont,
      candidates: Vector[Vector[Int]],
      combining: Vector[Boolean],
      zeroable: Vector[Boolean],
      anchorFraction: Double
  ): RunExtent =
    val n = candidates.length
    val advance = Array.tabulate(n) { i =>
      val own = candidates(i).iterator.map(g => Span.at(font.advance(g).toDouble)).reduce(_ hull _)
      if zeroable(i) then own.including(0.0) else own
    }
    val dx = Array.fill(n)(Span.zero)
    val dy = Array.fill(n)(Span.zero)
    def hullOver[A](items: Iterable[A])(span: A => Span): Span =
      items.iterator.map(span).foldLeft(Span.zero)(_ hull _)
    (0 until n).foreach { i =>
      val singles = candidates(i).map(font.singleAdjustments)
      advance(i) = advance(i) + hullOver(singles)(v => Span.subsetSums(v.map(_.xAdvance)))
      dx(i) = dx(i) + hullOver(singles)(v => Span.subsetSums(v.map(_.xPlacement)))
      dy(i) = dy(i) + hullOver(singles)(v => Span.subsetSums(v.map(_.yPlacement)))
    }
    // Pairs: each glyph with the following glyphs up to and including the next non-mark, so lookups
    // that skip marks are covered as well as those that do not.
    (0 until n).foreach { i =>
      var j = i + 1
      var reachedBase = false
      while j < n && !reachedBase do
        val pairs = for a <- candidates(i); b <- candidates(j) yield
          val gpos = font.pairAdjustments(a, b)
          val legacy = font.legacyKern(a, b)
          if legacy == 0 then gpos
          else gpos :+ (GlyphAdjustment(0, 0, legacy), GlyphAdjustment.zero)
        advance(i) = advance(i) + hullOver(pairs)(v => Span.subsetSums(v.map(_._1.xAdvance)))
        dx(i) = dx(i) + hullOver(pairs)(v => Span.subsetSums(v.map(_._1.xPlacement)))
        dy(i) = dy(i) + hullOver(pairs)(v => Span.subsetSums(v.map(_._1.yPlacement)))
        advance(j) = advance(j) + hullOver(pairs)(v => Span.subsetSums(v.map(_._2.xAdvance)))
        dx(j) = dx(j) + hullOver(pairs)(v => Span.subsetSums(v.map(_._2.xPlacement)))
        dy(j) = dy(j) + hullOver(pairs)(v => Span.subsetSums(v.map(_._2.yPlacement)))
        reachedBase = !combining(j)
        j += 1
    }
    val total = advance.foldLeft(Span.zero)(_ + _)
    // The anchor puts `anchorFraction` of the whole advance left of it, so glyph k starts at
    // sum(m < k) (1 - f) a(m) - sum(m >= k) f a(m). Each advance enters once, so the interval is
    // exact for independent advances rather than paying for every advance twice.
    val behind = Array.fill(n + 1)(Span.zero)
    val ahead = Array.fill(n + 1)(Span.zero)
    (0 until n).foreach { m =>
      behind(m + 1) = behind(m) + Span(
        (1.0 - anchorFraction) * advance(m).lo,
        (1.0 - anchorFraction) * advance(m).hi
      )
    }
    (n - 1 to 0 by -1).foreach { m =>
      ahead(m) =
        ahead(m + 1) + Span(-anchorFraction * advance(m).hi, -anchorFraction * advance(m).lo)
    }
    val originX = new Array[Span](n)
    val originY = new Array[Span](n)
    (0 until n).foreach { k =>
      val pen = behind(k) + ahead(k)
      var x = pen + dx(k)
      var y = dy(k)
      if k > 0 then
        // A shaper may attach to any earlier glyph back to the nearest base, depending on which
        // marks a lookup's flags skip: every one is a candidate, for mark-to-base and mark-to-mark.
        val baseSlot = (k - 1 to 0 by -1).find(i => !combining(i)).getOrElse(0)
        for
          b <- k - 1 to baseSlot by -1; kind <- Vector(4, 6); g <- candidates(b);
          m <- candidates(k); (ax, ay) <- font.markAttachments(kind, g, m)
        do
          x = x.hull(originX(b) + dx(k) + Span.at(ax.toDouble))
          y = y.hull(originY(b) + dy(k) + Span.at(ay.toDouble))
      originX(k) = x
      originY(k) = y
    }
    var ink: Option[(Double, Double, Double, Double)] = None
    (0 until n).foreach { k =>
      candidates(k).foreach { glyph =>
        font.glyphBox(glyph).foreach { box =>
          val placed = (
            originX(k).lo + box.xMin,
            originY(k).lo + box.yMin,
            originX(k).hi + box.xMax,
            originY(k).hi + box.yMax
          )
          ink = Some(ink.fold(placed) { case (x0, y0, x1, y1) =>
            (
              math.min(x0, placed._1),
              math.min(y0, placed._2),
              math.max(x1, placed._3),
              math.max(y1, placed._4)
            )
          })
        }
      }
    }
    RunExtent(ink, total)
