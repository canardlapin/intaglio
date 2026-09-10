package intaglio.laws

import intaglio.*

/** Direction claimed by a numeric transform over the supplied ordered fixture. */
enum TransformMonotonicity:
  case Increasing
  case Decreasing

/** Executable round-trip, monotonicity, and domain-endpoint laws for a [[Transform]].
  *
  * `validSamples` are sorted by the kit before monotonicity is checked. Open and closed endpoint
  * behavior comes from the transform's own [[TransformDomain]], so a fixture does not silently
  * redefine the advertised domain.
  */
object TransformLaws:
  def apply(
      transform: Transform,
      validSamples: Vector[Double],
      monotonicity: TransformMonotonicity,
      tolerance: Double = 1e-10
  ): LawSuite =
    val orderedSamples = validSamples.distinct.sorted

    def approximatelyEqual(left: Double, right: Double): Boolean =
      val scale = math.max(1.0, math.max(math.abs(left), math.abs(right)))
      math.abs(left - right) <= tolerance * scale

    def endpointProblems(bound: DomainBound, label: String): Vector[String] =
      val value = bound.value
      val expectedInside = bound match
        case DomainBound.Closed(_) => value.isFinite
        case DomainBound.Open(_)   => false
      val contained = transform.domain.contains(value)
      val mapped = transform.transform(value).isRight
      LawDiagnostics.problemWhen(
        contained != expectedInside || mapped != expectedInside,
        s"$label=$bound: contains=$contained, transform.isRight=$mapped, expected=$expectedInside"
      )

    LawSuite(
      s"transform:${transform.name.value}",
      Vector(
        Law(
          "valid fixture",
          () =>
            val empty =
              LawDiagnostics.problemWhen(validSamples.isEmpty, "provide at least one valid sample")
            val insufficientOrder = LawDiagnostics.problemWhen(
              orderedSamples.lengthCompare(2) < 0,
              "provide at least two distinct valid samples for monotonicity"
            )
            val invalid = validSamples.zipWithIndex.flatMap { case (sample, index) =>
              LawDiagnostics.problemWhen(
                !sample.isFinite || !transform.domain.contains(sample),
                s"sample $index (${LawDiagnostics.show(sample)}) is outside the advertised domain"
              )
            }
            val invalidTolerance = LawDiagnostics.problemWhen(
              !tolerance.isFinite || tolerance < 0.0,
              s"tolerance must be finite and non-negative, obtained $tolerance"
            )
            empty ++ insufficientOrder ++ invalid ++ invalidTolerance
        ),
        Law(
          "round trip",
          () =>
            orderedSamples.zipWithIndex.flatMap { case (sample, index) =>
              transform.transform(sample).flatMap(transform.inverse) match
                case Left(error) =>
                  Vector(s"sample $index ($sample) failed: ${error.message}")
                case Right(restored) =>
                  LawDiagnostics.problemWhen(
                    !approximatelyEqual(restored, sample),
                    s"sample $index ($sample) restored as $restored with tolerance $tolerance"
                  )
            }
        ),
        Law(
          "monotonicity",
          () =>
            orderedSamples
              .flatMap(sample => transform.transform(sample).toOption.map(sample -> _))
              .sliding(2)
              .flatMap {
                case Vector((leftInput, leftOutput), (rightInput, rightOutput)) =>
                  val monotone = monotonicity match
                    case TransformMonotonicity.Increasing =>
                      leftOutput <= rightOutput + tolerance
                    case TransformMonotonicity.Decreasing =>
                      leftOutput + tolerance >= rightOutput
                  LawDiagnostics.problemWhen(
                    !monotone,
                    s"$leftInput -> $leftOutput, $rightInput -> $rightOutput claimed $monotonicity"
                  )
                case _ => Vector.empty
              }
              .toVector
        ),
        Law(
          "open and closed endpoints",
          () =>
            endpointProblems(transform.domain.lower, "lower") ++
              endpointProblems(transform.domain.upper, "upper")
        )
      )
    )

