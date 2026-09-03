package intaglio

/** Runs the renderer conformance contract against the shared device lowering, which acts as the
  * reference backend: every case must resolve to numeric device primitives deterministically with
  * all named markers intact.
  */
class SceneConformanceSuite extends munit.FunSuite:

  private def discs(elements: Vector[DeviceElement]): Vector[DevicePrimitive.Disc] =
    elements.flatMap {
      case DeviceElement.Mark(disc: DevicePrimitive.Disc) => Vector(disc)
      case DeviceElement.Mark(
            DevicePrimitive.PointBatch(points, radii, shapes, params, name)
          ) =>
        points.indices.flatMap { index =>
          if shapes.valueAt(index) == PointShape.Circle then
            val point = points(index)
            Vector[DevicePrimitive.Disc](
              DevicePrimitive.Disc(
                point.x,
                point.y,
                radii.valueAt(index),
                params.valueAt(index),
                name
              )
            )
          else Vector.empty
        }.toVector
      case DeviceElement.Mark(_)                  => Vector.empty
      case DeviceElement.Group(_, _, _, children) => discs(children)
      case DeviceElement.Annotated(_, children)   => discs(children)
    }

  private object DeviceHarness extends RendererHarness[DeviceScene]:
    override def render(scene: Scene): Either[String, DeviceScene] =
      DeviceScene.fromScene(scene, RendererConformance.targetDevice).left.map(_.message)

    override def containsMarker(out: DeviceScene, name: GraphicsName): Boolean =
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
        case DeviceElement.Annotated(_, children) =>
          children.exists(containsName(_, name))

    private def primitiveName(primitive: DevicePrimitive): Option[GraphicsName] =
      primitive match
        case DevicePrimitive.Disc(_, _, _, _, name)                   => name
        case DevicePrimitive.PointBatch(_, _, _, _, name)             => name
        case DevicePrimitive.Polyline(_, _, _, name)                  => name
        case DevicePrimitive.CompoundPolygon(_, _, name)              => name
        case DevicePrimitive.RectShape(_, _, _, _, _, _, name)        => name
        case DevicePrimitive.TextRun(_, _, _, _, _, _, _, _, _, name) => name
        case DevicePrimitive.Image(_, _, _, _, _, _, _, name)         => name

    private def satisfiesElement(element: DeviceElement, requirement: RenderRequirement): Boolean =
      element match
        case DeviceElement.Mark(primitive) =>
          satisfiesPrimitive(primitive, requirement)
        case DeviceElement.Group(name, clip, rotation, children) =>
          val groupMatches = requirement match
            case RenderRequirement.Group(expected, clipped, rotated) =>
              name.contains(expected) && clip.nonEmpty == clipped && rotation.nonEmpty == rotated
            case _ => false
          groupMatches || children.exists(satisfiesElement(_, requirement))
        case DeviceElement.Annotated(_, children) =>
          children.exists(satisfiesElement(_, requirement))

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
            gp.lineType == lineType && gp.lineCap == lineCap && gp.lineJoin == lineJoin && gp.alpha == alpha
          }
        case RenderRequirement.PatternFill(name, paint, alpha) =>
          primitiveName(primitive).contains(name) &&
          primitiveParams(primitive).exists(gp =>
            gp.fillPattern.contains(paint) && gp.alpha == alpha
          )
        case RenderRequirement.Text(name, horizontal, vertical, rotated) =>
          primitive match
            case DevicePrimitive.TextRun(_, _, _, h, v, rotation, _, _, _, primitiveName) =>
              primitiveName.contains(
                name
              ) && h == horizontal && v == vertical && (rotation != 0.0) == rotated
            case _ => false
        case RenderRequirement.TextStyle(name, color, fontSizePx, fontFamily, alpha) =>
          primitive match
            case DevicePrimitive.TextRun(
                  _,
                  _,
                  _,
                  _,
                  _,
                  _,
                  actualFontSize,
                  actualFontFamily,
                  gp,
                  primitiveName
                ) =>
              primitiveName
                .contains(name) && gp.fill.orElse(gp.stroke).getOrElse(Rgba.Black) == color &&
              actualFontSize == fontSizePx && actualFontFamily == fontFamily && gp.alpha == alpha
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
        case DevicePrimitive.RectShape(_, _, _, _, _, _, _) =>
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
        case DevicePrimitive.RectShape(_, _, _, _, _, gp, _)        => Some(gp)
        case DevicePrimitive.TextRun(_, _, _, _, _, _, _, _, gp, _) => Some(gp)
        case DevicePrimitive.Image(_, _, _, _, _, _, _, _)          => None

    private def firstNonFinite(elements: Vector[DeviceElement]): Option[String] =
      elements.iterator.map(nonFinite).collectFirst { case Some(problem) => problem }

    private def nonFinite(element: DeviceElement): Option[String] =
      element match
        case DeviceElement.Mark(primitive) =>
          val values = primitive match
            case DevicePrimitive.Disc(cx, cy, r, _, _)              => Vector(cx, cy, r)
            case DevicePrimitive.PointBatch(points, radii, _, _, _) =>
              points.indices
                .flatMap(index => Vector(points(index).x, points(index).y, radii.valueAt(index)))
                .toVector
            case DevicePrimitive.Polyline(points, _, _, _) => points.flatMap(p => Vector(p.x, p.y))
            case DevicePrimitive.CompoundPolygon(rings, _, _) =>
              rings.flatten.flatMap(p => Vector(p.x, p.y))
            case DevicePrimitive.RectShape(x, y, w, h, r, _, _)           => Vector(x, y, w, h, r)
            case DevicePrimitive.TextRun(_, x, y, _, _, rot, fs, _, _, _) => Vector(x, y, rot, fs)
            case DevicePrimitive.Image(_, x, y, w, h, _, alpha, _) => Vector(x, y, w, h, alpha)
          if values.forall(_.isFinite) then None
          else Some(s"non-finite device coordinate in $primitive")
        case DeviceElement.Group(_, _, _, children) =>
          firstNonFinite(children)
        case DeviceElement.Annotated(_, children) =>
          firstNonFinite(children)

    private def pointShapeKind(shape: PointShape): RenderPrimitiveKind =
      shape match
        case PointShape.Circle   => RenderPrimitiveKind.Disc
        case PointShape.Square   => RenderPrimitiveKind.Rectangle
        case PointShape.Triangle => RenderPrimitiveKind.Polygon
        case PointShape.Cross    => RenderPrimitiveKind.Polyline
        case PointShape.Diamond  => RenderPrimitiveKind.Polygon

  test("conformance cases cover all behavior groups") {
    val cases = RendererConformance.cases.fold(e => fail(e.message), identity)
    val groups = cases.map(_.group).toSet
    assertEquals(
      groups,
      Set[ConformanceGroup](
        ConformanceGroup.Primitive,
        ConformanceGroup.PatternFill,
        ConformanceGroup.Layout,
        ConformanceGroup.Guide,
        ConformanceGroup.CompiledPlot
      )
    )
    assert(cases.forall(!_.scene.isEmpty))
    assert(cases.forall(_.markers.nonEmpty))
    assert(cases.exists(_.requirements.nonEmpty))
    assertEquals(cases.map(_.name.value).distinct.length, cases.length)
  }

  test("group selection filters cases by behavior family") {
    val primitives =
      RendererConformance.group(ConformanceGroup.Primitive).fold(e => fail(e.message), identity)
    assert(primitives.nonEmpty)
    assert(primitives.forall(_.group == ConformanceGroup.Primitive))
  }

  test("the canonical contract pins marker shapes and every target-bound style channel") {
    val shapes = RendererConformance.shapeCase.fold(e => fail(e.message), identity)
    val shapeKinds = shapes.requirements.collect { case RenderRequirement.Primitive(name, kind) =>
      name.value -> kind
    }
    assertEquals(
      shapeKinds,
      Vector(
        "conformance-square" -> RenderPrimitiveKind.Rectangle,
        "conformance-triangle" -> RenderPrimitiveKind.Polygon,
        "conformance-cross" -> RenderPrimitiveKind.Polyline,
        "conformance-diamond" -> RenderPrimitiveKind.Polygon
      )
    )

    val line = RendererConformance.lineCase.fold(e => fail(e.message), identity)
    val sourceWidth = line.scene.grobs.collectFirst { case value: Grob.Lines =>
      value.gp.strokeWidth
    }
    val resolvedWidth = line.requirements.collectFirst {
      case RenderRequirement.Style(_, _, _, width, _, _, _, _) => width
    }
    assertEquals(sourceWidth, Some(StrokeWidth.pointsUnsafe(1.5)))
    assertEquals(resolvedWidth, Some(2.0))

    val text = RendererConformance.textCase.fold(e => fail(e.message), identity)
    assert(
      text.requirements.contains(
        RenderRequirement.TextStyle(
          GraphicsName.unsafe("conformance-text"),
          Rgba.unsafe(20, 40, 80, 0.8),
          fontSizePx = 12.0,
          fontFamily = Some("Conformance Sans"),
          alpha = 0.65
        )
      )
    )
    assertEquals(
      RendererConformance.targetContext().deviceContext,
      RendererConformance.targetDevice
    )
  }

  test("the device lowering passes the full conformance contract") {
    val violations = RendererConformance.check(DeviceHarness).fold(e => fail(e.message), identity)
    assertEquals(violations, Vector.empty)
  }

  test("the explicit scaled-plot fixture frames every point disc inside its clip") {
    val scene = RendererConformance.scaledPlotCase.fold(e => fail(e.message), identity).scene
    val device = DeviceScene
      .fromScene(scene, DeviceContext.unsafe(640.0, 480.0))
      .fold(e => fail(e.message), identity)
    val (clip, discs) = device.elements
      .collectFirst {
        case DeviceElement.Group(name, Some(clip), _, children)
            if name.exists(_.value == "plot-panel") =>
          val discs = children.collect { case DeviceElement.Mark(disc: DevicePrimitive.Disc) =>
            disc
          }
          (clip, discs)
      }
      .getOrElse(fail("missing scaled-plot panel"))

    assertEquals(discs.length, 3)
    discs.foreach { disc =>
      assert(disc.centerX - disc.radius >= clip.x)
      assert(disc.centerX + disc.radius <= clip.x + clip.width)
      assert(disc.centerY - disc.radius >= clip.y)
      assert(disc.centerY + disc.radius <= clip.y + clip.height)
    }
  }

  test("the jitter comparison uses explicit filled circular marks") {
    val scene = RendererConformance.jitteredPositionCase.fold(e => fail(e.message), identity).scene
    val device = DeviceScene
      .fromScene(scene, DeviceContext.unsafe(640.0, 480.0))
      .fold(e => fail(e.message), identity)
    val marks = discs(device.elements)

    assertEquals(marks.length, 6)
    marks.foreach { mark =>
      assert(mark.gp.fill.nonEmpty)
      assertEquals(mark.gp.fill, mark.gp.stroke)
    }
  }

  test("the checker reports missing markers and failures as violations") {
    object BlindHarness extends RendererHarness[String]:
      override def render(scene: Scene): Either[String, String] = Right("")
      override def containsMarker(out: String, name: GraphicsName): Boolean = false
    val violations = RendererConformance.check(BlindHarness).fold(e => fail(e.message), identity)
    assert(violations.nonEmpty)
    assert(violations.exists(_.problem.startsWith("missing marker")))
    assert(violations.exists(_.problem.startsWith("missing semantic requirement")))
  }
