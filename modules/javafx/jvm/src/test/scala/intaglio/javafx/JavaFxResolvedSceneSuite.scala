package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import java.nio.file.{Files, Path}

/** One resolved `DeviceScene` feeds both the JavaFX program and named picking. */
class JavaFxResolvedSceneSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)

  private val fill = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))

  /** One named grob per mark, the shape a hand-built host scene takes. */
  private def marks(count: Int, columns: Int): Scene =
    Scene(
      Vector(
        Grob.group(
          (0 until count).toVector.map { i =>
            Grob.circleUnsafe(
              Point.npcUnsafe(
                (i % columns + 0.5) / columns,
                (i / columns + 0.5) / math.ceil(count.toDouble / columns)
              ),
              ExtentExpr.pointsUnsafe(1.5),
              fill,
              name = Some(GraphicsName.unsafe(s"mark-$i"))
            )
          },
          name = Some(GraphicsName.unsafe("marks"))
        ),
        Grob.textUnsafe("title", Point.npcUnsafe(0.5, 0.97), name = Some(GraphicsName.unsafe("t")))
      )
    )

  test("a program from a resolved scene equals the program compiled from its source") {
    for scale <- Vector(1, 2) do
      val context = RenderContext.unsafe(
        320 * scale,
        240 * scale,
        pixelsPerInch = 96.0 * scale,
        deviceScale = scale.toDouble
      )
      val scene = marks(60, 10)
      val resolved = ok(DeviceScene.fromScene(scene, context))
      val once = ok(JavaFxProgram.fromResolved(resolved, context))
      assertEquals(once, ok(JavaFxRenderer.compile(RenderPlan(scene, context))))
      val drawn = new RecordingFxContext
      JavaFxRenderer.draw(once, drawn)
      val reference = new RecordingFxContext
      JavaFxRenderer.draw(ok(JavaFxRenderer.compile(RenderPlan(scene, context))), reference)
      assertEquals(drawn.calls.toVector, reference.calls.toVector)

      val picked = ok(NamedPicking.fromResolved(resolved, context))
      val separate = ok(NamedPicking.compile(scene, context))
      assertEquals(picked.names, separate.names)
      for
        x <- 0 until context.width by 7
        y <- 0 until context.height by 7
      do
        val point = DevicePoint(x.toDouble, y.toDouble)
        assertEquals(picked.hits(point, 2), separate.hits(point, 2), point)
  }

  test("a resolved scene is checked at the typed boundary like a freshly lowered one") {
    val context = RenderContext.unsafe(100, 100)
    val nonFinite = DeviceScene(
      100,
      100,
      Vector(DeviceElement.Mark(DevicePrimitive.Disc(Double.NaN, 10, 2, fill, None)))
    )
    assert(
      JavaFxProgram.fromResolved(nonFinite, context).left.toOption.exists {
        case JavaFxRenderError.Graphics(_: GraphicsError.InvalidDeviceValue) => true
        case _                                                               => false
      }
    )
    val recipe = PatternRecipe
      .parallelRules(RuleOrientation.Vertical, PatternTile.MaxAxisPixels.toDouble + 1.0, 1.0)
      .fold(error => fail(error.message), identity)
    val oversized = DeviceScene(
      100,
      100,
      Vector(
        DeviceElement.Mark(
          DevicePrimitive.RectShape(
            10,
            10,
            50,
            50,
            0,
            GraphicParams.unsafe(stroke = None).withPatternFill(PatternPaint(recipe, Rgba.Black)),
            None
          )
        )
      )
    )
    assert(
      JavaFxProgram.fromResolved(oversized, context).left.toOption.exists {
        case JavaFxRenderError
              .Graphics(GraphicsError.InvalidPatternParameter("raster", "spacing", _, _)) =>
          true
        case _ => false
      }
    )
  }

  test("record draw+pick lowering count and time, separate paths against one resolution") {
    // 11,520 marks at device scale 2: the downstream host's reported scene size.
    val context = RenderContext.unsafe(1280, 960, pixelsPerInch = 192, deviceScale = 2)
    val scene = marks(11520, 120)
    def separate(): (JavaFxProgram, NamedPickingPlan) =
      // NamedPicking.compile and JavaFxRenderer.compile each resolve the scene: two lowerings.
      (
        ok(JavaFxRenderer.compile(RenderPlan(scene, context))),
        ok(NamedPicking.compile(scene, context))
      )
    def once(): (JavaFxProgram, NamedPickingPlan) =
      val resolved = ok(DeviceScene.fromScene(scene, context))
      (
        ok(JavaFxProgram.fromResolved(resolved, context)),
        ok(NamedPicking.fromResolved(resolved, context))
      )
    def lowering(): DeviceScene = ok(DeviceScene.fromScene(scene, context))
    def time[A](runs: Int)(body: => A): Vector[Long] =
      (0 until runs).toVector.map { _ =>
        val start = System.nanoTime()
        body
        System.nanoTime() - start
      }
    // Warm-up runs are discarded.
    time(3)(separate())
    time(3)(once())
    val before = time(7)(separate())
    val after = time(7)(once())
    val lower = time(7)(lowering())
    val (beforeProgram, beforePlan) = separate()
    val (afterProgram, afterPlan) = once()
    assertEquals(afterProgram, beforeProgram)
    assertEquals(afterPlan.names, beforePlan.names)
    def stats(values: Vector[Long]): String =
      val sorted = values.sorted.map(_ / 1e6)
      f"min=${sorted.head}%.1f median=${sorted(sorted.size / 2)}%.1f max=${sorted.last}%.1f"
    val receipt =
      s"""machine=${sys.props("os.name")} ${sys.props("os.arch")} ${sys.props("os.version")}
java=${sys.props("java.version")}
processors=${Runtime.getRuntime.availableProcessors()}
marks=11520 named circle grobs, device 1280x960 at scale 2
runs=7 after 3 discarded warm-up runs; milliseconds
lowerings_per_draw_and_pick_before=2 (JavaFxRenderer.compile + NamedPicking.compile)
lowerings_per_draw_and_pick_after=1 (DeviceScene.fromScene, then fromResolved twice)
draw_and_pick_before_ms ${stats(before)}
draw_and_pick_after_ms ${stats(after)}
one_lowering_ms ${stats(lower)}
"""
    val path = Path.of("target", "feature-evidence", "resolved-scene-lowering.txt")
    Files.createDirectories(path.getParent)
    Files.writeString(path, receipt)
    println(receipt)
  }
