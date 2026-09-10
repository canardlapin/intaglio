package intaglio.interaction

import intaglio.*
import PickGeometry.*

enum PickingError extends IntaglioError:
  case InvalidInput(field: String)
  case UnknownRoute(name: String)
  case InvalidRoute(name: String)
  case Unsupported(component: String)
  case TextMeasurementFailed
  def message: String = this match
    case InvalidInput(field)    => s"Invalid picking $field"
    case UnknownRoute(name)     => s"Unknown interaction route: $name"
    case InvalidRoute(name)     => s"Inconsistent interaction route: $name"
    case Unsupported(component) => s"Picking capability is not available: $component"
    case TextMeasurementFailed  => "Text picking bounds could not be measured"

enum DashPicking:
  /** Treat a dashed stroke as a continuous interaction corridor, preserving caps and joins. */
  case Continuous

  /** Respect painted dash intervals. Currently supported for linear outlines. */
  case Painted

enum AreaRule:
  case CenterInside, FullyContained, Intersecting

final class PickPolicy private (
    val includeTransparent: Boolean,
    val dashes: DashPicking,
    val miterLimit: Double
)
object PickPolicy:
  val default: PickPolicy = new PickPolicy(false, DashPicking.Continuous, 4.0)
  def apply(
      includeTransparent: Boolean = false,
      dashes: DashPicking = DashPicking.Continuous,
      miterLimit: Double = 4.0
  ): Either[PickingError, PickPolicy] =
    if !miterLimit.isFinite || miterLimit < 1 then Left(PickingError.InvalidInput("miter limit"))
    else Right(new PickPolicy(includeTransparent, dashes, miterLimit))

/** Query regions use the same device-pixel coordinate system as the picking plan. */
final class PickArea private (private[interaction] val region: Region)
object PickArea:
  def rectangle(
      left: Double,
      top: Double,
      right: Double,
      bottom: Double
  ): Either[PickingError, PickArea] =
    if !Vector(left, top, right, bottom).forall(_.isFinite) || left >= right || top >= bottom then
      Left(PickingError.InvalidInput("rectangle"))
    else Right(new PickArea(Region.rectangle(Box(left, top, right, bottom))))

  /** Even-odd lasso fill, including its boundary. Repeated closing points are accepted. */
  def lasso(points: Vector[DevicePoint]): Either[PickingError, PickArea] =
    val values = points.map(p => P(p.x, p.y))
    val distinct = values.distinct
    if distinct.length < 3 || values.exists(p => !p.x.isFinite || !p.y.isFinite) then
      Left(PickingError.InvalidInput("lasso"))
    else
      val a = distinct.head
      val b = distinct(1)
      if !distinct.drop(2).exists(p => math.abs((b - a).cross(p - a)) > epsilon) then
        Left(PickingError.InvalidInput("collinear lasso"))
      else Right(new PickArea(Region.polygon(Vector(values))))

final case class PickHit[A](target: TargetInfo[A], distanceDevicePx: Double, drawOrder: Int)

private[interaction] final case class PickPart(source: Region, clips: Vector[Region]):
  val visible: Clipped = new Clipped(source +: clips)

private[interaction] final case class PickTarget[A](
    info: TargetInfo[A],
    parts: Vector[PickPart],
    drawOrder: Int
):
  lazy val bounds: Option[Box] = parts.flatMap(_.source.bounds).reduceOption(_.union(_))
  def distance(point: P): Double =
    parts.iterator.map(_.visible.distance(point)).minOption.getOrElse(Double.PositiveInfinity)

/** Shared spatial queries backed by a uniform grid over target bounds built at compile time; the
  * exact geometric predicates run only on the grid's conservative candidate set, so results are
  * identical to an exhaustive scan. Results collapse multiple primitives to one logical target.
  * Nearest distance wins; equal distances prefer the later-drawn target.
  */
