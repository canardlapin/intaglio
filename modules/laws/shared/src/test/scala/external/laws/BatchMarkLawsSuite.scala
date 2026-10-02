package external.laws

import intaglio.*
import intaglio.laws.*

class BatchMarkLawsSuite extends munit.FunSuite:
  private val device = DeviceContext.unsafe(400.0, 300.0, 96.0)
  private val points =
    (0 until 40).toVector.map(i => Point.npcUnsafe((i + 1) / 41.0, (i % 7 + 1) / 8.0))
  private val marks =
    BatchMarks.unsafe(points.indices.toVector.map(i => GraphicsName.unsafe(s"row-$i")))

  /** Fixed-size chunks, slicing the names with the points. */
  private def chunks(size: Int)(values: Vector[Point], names: BatchMarks) =
    values.indices.grouped(size).toVector.map { indices =>
      (indices.toVector.map(values), names.slice(indices.head, indices.last + 1).orThrow)
    }

  /** Every third mark removed, with its name. */
  private def filtered(values: Vector[Point], names: BatchMarks) =
    val kept = values.indices.filter(_ % 3 != 0).toVector
    Vector((kept.map(values), BatchMarks.unsafe(kept.map(names.names))))

  test("chunking, filtering and leaving a batch whole all keep each mark's name") {
    for split <- Vector(
        chunks(7),
        chunks(40),
        filtered,
        (v: Vector[Point], m: BatchMarks) => Vector(v -> m)
      )
    do assertEquals(BatchMarkLaws(points, marks, split, device).failures, Vector.empty)
  }

  test("the kit detects a split that misassigns names") {
    // The bug a side table invites: each chunk restarts its names from the first one.
    def restarted(values: Vector[Point], names: BatchMarks) =
      values.indices.grouped(10).toVector.map { indices =>
        (indices.toVector.map(values), names.slice(0, indices.size).orThrow)
      }
    val failures = BatchMarkLaws(points, marks, restarted, device).failures
    assertEquals(failures.map(_.law), Vector("every mark keeps its name through the host's split"))
  }

  test("the kit detects a split whose names no longer fit its points") {
    def dropped(values: Vector[Point], names: BatchMarks) =
      Vector((values.drop(1), names))
    val failures = BatchMarkLaws(points, marks, dropped, device).failures
    assertEquals(failures.map(_.law), Vector("every mark keeps its name through the host's split"))
    assert(failures.head.detail.startsWith("split pieces were rejected"), failures.head.detail)
  }
