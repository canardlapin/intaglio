package intaglio

/** One value of a classed colour scale: the class it belongs to, by index into the scale's classes,
  * and the scalar to colour within that class.
  */
final case class ClassedValue(classIndex: Int, value: Double)

/** One class of a [[ClassedColorScaleSpec]]: a label for its legend and the continuous colour scale
  * (palette, transform, out-of-bounds policy) its values use.
  */
final case class ColorClass private (label: String, spec: ContinuousScaleSpec[Rgba])

object ColorClass:
  def apply(
      label: String,
      palette: Palette[Rgba],
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor
  ): Either[GraphicsError, ColorClass] =
    ContinuousScaleSpec(label, palette, transform, oob).map(new ColorClass(label, _))

  def unsafe(
      label: String,
      palette: Palette[Rgba],
      transform: Transform = Transform.identity,
      oob: OobPolicy = OobPolicy.Censor
  ): ColorClass =
    apply(label, palette, transform, oob).orThrow

/** A colour scale with one continuous palette per class, such as a diverging ramp for task columns
  * beside a muted grey ramp for nuisance columns in one raster. Each class trains its own domain
  * from its own values and maps through its own palette, so a class is an ordinary continuous
  * colour scale and gets its own colorbar; the classes share one fill aesthetic, so one image
  * carries all of them. A value whose class index names no class is out of the scale's domain.
  */
final class ClassedColorScaleSpec private (
    val name: GraphicsName,
    val classes: Vector[ColorClass]
) extends ScaleSpec[ClassedValue, Rgba]:
  override val kind: ScaleKind =
    ScaleKind.Continuous

  private[intaglio] override def observation(value: ClassedValue): Option[ScaleObservation] =
    Option.when(classes.indices.contains(value.classIndex))(
      ScaleObservation.Classed(value.classIndex, value.value)
    )

  private[intaglio] override def trainSpec(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[ClassedValue, Rgba]] =
    val byClass = observations
      .collect { case ScaleObservation.Classed(index, value) => index -> value }
      .groupMap(_._1)(pair => ScaleObservation.Continuous(pair._2))
    val trained = classes.zipWithIndex.foldLeft[
      Either[GraphicsError, Vector[Option[ContinuousScale[Rgba]]]]
    ](Right(Vector.empty)) { case (result, (colorClass, index)) =>
      result.flatMap { done =>
        byClass.get(index) match
          // A class with no values in this plot has no domain, so it draws nothing and has no bar.
          case None         => Right(done :+ None)
          case Some(values) =>
            colorClass.spec.trainSpec(values, theme, facetLocal) match
              case Right(scale: ContinuousScale[?]) =>
                Right(done :+ Some(scale.asInstanceOf[ContinuousScale[Rgba]]))
              // Every value masked, non-finite, or outside the class's transform domain: the same
              // as a class with no cells, rather than a failure of the whole plot.
              case Left(GraphicsError.EmptyContinuousRange) => Right(done :+ None)
              case Left(error)                              => Left(error)
              case Right(_)                                 =>
                Left(GraphicsError.InvalidStatResult("classed colour", "untrained class scale"))
      }
    }
    trained.map(scales => ClassedColorScale(name, classes.map(_.label), scales))

object ClassedColorScaleSpec:
  def apply(
      name: String,
      classes: Vector[ColorClass]
  ): Either[GraphicsError, ClassedColorScaleSpec] =
    if classes.isEmpty then Left(GraphicsError.EmptyPalette)
    else if classes.map(_.label).distinct.length != classes.length then
      Left(
        GraphicsError.DuplicateLevel(classes.map(_.label).diff(classes.map(_.label).distinct).head)
      )
    else GraphicsName(name, "classed colour scale").map(new ClassedColorScaleSpec(_, classes))

  def unsafe(name: String, classes: Vector[ColorClass]): ClassedColorScaleSpec =
    apply(name, classes).orThrow

/** A trained [[ClassedColorScaleSpec]]: one trained continuous colour scale per class, or `None`
  * for a class that had no values.
  */
final case class ClassedColorScale private[intaglio] (
    name: GraphicsName,
    labels: Vector[String],
    scales: Vector[Option[ContinuousScale[Rgba]]]
) extends Scale[ClassedValue, Rgba]:
  override def descriptor: ScaleDescriptor =
    ScaleDescriptor(name, ScaleKind.Continuous, ScaleDomain.Unspecified)

  /** The trained scale of each class that had values, with its label. */
  def classScales: Vector[(String, ContinuousScale[Rgba])] =
    labels.zip(scales).collect { case (label, Some(scale)) => label -> scale }

  override def mapValue(value: ClassedValue): Option[Rgba] =
    mapValueResult(value).toOption

  override def mapValueResult(value: ClassedValue): Either[ScaleMapFailure, Rgba] =
    scales.lift(value.classIndex).flatten match
      case Some(scale) => scale.mapValueResult(value.value)
      case None        =>
        Left(ScaleMapFailure.OutOfDomain(name.value, s"class ${value.classIndex}"))

  private[intaglio] override def observation(value: ClassedValue): Option[ScaleObservation] =
    Option.when(scales.indices.contains(value.classIndex))(
      ScaleObservation.Classed(value.classIndex, value.value)
    )
