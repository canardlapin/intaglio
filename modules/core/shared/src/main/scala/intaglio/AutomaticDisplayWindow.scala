package intaglio

/** Which finite values participate in automatic display-window estimation. Excluding zero is an
  * explicit background convention, not a mask inference.
  */
enum DisplaySampleDomain:
  case Finite, FiniteNonzero

final case class AutomaticWindowConfig(
    lowerProbability: Double = 0.02,
    upperProbability: Double = 0.98,
    domain: DisplaySampleDomain = DisplaySampleDomain.Finite,
    maxSamples: Int = 65536,
    seed: Long = 0L
):
  require(
    lowerProbability.isFinite && upperProbability.isFinite &&
      lowerProbability >= 0 && lowerProbability < upperProbability && upperProbability <= 1,
    "Automatic window probabilities require 0 <= lower < upper <= 1"
  )
  require(
    maxSamples >= 2 && maxSamples <= 1048576,
    "Automatic window sample limit must be in [2,1048576]"
  )

enum AutomaticWindowError extends IntaglioError:
  case NoEligibleValues
  case UnrepresentableWindow(lower: Double, upper: Double)
  def message: String = this match
    case NoEligibleValues => "No finite values satisfy the display sampling domain"
    case UnrepresentableWindow(lower, upper) =>
      s"Automatic display window cannot represent finite width: [$lower,$upper]"

/** Exact counts refer to the input stream, quantiles to the retained sample. A sampled result has
  * no guaranteed quantile-error bound. The deterministic seed makes an ordered input reproducible,
  * not permutation invariant.
  */
final case class AutomaticWindowEstimate(
    window: DisplayWindow,
    config: AutomaticWindowConfig,
    observed: Long,
    nonFinite: Long,
    excludedZero: Long,
    eligible: Long,
    retained: Int,
    expandedConstant: Boolean
):
  def sampled: Boolean = eligible > retained

object AutomaticDisplayWindow:
  /** One pass, O(maxSamples) storage. Algorithm R samples eligible values without replacement;
    * exact linear order-statistic interpolation is used when the entire eligible input fits. A
    * constant sample is expanded by 1% of its magnitude (or 0.5 at zero); unrepresentable extremes
    * are rejected. Negative values are retained under both domains. This is display-only.
    */
  def estimate(
      values: IterableOnce[Double],
      config: AutomaticWindowConfig = AutomaticWindowConfig()
  ): Either[AutomaticWindowError, AutomaticWindowEstimate] =
    estimateMany(values, Vector(config)).head

  /** Several windows from one traversal, one bounded reservoir and one sort. Configurations must
    * share their sampling domain, sample limit and seed; only their probabilities may differ.
    * Results preserve request order (including duplicates), and each window retains its own
    * constant expansion or refusal. With the same ordered input each result equals a separate
    * [[estimate]] call, including sampled streams. The input is consumed exactly once.
    *
    * An empty request or incompatible sampling configurations is a caller error, rejected before
    * obtaining the input iterator. Sampling remains O(maxSamples) storage; output costs O(windows).
    */
  def estimateMany(
      values: IterableOnce[Double],
      configs: Vector[AutomaticWindowConfig]
  ): Vector[Either[AutomaticWindowError, AutomaticWindowEstimate]] =
    require(configs.nonEmpty, "Automatic window batch requires at least one configuration")
    val config = configs.head
    require(
      configs.forall(c => c.domain == config.domain && c.maxSamples == config.maxSamples && c.seed == config.seed),
      "Automatic window batch must share sampling domain, sample limit and seed"
    )
    val sample = new Array[Double](config.maxSamples)
    val random = new scala.util.Random(config.seed)
    var observed = 0L
    var nonFinite = 0L
    var excludedZero = 0L
    var eligible = 0L
    values.iterator.foreach { value =>
      observed += 1
      if !value.isFinite then nonFinite += 1
      else if value == 0 && config.domain == DisplaySampleDomain.FiniteNonzero then
        excludedZero += 1
      else
        eligible += 1
        if eligible <= sample.length then sample((eligible - 1).toInt) = value
        else
          val slot = random.nextLong(eligible)
          if slot < sample.length then sample(slot.toInt) = value
    }
    if eligible == 0 then configs.map(_ => Left(AutomaticWindowError.NoEligibleValues))
    else
      val retained = math.min(eligible, sample.length.toLong).toInt
      val sorted = sample.take(retained).sorted
      def quantile(probability: Double): Double =
        val position = probability * (retained - 1)
        val index = position.toInt
        val fraction = position - index
        val next = sorted(math.min(index + 1, retained - 1))
        // Weighted endpoints avoid overflow in next-current for signed extremes.
        sorted(index) * (1 - fraction) + next * fraction
      configs.map { requested =>
        val lower = quantile(requested.lowerProbability)
        val upper = quantile(requested.upperProbability)
        val constant = lower == upper
        val delta = if lower == 0 then 0.5 else math.abs(lower) * 0.01
        val lo = if constant then lower - delta else lower
        val hi = if constant then upper + delta else upper
        if !lo.isFinite || !hi.isFinite || !(lo < hi) || !(hi - lo).isFinite then
          Left(AutomaticWindowError.UnrepresentableWindow(lo, hi))
        else
          Right(
            AutomaticWindowEstimate(
              DisplayWindow.unsafe(lo, hi),
              requested,
              observed,
              nonFinite,
              excludedZero,
              eligible,
              retained,
              constant
            )
          )
      }
