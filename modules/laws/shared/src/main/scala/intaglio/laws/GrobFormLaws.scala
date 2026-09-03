package intaglio.laws

import intaglio.*

/** Geometry laws for a rounded [[intaglio.Grob.Rect]] under device lowering.
  *
  * Rounding a corner is a styling choice, not a layout one: the rectangle occupies the same device
  * rectangle whatever its radius, a zero radius lowers to exactly the primitive a sharp rectangle
  * always lowered to, and a request larger than the rectangle admits is clamped to half its shorter
  * side rather than refused or allowed to self-intersect. The laws observe the public lowering
  * (`DeviceScene.fromScene`), so a backend author can rely on them without reading the core.
  */
object RectCornerLaws:
  def apply(
      device: DeviceContext,
      size: Size = Size.npcUnsafe(0.4, 0.2),
      tolerance: Double = 1.0e-9
  ): LawSuite =
    val at = Point.npcUnsafe(0.5, 0.5)

    def lowered(radius: ExtentExpr): Either[String, DevicePrimitive.RectShape] =
      Grob
        .rect(at, size, cornerRadius = radius)
        .flatMap(grob => DeviceScene.fromScene(Scene(Vector(grob)), device))
        .left
        .map(_.message)
        .flatMap(scene =>
          GrobFormLaws.primitives(scene.elements) match
            case Vector(rect: DevicePrimitive.RectShape) => Right(rect)
            case other => Left(s"expected one rectangle primitive, found $other")
        )

    def box(rect: DevicePrimitive.RectShape): (Double, Double, Double, Double) =
      (rect.x, rect.y, rect.width, rect.height)

    def sameBox(
        left: (Double, Double, Double, Double),
        right: (Double, Double, Double, Double)
    ): Boolean =
      math.abs(left._1 - right._1) <= tolerance &&
        math.abs(left._2 - right._2) <= tolerance &&
        math.abs(left._3 - right._3) <= tolerance &&
        math.abs(left._4 - right._4) <= tolerance

    val radii =
      Vector(
        ExtentExpr.zero,
        ExtentExpr.pointsUnsafe(1.0),
        ExtentExpr.pointsUnsafe(6.0),
        ExtentExpr.npcUnsafe(0.05),
        ExtentExpr.pointsUnsafe(1.0e6)
      )

    LawSuite(
      "rect-corner",
      Vector(
        Law(
          "a zero radius lowers to the sharp rectangle",
          () =>
            lowered(ExtentExpr.zero) match
              case Left(problem) => Vector(problem)
              case Right(rect)   =>
                LawDiagnostics.problemWhen(
                  rect.cornerRadius != 0.0,
                  s"a zero corner radius lowered to ${rect.cornerRadius}"
                )
        ),
        Law(
          "the corner radius never changes the device rectangle",
          () =>
            lowered(ExtentExpr.zero) match
              case Left(problem) => Vector(problem)
              case Right(sharp)  =>
                radii.flatMap { radius =>
                  lowered(radius) match
                    case Left(problem) => Vector(s"$radius: $problem")
                    case Right(rect)   =>
                      LawDiagnostics.problemWhen(
                        !sameBox(box(rect), box(sharp)),
                        s"radius $radius moved the rectangle from ${box(sharp)} to ${box(rect)}"
                      )
                }
        ),
        Law(
          "every resolved radius is finite, non-negative, and at most half the shorter side",
          () =>
            radii.flatMap { radius =>
              lowered(radius) match
                case Left(problem) => Vector(s"$radius: $problem")
                case Right(rect)   =>
                  val limit = math.min(math.abs(rect.width), math.abs(rect.height)) / 2.0
                  LawDiagnostics.problemWhen(
                    !rect.cornerRadius.isFinite ||
                      rect.cornerRadius < 0.0 ||
                      rect.cornerRadius > limit + tolerance,
                    s"radius $radius resolved to ${rect.cornerRadius}, outside [0, $limit]"
                  )
            }
        ),
        Law(
          "an oversized request resolves to exactly half the shorter side",
          () =>
            lowered(ExtentExpr.pointsUnsafe(1.0e6)) match
              case Left(problem) => Vector(problem)
              case Right(rect)   =>
                val limit = math.min(math.abs(rect.width), math.abs(rect.height)) / 2.0
                LawDiagnostics.problemWhen(
                  math.abs(rect.cornerRadius - limit) > tolerance,
                  s"an oversized radius resolved to ${rect.cornerRadius}, not the limit $limit"
                )
        ),
        Law(
          "a negative or non-finite radius is unrepresentable",
          () =>
            Vector(-1.0, Double.NaN, Double.PositiveInfinity).flatMap { value =>
              LawDiagnostics.problemWhen(
                ExtentExpr.points(value).isRight,
                s"ExtentExpr.points($value) was accepted as a corner radius"
              )
            }
        )
      )
    )

/** Laws for [[intaglio.LineInterpolation]] under device lowering.
  *
  * A step interpolation is a shorthand, not a new curve: it must lower to exactly the polyline an
  * author would have written by hand, `Linear` must remain the untouched default, and transposing
  * the axes must exchange the two step forms so a flipped step line has the flip of its own
  * expansion.
  */
