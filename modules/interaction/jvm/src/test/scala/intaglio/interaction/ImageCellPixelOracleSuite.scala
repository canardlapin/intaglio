package intaglio.interaction

import intaglio.*
import java.awt.{AlphaComposite, Graphics2D, RenderingHints, Shape}
import java.awt.geom.{AffineTransform, Area, Point2D, Rectangle2D}
import java.awt.image.BufferedImage

/** `NamedPickingPlan.cellAt` against pixels Java2D draws, independently of picking.
  *
  * The oracle draws the resolved `DeviceScene` the way the Java2D backend does — each group rotates
  * about its pivot, then clips in the rotated frame, and the image is placed by a translate-scale
  * transform with nearest-neighbour sampling — onto a transparent `BufferedImage`. Every cell of
  * the raster has its own colour, so the colour at a pixel names the cell drawn there. Java2D also
  * reports, as shapes, the device region its clip leaves and the image's device quad.
  *
  * Every pixel is classified by its unit square against the visible region (clip ∩ quad). A pixel
  * wholly inside must be painted with exactly the cell `cellAt` reports at its centre; a pixel
  * wholly outside must be unpainted and report no cell. A pixel straddling the clip or image edge
  * is rasterized by the renderer's own edge rule, so it is counted, not compared; so is a pixel
  * whose centre lies within 0.01 device pixel of a cell boundary (Java2D samples in single
  * precision). Rotations use `StrictMath`.
  */
class ImageCellPixelOracleSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val name = GraphicsName.unsafe("grid")

  private val columns = 7
  private val rows = 5
  private val blue = 77
  private val palette = RasterImage.tabulate(RasterDimensions.unsafe(columns, rows)) { (x, y) =>
    Rgba32.unsafe(20 + 30 * x, 20 + 40 * y, blue)
  }

  /** The cell a painted pixel's colour names, if it names one. */
  private def cellOf(argb: Int): Option[ImageCell] =
    val r = (argb >>> 16) & 255
    val g = (argb >>> 8) & 255
    val b = argb & 255
    Option.when(
      (argb >>> 24) == 255 && b == blue && (r - 20) % 30 == 0 && (g - 20) % 40 == 0 &&
        (r - 20) / 30 < columns && (g - 20) / 40 < rows
    )(ImageCell((g - 20) / 40, (r - 20) / 30))

  /** The image drawn, the device-to-image-frame map, and the device region left visible. */
  private final case class Drawn(
      image: DevicePrimitive.Image,
      toLocal: AffineTransform,
      visible: Area
  )

  /** `Graphics2D.rotate(radians, pivotX, pivotY)`, as the backend calls it, built with `StrictMath`
    * so the oracle is the same on every platform.
    */
  private def rotationOf(r: DeviceRotation): AffineTransform =
    val angle = StrictMath.toRadians(r.degrees)
    val c = StrictMath.cos(angle)
    val s = StrictMath.sin(angle)
    new AffineTransform(
      c,
      s,
      -s,
      c,
      r.pivotX - c * r.pivotX + s * r.pivotY,
      r.pivotY - s * r.pivotX - c * r.pivotY
    )

  /** Draw `scene` as the Java2D backend does. */
  private def draw(scene: DeviceScene): (BufferedImage, Drawn) =
    val out =
      new BufferedImage(scene.width.toInt, scene.height.toInt, BufferedImage.TYPE_INT_ARGB)
    val graphics = out.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    var drawn = Option.empty[Drawn]
    def walk(elements: Vector[DeviceElement], g: Graphics2D): Unit =
      elements.foreach {
        case DeviceElement.Group(_, clip, rotation, children) =>
          val copy = g.create().asInstanceOf[Graphics2D]
          try
            rotation.foreach(r => copy.transform(rotationOf(r)))
            clip.foreach(c => copy.clip(new Rectangle2D.Double(c.x, c.y, c.width, c.height)))
            walk(children, copy)
          finally copy.dispose()
        case DeviceElement.Annotated(_, children)             => walk(children, g)
        case DeviceElement.Mark(image: DevicePrimitive.Image) =>
          val copy = g.create().asInstanceOf[Graphics2D]
          try
            copy.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION,
              RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            )
            copy.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f))
            val source = new BufferedImage(columns, rows, BufferedImage.TYPE_INT_ARGB)
            for y <- 0 until rows; x <- 0 until columns do
              val p = image.image.pixelUnsafe(x, y)
              source.setRGB(x, y, (p.alpha << 24) | (p.red << 16) | (p.green << 8) | p.blue)
            val placement = new AffineTransform()
            placement.translate(image.x, image.y)
            placement.scale(image.width / columns, image.height / rows)
            copy.drawImage(source, placement, null)
            val device = copy.getTransform
            def toDevice(shape: Shape) = new Area(device.createTransformedShape(shape))
            val visible = toDevice(
              new Rectangle2D.Double(image.x, image.y, image.width, image.height)
            )
            Option(copy.getClip).foreach(clip => visible.intersect(toDevice(clip)))
            drawn = Some(Drawn(image, device.createInverse(), visible))
          finally copy.dispose()
        case DeviceElement.Mark(_) => ()
      }
    walk(scene.elements, graphics)
    graphics.dispose()
    (out, drawn.getOrElse(fail("the scene draws no image")))

  private final case class Tally(inside: Int, outside: Int, edge: Int, boundary: Int)

  private def compare(scene: Scene, context: RenderContext): Tally =
    val resolved = ok(DeviceScene.fromScene(scene, context))
    val plan = ok(NamedPicking.fromResolved(resolved, context))
    val (pixels, drawn) = draw(resolved)
    val cellWidth = drawn.image.width / columns
    val cellHeight = drawn.image.height / rows
    def nearCellBoundary(x: Double, y: Double): Boolean =
      val local = drawn.toLocal.transform(new Point2D.Double(x, y), null)
      val u = (local.getX - drawn.image.x) / cellWidth
      val v = (local.getY - drawn.image.y) / cellHeight
      math.abs(u - math.rint(u)) * cellWidth < 0.01 ||
      math.abs(v - math.rint(v)) * cellHeight < 0.01
    var tally = Tally(0, 0, 0, 0)
    for py <- 0 until pixels.getHeight; px <- 0 until pixels.getWidth do
      val square = new Rectangle2D.Double(px, py, 1, 1)
      val argb = pixels.getRGB(px, py)
      val reported = ok(plan.cellAt(name, DevicePoint(px + 0.5, py + 0.5)))
      if drawn.visible.contains(square) then
        val painted = cellOf(argb)
        assert(painted.nonEmpty, clues(px, py, argb.toHexString))
        if nearCellBoundary(px + 0.5, py + 0.5) then
          tally = tally.copy(boundary = tally.boundary + 1)
        else
          assertEquals(reported, painted, clues(px, py))
          tally = tally.copy(inside = tally.inside + 1)
      else if !drawn.visible.intersects(square) then
        assertEquals(argb >>> 24, 0, clues(px, py))
        assertEquals(reported, None, clues(px, py))
        tally = tally.copy(outside = tally.outside + 1)
      else tally = tally.copy(edge = tally.edge + 1)
    tally

  private val hidpi =
    RenderContext.unsafe(width = 360, height = 240, pixelsPerInch = 192, deviceScale = 2)

  private def grid(at: Point, size: Size): Grob =
    Grob.imageUnsafe(palette, at, size, name = Some(name))

  private def nested(child: Grob, inner: Viewport): Scene =
    val outer =
      Viewport.unsafe(origin = Point.npcUnsafe(0.05, 0.1), size = Size.npcUnsafe(0.9, 0.8))
    Scene(
      Vector(
        Grob.group(
          Vector(Grob.group(Vector(child), viewport = Some(inner))),
          viewport = Some(outer)
        )
      )
    )

  /** Most of the canvas is compared, inside and outside the image, and edges are a thin rim. */
  private def assertCovered(tally: Tally, label: String): Unit =
    assert(tally.inside > 4000, clues(label, tally))
    assert(tally.outside > 1000, clues(label, tally))
    assert(tally.boundary < tally.inside / 50, clues(label, tally))
    assert(tally.edge < tally.inside / 10, clues(label, tally))

  test("nested viewports at device scale 2 agree with the drawn pixels") {
    val inner =
      Viewport.unsafe(origin = Point.npcUnsafe(0.12, 0.08), size = Size.npcUnsafe(0.71, 0.83))
    val scene = nested(grid(Point.npcUnsafe(0.47, 0.52), Size.npcUnsafe(0.87, 0.79)), inner)
    assertCovered(compare(scene, hidpi), "scaled")
  }

  test("a clip that cuts the image leaves no cell where nothing is drawn") {
    val inner = Viewport.unsafe(
      origin = Point.npcUnsafe(0.3, 0.25),
      size = Size.npcUnsafe(0.4, 0.5),
      clip = Clip.On
    )
    val tally =
      compare(nested(grid(Point.npcUnsafe(0.45, 0.55), Size.npcUnsafe(1.7, 1.5)), inner), hidpi)
    assertCovered(tally, "clipped")
    assert(tally.outside > 360 * 240 / 2, clues(tally))
  }

  test("a rotated viewport turns the cells as drawn, with and without its clip") {
    Vector(Clip.Off, Clip.On).foreach { clip =>
      val inner = Viewport.unsafe(
        origin = Point.npcUnsafe(0.3, 0.15),
        size = Size.npcUnsafe(0.5, 0.6),
        clip = clip,
        angleDegrees = 27
      )
      val scene = nested(grid(Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(1.1, 0.9)), inner)
      assertCovered(compare(scene, hidpi), clip.toString)
    }
  }

  test("rotations and clips compose through nested rotated viewports") {
    val inner = Viewport.unsafe(
      origin = Point.npcUnsafe(0.2, 0.2),
      size = Size.npcUnsafe(0.6, 0.6),
      clip = Clip.On,
      angleDegrees = -40
    )
    val middle = Viewport.unsafe(
      origin = Point.npcUnsafe(0.25, 0.1),
      size = Size.npcUnsafe(0.5, 0.8),
      clip = Clip.Off,
      angleDegrees = 15
    )
    val scene = Scene(
      Vector(
        Grob.group(
          Vector(
            Grob.group(
              Vector(grid(Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(1.2, 1.0))),
              viewport = Some(inner)
            )
          ),
          viewport = Some(middle)
        )
      )
    )
    assertCovered(compare(scene, hidpi), "nested rotations")
  }
