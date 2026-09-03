package intaglio

/** A checked `class` attribute value: one or more whitespace-separated tokens, each a conservative
  * identifier (`-?[A-Za-z_][A-Za-z0-9_-]*`, ASCII only, no escapes). The value is normalised to
  * single-space separation, so equality is token equality.
  */
final case class CssClass private (tokens: Vector[String]):
  def value: String =
    tokens.mkString(" ")

object CssClass:
  val expectation: String =
    "one or more whitespace-separated tokens, each an ASCII identifier of the form -?[A-Za-z_][A-Za-z0-9_-]*"

  def apply(value: String): Either[GraphicsError, CssClass] =
    val tokens = value.split("[ \\t\\n\\r\\f]+").toVector.filter(_.nonEmpty)
    if tokens.isEmpty then Left(GraphicsError.InvalidCssClass(value, expectation))
    else
      tokens.find(token => !isToken(token)) match
        case Some(token) => Left(GraphicsError.InvalidCssClass(token, expectation))
        case None        => Right(new CssClass(tokens))

  def unsafe(value: String): CssClass =
    apply(value).orThrow

  private def isToken(token: String): Boolean =
    val start = if token.startsWith("-") then 1 else 0
    token.length > start &&
    isIdentStart(token.charAt(start)) &&
    (start + 1 until token.length).forall(index => isIdentChar(token.charAt(index)))

  private def isIdentStart(char: Char): Boolean =
    (char >= 'A' && char <= 'Z') || (char >= 'a' && char <= 'z') || char == '_'

  private def isIdentChar(char: Char): Boolean =
    isIdentStart(char) || (char >= '0' && char <= '9') || char == '-'

/** A checked `data-*` attribute suffix: a lowercase ASCII letter followed by lowercase letters,
  * digits, or hyphens (`[a-z][a-z0-9-]*`). The suffix `name` is refused because `data-name` is the
  * one attribute every backend reserves for a grob's [[GraphicsName]].
  */
final case class DataKey private (value: String):
  /** The full attribute name, `data-<value>`. */
  def attributeName: String =
    s"data-$value"

object DataKey:
  val expectation: String =
    "a lowercase ASCII letter followed by lowercase letters, digits, or hyphens, and not the reserved suffix 'name'"

  def apply(value: String): Either[GraphicsError, DataKey] =
    if !isKey(value) || value == "name" then Left(GraphicsError.InvalidDataKey(value, expectation))
    else Right(new DataKey(value))

  def unsafe(value: String): DataKey =
    apply(value).orThrow

  private def isKey(value: String): Boolean =
    value.nonEmpty &&
      isLetter(value.charAt(0)) &&
      (1 until value.length).forall { index =>
        val char = value.charAt(index)
        isLetter(char) || (char >= '0' && char <= '9') || char == '-'
      }

  private def isLetter(char: Char): Boolean =
    char >= 'a' && char <= 'z'

/** Presentation-neutral metadata that a [[Grob.Annotated]] carries for its child.
  *
  * The SVG backend emits it as a wrapping `<g>` with `class` and `data-*` attributes and
  * `<title>`/`<desc>` children, so a static document carries hover text and a host stylesheet can
  * address the mark. Raster backends and PDF ignore it: metadata never changes geometry or paint.
  * Text is free-form; XML-illegal characters are refused at the SVG boundary, and every value is
  * escaped on output. `data` keeps insertion order; a duplicate key is refused at the SVG boundary
  * because an element cannot carry one attribute twice.
  */
final case class GrobMeta(
    title: Option[String] = None,
    description: Option[String] = None,
    cssClass: Option[CssClass] = None,
    data: Vector[(DataKey, String)] = Vector.empty
):
  def isEmpty: Boolean =
    title.isEmpty && description.isEmpty && cssClass.isEmpty && data.isEmpty

  def withTitle(value: String): GrobMeta =
    copy(title = Some(value))

  def withDescription(value: String): GrobMeta =
    copy(description = Some(value))

  def withCssClass(value: CssClass): GrobMeta =
    copy(cssClass = Some(value))

  def withData(key: DataKey, value: String): GrobMeta =
    copy(data = data :+ (key -> value))

  /** The first key that appears more than once, if any. */
  def duplicateDataKey: Option[DataKey] =
    val seen = scala.collection.mutable.HashSet.empty[DataKey]
    data.iterator.map(_._1).find(key => !seen.add(key))

object GrobMeta:
  val empty: GrobMeta =
    GrobMeta()

  def title(value: String): GrobMeta =
    GrobMeta(title = Some(value))
