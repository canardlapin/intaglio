package intaglio

private[intaglio] final case class AnnotationPlan(
    reference: ReferenceLine,
    coordinate: Double,
    trainedScale: Option[TrainedScale] = None
):
  def isMapped: Boolean =
    trainedScale.nonEmpty

  def resolved: ResolvedReferenceLine =
    ResolvedReferenceLine(reference, coordinate, trainedScale)

/** Output of the mapping-resolution phase: one plan per layer with effective data and its canonical
  * aesthetic mapping.
  */
private[intaglio] final case class LayerPlan[Row](
    layerIndex: Int,
    layer: Layer[Row],
    data: Vector[Row],
    mapping: AesSpec[Row],
    packageKey: AnyRef,
    annotation: Option[AnnotationPlan]
)

/** Existential package that keeps one layer's row type attached to every compiler input derived
  * from it.
  */
private[intaglio] sealed trait PackedLayerPlan:
  type Row
  def value: LayerPlan[Row]

  final def layerIndex: Int = value.layerIndex
  final def layer: Layer[Row] = value.layer
  final def data: Vector[Row] = value.data
  final def mapping: AesSpec[Row] = value.mapping
  final def annotation: Option[AnnotationPlan] = value.annotation

private[intaglio] object PackedLayerPlan:
  type Aux[Row0] = PackedLayerPlan { type Row = Row0 }

  def apply[Row0](plan: LayerPlan[Row0]): Aux[Row0] =
    new PackedLayerPlan:
      type Row = Row0
      val value: LayerPlan[Row] = plan

/** A layer after its statistical transform. Every stat emits the same typed row envelope, so scale
  * training remains plot-wide even when layers have different statistics.
  */
private[intaglio] final case class StatPlan[Row](
    source: LayerPlan[Row],
    frame: StatFrame[Row],
    mapping: AesSpec[StatRow[Row]]
):
  def layerIndex: Int = source.layerIndex
  def layer: Layer[Row] = source.layer
  def data: Vector[StatRow[Row]] = frame.rows
  def annotation: Option[AnnotationPlan] = source.annotation

/** Existential package for a statistically transformed layer. All operations except alignment of
  * two copies of the same package remain fully typed.
  */
private[intaglio] sealed trait PackedStatPlan:
  type Row
  def value: StatPlan[Row]

  final def layerIndex: Int = value.layerIndex
  final def layer: Layer[Row] = value.layer
  final def data: Vector[StatRow[Row]] = value.data
  final def mapping: AesSpec[StatRow[Row]] = value.mapping
  final def frame: StatFrame[Row] = value.frame
  final def packageKey: AnyRef = value.source.packageKey
  final def annotation: Option[AnnotationPlan] = value.annotation

private[intaglio] object PackedStatPlan:
  type Aux[Row0] = PackedStatPlan { type Row = Row0 }

  def apply[Row0](plan: StatPlan[Row0]): Aux[Row0] =
    new PackedStatPlan:
      type Row = Row0
      val value: StatPlan[Row] = plan

  /** Facet compilation creates global and panel-local copies from the same package. Runtime
    * identity proves their hidden row types agree; the one unavoidable erasure recovery for
    * heterogeneous layers is confined here.
    */
  def mergePositionScales(
      global: PackedStatPlan,
      local: PackedStatPlan,
      scales: FacetScales
  ): PackedStatPlan =
    require(
      global.packageKey eq local.packageKey,
      "facet plans must originate from the same layer package"
    )
    mergeAligned(global.value, local.value.asInstanceOf[StatPlan[global.Row]], scales)

  private def mergeAligned[Row](
      global: StatPlan[Row],
      local: StatPlan[Row],
      scales: FacetScales
  ): PackedStatPlan =
    val withX =
      if scales.xIsFree then replace(global.mapping, local.mapping, Aesthetic.X)
      else global.mapping
    val mapping =
      if scales.yIsFree then replace(withX, local.mapping, Aesthetic.Y)
      else withX
    val annotation = global.annotation match
      case Some(value)
          if ((value.reference.aesthetic eq Aesthetic.X) && scales.xIsFree) ||
            ((value.reference.aesthetic eq Aesthetic.Y) && scales.yIsFree) =>
        local.annotation
      case value =>
        value
    PackedStatPlan(
      global.copy(mapping = mapping, source = global.source.copy(annotation = annotation))
    )

  private def replace[Row, A](
      target: AesSpec[Row],
      source: AesSpec[Row],
      aesthetic: Aesthetic[A]
  ): AesSpec[Row] =
    source.get(aesthetic).fold(target)(target.updated(aesthetic, _))

/** Phase 1 — mapping resolution: merge layer and plot mappings, validate the input contract, and
  * reject unsupported geoms before any row is evaluated.
  */
private[intaglio] object MappingPhase:
  def plan[Row](plot: Plot[Row]): Either[GraphicsError, Vector[PackedLayerPlan]] =
    val out = Vector.newBuilder[PackedLayerPlan]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < plot.layers.length && result.isRight do
      result = planLayer(plot, plot.layers(idx), idx).map { plan =>
        out += plan
        ()
      }
      idx += 1
    result.map(_ => out.result())

  def planPanel[Row](
      plot: Plot[Row],
      facet: FacetSpec[Row],
      cell: FacetCell
  ): Either[GraphicsError, Vector[PackedLayerPlan]] =
    val out = Vector.newBuilder[PackedLayerPlan]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < plot.layers.length && result.isRight do
      val packed = plot.layers(idx)
      val annotation = packed.layer.annotation.flatMap { reference =>
        Option.when(reference.facetPolicy == AnnotationFacetPolicy.Repeat)(
          AnnotationPlan(reference, reference.coordinate)
        )
      }
      result =
        for
          panelData <- packed.panelData(plot.data, facet, cell, idx)
          plan <- planValues(
            packed.layer,
            panelData,
            packed.effectiveMapping(plot.mapping),
            idx,
            packed,
            annotation
          )
        yield
          out += PackedLayerPlan(plan)
          ()
      idx += 1
    result.map(_ => out.result())

  def planLayer[Row](
      plot: Plot[Row],
      layer: Layer[Row],
      layerIndex: Int
  ): Either[GraphicsError, LayerPlan[Row]] =
    planValues(
      layer,
      layer.effectiveData(plot.data),
      layer.effectiveMapping(plot.mapping),
      layerIndex,
      layer,
      layer.annotation.map(reference => AnnotationPlan(reference, reference.coordinate))
    )

  private def planLayer[PlotRow](
      plot: Plot[PlotRow],
      packed: PlotLayer[PlotRow],
      layerIndex: Int
  ): Either[GraphicsError, PackedLayerPlan] =
    planValues(
      packed.layer,
      packed.effectiveData(plot.data),
      packed.effectiveMapping(plot.mapping),
      layerIndex,
      packed,
      packed.layer.annotation.map(reference => AnnotationPlan(reference, reference.coordinate))
    ).map(PackedLayerPlan(_))

  private def planValues[Row](
      layer: Layer[Row],
      data: Vector[Row],
      mapping: AesSpec[Row],
      layerIndex: Int,
      packageKey: AnyRef,
      annotation: Option[AnnotationPlan]
  ): Either[GraphicsError, LayerPlan[Row]] =
    if !isSupported(layer.geom) then Left(GraphicsError.UnsupportedGeom(layer.geom.label))
    else
      Layer.validate(layer, mapping).map { _ =>
        LayerPlan(layerIndex, layer, data, mapping, packageKey, annotation)
      }

  private def isSupported(geom: Geom): Boolean =
    geom match
      case Geom.Point | Geom.Line | Geom.Text | Geom.Rect | Geom.Bar | Geom.Segment |
          Geom.ErrorBar | Geom.Ribbon | Geom.Area | Geom.HLine | Geom.VLine | Geom.Tile |
          Geom.Polygon =>
        true

/** Phase 2 — statistical transformation. Identity only lifts the source mapping into a stat row.
  * Count aggregates by its typed key, creates count and proportion fields, and owns the discrete x
  * scale plus computed y.
  */
