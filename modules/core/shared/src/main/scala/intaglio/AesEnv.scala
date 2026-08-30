package intaglio

/** Source-compatible name for the canonical aesthetic mapping. `AesSpec` owns both the precise
  * public fields and the deterministic typed operations; there is no second environment
  * representation.
  */
type AesEnv[Row] = AesSpec[Row]

object AesEnv:
  def empty[Row]: AesEnv[Row] =
    AesSpec.empty[Row]

/** A scaled aesthetic binding with its hidden input/output types kept together. The only erased
  * cast packages an existentially typed `Scaled` value; all observation and retraining operations
  * are typed again inside this value.
  */
sealed trait RegisteredScale[Row]:
  type In
  type Out

  def aesthetic: Aesthetic[Out]
  def value: AesValue.Scaled[Row, In, Out]

  final def scale: Scale[In, Out] =
    value.scale

  final def descriptor: ScaleDescriptor =
    scale.descriptor

  final def sharesDeclaration(that: RegisteredScale[?]): Boolean =
    scale.asInstanceOf[AnyRef] eq that.scale.asInstanceOf[AnyRef]

  final def observations(
      rows: Vector[Row],
      layerIndex: Int
  ): Either[GraphicsError, Vector[ScaleObservation]] =
    val out = Vector.newBuilder[ScaleObservation]
    var rowIndex = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while rowIndex < rows.length && result.isRight do
      RowMapping.evaluateFunction(value.value, rows(rowIndex)) match
        case Right(input) =>
          scale.observation(input).foreach(out += _)
        case Left((contract, failure)) =>
          result = Left(
            GraphicsError.MappingEvaluationFailed(
              "scale training",
              Some(layerIndex),
              aesthetic.label,
              rowIndex,
              contract,
              failure
            )
          )
      rowIndex += 1
    result.map(_ => out.result())

  final def trainPlotWide(
      observations: Vector[ScaleObservation]
  ): Either[GraphicsError, RegisteredScale[Row]] =
    scale.trainPlotWide(observations).map { trained =>
      RegisteredScale(aesthetic, AesValue.Scaled(value.value, trained))
    }

  final def trainFacet(
      observations: Vector[ScaleObservation]
  ): Either[GraphicsError, RegisteredScale[Row]] =
    scale.trainFacet(observations).map { trained =>
      RegisteredScale(aesthetic, AesValue.Scaled(value.value, trained))
    }

  final def install(mapping: AesSpec[Row]): AesSpec[Row] =
    mapping.updated(aesthetic, value)

  final def declaration(layerIndex: Int): ScaleDeclaration =
    ScaleDeclaration(layerIndex, aesthetic.label, descriptor.name, descriptor.kind)

  final def trained: TrainedScale =
    TrainedScale(aesthetic.label, descriptor, scale)

object RegisteredScale:
  type Aux[Row, In0, Out0] = RegisteredScale[Row] { type In = In0; type Out = Out0 }

  def apply[Row, In0, Out0](
      aesthetic0: Aesthetic[Out0],
      value0: AesValue.Scaled[Row, In0, Out0]
  ): Aux[Row, In0, Out0] =
    new RegisteredScale[Row]:
      type In = In0
      type Out = Out0
      val aesthetic: Aesthetic[Out] = aesthetic0
      val value: AesValue.Scaled[Row, In, Out] = value0

  /** `AesSpec.updated` is the type-safe construction boundary. Existential iteration erases that
    * relation, so recover it once here and keep it packaged.
    */
  private[intaglio] def erased[Row](
      aesthetic: Aesthetic[?],
      value: AesValue.Scaled[Row, ?, ?]
  ): RegisteredScale[Row] =
    RegisteredScale(
      aesthetic.asInstanceOf[Aesthetic[Any]],
      value.asInstanceOf[AesValue.Scaled[Row, Any, Any]]
    )

/** Per-layer view of the plot-trained bindings in an effective aesthetic environment. Plot-wide
  * uniqueness and training live in `PlotScaleRegistry`; this view preserves layer provenance.
  */
final case class ScaleRegistry[Row] private (entries: Vector[RegisteredScale[Row]]):
  def declarations(layerIndex: Int): Vector[ScaleDeclaration] =
    entries.map(_.declaration(layerIndex))

  def trained: Vector[TrainedScale] =
    entries.map(_.trained)

  def forAesthetic(aesthetic: Aesthetic[?]): Option[RegisteredScale[Row]] =
    entries.find(_.aesthetic == aesthetic)

object ScaleRegistry:
  def fromMapping[Row](mapping: AesSpec[Row]): ScaleRegistry[Row] =
    ScaleRegistry(mapping.scaledEntries)

  def fromEnv[Row](env: AesEnv[Row]): ScaleRegistry[Row] =
    fromMapping(env)

/** The single trained scale table for a plot. Each aesthetic occurs at most once, in `Aesthetic`
  * declaration order.
  */
final case class PlotScaleRegistry private (scales: Vector[TrainedScale]):
  require(
    scales.map(_.aesthetic).distinct.length == scales.length,
    "plot scales must be unique by aesthetic"
  )

  def forAesthetic(aesthetic: Aesthetic[?]): Option[TrainedScale] =
    scales.find(_.aesthetic == aesthetic.label)

object PlotScaleRegistry:
  val empty: PlotScaleRegistry =
    PlotScaleRegistry(Vector.empty)

  private[intaglio] def from(scales: Vector[TrainedScale]): PlotScaleRegistry =
    PlotScaleRegistry(scales)
