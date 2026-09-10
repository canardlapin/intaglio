package intaglio.laws

import scala.util.control.NonFatal

/** One framework-neutral executable law. Returning an empty vector means the law passed; every
  * string is a distinct diagnostic when it failed.
  */
final case class Law(name: String, evaluate: () => Vector[String])

/** A structured law failure suitable for MUnit, ScalaTest, Weaver, or a custom test runner. */
final case class LawFailure(suite: String, law: String, detail: String):
  override def toString: String =
    s"$suite / $law: $detail"

/** A reusable collection of executable laws with no dependency on a testing framework. */
final case class LawSuite(name: String, laws: Vector[Law]):
  def failures: Vector[LawFailure] =
    laws.flatMap { law =>
      val problems =
        try law.evaluate()
        catch
          case NonFatal(error) =>
            val detail = Option(error.getMessage).filter(_.nonEmpty).getOrElse("no message")
            Vector(s"threw ${error.getClass.getName}: $detail")
      problems.map(LawFailure(name, law.name, _))
    }

  def isValid: Boolean =
    failures.isEmpty

private[laws] object LawDiagnostics:
  def problemWhen(condition: Boolean, detail: => String): Vector[String] =
    if condition then Vector(detail) else Vector.empty

  def show(value: Any): String =
    String.valueOf(value)
