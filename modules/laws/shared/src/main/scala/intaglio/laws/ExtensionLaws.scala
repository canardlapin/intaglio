package intaglio.laws

import intaglio.*

/** Laws for an ecosystem-defined typed aesthetic and the reference-identity storage contract. */
object AestheticLaws:
  def apply[Row, A](
      key: Aesthetic[A],
      sameLabelKey: Aesthetic[A],
      first: AesValue[Row, A],
      replacement: AesValue[Row, A]
  ): LawSuite =
    val inserted = AestheticMap.empty[Row].updated(key, first)
    val replaced = inserted.updated(key, replacement)
    val removed = replaced.removed(key)
    LawSuite(
      s"aesthetic:${key.label}",
      Vector(
        Law(
          "typed round trip",
          () =>
            LawDiagnostics.problemWhen(
              !inserted.get(key).contains(first) || !inserted.keys.exists(_ eq key),
              "the inserted typed value was not recovered with the same key"
            )
        ),
        Law(
          "reference identity",
          () =>
            LawDiagnostics.problemWhen(
              (key eq sameLabelKey) || key.label != sameLabelKey.label ||
                inserted.get(sameLabelKey).nonEmpty,
              "an independently created key with the same label was not isolated"
            )
        ),
        Law(
          "replacement is unique",
          () =>
            LawDiagnostics.problemWhen(
              !replaced.get(key).contains(replacement) || replaced.keys.count(_ eq key) != 1,
              "updating one key did not replace its value exactly once"
            )
        ),
        Law(
          "removal",
          () =>
            LawDiagnostics.problemWhen(
              removed.get(key).nonEmpty || removed.keys.exists(_ eq key),
              "removing the key left a value or key entry behind"
            )
        )
      )
    )

/** Determinism and public descriptor laws for an ecosystem [[Scale]]. */
object ScaleLaws:
  def apply[In, Out](scale: Scale[In, Out], samples: Vector[In]): LawSuite =
    withEquality(scale, samples)(_ == _)

  def withEquality[In, Out](
      scale: Scale[In, Out],
      samples: Vector[In]
  )(equivalent: (Out, Out) => Boolean): LawSuite =
    def sameOption(left: Option[Out], right: Option[Out]): Boolean =
      (left, right) match
        case (Some(first), Some(second)) => equivalent(first, second)
        case (None, None)                => true
        case _                           => false

    LawSuite(
      s"scale:${scale.name.value}",
      Vector(
        Law(
          "non-empty fixture",
          () => LawDiagnostics.problemWhen(samples.isEmpty, "provide at least one sample value")
        ),
        Law(
          "stable descriptor",
          () =>
            val first = scale.descriptor
            val second = scale.descriptor
            LawDiagnostics.problemWhen(
              scale.name.value.trim.isEmpty || first.name != scale.name || first != second,
              s"name=${scale.name.value}, first=$first, second=$second"
            )
        ),
        Law(
          "deterministic mapping",
          () =>
            samples.zipWithIndex.flatMap { case (sample, index) =>
              val first = scale.mapValue(sample)
              val second = scale.mapValue(sample)
              LawDiagnostics.problemWhen(
                !sameOption(first, second),
                s"sample $index (${LawDiagnostics.show(sample)}) mapped to $first then $second"
              )
            }
        ),
        Law(
          "optional and result mapping agree",
          () =>
            samples.zipWithIndex.flatMap { case (sample, index) =>
              val optional = scale.mapValue(sample)
              val result = scale.mapValueResult(sample)
              val agrees =
                (optional, result) match
                  case (Some(first), Right(second)) => equivalent(first, second)
                  case (None, Left(_))              => true
                  case _                            => false
              LawDiagnostics.problemWhen(
                !agrees,
                s"sample $index (${LawDiagnostics.show(sample)}): mapValue=$optional, mapValueResult=$result"
              )
            }
        )
      )
    )

