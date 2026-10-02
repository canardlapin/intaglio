package external.laws

import intaglio.*
import intaglio.laws.*

class TextPlateLawsSuite extends munit.FunSuite:
  test("the shared plate placement obeys the text-plate laws") {
    val suite = TextPlateLaws()
    assertEquals(suite.failures, Vector.empty, clues(suite.name))
  }

  test("the kit rejects a placement that pads only the right and bottom") {
    val lopsided = TextPlateLaws((plate, x, y, w, h) =>
      val pad = plate.padding.value
      TextPlateBounds(x, y, w + 2.0 * pad, h + 2.0 * pad, 0.0)
    )
    assert(
      lopsided.failures
        .map(_.law)
        .contains("the plate is the measured box with exactly the padding on every side")
    )
  }

  test("the kit rejects a placement that does not clamp its corner radius") {
    val unclamped = TextPlateLaws((plate, x, y, w, h) =>
      val pad = plate.padding.value
      TextPlateBounds(x - pad, y - pad, w + 2.0 * pad, h + 2.0 * pad, plate.cornerRadius.value)
    )
    assertEquals(
      unclamped.failures.map(_.law).distinct,
      Vector("the corner radius is the requested one, at most half the shorter side")
    )
  }