/** The exact normalized-coordinate contract shared by every continuous scale. */
object OobPolicyLaws:
  def apply(): LawSuite =
    val inside = 0.25
    val below = -0.25
    val above = 1.25

    LawSuite(
      "continuous-scale:oob-policies",
      Vector(
        Law(
          "endpoints are in bounds",
          () =>
            Vector(OobPolicy.Censor, OobPolicy.Squish, OobPolicy.Keep).flatMap { policy =>
              LawDiagnostics.problemWhen(
                policy(0.0) != Some(0.0) || policy(1.0) != Some(1.0),
                s"$policy did not preserve both normalized endpoints"
              )
            }
        ),
        Law(
          "censor rejects out of bounds",
          () =>
            LawDiagnostics.problemWhen(
              OobPolicy.Censor(below).nonEmpty || OobPolicy.Censor(above).nonEmpty ||
                OobPolicy.Censor(inside) != Some(inside),
              "Censor must reject values outside [0, 1] and preserve values inside it"
            )
        ),
        Law(
          "squish clamps out of bounds",
          () =>
            LawDiagnostics.problemWhen(
              OobPolicy.Squish(below) != Some(0.0) ||
                OobPolicy.Squish(above) != Some(1.0) ||
                OobPolicy.Squish(inside) != Some(inside),
              "Squish must clamp to [0, 1] and preserve values inside it"
            )
        ),
        Law(
          "keep preserves out of bounds",
          () =>
            LawDiagnostics.problemWhen(
              OobPolicy.Keep(below) != Some(below) || OobPolicy.Keep(above) != Some(above) ||
                OobPolicy.Keep(inside) != Some(inside),
              "Keep must preserve normalized and out-of-bounds coordinates"
            )
        )
      )
    )

/** Training algebra for Intaglio's built-in continuous scales.
  *
  * The first batch establishes the initial scale. Later batches exercise incremental plot-wide
  * training. The suite compares that result with one-shot concatenated training and deterministic
  * input permutations, then checks that transformed-domain endpoints reach the palette endpoints.
  */
object ContinuousScaleTrainingLaws:
  def apply(
      batches: Vector[Vector[Double]],
      transform: Transform = Transform.identity,
      tolerance: Double = 1e-10
  ): LawSuite =
    val values = batches.flatten

    def observations(batch: Vector[Double]): Vector[ScaleObservation] =
      batch.map(ScaleObservation.Continuous(_))

    def direct(input: Vector[Double]): Either[GraphicsError, ContinuousScale[Double]] =
      ContinuousScale.train(
        "continuous-training-law",
        input,
        Palette.numeric,
        transform = transform
      )

    def sequential: Either[GraphicsError, Scale[Double, Double]] =
      batches.headOption match
        case None        => Left(GraphicsError.EmptyContinuousRange)
        case Some(first) =>
          direct(first).flatMap { initial =>
            batches.tail.foldLeft[Either[GraphicsError, Scale[Double, Double]]](Right(initial)) {
              case (trained, batch) =>
                trained.flatMap(_.trainPlotWide(observations(batch)))
            }
          }

    def failure(prefix: String, result: Either[GraphicsError, ?]): Vector[String] =
      result.left.toOption.toVector.map(error => s"$prefix: ${error.message}")

    def descriptor(result: Either[GraphicsError, Scale[Double, Double]]): Option[ScaleDescriptor] =
      result.toOption.map(_.descriptor)

    def approximatelyEqual(left: Double, right: Double): Boolean =
      val scale = math.max(1.0, math.max(math.abs(left), math.abs(right)))
      math.abs(left - right) <= tolerance * scale

    LawSuite(
      s"continuous-scale-training:${transform.name.value}",
      Vector(
        Law(
          "trainable batches",
          () =>
            val missing = LawDiagnostics.problemWhen(
              batches.lengthCompare(2) < 0,
              "provide an initial batch and at least one later batch"
            )
            val invalidTolerance = LawDiagnostics.problemWhen(
              !tolerance.isFinite || tolerance < 0.0,
              s"tolerance must be finite and non-negative, obtained $tolerance"
            )
            missing ++ invalidTolerance ++ failure(
              "concatenated fixture is not trainable",
              direct(values)
            ) ++
              failure(
                "initial fixture is not trainable",
                batches.headOption.fold(direct(Vector.empty))(direct)
              )
        ),
        Law(
          "concatenation",
          () =>
            val incremental = sequential
            val concatenated = direct(values)
            failure("incremental training failed", incremental) ++
              failure("concatenated training failed", concatenated) ++
              LawDiagnostics.problemWhen(
                descriptor(incremental) != concatenated.toOption.map(_.descriptor),
                s"incremental=${descriptor(incremental)}, concatenated=${concatenated.toOption.map(_.descriptor)}"
              )
        ),
        Law(
          "permutation invariance",
          () =>
            val variants =
              if values.isEmpty then Vector(values)
              else Vector(values, values.reverse, values.drop(1) :+ values.head)
            val trained = variants.map(direct)
            trained.zipWithIndex.flatMap { case (result, index) =>
              failure(s"permutation $index failed", result)
            } ++ LawDiagnostics.problemWhen(
              trained.flatMap(_.toOption.map(_.descriptor)).distinct.lengthCompare(1) > 0,
              s"permutations produced ${trained.flatMap(_.toOption.map(_.descriptor)).distinct}"
            )
        ),
        Law(
          "transformed endpoints",
          () =>
            direct(values) match
              case Left(error) => Vector(s"training failed: ${error.message}")
              case Right(scale) if scale.transformedDomain.width == 0.0 =>
                scale.mapValue(scale.domain.lower) match
                  case Some(mapped) =>
                    LawDiagnostics.problemWhen(
                      !approximatelyEqual(mapped, 0.5),
                      s"degenerate endpoint mapped to $mapped instead of 0.5"
                    )
                  case None => Vector("degenerate endpoint was censored")
              case Right(scale) =>
                Vector(scale.domain.lower, scale.domain.upper).flatMap { endpoint =>
                  val expected = transform
                    .transform(endpoint)
                    .toOption
                    .map(scale.transformedDomain.rescale)
                  val actual = scale.mapValue(endpoint)
                  (expected, actual) match
                    case (Some(left), Some(right)) =>
                      LawDiagnostics.problemWhen(
                        !approximatelyEqual(left, right) ||
                          !(approximatelyEqual(right, 0.0) || approximatelyEqual(right, 1.0)),
                        s"endpoint $endpoint mapped to $right, expected normalized endpoint $left"
                      )
                    case _ =>
                      Vector(s"endpoint $endpoint did not map: expected=$expected, actual=$actual")
                }
        )
      )
    )

