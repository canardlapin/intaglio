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
    /** Selected observations the data did not have when saved; they are restored as such. */
    unresolved: Vector[KeyToken],
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

  /** Several key spaces here share the namespace, so a saved key's meaning is ambiguous. */
  case AmbiguousKeySpace(namespace: String)

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
    case Invalid(reason)              => s"The snapshot cannot be applied: $reason"
    case AmbiguousKeySpace(namespace) =>
      s"Several key spaces here are named '$namespace'; a saved key cannot say which it means"

/** A snapshot checked against one domain: typed keys and targets, ready to restore. */
final class RestoredSnapshot[A] private[interaction] (
    val domainRevision: PlanRevision,
    val mode: SelectionMode,
    val selection: Selection[A],
    val named: Map[SelectionName, Selection[A]],
    val viewports: Map[SemanticId, PanelViewport],
    /** Selected observations the data does not have (kept by `MissingEntityPolicy.Preserve`). */
    val unresolved: Set[EntityKey[A]]
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
      state.unresolved.toVector.map(_.token).sortBy(t => (t.namespace, t.payload)),
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
    def entity(token: KeyToken, allowMissing: Boolean): Either[SnapshotError, EntityKey[A]] =
      val candidates = domain.keySpaces.filter(_.namespace.value == token.namespace)
      if candidates.isEmpty then Left(SnapshotError.KeySpaceMismatch(token.namespace))
      else if candidates.size > 1 then Left(SnapshotError.AmbiguousKeySpace(token.namespace))
      else
        val space = candidates.head
        space.readEntity(token) match
          case Right(key) if domain.entities.contains(key) || allowMissing => Right(key)
          case Right(_) => Left(SnapshotError.UnknownEntity(token.payload))
          case Left(_)  =>
            val current = s"${space.codec.name.value}/${space.codec.version}"
            val saved = s"${token.codec}/${token.version}"
            if saved != current then
              Left(SnapshotError.CodecMismatch(token.namespace, saved, current))
            else Left(SnapshotError.Malformed(s"'${token.payload}' is not a valid $current key"))
    def target(address: TargetAddress) =
      domain
        .resolveTarget(address.plan, address.planRevision, address.scope, address.ordinal)
        .toRight(SnapshotError.UnknownTarget(address))
    val missing = snapshot.unresolved.toSet
    def selection(saved: SnapshotSelection, allowUnresolved: Boolean) =
      for
        keys <- all(saved.entities)(t => entity(t, allowUnresolved && missing.contains(t)))
        ids <- all(saved.targets)(target)
      yield Selection(keys.toSet, ids.toSet)
    // Every target's plan is listed, so its data revision is always checked.
    val listed = snapshot.plans.map(_.id).toSet
    val unlisted = (snapshot.selection.targets ++ snapshot.named.flatMap(_._2.targets))
      .map(_.plan)
      .find(plan => !listed.contains(plan))
    for
      _ <- Either.cond(
        snapshot.schema == Schema,
        (),
        SnapshotError.UnsupportedSchema(snapshot.schema, Schema)
      )
      _ <- unlisted.fold(Right(()))(plan =>
        Left(SnapshotError.Malformed(s"plan '$plan' is not listed"))
      )
      _ <- Either.cond(
        snapshot.plans.nonEmpty || domain.plans.isEmpty,
        (),
        SnapshotError.Malformed("no plans are listed")
      )
      _ <- all(snapshot.plans)(plan)
      _ <- Either.cond(
        snapshot.unresolved.forall(snapshot.selection.entities.contains),
        (),
        SnapshotError.Malformed("an unresolved key is not selected")
      )
      current <- selection(snapshot.selection, allowUnresolved = true)
      unresolved <- all(snapshot.unresolved)(t => entity(t, allowMissing = true))
      named <- all(snapshot.named) { (n, s) =>
        for
          name <- SelectionName(n).left.map(e => SnapshotError.Malformed(e.message))
          value <- selection(s, allowUnresolved = false)
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
      _ <- Either.cond(
        named.map(_._1).distinct.size == named.size,
        (),
        SnapshotError.Malformed("a saved selection name repeats")
      )
      _ <- Either.cond(
        viewports.map(_._1).distinct.size == viewports.size,
        (),
        SnapshotError.Malformed("a viewport panel repeats")
      )
    yield RestoredSnapshot(
      domain.revision,
      snapshot.mode,
      current,
      named.toMap,
      viewports.toMap,
      unresolved.toSet.diff(domain.entities)
    )

/** A strict JSON writer and reader for snapshots, identical on the JVM and Scala.js: numbers are
  * written as exact decimal expansions (not platform `Double.toString`), fields in a fixed order.
  * Reading accepts exactly the fields of the schema (an unknown field means another schema), JSON's
  * number grammar in ASCII, no duplicate fields and no lone surrogates.
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

  private def token(t: KeyToken): String = obj(
    "namespace" -> str(t.namespace),
    "codec" -> str(t.codec),
    "version" -> t.version.toString,
    "payload" -> str(t.payload)
  )

  private def selection(s: SnapshotSelection): String = obj(
    "entities" -> arr(s.entities.map(token)),
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
    "unresolved" -> arr(s.unresolved.map(token)),
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

  private final case class Bad(reason: String) extends RuntimeException(reason)

  /** JSON's number grammar, ASCII digits only. */
  private val JsonNumber = "-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?".r

  private final class Parser(text: String):
    private var at = 0
    private def fail(reason: String) = throw Bad(s"$reason at offset $at")
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
          if at < text.length && text(at) == '}' then
            at += 1
            J.Obj(Vector.empty)
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
              else
                expect('}')
                more = false
            val built = fields.result()
            if built.map(_._1).distinct.size != built.size then fail("duplicate field")
            J.Obj(built)
        case '[' =>
          at += 1
          ws()
          if at < text.length && text(at) == ']' then
            at += 1
            J.Arr(Vector.empty)
          else
            val values = Vector.newBuilder[J]
            var more = true
            while more do
              values += value(depth + 1)
              ws()
              if at < text.length && text(at) == ',' then at += 1
              else
                expect(']')
                more = false
            J.Arr(values.result())
        case '"'                                => J.Str(string())
        case 't' if text.startsWith("true", at) =>
          at += 4
          J.Bool(true)
        case 'f' if text.startsWith("false", at) =>
          at += 5
          J.Bool(false)
        case 'n' if text.startsWith("null", at) =>
          at += 4
          J.Null
        case c if c == '-' || (c >= '0' && c <= '9') =>
          val start = at
          at += 1
          while at < text.length && "0123456789.eE+-".contains(text(at)) do at += 1
          val number = text.substring(start, at)
          if number.length > 1100 || !JsonNumber.matches(number) then fail(s"bad number '$number'")
          J.Num(number)
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
          case '"' =>
            at += 1
            done = true
          case '\\' =>
            if at + 1 >= text.length then fail("unterminated escape")
            val escaped = text(at + 1) match
              case '"'                          => '"'
              case '\\'                         => '\\'
              case '/'                          => '/'
              case 'n'                          => '\n'
              case 'r'                          => '\r'
              case 't'                          => '\t'
              case 'b'                          => '\b'
              case 'f'                          => '\f'
              case 'u' if at + 6 <= text.length =>
                val hex = text.substring(at + 2, at + 6)
                if !hex.forall(c => "0123456789abcdefABCDEF".contains(c)) then
                  fail("bad \\u escape")
                at += 4
                Integer.parseInt(hex, 16).toChar
              case _ => fail("bad escape")
            out += escaped
            at += 2
          case c if c < ' ' => fail("control character in string")
          case c            =>
            out += c
            at += 1
      val result = out.result()
      // Strings may not hold a lone surrogate, from an escape or raw.
      var i = 0
      while i < result.length do
        val c = result(i)
        if Character.isHighSurrogate(c) then
          if i + 1 >= result.length || !Character.isLowSurrogate(result(i + 1)) then
            fail("lone surrogate")
          i += 2
        else if Character.isLowSurrogate(c) then fail("lone surrogate")
        else i += 1
      result
    def document(): J =
      val v = value()
      ws()
      if at != text.length then fail("trailing characters")
      v

  /** An object with exactly these fields: an unknown field means another schema. */
  private def exactly(j: J, names: String*): Unit = j match
    case J.Obj(fields) =>
      fields.map(_._1).find(n => !names.contains(n)).foreach(n => throw Bad(s"unknown field '$n'"))
    case _ => throw Bad("expected an object")
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
      val d =
        try new java.math.BigDecimal(t).doubleValue
        catch case _: NumberFormatException => throw Bad(s"$what must be a number")
      if d.isFinite then d else throw Bad(s"$what must be finite")
    case _ => throw Bad(s"$what must be a number")
  private def array(j: J, what: String): Vector[J] = j match
    case J.Arr(v) => v
    case _        => throw Bad(s"$what must be an array")

  private def readToken(e: J): KeyToken =
    exactly(e, "namespace", "codec", "version", "payload")
    KeyToken(
      text(field(e, "namespace"), "namespace"),
      text(field(e, "codec"), "codec"),
      int(field(e, "version"), "version"),
      text(field(e, "payload"), "payload")
    )

  private def readSelection(j: J): SnapshotSelection =
    exactly(j, "entities", "targets")
    SnapshotSelection(
      array(field(j, "entities"), "entities").map(readToken),
      array(field(j, "targets"), "targets").map { t =>
        exactly(t, "plan", "planRevision", "scope", "ordinal")
        TargetAddress(
          text(field(t, "plan"), "plan"),
          text(field(t, "planRevision"), "planRevision"),
          text(field(t, "scope"), "scope"),
          int(field(t, "ordinal"), "ordinal")
        )
      }
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
          exactly(root, "schema", "plans", "mode", "selection", "unresolved", "named", "viewports")
          val modeText = text(field(root, "mode"), "mode")
          val mode = SelectionMode.values
            .find(_.toString == modeText)
            .getOrElse(throw Bad(s"unknown selection mode '$modeText'"))
          Right(
            InteractionSnapshot(
              schema,
              array(field(root, "plans"), "plans").map { p =>
                exactly(p, "id", "planRevision", "dataRevision")
                SnapshotPlan(
                  text(field(p, "id"), "id"),
                  text(field(p, "planRevision"), "planRevision"),
                  text(field(p, "dataRevision"), "dataRevision")
                )
              },
              mode,
              readSelection(field(root, "selection")),
              array(field(root, "unresolved"), "unresolved").map(readToken),
              array(field(root, "named"), "named").map { n =>
                exactly(n, "name", "selection")
                text(field(n, "name"), "name") -> readSelection(field(n, "selection"))
              },
              array(field(root, "viewports"), "viewports").map { v =>
                exactly(v, "panel", "xMin", "xMax", "yMin", "yMax")
                SnapshotViewport(
                  text(field(v, "panel"), "panel"),
                  double(field(v, "xMin"), "xMin"),
                  double(field(v, "xMax"), "xMax"),
                  double(field(v, "yMin"), "yMin"),
                  double(field(v, "yMax"), "yMax")
                )
              }
            )
          )
      catch case Bad(reason) => Left(SnapshotError.Malformed(reason))
