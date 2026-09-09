package intaglio

import scala.collection.mutable

/** Observable effect of one [[PlotCompileCache]]: whether it is earning its memory. A cache that
  * never hits is worse than none, so the counters are the contract, not an afterthought.
  */
final case class PlotCacheProfile(
    trainedHits: Long,
    trainedMisses: Long,
    placedHits: Long,
    placedMisses: Long,
    evictions: Long,
    trainedEntries: Int,
    placedEntries: Int
):
  def hits: Long = trainedHits + placedHits
  def misses: Long = trainedMisses + placedMisses
  def requests: Long = hits + misses
  def hitRate: Double =
    if requests == 0L then 1.0 else hits.toDouble / requests.toDouble

object PlotCacheProfile:
  val empty: PlotCacheProfile = PlotCacheProfile(0L, 0L, 0L, 0L, 0L, 0, 0)

/** Caller-owned memoization for repeated compiles of unchanged plots.
  *
  * Compilation is pure, so the same plot and options produce the same trained data, and the same
  * trained data at an equal [[RenderContext]] produces the same placed plot. This cache exploits
  * exactly that and nothing more: plots and options are keyed by reference identity — plot values
  * carry user functions whose equality is undecidable, so a value-equal but distinct plot is
  * deliberately a miss — while contexts are keyed by their observable value (dimensions and
  * resolution field-wise; text metrics and font registry by reference), so returning to a previous
  * device size hits even through a freshly constructed context.
  *
  * The cache is explicit and never global: construct one with [[PlotCompileCache.bounded]], pass it
  * where repeated compiles happen (`PlotCompiler.resolve`, `train`, `place`, or
  * `PlotProgram.resolve`), and read [[profile]] to see whether it is doing anything.
  * [[PlotCompileCache.Disabled]] is the default everywhere and restores exactly the uncached
  * behavior at zero overhead. Instances are mutable and not thread-safe; each rendering thread owns
  * its own. Errors are never cached: a failing compile propagates unchanged and stores nothing.
  */
sealed trait PlotCompileCache:
  def profile: PlotCacheProfile

object PlotCompileCache:

  /** Zero-overhead non-cache: every compile takes the ordinary path and nothing is retained. */
  case object Disabled extends PlotCompileCache:
    def profile: PlotCacheProfile = PlotCacheProfile.empty

  /** A bounded cache with least-recently-used eviction per level. `trainedCapacity` bounds retained
    * data-side compilations (one per plot); `placedCapacity` bounds retained placements (one per
    * plot–context pair). A capacity of zero retains nothing at that level. Negative capacities are
    * treated as zero.
    */
  def bounded(trainedCapacity: Int = 8, placedCapacity: Int = 32): Bounded =
    new Bounded(math.max(0, trainedCapacity), math.max(0, placedCapacity))

  final class Bounded private[PlotCompileCache] (
      val trainedCapacity: Int,
      val placedCapacity: Int
  ) extends PlotCompileCache:
    private final class TrainedEntry(
        val plot: AnyRef,
        val options: AnyRef,
        val value: TrainedPlotData
    )
    private final class PlacedEntry(
        val trained: TrainedPlotData,
        val context: RenderContext,
        val value: TrainedPlot
    )

    // Most-recently-used entries live at the end; eviction removes the head.
    private val trainedEntries = mutable.ArrayBuffer.empty[TrainedEntry]
    private val placedEntries = mutable.ArrayBuffer.empty[PlacedEntry]
    private var trainedHits = 0L
    private var trainedMisses = 0L
    private var placedHits = 0L
    private var placedMisses = 0L
    private var evictions = 0L

    def profile: PlotCacheProfile =
      PlotCacheProfile(
        trainedHits,
        trainedMisses,
        placedHits,
        placedMisses,
        evictions,
        trainedEntries.length,
        placedEntries.length
      )

    private[intaglio] def trainedFor(
        plot: AnyRef,
        options: AnyRef
    )(compute: => Either[GraphicsError, TrainedPlotData]): Either[GraphicsError, TrainedPlotData] =
      var index = 0
      var found = -1
      while index < trainedEntries.length && found < 0 do
        val entry = trainedEntries(index)
        if (entry.plot eq plot) && (entry.options eq options) then found = index
        index += 1
      if found >= 0 then
        trainedHits += 1L
        val entry = trainedEntries.remove(found)
        trainedEntries += entry
        Right(entry.value)
      else
        trainedMisses += 1L
        compute.map { value =>
          if trainedCapacity > 0 then
            if trainedEntries.length >= trainedCapacity then
              val dropped = trainedEntries.remove(0)
              dropPlacementsOf(dropped.value)
              evictions += 1L
            trainedEntries += new TrainedEntry(plot, options, value)
          value
        }

    private[intaglio] def placedFor(
        trained: TrainedPlotData,
        context: RenderContext
    )(compute: => Either[GraphicsError, TrainedPlot]): Either[GraphicsError, TrainedPlot] =
      var index = 0
      var found = -1
      while index < placedEntries.length && found < 0 do
        val entry = placedEntries(index)
        if (entry.trained eq trained) && sameContext(entry.context, context) then found = index
        index += 1
      if found >= 0 then
        placedHits += 1L
        val entry = placedEntries.remove(found)
        placedEntries += entry
        Right(entry.value)
      else
        placedMisses += 1L
        compute.map { value =>
          if placedCapacity > 0 then
            if placedEntries.length >= placedCapacity then
              placedEntries.remove(0)
              evictions += 1L
            placedEntries += new PlacedEntry(trained, context, value)
          value
        }

    /** Placement depends on exactly these context observations, so two contexts that agree on all
      * of them — sharing the same metrics and font-resolution behavior by reference — place one
      * trained value identically.
      */
    private def sameContext(a: RenderContext, b: RenderContext): Boolean =
      (a eq b) || (
        a.width == b.width && a.height == b.height &&
          a.pixelsPerInch == b.pixelsPerInch && a.deviceScale == b.deviceScale &&
          a.lineHeightPt == b.lineHeightPt &&
          a.logicalWidth == b.logicalWidth && a.logicalHeight == b.logicalHeight &&
          (a.textMetrics eq b.textMetrics) && (a.fontRegistry eq b.fontRegistry)
      )

    /** An evicted training's placements can never hit again — their key is unreachable. */
    private def dropPlacementsOf(trained: TrainedPlotData): Unit =
      var index = placedEntries.length - 1
      while index >= 0 do
        if placedEntries(index).trained eq trained then
          placedEntries.remove(index)
          evictions += 1L
        index -= 1