private[intaglio] object StatPhase:
  def transform(plans: Vector[PackedLayerPlan]): Either[GraphicsError, Vector[PackedStatPlan]] =
    val out = Vector.newBuilder[PackedStatPlan]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < plans.length && result.isRight do
      result = transform(plans(idx).value).map { plan =>
        out += PackedStatPlan(plan)
        ()
      }
      idx += 1
    result.map(_ => out.result())

  def transform[Row](plan: LayerPlan[Row]): Either[GraphicsError, StatPlan[Row]] =
    plan.layer.stat match
      case Stat.Identity =>
        val rows = plan.data.map(row => StatRow(row, Vector(row), None, ComputedValues.empty))
        val frame = StatFrame(rows, Set.empty)
        val mapping = plan.mapping.contramap[StatRow[Row]](_.source)
        Right(StatPlan(plan, frame, mapping))
      case count: Stat.Count[?] =>
        countFrame(plan, count.asInstanceOf[Stat.Count[Row]])
      case bin: Stat.Bin[?] =>
        binFrame(plan, bin.asInstanceOf[Stat.Bin[Row]])
      case summary: Stat.Summary[?] =>
        summaryFrame(plan, summary.asInstanceOf[Stat.Summary[Row]])
      case density: Stat.Density[?] =>
        densityFrame(plan, density.asInstanceOf[Stat.Density[Row]])

  private def countFrame[Row](
      plan: LayerPlan[Row],
      stat: Stat.Count[Row]
  ): Either[GraphicsError, StatPlan[Row]] =
    if plan.data.isEmpty then
      val mapping = countMapping[Row](stat)
      mapping.map { resolved =>
        StatPlan(
          plan,
          StatFrame(Vector.empty, Set(ComputedAesthetic.Count, ComputedAesthetic.Proportion)),
          resolved
        )
      }
    else
      for
        keys <- evaluateStatMapping(plan, stat.label, Aesthetic.X.label, stat.x)
        rowGroups <- stat.group match
          case None =>
            Right(Vector.fill(plan.data.length)(Option.empty[String]))
          case Some(groupOf) =>
            evaluateStatMapping(plan, stat.label, Aesthetic.Group.label, groupOf)
              .map(_.map(Some(_)))
        mapping <- countMapping[Row](stat)
      yield
        val categories = stat.order.arrange(keys)
        val groupKeys = rowGroups.distinct
        val groups = scala.collection.mutable.HashMap
          .empty[(String, Option[String]), scala.collection.mutable.ArrayBuffer[Row]]
        var rowIndex = 0
        while rowIndex < plan.data.length do
          val key = keys(rowIndex)
          val group = rowGroups(rowIndex)
          groups.getOrElseUpdate((key, group), scala.collection.mutable.ArrayBuffer.empty) +=
            plan.data(rowIndex)
          rowIndex += 1
        val rows = categories.flatMap { category =>
          groupKeys.flatMap { group =>
            groups.get((category, group)).map { bucket =>
              val members = bucket.toVector
              StatRow(
                source = members.head,
                members = members,
                category = Some(category),
                computed = ComputedValues.counted(members.length, plan.data.length)
              )
            }
          }
        }
        StatPlan(
          plan,
          StatFrame(rows, Set(ComputedAesthetic.Count, ComputedAesthetic.Proportion)),
          mapping
        )

  private def countMapping[Row](
      stat: Stat.Count[Row]
  ): Either[GraphicsError, AesSpec[StatRow[Row]]] =
    BandScale(stat.scaleName.value, DiscreteDomain.empty, stat.padding).map { scale =>
      AesSpec[StatRow[Row]](
        x = Some(AesValue.scaled(_.category.getOrElse(""), scale)),
        y = Some(AesValue.direct(_.computed.get(ComputedAesthetic.Count).getOrElse(0.0))),
        group = stat.group.map(groupOf =>
          AesValue.direct(RowMapping.fromFunction(groupOf).contramap[StatRow[Row]](_.source))
        )
      )
    }

  private val binAesthetics: Set[ComputedAesthetic[?]] =
    Set(
      ComputedAesthetic.Count,
      ComputedAesthetic.Proportion,
      ComputedAesthetic.Density,
      ComputedAesthetic.BinLower,
      ComputedAesthetic.BinUpper,
      ComputedAesthetic.BinWidth,
      ComputedAesthetic.BinMidpoint
    )

  private val summaryAesthetics: Set[ComputedAesthetic[?]] =
    Set(
      ComputedAesthetic.Count,
      ComputedAesthetic.Position,
      ComputedAesthetic.Mean,
      ComputedAesthetic.Lower,
      ComputedAesthetic.Upper
    )

  private val densityAesthetics: Set[ComputedAesthetic[?]] =
    Set(ComputedAesthetic.Count, ComputedAesthetic.Position, ComputedAesthetic.Density)

  private def binFrame[Row](
      plan: LayerPlan[Row],
      stat: Stat.Bin[Row]
  ): Either[GraphicsError, StatPlan[Row]] =
    evaluateStatMapping(plan, stat.label, Aesthetic.X.label, stat.x).flatMap { values =>
      firstNonFinite(values) match
        case Some(value) =>
          Left(GraphicsError.NonFiniteStatInput(stat.label, Aesthetic.X.label, value))
        case None if values.isEmpty =>
          val mapping = binMapping[Row]
          Right(StatPlan(plan, StatFrame(Vector.empty, binAesthetics), mapping))
        case None =>
          val breaks = HistogramBins.partition(stat.bins, values.min, values.max)
          val lower = breaks.head
          val upper = breaks.last
          values.find(value => value < lower || value > upper) match
            case Some(value) if HistogramBins.isExplicit(stat.bins) =>
              Left(GraphicsError.StatInputOutsideBins(value, lower, upper))
            case _ =>
              val buckets =
                Array.fill(breaks.length - 1)(scala.collection.mutable.ArrayBuffer.empty[Row])
              var rowIndex = 0
              while rowIndex < plan.data.length do
                val value = values(rowIndex)
                val binIndex = findBin(value, breaks)
                if binIndex >= 0 then buckets(binIndex) += plan.data(rowIndex)
                rowIndex += 1
              val rows = Vector.newBuilder[StatRow[Row]]
              var binIndex = 0
              while binIndex < buckets.length do
                val members = buckets(binIndex).toVector
                if members.nonEmpty then
                  rows += StatRow(
                    members.head,
                    members,
                    None,
                    ComputedValues.binned(
                      members.length,
                      plan.data.length,
                      breaks(binIndex),
                      breaks(binIndex + 1)
                    )
                  )
                binIndex += 1
              val mapping = binMapping[Row]
              Right(StatPlan(plan, StatFrame(rows.result(), binAesthetics), mapping))
    }

  private def binMapping[Row]: AesSpec[StatRow[Row]] =
    AesSpec(
      x = Some(AesValue.direct(_.computed.get(ComputedAesthetic.BinMidpoint).getOrElse(0.0))),
      y = Some(AesValue.direct(_.computed.get(ComputedAesthetic.Count).getOrElse(0.0)))
    )

  /** ggplot2 histograms are right-closed by default: the first interval also owns its lower
    * boundary, while an internal break belongs to the bin on its left.
    */
  private def findBin(value: Double, breaks: Vector[Double]): Int =
    var idx = 0
    var found = -1
    while idx < breaks.length - 1 && found < 0 do
      val aboveLower = if idx == 0 then value >= breaks(idx) else value > breaks(idx)
      if aboveLower && value <= breaks(idx + 1) then found = idx
      idx += 1
    found

  private def summaryFrame[Row](
      plan: LayerPlan[Row],
      stat: Stat.Summary[Row]
  ): Either[GraphicsError, StatPlan[Row]] =
    for
      xs <- evaluateStatMapping(plan, stat.label, Aesthetic.X.label, stat.x)
      ys <- evaluateStatMapping(plan, stat.label, Aesthetic.Y.label, stat.y)
      transformed <-
        firstNonFinite(xs) match
          case Some(value) =>
            Left(GraphicsError.NonFiniteStatInput(stat.label, Aesthetic.X.label, value))
          case None =>
            firstNonFinite(ys) match
              case Some(value) =>
                Left(GraphicsError.NonFiniteStatInput(stat.label, Aesthetic.Y.label, value))
              case None =>
                val groups = scala.collection.mutable.HashMap
                  .empty[Double, scala.collection.mutable.ArrayBuffer[(Row, Double)]]
                var idx = 0
                while idx < plan.data.length do
                  groups.getOrElseUpdate(xs(idx), scala.collection.mutable.ArrayBuffer.empty) += ((
                    plan.data(idx),
                    ys(idx)
                  ))
                  idx += 1
                val rows = groups.keys.toVector.sorted.map { x =>
                  val observations = groups(x).toVector
                  val values = observations.map(_._2)
                  val mean = values.sum / values.length.toDouble
                  val (lower, upper) = summaryBounds(values, mean, stat.interval)
                  StatRow(
                    observations.head._1,
                    observations.map(_._1),
                    None,
                    ComputedValues.summarized(x, mean, lower, upper, values.length)
                  )
                }
                val mapping = summaryMapping[Row]
                Right(StatPlan(plan, StatFrame(rows, summaryAesthetics), mapping))
    yield transformed

  private def summaryBounds(
      values: Vector[Double],
      mean: Double,
      interval: SummaryInterval
  ): (Double, Double) =
    interval match
      case SummaryInterval.StandardError =>
        val standardError =
          if values.length < 2 then 0.0
          else
            var sumSquares = 0.0
            var idx = 0
            while idx < values.length do
              val centered = values(idx) - mean
              sumSquares += centered * centered
              idx += 1
            math.sqrt(sumSquares / (values.length - 1).toDouble) / math.sqrt(values.length.toDouble)
        (mean - standardError, mean + standardError)
      case SummaryInterval.Range =>
        (values.min, values.max)

  private def summaryMapping[Row]: AesSpec[StatRow[Row]] =
    AesSpec(
      x = Some(AesValue.direct(_.computed.get(ComputedAesthetic.Position).getOrElse(0.0))),
      y = Some(AesValue.direct(_.computed.get(ComputedAesthetic.Mean).getOrElse(0.0)))
    )

  private def densityFrame[Row](
      plan: LayerPlan[Row],
      stat: Stat.Density[Row]
  ): Either[GraphicsError, StatPlan[Row]] =
    evaluateStatMapping(plan, stat.label, Aesthetic.X.label, stat.x).flatMap { mapped =>
      val values = mapped.toArray
      firstNonFinite(values) match
        case Some(value) =>
          Left(GraphicsError.NonFiniteStatInput(stat.label, Aesthetic.X.label, value))
        case None if values.length < 2 =>
          Left(GraphicsError.InsufficientStatData(stat.label, 2, values.length))
        case None =>
          val bandwidth = stat.config.bandwidth.map(_.toDouble).getOrElse(DensityMath.nrd0(values))
          val domain = stat.config.domain.getOrElse(Interval.unsafe(values.min, values.max))
          val points = stat.config.points.toInt
          val step = domain.width / (points - 1).toDouble
          val rows = Vector.tabulate(points) { idx =>
            val position = domain.lower + step * idx.toDouble
            val density = gaussianDensity(values, position, bandwidth)
            StatRow(
              plan.data.head,
              plan.data,
              None,
              ComputedValues.densityAt(position, density, plan.data.length)
            )
          }
          val mapping = densityMapping[Row]
          Right(StatPlan(plan, StatFrame(rows, densityAesthetics), mapping))
    }

  private def evaluateStatMapping[Row, A](
      plan: LayerPlan[Row],
      stat: String,
      aesthetic: String,
      mapping: Row => A
  ): Either[GraphicsError, Vector[A]] =
    val out = Vector.newBuilder[A]
    var rowIndex = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while rowIndex < plan.data.length && result.isRight do
      RowMapping.evaluateFunction(mapping, plan.data(rowIndex)) match
        case Right(value) =>
          out += value
        case Left((contract, failure)) =>
          result = Left(
            GraphicsError.MappingEvaluationFailed(
              s"stat '$stat'",
              Some(plan.layerIndex),
              aesthetic,
              rowIndex,
              contract,
              failure
            )
          )
      rowIndex += 1
    result.map(_ => out.result())

  private def densityMapping[Row]: AesSpec[StatRow[Row]] =
    AesSpec(
      x = Some(AesValue.direct(_.computed.get(ComputedAesthetic.Position).getOrElse(0.0))),
      y = Some(AesValue.direct(_.computed.get(ComputedAesthetic.Density).getOrElse(0.0)))
    )

  private def gaussianDensity(values: Array[Double], position: Double, bandwidth: Double): Double =
    val normalizer = values.length.toDouble * bandwidth * math.sqrt(2.0 * math.Pi)
    var sum = 0.0
    var idx = 0
    while idx < values.length do
      val z = (position - values(idx)) / bandwidth
      sum += math.exp(-0.5 * z * z)
      idx += 1
    sum / normalizer

  private def firstNonFinite(values: Vector[Double]): Option[Double] =
    values.find(value => !value.isFinite)

  private def firstNonFinite(values: Array[Double]): Option[Double] =
    var idx = 0
    var result: Option[Double] = None
    while idx < values.length && result.isEmpty do
      if !values(idx).isFinite then result = Some(values(idx))
      idx += 1
    result

/** Output of plot-wide scale training: every layer plan is rebound to the same trained scale for
  * each aesthetic, and the plot registry contains one entry per aesthetic.
  */
private[intaglio] final case class ScaleResolution(
    plans: Vector[PackedStatPlan],
    registry: PlotScaleRegistry
)

/** Phase 3 — plot-wide scale training. All observations from all layers using an aesthetic train
  * one shared scale before any row is mapped. Distinct scale declarations for the same aesthetic
  * are rejected instead of silently placing independently normalized layers on one axis.
  */
