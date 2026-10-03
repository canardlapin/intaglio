package intaglio.interaction

import intaglio.*

/** A plan a snapshot was taken against: its id and the plan and data revisions it showed. */
final case class SnapshotPlan(id: String, planRevision: String, dataRevision: String)

/** A visual target by its stable address within a plan revision. */
final case class TargetAddress(plan: String, planRevision: String, scope: String, ordinal: Int)

/** A selection as transport data: observation key tokens and target addresses. */
final case class SnapshotSelection(entities: Vector[KeyToken], targets: Vector[TargetAddress])

/** A panel's recorded data window. */
final case class SnapshotViewport(
    panel: String,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double
)

/** The durable part of interaction state, as untrusted transport data: the selection, saved
  * selections, panel viewports and selection mode, with the plans, revisions and key codecs they
  * mean something under. Hover, focus, gestures, pending requests and callbacks are never saved.
  * [[InteractionSnapshot.resolve]] checks it against a domain before anything is restored.
  */
final case class InteractionSnapshot(
    schema: Int,
    plans: Vector[SnapshotPlan],
    mode: SelectionMode,
    selection: SnapshotSelection,
    named: Vector[(String, SnapshotSelection)],
    viewports: Vector[SnapshotViewport]
):
  def toJson: String = SnapshotJson.write(this)

/** Why a snapshot cannot be restored here. */
enum SnapshotError extends IntaglioError:
  case UnsupportedSchema(found: Int, supported: Int)
  case Malformed(reason: String)
  case UnknownPlan(plan: String)
  case StalePlanRevision(plan: String, saved: String, current: String)
  case StaleDataRevision(plan: String, saved: String, current: String)
  case KeySpaceMismatch(namespace: String)
  case CodecMismatch(namespace: String, saved: String, current: String)
  case UnknownEntity(payload: String)
  case UnknownTarget(address: TargetAddress)
  case Invalid(reason: String)

  def message: String = this match
    case UnsupportedSchema(found, supported) =>
      s"Snapshot schema $found is not supported (this version reads $supported)"
    case Malformed(reason) => s"The snapshot is not well formed: $reason"
    case UnknownPlan(plan) => s"The snapshot names plan '$plan', which is not shown here"
    case StalePlanRevision(plan, saved, current) =>
      s"Plan '$plan' was saved at plan revision $saved; it is now $current"
    case StaleDataRevision(plan, saved, current) =>
      s"Plan '$plan' was saved over data revision $saved; it now shows $current"
    case KeySpaceMismatch(namespace)              => s"No key space '$namespace' is used here"
    case CodecMismatch(namespace, saved, current) =>
      s"Keys of '$namespace' were saved with codec $saved; this plot reads $current"
    case UnknownEntity(payload) => s"Observation '$payload' is not in the data shown"
    case UnknownTarget(address) =>
      s"Target ${address.scope}#${address.ordinal} of plan '${address.plan}' is not shown here"
    case Invalid(reason) => s"The snapshot cannot be applied: $reason"

/** A snapshot checked against one domain: typed keys and targets, ready to restore. */
final class RestoredSnapshot[A] private[interaction] (
    val domainRevision: PlanRevision,
    val mode: SelectionMode,
    val selection: Selection[A],
    val named: Map[SelectionName, Selection[A]],
    val viewports: Map[SemanticId, PanelViewport]
)

