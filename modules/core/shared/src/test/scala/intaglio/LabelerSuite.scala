package intaglio

/** Labels must be byte-identical across JVM and Scala.js; this suite runs on both platforms and
  * pins exact strings, including the magnitudes where `Double.toString` diverges between them.
  */
class LabelerSuite extends munit.FunSuite:

  private def label(value: Double): String =
    Labeler.default(Vector(value)).head

  test("integral values label as plain integers") {
    assertEquals(label(0.0), "0")
    assertEquals(label(10.0), "10")
    assertEquals(label(-3.0), "-3")
    assertEquals(label(1e7), "10000000")
  }

  test("ordinary magnitudes label in fixed notation with trailing zeros stripped") {
    assertEquals(label(2.5), "2.5")
    assertEquals(label(-0.5), "-0.5")
    assertEquals(label(0.125), "0.125")
    assertEquals(label(1.25e7), "12500000")
    assertEquals(label(0.0001), "0.0001")
  }

  test("extreme magnitudes label in explicit scientific notation") {
    assertEquals(label(1.0e-5), "1e-5")
    assertEquals(label(2.5e-6), "2.5e-6")
    assertEquals(label(1.5e16), "1.5e16")
  }

  test("rounding keeps six significant digits") {
    assertEquals(label(1.0 / 3.0), "0.333333")
    assertEquals(label(12345.6789), "12345.7")
  }