private[intaglio] object ScalePhase:
  private final case class Contribution(
      layerIndex: Int,
      entry: RegisteredScale[?],
      observations: Vector[ScaleObservation]
  )

  def train(
      plans: Vector[PackedStatPlan],
      theme: Theme = Theme.default
  ): Either[GraphicsError, ScaleResolution] =
    val initial = ScaleResolution(plans, PlotScaleRegistry.empty)
    declaredAesthetics(plans).foldLeft[Either[GraphicsError, ScaleResolution]](Right(initial)) {
      (result, aesthetic) =>
        result.flatMap(
          trainAesthetic(
            _,
            aesthetic,
            facetLocal = false,
            unifyFacetCopies = false,
            theme = theme
          )
        )
    }

  /** Facet statistics are transformed panel-by-panel, so a computed stat may construct equivalent
    * scale values more than once. Copies are unified only when they retain the same source layer
    * and compatible descriptor; distinct plot layers keep the ordinary strict conflict rule.
    */
  def trainFacets(
      plans: Vector[PackedStatPlan],
      theme: Theme = Theme.default
  ): Either[GraphicsError, ScaleResolution] =
    val initial = ScaleResolution(plans, PlotScaleRegistry.empty)
    declaredAesthetics(plans).foldLeft[Either[GraphicsError, ScaleResolution]](Right(initial)) {
      (result, aesthetic) =>
        result.flatMap(
          trainAesthetic(
            _,
            aesthetic,
            facetLocal = false,
            unifyFacetCopies = true,
            theme = theme
          )
        )
    }

  def trainFacetPositions(
      plans: Vector[PackedStatPlan],
      scales: FacetScales,
      theme: Theme = Theme.default
  ): Either[GraphicsError, Vector[PackedStatPlan]] =
    val aesthetics =
      Vector(
        Option.when(scales.xIsFree)(Aesthetic.X),
        Option.when(scales.yIsFree)(Aesthetic.Y)
      ).flatten
    val initial = ScaleResolution(plans, PlotScaleRegistry.empty)
    aesthetics
      .foldLeft[Either[GraphicsError, ScaleResolution]](Right(initial)) { (result, aesthetic) =>
        result.flatMap(
          trainAesthetic(
            _,
            aesthetic,
            facetLocal = true,
            unifyFacetCopies = false,
            theme = theme
          )
        )
      }
      .map(_.plans)

  /** Every core and ecosystem key actually present in the plans, in deterministic declaration
    * order. Scale training must discover open aesthetics from mappings rather than a closed global
    * registry.
    */
  private[intaglio] def declaredAesthetics(
      plans: Vector[PackedStatPlan]
  ): Vector[Aesthetic[?]] =
    plans.foldLeft(Vector.empty[Aesthetic[?]]) { (result, plan) =>
      plan.mapping.bound.foldLeft(result) { (keys, aesthetic) =>
        if keys.exists(_ eq aesthetic) then keys else keys :+ aesthetic
      }
    }

  def registry(plan: PackedStatPlan): ScaleRegistry[?] =
    registryTyped(plan.value)

  private def registryTyped[Row](plan: StatPlan[Row]): ScaleRegistry[StatRow[Row]] =
    ScaleRegistry.fromMapping(plan.mapping)

  private def trainAesthetic(
      resolution: ScaleResolution,
      aesthetic: Aesthetic[?],
      facetLocal: Boolean,
      unifyFacetCopies: Boolean,
      theme: Theme
  ): Either[GraphicsError, ScaleResolution] =
    contributions(resolution.plans, aesthetic).flatMap { contributions =>
      contributions.headOption match
        case None =>
          Right(resolution)
        case Some(first) =>
          contributions.find { contribution =>
            !first.entry.sharesDeclaration(contribution.entry) &&
            !(unifyFacetCopies && compatibleFacetCopy(first, contribution))
          } match
            case Some(conflicting) =>
              Left(
                GraphicsError.ConflictingPlotScales(
                  aesthetic.label,
                  first.layerIndex,
                  first.entry.descriptor.name.value,
                  conflicting.layerIndex,
                  conflicting.entry.descriptor.name.value
                )
              )
            case None =>
              for
                annotationObservations <- annotationObservations(
                  resolution.plans,
                  aesthetic,
                  first.entry
                )
                observations = contributions.flatMap(_.observations) ++ annotationObservations
                trained <- trainEntry(first.entry, observations, facetLocal, theme)
                rebound <- rebind(
                  resolution.plans,
                  aesthetic,
                  first.entry,
                  trained,
                  unifyFacetCopies
                )
                plans <- mapAnnotations(rebound, aesthetic, trained)
              yield ScaleResolution(
                plans,
                PlotScaleRegistry.from(resolution.registry.scales :+ trained.trained)
              )
    }

  private def contributions(
      plans: Vector[PackedStatPlan],
      aesthetic: Aesthetic[?]
  ): Either[GraphicsError, Vector[Contribution]] =
    val out = Vector.newBuilder[Contribution]
    var index = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while index < plans.length && result.isRight do
      result = contribution(plans(index), aesthetic).map { value =>
        value.foreach(out += _)
        ()
      }
      index += 1
    result.map(_ => out.result())

  private def contribution(
      plan: PackedStatPlan,
      aesthetic: Aesthetic[?]
  ): Either[GraphicsError, Option[Contribution]] =
    contributionTyped(plan.value, aesthetic)

  private def contributionTyped[Row](
      plan: StatPlan[Row],
      aesthetic: Aesthetic[?]
  ): Either[GraphicsError, Option[Contribution]] =
    plan.mapping.scaledEntry(aesthetic) match
      case None =>
        Right(None)
      case Some(entry) =>
        entry
          .observations(plan.data, plan.layerIndex)
          .map(observations => Some(Contribution(plan.layerIndex, entry, observations)))

  private def compatibleFacetCopy(
      first: Contribution,
      candidate: Contribution
  ): Boolean =
    val left = first.entry.descriptor
    val right = candidate.entry.descriptor
    first.layerIndex == candidate.layerIndex &&
    left.name == right.name &&
    left.kind == right.kind &&
    left.training == right.training

  private def annotationObservations(
      plans: Vector[PackedStatPlan],
      aesthetic: Aesthetic[?],
      entry: RegisteredScale[?]
  ): Either[GraphicsError, Vector[ScaleObservation]] =
    val annotations = plans.flatMap(_.annotation).filter { annotation =>
      (annotation.reference.aesthetic eq aesthetic) &&
      annotation.reference.scalePolicy == AnnotationScalePolicy.Train
    }
    if annotations.isEmpty then Right(Vector.empty)
    else
      entry.descriptor.kind match
        case ScaleKind.Continuous =>
          Right(annotations.map(annotation => ScaleObservation.Continuous(annotation.coordinate)))
        case _ =>
          val reference = annotations.head.reference
          Left(
            GraphicsError.AnnotationRequiresContinuousScale(
              reference.orientation.label,
              aesthetic.label,
              entry.descriptor.name.value
            )
          )

  private def mapAnnotations[EntryRow](
      plans: Vector[PackedStatPlan],
      aesthetic: Aesthetic[?],
      trained: RegisteredScale[EntryRow]
  ): Either[GraphicsError, Vector[PackedStatPlan]] =
    val out = Vector.newBuilder[PackedStatPlan]
    var index = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while index < plans.length && result.isRight do
      result = mapAnnotation(plans(index), aesthetic, trained).map { plan =>
        out += plan
        ()
      }
      index += 1
    result.map(_ => out.result())

  private def mapAnnotation[EntryRow](
      plan: PackedStatPlan,
      aesthetic: Aesthetic[?],
      trained: RegisteredScale[EntryRow]
  ): Either[GraphicsError, PackedStatPlan] =
    mapAnnotationTyped(plan.value, aesthetic, trained)

  private def mapAnnotationTyped[Row, EntryRow](
      plan: StatPlan[Row],
      aesthetic: Aesthetic[?],
      trained: RegisteredScale[EntryRow]
  ): Either[GraphicsError, PackedStatPlan] =
    plan.annotation match
      case Some(annotation)
          if (annotation.reference.aesthetic eq aesthetic) &&
            annotation.reference.scalePolicy == AnnotationScalePolicy.Train =>
        mapAnnotationCoordinate(annotation, aesthetic, trained).map { coordinate =>
          val resolved = annotation.copy(
            coordinate = coordinate,
            trainedScale = Some(trained.trained)
          )
          PackedStatPlan(plan.copy(source = plan.source.copy(annotation = Some(resolved))))
        }
      case _ =>
        Right(PackedStatPlan(plan))

  private def mapAnnotationCoordinate[EntryRow](
      annotation: AnnotationPlan,
      aesthetic: Aesthetic[?],
      trained: RegisteredScale[EntryRow]
  ): Either[GraphicsError, Double] =
    trained.scale match
      case continuous: ContinuousScale[?] =>
        continuous
          .asInstanceOf[ContinuousScale[Double]]
          .mapValueResult(annotation.reference.coordinate)
          .left
          .map(failure =>
            GraphicsError.AnnotationScaleMappingFailed(
              annotation.reference.orientation.label,
              aesthetic.label,
              annotation.reference.coordinate,
              failure.toString
            )
          )
      case _ =>
        Left(
          GraphicsError.AnnotationRequiresContinuousScale(
            annotation.reference.orientation.label,
            aesthetic.label,
            trained.descriptor.name.value
          )
        )

  private def rebind(
      plans: Vector[PackedStatPlan],
      aesthetic: Aesthetic[?],
      source: RegisteredScale[?],
      trained: RegisteredScale[?],
      allowCompatibleFacetCopy: Boolean
  ): Either[GraphicsError, Vector[PackedStatPlan]] =
    val out = Vector.newBuilder[PackedStatPlan]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < plans.length && result.isRight do
      val plan = plans(idx)
      result = rebindPlan(plan, aesthetic, source, trained, allowCompatibleFacetCopy).map {
        rebound =>
          out += rebound
          ()
      }
      idx += 1
    result.map(_ => out.result())

  private def rebindPlan(
      plan: PackedStatPlan,
      aesthetic: Aesthetic[?],
      source: RegisteredScale[?],
      trained: RegisteredScale[?],
      allowCompatibleFacetCopy: Boolean
  ): Either[GraphicsError, PackedStatPlan] =
    rebindTyped(plan.value, aesthetic, source, trained, allowCompatibleFacetCopy)

  private def rebindTyped[Row](
      plan: StatPlan[Row],
      aesthetic: Aesthetic[?],
      source: RegisteredScale[?],
      trained: RegisteredScale[?],
      allowCompatibleFacetCopy: Boolean
  ): Either[GraphicsError, PackedStatPlan] =
    plan.mapping.scaledEntry(aesthetic) match
      case None =>
        Right(PackedStatPlan(plan))
      case Some(entry) =>
        Right(
          PackedStatPlan(
            plan.copy(
              mapping = entry.installTrainedFrom(
                source,
                trained,
                plan.mapping,
                allowCompatibleFacetCopy
              )
            )
          )
        )

  private def trainEntry[EntryRow](
      entry: RegisteredScale[EntryRow],
      observations: Vector[ScaleObservation],
      facetLocal: Boolean,
      theme: Theme
  ): Either[GraphicsError, RegisteredScale[EntryRow]] =
    entry.train(observations, theme, facetLocal)

/** Phase 4 — row evaluation: map each stat row through the canonical aesthetic mapping, keeping
  * typed drop diagnostics for rows a renderer must skip.
  */