object InteractionSnapshot:
  /** The schema this version writes and reads. */
  val Schema: Int = 1

  /** The durable state of `state`, as transport data. */
  def capture[A](state: InteractionState[A]): InteractionSnapshot =
    def address(id: VisualTargetId) =
      TargetAddress(id.plan.value, id.revision.value, id.scope.value, id.ordinal)
    def portable(selection: Selection[A]) = SnapshotSelection(
      selection.entities.toVector.map(_.token).sortBy(t => (t.namespace, t.payload)),
      selection.targets.toVector.map(address).sortBy(a => (a.plan, a.scope, a.ordinal))
    )
    InteractionSnapshot(
      Schema,
      state.domain.plans.map(p =>
        SnapshotPlan(p.id.value, p.revision.value, p.sourceRevision.value)
      ),
      state.selectionMode,
      portable(state.selection),
      state.named.toVector.map((n, s) => n.value -> portable(s)).sortBy(_._1),
      state.viewports.toVector
        .map((panel, v) => SnapshotViewport(panel.value, v.xMin, v.xMax, v.yMin, v.yMax))
        .sortBy(_.panel)
    )

  def fromJson(text: String): Either[SnapshotError, InteractionSnapshot] = SnapshotJson.read(text)

  /** Check `snapshot` against `domain`: the schema, every plan's plan and data revision, every
    * key's key space and codec, and every key and target's presence. The result restores nothing
    * until it is dispatched as [[InteractionAction.RestoreSnapshot]].
    */
  def resolve[A](
      snapshot: InteractionSnapshot,
      domain: InteractionDomain[A]
  ): Either[SnapshotError, RestoredSnapshot[A]] =
    def all[T, R](values: Vector[T])(f: T => Either[SnapshotError, R]) =
      values.foldLeft[Either[SnapshotError, Vector[R]]](Right(Vector.empty)) { (acc, v) =>
        acc.flatMap(done => f(v).map(done :+ _))
      }
    def plan(saved: SnapshotPlan) =
      domain.plans.find(_.id.value == saved.id) match
        case None => Left(SnapshotError.UnknownPlan(saved.id))
        case Some(current) if current.revision.value != saved.planRevision =>
          Left(
            SnapshotError.StalePlanRevision(saved.id, saved.planRevision, current.revision.value)
          )
        case Some(current) if current.sourceRevision.value != saved.dataRevision =>
          Left(
            SnapshotError.StaleDataRevision(
              saved.id,
              saved.dataRevision,
              current.sourceRevision.value
            )
          )
        case Some(_) => Right(())
    def entity(token: KeyToken): Either[SnapshotError, EntityKey[A]] =
      val candidates = domain.keySpaces.filter(_.namespace.value == token.namespace)
      if candidates.isEmpty then Left(SnapshotError.KeySpaceMismatch(token.namespace))
      else
        candidates.iterator.map(_.readEntity(token)).collectFirst { case Right(key) => key } match
          case Some(key) if domain.entities.contains(key) => Right(key)
          case Some(_) => Left(SnapshotError.UnknownEntity(token.payload))
          case None    =>
            val space = candidates.head
            val current = s"${space.codec.name.value}/${space.codec.version}"
            val saved = s"${token.codec}/${token.version}"
            if saved != current then
              Left(SnapshotError.CodecMismatch(token.namespace, saved, current))
            else
              Left(SnapshotError.Malformed(s"key '${token.payload}' is not canonical for $current"))
    def target(address: TargetAddress) =
      domain
        .resolveTarget(address.plan, address.planRevision, address.scope, address.ordinal)
        .toRight(SnapshotError.UnknownTarget(address))
    def selection(saved: SnapshotSelection) =
      for
        keys <- all(saved.entities)(entity)
        ids <- all(saved.targets)(target)
      yield Selection(keys.toSet, ids.toSet)
    for
      _ <- Either.cond(
        snapshot.schema == Schema,
        (),
        SnapshotError.UnsupportedSchema(snapshot.schema, Schema)
      )
      _ <- all(snapshot.plans)(plan)
      current <- selection(snapshot.selection)
      named <- all(snapshot.named) { (n, s) =>
        for
          name <- SelectionName(n).left.map(e => SnapshotError.Malformed(e.message))
          value <- selection(s)
        yield name -> value
      }
      viewports <- all(snapshot.viewports) { v =>
        for
          panel <- SemanticId(v.panel).left.map(e => SnapshotError.Malformed(e.message))
          window <- PanelViewport(v.xMin, v.xMax, v.yMin, v.yMax).left.map(e =>
            SnapshotError.Malformed(e.message)
          )
        yield panel -> window
      }
    yield RestoredSnapshot(domain.revision, snapshot.mode, current, named.toMap, viewports.toMap)

/** A strict JSON writer and reader for snapshots, identical on the JVM and Scala.js: numbers are
  * written as exact decimal expansions (not platform `Double.toString`), keys in a fixed order.
  */
