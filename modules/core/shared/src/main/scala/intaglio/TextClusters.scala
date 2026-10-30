package intaglio

/** Unicode 17 extended grapheme-cluster boundaries, expressed as UTF-16 offsets.
  *
  * This is deliberately a small shared primitive: renderers can keep a cluster intact while their
  * platform text engine performs glyph shaping. It does not attempt to render, measure, or split a
  * string into independently drawable code points.
  */
private[intaglio] object TextClusters:
  private final case class CodePoint(
      value: Int,
      utf16End: Int,
      gcb: Int,
      incb: Int,
      extendedPictographic: Boolean
  )

  private final val Other = 0
  private final val CR = 1
  private final val LF = 2
  private final val Control = 3
  private final val Extend = 4
  private final val ZWJ = 5
  private final val RegionalIndicator = 6
  private final val Prepend = 7
  private final val SpacingMark = 8
  private final val L = 9
  private final val V = 10
  private final val T = 11
  private final val LV = 12
  private final val LVT = 13

  private final val InCBConsonant = 1
  private final val InCBLinker = 2
  private final val InCBExtend = 3

  /** Returns cluster boundaries including zero and `text.length`.
    *
    * Lone UTF-16 surrogate code units are rejected instead of being silently treated as scalar
    * values. A high/low surrogate pair is decoded as one Unicode scalar.
    */
  def boundaries(text: String): Vector[Int] =
    val codePoints = decode(text)
    if codePoints.isEmpty then Vector(0)
    else
      val builder = Vector.newBuilder[Int]
      builder += 0
      var index = 1
      while index < codePoints.length do
        if breaksBefore(codePoints, index) then builder += codePoints(index - 1).utf16End
        index += 1
      builder += text.length
      builder.result()

  /** True when a fallback renderer must not substitute per-code-point drawing.
    *
    * The allow-list is intentionally narrow. Scripts not explicitly listed by Unicode's Scripts.txt
    * -- including Unknown/unassigned scalar values -- are protected, as they may require contextual
    * shaping.
    */
  def protectedScript(codePoint: Int): Boolean =
    if codePoint < 0 || codePoint > 0x10ffff then true
    else !UnicodeTextData.isUnprotectedScript(codePoint)

  private def decode(text: String): Vector[CodePoint] =
    val builder = Vector.newBuilder[CodePoint]
    var offset = 0
    while offset < text.length do
      val first = text.charAt(offset)
      val value =
        if Character.isHighSurrogate(first) then
          if offset + 1 >= text.length || !Character.isLowSurrogate(text.charAt(offset + 1)) then
            throw IllegalArgumentException(
              s"malformed UTF-16: high surrogate at index $offset is not paired"
            )
          val scalar = Character.toCodePoint(first, text.charAt(offset + 1))
          offset += 2
          scalar
        else if Character.isLowSurrogate(first) then
          throw IllegalArgumentException(
            s"malformed UTF-16: low surrogate at index $offset has no leading high surrogate"
          )
        else
          offset += 1
          first.toInt
      builder += CodePoint(
        value,
        offset,
        UnicodeTextData.graphemeBreakClass(value),
        UnicodeTextData.indicConjunctBreakClass(value),
        UnicodeTextData.isExtendedPictographic(value)
      )
    builder.result()

  private def breaksBefore(codePoints: Vector[CodePoint], index: Int): Boolean =
    val previous = codePoints(index - 1)
    val current = codePoints(index)

    // GB3 through GB9b.  The control rules precede all rules which otherwise
    // suppress a break, as prescribed by UAX #29.
    if previous.gcb == CR && current.gcb == LF then false
    else if isControl(previous.gcb) || isControl(current.gcb) then true
    else if previous.gcb == L && isOneOf(current.gcb, L, V, LV, LVT) then false
    else if isOneOf(previous.gcb, LV, V) && isOneOf(current.gcb, V, T) then false
    else if isOneOf(previous.gcb, LVT, T) && current.gcb == T then false
    else if current.gcb == Extend || current.gcb == ZWJ || current.gcb == SpacingMark then false
    else if previous.gcb == Prepend then false
    else if isIndicConjunct(codePoints, index) then false // GB9c
    else if isEmojiZwjSequence(codePoints, index) then false // GB11
    else if previous.gcb == RegionalIndicator && current.gcb == RegionalIndicator && oddPrecedingRIs(
        codePoints,
        index
      )
    then false
    else true

  private def isControl(gcb: Int): Boolean = gcb == CR || gcb == LF || gcb == Control

  private def isOneOf(value: Int, first: Int, second: Int): Boolean =
    value == first || value == second
  private def isOneOf(value: Int, first: Int, second: Int, third: Int, fourth: Int): Boolean =
    value == first || value == second || value == third || value == fourth

  private def oddPrecedingRIs(codePoints: Vector[CodePoint], index: Int): Boolean =
    var cursor = index - 1
    var count = 0
    while cursor >= 0 && codePoints(cursor).gcb == RegionalIndicator do
      count += 1
      cursor -= 1
    (count & 1) == 1

  private def isEmojiZwjSequence(codePoints: Vector[CodePoint], index: Int): Boolean =
    if !codePoints(index).extendedPictographic || codePoints(index - 1).gcb != ZWJ then false
    else
      var cursor = index - 2
      while cursor >= 0 && codePoints(cursor).gcb == Extend do cursor -= 1
      cursor >= 0 && codePoints(cursor).extendedPictographic

  private def isIndicConjunct(codePoints: Vector[CodePoint], index: Int): Boolean =
    if codePoints(index).incb != InCBConsonant then false
    else
      var cursor = index - 1
      var sawLinker = false
      while cursor >= 0 && isOneOf(codePoints(cursor).incb, InCBExtend, InCBLinker) do
        sawLinker = sawLinker || codePoints(cursor).incb == InCBLinker
        cursor -= 1
      sawLinker && cursor >= 0 && codePoints(cursor).incb == InCBConsonant