private[intaglio] object RowPhase:
  def resolve[Row](
      plan: StatPlan[Row],
      theme: Theme = Theme.default
  ): Either[GraphicsError, (Vector[ResolvedRow[Row]], Vector[DroppedRow[Row]])] =
    val grouping = plan.mapping.groupingDecision
    val rows = Vector.newBuilder[ResolvedRow[Row]]
    val dropped = Vector.newBuilder[DroppedRow[Row]]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < plan.data.length && result.isRight do
      val source = plan.data(idx)
      resolveRow(idx, source, plan.layer, plan.mapping, grouping, theme) match
        case RowResolution.Resolved(row) =>
          rows += row
        case RowResolution.Dropped(reason) =>
          dropped += DroppedRow(plan.layerIndex, idx, source.source, reason)
        case RowResolution.Failed(error) =>
          result = Left(error)
      idx += 1
    result.map(_ => (rows.result(), dropped.result()))

  private def resolveRow[Row](
      rowIndex: Int,
      source: StatRow[Row],
      layer: Layer[Row],
      mapping: AesSpec[StatRow[Row]],
      grouping: GroupingDecision,
      theme: Theme
  ): RowResolution[Row] =
    val resolved =
      for
        xValue <- requiredEvaluatedAes(Aesthetic.X, mapping.get(Aesthetic.X), source, rowIndex)
        yValue <- requiredEvaluatedAes(Aesthetic.Y, mapping.get(Aesthetic.Y), source, rowIndex)
        x = xValue.value
        y = yValue.value
        _ <- finitePosition(x, y)
        xBand = xValue.band
        yBand = yValue.band
        xEnd <- optionalFiniteAes(Aesthetic.XEnd, mapping.get(Aesthetic.XEnd), source, rowIndex)
        yEnd <- optionalFiniteAes(Aesthetic.YEnd, mapping.get(Aesthetic.YEnd), source, rowIndex)
        xMin <- optionalFiniteAes(Aesthetic.XMin, mapping.get(Aesthetic.XMin), source, rowIndex)
        xMax <- optionalFiniteAes(Aesthetic.XMax, mapping.get(Aesthetic.XMax), source, rowIndex)
        yMin <- optionalFiniteAes(Aesthetic.YMin, mapping.get(Aesthetic.YMin), source, rowIndex)
        yMax <- optionalFiniteAes(Aesthetic.YMax, mapping.get(Aesthetic.YMax), source, rowIndex)
        _ <- validBounds(Aesthetic.X.label, xMin, xMax)
        _ <- validBounds(Aesthetic.Y.label, yMin, yMax)
        text <- labelValue(layer.geom, mapping, source, rowIndex)
        explicitGroup <- optionalAes(
          Aesthetic.Group,
          mapping.get(Aesthetic.Group),
          source,
          rowIndex
        )
        subpath <- optionalAes(Aesthetic.Subpath, mapping.get(Aesthetic.Subpath), source, rowIndex)
        stroke <- optionalEvaluatedAes(
          Aesthetic.Color,
          mapping.get(Aesthetic.Color),
          source,
          rowIndex
        )
        fill <- optionalEvaluatedAes(Aesthetic.Fill, mapping.get(Aesthetic.Fill), source, rowIndex)
        alpha <- optionalEvaluatedAes(
          Aesthetic.Alpha,
          mapping.get(Aesthetic.Alpha),
          source,
          rowIndex
        )
        mappedSize <- optionalEvaluatedAes(
          Aesthetic.Size,
          mapping.get(Aesthetic.Size),
          source,
          rowIndex
        )
        gp <- rowGraphicParams(
          layer.params.getOrElse(theme.geom),
          stroke.map(_.value),
          fill.map(_.value),
          alpha.map(_.value)
        )
        size <- rowSize(mappedSize.map(_.value), theme.pointSizePt)
        groupKey <- resolveGroupKey(grouping, explicitGroup, stroke, fill, alpha, mappedSize)
      yield ResolvedRow(
        rowIndex = rowIndex,
        source = source.source,
        computed = source.computed,
        x = x,
        y = y,
        xBand = xBand,
        yBand = yBand,
        xEnd = xEnd,
        yEnd = yEnd,
        xMin = xMin,
        xMax = xMax,
        yMin = yMin,
        yMax = yMax,
        point = Point.nativeUnsafe(x, y),
        label = if layer.geom == Geom.Text then Some(text) else None,
        grouping = grouping,
        groupKey = groupKey,
        group = groupKey.map(_.display),
        subpath = subpath,
        gp = gp,
        size = size
      )
    resolved match
      case Right(row)   => RowResolution.Resolved(row)
      case Left(reason) => RowResolution.Dropped(reason)

  private def rowGraphicParams(
      base: GraphicParams,
      stroke: Option[Rgba],
      fill: Option[Rgba],
      alpha: Option[Double]
  ): Either[PlotDropReason, GraphicParams] =
    base
      .withAestheticOverrides(
        stroke = stroke,
        fill = fill,
        alpha = alpha
      )
      .left
      .map(error => PlotDropReason.InvalidAesthetic("gp", error.message))

  private def rowSize(
      value: Option[Double],
      defaultSizePt: Double
  ): Either[PlotDropReason, ExtentExpr] =
    value match
      case None =>
        Right(ExtentExpr.pointsUnsafe(defaultSizePt))
      case Some(size) =>
        ExtentExpr
          .points(size)
          .left
          .map(error => PlotDropReason.InvalidAesthetic("size", error.message))

  private def resolveGroupKey(
      grouping: GroupingDecision,
      explicit: Option[String],
      color: Option[EvaluatedAes[Rgba]],
      fill: Option[EvaluatedAes[Rgba]],
      alpha: Option[EvaluatedAes[Double]],
      size: Option[EvaluatedAes[Double]]
  ): Either[PlotDropReason, Option[GroupKey]] =
    grouping match
      case GroupingDecision.Ungrouped =>
        Right(None)
      case GroupingDecision.Explicit =>
        Right(explicit.map(GroupKey.Explicit(_)))
      case GroupingDecision.Inferred(aesthetics) =>
        val values = Vector(
          discreteGroupValue(Aesthetic.Color, color),
          discreteGroupValue(Aesthetic.Fill, fill),
          discreteGroupValue(Aesthetic.Alpha, alpha),
          discreteGroupValue(Aesthetic.Size, size)
        ).flatten
        aesthetics.find(aesthetic => !values.exists(_.aesthetic eq aesthetic)) match
          case Some(aesthetic) =>
            Left(PlotDropReason.GroupingCategoryUnavailable(aesthetic.label))
          case None =>
            Right(Some(GroupKey.Inferred(values)))

  private def discreteGroupValue[A](
      aesthetic: Aesthetic[A],
      evaluated: Option[EvaluatedAes[A]]
  ): Option[DiscreteGroupValue] =
    evaluated.flatMap(_.rawDiscreteCategory.map(DiscreteGroupValue(aesthetic, _)))

  private def labelValue[Row](
      geom: Geom,
      mapping: AesSpec[StatRow[Row]],
      row: StatRow[Row],
      rowIndex: Int
  ): Either[PlotDropReason, String] =
    geom match
      case Geom.Text =>
        requiredAes(Aesthetic.Label, mapping.get(Aesthetic.Label), row, rowIndex)
      case _ =>
        Right("")

  private def finitePosition(x: Double, y: Double): Either[PlotDropReason, Unit] =
    if x.isFinite && y.isFinite then Right(())
    else Left(PlotDropReason.NonFinitePosition(x, y))

  private def optionalFiniteAes[Row](
      aesthetic: Aesthetic[Double],
      value: Option[AesValue[Row, Double]],
      row: Row,
      rowIndex: Int
  ): Either[PlotDropReason, Option[Double]] =
    optionalAes(aesthetic, value, row, rowIndex).flatMap {
      case Some(resolved) if !resolved.isFinite =>
        Left(PlotDropReason.NonFiniteAesthetic(aesthetic.label, resolved))
      case resolved =>
        Right(resolved)
    }

  private def validBounds(
      axis: String,
      minimum: Option[Double],
      maximum: Option[Double]
  ): Either[PlotDropReason, Unit] =
    (minimum, maximum) match
      case (Some(lower), Some(upper)) if lower > upper =>
        Left(PlotDropReason.InvalidBounds(axis, lower, upper))
      case _ =>
        Right(())

  private def requiredAes[Row, A](
      aesthetic: Aesthetic[A],
      value: Option[AesValue[Row, A]],
      row: Row,
      rowIndex: Int
  ): Either[PlotDropReason, A] =
    requiredEvaluatedAes(aesthetic, value, row, rowIndex).map(_.value)

  private def requiredEvaluatedAes[Row, A](
      aesthetic: Aesthetic[A],
      value: Option[AesValue[Row, A]],
      row: Row,
      rowIndex: Int
  ): Either[PlotDropReason, EvaluatedAes[A]] =
    value match
      case None      => Left(PlotDropReason.MissingAesthetic(aesthetic.label))
      case Some(aes) => evalAes(aesthetic, aes, row, rowIndex)

  private def optionalAes[Row, A](
      aesthetic: Aesthetic[A],
      value: Option[AesValue[Row, A]],
      row: Row,
      rowIndex: Int
  ): Either[PlotDropReason, Option[A]] =
    value match
      case None      => Right(None)
      case Some(aes) => evalAes(aesthetic, aes, row, rowIndex).map(value => Some(value.value))

  private def optionalEvaluatedAes[Row, A](
      aesthetic: Aesthetic[A],
      value: Option[AesValue[Row, A]],
      row: Row,
      rowIndex: Int
  ): Either[PlotDropReason, Option[EvaluatedAes[A]]] =
    value match
      case None      => Right(None)
      case Some(aes) => evalAes(aesthetic, aes, row, rowIndex).map(Some(_))

  private def evalAes[Row, A](
      aesthetic: Aesthetic[A],
      value: AesValue[Row, A],
      row: Row,
      rowIndex: Int
  ): Either[PlotDropReason, EvaluatedAes[A]] =
    value match
      case AesValue.Direct(f) =>
        RowMapping
          .evaluateFunction(f, row)
          .map(EvaluatedAes(_, None, None))
          .left
          .map(toMappingDropReason(aesthetic, rowIndex, _))
      case AesValue.Constant(v) =>
        Right(EvaluatedAes(v, None, None))
      case scaled: AesValue.Scaled[Row, ?, A] =>
        RowMapping
          .evaluateFunction(scaled.value, row)
          .left
          .map(toMappingDropReason(aesthetic, rowIndex, _))
          .flatMap { input =>
            scaled.scale
              .mapDeclaredValueResult(input)
              .map { output =>
                val rawDiscreteCategory =
                  if scaled.scale.descriptor.kind == ScaleKind.Discrete then
                    scaled.scale.observation(input).collect {
                      case ScaleObservation.Discrete(category) => category
                    }
                  else None
                EvaluatedAes(output, scaled.scale.mappedBand(input), rawDiscreteCategory)
              }
              .left
              .map(toDropReason(aesthetic, _))
          }

  private def toMappingDropReason[A](
      aesthetic: Aesthetic[A],
      rowIndex: Int,
      problem: RowMapping.Problem
  ): PlotDropReason =
    PlotDropReason.MappingEvaluationFailed(
      aesthetic.label,
      rowIndex,
      problem._1,
      problem._2
    )

  private def toDropReason[A](
      aesthetic: Aesthetic[A],
      failure: ScaleMapFailure
  ): PlotDropReason =
    failure match
      case ScaleMapFailure.TransformDomain(transform, value) =>
        PlotDropReason.TransformDomain(aesthetic.label, transform, value)
      case ScaleMapFailure.OutOfDomain(scale, value) =>
        PlotDropReason.ScaleOutOfDomain(aesthetic.label, scale, value)
      case ScaleMapFailure.PaletteOverflow(scale, levels, capacity) =>
        PlotDropReason.PaletteOverflow(aesthetic.label, scale, levels, capacity)

  private final case class EvaluatedAes[+A](
      value: A,
      band: Option[Band],
      rawDiscreteCategory: Option[String]
  )

  private enum RowResolution[Row]:
    case Resolved(row: ResolvedRow[Row])
    case Dropped(reason: PlotDropReason)
    case Failed(error: GraphicsError)

/** Phase 5 — pure position adjustment over resolved statistical rows. The phase owns collision
  * semantics; geoms only lower the resulting geometry.
  */
