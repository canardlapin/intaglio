package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.event.{Event, EventType}
import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent, PickResult}
import _root_.javafx.scene.paint.Color
import java.awt.geom.{AffineTransform, Area, Point2D, Rectangle2D}

/** Image cells of a hand-built scene against the pixels the JavaFX host draws, and the host's hover
  * and selection cells.
  *
  * The pixel oracle: every cell of the raster has its own colour and is drawn without smoothing, so
  * the colour the host's base canvas shows at a pixel names the cell drawn there. Each snapshot
  * pixel's square, mapped to device pixels through the host's own mapping, is classified against
  * the visible region (the image quad and its clips, composed here with `StrictMath`). A pixel
  * wholly inside must be painted with exactly the cell `cellAt` reports at its centre; a pixel
  * wholly outside must report no cell, and in unrotated scenes must be unpainted. Pixels straddling
  * an edge are counted, not compared.
  *
  * Two allowances belong to the headless software pipeline these tests run on, measured here rather
  * than assumed: near a cell boundary it samples a nearest-neighbour image a fraction of a device
  * pixel away from the pixel centre, and blends the two texels there, so pixels whose centre is
  * within one device pixel of a cell boundary are counted, not compared; and under rotation it
  * paints the image's top and left edge texels beyond the rotated quad, so a rotated scene checks
  * the inside only. The exact, edge-by-edge comparison under rotation is
  * `ImageCellPixelOracleSuite` (Java2D) in the interaction module.
  */
class JavaFxImageCellSuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()

  private def fx[A](body: => A): A = FxToolkit.fx(body)
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)
  private def n(value: String): GraphicsName = GraphicsName.unsafe(value)

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

  private def grid(at: Point, size: Size): Grob =
    Grob.imageUnsafe(palette, at, size, name = Some(n("grid")))

  private val keys = ok(NamedInteraction.keySpace("cells"))

  private def namedView(scene: Scene, context: RenderContext) =
    ok(
      JavaFxInteractionView.named(
        scene,
        context,
        keys,
        SemanticId.unsafe("sheet"),
        ok(PlanRevision("v1"))
      )
    )

  /** The image, the device-to-image-frame map, and the device region left visible. */
  private final case class Frame(
      image: DevicePrimitive.Image,
      toLocal: AffineTransform,
      visible: Area
  )

  /** A rotation by `degrees` about the pivot, y down, built with `StrictMath`. */
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

  private def frameOf(scene: DeviceScene): Frame =
    def walk(
        elements: Vector[DeviceElement],
        transform: AffineTransform,
        clip: Option[Area]
    ): Option[Frame] =
      elements.iterator
        .map {
          case DeviceElement.Mark(image: DevicePrimitive.Image) =>
            val visible = new Area(
              transform.createTransformedShape(
                new Rectangle2D.Double(image.x, image.y, image.width, image.height)
              )
            )
            clip.foreach(visible.intersect)
            Some(Frame(image, transform.createInverse(), visible))
          case DeviceElement.Mark(_)                             => None
          case DeviceElement.Group(_, groupClip, rotation, kids) =>
            val next = new AffineTransform(transform)
            rotation.foreach(r => next.concatenate(rotationOf(r)))
            val nextClip = groupClip.fold(clip) { c =>
              val area =
                new Area(
                  next.createTransformedShape(new Rectangle2D.Double(c.x, c.y, c.width, c.height))
                )
              clip.foreach(area.intersect)
              Some(area)
            }
            walk(kids, next, nextClip)
          case DeviceElement.Annotated(_, kids) => walk(kids, transform, clip)
        }
        .collectFirst { case Some(frame) => frame }
    walk(scene.elements, new AffineTransform(), None).getOrElse(fail("the scene draws no image"))

  private final case class Tally(inside: Int, outside: Int, edge: Int, boundary: Int, beyond: Int)

  /** Compare every pixel of the host's base canvas with the reported cell. `exactOutside` asserts
    * that pixels wholly outside the visible region are unpainted; otherwise painted ones are
    * counted as `beyond`.
    */
  private def compare(
      scene: Scene,
      context: RenderContext,
      nodeWidth: Double,
      nodeHeight: Double,
      exactOutside: Boolean
  ): Tally =
    fx {
      val view = namedView(scene, context)
      val plan = view.names.getOrElse(fail("a named view carries its picking plan"))
      val frame = frameOf(view.deviceScene)
      val host = ok(JavaFxInteractionHost.attach(view))
      try
        host.node.resize(nodeWidth, nodeHeight)
        val base = host.node.getChildren.get(0).asInstanceOf[Canvas]
        val parameters = new SnapshotParameters()
        parameters.setFill(Color.TRANSPARENT)
        val image = base.snapshot(parameters, null)
        assertEquals((image.getWidth, image.getHeight), (nodeWidth, nodeHeight))
        val pixels = image.getPixelReader
        // The host's draw mapping: node-local = origin + device * scale.
        val (left, top) = ok(host.toLocal(DevicePoint(0, 0))).get
        val scale = ok(host.toLocal(DevicePoint(1, 0))).get._1 - left
        val cellWidth = frame.image.width / columns
        val cellHeight = frame.image.height / rows
        def nearCellBoundary(p: DevicePoint): Boolean =
          val local = frame.toLocal.transform(new Point2D.Double(p.x, p.y), null)
          val u = (local.getX - frame.image.x) / cellWidth
          val v = (local.getY - frame.image.y) / cellHeight
          math.abs(u - math.rint(u)) * cellWidth < 1 ||
          math.abs(v - math.rint(v)) * cellHeight < 1
        var tally = Tally(0, 0, 0, 0, 0)
        for py <- 0 until nodeHeight.toInt; px <- 0 until nodeWidth.toInt do
          val square =
            new Rectangle2D.Double((px - left) / scale, (py - top) / scale, 1 / scale, 1 / scale)
          val argb = pixels.getArgb(px, py)
          val centre = ok(host.toDevice(px + 0.5, py + 0.5))
          val reported = centre.flatMap(p => ok(plan.cellAt(n("grid"), p)))
          if frame.visible.contains(square) then
            if centre.forall(nearCellBoundary) then
              tally = tally.copy(boundary = tally.boundary + 1)
            else
              val painted = cellOf(argb)
              assert(painted.nonEmpty, clues(px, py, argb.toHexString))
              assertEquals(reported, painted, clues(px, py, centre))
              tally = tally.copy(inside = tally.inside + 1)
          else if !frame.visible.intersects(square) then
            assertEquals(reported, None, clues(px, py, centre))
            if exactOutside then assertEquals(argb >>> 24, 0, clues(px, py))
            else if (argb >>> 24) != 0 then tally = tally.copy(beyond = tally.beyond + 1)
            tally = tally.copy(outside = tally.outside + 1)
          else tally = tally.copy(edge = tally.edge + 1)
        tally
      finally host.dispose()
    }

  private val hidpi =
    RenderContext.unsafe(width = 360, height = 240, pixelsPerInch = 192, deviceScale = 2)

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

  /** Most of the image is compared, and boundaries and edges are a thin share of it. */
  private def assertCovered(tally: Tally, label: String): Unit =
    assert(tally.inside > 4000, clues(label, tally))
    assert(tally.outside > 1000, clues(label, tally))
    assert(tally.boundary < tally.inside / 2, clues(label, tally))
    assert(tally.edge < tally.inside / 10, clues(label, tally))

  test("nested viewports at device scale 2 agree pixel for pixel at several host scales") {
    val inner =
      Viewport.unsafe(origin = Point.npcUnsafe(0.12, 0.08), size = Size.npcUnsafe(0.71, 0.83))
    val scene = nested(grid(Point.npcUnsafe(0.47, 0.52), Size.npcUnsafe(0.87, 0.79)), inner)
    // One logical pixel per device pixel, an enlargement, and a letterboxed reduction.
    Vector((360.0, 240.0), (630.0, 420.0), (250.0, 200.0)).foreach { (w, h) =>
      assertCovered(compare(scene, hidpi, w, h, exactOutside = true), s"$w x $h")
    }
  }

  test("a clip that cuts the image leaves no cell, and no ink, where it cuts") {
    val inner = Viewport.unsafe(
      origin = Point.npcUnsafe(0.3, 0.25),
      size = Size.npcUnsafe(0.4, 0.5),
      clip = Clip.On
    )
    // Larger than its clipping viewport on every side.
    val scene = nested(grid(Point.npcUnsafe(0.45, 0.55), Size.npcUnsafe(1.7, 1.5)), inner)
    val tally = compare(scene, hidpi, 360, 240, exactOutside = true)
    assertCovered(tally, "clipped")
    assert(tally.outside > 360 * 240 / 2, clues(tally))
  }

  test("inside a rotated viewport the drawn cells are the reported cells") {
    Vector(Clip.Off, Clip.On).foreach { clip =>
      val inner = Viewport.unsafe(
        origin = Point.npcUnsafe(0.3, 0.15),
        size = Size.npcUnsafe(0.5, 0.6),
        clip = clip,
        angleDegrees = 27
      )
      val scene = nested(grid(Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(1.1, 0.9)), inner)
      assertCovered(compare(scene, hidpi, 360, 240, exactOutside = false), clip.toString)
    }
  }

  // ---- Host exposure: hover and selection ----

  private def mouse(
      host: JavaFxInteractionHost[?],
      kind: EventType[MouseEvent],
      x: Double,
      y: Double
  ): Unit =
    val p = host.node.localToScene(x, y)
    Event.fireEvent(
      host.node,
      new MouseEvent(
        kind,
        p.getX,
        p.getY,
        p.getX,
        p.getY,
        MouseButton.PRIMARY,
        1,
        false,
        false,
        false,
        false,
        kind == MouseEvent.MOUSE_PRESSED,
        false,
        false,
        false,
        false,
        true,
        new PickResult(host.node, p.getX, p.getY)
      )
    )

  private def click(host: JavaFxInteractionHost[?], x: Double, y: Double): Unit =
    mouse(host, MouseEvent.MOUSE_MOVED, x, y)
    mouse(host, MouseEvent.MOUSE_PRESSED, x, y)
    mouse(host, MouseEvent.MOUSE_RELEASED, x, y)
    mouse(host, MouseEvent.MOUSE_CLICKED, x, y)

  private def key(host: JavaFxInteractionHost[?], code: KeyCode): Unit =
    Event.fireEvent(
      host.node,
      new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)
    )

  /** A 7 x 5 sheet filling device box (40, 40)-(180, 140) — 20-pixel cells — beside a named dot. */
  private val sheetContext = RenderContext.unsafe(width = 240, height = 160)
  private val sheet = Scene(
    Vector(
      Grob.imageUnsafe(
        palette,
        Point.npcUnsafe(110.0 / 240, 1 - 90.0 / 160),
        Size.npcUnsafe(140.0 / 240, 100.0 / 160),
        name = Some(n("grid"))
      ),
      Grob.circleUnsafe(
        Point.npcUnsafe(215.0 / 240, 0.5),
        ExtentExpr.pointsUnsafe(6),
        GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black)),
        name = Some(n("dot"))
      )
    )
  )

  private def cell(row: Int, column: Int) = Some(NamedCell(n("grid"), ImageCell(row, column)))

  test("the sheet fixture places the image where the cell tests assume") {
    fx {
      val view = namedView(sheet, sheetContext)
      val image = frameOf(view.deviceScene).image
      assertEqualsDouble(image.x, 40, 1e-9)
      assertEqualsDouble(image.y, 40, 1e-9)
      assertEqualsDouble(image.width, 140, 1e-9)
      assertEqualsDouble(image.height, 100, 1e-9)
    }
  }

  test("hover reports the cell under the pointer and follows it within the image") {
    fx {
      val host = ok(JavaFxInteractionHost.attach(namedView(sheet, sheetContext)))
      host.node.resize(240, 160)
      var events = Vector.empty[JavaFxCellEvent]
      ok(host.subscribeCells(event => events = events :+ event))
      mouse(host, MouseEvent.MOUSE_MOVED, 45, 45)
      assertEquals(ok(host.hoveredCell), cell(0, 0))
      mouse(host, MouseEvent.MOUSE_MOVED, 47, 46)
      mouse(host, MouseEvent.MOUSE_MOVED, 175, 135)
      assertEquals(ok(host.hoveredCell), cell(4, 6))
      // The cell boundary at x = 60 belongs to the next column.
      mouse(host, MouseEvent.MOUSE_MOVED, 60, 70)
      assertEquals(ok(host.hoveredCell), cell(1, 1))
      // Another name has no cell; leaving the plot clears it.
      mouse(host, MouseEvent.MOUSE_MOVED, 215, 80)
      assertEquals(ok(host.state).hover.nonEmpty, true)
      assertEquals(ok(host.hoveredCell), None)
      mouse(host, MouseEvent.MOUSE_MOVED, 100, 100)
      mouse(host, MouseEvent.MOUSE_EXITED, 100, 100)
      assertEquals(ok(host.hoveredCell), None)
      assertEquals(
        events,
        Vector(
          JavaFxCellEvent.Hovered(cell(0, 0)),
          JavaFxCellEvent.Hovered(cell(4, 6)),
          JavaFxCellEvent.Hovered(cell(1, 1)),
          JavaFxCellEvent.Hovered(None),
          JavaFxCellEvent.Hovered(cell(3, 3)),
          JavaFxCellEvent.Hovered(None)
        ),
        "a move within one cell is not news"
      )
      host.dispose()
    }
  }

  test("a hover within the pointer tolerance but outside the image has no cell") {
    fx {
      val host = ok(JavaFxInteractionHost.attach(namedView(sheet, sheetContext)))
      host.node.resize(240, 160)
      mouse(host, MouseEvent.MOUSE_MOVED, 38, 80)
      val hovered = ok(host.state).hover
      assert(hovered.nonEmpty, "the default 4-pixel tolerance reaches the image")
      assertEquals(ok(host.hoveredCell), None)
      host.dispose()
    }
  }

  test("a click selects the image by name and records the clicked cell until it is deselected") {
    fx {
      val host = ok(JavaFxInteractionHost.attach(namedView(sheet, sheetContext)))
      host.node.resize(240, 160)
      var events = Vector.empty[JavaFxCellEvent]
      ok(host.subscribeCells(event => events = events :+ event))
      click(host, 105, 95) // column 3, row 2
      assertEquals(ok(host.state).selection.entities.map(_.value), Set(n("grid")))
      assertEquals(ok(host.selectedCell), cell(2, 3))
      click(host, 125, 55) // column 4, row 0
      assertEquals(ok(host.selectedCell), cell(0, 4))
      // Escape clears the selection, and with it the cell.
      key(host, KeyCode.ESCAPE)
      assertEquals(ok(host.state).selection.entities, Set.empty)
      assertEquals(ok(host.selectedCell), None)
      click(host, 45, 135)
      assertEquals(ok(host.selectedCell), cell(4, 0))
      // A projected selection of another name drops it too.
      ok(host.setSelection(Selection(Set(ok(keys.entity(n("dot")))))))
      assertEquals(ok(host.selectedCell), None)
      // A click on the dot selects it and records no cell.
      click(host, 215, 80)
      assertEquals(ok(host.selectedCell), None)
      assertEquals(
        events.collect { case e: JavaFxCellEvent.Selected => e },
        Vector(
          JavaFxCellEvent.Selected(cell(2, 3)),
          JavaFxCellEvent.Selected(cell(0, 4)),
          JavaFxCellEvent.Selected(None),
          JavaFxCellEvent.Selected(cell(4, 0)),
          JavaFxCellEvent.Selected(None)
        )
      )
      host.dispose()
    }
  }

  test("disposing reports no cell, and a plot's view never reports one") {
    fx {
      val host = ok(JavaFxInteractionHost.attach(namedView(sheet, sheetContext)))
      host.node.resize(240, 160)
      var events = Vector.empty[JavaFxCellEvent]
      ok(host.subscribeCells(event => events = events :+ event))
      click(host, 105, 95)
      host.dispose()
      assertEquals(
        events.takeRight(2),
        Vector(JavaFxCellEvent.Hovered(None), JavaFxCellEvent.Selected(None))
      )
      assert(host.hoveredCell.isLeft)
    }
    fx {
      val context = RenderContext.unsafe(320, 240)
      val space = ok(KeySpace("obs", KeyCodec.integer))
      val plan = ok(
        InteractionCompiler.compile(
          ok(Plot(Vector(1, 2, 3)).addLayer(Layer.point[Int](_.toDouble, _.toDouble))),
          space,
          ok(DataRevision("d1")),
          SemanticId.unsafe("plot"),
          ok(PlanRevision("v1")),
          PlotCompilerOptions.lean.copy(renderContext = Some(context))
        )(identity)
      )
      val view = ok(JavaFxInteractionView.compile(plan, context))
      assertEquals(view.names, None)
      val host = ok(JavaFxInteractionHost.attach(view))
      host.node.resize(320, 240)
      val anchor = ok(host.toLocal(view.navigation.targets.head.anchor)).get
      click(host, anchor._1, anchor._2)
      assertEquals(ok(host.hoveredCell), None)
      assertEquals(ok(host.selectedCell), None)
      host.dispose()
    }
  }
