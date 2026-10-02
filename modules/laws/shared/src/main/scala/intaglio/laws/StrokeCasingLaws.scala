package intaglio.laws

import intaglio.*

/** Lowering laws for [[intaglio.StrokeCasing]] on point marks and discs.
  *
  * A casing is paint, not geometry. Every point shape, drawn singly or in a batch, and every circle
  * grob must keep its casing through device lowering, resolved once to an absolute device-pixel
  * width; the lowered marks must occupy the same drawn geometry as the uncased marks; and a batch
  * must stay one primitive rather than expanding into per-mark paths.
  *
  * The kit observes a lowering function rather than the core directly, so a backend or scene
  * transformer that re-lowers scenes can run it against its own pipeline. The default observes the
  * public `DeviceScene.fromScene`.
  */
object StrokeCasingLaws:
  def apply(
      device: DeviceContext,
      lineWidth: Double = 2.0,
      multiplier: Double = 3.0
  ): LawSuite =
    apply(device, lineWidth, multiplier, scene => DeviceScene.fromScene(scene, device))

  def apply(
      device: DeviceContext,
      lineWidth: Double,
      multiplier: Double,
      lower: Scene => Either[GraphicsError, DeviceScene]
  ): LawSuite =
    val at = Vector(Point.npcUnsafe(0.3, 0.4), Point.npcUnsafe(0.7, 0.6))
    val plain = GraphicParams.unsafe(stroke = Some(Rgba.Black), lineWidth = lineWidth)
    val cased =
      plain.withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(multiplier)))
    val expectedWidth = lineWidth * multiplier

    def single(shape: PointShape, gp: GraphicParams): Either[GraphicsError, Grob] =
      Grob.points(at, size = ExtentExpr.pointsUnsafe(5.0), shape = shape, gp = gp)

    def batch(gp: GraphicParams): Either[GraphicsError, Grob] =
      Grob.pointBatch(
        at ++ Vector(Point.npcUnsafe(0.15, 0.4), Point.npcUnsafe(0.35, 0.6)),
        shapes = BatchColumn.Values(
          Vector(PointShape.Circle, PointShape.Square, PointShape.Cross, PointShape.Diamond)
        ),
        graphicParams = BatchColumn.Constant(gp)
      )

    def circle(gp: GraphicParams): Either[GraphicsError, Grob] =
      Grob.circle(Point.npcUnsafe(0.5, 0.5), ExtentExpr.pointsUnsafe(9.0), gp = gp)

    val fixtures: Vector[(String, GraphicParams => Either[GraphicsError, Grob])] =
      PointShape.values.toVector.map(shape =>
        (s"$shape point", (gp: GraphicParams) => single(shape, gp))
      ) ++ Vector("point batch" -> batch, "circle" -> circle)

    def lowered(
        build: GraphicParams => Either[GraphicsError, Grob],
        gp: GraphicParams
    ): Either[String, Vector[DevicePrimitive]] =
      build(gp)
        .flatMap(grob => lower(Scene(Vector(grob))))
        .left
        .map(_.message)
        .map(scene => primitives(scene.elements))

    def both(
        build: GraphicParams => Either[GraphicsError, Grob]
    ): Either[String, (Vector[DevicePrimitive], Vector[DevicePrimitive])] =
      for
        p <- lowered(build, plain)
        c <- lowered(build, cased)
      yield (p, c)

    LawSuite(
      "stroke-casing",
      Vector(
        Law(
          "every cased point and disc keeps its casing at the resolved device width",
          () =>
            fixtures.flatMap { (label, build) =>
              lowered(build, cased) match
                case Left(problem) => Vector(s"$label: $problem")
                case Right(marks)  =>
                  val styles = marks.flatMap(styleOf)
                  val wrong = styles.filterNot(gp => gp.casing.exists(resolvedTo(_, expectedWidth)))
                  LawDiagnostics.problemWhen(
                    styles.isEmpty || wrong.nonEmpty,
                    s"$label lowered without a resolved casing of $expectedWidth device pixels: " +
                      styles.map(_.casing).mkString(", ")
                  )
            }
        ),
        Law(
          "a casing does not move or resize the drawn geometry",
          () =>
            fixtures.flatMap { (label, build) =>
              both(build) match
                case Left(problem)            => Vector(s"$label: $problem")
                case Right((uncased, casing)) =>
                  LawDiagnostics.problemWhen(
                    footprint(uncased) != footprint(casing),
                    s"$label geometry ${footprint(casing)} differs from uncased ${footprint(uncased)}"
                  )
            }
        ),
        Law(
          "a cased batch stays one primitive",
          () =>
            lowered(batch, cased) match
              case Left(problem) => Vector(s"point batch: $problem")
              case Right(marks)  =>
                LawDiagnostics.problemWhen(
                  marks.length != 1 || !marks.head.isInstanceOf[DevicePrimitive.PointBatch],
                  s"cased batch lowered to ${marks.length} primitives: ${marks.map(_.productPrefix)}"
                )
        )
      )
    )

  private def resolvedTo(casing: StrokeCasing, expected: Double): Boolean =
    casing.width match
      case CasingWidth.Absolute(width) =>
        width.unit == StrokeUnit.DevicePixel && math.abs(width.value - expected) <= 1.0e-9
      case CasingWidth.Relative(_) => false

  private def styleOf(primitive: DevicePrimitive): Vector[GraphicParams] =
    primitive match
      case DevicePrimitive.Disc(_, _, _, gp, _)             => Vector(gp)
      case DevicePrimitive.PointBatch(points, _, _, gps, _) =>
        points.indices.map(gps.valueAt).toVector
      case DevicePrimitive.Polyline(_, _, gp, _)                 => Vector(gp)
      case DevicePrimitive.CompoundPolygon(_, gp, _)             => Vector(gp)
      case DevicePrimitive.RectShape(_, _, _, _, _, gp, _)       => Vector(gp)
      case DevicePrimitive.TextRun(_, _, _, _, _, _, _, _, _, _) => Vector.empty
      case DevicePrimitive.Image(_, _, _, _, _, _, _, _)         => Vector.empty

  /** Drawn geometry as a set of (centre-or-vertex, extent) samples, independent of how a point is
    * packaged: a cross drawn as two bars and the same cross drawn as one batch mark both contribute
    * the cross's centre and half-span, so packaging alone never reads as moved geometry.
    */
  private def footprint(marks: Vector[DevicePrimitive]): Set[(Long, Long, Long)] =
    def key(x: Double, y: Double, extent: Double): (Long, Long, Long) =
      (math.round(x * 1.0e6), math.round(y * 1.0e6), math.round(extent * 1.0e6))
    marks.flatMap {
      case DevicePrimitive.Disc(cx, cy, radius, _, _)              => Vector(key(cx, cy, radius))
      case DevicePrimitive.PointBatch(points, radii, shapes, _, _) =>
        points.indices.toVector.map { index =>
          val radius = radii.valueAt(index)
          val extent = shapes.valueAt(index) match
            case PointShape.Diamond => PointShape.diamondHalfDiagonal(radius)
            case PointShape.Circle | PointShape.Square | PointShape.Triangle | PointShape.Cross =>
              radius
          key(points(index).x, points(index).y, extent)
        }
      case DevicePrimitive.Polyline(points, _, _, _) =>
        val xs = points.map(_.x)
        val ys = points.map(_.y)
        Vector(
          key(
            (xs.min + xs.max) / 2.0,
            (ys.min + ys.max) / 2.0,
            math.max(xs.max - xs.min, ys.max - ys.min) / 2.0
          )
        )
      case DevicePrimitive.RectShape(x, y, width, height, _, _, _) =>
        Vector(key(x + width / 2.0, y + height / 2.0, math.max(width, height) / 2.0))
      case DevicePrimitive.CompoundPolygon(rings, _, _) =>
        rings.flatten.map(p => key(p.x, p.y, 0.0))
      case DevicePrimitive.TextRun(_, x, y, _, _, _, _, _, _, _) => Vector(key(x, y, 0.0))
      case DevicePrimitive.Image(_, x, y, width, _, _, _, _)     => Vector(key(x, y, width))
    }.toSet

  private def primitives(elements: Vector[DeviceElement]): Vector[DevicePrimitive] =
    elements.flatMap {
      case DeviceElement.Mark(primitive)          => Vector(primitive)
      case DeviceElement.Group(_, _, _, children) => primitives(children)
      case DeviceElement.Annotated(_, children)   => primitives(children)
    }