private[intaglio] object PositionPhase:
  def adjust[Row](
      layer: Layer[Row],
      rows: Vector[ResolvedRow[Row]]
  ): Either[GraphicsError, Vector[ResolvedRow[Row]]] =
    layer.position match
      case Position.Identity =>
        Right(rows)
      case Position.Dodge(config) =>
        Right(dodge(layer.geom, rows, config))
      case Position.Stack(order) =>
        if layer.geom == Geom.Bar then Right(stack(rows, order))
        else Left(GraphicsError.InvalidPositionGeom("stack", layer.geom.label))
      case Position.Jitter(config) =>
        if layer.geom == Geom.Point then Right(jitter(rows, config))
        else Left(GraphicsError.InvalidPositionGeom("jitter", layer.geom.label))

  private def dodge[Row](
      geom: Geom,
      rows: Vector[ResolvedRow[Row]],
      config: DodgeConfig
  ): Vector[ResolvedRow[Row]] =
    val updated = scala.collection.mutable.ArrayBuffer.from(rows)
    val globalGroups = rows.map(_.groupKey).distinct
    val positions = rows.map(_.x).distinct
    positions.foreach { base =>
      val indices = rows.indices.filter(index => rows(index).x == base).toVector
      val localGroups =
        globalGroups.filter(group => indices.exists(index => rows(index).groupKey == group))
      val slots = config.preserve match
        case DodgePreserve.Total  => localGroups
        case DodgePreserve.Single => globalGroups
      val slotCount = math.max(1, slots.length)
      val displacementWidth = config.width.fold {
        indices.flatMap(index => rows(index).xBand.map(_.width)).maxOption.getOrElse(0.9)
      }(_.toDouble)
      indices.foreach { index =>
        val row = rows(index)
        val slot = math.max(0, slots.indexOf(row.groupKey))
        val center = base + displacementWidth * ((slot.toDouble + 0.5) / slotCount.toDouble - 0.5)
        val delta = center - row.x
        val sourceWidth = row.xBand.map(_.width).getOrElse(0.9)
        val band = row.xBand
          .map(_ => Band.unsafe(center, sourceWidth / slotCount.toDouble))
          .orElse(
            Option.when(geom == Geom.Bar)(Band.unsafe(center, sourceWidth / slotCount.toDouble))
          )
        val (xMin, xMax) = (row.xMin, row.xMax) match
          case (Some(lower), Some(upper)) =>
            val width = (upper - lower) / slotCount.toDouble
            (Some(center - width / 2.0), Some(center + width / 2.0))
          case _ =>
            (row.xMin.map(_ + delta), row.xMax.map(_ + delta))
        updated(index) = row.copy(
          x = center,
          xBand = band,
          xEnd = row.xEnd.map(_ + delta),
          xMin = xMin,
          xMax = xMax,
          point = Point.nativeUnsafe(center, row.y)
        )
      }
    }
    updated.toVector

  private def stack[Row](
      rows: Vector[ResolvedRow[Row]],
      order: StackOrder
  ): Vector[ResolvedRow[Row]] =
    val updated = scala.collection.mutable.ArrayBuffer.from(rows)
    val encountered = rows.map(_.groupKey).distinct
    val groupOrder = order match
      case StackOrder.Encountered => encountered
      case StackOrder.Reverse     => encountered.reverse
    rows.map(_.x).distinct.foreach { x =>
      val atPosition = rows.indices.filter(index => rows(index).x == x).toVector
      val positives = ordered(atPosition.filter(index => rows(index).y >= 0.0), rows, groupOrder)
      val negatives = ordered(atPosition.filter(index => rows(index).y < 0.0), rows, groupOrder)
      stackSide(positives, rows, updated, positive = true)
      stackSide(negatives, rows, updated, positive = false)
    }
    updated.toVector

  private def ordered[Row](
      indices: Vector[Int],
      rows: Vector[ResolvedRow[Row]],
      groups: Vector[Option[GroupKey]]
  ): Vector[Int] =
    indices.sortBy(index => (groups.indexOf(rows(index).groupKey), index))

  private def stackSide[Row](
      indices: Vector[Int],
      rows: Vector[ResolvedRow[Row]],
      updated: scala.collection.mutable.ArrayBuffer[ResolvedRow[Row]],
      positive: Boolean
  ): Unit =
    var cursor = 0.0
    indices.foreach { index =>
      val row = rows(index)
      val next = cursor + row.y
      val lower = math.min(cursor, next)
      val upper = math.max(cursor, next)
      val position = if positive then upper else lower
      updated(index) = row.copy(
        y = position,
        yMin = Some(lower),
        yMax = Some(upper),
        point = Point.nativeUnsafe(row.x, position)
      )
      cursor = next
    }

  private def jitter[Row](
      rows: Vector[ResolvedRow[Row]],
      config: JitterConfig
  ): Vector[ResolvedRow[Row]] =
    val xAmount = config.width.fold(resolution(rows.map(_.x)) * 0.4)(_.toDouble)
    val yAmount = config.height.fold(resolution(rows.map(_.y)) * 0.4)(_.toDouble)
    rows.zipWithIndex.map { case (row, index) =>
      val xOffset = symmetric(config.seed.toLong, index, axis = 0) * xAmount
      val yOffset = symmetric(config.seed.toLong, index, axis = 1) * yAmount
      translate(row, xOffset, yOffset)
    }

  private def resolution(values: Vector[Double]): Double =
    val ordered = values.filter(_.isFinite).distinct.sorted
    ordered
      .sliding(2)
      .flatMap {
        case Vector(left, right) if right > left => Some(right - left)
        case _                                   => None
      }
      .minOption
      .getOrElse(1.0)

  private def translate[Row](
      row: ResolvedRow[Row],
      xOffset: Double,
      yOffset: Double
  ): ResolvedRow[Row] =
    val x = row.x + xOffset
    val y = row.y + yOffset
    row.copy(
      x = x,
      y = y,
      xEnd = row.xEnd.map(_ + xOffset),
      yEnd = row.yEnd.map(_ + yOffset),
      xMin = row.xMin.map(_ + xOffset),
      xMax = row.xMax.map(_ + xOffset),
      yMin = row.yMin.map(_ + yOffset),
      yMax = row.yMax.map(_ + yOffset),
      point = Point.nativeUnsafe(x, y)
    )

  /** SplitMix64 gives identical integer arithmetic on the JVM and Scala.js. Each row/axis is
    * addressed independently, so traversal refactors cannot perturb later offsets.
    */
  private def symmetric(seed: Long, row: Int, axis: Int): Double =
    var value = seed + 0x9e3779b97f4a7c15L * (row.toLong * 2L + axis.toLong + 1L)
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value = value ^ (value >>> 31)
    val bits = value >>> 11
    bits.toDouble / 9007199254740992.0 * 2.0 - 1.0

/** Phase 6 — geom lowering: turn adjusted rows into grobs. Lowering is group-aware: layers honoring
  * the group aesthetic lower to one grob per group carrying that group's graphic params.
  */
private[intaglio] object GeomPhase:
  def lower[Row](
      layer: Layer[Row],
      rows: Vector[ResolvedRow[Row]],
      annotation: Option[ResolvedReferenceLine],
      theme: Theme
  ): Either[GraphicsError, Vector[Grob]] =
    annotation match
      case Some(reference) =>
        referenceLineGrob(reference, layer.params.getOrElse(theme.geom))
      case None if layer.annotation.nonEmpty =>
        // The layer is a valid annotation declaration that this facet panel explicitly excludes.
        // Keep that distinct from a row-backed HLine/VLine, which remains an invalid contract.
        Right(Vector.empty)
      case None =>
        validateGroupConstancy(layer.geom, rows).flatMap { _ =>
          layer.stat match
            case _: Stat.Summary[?] => summaryGrobs(rows)
            case _: Stat.Density[?] => densityGrobs(rows)
            case _                  => lowerIdentity(layer.geom, rows)
        }

  private def validateGroupConstancy[Row](
      geom: Geom,
      rows: Vector[ResolvedRow[Row]]
  ): Either[GraphicsError, Unit] =
    val groups = groupInOrder(rows)
    val aesthetics = geom.contract.groupConstant
    var groupIndex = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while groupIndex < groups.length && result.isRight do
      val group = groups(groupIndex)
      if group.nonEmpty then
        var aestheticIndex = 0
        while aestheticIndex < aesthetics.length && result.isRight do
          val aesthetic = aesthetics(aestheticIndex)
          val first = group.head
          val expected = groupAestheticValue(first, aesthetic)
          group.tail.find(row => groupAestheticValue(row, aesthetic) != expected).foreach { row =>
            result = Left(
              GraphicsError.VaryingGroupAesthetic(
                geom.label,
                aesthetic.label,
                first.groupKey.map(_.display).getOrElse("<ungrouped>"),
                first.rowIndex,
                row.rowIndex
              )
            )
          }
          aestheticIndex += 1
      groupIndex += 1
    result

  private def groupAestheticValue(
      row: ResolvedRow[?],
      aesthetic: Aesthetic[?]
  ): GroupAestheticValue =
    aesthetic match
      case Aesthetic.Color => GroupAestheticValue.Color(row.gp.stroke)
      case Aesthetic.Fill  => GroupAestheticValue.Fill(row.gp.fill, row.gp.fillPattern)
      case Aesthetic.Alpha => GroupAestheticValue.Alpha(row.gp.alpha)
      case Aesthetic.Size  => GroupAestheticValue.Size(row.size)
      case other           => GroupAestheticValue.Unsupported(other.label)

  private enum GroupAestheticValue:
    case Color(value: Option[Rgba])
    case Fill(value: Option[Rgba], pattern: Option[PatternPaint])
    case Alpha(value: Double)
    case Size(value: ExtentExpr)
    case Unsupported(aesthetic: String)

  private def referenceLineGrob(
      annotation: ResolvedReferenceLine,
      params: GraphicParams
  ): Either[GraphicsError, Vector[Grob]] =
    val coordinate = LengthExpr.nativeUnsafe(annotation.coordinate)
    val (segment, name) = annotation.reference.orientation match
      case ReferenceLineOrientation.Horizontal =>
        (
          Point(LengthExpr.npcUnsafe(0.0), coordinate) ->
            Point(LengthExpr.npcUnsafe(1.0), coordinate),
          "geom-hline"
        )
      case ReferenceLineOrientation.Vertical =>
        (
          Point(coordinate, LengthExpr.npcUnsafe(0.0)) ->
            Point(coordinate, LengthExpr.npcUnsafe(1.0)),
          "geom-vline"
        )
    Grob
      .segments(
        Vector(segment),
        gp = params,
        name = Some(GraphicsName.unsafe(name))
      )
      .map(Vector(_))

  private def lowerIdentity[Row](
      geom: Geom,
      rows: Vector[ResolvedRow[Row]]
  ): Either[GraphicsError, Vector[Grob]] =
    geom match
      case Geom.Point =>
        pointGrobs(rows)
      case Geom.Line =>
        lineGrobs(rows)
      case Geom.Text =>
        textGrobs(rows)
      case Geom.Bar =>
        barGrobs(rows)
      case Geom.Rect =>
        boundedRectGrobs(rows, "rect")
      case Geom.Segment =>
        segmentGrobs(rows)
      case Geom.ErrorBar =>
        errorBarGrobs(rows)
      case Geom.Ribbon =>
        ribbonGrobs(rows, "ribbon")
      case Geom.Area =>
        ribbonGrobs(rows, "area")
      case Geom.HLine | Geom.VLine =>
        Left(GraphicsError.ReferenceLineRequiresAnnotation(geom.label))
      case Geom.Tile =>
        boundedRectGrobs(rows, "tile")
      case Geom.Polygon =>
        polygonGrobs(rows)

  private def summaryGrobs[Row](
      rows: Vector[ResolvedRow[Row]]
  ): Either[GraphicsError, Vector[Grob]] =
    val out = Vector.newBuilder[Grob]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < rows.length && result.isRight do
      val row = rows(idx)
      val lower = row.computed.get(ComputedAesthetic.Lower).getOrElse(row.y)
      val upper = row.computed.get(ComputedAesthetic.Upper).getOrElse(row.y)
      result = Grob
        .segments(
          Vector((Point.nativeUnsafe(row.x, lower), Point.nativeUnsafe(row.x, upper))),
          gp = row.gp,
          name = Some(GraphicsName.unsafe(s"stat-summary-interval-$idx"))
        )
        .flatMap { interval =>
          Grob
            .points(
              Vector(row.point),
              size = row.size,
              gp = row.gp,
              name = Some(GraphicsName.unsafe(s"stat-summary-mean-$idx"))
            )
            .map { point =>
              out += interval
              out += point
              ()
            }
        }
      idx += 1
    result.map(_ => out.result())

  private def densityGrobs[Row](
      rows: Vector[ResolvedRow[Row]]
  ): Either[GraphicsError, Vector[Grob]] =
    if rows.length < 2 then Right(Vector.empty)
    else
      Grob
        .lines(
          rows.map(_.point),
          gp = rows.head.gp,
          name = Some(GraphicsName.unsafe("stat-density-line"))
        )
        .map(Vector(_))

  private def boundedRectGrobs[Row](
      rows: Vector[ResolvedRow[Row]],
      prefix: String
  ): Either[GraphicsError, Vector[Grob]] =
    val out = Vector.newBuilder[Grob]
    var idx = 0
    while idx < rows.length do
      val row = rows(idx)
      val xMin = row.xMin.getOrElse(row.x)
      val xMax = row.xMax.getOrElse(row.x)
      val yMin = row.yMin.getOrElse(row.y)
      val yMax = row.yMax.getOrElse(row.y)
      out += Grob.rectUnsafe(
        center = Point.nativeUnsafe(xMin + (xMax - xMin) / 2.0, yMin + (yMax - yMin) / 2.0),
        size = Size
          .fromExtents(ExtentExpr.nativeUnsafe(xMax - xMin), ExtentExpr.nativeUnsafe(yMax - yMin)),
        gp = row.gp,
        name = Some(GraphicsName.unsafe(s"geom-$prefix-$idx"))
      )
      idx += 1
    Right(out.result())

  private def segmentGrobs[Row](
      rows: Vector[ResolvedRow[Row]]
  ): Either[GraphicsError, Vector[Grob]] =
    val out = Vector.newBuilder[Grob]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < rows.length && result.isRight do
      val row = rows(idx)
      val end = Point.nativeUnsafe(row.xEnd.getOrElse(row.x), row.yEnd.getOrElse(row.y))
      result = Grob
        .segments(
          Vector(row.point -> end),
          gp = row.gp,
          name = Some(GraphicsName.unsafe(s"geom-segment-$idx"))
        )
        .map { grob =>
          out += grob
          ()
        }
      idx += 1
    result.map(_ => out.result())

  private def errorBarGrobs[Row](
      rows: Vector[ResolvedRow[Row]]
  ): Either[GraphicsError, Vector[Grob]] =
    val out = Vector.newBuilder[Grob]
    val halfCap = ExtentExpr.pointsUnsafe(3.0)
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < rows.length && result.isRight do
      val row = rows(idx)
      val lower = row.yMin.getOrElse(row.y)
      val upper = row.yMax.getOrElse(row.y)
      val x = LengthExpr.nativeUnsafe(row.x)
      val lowerY = LengthExpr.nativeUnsafe(lower)
      val upperY = LengthExpr.nativeUnsafe(upper)
      val segments = Vector(
        Point(x, lowerY) -> Point(x, upperY),
        Point(x - halfCap, lowerY) -> Point(x + halfCap, lowerY),
        Point(x - halfCap, upperY) -> Point(x + halfCap, upperY)
      )
      result = Grob
        .segments(segments, gp = row.gp, name = Some(GraphicsName.unsafe(s"geom-errorbar-$idx")))
        .map { grob =>
          out += grob
          ()
        }
      idx += 1
    result.map(_ => out.result())

  private def ribbonGrobs[Row](
      rows: Vector[ResolvedRow[Row]],
      prefix: String
  ): Either[GraphicsError, Vector[Grob]] =
    val groups = groupInOrder(rows)
    val out = Vector.newBuilder[Grob]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < groups.length && result.isRight do
      val group = groups(idx)
      if group.length >= 2 then
        val upper = group.map(row => Point.nativeUnsafe(row.x, row.yMax.getOrElse(row.y)))
        val lower = group.reverse.map(row => Point.nativeUnsafe(row.x, row.yMin.getOrElse(row.y)))
        result = Grob
          .polygon(
            upper ++ lower,
            gp = group.head.gp,
            name = Some(GraphicsName.unsafe(s"geom-$prefix-$idx"))
          )
          .map { grob =>
            out += grob
            ()
          }
      idx += 1
    result.map(_ => out.result())

  private def pointGrobs[Row](rows: Vector[ResolvedRow[Row]]): Either[GraphicsError, Vector[Grob]] =
    val out = Vector.newBuilder[Grob]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < rows.length && result.isRight do
      val row = rows(idx)
      result = Grob.points(Vector(row.point), size = row.size, gp = row.gp).map { grob =>
        out += grob
        ()
      }
      idx += 1
    result.map(_ => out.result())

  private def lineGrobs[Row](rows: Vector[ResolvedRow[Row]]): Either[GraphicsError, Vector[Grob]] =
    val groups = groupInOrder(rows)
    val out = Vector.newBuilder[Grob]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < groups.length && result.isRight do
      val group = groups(idx)
      if group.length >= 2 then
        result = Grob.lines(group.map(_.point), gp = group.head.gp).map { grob =>
          out += grob
          ()
        }
      idx += 1
    result.map(_ => out.result())

  private def polygonGrobs[Row](
      rows: Vector[ResolvedRow[Row]]
  ): Either[GraphicsError, Vector[Grob]] =
    val groups = groupInOrder(rows)
    val out = Vector.newBuilder[Grob]
    var index = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while index < groups.length && result.isRight do
      val group = groups(index)
      if group.length >= 3 then
        val grob =
          if group.exists(_.subpath.nonEmpty) then
            Grob.compoundPolygon(
              subpathsInOrder(group).map(_.map(_.point)),
              gp = group.head.gp,
              name = Some(GraphicsName.unsafe(s"geom-polygon-$index"))
            )
          else
            Grob.polygon(
              group.map(_.point),
              gp = group.head.gp,
              name = Some(GraphicsName.unsafe(s"geom-polygon-$index"))
            )
        result = grob
          .map { grob =>
            out += grob
            ()
          }
      index += 1
    result.map(_ => out.result())

  private def subpathsInOrder[Row](
      rows: Vector[ResolvedRow[Row]]
  ): Vector[Vector[ResolvedRow[Row]]] =
    val order = scala.collection.mutable.ArrayBuffer.empty[Option[String]]
    val buckets = scala.collection.mutable.HashMap
      .empty[Option[String], scala.collection.mutable.ArrayBuffer[ResolvedRow[Row]]]
    rows.foreach { row =>
      val key = row.subpath
      val bucket = buckets.getOrElseUpdate(
        key, {
          order += key
          scala.collection.mutable.ArrayBuffer.empty[ResolvedRow[Row]]
        }
      )
      bucket += row
    }
    order.toVector.map(key => buckets(key).toVector)

  private def textGrobs[Row](rows: Vector[ResolvedRow[Row]]): Either[GraphicsError, Vector[Grob]] =
    val out = Vector.newBuilder[Grob]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < rows.length && result.isRight do
      val row = rows(idx)
      result = Grob.text(row.label.getOrElse(""), row.point, gp = row.gp).map { grob =>
        out += grob
        ()
      }
      idx += 1
    result.map(_ => out.result())

  private def barGrobs[Row](rows: Vector[ResolvedRow[Row]]): Either[GraphicsError, Vector[Grob]] =
    val out = Vector.newBuilder[Grob]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < rows.length && result.isRight do
      val row = rows(idx)
      val lower = row.yMin.getOrElse(math.min(0.0, row.y))
      val upper = row.yMax.getOrElse(math.max(0.0, row.y))
      val height = upper - lower
      val centerY = lower + height / 2.0
      val width =
        row.xBand.map(_.width).orElse(row.computed.get(ComputedAesthetic.BinWidth)).getOrElse(0.9)
      val statName =
        if row.computed.get(ComputedAesthetic.BinWidth).nonEmpty then "bin" else "count"
      result = Grob
        .rect(
          center = Point.nativeUnsafe(row.x, centerY),
          size = Size.fromExtents(ExtentExpr.nativeUnsafe(width), ExtentExpr.nativeUnsafe(height)),
          gp = row.gp,
          name = Some(GraphicsName.unsafe(s"stat-$statName-bar-$idx"))
        )
        .map { grob =>
          out += grob
          ()
        }
      idx += 1
    result.map(_ => out.result())

  /** Partition rows by their group value, preserving first-encounter order of groups and row order
    * within each group.
    */
  private def groupInOrder[Row](rows: Vector[ResolvedRow[Row]]): Vector[Vector[ResolvedRow[Row]]] =
    if rows.forall(_.groupKey.isEmpty) then if rows.isEmpty then Vector.empty else Vector(rows)
    else
      val order = Vector.newBuilder[Option[GroupKey]]
      val buckets = scala.collection.mutable.HashMap
        .empty[Option[GroupKey], scala.collection.mutable.ArrayBuffer[ResolvedRow[Row]]]
      rows.foreach { row =>
        val bucket = buckets.getOrElseUpdate(
          row.groupKey, {
            order += row.groupKey
            scala.collection.mutable.ArrayBuffer.empty[ResolvedRow[Row]]
          }
        )
        bucket += row
      }
      order.result().map(key => buckets(key).toVector)

