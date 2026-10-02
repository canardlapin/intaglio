package intaglio.laws

import intaglio.*

/** Per-mark identity laws for point batches, and for a host's own splitting or filtering of them.
  *
  * `split` is the host's operation: it turns one identified batch (its points and its
  * [[BatchMarks]]) into the pieces the host actually draws — chunks, a filtered subset, or the
  * batch unchanged. The laws require that identity is invisible to geometry, that every mark the
  * pieces draw keeps the name it had in the whole batch, and that names which no longer match their
  * marks are refused rather than silently misassigned.
  */
object BatchMarkLaws:
  def apply(
      points: Vector[Point],
      marks: BatchMarks,
      split: (Vector[Point], BatchMarks) => Vector[(Vector[Point], BatchMarks)],
      device: DeviceContext
  ): LawSuite =
    def batch(values: Vector[Point]): Either[GraphicsError, Grob] = Grob.pointBatch(values)
    def identified(values: Vector[Point], names: BatchMarks): Either[GraphicsError, Grob] =
      batch(values).map(Grob.annotated(_, GrobMeta.marks(names)))
    def lower(grobs: Vector[Grob]): Either[GraphicsError, DeviceScene] =
      DeviceScene.fromScene(Scene(grobs), device)
    def named(scene: DeviceScene): Vector[(String, Double, Double)] =
      BatchMarks.marksOf(scene).map(m => (m.name.value, m.at.x, m.at.y)).sortBy(identity)

    LawSuite(
      "batch-marks",
      Vector(
        Law(
          "identity does not change lowering",
          () =>
            val result = for
              plain <- batch(points).flatMap(grob => lower(Vector(grob)))
              marked <- identified(points, marks).flatMap(grob => lower(Vector(grob)))
            yield (plain, marked)
            result match
              case Left(error)            => Vector(s"fixture was rejected: ${error.message}")
              case Right((plain, marked)) =>
                LawDiagnostics.problemWhen(
                  marked.elements != Vector(
                    DeviceElement.Annotated(GrobMeta.marks(marks), plain.elements)
                  ),
                  s"identified lowering ${marked.elements} is not the plain lowering wrapped"
                )
        ),
        Law(
          "every mark keeps its name through the host's split",
          () =>
            val pieces = split(points, marks)
            val result = for
              whole <- identified(points, marks).flatMap(grob => lower(Vector(grob)))
              grobs <- pieces.foldLeft[Either[GraphicsError, Vector[Grob]]](Right(Vector.empty)) {
                case (acc, (values, names)) =>
                  acc.flatMap(gs => identified(values, names).map(gs :+ _))
              }
              parts <- lower(grobs)
            yield (named(whole), named(parts))
            result match
              case Left(error)            => Vector(s"split pieces were rejected: ${error.message}")
              case Right((whole, pieces)) =>
                val drawn =
                  whole.filter(mark => pieces.exists(p => p._2 == mark._2 && p._3 == mark._3))
                LawDiagnostics.problemWhen(
                  !pieces.forall(whole.contains) || pieces.size != drawn.size,
                  s"split names ${pieces.take(8)} do not match the whole batch's ${drawn.take(8)}"
                )
        ),
        Law(
          "names that do not match their marks are refused",
          () =>
            if marks.size < 2 then Vector.empty
            else
              marks.slice(0, marks.size - 1) match
                case Left(error)  => Vector(s"could not drop a name: ${error.message}")
                case Right(short) =>
                  identified(points, short).flatMap(grob => lower(Vector(grob))) match
                    case Left(GraphicsError.BatchColumnLengthMismatch("mark names", _, _)) =>
                      Vector.empty
                    case other => Vector(s"a batch with one name too few lowered to $other")
        )
      )
    )
