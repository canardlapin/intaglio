package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.application.Platform
import _root_.javafx.scene.AccessibleRole
import _root_.javafx.scene.layout.VBox
import _root_.javafx.scene.text.{Font, FontWeight, Text}

/** One row of the inspector's text: a section, a label and its value. */
final case class InspectorRow(section: String, label: String, value: String)

/** A text inspector for one host or a linked group: for each plot, what its selection means (the
  * shared `InspectorModel`), in the rows the browser `InspectorPanel` shows — observations,
  * selected marks and aggregates, how far the selection reaches into each aggregate, exact members,
  * uncountable aggregates, unresolved keys and saved selections — and a filter's report in its own
  * section. It follows each host through `subscribeState`, so changes projected in by a link appear
  * too. Plain `Text` nodes; create, use and dispose it on the FX application thread.
  */
final class JavaFxInspector[A] private (
    hosts: Vector[(String, JavaFxInteractionHost[A])],
    sample: Int
):
  val node: VBox = new VBox(4)
  node.setAccessibleRole(AccessibleRole.TEXT)
  node.setAccessibleRoleDescription("selection inspector")
  private var models = Map.empty[Int, (String, InspectorModel[A])]
  private var filter = Vector.empty[InspectorRow]
  private var unsubscribe = Vector.empty[() => Unit]

  /** Every row now shown, plot by plot, then the filter report. */
  def rows: Vector[InspectorRow] =
    hosts.indices
      .flatMap(i => models.get(i))
      .flatMap((label, m) => JavaFxInspector.rows(label, m))
      .toVector ++ filter

  private def wire(): Either[IntaglioError, Unit] =
    hosts.zipWithIndex.foldLeft[Either[IntaglioError, Unit]](Right(())) {
      case (done, ((label, host), i)) =>
        done.flatMap(_ =>
          host
            .subscribeState { state =>
              models += i -> (label, InspectorModel.of(state, sample))
              render()
            }
            .map(stop => unsubscribe = unsubscribe :+ stop)
        )
    }

  /** Show a filter's report: rows before and after, observations removed, and each group's targets
    * and member totals before and after. Selection and emphasis are not part of it.
    */
  def showFilter(label: String, result: FilterResult[A]): Unit =
    def totals(v: Vector[Option[Int]]) = v.map(_.fold("?")(_.toString)).mkString(" ")
    val section = s"Filter applied to $label"
    filter = Vector(
      InspectorRow(section, "Rows", s"${result.input.rowsBefore} → ${result.input.rowsAfter}"),
      InspectorRow(section, "Observations removed", result.input.removed.size.toString)
    ) ++ result.statistics.map(s =>
      InspectorRow(
        section,
        s"group ${s.group + 1}",
        s"${s.targetsBefore} → ${s.targetsAfter} targets; totals " +
          s"${totals(s.totalsBefore)} → ${totals(s.totalsAfter)}"
      )
    )
    render()

  def clearFilter(): Unit =
    filter = Vector.empty
    render()

  def dispose(): Unit =
    unsubscribe.foreach(_())
    unsubscribe = Vector.empty
    node.getChildren.clear()

  private def render(): Unit =
    node.getChildren.clear()
    var section = ""
    rows.foreach { row =>
      if row.section != section then
        section = row.section
        val heading = new Text(section)
        heading.setFont(Font.font("System", FontWeight.BOLD, 12))
        node.getChildren.add(heading)
      val line = new Text(s"${row.label}: ${row.value}")
      line.setFont(Font.font("System", 12))
      node.getChildren.add(line)
    }

object JavaFxInspector:
  /** Inspect `hosts` (each with a label), listing at most `sample` keys in any one list. */
  def mount[A](
      hosts: Vector[(String, JavaFxInteractionHost[A])],
      sample: Int = 12
  ): Either[IntaglioError, JavaFxInspector[A]] =
    if !Platform.isFxApplicationThread then Left(JavaFxHostError.WrongThread)
    else if hosts.isEmpty then Left(InteractionError.InvalidValue("inspector", "no hosts"))
    else if sample < 0 then Left(InteractionError.InvalidValue("inspector sample", sample.toString))
    else
      val inspector = new JavaFxInspector(hosts, sample)
      inspector.wire() match
        case Right(_)    => Right(inspector)
        case Left(error) =>
          inspector.dispose()
          Left(error)

  private def keyText[A](key: EntityKey[A]): String = key.token.payload
  private def targetText(id: VisualTargetId): String =
    s"${id.plan.value} ${id.scope.value}#${id.ordinal}"

  def coverageText(coverage: MemberCoverage): String = coverage match
    case MemberCoverage.Known(k, n)      => s"$k of $n selected"
    case MemberCoverage.Unknown(ability) =>
      s"not countable (${ability.toString.toLowerCase} membership)"
    case MemberCoverage.Stale => "not countable (stale membership)"

  /** The inspector rows for one plot's model, as the browser `InspectorPanel` words them. */
  def rows[A](label: String, model: InspectorModel[A]): Vector[InspectorRow] =
    val sampleText =
      if model.observationSample.isEmpty then ""
      else
        s": ${model.observationSample.map(keyText).mkString(", ")}" +
          (if model.observations > model.observationSample.size then ", …" else "")
    val marks = model.targets.count(_.entity.nonEmpty)
    val aggregates = model.targets.count(_.entity.isEmpty)
    val selection = s"$label: Selection"
    val summary = Vector(
      InspectorRow(selection, "Observations selected", s"${model.observations}$sampleText"),
      InspectorRow(selection, "Targets selected", s"$marks marks, $aggregates aggregates"),
      InspectorRow(selection, "Aggregates not countable", model.uncountedAggregates.toString),
      InspectorRow(
        selection,
        "Unresolved observations",
        if model.unresolved.isEmpty then "none" else model.unresolved.map(keyText).mkString(", ")
      )
    )
    val reached = model.aggregates.map { a =>
      val members = a.memberCount.fold("")(n =>
        s"; members ${a.members.map(keyText).mkString(", ")}" +
          (if n > a.members.size then ", …" else "")
      )
      InspectorRow(
        s"$label: Aggregates the selection reaches",
        targetText(a.id),
        s"${coverageText(a.coverage)}$members"
      )
    }
    val saved = model.named.map((name, observations, targets) =>
      InspectorRow(
        s"$label: Saved selections",
        name.value,
        s"$observations observations, $targets targets"
      )
    )
    summary ++ reached ++ saved
