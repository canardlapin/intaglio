package intaglio

opaque type GraphicsName = String

object GraphicsName:
  def apply(value: String, kind: String = "graphics"): Either[GraphicsError, GraphicsName] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(GraphicsError.BlankName(kind))
    else Right(trimmed)

  def unsafe(value: String, kind: String = "graphics"): GraphicsName =
    apply(value, kind).orThrow

extension (name: GraphicsName)
  def value: String =
    name

/** An open, typed aesthetic key. Keys use reference identity: two independently created keys may
  * share a display label without becoming interchangeable. This lets heterogeneous mapping storage
  * recover `A` only after proving that the requested key is the exact key used at insertion.
  */
final class Aesthetic[A] private (val name: GraphicsName):
  def label: String =
    name.value

  override def toString: String =
    s"Aesthetic($label)"

object Aesthetic:
  /** Define an ecosystem aesthetic without editing intaglio-core. Retain the returned key and use
    * that same value for every typed lookup.
    */
  def apply[A](label: String): Either[GraphicsError, Aesthetic[A]] =
    GraphicsName(label, "aesthetic").map(new Aesthetic(_))

  def unsafe[A](label: String): Aesthetic[A] =
    apply[A](label).orThrow

  val X: Aesthetic[Double] = unsafe("x")
  val Y: Aesthetic[Double] = unsafe("y")
  val XEnd: Aesthetic[Double] = unsafe("xend")
  val YEnd: Aesthetic[Double] = unsafe("yend")
  val XMin: Aesthetic[Double] = unsafe("xmin")
  val XMax: Aesthetic[Double] = unsafe("xmax")
  val YMin: Aesthetic[Double] = unsafe("ymin")
  val YMax: Aesthetic[Double] = unsafe("ymax")
  val Color: Aesthetic[Rgba] = unsafe("color")
  val Fill: Aesthetic[Rgba] = unsafe("fill")
  val Alpha: Aesthetic[Double] = unsafe("alpha")
  val Size: Aesthetic[Double] = unsafe("size")
  val Shape: Aesthetic[PointShape] = unsafe("shape")
  val LineType: Aesthetic[intaglio.LineType] = unsafe("linetype")
  val LineWidth: Aesthetic[Double] = unsafe("linewidth")
  val Angle: Aesthetic[Double] = unsafe("angle")
  val HJust: Aesthetic[intaglio.HJust] = unsafe("hjust")
  val VJust: Aesthetic[intaglio.VJust] = unsafe("vjust")
  val Label: Aesthetic[String] = unsafe("label")
  val Group: Aesthetic[String] = unsafe("group")
  val Subpath: Aesthetic[String] = unsafe("subpath")

  /** Stable declaration order for the core keys. Custom keys follow these in their insertion order
    * inside an [[AestheticMap]].
    */
  val builtIns: Vector[Aesthetic[?]] =
    Vector(
      X,
      Y,
      XEnd,
      YEnd,
      XMin,
      XMax,
      YMin,
      YMax,
      Color,
      Fill,
      Alpha,
      Size,
      Shape,
      LineType,
      LineWidth,
      Angle,
      HJust,
      VJust,
      Label,
      Group,
      Subpath
    )

  /** Source-compatible view of the former enum cases. Open keys are discovered from mappings, not
    * from this finite core list.
    */
  def values: Array[Aesthetic[?]] =
    builtIns.toArray

  private[intaglio] def builtInIndex(aesthetic: Aesthetic[?]): Option[Int] =
    builtIns.indexWhere(_ eq aesthetic) match
      case -1    => None
      case index => Some(index)

enum PointShape:
  case Circle
  case Square
  case Triangle
  case Cross

enum LineType:
  case Solid
  case Dashed
  case Dotted

enum LineCap:
  case Butt
  case Round
  case Square

enum LineJoin:
  case Miter
  case Round
  case Bevel

enum Clip:
  case On
  case Off
