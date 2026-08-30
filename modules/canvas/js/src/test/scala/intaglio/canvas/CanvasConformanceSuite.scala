package intaglio.canvas

import intaglio.*

class CanvasConformanceSuite extends munit.FunSuite:

  private object CanvasHarness extends RendererHarness[CanvasProgram]:
    private val options = CanvasOptions.unsafe(width = 240, height = 160)

    override def render(scene: Scene): Either[String, CanvasProgram] =
      CanvasRenderer.compile(scene, options).left.map(_.message)

    override def containsMarker(out: CanvasProgram, name: GraphicsName): Boolean =
      out.commands.exists(commandName(_).contains(name))

    override def satisfies(out: CanvasProgram, requirement: RenderRequirement): Boolean =
      requirement match
        case RenderRequirement.Primitive(name, kind) =>
          out.commands.exists(command => commandName(command).contains(name) && primitiveKind(command).contains(kind))
        case RenderRequirement.Group(name, clipped, rotated) =>
          groupEffects(out.commands, name).contains((clipped, rotated))
        case RenderRequirement.Style(name, stroke, fill, lineWidth, lineType, lineCap, lineJoin, alpha) =>
          out.commands.exists { command =>
            commandName(command).contains(name) && commandPaint(command).exists { paint =>
              paint.stroke == stroke.map(CanvasColor.fromRgba) &&
              paint.fill == fill.map(CanvasColor.fromRgba) &&
              paint.lineWidth == lineWidth &&
              paint.dash == CanvasLineDash.fromLineType(lineType) &&
              paint.lineCap == lineCap &&
              paint.lineJoin == lineJoin &&
              paint.opacity == alpha
            }
          }
        case RenderRequirement.PatternFill(name, pattern, alpha) =>
          out.commands.exists { command =>
            commandName(command).contains(name) &&
            commandPaint(command).exists(paint => paint.fillPattern.contains(pattern) && paint.opacity == alpha)
          }
        case RenderRequirement.Text(name, horizontal, vertical, rotated) =>
          out.commands.exists {
            case CanvasCommand.Text(_, _, _, h, v, rotation, _, _, _, commandName) =>
              commandName.contains(name) && h == horizontal && v == vertical && (rotation != 0.0) == rotated
            case _ => false
          }
        case RenderRequirement.Image(name, dimensions, interpolation, alpha) =>
          out.commands.exists {
            case CanvasCommand.Image(image, _, _, _, _, actualInterpolation, actualAlpha, commandName) =>
              commandName.contains(name) && image.dimensions == dimensions &&
                actualInterpolation == interpolation && actualAlpha == alpha
            case _ => false
          }

    override def validate(out: CanvasProgram): Option[String] =
      CanvasProgram.validate(out)

  test("the Canvas backend passes the renderer conformance contract") {
    val violations = RendererConformance.check(CanvasHarness).fold(e => fail(e.message), identity)
    assertEquals(violations, Vector.empty)
  }

  test("combined viewport effects rotate before installing the clip") {
    val scene = RendererConformance.clippedRotatedViewportCase.fold(e => fail(e.message), identity).scene
    val program = CanvasRenderer
      .compile(scene, CanvasOptions.unsafe(width = 240, height = 160))
      .fold(e => fail(e.message), identity)
    val name = GraphicsName.unsafe("conformance-clip-rotation")
    val start = program.commands.indexWhere {
      case CanvasCommand.Save(groupName) => groupName.contains(name)
      case _                             => false
    }

    assert(start >= 0)
    assert(program.commands(start + 1).isInstanceOf[CanvasCommand.Rotate])
    assert(program.commands(start + 2).isInstanceOf[CanvasCommand.ClipRect])
    assert(program.commands.drop(start + 3).head.isInstanceOf[CanvasCommand.Polyline])
    assert(program.commands.drop(start + 4).head == CanvasCommand.Restore(Some(name)))
  }

  private def commandName(command: CanvasCommand): Option[GraphicsName] =
    command match
      case CanvasCommand.Save(name)                         => name
      case CanvasCommand.Restore(name)                      => name
      case CanvasCommand.Disc(_, _, _, _, name)             => name
      case CanvasCommand.Polyline(_, _, _, name)             => name
      case CanvasCommand.CompoundPolygon(_, _, name)         => name
      case CanvasCommand.Rectangle(_, _, _, _, _, name)      => name
      case CanvasCommand.Text(_, _, _, _, _, _, _, _, _, name) => name
      case CanvasCommand.Image(_, _, _, _, _, _, _, name)       => name
      case CanvasCommand.Rotate(_, _, _) | CanvasCommand.ClipRect(_, _, _, _) => None

  private def primitiveKind(command: CanvasCommand): Option[RenderPrimitiveKind] =
    command match
      case CanvasCommand.Disc(_, _, _, _, _) => Some(RenderPrimitiveKind.Disc)
      case CanvasCommand.Polyline(_, closed, _, _) =>
        Some(if closed then RenderPrimitiveKind.Polygon else RenderPrimitiveKind.Polyline)
      case CanvasCommand.CompoundPolygon(_, _, _) => Some(RenderPrimitiveKind.Polygon)
      case CanvasCommand.Rectangle(_, _, _, _, _, _) => Some(RenderPrimitiveKind.Rectangle)
      case CanvasCommand.Text(_, _, _, _, _, _, _, _, _, _) => Some(RenderPrimitiveKind.Text)
      case CanvasCommand.Image(_, _, _, _, _, _, _, _) => Some(RenderPrimitiveKind.Image)
      case _ => None

  private def commandPaint(command: CanvasCommand): Option[CanvasPaint] =
    command match
      case CanvasCommand.Disc(_, _, _, paint, _)          => Some(paint)
      case CanvasCommand.Polyline(_, _, paint, _)         => Some(paint)
      case CanvasCommand.CompoundPolygon(_, paint, _)     => Some(paint)
      case CanvasCommand.Rectangle(_, _, _, _, paint, _)  => Some(paint)
      case CanvasCommand.Text(_, _, _, _, _, _, _, _, paint, _) => Some(paint)
      case _ => None

  private def groupEffects(
      commands: Vector[CanvasCommand],
      name: GraphicsName
  ): Option[(Boolean, Boolean)] =
    val start = commands.indexWhere {
      case CanvasCommand.Save(groupName) => groupName.contains(name)
      case _                             => false
    }
    if start < 0 then None
    else
      val effects = commands.drop(start + 1).takeWhile {
        case CanvasCommand.Rotate(_, _, _) | CanvasCommand.ClipRect(_, _, _, _) => true
        case _                                                                  => false
      }
      Some((effects.exists(_.isInstanceOf[CanvasCommand.ClipRect]), effects.exists(_.isInstanceOf[CanvasCommand.Rotate])))
