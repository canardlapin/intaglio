package intaglio.laws

import intaglio.*

/** Scene algebra, ordered traversal, and deterministic device-lowering laws. */
object SceneDeviceLaws:
  def apply(
      first: Scene,
      second: Scene,
      third: Scene,
      device: DeviceContext
  ): LawSuite =
    val combined = first ++ second ++ third

    LawSuite(
      "scene-device",
      Vector(
        Law(
          "scene identity",
          () =>
            Vector(
              LawDiagnostics.problemWhen(
                Scene.empty ++ combined != combined,
                "the empty scene was not a left identity"
              ),
              LawDiagnostics.problemWhen(
                combined ++ Scene.empty != combined,
                "the empty scene was not a right identity"
              )
            ).flatten
        ),
        Law(
          "scene associativity",
          () =>
            LawDiagnostics.problemWhen(
              (first ++ second) ++ third != first ++ (second ++ third),
              "scene concatenation changed with grouping"
            )
        ),
        Law(
          "ordered depth-first traversal",
          () =>
            val expected = traversal(first) ++ traversal(second) ++ traversal(third)
            val observed = traversal(combined)
            LawDiagnostics.problemWhen(
              observed != expected || traversal(combined) != observed,
              s"expected ${names(expected)}, observed ${names(observed)}"
            )
        ),
        Law(
          "successful deterministic device lowering",
          () =>
            val firstLowering = DeviceScene.fromScene(combined, device)
            val secondLowering = DeviceScene.fromScene(combined, device)
            firstLowering match
              case Left(error) => Vector(s"fixture was rejected: ${error.message}")
              case Right(_)    =>
                LawDiagnostics.problemWhen(
                  firstLowering != secondLowering,
                  s"first=$firstLowering, second=$secondLowering"
                )
        )
      )
    )

  private def traversal(scene: Scene): Vector[Grob] =
    scene.grobs.flatMap(traversal)

  private def traversal(grob: Grob): Vector[Grob] =
    grob +: grob.children.flatMap(traversal)

  private def names(grobs: Vector[Grob]): Vector[String] =
    grobs.map(_.name.fold("<unnamed>")(_.value))

/** Applying the built-in coordinate transpose twice must recover the public coordinate result. */
object CoordinateInvolutionLaws:
  def apply(input: CoordInput): LawSuite =
    withEquality(input)(samePublicResult)

  def withEquality(input: CoordInput)(
      equivalent: (CoordResult, CoordResult) => Boolean
  ): LawSuite =
    val flipped = Coord.Flipped()
    val expected = CoordResult(input.layers, input.ranges)

    def twice: Either[GraphicsError, CoordResult] =
      flipped.transform(input).flatMap { once =>
        flipped.transform(CoordInput(once.layers, once.ranges, input.scales))
      }

    LawSuite(
      "coordinate-involution",
      Vector(
        Law(
          "successful transpose fixture",
          () =>
            flipped.transform(input) match
              case Left(error) => Vector(s"fixture was rejected: ${error.message}")
              case Right(_)    => Vector.empty
        ),
        Law(
          "transpose involution",
          () =>
            twice match
              case Left(error)     => Vector(s"second transpose failed: ${error.message}")
              case Right(observed) =>
                LawDiagnostics.problemWhen(
                  !equivalent(observed, expected),
                  "two coordinate transposes did not recover the original public result"
                )
        ),
        Law(
          "deterministic transpose",
          () =>
            val first = twice
            val second = twice
            val agrees =
              (first, second) match
                case (Left(left), Left(right))   => left == right
                case (Right(left), Right(right)) => equivalent(left, right)
                case _                           => false
            LawDiagnostics.problemWhen(!agrees, "the transpose involution was not deterministic")
        )
      )
    )

  private def samePublicResult(left: CoordResult, right: CoordResult): Boolean =
    left.ranges == right.ranges && left.layers.length == right.layers.length &&
      left.layers.zip(right.layers).forall { case (first, second) =>
        first.layerIndex == second.layerIndex && first.geom == second.geom &&
        first.stat.label == second.stat.label && first.stat.contract == second.stat.contract &&
        first.position == second.position && first.dataSize == second.dataSize &&
        first.annotation == second.annotation && first.grouping == second.grouping &&
        first.scaleDeclarations == second.scaleDeclarations && first.rows == second.rows &&
        first.droppedRows == second.droppedRows && first.grobs == second.grobs
      }

