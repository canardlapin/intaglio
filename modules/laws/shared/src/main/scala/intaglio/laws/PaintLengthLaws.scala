package intaglio.laws

import intaglio.*

/** Lowering laws for [[intaglio.PaintLengthUnit]]: dash rhythms and fill-pattern geometry.
  *
  * A rhythm or pattern in a physical unit (layout pixels, points, millimetres) must lower to the
  * same physical size at every density, so a dash and a hatch look the same at 1x, at 2x and in
  * export; a layout-pixel value at 96 ppi must lower to itself, which is what keeps existing 1x
  * output unchanged; and an explicit device-pixel value must keep its device size at every density.
  *
  * The kit observes a lowering function so a pipeline that re-lowers scenes can run it against its
  * own lowering; the default is the public `DeviceScene.fromScene`.
  */
object PaintLengthLaws:
  def apply(densities: Vector[Double] = Vector(72.0, 96.0, 144.0, 192.0, 300.0)): LawSuite =
    apply(densities, (scene, device) => DeviceScene.fromScene(scene, device))

  def apply(
      densities: Vector[Double],
      lower: (Scene, DeviceContext) => Either[GraphicsError, DeviceScene]
  ): LawSuite =
    val rhythm = Vector(5.0, 2.0, 1.0, 2.0)
    val physical =
      Vector(PaintLengthUnit.LayoutPixel, PaintLengthUnit.Point, PaintLengthUnit.Millimetre)
    def inchesPerUnit(unit: PaintLengthUnit): Double =
      unit match
        case PaintLengthUnit.LayoutPixel => 1.0 / 96.0
        case PaintLengthUnit.Point       => 1.0 / 72.0
        case PaintLengthUnit.Millimetre  => 1.0 / 25.4
        case PaintLengthUnit.DevicePixel => Double.NaN

    def style(gp: GraphicParams, ppi: Double): Either[String, GraphicParams] =
      Grob
        .rect(Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(0.5, 0.5), gp = gp)
        .flatMap(grob => lower(Scene(Vector(grob)), DeviceContext.unsafe(100, 100, ppi)))
        .left
        .map(_.message)
        .flatMap(scene =>
          styles(scene.elements).headOption.toRight("the rectangle lowered to no styled mark")
        )

    def dash(unit: PaintLengthUnit, ppi: Double): Either[String, Vector[Double]] =
      DashPattern(rhythm, unit).left
        .map(_.message)
        .flatMap(pattern => style(GraphicParams.unsafe(lineType = LineType.Custom(pattern)), ppi))
        .flatMap(_.lineType.dash.map(_.segments).toRight("the dash was dropped"))

    def spacing(unit: PaintLengthUnit, ppi: Double): Either[String, Double] =
      PatternRecipe
        .angledHatch(30.0, 12.0, 1.5)
        .left
        .map(_.message)
        .flatMap(recipe =>
          style(
            GraphicParams.unsafe().withPatternFill(PatternPaint(recipe, Rgba.Black).withUnit(unit)),
            ppi
          )
        )
        .flatMap(_.fillPattern.map(_.recipe.spacing).toRight("the pattern was dropped"))

    def close(a: Double, b: Double): Boolean =
      math.abs(a - b) <= 1.0e-9 * math.max(1.0, math.abs(b))

    LawSuite(
      "paint-length",
      Vector(
        Law(
          "a physical dash and hatch keep one physical size at every density",
          () =>
            for
              unit <- physical
              ppi <- densities
              problem <- (dash(unit, ppi), spacing(unit, ppi)) match
                case (Left(problem), _)            => Vector(s"$unit at $ppi ppi: $problem")
                case (_, Left(problem))            => Vector(s"$unit at $ppi ppi: $problem")
                case (Right(segments), Right(gap)) =>
                  val expected = rhythm.map(_ * inchesPerUnit(unit))
                  LawDiagnostics.problemWhen(
                    segments.length != rhythm.length ||
                      !segments.map(_ / ppi).zip(expected).forall(close.tupled) ||
                      !close(gap / ppi, 12.0 * inchesPerUnit(unit)),
                    s"$unit at $ppi ppi lowered to dash $segments and spacing $gap device pixels"
                  )
            yield problem
        ),
        Law(
          "a layout-pixel rhythm at 96 ppi lowers to itself",
          () =>
            DashPattern(rhythm).left.map(_.message).flatMap { pattern =>
              style(GraphicParams.unsafe(lineType = LineType.Custom(pattern)), 96.0)
            } match
              case Left(problem) => Vector(problem)
              case Right(gp)     =>
                LawDiagnostics.problemWhen(
                  !gp.lineType.dash.exists(_.segments == rhythm),
                  s"96 ppi rhythm lowered to ${gp.lineType}"
                )
        ),
        Law(
          "an explicit device-pixel dash and hatch keep their device size",
          () =>
            densities.flatMap { ppi =>
              (
                dash(PaintLengthUnit.DevicePixel, ppi),
                spacing(PaintLengthUnit.DevicePixel, ppi)
              ) match
                case (Left(problem), _)            => Vector(s"$ppi ppi: $problem")
                case (_, Left(problem))            => Vector(s"$ppi ppi: $problem")
                case (Right(segments), Right(gap)) =>
                  LawDiagnostics.problemWhen(
                    segments != rhythm || gap != 12.0,
                    s"device-pixel values at $ppi ppi lowered to $segments and $gap"
                  )
            }
        )
      )
    )

  private def styles(elements: Vector[DeviceElement]): Vector[GraphicParams] =
    elements.flatMap {
      case DeviceElement.Mark(DevicePrimitive.RectShape(_, _, _, _, _, gp, _)) => Vector(gp)
      case DeviceElement.Mark(_)                                               => Vector.empty
      case DeviceElement.Group(_, _, _, children)                              => styles(children)
      case DeviceElement.Annotated(_, children)                                => styles(children)
    }
