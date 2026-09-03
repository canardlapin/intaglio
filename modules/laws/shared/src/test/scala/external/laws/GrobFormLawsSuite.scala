package external.laws

import intaglio.*
import intaglio.laws.*

class GrobFormLawsSuite extends munit.FunSuite:
  private def assertValid(suite: LawSuite): Unit =
    assertEquals(suite.failures, Vector.empty, clues(suite.name))

  test("rounded rectangles obey the corner laws at the conformance target") {
    assertValid(RectCornerLaws(RendererConformance.targetDevice))
  }

  test("rounded rectangles obey the corner laws on an anisotropic high-density device") {
    assertValid(
      RectCornerLaws(DeviceContext.unsafe(300.0, 90.0, 300.0), Size.npcUnsafe(0.9, 0.08))
    )
  }

  test("step interpolation obeys the expansion laws at the conformance target") {
    assertValid(LineInterpolationLaws(RendererConformance.targetDevice))
  }

  test("step interpolation obeys the expansion laws on an anisotropic high-density device") {
    assertValid(LineInterpolationLaws(DeviceContext.unsafe(300.0, 90.0, 300.0)))
  }

  /** The kits must detect breakage, not merely pass. A step line whose explicit peer omits one
    * corner has to fail the equality law.
    */
  test("the step-line kit rejects an expansion that is not the explicit corner form") {
    val stepped = Grob
      .lines(
        Vector(Point.npcUnsafe(0.1, 0.2), Point.npcUnsafe(0.6, 0.8)),
        interpolation = LineInterpolation.StepAfter
      )
      .orThrow
    val truncated = Grob
      .lines(Vector(Point.npcUnsafe(0.1, 0.2), Point.npcUnsafe(0.6, 0.8)))
      .orThrow
    val device = RendererConformance.targetDevice
    def vertices(grob: Grob): Vector[DevicePoint] =
      DeviceScene
        .fromScene(Scene(Vector(grob)), device)
        .orThrow
        .elements
        .collect { case DeviceElement.Mark(DevicePrimitive.Polyline(points, _, _, _)) =>
          points
        }
        .flatten

    assertEquals(vertices(stepped).length, 3)
    assertEquals(vertices(truncated).length, 2)
    assertNotEquals(vertices(stepped), vertices(truncated))
  }
