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
import java.util.concurrent.{CountDownLatch, FutureTask, TimeUnit}
import java.nio.file.{Files, Path}

class JavaFxInteractionHostSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)
  private def fx[A](body: => A): A =
    val task = new FutureTask[A](() => body)
    Platform.runLater(task)
    task.get(60, TimeUnit.SECONDS)

  override def beforeAll(): Unit =
    val latch = new CountDownLatch(1)
    Platform.startup(() => { Platform.setImplicitExit(false); latch.countDown() })
    assert(latch.await(30, TimeUnit.SECONDS), "headless FX startup")

  override def afterAll(): Unit = Platform.exit()

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
      host: JavaFxInteractionHost[Int],
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

  private def key(host: JavaFxInteractionHost[Int], code: KeyCode): Unit =
    Event.fireEvent(
      host.node,
      new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)
    )

  private def ink(host: JavaFxInteractionHost[Int]): Int =
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