/** Phase 7 — coordinate transformation is deliberately one compiler phase. Statistical output and
  * geoms remain expressed in logical x/y space; this phase turns their rows, grobs, and panel
  * ranges into physical panel coordinates before layout and guide lowering. Backends therefore know
  * nothing about plot coordinates.
  */
private[intaglio] object CoordPhase:
  final case class CoordinateResolution(
      layers: Vector[TrainedLayer],
      ranges: Option[(Interval, Interval)]
  )

  def transform(
      coord: Coord,
      layers: Vector[TrainedLayer],
      ranges: Option[(Interval, Interval)]
  ): Either[GraphicsError, CoordinateResolution] =
    coord match
      case Coord.Flipped(_) =>
        Right(
          CoordinateResolution(
            layers.map(flipLayer),
            ranges.map { case (xRange, yRange) => (yRange, xRange) }
          )
        )
      case Coord.Cartesian(_) | Coord.Fixed(_, _) =>
        Right(CoordinateResolution(layers, ranges))

  private def flipLayer(layer: TrainedLayer): TrainedLayer =
    flipTypedLayer(layer.value)

  private def flipTypedLayer[Row](layer: ResolvedLayer[Row]): TrainedLayer =
    TrainedLayer(
      layer.copy(
        rows = layer.rows.map(flipRow),
        annotation = layer.annotation.map(_.flipped),
        grobs = layer.grobs.map(flipGrob)
      )
    )

  private def flipRow[Row](row: ResolvedRow[Row]): ResolvedRow[Row] =
    row.copy(
      x = row.y,
      y = row.x,
      xBand = row.yBand,
      yBand = row.xBand,
      xEnd = row.yEnd,
      yEnd = row.xEnd,
      xMin = row.yMin,
      xMax = row.yMax,
      yMin = row.xMin,
      yMax = row.xMax,
      point = flipPoint(row.point)
    )

  private def flipPoint(point: Point): Point =
    Point(point.y, point.x)

  private def flipSize(size: Size): Size =
    Size.fromExtents(size.height, size.width)

  private def flipGrob(grob: Grob): Grob =
    grob match
      case points: Grob.Points =>
        points.copy(points = points.points.map(flipPoint))
      case lines: Grob.Lines =>
        lines.copy(points = lines.points.map(flipPoint))
      case polygon: Grob.Polygon =>
        polygon.copy(points = polygon.points.map(flipPoint))
      case polygon: Grob.CompoundPolygon =>
        polygon.copy(rings = polygon.rings.map(_.map(flipPoint)))
      case segments: Grob.Segments =>
        segments.copy(segments = segments.segments.map { case (start, end) =>
          (flipPoint(start), flipPoint(end))
        })
      case rect: Grob.Rect =>
        rect.copy(center = flipPoint(rect.center), size = flipSize(rect.size))
      case circle: Grob.Circle =>
        circle.copy(center = flipPoint(circle.center))
      case text: Grob.Text =>
        text.copy(at = flipPoint(text.at))
      case image: Grob.Image =>
        image.copy(at = flipPoint(image.at), size = flipSize(image.size))
      case group: Grob.Group =>
        group.copy(children = group.children.map(flipGrob))

/** Phase 8 — layout resolution: use the explicit panel layout when given, or derive one from an
  * explicit frame plus panel data ranges computed from the layers' position scales (mapped space is
  * the unit interval) or their resolved row values when a position is unscaled.
  */
