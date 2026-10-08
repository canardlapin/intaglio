package intaglio.interaction

import intaglio.*
import PickGeometry.*

/** One named target under a query point: the name, the distance from the point to its nearest
  * visible painted part, and its draw order (larger is drawn later).
  */
final case class NamedHit(name: GraphicsName, distanceDevicePx: Double, drawOrder: Int)

/** One cell of a named image grob, as [[NamedPickingPlan.cellAt]] reports it.
  *
  * `row` and `column` index the `RasterImage` the grob draws: row 0 is the image's first row, which
  * `RasterImage` defines as its visual top row, so it is drawn along the top edge of the image's
  * device box (before any rotation of an enclosing viewport, which turns the whole box); column 0
  * is drawn along its left edge. This is the image's own row order, and it differs from
  * [[RasterCell]]: a plot compiler's raster reports its source field's y-up row index, but a
  * hand-built image has no field, so the image's rows are the only order the scene knows. A caller
  * who filled the image from a y-up matrix reads that matrix's row as `image.height - 1 - row`.
  */
final case class ImageCell(row: Int, column: Int)

/** An [[ImageCell]] of the named target that paints it. */
final case class NamedCell(name: GraphicsName, cell: ImageCell)

/** `orders(i)` is the draw order of `parts(i)`; `drawOrder` is the target's last-drawn part. */
private[interaction] final case class NamedTarget(
    name: GraphicsName,
    parts: Vector[PickPart],
    orders: Vector[Int]
):
  def drawOrder: Int = orders.last
  lazy val bounds: Option[Box] = parts.flatMap(_.source.bounds).reduceOption(_.union(_))

  /** The distance to the nearest visible part, and the draw order of the topmost part at that
    * distance: under a pointer, what matters is the part actually drawn there, not the target's
    * last part elsewhere.
    */
  def reach(point: P): (Double, Int) =
    var best = Double.PositiveInfinity
    var order = drawOrder
    var i = 0
    while i < parts.length do
      val d = parts(i).visible.distance(point)
      if d < best - epsilon then
        best = d
        order = orders(i)
      else if math.abs(d - best) <= epsilon then order = math.max(order, orders(i))
      i += 1
    (best, order)

/** Picking over a scene that was drawn directly from grobs, where a target is identified by its
  * `GraphicsName` rather than by a plot's typed routing table.
  *
  * A painted part belongs to the innermost name that encloses it: the primitive's own name, else
  * the nearest named group around it. That is the name an SVG host's `closest("[data-name]")`
  * returns for the same part, provided the page adds no `data-name` above the `<svg>`. Parts that
  * share a name form one logical target; unnamed parts are not targets but still occupy draw order.
  * A hit reports the draw order of the target's topmost part at the query point, so interleaved
  * targets tie-break by what is actually drawn there. Geometry, paint visibility, clipping,
  * rotation and dash handling are those of [[PickingPlan]]; like it, the default policy ignores
  * fully transparent paint, which a browser's `visiblePainted` hit test would still hit.
  *
  * A mark of a point batch identified by [[intaglio.BatchMarks]] has its mark name as its innermost
  * name, so it is a target of its own; in SVG that name is the mark's configured `data-*` attribute
  * rather than `data-name`, which keeps the batch's name.
  *
  * The marks of a point batch are point glyphs, so `PickPolicy.hollowPoints` applies to them. A
  * point drawn from an individual `Grob.points` lowers to the same circle, square or closed path as
  * any other grob of that shape, so a named scene cannot tell it apart and it follows
  * `PickPolicy.hollow`.
  */
