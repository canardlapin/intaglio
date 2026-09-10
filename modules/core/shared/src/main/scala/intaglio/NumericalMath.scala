package intaglio

/** Small, dependency-free numerical building blocks shared by statistical and contour kernels.
  * These types stay package-private so public statistics expose domain concepts rather than an
  * implementation-specific accumulator API.
  */
private[intaglio] object NumericalMath:
  /** Neumaier compensated summation. It retains low-order terms when a larger partial sum would
    * otherwise absorb them, while preserving the ordinary IEEE result if the sum overflows.
    */
  final class CompensatedSum:
    private var partial = 0.0
    private var correction = 0.0

    def add(value: Double): Unit =
      val next = partial + value
      if partial.isFinite && next.isFinite then
        if math.abs(partial) >= math.abs(value) then correction += (partial - next) + value
        else correction += (value - next) + partial
      else correction = 0.0
      partial = next

    def result: Double =
      val corrected = partial + correction
      if corrected.isNaN && !partial.isNaN then partial else corrected

  /** One-pass sample moments. Welford's recurrence keeps the second central moment stable; a
    * compensated total supplies the reported mean when that total remains representable.
    */
  final class OnlineMoments:
    private val total = CompensatedSum()
    private val secondMoment = CompensatedSum()
    private var size = 0
    private var onlineMean = 0.0
    private var minimumValue = Double.PositiveInfinity
    private var maximumValue = Double.NegativeInfinity

    def add(value: Double): Unit =
      val previousMean = onlineMean
      size += 1
      val delta = value - previousMean
      onlineMean = previousMean + delta / size.toDouble
      secondMoment.add(delta * (value - onlineMean))
      total.add(value)
      if value < minimumValue then minimumValue = value
      if value > maximumValue then maximumValue = value

    def result: SampleMoments =
      require(size > 0, "sample moments require at least one observation")
      val compensatedTotal = total.result
      val stableMean =
        if compensatedTotal.isFinite then compensatedTotal / size.toDouble else onlineMean
      val meanCorrection = onlineMean - stableMean
      val correctedSecondMoment =
        secondMoment.result + size.toDouble * meanCorrection * meanCorrection
      SampleMoments(
        count = size,
        mean = stableMean,
        secondCentralMoment = math.max(0.0, correctedSecondMoment),
        minimum = minimumValue,
        maximum = maximumValue
      )

  final case class SampleMoments private[NumericalMath] (
      count: Int,
      mean: Double,
      secondCentralMoment: Double,
      minimum: Double,
      maximum: Double
  ):
    def sampleVariance: Double =
      if count < 2 then 0.0 else secondCentralMoment / (count - 1).toDouble

    def sampleStandardDeviation: Double =
      math.sqrt(sampleVariance)

  def moments(values: IterableOnce[Double]): SampleMoments =
    val accumulator = OnlineMoments()
    val iterator = values.iterator
    while iterator.hasNext do accumulator.add(iterator.next())
    accumulator.result