private[intaglio] object LayoutPhase:
  final case class LayoutResolution(layout: Option[PanelLayout], frames: Option[PlotFrames])

  /** Panel data ranges when any layout source (explicit layout, frame, or solver policy) is in
    * play; `None` when the plot compiles layout-free.
    */
  def panelRangesFor(
      options: PlotCompilerOptions,
      layers: Vector[TrainedLayer]
  ): Either[GraphicsError, Option[(Interval, Interval)]] =
    options.layout match
      case Some(layout) =>
        Right(Some((layout.xScale, layout.yScale)))
      case None if options.frame.nonEmpty || options.policy.nonEmpty =>
        panelRanges(layers).map(Some(_))
      case None =>
        Right(None)

  def assemble(
      coord: Coord,
      options: PlotCompilerOptions,
      ranges: Option[(Interval, Interval)],
      specs: Vector[GuideSpec],
      labels: PlotLabels
  ): Either[GraphicsError, LayoutResolution] =
    val clip = coordClip(coord)
    (options.layout, options.frame, options.policy, ranges) match
      case (Some(layout), _, _, Some((xRange, yRange))) =>
        Right(
          LayoutResolution(Some(layout.copy(xScale = xRange, yScale = yRange, clip = clip)), None)
        )
      case (None, Some(frame), _, Some((xRange, yRange))) =>
        expandedRanges(options.expansion, xRange, yRange).map { case (expandedX, expandedY) =>
          LayoutResolution(
            Some(PanelLayout(frame, expandedX, expandedY, options.margins, clip)),
            None
          )
        }
      case (None, None, Some(policy), Some((xRange, yRange))) =>
        for
          expanded <- expandedRanges(options.expansion, xRange, yRange)
          aspect <- panelAspect(coord, expanded._1, expanded._2)
          frames <- PlotLayoutSolver.solve(
            policy,
            layoutRequest(specs, expanded._1, expanded._2, labels, aspect)
          )
        yield
          val (expandedX, expandedY) = expanded
          LayoutResolution(
            Some(PanelLayout(frames.panel, expandedX, expandedY, options.margins, clip)),
            Some(frames)
          )
      case _ =>
        if options.guides.requiresLayout then Left(GraphicsError.MissingLayout("guides"))
        else Right(LayoutResolution(None, None))

  private[intaglio] def expandedRanges(
      expansion: RangeExpansion,
      xRange: Interval,
      yRange: Interval
  ): Either[GraphicsError, (Interval, Interval)] =
    for
      x <- expansion.expand(xRange)
      y <- expansion.expand(yRange)
    yield (x, y)

  private[intaglio] def layoutRequest(
      specs: Vector[GuideSpec],
      xRange: Interval,
      yRange: Interval,
      labels: PlotLabels,
      panelAspect: Option[CoordinateRatio],
      grid: Option[PanelGridRequest] = None
  ): PlotLayoutRequest =
    val axes = specs.collect { case axis: GuideSpec.Axis =>
      val range = if axis.side.isHorizontal then xRange else yRange
      axis.side -> AxisRequest(axisLabels(axis, range), axis.title)
    }.toMap
    val nonPositionGuides = specs.collect {
      case legend: GuideSpec.Legend =>
        GuideLayoutRequest.Legend(legend.title, legend.entries.map(_.label))
      case colorbar: GuideSpec.Colorbar =>
        GuideLayoutRequest.Colorbar(colorbar.title, colorbar.ticks.map(_.label))
    }
    val legend =
      if nonPositionGuides.isEmpty then None
      else Some(LegendRequest(nonPositionGuides))
    PlotLayoutRequest(axes, legend, labels, panelAspect, grid)

  private[intaglio] def panelAspect(
      coord: Coord,
      xRange: Interval,
      yRange: Interval
  ): Either[GraphicsError, Option[CoordinateRatio]] =
    coord match
      case Coord.Fixed(ratio, _) =>
        if xRange.width <= 0.0 || yRange.width <= 0.0 then
          Left(GraphicsError.DegenerateFixedAspect(xRange.width, yRange.width))
        else CoordinateRatio(yRange.width / xRange.width * ratio.toDouble).map(Some(_))
      case Coord.Cartesian(_) | Coord.Flipped(_) =>
        Right(None)

  private def axisLabels(axis: GuideSpec.Axis, range: Interval): Vector[String] =
    axis.ticks match
      case Some(ticks) =>
        ticks.map(_.label)
      case None =>
        Axis.ticks(range, axis.breaks, axis.labeler).map(_.map(_.label)).getOrElse(Vector.empty)

  def panelRanges(
      layers: Vector[TrainedLayer]
  ): Either[GraphicsError, (Interval, Interval)] =
    for
      xRange <- positionRange(layers, Aesthetic.X)
      yRange <- positionRange(layers, Aesthetic.Y)
    yield (xRange, yRange)

  /** Union of the position ranges contributed by each layer. Scaled layers live in mapped unit
    * space (trained with the unit interval plus their actual mapped rows, so an `OobPolicy.Keep`
    * overflow widens the panel rather than silently clipping); unscaled layers contribute raw row
    * values. Mixing the two across layers is incoherent — mapped and raw coordinates share no unit
    * — and is a typed error.
    */
  private def positionRange(
      layers: Vector[TrainedLayer],
      aesthetic: Aesthetic[Double]
  ): Either[GraphicsError, Interval] =
    var sawScaled = false
    var sawUnscaledData = false
    var range = ContinuousRange.empty
    layers.foreach { layer =>
      val contributes =
        !(layer.geom == Geom.HLine && aesthetic == Aesthetic.X)
          && !(layer.geom == Geom.VLine && aesthetic == Aesthetic.Y)
      val values =
        if contributes then
          layer.rows.iterator.flatMap(row => positionValues(row, aesthetic)).toVector
        else Vector.empty
      val annotationValues = layer.annotation.toVector.collect {
        case annotation
            if annotation.reference.scalePolicy == AnnotationScalePolicy.Train &&
              (annotation.reference.aesthetic eq aesthetic) =>
          annotation.coordinate
      }
      val positionData = values ++ annotationValues
      layer.trainedScales.find(_.key eq aesthetic) match
        case Some(scale) =>
          sawScaled = true
          if scale.descriptor.kind == ScaleKind.Continuous then
            range = range.train(Vector(0.0, 1.0))
          range = range.train(positionData)
        case None =>
          if positionData.nonEmpty then sawUnscaledData = true
          range = range.train(positionData)
      if layer.geom == Geom.Bar then
        if aesthetic == Aesthetic.X then
          val edges = layer.rows.iterator.flatMap { row =>
            val halfWidth =
              row.xBand
                .map(_.width)
                .orElse(row.computed.get(ComputedAesthetic.BinWidth))
                .getOrElse(0.9) / 2.0
            Iterator(row.x - halfWidth, row.x + halfWidth)
          }
          range = range.train(edges)
        else if aesthetic == Aesthetic.Y then range = range.train(Iterator.single(0.0))
      if aesthetic == Aesthetic.Y then
        val intervalValues = layer.rows.iterator.flatMap { row =>
          Iterator(
            row.computed.get(ComputedAesthetic.Lower),
            row.computed.get(ComputedAesthetic.Upper)
          ).flatten
        }
        range = range.train(intervalValues)
    }
    if sawScaled && sawUnscaledData then Left(GraphicsError.MixedPositionScaling(aesthetic.label))
    else
      range.requireTrained match
        case Left(GraphicsError.EmptyContinuousRange) if layers.exists(_.annotation.nonEmpty) =>
          Right(Interval.unsafe(0.0, 1.0))
        case result =>
          result

  private def positionValues(
      row: ResolvedRow[?],
      aesthetic: Aesthetic[Double]
  ): Vector[Double] =
    if aesthetic == Aesthetic.X then
      Vector(Some(row.x), row.xEnd, row.xMin, row.xMax).flatten ++
        row.xBand.toVector.flatMap(band => Vector(band.lower, band.upper))
    else
      Vector(Some(row.y), row.yEnd, row.yMin, row.yMax).flatten ++
        row.yBand.toVector.flatMap(band => Vector(band.lower, band.upper))

  private[intaglio] def coordClip(coord: Coord): Clip =
    coord.clipping

/** Structural plot text lowers into solver-owned regions before any backend sees the scene. Axis
  * titles remain guide children; title and subtitle are top-level text grobs in dedicated
  * viewports.
  */
private[intaglio] object PlotLabelPhase:
  def lower(
      labels: PlotLabels,
      frames: Option[PlotFrames],
      theme: PlotTextTheme
  ): Either[GraphicsError, Vector[Grob]] =
    val needsHeader = labels.title.nonEmpty || labels.subtitle.nonEmpty
    if !needsHeader then Right(Vector.empty)
    else
      frames match
        case None         => Left(GraphicsError.MissingLayout("plot title"))
        case Some(solved) =>
          val out = Vector.newBuilder[Grob]
          for
            _ <- addLabel(
              labels.title,
              solved.titleViewport,
              theme.title,
              PlotRegion.Title,
              out
            )
            _ <- addLabel(
              labels.subtitle,
              solved.subtitleViewport,
              theme.subtitle,
              PlotRegion.Subtitle,
              out
            )
          yield out.result()

  private def addLabel(
      text: Option[String],
      viewport: Option[Viewport],
      gp: GraphicParams,
      name: GraphicsName,
      out: scala.collection.mutable.Builder[Grob, Vector[Grob]]
  ): Either[GraphicsError, Unit] =
    text match
      case None        => Right(())
      case Some(label) =>
        viewport match
          case None        => Left(GraphicsError.MissingLayout(name.value))
          case Some(frame) =>
            Grob
              .text(
                label,
                Point.npcUnsafe(0.0, 0.5),
                anchor = Anchor(HJust.Left, VJust.Center),
                gp = gp,
                viewport = Some(frame),
                name = Some(name)
              )
              .map { grob =>
                out += grob
                ()
              }

/** Phase 7 — guide resolution: determine guide specs from the policy (deriving routine axes and
  * legends from trained scales) and lower them against the panel layout.
  */
