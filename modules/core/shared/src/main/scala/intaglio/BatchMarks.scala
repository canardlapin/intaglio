package intaglio

/** Per-mark identity for point batches: one [[GraphicsName]] per mark and, optionally, one short
  * accessible text per mark.
  *
  * Attach it with `Grob.annotated(batch, GrobMeta.marks(value))`. The names run, in draw order,
  * over the marks of every point batch beneath that annotation, except batches under a nearer
  * annotation that carries its own marks; lowering refuses a scene whose counts disagree. Because
  * the names travel with the scene rather than in a host's side table, a host that filters, splits
  * or clips its batches keeps each mark's identity as long as it slices the names with the points
  * (see [[slice]]).
  *
  * Identity never changes geometry or paint. Named picking treats each mark as a target named by
  * its mark name — the mark's innermost name, inside the batch's own name. The SVG backend writes
  * each mark's accessible text as a `<title>` and, when [[dataAttribute]] is set, its name as that
  * `data-*` attribute; other backends draw the batch unchanged. A scene without marks carries no
  * per-mark identity and pays nothing for the feature.
  */
final class BatchMarks private (
    val names: Vector[GraphicsName],
    val titles: Option[Vector[String]],
    val dataAttribute: Option[DataKey]
):
  def size: Int = names.length

  /** The accessible text of the mark at `index`, if titles are present. */
  def title(index: Int): Option[String] = titles.flatMap(_.lift(index))

  /** The same names with one accessible text per mark. */
  def withTitles(values: Vector[String]): Either[GraphicsError, BatchMarks] =
    if values.length != names.length then
      Left(GraphicsError.BatchColumnLengthMismatch("mark titles", names.length, values.length))
    else Right(new BatchMarks(names, Some(values), dataAttribute))

  /** The same marks, written by the SVG backend as `data-<key>` on each mark. */
  def withDataAttribute(key: DataKey): BatchMarks = new BatchMarks(names, titles, Some(key))

  /** The marks `[from, until)`, for the matching slice of a split or filtered batch. */
  def slice(from: Int, until: Int): Either[GraphicsError, BatchMarks] =
    if from < 0 || until > names.length || from >= until then
      Left(GraphicsError.InvalidGeometrySize("batch marks slice", 1, until - from))
    else
      Right(
        new BatchMarks(names.slice(from, until), titles.map(_.slice(from, until)), dataAttribute)
      )

  override def equals(other: Any): Boolean = other match
    case that: BatchMarks =>
      names == that.names && titles == that.titles && dataAttribute == that.dataAttribute
    case _ => false

  override def hashCode(): Int = (names, titles, dataAttribute).hashCode

  override def toString: String =
    s"BatchMarks(${names.length} names, titles=${titles.isDefined}, dataAttribute=${dataAttribute
        .map(_.value)})"

/** One identified mark of a resolved scene: its name, its device position and its accessible text.
  */
final case class MarkedPoint(name: GraphicsName, at: DevicePoint, title: Option[String])

object BatchMarks:
  def apply(names: Vector[GraphicsName]): Either[GraphicsError, BatchMarks] =
    if names.isEmpty then Left(GraphicsError.EmptyGeometry("batch marks"))
    else Right(new BatchMarks(names, None, None))

  def unsafe(names: Vector[GraphicsName]): BatchMarks = apply(names).orThrow

  /** Every identified mark of `scene` in draw order, with the device position it is drawn at before
    * group rotation. Marks of batches without names are omitted.
    */
  def marksOf(scene: DeviceScene): Vector[MarkedPoint] =
    val out = Vector.newBuilder[MarkedPoint]
    def walk(elements: Vector[DeviceElement], cursor: Option[Cursor]): Unit =
      elements.foreach {
        case DeviceElement.Mark(batch: DevicePrimitive.PointBatch) =>
          cursor.foreach { c =>
            batch.points.indices.foreach { i =>
              c.at(i).foreach { case (name, title) =>
                out += MarkedPoint(name, batch.points(i), title)
              }
            }
            c.advance(batch.points.length)
          }
        case DeviceElement.Mark(_)                   => ()
        case DeviceElement.Group(_, _, _, children)  => walk(children, cursor)
        case DeviceElement.Annotated(meta, children) =>
          walk(children, meta.marks.map(new Cursor(_)).orElse(cursor))
      }
    walk(scene.elements, None)
    out.result()

  /** How many point-batch marks lie beneath `children` and belong to an enclosing annotation's
    * names: batches under a nearer marked annotation are counted by that annotation instead.
    */
  private[intaglio] def markCount(children: Vector[DeviceElement]): Long =
    children.foldLeft(0L) { (total, element) =>
      total + (element match
        case DeviceElement.Mark(batch: DevicePrimitive.PointBatch) => batch.points.length.toLong
        case DeviceElement.Mark(_)                                 => 0L
        case DeviceElement.Group(_, _, _, nested)                  => markCount(nested)
        case DeviceElement.Annotated(meta, nested)                 =>
          if meta.marks.isDefined then 0L else markCount(nested))
    }

  /** The position of the next unnamed mark within one annotation's names, as a walk proceeds. */
  private[intaglio] final class Cursor(val marks: BatchMarks):
    private var offset = 0
    def at(index: Int): Option[(GraphicsName, Option[String])] =
      val i = offset + index
      if i >= 0 && i < marks.size then Some(marks.names(i) -> marks.title(i)) else None
    def advance(count: Int): Unit = offset += count
