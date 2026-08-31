package intaglio.java2d

import intaglio.*

class Java2DConformanceSuite extends munit.FunSuite:

  private object Java2DHarness extends RendererHarness[Java2DProgram]:
    private val options = Java2DOptions.unsafe(width = 240, height = 160)

    override def render(scene: Scene): Either[String, Java2DProgram] =
      Java2DRenderer.compile(scene, options).left.map(_.message)

    override def containsMarker(out: Java2DProgram, name: GraphicsName): Boolean =
      out.commands.exists(commandName(_).contains(name))

    override def satisfies(out: Java2DProgram, requirement: RenderRequirement): Boolean =
      requirement match
        case RenderRequirement.Primitive(name, kind) =>
          out.commands.exists(command =>
            commandName(command).contains(name) && primitiveKind(command).contains(kind)
          )
        case RenderRequirement.Group(name, clipped, rotated) =>
          groupEffects(out.commands, name).contains((clipped, rotated))
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
          out.commands.exists { command =>
            commandName(command).contains(name) && commandPaint(command).exists { paint =>
              paint.stroke == stroke.map(Java2DColor.fromRgba) &&
              paint.fill == fill.map(Java2DColor.fromRgba) &&
              paint.lineWidth == lineWidth &&
              paint.dash == Java2DLineDash.fromLineType(lineType) &&
              paint.lineCap == lineCap &&
              paint.lineJoin == lineJoin &&
              paint.opacity == alpha
            }
          }
        case RenderRequirement.PatternFill(name, pattern, alpha) =>
          out.commands.exists { command =>
            commandName(command).contains(name) &&
            commandPaint(command).exists(paint =>
              paint.fillPattern.contains(pattern) && paint.opacity == alpha
            )
          }
        case RenderRequirement.Text(name, horizontal, vertical, rotated) =>
          out.commands.exists {
            case Java2DCommand.Text(_, _, _, h, v, rotation, _, _, _, commandName) =>
              commandName.contains(
                name
              ) && h == horizontal && v == vertical && (rotation != 0.0) == rotated
            case _ => false
          }
        case RenderRequirement.Image(name, dimensions, interpolation, alpha) =>
          out.commands.exists {
            case Java2DCommand.Image(
                  image,
                  _,
                  _,
                  _,
                  _,
                  actualInterpolation,
                  actualAlpha,
                  commandName
                ) =>
              commandName.contains(name) && image.dimensions == dimensions &&
              actualInterpolation == interpolation && actualAlpha == alpha
            case _ => false
          }

    override def validate(out: Java2DProgram): Option[String] =
      Java2DProgram.validate(out)

  test("the Java2D backend passes the renderer conformance contract") {
    val violations = RendererConformance.check(Java2DHarness).fold(e => fail(e.message), identity)
    assertEquals(violations, Vector.empty)
  }

  test("combined viewport effects rotate before installing the clip") {
    val scene =
      RendererConformance.clippedRotatedViewportCase.fold(e => fail(e.message), identity).scene
    val program = Java2DRenderer
      .compile(scene, Java2DOptions.unsafe(width = 240, height = 160))
      .fold(e => fail(e.message), identity)
    val name = GraphicsName.unsafe("conformance-clip-rotation")
    val start = program.commands.indexWhere {
      case Java2DCommand.Save(groupName) => groupName.contains(name)
      case _                             => false
    }

    assert(start >= 0)
    assert(program.commands(start + 1).isInstanceOf[Java2DCommand.Rotate])
    assert(program.commands(start + 2).isInstanceOf[Java2DCommand.ClipRect])
    assert(program.commands(start + 3).isInstanceOf[Java2DCommand.Polyline])
    assertEquals(program.commands(start + 4), Java2DCommand.Restore(Some(name)))
  }

  private def commandName(command: Java2DCommand): Option[GraphicsName] =
    command match
      case Java2DCommand.Save(name)                                           => name
      case Java2DCommand.Restore(name)                                        => name
      case Java2DCommand.Disc(_, _, _, _, name)                               => name
      case Java2DCommand.PointBatch(_, _, _, _, name)                         => name
      case Java2DCommand.Polyline(_, _, _, name)                              => name
      case Java2DCommand.CompoundPolygon(_, _, name)                          => name
      case Java2DCommand.Rectangle(_, _, _, _, _, name)                       => name
      case Java2DCommand.Text(_, _, _, _, _, _, _, _, _, name)                => name
      case Java2DCommand.Image(_, _, _, _, _, _, _, name)                     => name
      case Java2DCommand.Rotate(_, _, _) | Java2DCommand.ClipRect(_, _, _, _) => None

  private def primitiveKind(command: Java2DCommand): Option[RenderPrimitiveKind] =
    command match
      case Java2DCommand.Disc(_, _, _, _, _)            => Some(RenderPrimitiveKind.Disc)
      case Java2DCommand.PointBatch(_, _, shapes, _, _) =>
        Some(pointShapeKind(shapes.valueAt(0)))
      case Java2DCommand.Polyline(_, closed, _, _) =>
        Some(if closed then RenderPrimitiveKind.Polygon else RenderPrimitiveKind.Polyline)
      case Java2DCommand.CompoundPolygon(_, _, _)           => Some(RenderPrimitiveKind.Polygon)
      case Java2DCommand.Rectangle(_, _, _, _, _, _)        => Some(RenderPrimitiveKind.Rectangle)
      case Java2DCommand.Text(_, _, _, _, _, _, _, _, _, _) => Some(RenderPrimitiveKind.Text)
      case Java2DCommand.Image(_, _, _, _, _, _, _, _)      => Some(RenderPrimitiveKind.Image)
      case _                                                => None

  private def commandPaint(command: Java2DCommand): Option[Java2DPaint] =
    command match
      case Java2DCommand.Disc(_, _, _, paint, _)                => Some(paint)
      case Java2DCommand.PointBatch(_, _, _, paints, _)         => Some(paints.valueAt(0))
      case Java2DCommand.Polyline(_, _, paint, _)               => Some(paint)
      case Java2DCommand.CompoundPolygon(_, paint, _)           => Some(paint)
      case Java2DCommand.Rectangle(_, _, _, _, paint, _)        => Some(paint)
      case Java2DCommand.Text(_, _, _, _, _, _, _, _, paint, _) => Some(paint)
      case _                                                    => None

  private def pointShapeKind(shape: PointShape): RenderPrimitiveKind =
    shape match
      case PointShape.Circle   => RenderPrimitiveKind.Disc
      case PointShape.Square   => RenderPrimitiveKind.Rectangle
      case PointShape.Triangle => RenderPrimitiveKind.Polygon
      case PointShape.Cross    => RenderPrimitiveKind.Polyline

  private def groupEffects(
      commands: Vector[Java2DCommand],
      name: GraphicsName
  ): Option[(Boolean, Boolean)] =
    val start = commands.indexWhere {
      case Java2DCommand.Save(groupName) => groupName.contains(name)
      case _                             => false
    }
    if start < 0 then None
    else
      val effects = commands.drop(start + 1).takeWhile {
        case Java2DCommand.Rotate(_, _, _) | Java2DCommand.ClipRect(_, _, _, _) => true
        case _                                                                  => false
      }
      Some(
        (
          effects.exists(_.isInstanceOf[Java2DCommand.ClipRect]),
          effects.exists(_.isInstanceOf[Java2DCommand.Rotate])
        )
      )
