package intaglio.browser

import intaglio.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}

/** A text inspector for one widget or a linked group: for each plot, what its selection means
  * (`InspectorModel`): observations, selected marks and aggregates, how far the selection reaches
  * into each aggregate, exact members, uncountable aggregates, unresolved keys and saved
  * selections. A filter's report (input and statistical changes) is shown in its own section, apart
  * from selection and emphasis. Plain semantic tables, text only, live as the widgets change
  * (including changes projected in by a link).
  */
final class InspectorPanel[A] private (
    container: js.Dynamic,
    widgets: Vector[(String, SvgWidget[A])],
    sample: Int
):
  private val document = g.document
  private val root = document.createElement("section")
  root.setAttribute("class", "intaglio-inspector")
  root.setAttribute("aria-label", "Selection inspector")
  container.appendChild(root)
  private val plots = widgets.map { (label, _) =>
    val block = document.createElement("div")
    block.setAttribute("class", "intaglio-inspector-plot")
    block.setAttribute("data-plot", label)
    root.appendChild(block)
    block
  }
  private val filterBlock = document.createElement("div")
  filterBlock.setAttribute("class", "intaglio-inspector-filter")
  filterBlock.hidden = true
  root.appendChild(filterBlock)
  private var unsubscribe = widgets.zipWithIndex.map { case ((label, widget), i) =>
    widget.subscribeState(state => render(i, label, InspectorModel.of(state, sample)))
  }

  private def keyText(key: EntityKey[A]): String = key.token.payload
  private def targetText(id: VisualTargetId): String =
    s"${id.plan.value} ${id.scope.value}#${id.ordinal}"

  private def table(caption: String, rows: Vector[(String, String)]): js.Dynamic =
    val t = document.createElement("table")
    val c = document.createElement("caption")
    c.textContent = caption
    t.appendChild(c)
    rows.foreach { (k, v) =>
      val tr = document.createElement("tr")
      val th = document.createElement("th")
      th.setAttribute("scope", "row")
      th.textContent = k
      val td = document.createElement("td")
      td.textContent = v
      tr.appendChild(th)
      tr.appendChild(td)
      t.appendChild(tr)
    }
    t

  private def coverageText(coverage: MemberCoverage): String = coverage match
    case MemberCoverage.Known(k, n)      => s"$k of $n selected"
    case MemberCoverage.Unknown(ability) =>
      s"not countable (${ability.toString.toLowerCase} membership)"
    case MemberCoverage.Stale => "not countable (stale membership)"

  private def render(i: Int, label: String, model: InspectorModel[A]): Unit =
    val block = plots(i)
    block.textContent = ""
    val heading = document.createElement("h3")
    heading.textContent = label
    block.appendChild(heading)
    val sampleText =
      if model.observationSample.isEmpty then ""
      else
        s": ${model.observationSample.map(keyText).mkString(", ")}" +
          (if model.observations > model.observationSample.size then ", …" else "")
    val marks = model.targets.count(_.entity.nonEmpty)
    val aggregates = model.targets.count(_.entity.isEmpty)
    val summary = Vector(
      "Observations selected" -> s"${model.observations}$sampleText",
      "Targets selected" -> s"$marks marks, $aggregates aggregates",
      "Aggregates not countable" -> model.uncountedAggregates.toString,
      "Unresolved observations" ->
        (if model.unresolved.isEmpty then "none" else model.unresolved.map(keyText).mkString(", "))
    )
    block.appendChild(table("Selection", summary))
    if model.aggregates.nonEmpty then
      block.appendChild(
        table(
          "Aggregates the selection reaches",
          model.aggregates.map { a =>
            val members = a.memberCount.fold("")(n =>
              s"; members ${a.members.map(keyText).mkString(", ")}" + (if n > a.members.size then
                                                                         ", …"
                                                                       else "")
            )
            targetText(a.id) -> s"${coverageText(a.coverage)}$members"
          }
        )
      )
    if model.named.nonEmpty then
      block.appendChild(
        table(
          "Saved selections",
          model.named.map((name, observations, targets) =>
            name.value -> s"$observations observations, $targets targets"
          )
        )
      )

  /** Show a filter's report: rows before and after, observations removed, and each group's targets
    * and member totals before and after. Selection and emphasis are not part of it.
    */
  def showFilter(label: String, result: FilterResult[A]): Unit =
    filterBlock.textContent = ""
    filterBlock.hidden = false
    val heading = document.createElement("h3")
    heading.textContent = s"Filter applied to $label"
    filterBlock.appendChild(heading)
    filterBlock.appendChild(
      table(
        "Input",
        Vector(
          "Rows" -> s"${result.input.rowsBefore} → ${result.input.rowsAfter}",
          "Observations removed" -> result.input.removed.size.toString
        )
      )
    )
    filterBlock.appendChild(
      table(
        "Statistical result",
        result.statistics.map { s =>
          def totals(v: Vector[Option[Int]]) = v.map(_.fold("?")(_.toString)).mkString(" ")
          s"group ${s.group + 1}" ->
            s"${s.targetsBefore} → ${s.targetsAfter} targets; totals ${totals(s.totalsBefore)} → ${totals(s.totalsAfter)}"
        }
      )
    )

  /** Hide the filter report, for example once the filtered view is replaced or undone. */
  def clearFilter(): Unit =
    filterBlock.textContent = ""
    filterBlock.hidden = true

  def dispose(): Unit =
    unsubscribe.foreach(_())
    unsubscribe = Vector.empty
    if root.parentNode != null then root.parentNode.removeChild(root)

object InspectorPanel:
  /** Mount an inspector for `widgets` (each with a label) in `container`. */
  def mount[A](
      container: js.Dynamic,
      widgets: Vector[(String, SvgWidget[A])],
      sample: Int = 12
  ): Either[IntaglioError, InspectorPanel[A]] =
    if js.isUndefined(container) || container == null then
      Left(InteractionError.InvalidValue("inspector container", "no DOM element"))
    else if widgets.isEmpty then Left(InteractionError.InvalidValue("inspector", "no widgets"))
    else Right(new InspectorPanel(container, widgets, sample))