final class PickingPlan[A] private[interaction] (private val targets: Vector[PickTarget[A]]):
  private val index: PickIndex = PickIndex.build(targets.map(_.bounds))

  def targetCount: Int = targets.size

  def hits(
      point: DevicePoint,
      toleranceDevicePx: Double = 0
  ): Either[PickingError, Vector[PickHit[A]]] =
    if !point.x.isFinite || !point.y.isFinite || !toleranceDevicePx.isFinite || toleranceDevicePx < 0
    then Left(PickingError.InvalidInput("query point or tolerance"))
    else
      val p = P(point.x, point.y)
      val reach = toleranceDevicePx + PickIndex.safety
      val candidates = index.candidates(p.x - reach, p.y - reach, p.x + reach, p.y + reach)
      val out = Vector.newBuilder[PickHit[A]]
      var i = 0
      while i < candidates.length do
        val target = targets(candidates(i))
        val distance = target.distance(p)
        if distance <= toleranceDevicePx + epsilon then
          out += PickHit(target.info, distance, target.drawOrder)
        i += 1
      Right(out.result().sortBy(hit => (hit.distanceDevicePx, -hit.drawOrder)))

  /** The pre-index scan, retained as the semantic oracle for the grid path in tests. */
  private[interaction] def hitsExhaustive(
      point: DevicePoint,
      toleranceDevicePx: Double = 0
  ): Either[PickingError, Vector[PickHit[A]]] =
    if !point.x.isFinite || !point.y.isFinite || !toleranceDevicePx.isFinite || toleranceDevicePx < 0
    then Left(PickingError.InvalidInput("query point or tolerance"))
    else
      val p = P(point.x, point.y)
      Right(
        targets
          .flatMap { target =>
            val distance = target.distance(p)
            if distance <= toleranceDevicePx + epsilon then
              Some(PickHit(target.info, distance, target.drawOrder))
            else None
          }
          .sortBy(hit => (hit.distanceDevicePx, -hit.drawOrder))
      )

  def nearest(
      point: DevicePoint,
      maximumDistanceDevicePx: Double
  ): Either[PickingError, Option[PickHit[A]]] =
    hits(point, maximumDistanceDevicePx).map(_.headOption)

  /** Center is the center of the target's uncut rendered bounds. Clips must expose that center.
    * Full containment and intersection operate on all visible parts, including stroke outlines.
    */
  def select(area: PickArea, rule: AreaRule): Vector[TargetInfo[A]] =
    val candidates = area.region.bounds match
      case Some(box) =>
        index.candidates(
          box.left - PickIndex.safety,
          box.top - PickIndex.safety,
          box.right + PickIndex.safety,
          box.bottom + PickIndex.safety
        )
      case None => Array.range(0, targets.length)
    candidates.toVector
      .map(targets(_))
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
      .map(_.info)

  /** The pre-index area scan, retained as the semantic oracle for the grid path in tests. */
  private[interaction] def selectExhaustive(area: PickArea, rule: AreaRule): Vector[TargetInfo[A]] =
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
      .map(_.info)

