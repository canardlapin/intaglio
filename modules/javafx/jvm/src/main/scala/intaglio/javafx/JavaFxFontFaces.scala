package intaglio.javafx

import java.util.Locale
import javafx.scene.text.{Font, FontWeight}
import scala.jdk.CollectionConverters.*

/** JavaFX can register static Medium/Semibold TTFs as separate families. Select their full face
  * names before falling back to family lookup. Only exact family-plus-weight names match; italic,
  * condensed and other family variants are never substituted by this resolver.
  */
private[javafx] object JavaFxFontFaces:
  private var knownCount = -1
  private var names = Map.empty[String, String]

  private def key(value: String): String =
    value.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "")

  private def suffixes(weight: Int): Vector[String] = weight match
    case 100 => Vector("thin", "hairline")
    case 200 => Vector("extralight", "ultralight", "xlight")
    case 300 => Vector("light")
    case 400 => Vector("regular", "normal", "roman", "")
    case 500 => Vector("medium", "medm")
    case 600 => Vector("semibold", "demibold", "smbld", "demi")
    case 700 => Vector("bold")
    case 800 => Vector("extrabold", "ultrabold", "xbold")
    case 900 => Vector("black", "heavy")
    case _   => Vector.empty

  def resolve(family: String, weight: FontWeight, size: Double): Option[Font] = synchronized {
    val available = Font.getFontNames
    // Some JavaFX versions cache this inventory before later Font.loadFont registrations.
    // Keep it as a spelling index, but also probe conventional full names directly below.
    if available.size != knownCount then
      names = available.asScala.toVector.sorted.reverse.map(name => key(name) -> name).toMap
      knownCount = available.size
    suffixes(weight.getWeight).iterator
      .flatMap { suffix =>
        names.get(key(family) + suffix).iterator ++
          Iterator(s"$family $suffix".trim, s"$family-$suffix", family + suffix)
      }
      .map(name => name -> new Font(name, size))
      .collectFirst { case (name, font) if key(font.getName) == key(name) => font }
  }
