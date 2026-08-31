package intaglio.laws

import intaglio.*

import scala.util.control.NonFatal

/** A framework-neutral seeded law. Every returned problem, including a thrown non-fatal exception,
  * carries the exact decimal seed needed to replay that case.
  */
object SeededLaw:
  /** Small, stable edge-oriented seed court shared byte-for-byte by the JVM and Scala.js. */
  val defaultSeeds: Vector[Long] =
    Vector(
      0L,
      1L,
      -1L,
      2L,
      -2L,
      42L,
      -42L,
      20260831L,
      0x5eed5eedL,
      0xdeadbeefL,
      0x0123456789abcdefL,
      0x0f0e0d0c0b0a0908L,
      Long.MinValue / 2L,
      Long.MaxValue / 2L,
      Long.MinValue,
      Long.MaxValue
    )

  def apply(
      name: String,
      seeds: Vector[Long] = defaultSeeds
  )(evaluate: Long => Vector[String]): Law =
    Law(
      name,
      () =>
        if seeds.isEmpty then Vector("provide at least one reproducible seed")
        else
          seeds.flatMap { seed =>
            val problems =
              try evaluate(seed)
              catch
                case NonFatal(error) =>
                  val detail = Option(error.getMessage).filter(_.nonEmpty).getOrElse("no message")
                  Vector(s"threw ${error.getClass.getName}: $detail")
            problems.map(problem => s"seed=$seed: $problem")
          }
    )

private[laws] final class LawRng private (private var state: Long):
  def nextLong(): Long =
    state += 0x9e3779b97f4a7c15L
    var value = state
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value ^ (value >>> 31)

  def nextDouble(): Double =
    (nextLong() >>> 11).toDouble / 9007199254740992.0

  def between(lower: Double, upper: Double): Double =
    lower + (upper - lower) * nextDouble()

  def nextInt(bound: Int): Int =
    require(bound > 0, "bound must be positive")
    math.min(bound - 1, (nextDouble() * bound.toDouble).toInt)

  def shuffle[A](values: Vector[A]): Vector[A] =
    val out = scala.collection.mutable.ArrayBuffer.from(values)
    var index = out.length - 1
    while index > 0 do
      val target = nextInt(index + 1)
      val value = out(index)
      out(index) = out(target)
      out(target) = value
      index -= 1
    out.toVector

private[laws] object LawRng:
  def apply(seed: Long): LawRng =
    new LawRng(seed)

/** Seeded invariants for Intaglio's native statistical kernels. These laws use the public
  * `Stat.compute` boundary and typed result rows; they do not rely on renderer output.
  */