object Picking:
  def compile[A](
      plan: InteractionPlan[A],
      context: RenderContext,
      policy: PickPolicy = PickPolicy.default
  ): Either[IntaglioError, PickingPlan[A]] =
    DeviceScene
      .fromScene(plan.scene, context)
      .flatMap(fromDeviceScene(_, plan.groups, context, policy))

  def composition[A](
      plot: ComposedInteraction[A],
      policy: PickPolicy = PickPolicy.default
  ): Either[IntaglioError, PickingPlan[A]] =
    val context = plot.composition.context
    DeviceScene
      .fromScene(plot.scene, context)
      .flatMap(fromDeviceScene(_, plot.groups, context, policy))

  private[interaction] def fromDeviceScene[A](
      scene: DeviceScene,
      groups: Vector[TargetGroup[A]],
      context: RenderContext,
      policy: PickPolicy
  ): Either[PickingError, PickingPlan[A]] =
    if scene.width <= 0 || scene.height <= 0 || !scene.width.isFinite || !scene.height.isFinite then
      Left(PickingError.InvalidInput("scene dimensions"))
    else if groups.map(_.name).distinct.size != groups.size then
      Left(PickingError.InvalidInput("duplicate route names"))
    else
      val routes = groups.map(group => group.name.value -> group).toMap
      var seen = Set.empty[String]
      val targets = scala.collection.mutable.LinkedHashMap.empty[VisualTargetId, PickTarget[A]]
      var order = 0
      var failure: Option[PickingError] = None

      def add(
          group: TargetGroup[A],
          index: Int,
          primitive: DevicePrimitive,
          transform: Rigid,
          clips: Vector[Region]
      ): Unit =
        if failure.isEmpty then
          group.at(index) match
            case Left(_)     => failure = Some(PickingError.InvalidRoute(group.name.value))
            case Right(info) =>
              primitiveRegions(primitive, context, policy) match
                case Left(error)    => failure = Some(error)
                case Right(regions) =>
                  val parts = regions.map(region => PickPart(region.transform(transform), clips))
                  if parts.nonEmpty then
                    val previous = targets.get(info.id).fold(Vector.empty[PickPart])(_.parts)
                    targets.update(info.id, PickTarget(info, previous ++ parts, order))
                  order += 1

      def walk(
          elements: Vector[DeviceElement],
          current: Option[TargetGroup[A]],
          transform: Rigid,
          clips: Vector[Region]
      ): Unit =
        elements.foreach {
          case _ if failure.nonEmpty                            => ()
          case DeviceElement.Group(_, clip, rotation, children) =>
            val next = rotation.fold(transform)(r =>
              transform.compose(Rigid.rotation(r.degrees, P(r.pivotX, r.pivotY)))
            )
            val allClips = clips ++ clip.map(c =>
              Region.rectangle(Box(c.x, c.y, c.x + c.width, c.y + c.height)).transform(next)
            )
            walk(children, current, next, allClips)
          case DeviceElement.Annotated(meta, children) =>
            meta.data.collect {
              case (key, value) if key == InteractionCompiler.targetAttribute => value
            } match
              case Vector()     => walk(children, current, transform, clips)
              case Vector(name) =>
                routes.get(name) match
                  case None        => failure = Some(PickingError.UnknownRoute(name))
                  case Some(group) =>
                    seen += name
                    walk(children, Some(group), transform, clips)
              case _ => failure = Some(PickingError.InvalidInput("duplicate routing attributes"))
          case DeviceElement.Mark(batch: DevicePrimitive.PointBatch) =>
            current match
              case None        => order += batch.points.size
              case Some(group) =>
                if group.size != 1 && group.size != batch.points.size then
                  failure = Some(PickingError.InvalidRoute(group.name.value))
                else if Vector(
                    batch.radii.valueCount,
                    batch.shapes.valueCount,
                    batch.graphicParams.valueCount
                  ).flatten.exists(_ != batch.points.size)
                then failure = Some(PickingError.InvalidInput("point batch columns"))
                else
                  batch.points.indices.foreach { index =>
                    val p = batch.points(index)
                    val r = batch.radii.valueAt(index)
                    val gp = batch.graphicParams.valueAt(index)
                    if !r.isFinite || r < 0 then
                      failure = Some(PickingError.InvalidInput("point batch radius"))
                    else
                      pointPrimitives(P(p.x, p.y), r, batch.shapes.valueAt(index), gp).foreach {
                        primitive =>
                          add(
                            group,
                            if group.size == 1 then 0 else index,
                            primitive,
                            transform,
                            clips
                          )
                      }
                  }
          case DeviceElement.Mark(primitive) =>
            current match
              case None        => order += 1
              case Some(group) =>
                if group.size != 1 then failure = Some(PickingError.InvalidRoute(group.name.value))
                else add(group, 0, primitive, transform, clips)
        }

      walk(
        scene.elements,
        None,
        Rigid(),
        Vector(Region.rectangle(Box(0, 0, scene.width, scene.height)))
      )
      failure match
        case Some(error)                   => Left(error)
        case None if seen != routes.keySet =>
          Left(PickingError.InvalidInput("scene and routing table disagree"))
        case None => Right(new PickingPlan(targets.values.toVector))

  private def pointPrimitives(
      at: P,
      radius: Double,
      shape: PointShape,
      gp: GraphicParams
  ): Vector[DevicePrimitive] =
    def line(points: Vector[P], closed: Boolean) =
      DevicePrimitive.Polyline(points.map(p => DevicePoint(p.x, p.y)), closed, gp, None)
    shape match
      case PointShape.Circle => Vector(DevicePrimitive.Disc(at.x, at.y, radius, gp, None))
      case PointShape.Square =>
        Vector(
          DevicePrimitive.RectShape(
            at.x - radius,
            at.y - radius,
            radius * 2,
            radius * 2,
            0,
            gp,
            None
          )
        )
      case PointShape.Triangle =>
        Vector(
          line(
            Vector(
              P(at.x, at.y - radius),
              P(at.x + radius, at.y + radius),
              P(at.x - radius, at.y + radius)
            ),
            true
          )
        )
      case PointShape.Cross =>
        Vector(
          line(Vector(P(at.x - radius, at.y), P(at.x + radius, at.y)), false),
          line(Vector(P(at.x, at.y - radius), P(at.x, at.y + radius)), false)
        )
      case PointShape.Diamond =>
        val half = PointShape.diamondHalfDiagonal(radius)
        Vector(
          line(
            Vector(
              P(at.x, at.y - half),
              P(at.x + half, at.y),
              P(at.x, at.y + half),
              P(at.x - half, at.y)
            ),
            true
          )
        )

  private def primitiveRegions(
      primitive: DevicePrimitive,
      context: RenderContext,
      policy: PickPolicy
  ): Either[PickingError, Vector[Region]] =
    def visible(alpha: Double) = policy.includeTransparent || alpha > 0
    def filled(gp: GraphicParams) = visible(gp.alpha) &&
      (gp.fill.exists(color => visible(color.alpha)) || gp.fillPattern.exists(p =>
        visible(p.ink.alpha) || p.background.exists(c => visible(c.alpha))
      ))
    def stroked(gp: GraphicParams) =
      gp.lineWidth > 0 && visible(gp.alpha) && gp.stroke.exists(color => visible(color.alpha))
    def numbers(values: Double*) = values.forall(_.isFinite)
    def linear(
        points: Vector[P],
        closed: Boolean,
        gp: GraphicParams
    ): Either[PickingError, Vector[Region]] =
      if points.exists(p => !numbers(p.x, p.y)) then
        Left(PickingError.InvalidInput("path coordinates"))
      else
        val length = (if closed && points.nonEmpty then points :+ points.head else points)
          .sliding(2)
          .filter(_.size == 2)
          .map(p => p(0).distance(p(1)))
          .sum
        if stroked(
            gp
          ) && policy.dashes == DashPicking.Painted && gp.lineType != LineType.Solid && length > 100000
        then
          Left(
            PickingError.Unsupported(
              "painted dash outline exceeds 100000 device pixels; use continuous picking"
            )
          )
        else
          Right(
            (if closed && filled(gp) && points.size >= 3 then
               Vector(Region.polygon(Vector(points), evenOdd = false))
             else Vector.empty) ++
              (if stroked(gp) then
                 PickStroke(
                   points,
                   closed,
                   gp,
                   policy.dashes == DashPicking.Painted,
                   policy.miterLimit
                 )
               else Vector.empty)
          )

    primitive match
      case DevicePrimitive.Disc(x, y, radius, gp, _) =>
        if !numbers(x, y, radius) || radius < 0 then Left(PickingError.InvalidInput("disc"))
        else if radius == 0 then Right(Vector.empty)
        else if stroked(gp) && policy.dashes == DashPicking.Painted && gp.lineType != LineType.Solid
        then Left(PickingError.Unsupported("painted dashes on circular outlines"))
        else
          Right(
            (if filled(gp) then Vector(Region.disc(P(x, y), radius)) else Vector.empty) ++
              (if stroked(gp) then
                 Vector(
                   Region.annulus(
                     P(x, y),
                     math.max(0, radius - gp.lineWidth / 2),
                     radius + gp.lineWidth / 2
                   )
                 )
               else Vector.empty)
          )
      case DevicePrimitive.Polyline(points, closed, gp, _) =>
        linear(points.map(p => P(p.x, p.y)), closed, gp)
      case DevicePrimitive.CompoundPolygon(rings, gp, _) =>
        val points = rings.map(_.map(p => P(p.x, p.y)))
        val perimeter = points
          .map(ring =>
            if ring.isEmpty then 0.0
            else
              (ring :+ ring.head).sliding(2).filter(_.size == 2).map(p => p(0).distance(p(1))).sum
          )
          .sum
        if points.flatten.exists(p => !numbers(p.x, p.y)) then
          Left(PickingError.InvalidInput("polygon coordinates"))
        else if stroked(
            gp
          ) && policy.dashes == DashPicking.Painted && gp.lineType != LineType.Solid && perimeter > 100000
        then
          Left(
            PickingError.Unsupported(
              "painted dash outline exceeds 100000 device pixels; use continuous picking"
            )
          )
        else
          val fill =
            if filled(gp) then Vector(Region.polygon(points, evenOdd = false)) else Vector.empty
          val stroke = if stroked(gp) then
            points.flatMap(ring =>
              PickStroke(ring, true, gp, policy.dashes == DashPicking.Painted, policy.miterLimit)
            )
          else Vector.empty
          Right(fill ++ stroke)
      case DevicePrimitive.RectShape(x, y, width, height, radius, gp, _) =>
        if !numbers(x, y, width, height, radius) || width < 0 || height < 0 || radius < 0 then
          Left(PickingError.InvalidInput("rectangle"))
        else if width == 0 || height == 0 then Right(Vector.empty)
        else if radius == 0 then
          linear(
            Vector(P(x, y), P(x + width, y), P(x + width, y + height), P(x, y + height)),
            true,
            gp
          )
        else if stroked(gp) && policy.dashes == DashPicking.Painted && gp.lineType != LineType.Solid
        then Left(PickingError.Unsupported("painted dashes on rounded outlines"))
        else
          val box = Box(x, y, x + width, y + height)
          val fill =
            if filled(gp) then Vector(Region.roundedRectangle(box, radius)) else Vector.empty
          val h = gp.lineWidth / 2
          val stroke = if !stroked(gp) then Vector.empty
          else
            val outer =
              Region.roundedRectangle(Box(x - h, y - h, x + width + h, y + height + h), radius + h)
            val inner = if width <= 2 * h || height <= 2 * h then Region.empty
            else
              Region.roundedRectangle(
                Box(x + h, y + h, x + width - h, y + height - h),
                math.max(0, radius - h)
              )
            Vector(Region.difference(outer, inner))
          Right(fill ++ stroke)
      case DevicePrimitive.Image(_, x, y, width, height, _, alpha, _) =>
        if !numbers(x, y, width, height, alpha) || width < 0 || height < 0 then
          Left(PickingError.InvalidInput("image"))
        else
          Right(if visible(alpha) && width > 0 && height > 0 then
            Vector(Region.rectangle(Box(x, y, x + width, y + height)))
          else Vector.empty)
      case DevicePrimitive.TextRun(
            label,
            x,
            y,
            horizontal,
            vertical,
            rotation,
            fontSize,
            family,
            gp,
            _
          ) =>
        if !numbers(x, y, rotation, fontSize) || fontSize <= 0 then
          Left(PickingError.InvalidInput("text"))
        else if label.isEmpty || !visible(gp.alpha) || !visible(
            gp.fill.orElse(gp.stroke).getOrElse(Rgba.Black).alpha
          )
        then Right(Vector.empty)
        else
          try
            val pixelsPerPoint = context.pixelsPerInch / 72.0
            // The weight has to travel with the family and size: bold is wider, so a hit box
            // measured at the regular advance would be narrower than the glyphs a reader is
            // clicking on.
            val style = TextStyle(family, fontSize / pixelsPerPoint, gp.fontWeight)
            val width = context.textMetrics.widthPt(label, style) * pixelsPerPoint
            val height = context.textMetrics.heightPt(style) * pixelsPerPoint
            if !numbers(width, height) || width < 0 || height <= 0 then
              Left(PickingError.TextMeasurementFailed)
            else
              val left = horizontal match
                case HJust.Left   => x
                case HJust.Center => x - width / 2
                case HJust.Right  => x - width
              val top = vertical match
                case VJust.Top    => y
                case VJust.Center => y - height / 2
                case VJust.Bottom => y - height
              Right(
                Vector(
                  Region
                    .rectangle(Box(left, top, left + width, top + height))
                    .transform(Rigid.rotation(rotation, P(x, y)))
                )
              )
          catch case scala.util.control.NonFatal(_) => Left(PickingError.TextMeasurementFailed)
      case _: DevicePrimitive.PointBatch =>
        Left(PickingError.InvalidInput("unexpanded point batch"))
