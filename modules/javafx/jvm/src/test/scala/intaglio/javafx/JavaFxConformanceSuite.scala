package intaglio.javafx

import intaglio.*

class JavaFxConformanceSuite extends munit.FunSuite:

  private object JavaFxHarness extends RendererHarness[JavaFxProgram]:
    private val options = JavaFxOptions.unsafe(width = 240, height = 160)

    override def render(scene: Scene): Either[String, JavaFxProgram] =
      JavaFxRenderer.compile(scene, options).left.map(_.message)

    override def containsMarker(out: JavaFxProgram, name: GraphicsName): Boolean =
      out.commands.exists(commandName(_).contains(name))

    override def satisfies(out: JavaFxProgram, requirement: RenderRequirement): Boolean =
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
              paint.stroke == stroke.map(JavaFxColor.fromRgba) &&
              paint.fill == fill.map(JavaFxColor.fromRgba) &&
              paint.lineWidth == lineWidth &&
              paint.dash == JavaFxLineDash.fromLineType(lineType) &&
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
            case JavaFxCommand.Text(_, _, _, h, v, rotation, _, _, _, commandName) =>
              commandName.contains(
                name
              ) && h == horizontal && v == vertical && (rotation != 0.0) == rotated
            case _ => false
          }
        case RenderRequirement.Image(name, dimensions, interpolation, alpha) =>
          out.commands.exists {
            case JavaFxCommand.Image(
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

    override def validate(out: JavaFxProgram): Option[String] =
      JavaFxProgram.validate(out)

  test("the JavaFX backend passes the renderer conformance contract") {
    val violations = RendererConformance.check(JavaFxHarness).fold(e => fail(e.message), identity)
    assertEquals(violations, Vector.empty)
  }

  test("combined viewport effects rotate before installing the clip") {
    val scene =
      RendererConformance.clippedRotatedViewportCase.fold(e => fail(e.message), identity).scene
    val program = JavaFxRenderer
      .compile(scene, JavaFxOptions.unsafe(width = 240, height = 160))
      .fold(e => fail(e.message), identity)
    val name = GraphicsName.unsafe("conformance-clip-rotation")
    val start = program.commands.indexWhere {
      case JavaFxCommand.Save(groupName) => groupName.contains(name)
      case _                             => false
    }

    assert(start >= 0)
    assert(program.commands(start + 1).isInstanceOf[JavaFxCommand.Rotate])
    assert(program.commands(start + 2).isInstanceOf[JavaFxCommand.ClipRect])
    assert(program.commands(start + 3).isInstanceOf[JavaFxCommand.Polyline])
    assertEquals(program.commands(start + 4), JavaFxCommand.Restore(Some(name)))
  }

  private def commandName(command: JavaFxCommand): Option[GraphicsName] =
    command match
      case JavaFxCommand.Save(name)                                           => name
      case JavaFxCommand.Restore(name)                                        => name
      case JavaFxCommand.Disc(_, _, _, _, name)                               => name
      case JavaFxCommand.PointBatch(_, _, _, _, name)                         => name
      case JavaFxCommand.Polyline(_, _, _, name)                              => name
      case JavaFxCommand.CompoundPolygon(_, _, name)                          => name
      case JavaFxCommand.Rectangle(_, _, _, _, _, name)                       => name
      case JavaFxCommand.Text(_, _, _, _, _, _, _, _, _, name)                => name
      case JavaFxCommand.Image(_, _, _, _, _, _, _, name)                     => name
      case JavaFxCommand.Rotate(_, _, _) | JavaFxCommand.ClipRect(_, _, _, _) => None

  private def primitiveKind(command: JavaFxCommand): Option[RenderPrimitiveKind] =
    command match
      case JavaFxCommand.Disc(_, _, _, _, _)            => Some(RenderPrimitiveKind.Disc)
      case JavaFxCommand.PointBatch(_, _, shapes, _, _) =>
        Some(pointShapeKind(shapes.valueAt(0)))
      case JavaFxCommand.Polyline(_, closed, _, _) =>
        Some(if closed then RenderPrimitiveKind.Polygon else RenderPrimitiveKind.Polyline)
      case JavaFxCommand.CompoundPolygon(_, _, _)           => Some(RenderPrimitiveKind.Polygon)
      case JavaFxCommand.Rectangle(_, _, _, _, _, _)        => Some(RenderPrimitiveKind.Rectangle)
      case JavaFxCommand.Text(_, _, _, _, _, _, _, _, _, _) => Some(RenderPrimitiveKind.Text)
      case JavaFxCommand.Image(_, _, _, _, _, _, _, _)      => Some(RenderPrimitiveKind.Image)
      case _                                                => None

  private def commandPaint(command: JavaFxCommand): Option[JavaFxPaint] =
    command match
      case JavaFxCommand.Disc(_, _, _, paint, _)                => Some(paint)
      case JavaFxCommand.PointBatch(_, _, _, paints, _)         => Some(paints.valueAt(0))
      case JavaFxCommand.Polyline(_, _, paint, _)               => Some(paint)
      case JavaFxCommand.CompoundPolygon(_, paint, _)           => Some(paint)
      case JavaFxCommand.Rectangle(_, _, _, _, paint, _)        => Some(paint)
      case JavaFxCommand.Text(_, _, _, _, _, _, _, _, paint, _) => Some(paint)
      case _                                                    => None

  private def pointShapeKind(shape: PointShape): RenderPrimitiveKind =
    shape match
      case PointShape.Circle   => RenderPrimitiveKind.Disc
      case PointShape.Square   => RenderPrimitiveKind.Rectangle
      case PointShape.Triangle => RenderPrimitiveKind.Polygon
      case PointShape.Cross    => RenderPrimitiveKind.Polyline

  private def groupEffects(
      commands: Vector[JavaFxCommand],
      name: GraphicsName
  ): Option[(Boolean, Boolean)] =
    val start = commands.indexWhere {
      case JavaFxCommand.Save(groupName) => groupName.contains(name)
      case _                             => false
    }
    if start < 0 then None
    else
      val effects = commands.drop(start + 1).takeWhile {
        case JavaFxCommand.Rotate(_, _, _) | JavaFxCommand.ClipRect(_, _, _, _) => true
        case _                                                                  => false
      }
      Some(
        (
          effects.exists(_.isInstanceOf[JavaFxCommand.ClipRect]),
          effects.exists(_.isInstanceOf[JavaFxCommand.Rotate])
        )
      )