private[intaglio] object GuidePhase:
  def specs(
      policy: GuidePolicy,
      coord: Coord,
      plotScales: PlotScaleRegistry,
      ranges: Option[(Interval, Interval)],
      relativeLegend: Boolean,
      labels: PlotLabels
  ): Either[GraphicsError, Vector[GuideSpec]] =
    policy match
      case GuidePolicy.NoGuides =>
        Right(Vector.empty)
      case GuidePolicy.Explicit(explicit) =>
        if explicit.isEmpty then Right(Vector.empty)
        else
          ranges match
            case Some((xRange, yRange)) => materializeAxisTicks(explicit, xRange, yRange)
            case None                   => Left(GraphicsError.MissingLayout("guides"))
      case GuidePolicy.Derived(overrides, deriveLegends) =>
        ranges match
          case None =>
            Left(GraphicsError.MissingLayout("guides"))
          case Some((xRange, yRange)) =>
            derived(
              coord,
              plotScales,
              xRange,
              yRange,
              overrides,
              deriveLegends,
              relativeLegend,
              labels
            )

  private def derived(
      coord: Coord,
      plotScales: PlotScaleRegistry,
      xRange: Interval,
      yRange: Interval,
      overrides: Vector[GuideSpec],
      deriveLegends: Boolean,
      relativeLegend: Boolean,
      labels: PlotLabels
  ): Either[GraphicsError, Vector[GuideSpec]] =
    val overriddenSides = overrides.collect { case axis: GuideSpec.Axis => axis.side }.toSet
    val hasLegendOverride = overrides.exists {
      case _: GuideSpec.Legend   => true
      case _: GuideSpec.Colorbar => true
      case _                     => false
    }
    val (xSide, xPhysicalRange, ySide, yPhysicalRange) =
      coord match
        case Coord.Flipped(_) => (AxisSide.Left, xRange, AxisSide.Bottom, yRange)
        case Coord.Cartesian(_) | Coord.Fixed(_, _) =>
          (AxisSide.Bottom, xRange, AxisSide.Left, yRange)
    for
      resolvedOverrides <- materializeAxisTicks(overrides, xRange, yRange)
      xAxis <-
        if overriddenSides.contains(xSide) then Right(None)
        else positionAxis(plotScales, Aesthetic.X, xSide, xPhysicalRange, labels.x)
      yAxis <-
        if overriddenSides.contains(ySide) then Right(None)
        else positionAxis(plotScales, Aesthetic.Y, ySide, yPhysicalRange, labels.y)
      legends <-
        if hasLegendOverride || !deriveLegends then Right(Vector.empty)
        else nonPositionGuides(plotScales)
    yield Vector(xAxis, yAxis).flatten ++ resolvedOverrides ++ legends

  /** Resolve caller-supplied break policies against the unexpanded data ranges. Panel padding is a
    * view concern and must not leak into tick values or labels when the guides are lowered later
    * against the expanded layout.
    */
  private def materializeAxisTicks(
      specs: Vector[GuideSpec],
      xRange: Interval,
      yRange: Interval
  ): Either[GraphicsError, Vector[GuideSpec]] =
    val out = Vector.newBuilder[GuideSpec]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < specs.length && result.isRight do
      specs(idx) match
        case axis: GuideSpec.Axis if axis.ticks.isEmpty =>
          val range = if axis.side.isHorizontal then xRange else yRange
          result = Axis.ticks(range, axis.breaks, axis.labeler).map { ticks =>
            out += axis.copy(ticks = Some(ticks))
            ()
          }
        case spec =>
          out += spec
      idx += 1
    result.map(_ => out.result())

  /** Derive an axis for a position aesthetic. A trained continuous scale provides breaks and labels
    * in the raw data domain, positioned in mapped unit space; an unscaled position takes default
    * breaks over the panel range. Both carry explicit ticks so the layout solver can size strips
    * from the actual labels.
    */
  private def positionAxis(
      plotScales: PlotScaleRegistry,
      aesthetic: Aesthetic[?],
      side: AxisSide,
      range: Interval,
      requestedTitle: Option[String]
  ): Either[GraphicsError, Option[GuideSpec.Axis]] =
    val name = GraphicsName.unsafe(s"${aesthetic.label}-axis")
    plotScales.forAesthetic(aesthetic) match
      case Some(trained) =>
        trained.scale match
          case continuous: ContinuousScale[?] =>
            scaledTicks(continuous).map { ticks =>
              Some(
                GuideSpec.Axis(
                  side,
                  ticks = Some(ticks),
                  title = requestedTitle.orElse(Some(continuous.name.value)),
                  name = Some(name)
                )
              )
            }
          case band: BandScale =>
            Right(
              Some(
                GuideSpec.Axis(
                  side,
                  ticks = Some(band.bands.map { case (level, position) =>
                    AxisTick.unsafe(position.center, level)
                  }),
                  title = requestedTitle.orElse(Some(band.name.value)),
                  name = Some(name)
                )
              )
            )
          case discrete: DiscreteScale[?] =>
            discretePositionTicks(discrete) match
              case Some(ticks) =>
                Right(
                  Some(
                    GuideSpec.Axis(
                      side,
                      ticks = Some(ticks),
                      title = requestedTitle.orElse(Some(discrete.name.value)),
                      name = Some(name)
                    )
                  )
                )
              case None =>
                defaultTicks(side, range, name, requestedTitle.orElse(Some(aesthetic.label)))
          case _ =>
            defaultTicks(side, range, name, requestedTitle.orElse(Some(aesthetic.label)))
      case None =>
        defaultTicks(side, range, name, requestedTitle.orElse(Some(aesthetic.label)))

  private def defaultTicks(
      side: AxisSide,
      range: Interval,
      name: GraphicsName,
      title: Option[String]
  ): Either[GraphicsError, Option[GuideSpec.Axis]] =
    Axis.ticks(range, Breaks.default, Labeler.default).map { ticks =>
      Some(GuideSpec.Axis(side, ticks = Some(ticks), title = title, name = Some(name)))
    }

  private def discretePositionTicks(scale: DiscreteScale[?]): Option[Vector[AxisTick]] =
    val out = Vector.newBuilder[AxisTick]
    var idx = 0
    var valid = true
    while idx < scale.domain.levels.length && valid do
      val level = scale.domain.levels(idx)
      scale.mapValue(level) match
        case Some(position: Double) =>
          AxisTick(position, level) match
            case Right(tick) => out += tick
            case Left(_)     => valid = false
        case _ =>
          valid = false
      idx += 1
    if valid then Some(out.result()) else None

  /** Ticks for a trained continuous scale: break values come from the scale's transform in the raw
    * data domain; positions are the mapped unit-space coordinates the rows were resolved into.
    */
  private def scaledTicks(scale: ContinuousScale[?]): Either[GraphicsError, Vector[AxisTick]] =
    scale.breaksResult.flatMap { breaks =>
      val labels = scale.transform.labeler(breaks)
      if labels.length != breaks.length then
        Left(GraphicsError.AxisLabelCountMismatch(breaks.length, labels.length))
      else
        val out = Vector.newBuilder[AxisTick]
        var idx = 0
        var result: Either[GraphicsError, Unit] = Right(())
        while idx < breaks.length && result.isRight do
          result = scale.transform.transform(breaks(idx)).flatMap { transformed =>
            AxisTick(scale.transformedDomain.rescale(transformed), labels(idx)).map { tick =>
              out += tick
              ()
            }
          }
          idx += 1
        result.map(_ => out.result())
    }

  /** One guide per distinct color/fill scale: discrete scales become keyed legends and continuous
    * scales become sampled colorbars. The layout solver measures and places the resulting stack
    * later.
    */
  private def nonPositionGuides(
      plotScales: PlotScaleRegistry
  ): Either[GraphicsError, Vector[GuideSpec]] =
    val seen = scala.collection.mutable.HashSet.empty[String]
    val out = Vector.newBuilder[GuideSpec]
    var result: Either[GraphicsError, Unit] = Right(())
    plotScales.scales.foreach { trained =>
      if result.isRight
        && (trained.key == Aesthetic.Color || trained.key == Aesthetic.Fill)
        && seen.add(trained.descriptor.name.value)
      then
        trained.scale match
          case discrete: DiscreteScale[?] =>
            result = legendFor(discrete).map { legend =>
              legend.foreach { spec =>
                out += spec
              }
              ()
            }
          case continuous: ContinuousScale[?] =>
            result = colorbarFor(continuous).map { colorbar =>
              colorbar.foreach { spec =>
                out += spec
              }
              ()
            }
          case _ =>
            ()
    }
    result.map(_ => out.result())

  private def legendFor(
      scale: DiscreteScale[?]
  ): Either[GraphicsError, Option[GuideSpec.Legend]] =
    val entries = Vector.newBuilder[LegendEntry]
    var colorable = true
    var result: Either[GraphicsError, Unit] = Right(())
    scale.domain.levels.foreach { level =>
      if result.isRight && colorable then
        scale.mapValue(level) match
          case Some(color: Rgba) =>
            result = LegendEntry.color(level, color).map { entry =>
              entries += entry
              ()
            }
          case _ =>
            colorable = false
    }
    result.map { _ =>
      val resolved = entries.result()
      if !colorable || resolved.isEmpty then None
      else
        Some(
          GuideSpec.Legend(
            title = Some(scale.name.value),
            entries = resolved,
            name = Some(GraphicsName.unsafe(s"${scale.name.value}-legend"))
          )
        )
    }

  private def colorbarFor(
      scale: ContinuousScale[?]
  ): Either[GraphicsError, Option[GuideSpec.Colorbar]] =
    scale.paletteSamples(32).flatMap { samples =>
      val colors = samples.collect { case color: Rgba => color }
      if colors.length != samples.length then Right(None)
      else
        scaledTicks(scale).map { ticks =>
          Some(
            GuideSpec.Colorbar(
              title = Some(scale.name.value),
              colors = colors,
              ticks = ticks,
              name = Some(GraphicsName.unsafe(s"${scale.name.value}-colorbar"))
            )
          )
        }
    }

  def lower(
      layout: Option[PanelLayout],
      frames: Option[PlotFrames],
      specs: Vector[GuideSpec],
      policy: LayoutPolicy = LayoutPolicy(),
      theme: Theme = Theme.default
  ): Either[GraphicsError, Vector[ResolvedGuide]] =
    if specs.isEmpty then Right(Vector.empty)
    else
      layout match
        case None =>
          Left(GraphicsError.MissingLayout("guides"))
        case Some(panel) =>
          val legendViewport = frames.flatMap(_.legendViewport())
          // Each request stays tied to the index of the spec it came from, so a
          // spec this phase does not measure — an axis, or a guide kind added
          // later — cannot shift the placements belonging to the others.
          val requests = specs.zipWithIndex.collect {
            case (legend: GuideSpec.Legend, at) =>
              at -> GuideLayoutRequest.Legend(legend.title, legend.entries.map(_.label))
            case (colorbar: GuideSpec.Colorbar, at) =>
              at -> GuideLayoutRequest.Colorbar(colorbar.title, colorbar.ticks.map(_.label))
          }
          val placements =
            if legendViewport.isEmpty then Map.empty[Int, GuidePlacement]
            else
              val plan = GuideStackSolver.plan(policy, LegendRequest(requests.map(_._2)))
              requests.map(_._1).zip(plan.placements).toMap
          val out = Vector.newBuilder[ResolvedGuide]
          var idx = 0
          var result: Either[GraphicsError, Unit] = Right(())
          while idx < specs.length && result.isRight do
            val spec = specs(idx)
            val placed = placements.get(idx).fold(spec)(placeGuide(spec, _))
            result = GuideSpec.lower(placed, panel, legendViewport, policy, theme).map { guide =>
              out += guide
              ()
            }
            idx += 1
          result.map(_ => out.result())

  /** Apply a solved placement to the spec it was measured from. Placements are keyed by that spec's
    * index, so the two variants always agree; the final case is unreachable and keeps the authored
    * origin rather than inventing an error for a condition that cannot arise.
    */
  private def placeGuide(spec: GuideSpec, placement: GuidePlacement): GuideSpec =
    def x(value: Double): LengthExpr = LengthExpr(Length.pointsUnsafe(value))
    def y(value: Double): LengthExpr = LengthExpr.npcUnsafe(1.0) - ExtentExpr.pointsUnsafe(value)
    (spec, placement) match
      case (legend: GuideSpec.Legend, solved: GuidePlacement.Legend) =>
        legend.copy(
          origin = Point(x(solved.xPt), y(solved.topPt)),
          rowGap = ExtentExpr.pointsUnsafe(solved.rowPitchPt),
          firstRowOffset = Some(ExtentExpr.pointsUnsafe(solved.firstRowOffsetPt)),
          labelOffset = x(solved.labelOffsetPt),
          markerSize = ExtentExpr.pointsUnsafe(solved.markerSizePt)
        )
      case (colorbar: GuideSpec.Colorbar, solved: GuidePlacement.Colorbar) =>
        colorbar.copy(
          origin = Point(
            x(solved.xPt),
            y(solved.topPt + solved.barTopOffsetPt + solved.barHeightPt)
          ),
          barWidth = ExtentExpr.pointsUnsafe(solved.barWidthPt),
          barHeight = ExtentExpr.pointsUnsafe(solved.barHeightPt),
          tickLength = ExtentExpr.pointsUnsafe(solved.tickLengthPt),
          labelOffset = ExtentExpr.pointsUnsafe(solved.labelOffsetPt),
          titleOffset = ExtentExpr.pointsUnsafe(solved.titleOffsetPt)
        )
      case _ => spec

/** Panel decoration is ordinary renderer-neutral geometry. It is lowered after guide derivation so
  * grid lines use the same tick positions as axes, and inserted before layer marks so data remains
  * visually authoritative.
  */
private[intaglio] object PanelPhase:
  def lower(
      layout: Option[PanelLayout],
      specs: Vector[GuideSpec],
      theme: PanelTheme
  ): Either[GraphicsError, Vector[Grob]] =
    layout match
      case None        => Right(Vector.empty)
      case Some(panel) =>
        val out = Vector.newBuilder[Grob]
        theme.background.foreach { gp =>
          out += Grob.rectUnsafe(
            center = Point.npcUnsafe(0.5, 0.5),
            size = Size.npcUnsafe(1.0, 1.0),
            gp = gp,
            name = Some(PlotRegion.PanelBackground)
          )
        }
        theme.grid match
          case None     => Right(out.result())
          case Some(gp) =>
            val xValues = tickValues(specs, horizontal = true).filter(panel.xScale.contains)
            val yValues = tickValues(specs, horizontal = false).filter(panel.yScale.contains)
            if xValues.nonEmpty then
              out += Grob
                .segments(
                  xValues.map(x =>
                    Point.nativeUnsafe(x, panel.yScale.lower) -> Point
                      .nativeUnsafe(x, panel.yScale.upper)
                  ),
                  gp = gp,
                  name = Some(PlotRegion.PanelGridX)
                )
                .orThrow
            if yValues.nonEmpty then
              out += Grob
                .segments(
                  yValues.map(y =>
                    Point.nativeUnsafe(panel.xScale.lower, y) -> Point
                      .nativeUnsafe(panel.xScale.upper, y)
                  ),
                  gp = gp,
                  name = Some(PlotRegion.PanelGridY)
                )
                .orThrow
            Right(out.result())

  private def tickValues(specs: Vector[GuideSpec], horizontal: Boolean): Vector[Double] =
    specs.iterator
      .collect {
        case axis: GuideSpec.Axis if axis.side.isHorizontal == horizontal =>
          axis.ticks.getOrElse(Vector.empty).map(_.value)
      }
      .flatten
      .toVector
      .distinct