final class NamedPickingPlan private[interaction] (
    private[interaction] val targets: Vector[NamedTarget],
    titles: Map[GraphicsName, String] = Map.empty
):
  private val index: PickIndex = PickIndex.build(targets.map(_.bounds))

  private lazy val byName: Map[GraphicsName, NamedTarget] = targets.map(t => t.name -> t).toMap

  def targetCount: Int = targets.size

  /** The accessible text a [[BatchMarks]] title gives the mark named `name`, if any. */
  def accessibleText(name: GraphicsName): Option[String] = titles.get(name)

  /** Every named target, in draw order. */
  def names: Vector[GraphicsName] = targets.sortBy(_.drawOrder).map(_.name)

  /** Every target within `toleranceDevicePx`, nearest first; equal distances prefer the later-drawn
    * target.
    */
  def hits(
      point: DevicePoint,
      toleranceDevicePx: Double = 0
  ): Either[PickingError, Vector[NamedHit]] =
    query(point, toleranceDevicePx) { reach =>
      index.candidates(point.x - reach, point.y - reach, point.x + reach, point.y + reach).toVector
    }

  /** The pre-index scan, retained as the semantic oracle for the grid path in tests. */
  private[interaction] def hitsExhaustive(
      point: DevicePoint,
      toleranceDevicePx: Double = 0
  ): Either[PickingError, Vector[NamedHit]] =
    query(point, toleranceDevicePx)(_ => targets.indices.toVector)

  def nearest(
      point: DevicePoint,
      maximumDistanceDevicePx: Double
  ): Either[PickingError, Option[NamedHit]] =
    hits(point, maximumDistanceDevicePx).map(_.headOption)

  /** The cell of `name`'s image grob drawn under `point`, or `None` when no image part of `name`
    * covers the point.
    *
    * The point is mapped into the image's own frame through the rotation it was drawn with, and the
    * cell is `floor((x - left) * columns / width)` across and `floor((y - top) * rows / height)`
    * down, so each cell is the half-open interval from its left (top) edge to the next cell's: a
    * point exactly on an interior boundary belongs to the cell to its right (below). The image's
    * right and bottom edges belong to its last column and row. Cells are reported only where the
    * image is painted: inside its box (inclusive within the 1e-8 device-pixel tolerance of every
    * picking boundary) and inside every clip around it: where one of `name`'s image parts makes
    * `hits(point)` report `name` at distance zero. A hit within a tolerance but outside the image,
    * or one on another grob of the same name, has no cell; and a later-drawn grob of the same name
    * over the image does not hide its cell. Every pixel counts, whatever its alpha, as it does for
    * `hits`; nearest-neighbour and smooth interpolation share the cell grid. Where one name paints
    * several images over the point, the last drawn wins. Grobs other than images never report a
    * cell.
    *
    * The arithmetic is plain IEEE double arithmetic, identical on the JVM and Scala.js. Without
    * rotation the mapping is exact; under rotation it goes through `toRadians`, `cos` and `sin`,
    * whose last bit can differ between platforms, so only a point within rounding of a cell
    * boundary or of an image or clip edge may differ.
    */
  def cellAt(name: GraphicsName, point: DevicePoint): Either[PickingError, Option[ImageCell]] =
    if !point.x.isFinite || !point.y.isFinite then Left(PickingError.InvalidInput("query point"))
    else
      val p = P(point.x, point.y)
      Right(byName.get(name).flatMap { target =>
        var found = Option.empty[ImageCell]
        var order = Int.MinValue
        var i = 0
        while i < target.parts.length do
          val part = target.parts(i)
          part.mark match
            case Some(MarkSource(image: DevicePrimitive.Image, transform))
                if target.orders(i) >= order && part.visible.contains(p) =>
              found = Some(cellOf(image, transform.inverse(p)))
              order = target.orders(i)
            case _ => ()
          i += 1
        found
      })

  /** The cell under `local`, a point in the image's own frame inside its box (within epsilon). */
  private def cellOf(image: DevicePrimitive.Image, local: P): ImageCell =
    def index(offset: Double, extent: Double, count: Int): Int =
      val at = math.floor(offset * count / extent)
      if at < 0 then 0 else if at >= count then count - 1 else at.toInt
    ImageCell(
      index(local.y - image.y, image.height, image.image.height),
      index(local.x - image.x, image.width, image.image.width)
    )

  /** Rings that follow `name`'s marks `offsetDevicePx` outside their ink, as
    * [[PickingPlan.outline]]; `None` when no painted part carries the name.
    */
  def outline(
      name: GraphicsName,
      offsetDevicePx: Double
  ): Either[PickingError, Option[TargetOutline]] =
    if !offsetDevicePx.isFinite || offsetDevicePx < 0 then
      Left(PickingError.InvalidInput("outline offset"))
    else Right(targets.find(_.name == name).map(t => TargetOutline.of(t.parts, offsetDevicePx)))

  /** Area selection with the same rules as [[PickingPlan.select]], in draw order. */
  def select(area: PickArea, rule: AreaRule): Vector[GraphicsName] =
    targets
      .filter { target =>
        val visible = target.parts.filter(_.visible.nonEmpty)
        rule match
          case AreaRule.CenterInside =>
            target.bounds.exists { bounds =>
              area.region.contains(bounds.center) && visible.exists(
                _.clips.forall(_.contains(bounds.center))
              )
            }
          case AreaRule.FullyContained =>
            visible.nonEmpty && visible.forall(_.visible.containedBy(area.region))
          case AreaRule.Intersecting => visible.exists(_.visible.intersects(area.region))
      }
      .sortBy(_.drawOrder)
      .map(_.name)

  private def query(point: DevicePoint, tolerance: Double)(
      candidates: Double => Vector[Int]
  ): Either[PickingError, Vector[NamedHit]] =
    if !point.x.isFinite || !point.y.isFinite || !tolerance.isFinite || tolerance < 0 then
      Left(PickingError.InvalidInput("query point or tolerance"))
    else
      val p = P(point.x, point.y)
      Right(
        candidates(tolerance + PickIndex.safety)
          .map(targets(_))
          .flatMap { target =>
            val (distance, order) = target.reach(p)
            if distance <= tolerance + epsilon then Some(NamedHit(target.name, distance, order))
            else None
          }
          .sortBy(hit => (hit.distanceDevicePx, -hit.drawOrder))
      )

