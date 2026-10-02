package intaglio.svg

import intaglio.{FontWeight, IntaglioError}

/** A font file format an SVG viewer can load from an `@font-face` data URI, recognised by the
  * file's own signature rather than by a caller's label.
  */
enum SvgFontFormat(val mediaType: String, val cssFormat: String):
  case Woff2 extends SvgFontFormat("font/woff2", "woff2")
  case Woff extends SvgFontFormat("font/woff", "woff")
  case TrueType extends SvgFontFormat("font/ttf", "truetype")
  case OpenType extends SvgFontFormat("font/otf", "opentype")

enum SvgFontError extends IntaglioError:
  case InvalidFamily(family: String)
  case Empty(family: String)
  case TooLarge(family: String, bytes: Int)
  case UnrecognisedFormat(family: String)
  case EmbeddingRestricted(family: String)
  case DuplicateFace(family: String, weight: Int)

  def message: String =
    this match
      case InvalidFamily(family) =>
        s"SVG font family '$family' must be non-empty printable text without quotes, backslashes, " +
          "markup or CSS punctuation"
      case Empty(family)           => s"SVG font face '$family' has no bytes"
      case TooLarge(family, bytes) =>
        s"SVG font face '$family' is $bytes bytes; at most ${SvgFontFace.MaximumBytes} embed"
      case UnrecognisedFormat(family) =>
        s"SVG font face '$family' is not a WOFF2, WOFF, TrueType or OpenType file"
      case EmbeddingRestricted(family) =>
        s"SVG font face '$family' declares restricted-licence embedding (OS/2 fsType); " +
          "it may not be embedded"
      case DuplicateFace(family, weight) =>
        s"SVG fonts supply family '$family' at weight $weight twice"

object SvgFontError:
  extension [A](either: Either[SvgFontError, A])
    def orThrow: A =
      either match
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(error.message)

/** Font bytes a caller supplies, and is licensed, to embed in SVG output.
  *
  * Intaglio bundles no typeface: the caller chooses the face and answers for its licence. The
  * constructor recognises the format from the file signature, bounds the size, and refuses a
  * TrueType or OpenType file whose OS/2 `fsType` declares restricted-licence embedding. WOFF and
  * WOFF2 tables are compressed, so that flag is not read for them.
  */
final class SvgFontFace private (
    val family: String,
    val weight: FontWeight,
    val format: SvgFontFormat,
    private val data: Array[Byte]
):
  /** A copy of the font bytes. */
  def bytes: Array[Byte] = data.clone()

  private[svg] def dataUri: String =
    s"data:${format.mediaType};base64,${PngEncoder.base64(data)}"

  override def equals(other: Any): Boolean =
    other match
      case that: SvgFontFace =>
        family == that.family && weight == that.weight && format == that.format &&
        java.util.Arrays.equals(data, that.data)
      case _ => false

  override def hashCode: Int =
    (family, weight.value, format, java.util.Arrays.hashCode(data)).hashCode

  override def toString: String =
    s"SvgFontFace($family, ${weight.value}, $format, ${data.length} bytes)"

object SvgFontFace:
  /** Larger faces are refused rather than silently inflating every document they appear in. */
  val MaximumBytes: Int = 16 * 1024 * 1024

  def apply(
      family: String,
      bytes: Array[Byte],
      weight: FontWeight = FontWeight.Regular
  ): Either[SvgFontError, SvgFontFace] =
    if !validFamily(family) then Left(SvgFontError.InvalidFamily(family))
    else if bytes.isEmpty then Left(SvgFontError.Empty(family))
    else if bytes.length > MaximumBytes then Left(SvgFontError.TooLarge(family, bytes.length))
    else
      detect(bytes) match
        case None         => Left(SvgFontError.UnrecognisedFormat(family))
        case Some(format) =>
          val sfnt = format == SvgFontFormat.TrueType || format == SvgFontFormat.OpenType
          if sfnt && restrictedEmbedding(bytes) then Left(SvgFontError.EmbeddingRestricted(family))
          else Right(new SvgFontFace(family, weight, format, bytes.clone()))

  def unsafe(
      family: String,
      bytes: Array[Byte],
      weight: FontWeight = FontWeight.Regular
  ): SvgFontFace =
    apply(family, bytes, weight).orThrow

  private def validFamily(family: String): Boolean =
    family.trim.nonEmpty && family.length <= 256 && family.forall { c =>
      c >= ' ' && c != 0x7f && !"\"'\\<>&;{}".contains(c)
    }

  private def tag(bytes: Array[Byte], offset: Int): Int =
    ((bytes(offset) & 0xff) << 24) | ((bytes(offset + 1) & 0xff) << 16) |
      ((bytes(offset + 2) & 0xff) << 8) | (bytes(offset + 3) & 0xff)

  private def u16(bytes: Array[Byte], offset: Int): Int =
    ((bytes(offset) & 0xff) << 8) | (bytes(offset + 1) & 0xff)

  private def detect(bytes: Array[Byte]): Option[SvgFontFormat] =
    if bytes.length < 12 then None
    else
      tag(bytes, 0) match
        case 0x774f4632              => Some(SvgFontFormat.Woff2) // "wOF2"
        case 0x774f4646              => Some(SvgFontFormat.Woff) // "wOFF"
        case 0x00010000 | 0x74727565 => Some(SvgFontFormat.TrueType) // 1.0, "true"
        case 0x4f54544f              => Some(SvgFontFormat.OpenType) // "OTTO"
        case _                       => None

  /** OS/2 `fsType` bit 1 (0x0002) is "Restricted License embedding": the face must not be embedded.
    * A file without a readable OS/2 table is not judged restricted here.
    */
  private def restrictedEmbedding(bytes: Array[Byte]): Boolean =
    val tables = u16(bytes, 4)
    val os2 = (0 until tables).iterator
      .map(index => 12 + index * 16)
      .takeWhile(record => record + 16 <= bytes.length)
      .find(record => tag(bytes, record) == 0x4f532f32) // "OS/2"
    os2.exists { record =>
      val offset = tag(bytes, record + 8)
      offset >= 0 && offset + 10 <= bytes.length && (u16(bytes, offset + 8) & 0x000f) == 0x0002
    }

/** The faces an SVG document may embed. A face is written only when some text run in the document
  * names its family, so supplying a face costs nothing in documents that do not use it.
  */
final class SvgFonts private (val faces: Vector[SvgFontFace]):
  override def equals(other: Any): Boolean =
    other match
      case that: SvgFonts => faces == that.faces
      case _              => false

  override def hashCode: Int = faces.hashCode

  override def toString: String = s"SvgFonts(${faces.mkString(", ")})"

object SvgFonts:
  val empty: SvgFonts = new SvgFonts(Vector.empty)

  /** CSS compares family names ASCII case-insensitively; this is the same fold on both platforms,
    * with no locale.
    */
  private[svg] def familyKey(family: String): String =
    family.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)

  /** Faces in the order given, which is the order they are written. Family names match text runs
    * case-insensitively, as CSS matches them, so one family may not repeat a weight.
    */
  def apply(faces: SvgFontFace*): Either[SvgFontError, SvgFonts] =
    val keys =
      faces.map(face => (SvgFonts.familyKey(face.family), face.weight.value))
    keys.indices.find(index => keys.indexOf(keys(index)) != index) match
      case Some(index) => Left(SvgFontError.DuplicateFace(faces(index).family, keys(index)._2))
      case None        => Right(new SvgFonts(faces.toVector))
