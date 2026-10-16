package intaglio

class ScalarRampCompatibilitySuite extends munit.FunSuite:
  test("the legacy one-argument JVM constructor retains linear interpolation"):
    // Scala-private constructors used by companions have public JVM descriptors.
    // Resolve the exact baseline descriptor, without Scala's default arguments.
    val constructor = classOf[ScalarRamp].getConstructor(classOf[Vector[?]])
    val stops = Vector(
      0.0 -> Rgba32.unsafe(0, 20, 40, 60),
      1.0 -> Rgba32.unsafe(200, 220, 240, 160)
    )
    val ramp = constructor.newInstance(stops)
    assertEquals(ramp.stops, stops)
    assertEquals(ramp.sampling, ScalarRampSampling.Linear)
    assertEquals(ramp.colorAt(0.25), Rgba32.unsafe(50, 70, 90, 85))
    assertEquals(ramp.colorAt(-1.0), stops.head._2)
    assertEquals(ramp.colorAt(2.0), stops.last._2)