object LineInterpolationLaws:
  def apply(
      device: DeviceContext,
      tolerance: Double = 1.0e-9
  ): LawSuite =
    val points =
      Vector(
        Point.npcUnsafe(0.1, 0.2),
        Point.npcUnsafe(0.4, 0.7),
        Point.npcUnsafe(0.6, 0.35),
        Point.npcUnsafe(0.9, 0.8)
      )

    def lowered(
        vertices: Vector[Point],
        interpolation: LineInterpolation
    ): Either[String, Vector[DevicePoint]] =
      Grob
        .lines(vertices, interpolation = interpolation)
        .flatMap(grob => DeviceScene.fromScene(Scene(Vector(grob)), device))
        .left
        .map(_.message)
        .flatMap(scene =>
          GrobFormLaws.primitives(scene.elements) match
            case Vector(DevicePrimitive.Polyline(resolved, false, _, _)) => Right(resolved)
            case other => Left(s"expected one open polyline, found $other")
        )

    /** The corners an author writes by hand for a step-after track. */
    def explicitAfter(vertices: Vector[Point]): Vector[Point] =
      vertices.head +: vertices
        .sliding(2)
        .collect { case Vector(previous, current) =>
          Vector(Point(current.x, previous.y), current)
        }
        .toVector
        .flatten

    def explicitBefore(vertices: Vector[Point]): Vector[Point] =
      vertices.head +: vertices
        .sliding(2)
        .collect { case Vector(previous, current) =>
          Vector(Point(previous.x, current.y), current)
        }
        .toVector
        .flatten

    def compare(
        label: String,
        interpolation: LineInterpolation,
        explicit: Vector[Point]
    ): Vector[String] =
      (lowered(points, interpolation), lowered(explicit, LineInterpolation.Linear)) match
        case (Left(problem), _)               => Vector(s"$label: $problem")
        case (_, Left(problem))               => Vector(s"$label explicit form: $problem")
        case (Right(stepped), Right(written)) =>
          Vector(
            LawDiagnostics.problemWhen(
              stepped.length != written.length,
              s"$label lowered to ${stepped.length} vertices, the explicit form to ${written.length}"
            ),
            LawDiagnostics.problemWhen(
              stepped.length == written.length &&
                stepped.indices.exists(index =>
                  math.abs(stepped(index).x - written(index).x) > tolerance ||
                    math.abs(stepped(index).y - written(index).y) > tolerance
                ),
              s"$label lowered to $stepped, the explicit form to $written"
            )
          ).flatten

    LawSuite(
      "line-interpolation",
      Vector(
        Law(
          "linear interpolation lowers the given points unchanged",
          () =>
            lowered(points, LineInterpolation.Linear) match
              case Left(problem)   => Vector(problem)
              case Right(resolved) =>
                LawDiagnostics.problemWhen(
                  resolved.length != points.length,
                  s"linear interpolation lowered ${points.length} points to ${resolved.length}"
                )
        ),
        Law(
          "step-after equals its explicit corner form",
          () => compare("step-after", LineInterpolation.StepAfter, explicitAfter(points))
        ),
        Law(
          "step-before equals its explicit corner form",
          () => compare("step-before", LineInterpolation.StepBefore, explicitBefore(points))
        ),
        Law(
          "a step line holds one value between consecutive corners",
          () =>
            Vector(LineInterpolation.StepAfter, LineInterpolation.StepBefore).flatMap {
              interpolation =>
                lowered(points, interpolation) match
                  case Left(problem)   => Vector(s"$interpolation: $problem")
                  case Right(resolved) =>
                    val axisAligned =
                      resolved
                        .sliding(2)
                        .collect { case Vector(from, to) =>
                          math.abs(from.x - to.x) <= tolerance ||
                          math.abs(from.y - to.y) <= tolerance
                        }
                        .toVector
                    Vector(
                      LawDiagnostics.problemWhen(
                        resolved.length != points.length * 2 - 1,
                        s"$interpolation lowered ${points.length} points to ${resolved.length}, not ${points.length * 2 - 1}"
                      ),
                      LawDiagnostics.problemWhen(
                        axisAligned.contains(false),
                        s"$interpolation produced a diagonal segment in $resolved"
                      )
                    ).flatten
            }
        ),
        Law(
          "a one-point step line lowers to that point",
          () =>
            Vector(LineInterpolation.StepAfter, LineInterpolation.StepBefore).flatMap {
              interpolation =>
                lowered(Vector(points.head), interpolation) match
                  case Left(problem)   => Vector(s"$interpolation: $problem")
                  case Right(resolved) =>
                    LawDiagnostics.problemWhen(
                      resolved.length != 1,
                      s"$interpolation lowered one point to ${resolved.length} vertices"
                    )
            }
        )
      )
    )

private[laws] object GrobFormLaws:
  def primitives(elements: Vector[DeviceElement]): Vector[DevicePrimitive] =
    elements.flatMap {
      case DeviceElement.Mark(primitive)          => Vector(primitive)
      case DeviceElement.Group(_, _, _, children) => primitives(children)
      case DeviceElement.Annotated(_, children)   => primitives(children)
    }
