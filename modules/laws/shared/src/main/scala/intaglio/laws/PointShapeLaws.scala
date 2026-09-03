package intaglio.laws

import intaglio.*

/** Geometry laws for [[intaglio.PointShape]] under device lowering.
  *
  * Every shape must lower to marks centred on the resolved point, and the `Diamond` must cover
  * exactly the area of the `Circle` drawn at the same size, so a size-by-value encoding reads the
  * same across those two shapes. The laws observe the public lowering (`DeviceScene.fromScene`),
  * not the formula, so a backend-neutral consumer can rely on them without reading the core.
  */
object PointShapeLaws:
  def apply(
      device: DeviceContext,
      size: ExtentExpr = ExtentExpr.pointsUnsafe(6.0),
      tolerance: Double = 1.0e-9
  ): LawSuite =
    val at = Point.npcUnsafe(0.5, 0.5)

    def lowered(shape: PointShape): Either[String, Vector[DevicePrimitive]] =
      Grob
        .points(Vector(at), size = size, shape = shape)
        .flatMap(grob => DeviceScene.fromScene(Scene(Vector(grob)), device))
        .left
        .map(_.message)
        .map(scene => primitives(scene.elements))

    def centre: Either[String, (Double, Double)] =
      lowered(PointShape.Circle).flatMap {
        case Vector(DevicePrimitive.Disc(cx, cy, _, _, _)) => Right((cx, cy))
        case other => Left(s"expected one disc for the circle shape, found $other")
      }

    def circleRadius: Either[String, Double] =
      lowered(PointShape.Circle).flatMap {
        case Vector(DevicePrimitive.Disc(_, _, radius, _, _)) => Right(radius)
        case other => Left(s"expected one disc for the circle shape, found $other")
      }

    def diamond: Either[String, Vector[DevicePoint]] =
      lowered(PointShape.Diamond).flatMap {
        case Vector(DevicePrimitive.Polyline(points, true, _, _)) if points.length == 4 =>
          Right(points)
        case other =>
          Left(s"expected one closed four-vertex polyline for the diamond, found $other")
      }

    LawSuite(
      "point-shape",
      Vector(
        Law(
          "every shape lowers successfully",
          () =>
            PointShape.values.toVector.flatMap { shape =>
              lowered(shape) match
                case Left(problem) => Vector(s"$shape: $problem")
                case Right(marks)  =>
                  LawDiagnostics.problemWhen(marks.isEmpty, s"$shape lowered to no marks")
            }
        ),
        Law(
          "every shape is centred on its point",
          () =>
            centre match
              case Left(problem)   => Vector(problem)
              case Right((cx, cy)) =>
                PointShape.values.toVector.flatMap { shape =>
                  lowered(shape) match
                    case Left(problem) => Vector(s"$shape: $problem")
                    case Right(marks)  =>
                      val box = bounds(marks)
                      val (midX, midY) = ((box._1 + box._3) / 2.0, (box._2 + box._4) / 2.0)
                      LawDiagnostics.problemWhen(
                        math.abs(midX - cx) > tolerance || math.abs(midY - cy) > tolerance,
                        s"$shape bounding box centre ($midX, $midY) is not the point ($cx, $cy)"
                      )
                }
        ),
        Law(
          "diamond area equals the circle area at the same size",
          () =>
            (circleRadius, diamond) match
              case (Left(problem), _)               => Vector(problem)
              case (_, Left(problem))               => Vector(problem)
              case (Right(radius), Right(vertices)) =>
                val expected = math.Pi * radius * radius
                val observed = shoelaceArea(vertices)
                LawDiagnostics.problemWhen(
                  math.abs(observed - expected) > tolerance * math.max(1.0, expected),
                  s"diamond area $observed differs from circle area $expected"
                )
        ),
        Law(
          "diamond vertices lie on the axes at the documented half-diagonal",
          () =>
            (centre, circleRadius, diamond) match
              case (Left(problem), _, _)                             => Vector(problem)
              case (_, Left(problem), _)                             => Vector(problem)
              case (_, _, Left(problem))                             => Vector(problem)
              case (Right((cx, cy)), Right(radius), Right(vertices)) =>
                val half = PointShape.diamondHalfDiagonal(radius)
                val offAxis = vertices.filterNot(vertex =>
                  (math.abs(vertex.x - cx) <= tolerance &&
                    math.abs(math.abs(vertex.y - cy) - half) <= tolerance) ||
                    (math.abs(vertex.y - cy) <= tolerance &&
                      math.abs(math.abs(vertex.x - cx) - half) <= tolerance)
                )
                val box = bounds(
                  Vector(DevicePrimitive.Polyline(vertices, true, GraphicParams.unsafe(), None))
                )
                Vector(
                  LawDiagnostics.problemWhen(
                    offAxis.nonEmpty,
                    s"vertices $offAxis are not on an axis at distance $half from ($cx, $cy)"
                  ),
                  LawDiagnostics.problemWhen(
                    math.abs((box._3 - box._1) - 2.0 * half) > tolerance ||
                      math.abs((box._4 - box._2) - 2.0 * half) > tolerance,
                    s"diamond hit-test bounds $box do not span 2 * $half on both axes"
                  )
                ).flatten
        )
      )
    )

  private def primitives(elements: Vector[DeviceElement]): Vector[DevicePrimitive] =
    elements.flatMap {
      case DeviceElement.Mark(primitive)          => Vector(primitive)
      case DeviceElement.Group(_, _, _, children) => primitives(children)
    }

  /** Axis-aligned bounds `(minX, minY, maxX, maxY)` of the drawn geometry, ignoring stroke width.
    */
  private def bounds(marks: Vector[DevicePrimitive]): (Double, Double, Double, Double) =
    val xs = Vector.newBuilder[Double]
    val ys = Vector.newBuilder[Double]
    marks.foreach {
      case DevicePrimitive.Disc(cx, cy, radius, _, _) =>
        xs += cx - radius
        xs += cx + radius
        ys += cy - radius
        ys += cy + radius
      case DevicePrimitive.PointBatch(points, radii, _, _, _) =>
        points.indices.foreach { index =>
          val radius = radii.valueAt(index)
          xs += points(index).x - radius
          xs += points(index).x + radius
          ys += points(index).y - radius
          ys += points(index).y + radius
        }
      case DevicePrimitive.Polyline(points, _, _, _) =>
        points.foreach { point =>
          xs += point.x
          ys += point.y
        }
      case DevicePrimitive.CompoundPolygon(rings, _, _) =>
        rings.flatten.foreach { point =>
          xs += point.x
          ys += point.y
        }
      case DevicePrimitive.RectShape(x, y, width, height, _, _) =>
        xs += x
        xs += x + width
        ys += y
        ys += y + height
      case DevicePrimitive.TextRun(_, x, y, _, _, _, _, _, _, _) =>
        xs += x
        ys += y
      case DevicePrimitive.Image(_, x, y, width, height, _, _, _) =>
        xs += x
        xs += x + width
        ys += y
        ys += y + height
    }
    val allX = xs.result()
    val allY = ys.result()
    (allX.min, allY.min, allX.max, allY.max)

  private def shoelaceArea(vertices: Vector[DevicePoint]): Double =
    var sum = 0.0
    var index = 0
    while index < vertices.length do
      val current = vertices(index)
      val next = vertices((index + 1) % vertices.length)
      sum += current.x * next.y - next.x * current.y
      index += 1
    math.abs(sum) / 2.0
