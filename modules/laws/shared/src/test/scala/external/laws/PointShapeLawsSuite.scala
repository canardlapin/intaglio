package external.laws

import intaglio.*
import intaglio.laws.*

class PointShapeLawsSuite extends munit.FunSuite:
  private def assertValid(suite: LawSuite): Unit =
    assertEquals(suite.failures, Vector.empty, clues(suite.name))

  test("point shapes obey the centring and diamond area-parity laws at the conformance target") {
    assertValid(PointShapeLaws(RendererConformance.targetDevice))
  }

  test("point shapes obey the laws on an anisotropic high-density device") {
    assertValid(
      PointShapeLaws(DeviceContext.unsafe(300.0, 90.0, 300.0), ExtentExpr.pointsUnsafe(2.5))
    )
  }
