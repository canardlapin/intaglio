package external.laws

import intaglio.*
import intaglio.laws.*

class PaintLengthLawsSuite extends munit.FunSuite:
  test("dash rhythms and fill patterns obey the paint-length laws") {
    val suite = PaintLengthLaws()
    assertEquals(suite.failures, Vector.empty, clues(suite.name))
  }

  /** A pipeline that lowers every scene at 96 ppi, whatever its target, draws the given numbers as
    * device pixels: exactly the HiDPI shrinkage the unit exists to prevent.
    */
  test("the kit rejects a lowering that ignores the target density") {
    val fixed = PaintLengthLaws(
      Vector(96.0, 192.0),
      (scene, device) =>
        DeviceScene.fromScene(scene, DeviceContext.unsafe(device.width, device.height, 96.0))
    )
    assertEquals(
      fixed.failures.map(_.law).distinct,
      Vector("a physical dash and hatch keep one physical size at every density")
    )
  }

  /** A host that "fixes" HiDPI dashes by rescaling every lowered rhythm by the density ratio
    * doubles the physical units it already resolved and scales the device-pixel opt-in.
    */
  test("the kit rejects a lowering that rescales every lowered dash by density") {
    def rescaled(scene: DeviceScene, factor: Double): DeviceScene =
      def walk(elements: Vector[DeviceElement]): Vector[DeviceElement] =
        elements.map {
          case DeviceElement.Mark(DevicePrimitive.RectShape(x, y, w, h, r, gp, name)) =>
            val restyled = gp.lineType.dash match
              case Some(pattern) =>
                GraphicParams
                  .unsafe(
                    lineType = LineType.Custom(
                      DashPattern(pattern.segments.map(_ * factor), pattern.unit).orThrow
                    )
                  )
              case None => gp
            DeviceElement.Mark(DevicePrimitive.RectShape(x, y, w, h, r, restyled, name))
          case DeviceElement.Group(name, clip, rotation, children) =>
            DeviceElement.Group(name, clip, rotation, walk(children))
          case DeviceElement.Annotated(meta, children) =>
            DeviceElement.Annotated(meta, walk(children))
          case mark: DeviceElement.Mark => mark
        }
      scene.copy(elements = walk(scene.elements))
    val rescaling = PaintLengthLaws(
      Vector(96.0, 192.0),
      (scene, device) =>
        DeviceScene.fromScene(scene, device).map(rescaled(_, device.pixelsPerInch / 96.0))
    )
    assertEquals(
      rescaling.failures.map(_.law).distinct.toSet,
      Set(
        "a physical dash and hatch keep one physical size at every density",
        "an explicit device-pixel dash and hatch keep their device size"
      )
    )
  }
