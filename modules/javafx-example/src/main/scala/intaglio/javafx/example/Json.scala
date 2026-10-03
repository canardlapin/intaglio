package intaglio.javafx.example

/** A small JSON reader and writer for the trace files the browser runner records; test-only, so the
  * module takes no JSON dependency.
  */
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: Double)
  case Str(value: String)
  case Arr(values: Vector[Json])
  case Obj(fields: Vector[(String, Json)])

  def apply(key: String): Json = this match
    case Obj(fields) => fields.find(_._1 == key).map(_._2).getOrElse(Null)
    case _           => Null

  def get(key: String): Option[Json] = this match
    case Obj(fields) => fields.find(_._1 == key).map(_._2)
    case _           => None

  def str: String = this match
    case Str(value) => value
    case other      => throw new IllegalArgumentException(s"not a string: $other")

  def strOpt: Option[String] = this match
    case Str(value) => Some(value)
    case _          => None

  def int: Int = this match
    case Num(value) => value.toInt
    case other      => throw new IllegalArgumentException(s"not a number: $other")

  def bool: Boolean = this match
    case Bool(value) => value
    case _           => false

  def items: Vector[Json] = this match
    case Arr(values) => values
    case _           => Vector.empty

  def strings: Vector[String] = items.map(_.str)

  def render: String = this match
    case Null        => "null"
    case Bool(value) => value.toString
    case Num(value)  => if value == math.rint(value) then value.toLong.toString else value.toString
    case Str(value)  => Json.quote(value)
    case Arr(values) => values.map(_.render).mkString("[", ",", "]")
    case Obj(fields) =>
      fields.map((k, v) => s"${Json.quote(k)}:${v.render}").mkString("{", ",", "}")

object Json:
  def strs(values: Iterable[String]): Json = Arr(values.toVector.map(Str(_)))
  def obj(fields: (String, Json)*): Json = Obj(fields.toVector)

  def quote(value: String): String =
    val out = new StringBuilder("\"")
    value.foreach {
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

  def parse(text: String): Json =
    var i = 0
    def ws(): Unit = while i < text.length && text.charAt(i).isWhitespace do i += 1
    def expect(c: Char): Unit =
      ws()
      if text.charAt(i) != c then throw new IllegalArgumentException(s"expected $c at $i")
      i += 1
    def value(): Json =
      ws()
      text.charAt(i) match
        case '{' =>
          i += 1
          ws()
          val fields = Vector.newBuilder[(String, Json)]
          if text.charAt(i) == '}' then i += 1
          else
            var more = true
            while more do
              ws()
              val key = string()
              expect(':')
              fields += key -> value()
              ws()
              if text.charAt(i) == ',' then i += 1 else { expect('}'); more = false }
          Obj(fields.result())
        case '[' =>
          i += 1
          ws()
          val items = Vector.newBuilder[Json]
          if text.charAt(i) == ']' then i += 1
          else
            var more = true
            while more do
              items += value()
              ws()
              if text.charAt(i) == ',' then i += 1 else { expect(']'); more = false }
          Arr(items.result())
        case '"' => Str(string())
        case 't' => i += 4; Bool(true)
        case 'f' => i += 5; Bool(false)
        case 'n' => i += 4; Null
        case _   =>
          val start = i
          while i < text.length && "+-0123456789.eE".indexOf(text.charAt(i)) >= 0 do i += 1
          Num(text.substring(start, i).toDouble)
    def string(): String =
      ws()
      if text.charAt(i) != '"' then throw new IllegalArgumentException(s"expected string at $i")
      i += 1
      val out = new StringBuilder
      while text.charAt(i) != '"' do
        text.charAt(i) match
          case '\\' =>
            i += 1
            text.charAt(i) match
              case 'n' => out += '\n'
              case 'r' => out += '\r'
              case 't' => out += '\t'
              case 'b' => out += '\b'
              case 'f' => out += '\f'
              case 'u' =>
                out += Integer.parseInt(text.substring(i + 1, i + 5), 16).toChar
                i += 4
              case c => out += c
            i += 1
          case c =>
            out += c
            i += 1
      i += 1
      out.result()
    val result = value()
    ws()
    if i != text.length then throw new IllegalArgumentException(s"trailing input at $i")
    result
