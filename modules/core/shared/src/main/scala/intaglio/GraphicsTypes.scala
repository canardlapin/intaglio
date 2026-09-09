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

/** Mark shapes for point grobs. Every shape is centred on its point and sized by one resolved
  * device radius `r` (the point size): `Circle` is the disc of radius `r`; `Square` and `Triangle`
  * span `[-r, r]` on both axes; `Cross` is two strokes of length `2r`; `Diamond` is a square
  * rotated 45 degrees whose area equals the circle's, so a size-by-value encoding reads the same
  * across those two shapes.
  */
enum PointShape:
  case Circle
  case Square
  case Triangle
  case Cross
  case Diamond

object PointShape:
  /** Half-diagonal of a `Diamond` per unit of point radius: `sqrt(pi / 2)`. A diamond with
    * half-diagonal `d` covers `2 d^2`, so `d = r sqrt(pi / 2)` gives it the circle's area `pi r^2`.
    * Every backend derives the diamond's vertices from this one value.
    */
  val DiamondHalfDiagonalRatio: Double =
    math.sqrt(math.Pi / 2.0)

  /** Device half-diagonal of a `Diamond` mark drawn at point radius `radius`. */
  def diamondHalfDiagonal(radius: Double): Double =
    radius * DiamondHalfDiagonalRatio

/** How a [[Grob.Lines]] joins its given points.
  *
  * `Linear` connects them directly. The step forms insert one corner between each neighbouring
  * pair, so a track that holds a value and then jumps is one grob with one name rather than one
  * grob per horizontal run: `StepAfter` holds each y until the next x, `StepBefore` jumps to the
  * next y at the current x. Lowering expands them into the same device polyline the explicit corner
  * points would have produced.
  */
enum LineInterpolation:
  case Linear
  case StepAfter
  case StepBefore

  /** The interpolation that draws the same track once the axes are exchanged. Transposing turns a
    * hold-then-jump into a jump-then-hold, because the corner a step-after inserts at
    * `(x(i+1), y(i))` becomes the corner a step-before inserts at `(y(i), x(i+1))`. A coordinate
    * implementation that transposes a scene must apply this to every `Lines` grob it flips.
    */
  def transposed: LineInterpolation =
    this match
      case Linear     => Linear
      case StepAfter  => StepBefore
      case StepBefore => StepAfter

/** A stroke dash rhythm: alternating on and off lengths, in device pixels.
  *
  * Lengths are device pixels rather than points, which is what the named rhythms of [[LineType]]
  * have always meant. A stroke measured in points therefore scales with the device while its dash
  * does not; that is a pre-existing property of `Dashed` and `Dotted`, not something this type
  * introduces, and it is recorded in `docs/limits.md`.
  *
  * The constructor refuses a pattern no backend could draw: empty, longer than
  * [[DashPattern.MaximumSegments]], non-finite, negative, or all zero. The last is not pedantry —
  * `java.awt.BasicStroke` throws on an all-zero dash array, so an unchecked value would render on
  * three backends and fail on the fourth.
  */
final case class DashPattern private (segments: Vector[Double])

object DashPattern:
  /** Enough rhythms to distinguish any categorical encoding a reader could follow, and few enough
    * that a malformed value cannot inflate a document.
    */
  val MaximumSegments: Int = 32

  def apply(segments: Vector[Double]): Either[GraphicsError, DashPattern] =
    if segments.isEmpty then Left(GraphicsError.InvalidDashPattern("at least one segment", "empty"))
    else if segments.lengthCompare(MaximumSegments) > 0 then
      Left(
        GraphicsError.InvalidDashPattern(
          s"at most $MaximumSegments segments",
          segments.length.toString
        )
      )
    else
      // The offending segment is reported by index rather than by value: `Double.toString`
      // disagrees between the JVM and Scala.js for whole numbers, so interpolating one here would
      // make the message platform-dependent. The index locates it exactly and is stable.
      segments.zipWithIndex.collectFirst {
        case (value, index) if !value.isFinite =>
          GraphicsError.InvalidDashPattern("finite segments", s"segment $index is not finite")
        case (value, index) if value < 0.0 =>
          GraphicsError.InvalidDashPattern("non-negative segments", s"segment $index is negative")
      } match
        case Some(error) => Left(error)
        case None        =>
          if segments.forall(_ == 0.0) then
            Left(
              GraphicsError.InvalidDashPattern("one segment above zero", "every segment is zero")
            )
          else Right(new DashPattern(segments))

  def unsafe(segments: Double*): DashPattern =
    apply(segments.toVector).orThrow

  /** The rhythm `LineType.Dashed` draws. Named here rather than in a `LineType` companion because
    * declaring one moves the enum's synthetic `values`/`valueOf` out of the class, which is a
    * binary break for already-compiled callers.
    */
  val Dashed: DashPattern = unsafe(6.0, 4.0)

  /** The rhythm `LineType.Dotted` draws. */
  val Dotted: DashPattern = unsafe(1.0, 3.0)

enum LineType:
  case Solid
  case Dashed
  case Dotted

  /** An explicit rhythm, for an encoding that needs more than the two named ones. */
  case Custom(pattern: DashPattern)

  /** The rhythm this line type draws, or `None` for a solid stroke.
    *
    * Every backend resolves a dash through here, so the two named rhythms have one definition
    * rather than one per renderer. Before this existed each of the five backends carried its own
    * copy of `6 4` and `1 3`.
    */
  def dash: Option[DashPattern] =
    this match
      case Solid           => None
      case Dashed          => Some(DashPattern.Dashed)
      case Dotted          => Some(DashPattern.Dotted)
      case Custom(pattern) => Some(pattern)

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
