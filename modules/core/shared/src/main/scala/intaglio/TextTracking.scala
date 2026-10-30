package intaglio

/** Safe units for the conservative tracking policy. Contextual-script words stay intact; other
  * words may be spaced between extended grapheme clusters. Offsets refer to the original UTF-16
  * paragraph, so a native shaper can retain bidi controls and contextual information.
  */
private[intaglio] object TextTracking:
  final case class UnitRange(start: Int, end: Int, spaced: Boolean)

  def unitCount(text: String): Int = units(text).count(_.spaced)

  def validate(text: String): Either[GraphicsError, Unit] =
    try
      units(text)
      Right(())
    catch
      case error: IllegalArgumentException =>
        Left(GraphicsError.InvalidTextTracking(error.getMessage))

  def units(text: String): Vector[UnitRange] =
    require(
      !text.exists(c => c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'),
      "Letter spacing applies to a single paragraph; place separate lines explicitly"
    )
    val boundaries = TextClusters.boundaries(text)
    val clusters = boundaries
      .sliding(2)
      .collect { case Vector(start, end) =>
        UnitRange(start, end, hasVisibleCharacter(text, start, end))
      }
      .toVector
    val result = Vector.newBuilder[UnitRange]
    var index = 0
    while index < clusters.length do
      val first = clusters(index)
      if Character.isWhitespace(text.codePointAt(first.start)) then
        result += first
        index += 1
      else
        var limit = index + 1
        while limit < clusters.length &&
          !Character.isWhitespace(text.codePointAt(clusters(limit).start))
        do limit += 1
        val end = clusters(limit - 1).end
        var offset = first.start
        var protect = false
        while offset < end do
          val cp = text.codePointAt(offset)
          protect ||= TextClusters.protectedScript(cp)
          offset += Character.charCount(cp)
        if protect then
          result += UnitRange(first.start, end, hasVisibleCharacter(text, first.start, end))
        else result ++= clusters.slice(index, limit)
        index = limit
    result.result()

  private def hasVisibleCharacter(text: String, start: Int, end: Int): Boolean =
    var offset = start
    var visible = false
    while offset < end do
      val cp = text.codePointAt(offset)
      val kind = Character.getType(cp)
      visible ||= kind != Character.FORMAT && kind != Character.CONTROL
      offset += Character.charCount(cp)
    visible