object NamedPicking:

  /** Compile a picking plan for `scene` under the same render context used to draw it. */
  def compile(
      scene: Scene,
      context: RenderContext,
      policy: PickPolicy = PickPolicy.default
  ): Either[IntaglioError, NamedPickingPlan] =
    DeviceScene.fromScene(scene, context).flatMap(fromDeviceScene(_, context, policy))

  /** Build named picking from a scene the host has already resolved with
    * `DeviceScene.fromScene(scene, context)`, so drawing, picking and overlays share one lowering.
    * The plan is identical to `compile(scene, context, policy)` for the source scene. `context`
    * must be the one the scene was resolved under: text is measured with its metrics.
    */
  def fromResolved(
      scene: DeviceScene,
      context: RenderContext,
      policy: PickPolicy = PickPolicy.default
  ): Either[PickingError, NamedPickingPlan] =
    fromDeviceScene(scene, context, policy)

  /** `keep` limits the targets built to the names a caller can use (plot parts ignore data marks);
    * a skipped name's marks still take their draw orders, so kept targets order as before, but
    * their geometry is never measured.
    */
  private[interaction] def fromDeviceScene(
      scene: DeviceScene,
      context: RenderContext,
      policy: PickPolicy,
      keep: GraphicsName => Boolean = _ => true
  ): Either[PickingError, NamedPickingPlan] =
    if scene.width <= 0 || scene.height <= 0 || !scene.width.isFinite || !scene.height.isFinite then
      Left(PickingError.InvalidInput("scene dimensions"))
    else
      val targets = scala.collection.mutable.LinkedHashMap.empty[GraphicsName, NamedTarget]
      val titles = scala.collection.mutable.HashMap.empty[GraphicsName, String]
      var order = 0
      var failure: Option[PickingError] = None

      def add(
          name: GraphicsName,
          primitive: DevicePrimitive,
          transform: Rigid,
          clips: Vector[Region],
          pointGlyph: Boolean = false
      ): Unit =
        if failure.isEmpty && !keep(name) then order += 1
        else if failure.isEmpty then
          Picking.primitiveRegions(primitive, context, policy, Some(name), pointGlyph) match
            case Left(error)    => failure = Some(error)
            case Right(regions) =>
              val mark = Some(MarkSource(primitive, transform))
              val parts =
                regions.map(region => PickPart(region.transform(transform), clips, mark))
              if parts.nonEmpty then
                val (previous, orders) =
                  targets
                    .get(name)
                    .fold((Vector.empty[PickPart], Vector.empty[Int]))(t => (t.parts, t.orders))
                targets.update(
                  name,
                  NamedTarget(name, previous ++ parts, orders ++ Vector.fill(parts.size)(order))
                )
              order += 1

      def walk(
          elements: Vector[DeviceElement],
          current: Option[GraphicsName],
          transform: Rigid,
          clips: Vector[Region],
          marks: Option[BatchMarks.Cursor]
      ): Unit =
        elements.foreach {
          case _ if failure.nonEmpty                               => ()
          case DeviceElement.Group(name, clip, rotation, children) =>
            val next = rotation.fold(transform)(r =>
              transform.compose(Rigid.rotation(r.degrees, P(r.pivotX, r.pivotY)))
            )
            val allClips = clips ++ clip.map(c =>
              Region.rectangle(Box(c.x, c.y, c.x + c.width, c.y + c.height)).transform(next)
            )
            walk(children, name.orElse(current), next, allClips, marks)
          case DeviceElement.Annotated(meta, children) =>
            walk(
              children,
              current,
              transform,
              clips,
              meta.marks.map(new BatchMarks.Cursor(_)).orElse(marks)
            )
          case DeviceElement.Mark(batch: DevicePrimitive.PointBatch) =>
            val enclosing = batch.name.orElse(current)
            if enclosing.isEmpty && marks.isEmpty then order += batch.points.size
            else if Vector(
                batch.radii.valueCount,
                batch.shapes.valueCount,
                batch.graphicParams.valueCount
              ).flatten.exists(_ != batch.points.size)
            then failure = Some(PickingError.InvalidInput("point batch columns"))
            else
              batch.points.indices.foreach { index =>
                // A mark's own name, when the batch is identified, is its innermost name.
                val identity = marks.flatMap(_.at(index))
                if marks.nonEmpty && identity.isEmpty then
                  failure = Some(PickingError.InvalidInput("batch mark names"))
                identity.foreach { case (name, title) => title.foreach(titles.update(name, _)) }
                val p = batch.points(index)
                val r = batch.radii.valueAt(index)
                if failure.nonEmpty then ()
                else if !r.isFinite || r < 0 then
                  failure = Some(PickingError.InvalidInput("point batch radius"))
                else
                  identity.map(_._1).orElse(enclosing) match
                    case None       => order += 1
                    case Some(name) =>
                      Picking
                        .pointPrimitives(
                          P(p.x, p.y),
                          r,
                          batch.shapes.valueAt(index),
                          batch.graphicParams.valueAt(index)
                        )
                        .foreach(primitive =>
                          add(name, primitive, transform, clips, pointGlyph = true)
                        )
              }
              marks.foreach(_.advance(batch.points.size))
          case DeviceElement.Mark(primitive) =>
            Picking.nameOf(primitive).orElse(current) match
              case None       => order += 1
              case Some(name) => add(name, primitive, transform, clips)
        }

      walk(
        scene.elements,
        None,
        Rigid(),
        Vector(Region.rectangle(Box(0, 0, scene.width, scene.height))),
        None
      )
      failure.toLeft(new NamedPickingPlan(targets.values.toVector, titles.toMap))