/** A fixed scale must ignore every later training observation. This generic law covers Intaglio's
  * continuous, discrete, band, and temporal scales when their descriptor advertises
  * [[ScaleTraining.Fixed]].
  */
object FixedScaleLaws:
  def apply[In, Out](scale: Scale[In, Out], laterValues: Vector[In]): LawSuite =
    withEquality(scale, laterValues)(_ == _)

  def withEquality[In, Out](
      scale: Scale[In, Out],
      laterValues: Vector[In]
  )(equivalent: (Out, Out) => Boolean): LawSuite =
    val observations = laterValues.flatMap(scale.observation)

    def sameOption(left: Option[Out], right: Option[Out]): Boolean =
      (left, right) match
        case (Some(first), Some(second)) => equivalent(first, second)
        case (None, None)                => true
        case _                           => false

    def trained: Either[GraphicsError, Scale[In, Out]] =
      scale.trainPlotWide(observations)

    LawSuite(
      s"fixed-scale:${scale.name.value}",
      Vector(
        Law(
          "training fixture",
          () =>
            LawDiagnostics.problemWhen(
              laterValues.isEmpty || observations.isEmpty,
              "provide at least one value that contributes a training observation"
            )
        ),
        Law(
          "fixed applicability",
          () =>
            LawDiagnostics.problemWhen(
              scale.descriptor.training != ScaleTraining.Fixed,
              s"descriptor advertises ${scale.descriptor.training}, not Fixed"
            )
        ),
        Law(
          "fixed training is identity",
          () =>
            trained match
              case Left(error)  => Vector(s"later training failed: ${error.message}")
              case Right(after) =>
                val descriptorProblem = LawDiagnostics.problemWhen(
                  after.descriptor != scale.descriptor,
                  s"before=${scale.descriptor}, after=${after.descriptor}"
                )
                val mappingProblems = laterValues.zipWithIndex.flatMap { case (value, index) =>
                  val before = scale.mapValue(value)
                  val result = after.mapValue(value)
                  LawDiagnostics.problemWhen(
                    !sameOption(before, result),
                    s"later value $index (${LawDiagnostics.show(value)}): before=$before, after=$result"
                  )
                }
                descriptorProblem ++ mappingProblems
        )
      )
    )

/** Which ordering laws apply to a discrete domain. */
enum DiscreteDomainApplicability:
  /** Order is semantic: declared levels stay first and novel levels follow first encounter. */
  case Ordered

  /** Encounter order is immaterial: `CategoryIdentity.ordering` defines a canonical order. */
  case Unordered