private[interaction] object SnapshotJson:
  // ---- writing ----
  private def str(s: String): String =
    val out = new StringBuilder("\"")
    s.foreach {
      case '"'          => out ++= "\\\""
      case '\\'         => out ++= "\\\\"
      case '\n'         => out ++= "\\n"
      case '\r'         => out ++= "\\r"
      case '\t'         => out ++= "\\t"
      case c if c < ' ' => out ++= f"\\u${c.toInt}%04x"
      case c            => out += c
    }
    out += '"'
    out.result()
  private def num(d: Double): String = new java.math.BigDecimal(d).toPlainString
  private def obj(fields: (String, String)*): String =
    fields.map((k, v) => s"${str(k)}:$v").mkString("{", ",", "}")
  private def arr(values: Iterable[String]): String = values.mkString("[", ",", "]")

  private def selection(s: SnapshotSelection): String = obj(
    "entities" -> arr(
      s.entities.map(t =>
        obj(
          "namespace" -> str(t.namespace),
          "codec" -> str(t.codec),
          "version" -> t.version.toString,
          "payload" -> str(t.payload)
        )
      )
    ),
    "targets" -> arr(
      s.targets.map(a =>
        obj(
          "plan" -> str(a.plan),
          "planRevision" -> str(a.planRevision),
          "scope" -> str(a.scope),
          "ordinal" -> a.ordinal.toString
        )
      )
    )
  )

  def write(s: InteractionSnapshot): String = obj(
    "schema" -> s.schema.toString,
    "plans" -> arr(
      s.plans.map(p =>
        obj(
          "id" -> str(p.id),
          "planRevision" -> str(p.planRevision),
          "dataRevision" -> str(p.dataRevision)
        )
      )
    ),
    "mode" -> str(s.mode.toString),
    "selection" -> selection(s.selection),
    "named" -> arr(s.named.map((n, sel) => obj("name" -> str(n), "selection" -> selection(sel)))),
    "viewports" -> arr(
      s.viewports.map(v =>
        obj(
          "panel" -> str(v.panel),
          "xMin" -> num(v.xMin),
          "xMax" -> num(v.xMax),
          "yMin" -> num(v.yMin),
          "yMax" -> num(v.yMax)
        )
      )
    )
  )

  // ---- reading ----
  private enum J:
    case Obj(fields: Vector[(String, J)])
    case Arr(values: Vector[J])
    case Str(value: String)
    case Num(text: String)
    case Bool(value: Boolean)
    case Null

  private final class Parser(text: String):
    private var at = 0
    private def fail(reason: String) = throw SnapshotJson.Bad(s"$reason at offset $at")
    private def ws(): Unit = while at < text.length && " \t\r\n".contains(text(at)) do at += 1
    private def expect(c: Char): Unit =
      ws()
      if at >= text.length || text(at) != c then fail(s"expected '$c'")
      at += 1
    def value(depth: Int = 0): J =
      if depth > 32 then fail("nesting too deep")
      ws()
      if at >= text.length then fail("unexpected end")
      text(at) match
        case '{' =>
          at += 1
          ws()
          if at < text.length && text(at) == '}' then { at += 1; J.Obj(Vector.empty) }
          else
            val fields = Vector.newBuilder[(String, J)]
            var more = true
            while more do
              ws()
              val key = string()
              expect(':')
              fields += key -> value(depth + 1)
              ws()
              if at < text.length && text(at) == ',' then at += 1
              else { expect('}'); more = false }
            val built = fields.result()
            if built.map(_._1).distinct.size != built.size then fail("duplicate field")
            J.Obj(built)
        case '[' =>
          at += 1
          ws()
          if at < text.length && text(at) == ']' then { at += 1; J.Arr(Vector.empty) }
          else
            val values = Vector.newBuilder[J]
            var more = true
            while more do
              values += value(depth + 1)
              ws()
              if at < text.length && text(at) == ',' then at += 1
              else { expect(']'); more = false }
            J.Arr(values.result())
        case '"'                                 => J.Str(string())
        case 't' if text.startsWith("true", at)  => at += 4; J.Bool(true)
        case 'f' if text.startsWith("false", at) => at += 5; J.Bool(false)
        case 'n' if text.startsWith("null", at)  => at += 4; J.Null
        case c if c == '-' || c.isDigit          =>
          val start = at
          at += 1
          while at < text.length && "0123456789.eE+-".contains(text(at)) do at += 1
          J.Num(text.substring(start, at))
        case c => fail(s"unexpected '$c'")
    private def string(): String =
      ws()
      if at >= text.length || text(at) != '"' then fail("expected a string")
      at += 1
      val out = new StringBuilder
      var done = false
      while !done do
        if at >= text.length then fail("unterminated string")
        text(at) match
          case '"'  => at += 1; done = true
          case '\\' =>
            if at + 1 >= text.length then fail("unterminated escape")
            text(at + 1) match
              case '"'                          => out += '"'; at += 2
              case '\\'                         => out += '\\'; at += 2
              case '/'                          => out += '/'; at += 2
              case 'n'                          => out += '\n'; at += 2
              case 'r'                          => out += '\r'; at += 2
              case 't'                          => out += '\t'; at += 2
              case 'b'                          => out += '\b'; at += 2
              case 'f'                          => out += '\f'; at += 2
              case 'u' if at + 6 <= text.length =>
                val hex = text.substring(at + 2, at + 6)
                if !hex.forall(c => "0123456789abcdefABCDEF".contains(c)) then
                  fail("bad \\u escape")
                out += Integer.parseInt(hex, 16).toChar
                at += 6
              case _ => fail("bad escape")
          case c if c < ' ' => fail("control character in string")
          case c            => out += c; at += 1
      out.result()
    def document(): J =
      val v = value()
      ws()
      if at != text.length then fail("trailing characters")
      v

  private final case class Bad(reason: String) extends RuntimeException(reason)

  private def field(o: J, name: String): J = o match
    case J.Obj(fields) =>
      fields.collectFirst { case (`name`, v) => v }.getOrElse(throw Bad(s"missing field '$name'"))
    case _ => throw Bad(s"expected an object holding '$name'")
  private def text(j: J, what: String): String = j match
    case J.Str(v) => v
    case _        => throw Bad(s"$what must be a string")
  private def int(j: J, what: String): Int = j match
    case J.Num(t) if t.matches("-?(0|[1-9][0-9]{0,9})") && t.toLong.isValidInt => t.toInt
    case _ => throw Bad(s"$what must be an integer")
  private def double(j: J, what: String): Double = j match
    case J.Num(t) =>
      try
        val d = BigDecimal(t).toDouble
        if d.isFinite then d else throw Bad(s"$what must be finite")
      catch case _: NumberFormatException => throw Bad(s"$what must be a number")
    case _ => throw Bad(s"$what must be a number")
  private def array(j: J, what: String): Vector[J] = j match
    case J.Arr(v) => v
    case _        => throw Bad(s"$what must be an array")

  private def readSelection(j: J): SnapshotSelection =
    SnapshotSelection(
      array(field(j, "entities"), "entities").map(e =>
        KeyToken(
          text(field(e, "namespace"), "namespace"),
          text(field(e, "codec"), "codec"),
          int(field(e, "version"), "version"),
          text(field(e, "payload"), "payload")
        )
      ),
      array(field(j, "targets"), "targets").map(t =>
        TargetAddress(
          text(field(t, "plan"), "plan"),
          text(field(t, "planRevision"), "planRevision"),
          text(field(t, "scope"), "scope"),
          int(field(t, "ordinal"), "ordinal")
        )
      )
    )

  def read(input: String): Either[SnapshotError, InteractionSnapshot] =
    if input == null then Left(SnapshotError.Malformed("no text"))
    else if input.length > 16 * 1024 * 1024 then Left(SnapshotError.Malformed("larger than 16 MiB"))
    else
      try
        val root = Parser(input).document()
        // The schema is read first, so a future schema is reported as such, not as malformed.
        val schema = int(field(root, "schema"), "schema")
        if schema != InteractionSnapshot.Schema then
          Left(SnapshotError.UnsupportedSchema(schema, InteractionSnapshot.Schema))
        else
          val modeText = text(field(root, "mode"), "mode")
          val mode = SelectionMode.values
            .find(_.toString == modeText)
            .getOrElse(throw Bad(s"unknown selection mode '$modeText'"))
          Right(
            InteractionSnapshot(
              schema,
              array(field(root, "plans"), "plans").map(p =>
                SnapshotPlan(
                  text(field(p, "id"), "id"),
                  text(field(p, "planRevision"), "planRevision"),
                  text(field(p, "dataRevision"), "dataRevision")
                )
              ),
              mode,
              readSelection(field(root, "selection")),
              array(field(root, "named"), "named").map(n =>
                text(field(n, "name"), "name") -> readSelection(field(n, "selection"))
              ),
              array(field(root, "viewports"), "viewports").map(v =>
                SnapshotViewport(
                  text(field(v, "panel"), "panel"),
                  double(field(v, "xMin"), "xMin"),
                  double(field(v, "xMax"), "xMax"),
                  double(field(v, "yMin"), "yMin"),
                  double(field(v, "yMax"), "yMax")
                )
              )
            )
          )
      catch case Bad(reason) => Left(SnapshotError.Malformed(reason))
