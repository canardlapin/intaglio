package intaglio

/** Thread-local phase timing for the compile pipeline.
  *
  * Disabled by default and enabled only inside [[PhaseClock.profile]], so ordinary compilation pays
  * one thread-local read and one boolean test per phase boundary and nothing else. Readings are
  * exclusive (self time): a phase nested inside another attributes its elapsed time to itself and
  * not to its parent, so per-phase totals sum to at most the profiled wall time.
  *
  * This is measurement plumbing for the timing harness in `modules/performance`, not public API. It
  * must never influence compilation semantics.
  */
private[intaglio] object PhaseClock:
  enum Phase:
    case Mapping, Stat, ScaleTraining, Resolve, Layout, Lowering

  /** Accumulated self time and invocation count for one phase within one profiled region. */
  final case class Reading(nanos: Long, calls: Long)

  private val phaseCount: Int = Phase.values.length

  private final class State:
    var enabled: Boolean = false
    var depth: Int = 0
    val nanos: Array[Long] = new Array[Long](phaseCount)
    val calls: Array[Long] = new Array[Long](phaseCount)
    var framePhase: Array[Int] = new Array[Int](16)
    var frameStart: Array[Long] = new Array[Long](16)
    var frameChild: Array[Long] = new Array[Long](16)

    def growFrames(): Unit =
      framePhase = java.util.Arrays.copyOf(framePhase, framePhase.length * 2)
      frameStart = java.util.Arrays.copyOf(frameStart, frameStart.length * 2)
      frameChild = java.util.Arrays.copyOf(frameChild, frameChild.length * 2)

  private val local: ThreadLocal[State] = new ThreadLocal[State]:
    override def initialValue(): State = new State

  /** Attribute the body's self time to `phase` when profiling is active on this thread. */
  def timed[A](phase: Phase)(body: => A): A =
    val state = local.get()
    if !state.enabled then body
    else
      if state.depth == state.framePhase.length then state.growFrames()
      val frame = state.depth
      state.framePhase(frame) = phase.ordinal
      state.frameStart(frame) = System.nanoTime()
      state.frameChild(frame) = 0L
      state.depth = frame + 1
      try body
      finally
        val elapsed = System.nanoTime() - state.frameStart(frame)
        state.depth = frame
        state.nanos(state.framePhase(frame)) += elapsed - state.frameChild(frame)
        state.calls(state.framePhase(frame)) += 1L
        if frame > 0 then state.frameChild(frame - 1) += elapsed

  /** Run `body` with phase timing enabled on this thread and return its per-phase readings.
    * Profiled regions do not nest; the inner region would silently steal the outer readings.
    */
  def profile[A](body: => A): (A, Map[Phase, Reading]) =
    val state = local.get()
    require(!state.enabled, "PhaseClock.profile does not nest")
    java.util.Arrays.fill(state.nanos, 0L)
    java.util.Arrays.fill(state.calls, 0L)
    state.depth = 0
    state.enabled = true
    try
      val result = body
      val readings = Phase.values.iterator
        .filter(phase => state.calls(phase.ordinal) > 0L)
        .map(phase => phase -> Reading(state.nanos(phase.ordinal), state.calls(phase.ordinal)))
        .toMap
      (result, readings)
    finally state.enabled = false
