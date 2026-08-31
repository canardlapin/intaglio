package intaglio

/** Stable, renderer-neutral identity for an accessible plot node. IDs intentionally use the
  * portable XML/HTML identifier subset so SVG backends can expose them without rewriting.
  */
final class SemanticId private (val value: String):
  override def equals(other: Any): Boolean =
    other match
      case that: SemanticId => value == that.value
      case _                => false

  override def hashCode(): Int =
    value.hashCode

  override def toString: String =
    s"SemanticId($value)"

object SemanticId:
  def apply(value: String): Either[GraphicsError, SemanticId] =
    if valid(value) then Right(new SemanticId(value))
    else Left(GraphicsError.InvalidSemanticId(value))

  def unsafe(value: String): SemanticId =
    apply(value).orThrow

  private[intaglio] def child(parent: SemanticId, segment: String): SemanticId =
    unsafe(s"${parent.value}-$segment")

  private def valid(value: String): Boolean =
    value.nonEmpty && validFirst(value.head) && value.tail.forall(validRest)

  private def validFirst(value: Char): Boolean =
    value == '_' || asciiLetter(value)

  private def validRest(value: Char): Boolean =
    validFirst(value) || asciiDigit(value) || value == '-' || value == '.'

  private def asciiLetter(value: Char): Boolean =
    (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')

  private def asciiDigit(value: Char): Boolean =
    value >= '0' && value <= '9'

/** User-supplied accessibility context retained through plot compilation. */
final case class PlotAccessibility(
    semanticId: SemanticId = SemanticId.unsafe("intaglio-plot"),
    description: Option[String] = None,
    altText: Option[String] = None
)

/** Compact deterministic datum identity series. IDs are generated on demand, so lean batches do not
  * trade away their columnar memory behavior merely to retain semantic identity.
  */
final case class DatumIdSeries private (prefix: SemanticId, count: Int):
  require(count >= 0, "`count` must be non-negative")

  def valueAt(index: Int): SemanticId =
    if index < 0 || index >= count then
      throw new IndexOutOfBoundsException(s"datum semantic index $index outside [0, $count)")
    SemanticId.child(prefix, index.toString)

  def values: Vector[SemanticId] =
    Vector.tabulate(count)(valueAt)

  private[intaglio] def contains(id: SemanticId): Boolean =
    val marker = s"${prefix.value}-"
    if !id.value.startsWith(marker) then false
    else id.value.drop(marker.length).toIntOption.exists(index => index >= 0 && index < count)

object DatumIdSeries:
  private[intaglio] def apply(layerId: SemanticId, count: Int): DatumIdSeries =
    new DatumIdSeries(SemanticId.child(layerId, "datum"), count)

final case class LayerSemantics(
    id: SemanticId,
    layerIndex: Int,
    geom: String,
    stat: String,
    inputRows: Int,
    resolvedRows: Int,
    droppedRows: Int,
    datumIds: DatumIdSeries
)

final case class ScaleSemantics(
    aesthetic: String,
    name: String,
    kind: ScaleKind,
    domain: String
)

enum AccessibilityDiagnostic:
  case AmbiguousPalette(
      aesthetic: String,
      scale: String,
      sampledValues: Int,
      distinctColors: Int
  )

  def code: String =
    this match
      case AmbiguousPalette(_, _, _, _) => "ambiguous-palette"

  def message: String =
    this match
      case AmbiguousPalette(aesthetic, scale, sampledValues, distinctColors) =>
        s"$aesthetic scale '$scale' maps $sampledValues sampled values to only $distinctColors distinct RGBA colors"

/** Accessibility and identity contract for one compiled plot. */
final case class PlotSemantics(
    id: SemanticId,
    title: Option[String],
    description: Option[String],
    altText: Option[String],
    layers: Vector[LayerSemantics],
    scales: Vector[ScaleSemantics],
    diagnostics: Vector[AccessibilityDiagnostic]
):
  def isEmpty: Boolean =
    this == PlotSemantics.empty

  def accessibleTitle: String =
    nonBlank(title).getOrElse("Intaglio plot")

  def accessibleDescription: String =
    nonBlank(altText).orElse(nonBlank(description)).getOrElse(textSummary)

  def textSummary: String =
    val markCount = layers.map(_.resolvedRows).sum
    val out = Vector.newBuilder[String]
    out += s"Plot ${id.value}: ${counted(layers.length, "layer")}, ${counted(markCount, "resolved mark")}, ${counted(scales.length, "scale")}."
    layers.foreach { layer =>
      out += s"Layer ${layer.layerIndex} (${layer.id.value}): geom=${layer.geom}, stat=${layer.stat}, input=${layer.inputRows}, resolved=${layer.resolvedRows}, dropped=${layer.droppedRows}."
    }
    scales.foreach { scale =>
      out += s"Scale ${scale.aesthetic} (${scale.name}): kind=${scale.kind.toString.toLowerCase}, domain=${scale.domain}."
    }
    diagnostics.foreach(diagnostic =>
      out += s"Diagnostic ${diagnostic.code}: ${diagnostic.message}."
    )
    out.result().mkString("\n")

  def withAltText(value: String): PlotSemantics =
    copy(altText = Some(value))

  def withDescription(value: String): PlotSemantics =
    copy(description = Some(value))

  private def counted(count: Int, noun: String): String =
    s"$count $noun${if count == 1 then "" else "s"}"

  private def nonBlank(value: Option[String]): Option[String] =
    value.filter(_.trim.nonEmpty)

object PlotSemantics:
  val empty: PlotSemantics =
    PlotSemantics(
      SemanticId.unsafe("intaglio-plot"),
      None,
      None,
      None,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )

  private[intaglio] def build(
      accessibility: PlotAccessibility,
      labels: PlotLabels,
      layers: Vector[TrainedLayer],
      scaleRegistry: PlotScaleRegistry
  ): Either[GraphicsError, PlotSemantics] =
    val layerSemantics = summarizeLayers(accessibility.semanticId, layers)
    validateIds(accessibility.semanticId, layerSemantics).map { _ =>
      val scales = scaleRegistry.scales.map(summarizeScale)
      PlotSemantics(
        accessibility.semanticId,
        labels.title,
        accessibility.description,
        accessibility.altText,
        layerSemantics,
        scales,
        paletteDiagnostics(scaleRegistry.scales)
      )
    }

  private def summarizeLayers(
      plotId: SemanticId,
      layers: Vector[TrainedLayer]
  ): Vector[LayerSemantics] =
    val indices = layers.map(_.layerIndex).distinct
    indices.map { layerIndex =>
      val copies = layers.filter(_.layerIndex == layerIndex)
      val id = copies.iterator.flatMap(_.value.semanticId).nextOption().getOrElse {
        SemanticId.child(plotId, s"layer-$layerIndex")
      }
      val resolvedRows = copies.map(_.rows.length).sum
      LayerSemantics(
        id = id,
        layerIndex = layerIndex,
        geom = copies.head.geom.label,
        stat = copies.head.stat.label,
        inputRows = copies.map(_.dataSize).sum,
        resolvedRows = resolvedRows,
        droppedRows = copies.map(_.droppedRows.length).sum,
        datumIds = DatumIdSeries(id, resolvedRows)
      )
    }

  private def validateIds(
      plotId: SemanticId,
      layers: Vector[LayerSemantics]
  ): Either[GraphicsError, Unit] =
    val structural = plotId +: layers.map(_.id)
    structural.groupBy(_.value).collectFirst {
      case (id, values) if values.lengthCompare(1) > 0 =>
        id
    } match
      case Some(id) => Left(GraphicsError.DuplicateSemanticId(id))
      case None     =>
        structural.find(id => layers.exists(_.datumIds.contains(id))) match
          case Some(id) => Left(GraphicsError.DuplicateSemanticId(id.value))
          case None     => Right(())

  private def summarizeScale(scale: TrainedScale): ScaleSemantics =
    ScaleSemantics(
      scale.aesthetic,
      scale.descriptor.name.value,
      scale.descriptor.kind,
      domainText(scale.descriptor.domain)
    )

  private def domainText(domain: ScaleDomain): String =
    domain match
      case ScaleDomain.Continuous(raw, _)              => s"[${raw.lower}, ${raw.upper}]"
      case ScaleDomain.Temporal(kind, _, lower, upper) =>
        s"${kind.label} [$lower, $upper]"
      case ScaleDomain.Discrete(levels, ordered) =>
        s"${if ordered then "ordered " else ""}[${levels.mkString(", ")}]"
      case ScaleDomain.Band(levels, ordered, _) =>
        s"${if ordered then "ordered " else ""}bands [${levels.mkString(", ")}]"
      case ScaleDomain.Unspecified => "unspecified"

  private def paletteDiagnostics(
      scales: Vector[TrainedScale]
  ): Vector[AccessibilityDiagnostic] =
    scales.flatMap { scale =>
      if (scale.key eq Aesthetic.Color) || (scale.key eq Aesthetic.Fill) then
        val colors = sampledColors(scale)
        val distinct = colors.distinct.length
        Option.when(colors.lengthCompare(1) > 0 && distinct < colors.length)(
          AccessibilityDiagnostic.AmbiguousPalette(
            scale.aesthetic,
            scale.descriptor.name.value,
            colors.length,
            distinct
          )
        )
      else None
    }

  private def sampledColors(scale: TrainedScale): Vector[Rgba] =
    scale.scale match
      case discrete: DiscreteScale[?, ?] =>
        val count = scale.descriptor.domain match
          case ScaleDomain.Discrete(levels, _) => levels.length
          case _                               => 0
        Vector.tabulate(count)(index => discrete.palette(index, count)).collect {
          case color: Rgba =>
            color
        }
      case continuous: ContinuousScale[?] =>
        continuous.paletteSamples(5).toOption.toVector.flatten.collect { case color: Rgba => color }
      case _ => Vector.empty

/** Semantic sidecar carried by logical and device scenes. */
final case class SceneSemantics(plots: Vector[PlotSemantics]):
  def ++(that: SceneSemantics): SceneSemantics =
    SceneSemantics(plots ++ that.plots)

  def isEmpty: Boolean = plots.isEmpty

  def documentId: Option[SemanticId] =
    plots match
      case Vector(plot) => Some(plot.id)
      case _            => None

  def accessibleTitle: Option[String] =
    plots match
      case Vector()     => None
      case Vector(plot) => Some(plot.accessibleTitle)
      case values       => Some(s"Intaglio composition with ${values.length} plots")

  def accessibleDescription: Option[String] =
    Option.when(plots.nonEmpty)(plots.map(_.accessibleDescription).mkString("\n"))

object SceneSemantics:
  val empty: SceneSemantics = SceneSemantics(Vector.empty)

  def single(plot: PlotSemantics): SceneSemantics = SceneSemantics(Vector(plot))
