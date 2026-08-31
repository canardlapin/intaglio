package external.laws

import intaglio.*
import intaglio.laws.*

/** A consumer-package reference backend implemented only through public renderer contracts. */
object ExternalDeviceHarness extends RendererHarness[DeviceScene]:
  private val device = DeviceContext.unsafe(240.0, 160.0)

  def render(scene: Scene): Either[String, DeviceScene] =
    DeviceScene.fromScene(scene, device).left.map(_.message)

  def containsMarker(out: DeviceScene, name: GraphicsName): Boolean =
    out.elements.exists(containsName(_, name))

  override def satisfies(out: DeviceScene, requirement: RenderRequirement): Boolean =
    out.elements.exists(satisfiesElement(_, requirement))

  override def validate(out: DeviceScene): Option[String] =
    firstNonFinite(out.elements)

  private def containsName(element: DeviceElement, name: GraphicsName): Boolean =
    element match
      case DeviceElement.Mark(primitive) =>
        primitiveName(primitive).contains(name)
      case DeviceElement.Group(groupName, _, _, children) =>
        groupName.contains(name) || children.exists(containsName(_, name))

  private def primitiveName(primitive: DevicePrimitive): Option[GraphicsName] =
    primitive match
      case DevicePrimitive.Disc(_, _, _, _, name)                   => name
      case DevicePrimitive.PointBatch(_, _, _, _, name)             => name
      case DevicePrimitive.Polyline(_, _, _, name)                  => name
      case DevicePrimitive.CompoundPolygon(_, _, name)              => name
      case DevicePrimitive.RectShape(_, _, _, _, _, name)           => name
      case DevicePrimitive.TextRun(_, _, _, _, _, _, _, _, _, name) => name
      case DevicePrimitive.Image(_, _, _, _, _, _, _, name)         => name

  private def satisfiesElement(
      element: DeviceElement,
      requirement: RenderRequirement
  ): Boolean =
    element match
      case DeviceElement.Mark(primitive) =>
        satisfiesPrimitive(primitive, requirement)
      case DeviceElement.Group(name, clip, rotation, children) =>
        val groupMatches = requirement match
          case RenderRequirement.Group(expected, clipped, rotated) =>
            name.contains(expected) && clip.nonEmpty == clipped && rotation.nonEmpty == rotated
          case _ => false
        groupMatches || children.exists(satisfiesElement(_, requirement))

  private def satisfiesPrimitive(
      primitive: DevicePrimitive,
      requirement: RenderRequirement
  ): Boolean =
    requirement match
      case RenderRequirement.Primitive(name, kind) =>
        primitiveName(primitive).contains(name) && primitiveKind(primitive) == kind
      case RenderRequirement.Style(
            name,
            stroke,
            fill,
            lineWidth,
            lineType,
            lineCap,
            lineJoin,
            alpha
          ) =>
        primitiveName(primitive).contains(name) &&
        primitiveParams(primitive).exists { gp =>
          gp.stroke == stroke && gp.fill == fill && gp.lineWidth == lineWidth &&
          gp.lineType == lineType && gp.lineCap == lineCap && gp.lineJoin == lineJoin &&
          gp.alpha == alpha
        }
      case RenderRequirement.PatternFill(name, paint, alpha) =>
        primitiveName(primitive).contains(name) &&
        primitiveParams(primitive).exists(gp => gp.fillPattern.contains(paint) && gp.alpha == alpha)
      case RenderRequirement.Text(name, horizontal, vertical, rotated) =>
        primitive match
          case DevicePrimitive.TextRun(_, _, _, h, v, rotation, _, _, _, primitiveName) =>
            primitiveName.contains(name) && h == horizontal && v == vertical &&
            (rotation != 0.0) == rotated
          case _ => false
      case RenderRequirement.Image(name, dimensions, interpolation, alpha) =>
        primitive match
          case DevicePrimitive.Image(
                image,
                _,
                _,
                _,
                _,
                actualInterpolation,
                actualAlpha,
                primitiveName
              ) =>
            primitiveName.contains(name) && image.dimensions == dimensions &&
            actualInterpolation == interpolation && actualAlpha == alpha
          case _ => false
      case RenderRequirement.Group(_, _, _) => false

  private def primitiveKind(primitive: DevicePrimitive): RenderPrimitiveKind =
    primitive match
      case DevicePrimitive.Disc(_, _, _, _, _) =>
        RenderPrimitiveKind.Disc
      case DevicePrimitive.PointBatch(_, _, shapes, _, _) =>
        pointShapeKind(shapes.valueAt(0))
      case DevicePrimitive.Polyline(_, closed, _, _) =>
        if closed then RenderPrimitiveKind.Polygon else RenderPrimitiveKind.Polyline
      case DevicePrimitive.CompoundPolygon(_, _, _) =>
        RenderPrimitiveKind.Polygon
      case DevicePrimitive.RectShape(_, _, _, _, _, _) =>
        RenderPrimitiveKind.Rectangle
      case DevicePrimitive.TextRun(_, _, _, _, _, _, _, _, _, _) =>
        RenderPrimitiveKind.Text
      case DevicePrimitive.Image(_, _, _, _, _, _, _, _) =>
        RenderPrimitiveKind.Image

  private def primitiveParams(primitive: DevicePrimitive): Option[GraphicParams] =
    primitive match
      case DevicePrimitive.Disc(_, _, _, gp, _)                   => Some(gp)
      case DevicePrimitive.PointBatch(_, _, _, params, _)         => Some(params.valueAt(0))
      case DevicePrimitive.Polyline(_, _, gp, _)                  => Some(gp)
      case DevicePrimitive.CompoundPolygon(_, gp, _)              => Some(gp)
      case DevicePrimitive.RectShape(_, _, _, _, gp, _)           => Some(gp)
      case DevicePrimitive.TextRun(_, _, _, _, _, _, _, _, gp, _) => Some(gp)
      case DevicePrimitive.Image(_, _, _, _, _, _, _, _)          => None

  private def firstNonFinite(elements: Vector[DeviceElement]): Option[String] =
    elements.iterator.map(nonFinite).collectFirst { case Some(problem) => problem }

  private def nonFinite(element: DeviceElement): Option[String] =
    element match
      case DeviceElement.Mark(primitive) =>
        val values = primitive match
          case DevicePrimitive.Disc(cx, cy, radius, _, _)         => Vector(cx, cy, radius)
          case DevicePrimitive.PointBatch(points, radii, _, _, _) =>
            points.indices
              .flatMap(index => Vector(points(index).x, points(index).y, radii.valueAt(index)))
              .toVector
          case DevicePrimitive.Polyline(points, _, _, _) =>
            points.flatMap(point => Vector(point.x, point.y))
          case DevicePrimitive.CompoundPolygon(rings, _, _) =>
            rings.flatten.flatMap(point => Vector(point.x, point.y))
          case DevicePrimitive.RectShape(x, y, width, height, _, _) =>
            Vector(x, y, width, height)
          case DevicePrimitive.TextRun(_, x, y, _, _, rotation, fontSize, _, _, _) =>
            Vector(x, y, rotation, fontSize)
          case DevicePrimitive.Image(_, x, y, width, height, _, alpha, _) =>
            Vector(x, y, width, height, alpha)
        Option.when(values.exists(value => !value.isFinite))(
          s"non-finite device coordinate in $primitive"
        )
      case DeviceElement.Group(_, _, _, children) =>
        firstNonFinite(children)

  private def pointShapeKind(shape: PointShape): RenderPrimitiveKind =
    shape match
      case PointShape.Circle   => RenderPrimitiveKind.Disc
      case PointShape.Square   => RenderPrimitiveKind.Rectangle
      case PointShape.Triangle => RenderPrimitiveKind.Polygon
      case PointShape.Cross    => RenderPrimitiveKind.Polyline

class ExternalBackendLawsSuite extends munit.FunSuite:
  test("an external backend passes the complete published renderer laws") {
    val suite = BackendLaws(ExternalDeviceHarness)
    assertEquals(suite.failures, Vector.empty, clues(suite.name))
  }
