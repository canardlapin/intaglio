package intaglio.laws

import intaglio.*

/** Placement laws for [[intaglio.TextPlate]].
  *
  * Every backend measures a text run its own way and then places the plate around that measured
  * box. Whatever the measure, the plate must contain the box with exactly the padding on every
  * side, its corner radius must never exceed half its shorter side, and a plate with no padding and
  * no radius must be the box itself. The kit observes a placement function so a custom backend can
  * run it against its own; the default is the shared `TextPlate.around`.
  */
object TextPlateLaws:
  def apply(): LawSuite =
    apply((plate, x, y, width, height) => plate.around(x, y, width, height))

  def apply(
      place: (TextPlate, Double, Double, Double, Double) => TextPlateBounds,
      tolerance: Double = 1.0e-9
  ): LawSuite =
    val boxes = Vector(
      (0.0, 0.0, 40.0, 12.0),
      (-15.5, 30.25, 3.0, 18.0),
      (100.0, -7.0, 0.0, 9.5),
      (2.0, 3.0, 250.0, 0.0)
    )
    val paddings = Vector(0.0, 1.5, 4.0, 20.0)
    val radii = Vector(0.0, 2.0, 1.0e6)
    def plate(padding: Double, radius: Double) =
      TextPlate(
        Rgba.White,
        StrokeWidth.devicePixelsUnsafe(padding),
        StrokeWidth.devicePixelsUnsafe(radius)
      )
    def near(a: Double, b: Double) = math.abs(a - b) <= tolerance
    val cases =
      for
        box <- boxes
        padding <- paddings
        radius <- radii
      yield (box, padding, radius, place(plate(padding, radius), box._1, box._2, box._3, box._4))

    LawSuite(
      "text-plate",
      Vector(
        Law(
          "the plate is the measured box with exactly the padding on every side",
          () =>
            cases.flatMap { case ((x, y, w, h), padding, _, placed) =>
              LawDiagnostics.problemWhen(
                !near(placed.x, x - padding) || !near(placed.y, y - padding) ||
                  !near(placed.x + placed.width, x + w + padding) ||
                  !near(placed.y + placed.height, y + h + padding),
                s"box ($x, $y, $w, $h) with padding $padding placed at $placed"
              )
            }
        ),
        Law(
          "the corner radius is the requested one, at most half the shorter side",
          () =>
            cases.flatMap { case (_, _, radius, placed) =>
              val limit = math.min(placed.width, placed.height) / 2.0
              LawDiagnostics.problemWhen(
                !near(placed.cornerRadius, math.min(radius, limit)),
                s"radius $radius placed as ${placed.cornerRadius} on $placed"
              )
            }
        ),
        Law(
          "an unpadded square plate is the measured box",
          () =>
            boxes.flatMap { case (x, y, w, h) =>
              val placed = place(plate(0.0, 0.0), x, y, w, h)
              LawDiagnostics.problemWhen(
                placed != TextPlateBounds(x, y, w, h, 0.0),
                s"box ($x, $y, $w, $h) placed as $placed"
              )
            }
        )
      )
    )
