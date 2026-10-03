package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.application.Platform
import _root_.javafx.event.Event
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.paint.Color
import _root_.javafx.stage.Stage
import _root_.javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent, PickResult}
import java.lang.ref.WeakReference
import java.nio.file.{Files, Path}
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class JavaFxInteractionHostSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)
  private def fx[A](body: => A): A = FxToolkit.fx(body)

  override def beforeAll(): Unit = FxToolkit.start()

  private def raster(color: Int, width: Int = 8, height: Int = 8): RasterImage =
    RasterImage.solid(
      RasterDimensions.unsafe(width, height),
      Rgba32.unsafe(color & 255, (color >>> 8) & 255, (color >>> 16) & 255)
    )

  private def pattern(color: Rgba): PatternPaint =
    PatternPaint(ok(PatternRecipe.stipple(8.0, 2.0)), color)

  test("1000 changing rasters stay within the shared byte budget") {
    fx {
      val canvas = new Canvas(8, 8)
      val adapter = new JavaFxCanvasContext(canvas.getGraphicsContext2D, 2048L)
      (0 until 1000).foreach { index =>
        adapter.drawImage(raster(index), 0, 0, 8, 8)
        assert(adapter.cachedResourceBytes <= adapter.cacheByteLimit)
        assert(adapter.cachedResourceCount <= 2)
      }
      assertEquals(adapter.cachedResourceBytes, 1536L)
      assertEquals(adapter.cachedResourceCount, 2)
      val output = canvas.snapshot(null, null)
      assertEquals(output.getPixelReader.getArgb(4, 4), 0xffe70300)
      adapter.release()
    }
  }

  test("raster hits refresh LRU order and patterns compete for the same budget") {
    fx {
      val canvas = new Canvas(8, 8)
      val adapter = new JavaFxCanvasContext(canvas.getGraphicsContext2D, 1536L)
      val first = raster(1)
      val second = raster(2)
      adapter.drawImage(first, 0, 0, 8, 8)
      val firstNative = adapter.cachedNativeImages.head
      adapter.drawImage(second, 0, 0, 8, 8)
      val secondNative = adapter.cachedNativeImages.find(_ ne firstNative).get
      adapter.drawImage(first, 0, 0, 8, 8)
      adapter.drawImage(raster(3), 0, 0, 8, 8)
      assert(adapter.cachedNativeImages.exists(_ eq firstNative))
      assert(!adapter.cachedNativeImages.exists(_ eq secondNative))

      val hatch = pattern(Rgba.Black)
      assert(!adapter.setPatternFill(hatch))
      assert(!adapter.cachedNativeImages.exists(_ eq firstNative))
      assertEquals(adapter.cachedResourceBytes, 1280L)
      assert(adapter.setPatternFill(hatch))
      assertEquals(adapter.cachedResourceCount, 2)
      canvas.snapshot(null, null)
      adapter.clearCaches()
    }
  }

  test("zero or undersized budgets draw correctly without retaining large resources") {
    fx {
      Vector(0L, 512L).foreach { budget =>
        val canvas = new Canvas(8, 8)
        val adapter = new JavaFxCanvasContext(canvas.getGraphicsContext2D, budget)
        adapter.drawImage(raster(255), 0, 0, 8, 8)
        assertEquals(adapter.cachedResourceCount, 0)
        assertEquals(adapter.cachedResourceBytes, 0L)
        assertEquals(canvas.snapshot(null, null).getPixelReader.getArgb(4, 4), 0xffff0000)
        adapter.release()
      }
      intercept[IllegalArgumentException] {
        new JavaFxCanvasContext(new Canvas(8, 8).getGraphicsContext2D, -1L)
      }
    }
  }

  test("release makes native images collectible while the adapter and Canvas remain alive") {
    val (adapter, canvas, references) = fx {
      val canvas = new Canvas(8, 8)
      val adapter = new JavaFxCanvasContext(canvas.getGraphicsContext2D)
      adapter.drawImage(raster(255), 0, 0, 8, 8)
      adapter.setPatternFill(pattern(Rgba.Black))
      adapter.rect(0, 0, 8, 8)
      adapter.fillPath()
      canvas.snapshot(null, null)
      val references = adapter.cachedNativeImages.map(image => new WeakReference(image))
      assertEquals(references.size, 2)
      adapter.release()
      adapter.release()
      assertEquals(adapter.cachedResourceBytes, 0L)
      assertEquals(adapter.cachedResourceCount, 0)
      assertEquals(canvas.getGraphicsContext2D.getFill, Color.BLACK)
      canvas.snapshot(null, null)
      (adapter, canvas, references)
    }
    var attempts = 0
    while references.exists(_.get() != null) && attempts < 20 do
      System.gc()
      Thread.sleep(25)
      fx(())
      attempts += 1
    assert(references.forall(_.get() == null), "released native images must be collectible")
    fx {
      adapter.drawImage(raster(1), 0, 0, 8, 8)
      assertEquals(adapter.cachedResourceCount, 1)
      canvas.snapshot(null, null)
      adapter.clearCaches()
      assertEquals(adapter.cachedResourceBytes, 0L)
    }
  }

  test("release clears an evicted pattern restored by balanced save and restore") {
    fx {
      val canvas = new Canvas(8, 8)
      val graphics = canvas.getGraphicsContext2D
      val adapter = new JavaFxCanvasContext(graphics, 512L)
      val first = pattern(Rgba.Black)
      adapter.setPatternFill(first)
      val restored = graphics.getFill match
        case value: _root_.javafx.scene.paint.ImagePattern => value
        case other => fail(s"expected a pattern, received $other")
      adapter.save()
      adapter.setPatternFill(pattern(Rgba.White))
      adapter.restore()
      assert(graphics.getFill eq restored)
      assert(!adapter.cachedNativeImages.exists(_ eq restored.getImage))
      adapter.release()
      assertEquals(graphics.getFill, Color.BLACK)
      // Application-owned paint remains the application's responsibility.
      graphics.setFill(restored)
      adapter.release()
      assert(graphics.getFill eq restored)
      graphics.setFill(Color.BLACK)
    }
  }

  test("over-limit rasters return dimensions through compile and render without drawing") {
    val limit = JavaFxCanvasContext.MaxRasterDimension
    Vector((limit + 1, 1), (1, limit + 1)).foreach { (width, height) =>
      val image = Grob.imageUnsafe(
        raster(1, width, height),
        Point.npcUnsafe(0.5, 0.5),
        Size.npcUnsafe(1, 1)
      )
      val scene = Scene(Vector(image))
      val expected = JavaFxRenderError.RasterTooLarge(width, height, limit)
      assertEquals(JavaFxRenderer.compile(scene).left.toOption, Some(expected))
      val recording = new RecordingFxContext
      assertEquals(JavaFxRenderer.render(scene, recording).left.toOption, Some(expected))
      assert(recording.calls.isEmpty)
      assert(expected.message.contains(s"${width}x$height"))
    }
    val boundary = Grob.imageUnsafe(
      raster(1, limit, 1),
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(1, 1)
    )
    assert(JavaFxRenderer.compile(Scene(Vector(boundary))).isRight)
    fx {
      val adapter = new JavaFxCanvasContext(new Canvas(8, 8).getGraphicsContext2D)
      intercept[IllegalArgumentException] {
        adapter.drawImage(raster(1, limit + 1, 1), 0, 0, 8, 8)
      }
      assertEquals(adapter.cachedResourceCount, 0)
    }
  }

  test("native context sets the shared miter limit instead of inheriting toolkit defaults") {
    fx {
      val context = new Canvas(100, 100).getGraphicsContext2D
      context.setMiterLimit(10)
      new JavaFxCanvasContext(context).setLineJoin(LineJoin.Miter)
      assertEquals(context.getMiterLimit, 4.0)
    }
  }

  test(
    "mounted headless window has output scale two and routes child events through one focus stop"
  ) {
    val view = prepared(2)
    val (host, stage) = fx {
      val host = ok(JavaFxInteractionHost.attach(view))
      val stage = new Stage()
      stage.setScene(new _root_.javafx.scene.Scene(host.node, 400, 300))
      stage.show()
      stage.requestFocus()
      host.node.requestFocus()
      (host, stage)
    }
    try
      fx {
        assertEquals(stage.getOutputScaleX, 2.0)
        assertEquals(stage.getOutputScaleY, 2.0)
        assertEquals(stage.getScene.getFocusOwner, host.node)
        val mark = view.navigation.targets.find(_.target.entity.exists(_.value == 4)).get
        val x = mark.anchor.x / 2
        val y = mark.anchor.y / 2
        val child = host.node.getChildren.get(0)
        Event.fireEvent(
          child,
          new MouseEvent(
            MouseEvent.MOUSE_CLICKED,
            x,
            y,
            x,
            y,
            MouseButton.PRIMARY,
            1,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            new PickResult(child, x, y)
          )
        )
        assertEquals(ok(host.state).selection.entities.map(_.value), Set(4))
        Event.fireEvent(
          stage.getScene.getFocusOwner,
          new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.HOME, false, false, false, false)
        )
        key(host, KeyCode.ENTER)
        assertEquals(ok(host.state).selection.entities.map(_.value), Set(0))
        assert(ink(host) > 0)
      }
    finally fx { ok(host.dispose()); stage.close() }
  }

  private def prepared(
      scale: Int = 1,
      tiles: Boolean = false,
      count: Int = 6,
      positions: Option[Vector[(Double, Double)]] = None
  ): JavaFxInteractionView[Int] =
    val columns = if count == 10000 then 100 else 3
    val rows = (0 until count).toVector
    def x(i: Int): Double = positions.fold((i % columns).toDouble)(_(i)._1)
    def y(i: Int): Double = positions.fold((i / columns).toDouble)(_(i)._2)
    val layer = if tiles then Layer.tile[Int](x, y, _ => 0.8, _ => 0.8)
    else Layer.point[Int](x, y, params = Some(GraphicParams.unsafe(fill = Some(Rgba.Black))))
    val plot = ok(Plot(rows).addLayer(layer))
    val context = RenderContext.unsafe(
      400 * scale,
      300 * scale,
      pixelsPerInch = 96.0 * scale,
      deviceScale = scale.toDouble
    )
    val plan = ok(
      InteractionCompiler.compile(
        plot,
        ok(KeySpace("marks", KeyCodec.integer)),
        ok(DataRevision("one")),
        SemanticId.unsafe("host-test"),
        ok(PlanRevision("one")),
        PlotCompilerOptions.lean.copy(renderContext = Some(context))
      )(identity)
    )
    ok(JavaFxInteractionView.compile(plan, context))

  private def mouse(
      host: JavaFxInteractionHost[?],
      kind: _root_.javafx.event.EventType[MouseEvent],
      x: Double,
      y: Double
  ): Unit =
    Event.fireEvent(
      host.node,
      new MouseEvent(
        kind,
        x,
        y,
        x,
        y,
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
        new PickResult(host.node, x, y)
      )
    )

  private def key(host: JavaFxInteractionHost[?], code: KeyCode): Unit =
    Event.fireEvent(
      host.node,
      new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)
    )

  private def ink(host: JavaFxInteractionHost[?]): Int =
    val canvas = host.node.getChildren.get(1).asInstanceOf[Canvas]
    val parameters = new SnapshotParameters()
    parameters.setFill(Color.TRANSPARENT)
    val image = canvas.snapshot(parameters, null)
    var n = 0
    for y <- 0 until image.getHeight.toInt; x <- 0 until image.getWidth.toInt do
      if (image.getPixelReader.getArgb(x, y) >>> 24) != 0 then n += 1
    n

  test("real FX pointer events use the draw transform at 1x and 2x, including resize") {
    for scale <- Vector(1, 2) do
      val view = prepared(scale)
      fx {
        val host = ok(JavaFxInteractionHost.attach(view))
        var events = Vector.empty[EventRecord[Int]]
        ok(host.subscribe(e => events :+= e))
        val mark = view.navigation.targets.find(_.target.entity.exists(_.value == 4)).get
        val x = mark.anchor.x / scale
        val y = mark.anchor.y / scale
        val before = host.profile.baseDraws
        val nativeMapped = ok(
          ok(view.deviceScene.frame(GraphicsName.unsafe("plot-panel")))
            .nativeToDevice(DevicePoint(1, 1))
        )
        assert(nativeMapped.x.isFinite && nativeMapped.y.isFinite)
        assertEquals(
          ok(host.pickNative(GraphicsName.unsafe("plot-panel"), DevicePoint(1, 1)))
            .flatMap(_.target.entity)
            .map(_.value),
          Some(4)
        )
        mouse(host, MouseEvent.MOUSE_MOVED, x, y)
        assertEquals(ok(host.state).hover, Some(mark.target.id))
        mouse(host, MouseEvent.MOUSE_PRESSED, x, y)
        mouse(host, MouseEvent.MOUSE_DRAGGED, x, y)
        mouse(host, MouseEvent.MOUSE_RELEASED, x, y)
        mouse(host, MouseEvent.MOUSE_CLICKED, x, y)
        assertEquals(ok(host.state).selection.entities.map(_.value), Set(4))
        assert(events.exists(_.event.isInstanceOf[InteractionEvent.Activated[?]]))
        assert(events.forall(_.stamp.cause == InputCause.Pointer))
        assertEquals(host.profile.baseDraws, before)
        host.node.resize(800, 300)
        mouse(host, MouseEvent.MOUSE_MOVED, 200 + x, y)
        assertEquals(ok(host.state).hover, Some(mark.target.id))
        assertEquals(ok(host.toDevice(20, 20)), None)
        assert(ink(host) > 0)
        mouse(host, MouseEvent.MOUSE_EXITED, 0, 0)
        assertEquals(ok(host.state).hover, None)
        ok(host.dispose())
        ok(host.dispose())
      }
  }

  test("a real FX pointer at a hollow point's centre hovers it under the default policy") {
    val hollow = GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = None, lineWidth = 2)
    val context = RenderContext.unsafe(400, 300, pixelsPerInch = 96.0)
    for options <- Vector(PlotCompilerOptions.default, PlotCompilerOptions.lean) do
      val rows = Vector(0, 1, 2, 3, 4)
      val plan = ok(
        InteractionCompiler.compile(
          ok(Plot(rows).addLayer(Layer.point[Int](_.toDouble, _.toDouble, params = Some(hollow)))),
          ok(KeySpace("marks", KeyCodec.integer)),
          ok(DataRevision("one")),
          SemanticId.unsafe("hollow-host"),
          ok(PlanRevision("one")),
          options.copy(renderContext = Some(context))
        )(identity)
      )
      val view = ok(JavaFxInteractionView.compile(plan, context))
      val mark = view.navigation.targets.find(_.target.entity.exists(_.value == 2)).get
      val x = (mark.left + mark.right) / 2
      val y = (mark.top + mark.bottom) / 2
      val outlineOnly = ok(
        Picking.fromResolved(
          view.deviceScene,
          plan.groups,
          context,
          PickPolicy.default.withHollowPoints(HollowPicking.Outline)
        )
      )
      assertEquals(ok(outlineOnly.hits(DevicePoint(x, y))), Vector.empty, "outline-only control")
      fx {
        val host = ok(JavaFxInteractionHost.attach(view, toleranceLogicalPx = 0))
        mouse(host, MouseEvent.MOUSE_MOVED, x, y)
        assertEquals(ok(host.state).hover, Some(mark.target.id))
        mouse(host, MouseEvent.MOUSE_PRESSED, x, y)
        mouse(host, MouseEvent.MOUSE_RELEASED, x, y)
        mouse(host, MouseEvent.MOUSE_CLICKED, x, y)
        assertEquals(ok(host.state).selection.entities.map(_.value), Set(2))
        ok(host.dispose())
      }
      val outlineView = ok(
        JavaFxInteractionView.compile(
          plan,
          context,
          PickPolicy.default.withHollowPoints(HollowPicking.Outline)
        )
      )
      fx {
        val host = ok(JavaFxInteractionHost.attach(outlineView, toleranceLogicalPx = 0))
        mouse(host, MouseEvent.MOUSE_MOVED, x, y)
        assertEquals(ok(host.state).hover, None, "the explicit Outline policy misses the centre")
        mouse(host, MouseEvent.MOUSE_MOVED, mark.right - 0.25, y)
        assertEquals(ok(host.state).hover, Some(mark.target.id), "and still hits the outline")
        ok(host.dispose())
      }
  }

  test("projected selection and hover redraw visible overlay without emitting user events") {
    val view = prepared()
    fx {
      val host = ok(JavaFxInteractionHost.attach(view))
      var events = 0
      ok(host.subscribe(_ => events += 1))
      val mark = view.navigation.targets.head
      val before = host.profile
      ok(host.setSelection(Selection(mark.target.entity.toSet)))
      ok(host.setHover(Some(mark.target.id)))
      assertEquals(events, 0)
      assert(ink(host) > 0)
      assertEquals(host.profile.baseDraws, before.baseDraws)
      assert(host.profile.overlayDraws > before.overlayDraws)
      ok(host.setSelection(Selection[Int]()))
      ok(host.setHover(None))
      assertEquals(ink(host), 0)
      ok(host.dispose())
    }
  }

  test("keyboard alone can select every mark in scatter and tile fixtures") {
    for tiles <- Vector(false, true) do
      val view = prepared(tiles = tiles)
      fx {
        val host = ok(JavaFxInteractionHost.attach(view))
        var reached = Set.empty[Int]
        key(host, KeyCode.RIGHT)
        // Two rows of three marks: traverse both directions from every reached state.
        def visit(depth: Int): Unit =
          key(host, KeyCode.ENTER)
          reached ++= ok(host.state).selection.entities.map(_.value)
          if depth > 0 then
            Vector(KeyCode.LEFT, KeyCode.RIGHT, KeyCode.UP, KeyCode.DOWN).foreach { code =>
              key(host, code)
              visit(depth - 1)
            }
        visit(3)
        assertEquals(reached, (0 until 6).toSet)
        assert(host.node.getAccessibleText.startsWith("marks:"))
        key(host, KeyCode.ESCAPE)
        assertEquals(ok(host.state).selection.size, 0)
        assert(ink(host) > 0, "focus remains visible after selection clears")
        ok(host.dispose())
      }
  }

  test("sequential keys reach marks stranded by directional nearest on an irregular scatter") {
    val view =
      prepared(count = 4, positions = Some(Vector((10d, 11d), (4d, 5d), (6d, 10d), (10d, 6d))))
    fx {
      val host = ok(JavaFxInteractionHost.attach(view))
      var reached = Set.empty[Int]
      key(host, KeyCode.HOME)
      for _ <- 0 until 4 do
        key(host, KeyCode.ENTER)
        reached ++= ok(host.state).selection.entities.map(_.value)
        key(host, KeyCode.PAGE_DOWN)
      assertEquals(reached, Set(0, 1, 2, 3))
      key(host, KeyCode.END)
      for _ <- 0 until 3 do key(host, KeyCode.PAGE_UP)
      assertEquals(ok(host.state).focus, Some(view.navigation.targets.head.target.id))
      ok(host.dispose())
    }
  }

  test("native canvas paints a cased hollow circle point with the cased path's casing band") {
    val casedMark = GraphicParams
      .unsafe(stroke = Some(Rgba.unsafe(24, 94, 180)), lineWidth = 2.0)
      .withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(4.0)))
    def masks(grob: Grob): (Set[(Int, Int)], Set[(Int, Int)], Int) =
      val program = JavaFxRenderer
        .compile(Scene(Vector(grob)), JavaFxOptions.unsafe(width = 80, height = 80))
        .fold(error => fail(error.message), identity)
      fx {
        val canvas = new Canvas(80, 80)
        JavaFxRenderer.draw(program, new JavaFxCanvasContext(canvas.getGraphicsContext2D))
        val parameters = new SnapshotParameters()
        parameters.setFill(Color.BLACK)
        val reader = canvas.snapshot(parameters, null).getPixelReader
        val pixels =
          for y <- 0 until 80; x <- 0 until 80 yield
            val argb = reader.getArgb(x, y)
            ((x, y), (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255)
        val casing = pixels.collect { case (p, r, g, b) if r > 200 && g > 200 && b > 200 => p }
        val stroke = pixels.collect { case (p, r, _, b) if b > 140 && r < 120 => p }
        (casing.toSet, stroke.toSet, reader.getArgb(40, 40) & 0xffffff)
      }
    val point = ok(
      Grob.points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        size = ExtentExpr.npcUnsafe(0.25),
        gp = casedMark
      )
    )
    val ring = (0 until 720).toVector.map { index =>
      val angle = index * 2.0 * math.Pi / 720.0
      Point.npcUnsafe(0.5 + 0.25 * math.cos(angle), 0.5 + 0.25 * math.sin(angle))
    }
    // Round joins: a mitred many-segment ring spikes outward at sub-pixel segments, which is a
    // property of the polygon oracle, not of casing.
    val path = ok(
      Grob.polygon(
        ring,
        gp = GraphicParams
          .unsafe(
            stroke = Some(Rgba.unsafe(24, 94, 180)),
            lineWidth = 2.0,
            lineJoin = LineJoin.Round
          )
          .withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(4.0)))
      )
    )
    // Radius 20 px, stroke 2 px, casing 8 px: casing band 16..24 px from the centre, stroke band
    // 19..21 px. The cased point and the cased path must both realise it to a device pixel.
    def distance(p: (Int, Int)) = math.hypot(p._1 + 0.5 - 40.0, p._2 + 0.5 - 40.0)
    val pixels = for y <- 0 until 80; x <- 0 until 80 yield (x, y)
    for (label, grob) <- Vector("point" -> point, "path" -> path) do
      val (casing, stroke, centre) = masks(grob)
      assert(casing.size > 300, s"$label native casing band visible: ${casing.size} pixels")
      assertEquals(
        casing.filter(p => distance(p) < 15.0 || distance(p) > 25.0),
        Set.empty[(Int, Int)],
        clue(label)
      )
      assertEquals(
        stroke.filter(p => math.abs(distance(p) - 20.0) > 2.0),
        Set.empty[(Int, Int)],
        clue(label)
      )
      assertEquals(
        pixels.filter { p =>
          val d = distance(p)
          ((d >= 17.0 && d <= 18.0) || (d >= 22.0 && d <= 23.0)) && !casing.contains(p)
        }.toVector,
        Vector.empty,
        clue(label)
      )
      assertEquals(
        pixels.filter(p => math.abs(distance(p) - 20.0) <= 0.3 && !stroke.contains(p)).toVector,
        Vector.empty,
        clue(label)
      )
      assertEquals(centre, 0x000000, s"$label stays hollow")
    val (plainCasing, _, _) = masks(
      ok(
        Grob.points(
          Vector(Point.npcUnsafe(0.5, 0.5)),
          size = ExtentExpr.npcUnsafe(0.25),
          gp = casedMark.withoutCasing
        )
      )
    )
    assertEquals(plainCasing, Set.empty[(Int, Int)])
  }

  test("native text plates include glyph overhangs for every anchor") {
    val evidence = Path.of("target", "feature-evidence", "javafx-text-plate-overhang")
    Files.createDirectories(evidence)
    for
      family <- Vector(Some("Serif"), None, Some("Intaglio No Such Face"))
      label <- Vector("j", "f", "fj", "Wj", "A\u0301", "", "  ")
      horizontal <- HJust.values
      vertical <- VJust.values
    do
      val anchor = Anchor(horizontal, vertical)
      val gp = GraphicParams.unsafe(
        stroke = None,
        fill = Some(Rgba.Black),
        fontFamily = family,
        fontSize = Length.pointsUnsafe(144)
      )
      val plateParams = gp
        .withSolidFill(Some(Rgba.unsafe(0, 0, 0, 0)))
        .withTextPlate(
          TextPlate(Rgba.unsafe(0, 160, 0), StrokeWidth.devicePixelsUnsafe(4))
        )
      def pixels(params: GraphicParams): Vector[Int] = fx {
        val text = ok(Grob.text(label, Point.npcUnsafe(0.5, 0.5), anchor = anchor, gp = params))
        val options = JavaFxOptions.unsafe(width = 640, height = 480, pixelsPerInch = 72)
        val program = ok(JavaFxRenderer.compile(Scene(Vector(text)), options))
        val canvas = new Canvas(640, 480)
        JavaFxRenderer.draw(program, new JavaFxCanvasContext(canvas.getGraphicsContext2D))
        val parameters = new SnapshotParameters()
        parameters.setFill(Color.TRANSPARENT)
        val reader = canvas.snapshot(parameters, null).getPixelReader
        (for y <- 0 until 480; x <- 0 until 640 yield reader.getArgb(x, y)).toVector
      }
      val ink = pixels(gp)
      val plate = pixels(plateParams)
      if family.contains("Serif") && label == "j" && anchor == Anchor.BottomLeft then
        for (name, argb) <- Vector("ink" -> ink, "plate" -> plate) do
          val image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB)
          image.setRGB(0, 0, 640, 480, argb.toArray, 0, 640)
          ImageIO.write(image, "png", evidence.resolve(s"serif-j-$name.png").toFile)
      val inkPoints = ink.zipWithIndex.collect {
        case (argb, index) if (argb >>> 24) >= 128 => (index % 640, index / 640)
      }
      val platePoints = plate.zipWithIndex.collect {
        case (argb, index) if (argb >>> 24) > 0 => (index % 640, index / 640)
      }
      def bounds(points: Seq[(Int, Int)]) =
        (points.map(_._1).min, points.map(_._2).min, points.map(_._1).max, points.map(_._2).max)
      val outside = ink.zip(plate).count { case (i, p) => (i >>> 24) >= 128 && (p >>> 24) == 0 }
      assert(platePoints.nonEmpty, (family, label, anchor))
      if label.isBlank then assertEquals(inkPoints, Vector.empty, (family, label, anchor))
      else
        assert(inkPoints.nonEmpty, (family, label, anchor))
        val (pl, pt, pr, pb) = bounds(platePoints)
        val (il, it, ir, ib) = bounds(inkPoints)
        assert(
          il - pl >= 3 && pr - ir >= 3 && it - pt >= 3 && pb - ib >= 3,
          (family, label, anchor, outside, (pl, pt, pr, pb), (il, it, ir, ib))
        )
      assertEquals(outside, 0, (family, label, anchor))
  }

  test("native canvas sizes a text plate from JavaFX's own layout, fallback face included") {
    for
      family <- Vector(None, Some("Intaglio No Such Face"))
      anchor <- Vector(Anchor.Center, Anchor.BottomLeft)
    do
      val gp = GraphicParams
        .unsafe(stroke = None, fill = Some(Rgba.Black), fontFamily = family)
        .withTextPlate(TextPlate(Rgba.unsafe(0, 160, 0), StrokeWidth.devicePixelsUnsafe(4.0)))
      val text = ok(Grob.text("Plate Wg", Point.npcUnsafe(0.5, 0.5), anchor = anchor, gp = gp))
      val program = JavaFxRenderer
        .compile(Scene(Vector(text)), JavaFxOptions.unsafe(width = 240, height = 80))
        .fold(error => fail(error.message), identity)
      val (plate, ink) = fx {
        val canvas = new Canvas(240, 80)
        JavaFxRenderer.draw(program, new JavaFxCanvasContext(canvas.getGraphicsContext2D))
        val parameters = new SnapshotParameters()
        parameters.setFill(Color.TRANSPARENT)
        val reader = canvas.snapshot(parameters, null).getPixelReader
        val pixels = for y <- 0 until 80; x <- 0 until 240 yield (x, y, reader.getArgb(x, y))
        (
          // Glyph ink must not enlarge the plate's measured bounds.
          pixels.collect {
            case (x, y, argb) if (argb >>> 24) > 0 && ((argb >>> 8) & 0xff) > 100 => (x, y)
          },
          pixels.collect {
            case (x, y, argb) if (argb >>> 24) == 0xff && ((argb >>> 8) & 0xff) < 100 => (x, y)
          }
        )
      }
      def bounds(points: Seq[(Int, Int)]) =
        (points.map(_._1).min, points.map(_._2).min, points.map(_._1).max, points.map(_._2).max)
      assert(ink.nonEmpty, clue((family, anchor)))
      val (pl, pt, pr, pb) = bounds(plate)
      val (il, it, ir, ib) = bounds(ink)
      assert(
        il - pl >= 3 && pr - ir >= 3 && it - pt >= 3 && pb - ib >= 3,
        ((family, anchor), (pl, pt, pr, pb), (il, it, ir, ib))
      )
      assert(il - pl <= 8 && pr - ir <= 8, ((family, anchor), (pl, pr), (il, ir)))
  }

  test("focus outline is painted last over an overlapping hovered target") {
    val view = prepared(count = 2, positions = Some(Vector((0d, 0d), (0d, 0d))))
    fx {
      val host = ok(JavaFxInteractionHost.attach(view))
      key(host, KeyCode.HOME)
      val focused = view.navigation.targets.head
      val hovered = view.navigation.targets(1)
      ok(host.setHover(Some(hovered.target.id)))
      val parameters = new SnapshotParameters()
      parameters.setFill(Color.TRANSPARENT)
      val image = host.node.getChildren.get(1).asInstanceOf[Canvas].snapshot(parameters, null)
      val x = math.round(focused.right + 3).toInt
      val y = math.round(focused.anchor.y).toInt
      val argb = image.getPixelReader.getArgb(x, y)
      val red = (argb >>> 16) & 255
      assertEquals(argb >>> 24, 255)
      assertEquals((argb >>> 8) & 255, red)
      assertEquals(argb & 255, red)
      assert(red > 128, "opaque neutral focus casing covers orange hover, allowing antialiasing")
      ok(host.dispose())
    }
  }

  test("1000 attach/dispose cycles release handlers, subscriptions and native caches") {
    val view = prepared()
    val retained = fx {
      (0 until 1000).map { _ =>
        val host = ok(JavaFxInteractionHost.attach(view))
        val node = host.node
        val weak = new WeakReference(host)
        ok(host.subscribe(_ => fail("disposed subscription invoked")))
        ok(host.dispose())
        assertEquals(node.getChildren.size(), 0)
        key(host, KeyCode.RIGHT)
        assertEquals(host.state.left.toOption, Some(JavaFxHostError.Disposed))
        (node, weak)
      }.toVector
    }
    var attempts = 0
    while retained.exists(_._2.get() != null) && attempts < 20 do
      System.gc()
      Thread.sleep(25)
      fx(())
      attempts += 1
    assertEquals(
      retained.count(_._2.get() != null),
      0,
      "nodes must not retain disposed hosts through listeners"
    )
  }

  private def namedScene: Scene =
    val ink = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
    Scene(
      Grob
        .rectUnsafe(Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(1, 1), gp = GraphicParams.unsafe()) +:
        (0 until 6).toVector.map { i =>
          Grob.circleUnsafe(
            Point.npcUnsafe(0.2 + (i % 3) * 0.3, 0.3 + (i / 3) * 0.4),
            ExtentExpr.pointsUnsafe(8),
            ink,
            name = Some(GraphicsName.unsafe(s"mark-$i"))
          )
        }
    )

  test("a hand-built scene is hosted with the names NamedPicking reports, at 1x and 2x") {
    for scale <- Vector(1, 2) do
      val context = RenderContext.unsafe(
        400 * scale,
        300 * scale,
        pixelsPerInch = 96.0 * scale,
        deviceScale = scale.toDouble
      )
      val keys = ok(NamedInteraction.keySpace("marks"))
      val view = ok(
        JavaFxInteractionView.named(
          namedScene,
          context,
          keys,
          SemanticId.unsafe("hand-built"),
          ok(PlanRevision("one"))
        )
      )
      val names = ok(NamedPicking.compile(namedScene, context))
      def nameOf(id: Option[VisualTargetId]): Option[GraphicsName] =
        id.flatMap(i => view.navigation.targets.find(_.target.id == i))
          .flatMap(_.target.entity)
          .map(_.value)
      fx {
        val host = ok(JavaFxInteractionHost.attach(view))
        var events = Vector.empty[EventRecord[GraphicsName]]
        ok(host.subscribe(e => events :+= e))
        assertEquals(view.navigation.targets.size, 6)
        view.navigation.targets.foreach { g =>
          mouse(host, MouseEvent.MOUSE_MOVED, g.anchor.x / scale, g.anchor.y / scale)
          assertEquals(
            nameOf(ok(host.state).hover),
            ok(names.nearest(g.anchor, 4.0 * scale)).map(_.name),
            g.anchor
          )
        }
        val third = view.navigation.targets(2)
        mouse(host, MouseEvent.MOUSE_CLICKED, third.anchor.x / scale, third.anchor.y / scale)
        assertEquals(
          ok(host.state).selection.entities.map(_.value),
          Set(GraphicsName.unsafe("mark-2"))
        )
        assert(events.exists(_.event.isInstanceOf[InteractionEvent.Activated[?]]))
        mouse(host, MouseEvent.MOUSE_MOVED, 1, 1)
        assertEquals(ok(host.state).hover, None, "the unnamed backdrop is not a target")

        key(host, KeyCode.HOME)
        var visited = Vector(nameOf(ok(host.state).focus))
        for _ <- 1 until 6 do
          key(host, KeyCode.PAGE_DOWN)
          visited :+= nameOf(ok(host.state).focus)
        assertEquals(visited.flatten, names.names)
        key(host, KeyCode.HOME)
        key(host, KeyCode.RIGHT)
        val first = view.navigation.targets.head.target.id
        assertEquals(
          nameOf(ok(host.state).focus),
          ok(view.navigation.nearest(first, NavigationDirection.Right))
            .flatMap(_.target.entity)
            .map(_.value)
        )
        assertEquals(nameOf(ok(host.state).focus), Some(GraphicsName.unsafe("mark-1")))
        // Row 0 is drawn lower on the device (npc y runs upward), so mark-4 is above mark-1.
        key(host, KeyCode.UP)
        assertEquals(nameOf(ok(host.state).focus), Some(GraphicsName.unsafe("mark-4")))
        key(host, KeyCode.ENTER)
        assertEquals(
          ok(host.state).selection.entities.map(_.value),
          Set(GraphicsName.unsafe("mark-4"))
        )
        assertEquals(host.node.getAccessibleText, "marks: mark-4")
        assert(ink(host) > 0)
        ok(host.dispose())
      }
  }

  private def overlayArgb(host: JavaFxInteractionHost[?], x: Double, y: Double): Int =
    val parameters = new SnapshotParameters()
    parameters.setFill(Color.TRANSPARENT)
    val image = host.node.getChildren.get(1).asInstanceOf[Canvas].snapshot(parameters, null)
    image.getPixelReader.getArgb(math.round(x).toInt, math.round(y).toInt)

  test("the default overlay style draws the original selection colour around the bounds") {
    val view = prepared()
    fx {
      val host = ok(JavaFxInteractionHost.attach(view))
      assert(host.overlayStyle eq JavaFxOverlayStyle.default)
      val mark = view.navigation.targets.head
      ok(host.setSelection(Selection(mark.target.entity.toSet)))
      val argb = overlayArgb(host, mark.right + 3, mark.anchor.y)
      assertEquals(
        (argb >>> 24, (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255),
        (255, 0x00, 0x72, 0xb2)
      )
      val before = host.profile
      val rebuilt = ok(
        JavaFxOverlayStyle(
          ok(OverlayStroke(Rgba.unsafe(0x00, 0x72, 0xb2), 2)),
          ok(OverlayStroke(Rgba.unsafe(0xd5, 0x5e, 0x00), 2)),
          ok(OverlayStroke.cased(Rgba.Black, 2, Rgba.White, 5))
        )
      )
      val parameters = new SnapshotParameters()
      parameters.setFill(Color.TRANSPARENT)
      def pixels(): Vector[Int] =
        val image = host.node.getChildren.get(1).asInstanceOf[Canvas].snapshot(parameters, null)
        (for
          y <- 0 until image.getHeight.toInt
          x <- 0 until image.getWidth.toInt
        yield image.getPixelReader.getArgb(x, y)).toVector
      key(host, KeyCode.HOME)
      val original = pixels()
      ok(host.setOverlayStyle(rebuilt))
      assertEquals(pixels(), original, "an explicitly built default draws identical pixels")
      assertEquals(host.profile.baseDraws, before.baseDraws)
      val themed = ok(
        JavaFxOverlayStyle(
          ok(OverlayStroke(Rgba.unsafe(200, 0, 120), 4)),
          ok(OverlayStroke(Rgba.unsafe(0xd5, 0x5e, 0x00), 2)),
          ok(OverlayStroke.cased(Rgba.Black, 2, Rgba.White, 5))
        )
      )
      // Focus stays on the first mark; select an unfocused one so its ring is not covered.
      val other = view.navigation.targets.last
      ok(host.setSelection(Selection(other.target.entity.toSet)))
      ok(host.setOverlayStyle(themed))
      val recoloured = overlayArgb(host, other.right + 3, other.anchor.y)
      assertEquals(((recoloured >>> 16) & 255, (recoloured >>> 8) & 255), (200, 0))
      ok(host.dispose())
      assertEquals(host.setOverlayStyle(themed).left.toOption, Some(JavaFxHostError.Disposed))
    }
  }

  test("geometry outlines follow circle and diamond marks at the offset, at 1x and 2x") {
    for scale <- Vector(1, 2) do
      val context = RenderContext.unsafe(
        400 * scale,
        300 * scale,
        pixelsPerInch = 96.0 * scale,
        deviceScale = scale.toDouble
      )
      val ink = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
      val scene = Scene(
        Vector(
          Grob.circleUnsafe(
            Point.npcUnsafe(0.3, 0.5),
            ExtentExpr.pointsUnsafe(20),
            ink,
            name = Some(GraphicsName.unsafe("circle"))
          ),
          Grob.pointBatchUnsafe(
            Vector(Point.npcUnsafe(0.7, 0.5)),
            sizes = BatchColumn.Constant(ExtentExpr.pointsUnsafe(20)),
            shapes = BatchColumn.Constant(PointShape.Diamond),
            graphicParams = BatchColumn.Constant(ink),
            name = Some(GraphicsName.unsafe("diamond"))
          )
        )
      )
      val view = ok(
        JavaFxInteractionView.named(
          scene,
          context,
          ok(NamedInteraction.keySpace("marks")),
          SemanticId.unsafe("outlines"),
          ok(PlanRevision("one"))
        )
      )
      fx {
        val host = ok(JavaFxInteractionHost.attach(view))
        val geometry = host.overlayStyle.withOutline(OverlayOutline.Geometry)
        for g <- view.navigation.targets do
          ok(host.setOverlayStyle(JavaFxOverlayStyle.default))
          ok(host.setHover(None))
          // Focus the target through the keyboard, then look at the focus ring 5 logical px out.
          key(host, KeyCode.HOME)
          while ok(host.state).focus != Some(g.target.id) do key(host, KeyCode.PAGE_DOWN)
          val offset = 5.0 * scale
          val cx = (g.left + g.right) / 2
          val cy = (g.top + g.bottom) / 2
          val half = (g.right - g.left) / 2
          val corner = ((g.left - offset) / scale, (g.top - offset) / scale)
          assert((overlayArgb(host, corner._1, corner._2) >>> 24) > 0, s"bounds corner $g")
          ok(host.setOverlayStyle(geometry))
          assertEquals(overlayArgb(host, corner._1, corner._2) >>> 24, 0, s"no box corner $g")
          // On the outline: 45 degrees out on the circle; straight right of the diamond's vertex.
          val (x, y) =
            if g.target.entity.exists(_.value == GraphicsName.unsafe("circle")) then
              val r = half + offset
              (cx + r * math.sqrt(0.5), cy + r * math.sqrt(0.5))
            else (g.right + offset, cy)
          assert((overlayArgb(host, x / scale, y / scale) >>> 24) == 255, s"ring at $x,$y")
        ok(host.dispose())
      }
  }

  test("record 10000-mark pointer-to-highlight and overlay snapshot latency") {
    val view = prepared(count = 10000)
    fx {
      val host = ok(JavaFxInteractionHost.attach(view))
      val stage = new Stage()
      stage.setScene(new _root_.javafx.scene.Scene(host.node, 400, 300))
      stage.show()
      assertEquals(stage.getOutputScaleX, 2.0)
      val sample = view.navigation.targets.take(100)
      val hover = Vector.newBuilder[Long]
      val overlay = Vector.newBuilder[Long]
      sample.take(20).foreach(g => mouse(host, MouseEvent.MOUSE_MOVED, g.anchor.x, g.anchor.y))
      val baseDraws = host.profile.baseDraws
      sample.foreach { g =>
        val start = System.nanoTime()
        mouse(host, MouseEvent.MOUSE_MOVED, g.anchor.x, g.anchor.y)
        host.node.getChildren.get(1).asInstanceOf[Canvas].snapshot(null, null)
        hover += System.nanoTime() - start
        val redrawStart = System.nanoTime()
        ok(host.setHover(Some(g.target.id)))
        host.node.getChildren.get(1).asInstanceOf[Canvas].snapshot(null, null)
        overlay += System.nanoTime() - redrawStart
      }
      def quantile(values: Vector[Long], q: Double): Double =
        values.sorted.apply((q * (values.size - 1)).toInt) / 1e6
      val h = hover.result()
      val o = overlay.result()
      val receipt =
        s"""machine=${sys.props("os.name")} ${sys.props("os.arch")} ${sys.props("os.version")}
java=${sys.props("java.version")}
processors=${Runtime.getRuntime.availableProcessors()}
backend=Monocle Headless / Prism software / synchronous Canvas snapshot
window_output_scale=${stage.getOutputScaleX}
marks=10000
samples=${h.size}
pointer_to_snapshot_median_ms=${quantile(h, 0.5)}
pointer_to_snapshot_p95_ms=${quantile(h, 0.95)}
overlay_to_snapshot_median_ms=${quantile(o, 0.5)}
overlay_to_snapshot_p95_ms=${quantile(o, 0.95)}
base_redraws_during_input=${host.profile.baseDraws - baseDraws}
"""
      val path = Path.of("target", "feature-evidence", "javafx-interaction-latency.txt")
      Files.createDirectories(path.getParent)
      Files.writeString(path, receipt)
      println(receipt)
      assertEquals(host.profile.baseDraws, baseDraws)
      ok(host.dispose())
      stage.close()
    }
  }