/** Finite, root-bounded frame and measured-guide-fit laws for the portable plot-layout solver. */
object PlotLayoutLaws:
  private val Tolerance = 1.0e-9

  private final case class NpcFrame(x: Double, y: Double, width: Double, height: Double)

  def apply(policy: LayoutPolicy, request: PlotLayoutRequest): LawSuite =
    def solved: Either[GraphicsError, PlotFrames] =
      PlotLayoutSolver.solve(policy, request)

    LawSuite(
      "plot-layout",
      Vector(
        Law(
          "successful deterministic solve",
          () =>
            val first = solved
            val second = solved
            first match
              case Left(error) => Vector(s"fixture was rejected: ${error.message}")
              case Right(_)    =>
                LawDiagnostics.problemWhen(
                  first != second,
                  s"the same layout request solved differently: first=$first, second=$second"
                )
        ),
        Law(
          "finite root-bounded frames",
          () =>
            solved match
              case Left(error)   => Vector(s"fixture was rejected: ${error.message}")
              case Right(frames) =>
                allFrames(frames).flatMap { case (label, frame) =>
                  npcFrame(frame) match
                    case Left(problem) => Vector(s"$label: $problem")
                    case Right(value)  =>
                      val numbers = Vector(value.x, value.y, value.width, value.height)
                      Vector(
                        LawDiagnostics.problemWhen(
                          numbers.exists(number => !number.isFinite),
                          s"$label contains a non-finite frame: $value"
                        ),
                        LawDiagnostics.problemWhen(
                          value.x < -Tolerance || value.y < -Tolerance || value.width < 0.0 ||
                            value.height < 0.0 || value.x + value.width > 1.0 + Tolerance ||
                            value.y + value.height > 1.0 + Tolerance,
                          s"$label falls outside the root viewport: $value"
                        )
                      ).flatten
                }
        ),
        Law(
          "measured guide stack fits",
          () =>
            (request.legend, solved) match
              case (None, _)                    => Vector.empty
              case (_, Left(error))             => Vector(s"fixture was rejected: ${error.message}")
              case (Some(guide), Right(frames)) =>
                frames.legend match
                  case None        => Vector("the solver omitted the requested guide frame")
                  case Some(frame) =>
                    npcFrame(frame) match
                      case Left(problem) => Vector(s"guide frame: $problem")
                      case Right(value)  =>
                        val plan = GuideStackSolver.plan(policy, guide)
                        val pixelsPerPoint = policy.referenceDevice.pixelsPerInch / 72.0
                        val widthPt = value.width * policy.referenceDevice.width / pixelsPerPoint
                        val heightPt = value.height * policy.referenceDevice.height / pixelsPerPoint
                        val numbers = Vector(plan.widthPt, plan.heightPt, widthPt, heightPt)
                        Vector(
                          LawDiagnostics.problemWhen(
                            numbers.exists(number => !number.isFinite),
                            s"guide measurement is non-finite: plan=$plan, frame=$value"
                          ),
                          LawDiagnostics.problemWhen(
                            plan.placements.length != guide.items.length,
                            s"expected ${guide.items.length} placements, observed ${plan.placements.length}"
                          ),
                          LawDiagnostics.problemWhen(
                            widthPt + Tolerance < plan.widthPt || heightPt + Tolerance < plan.heightPt,
                            s"guide plan ${plan.widthPt}x${plan.heightPt}pt does not fit ${widthPt}x${heightPt}pt"
                          )
                        ).flatten
        )
      )
    )

  private def allFrames(frames: PlotFrames): Vector[(String, PanelFrame)] =
    Vector("panel" -> frames.panel) ++
      AxisSide.values.toVector.flatMap(side => frames.axes.get(side).map(side.toString -> _)) ++
      frames.legend.map("legend" -> _).toVector ++
      frames.title.map("title" -> _).toVector ++
      frames.subtitle.map("subtitle" -> _).toVector ++
      frames.grid.zipWithIndex.flatMap { case (frame, index) =>
        Vector(s"grid[$index].panel" -> frame.panel, s"grid[$index].strip" -> frame.strip)
      }

  private def npcFrame(frame: PanelFrame): Either[String, NpcFrame] =
    for
      x <- npc(frame.origin.x, "x")
      y <- npc(frame.origin.y, "y")
      width <- npc(frame.size.width.expr, "width")
      height <- npc(frame.size.height.expr, "height")
    yield NpcFrame(x, y, width, height)

  private def npc(expr: LengthExpr, field: String): Either[String, Double] =
    expr match
      case LengthExpr.Const(length) if length.unit == LengthUnit.Npc => Right(length.value)
      case other => Left(s"$field is not a solved npc constant: $other")

