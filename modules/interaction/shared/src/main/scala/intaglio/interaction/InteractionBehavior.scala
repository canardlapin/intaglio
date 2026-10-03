package intaglio.interaction

import intaglio.*

/** What a target shows when it is inspected: plain text, or a short structured document of labelled
  * fields. Content is a value, never markup or script; a host renders it as text, so no escaping is
  * the caller's job and no string can execute.
  */
enum TargetContent:
  case Text(value: String)
  case Fields(title: Option[String], rows: Vector[TargetField])

  /** The content as one line of plain text, for accessible names and text companions. */
  def plainText: String =
    this match
      case Text(value)         => value
      case Fields(title, rows) =>
        (title.toVector ++ rows.map(row => s"${row.label}: ${row.value}")).mkString("; ")

final case class TargetField private (label: String, value: String)

object TargetField:
  def apply(label: String, value: String): Either[InteractionError, TargetField] =
    for
      l <- TargetContent.checkedText("field label", label)
      v <- TargetContent.checkedText("field value", value)
    yield new TargetField(l, v)

object TargetContent:
  /** Text content; refuses characters XML and accessibility trees cannot carry. */
  def text(value: String): Either[InteractionError, TargetContent] =
    checkedText("content", value).map(Text(_))

  def fields(
      title: Option[String],
      rows: Vector[TargetField]
  ): Either[InteractionError, TargetContent] =
    title match
      case Some(value) => checkedText("content title", value).map(t => Fields(Some(t), rows))
      case None        => Right(Fields(None, rows))

  private[interaction] def checkedText(
      field: String,
      value: String
  ): Either[InteractionError, String] =
    // Walk code points by hand: java.util.stream is unavailable on Scala.js.
    var illegal: Option[Int] = None
    var index = 0
    while index < value.length && illegal.isEmpty do
      val cp = value.codePointAt(index)
      val unpairedSurrogate = Character.isSurrogate(value.charAt(index)) && cp < 0x10000
      if (cp < 0x20 && cp != 0x09 && cp != 0x0a && cp != 0x0d) || cp == 0xfffe ||
        cp == 0xffff || unpairedSurrogate
      then illegal = Some(cp)
      index += Character.charCount(cp)
    illegal match
      case Some(cp) =>
        Left(InteractionError.InvalidValue(field, s"contains code point U+${cp.toHexString}"))
      case None => Right(value)

/** Where activating a target leads. A link is a checked URL value, never a script string: only
  * `http`, `https` and `mailto` schemes, or a same-site relative reference, are accepted. A
  * scheme-relative (`//host`) or backslashed reference is refused because browsers send it to
  * another host.
  */
final case class TargetLink private (url: String, newContext: Boolean)

object TargetLink:
  private val allowedSchemes = Set("http", "https", "mailto")

  def apply(url: String, newContext: Boolean = false): Either[InteractionError, TargetLink] =
    val trimmed = url.trim
    val scheme = trimmed.takeWhile(c => c != ':' && c != '/' && c != '?' && c != '#')
    val hasScheme = scheme.length < trimmed.length && trimmed.charAt(scheme.length) == ':'
    if trimmed.isEmpty then Left(InteractionError.InvalidValue("link", "empty URL"))
    else if trimmed.exists(c => c.isControl || c.isWhitespace) then
      Left(InteractionError.InvalidValue("link", "URL contains whitespace or control characters"))
    else if trimmed.startsWith("//") || trimmed.contains('\\') then
      // A scheme-relative or backslashed reference resolves to another host in browsers.
      Left(
        InteractionError
          .InvalidValue("link", "scheme-relative and backslashed URLs are not allowed")
      )
    else if hasScheme && !allowedSchemes.contains(scheme.toLowerCase) then
      Left(InteractionError.InvalidValue("link", s"scheme '$scheme' is not allowed"))
    else Right(new TargetLink(trimmed, newContext))