object NativeStatLaws:
  private final case class Datum(
      id: Int,
      category: String,
      group: String,
      x: Double,
      y: Double
  )

  private val context = StatContext(0, Geom.Bar, StatScope.Plot)
  private val categories = Vector("alpha", "beta", "gamma", "delta")
  private val groups = Vector("one", "two", "three")
  private val histogramBreaks = Vector(-4.0, -2.0, 0.0, 2.0, 4.0)

  def apply(seeds: Vector[Long] = SeededLaw.defaultSeeds): LawSuite =
    LawSuite(
      "native-statistics",
      Vector(
        SeededLaw("count and bin mass", seeds)(massProblems),
        SeededLaw("right-closed histogram intervals", seeds)(intervalProblems),
        SeededLaw("summary bounds", seeds)(summaryProblems),
        SeededLaw("density integrates to one", seeds)(densityProblems),
        SeededLaw("contour topology", seeds)(contourProblems),
        SeededLaw("explicit declared order is permutation invariant", seeds)(orderProblems)
      )
    )

  private def batch(rows: Vector[Datum]): StatBatch[Datum] =
    StatBatch(rows, AesSpec.empty[Datum])

  private def countFixture(seed: Long): Vector[Datum] =
    val rng = LawRng(seed)
    val size = 24 + rng.nextInt(24)
    Vector.tabulate(size) { index =>
      Datum(
        index,
        categories(rng.nextInt(categories.length)),
        groups(rng.nextInt(groups.length)),
        x = 0.0,
        y = 0.0
      )
    }

  private def histogramFixture(seed: Long): Vector[Datum] =
    val rng = LawRng(seed ^ 0x62696e6d617373L)
    val boundaries = histogramBreaks.zipWithIndex.map { case (value, index) =>
      Datum(index, "boundary", "all", value, 0.0)
    }
    boundaries ++ Vector.tabulate(32 + rng.nextInt(32)) { offset =>
      Datum(
        boundaries.length + offset,
        "sample",
        "all",
        rng.between(histogramBreaks.head, histogramBreaks.last),
        0.0
      )
    }

  private def massProblems(seed: Long): Vector[String] =
    val countInput = countFixture(seed)
    val countResult = Stat
      .Count[Datum](_.category, group = Some(_.group))
      .compute(batch(countInput), context)
    val countProblems = countResult match
      case Left(error)   => Vector(s"count rejected a valid fixture: ${error.message}")
      case Right(result) =>
        val rows = result.rows
        val observedIds = rows.flatMap(_.members.map(_.id)).sorted
        LawDiagnostics.problemWhen(
          rows.map(_.count).sum != countInput.length,
          s"count mass=${rows.map(_.count).sum}, input=${countInput.length}"
        ) ++ LawDiagnostics.problemWhen(
          observedIds != countInput.map(_.id).sorted,
          s"count members do not partition input ids: $observedIds"
        ) ++ LawDiagnostics.problemWhen(
          !approximately(rows.map(_.proportion).sum, 1.0, 1e-12),
          s"count proportions sum to ${rows.map(_.proportion).sum}"
        )

    val binInput = histogramFixture(seed)
    val binResult = Stat
      .Bin[Datum](_.x, HistogramBins.breaksUnsafe(histogramBreaks))
      .compute(batch(binInput), context)
    val binProblems = binResult match
      case Left(error)   => Vector(s"bin rejected a valid fixture: ${error.message}")
      case Right(result) =>
        val rows = result.rows
        val observedIds = rows.flatMap(_.members.map(_.id)).sorted
        val probabilityMass = rows.map(row => row.density * row.binWidth).sum
        LawDiagnostics.problemWhen(
          rows.map(_.count).sum != binInput.length,
          s"bin mass=${rows.map(_.count).sum}, input=${binInput.length}"
        ) ++ LawDiagnostics.problemWhen(
          observedIds != binInput.map(_.id).sorted,
          s"bin members do not partition input ids: $observedIds"
        ) ++ LawDiagnostics.problemWhen(
          !approximately(rows.map(_.proportion).sum, 1.0, 1e-12),
          s"bin proportions sum to ${rows.map(_.proportion).sum}"
        ) ++ LawDiagnostics.problemWhen(
          !approximately(probabilityMass, 1.0, 1e-12),
          s"bin density mass is $probabilityMass"
        )

    countProblems ++ binProblems

  private def intervalProblems(seed: Long): Vector[String] =
    val input = histogramFixture(seed)
    Stat
      .Bin[Datum](_.x, HistogramBins.breaksUnsafe(histogramBreaks))
      .compute(batch(input), context) match
      case Left(error)   => Vector(s"bin rejected a valid fixture: ${error.message}")
      case Right(result) =>
        val rows = result.rows
        val membershipProblems = rows.zipWithIndex.flatMap { case (row, index) =>
          row.members.flatMap { member =>
            val aboveLower =
              if index == 0 then member.x >= row.binLower else member.x > row.binLower
            LawDiagnostics.problemWhen(
              !aboveLower || member.x > row.binUpper,
              s"value ${member.x} assigned to (${row.binLower}, ${row.binUpper}] at bin $index"
            )
          }
        }
        val boundaryProblems = histogramBreaks.zipWithIndex.flatMap { case (boundary, index) =>
          rows.find(_.members.exists(_.id == index)) match
            case None                    => Vector(s"boundary $boundary was not retained")
            case Some(row) if index == 0 =>
              LawDiagnostics.problemWhen(
                row.binLower != boundary,
                s"lower endpoint $boundary entered [${row.binLower}, ${row.binUpper}]"
              )
            case Some(row) =>
              LawDiagnostics.problemWhen(
                row.binUpper != boundary,
                s"right-closed boundary $boundary entered [${row.binLower}, ${row.binUpper}]"
              )
        }
        membershipProblems ++ boundaryProblems

  private def summaryFixture(seed: Long): Vector[Datum] =
    val rng = LawRng(seed ^ 0x73756d6d617279L)
    Vector
      .tabulate(3) { groupIndex =>
        Vector.tabulate(4 + rng.nextInt(5)) { withinGroup =>
          val id = groupIndex * 16 + withinGroup
          Datum(
            id,
            "summary",
            groupIndex.toString,
            groupIndex.toDouble,
            rng.between(-10.0, 10.0)
          )
        }
      }
      .flatten

  private def summaryProblems(seed: Long): Vector[String] =
    val input = summaryFixture(seed)
    val rangeResult = Stat
      .Summary[Datum](_.x, _.y, SummaryInterval.Range)
      .compute(batch(input), context.copy(geom = Geom.Point))
    val standardErrorResult = Stat
      .Summary[Datum](_.x, _.y, SummaryInterval.StandardError)
      .compute(batch(input), context.copy(geom = Geom.Point))

    type SummaryResult =
      Either[StatError, StatResult.Aux[Datum, StatRow.Summarized[Datum]]]

    def baseProblems(result: SummaryResult, label: String): Vector[String] =
      result match
        case Left(error)  => Vector(s"$label summary rejected a valid fixture: ${error.message}")
        case Right(value) =>
          value.rows.zipWithIndex.flatMap { case (row, index) =>
            val expectedMean = row.members.map(_.y).sum / row.members.length.toDouble
            LawDiagnostics.problemWhen(
              !(row.lower <= row.mean && row.mean <= row.upper),
              s"$label row $index has ${row.lower} > ${row.mean} or ${row.mean} > ${row.upper}"
            ) ++ LawDiagnostics.problemWhen(
              !approximately(row.mean, expectedMean, 1e-12),
              s"$label row $index mean=${row.mean}, oracle=$expectedMean"
            )
          }

    val rangeSpecific = rangeResult match
      case Right(result) =>
        result.rows.zipWithIndex.flatMap { case (row, index) =>
          val values = row.members.map(_.y)
          LawDiagnostics.problemWhen(
            row.lower != values.min || row.upper != values.max,
            s"range row $index=[${row.lower}, ${row.upper}], oracle=[${values.min}, ${values.max}]"
          )
        }
      case Left(_) => Vector.empty
    val standardErrorSpecific = standardErrorResult match
      case Right(result) =>
        result.rows.zipWithIndex.flatMap { case (row, index) =>
          LawDiagnostics.problemWhen(
            !approximately(row.mean - row.lower, row.upper - row.mean, 1e-12),
            s"standard-error row $index is asymmetric: ${row.lower}, ${row.mean}, ${row.upper}"
          )
        }
      case Left(_) => Vector.empty

    baseProblems(rangeResult, "range") ++ rangeSpecific ++
      baseProblems(standardErrorResult, "standard-error") ++ standardErrorSpecific

  private def densityProblems(seed: Long): Vector[String] =
    val rng = LawRng(seed ^ 0x64656e73697479L)
    val input = Vector.tabulate(12 + rng.nextInt(13)) { index =>
      Datum(index, "density", "all", rng.between(-2.0, 2.0), 0.0)
    }
    val config = DensityConfig.fixedUnsafe(
      bandwidth = 0.5,
      points = 1025,
      domain = Some(Interval.unsafe(-8.0, 8.0))
    )
    Stat
      .Density[Datum](_.x, config)
      .compute(batch(input), context.copy(geom = Geom.Line)) match
      case Left(error)   => Vector(s"density rejected a valid fixture: ${error.message}")
      case Right(result) =>
        val rows = result.rows
        val integral = rows
          .sliding(2)
          .map {
            case Vector(left, right) =>
              (left.density + right.density) * 0.5 * (right.position - left.position)
            case _ => 0.0
          }
          .sum
        LawDiagnostics.problemWhen(
          !rows.forall(row => row.density >= 0.0 && row.density.isFinite),
          "density emitted a negative or non-finite ordinate"
        ) ++ LawDiagnostics.problemWhen(
          rows.exists(row => row.sampleSize != input.length || row.members != input),
          "density rows did not retain the complete input sample"
        ) ++ LawDiagnostics.problemWhen(
          !approximately(integral, 1.0, 1e-6),
          s"trapezoidal integral=$integral over [-8, 8]"
        )

  private def contourProblems(seed: Long): Vector[String] =
    val rng = LawRng(seed ^ 0x636f6e746f7572L)
    val centerX = rng.between(-0.2, 0.2)
    val centerY = rng.between(-0.2, 0.2)
    val axis = RegularGridAxis.vertexCenteredUnsafe(-2.0, 2.0, 41)
    val field = ScalarField2D
      .tabulate(axis, axis) { (x, y) =>
        val dx = x - centerX
        val dy = y - centerY
        dx * dx + dy * dy
      }
      .orThrow
    val lines = ContourSet.extract(field, ContourLevels.atUnsafe(Vector(1.0)))
    val bands = ContourBandSet.extract(field, ContourBreaks.atUnsafe(Vector(0.25, 1.0)))

    val lineProblems = lines match
      case Left(error) => Vector(s"line contour failed: ${error.message}")
      case Right(set)  =>
        set.lines match
          case Vector(line) if line.paths.length == 1 =>
            val path = line.paths.head
            val body = path.points.dropRight(1)
            LawDiagnostics.problemWhen(!path.isClosed, "radial contour is not closed") ++
              LawDiagnostics.problemWhen(
                body.lengthCompare(8) < 0 || body.distinct.length != body.length,
                s"closed contour has ${body.length} points and ${body.distinct.length} unique vertices"
              ) ++ LawDiagnostics.problemWhen(
                path.points.sliding(2).exists {
                  case Vector(left, right) => left == right
                  case _                   => false
                },
                "contour contains a zero-length edge"
              )
          case other =>
            Vector(s"expected one level with one path, obtained ${other.map(_.paths.length)}")

    val bandProblems = bands match
      case Left(error) => Vector(s"filled contour failed: ${error.message}")
      case Right(set)  =>
        set.bands match
          case Vector(band) if band.regions.length == 1 =>
            val region = band.regions.head
            LawDiagnostics.problemWhen(
              region.outer.winding != RingWinding.CounterClockwise,
              s"outer winding=${region.outer.winding}"
            ) ++ LawDiagnostics.problemWhen(
              region.holes.length != 1 ||
                region.holes.exists(_.winding != RingWinding.Clockwise),
              s"expected one clockwise hole, obtained ${region.holes.map(_.winding)}"
            )
          case other =>
            Vector(s"expected one annular band and region, obtained ${other.map(_.regions.length)}")

    lineProblems ++ bandProblems

  private def orderProblems(seed: Long): Vector[String] =
    val rng = LawRng(seed ^ 0x6f72646572L)
    val input = categories.zipWithIndex.flatMap { case (category, categoryIndex) =>
      Vector.tabulate(2 + rng.nextInt(5)) { withinCategory =>
        Datum(categoryIndex * 16 + withinCategory, category, "all", 0.0, 0.0)
      }
    }
    val order = CountOrder.declaredUnsafe(categories)
    val stat = Stat.Count[Datum](_.category, order = order)

    def snapshot(rows: Vector[Datum]): Either[StatError, Vector[(String, Int, Double)]] =
      stat
        .compute(batch(rows), context)
        .map(_.rows.map(row => (row.level, row.count, row.proportion)))

    val original = snapshot(input)
    val permuted = snapshot(rng.shuffle(input))
    (original, permuted) match
      case (Right(left), Right(right)) =>
        LawDiagnostics.problemWhen(
          left != right || left.map(_._1) != categories,
          s"original=$left, permuted=$right, declared=$categories"
        )
      case (Left(error), _) => Vector(s"original count failed: ${error.message}")
      case (_, Left(error)) => Vector(s"permuted count failed: ${error.message}")

  private def approximately(left: Double, right: Double, tolerance: Double): Boolean =
    val scale = math.max(1.0, math.max(math.abs(left), math.abs(right)))
    math.abs(left - right) <= tolerance * scale