/** Recompilation laws for two render contexts describing the same physical target. The observer
  * chooses the target-bound facts that must agree after normalizing device coordinates.
  */
object TargetRecompilationLaws:
  private val Tolerance = 1.0e-12

  def apply[Row, Snapshot](
      plot: Plot[Row],
      first: RenderContext,
      second: RenderContext,
      options: PlotCompilerOptions = PlotCompilerOptions.default
  )(
      observe: RenderPlan => Either[GraphicsError, Snapshot]
  ): LawSuite =
    withEquality(plot, first, second, options)(observe)(_ == _)

  def withEquality[Row, Snapshot](
      plot: Plot[Row],
      first: RenderContext,
      second: RenderContext,
      options: PlotCompilerOptions = PlotCompilerOptions.default
  )(
      observe: RenderPlan => Either[GraphicsError, Snapshot]
  )(
      equivalent: (Snapshot, Snapshot) => Boolean
  ): LawSuite =
    def compile(context: RenderContext): Either[GraphicsError, RenderPlan] =
      PlotCompiler.compile(plot, context, options)

    def samePhysicalAxis(firstPixels: Int, firstPpi: Double, secondPixels: Int, secondPpi: Double) =
      math.abs(firstPixels.toDouble / firstPpi - secondPixels.toDouble / secondPpi) <= Tolerance

    LawSuite(
      "target-recompilation",
      Vector(
        Law(
          "equal physical target applicability",
          () =>
            LawDiagnostics.problemWhen(
              !samePhysicalAxis(
                first.width,
                first.pixelsPerInch,
                second.width,
                second.pixelsPerInch
              ) ||
                !samePhysicalAxis(
                  first.height,
                  first.pixelsPerInch,
                  second.height,
                  second.pixelsPerInch
                ),
              s"contexts describe different physical sizes: ${first.width}x${first.height}@${first.pixelsPerInch}ppi and ${second.width}x${second.height}@${second.pixelsPerInch}ppi"
            )
        ),
        Law(
          "successful deterministic compilation",
          () =>
            val firstOnce = compile(first)
            val firstTwice = compile(first)
            val secondOnce = compile(second)
            val secondTwice = compile(second)
            Vector(
              firstOnce.left.toOption
                .map(error => s"first target failed: ${error.message}")
                .toVector,
              secondOnce.left.toOption
                .map(error => s"second target failed: ${error.message}")
                .toVector,
              LawDiagnostics.problemWhen(
                firstOnce.map(_.scene) != firstTwice.map(_.scene),
                "first target compiled differently across runs"
              ),
              LawDiagnostics.problemWhen(
                secondOnce.map(_.scene) != secondTwice.map(_.scene),
                "second target compiled differently across runs"
              )
            ).flatten
        ),
        Law(
          "target-neutral scene",
          () =>
            (compile(first), compile(second)) match
              case (Left(error), _) => Vector(s"first target failed: ${error.message}")
              case (_, Left(error)) => Vector(s"second target failed: ${error.message}")
              case (Right(firstPlan), Right(secondPlan)) =>
                LawDiagnostics.problemWhen(
                  firstPlan.scene != secondPlan.scene,
                  "equal physical targets produced different renderer-neutral scenes"
                )
        ),
        Law(
          "equivalent target-bound observation",
          () =>
            (compile(first), compile(second)) match
              case (Left(error), _) => Vector(s"first target failed: ${error.message}")
              case (_, Left(error)) => Vector(s"second target failed: ${error.message}")
              case (Right(firstPlan), Right(secondPlan)) =>
                (observe(firstPlan), observe(secondPlan)) match
                  case (Left(error), _) => Vector(s"first observation failed: ${error.message}")
                  case (_, Left(error)) => Vector(s"second observation failed: ${error.message}")
                  case (Right(firstValue), Right(secondValue)) =>
                    LawDiagnostics.problemWhen(
                      !equivalent(firstValue, secondValue),
                      s"first=$firstValue, second=$secondValue"
                    )
        )
      )
    )