/** Concatenation plus ordering laws for [[DiscreteDomain]].
  *
  * Ordered fixtures execute encounter-order laws and deliberately omit permutation invariance.
  * Unordered fixtures execute permutation invariance and deliberately omit encounter-order laws.
  * The selected law names therefore expose applicability to test-framework adapters as well as to
  * readers.
  */
object DiscreteDomainLaws:
  def ordered[A](declared: Vector[A], batches: Vector[Vector[A]])(using
      categories: CategoryIdentity[A]
  ): LawSuite =
    apply(DiscreteDomainApplicability.Ordered, declared, batches)

  def unordered[A](declared: Vector[A], batches: Vector[Vector[A]])(using
      categories: CategoryIdentity[A]
  ): LawSuite =
    apply(DiscreteDomainApplicability.Unordered, declared, batches)

  def apply[A](
      applicability: DiscreteDomainApplicability,
      declared: Vector[A],
      batches: Vector[Vector[A]]
  )(using categories: CategoryIdentity[A]): LawSuite =
    def construct(values: Vector[A]): Either[GraphicsError, DiscreteDomain[A]] =
      applicability match
        case DiscreteDomainApplicability.Ordered   => DiscreteDomain.ordered(values)
        case DiscreteDomainApplicability.Unordered => DiscreteDomain.unordered(values)

    def fingerprint(domain: DiscreteDomain[A]): Vector[Any] =
      domain.levels.map(categories.erasedIdentity)

    def distinct(values: Vector[A]): Vector[A] =
      val seen = scala.collection.mutable.HashSet.empty[Any]
      values.filter(value => seen.add(categories.erasedIdentity(value)))

    def expectedOrder: Vector[Any] =
      val values = distinct(declared ++ batches.flatten)
      val ordered = applicability match
        case DiscreteDomainApplicability.Ordered   => values
        case DiscreteDomainApplicability.Unordered =>
          values.sortWith(categories.compare(_, _) < 0)
      ordered.map(categories.erasedIdentity)

    def sequential: Either[GraphicsError, DiscreteDomain[A]] =
      construct(declared).flatMap { initial =>
        batches.foldLeft[Either[GraphicsError, DiscreteDomain[A]]](Right(initial)) {
          case (domain, batch) => domain.flatMap(_.train(batch))
        }
      }

    def concatenated: Either[GraphicsError, DiscreteDomain[A]] =
      construct(declared).flatMap(_.train(batches.flatten))

    val applicabilityLaw = applicability match
      case DiscreteDomainApplicability.Ordered =>
        Law(
          "encounter order",
          () =>
            sequential match
              case Left(error)   => Vector(s"training failed: ${error.message}")
              case Right(domain) =>
                LawDiagnostics.problemWhen(
                  !domain.ordered || fingerprint(domain) != expectedOrder,
                  s"actual=${fingerprint(domain)}, expected=$expectedOrder, ordered=${domain.ordered}"
                )
        )
      case DiscreteDomainApplicability.Unordered =>
        Law(
          "permutation invariance",
          () =>
            val values = distinct(declared ++ batches.flatten)
            val variants =
              if values.isEmpty then Vector(values)
              else Vector(values, values.reverse, values.drop(1) :+ values.head)
            val results = variants.map(construct)
            val failures = results.zipWithIndex.flatMap { case (result, index) =>
              result.left.toOption.toVector.map(error => s"permutation $index: ${error.message}")
            }
            val fingerprints = results.flatMap(_.toOption.map(fingerprint))
            failures ++ LawDiagnostics.problemWhen(
              fingerprints.distinct.lengthCompare(1) > 0 ||
                fingerprints.exists(_ != expectedOrder) ||
                results.flatMap(_.toOption).exists(_.ordered),
              s"permutations produced $fingerprints, expected canonical order $expectedOrder"
            )
        )

    LawSuite(
      s"discrete-domain:${applicability.toString.toLowerCase}",
      Vector(
        Law(
          "trainable fixture",
          () =>
            sequential.left.toOption.toVector.map(error => s"sequential: ${error.message}") ++
              concatenated.left.toOption.toVector.map(error => s"concatenated: ${error.message}")
        ),
        Law(
          "concatenation",
          () =>
            val left = sequential.toOption.map(fingerprint)
            val right = concatenated.toOption.map(fingerprint)
            LawDiagnostics.problemWhen(
              left != right,
              s"sequential=$left, concatenated=$right"
            )
        ),
        applicabilityLaw
      )
    )