/** Seeded invariants for the pure native position phase. The suite checks rows before geom lowering
  * so backend behavior cannot hide an adjustment defect.
  */
object NativePositionLaws:
  private final case class Datum(id: Int, category: String, group: String, x: Double, y: Double)

  def apply(seeds: Vector[Long] = SeededLaw.defaultSeeds): LawSuite =
    LawSuite(
      "native-positions",
      Vector(
        SeededLaw("identity", seeds)(identityProblems),
        SeededLaw("deterministic bounded jitter", seeds)(jitterProblems),
        SeededLaw("non-overlapping dodge", seeds)(dodgeProblems),
        SeededLaw("signed stack", seeds)(stackProblems),
        SeededLaw("explicit stack order preserves row identity", seeds)(orderProblems)
      )
    )

  private def pointLayer(position: Position): Layer[Datum] =
    Layer.point[Datum](_.x, _.y, inheritMapping = false, position = position)

  private def barLayer(position: Position): Layer[Datum] =
    Layer.count[Datum](_.category, group = Some(_.group), position = position)

  private def row(
      value: Datum,
      band: Option[Band] = None
  ): ResolvedRow[Datum] =
    ResolvedRow(
      rowIndex = value.id,
      source = value,
      statRow = StatRow.Identity(value),
      x = value.x,
      y = value.y,
      xBand = band,
      yBand = None,
      xEnd = Some(value.x + 0.1),
      yEnd = Some(value.y + 0.1),
      xMin = Some(value.x - band.fold(0.1)(_.width / 2.0)),
      xMax = Some(value.x + band.fold(0.1)(_.width / 2.0)),
      yMin = Some(math.min(0.0, value.y)),
      yMax = Some(math.max(0.0, value.y)),
      point = Point.nativeUnsafe(value.x, value.y),
      label = None,
      grouping = GroupingDecision.Explicit,
      groupKey = Some(GroupKey.Explicit(value.group)),
      group = Some(value.group),
      subpath = None,
      gp = GraphicParams.unsafe(),
      size = ExtentExpr.pointsUnsafe(4.0),
      xCategoryIdentity = Some(CategoryToken(value.category, CategoryIdentity.strings))
    )

  private def pointFixture(seed: Long): Vector[ResolvedRow[Datum]] =
    val rng = LawRng(seed ^ 0x706f696e74L)
    Vector.tabulate(12) { index =>
      row(
        Datum(
          index,
          s"p${index % 3}",
          s"g${index % 4}",
          rng.between(-4.0, 4.0),
          rng.between(-4.0, 4.0)
        )
      )
    }

  private def identityProblems(seed: Long): Vector[String] =
    val rows = pointFixture(seed)
    PositionPhase.adjust(pointLayer(Position.Identity), rows) match
      case Left(error)     => Vector(s"identity failed: ${error.message}")
      case Right(adjusted) =>
        LawDiagnostics.problemWhen(adjusted != rows, "identity changed resolved rows")

  private def jitterProblems(seed: Long): Vector[String] =
    val rows = pointFixture(seed)
    val width = 0.25
    val height = 0.15
    val jitter = Position.jitterUnsafe(seed, width = Some(width), height = Some(height))
    val other = Position.jitterUnsafe(seed ^ 0x6a6974746572L, Some(width), Some(height))
    val first = PositionPhase.adjust(pointLayer(jitter), rows)
    val second = PositionPhase.adjust(pointLayer(jitter), rows)
    val different = PositionPhase.adjust(pointLayer(other), rows)
    (first, second, different) match
      case (Right(left), Right(repeated), Right(changed)) =>
        val translationProblems =
          left.zip(rows).zipWithIndex.flatMap { case ((after, before), index) =>
            val dx = after.x - before.x
            val dy = after.y - before.y
            def translated(
                actual: Option[Double],
                original: Option[Double],
                delta: Double
            ): Boolean =
              (actual, original) match
                case (Some(result), Some(source)) =>
                  approximately(result, source + delta, 1e-14)
                case (None, None) => true
                case _            => false
            val translatedBounds =
              translated(after.xMin, before.xMin, dx) &&
                translated(after.xMax, before.xMax, dx) &&
                translated(after.yMin, before.yMin, dy) &&
                translated(after.yMax, before.yMax, dy)
            LawDiagnostics.problemWhen(
              math.abs(dx) > width || math.abs(dy) > height || !translatedBounds ||
                after.point != Point.nativeUnsafe(after.x, after.y),
              s"row $index offset=($dx, $dy), translatedBounds=$translatedBounds"
            )
          }
        LawDiagnostics.problemWhen(left != repeated, "same jitter seed produced different rows") ++
          LawDiagnostics.problemWhen(
            left == changed,
            "a distinct jitter seed produced identical rows"
          ) ++
          LawDiagnostics.problemWhen(
            left.map(_.source) != rows.map(_.source),
            "jitter changed source order"
          ) ++ translationProblems
      case _ =>
        Vector(s"jitter adjustment failed: first=$first, second=$second, different=$different")

  private def dodgeFixture(seed: Long): Vector[ResolvedRow[Datum]] =
    val rng = LawRng(seed ^ 0x646f646765L)
    val categories = Vector("A", "B", "C")
    val groups = Vector("red", "green", "blue")
    val values = categories.zipWithIndex.flatMap { case (category, categoryIndex) =>
      groups.zipWithIndex.collect {
        case (group, groupIndex) if categoryIndex == 0 || (categoryIndex + groupIndex) % 3 != 0 =>
          Datum(
            categoryIndex * groups.length + groupIndex,
            category,
            group,
            categoryIndex.toDouble,
            rng.between(0.5, 5.0)
          )
      }
    }
    rng.shuffle(values).map(value => row(value, Some(Band.unsafe(value.x, 0.9))))

  private def dodgeProblems(seed: Long): Vector[String] =
    val rows = dodgeFixture(seed)
    def check(preserve: DodgePreserve): Vector[String] =
      PositionPhase.adjust(barLayer(Position.Dodge(DodgeConfig(preserve = preserve))), rows) match
        case Left(error)   => Vector(s"$preserve dodge failed: ${error.message}")
        case Right(values) =>
          val bucketProblems =
            values.groupBy(_.source.category).toVector.flatMap { case (category, bucket) =>
              val center = bucket.head.source.x
              val bands = bucket.flatMap(_.xBand).sortBy(_.lower)
              val overlaps = bands.sliding(2).exists {
                case Vector(left, right) => left.upper > right.lower + 1e-12
                case _                   => false
              }
              LawDiagnostics.problemWhen(
                bands.length != bucket.length || overlaps ||
                  bands.exists(band =>
                    band.lower < center - 0.45 - 1e-12 || band.upper > center + 0.45 + 1e-12
                  ),
                s"$preserve category $category produced bands $bands within center $center"
              )
            }
          LawDiagnostics.problemWhen(
            values.map(_.source) != rows.map(_.source),
            s"$preserve dodge changed source order"
          ) ++ bucketProblems

    check(DodgePreserve.Total) ++ check(DodgePreserve.Single)

  private def stackFixture(seed: Long): Vector[ResolvedRow[Datum]] =
    val rng = LawRng(seed ^ 0x737461636bL)
    val categories = Vector("A", "B")
    val groupNames = Vector("red", "green", "blue", "orange")
    val values = categories.zipWithIndex.flatMap { case (category, categoryIndex) =>
      groupNames.zipWithIndex.map { case (group, groupIndex) =>
        val sign = if groupIndex % 2 == 0 then 1.0 else -1.0
        Datum(
          categoryIndex * groupNames.length + groupIndex,
          category,
          group,
          categoryIndex.toDouble,
          sign * rng.between(0.5, 5.0)
        )
      }
    }
    rng.shuffle(values).map(value => row(value, Some(Band.unsafe(value.x, 0.9))))

  private def stackProblems(seed: Long): Vector[String] =
    val rows = stackFixture(seed)
    PositionPhase.adjust(barLayer(Position.Stack(StackOrder.Reverse)), rows) match
      case Left(error)   => Vector(s"stack failed: ${error.message}")
      case Right(values) =>
        val bucketProblems =
          values.groupBy(_.source.category).toVector.flatMap { case (category, bucket) =>
            val positive = bucket.filter(_.source.y >= 0.0)
            val negative = bucket.filter(_.source.y < 0.0)
            def interval(value: ResolvedRow[Datum]): Option[(Double, Double)] =
              for
                lower <- value.yMin
                upper <- value.yMax
              yield lower -> upper
            val positiveIntervals = positive.flatMap(interval).sortBy(_._1)
            val negativeIntervals = negative.flatMap(interval).sortBy(_._1)
            val positiveContiguous = contiguous(positiveIntervals) &&
              approximately(positiveIntervals.head._1, 0.0, 1e-12) &&
              approximately(positiveIntervals.last._2, positive.map(_.source.y).sum, 1e-12)
            val negativeContiguous = contiguous(negativeIntervals) &&
              approximately(negativeIntervals.head._1, negative.map(_.source.y).sum, 1e-12) &&
              approximately(negativeIntervals.last._2, 0.0, 1e-12)
            val anchors = bucket.forall(value =>
              (value.yMin, value.yMax) match
                case (Some(lower), Some(upper)) =>
                  if value.source.y >= 0.0 then value.y == upper else value.y == lower
                case _ => false
            )
            LawDiagnostics.problemWhen(
              positiveIntervals.length != positive.length ||
                negativeIntervals.length != negative.length ||
                !positiveContiguous || !negativeContiguous || !anchors,
              s"category $category positive=$positiveIntervals negative=$negativeIntervals anchors=$anchors"
            )
          }
        LawDiagnostics.problemWhen(
          values.map(_.source) != rows.map(_.source),
          "stack changed source order"
        ) ++ bucketProblems

  private def orderProblems(seed: Long): Vector[String] =
    val rows = stackFixture(seed)
    val encountered = PositionPhase.adjust(
      barLayer(Position.Stack(StackOrder.Encountered)),
      rows
    )
    val reverse = PositionPhase.adjust(barLayer(Position.Stack(StackOrder.Reverse)), rows)
    (encountered, reverse) match
      case (Right(left), Right(right)) =>
        def envelopes(values: Vector[ResolvedRow[Datum]]): Map[String, (Double, Double)] =
          values
            .groupBy(_.source.category)
            .view
            .mapValues { bucket =>
              bucket.flatMap(_.yMin).min -> bucket.flatMap(_.yMax).max
            }
            .toMap
        LawDiagnostics.problemWhen(
          left.map(_.source) != rows.map(_.source) || right.map(_.source) != rows.map(_.source),
          "an explicit StackOrder changed row identity or source order"
        ) ++ LawDiagnostics.problemWhen(
          envelopes(left) != envelopes(right),
          s"stack envelopes changed with order: encountered=${envelopes(left)}, reverse=${envelopes(right)}"
        ) ++ LawDiagnostics.problemWhen(
          left.map(row => row.yMin -> row.yMax) == right.map(row => row.yMin -> row.yMax),
          "Encountered and Reverse stack orders produced identical placements"
        )
      case _ => Vector(s"explicit stack orders failed: encountered=$encountered, reverse=$reverse")

  private def contiguous(intervals: Vector[(Double, Double)]): Boolean =
    intervals.nonEmpty && intervals.sliding(2).forall {
      case Vector(left, right) => approximately(left._2, right._1, 1e-12)
      case _                   => true
    }

  private def approximately(left: Double, right: Double, tolerance: Double): Boolean =
    val scale = math.max(1.0, math.max(math.abs(left), math.abs(right)))
    math.abs(left - right) <= tolerance * scale
