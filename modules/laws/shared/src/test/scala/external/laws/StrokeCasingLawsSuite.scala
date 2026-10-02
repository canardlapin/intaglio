package external.laws

import intaglio.*
import intaglio.laws.*

class StrokeCasingLawsSuite extends munit.FunSuite:
  private def assertValid(suite: LawSuite): Unit =
    assertEquals(suite.failures, Vector.empty, clues(suite.name))

  private def failedLaws(suite: LawSuite): Set[String] =
    suite.failures.map(_.law).toSet

  private val device = RendererConformance.targetDevice

  test("cased points and discs obey the casing laws at the conformance target") {
    assertValid(StrokeCasingLaws(device))
  }

  test("cased points and discs obey the casing laws on an anisotropic high-density device") {
    assertValid(StrokeCasingLaws(DeviceContext.unsafe(300.0, 90.0, 300.0), 0.5, 4.0))
  }

  private def mapPrimitives(
      scene: DeviceScene
  )(f: DevicePrimitive => Vector[DevicePrimitive]): DeviceScene =
    def walk(elements: Vector[DeviceElement]): Vector[DeviceElement] =
      elements.flatMap {
        case DeviceElement.Mark(primitive) => f(primitive).map(DeviceElement.Mark(_))
        case DeviceElement.Group(name, clip, rotation, children) =>
          Vector(DeviceElement.Group(name, clip, rotation, walk(children)))
        case DeviceElement.Annotated(meta, children) =>
          Vector(DeviceElement.Annotated(meta, walk(children)))
      }
    scene.copy(elements = walk(scene.elements))

  test("the kit rejects a lowering that drops the casing from discs") {
    val stripped = StrokeCasingLaws(
      device,
      2.0,
      3.0,
      scene =>
        DeviceScene.fromScene(scene, device).map { lowered =>
          mapPrimitives(lowered) {
            case DevicePrimitive.Disc(x, y, r, gp, name) =>
              Vector(DevicePrimitive.Disc(x, y, r, gp.withoutCasing, name))
            case other => Vector(other)
          }
        }
    )
    assertEquals(
      failedLaws(stripped),
      Set("every cased point and disc keeps its casing at the resolved device width")
    )
  }

  test("the kit rejects a lowering that expands a batch into per-mark primitives") {
    val expanded = StrokeCasingLaws(
      device,
      2.0,
      3.0,
      scene =>
        DeviceScene.fromScene(scene, device).map { lowered =>
          mapPrimitives(lowered) {
            case DevicePrimitive.PointBatch(points, radii, shapes, gps, name) =>
              points.indices.toVector.map(index =>
                DevicePrimitive.PointBatch(
                  Vector(points(index)),
                  BatchColumn.Constant(radii.valueAt(index)),
                  BatchColumn.Constant(shapes.valueAt(index)),
                  BatchColumn.Constant(gps.valueAt(index)),
                  name
                )
              )
            case other => Vector(other)
          }
        }
    )
    assertEquals(failedLaws(expanded), Set("a cased batch stays one primitive"))
  }

  test("the kit rejects a casing that moves the drawn geometry") {
    val moved = StrokeCasingLaws(
      device,
      2.0,
      3.0,
      scene =>
        DeviceScene.fromScene(scene, device).map { lowered =>
          mapPrimitives(lowered) {
            case DevicePrimitive.Disc(x, y, r, gp, name) if gp.casing.nonEmpty =>
              Vector(DevicePrimitive.Disc(x, y, r + 1.0, gp, name))
            case other => Vector(other)
          }
        }
    )
    assertEquals(failedLaws(moved), Set("a casing does not move or resize the drawn geometry"))
  }
