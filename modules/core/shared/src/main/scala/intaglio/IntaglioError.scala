package intaglio

/** Root of every typed Intaglio failure.
  *
  * Core validation and each backend renderer report their own error enum, so provenance stays
  * precise. They share this supertype so a pipeline that crosses the core/backend boundary —
  * compile a `Scene`, then render it — can carry a single typed channel (`Either[IntaglioError,
  * ?]`) instead of a union of unrelated error types.
  */
trait IntaglioError:
  def message: String
