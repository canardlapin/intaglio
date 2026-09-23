package intaglio.interaction

import intaglio.*
import PickGeometry.*

/** One named target under a query point: the name, the distance from the point to its nearest
  * visible painted part, and its draw order (larger is drawn later).
  */
final case class NamedHit(name: GraphicsName, distanceDevicePx: Double, drawOrder: Int)

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
  */
final class NamedPickingPlan private[interaction] (private val targets: Vector[NamedTarget]):
  private val index: PickIndex = PickIndex.build(targets.map(_.bounds))

  def targetCount: Int = targets.size

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

  private[interaction] def fromDeviceScene(
      scene: DeviceScene,
      context: RenderContext,
      policy: PickPolicy
  ): Either[PickingError, NamedPickingPlan] =
    if scene.width <= 0 || scene.height <= 0 || !scene.width.isFinite || !scene.height.isFinite then
      Left(PickingError.InvalidInput("scene dimensions"))
    else
      val targets = scala.collection.mutable.LinkedHashMap.empty[GraphicsName, NamedTarget]
      var order = 0
      var failure: Option[PickingError] = None

      def add(
          name: GraphicsName,
          primitive: DevicePrimitive,
          transform: Rigid,
          clips: Vector[Region]
      ): Unit =
        if failure.isEmpty then
          Picking.primitiveRegions(primitive, context, policy) match
            case Left(error)    => failure = Some(error)
            case Right(regions) =>
              val parts = regions.map(region => PickPart(region.transform(transform), clips))
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
          clips: Vector[Region]
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
            walk(children, name.orElse(current), next, allClips)
          case DeviceElement.Annotated(_, children) => walk(children, current, transform, clips)
          case DeviceElement.Mark(batch: DevicePrimitive.PointBatch) =>
            batch.name.orElse(current) match
              case None       => order += batch.points.size
              case Some(name) =>
                if Vector(
                    batch.radii.valueCount,
                    batch.shapes.valueCount,
                    batch.graphicParams.valueCount
                  ).flatten.exists(_ != batch.points.size)
                then failure = Some(PickingError.InvalidInput("point batch columns"))
                else
                  batch.points.indices.foreach { index =>
                    val p = batch.points(index)
                    val r = batch.radii.valueAt(index)
                    if failure.nonEmpty then ()
                    else if !r.isFinite || r < 0 then
                      failure = Some(PickingError.InvalidInput("point batch radius"))
                    else
                      Picking
                        .pointPrimitives(
                          P(p.x, p.y),
                          r,
                          batch.shapes.valueAt(index),
                          batch.graphicParams.valueAt(index)
                        )
                        .foreach(primitive => add(name, primitive, transform, clips))
                  }
          case DeviceElement.Mark(primitive) =>
            nameOf(primitive).orElse(current) match
              case None       => order += 1
              case Some(name) => add(name, primitive, transform, clips)
        }

      walk(
        scene.elements,
        None,
        Rigid(),
        Vector(Region.rectangle(Box(0, 0, scene.width, scene.height)))
      )
      failure.toLeft(new NamedPickingPlan(targets.values.toVector))

  private def nameOf(primitive: DevicePrimitive): Option[GraphicsName] = primitive match
    case p: DevicePrimitive.Disc            => p.name
    case p: DevicePrimitive.PointBatch      => p.name
    case p: DevicePrimitive.Polyline        => p.name
    case p: DevicePrimitive.CompoundPolygon => p.name
    case p: DevicePrimitive.RectShape       => p.name
    case p: DevicePrimitive.TextRun         => p.name
    case p: DevicePrimitive.Image           => p.name
