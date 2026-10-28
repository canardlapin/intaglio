package intaglio.javafx

import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.image.{Image, PixelFormat, WritableImage}
import _root_.javafx.scene.paint.Color
import _root_.javafx.scene.{Group, Scene as FxScene}
import _root_.javafx.stage.Stage
import intaglio.*
import java.nio.file.{Files, Path, StandardOpenOption}

/** Compare pure JavaFX, adapter and renderer at the consumer's fractional gradient geometry. Scale
  * is an explicit drawing transform, not an asserted physical window scale. Source RGBA bytes come
  * from the retained consumer.
  */
class GradientEdgeSuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()
  private case class Sample(
      name: String,
      mode: String,
      scale: Int,
      interpolation: String,
      x: Double,
      y: Double,
      bad: Vector[(Int, Int, Int, Int, Int)]
  )
  private val packed = Vector(574647807, 692417279, 793474815, 911309567, 1029079039, 1130136575,
    1247971327, 1348963583, 1466798335, 1567855871, 1685625343, 1786682879, 1904517631, 2022287103,
    2123344639, -2053787905, -1952795649, -1834960897, -1733903361, -1616133889, -1498299137,
    -1397241601, -1279472129, -1178414593, -1060579841, -959587585, -841752833, -740695297,
    -622925825, -505091073, -404033537, -286264065)
  private val source =
    RasterImage.tabulate(RasterDimensions.unsafe(1, 32))((_, y) => Rgba32.fromPackedInt(packed(y)))
  private val evidence = sys.env.get("INTAGLIO_GRADIENT_EVIDENCE").map(Path.of(_))
  private def save(name: String, image: Image): Unit = evidence.foreach { directory =>
    val path = directory.resolve(name + ".png")
    require(!Files.exists(path))
    val buffer = new java.awt.image.BufferedImage(
      image.getWidth.toInt,
      image.getHeight.toInt,
      java.awt.image.BufferedImage.TYPE_INT_ARGB
    )
    for y <- 0 until image.getHeight.toInt; x <- 0 until image.getWidth.toInt do
      buffer.setRGB(x, y, image.getPixelReader.getArgb(x, y))
    javax.imageio.ImageIO.write(buffer, "png", path.toFile)
  }

  test("opaque fractional gradient edges stay within source and background channels") {
    evidence.foreach { p =>
      Files.createDirectories(p)
      Files.writeString(
        p.resolve("runtime.txt"),
        System.getProperty("java.version") + "\n" +
          System.getProperty("javafx.runtime.version") + "\n" + System.getProperty(
            "os.name"
          ) + " " +
          System.getProperty("os.arch") + "\n" + System
            .getProperty("prism.order", "automatic") + "\n",
        StandardOpenOption.CREATE_NEW
      )
    }
    val reports = scala.collection.mutable.ArrayBuffer.empty[Sample]
    for
      mode <- Vector("direct", "adapter", "renderer")
      scale <- Vector(1, 2)
      interpolation <- Vector(RasterInterpolation.Smooth, RasterInterpolation.Nearest)
      (x, y) <- Vector(
        (361.07161458333337, 37.19921874999997),
        (149.0, 37.19921874999997),
        (361.0, 37.0)
      )
    do
      val name = s"$mode-$scale-$interpolation-${reports.size}"
      val width = 10.666666666666666; val height = 80.0
      val report = FxToolkit.fx {
        val canvas = new Canvas(413, 190)
        val gc = canvas.getGraphicsContext2D
        val adapter = new JavaFxCanvasContext(gc)
        // Pure JavaFX gets independently translated ARGB, not adapter helpers.
        val raw = new WritableImage(1, 32)
        val argb = packed.map(p => ((p & 255) << 24) | (p >>> 8)).toArray
        raw.getPixelWriter.setPixels(0, 0, 1, 32, PixelFormat.getIntArgbInstance, argb, 0, 1)
        try
          for _ <- 0 until 4 do
            gc.setFill(Color.WHITE); gc.fillRect(0, 0, 413, 190)
            gc.save()
            try
              gc.scale(1.0 / scale, 1.0 / scale)
              mode match
                case "direct" =>
                  gc.setImageSmoothing(interpolation == RasterInterpolation.Smooth)
                  gc.drawImage(raw, x * scale, y * scale, width * scale, height * scale)
                case "adapter" =>
                  adapter.setImageSmoothing(interpolation == RasterInterpolation.Smooth)
                  adapter.drawImage(source, x * scale, y * scale, width * scale, height * scale)
                case "renderer" =>
                  val grob = Grob.imageUnsafe(
                    source,
                    Point.npcUnsafe(x / 413, 1 - y / 190),
                    Size.npcUnsafe(width / 413, height / 190),
                    anchor = Anchor(HJust.Left, VJust.Top),
                    interpolation = interpolation
                  )
                  JavaFxRenderer
                    .render(
                      Scene(Vector(grob)),
                      adapter,
                      JavaFxOptions.unsafe(
                        413 * scale,
                        190 * scale,
                        pixelsPerInch = 96.0 * scale,
                        deviceScale = scale
                      )
                    )
                    .orThrow
                case other => fail(other)
            finally gc.restore()
          val image = canvas.snapshot(null, null)
          save(name, image)
          val lo = Vector(24, 16, 8).map(shift => packed.map(p => (p >>> shift) & 255).min)
          val bad = for
            py <- math.ceil(y).toInt + 1 until math.floor(y + height).toInt
            px <- math.ceil(x).toInt + 1 until math.floor(x + width).toInt
            pixel = image.getPixelReader.getArgb(px, py)
            channels = Vector((pixel >>> 16) & 255, (pixel >>> 8) & 255, pixel & 255)
            if channels.zip(lo).exists((value, minimum) => value < minimum - 1)
          yield (px, py, channels(0), channels(1), channels(2))
          Sample(name, mode, scale, interpolation.toString, x, y, bad.toVector)
        finally adapter.release()
      }
      reports += report
    val result = "name,mode,scale_transform,interpolation,x,y,bad_count,bad_pixels\n" + reports
      .map { r =>
        val bad = r.bad.take(30).map(p => s"${p._1}:${p._2}:${p._3}:${p._4}:${p._5}").mkString("|")
        s"${r.name},${r.mode},${r.scale},${r.interpolation},${r.x},${r.y},${r.bad.size},$bad"
      }
      .mkString("\n") + "\n"
    evidence.foreach(p =>
      Files.writeString(p.resolve("gradient-isolation.csv"), result, StandardOpenOption.CREATE_NEW)
    )
    assert(
      reports.filter(_.mode != "direct").forall(_.bad.isEmpty),
      reports.filter(r => r.mode != "direct" && r.bad.nonEmpty).mkString("\n")
    )
  }

  test("saved complete consumer scene isolates surrounding drawing commands") {
    evidence.foreach(Files.createDirectories(_))
    def keep(
        elements: Vector[DeviceElement],
        accept: DevicePrimitive => Boolean
    ): Vector[DeviceElement] = elements.flatMap {
      case DeviceElement.Mark(mark) =>
        if accept(mark) then Vector(DeviceElement.Mark(mark)) else Vector.empty
      case DeviceElement.Group(name, clip, rotation, children) =>
        Vector(DeviceElement.Group(name, clip, rotation, keep(children, accept)))
      case DeviceElement.Annotated(meta, children) =>
        Vector(DeviceElement.Annotated(meta, keep(children, accept)))
    }
    val filters: Vector[(String, DevicePrimitive => Boolean)] = Vector(
      "full" -> (_ => true),
      "images" -> (_.isInstanceOf[DevicePrimitive.Image]),
      "images-text" -> (p =>
        p.isInstanceOf[DevicePrimitive.Image] || p.isInstanceOf[DevicePrimitive.TextRun]
      ),
      "no-text" -> (p => !p.isInstanceOf[DevicePrimitive.TextRun])
    )
    val reports = filters.map { (label, accept) =>
      FxToolkit.fx {
        val saved = SavedGradientScene.scene
        val scene = saved.copy(elements = keep(saved.elements, accept))
        val context = RenderContext.unsafe(scene.width.toInt, scene.height.toInt)
        val program = JavaFxProgram.fromResolved(scene, context).orThrow
        val canvas = new Canvas(scene.width, scene.height)
        val gc = canvas.getGraphicsContext2D
        val adapter = new JavaFxCanvasContext(gc)
        try
          for _ <- 0 until 4 do
            gc.setFill(Color.WHITE); gc.fillRect(0, 0, canvas.getWidth, canvas.getHeight)
            JavaFxRenderer.draw(program, adapter)
          val image = canvas.snapshot(null, null)
          save("saved-" + label, image)
          val bad = for
            y <- 39 until 117
            x <- 363 until 371
            pixel = image.getPixelReader.getArgb(x, y)
            if (pixel & 255) < 108
          yield (x, y, (pixel >>> 16) & 255, (pixel >>> 8) & 255, pixel & 255)
          s"$label,${bad.size},${bad.take(20).map(p => s"${p._1}:${p._2}:${p._3}:${p._4}:${p._5}").mkString("|")}"
        finally adapter.release()
      }
    }
    val result = "variant,bad_count,bad_pixels\n" + reports.mkString("\n") + "\n"
    evidence.foreach(p =>
      Files.writeString(
        p.resolve("saved-scene-isolation.csv"),
        result,
        StandardOpenOption.CREATE_NEW
      )
    )
    assert(reports.forall(_.split(",")(1) == "0"), result)
  }

  test("saved gradient paint is checked after attachment and an actual window pulse") {
    evidence.foreach(Files.createDirectories(_))
    val (canvas, stage, adapter) = FxToolkit.fx {
      val saved = SavedGradientScene.scene
      val canvas = new Canvas(saved.width, saved.height)
      val stage = new Stage()
      stage.setScene(new FxScene(new Group(canvas), saved.width, saved.height))
      stage.show()
      val adapter = new JavaFxCanvasContext(canvas.getGraphicsContext2D)
      val program = JavaFxProgram
        .fromResolved(saved, RenderContext.unsafe(saved.width.toInt, saved.height.toInt))
        .orThrow
      for _ <- 0 until 4 do
        canvas.getGraphicsContext2D.setFill(Color.WHITE)
        canvas.getGraphicsContext2D.fillRect(0, 0, saved.width, saved.height)
        JavaFxRenderer.draw(program, adapter)
      (canvas, stage, adapter)
    }
    try
      Thread.sleep(150) // Test thread only; allow a real window pulse before capture.
      val result = FxToolkit.fx {
        val image = canvas.snapshot(null, null)
        save("saved-attached", image)
        val bad = for
          y <- 39 until 117
          x <- 363 until 371
          pixel = image.getPixelReader.getArgb(x, y)
          if (pixel & 255) < 108
        yield (x, y, (pixel >>> 16) & 255, (pixel >>> 8) & 255, pixel & 255)
        val text =
          s"window_scale,bad_count,bad_pixels\n${stage.getRenderScaleX},${bad.size},${bad.mkString("|")}\n"
        evidence.foreach(p =>
          Files.writeString(
            p.resolve("attached-isolation.csv"),
            text,
            StandardOpenOption.CREATE_NEW
          )
        )
        bad
      }
      assert(result.isEmpty, result.mkString("\n"))
    finally FxToolkit.fx { adapter.release(); stage.close() }
  }

  test(
    "native gradient images reused across separate window pulses preserve source channel bounds"
  ) {
    evidence.foreach(Files.createDirectories(_))
    val reports = scala.collection.mutable.ArrayBuffer.empty[String]
    for mode <- Vector(
        "direct",
        "adapter",
        "renderer",
        "renderer-images",
        "renderer-gradient",
        "renderer-no-text",
        "renderer-images-text"
      )
    do
      val (canvas, stage, adapter, raw, program) = FxToolkit.fx {
        val canvas = new Canvas(413, 190)
        val stage = new Stage()
        stage.setScene(new FxScene(new Group(canvas), 500, 250)); stage.show()
        val raw = new WritableImage(1, 32)
        raw.getPixelWriter.setPixels(
          0,
          0,
          1,
          32,
          PixelFormat.getIntArgbInstance,
          packed.map(p => ((p & 255) << 24) | (p >>> 8)).toArray,
          0,
          1
        )
        def keep(es: Vector[DeviceElement]): Vector[DeviceElement] = es.flatMap {
          case DeviceElement.Mark(mark) =>
            val accept = mode match
              case "renderer-images"   => mark.isInstanceOf[DevicePrimitive.Image]
              case "renderer-gradient" =>
                mark match
                  case im: DevicePrimitive.Image => im.image.width == 1
                  case _                         => false
              case "renderer-no-text"     => !mark.isInstanceOf[DevicePrimitive.TextRun]
              case "renderer-images-text" =>
                mark.isInstanceOf[DevicePrimitive.Image] || mark
                  .isInstanceOf[DevicePrimitive.TextRun]
              case _ => true
            if accept then Vector(DeviceElement.Mark(mark)) else Vector.empty
          case DeviceElement.Group(n, c, r, children) =>
            Vector(DeviceElement.Group(n, c, r, keep(children)))
          case DeviceElement.Annotated(meta, children) =>
            Vector(DeviceElement.Annotated(meta, keep(children)))
        }
        val selected =
          SavedGradientScene.scene.copy(elements = keep(SavedGradientScene.scene.elements))
        (
          canvas,
          stage,
          new JavaFxCanvasContext(canvas.getGraphicsContext2D),
          raw,
          JavaFxProgram.fromResolved(selected, RenderContext.unsafe(413, 190)).orThrow
        )
      }
      try
        for iteration <- 0 until 5 do
          FxToolkit.fx {
            val gc = canvas.getGraphicsContext2D
            gc.setFill(Color.WHITE); gc.fillRect(0, 0, 413, 190)
            mode match
              case "direct" =>
                gc.setImageSmoothing(true)
                gc.drawImage(raw, 361.07161458333337, 37.19921874999997, 10.666666666666666, 80)
              case "adapter" =>
                adapter.setImageSmoothing(true)
                adapter.drawImage(
                  source,
                  361.07161458333337,
                  37.19921874999997,
                  10.666666666666666,
                  80
                )
              case p if p.startsWith("renderer") => JavaFxRenderer.draw(program, adapter)
              case other                         => fail(other)
          }
          Thread.sleep(100)
          FxToolkit.fx {
            val image = canvas.snapshot(null, null)
            save(s"pulsed-$mode-$iteration", image)
            val bad = (39 until 117)
              .flatMap(y => (363 until 371).map(x => image.getPixelReader.getArgb(x, y) & 255))
              .count(_ < 108)
            reports += s"$mode,$iteration,${stage.getRenderScaleX},$bad"
          }
      finally FxToolkit.fx { adapter.release(); stage.close() }
    val result = "mode,iteration,window_scale,bad_count\n" + reports.mkString("\n") + "\n"
    evidence.foreach(p =>
      Files.writeString(p.resolve("pulsed-isolation.csv"), result, StandardOpenOption.CREATE_NEW)
    )
    assert(reports.filterNot(_.startsWith("direct,")).forall(_.split(",")(3) == "0"), result)
  }

  test("pure JavaFX vendor baseline is recorded separately from protected adapter guarantees") {
    evidence.foreach(Files.createDirectories(_))
    val (canvas, stage, inputs) = FxToolkit.fx {
      val canvas = new Canvas(413, 190)
      val stage = new Stage(); stage.setScene(new FxScene(new Group(canvas), 500, 250));
      stage.show()
      val inputs = Vector.fill(16) {
        val image = new WritableImage(1, 32)
        image.getPixelWriter.setPixels(
          0,
          0,
          1,
          32,
          PixelFormat.getIntArgbInstance,
          packed.map(p => ((p & 255) << 24) | (p >>> 8)).toArray,
          0,
          1
        )
        image
      }
      (canvas, stage, inputs)
    }
    val reports = scala.collection.mutable.ArrayBuffer.empty[String]
    try
      for iteration <- 0 until 5 do
        FxToolkit.fx {
          inputs.zipWithIndex.foreach { (source, index) =>
            val gc = canvas.getGraphicsContext2D
            gc.setFill(Color.WHITE); gc.fillRect(0, 0, 413, 190); gc.setImageSmoothing(true)
            gc.drawImage(source, 361.07161458333337, 37.19921874999997, 10.666666666666666, 80)
            val image = canvas.snapshot(null, null)
            val bad = (39 until 117)
              .flatMap(y => (363 until 371).map(x => image.getPixelReader.getArgb(x, y) & 255))
              .count(_ < 108)
            if bad > 0 then save(s"pure-$iteration-$index", image)
            reports += s"$iteration,$index,$bad"
          }
        }
        Thread.sleep(100)
      val result = "iteration,image,bad_count\n" + reports.mkString("\n") + "\n"
      evidence.foreach(p =>
        Files.writeString(
          p.resolve("pure-javafx-isolation.csv"),
          result,
          StandardOpenOption.CREATE_NEW
        )
      )
      val failed = reports.count(_.split(",")(2) != "0")
      println(
        s"Unprotected direct JavaFX baseline: $failed/80 image frames outside source bounds; recorded, not an adapter guarantee"
      )
      FxToolkit.fx {
        inputs.foreach { image =>
          for y <- 0 until 32 do
            assertEquals(
              image.getPixelReader.getArgb(0, y),
              ((packed(y) & 255) << 24) | (packed(y) >>> 8),
              "Vendor baseline CPU source pixels must remain exact, even if cached paint is invalid"
            )
        }
      }
    finally FxToolkit.fx(stage.close())
  }

  test("native expansion preserves thin source pixels and accounts actual retained storage") {
    FxToolkit.fx {
      for (w, h) <- Vector((1, 1), (1, 32), (32, 1), (2, 3)) do
        val image = RasterImage.tabulate(RasterDimensions.unsafe(w, h))((x, y) =>
          Rgba32.unsafe(40 + x, 80 + y, 160)
        )
        val original = (0 until h).flatMap(y => (0 until w).map(x => image.pixelUnsafe(x, y)))
        val context = new JavaFxCanvasContext(new Canvas(80, 80).getGraphicsContext2D)
        context.setImageSmoothing(true); context.drawImage(image, 1, 1, 64, 64)
        val native = context.cachedNativeImages.head
        assertEquals(native.getWidth.toInt, math.max(2, w))
        assertEquals(native.getHeight.toInt, math.max(2, h))
        for y <- 0 until native.getHeight.toInt; x <- 0 until native.getWidth.toInt do
          val p = image.pixelUnsafe(if w == 1 then 0 else x, if h == 1 then 0 else y)
          assertEquals(
            native.getPixelReader.getArgb(x, y),
            (255 << 24) | (p.red << 16) | (p.green << 8) | p.blue
          )
        assertEquals(
          (0 until h).flatMap(y => (0 until w).map(x => image.pixelUnsafe(x, y))),
          original
        )
        assertEquals(
          context.cachedResourceBytes,
          256L + w.toLong * h * 4 + native.getWidth.toLong * native.getHeight.toLong * 4
        )
        context.release(); assertEquals(context.cachedResourceBytes, 0L)
    }
  }
