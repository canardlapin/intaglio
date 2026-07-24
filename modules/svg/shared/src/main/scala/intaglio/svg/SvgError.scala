package intaglio.svg

import intaglio.{GraphicsError, IntaglioError}

enum SvgRenderError extends IntaglioError:
  case InvalidDocumentSize(width: Int, height: Int)
  case InvalidXmlCharacter(field: String, codePoint: Int)
  case Graphics(error: GraphicsError)

  def message: String =
    this match
      case InvalidDocumentSize(width, height) =>
        s"SVG document size must be positive: ${width}x$height"
      case InvalidXmlCharacter(field, codePoint) =>
        s"SVG $field contains XML-illegal code point U+${codePoint.toHexString.toUpperCase}"
      case Graphics(error) =>
        error.message

object SvgRenderError:
  extension [A](either: Either[SvgRenderError, A])
    def orThrow: A =
      either match
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(error.message)
