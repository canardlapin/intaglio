package intaglio.interaction

import intaglio.*
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}

/** Records what per-mark identity costs a 10,000-point batch, and that an unidentified batch pays
  * nothing for it. Timings and allocation are measurements on the running machine, not gates; the
  * assertions are structural.
  */
class BatchMarkCostSuite extends munit.FunSuite:
  // The measured runs are receipts, not gates; munit's 30 s default fails them on a loaded machine.
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val context = RenderContext.unsafe(width = 1000, height = 1000)
  private val count = 10000
  private val points =
    (0 until count).toVector.map(i =>
      Point.npcUnsafe((i % 100 * 10 + 5) / 1000.0, (i / 100 * 10 + 5) / 1000.0)
    )
  private val batch = Grob.pointBatchUnsafe(
    points,
    sizes = BatchColumn.Constant(ExtentExpr.pointsUnsafe(1.5)),
    graphicParams =
      BatchColumn.Constant(GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))),
    name = Some(GraphicsName.unsafe("batch"))
  )
  private val marks =
    BatchMarks.unsafe((0 until count).toVector.map(i => GraphicsName.unsafe(s"row-$i")))
  private val plain = Scene(Vector(batch))
  private val marked = Scene(Vector(Grob.annotated(batch, GrobMeta.marks(marks))))

  private val threads =
    ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  private def measure[A](runs: Int)(body: => A): (Vector[Long], Vector[Long]) =
    (0 until 15).foreach(_ => body)
    val samples = (0 until runs).toVector.map { _ =>
      val id = Thread.currentThread().getId
      val bytes = threads.getThreadAllocatedBytes(id)
      val start = System.nanoTime()
      body
      (System.nanoTime() - start, threads.getThreadAllocatedBytes(id) - bytes)
    }
    (samples.map(_._1), samples.map(_._2))
  private def median(values: Vector[Long]): Long = values.sorted.apply(values.size / 2)

  test("record lowering and picking cost with and without per-mark identity") {
    val plainScene = ok(DeviceScene.fromScene(plain, context))
    val markedScene = ok(DeviceScene.fromScene(marked, context))
    // Absent identity leaves the lowered scene exactly as before: one batch primitive, no wrapper.
    plainScene.elements match
      case Vector(DeviceElement.Mark(_: DevicePrimitive.PointBatch)) => ()
      case other                                                     => fail(s"unexpected $other")
    assertEquals(
      markedScene.elements,
      Vector(DeviceElement.Annotated(GrobMeta.marks(marks), plainScene.elements))
    )

    val (lowerPlainTime, lowerPlainBytes) = measure(9)(DeviceScene.fromScene(plain, context))
    val (lowerMarkedTime, lowerMarkedBytes) = measure(9)(DeviceScene.fromScene(marked, context))
    // Measured again so warm-up order is visible in the receipt rather than read as a cost.
    val (lowerAgainTime, lowerAgainBytes) = measure(9)(DeviceScene.fromScene(plain, context))
    val (pickPlainTime, pickPlainBytes) = measure(5)(NamedPicking.fromResolved(plainScene, context))
    val (pickMarkedTime, pickMarkedBytes) =
      measure(5)(NamedPicking.fromResolved(markedScene, context))
    val plainPlan = ok(NamedPicking.fromResolved(plainScene, context))
    val markedPlan = ok(NamedPicking.fromResolved(markedScene, context))
    val queries =
      (0 until 200).map(i => DevicePoint((i * 37) % 100 * 10 + 5, (i * 53) % 100 * 10 + 5))
    val (hitsPlainTime, _) = measure(3)(queries.foreach(plainPlan.hits(_, 2)))
    val (hitsMarkedTime, _) = measure(3)(queries.foreach(markedPlan.hits(_, 2)))
    assertEquals(plainPlan.targetCount, 1)
    assertEquals(markedPlan.targetCount, count)

    def ms(values: Vector[Long]) = f"${median(values) / 1e6}%.2f"
    def kb(values: Vector[Long]) = f"${median(values) / 1024.0}%.0f"
    val receipt =
      s"""machine=${sys.props("os.name")} ${sys.props("os.arch")} ${sys.props("os.version")}
java=${sys.props("java.version")}
marks=$count point batch, device 1000x1000; medians after 15 discarded warm-up runs
lower_plain_ms=${ms(lowerPlainTime)} lower_plain_kib=${kb(lowerPlainBytes)}
lower_marked_ms=${ms(lowerMarkedTime)} lower_marked_kib=${kb(lowerMarkedBytes)}
lower_plain_again_ms=${ms(lowerAgainTime)} lower_plain_again_kib=${kb(lowerAgainBytes)}
named_picking_plain_ms=${ms(pickPlainTime)} named_picking_plain_kib=${kb(pickPlainBytes)} targets=1
named_picking_marked_ms=${ms(pickMarkedTime)} named_picking_marked_kib=${kb(
          pickMarkedBytes
        )} targets=$count
hits_200_plain_ms=${ms(hitsPlainTime)} (one target spanning the batch)
hits_200_marked_ms=${ms(hitsMarkedTime)} (one target per mark)
"""
    val path = Path.of("target", "feature-evidence", "batch-marks-cost.txt")
    Files.createDirectories(path.getParent)
    Files.writeString(path, receipt)
    println(receipt)
  }