/** Public contract, source-preservation, and determinism laws for an ecosystem [[Stat]]. */
object StatLaws:
  def apply[Row, Snapshot](
      stat: Stat[Row],
      successfulBatch: StatBatch[Row],
      context: StatContext
  )(observe: StatResult[Row] => Snapshot): LawSuite =
    withEquality(stat, successfulBatch, context)(observe)(_ == _)

  def withEquality[Row, Snapshot](
      stat: Stat[Row],
      successfulBatch: StatBatch[Row],
      context: StatContext
  )(observe: StatResult[Row] => Snapshot)(equivalent: (Snapshot, Snapshot) => Boolean): LawSuite =
    def preservation(result: StatResult[Row]): Vector[String] =
      val outputs = result.rows
      stat.contract.inputPreservation match
        case StatInputPreservation.OneToOne =>
          val size = LawDiagnostics.problemWhen(
            outputs.length != successfulBatch.size,
            s"declared one-to-one but produced ${outputs.length} rows from ${successfulBatch.size} inputs"
          )
          val members = outputs.zipWithIndex.flatMap { case (output, index) =>
            LawDiagnostics.problemWhen(
              output.members != Vector(output.source),
              s"output $index does not preserve its source as its sole member"
            )
          }
          var unmatched = successfulBatch.rows
          outputs.foreach { output =>
            val index = unmatched.indexOf(output.source)
            if index >= 0 then unmatched = unmatched.patch(index, Vector.empty, 1)
          }
          val multiplicity =
            LawDiagnostics.problemWhen(
              unmatched.nonEmpty || outputs.length != successfulBatch.size,
              "one-to-one output sources do not match input multiplicities"
            )
          size ++ members ++ multiplicity
        case StatInputPreservation.AggregateMembers =>
          outputs.zipWithIndex.flatMap { case (output, index) =>
            LawDiagnostics.problemWhen(
              output.members.isEmpty || !output.members.contains(output.source) ||
                output.members.exists(value => !successfulBatch.rows.contains(value)),
              s"aggregate output $index has empty, foreign, or source-excluding members"
            )
          }
        case StatInputPreservation.WholeBatch =>
          outputs.zipWithIndex.flatMap { case (output, index) =>
            LawDiagnostics.problemWhen(
              output.members != successfulBatch.rows || !output.members.contains(output.source),
              s"whole-batch output $index does not retain the complete input batch"
            )
          }
        case StatInputPreservation.Custom(_) =>
          Vector.empty

    LawSuite(
      s"stat:${stat.label}",
      Vector(
        Law(
          "stable public contract",
          () =>
            val first = stat.contract
            val second = stat.contract
            LawDiagnostics.problemWhen(
              stat.label.trim.isEmpty || first != second,
              s"label='${stat.label}', first=$first, second=$second"
            )
        ),
        Law(
          "successful fixture",
          () =>
            stat.compute(successfulBatch, context) match
              case Left(error) => Vector(s"fixture was rejected: ${error.message}")
              case Right(_)    => Vector.empty
        ),
        Law(
          "deterministic computation",
          () =>
            val first = stat.compute(successfulBatch, context)
            val second = stat.compute(successfulBatch, context)
            val agrees =
              (first, second) match
                case (Left(left), Left(right))   => left == right
                case (Right(left), Right(right)) => equivalent(observe(left), observe(right))
                case _                           => false
            LawDiagnostics.problemWhen(!agrees, "the same batch produced different observations")
        ),
        Law(
          "declared input preservation",
          () =>
            stat.compute(successfulBatch, context) match
              case Left(error)   => Vector(s"fixture was rejected: ${error.message}")
              case Right(result) => preservation(result)
        )
      )
    )

/** Contract and deterministic lowering laws for an ecosystem [[Geom]]. */
object GeomLaws:
  def apply[Row](geom: Geom, successfulBatch: GeomBatch[Row]): LawSuite =
    LawSuite(
      s"geom:${geom.label}",
      Vector(
        Law(
          "checked public contract",
          () =>
            val contract = geom.contract
            val checked = GeomAestheticContract.checked(
              contract.required,
              contract.optional,
              contract.groupConstant
            )
            LawDiagnostics.problemWhen(
              geom.label.trim.isEmpty || checked != Right(contract) || geom.contract != contract,
              s"label='${geom.label}', checked=$checked"
            )
        ),
        Law(
          "successful fixture",
          () =>
            geom.lower(successfulBatch) match
              case Left(error) => Vector(s"fixture was rejected: ${error.message}")
              case Right(_)    => Vector.empty
        ),
        Law(
          "deterministic lowering",
          () =>
            val first = geom.lower(successfulBatch)
            val second = geom.lower(successfulBatch)
            LawDiagnostics.problemWhen(
              first != second,
              "the same resolved batch lowered differently"
            )
        )
      )
    )

