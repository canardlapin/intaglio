package intaglio.pdf

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import java.util.Locale
import scala.util.control.NonFatal
import intaglio.*

enum PdfRenderError extends IntaglioError:
  case Graphics(error: GraphicsError)
  case InvalidPageSize(widthPoints: Double, heightPoints: Double)
  case BlankFontFamily
  case EmptyFontData(family: String)
  case DuplicateFontFamily(family: String)
  case FontReadFailed(path: Path, details: String)
  case MissingFont(family: Option[String])
  case FontLoadFailed(family: String, details: String)
  case UnsupportedGlyph(family: String, codePoint: Int)
  case PdfEncodingFailed(details: String)
  case PdfWriteFailed(path: Path, details: String)

  def message: String =
    this match
      case Graphics(error)                => error.message
      case InvalidPageSize(width, height) =>
        s"PDF page size must be finite, positive, and no larger than 14,400 points per axis: ${width}x$height"
      case BlankFontFamily =>
        "PDF font family must not be blank"
      case EmptyFontData(family) =>
        s"PDF font '$family' has no font data"
      case DuplicateFontFamily(family) =>
        s"PDF font catalog contains duplicate family '$family'"
      case FontReadFailed(path, details) =>
        s"Could not read PDF font '$path': $details"
      case MissingFont(Some(family)) =>
        s"No embedded PDF font is registered for family '$family'"
      case MissingFont(None) =>
        "The PDF contains text but the font catalog has no default font"
      case FontLoadFailed(family, details) =>
        s"Could not load embedded PDF font '$family': $details"
      case UnsupportedGlyph(family, codePoint) =>
        val hex = Integer.toHexString(codePoint).toUpperCase(Locale.ROOT)
        s"Embedded PDF font '$family' does not contain Unicode code point U+$hex"
      case PdfEncodingFailed(details) =>
        s"PDF encoding failed: $details"
      case PdfWriteFailed(path, details) =>
        s"Could not write PDF '$path': $details"

object PdfRenderError:
  extension [A](either: Either[PdfRenderError, A])
    def orThrow: A =
      either match
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(error.message)

/** Immutable TrueType bytes (including OpenType fonts with TrueType outlines) registered under the
  * family name that appears in a [[intaglio.RenderContext]]. PDF output never searches
  * host-installed fonts or substitutes a PDF base font: every text run must resolve to one of these
  * supplied resources.
  */
final class PdfFont private (
    val family: String,
    private val encoded: Array[Byte]
):
  private[pdf] def inputStream: ByteArrayInputStream =
    new ByteArrayInputStream(encoded)

object PdfFont:
  def fromBytes(family: String, bytes: Array[Byte]): Either[PdfRenderError, PdfFont] =
    val canonical = family.trim
    if canonical.isEmpty then Left(PdfRenderError.BlankFontFamily)
    else if bytes.isEmpty then Left(PdfRenderError.EmptyFontData(canonical))
    else Right(new PdfFont(canonical, bytes.clone()))

  def load(family: String, path: Path): Either[PdfRenderError, PdfFont] =
    try fromBytes(family, Files.readAllBytes(path))
    catch
      case NonFatal(error) =>
        Left(PdfRenderError.FontReadFailed(path, PdfMessages.details(error)))

/** Deterministic mapping from resolved Intaglio font-family names to embedded PDF font data. Family
  * matching is case-insensitive. Unknown requested families remain unknown through `fontRegistry`
  * and fail closed during rendering; only an absent request uses the default.
  */
final class PdfFontCatalog private (
    private val default: Option[PdfFont],
    private val indexed: Map[String, PdfFont],
    val families: Vector[String]
):
  private[pdf] def resolve(family: Option[String]): Option[PdfFont] =
    family match
      case Some(value) => indexed.get(PdfFontCatalog.normalize(value))
      case None        => default

  val fontRegistry: FontRegistry =
    FontRegistry {
      case Some(value) =>
        indexed.get(PdfFontCatalog.normalize(value)).map(_.family).orElse(Some(value))
      case None => default.map(_.family)
    }

object PdfFontCatalog:
  val empty: PdfFontCatalog =
    new PdfFontCatalog(None, Map.empty, Vector.empty)

  def single(font: PdfFont): PdfFontCatalog =
    val key = normalize(font.family)
    new PdfFontCatalog(Some(font), Map(key -> font), Vector(font.family))

  def from(
      default: PdfFont,
      additional: PdfFont*
  ): Either[PdfRenderError, PdfFontCatalog] =
    val all = default +: additional.toVector
    var seen = Set.empty[String]
    var duplicate: Option[String] = None
    var index = 0
    while index < all.length && duplicate.isEmpty do
      val font = all(index)
      val key = normalize(font.family)
      if seen.contains(key) then duplicate = Some(font.family)
      else seen += key
      index += 1
    duplicate match
      case Some(family) => Left(PdfRenderError.DuplicateFontFamily(family))
      case None         =>
        Right(
          new PdfFontCatalog(
            Some(default),
            all.iterator.map(font => normalize(font.family) -> font).toMap,
            all.map(_.family)
          )
        )

  private[pdf] def normalize(value: String): String =
    value.trim.toLowerCase(Locale.ROOT)

/** PDF output has one deliberately narrow raster boundary. All Intaglio shapes, text, clips, and
  * fill patterns remain PDF vector operations; only explicit [[intaglio.RasterImage]] grobs become
  * lossless image XObjects.
  */
enum PdfRasterPolicy:
  case ExplicitImagesOnly

final case class PdfOptions(
    title: Option[String] = None,
    author: Option[String] = None,
    subject: Option[String] = None
)

object PdfOptions:
  val default: PdfOptions = PdfOptions()

final case class PdfRenderProfile(
    vectorShapes: Int,
    textRuns: Int,
    rasterImagePlacements: Int,
    rasterPayloads: Int,
    vectorPatterns: Int,
    embeddedSubsetFonts: Int
)

/** One-page PDF bytes plus the physical and resource contract observed while encoding them. */
final class PdfDocument private[pdf] (
    private val encoded: Array[Byte],
    val widthPoints: Double,
    val heightPoints: Double,
    val rasterPolicy: PdfRasterPolicy,
    val profile: PdfRenderProfile
):
  def bytes: Array[Byte] =
    encoded.clone()

  def writeTo(path: Path): Either[PdfRenderError, Path] =
    try
      Files.write(path, encoded)
      Right(path)
    catch
      case NonFatal(error) =>
        Left(PdfRenderError.PdfWriteFailed(path, PdfMessages.details(error)))

private[pdf] object PdfMessages:
  def details(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
