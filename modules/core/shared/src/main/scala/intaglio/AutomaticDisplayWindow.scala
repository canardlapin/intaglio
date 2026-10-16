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

  /** A batch request named no window. */
  case EmptyBatch

  /** Batched windows share one sample, so they must share its domain, size and seed. */
  case IncompatibleBatchSampling
  def message: String = this match
    case NoEligibleValues          => "No finite values satisfy the display sampling domain"
    case EmptyBatch                => "Automatic window batch requires at least one configuration"
    case IncompatibleBatchSampling =>
      "Automatic window batch must share sampling domain, sample limit and seed"
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
    estimateMany(values, Vector(config)).flatMap(_.head)

  /** Several windows from one traversal and one bounded reservoir. Configurations must share their
    * sampling domain, sample limit and seed; only their probabilities may differ. Results preserve
    * request order (including duplicates), and each window retains its own constant expansion or
    * refusal. With the same ordered input each result equals a separate [[estimate]] call,
    * including sampled streams. The input is consumed exactly once.
    *
    * An empty request or incompatible sampling configurations is refused as a typed error before
    * obtaining the input iterator; otherwise each window succeeds or fails on its own. Sampling
    * remains O(maxSamples) storage; output costs O(windows). Requested quantile endpoints are
    * selected directly from the retained reservoir, rather than sorting every retained value.
    */
  def estimateMany(
      values: IterableOnce[Double],
      configs: Vector[AutomaticWindowConfig]
  ): Either[AutomaticWindowError, Vector[Either[AutomaticWindowError, AutomaticWindowEstimate]]] =
    configs.headOption match
      case None         => Left(AutomaticWindowError.EmptyBatch)
      case Some(config) =>
        if configs.forall(c =>
            c.domain == config.domain && c.maxSamples == config.maxSamples && c.seed == config.seed
          )
        then Right(sampled(values, config, configs))
        else Left(AutomaticWindowError.IncompatibleBatchSampling)

  private def sampled(
      values: IterableOnce[Double],
      config: AutomaticWindowConfig,
      configs: Vector[AutomaticWindowConfig]
  ): Vector[Either[AutomaticWindowError, AutomaticWindowEstimate]] =
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
      val orderStatistics = selectedOrderStatistics(sample, retained, configs)
      def quantile(probability: Double): Double =
        val position = probability * (retained - 1)
        val index = position.toInt
        val fraction = position - index
        val value = orderStatistics(index)
        val next = orderStatistics(math.min(index + 1, retained - 1))
        // Weighted endpoints avoid overflow in next-current for signed extremes.
        value * (1 - fraction) + next * fraction
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

  /** Select only the order statistics requested by the batch. The retained reservoir is copied
    * before partitioning so Algorithm R's sample remains immutable until all counting completes.
    * Ranks retain the former sort order of signed zero.
    */
  private def selectedOrderStatistics(
      sample: Array[Double],
      retained: Int,
      configs: Vector[AutomaticWindowConfig]
  ): Int => Double =
    val requestedRanks = new Array[Int](configs.length * 4)
    var requestedCount = 0
    configs.foreach { config =>
      val probabilities = Array(config.lowerProbability, config.upperProbability)
      var probabilityIndex = 0
      while probabilityIndex < probabilities.length do
        val position = probabilities(probabilityIndex) * (retained - 1)
        val index = position.toInt
        requestedRanks(requestedCount) = index
        requestedRanks(requestedCount + 1) = math.min(index + 1, retained - 1)
        requestedCount += 2
        probabilityIndex += 1
    }
    scala.util.Sorting.quickSort(requestedRanks)
    var distinctCount = 0
    var index = 0
    while index < requestedCount do
      if distinctCount == 0 || requestedRanks(index) != requestedRanks(distinctCount - 1) then
        requestedRanks(distinctCount) = requestedRanks(index)
        distinctCount += 1
      index += 1

    val values = new Array[Double](retained)
    Array.copy(sample, 0, values, 0, retained)
    val selected = new Array[Double](distinctCount)
    var partitionWorkRemaining = retained.toLong * (2L * floorLog2(retained) + 1L)

    def swap(left: Int, right: Int): Unit =
      val value = values(left)
      values(left) = values(right)
      values(right) = value

    /** Three-way partition returns the inclusive range equal to its median-of-three pivot. */
    def partition(left: Int, right: Int): (Int, Int, Double) =
      val middle = left + (right - left) / 2
      val first = values(left)
      val centre = values(middle)
      val last = values(right)
      val pivot =
        if totalCompare(first, centre) <= 0 then
          if totalCompare(centre, last) <= 0 then centre
          else if totalCompare(first, last) <= 0 then last
          else first
        else if totalCompare(first, last) <= 0 then first
        else if totalCompare(centre, last) <= 0 then last
        else centre
      var less = left
      var current = left
      var greater = right
      while current <= greater do
        val comparison = totalCompare(values(current), pivot)
        if comparison < 0 then
          swap(less, current)
          less += 1
          current += 1
        else if comparison > 0 then
          swap(current, greater)
          greater -= 1
        else current += 1
      (less, greater, pivot)

    def sortAndSelect(left: Int, right: Int, rankStart: Int, rankEnd: Int): Unit =
      val length = right - left + 1
      val fallback = new Array[Double](length)
      Array.copy(values, left, fallback, 0, length)
      scala.util.Sorting.quickSort(fallback)
      var rankIndex = rankStart
      while rankIndex < rankEnd do
        selected(rankIndex) = fallback(requestedRanks(rankIndex) - left)
        rankIndex += 1

    def select(left: Int, right: Int, rankStart: Int, rankEnd: Int, depthRemaining: Int): Unit =
      if rankStart < rankEnd then
        val length = right - left + 1
        if depthRemaining == 0 || partitionWorkRemaining < length then
          // An adversarial sequence can make median-of-three selection repeatedly choose a
          // boundary pivot. Sorting only this still-relevant primitive range bounds both work
          // and stack depth while retaining the same total Double order as the former full sort.
          sortAndSelect(left, right, rankStart, rankEnd)
        else
          partitionWorkRemaining -= length
          val (equalStart, equalEnd, pivot) = partition(left, right)
          var equalRankStart = rankStart
          while equalRankStart < rankEnd && requestedRanks(equalRankStart) < equalStart do
            equalRankStart += 1
          var equalRankEnd = equalRankStart
          while equalRankEnd < rankEnd && requestedRanks(equalRankEnd) <= equalEnd do
            selected(equalRankEnd) = pivot
            equalRankEnd += 1
          select(left, equalStart - 1, rankStart, equalRankStart, depthRemaining - 1)
          select(equalEnd + 1, right, equalRankEnd, rankEnd, depthRemaining - 1)

    select(0, retained - 1, 0, distinctCount, 2 * floorLog2(retained) + 1)
    def valueAt(rank: Int): Double =
      var low = 0
      var high = distinctCount - 1
      var found = false
      var result = 0.0
      while low <= high && !found do
        val middle = low + (high - low) / 2
        val candidate = requestedRanks(middle)
        if candidate < rank then low = middle + 1
        else if candidate > rank then high = middle - 1
        else
          result = selected(middle)
          found = true
      if found then result
      else throw new IllegalStateException(s"Missing selected order statistic at rank $rank")
    valueAt

  private def floorLog2(value: Int): Int =
    31 - java.lang.Integer.numberOfLeadingZeros(value)

  /** Eligible values are finite. Only signed zero needs the full Java ordering; ordinary finite
    * values use primitive comparisons in the partition hot loop.
    */
  private inline def totalCompare(left: Double, right: Double): Int =
    if left < right then -1
    else if left > right then 1
    else if left == 0.0 && right == 0.0 then java.lang.Double.compare(left, right)
    else 0