/** Determinism and layer-preservation laws for an ecosystem [[Coord]]. */
object CoordLaws:
  def apply(
      coord: Coord,
      successfulInput: CoordInput,
      xRange: Interval,
      yRange: Interval
  ): LawSuite =
    withEquality(coord, successfulInput, xRange, yRange)(samePublicResult)

  def withEquality(
      coord: Coord,
      successfulInput: CoordInput,
      xRange: Interval,
      yRange: Interval
  )(equivalent: (CoordResult, CoordResult) => Boolean): LawSuite =
    def sameTransform(
        first: Either[GraphicsError, CoordResult],
        second: Either[GraphicsError, CoordResult]
    ): Boolean =
      (first, second) match
        case (Left(left), Left(right))   => left == right
        case (Right(left), Right(right)) => equivalent(left, right)
        case _                           => false

    LawSuite(
      "coord",
      Vector(
        Law(
          "successful layer-preserving transform",
          () =>
            coord.transform(successfulInput) match
              case Left(error)   => Vector(s"fixture was rejected: ${error.message}")
              case Right(result) =>
                LawDiagnostics.problemWhen(
                  result.layers.length != successfulInput.layers.length,
                  s"transformed ${successfulInput.layers.length} layers into ${result.layers.length}"
                )
        ),
        Law(
          "deterministic transform",
          () =>
            val first = coord.transform(successfulInput)
            val second = coord.transform(successfulInput)
            LawDiagnostics.problemWhen(
              !sameTransform(first, second),
              "the same coordinate input transformed differently"
            )
        ),
        Law(
          "deterministic layout declarations",
          () =>
            val first = (
              coord.clipping,
              coord.guideLayout(xRange, yRange),
              coord.panelAspect(xRange, yRange),
              coord.validateFacet
            )
            val second = (
              coord.clipping,
              coord.guideLayout(xRange, yRange),
              coord.panelAspect(xRange, yRange),
              coord.validateFacet
            )
            LawDiagnostics.problemWhen(first != second, s"first=$first, second=$second")
        )
      )
    )

  private def samePublicResult(left: CoordResult, right: CoordResult): Boolean =
    left.ranges == right.ranges && left.layers.length == right.layers.length &&
      left.layers.zip(right.layers).forall { case (first, second) =>
        first.layerIndex == second.layerIndex && first.geom == second.geom &&
        first.stat.label == second.stat.label && first.stat.contract == second.stat.contract &&
        first.position == second.position && first.dataSize == second.dataSize &&
        first.annotation == second.annotation && first.grouping == second.grouping &&
        first.scaleDeclarations == second.scaleDeclarations && first.rows == second.rows &&
        first.droppedRows == second.droppedRows && first.grobs == second.grobs
      }

/** Conversion, compiler-bridge, and scene determinism laws for an ecosystem [[PlotRecipe]]. */
object PlotRecipeLaws:
  def apply[Source, Row, Snapshot](
      source: Source,
      recipe: PlotRecipe.Aux[Source, Row]
  )(observe: PlotSpec[Row] => Snapshot): LawSuite =
    withEquality(source, recipe)(observe)(_ == _)

  def withEquality[Source, Row, Snapshot](
      source: Source,
      recipe: PlotRecipe.Aux[Source, Row]
  )(observe: PlotSpec[Row] => Snapshot)(equivalent: (Snapshot, Snapshot) => Boolean): LawSuite =
    LawSuite(
      "plot-recipe",
      Vector(
        Law(
          "successful fixture",
          () =>
            recipe(source) match
              case Left(error) => Vector(s"fixture was rejected: ${error.message}")
              case Right(_)    => Vector.empty
        ),
        Law(
          "deterministic conversion",
          () =>
            val first = recipe(source)
            val second = recipe(source)
            val agrees =
              (first, second) match
                case (Left(left), Left(right))   => left == right
                case (Right(left), Right(right)) => equivalent(observe(left), observe(right))
                case _                           => false
            LawDiagnostics.problemWhen(!agrees, "the same source converted differently")
        ),
        Law(
          "program bridge",
          () =>
            recipe(source) match
              case Left(error) => Vector(s"fixture was rejected: ${error.message}")
              case Right(spec) =>
                LawDiagnostics.problemWhen(
                  PlotSpec.fromProgram(spec.program) != spec,
                  "PlotSpec -> PlotProgram -> PlotSpec did not retain the compiler input"
                )
        ),
        Law(
          "deterministic scene",
          () =>
            recipe(source) match
              case Left(error) => Vector(s"fixture was rejected: ${error.message}")
              case Right(spec) =>
                val first = spec.scene
                val second = spec.scene
                first match
                  case Left(error) =>
                    Vector(s"recipe produced an uncompilable spec: ${error.message}")
                  case Right(_) =>
                    LawDiagnostics.problemWhen(
                      first != second,
                      "the same specification compiled differently"
                    )
        )
      )
    )

/** Full renderer conformance wrapped as a framework-neutral law suite. */
object BackendLaws:
  def apply[Out](harness: RendererHarness[Out]): LawSuite =
    LawSuite(
      "backend",
      Vector(
        Law(
          "renderer conformance",
          () =>
            RendererConformance.check(harness) match
              case Left(error) => Vector(s"could not construct conformance cases: ${error.message}")
              case Right(violations) =>
                violations.map(violation =>
                  s"${violation.group}/${violation.caseName}: ${violation.problem}"
                )
        )
      )
    )