/** Where a tooltip is placed. */
enum TooltipPlacement:
  /** Offset from the pointer; a keyboard-focused target falls back to its mark anchor. */
  case Pointer(offsetCssPx: Double)

  /** Beside the mark's anchor (its centre or point), whichever input revealed it. */
  case Anchored(offsetCssPx: Double)

  /** At a fixed position within the widget, in CSS pixels from its top-left corner. */
  case Fixed(xCssPx: Double, yCssPx: Double)

/** Which target the pointer reveals. */
enum HoverRule:
  /** Only a target the pointer is over, within the host's small hit tolerance. */
  case Direct

  /** The nearest target within `maxCssPx`, so sparse marks are easy to reach. */
  case Nearest(maxCssPx: Double)

/** Optional behaviours attached to an ordinary compiled plot or named scene. Hosts (browser,
  * JavaFX) read the same value; effects such as following a link stay in the host, and the state
  * transition function never sees them.
  *
  * `tooltip` and `link` are functions of the typed target, so content is described once from the
  * entity key (or a raster cell) rather than per mark. `inverseEmphasis` dims everything except the
  * hovered or selected targets; `tooltipDelayMs` applies to pointer hover only, never to keyboard
  * focus.
  */
final case class InteractionBehavior[A] private (
    tooltip: TargetInfo[A] => Option[TargetContent],
    link: TargetInfo[A] => Option[TargetLink],
    placement: TooltipPlacement,
    tooltipDelayMs: Int,
    hover: HoverRule,
    inverseEmphasis: Boolean,
    selection: SelectionMode,
    legendLinks: Vector[LegendLink] = Vector.empty
):
  def withTooltip(value: TargetInfo[A] => Option[TargetContent]): InteractionBehavior[A] =
    copy(tooltip = value)
  def withLink(value: TargetInfo[A] => Option[TargetLink]): InteractionBehavior[A] =
    copy(link = value)
  def withPlacement(value: TooltipPlacement): InteractionBehavior[A] = copy(placement = value)
  def withInverseEmphasis(value: Boolean): InteractionBehavior[A] = copy(inverseEmphasis = value)
  def withSelection(value: SelectionMode): InteractionBehavior[A] = copy(selection = value)

  /** Link a keyed legend to the marks whose layer binding projects its entries' link keys. */
  def withLegendLink(value: LegendLink): InteractionBehavior[A] =
    copy(legendLinks = legendLinks.filterNot(_.legend == value.legend) :+ value)

  def withTooltipDelay(ms: Int): Either[InteractionError, InteractionBehavior[A]] =
    if ms >= 0 && ms <= 10000 then Right(copy(tooltipDelayMs = ms))
    else Left(InteractionError.InvalidValue("tooltip delay", s"$ms ms is outside 0..10000"))

  def withHover(value: HoverRule): Either[InteractionError, InteractionBehavior[A]] =
    value match
      case HoverRule.Nearest(max) if !max.isFinite || max <= 0.0 =>
        Left(InteractionError.InvalidValue("nearest hover distance", s"$max css px"))
      case _ => Right(copy(hover = value))

object InteractionBehavior:
  /** No tooltip or link; pointer-relative placement, 300 ms delay, direct hover, no inverse
    * emphasis, multiple selection.
    */
  def default[A]: InteractionBehavior[A] =
    new InteractionBehavior[A](
      _ => None,
      _ => None,
      TooltipPlacement.Pointer(12.0),
      300,
      HoverRule.Direct,
      false,
      SelectionMode.Multiple
    )

  /** Tooltips naming each target's entity with `describe`, or a raster cell's value; a common
    * default for plots whose rows are identified by a readable key.
    */
  def describingEntities[A](describe: A => String): InteractionBehavior[A] =
    default[A].withTooltip { target =>
      target.entity
        .map(key => TargetContent.Text(describe(key.value)))
        .orElse(
          target.rasterCell
            .map(cell => TargetContent.Text(Labeler.default(Vector(cell.value)).head))
        )
    }
