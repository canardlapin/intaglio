package intaglio

/** Run explicitly after correctness gates; elapsed observations are not assertions or app timing.
  * `coreJVM/Test/runMain intaglio.AutomaticDisplayWindowProfile`
  */
object AutomaticDisplayWindowProfile:
  def main(args: Array[String]): Unit =
    require(args.isEmpty)
    val values = Vector.tabulate(206000)(i => math.abs(math.sin(i * 0.017) * (i % 103 + 1)))
    val configs = Vector(0.5, 0.9).map(p =>
      AutomaticWindowConfig(p, 0.98, DisplaySampleDomain.FiniteNonzero, 262144)
    )
    def separate() = configs.map(c => AutomaticDisplayWindow.estimate(values.iterator, c))
    def batch() = AutomaticDisplayWindow.estimateMany(values.iterator, configs)
    val expected = separate()
    require(batch() == expected)
    for _ <- 0 until 5 do
      require(separate() == expected)
      require(batch() == expected)
    def timed(f: () => Vector[Either[AutomaticWindowError, AutomaticWindowEstimate]]): Long =
      val start = System.nanoTime()
      val result = f()
      val elapsed = System.nanoTime() - start
      require(result == expected)
      elapsed
    val runs = Vector.tabulate(12) { i =>
      // Alternate ordering to reduce systematic warmup/thermal bias between paths.
      if i % 2 == 0 then (timed(() => separate()), timed(() => batch()))
      else
        val batched = timed(() => batch())
        (timed(() => separate()), batched)
    }
    println("AutomaticDisplayWindow public API: 206000 magnitudes, exact p50/p90/p98")
    println("separate_ns=" + runs.map(_._1).mkString(","))
    println("batch_ns=" + runs.map(_._2).mkString(","))
    println("PASS: public batch and separate window estimates agree in every profiling iteration")
