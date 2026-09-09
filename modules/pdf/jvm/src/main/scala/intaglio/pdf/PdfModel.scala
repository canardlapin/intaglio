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
  case DuplicateFontFace(family: String, weight: Int)
  case MissingFontWeight(family: Option[String], weight: Int)
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
      case DuplicateFontFace(family, weight) =>
        s"font catalog has more than one face for family '$family' at weight $weight"
      case MissingFontWeight(family, weight) =>
        s"font catalog has no face at weight $weight for ${family.fold("the default family")(name =>
            s"family '$name'"
          )}"
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
    val weight: FontWeight,
    private val encoded: Array[Byte]
):
  private[pdf] def inputStream: ByteArrayInputStream =
    new ByteArrayInputStream(encoded)

object PdfFont:
  /** A face is one family at one weight. PDF embeds font programs and PDFBox will not synthesize a
    * bold face for a subset, so a document that draws bold has to be given a bold face.
    */
  def fromBytes(
      family: String,
      bytes: Array[Byte],
      weight: FontWeight = FontWeight.Regular
  ): Either[PdfRenderError, PdfFont] =
    val canonical = family.trim
    if canonical.isEmpty then Left(PdfRenderError.BlankFontFamily)
    else if bytes.isEmpty then Left(PdfRenderError.EmptyFontData(canonical))
    else Right(new PdfFont(canonical, weight, bytes.clone()))

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
    private val indexed: Map[(String, Int), PdfFont],
    val families: Vector[String]
):
  /** The face for a family at a weight.
    *
    * An unset weight means whatever the family was registered as, which keeps a catalog that never
    * mentions weight behaving exactly as it did. A set weight is looked up exactly: PDF embeds font
    * programs and cannot synthesize a face it was not given, so a near miss would be a document
    * that silently reads as regular.
    */
  private[pdf] def resolve(family: Option[String], weight: Option[FontWeight]): Option[PdfFont] =
    (family, weight) match
      case (Some(value), Some(requested)) =>
        indexed.get((PdfFontCatalog.normalize(value), requested.value))
      case (Some(value), None) =>
        facesFor(PdfFontCatalog.normalize(value)).minByOption(_.weight.value)
      case (None, Some(requested)) =>
        default.flatMap(font =>
          indexed.get((PdfFontCatalog.normalize(font.family), requested.value))
        )
      case (None, None) => default

  /** Whether the family exists at any weight, which separates "no such family" from "no such
    * weight" when reporting a failure.
    */
  private[pdf] def hasFamily(family: Option[String]): Boolean =
    family match
      case Some(value) => facesFor(PdfFontCatalog.normalize(value)).nonEmpty
      case None        => default.isDefined

  private def facesFor(key: String): Vector[PdfFont] =
    indexed.iterator.collect { case ((family, _), font) if family == key => font }.toVector

  val fontRegistry: FontRegistry =
    FontRegistry {
      case Some(value) =>
        facesFor(PdfFontCatalog.normalize(value)).headOption.map(_.family).orElse(Some(value))
      case None => default.map(_.family)
    }

object PdfFontCatalog:
  val empty: PdfFontCatalog =
    new PdfFontCatalog(None, Map.empty, Vector.empty)

  def single(font: PdfFont): PdfFontCatalog =
    new PdfFontCatalog(
      Some(font),
      Map((normalize(font.family), font.weight.value) -> font),
      Vector(font.family)
    )

  def from(
      default: PdfFont,
      additional: PdfFont*
  ): Either[PdfRenderError, PdfFontCatalog] =
    val all = default +: additional.toVector
    var seen = Set.empty[(String, Int)]
    var duplicate: Option[PdfFont] = None
    var index = 0
    while index < all.length && duplicate.isEmpty do
      val font = all(index)
      val key = (normalize(font.family), font.weight.value)
      if seen.contains(key) then duplicate = Some(font)
      else seen += key
      index += 1
    duplicate match
      case Some(font) => Left(PdfRenderError.DuplicateFontFace(font.family, font.weight.value))
      case None       =>
        Right(
          new PdfFontCatalog(
            Some(default),
            all.iterator.map(font => (normalize(font.family), font.weight.value) -> font).toMap,
            all.map(_.family).distinct
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

/** Package-visible semantic receipt recorded by the encoder branch that emitted each PDF element.
  * It lets the PDF backend run the same named conformance contract as command-oriented renderers
  * without adding test markers or Intaglio metadata to publication output.
  */
private[pdf] final case class PdfRenderTrace(
    markers: Vector[GraphicsName],
    requirements: Vector[RenderRequirement]
)

/** One-page PDF bytes plus the physical and resource contract observed while encoding them. */
final class PdfDocument private[pdf] (
    private val encoded: Array[Byte],
    val widthPoints: Double,
    val heightPoints: Double,
    val rasterPolicy: PdfRasterPolicy,
    val profile: PdfRenderProfile,
    private[pdf] val trace: PdfRenderTrace
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
