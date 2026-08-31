package intaglio.performance

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class PerformanceReceiptSuite extends munit.FunSuite:
  test("the versioned TSV receipt exactly matches the cross-platform gate definitions") {
    val receipt = locateReceipt()
    val lines = Files.readAllLines(receipt, StandardCharsets.UTF_8).asScala.toVector
    assert(lines.contains(s"# schema_version=${PerformanceBaselines.schemaVersion}"))
    assert(lines.contains(s"# source_sha=${PerformanceBaselines.sourceSha}"))
    val data = lines.filter(line => line.nonEmpty && !line.startsWith("#"))
    assertEquals(
      data.headOption,
      Some("workload\tmetric\trecorded\thigh_severity_limit\trationale")
    )
    val parsed = data.drop(1).map { line =>
      line.split("\t", -1).toVector match
        case Vector(workload, metric, recorded, limit, rationale) =>
          PerformanceBaseline(workload, metric, recorded.toLong, limit.toLong, rationale)
        case fields =>
          fail(s"expected five tab-separated receipt fields, found ${fields.length}: $line")
    }
    assertEquals(parsed, PerformanceBaselines.entries)
  }

  private def locateReceipt(): Path =
    val relative = Path.of("performance", "baselines", "v1.tsv")
    Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(Files.isRegularFile(_))
      .getOrElse(fail(s"could not locate $relative from ${Path.of("").toAbsolutePath}"))
